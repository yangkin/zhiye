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
        List<String> cmd = new ArrayList<>();
        if (props.sandbox().seatbelt()) {
            Path profile = props.resolveFromRoot(props.sandbox().seatbeltProfile());
            if (Files.isRegularFile(profile) && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) {
                cmd.addAll(List.of("/usr/bin/sandbox-exec", "-f", profile.toString(),
                        "-D", "WORKSPACE=" + spec.workspace().toRealPath(),
                        "-D", "AGENT_DIR=" + spec.agentDir().toRealPath(),
                        "-D", "ZHIYE_ROOT=" + root.toRealPath(),
                        "-D", "NODE_DIR=" + realOrSelf(nodeDir.getParent()),
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
        env.put("HOME", spec.agentDir().toString());
        env.put("PATH", nodeDir + ":/usr/bin:/bin");
        env.put("LANG", "en_US.UTF-8");
        if (spec.allowNet()) {
            env.put("ALLOW_NET", "1");
            // 导入任务安装依赖：共享 npm 缓存（沙箱允许写 /private/tmp），可用 ZHIYE_NPM_REGISTRY 指定镜像
            env.put("npm_config_cache", "/private/tmp/zhiye-npm-cache");
            String registry = System.getenv("ZHIYE_NPM_REGISTRY");
            if (registry != null && !registry.isBlank()) env.put("npm_config_registry", registry);
        }
        env.put("TMPDIR", spec.agentDir().resolve("tmp").toString());
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
        HttpRunnerSession session = new HttpRunnerSession("http://127.0.0.1:" + port, Map.of(), controlToken,
                new ProcessBackend(p), spec.onEvent(), logWriter);
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

    /** 本机 runner 进程的生命周期。 */
    private static final class ProcessBackend implements HttpRunnerBackend {
        private final Process process;

        ProcessBackend(Process process) { this.process = process; }

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