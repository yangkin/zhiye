package com.tcl.csot.zhiye.service.chat;

import com.tcl.csot.zhiye.service.app.AssetService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 问答消息里附带的素材：图片作为视觉输入带给模型，PDF / Word / Excel / PPT 提取成文本片段。
 * 只在问答（不改代码）时使用；生成 / 修改任务仍由 TaskExecutor 把文件拷进项目。
 */
@Service
public class AskAttachments {
    private static final Logger log = LoggerFactory.getLogger(AskAttachments.class);

    /** text：提取出的文字片段（null 表示没能提取）；image：原始图片字节 */
    public record Attached(String id, String name, String mime, String text, byte[] image) {
        public boolean isImage() { return image != null; }
    }

    static final int MAX_ATTACH = 5;
    /** 单个文本附件带进模型的上限 */
    static final int TEXT_CHARS = 8000;
    /** 全部文本附件合计上限 */
    static final int TOTAL_TEXT_CHARS = 24000;
    /** 图片超过这个大小就不带（上传上限 10MB，太大的 base64 会让请求很慢） */
    static final long IMAGE_BYTES = 6L * 1024 * 1024;

    private final AssetService assets;

    public AskAttachments(AssetService assets) { this.assets = assets; }

    /** 校验归属并按类型整理。找不到或不属于这个工作空间的素材直接报错（请求线程里返回 404）。 */
    public List<Attached> load(String ws, List<String> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) return List.of();
        List<Attached> out = new ArrayList<>();
        int textTotal = 0;
        for (String id : assetIds) {
            if (id == null || id.isBlank() || out.size() >= MAX_ATTACH) continue;
            AssetService.Content c;
            try {
                c = assets.content(ws, id);
            } catch (com.tcl.csot.zhiye.exception.ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (c == null) continue;
            // SVG 是 XML 文本，当文档处理（视觉模型对矢量图效果不好）
            if (c.mime().startsWith("image/") && !c.mime().equals("image/svg+xml")) {
                out.add(c.data().length <= IMAGE_BYTES
                        ? new Attached(id, c.name(), c.mime(), null, c.data())
                        : new Attached(id, c.name(), c.mime(), "（图片超过大小限制，未能带给模型）", null));
                continue;
            }
            if (textTotal >= TOTAL_TEXT_CHARS) {
                out.add(new Attached(id, c.name(), c.mime(), "（附件过多，这份没有读取）", null));
                continue;
            }
            String text = extract(c.name(), c.mime(), c.data());
            if (text == null) {
                out.add(new Attached(id, c.name(), c.mime(), "（暂时读不了这种格式的内容）", null));
                continue;
            }
            int room = Math.min(TEXT_CHARS, TOTAL_TEXT_CHARS - textTotal);
            if (text.length() > room) text = text.substring(0, room) + "\n……（内容过长，已截断）";
            textTotal += text.length();
            out.add(new Attached(id, c.name(), c.mime(), text, null));
        }
        return out;
    }

    /** 提取文档文字；返回 null 表示这个格式读不了 */
    static String extract(String name, String mime, byte[] data) {
        String lower = name.toLowerCase();
        try {
            if (mime.startsWith("text/") || lower.matches(".*\\.(txt|md|markdown|csv|json|log|xml|ya?ml|sql)$"))
                return new String(data, StandardCharsets.UTF_8);
            if (mime.equals("application/pdf") || lower.endsWith(".pdf")) return pdfText(data);
            if (lower.endsWith(".docx")) return docxText(data);
            if (lower.endsWith(".xlsx")) return xlsxText(data);
            if (lower.endsWith(".pptx")) return pptxText(data);
            if (mime.equals("image/svg+xml") || lower.endsWith(".svg")) return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("提取附件文字失败 {}：{}", name, e.getMessage());
        }
        return null;
    }

