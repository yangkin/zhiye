package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.config.ServiceRuntimeProperties;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.repo.GitService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 本地单体后端运行时（Deploy Engine V2）：
 * - 蓝绿零停机切流（Blue/Green Cutover）：新版本在独立临时端口启动并通过健康检查后再切换流量、优雅停止旧进程；若新版本启动失败则旧版保持 100% 存活！
 * - 不可变产物快照（releases/{releaseKey}）：每次成功构建保留产物快照（每槽位保留最新 5 份），支持「预览晋升线上」0 重建切流与「秒级回滚」
 * - 实时 SSE 构建日志广播（DeployLogBroadcaster）
 * - 真实进程运行态指标采集（PID / 端口 / RSS 内存 / CPU / 运行时长）
 */
@Component
@ConditionalOnProperty(name = "zhiye.service-runtime.provider", havingValue = "local", matchIfMissing = true)
public class LocalServiceRuntimeProvider implements ServiceRuntimeProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalServiceRuntimeProvider.class);
    private static final int MAX_KEPT_RELEASES = 5;

    private static final class RunningProc {
        final Process process;
        final int port;
        final Path logFile;
        final Path staticDir;
        final Instant startedAt;

        RunningProc(Process process, int port, Path logFile, Path staticDir, Instant startedAt) {
            this.process = process;
            this.port = port;
            this.logFile = logFile;
            this.staticDir = staticDir;
            this.startedAt = startedAt;
        }
    }

    private final ZhiyeProperties zhiyeProps;
    private final ServiceRuntimeProperties props;
    private final ConcurrentHashMap<String, RunningProc> procs = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LiveWorkspaceService liveWorkspace;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeployLogBroadcaster broadcaster;

    public LocalServiceRuntimeProvider(ZhiyeProperties zhiyeProps, ServiceRuntimeProperties props) {
        this.zhiyeProps = zhiyeProps;
        this.props = props;
    }

    @Override
    public String name() { return "local"; }

    private Path appDir(String appId) {
        return zhiyeProps.runtimePath().resolve("services").resolve(appId);
    }

    private Path canonicalDataDir(String appId) {
        String baseId = appId != null && appId.endsWith("-prev") ? appId.substring(0, appId.length() - 5) : appId;
        return zhiyeProps.runtimePath().resolve("services").resolve(baseId).resolve("data");
    }

    public Path staticDir(String appId) {
        RunningProc cur = procs.get(appId);
        if (cur != null && cur.staticDir != null && Files.isDirectory(cur.staticDir)) {
            return cur.staticDir;
        }
        return appDir(appId).resolve("static");
    }

    public Path staticDir(String appId, boolean preview) {
        if (preview) {
            Path p = staticDir(appId + "-prev");
            if (Files.isDirectory(p)) return p;
        }
        return staticDir(appId);
    }

    private void emitStage(String appId, String stage, String line, StringBuilder out) {
        if (out != null) out.append(line).append('\n');
        if (broadcaster != null) broadcaster.emitStage(appId, stage, line);
    }

    private void emitLog(String appId, String line) {
        if (broadcaster != null) broadcaster.emitLog(appId, line);
    }

    @Override
    public DeployResult deploy(DeploySpec spec) throws Exception {
        Path base = appDir(spec.appId());
        String releaseKey = "rel-" + System.currentTimeMillis() + "-" + Long.toString(ThreadLocalRandom.current().nextInt(1000, 9999));
        Path relRoot = base.resolve("releases").resolve(releaseKey);
        Path stageWork = relRoot.resolve("work");
        Path stageStatic = relRoot.resolve("static");
        Path dataDir = canonicalDataDir(spec.appId());
        Files.createDirectories(dataDir);
        Files.createDirectories(relRoot);
        copyDir(spec.sourceDir(), stageWork);

        StringBuilder buildLog = new StringBuilder();
        emitStage(spec.appId(), "prepare", "[zhiye] 准备部署单体应用 runtime=" + spec.runtime() + " framework=" + spec.framework(), buildLog);

        try {
            // 1. 前后端一体：若存在独立前端目录，先构建或复制静态产物（在独立 release 快照目录执行，构建失败不影响旧版运行）
            if (spec.frontendDir() != null && !spec.frontendDir().isBlank()) {
                Path fdir = stageWork.resolve(spec.frontendDir());
                if (Files.isDirectory(fdir)) {
                    emitStage(spec.appId(), "frontend", "[zhiye] 处理前端目录: " + spec.frontendDir(), buildLog);
                    if (Files.isRegularFile(fdir.resolve("package.json"))) {
                        if (Files.isDirectory(fdir.resolve("dist"))) {
                            copyDir(fdir.resolve("dist"), stageStatic);
                            emitStage(spec.appId(), "frontend", "[zhiye] 复用已存在的 " + spec.frontendDir() + "/dist", buildLog);
                        } else {
                            // 优先复用预热 vue-template-cache 依赖，零联网秒级构建 Vue 前端
                            Path vueCacheNm = zhiyeProps.runtimePath().resolve("vue-template-cache/node_modules");
                            Path vueCachePkg = zhiyeProps.runtimePath().resolve("vue-template-cache/package.json");
                            Path fNm = fdir.resolve("node_modules");
                            boolean linkedVueCache = false;
                            try {
                                if (!Files.exists(fNm, LinkOption.NOFOLLOW_LINKS)
                                        && Files.isDirectory(vueCacheNm) && Files.isRegularFile(vueCachePkg)
                                        && Arrays.equals(Files.readAllBytes(fdir.resolve("package.json")), Files.readAllBytes(vueCachePkg))) {
                                    Files.createSymbolicLink(fNm, vueCacheNm);
                                    linkedVueCache = true;
                                    emitStage(spec.appId(), "frontend", "[zhiye] 命中预热 Vue 模板依赖缓存（免重装 node_modules）", buildLog);
                                }
                            } catch (Exception ignored) {}
                            String npmCmd = linkedVueCache
                                    ? "npm run build"
                                    : (props.npmRegistry().isBlank()
                                        ? "(npm ci --no-audit --no-fund || npm install --no-audit --no-fund) && npm run build"
                                        : "npm config set registry " + shellQuote(props.npmRegistry())
                                          + " && (npm ci --no-audit --no-fund || npm install --no-audit --no-fund) && npm run build");
                            runBuildCmd(spec.appId(), fdir, npmCmd, spec.envVars(), buildLog);
                            Path dist = Files.isDirectory(fdir.resolve("dist")) ? fdir.resolve("dist")
                                    : Files.isDirectory(fdir.resolve("build")) ? fdir.resolve("build") : fdir;
                            copyDir(dist, stageStatic);
                        }
                    } else {
                        copyDir(fdir, stageStatic);
                    }
                }
            }

            // 2. 后端编译 / 依赖安装（优先命中活体开发环境 Snapshot-to-Deploy 热缓存，构建失败保持线上旧版存活）
            Path bdirStage = (spec.backendDir() == null || spec.backendDir().isBlank()) ? stageWork : stageWork.resolve(spec.backendDir());
            if (!Files.isDirectory(bdirStage)) throw new IllegalStateException("找不到后端目录：" + spec.backendDir());

            boolean reusedWarmNodeDeps = false;
            if (liveWorkspace != null && "node".equalsIgnoreCase(spec.runtime())) {
                String depsHash = LiveWorkspaceService.computeDepsHash(bdirStage);
                var snap = liveWorkspace.findWarmSnapshot(spec.appId(), depsHash);
                if (snap.isPresent() && snap.get().nodeModulesDir() != null && !Files.exists(bdirStage.resolve("node_modules"), LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.createSymbolicLink(bdirStage.resolve("node_modules"), snap.get().nodeModulesDir().toAbsolutePath());
                        reusedWarmNodeDeps = true;
                        emitStage(spec.appId(), "cache", "[zhiye] 命中活体工作区 Snapshot-to-Deploy 依赖缓存（depsHash="
                                + (depsHash.length() > 8 ? depsHash.substring(0, 8) : depsHash) + "，免重装 node_modules）", buildLog);
                    } catch (Exception ignored) {}
                }
                if (snap.isPresent() && snap.get().distDir() != null && !Files.isDirectory(bdirStage.resolve("dist"))) {
                    try {
                        copyDir(snap.get().distDir(), bdirStage.resolve("dist"));
                        emitStage(spec.appId(), "cache", "[zhiye] 复用活体工作区已验证前端构建产物 dist/", buildLog);
                    } catch (Exception ignored) {}
                }
            }

            if ("java".equals(spec.runtime()) && Files.isRegularFile(stageStatic.resolve("index.html"))
                    && Files.isRegularFile(bdirStage.resolve("pom.xml"))) {
                Path javaStatic = bdirStage.resolve("src/main/resources/static");
                GitService.deleteRecursively(javaStatic);
                copyDir(stageStatic, javaStatic);
            }

            String buildCmd = resolveLocalBuildCmd(spec, base, bdirStage);
            if (reusedWarmNodeDeps && buildCmd != null && (buildCmd.contains("npm ci") || buildCmd.contains("npm install")) && !buildCmd.contains("npm run build")) {
                emitStage(spec.appId(), "build", "[zhiye] 已命中热依赖缓存，跳过重复依赖安装", buildLog);
            } else if (buildCmd != null && !buildCmd.isBlank()) {
                emitStage(spec.appId(), "build", "[zhiye] 执行构建命令: " + buildCmd, buildLog);
                runBuildCmd(spec.appId(), bdirStage, buildCmd, spec.envVars(), buildLog);
            } else {
                emitStage(spec.appId(), "build", "[zhiye] 无需额外构建步骤", buildLog);
            }

            // Java Maven/Gradle 构建产物自动归位为 app.jar，兼容统一启动命令 java -jar app.jar
            if ("java".equals(spec.runtime()) && !Files.isRegularFile(bdirStage.resolve("app.jar"))) {
                copyBuiltJavaJar(bdirStage, buildLog);
            }

            // 3. 蓝绿零停机启动新实例：旧进程持续服务，待新端口通过健康检查后再原子切流并优雅下线旧进程
            String endpoint = blueGreenStartAndCutover(spec, relRoot, stageWork, stageStatic, dataDir, buildLog);
            pruneOldReleases(base, relRoot);

            String imageRef = "local/" + spec.slug() + ":" + (spec.versionId() == null ? "latest" : spec.versionId());
            if (broadcaster != null) broadcaster.emitDone(spec.appId(), "[zhiye] 零停机切流完成 → " + endpoint);
            return new DeployResult(endpoint, imageRef, buildLog.toString(), relRoot.toAbsolutePath().toString());
        } catch (Exception e) {
            GitService.deleteRecursively(relRoot);
            if (broadcaster != null) broadcaster.emitError(spec.appId(), "[zhiye] 部署失败（线上旧版本未受影响）: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public DeployResult deployPrebuilt(DeploySpec spec, String existingImageRef, Path artifactDir) throws Exception {
        if (artifactDir == null || !Files.isDirectory(artifactDir.resolve("work"))) {
            return deploy(spec);
        }
        Path base = appDir(spec.appId());
        String releaseKey = "promo-" + System.currentTimeMillis() + "-" + Long.toString(ThreadLocalRandom.current().nextInt(1000, 9999));
        Path relRoot = base.resolve("releases").resolve(releaseKey);
        Path targetWork = relRoot.resolve("work");
        Path targetStatic = relRoot.resolve("static");
        Path dataDir = canonicalDataDir(spec.appId());
        Files.createDirectories(dataDir);
        Files.createDirectories(relRoot);

        StringBuilder buildLog = new StringBuilder();
        emitStage(spec.appId(), "promote", "[zhiye] 命中已验证不可变产物快照，执行 0 重建极速晋升/回滚（source=" + artifactDir.getFileName() + "）", buildLog);

        try {
            copyDirPreservingSymlinks(artifactDir.resolve("work"), targetWork);
            if (Files.isDirectory(artifactDir.resolve("static"))) {
                copyDirPreservingSymlinks(artifactDir.resolve("static"), targetStatic);
            }
            // 若原快照指向外部 .venv（如从 preview 晋升到 live），同步软链 Python venv 避免重装
            Path srcVenv = artifactDir.getParent() != null && artifactDir.getParent().getParent() != null
                    ? artifactDir.getParent().getParent().resolve(".venv") : null;
            Path dstVenv = base.resolve(".venv");
            if (srcVenv != null && Files.isDirectory(srcVenv) && !Files.exists(dstVenv, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createSymbolicLink(dstVenv, srcVenv.toAbsolutePath());
                } catch (Exception ignored) {}
            }

            String endpoint = blueGreenStartAndCutover(spec, relRoot, targetWork, targetStatic, dataDir, buildLog);
            pruneOldReleases(base, relRoot);

            String imageRef = (existingImageRef != null && !existingImageRef.isBlank())
                    ? existingImageRef
                    : "local/" + spec.slug() + ":" + (spec.versionId() == null ? "latest" : spec.versionId());
            if (broadcaster != null) broadcaster.emitDone(spec.appId(), "[zhiye] 0 重建零停机切流完成 → " + endpoint);
            return new DeployResult(endpoint, imageRef, buildLog.toString(), relRoot.toAbsolutePath().toString());
        } catch (Exception e) {
            GitService.deleteRecursively(relRoot);
            if (broadcaster != null) broadcaster.emitError(spec.appId(), "[zhiye] 产物切流失败（旧版本保持存活）: " + e.getMessage());
            throw e;
        }
    }

    /** 蓝绿零停机启动与原子切流核心逻辑：旧进程持续承接流量直至新进程健康就绪。 */
    private String blueGreenStartAndCutover(DeploySpec spec, Path relRoot, Path newWork, Path newStatic,
                                            Path dataDir, StringBuilder buildLog) throws Exception {
        RunningProc oldProc = procs.get(spec.appId());
        long oldExternalPid = oldProc == null ? externalAlivePid(spec.appId()) : -1L;

        Path bdir = (spec.backendDir() == null || spec.backendDir().isBlank()) ? newWork : newWork.resolve(spec.backendDir());
        emitStage(spec.appId(), "cutover", "[zhiye] 蓝绿启动新版本实例（旧实例保持在线承接流量）…", buildLog);
        RunningProc nextProc = launchAndVerifyProcess(spec, bdir, dataDir, newStatic, buildLog);

        // 新进程健康就绪后，原子切换运行态指针与当前 work/static 链接
        procs.put(spec.appId(), nextProc);
        Path base = appDir(spec.appId());
        Files.writeString(base.resolve("process.pid"), nextProc.process.pid() + ":" + nextProc.port, StandardCharsets.UTF_8);
        updateCurrentLink(base.resolve("work"), newWork);
        if (Files.isDirectory(newStatic)) {
            updateCurrentLink(base.resolve("static"), newStatic);
        } else {
            deleteLinkOrDir(base.resolve("static"));
        }

        // 切流完成后再优雅下线旧进程
        if (oldProc != null && oldProc.process != nextProc.process) {
            emitStage(spec.appId(), "cutover", "[zhiye] 流量已切至 :" + nextProc.port + "，正在优雅下线旧实例 (:" + oldProc.port + ")", buildLog);
            terminateProcess(oldProc.process);
        } else if (oldExternalPid > 0 && oldExternalPid != nextProc.process.pid()) {
            emitStage(spec.appId(), "cutover", "[zhiye] 流量已切至 :" + nextProc.port + "，正在下线旧进程 PID=" + oldExternalPid, buildLog);
            terminatePid(oldExternalPid);
        }
        return "http://127.0.0.1:" + nextProc.port;
    }

    @Override
    public String wake(DeploySpec spec, String imageRef) throws Exception {
        RunningProc cur = procs.get(spec.appId());
        if (cur != null && cur.process.isAlive()) {
            return "http://127.0.0.1:" + cur.port;
        }
        int externalPort = externalAlivePort(spec.appId());
        if (externalPort > 0) {
            return "http://127.0.0.1:" + externalPort;
        }
        Path base = appDir(spec.appId());
        Path work = base.resolve("work");
        Path dataDir = canonicalDataDir(spec.appId());
        Path staticOut = base.resolve("static");
        Files.createDirectories(dataDir);
        if (!Files.isDirectory(work)) {
            return deploy(spec).endpointUrl();
        }
        Path realWork = Files.isSymbolicLink(work) ? work.toRealPath() : work;
        Path bdir = (spec.backendDir() == null || spec.backendDir().isBlank()) ? realWork : realWork.resolve(spec.backendDir());
        StringBuilder wakeLog = new StringBuilder("[zhiye] 从休眠态唤醒应用...\n");
        RunningProc rp = launchAndVerifyProcess(spec, bdir, dataDir, staticOut, wakeLog);
        procs.put(spec.appId(), rp);
        Files.writeString(base.resolve("process.pid"), rp.process.pid() + ":" + rp.port, StandardCharsets.UTF_8);
        return "http://127.0.0.1:" + rp.port;
    }

    @Override
    public void sleep(String appId, String slug) {
        stop(appId, slug);
    }

    @Override
    public void stop(String appId, String slug) {
        RunningProc cur = procs.remove(appId);
        if (cur != null) {
            terminateProcess(cur.process);
        }
        Path pidFile = appDir(appId).resolve("process.pid");
        if (Files.isRegularFile(pidFile)) {
            try {
                String raw = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                long pid = Long.parseLong(raw.split(":")[0]);
                terminatePid(pid);
            } catch (Exception ignored) {
            } finally {
                try { Files.deleteIfExists(pidFile); } catch (IOException ignored) {}
            }
        }
    }

    @Override
    public void destroy(String appId, String slug) throws IOException {
        stop(appId, slug);
        GitService.deleteRecursively(appDir(appId));
    }

    @Override
    public String logs(String appId, String slug, int tail) {
        Path logFile = appDir(appId).resolve("runtime.log");
        if (!Files.isRegularFile(logFile)) return "";
        try {
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int limit = Math.max(1, Math.min(tail <= 0 ? props.maxLogLines() : tail, 2000));
            int start = Math.max(0, lines.size() - limit);
            return String.join("\n", lines.subList(start, lines.size()));
        } catch (IOException e) {
            return "读取日志失败：" + e.getMessage();
        }
    }

    @Override
    public boolean isRunning(String appId, String slug) {
        RunningProc cur = procs.get(appId);
        if (cur != null && cur.process.isAlive()) return true;
        return externalAlivePort(appId) > 0;
    }

    @Override
    public RuntimeMetrics metrics(String appId, String slug) {
        RunningProc cur = procs.get(appId);
        Long pid = null;
        Integer port = null;
        Long uptime = null;
        if (cur != null && cur.process.isAlive()) {
            pid = cur.process.pid();
            port = cur.port;
            uptime = Math.max(0L, Duration.between(cur.startedAt, Instant.now()).getSeconds());
        } else {
            long extPid = externalAlivePid(appId);
            int extPort = externalAlivePort(appId);
            if (extPid > 0 && extPort > 0) {
                pid = extPid;
                port = extPort;
                uptime = ProcessHandle.of(extPid)
                        .flatMap(h -> h.info().startInstant())
                        .map(s -> Math.max(0L, Duration.between(s, Instant.now()).getSeconds()))
                        .orElse(null);
            }
        }
        if (pid == null) {
            return new RuntimeMetrics(false, null, null, null, null, null, name());
        }
        double[] rssCpu = sampleProcessStats(pid);
        return new RuntimeMetrics(true, pid, port, rssCpu != null ? rssCpu[0] : null, rssCpu != null ? rssCpu[1] : null, uptime, name());
    }

    private static double[] sampleProcessStats(long pid) {
        try {
            Process p = new ProcessBuilder("ps", "-o", "rss=,%cpu=", "-p", String.valueOf(pid))
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(2, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) return null;
            String[] toks = out.split("\\s+");
            if (toks.length >= 2) {
                double rssKb = Double.parseDouble(toks[0]);
                double cpu = Double.parseDouble(toks[1]);
                return new double[]{Math.round(rssKb / 1024.0 * 10.0) / 10.0, Math.round(cpu * 10.0) / 10.0};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private long externalAlivePid(String appId) {
        Path pidFile = appDir(appId).resolve("process.pid");
        if (!Files.isRegularFile(pidFile)) return -1L;
        try {
            String[] parts = Files.readString(pidFile, StandardCharsets.UTF_8).trim().split(":");
            if (parts.length != 2) return -1L;
            long pid = Long.parseLong(parts[0]);
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) ? pid : -1L;
        } catch (Exception e) {
            return -1L;
        }
    }

    private int externalAlivePort(String appId) {
        Path pidFile = appDir(appId).resolve("process.pid");
        if (!Files.isRegularFile(pidFile)) return -1;
        try {
            String[] parts = Files.readString(pidFile, StandardCharsets.UTF_8).trim().split(":");
            if (parts.length != 2) return -1;
            long pid = Long.parseLong(parts[0]);
            int port = Integer.parseInt(parts[1]);
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) return -1;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 150);
                return port;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    @PreDestroy
    public void shutdownAll() {
        for (String id : new ArrayList<>(procs.keySet())) {
            stop(id, "");
        }
    }

    private RunningProc launchAndVerifyProcess(DeploySpec spec, Path bdir, Path dataDir, Path staticOut, StringBuilder buildLog) throws Exception {
        int port = allocatePort();
        Path logFile = appDir(spec.appId()).resolve("runtime.log");
        Files.createDirectories(logFile.getParent());

        String startCmd = resolveLocalStartCmd(spec, bdir);
        emitStage(spec.appId(), "start", "[zhiye] 启动服务命令: " + startCmd + " (PORT=" + port + ")", buildLog);

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", startCmd);
        pb.directory(bdir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        Map<String, String> env = pb.environment();
        augmentPath(env);
        Path venvBin = appDir(spec.appId()).resolve(".venv").resolve(File.pathSeparator.equals(";") ? "Scripts" : "bin");
        if (Files.isDirectory(venvBin)) {
            env.put("PATH", venvBin.toAbsolutePath() + File.pathSeparator + env.getOrDefault("PATH", ""));
            env.put("VIRTUAL_ENV", appDir(spec.appId()).resolve(".venv").toAbsolutePath().toString());
        }
        env.put("PORT", String.valueOf(port));
        env.put("HOST", "127.0.0.1");
        env.put("DATA_DIR", dataDir.toAbsolutePath().toString());
        env.put("DATABASE_PATH", dataDir.resolve("app.db").toAbsolutePath().toString());
        if (staticOut != null && Files.isDirectory(staticOut)) {
            env.put("STATIC_DIR", staticOut.toAbsolutePath().toString());
        }
        if (spec.envVars() != null) {
            for (var e : spec.envVars().entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    env.put(e.getKey(), e.getValue());
                }
            }
        }
        env.put("PORT", String.valueOf(port));
        env.put("SERVER_PORT", String.valueOf(port));

        Files.writeString(logFile, "\n===== [" + Instant.now() + "] START (" + spec.runtime() + " :" + port + ") =====\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Process proc = pb.start();
        int waitSec = "java".equals(spec.runtime()) ? Math.max(60, props.wakeTimeoutSeconds()) : Math.max(15, props.wakeTimeoutSeconds());
        boolean ready = waitReady(proc, port, Duration.ofSeconds(waitSec));
        if (!ready) {
            String recent = logs(spec.appId(), spec.slug(), 60);
            terminateProcess(proc);
            throw new IllegalStateException("服务启动失败或监听端口超时（PORT=" + port + "）。运行日志：\n" + recent);
        }
        emitStage(spec.appId(), "ready", "[zhiye] 服务启动成功，新实例健康检查通过，监听 http://127.0.0.1:" + port, buildLog);
        return new RunningProc(proc, port, logFile, (staticOut != null && Files.isDirectory(staticOut)) ? staticOut : null, Instant.now());
    }

    private void terminateProcess(Process proc) {
        if (proc == null) return;
        try {
            proc.descendants().forEach(ProcessHandle::destroy);
            proc.destroy();
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                proc.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("停止进程异常: {}", e.getMessage());
        }
    }

    private void terminatePid(long pid) {
        ProcessHandle.of(pid).ifPresent(ph -> {
            ph.descendants().forEach(ProcessHandle::destroy);
            ph.destroy();
            try { Thread.sleep(150); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (ph.isAlive()) {
                ph.descendants().forEach(ProcessHandle::destroyForcibly);
                ph.destroyForcibly();
            }
        });
    }

    private void updateCurrentLink(Path linkPath, Path target) throws IOException {
        deleteLinkOrDir(linkPath);
        try {
            Files.createSymbolicLink(linkPath, target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException e) {
            // Windows 无符号链接权限时回退目录拷贝
            copyDirPreservingSymlinks(target, linkPath);
        }
    }

    private void deleteLinkOrDir(Path p) {
        try {
            if (Files.isSymbolicLink(p)) {
                Files.deleteIfExists(p);
            } else if (Files.exists(p)) {
                GitService.deleteRecursively(p);
            }
        } catch (IOException ignored) {}
    }

    private void pruneOldReleases(Path base, Path keep) {
        Path relsDir = base.resolve("releases");
        if (!Files.isDirectory(relsDir)) return;
        try (var stream = Files.list(relsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .collect(Collectors.toList());
            for (int i = MAX_KEPT_RELEASES; i < dirs.size(); i++) {
                Path old = dirs.get(i);
                if (!old.equals(keep)) {
                    GitService.deleteRecursively(old);
                }
            }
        } catch (Exception ignored) {}
    }

    private String resolveLocalBuildCmd(DeploySpec spec, Path appBase, Path bdir) {
        String rawBuild = spec.buildCmd() == null ? "" : spec.buildCmd().trim();
        if ("python".equals(spec.runtime())) {
            Path req = bdir.resolve("requirements.txt");
            boolean hasReqDeps = false;
            if (Files.isRegularFile(req)) {
                try {
                    hasReqDeps = Arrays.stream(Files.readString(req, StandardCharsets.UTF_8).split("\n"))
                            .map(String::trim).anyMatch(l -> !l.isEmpty() && !l.startsWith("#"));
                } catch (IOException ignored) {}
            }
            if (!hasReqDeps && rawBuild.contains("pip install") && rawBuild.contains("requirements.txt")) {
                return "";
            }
            if (rawBuild.contains("pip install")) {
                boolean windows = File.pathSeparator.equals(";");
                String venvDir = shellQuote(appBase.resolve(".venv").toAbsolutePath().toString());
                String pip = venvDir + (windows ? "/Scripts/pip.exe" : "/bin/pip");
                String python = windows ? "python" : "python3";
                String pipCmd = rawBuild.replaceFirst("pip3?\\s+install", pip + " install");
                if (!props.pipIndexUrl().isBlank() && !pipCmd.contains(" -i ")) {
                    pipCmd = pipCmd + " -i " + shellQuote(props.pipIndexUrl());
                }
                return "([ -d " + venvDir + " ] || " + python + " -m venv " + venvDir + ") && " + pipCmd;
            }
            return rawBuild;
        }
        if ("node".equals(spec.runtime()) && Files.isRegularFile(bdir.resolve("package.json"))) {
            Path cachedNm = zhiyeProps.runtimePath().resolve("fullstack-template-cache/node_modules");
            Path cachedPkg = zhiyeProps.runtimePath().resolve("fullstack-template-cache/package.json");
            Path localNm = bdir.resolve("node_modules");
            try {
                if (!Files.exists(localNm, LinkOption.NOFOLLOW_LINKS)
                        && Files.isDirectory(cachedNm) && Files.isRegularFile(cachedPkg)
                        && Arrays.equals(Files.readAllBytes(bdir.resolve("package.json")), Files.readAllBytes(cachedPkg))) {
                    try {
                        Files.createSymbolicLink(localNm, cachedNm);
                    } catch (Exception e) {
                        if (File.pathSeparator.equals(";")) {
                            Process link = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                                    localNm.toString(), cachedNm.toString()).redirectErrorStream(true).start();
                            if (!link.waitFor(10, TimeUnit.SECONDS)) link.destroyForcibly();
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (Files.exists(localNm, LinkOption.NOFOLLOW_LINKS)) return rawBuild;
            boolean hasDeps = false;
            try {
                String raw = Files.readString(bdir.resolve("package.json"), StandardCharsets.UTF_8);
                hasDeps = (raw.contains("\"dependencies\"") && !raw.matches("(?s).*\"dependencies\"\\s*:\\s*\\{\\s*\\}.*"))
                        || (raw.contains("\"devDependencies\"") && !raw.matches("(?s).*\"devDependencies\"\\s*:\\s*\\{\\s*\\}.*"));
            } catch (IOException ignored) {}
            String install = !hasDeps ? "" : (props.npmRegistry().isBlank()
                    ? "(npm ci --no-audit --no-fund || npm install --no-audit --no-fund)"
                    : "npm config set registry " + shellQuote(props.npmRegistry()) + " && (npm ci --no-audit --no-fund || npm install --no-audit --no-fund)");
            if (rawBuild.isBlank()) return install;
            if (install.isBlank() || rawBuild.contains("npm install") || rawBuild.contains("npm ci")) return rawBuild;
            return install + " && " + rawBuild;
        }
        if ("java".equals(spec.runtime()) && rawBuild.contains("mvn ")) {
            String cmd = Files.isRegularFile(bdir.resolve("mvnw"))
                    ? rawBuild.replaceFirst("^mvn\\b", "chmod +x mvnw && ./mvnw")
                    : rawBuild;
            if (!cmd.contains(" -o ") && !cmd.contains("--offline")) {
                String tailFlag = "";
                String envM2 = System.getenv("ZHIYE_M2_REPO");
                Path runtimeM2 = zhiyeProps.runtimePath().resolve("m2-repo");
                Path m2 = (envM2 != null && !envM2.isBlank() && Files.isDirectory(Path.of(envM2.trim())))
                        ? Path.of(envM2.trim())
                        : Files.isDirectory(runtimeM2) ? runtimeM2 : null;
                if (m2 != null) {
                    tailFlag = " -Dmaven.repo.local.tail=" + shellQuote(m2.toAbsolutePath().toString());
                }
                String offline = cmd.replaceFirst("\\bmvn\\b", "mvn -o -Daether.syncContext.named.factory=noop" + tailFlag);
                return "(" + offline + ") || (" + cmd + ")";
            }
            return cmd;
        }
        return rawBuild;
    }

    private void copyBuiltJavaJar(Path bdir, StringBuilder buildLog) throws IOException {
        for (String sub : List.of("target", "build/libs")) {
            Path dir = bdir.resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            try (var stream = Files.list(dir)) {
                Optional<Path> jar = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                            return n.endsWith(".jar") && !n.endsWith("-plain.jar")
                                    && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
                        })
                        .max(Comparator.comparingLong(p -> {
                            try { return Files.size(p); } catch (IOException e) { return 0L; }
                        }));
                if (jar.isPresent()) {
                    Files.copy(jar.get(), bdir.resolve("app.jar"), StandardCopyOption.REPLACE_EXISTING);
                    buildLog.append("[zhiye] 已定位可执行 JAR: ").append(bdir.relativize(jar.get())).append(" → app.jar\n");
                    return;
                }
            }
        }
    }

    private String resolveLocalStartCmd(DeploySpec spec, Path bdir) {
        if ("python".equals(spec.runtime()) && File.pathSeparator.equals(";")) {
            if (spec.startCmd() != null && !spec.startCmd().isBlank())
                return spec.startCmd().replaceFirst("^python3\\b", "python");
            return "python main.py";
        }
        if (spec.startCmd() != null && !spec.startCmd().isBlank()) return spec.startCmd();
        return switch (spec.runtime()) {
            case "python" -> "python3 main.py";
            case "go" -> "./app";
            case "java" -> Files.isRegularFile(bdir.resolve("app.jar")) ? "java -jar app.jar --server.port=${PORT}" : "java -cp out Main";
            default -> "npm start";
        };
    }

    private void runBuildCmd(String appId, Path workdir, String cmd, Map<String, String> userEnv, StringBuilder out) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        augmentPath(env);
        if (!props.goProxy().isBlank()) env.put("GOPROXY", props.goProxy());
        if (userEnv != null) userEnv.forEach((k, v) -> { if (k != null && v != null) env.put(k, v); });
        Process p = pb.start();
        StringBuilder captured = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                captured.append(line).append('\n');
                emitLog(appId, line);
                if (captured.length() > 64_000) captured.delete(0, captured.length() - 64_000);
            }
        }
        boolean finished = p.waitFor(Math.max(30, props.buildTimeoutSeconds()), TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            out.append(captured);
            throw new IllegalStateException("构建超时（>" + props.buildTimeoutSeconds() + "s）");
        }
        out.append(captured);
        if (p.exitValue() != 0) {
            throw new IllegalStateException("构建命令执行失败（exit=" + p.exitValue() + "）：\n" + captured);
        }
    }

    private static boolean waitReady(Process proc, int port, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!proc.isAlive()) return false;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return true;
            } catch (IOException ignored) {
                Thread.sleep(150);
            }
        }
        return false;
    }

    private static int allocatePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }

    private static void augmentPath(Map<String, String> env) {
        String cur = env.getOrDefault("PATH", "");
        String sep = File.pathSeparator;
        String javaHomeProp = System.getProperty("java.home", "");
        if (!javaHomeProp.isEmpty()) {
            env.putIfAbsent("JAVA_HOME", javaHomeProp);
        }
        if (sep.equals(";")) {
            if (!cur.isEmpty()) env.put("PATH", cur);
            return;
        }
        StringBuilder extra = new StringBuilder();
        if (!javaHomeProp.isEmpty() && Files.isDirectory(Path.of(javaHomeProp, "bin"))) {
            extra.append(Path.of(javaHomeProp, "bin")).append(":");
        }
        extra.append("/opt/homebrew/bin:/opt/homebrew/opt/openjdk@21/bin:/usr/local/bin");
        String home = System.getProperty("user.home", "");
        if (!home.isEmpty()) {
            Path nvm = Path.of(home, ".nvm", "versions", "node");
            if (Files.isDirectory(nvm)) {
                try (var stream = Files.list(nvm)) {
                    Optional<Path> latest = stream.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).findFirst();
                    if (latest.isPresent()) extra.insert(0, latest.get().resolve("bin") + ":");
                } catch (IOException ignored) {}
            }
        }
        env.put("PATH", extra + (cur.isEmpty() ? "" : sep + cur));
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(src) && (".git".equals(name) || ".zhiye".equals(name) || "node_modules".equals(name) || "target".equals(name))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.copy(file, dst.resolve(src.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyDirPreservingSymlinks(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        Files.walkFileTree(src, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(dir)) {
                    Path target = Files.readSymbolicLink(dir);
                    Path out = dst.resolve(src.relativize(dir).toString());
                    Files.createDirectories(out.getParent());
                    Files.createSymbolicLink(out, target);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(src) && ".git".equals(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Path out = dst.resolve(src.relativize(file).toString());
                Files.createDirectories(out.getParent());
                if (Files.isSymbolicLink(file)) {
                    Files.createSymbolicLink(out, Files.readSymbolicLink(file));
                } else {
                    Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
