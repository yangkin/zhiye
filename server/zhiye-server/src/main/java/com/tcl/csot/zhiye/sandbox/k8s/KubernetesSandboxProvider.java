package com.tcl.csot.zhiye.sandbox.k8s;

import com.tcl.csot.zhiye.sandbox.PathMapper;
import com.tcl.csot.zhiye.sandbox.http.HttpRunnerBackend;
import com.tcl.csot.zhiye.sandbox.http.HttpRunnerSession;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Threads;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.sandbox.RunnerException;
import com.tcl.csot.zhiye.sandbox.RunnerSession;
import com.tcl.csot.zhiye.sandbox.SandboxProvider;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Kubernetes 沙箱：每个任务一个 Pod（zhiye.sandbox.provider=k8s）。
 * <ol>
 *   <li>工作进程已经在共享 RWX 卷（NAS）上准备好 workspaces/{id} 和 agents/{id}</li>
 *   <li>创建 Pod：只用 subPath 挂这两个目录；容器 sleep 到截止时间</li>
 *   <li>Pod Running 后 exec {@code sh -c 'cd /workspace && … && exec node runner'} 启动 runner（HTTP 服务）</li>
 *   <li>工作进程通过 Pod IP 直连 runner 控制端口（HTTP + SSE，见 docs/runner-protocol.md），close → 删除 Pod（grace 0）</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "zhiye.sandbox.provider", havingValue = "k8s")
public class KubernetesSandboxProvider implements SandboxProvider {
    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxProvider.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KubernetesClient client;
    private final K8sSandboxProperties cfg;
    private final ZhiyeProperties props;
    /** 本实例上存活的沙箱 Pod 名（回收器会跳过它们，直到超过截止时间） */
    private final Set<String> live = ConcurrentHashMap.newKeySet();
    Duration pollInterval = Duration.ofSeconds(1);

    public KubernetesSandboxProvider(KubernetesClient client, K8sSandboxProperties cfg, ZhiyeProperties props) {
        this.client = client;
        this.cfg = cfg;
        this.props = props;
        if (cfg.ensureTemplateCacheStub()) ensureTemplateCacheStub();
    }

    /**
     * TaskExecutor 只在 {runtime}/template-cache/node_modules 存在时才给 template 项目建 node_modules 链接。
     * 工作进程本身不需要模板依赖（依赖打在 runner 镜像里），所以这里建一个空目录让链接照常创建，
     * Pod 里再把链接改指到镜像内的 /opt/zhiye/template-cache/node_modules。
     */
    private void ensureTemplateCacheStub() {
        stub("template-cache", cfg.templateNodeModules());
        stub("vue-template-cache", cfg.vueTemplateNodeModules());
        stub("fullstack-template-cache", "/opt/zhiye/fullstack-template-cache/node_modules");
    }

    private void stub(String dir, String imagePath) {
        Path nm = props.runtimePath().resolve(dir + "/node_modules");
        try {
            if (!Files.exists(nm)) {
                Files.createDirectories(nm);
                Files.writeString(nm.getParent().resolve("README.txt"),
                        "K8s 沙箱模式下模板依赖打在 runner 镜像的 " + imagePath + "，这里只是占位目录。\n");
            }
        } catch (IOException e) {
            log.warn("无法创建模板依赖占位目录 {}：{}", nm, e.getMessage());
        }
    }

    Set<String> livePods() { return live; }

    // ---------------- 路径 ----------------
    Path volumeRoot() {
        String v = cfg.volumeRoot();
        return v == null || v.isBlank() ? props.runtimePath() : Path.of(v).toAbsolutePath().normalize();
    }

