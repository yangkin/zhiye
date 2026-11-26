package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.ops.PlatformLogService;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 无页面 Python 3 / Node.js (JS/MJS/CJS) 脚本在线安全执行引擎。
 * - 从当前 Git 版本检出到独立临时目录执行，避免污染代码仓库
 * - 使用 ProcessBuilder 显式参数数组（不走 sh -c），禁止路径逃逸与危险环境变量
 * - 捕获 stdout / stderr、退出码、耗时，并检测脚本新生成/修改的输出文件供在线预览
 * - 持久化运行记录到 script_run 表
 */
@Service
public class ScriptRunnerService {
    private static final int MAX_OUTPUT_BYTES = 128 * 1024;
    private static final int MAX_PREVIEW_BYTES = 64 * 1024;
    private static final int DEFAULT_TIMEOUT_SEC = 15;
    private static final int MAX_TIMEOUT_SEC = 30;
    private static final Set<String> BLOCKED_ENV_KEYS = Set.of(
            "PATH", "HOME", "USER", "SHELL", "LD_PRELOAD", "LD_LIBRARY_PATH",
            "DYLD_INSERT_LIBRARIES", "DYLD_LIBRARY_PATH", "NODE_OPTIONS", "PYTHONPATH", "PYTHONSTARTUP");

    private final AppRepository apps;
    private final VersionRepository versions;
    private final GitService git;
    private final ZhiyeProperties props;
    private final JdbcClient jdbc;
    @Autowired(required = false)
    private PlatformLogService platformLogs;

    public ScriptRunnerService(AppRepository apps, VersionRepository versions, GitService git,
                               ZhiyeProperties props, JdbcClient jdbc) {
        this.apps = apps;
        this.versions = versions;
        this.git = git;
        this.props = props;
        this.jdbc = jdbc;
    }

