package com.tcl.csot.zhiye.sandbox.k8s;

import com.tcl.csot.zhiye.sandbox.RunnerException;
import com.tcl.csot.zhiye.sandbox.SandboxProvider;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class KubernetesSandboxProviderTest {
    static KubernetesClient client;
    static final String NS = "zhiye-sandbox";

    @TempDir Path runtime;
    KubernetesSandboxProvider provider;

    @BeforeEach
    void setUp() {
        client.pods().inNamespace(NS).delete();
        provider = new KubernetesSandboxProvider(client, K8sTestSupport.k8s(Map.of(
                "zhiye.sandbox.k8s.startup-timeout-seconds", "2",
                "zhiye.sandbox.k8s.gateway-url", "http://zhiye-api.zhiye.svc:8080/llm/v1",
                "zhiye.sandbox.k8s.npm-registry", "https://registry.npmmirror.com")),
                K8sTestSupport.zhiye(runtime.toString()));
        provider.pollInterval = Duration.ofMillis(50);
    }

    private SandboxProvider.Spec spec(String taskId, boolean allowNet) throws Exception {
        Path ws = Files.createDirectories(runtime.resolve("workspaces/" + taskId));
        return new SandboxProvider.Spec(taskId, ws, runtime.resolve("agents/" + taskId), allowNet,
                "http://127.0.0.1:8080/llm/v1", "tok-" + taskId, runtime.resolve("logs/tasks/" + taskId + ".log"), ev -> {});
    }

    private Pod awaitPod(String taskId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var items = client.pods().inNamespace(NS).withLabel("zhiye/task-id", taskId).list().getItems();
            if (!items.isEmpty()) return items.get(0);
            Thread.sleep(20);
        }
        fail("沙箱 Pod 没有创建");
        return null;
    }

    private void setStatus(Pod p, UnaryOperator<PodStatus> f) {
        client.pods().inNamespace(NS).withName(p.getMetadata().getName()).editStatus(pod -> {
            pod.setStatus(f.apply(new PodStatus()));
            return pod;
        });
    }

    private CompletableFuture<Exception> startAsync(SandboxProvider.Spec s) {
        return CompletableFuture.supplyAsync(() -> {
            try { provider.start(s); return null; } catch (Exception e) { return e; }
        });
    }

    @Test
    void createsPodWithSubPathsAndEnv() throws Exception {
        var s = spec("task_env1", true);
        var f = startAsync(s);
        Pod p = awaitPod("task_env1");
        Exception e = f.get(10, TimeUnit.SECONDS); // 超时（没有节点让它 Running）
        assertNotNull(e);
        var c = p.getSpec().getContainers().get(0);
        Map<String, String> env = new java.util.HashMap<>();
        c.getEnv().forEach(v -> env.put(v.getName(), v.getValue()));
        assertEquals("http://zhiye-api.zhiye.svc:8080/llm/v1", env.get("ZHIYE_GATEWAY_URL"));
        assertEquals("tok-task_env1", env.get("ZHIYE_TASK_TOKEN"));
        assertEquals("/agent", env.get("HOME"));
        assertEquals("/opt/zhiye/packages/vite-plugin-loc/index.mjs", env.get("ZHIYE_LOC_PLUGIN"));
        assertEquals("/usr/bin/chromium", env.get("ZHIYE_CHROME_PATH"));
        assertEquals("1", env.get("ALLOW_NET"));
        assertEquals("https://registry.npmmirror.com", env.get("npm_config_registry"));
        assertFalse(env.containsKey("KIMI_API_KEY"));
        assertEquals("1", p.getMetadata().getLabels().get("zhiye/allow-net"));
        assertEquals(8 * 3600 + 300, p.getSpec().getActiveDeadlineSeconds()); // 沙箱存活上限按 sandbox-max-hours，任务可以多次「继续」
        var mounts = c.getVolumeMounts();
        assertTrue(mounts.stream().anyMatch(m -> m.getMountPath().equals("/workspace") && m.getSubPath().equals("workspaces/task_env1")));
        assertTrue(mounts.stream().anyMatch(m -> m.getMountPath().equals("/agent") && m.getSubPath().equals("agents/task_env1")));
        // agent 目录由 provider 创建；模板依赖占位目录已建好
        assertTrue(Files.isDirectory(runtime.resolve("agents/task_env1")));
        assertTrue(Files.isDirectory(runtime.resolve("template-cache/node_modules")));
    }

    @Test
    void imagePullFailureIsReadableAndPodDeleted() throws Exception {
        var f = startAsync(spec("task_img", false));
        Pod p = awaitPod("task_img");
        setStatus(p, st -> new PodStatusBuilder().withPhase("Pending").withContainerStatuses(new ContainerStatusBuilder()
                .withName("runner").withImage("zhiye-runner:nope")
                .withNewState().withNewWaiting().withReason("ImagePullBackOff").withMessage("Back-off pulling image").endWaiting().endState()
                .build()).build());
        Exception e = f.get(10, TimeUnit.SECONDS);
        assertInstanceOf(RunnerException.class, e);
        assertTrue(e.getMessage().startsWith("沙箱启动失败：镜像拉取失败"), e.getMessage());
        assertTrue(e.getMessage().contains("zhiye-runner:nope"));
        assertTrue(client.pods().inNamespace(NS).withLabel("zhiye/task-id", "task_img").list().getItems().isEmpty(), "失败后应删除 Pod");
        assertTrue(provider.livePods().isEmpty());
        String log = Files.readString(runtime.resolve("logs/tasks/task_img.log"));
        assertTrue(log.contains("镜像拉取失败"));
    }

    @Test
    void unschedulableTimesOutWithReason() throws Exception {
        var f = startAsync(spec("task_sched", false));
        Pod p = awaitPod("task_sched");
        setStatus(p, st -> new PodStatusBuilder().withPhase("Pending").withConditions(new PodConditionBuilder()
                .withType("PodScheduled").withStatus("False").withReason("Unschedulable")
                .withMessage("0/3 nodes are available: 3 node(s) had untolerated taint").build()).build());
        Exception e = f.get(10, TimeUnit.SECONDS);
        assertNotNull(e);
        assertTrue(e.getMessage().contains("沙箱启动失败") && e.getMessage().contains("无法调度"), e.getMessage());
        assertTrue(client.pods().inNamespace(NS).withLabel("zhiye/task-id", "task_sched").list().getItems().isEmpty());
    }

    @Test
    void failureReasons() {
        Pod deadline = new PodBuilder().withNewStatus().withPhase("Failed").withReason("DeadlineExceeded").endStatus().build();
        assertTrue(KubernetesSandboxProvider.failureReason(deadline).contains("超过最长运行时间"));
        Pod evicted = new PodBuilder().withNewStatus().withPhase("Failed").withReason("Evicted").withMessage("node low on memory").endStatus().build();
        assertTrue(KubernetesSandboxProvider.failureReason(evicted).contains("被驱逐"));
        Pod oom = new PodBuilder().withNewStatus().withPhase("Running").withContainerStatuses(new ContainerStatusBuilder()
                .withNewState().withNewTerminated().withReason("OOMKilled").withExitCode(137).endTerminated().endState().build()).endStatus().build();
        assertTrue(KubernetesSandboxProvider.failureReason(oom).contains("OOMKilled"));
        assertTrue(KubernetesSandboxProvider.failureReason(null).contains("已被删除"));
        Pod ok = new PodBuilder().withNewStatus().withPhase("Running").withContainerStatuses(new ContainerStatusBuilder()
                .withNewState().withNewRunning().endRunning().endState().build()).endStatus().build();
        assertNull(KubernetesSandboxProvider.failureReason(ok));
        assertTrue(KubernetesSandboxProvider.startupState(ok).ready());
    }

    @Test
    void subPathMapping() throws Exception {
        assertEquals("workspaces/t1", provider.subPathOf(runtime.resolve("workspaces/t1")));
        assertThrows(IllegalStateException.class, () -> provider.subPathOf(Path.of("/etc/passwd")));
        assertThrows(IllegalStateException.class, () -> provider.subPathOf(runtime));
        var p2 = new KubernetesSandboxProvider(client, K8sTestSupport.k8s(Map.of(
                "zhiye.sandbox.k8s.ensure-template-cache-stub", "false",
                "zhiye.sandbox.k8s.sub-path-rewrites[/data/agents]", "shared/agents")), K8sTestSupport.zhiye(runtime.toString()));
        assertEquals("shared/agents/t9", p2.subPathOf(Path.of("/data/agents/t9")));
        assertEquals("workspaces/t9", p2.subPathOf(runtime.resolve("workspaces/t9")));
    }

    @Test
    void runnerCommandFixesNodeModulesLink() {
        List<String> cmd = List.of(provider.runnerCommand());
        assertEquals("sh", cmd.get(0));
        assertTrue(cmd.get(2).startsWith("cd /workspace && "));
        assertTrue(cmd.get(2).contains("T=\"$ZHIYE_TEMPLATE_NODE_MODULES\""));
        assertTrue(cmd.get(2).contains("*/vue-template-cache/node_modules) T=\"$ZHIYE_VUE_TEMPLATE_NODE_MODULES\""));
        assertTrue(cmd.get(2).contains("ln -sfn \"$T\" node_modules"));
        assertTrue(cmd.get(2).endsWith("exec node /opt/zhiye/runner/src/main.mjs"));
    }
}
