package com.tcl.csot.zhiye.service.chat;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 零外部依赖的 Word（.docx）结构化提取器：
 * - 完整提取标题层级、正文段落、表格（Markdown 表格）、文本框/内容控件文字与内嵌图片（word/media/*）
 * - 自动按 word/_rels/document.xml.rels 在正文中精确还原每张图片的出现位置
 * - 规避 OOXML 的 mc:Fallback 重复节点、Windows 反斜杠路径、超大媒体条目截断与 XXE 漏洞
 * - 自动识别伪装后缀的旧版二进制 .doc 与 HTML/MHTML 另存文档并给出清晰提示
 */
public final class DocxExtractor {
    private static final int MAX_ZIP_ENTRIES = 2000;
    private static final long MAX_TOTAL_UNZIP_BYTES = 28L * 1024 * 1024;
    private static final int MAX_IMAGES = 30;
    private static final long MAX_SINGLE_IMAGE_BYTES = 6L * 1024 * 1024;

    private static final Map<String, String> IMAGE_MIMES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("svg", "image/svg+xml")
    );

    private static final Pattern HEADING_NUM = Pattern.compile("^(?:heading|标题)\\s*([1-6])$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PURE_DIGIT_HEADING = Pattern.compile("^([1-6])$");

    private DocxExtractor() {}

    public record ExtractedImage(String filename, String mime, byte[] bytes) {
        public boolean isRasterForVision() {
            return mime != null && mime.startsWith("image/") && !"image/svg+xml".equals(mime) && !"image/bmp".equals(mime);
        }
    }

    public record Result(String markdown, List<ExtractedImage> images, boolean legacyBinaryDoc, String notice) {
        public boolean hasContent() {
            return (markdown != null && !markdown.isBlank()) || !images.isEmpty();
        }
    }

    /**
     * 解析 .docx 字节流。
     *
     * @param data            原始文件字节
     * @param imageLinkPrefix 若非空（例如 "./assets/doc-media/"），正文内图片以 Markdown 图片语法
     *                        `![图片 1](./assets/doc-media/image1.png)` 输出；若为空（问答上下文），输出 `[内嵌图片 1：image1.png]`。
     */
    public static Result extract(byte[] data, String imageLinkPrefix) throws Exception {
        if (data == null || data.length == 0) {
            return new Result("", List.of(), false, null);
        }
        // 1. 识别旧版二进制 OLE2 .doc（即使被手动改成了 .docx 后缀）
        if (isOle2Binary(data)) {
            return new Result(
                    "",
                    List.of(),
                    true,
                    "检测到该文件实际上是旧版二进制 .doc 格式（非标准 .docx 压缩包），无法直接解析。请用 Word 或 WPS 打开后选择「另存为 → Word 文档 (*.docx)」再重新上传。"
            );
        }
        // 2. 识别 HTML / MHTML / WordprocessingML XML 伪装的 Word 文档（很多内网系统导出的是 HTML 改名 .doc/.docx）
        if (!isZipHeader(data)) {
            String raw = new String(data, StandardCharsets.UTF_8);
            String trimmed = raw.stripLeading();
            String lowerHead = trimmed.substring(0, Math.min(trimmed.length(), 400)).toLowerCase(Locale.ROOT);
            if (lowerHead.contains("<html") || lowerHead.contains("<!doctype html") || lowerHead.contains("<body")
                    || lowerHead.contains("<?xml") || lowerHead.startsWith("mime-version:")) {
                String plain = tidy(unescape(raw
                        .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                        .replaceAll("(?i)<(br|/p|/div|/tr|/li|/h[1-6])[^>]*>", "\n")
                        .replaceAll("(?i)<(td|th)[^>]*>", "\t")
                        .replaceAll("<[^>]+>", "")));
                return new Result(plain, List.of(), false, null);
            }
            return new Result("", List.of(), false, "该文件不是有效的 .docx 文档，请在 Word/WPS 中重新另存为标准 .docx 后上传。");
        }

        // 3. 安全解包 ZIP：XML 单独按 UTF-8 读，图片单独按原始字节读（绝不把二进制图片当 UTF-8 解码）
        Map<String, byte[]> xmlFiles = new LinkedHashMap<>();
        Map<String, byte[]> mediaBytes = new LinkedHashMap<>();
        Map<String, String> mediaOriginalName = new LinkedHashMap<>();
        long totalRead = 0;
        int entryCount = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) break;
                if (e.isDirectory()) continue;
                String norm = normalizeZipPath(e.getName());
                String lower = norm.toLowerCase(Locale.ROOT);
                boolean wantXml = lower.equals("word/document.xml")
                        || lower.equals("word/_rels/document.xml.rels")
                        || lower.matches("word/(header|footer)\\d*\\.xml");
                boolean wantMedia = lower.startsWith("word/media/") && mediaBytes.size() < MAX_IMAGES;
                if (!wantXml && !wantMedia) continue;

                long entryLimit = wantMedia ? MAX_SINGLE_IMAGE_BYTES : (4L * 1024 * 1024);
                long remaining = MAX_TOTAL_UNZIP_BYTES - totalRead;
                if (remaining <= 0) continue;
                byte[] bytes = readBounded(in, Math.min(entryLimit, remaining));
                if (bytes == null) continue;
                totalRead += bytes.length;

                if (wantXml) {
                    xmlFiles.put(lower, bytes);
                } else {
                    String baseName = norm.substring(norm.lastIndexOf('/') + 1);
                    String ext = extensionOf(baseName);
                    if (IMAGE_MIMES.containsKey(ext)) {
                        String safeFile = sanitizeMediaName(baseName);
                        mediaBytes.put(lower, bytes);
                        mediaOriginalName.put(lower, safeFile);
                    }
                }
            }
        }

        byte[] docXmlBytes = xmlFiles.get("word/document.xml");
        if (docXmlBytes == null) {
            return new Result("", List.of(), false, "未在 .docx 压缩包中找到正文（word/document.xml），文档可能已损坏或受加密保护。");
        }

        // 4. 解析关系表 word/_rels/document.xml.rels（rId -> word/media/xxx）
        Map<String, String> relToMediaKey = parseRels(xmlFiles.get("word/_rels/document.xml.rels"));

        // 5. DOM 遍历正文（保留标题、段落、表格、列表与精确图片位置）；若遇到极少数非规范 XML，自动退回标签清洗兜底，仍完整保留图片
        WalkContext wctx = new WalkContext(relToMediaKey, mediaBytes, mediaOriginalName, imageLinkPrefix);
        StringBuilder md = new StringBuilder();
        try {
            Document doc = parseXml(docXmlBytes);
            Element body = firstDescendant(doc.getDocumentElement(), "body");
            if (body != null) {
                walkContainer(body, md, wctx);
            } else {
                walkContainer(doc.getDocumentElement(), md, wctx);
            }
        } catch (Exception xmlErr) {
            String rawXml = new String(docXmlBytes, StandardCharsets.UTF_8);
            String fallback = tidy(unescape(rawXml
                    .replaceAll("</w:p>", "\n")
                    .replaceAll("<w:br\\s*/?>", "\n")
                    .replaceAll("<w:cr\\s*/?>", "\n")
                    .replaceAll("<w:tab\\s*/?>", "\t")
                    .replaceAll("<[^>]+>", "")));
            if (!fallback.isEmpty()) md.append(fallback);
        }

        // 收集正文里未直接通过 rId 命中但确实存在于 word/media/ 里的兜底图片（如 SmartArt / 组合图形 / 页眉图）
        List<ExtractedImage> orderedImages = new ArrayList<>(wctx.orderedImages);
        for (Map.Entry<String, byte[]> entry : mediaBytes.entrySet()) {
            if (wctx.seenMediaKeys.contains(entry.getKey())) continue;
            String fname = mediaOriginalName.get(entry.getKey());
            String mime = IMAGE_MIMES.get(extensionOf(fname));
            if (mime != null) {
                orderedImages.add(new ExtractedImage(fname, mime, entry.getValue()));
                int idx = orderedImages.size();
                if (imageLinkPrefix != null && !imageLinkPrefix.isEmpty()) {
                    md.append("\n\n![文档附图 ").append(idx).append("](").append(imageLinkPrefix).append(fname).append(")");
                } else {
                    md.append("\n\n[文档附图 ").append(idx).append("：").append(fname).append("]");
                }
            }
        }

        String text = tidy(md.toString());
        return new Result(text, orderedImages, false, null);
    }

    private static final class WalkContext {
        final Map<String, String> relToMediaKey;
        final Map<String, byte[]> mediaBytes;
        final Map<String, String> mediaNames;
        final String imageLinkPrefix;
        final List<ExtractedImage> orderedImages = new ArrayList<>();
        final Set<String> seenMediaKeys = new LinkedHashSet<>();

        WalkContext(Map<String, String> relToMediaKey, Map<String, byte[]> mediaBytes,
                    Map<String, String> mediaNames, String imageLinkPrefix) {
            this.relToMediaKey = relToMediaKey;
            this.mediaBytes = mediaBytes;
            this.mediaNames = mediaNames;
            this.imageLinkPrefix = imageLinkPrefix;
        }

        String registerImageByRel(String rId) {
            if (rId == null || rId.isBlank()) return null;
            String mediaKey = relToMediaKey.get(rId);
            if (mediaKey == null) return null;
            byte[] bytes = mediaBytes.get(mediaKey);
            String fname = mediaNames.get(mediaKey);
            if (bytes == null || fname == null) return null;
            if (seenMediaKeys.add(mediaKey)) {
                String mime = IMAGE_MIMES.get(extensionOf(fname));
                if (mime != null) orderedImages.add(new ExtractedImage(fname, mime, bytes));
            }
            int idx = indexOfImage(fname);
            if (imageLinkPrefix != null && !imageLinkPrefix.isEmpty()) {
                return "![图片 " + idx + "](" + imageLinkPrefix + fname + ")";
            }
            return "[内嵌图片 " + idx + "：" + fname + "]";
        }

        private int indexOfImage(String fname) {
            for (int i = 0; i < orderedImages.size(); i++) {
                if (orderedImages.get(i).filename().equals(fname)) return i + 1;
            }
            return orderedImages.size() + 1;
        }
    }

    private static void walkContainer(Element container, StringBuilder md, WalkContext ctx) {
        NodeList children = container.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String local = localName(el);
            if ("Fallback".equals(local)) continue;
            switch (local) {
                case "p" -> {
                    String line = renderParagraph(el, ctx);
                    if (!line.isBlank()) {
                        if (!md.isEmpty()) md.append("\n\n");
                        md.append(line);
                    }
                }
                case "tbl" -> {
                    String table = renderTable(el, ctx);
                    if (!table.isBlank()) {
                        if (!md.isEmpty()) md.append("\n\n");
                        md.append(table);
                    }
                }
                case "sdt" -> {
                    Element content = firstChild(el, "sdtContent");
                    if (content != null) walkContainer(content, md, ctx);
                }
                default -> { }
            }
        }
    }

    private static String renderParagraph(Element p, WalkContext ctx) {
        int headingLevel = detectHeadingLevel(p);
        boolean isList = hasDirectChildPath(p, "pPr", "numPr");
        StringBuilder buf = new StringBuilder();
        collectInline(p, buf, ctx);
        String text = buf.toString().replaceAll("[ \\t]+\\n", "\n").trim();
        if (text.isEmpty()) return "";
        if (headingLevel > 0) {
            return "#".repeat(Math.min(6, headingLevel)) + " " + text.replace("\n", " ");
        }
        if (isList && !text.startsWith("- ") && !text.startsWith("* ")) {
            return "- " + text;
        }
        return text;
    }

    private static void collectInline(Node node, StringBuilder out, WalkContext ctx) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) n;
            String local = localName(el);
            if ("Fallback".equals(local) || "pPr".equals(local) || "rPr".equals(local)
                    || "del".equals(local) || "instrText".equals(local)) {
                continue;
            }
            switch (local) {
                case "t" -> out.append(el.getTextContent());
                case "tab" -> out.append('\t');
                case "br", "cr" -> out.append('\n');
                case "blip" -> {
                    String rId = attrByLocal(el, "embed");
                    if (rId == null) rId = attrByLocal(el, "link");
                    String tag = ctx.registerImageByRel(rId);
                    if (tag != null) {
                        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append('\n');
                        out.append(tag).append('\n');
                    }
                }
                case "imagedata" -> {
                    String rId = attrByLocal(el, "id");
                    String tag = ctx.registerImageByRel(rId);
                    if (tag != null) {
                        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append('\n');
                        out.append(tag).append('\n');
                    }
                }
                case "txbxContent" -> {
                    StringBuilder box = new StringBuilder();
                    walkContainer(el, box, ctx);
                    String bt = box.toString().trim();
                    if (!bt.isEmpty()) {
                        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append('\n');
                        out.append(bt).append('\n');
                    }
                }
                default -> collectInline(el, out, ctx);
            }
        }
    }

    private static String renderTable(Element tbl, WalkContext ctx) {
        List<List<String>> rows = new ArrayList<>();
        NodeList children = tbl.getChildNodes();
        int maxCols = 0;
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE || !"tr".equals(localName(n))) continue;
            List<String> cells = new ArrayList<>();
            NodeList trChildren = n.getChildNodes();
            for (int j = 0; j < trChildren.getLength(); j++) {
                Node cn = trChildren.item(j);
                if (cn.getNodeType() != Node.ELEMENT_NODE || !"tc".equals(localName(cn))) continue;
                StringBuilder cellMd = new StringBuilder();
                walkContainer((Element) cn, cellMd, ctx);
                String cellText = cellMd.toString()
                        .replace("|", "\\|")
                        .replaceAll("\\s*\\n+\\s*", " / ")
                        .trim();
                cells.add(cellText);
            }
            if (!cells.isEmpty()) {
                maxCols = Math.max(maxCols, cells.size());
                rows.add(cells);
            }
        }
        if (rows.isEmpty() || maxCols == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            sb.append('|');
            for (int c = 0; c < maxCols; c++) {
                sb.append(' ').append(c < row.size() ? row.get(c) : "").append(" |");
            }
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                for (int c = 0; c < maxCols; c++) sb.append(" --- |");
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static int detectHeadingLevel(Element p) {
        Element pPr = firstChild(p, "pPr");
        if (pPr == null) return 0;
        Element pStyle = firstChild(pPr, "pStyle");
        if (pStyle != null) {
            String val = attrByLocal(pStyle, "val");
            if (val != null) {
                String v = val.trim();
                if ("title".equalsIgnoreCase(v)) return 1;
                Matcher m = HEADING_NUM.matcher(v);
                if (m.matches()) return Integer.parseInt(m.group(1));
                Matcher d = PURE_DIGIT_HEADING.matcher(v);
                if (d.matches()) return Integer.parseInt(d.group(1));
            }
        }
        Element outline = firstChild(pPr, "outlineLvl");
        if (outline != null) {
            String val = attrByLocal(outline, "val");
            if (val != null) {
                try {
                    int lvl = Integer.parseInt(val.trim());
                    if (lvl >= 0 && lvl <= 5) return lvl + 1;
                } catch (NumberFormatException ignored) { }
            }
        }
        return 0;
    }

    private static Map<String, String> parseRels(byte[] relsBytes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (relsBytes == null || relsBytes.length == 0) return out;
        try {
            Document doc = parseXml(relsBytes);
            NodeList list = doc.getElementsByTagName("*");
            for (int i = 0; i < list.getLength(); i++) {
                Node n = list.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE || !"Relationship".equals(localName(n))) continue;
                Element el = (Element) n;
                String id = el.getAttribute("Id");
                String target = el.getAttribute("Target");
                String mode = el.getAttribute("TargetMode");
                if (id == null || id.isBlank() || target == null || target.isBlank()) continue;
                if ("External".equalsIgnoreCase(mode)) continue;
                String norm = normalizeZipPath(target);
                if (norm.startsWith("../")) norm = norm.substring(3);
                else if (!norm.toLowerCase(Locale.ROOT).startsWith("word/")) norm = "word/" + norm;
                out.put(id, norm.toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static Document parseXml(byte[] xmlBytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // 关闭严格命名空间校验，兼容 WPS / 第三方导出省略 xmlns 前缀声明的 .docx（localName() 已统一剥离前缀）
        f.setNamespaceAware(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xmlBytes));
    }

    private static String localName(Node n) {
        String ln = n.getLocalName();
        if (ln != null) return ln;
        String name = n.getNodeName();
        int idx = name.indexOf(':');
        return idx >= 0 ? name.substring(idx + 1) : name;
    }

    private static Element firstChild(Element parent, String local) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(localName(n))) return (Element) n;
        }
        return null;
    }

    private static Element firstDescendant(Element parent, String local) {
        if (parent == null) return null;
        NodeList list = parent.getElementsByTagName("*");
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(localName(n))) return (Element) n;
        }
        return null;
    }

    private static boolean hasDirectChildPath(Element el, String first, String second) {
        Element a = firstChild(el, first);
        return a != null && firstChild(a, second) != null;
    }

    private static String attrByLocal(Element el, String local) {
        NamedNodeMap attrs = el.getAttributes();
        if (attrs == null) return null;
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (local.equals(localName(a))) return a.getNodeValue();
        }
        return null;
    }

    private static byte[] readBounded(ZipInputStream in, long maxBytes) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > maxBytes) return null;
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String normalizeZipPath(String name) {
        return name == null ? "" : name.replace('\\', '/').replaceFirst("^/+", "");
    }

    private static String sanitizeMediaName(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceFirst("^-+", "");
        return s.isBlank() ? "image.png" : s;
    }

    private static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isZipHeader(byte[] data) {
        return data.length >= 4 && data[0] == 0x50 && data[1] == 0x4B
                && (data[2] == 0x03 || data[2] == 0x05 || data[2] == 0x07);
    }

    private static boolean isOle2Binary(byte[] data) {
        return data.length >= 8
                && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF
                && (data[2] & 0xFF) == 0x11 && (data[3] & 0xFF) == 0xE0;
    }

    private static String unescape(String s) {
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&").replace("&nbsp;", " ");
        s = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(s)
                .replaceAll(m -> new String(Character.toChars(Integer.parseInt(m.group(1), 16))));
        return Pattern.compile("&#(\\d+);").matcher(s)
                .replaceAll(m -> new String(Character.toChars(Integer.parseInt(m.group(1)))));
    }

    private static String tidy(String s) {
        return s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
