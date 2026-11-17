package com.tcl.csot.zhiye.sandbox.k8s;

import com.tcl.csot.zhiye.sandbox.PathMapper;

import com.tcl.csot.zhiye.util.Ids;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kubernetes 沙箱：每个任务一个 Pod（zhiye.sandbox.provider=k8s）。
 * <ol>
 *   <li>工作进程已经在共享 RWX 卷（NAS）上准备好 workspaces/{id} 和 agents/{id}</li>
 *   <li>创建 Pod：只用 subPath 挂这两个目录；容器 sleep 到截止时间</li>
 *   <li>Pod Running 后 exec {@code sh -c 'cd /workspace && … && exec node runner'}，stdin/stdout 走 JSON 行协议</li>
 *   <li>close → 结束 exec、删除 Pod（grace 0）</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "zhiye.sandbox.provider", havingValue = "k8s")
public class KubernetesSandboxProvider implements SandboxProvider {
    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxProvider.class);

    private final KubernetesClient client;
    private final K8sSandboxProperties cfg;
    private final ZhiyeProperties props;
    /** 本实例上存活的沙箱 Pod 名（回收器会跳过它们，直到超过截止时间） */
    private final Set<String> live = ConcurrentHashMap.newKeySet();
    Duration pollInterval = Duration.ofSeconds(1);
    Duration monitorInterval = Duration.ofSeconds(3);

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

    Map<String, String> runnerEnv(Spec spec) {
        Map<String, String> env = new LinkedHashMap<>();
        String gw = cfg.gatewayUrl() != null && !cfg.gatewayUrl().isBlank() ? cfg.gatewayUrl() : spec.gatewayUrl();
        env.put("ZHIYE_GATEWAY_URL", gw);
        env.put("ZHIYE_TASK_TOKEN", spec.taskToken());
        env.put("ZHIYE_TASK_ID", spec.taskId());
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
                + " case \"$(readlink node_modules)\" in */vue-template-cache/node_modules) T=\"$ZHIYE_VUE_TEMPLATE_NODE_MODULES\";; esac;"
                + " if [ \"$(readlink node_modules)\" != \"$T\" ]; then ln -sfn \"$T\" node_modules; fi; fi"
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
        Pod pod = SandboxPodFactory.build(cfg, new SandboxPodFactory.Input(name, spec.taskId(), wsSub, agentSub,
                spec.allowNet(), deadlineSeconds(), runnerEnv(spec)));

        PrintWriter logWriter = new PrintWriter(new OutputStreamWriter(new FileOutputStream(spec.logFile().toFile(), true), StandardCharsets.UTF_8));
        logWriter.println("[java] 创建沙箱 Pod " + cfg.namespace() + "/" + name + "（image=" + cfg.image()
                + (cfg.runtimeClassName() == null || cfg.runtimeClassName().isBlank() ? "" : "，runtimeClass=" + cfg.runtimeClassName())
                + "，workspace=" + wsSub + "，agent=" + agentSub + "，allowNet=" + spec.allowNet() + "）");
        logWriter.flush();

        PodResource res = client.pods().inNamespace(cfg.namespace()).withName(name);
        live.add(name);
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
            PodOps ops = new PodOps(res, name);
            PathMapper mapper = new PathMapper(spec.workspace().toAbsolutePath().normalize().toString(),
                    spec.agentDir().toAbsolutePath().normalize().toString(), SandboxPodFactory.POD_WORKSPACE, SandboxPodFactory.POD_AGENT);
            var resCfg = cfg.resources();
            return new K8sRunnerSession(spec.taskId(), new FabricExec(watch), ops, spec.onEvent(), logWriter, mapper, monitorInterval,
                    new RunnerSession.SandboxDescriptor("k8s", cfg.namespace() + "/" + name, null, cfg.image(),
                            resCfg == null ? null : resCfg.cpuLimit(), resCfg == null ? null : resCfg.memoryLimit()));
        } catch (Exception e) {
            logWriter.println("[java] " + e.getMessage());
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

    private final class PodOps implements K8sRunnerSession.PodOps {
        private final PodResource res;
        private final String name;
        PodOps(PodResource res, String name) { this.res = res; this.name = name; }
        @Override public String failureReason() { return KubernetesSandboxProvider.failureReason(res.get()); }
        @Override public void delete() { deletePod(res, name); }
        @Override public String name() { return name; }
    }

    private static final class FabricExec implements K8sRunnerSession.Exec {
        private final ExecWatch w;
        private final CompletableFuture<Integer> code = new CompletableFuture<>();
        FabricExec(ExecWatch w) {
            this.w = w;
            w.exitCode().whenComplete((c, e) -> code.complete(e != null || c == null ? -1 : c));
        }
        @Override public OutputStream stdin() { return w.getInput(); }
        @Override public InputStream stdout() { return w.getOutput(); }
        @Override public InputStream stderr() { return w.getError(); }
        @Override public CompletableFuture<Integer> exitCode() { return code; }
        @Override public void close() { try { w.close(); } catch (Exception ignored) { } code.complete(-1); }
    }
}