    /** 工作进程上的目录 → 共享卷内的 subPath */
    String subPathOf(Path dir) {
        String abs = dir.toAbsolutePath().normalize().toString().replace('\\', '/');
        for (var e : cfg.subPathRewrites().entrySet()) {
            String from = Path.of(e.getKey()).toAbsolutePath().normalize().toString().replace('\\', '/');
            if (abs.equals(from) || abs.startsWith(from + "/")) {
                String to = e.getValue() == null ? "" : e.getValue().replaceAll("^/+|/+$", "");
                String rest = abs.substring(from.length()).replaceAll("^/+", "");
                return validSubPath(to.isEmpty() ? rest : rest.isEmpty() ? to : to + "/" + rest, abs);
            }
        }
        Path root = volumeRoot();
        Path p = Path.of(abs);
        if (!p.startsWith(root))
            throw new IllegalStateException("目录 " + abs + " 不在共享卷 " + root + " 下（请配置 zhiye.sandbox.k8s.volume-root 或 sub-path-rewrites）");
        return validSubPath(root.relativize(p).toString(), abs);
    }

    private static String validSubPath(String s, String abs) {
        if (s.isEmpty() || s.startsWith("/") || s.equals("..") || s.startsWith("../") || s.contains("/../") || s.endsWith("/.."))
            throw new IllegalStateException("无法把 " + abs + " 映射到共享卷子目录");
        return s.replace('\\', '/');
    }

    long deadlineSeconds() { return props.task().sandboxMaxHours() * 3600L + cfg.deadlineMarginSeconds(); }

    Map<String, String> runnerEnv(Spec spec, String controlToken) {
        Map<String, String> env = new LinkedHashMap<>();
        String gw = cfg.gatewayUrl() != null && !cfg.gatewayUrl().isBlank() ? cfg.gatewayUrl() : spec.gatewayUrl();
        env.put("ZHIYE_GATEWAY_URL", gw);
        env.put("ZHIYE_TASK_TOKEN", spec.taskToken());
        env.put("ZHIYE_TASK_ID", spec.taskId());
        env.put("ZHIYE_RUNNER_TOKEN", controlToken);
        env.put("ZHIYE_RUNNER_HOST", "0.0.0.0");
        env.put("ZHIYE_RUNNER_PORT", String.valueOf(cfg.controlPort()));
        env.put("ZHIYE_LOC_PLUGIN", cfg.locPlugin());
        env.put("ZHIYE_BRIDGE_PATH", cfg.bridgePath());
        env.put("ZHIYE_CHROME_PATH", cfg.chromePath());
        env.put("ZHIYE_TEMPLATE_NODE_MODULES", cfg.templateNodeModules());
        env.put("ZHIYE_VUE_TEMPLATE_NODE_MODULES", cfg.vueTemplateNodeModules());
        env.put("HOME", SandboxPodFactory.POD_AGENT);
        env.put("TMPDIR", "/tmp");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.put("LANG", "C.UTF-8");
        if (spec.allowNet()) {
            env.put("ALLOW_NET", "1");
            env.put("npm_config_cache", "/tmp/npm-cache");
            if (cfg.npmRegistry() != null && !cfg.npmRegistry().isBlank()) env.put("npm_config_registry", cfg.npmRegistry());
        }
        env.putAll(cfg.extraEnv());
        return env;
    }

