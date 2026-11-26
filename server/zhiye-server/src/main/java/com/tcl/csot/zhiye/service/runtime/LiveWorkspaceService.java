package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Threads;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 持久化活体开发环境引擎（Phase 2: Live Dev Sandbox）：
 * - 为每个应用维护持久化工作区 runtime/live-workspaces/{appId}/，跨任务保留 node_modules/.venv/构建缓存与 /data
 * - 支持一键启动/唤醒热更新开发服务（Vite HMR / 全栈后端）、闲置自动休眠（保留热缓存）、秒级重启与重置
 * - 提供受控安全 Web 终端（严格拦截越权路径、外部联网、sudo、.env 泄露，仅允许本应用工作区诊断与本机端口探活）
 * - 提供实时 SSE Dev 日志流与 Snapshot-to-Deploy 缓存桥，让部署层直接复用活体环境已验证的依赖层与构建产物
 */
@Service
public class LiveWorkspaceService {
    private static final Logger log = LoggerFactory.getLogger(LiveWorkspaceService.class);
    private static final int MAX_LOG_LINES = 300;
    private static final Pattern FORBIDDEN_CMD = Pattern.compile(
            "(?i)(^|[;&|`($\\s])(sudo|su|doas|chown|chroot|systemctl|launchctl|osascript|open|ssh|scp|sftp|nc|ncat|netcat|telnet|wget|apt|apt-get|yum|dnf|brew|docker|kubectl|killall|pkill|shutdown|reboot)\\b");
    private static final Pattern ENV_FILE_ACCESS = Pattern.compile("(?i)(^|[/\\s'\"])\\.env(\\.[A-Za-z0-9_-]+)?($|[\\s'\"/])");
    private static final Pattern EXTERNAL_URL = Pattern.compile("(?i)https?://(?!127\\.0\\.0\\.1[:/]|localhost[:/])");

    public record WorkspaceRow(
            String appId, String workspaceId, String status, String projectKind, String workspaceDir,
            Integer devPort, Integer backendPort, Long devPid, Long backendPid,
            String depsHash, String snapshotCommit, boolean snapshotReady, String lastError,
            int idleSleepMin, Instant lastActiveAt, Instant createdAt, Instant updatedAt
    ) {
        static WorkspaceRow map(ResultSet rs, int i) throws SQLException {
            return new WorkspaceRow(
                    rs.getString("app_id"), rs.getString("workspace_id"), rs.getString("status"),
                    rs.getString("project_kind"), rs.getString("workspace_dir"),
                    rs.getObject("dev_port", Integer.class), rs.getObject("backend_port", Integer.class),
                    rs.getObject("dev_pid", Long.class), rs.getObject("backend_pid", Long.class),
                    rs.getString("deps_hash"), rs.getString("snapshot_commit"), rs.getBoolean("snapshot_ready"),
                    rs.getString("last_error"), rs.getInt("idle_sleep_min"),
                    Db.ts(rs, "last_active_at"), Db.ts(rs, "created_at"), Db.ts(rs, "updated_at")
            );
        }
    }

    public record WarmSnapshot(Path workspaceDir, Path nodeModulesDir, Path distDir, String depsHash, String commit) {}

    private static final class LiveProc {
        final Process process;
        final int port;
        final long pid;
        final String mode;
        LiveProc(Process process, int port, String mode) {
            this.process = process;
            this.port = port;
            this.pid = process.pid();
            this.mode = mode;
        }
    }

    private final JdbcClient jdbc;
    private final AppRepository apps;
    private final VersionRepository versions;
    private final GitService git;
    private final ZhiyeProperties props;

    private final ConcurrentHashMap<String, Object> appLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LiveProc> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<String>> logBuffers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> logSubscribers = new ConcurrentHashMap<>();

