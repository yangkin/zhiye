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

/**
 * 本地单体后端运行时（zhiye.service-runtime.provider=local，默认）：
 * 直接在隔离工作目录编译并启动 Python / Node.js / Java / Go 进程，分配空闲本地端口并做真实健康检查，
 * 支持前后端一体（后端同目录或静态产物回退）、持久化 /data 目录、密文环境变量、闲置休眠与按需唤醒。
 */
@Component
@ConditionalOnProperty(name = "zhiye.service-runtime.provider", havingValue = "local", matchIfMissing = true)
public class LocalServiceRuntimeProvider implements ServiceRuntimeProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalServiceRuntimeProvider.class);

    private static final class RunningProc {
        final Process process;
        final int port;
        final Path logFile;
        final Path staticDir;

        RunningProc(Process process, int port, Path logFile, Path staticDir) {
            this.process = process;
            this.port = port;
            this.logFile = logFile;
            this.staticDir = staticDir;
        }
    }

    private final ZhiyeProperties zhiyeProps;
    private final ServiceRuntimeProperties props;
    private final ConcurrentHashMap<String, RunningProc> procs = new ConcurrentHashMap<>();

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
        return appDir(appId).resolve("static");
    }

    public Path staticDir(String appId, boolean preview) {
        if (preview) {
            Path p = appDir(appId + "-prev").resolve("static");
            if (Files.isDirectory(p)) return p;
        }
        return appDir(appId).resolve("static");
    }

    @Override
    public DeployResult deploy(DeploySpec spec) throws Exception {
        Path base = appDir(spec.appId());
        Path stageWork = base.resolve("work-stage");
        Path stageStatic = base.resolve("static-stage");
        Path dataDir = canonicalDataDir(spec.appId());
        Files.createDirectories(dataDir);
        GitService.deleteRecursively(stageWork);
        GitService.deleteRecursively(stageStatic);
        copyDir(spec.sourceDir(), stageWork);

        StringBuilder buildLog = new StringBuilder();
        buildLog.append("[zhiye] 准备部署单体应用 runtime=").append(spec.runtime())
                .append(" framework=").append(spec.framework()).append("\n");

        // 1. 前后端一体：若存在独立前端目录，先构建或复制静态产物（在 staging 目录执行，构建失败不影响旧版运行）
        if (spec.frontendDir() != null && !spec.frontendDir().isBlank()) {
            Path fdir = stageWork.resolve(spec.frontendDir());
            if (Files.isDirectory(fdir)) {
                buildLog.append("[zhiye] 处理前端目录: ").append(spec.frontendDir()).append("\n");
                if (Files.isRegularFile(fdir.resolve("package.json"))) {
                    if (Files.isDirectory(fdir.resolve("dist"))) {
                        copyDir(fdir.resolve("dist"), stageStatic);
                        buildLog.append("[zhiye] 复用已存在的 ").append(spec.frontendDir()).append("/dist\n");
                    } else {
                        String npmCmd = props.npmRegistry().isBlank()
                                ? "(npm ci --no-audit --no-fund || npm install --no-audit --no-fund) && npm run build"
                                : "npm config set registry " + shellQuote(props.npmRegistry())
                                  + " && (npm ci --no-audit --no-fund || npm install --no-audit --no-fund) && npm run build";
                        runBuildCmd(fdir, npmCmd, spec.envVars(), buildLog);
                        Path dist = Files.isDirectory(fdir.resolve("dist")) ? fdir.resolve("dist")
                                : Files.isDirectory(fdir.resolve("build")) ? fdir.resolve("build") : fdir;
                        copyDir(dist, stageStatic);
                    }
                } else {
                    copyDir(fdir, stageStatic);
                }
            }
        }

        // 2. 后端编译 / 依赖安装（构建失败直接抛出，保持线上旧版进程存活）
        Path bdirStage = (spec.backendDir() == null || spec.backendDir().isBlank()) ? stageWork : stageWork.resolve(spec.backendDir());
        if (!Files.isDirectory(bdirStage)) throw new IllegalStateException("找不到后端目录：" + spec.backendDir());

        String buildCmd = resolveLocalBuildCmd(spec, base, bdirStage);
        if (buildCmd != null && !buildCmd.isBlank()) {
            buildLog.append("[zhiye] 执行构建命令: ").append(buildCmd).append("\n");
            runBuildCmd(bdirStage, buildCmd, spec.envVars(), buildLog);
        } else {
            buildLog.append("[zhiye] 无需额外构建步骤\n");
        }

        // 构建通过后切换运行目录并重启该槽位进程
        stop(spec.appId(), spec.slug());
        Path work = base.resolve("work");
        Path staticOut = base.resolve("static");
        GitService.deleteRecursively(work);
        GitService.deleteRecursively(staticOut);
        Files.move(stageWork, work, StandardCopyOption.REPLACE_EXISTING);
        if (Files.isDirectory(stageStatic)) {
            Files.move(stageStatic, staticOut, StandardCopyOption.REPLACE_EXISTING);
        }
        Path bdir = (spec.backendDir() == null || spec.backendDir().isBlank()) ? work : work.resolve(spec.backendDir());

        // Java Maven/Gradle 构建产物自动归位为 app.jar，兼容统一启动命令 java -jar app.jar
        if ("java".equals(spec.runtime()) && !Files.isRegularFile(bdir.resolve("app.jar"))) {
            copyBuiltJavaJar(bdir, buildLog);
        }

        // 3. 启动服务进程并等待端口就绪
        String endpoint = startProcess(spec, bdir, dataDir, staticOut, buildLog);
        String imageRef = "local/" + spec.slug() + ":" + (spec.versionId() == null ? "latest" : spec.versionId());
        return new DeployResult(endpoint, imageRef, buildLog.toString());
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
            // 若工作目录缺失（如服务重启后清理），重新走完整构建部署
            return deploy(spec).endpointUrl();
        }
        Path bdir = (spec.backendDir() == null || spec.backendDir().isBlank()) ? work : work.resolve(spec.backendDir());
        StringBuilder wakeLog = new StringBuilder("[zhiye] 从休眠态唤醒应用...\n");
        return startProcess(spec, bdir, dataDir, staticOut, wakeLog);
    }

    @Override
    public void sleep(String appId, String slug) {
        stop(appId, slug);
    }

    @Override
    public void stop(String appId, String slug) {
        RunningProc cur = procs.remove(appId);
        if (cur != null) {
            try {
                cur.process.descendants().forEach(ProcessHandle::destroy);
                cur.process.destroy();
                if (!cur.process.waitFor(3, TimeUnit.SECONDS)) {
                    cur.process.descendants().forEach(ProcessHandle::destroyForcibly);
                    cur.process.destroyForcibly();
                    cur.process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                log.warn("停止本地单体服务失败 app={}: {}", appId, e.getMessage());
            }
        }
        // 兼容 api(8080) 与 site(8083) 双进程本地模式：通过 process.pid 停止跨 JVM 启动的子进程
        Path pidFile = appDir(appId).resolve("process.pid");
        if (Files.isRegularFile(pidFile)) {
            try {
                String raw = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
                long pid = Long.parseLong(raw.split(":")[0]);
                ProcessHandle.of(pid).ifPresent(ph -> {
                    ph.descendants().forEach(ProcessHandle::destroy);
                    ph.destroy();
                    try { Thread.sleep(150); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    if (ph.isAlive()) {
                        ph.descendants().forEach(ProcessHandle::destroyForcibly);
                        ph.destroyForcibly();
                    }
                });
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

    private String startProcess(DeploySpec spec, Path bdir, Path dataDir, Path staticOut, StringBuilder buildLog) throws Exception {
        int port = allocatePort();
        Path logFile = appDir(spec.appId()).resolve("runtime.log");
        Files.createDirectories(logFile.getParent());

        String startCmd = resolveLocalStartCmd(spec, bdir);
        buildLog.append("[zhiye] 启动服务命令: ").append(startCmd).append(" (PORT=").append(port).append(")\n");

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", startCmd);
        pb.directory(bdir.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        Map<String, String> env = pb.environment();
        augmentPath(env);
        Path venvBin = appDir(spec.appId()).resolve(".venv").resolve("bin");
        if (Files.isDirectory(venvBin)) {
            env.put("PATH", venvBin.toAbsolutePath() + File.pathSeparator + env.getOrDefault("PATH", ""));
            env.put("VIRTUAL_ENV", appDir(spec.appId()).resolve(".venv").toAbsolutePath().toString());
        }
        env.put("PORT", String.valueOf(port));
        env.put("HOST", "127.0.0.1");
        env.put("DATA_DIR", dataDir.toAbsolutePath().toString());
        env.put("DATABASE_PATH", dataDir.resolve("app.db").toAbsolutePath().toString());
        if (Files.isDirectory(staticOut)) {
            env.put("STATIC_DIR", staticOut.toAbsolutePath().toString());
        }
        if (spec.envVars() != null) {
            for (var e : spec.envVars().entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
                    env.put(e.getKey(), e.getValue());
                }
            }
        }
        // 确保 PORT 始终由平台掌控，避免被用户环境变量覆盖导致端口冲突
        env.put("PORT", String.valueOf(port));
        env.put("SERVER_PORT", String.valueOf(port));

        Files.writeString(logFile, "\n===== [" + Instant.now() + "] START (" + spec.runtime() + " :" + port + ") =====\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Process proc = pb.start();
        RunningProc rp = new RunningProc(proc, port, logFile, Files.isDirectory(staticOut) ? staticOut : null);
        procs.put(spec.appId(), rp);
        Files.writeString(appDir(spec.appId()).resolve("process.pid"), proc.pid() + ":" + port, StandardCharsets.UTF_8);

        // Java / Spring Boot 冷启动耗时略长，本地模式给足 60 秒就绪等待窗口
        int waitSec = "java".equals(spec.runtime()) ? Math.max(60, props.wakeTimeoutSeconds()) : Math.max(15, props.wakeTimeoutSeconds());
        boolean ready = waitReady(proc, port, Duration.ofSeconds(waitSec));
        if (!ready) {
            String recent = logs(spec.appId(), spec.slug(), 60);
            stop(spec.appId(), spec.slug());
            throw new IllegalStateException("服务启动失败或监听端口超时（PORT=" + port + "）。运行日志：\n" + recent);
        }
        buildLog.append("[zhiye] 服务启动成功，监听 http://127.0.0.1:").append(port).append("\n");
        return "http://127.0.0.1:" + port;
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
                String venvDir = shellQuote(appBase.resolve(".venv").toAbsolutePath().toString());
                String pipCmd = rawBuild.replaceFirst("pip3?\\s+install", venvDir + "/bin/pip install");
                if (!props.pipIndexUrl().isBlank() && !pipCmd.contains(" -i ")) {
                    pipCmd = pipCmd + " -i " + shellQuote(props.pipIndexUrl());
                }
                return "([ -d " + venvDir + " ] || python3 -m venv " + venvDir + ") && " + pipCmd;
            }
            return rawBuild;
        }
        if ("node".equals(spec.runtime()) && Files.isRegularFile(bdir.resolve("package.json"))) {
            // 若命中本地全栈模板缓存且尚未安装 node_modules，直接软链免去重复 npm install
            Path cachedNm = zhiyeProps.runtimePath().resolve("fullstack-template-cache/node_modules");
            Path cachedPkg = zhiyeProps.runtimePath().resolve("fullstack-template-cache/package.json");
            Path localNm = bdir.resolve("node_modules");
            try {
                if (!Files.exists(localNm, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        && Files.isDirectory(cachedNm) && Files.isRegularFile(cachedPkg)
                        && Arrays.equals(Files.readAllBytes(bdir.resolve("package.json")), Files.readAllBytes(cachedPkg))) {
                    Files.createSymbolicLink(localNm, cachedNm);
                }
            } catch (Exception ignored) {}
            if (Files.exists(localNm, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return rawBuild;
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
        if ("java".equals(spec.runtime()) && rawBuild.contains("mvn ") && Files.isRegularFile(bdir.resolve("mvnw"))) {
            return rawBuild.replaceFirst("^mvn\\b", "chmod +x mvnw && ./mvnw");
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
        if (spec.startCmd() != null && !spec.startCmd().isBlank()) return spec.startCmd();
        return switch (spec.runtime()) {
            case "python" -> "python3 main.py";
            case "go" -> "./app";
            case "java" -> Files.isRegularFile(bdir.resolve("app.jar")) ? "java -jar app.jar --server.port=${PORT}" : "java -cp out Main";
            default -> "npm start";
        };
    }

    private void runBuildCmd(Path workdir, String cmd, Map<String, String> userEnv, StringBuilder out) throws Exception {
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
        String extra = "/opt/homebrew/bin:/opt/homebrew/opt/openjdk@21/bin:/usr/local/bin";
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
        env.put("PATH", extra + (cur.isEmpty() ? "" : ":" + cur));
    }

    private static void copyDir(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(src) && (".git".equals(name) || ".zhiye".equals(name) || "node_modules".equals(name))) {
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

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }
}