    /**
     * exec 的命令。工作进程建的 node_modules 是指向它自己路径的绝对链接，在 Pod 里解析不到，
     * 所以先把它改指到镜像里的模板依赖（Vue 模板项目指到 Vue 的那份），再 exec runner（cwd = /workspace）。
     */
    String[] runnerCommand() {
        String script = "cd " + SandboxPodFactory.POD_WORKSPACE
                + " && if [ -L node_modules ]; then T=\"$ZHIYE_TEMPLATE_NODE_MODULES\";"
                + " case \"$(readlink node_modules)\" in */vue-template-cache/node_modules) T=\"$ZHIYE_VUE_TEMPLATE_NODE_MODULES\";;"
                + " */fullstack-template-cache/node_modules) T=\"${ZHIYE_FULLSTACK_TEMPLATE_NODE_MODULES:-/opt/zhiye/fullstack-template-cache/node_modules}\";; esac;"
                + " if [ \"$(readlink node_modules)\" != \"$T\" ]; then ln -sfn \"$T\" node_modules; fi; fi"
                + " && if [ -L frontend/node_modules ]; then FT=\"$ZHIYE_VUE_TEMPLATE_NODE_MODULES\";"
                + " if [ \"$(readlink frontend/node_modules)\" != \"$FT\" ]; then ln -sfn \"$FT\" frontend/node_modules; fi;"
                + " elif [ ! -e frontend/node_modules ] && [ -f frontend/package.json ] && grep -q '\"vue\"' frontend/package.json 2>/dev/null; then"
                + " if [ -d \"$ZHIYE_VUE_TEMPLATE_NODE_MODULES\" ]; then ln -sfn \"$ZHIYE_VUE_TEMPLATE_NODE_MODULES\" frontend/node_modules; fi; fi"
                + " && if [ -d \"${ZHIYE_M2_REPO:-/opt/zhiye/m2/repository}\" ] && [ ! -e \"${HOME:-/agent}/.m2/repository\" ]; then"
                + " mkdir -p \"${HOME:-/agent}/.m2\" && ln -sfn \"${ZHIYE_M2_REPO:-/opt/zhiye/m2/repository}\" \"${HOME:-/agent}/.m2/repository\"; fi"
                + " && exec node " + cfg.runnerScript();
        return new String[]{"sh", "-c", script};
    }