    public LiveWorkspaceService(JdbcClient jdbc, AppRepository apps, VersionRepository versions,
                                GitService git, ZhiyeProperties props) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.versions = versions;
        this.git = git;
        this.props = props;
    }

    private Object lockFor(String appId) { return appLocks.computeIfAbsent(appId, k -> new Object()); }

    public Path liveRoot(String appId) {
        return props.runtimePath().resolve("live-workspaces").resolve(appId);
    }

    public Optional<WorkspaceRow> find(String appId) {
        return jdbc.sql("select * from app_live_workspace where app_id = ?").param(appId).query(WorkspaceRow::map).optional();
    }

    public Map<String, Object> status(String ws, String appId) {
        AppRow app = requireApp(ws, appId);
        WorkspaceRow row = find(appId).orElse(null);
        LiveProc lp = running.get(appId);
        boolean alive = lp != null && lp.process.isAlive();
        boolean processExpected = row != null && row.devPid() != null;
        String effStatus = alive ? "running" : (row == null ? "idle" : ("running".equals(row.status()) && processExpected ? "sleeping" : row.status()));
        Path dir = liveRoot(appId);
        boolean hasNodeModules = Files.isDirectory(dir.resolve("node_modules"));
        boolean hasDist = Files.isDirectory(dir.resolve("dist"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appId", appId);
        m.put("projectKind", app.projectKind());
        m.put("status", effStatus);
        m.put("alive", alive);
        m.put("devPort", alive ? Integer.valueOf(lp.port) : (row != null ? row.devPort() : null));
        m.put("devMode", alive ? lp.mode : null);
        m.put("devUrl", alive && lp.port > 0 ? "http://127.0.0.1:" + lp.port : null);
        m.put("depsHash", row != null ? row.depsHash() : null);
        m.put("hasNodeModules", hasNodeModules);
        m.put("hasDist", hasDist);
        m.put("snapshotCommit", row != null ? row.snapshotCommit() : null);
        m.put("snapshotReady", row != null && row.snapshotReady());
        m.put("idleSleepMin", row != null ? row.idleSleepMin() : 20);
        m.put("lastError", row != null ? row.lastError() : null);
        m.put("lastActiveAt", row != null ? Db.iso(row.lastActiveAt()) : null);
        m.put("logs", recentLogs(appId));
        return m;
    }

    /** 启动或唤醒应用的持久化活体开发环境（自动同步最新提交并保留热依赖缓存）。 */
    public Map<String, Object> startOrWake(String ws, String appId) throws Exception {
        AppRow app = requireApp(ws, appId);
        synchronized (lockFor(appId)) {
            LiveProc cur = running.get(appId);
            if (cur != null && cur.process.isAlive()) {
                touch(appId);
                return status(ws, appId);
            }
            Path dir = syncWorkspaceLocked(app, currentCommit(app));
            String depsHash = computeDepsHash(dir);
            String commit = currentCommit(app);

            try {
                LiveProc proc = spawnDevProcess(app, dir);
                if (proc != null) {
                    running.put(appId, proc);
                    upsertRow(app, "running", dir, proc.port, null, proc.pid, null, depsHash, commit, Files.isDirectory(dir.resolve("dist")), null);
                    appendLog(appId, "[live-sandbox] 活体开发环境已启动 mode=" + proc.mode + " port=" + proc.port + " depsHash=" + shortHash(depsHash));
                } else {
                    // 静态/脚本/技能项目：工作区常驻就绪，支持直接终端诊断
                    upsertRow(app, "running", dir, null, null, null, null, depsHash, commit, true, null);
                    appendLog(appId, "[live-sandbox] 持久化工作区已就绪（" + app.projectKind() + " 模式，支持终端命令与实时同步）");
                }
            } catch (Exception e) {
                String msg = Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
                upsertRow(app, "error", dir, null, null, null, null, depsHash, commit, false, msg);
                appendLog(appId, "[live-sandbox] 启动失败：" + msg);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "LIVE_SANDBOX_FAILED", "活体开发环境启动失败：" + msg);
            }
            return status(ws, appId);
        }
    }

    /** 休眠活体环境：停止常驻进程，但完整保留磁盘工作区与 node_modules/.venv 缓存供下次秒级唤醒与 Snapshot-to-Deploy。 */
    public Map<String, Object> sleep(String ws, String appId) {
        requireApp(ws, appId);
        sleepInternal(appId, "手动休眠（保留依赖与构建缓存）");
        return status(ws, appId);
    }

    /** 重置活体环境：停止进程并按当前版本重新同步干净目录。 */
    public Map<String, Object> reset(String ws, String appId) throws Exception {
        AppRow app = requireApp(ws, appId);
        synchronized (lockFor(appId)) {
            stopProcessLocked(appId);
            Path dir = liveRoot(appId);
            GitService.deleteRecursively(dir);
            Path synced = syncWorkspaceLocked(app, currentCommit(app));
            String depsHash = computeDepsHash(synced);
            upsertRow(app, "sleeping", synced, null, null, null, null, depsHash, currentCommit(app), false, null);
            appendLog(appId, "[live-sandbox] 已重置持久化工作区至最新提交 " + shortHash(currentCommit(app)));
        }
        return status(ws, appId);
    }

    /** 受控安全 Web 终端：在应用持久化工作区内执行诊断命令，返回 stdout/stderr/退出码与耗时。 */
    public Map<String, Object> execCommand(String ws, String appId, String rawCmd) throws Exception {
        AppRow app = requireApp(ws, appId);
        String cmd = rawCmd == null ? "" : rawCmd.trim();
        if (cmd.isEmpty()) throw ApiException.validation("请输入要执行的命令");
        if (cmd.length() > 500) throw ApiException.validation("单条命令最长 500 字符");

        String violation = validateTerminalCommand(cmd, running.get(appId));
        if (violation != null) throw new ApiException(HttpStatus.FORBIDDEN, "TERMINAL_BLOCKED", violation);

        Path dir;
        synchronized (lockFor(appId)) {
            dir = liveRoot(appId);
            if (!Files.isDirectory(dir)) {
                dir = syncWorkspaceLocked(app, currentCommit(app));
                upsertRow(app, "sleeping", dir, null, null, null, null, computeDepsHash(dir), currentCommit(app), Files.isDirectory(dir.resolve("dist")), null);
            }
        }
        touch(appId);
        long t0 = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.directory(dir.toFile());
        Map<String, String> env = pb.environment();
        env.put("NO_COLOR", "1");
        env.put("CI", "1");
        LiveProc lp = running.get(appId);
        if (lp != null && lp.process.isAlive()) env.put("DEV_PORT", String.valueOf(lp.port));
        // 清理敏感平台环境变量
        env.keySet().removeIf(k -> k.startsWith("ZHIYE_") || k.contains("SECRET") || k.contains("TOKEN") || k.contains("PASSWORD") || k.contains("API_KEY"));

        Process proc = pb.start();
        CompletableFuture<String> outF = CompletableFuture.supplyAsync(() -> readStreamCapped(proc.getInputStream(), 32 * 1024));
        CompletableFuture<String> errF = CompletableFuture.supplyAsync(() -> readStreamCapped(proc.getErrorStream(), 16 * 1024));
        boolean finished = proc.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
        }
        String stdout = outF.get(2, TimeUnit.SECONDS);
        String stderr = errF.get(2, TimeUnit.SECONDS);
        int exitCode = finished ? proc.exitValue() : 124;
        long dur = System.currentTimeMillis() - t0;
        appendLog(appId, "$ " + cmd + "  (exit=" + exitCode + ", " + dur + "ms)");
        if (!stdout.isBlank()) appendLog(appId, stdout.length() > 400 ? stdout.substring(0, 400) + "…" : stdout);
        if (!stderr.isBlank()) appendLog(appId, "[stderr] " + (stderr.length() > 300 ? stderr.substring(0, 300) + "…" : stderr));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", finished && exitCode == 0);
        out.put("exitCode", exitCode);
        out.put("timedOut", !finished);
        out.put("stdout", stdout);
        out.put("stderr", stderr);
        out.put("durationMs", dur);
        out.put("command", cmd);
        return out;
    }

    /** 实时 SSE Dev 日志流订阅。 */
    public SseEmitter streamLogs(String ws, String appId) {
        requireApp(ws, appId);
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        var subs = logSubscribers.computeIfAbsent(appId, k -> new CopyOnWriteArrayList<>());
        subs.add(emitter);
        Runnable cleanup = () -> subs.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            for (String line : recentLogs(appId)) {
                emitter.send(SseEmitter.event().name("log").data(Map.of("line", line)));
            }
        } catch (Exception e) {
            cleanup.run();
        }
        return emitter;
    }

    /**
     * TaskExecutor 钩子（热复用）：若持久化活体工作区已有与当前项目匹配的依赖层，直接将其 node_modules 链接给任务工作区加速冷启动。
     */
    public boolean attachWarmDepsToTask(String appId, Path taskWorkspace) {
        Path liveDir = liveRoot(appId);
        Path liveNm = liveDir.resolve("node_modules");
        Path targetNm = taskWorkspace.resolve("node_modules");
        if (!Files.isDirectory(liveNm) || Files.exists(targetNm, LinkOption.NOFOLLOW_LINKS)) return false;
        String liveHash = computeDepsHash(liveDir);
        String taskHash = computeDepsHash(taskWorkspace);
        if (liveHash.isBlank() || !liveHash.equals(taskHash)) return false;
        try {
            Files.createSymbolicLink(targetNm, liveNm.toAbsolutePath());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * TaskExecutor 钩子（Snapshot-to-Deploy）：任务提交新版本后同步更新持久化活体工作区（含构建产物与热依赖），
     * 供活体热更新预览与后续部署秒级命中缓存。
     */
    public void syncAfterTask(AppRow app, Path taskWorkspace, String commit) {
        if (app == null || commit == null || commit.isBlank()) return;
        try {
            synchronized (lockFor(app.id())) {
                Path liveDir = syncWorkspaceLocked(app, commit);
                // 同步任务产物 dist/（若有）到活体目录以支撑 Snapshot-to-Deploy
                Path taskDist = taskWorkspace != null ? taskWorkspace.resolve("dist") : null;
                if (taskDist != null && Files.isDirectory(taskDist)) {
                    Path liveDist = liveDir.resolve("dist");
                    GitService.deleteRecursively(liveDist);
                    copyDir(taskDist, liveDist);
                }
                // 若任务里通过 install_package 装了新包（真实目录非符号链接），保留到活体目录
                Path taskNm = taskWorkspace != null ? taskWorkspace.resolve("node_modules") : null;
                if (taskNm != null && Files.isDirectory(taskNm, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(taskNm)) {
                    Path liveNm = liveDir.resolve("node_modules");
                    if (Files.isSymbolicLink(liveNm)) Files.delete(liveNm);
                    else GitService.deleteRecursively(liveNm);
                    try {
                        Files.move(taskNm, liveNm, StandardCopyOption.REPLACE_EXISTING);
                    } catch (FileSystemException e) {
                        copyDir(taskNm, liveNm);
                        GitService.deleteRecursively(taskNm);
                    }
                }
                String depsHash = computeDepsHash(liveDir);
                LiveProc lp = running.get(app.id());
                boolean alive = lp != null && lp.process.isAlive();
                upsertRow(app, alive ? "running" : "sleeping", liveDir,
                        alive ? lp.port : null, null, alive ? lp.pid : null, null,
                        depsHash, commit, Files.isDirectory(liveDir.resolve("dist")) || Files.isDirectory(liveDir.resolve("node_modules")), null);
                appendLog(app.id(), "[snapshot] 已同步版本提交 " + shortHash(commit) + "（depsHash=" + shortHash(depsHash) + "）");
            }
        } catch (Exception e) {
            log.warn("同步活体工作区快照失败 app={}: {}", app.id(), e.getMessage());
        }
    }

    /** 部署层查询可复用的活体快照（匹配相同 depsHash 时直接复用 node_modules / dist，实现 Snapshot-to-Deploy）。 */
    public Optional<WarmSnapshot> findWarmSnapshot(String appId, String expectedDepsHash) {
        String baseId = appId != null && appId.endsWith("-prev") ? appId.substring(0, appId.length() - 5) : appId;
        if (baseId == null || baseId.isBlank()) return Optional.empty();
        Optional<WorkspaceRow> row = find(baseId);
        if (row.isEmpty()) return Optional.empty();
        Path dir = liveRoot(baseId);
        if (!Files.isDirectory(dir)) return Optional.empty();
        String actualHash = computeDepsHash(dir);
        if (expectedDepsHash != null && !expectedDepsHash.isBlank() && !expectedDepsHash.equals(actualHash)) {
            return Optional.empty();
        }
        Path nm = dir.resolve("node_modules");
        Path dist = dir.resolve("dist");
        if (!Files.isDirectory(nm) && !Files.isDirectory(dist)) return Optional.empty();
        return Optional.of(new WarmSnapshot(dir, Files.isDirectory(nm) ? nm : null, Files.isDirectory(dist) ? dist : null,
                actualHash, row.get().snapshotCommit()));
    }

    /** 计算目录依赖清单文件的稳定哈希，用于判断依赖层缓存是否可安全复用。 */
    public static String computeDepsHash(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return "";
        StringBuilder sb = new StringBuilder();
        for (String rel : List.of("package.json", "package-lock.json", "requirements.txt", "pyproject.toml", "go.mod", "pom.xml")) {
            Path p = dir.resolve(rel);
            if (Files.isRegularFile(p)) {
                try {
                    sb.append(rel).append(':').append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
                } catch (Exception ignored) {}
            }
        }
        if (sb.isEmpty()) return "";
        return Ids.sha256(sb.toString()).substring(0, 24);
    }

    static String validateTerminalCommand(String cmd, LiveProc liveProc) {
        if (cmd.contains("\n") || cmd.contains("\r")) return "终端一次只允许执行单行命令";
        if (FORBIDDEN_CMD.matcher(cmd).find()) return "安全拦截：不允许执行提权、系统管理或外部网络命令";
        if (ENV_FILE_ACCESS.matcher(cmd).find()) return "安全拦截：禁止读取或修改 .env 环境变量文件";
        if (cmd.contains("../") || cmd.contains("..\\") || cmd.matches(".*\\bcd\\s+(/|~).*")) return "安全拦截：禁止访问应用工作区以外的路径";
        if (cmd.matches(".*(?<![>&])&(?!&).*")) return "安全拦截：禁止在终端启动后台常驻进程（请使用活体环境启动按钮）";
        if (EXTERNAL_URL.matcher(cmd).find()) return "安全拦截：curl 仅允许探活本机 127.0.0.1 / localhost 活体服务端口";
        if (cmd.toLowerCase(Locale.ROOT).contains("rm ") && (cmd.contains(" /") || cmd.contains(" *"))) {
            return "安全拦截：禁止执行高危批量删除命令";
        }
        // curl 访问本机时限制到当前活体端口
        if (Pattern.compile("(?i)\\bcurl\\b").matcher(cmd).find()) {
            Matcher m = Pattern.compile("https?://(?:127\\.0\\.0\\.1|localhost):(\\d+)").matcher(cmd);
            if (m.find() && liveProc != null && liveProc.process.isAlive()) {
                int p = Integer.parseInt(m.group(1));
                if (p != liveProc.port) return "安全拦截：仅允许探测当前应用的活体端口 " + liveProc.port;
            }
        }
        return null;
    }

    @Scheduled(fixedDelay = 60_000)
    public void sleepIdleWorkspaces() {
        try {
            List<WorkspaceRow> rows = jdbc.sql("select * from app_live_workspace where status = 'running'")
                    .query(WorkspaceRow::map).list();
            Instant now = Instant.now();
            for (WorkspaceRow r : rows) {
                int idleMin = Math.max(5, r.idleSleepMin());
                Instant last = r.lastActiveAt() != null ? r.lastActiveAt() : r.updatedAt();
                if (last != null && last.plusSeconds(idleMin * 60L).isBefore(now)) {
                    sleepInternal(r.appId(), "闲置超过 " + idleMin + " 分钟自动休眠（保留依赖缓存）");
                }
            }
        } catch (Exception e) {
            log.debug("活体工作区闲置巡检跳过：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdownAll() {
        for (String appId : List.copyOf(running.keySet())) {
            sleepInternal(appId, "服务停机清理");
        }
    }

    private void sleepInternal(String appId, String reason) {
        synchronized (lockFor(appId)) {
            stopProcessLocked(appId);
            jdbc.sql("update app_live_workspace set status = 'sleeping', dev_port = null, dev_pid = null, updated_at = now() where app_id = ?")
                    .param(appId).update();
            appendLog(appId, "[live-sandbox] " + reason);
        }
    }

    private void stopProcessLocked(String appId) {
        LiveProc cur = running.remove(appId);
        if (cur == null) return;
        try {
            cur.process.descendants().forEach(ProcessHandle::destroy);
            cur.process.destroy();
            if (!cur.process.waitFor(2, TimeUnit.SECONDS)) {
                cur.process.descendants().forEach(ProcessHandle::destroyForcibly);
                cur.process.destroyForcibly();
            }
        } catch (Exception ignored) {}
    }

    private Path syncWorkspaceLocked(AppRow app, String commit) throws Exception {
        Path dir = liveRoot(app.id());
        Files.createDirectories(dir.getParent());
        // 若已有持久化目录，保留 node_modules / data / .venv，仅更新源码文件
        Path savedNm = null;
        Path savedData = null;
        String prevHash = Files.isDirectory(dir) ? computeDepsHash(dir) : "";
        Path tmpSave = null;
        try {
            if (Files.isDirectory(dir)) {
                tmpSave = Files.createTempDirectory("zhiye-live-keep-");
                Path nm = dir.resolve("node_modules");
                if (Files.isDirectory(nm, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(nm)) {
                    savedNm = tmpSave.resolve("node_modules");
                    Files.move(nm, savedNm);
                }
                Path data = dir.resolve("data");
                if (Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)) {
                    savedData = tmpSave.resolve("data");
                    Files.move(data, savedData);
                }
                GitService.deleteRecursively(dir);
            }
            git.checkout(app.id(), commit, dir, app.projectKind());
            if (savedData != null) Files.move(savedData, dir.resolve("data"));
            else Files.createDirectories(dir.resolve("data"));

            String newHash = computeDepsHash(dir);
            if (savedNm != null && !prevHash.isBlank() && prevHash.equals(newHash)) {
                Files.move(savedNm, dir.resolve("node_modules"));
            } else {
                // 回落链接平台预装模板依赖缓存
                attachTemplateCacheIfPresent(app, dir);
            }
            return dir;
        } finally {
            if (tmpSave != null) GitService.deleteRecursively(tmpSave);
        }
    }

    private void attachTemplateCacheIfPresent(AppRow app, Path dir) {
        try {
            Path link = dir.resolve("node_modules");
            if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                Path cache = null;
                if (app.isVue()) cache = props.runtimePath().resolve("vue-template-cache/node_modules");
                else if (app.isTemplateLike()) cache = props.runtimePath().resolve("template-cache/node_modules");
                else if (app.isService() && Files.isRegularFile(dir.resolve("server/index.mjs"))) {
                    cache = props.runtimePath().resolve("fullstack-template-cache/node_modules");
                }
                if (cache != null && Files.isDirectory(cache)) Files.createSymbolicLink(link, cache);
            }
            // 动态扫描前后端分目录项目（frontend/、web/、client/、ui/），按 package.json 实际框架软链本地预热缓存
            for (String sub : List.of("frontend", "web", "client", "ui")) {
                Path frontDir = dir.resolve(sub);
                Path pkg = frontDir.resolve("package.json");
                Path frontLink = frontDir.resolve("node_modules");
                if (app.isService() && Files.isRegularFile(pkg) && !Files.exists(frontLink, LinkOption.NOFOLLOW_LINKS)) {
                    String rawPkg = Files.readString(pkg, StandardCharsets.UTF_8);
                    Path targetCache = rawPkg.contains("\"vue\"")
                            ? props.runtimePath().resolve("vue-template-cache/node_modules")
                            : props.runtimePath().resolve("template-cache/node_modules");
                    if (Files.isDirectory(targetCache)) Files.createSymbolicLink(frontLink, targetCache);
                }
            }
        } catch (Exception ignored) {}
    }

    private String readZhiyeYamlDevCommand(Path dir) {
        for (String name : List.of("zhiye.yaml", "zhiye.yml", ".zhiye.yaml")) {
            Path f = dir.resolve(name);
            if (!Files.isRegularFile(f)) continue;
            try {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                boolean inDev = false;
                for (String raw : lines) {
                    String trimmed = raw.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    int indent = raw.indexOf(trimmed);
                    if (indent == 0) {
                        inDev = trimmed.startsWith("dev:");
                        if (inDev && trimmed.length() > 4) {
                            String inline = trimmed.substring(4).trim().replaceAll("^['\"]|['\"]$", "");
                            if (!inline.isEmpty()) return inline;
                        }
                    } else if (inDev && trimmed.startsWith("command:")) {
                        return trimmed.substring("command:".length()).trim().replaceAll("^['\"]|['\"]$", "");
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private LiveProc spawnDevProcess(AppRow app, Path dir) throws Exception {
        String kind = app.projectKind();
        if ("skill".equals(kind) || "script".equals(kind) || "static".equals(kind)) return null;

        int port = freePort();
        ProcessBuilder pb = null;
        String mode = null;
        Path workDir = dir;

        // 0. 优先读取项目自声明的 zhiye.yaml dev.command（通过安全校验）
        String customDev = readZhiyeYamlDevCommand(dir);
        if (customDev != null && !customDev.isBlank()
                && !FORBIDDEN_CMD.matcher(customDev).find()
                && !ENV_FILE_ACCESS.matcher(customDev).find()
                && !EXTERNAL_URL.matcher(customDev).find()) {
            pb = new ProcessBuilder("sh", "-c", customDev.replace("${PORT}", String.valueOf(port)));
            mode = "zhiye-yaml-dev";
        }

        // 1. 动态探测项目真实开发入口（不写死模板名或子目录名）
        if (pb == null && "service".equals(kind) && Files.isRegularFile(dir.resolve("server/index.mjs"))) {
            pb = new ProcessBuilder(props.nodeBinary(), "server/index.mjs");
            mode = "fullstack-node";
        } else if (pb == null && Files.isRegularFile(dir.resolve("node_modules/vite/bin/vite.js"))) {
            pb = new ProcessBuilder(props.nodeBinary(), "node_modules/vite/bin/vite.js",
                    "--host", "127.0.0.1", "--port", String.valueOf(port), "--strictPort");
            mode = "vite-hmr";
        } else if (pb == null) {
            for (String sub : List.of("frontend", "web", "client", "ui")) {
                if (Files.isRegularFile(dir.resolve(sub).resolve("node_modules/vite/bin/vite.js"))) {
                    pb = new ProcessBuilder(props.nodeBinary(), "node_modules/vite/bin/vite.js",
                            "--host", "127.0.0.1", "--port", String.valueOf(port), "--strictPort");
                    workDir = dir.resolve(sub);
                    mode = "vite-hmr-" + sub;
                    break;
                }
            }
        }
        if (pb == null) return null;
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PORT", String.valueOf(port));
        env.put("HOST", "127.0.0.1");
        env.put("DATA_DIR", dir.resolve("data").toString());
        env.put("NO_COLOR", "1");
        Process proc = pb.start();
        Threads.start(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendLog(app.id(), line);
                }
            } catch (Exception ignored) {}
        });
        // 等待端口监听就绪（最长 8 秒）
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) throw new IllegalStateException("开发进程提前退出（exit=" + proc.exitValue() + "）");
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
                return new LiveProc(proc, port, mode);
            } catch (IOException ignored) {
                Thread.sleep(150);
            }
        }
        // 超时但进程存活也返回，日志流继续输出
        if (proc.isAlive()) return new LiveProc(proc, port, mode);
        throw new IllegalStateException("活体开发服务监听超时");
    }

    private void appendLog(String appId, String line) {
        if (line == null) return;
        String clean = line.length() > 1000 ? line.substring(0, 1000) : line;
        Deque<String> buf = logBuffers.computeIfAbsent(appId, k -> new ConcurrentLinkedDeque<>());
        buf.addLast(clean);
        while (buf.size() > MAX_LOG_LINES) buf.pollFirst();
        var subs = logSubscribers.get(appId);
        if (subs != null) {
            for (SseEmitter em : subs) {
                try { em.send(SseEmitter.event().name("log").data(Map.of("line", clean))); }
                catch (Exception e) { subs.remove(em); }
            }
        }
    }

    public List<String> recentLogs(String appId) {
        Deque<String> buf = logBuffers.get(appId);
        return buf == null ? List.of() : new ArrayList<>(buf);
    }

    private void touch(String appId) {
        jdbc.sql("update app_live_workspace set last_active_at = now(), updated_at = now() where app_id = ?").param(appId).update();
    }

    private void upsertRow(AppRow app, String status, Path dir, Integer devPort, Integer backendPort,
                           Long devPid, Long backendPid, String depsHash, String snapshotCommit,
                           boolean snapshotReady, String lastError) {
        jdbc.sql("""
                insert into app_live_workspace
                (app_id, workspace_id, status, project_kind, workspace_dir, dev_port, backend_port, dev_pid, backend_pid,
                 deps_hash, snapshot_commit, snapshot_ready, last_error, last_active_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                on conflict (app_id) do update set
                  status = excluded.status,
                  project_kind = excluded.project_kind,
                  workspace_dir = excluded.workspace_dir,
                  dev_port = excluded.dev_port,
                  backend_port = excluded.backend_port,
                  dev_pid = excluded.dev_pid,
                  backend_pid = excluded.backend_pid,
                  deps_hash = excluded.deps_hash,
                  snapshot_commit = excluded.snapshot_commit,
                  snapshot_ready = excluded.snapshot_ready,
                  last_error = excluded.last_error,
                  last_active_at = now(),
                  updated_at = now()
                """).params(app.id(), app.workspaceId(), status, app.projectKind(), dir.toString(),
                devPort, backendPort, devPid, backendPid, depsHash, snapshotCommit, snapshotReady, lastError).update();
    }

    private AppRow requireApp(String ws, String appId) {
        return apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
    }

    private String currentCommit(AppRow app) throws Exception {
        if (app.currentVersionId() != null) {
            Optional<VersionRow> v = versions.find(app.currentVersionId());
            if (v.isPresent() && v.get().gitCommit() != null) return v.get().gitCommit();
        }
        return git.head(app.id()).map(GitService.CommitResult::commit).orElse(null);
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        }
    }

    private static String readStreamCapped(InputStream in, int maxBytes) {
        try (in) {
            byte[] data = in.readNBytes(maxBytes);
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String shortHash(String s) {
        if (s == null || s.isBlank()) return "-";
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(d)));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                Files.copy(f, dst.resolve(src.relativize(f)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