    private static String pdfText(byte[] data) throws Exception {
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return tidy(stripper.getText(doc));
        }
    }

    /** OOXML：解 zip，取需要的 XML，去标签留文字 */
    private static Map<String, String> unzip(byte[] data) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory() || files.size() > 200) continue;
                files.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static String docxText(byte[] data) throws Exception {
        String xml = unzip(data).get("word/document.xml");
        if (xml == null) return null;
        return tidy(stripTags(xml
                .replaceAll("</w:p>", "\n").replaceAll("<w:br\\s*/?>", "\n")
                .replaceAll("<w:cr\\s*/?>", "\n").replaceAll("<w:tab\\s*/?>", "\t")));
    }

    private static String pptxText(byte[] data) throws Exception {
        Map<String, String> files = unzip(data);
        StringBuilder out = new StringBuilder();
        files.keySet().stream().filter(k -> k.matches("ppt/slides/slide\\d+\\.xml"))
                .sorted((a, b) -> Integer.compare(num(a), num(b)))
                .forEach(k -> {
                    if (!out.isEmpty()) out.append("\n\n");
                    out.append(stripTags(files.get(k).replaceAll("</a:p>", "\n")));
                });
        return out.isEmpty() ? null : tidy(out.toString());
    }

    private static int num(String path) {
        Matcher m = Pattern.compile("\\d+").matcher(path.substring(path.lastIndexOf('/') + 1));
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    private static final Pattern SI = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<c\\b([^>]*)>(.*?)</c>|<c\\b([^>]*)/>", Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<row\\b[^>]*>(.*?)</row>", Pattern.DOTALL);
    private static final Pattern V = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL);
    private static final Pattern T_ATTR = Pattern.compile("\\bt=\"([^\"]+)\"");

    private static String xlsxText(byte[] data) throws Exception {
        Map<String, String> files = unzip(data);
        List<String> shared = new ArrayList<>();
        String ss = files.get("xl/sharedStrings.xml");
        if (ss != null) {
            Matcher m = SI.matcher(ss);
            while (m.find()) shared.add(stripTags(m.group(1)));
        }
        StringBuilder out = new StringBuilder();
        files.keySet().stream().filter(k -> k.matches("xl/worksheets/sheet\\d+\\.xml"))
                .sorted((a, b) -> Integer.compare(num(a), num(b)))
                .forEach(k -> {
                    String sheet = files.get(k);
                    Matcher rows = ROW.matcher(sheet);
                    while (rows.find()) {
                        StringBuilder line = new StringBuilder();
                        Matcher cells = CELL.matcher(rows.group(1));
                        while (cells.find()) {
                            String attrs = cells.group(1) != null ? cells.group(1) : cells.group(3);
                            String inner = cells.group(2) == null ? "" : cells.group(2);
                            String v = null;
                            Matcher tm = T_ATTR.matcher(attrs == null ? "" : attrs);
                            String type = tm.find() ? tm.group(1) : "";
                            Matcher vm = V.matcher(inner);
                            if ("s".equals(type) && vm.find()) {
                                int i;
                                try { i = Integer.parseInt(vm.group(1).trim()); } catch (NumberFormatException e) { i = -1; }
                                v = i >= 0 && i < shared.size() ? shared.get(i) : null;
                            } else if ("inlineStr".equals(type)) {
                                v = stripTags(inner);
                            } else if (vm.find()) {
                                v = "b".equals(type) ? ("1".equals(vm.group(1).trim()) ? "TRUE" : "FALSE") : vm.group(1);
                            }
                            if (v != null && !v.isBlank()) {
                                if (!line.isEmpty()) line.append('\t');
                                line.append(v.trim());
                            }
                        }
                        if (!line.isEmpty()) out.append(line).append('\n');
                    }
                });
        return out.isEmpty() ? null : tidy(out.toString());
    }

    private static String stripTags(String xml) {
        return unescape(xml.replaceAll("<[^>]+>", ""));
    }

    private static String unescape(String s) {
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
        s = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(s)
                .replaceAll(m -> new String(Character.toChars(Integer.parseInt(m.group(1), 16))));
        return Pattern.compile("&#(\\d+);").matcher(s)
                .replaceAll(m -> new String(Character.toChars(Integer.parseInt(m.group(1)))));
    }

    /** 收敛多余空行和首尾空白 */
    private static String tidy(String s) {
        return s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
