package com.tcl.csot.zhiye.service.cloud;

import com.tcl.csot.zhiye.util.Threads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.ServerPort;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.llm.UsageHook;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.repository.VersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 云函数（技术方案 20.7，轻量版）：functions/{name}.ts 跟着版本走，每次调用起一个 Deno 进程执行。
 * - Deno 权限：不能读写文件、读环境变量、起子进程；网络只允许访问织页自己的端口（/fnapi，带本次调用的临时令牌）
 * - 限制：10 秒、128 MB、返回 1 MB；每次调用写一条记录（控制台「函数」标签、修改任务附带最近报错）
 * - 函数里的 ctx.db 不受权限预设限制，但字段规则和状态流转照样生效（状态流转可以写 by: "function"）
 */
@Service
public class FunctionService {
    private static final Logger log = LoggerFactory.getLogger(FunctionService.class);
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,47}");
    static final int TIMEOUT_SECONDS = 10, MAX_OUTPUT = 1024 * 1024, TOKEN_TTL = 30;

    private final ZhiyeProperties props;
    private final ServerPort port;
    private final GitService git;
    private final VersionRepository versions;
    private final AppRepository apps;
    private final CloudService cloud;
    private final JdbcClient jdbc;
    private final UsageHook usage;
    private final String denoSetting;
    private volatile String deno;

    public FunctionService(ZhiyeProperties props, ServerPort port, GitService git, VersionRepository versions, AppRepository apps,
                           CloudService cloud, JdbcClient jdbc, UsageHook usage, @Value("${zhiye.functions.deno:}") String deno) {
        this.props = props;
        this.port = port;
        this.git = git;
        this.versions = versions;
        this.apps = apps;
        this.cloud = cloud;
        this.jdbc = jdbc;
        this.usage = usage;
        this.denoSetting = deno;
    }

    /** 找 deno：配置 → PATH → 常见安装位置 → 仓库本地工具目录；找不到返回 null */
    String deno() {
        if (deno != null) return deno;
        List<String> c = new ArrayList<>();
        if (denoSetting != null && !denoSetting.isBlank()) c.add(denoSetting);
        boolean win = java.io.File.pathSeparator.equals(";");
        for (String dir : Optional.ofNullable(System.getenv("PATH")).orElse("").split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            c.add(dir + "/deno");
            if (win) c.add(dir + "/deno.exe");
        }
        c.addAll(List.of("/opt/homebrew/bin/deno", "/usr/local/bin/deno", "/usr/bin/deno", System.getProperty("user.home") + "/.deno/bin/deno"));
        if (win) c.add(props.rootPath().resolve("runtime/tools/deno/deno.exe").toString());
        for (String p : c) if (Files.isExecutable(Path.of(p))) return deno = p;
        return null;
    }

    public boolean available() { return deno() != null; }

    // ---------- 调用 ----------

    public record Result(Object value, List<String> logs) {}

    /** 预览：当前版本；发布站点：线上版本 */
    public String versionFor(String appId, boolean published) {
        var app = apps.findById(appId).orElseThrow(() -> ApiException.notFound("应用"));
        String vid = published ? app.publishedVersionId() : app.currentVersionId();
        if (vid == null) throw ApiException.notFound("版本");
        return vid;
    }

    public Optional<String> source(String appId, String versionId, String name) {
        if (!NAME.matcher(name).matches()) return Optional.empty();
        var v = versions.find(versionId).orElseThrow(() -> ApiException.notFound("版本"));
        try {
            return git.readFile(appId, v.gitCommit(), "functions/" + name + ".ts").map(b -> new String(b, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public boolean exists(String appId, String versionId, String name) { return source(appId, versionId, name).isPresent(); }

    public Result call(String appId, String versionId, String name, JsonNode args, CloudPolicy.Who caller) {
        String code = source(appId, versionId, name).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FN_NOT_FOUND", "没有这个云函数：" + name));
        String uid = caller == null ? null : caller.userId();
        usage.before(appId, uid, "fn");
        long t0 = System.currentTimeMillis();
        try {
            Result r = run(appId, name, code, args, caller, false);
            record(appId, name, versionId, true, (int) (System.currentTimeMillis() - t0), null, r.logs(), caller);
            usage.after(appId, uid, "fn", 1);
            return r;
        } catch (FnError e) {
            record(appId, name, versionId, false, (int) (System.currentTimeMillis() - t0), e.getMessage() + (e.stack == null ? "" : "\n" + e.stack), e.logs, caller);
            throw new ApiException(e.status, e.code, "云函数 " + name + " 出错：" + e.getMessage());
        }
    }

    /** verify 用：只加载模块、确认有默认导出，不执行处理函数；返回错误说明，没问题返回 null */
    public String check(String name, String code) {
        if (!available()) return null;
        try {
            run("check", name, code, null, null, true);
            return null;
        } catch (FnError e) {
            return e.getMessage();
        }
    }

    static final class FnError extends RuntimeException {
        final HttpStatus status;
        final String code, stack;
        final List<String> logs;
        FnError(HttpStatus status, String code, String message, String stack, List<String> logs) {
            super(message);
            this.status = status;
            this.code = code;
            this.stack = stack;
            this.logs = logs == null ? List.of() : logs;
        }
    }

    private Result run(String appId, String name, String code, JsonNode args, CloudPolicy.Who caller, boolean checkOnly) {
        String bin = deno();
        if (bin == null) throw new FnError(HttpStatus.SERVICE_UNAVAILABLE, "FN_RUNTIME_UNAVAILABLE", "当前环境没有安装云函数运行服务（Deno）", null, null);
        Path host = props.rootPath().resolve("packages/fn-runtime/host.ts");
        Path denoDir = props.runtimePath().resolve("deno-cache");
        ObjectNode in = Json.obj();
        in.put("codeB64", Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8)));
        in.put("name", name);
        in.set("args", args == null || !args.isObject() ? Json.obj() : args);
        in.set("user", caller == null || caller.anonymous() ? null : Json.M.valueToTree(caller.view()));
        in.put("api", "http://127.0.0.1:" + port.port() + "/fnapi");
        in.put("token", checkOnly ? "" : mint(appId, name, caller));
        in.put("checkOnly", checkOnly);
        ProcessBuilder pb = new ProcessBuilder(bin, "run", "--quiet", "--no-prompt", "--no-config", "--no-lock", "--no-npm", "--no-remote",
                "--allow-net=127.0.0.1:" + port.port(), "--v8-flags=--max-old-space-size=128", host.toString());
        pb.environment().clear();
        pb.environment().put("DENO_DIR", denoDir.toString());
        pb.environment().put("NO_COLOR", "1");
        pb.redirectErrorStream(true);
        Process p = null;
        try {
            Files.createDirectories(denoDir);
            p = pb.start();
            try (var os = p.getOutputStream()) { os.write(Json.write(in).getBytes(StandardCharsets.UTF_8)); }
            Process proc = p;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread reader = Threads.start(() -> {
                try (InputStream is = proc.getInputStream()) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = is.read(b)) > 0) { if (buf.size() < MAX_OUTPUT + 4096) buf.write(b, 0, n); }
                } catch (Exception ignored) { }
            });
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new FnError(HttpStatus.GATEWAY_TIMEOUT, "FN_TIMEOUT", "运行超过 " + TIMEOUT_SECONDS + " 秒，已停止", null, null);
            }
            reader.join(2000);
            if (buf.size() > MAX_OUTPUT) throw new FnError(HttpStatus.BAD_GATEWAY, "FN_OUTPUT_TOO_LARGE", "返回内容超过 1 MB", null, null);
            String out = buf.toString(StandardCharsets.UTF_8);
            int i = out.lastIndexOf("__ZHIYE_FN__");
            if (i < 0) {
                String tail = out.length() > 500 ? out.substring(out.length() - 500) : out;
                throw new FnError(HttpStatus.BAD_GATEWAY, "FN_CRASHED", tail.contains("out of memory") || tail.contains("heap") ? "内存超过 128 MB" : "运行异常退出：" + tail.trim(), null, null);
            }
            JsonNode r = Json.read(out.substring(i + "__ZHIYE_FN__".length()).trim());
            List<String> logs = new ArrayList<>();
            r.path("logs").forEach(x -> logs.add(x.asText()));
            if (!r.path("ok").asBoolean(false)) {
                JsonNode e = r.path("error");
                throw new FnError(HttpStatus.BAD_REQUEST, "FN_ERROR", e.path("message").asText("未知错误"), e.path("stack").asText(null), logs);
            }
            return new Result(Json.M.treeToValue(r.get("result"), Object.class), logs);
        } catch (FnError e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FnError(HttpStatus.BAD_GATEWAY, "FN_CRASHED", "调用被中断", null, null);
        } catch (Exception e) {
            log.warn("云函数 {} 运行失败", name, e);
            throw new FnError(HttpStatus.BAD_GATEWAY, "FN_CRASHED", "运行失败：" + e.getMessage(), null, null);
        } finally {
            if (p != null && p.isAlive()) p.destroyForcibly();
        }
    }

    // ---------- 临时令牌：只在本次调用里有效，代表「某应用的某个函数，替某个调用者」 ----------

    public record FnToken(String appId, String fn, CloudPolicy.Who who) {}

    String mint(String appId, String fn, CloudPolicy.Who caller) {
        ObjectNode t = Json.obj();
        t.put("a", appId);
        t.put("f", fn);
        t.put("e", System.currentTimeMillis() / 1000 + TOKEN_TTL);
        if (caller != null && !caller.anonymous()) {
            t.put("u", caller.userId());
            t.put("l", caller.loginName());
            t.put("n", caller.name());
            t.put("ad", caller.admin());
        }
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(t).getBytes(StandardCharsets.UTF_8));
        return "zf." + payload + "." + cloud.sign("fn:" + payload);
    }

    public FnToken verify(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3 || !"zf".equals(parts[0]) || !cloud.sign("fn:" + parts[1]).equals(parts[2]))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "FN_TOKEN_INVALID", "云函数令牌无效");
        JsonNode t = Json.read(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        if (t.path("e").asLong() < System.currentTimeMillis() / 1000) throw new ApiException(HttpStatus.UNAUTHORIZED, "FN_TOKEN_EXPIRED", "云函数令牌已过期");
        CloudPolicy.Who who = t.hasNonNull("u")
                ? new CloudPolicy.Who(t.get("u").asText(), t.path("l").asText(null), t.path("n").asText(null), t.path("ad").asBoolean(false))
                : CloudPolicy.Who.ANON;
        return new FnToken(t.path("a").asText(), t.path("f").asText(), who.viaFunction(t.path("f").asText()));
    }

    // ---------- 记录 ----------

    private void record(String appId, String name, String versionId, boolean ok, int ms, String error, List<String> logs, CloudPolicy.Who caller) {
        try {
            jdbc.sql("insert into app_fn_log (app_id, name, version_id, ok, duration_ms, error, logs, caller) values (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(appId, name, versionId, ok, ms, error == null ? null : truncate(error, 4000), logs == null || logs.isEmpty() ? null : truncate(String.join("\n", logs), 8000),
                            caller == null || caller.anonymous() ? null : caller.loginName())
                    .update();
            if (Math.random() < 0.02) jdbc.sql("delete from app_fn_log where created_at < now() - interval '7 days'").update();
        } catch (Exception e) {
            log.warn("记录云函数调用失败：{}", e.toString());
        }
    }

    private static String truncate(String s, int n) { return s.length() > n ? s.substring(0, n) + "…" : s; }

    /** 控制台：函数列表（当前版本里的 functions/*.ts）与 24 小时调用统计、最近记录 */
    public Map<String, Object> overview(String appId) {
        var app = apps.findById(appId).orElseThrow();
        List<String> names = new ArrayList<>();
        if (app.currentVersionId() != null) {
            try {
                var v = versions.find(app.currentVersionId()).orElseThrow();
                for (var f : git.listFiles(appId, v.gitCommit())) {
                    String p = f.path();
                    if (p.startsWith("functions/") && p.endsWith(".ts") && !p.endsWith(".d.ts")) {
                        String n = p.substring("functions/".length(), p.length() - 3);
                        if (NAME.matcher(n).matches()) names.add(n);
                    }
                }
            } catch (Exception e) {
                log.warn("读取函数列表失败：{}", e.toString());
            }
        }
        Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
        for (String n : names) stats.put(n, new LinkedHashMap<>(Map.of("name", n, "calls", 0L, "errors", 0L)));
        jdbc.sql("select name, count(*), count(*) filter (where not ok) from app_fn_log where app_id = ? and created_at > now() - interval '24 hours' group by name")
                .param(appId).query((rs, i) -> {
                    var m = stats.computeIfAbsent(rs.getString(1), k -> new LinkedHashMap<>(Map.of("name", k, "calls", 0L, "errors", 0L)));
                    m.put("calls", rs.getLong(2));
                    m.put("errors", rs.getLong(3));
                    return null;
                }).list();
        List<Map<String, Object>> logs = jdbc.sql("""
                select name, ok, duration_ms, error, logs, caller, created_at from app_fn_log
                where app_id = ? order by created_at desc limit 50""").param(appId).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", rs.getString(1));
            m.put("ok", rs.getBoolean(2));
            m.put("durationMs", rs.getInt(3));
            m.put("error", rs.getString(4));
            m.put("logs", rs.getString(5));
            m.put("caller", rs.getString(6));
            m.put("at", rs.getObject(7, java.time.OffsetDateTime.class).toString());
            return m;
        }).list();
        return Map.of("available", available(), "functions", new ArrayList<>(stats.values()), "logs", logs);
    }

    /** 修改任务附带：最近 24 小时的函数报错摘要（最多 5 条），没有返回空串 */
    public String recentErrors(String appId) {
        List<String> rows = jdbc.sql("""
                select name, error, created_at from app_fn_log where app_id = ? and not ok and created_at > now() - interval '24 hours'
                order by created_at desc limit 5""").param(appId)
                .query((rs, i) -> "- functions/" + rs.getString(1) + ".ts（" + rs.getObject(3, java.time.OffsetDateTime.class).toLocalDateTime().withNano(0) + "）：" + truncate(String.valueOf(rs.getString(2)), 600))
                .list();
        if (rows.isEmpty()) return "";
        return "\n\n## 云函数最近的报错（最近 24 小时，最多 5 条）\n用户说「某个按钮报错 / 提交失败」时，先对照这些报错修对应的函数：\n" + String.join("\n", rows) + "\n";
    }
}
