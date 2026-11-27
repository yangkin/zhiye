package com.tcl.csot.zhiye.service.importer;

import com.tcl.csot.zhiye.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.Inflater;

/**
 * 导入预检：读取 zip 中央目录（不解压到磁盘），做安全检查和项目类型识别。
 * 规则与 docs/runner-protocol.md §4 import 一致；最终以 runner 的识别为准。
 */
public final class ImportInspector {
    public record FileInfo(String path, long size) {}

    public record Result(String framework, boolean deployable, String unsupportedReason, String projectKind,
                         int fileCount, long totalSize, List<FileInfo> files, String rootDir,
                         List<ProjectAnalyzer.Candidate> candidates, List<ProjectAnalyzer.Warning> warnings) {}

    public record Limits(long maxUpload, int maxFiles, long maxUnpacked, long maxFile) {}

    private record Entry(String name, int method, long csize, long usize, long localOffset, boolean dir, boolean symlink) {}

    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", ".git", ".svn", ".hg", "__MACOSX", ".venv", "venv", "__pycache__",
            ".pytest_cache", ".mypy_cache", ".idea", ".vscode", ".turbo", ".gradle", ".next", ".nuxt", ".parcel-cache"
    );
    private static final Set<String> SKIP_FILES = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");

    /** 不应被当作「外层冗余壳目录」剥离的标准项目内部目录名 */
    private static final Set<String> KEEP_TOP_DIRS = Set.of(
            "src", "app", "public", "static", "templates", "frontend", "backend", "server",
            "client", "web", "api", "dist", "build", "scripts", "docs", "references",
            "cmd", "internal", "pkg", "assets", "components", "pages"
    );

    private ImportInspector() {}

    static ApiException invalid(String msg) { return new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_INVALID", msg); }

    public static Result inspectHtml(byte[] data, Limits limits) {
        if (data.length > limits.maxFile()) throw invalid("HTML 文件太大：单个文件最多 " + (limits.maxFile() >> 20) + " MB");
        String s = new String(data, StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) throw invalid("文件是空的，请选择一个有内容的 HTML 文件");
        if (!s.toLowerCase().matches("(?s).*<(html|body|head|div|h1|p|main|script|!doctype)[\\s>].*"))
            throw invalid("这不是有效的 HTML 文件，请检查文件内容");
        return new Result("HTML", true, null, "static", 1, data.length, List.of(new FileInfo("index.html", data.length)), "",
                List.of(new ProjectAnalyzer.Candidate("", "static", "HTML", "根目录 · HTML", false)), List.of());
    }

    /** 单文件 Python / Node.js 脚本导入预检（支持无页面脚本在线运行；若包含 HTTP 监听代码则同时提供服务托管候选）。 */
    public static Result inspectScript(String fileName, byte[] data, Limits limits) {
        if (data.length > limits.maxFile()) throw invalid("脚本文件太大：单个文件最多 " + (limits.maxFile() >> 20) + " MB");
        String src = new String(data, StandardCharsets.UTF_8);
        if (src.trim().isEmpty()) throw invalid("脚本文件是空的，请选择有内容的 Python 或 JS 文件");
        String lower = fileName.toLowerCase(Locale.ROOT);
        boolean isPy = lower.endsWith(".py");
        String scriptFw = isPy ? "Python 脚本" : "Node.js (JS) 脚本";
        boolean looksWeb = isPy
                ? com.tcl.csot.zhiye.service.runtime.ServiceRuntimeDetector.looksLikePythonWebSource(src)
                : com.tcl.csot.zhiye.service.runtime.ServiceRuntimeDetector.looksLikeNodeWebSource(src);
        List<ProjectAnalyzer.Candidate> candidates = new ArrayList<>();
        List<ProjectAnalyzer.Warning> warnings = new ArrayList<>();
        if (looksWeb) {
            String svcFw = isPy ? "Python HTTP 服务" : "Node.js HTTP 服务";
            candidates.add(new ProjectAnalyzer.Candidate("", "service", svcFw, "根目录 · " + svcFw + "（单体服务托管）", false));
            candidates.add(new ProjectAnalyzer.Candidate("", "script", scriptFw, "根目录 · " + scriptFw + "（在线运行脚本）", false));
            warnings.add(new ProjectAnalyzer.Warning("info", "headless-api", "识别到无页面 HTTP 接口脚本（" + svcFw + "）",
                    "检测到服务监听代码，默认按单体后端服务托管；若只需要单次执行查看输出，也可改选「在线运行脚本」。"));
            return new Result(svcFw, true, null, "service", 1, data.length, List.of(new FileInfo(fileName, data.length)), "", candidates, warnings);
        }
        candidates.add(new ProjectAnalyzer.Candidate("", "script", scriptFw, "根目录 · " + scriptFw + "（在线运行脚本）", false));
        warnings.add(new ProjectAnalyzer.Warning("info", "headless-script", "无页面脚本项目（" + scriptFw + "）",
                "导入后无需前端页面，可直接在工作台「在线运行」传入命令行参数与标准输入测试，或用 AI 迭代扩展。"));
        return new Result(scriptFw, true, null, "script", 1, data.length, List.of(new FileInfo(fileName, data.length)), "", candidates, warnings);
    }

    public static Result inspectZip(Path zip, Limits limits) throws IOException {
        long size = Files.size(zip);
        if (size > limits.maxUpload()) throw invalid("压缩包太大：最多 " + (limits.maxUpload() >> 20) + " MB，请删掉不需要的文件后重试");
        List<Entry> entries = readCentralDirectory(zip);
        List<Entry> files = new ArrayList<>();
        for (Entry e : entries) {
            String n = e.name().replace('\\', '/');
            if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")) throw invalid("压缩包包含绝对路径「" + n + "」，已拒绝导入");
            for (String part : n.split("/")) if (part.equals("..")) throw invalid("压缩包包含不安全的路径「" + n + "」，已拒绝导入");
            if (e.symlink()) throw invalid("压缩包包含符号链接「" + n + "」，已拒绝导入");
            if (e.dir() || n.endsWith("/")) continue;
            if (skipped(n)) continue;
            files.add(e);
        }
        if (files.isEmpty()) throw invalid("压缩包里没有可导入的文件");
        if (files.size() > limits.maxFiles()) throw invalid("文件太多：最多 " + limits.maxFiles() + " 个，当前 " + files.size() + " 个");
        long total = 0;
        for (Entry e : files) {
            if (e.usize() > limits.maxFile()) throw invalid("文件「" + e.name() + "」太大：单个文件最多 " + (limits.maxFile() >> 20) + " MB");
            if (e.csize() > 0 && e.usize() / Math.max(1, e.csize()) > 200 && e.usize() > (1 << 20))
                throw invalid("压缩包里的「" + e.name() + "」压缩比异常，疑似压缩炸弹，已拒绝导入");
            total += e.usize();
        }
        if (total > limits.maxUnpacked()) throw invalid("解压后太大：最多 " + (limits.maxUnpacked() >> 20) + " MB");

        // 去掉共同的顶层目录
        String prefix = commonTopDir(files);
        Map<String, Entry> byPath = new LinkedHashMap<>();
        for (Entry e : files) byPath.put(e.name().replace('\\', '/').substring(prefix.length()), e);
        List<FileInfo> infos = byPath.entrySet().stream().map(x -> new FileInfo(x.getKey(), x.getValue().usize())).toList();

        byte[] all = Files.readAllBytes(zip);
        ProjectAnalyzer.Report rep = ProjectAnalyzer.analyze(sizes(byPath), p -> {
            Entry e = byPath.get(p);
            if (e == null || e.usize() > (2 << 20)) return null;
            try { return new String(readEntry(all, e, 2 << 20), StandardCharsets.UTF_8); } catch (Exception ex) { return null; }
        });
        return new Result(rep.framework(), rep.deployable(), rep.unsupportedReason(), rep.projectKind(), infos.size(), total, infos,
                rep.rootDir(), rep.candidates(), rep.warnings());
    }

    /** 安全解压 ZIP 到目标目录（去掉统一顶层目录、支持 rootDir 子目录、跳过 .git / node_modules / __MACOSX / .DS_Store）。 */
    public static int extractZipToDir(Path zip, Path targetDir, String rootDir, Limits limits) throws Exception {
        Result checked = inspectZip(zip, limits);
        List<Entry> entries = readCentralDirectory(zip);
        List<Entry> files = new ArrayList<>();
        for (Entry e : entries) {
            String n = e.name().replace('\\', '/');
            if (e.dir() || n.endsWith("/") || skipped(n)) continue;
            files.add(e);
        }
        String prefix = commonTopDir(files);
        String subPrefix = (rootDir == null || rootDir.isBlank()) ? "" : rootDir.replace('\\', '/').replaceAll("^/+|/+$", "") + "/";
        byte[] all = Files.readAllBytes(zip);
        Path root = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        int written = 0;
        for (Entry e : files) {
            String rel = e.name().replace('\\', '/').substring(prefix.length());
            if (!subPrefix.isEmpty()) {
                if (!rel.startsWith(subPrefix)) continue;
                rel = rel.substring(subPrefix.length());
            }
            if (rel.isEmpty() || rel.contains("..")) continue;
            Path dst = root.resolve(rel).normalize();
            if (!dst.startsWith(root)) throw invalid("压缩包包含不安全的路径：" + rel);
            Files.createDirectories(dst.getParent());
            Files.write(dst, readEntry(all, e, (int) Math.min(Integer.MAX_VALUE, limits.maxFile())));
            written++;
        }
        if (written == 0 && checked.fileCount() > 0) throw invalid("压缩包里找不到指定目录的文件：" + rootDir);
        return written;
    }

    private static Map<String, Long> sizes(Map<String, Entry> byPath) {
        Map<String, Long> out = new LinkedHashMap<>();
        byPath.forEach((k, v) -> out.put(k, v.usize()));
        return out;
    }

    static boolean skipped(String n) {
        String[] parts = n.split("/");
        for (int i = 0; i < parts.length - 1; i++) if (SKIP_DIRS.contains(parts[i])) return true;
        String last = parts[parts.length - 1];
        return SKIP_FILES.contains(last) || last.startsWith("._") || SKIP_DIRS.contains(last);
    }

    private static String commonTopDir(List<Entry> files) {
        String prefix = "";
        for (int depth = 0; depth < 3; depth++) {
            String first = null;
            boolean valid = true;
            for (Entry e : files) {
                String n = e.name().replace('\\', '/').substring(prefix.length());
                int i = n.indexOf('/');
                if (i < 0) { valid = false; break; }
                String top = n.substring(0, i);
                if (depth > 0 && KEEP_TOP_DIRS.contains(top.toLowerCase(Locale.ROOT))) { valid = false; break; }
                String topSlash = top + "/";
                if (first == null) first = topSlash;
                else if (!first.equals(topSlash)) { valid = false; break; }
            }
            if (!valid || first == null) break;
            prefix += first;
        }
        return prefix;
    }

    // ---------- zip 中央目录解析 ----------
    private static List<Entry> readCentralDirectory(Path zip) throws IOException {
        byte[] all = Files.readAllBytes(zip);
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = all.length - 22; i >= Math.max(0, all.length - 22 - 65535); i--) {
            if (b.getInt(i) == 0x06054b50) { eocd = i; break; }
        }
        if (eocd < 0) throw invalid("文件不是有效的 ZIP 压缩包，请重新打包后再试");
        int count = Short.toUnsignedInt(b.getShort(eocd + 10));
        long cdOffset = Integer.toUnsignedLong(b.getInt(eocd + 16));
        if (cdOffset >= all.length) throw invalid("ZIP 文件已损坏，请重新打包后再试");
        List<Entry> out = new ArrayList<>();
        int p = (int) cdOffset;
        for (int k = 0; k < count; k++) {
            if (p + 46 > all.length || b.getInt(p) != 0x02014b50) throw invalid("ZIP 文件已损坏，请重新打包后再试");
            int madeBy = Short.toUnsignedInt(b.getShort(p + 4));
            int flags = Short.toUnsignedInt(b.getShort(p + 8));
            int method = Short.toUnsignedInt(b.getShort(p + 10));
            long csize = Integer.toUnsignedLong(b.getInt(p + 20));
            long usize = Integer.toUnsignedLong(b.getInt(p + 24));
            int nameLen = Short.toUnsignedInt(b.getShort(p + 28));
            int extraLen = Short.toUnsignedInt(b.getShort(p + 30));
            int commentLen = Short.toUnsignedInt(b.getShort(p + 32));
            long ext = Integer.toUnsignedLong(b.getInt(p + 38));
            long local = Integer.toUnsignedLong(b.getInt(p + 42));
            if (p + 46 + nameLen > all.length) throw invalid("ZIP 文件已损坏，请重新打包后再试");
            byte[] nameBytes = Arrays.copyOfRange(all, p + 46, p + 46 + nameLen);
            String name = decodeName(nameBytes, (flags & 0x800) != 0);
            boolean unix = (madeBy >> 8) == 3;
            int mode = (int) (ext >>> 16);
            boolean symlink = unix && (mode & 0170000) == 0120000;
            boolean dir = name.endsWith("/") || (unix && (mode & 0170000) == 0040000);
            out.add(new Entry(name, method, csize, usize, local, dir, symlink));
            p += 46 + nameLen + extraLen + commentLen;
        }
        return out;
    }

    private static String decodeName(byte[] bytes, boolean utf8Flag) {
        if (utf8Flag) return new String(bytes, StandardCharsets.UTF_8);
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
        try { return d.decode(ByteBuffer.wrap(bytes)).toString(); } catch (Exception e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    private static byte[] readEntry(byte[] all, Entry e, int max) throws Exception {
        if (e.usize() > max) throw new IOException("too large");
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int p = (int) e.localOffset();
        if (b.getInt(p) != 0x04034b50) throw new IOException("bad local header");
        int nameLen = Short.toUnsignedInt(b.getShort(p + 26));
        int extraLen = Short.toUnsignedInt(b.getShort(p + 28));
        int start = p + 30 + nameLen + extraLen;
        byte[] data = Arrays.copyOfRange(all, start, (int) (start + e.csize()));
        if (e.method() == 0) return data;
        Inflater inf = new Inflater(true);
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("too large");
        }
        inf.end();
        return out.toByteArray();
    }

    @SuppressWarnings("unused")
    private static byte[] readAll(InputStream in) throws IOException { return in.readAllBytes(); }
}
