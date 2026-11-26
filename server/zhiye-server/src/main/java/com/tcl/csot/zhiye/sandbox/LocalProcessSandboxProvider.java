package com.tcl.csot.zhiye.sandbox;

import com.tcl.csot.zhiye.util.Threads;

import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.sandbox.http.HttpRunnerBackend;
import com.tcl.csot.zhiye.sandbox.http.HttpRunnerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地进程沙箱：在工作区目录启动 node runner，runner 以 HTTP 服务运行（只监听 127.0.0.1）。
 * zhiye.sandbox.seatbelt=true 时用 sandbox-exec 包一层。
 * 环境变量只包含网关地址、任务令牌和本任务的 runner 控制令牌，不含真实模型密钥。
 * zhiye.sandbox.provider=local（默认）时启用；k8s 环境由 OpenSandbox Provider 代替。
 */
@Component
@ConditionalOnProperty(name = "zhiye.sandbox.provider", havingValue = "local", matchIfMissing = true)
public class LocalProcessSandboxProvider implements SandboxProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalProcessSandboxProvider.class);
    private static final Pattern READY = Pattern.compile("ZHIYE_RUNNER_READY port=(\\d+)");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ZhiyeProperties props;

    public LocalProcessSandboxProvider(ZhiyeProperties props) { this.props = props; }

    @Override
    public RunnerSession start(Spec spec) throws Exception {
        Path root = props.rootPath();
        String node = props.nodeBinary();
        Path nodeDir = Path.of(node).getParent();
        Path script = props.resolveFromRoot(props.runner().script());
        Files.createDirectories(spec.agentDir());
        Files.createDirectories(spec.logFile().getParent());

        String controlToken = HexFormat.of().formatHex(newToken());
        Path javaHome = resolveJavaHome();
        Path mavenRepo = resolveMavenRepo(root);
        List<String> cmd = new ArrayList<>();
        if (props.sandbox().seatbelt()) {
            Path profile = props.resolveFromRoot(props.sandbox().seatbeltProfile());
            if (Files.isRegularFile(profile) && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
                cmd.addAll(List.of("/usr/bin/sandbox-exec", "-f", profile.toString(),
                        "-D", "WORKSPACE=" + spec.workspace().toRealPath(),
                        "-D", "AGENT_DIR=" + spec.agentDir().toRealPath(),
                        "-D", "ZHIYE_ROOT=" + root.toRealPath(),
                        "-D", "NODE_DIR=" + realOrSelf(nodeDir.getParent()),
                        "-D", "JAVA_HOME_DIR=" + (javaHome != null ? realOrSelf(javaHome) : "/nonexistent-java"),
                        "-D", "MAVEN_REPO=" + (mavenRepo != null ? realOrSelf(mavenRepo) : "/nonexistent-m2"),
                        "-D", "ALLOW_NET=" + (spec.allowNet() ? "1" : "0")));
            } else {
                log.warn("Seatbelt 配置 {} 不存在（或没有 sandbox-exec），runner 将不在沙箱中运行", profile);
            }
        }
        cmd.add(node);
        cmd.add(script.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd).directory(spec.workspace().toFile());
        Map<String, String> env = pb.environment();
        env.clear();
        env.put("ZHIYE_GATEWAY_URL", spec.gatewayUrl());
        env.put("ZHIYE_TASK_TOKEN", spec.taskToken());
        env.put("ZHIYE_RUNNER_TOKEN", controlToken);
        env.put("ZHIYE_RUNNER_HOST", "127.0.0.1");
        env.put("ZHIYE_RUNNER_PORT", "0");
        env.put("ZHIYE_LOC_PLUGIN", root.resolve("packages/vite-plugin-loc/index.mjs").toString());
        env.put("ZHIYE_BRIDGE_PATH", root.resolve("packages/editor-bridge/bridge.js").toString());
        env.put("ZHIYE_CHROME_PATH", props.sandbox().chromePath());
        env.put("ZHIYE_TASK_ID", spec.taskId());
        String agentHome = realOrSelf(spec.agentDir());
        env.put("HOME", agentHome);
        if (javaHome != null) {
            env.put("JAVA_HOME", realOrSelf(javaHome));
            // macOS JVM 默认从 passwd 读取 user.home 而忽略 $HOME，显式绑定到隔离的 agentDir，既免触发 Seatbelt 拒绝 ~/.m2/settings.xml，也不暴露宿主机凭证
            env.put("JAVA_TOOL_OPTIONS", "-Duser.home=" + agentHome + " -DsocksNonProxyHosts=localhost|127.*|*.local|*.localhost");
        }
        if (mavenRepo != null) {
            String m2 = realOrSelf(mavenRepo);
            env.put("ZHIYE_M2_REPO", m2);
            // 离线模式免写锁读取预热本地仓库（tail 仓库忽略宿主机 _remote.repositories 镜像标记），避免 Seatbelt 只读约束下报错
            env.put("MAVEN_OPTS", "-Duser.home=" + agentHome + " -Dmaven.repo.local=" + m2 + " -Dmaven.repo.local.tail=" + m2 + " -Daether.syncContext.named.factory=noop -DsocksNonProxyHosts=localhost|127.*|*.local|*.localhost");
            // 同步 ~/.m2/repository 只读软链，确保直接运行 mvn -o 也能命中离线仓库
            try {
                Path agentM2 = spec.agentDir().resolve(".m2");
                Files.createDirectories(agentM2);
                Path link = agentM2.resolve("repository");
                if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Files.createSymbolicLink(link, Path.of(m2));
                }
            } catch (Exception ignored) {}
        }
        // Windows 的 PATH 分隔符是 ;，且 npm.cmd 等依赖系统 PATH；mac/linux 包含 node、JDK 与常见工具目录（mvn/go/python3）
        if (File.pathSeparator.equals(";")) {
            String sysPath = System.getenv("PATH");
            String javaPart = javaHome != null ? javaHome.resolve("bin") + ";" : "";
            env.put("PATH", javaPart + nodeDir + ";" + (sysPath == null ? "" : sysPath));
        } else {
            List<String> pathParts = new ArrayList<>();
            if (javaHome != null) pathParts.add(realOrSelf(javaHome.resolve("bin")));
            pathParts.add(nodeDir.toString());
            for (String extra : List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin")) {
                if (Files.isDirectory(Path.of(extra)) && !pathParts.contains(extra)) pathParts.add(extra);
            }
            env.put("PATH", String.join(":", pathParts));
        }
        env.put("LANG", "en_US.UTF-8");
        if (spec.allowNet()) {
            env.put("ALLOW_NET", "1");
            // 导入任务安装依赖：共享 npm 缓存，可用 ZHIYE_NPM_REGISTRY 指定镜像
            String npmCache = File.pathSeparator.equals(";")
                    ? root.resolve("runtime/npm-cache").toString()
                    : "/private/tmp/zhiye-npm-cache";
            env.put("npm_config_cache", npmCache);
            String registry = System.getenv("ZHIYE_NPM_REGISTRY");
            if (registry != null && !registry.isBlank()) env.put("npm_config_registry", registry);
        }
        // 自定义模型定义（glm 中转等不在 Pi 内置清单的模型）：服务端从环境变量转发给 runner
        String modelDef = System.getenv("ZHIYE_MODEL_DEF");
        if (modelDef != null && !modelDef.isBlank()) env.put("ZHIYE_MODEL_DEF", modelDef);
        env.put("TMPDIR", spec.agentDir().resolve("tmp").toString());
        env.put("TEMP", spec.agentDir().resolve("tmp").toString());
        env.put("TMP", spec.agentDir().resolve("tmp").toString());
        Files.createDirectories(spec.agentDir().resolve("tmp"));

        PrintWriter logWriter = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(spec.logFile().toFile(), true), StandardCharsets.UTF_8), true);
        logWriter.println("[java] 启动 runner：" + String.join(" ", cmd));
        // runner 的协议和日志都走 HTTP/SSE 与 stderr，stdout 只用于丢弃误打印的内容
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();

        LinkedBlockingQueue<String> readyLines = new LinkedBlockingQueue<>();
        Threads.start("runner-log-" + spec.taskId(), () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (logWriter) { logWriter.println(line); }
                    if (line.contains("ZHIYE_RUNNER_READY")) readyLines.offer(line);
                }
            } catch (Exception e) {
                // 进程结束
            }
        });

        int port = awaitReadyPort(p, readyLines, Duration.ofSeconds(60));
        if (port <= 0) {
            if (p.isAlive()) p.destroyForcibly();
            synchronized (logWriter) { logWriter.close(); }
            throw new IllegalStateException("runner 没有报告监听端口（见任务日志）");
        }
        String descCmd = props.sandbox().seatbelt() ? "sandbox-exec + node runner" : "node runner";
        HttpRunnerSession session = new HttpRunnerSession("http://127.0.0.1:" + port, Map.of(), controlToken,
                new ProcessBackend(p, descCmd), spec.onEvent(), logWriter);
        try {
            session.waitReady(Duration.ofSeconds(30));
        } catch (Exception e) {
            session.close();
            throw e;
        }
        return session;
    }

    private static int awaitReadyPort(Process p, LinkedBlockingQueue<String> lines, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!p.isAlive()) {
                while (true) {
                    String l = lines.poll();
                    if (l == null) break;
                    Matcher m = READY.matcher(l);
                    if (m.find()) return Integer.parseInt(m.group(1));
                }
                return -1;
            }
            String line = lines.poll(100, TimeUnit.MILLISECONDS);
            if (line == null) continue;
            Matcher m = READY.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    private static byte[] newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return b;
    }

    private static String realOrSelf(Path p) {
        try { return p.toRealPath().toString(); } catch (Exception e) { return p.toString(); }
    }

    /** 探测本机 JDK 目录，确保沙箱内 java / javac / mvn 可用。 */
    private static Path resolveJavaHome() {
        List<String> candidates = new ArrayList<>();
        String envHome = System.getenv("JAVA_HOME");
        if (envHome != null && !envHome.isBlank()) candidates.add(envHome.trim());
        String propHome = System.getProperty("java.home");
        if (propHome != null && !propHome.isBlank()) candidates.add(propHome.trim());
        candidates.addAll(List.of(
                "/opt/homebrew/opt/openjdk@21",
                "/opt/homebrew/opt/openjdk@17",
                "/opt/homebrew/opt/openjdk",
                "/usr/lib/jvm/java-21-openjdk-amd64",
                "/usr/lib/jvm/java-21-openjdk-arm64",
                "/usr/lib/jvm/java-17-openjdk-amd64",
                "/usr/lib/jvm/default-java"));
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isExecutable(p.resolve("bin/java")) || Files.isExecutable(p.resolve("bin/java.exe"))) {
                return p;
            }
        }
        return null;
    }

    /** 探测已预热的离线 Maven 本地仓库，供沙箱内 mvn -o 零联网编译 Spring Boot 项目。 */
    private static Path resolveMavenRepo(Path root) {
        String envRepo = System.getenv("ZHIYE_M2_REPO");
        if (envRepo != null && !envRepo.isBlank() && Files.isDirectory(Path.of(envRepo.trim()))) {
            return Path.of(envRepo.trim());
        }
        Path runtimeM2 = root.resolve("runtime/m2-repo");
        if (Files.isDirectory(runtimeM2)) return runtimeM2;
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            Path homeM2 = Path.of(userHome, ".m2", "repository");
            if (Files.isDirectory(homeM2)) return homeM2;
        }
        return null;
    }

    /** 本机 runner 进程的生命周期。 */
    private static final class ProcessBackend implements HttpRunnerBackend {
        private final Process process;
        private final String imageOrCmd;

        ProcessBackend(Process process, String imageOrCmd) {
            this.process = process;
            this.imageOrCmd = imageOrCmd;
        }

        @Override
        public RunnerSession.SandboxDescriptor descriptor() {
            return new RunnerSession.SandboxDescriptor("local", "pid-" + process.pid(), process.pid(), imageOrCmd, null, null);
        }

        @Override
        public boolean alive() { return process.isAlive(); }

        @Override
        public void awaitExit(Duration timeout) throws InterruptedException {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            if (!process.isAlive()) return;
            try { process.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (process.isAlive()) kill();
        }

        @Override
        public void kill() {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}