    // ---------------- 启动 ----------------
    @Override
    public RunnerSession start(Spec spec) throws Exception {
        Files.createDirectories(spec.agentDir());
        Files.createDirectories(spec.logFile().getParent());
        if (!Files.isDirectory(spec.workspace())) throw new IllegalStateException("工作区不存在：" + spec.workspace());
        String wsSub = subPathOf(spec.workspace());
        String agentSub = subPathOf(spec.agentDir());
        String name = SandboxPodFactory.podName(spec.taskId(), Ids.random(5));
        String controlToken = HexFormat.of().formatHex(newToken());
        Pod pod = SandboxPodFactory.build(cfg, new SandboxPodFactory.Input(name, spec.taskId(), wsSub, agentSub,
                spec.allowNet(), deadlineSeconds(), runnerEnv(spec, controlToken)));

        PrintWriter logWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(spec.logFile().toFile(), true), StandardCharsets.UTF_8));
        logWriter.println("[java] 创建沙箱 Pod " + cfg.namespace() + "/" + name + "（image=" + cfg.image()
                + (cfg.runtimeClassName() == null || cfg.runtimeClassName().isBlank() ? "" : "，runtimeClass=" + cfg.runtimeClassName())
                + "，workspace=" + wsSub + "，agent=" + agentSub + "，allowNet=" + spec.allowNet() + "）");
        logWriter.flush();

        PodResource res = client.pods().inNamespace(cfg.namespace()).withName(name);
        live.add(name);
        PodHttpBackend backend = null;
        HttpRunnerSession session = null;
        try {
            client.pods().inNamespace(cfg.namespace()).resource(pod).create();
            awaitRunning(res, name, Duration.ofSeconds(cfg.startupTimeoutSeconds()));
            if (cfg.isolationCheck()) {
                long ms = awaitIsolation(res);
                logWriter.println("[java] 网络隔离已生效（" + ms + " ms）");
            }
            logWriter.println("[java] 沙箱 Pod 已运行，启动 runner");
            logWriter.flush();
            ExecWatch watch = startExec(res);
            backend = new PodHttpBackend(res, name, watch, logWriter);
            String ip = awaitPodIp(res, name);
            PathMapper mapper = new PathMapper(spec.workspace().toAbsolutePath().normalize().toString(),
                    spec.agentDir().toAbsolutePath().normalize().toString(), SandboxPodFactory.POD_WORKSPACE, SandboxPodFactory.POD_AGENT);
            var resCfg = cfg.resources();
            backend.descriptor = new RunnerSession.SandboxDescriptor("k8s", cfg.namespace() + "/" + name, null, cfg.image(),
                    resCfg == null ? null : resCfg.cpuLimit(), resCfg == null ? null : resCfg.memoryLimit());
            session = new HttpRunnerSession("http://" + ip + ":" + cfg.controlPort(), Map.of(), controlToken,
                    backend, spec.onEvent(), logWriter, mapper);
            session.waitReady(Duration.ofSeconds(cfg.startupTimeoutSeconds()));
            return session;
        } catch (Exception e) {
            logWriter.println("[java] " + e.getMessage());
            if (backend != null) logWriter.print(backend.recentStderr());
            if (session != null) {
                try { session.close(); } catch (Exception ignored) { }
            }
            logWriter.close();
            deletePod(res, name);
            throw e;
        }
    }

    private ExecWatch startExec(PodResource res) {
        return res.inContainer(SandboxPodFactory.CONTAINER)
                .redirectingInput()
                .redirectingOutput()
                .redirectingError()
                .exec(runnerCommand());
    }

    /** Pod Running 后等 CNI 分配 IP（通常已经有）。 */
    private String awaitPodIp(PodResource res, String name) throws Exception {
        Instant end = Instant.now().plusSeconds(15);
        while (true) {
            Pod p = res.get();
            if (p == null) throw new RunnerException("沙箱启动失败：Pod " + name + " 已被删除");
            String ip = p.getStatus() == null ? null : p.getStatus().getPodIP();
            if (ip != null && !ip.isBlank()) return ip;
            if (Instant.now().isAfter(end)) throw new RunnerException("沙箱启动失败：Pod 没有分配到 IP");
            Thread.sleep(pollInterval.toMillis());
        }
    }

    /** 在 Pod 里反复连接一个被策略拒绝的地址，连不上（连续两次）才算隔离生效；返回等待的毫秒数。 */
    static final String ISOLATION_SCRIPT = String.join("",
            "const net=require('net');const [h,p]=process.argv[1].split(':');const end=Date.now()+(+process.argv[2]);",
            "const t0=Date.now();let blocked=0;const out=(m,c)=>process.stdout.write(m+'\\n',()=>process.exit(c));",
            "function once(){const s=net.connect({host:h,port:+p,timeout:700});let done=false;",
            "const fin=(open)=>{if(done)return;done=true;s.destroy();",
            "if(open){blocked=0}else if(++blocked>=2){return out(String(Date.now()-t0),0)}",
            "if(Date.now()>end){return out('网络隔离在 '+(Date.now()-t0)+' ms 内没有生效：仍能连上 '+h+':'+p,3)}",
            "setTimeout(once,open?300:50)};",
            "s.on('connect',()=>fin(true));s.on('timeout',()=>fin(false));s.on('error',()=>fin(false))}",
            "once()");

    long awaitIsolation(PodResource res) throws Exception {
        long timeoutMs = cfg.isolationCheckTimeoutSeconds() * 1000L;
        var out = new java.io.ByteArrayOutputStream();
        try (ExecWatch w = res.inContainer(SandboxPodFactory.CONTAINER).writingOutput(out).writingError(out)
                .exec("node", "-e", ISOLATION_SCRIPT, cfg.isolationCheckTarget(), String.valueOf(timeoutMs))) {
            Integer code = w.exitCode().get(timeoutMs + 15_000, java.util.concurrent.TimeUnit.MILLISECONDS);
            String text = out.toString(StandardCharsets.UTF_8).trim();
            if (code == null || code != 0)
                throw new RunnerException("沙箱启动失败：网络隔离策略没有生效（" + (text.isEmpty() ? "退出码 " + code : text) + "）");
            try { return Long.parseLong(text.lines().reduce((a, b) -> b).orElse("0").trim()); } catch (NumberFormatException e) { return 0; }
        } catch (java.util.concurrent.TimeoutException e) {
            throw new RunnerException("沙箱启动失败：网络隔离检查超时");
        }
    }

    /** 等待 Pod Running；镜像拉取失败、无法调度、Pod 失败时给出中文原因。 */
    void awaitRunning(PodResource res, String name, Duration timeout) throws Exception {
        Instant end = Instant.now().plus(timeout);
        String lastWait = "等待调度";
        while (true) {
            Pod p = res.get();
            if (p == null) throw new RunnerException("沙箱启动失败：Pod " + name + " 已被删除");
            Startup s = startupState(p);
            if (s.ready) return;
            if (s.fatal != null) throw new RunnerException("沙箱启动失败：" + s.fatal);
            if (s.waiting != null) lastWait = s.waiting;
            if (Instant.now().isAfter(end))
                throw new RunnerException("沙箱启动失败：" + timeout.toSeconds() + " 秒内没有就绪（" + lastWait + "）");
            Thread.sleep(pollInterval.toMillis());
        }
    }

    record Startup(boolean ready, String fatal, String waiting) {}

    static Startup startupState(Pod p) {
        var st = p.getStatus();
        String phase = st == null ? null : st.getPhase();
        if ("Failed".equals(phase) || "Succeeded".equals(phase)) return new Startup(false, failureReason(p), null);
        if (st != null && st.getContainerStatuses() != null) {
            for (ContainerStatus cs : st.getContainerStatuses()) {
                var w = cs.getState() == null ? null : cs.getState().getWaiting();
                if (w != null && w.getReason() != null) {
                    String r = w.getReason();
                    String msg = w.getMessage() == null ? "" : "：" + w.getMessage();
                    switch (r) {
                        case "ImagePullBackOff", "ErrImagePull", "InvalidImageName", "ErrImageNeverPull", "RegistryUnavailable" -> {
                            if (!"ErrImagePull".equals(r)) return new Startup(false, "镜像拉取失败（" + cs.getImage() + "，" + r + "）" + msg, null);
                            return new Startup(false, null, "镜像拉取失败，正在重试（" + cs.getImage() + "）" + msg);
                        }
                        case "CreateContainerConfigError", "CreateContainerError", "RunContainerError", "CrashLoopBackOff" -> {
                            return new Startup(false, "容器无法启动（" + r + "）" + msg, null);
                        }
                        default -> { return new Startup(false, null, "容器创建中（" + r + "）"); }
                    }
                }
                var t = cs.getState() == null ? null : cs.getState().getTerminated();
                if (t != null) return new Startup(false, "容器已退出（" + t.getReason() + "，退出码 " + t.getExitCode() + "）", null);
            }
        }
        if ("Running".equals(phase)) {
            boolean running = st.getContainerStatuses() != null && st.getContainerStatuses().stream()
                    .anyMatch(cs -> cs.getState() != null && cs.getState().getRunning() != null);
            if (running) return new Startup(true, null, null);
        }
        if (st != null && st.getConditions() != null) {
            for (PodCondition c : st.getConditions()) {
                if ("PodScheduled".equals(c.getType()) && "False".equals(c.getStatus())) {
                    return new Startup(false, null, "没有可用的沙箱节点，无法调度（" + (c.getReason() == null ? "" : c.getReason())
                            + (c.getMessage() == null ? "" : "：" + c.getMessage()) + "）");
                }
            }
        }
        return new Startup(false, null, phase == null ? "等待调度" : "Pod 状态 " + phase);
    }

    /** Pod 健康时返回 null；否则返回中文原因。 */
    static String failureReason(Pod p) {
        if (p == null) return "沙箱 Pod 已被删除";
        if (p.getMetadata() != null && p.getMetadata().getDeletionTimestamp() != null) return "沙箱 Pod 已被删除";
        var st = p.getStatus();
        if (st == null) return null;
        String reason = st.getReason();
        if ("DeadlineExceeded".equals(reason)) return "超过最长运行时间，沙箱 Pod 已被 Kubernetes 终止（DeadlineExceeded）";
        if ("Evicted".equals(reason)) return "沙箱 Pod 被驱逐（" + (st.getMessage() == null ? "节点资源不足" : st.getMessage()) + "）";
        if (st.getContainerStatuses() != null) {
            for (ContainerStatus cs : st.getContainerStatuses()) {
                var t = cs.getState() == null ? null : cs.getState().getTerminated();
                if (t != null) {
                    if ("OOMKilled".equals(t.getReason())) return "内存超出上限，沙箱被终止（OOMKilled）";
                    return "沙箱容器已退出（" + t.getReason() + "，退出码 " + t.getExitCode() + "）";
                }
            }
        }
        if ("Failed".equals(st.getPhase())) return "沙箱 Pod 失败（" + reason + (st.getMessage() == null ? "" : "：" + st.getMessage()) + "）";
        if ("Succeeded".equals(st.getPhase())) return "沙箱 Pod 已结束";
        return null;
    }

    private void deletePod(PodResource res, String name) {
        live.remove(name);
        try { res.withGracePeriod(0).delete(); } catch (Exception e) { log.warn("删除沙箱 Pod {} 失败：{}", name, e.getMessage()); }
    }

    private static byte[] newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return b;
    }

    /**
     * 沙箱 Pod 上的 runner 生命周期。runner 的日志走 SSE（与 OpenSandbox 一致），
     * exec 的 stderr 只保留最近 200 行，用于 runner 没起来 / 异常退出时的诊断。
     */
    private final class PodHttpBackend implements HttpRunnerBackend {
        private final PodResource res;
        private final String name;
        private final ExecWatch watch;
        private final PrintWriter logWriter;
        private final Deque<String> stderrTail = new ArrayDeque<>(200);
        private volatile boolean destroyed;
        volatile RunnerSession.SandboxDescriptor descriptor;

        PodHttpBackend(PodResource res, String name, ExecWatch watch, PrintWriter logWriter) {
            this.res = res;
            this.name = name;
            this.watch = watch;
            this.logWriter = logWriter;
            Threads.start("k8s-runner-err-" + name, this::collectStderr);
            watch.exitCode().whenComplete((code, err) -> {
                if (destroyed) return;
                logLine("[java] runner 进程已退出（退出码 " + (err != null || code == null ? "未知" : code) + "）");
                logWriter.print(recentStderr());
                logWriter.flush();
            });
        }

        private void collectStderr() {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(watch.getError(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (stderrTail) {
                        if (stderrTail.size() >= 200) stderrTail.pollFirst();
                        stderrTail.addLast(line);
                    }
                }
            } catch (IOException ignored) { }
        }

        synchronized String recentStderr() {
            synchronized (stderrTail) {
                return stderrTail.isEmpty() ? "" : "[java] runner stderr（最近 " + stderrTail.size() + " 行）：\n"
                        + String.join("\n", stderrTail) + "\n";
            }
        }

        private void logLine(String s) {
            synchronized (logWriter) { logWriter.println(s); logWriter.flush(); }
        }

        @Override
        public RunnerSession.SandboxDescriptor descriptor() { return descriptor; }

        @Override
        public boolean alive() {
            if (destroyed || watch.exitCode().isDone()) return false;
            try { return failureReason(res.get()) == null; } catch (Exception e) { return false; }
        }

        @Override
        public void awaitExit(Duration timeout) {
            try { watch.exitCode().get(timeout.toMillis(), TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
        }

        @Override
        public void close() { destroy(); }

        @Override
        public void kill() { destroy(); }

        private synchronized void destroy() {
            if (destroyed) return;
            destroyed = true;
            try { watch.close(); } catch (Exception ignored) { }
            deletePod(res, name);
            logLine("[java] 已删除沙箱 Pod " + name);
        }
    }
}