    public Map<String, Object> inspect(String ws, String appId, String versionId) throws Exception {
        AppRow app = requireApp(ws, appId);
        VersionRow ver = resolveVersion(app, versionId);
        List<Map<String, Object>> entryFiles = new ArrayList<>();
        List<String> sampleFiles = new ArrayList<>();
        if (ver != null) {
            List<GitService.FileEntry> all = git.listFiles(app.id(), ver.gitCommit());
            for (var f : all) {
                String p = f.path();
                String lower = p.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) {
                    String rt = lower.endsWith(".py") ? "python" : "node";
                    entryFiles.add(Map.of("path", p, "runtime", rt, "size", f.size()));
                } else if (lower.startsWith("examples/") || lower.startsWith("data/")) {
                    sampleFiles.add(p);
                }
            }
            entryFiles.sort(Comparator.comparingInt(e -> entryPriority(String.valueOf(e.get("path")))));
        }
        List<Map<String, Object>> history = jdbc.sql("""
                select id, version_id, entry_file, runtime, args_text, exit_code, timed_out, duration_ms,
                       stdout_preview, stderr_preview, created_at
                from script_run where app_id = ? order by created_at desc limit 20
                """).param(app.id()).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("versionId", rs.getString("version_id"));
            m.put("entryFile", rs.getString("entry_file"));
            m.put("runtime", rs.getString("runtime"));
            m.put("args", rs.getString("args_text"));
            m.put("exitCode", rs.getObject("exit_code", Integer.class));
            m.put("timedOut", rs.getBoolean("timed_out"));
            m.put("durationMs", rs.getInt("duration_ms"));
            m.put("stdout", rs.getString("stdout_preview"));
            m.put("stderr", rs.getString("stderr_preview"));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).list();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appId", app.id());
        out.put("versionId", ver == null ? null : ver.id());
        out.put("versionNumber", ver == null ? null : ver.number());
        out.put("entries", entryFiles);
        out.put("defaultEntry", entryFiles.isEmpty() ? null : entryFiles.get(0).get("path"));
        out.put("sampleFiles", sampleFiles);
        out.put("history", history);
        return out;
    }

    public Map<String, Object> run(String ws, String appId, String versionId, String entryFile,
                                   String argsText, String stdinText, Map<String, String> userEnv,
                                   Integer timeoutSec) throws Exception {
        AppRow app = requireApp(ws, appId);
        VersionRow ver = resolveVersion(app, versionId);
        if (ver == null) throw ApiException.validation("当前应用还没有可运行的代码版本");

        String rel = normalizeRelativePath(entryFile);
        String lower = rel.toLowerCase(Locale.ROOT);
        boolean isPy = lower.endsWith(".py");
        boolean isJs = lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs");
        if (!isPy && !isJs) {
            throw ApiException.validation("仅支持运行 Python (.py) 或 Node.js (.js / .mjs / .cjs) 脚本文件");
        }
        String runtime = isPy ? "python" : "node";
        int timeout = timeoutSec == null ? DEFAULT_TIMEOUT_SEC : Math.max(1, Math.min(timeoutSec, MAX_TIMEOUT_SEC));
        List<String> parsedArgs = parseArgs(argsText);

        String runId = Ids.newId("srun");
        Path runDir = props.runtimePath().resolve("script-runs").resolve(runId);
        Path dataDir = props.runtimePath().resolve("scripts").resolve(app.id()).resolve("data");
        Files.createDirectories(runDir);
        Files.createDirectories(dataDir);

        try {
            git.checkout(app.id(), ver.gitCommit(), runDir, "script");
            Path scriptPath = runDir.resolve(rel).normalize();
            if (!scriptPath.startsWith(runDir.toAbsolutePath().normalize()) || !Files.isRegularFile(scriptPath)) {
                throw ApiException.validation("脚本文件不存在：" + rel);
            }

            Map<String, Long> beforeSnapshot = snapshotFiles(runDir);

            List<String> cmd = new ArrayList<>();
            cmd.add(isPy ? resolvePythonBinary() : props.nodeBinary());
            cmd.add(rel);
            cmd.addAll(parsedArgs);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(runDir.toFile());
            Map<String, String> env = pb.environment();
            augmentPath(env);
            env.put("PYTHONUNBUFFERED", "1");
            env.put("PYTHONIOENCODING", "utf-8");
            env.put("DATA_DIR", dataDir.toAbsolutePath().toString());
            env.put("ZHIYE_APP_ID", app.id());
            if (userEnv != null) {
                for (var e : userEnv.entrySet()) {
                    String k = e.getKey() == null ? "" : e.getKey().trim();
                    if (k.isEmpty() || !k.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) continue;
                    if (BLOCKED_ENV_KEYS.contains(k.toUpperCase(Locale.ROOT))) continue;
                    if (e.getValue() != null) env.put(k, e.getValue());
                }
            }

            long t0 = System.currentTimeMillis();
            Process proc = pb.start();

            // 写入标准输入后关闭 stdin，防止脚本阻塞等待 EOF
            if (stdinText != null && !stdinText.isEmpty()) {
                try (OutputStream os = proc.getOutputStream()) {
                    os.write(stdinText.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {}
            } else {
                try { proc.getOutputStream().close(); } catch (IOException ignored) {}
            }

            ExecutorService ioPool = Executors.newFixedThreadPool(2);
            Future<String> outFut = ioPool.submit(() -> readCapped(proc.getInputStream(), MAX_OUTPUT_BYTES));
            Future<String> errFut = ioPool.submit(() -> readCapped(proc.getErrorStream(), MAX_OUTPUT_BYTES));

            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            boolean timedOut = !finished;
            if (timedOut) {
                try {
                    proc.descendants().forEach(ProcessHandle::destroyForcibly);
                    proc.destroyForcibly();
                    proc.waitFor(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            }
            int durationMs = (int) Math.min(Integer.MAX_VALUE, Math.max(1, System.currentTimeMillis() - t0));
            Integer exitCode = timedOut ? null : proc.exitValue();

            String stdout = "";
            String stderr = "";
            try { stdout = outFut.get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
            try { stderr = errFut.get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
            ioPool.shutdownNow();

            if (timedOut) {
                stderr = (stderr.isBlank() ? "" : stderr + "\n") + "[zhiye] 脚本运行超过 " + timeout + " 秒上限，已自动终止。";
            }

            List<Map<String, Object>> outputFiles = collectOutputFiles(runDir, beforeSnapshot);

            String storedOut = truncate(stdout, 16_000);
            String storedErr = truncate(stderr, 16_000);
            jdbc.sql("""
                    insert into script_run (id, app_id, workspace_id, version_id, entry_file, runtime,
                                            args_text, exit_code, timed_out, duration_ms, stdout_preview, stderr_preview)
                    values (?,?,?,?,?,?,?,?,?,?,?,?)
                    """).params(runId, app.id(), ws, ver.id(), rel, runtime,
                    argsText == null ? "" : truncate(argsText, 1000), exitCode, timedOut, durationMs, storedOut, storedErr).update();

            if (platformLogs != null) {
                platformLogs.info("runtime", "SCRIPT_RUN", ws, null, app.id(), null,
                        "在线运行脚本 " + rel + " (" + runtime + ") exit=" + (timedOut ? "TIMEOUT" : exitCode) + " · " + durationMs + "ms",
                        null, Map.of("runId", runId, "entryFile", rel, "runtime", runtime, "durationMs", durationMs,
                                "timedOut", timedOut, "exitCode", exitCode == null ? -1 : exitCode));
            }

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("id", runId);
            res.put("versionId", ver.id());
            res.put("entryFile", rel);
            res.put("runtime", runtime);
            res.put("args", argsText == null ? "" : argsText);
            res.put("exitCode", exitCode);
            res.put("timedOut", timedOut);
            res.put("durationMs", durationMs);
            res.put("stdout", stdout);
            res.put("stderr", stderr);
            res.put("outputFiles", outputFiles);
            res.put("createdAt", java.time.Instant.now().toString());
            return res;
        } finally {
            try { GitService.deleteRecursively(runDir); } catch (Exception ignored) {}
        }
    }

    private AppRow requireApp(String ws, String appId) {
        AppRow app = apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
        if (app.isSkill()) throw ApiException.validation("技能包请使用 SkillHub 或导出源码");
        return app;
    }

    private VersionRow resolveVersion(AppRow app, String versionId) {
        String vid = (versionId == null || versionId.isBlank()) ? app.currentVersionId() : versionId;
        if (vid == null || vid.isBlank()) return null;
        return versions.find(vid).filter(v -> v.appId().equals(app.id())).orElse(null);
    }

    private static String normalizeRelativePath(String raw) {
        if (raw == null || raw.isBlank()) throw ApiException.validation("请选择要运行的脚本文件");
        String p = raw.trim().replace('\\', '/');
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) throw ApiException.validation("非法脚本路径");
        for (String seg : p.split("/")) {
            if ("..".equals(seg) || ".git".equals(seg) || ".zhiye".equals(seg)) {
                throw ApiException.validation("非法脚本路径：" + raw);
            }
        }
        return p;
    }

    static List<String> parseArgs(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < raw.length()) {
                    cur.append(raw.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else if (c == '\\' && i + 1 < raw.length()) {
                cur.append(raw.charAt(++i));
                inToken = true;
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) out.add(cur.toString());
        return out;
    }

    private static int entryPriority(String p) {
        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.equals("main.py") || lower.equals("index.js") || lower.equals("index.mjs")) return 0;
        if (lower.equals("app.py") || lower.equals("run.py") || lower.equals("run.js") || lower.equals("main.js") || lower.equals("main.mjs")) return 1;
        if (!lower.contains("/")) return 2;
        if (lower.startsWith("scripts/")) return 3;
        return 4;
    }

    private static String resolvePythonBinary() {
        for (String candidate : List.of("/opt/homebrew/bin/python3", "/usr/local/bin/python3", "/usr/bin/python3")) {
            if (Files.isExecutable(Path.of(candidate))) return candidate;
        }
        return "python3";
    }

    private static void augmentPath(Map<String, String> env) {
        String cur = env.getOrDefault("PATH", "");
        if (File.pathSeparator.equals(";")) return;
        String extra = "/opt/homebrew/bin:/usr/local/bin";
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty()) {
            Path nvm = Path.of(home, ".nvm", "versions", "node");
            if (Files.isDirectory(nvm)) {
                try (var stream = Files.list(nvm)) {
                    Optional<Path> latest = stream.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).findFirst();
                    if (latest.isPresent()) extra = latest.get().resolve("bin") + ":" + extra;
                } catch (IOException ignored) {}
            }
        }
        env.put("PATH", extra + (cur.isEmpty() ? "" : File.pathSeparator + cur));
    }

    private static String readCapped(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        boolean truncated = false;
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (total < maxBytes) {
                int take = Math.min(n, maxBytes - total);
                bos.write(buf, 0, take);
                total += take;
                if (take < n) truncated = true;
            } else {
                truncated = true;
            }
        }
        String s = bos.toString(StandardCharsets.UTF_8);
        return truncated ? s + "\n… [输出超过 128 KB 已截断]" : s;
    }

    private static Map<String, Long> snapshotFiles(Path root) throws IOException {
        Map<String, Long> map = new HashMap<>();
        if (!Files.isDirectory(root)) return map;
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".git/") || rel.startsWith("__pycache__/") || rel.contains("/__pycache__/")) return;
                try { map.put(rel, Files.getLastModifiedTime(p).toMillis() ^ Files.size(p)); } catch (IOException ignored) {}
            });
        }
        return map;
    }

    private static List<Map<String, Object>> collectOutputFiles(Path root, Map<String, Long> before) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                if (out.size() >= 10) return;
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith(".git/") || rel.startsWith("__pycache__/") || rel.contains("/__pycache__/") || rel.endsWith(".pyc")) return;
                try {
                    long sig = Files.getLastModifiedTime(p).toMillis() ^ Files.size(p);
                    if (Objects.equals(before.get(rel), sig)) return;
                    long size = Files.size(p);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", rel);
                    item.put("size", size);
                    String lower = rel.toLowerCase(Locale.ROOT);
                    boolean textLike = lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".csv")
                            || lower.endsWith(".md") || lower.endsWith(".log") || lower.endsWith(".yaml")
                            || lower.endsWith(".yml") || lower.endsWith(".xml") || lower.endsWith(".html")
                            || lower.endsWith(".sql") || lower.endsWith(".tsv");
                    if (textLike && size <= MAX_PREVIEW_BYTES) {
                        item.put("preview", Files.readString(p, StandardCharsets.UTF_8));
                    }
                    out.add(item);
                } catch (IOException ignored) {}
            });
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("path"))));
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…（已截断）";
    }
}
