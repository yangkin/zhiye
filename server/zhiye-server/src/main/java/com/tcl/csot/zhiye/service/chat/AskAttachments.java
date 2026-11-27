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
    static final int TEXT_CHARS = 14000;
    /** 全部文本附件合计上限 */
    static final int TOTAL_TEXT_CHARS = 32000;
    /** 图片超过这个大小就不带（上传上限 10MB，太大的 base64 会让请求很慢） */
    static final long IMAGE_BYTES = 6L * 1024 * 1024;
    /** Word 内嵌图片带给多模态模型的上限（构建时会全部解压到 public/assets/） */
    static final int MAX_DOCX_VISION_IMAGES = 4;
    static final long MAX_DOCX_VISION_IMAGE_BYTES = 2L * 1024 * 1024;
    static final long MAX_DOCX_VISION_TOTAL_BYTES = 5L * 1024 * 1024;

    private final AssetService assets;

    public AskAttachments(AssetService assets) { this.assets = assets; }

    /** 校验归属并按类型整理。找不到或不属于这个工作空间的素材直接报错（请求线程里返回 404）。 */
    public List<Attached> load(String ws, List<String> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) return List.of();
        List<Attached> out = new ArrayList<>();
        int textTotal = 0;
        int docCount = 0;
        for (String id : assetIds) {
            if (id == null || id.isBlank() || docCount >= MAX_ATTACH) continue;
            AssetService.Content c;
            try {
                c = assets.content(ws, id);
            } catch (com.tcl.csot.zhiye.exception.ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (c == null) continue;
            docCount++;
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
            // Word（.docx）：提取完整结构化 Markdown + 表格 + 内嵌图片（同时把关键内嵌图片带给视觉模型）
            if (isDocx(c.name(), c.mime())) {
                try {
                    DocxExtractor.Result docx = DocxExtractor.extract(c.data(), null);
                    if (docx.legacyBinaryDoc() || (!docx.hasContent() && docx.notice() != null)) {
                        out.add(new Attached(id, c.name(), c.mime(), "（" + docx.notice() + "）", null));
                        continue;
                    }
                    StringBuilder body = new StringBuilder();
                    if (!docx.images().isEmpty()) {
                        body.append("（该 Word 文档共解析出 ").append(docx.images().size())
                                .append(" 张内嵌图片，已附在下方供视觉参考；构建页面时全部原图会自动解压到 `public/assets/` 并生成带图片链接的 Markdown，可直接嵌入 HTML。）\n\n");
                    }
                    body.append(docx.markdown() == null || docx.markdown().isBlank() ? "（文档正文无文字，主要内容为内嵌图片）" : docx.markdown());
                    String text = body.toString();
                    int room = Math.min(TEXT_CHARS, TOTAL_TEXT_CHARS - textTotal);
                    if (text.length() > room) text = text.substring(0, room) + "\n……（内容过长，已截断；构建时完整内容会自动解压到项目目录）";
                    textTotal += text.length();
                    out.add(new Attached(id, c.name(), c.mime(), text, null));

                    long visionBytes = 0;
                    int visionCount = 0;
                    for (DocxExtractor.ExtractedImage img : docx.images()) {
                        if (visionCount >= MAX_DOCX_VISION_IMAGES) break;
                        if (!img.isRasterForVision() || img.bytes().length > MAX_DOCX_VISION_IMAGE_BYTES) continue;
                        if (visionBytes + img.bytes().length > MAX_DOCX_VISION_TOTAL_BYTES) continue;
                        visionBytes += img.bytes().length;
                        visionCount++;
                        out.add(new Attached(id + "#" + img.filename(), c.name() + " 内嵌图 " + visionCount + " (" + img.filename() + ")",
                                img.mime(), null, img.bytes()));
                    }
                    continue;
                } catch (Exception e) {
                    log.warn("提取 Word 附件失败 {}：{}", c.name(), e.getMessage());
                }
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

    /** 为多轮对话后续轮次提供轻量历史附件上下文：文档/表格保留结构化画像与摘要，图片仅保留引用避免重复传输 Base64。 */
    public String historicalContext(String ws, List<String> earlierAssetIds) {
        if (earlierAssetIds == null || earlierAssetIds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int docCount = 0;
        int totalChars = 0;
        final int maxDocs = 3;
        final int perDocCap = 2600;
        final int totalCap = 6500;
        for (String id : earlierAssetIds) {
            if (id == null || id.isBlank()) continue;
            AssetService.Content c;
            try {
                c = assets.content(ws, id);
            } catch (Exception ignored) {
                continue;
            }
            if (c == null) continue;
            if (c.mime().startsWith("image/") && !c.mime().equals("image/svg+xml")) {
                sb.append("- 前序上传图片：`").append(c.name())
                        .append("`（已在上传当轮完成视觉识别；构建应用时原图会自动拷贝至 `public/assets/`）\n");
                continue;
            }
            if (docCount >= maxDocs || totalChars >= totalCap) {
                sb.append("- 前序上传文件：`").append(c.name()).append("`（构建应用时原文件会自动拷贝至 `public/assets/`）\n");
                continue;
            }
            String text = extract(c.name(), c.mime(), c.data());
            if (text == null || text.isBlank()) {
                sb.append("- 前序上传文件：`").append(c.name()).append("`（构建应用时原文件会自动拷贝至 `public/assets/`）\n");
                continue;
            }
            int room = Math.min(perDocCap, totalCap - totalChars);
            if (text.length() > room) text = text.substring(0, room) + "\n……（历史附件摘要已截断，完整文件将随构建拷入 public/assets/）";
            totalChars += text.length();
            docCount++;
            sb.append("### 前序上传文件：").append(c.name()).append('\n').append(text).append("\n\n");
        }
        if (sb.isEmpty()) return "";
        return "\n\n【本会话前序轮次上传的附件上下文（多轮对话持续有效；构建应用时所有原文件会自动拷贝至 public/assets/）】\n" + sb.toString().trim();
    }

    static boolean isDocx(String name, String mime) {
        String lower = name == null ? "" : name.toLowerCase();
        return lower.endsWith(".docx")
                || (mime != null && mime.toLowerCase().contains("wordprocessingml.document"));
    }

    /** 提取文档文字；对 CSV / XLSX 自动生成结构化数据画像 + 样例行；对 DOCX 保留标题/表格与图片标记；返回 null 表示这个格式读不了 */
    public static String extract(String name, String mime, byte[] data) {
        String lower = name == null ? "" : name.toLowerCase();
        String m = mime == null ? "" : mime;
        try {
            if (lower.endsWith(".csv") || "text/csv".equalsIgnoreCase(m)) return csvProfile(data);
            if (m.startsWith("text/") || lower.matches(".*\\.(txt|md|markdown|json|log|xml|ya?ml|sql)$"))
                return new String(data, StandardCharsets.UTF_8);
            if (m.equals("application/pdf") || lower.endsWith(".pdf")) return pdfText(data);
            if (isDocx(name, m)) return docxText(data);
            if (lower.endsWith(".xlsx")) return xlsxText(data);
            if (lower.endsWith(".pptx")) return pptxText(data);
            if (m.equals("image/svg+xml") || lower.endsWith(".svg")) return new String(data, StandardCharsets.UTF_8);
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

    /** OOXML XML 提取：仅解码 .xml / .rels 文本条目，跳过图片等二进制条目，规范化路径分隔符，避免媒体文件撑爆条目上限 */
    private static Map<String, String> unzip(byte[] data) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            int seen = 0;
            while ((e = in.getNextEntry()) != null && seen++ < 2000) {
                if (e.isDirectory() || files.size() > 300) continue;
                String norm = e.getName().replace('\\', '/').replaceFirst("^/+", "");
                String lower = norm.toLowerCase();
                if (!lower.endsWith(".xml") && !lower.endsWith(".rels")) continue;
                files.put(norm, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static String docxText(byte[] data) throws Exception {
        DocxExtractor.Result res = DocxExtractor.extract(data, null);
        if (res.legacyBinaryDoc()) return res.notice();
        if (res.hasContent()) {
            StringBuilder sb = new StringBuilder();
            if (!res.images().isEmpty()) {
                sb.append("（含 ").append(res.images().size()).append(" 张内嵌图片）\n\n");
            }
            sb.append(res.markdown());
            return tidy(sb.toString());
        }
        return res.notice();
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
    private static final Pattern R_ATTR = Pattern.compile("\\br=\"([A-Z]+)\\d+\"");
    private static final Pattern SHEET_NAME = Pattern.compile("<sheet\\b[^>]*name=\"([^\"]+)\"");
    private static final Pattern DATE_LIKE = Pattern.compile("^\\d{4}[-/.]\\d{1,2}([-/.]\\d{1,2})?([ T]\\d{1,2}:\\d{2}(:\\d{2})?)?$");

    private static String csvProfile(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        List<List<String>> rows = parseCsvRows(text, 20000);
        if (rows.isEmpty()) return tidy(text);
        return formatTableProfile("CSV 数据表", rows);
    }

    private static List<List<String>> parseCsvRows(String text, int maxRows) {
        List<List<String>> rows = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    cur.add(field.toString().trim());
                    field.setLength(0);
                } else if (c == '\r' || c == '\n') {
                    if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                    cur.add(field.toString().trim());
                    field.setLength(0);
                    if (cur.stream().anyMatch(s -> !s.isEmpty())) rows.add(cur);
                    cur = new ArrayList<>();
                    if (rows.size() >= maxRows) break;
                } else {
                    field.append(c);
                }
            }
        }
        if (rows.size() < maxRows && (!field.isEmpty() || !cur.isEmpty())) {
            cur.add(field.toString().trim());
            if (cur.stream().anyMatch(s -> !s.isEmpty())) rows.add(cur);
        }
        return rows;
    }

    private static int colIndex(String colLetters) {
        int idx = 0;
        for (int i = 0; i < colLetters.length(); i++) {
            idx = idx * 26 + (colLetters.charAt(i) - 'A' + 1);
        }
        return idx - 1;
    }

    private static String xlsxText(byte[] data) throws Exception {
        Map<String, String> files = unzip(data);
        List<String> shared = new ArrayList<>();
        String ss = files.get("xl/sharedStrings.xml");
        if (ss != null) {
            Matcher m = SI.matcher(ss);
            while (m.find()) shared.add(stripTags(m.group(1)));
        }
        List<String> sheetNames = new ArrayList<>();
        String wb = files.get("xl/workbook.xml");
        if (wb != null) {
            Matcher sm = SHEET_NAME.matcher(wb);
            while (sm.find()) sheetNames.add(unescape(sm.group(1)));
        }
        List<String> sheetKeys = files.keySet().stream()
                .filter(k -> k.matches("xl/worksheets/sheet\\d+\\.xml"))
                .sorted((a, b) -> Integer.compare(num(a), num(b)))
                .toList();
        if (sheetKeys.isEmpty()) return null;

        StringBuilder out = new StringBuilder();
        out.append("[Excel 工作簿概览] 共 ").append(sheetKeys.size()).append(" 个工作表");
        if (!sheetNames.isEmpty()) out.append("：").append(String.join("、", sheetNames));
        out.append("\n\n");

        int limitSheets = Math.min(sheetKeys.size(), 3);
        for (int si = 0; si < limitSheets; si++) {
            String k = sheetKeys.get(si);
            String title = si < sheetNames.size() ? sheetNames.get(si) : ("Sheet" + (si + 1));
            String sheet = files.get(k);
            List<List<String>> rows = new ArrayList<>();
            Matcher rowMatcher = ROW.matcher(sheet);
            while (rowMatcher.find() && rows.size() < 20000) {
                List<String> rowVals = new ArrayList<>();
                Matcher cells = CELL.matcher(rowMatcher.group(1));
                while (cells.find()) {
                    String attrs = cells.group(1) != null ? cells.group(1) : cells.group(3);
                    String inner = cells.group(2) == null ? "" : cells.group(2);
                    String attrStr = attrs == null ? "" : attrs;
                    Matcher rm = R_ATTR.matcher(attrStr);
                    int targetCol = rm.find() ? colIndex(rm.group(1)) : rowVals.size();
                    while (rowVals.size() < targetCol && rowVals.size() < 80) rowVals.add("");

                    String v = "";
                    Matcher tm = T_ATTR.matcher(attrStr);
                    String type = tm.find() ? tm.group(1) : "";
                    Matcher vm = V.matcher(inner);
                    if ("s".equals(type) && vm.find()) {
                        int idx;
                        try { idx = Integer.parseInt(vm.group(1).trim()); } catch (NumberFormatException e) { idx = -1; }
                        v = idx >= 0 && idx < shared.size() ? shared.get(idx).trim() : "";
                    } else if ("inlineStr".equals(type)) {
                        v = stripTags(inner).trim();
                    } else if (vm.find()) {
                        v = "b".equals(type) ? ("1".equals(vm.group(1).trim()) ? "TRUE" : "FALSE") : vm.group(1).trim();
                    }
                    if (targetCol < 80) {
                        if (targetCol == rowVals.size()) rowVals.add(v);
                        else rowVals.set(targetCol, v);
                    }
                }
                while (!rowVals.isEmpty() && rowVals.get(rowVals.size() - 1).isEmpty()) rowVals.remove(rowVals.size() - 1);
                if (!rowVals.isEmpty()) rows.add(rowVals);
            }
            if (!rows.isEmpty()) {
                out.append(formatTableProfile("工作表「" + title + "」", rows)).append("\n\n");
            }
        }
        return out.isEmpty() ? null : tidy(out.toString());
    }

    /** 为 Excel / CSV 生成结构化数据画像（行列规模、列类型、数值统计、分类分布）+ 前 25 行样例。 */
    private static String formatTableProfile(String title, List<List<String>> rows) {
        if (rows.isEmpty()) return "";
        List<String> header = rows.get(0);
        int colCount = Math.min(header.size(), 40);
        int dataRowCount = Math.max(0, rows.size() - 1);
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(title).append("（数据行 ").append(dataRowCount)
                .append(" 行 × ").append(colCount).append(" 列）\n");

        if (dataRowCount > 0 && colCount > 0) {
            sb.append("### 字段画像与统计摘要\n");
            for (int c = 0; c < colCount; c++) {
                String colName = c < header.size() && !header.get(c).isBlank() ? header.get(c) : ("列" + (c + 1));
                int nonEmpty = 0, numCount = 0, dateCount = 0;
                double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0;
                Map<String, Integer> freq = new LinkedHashMap<>();
                for (int r = 1; r < rows.size(); r++) {
                    List<String> row = rows.get(r);
                    String val = c < row.size() ? row.get(c).trim() : "";
                    if (val.isEmpty()) continue;
                    nonEmpty++;
                    Double num = parseNumeric(val);
                    if (num != null) {
                        numCount++;
                        if (num < min) min = num;
                        if (num > max) max = num;
                        sum += num;
                    } else if (DATE_LIKE.matcher(val).matches()) {
                        dateCount++;
                    }
                    if (freq.size() < 40 || freq.containsKey(val)) {
                        freq.merge(val, 1, Integer::sum);
                    }
                }
                String kind;
                String detail;
                if (nonEmpty == 0) {
                    kind = "空列";
                    detail = "无有效值";
                } else if (numCount >= nonEmpty * 0.85) {
                    kind = "数值";
                    double avg = sum / numCount;
                    detail = String.format(java.util.Locale.ROOT, "min=%.2f, max=%.2f, avg=%.2f, sum=%.2f", min, max, avg, sum);
                } else if (dateCount >= nonEmpty * 0.8) {
                    kind = "日期/时间";
                    List<String> keys = new ArrayList<>(freq.keySet());
                    detail = keys.size() <= 2 ? String.join(" ~ ", keys) : (keys.get(0) + " ~ " + keys.get(keys.size() - 1));
                } else if (freq.size() <= 15) {
                    kind = "分类维度";
                    detail = freq.entrySet().stream()
                            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                            .limit(6)
                            .map(e -> e.getKey() + "(" + e.getValue() + ")")
                            .collect(java.util.stream.Collectors.joining(", "));
                } else {
                    kind = "文本";
                    detail = "去重值 ≥ " + freq.size() + " 项";
                }
                sb.append("- `").append(colName).append("` [").append(kind).append("]：").append(detail).append('\n');
            }
        }

        int sampleRows = Math.min(rows.size(), 26);
        sb.append("\n### 数据样例（表头 + 前 ").append(Math.max(0, sampleRows - 1)).append(" 行，原始全量文件将随构建放入 public/assets/）\n");
        for (int r = 0; r < sampleRows; r++) {
            List<String> row = rows.get(r);
            int lim = Math.min(Math.max(row.size(), colCount), 40);
            for (int c = 0; c < lim; c++) {
                if (c > 0) sb.append('\t');
                String cell = c < row.size() ? row.get(c).replace('\t', ' ').replace('\n', ' ') : "";
                sb.append(cell.length() > 60 ? cell.substring(0, 60) + "…" : cell);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static Double parseNumeric(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String s = raw.replace(",", "").replace("¥", "").replace("$", "").replace("%", "").trim();
        if (s.isEmpty()) return null;
        try {
            double v = Double.parseDouble(s);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
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
