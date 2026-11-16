package com.tcl.csot.zhiye.sandbox.k8s;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class SandboxPodFactoryTest {
    static KubernetesClient client;

    private static K8sSandboxProperties props() {
        return K8sTestSupport.k8s(Map.of(
                "zhiye.sandbox.k8s.image", "registry.example.com/zhiye/runner:1.0",
                "zhiye.sandbox.k8s.runtime-class-name", "gvisor",
                "zhiye.sandbox.k8s.node-selector[zhiye/pool]", "sandbox",
                "zhiye.sandbox.k8s.tolerations[0].key", "zhiye/sandbox",
                "zhiye.sandbox.k8s.tolerations[0].value", "true",
                "zhiye.sandbox.k8s.image-pull-secrets[0]", "acr"));
    }

    @Test
    void podSpecIsLockedDown() {
        K8sSandboxProperties p = props();
        Pod pod = SandboxPodFactory.build(p, new SandboxPodFactory.Input("sb-task-abc-x1", "task_ABC", "workspaces/task_ABC",
                "agents/task_ABC", false, 1200, Map.of("ZHIYE_TASK_TOKEN", "t")));
        // 能被 API Server 接受（CRUD 模拟服务器会做序列化往返）
        Pod created = client.pods().inNamespace("zhiye-sandbox").resource(pod).create();

        assertEquals("zhiye-sandbox", created.getMetadata().getNamespace());
        assertEquals(Map.of("app", "zhiye-sandbox", "zhiye/task-id", "task_ABC", "zhiye/allow-net", "0"), created.getMetadata().getLabels());
        var spec = created.getSpec();
        assertEquals("Never", spec.getRestartPolicy());
        assertEquals(1200L, spec.getActiveDeadlineSeconds());
        assertFalse(spec.getAutomountServiceAccountToken());
        assertFalse(spec.getEnableServiceLinks());
        assertEquals("gvisor", spec.getRuntimeClassName());
        assertEquals(Map.of("zhiye/pool", "sandbox"), spec.getNodeSelector());
        assertEquals("zhiye/sandbox", spec.getTolerations().get(0).getKey());
        assertEquals("NoSchedule", spec.getTolerations().get(0).getEffect());
        assertEquals("acr", spec.getImagePullSecrets().get(0).getName());
        assertEquals(10001L, spec.getSecurityContext().getFsGroup());
        assertTrue(spec.getSecurityContext().getRunAsNonRoot());
        assertEquals("RuntimeDefault", spec.getSecurityContext().getSeccompProfile().getType());

        var c = spec.getContainers().get(0);
        assertEquals("registry.example.com/zhiye/runner:1.0", c.getImage());
        assertEquals("IfNotPresent", c.getImagePullPolicy());
        assertEquals(java.util.List.of("sleep", "1200"), c.getCommand());
        var sc = c.getSecurityContext();
        assertTrue(sc.getReadOnlyRootFilesystem());
        assertTrue(sc.getRunAsNonRoot());
        assertEquals(10001L, sc.getRunAsUser());
        assertFalse(sc.getAllowPrivilegeEscalation());
        assertEquals(java.util.List.of("ALL"), sc.getCapabilities().getDrop());
        assertEquals("2", c.getResources().getLimits().get("cpu").toString());
        assertEquals("4Gi", c.getResources().getLimits().get("memory").toString());
        assertEquals("2", c.getResources().getRequests().get("cpu").toString());

        // 只挂本任务的两个子目录 + /tmp
        Map<String, VolumeMount> mounts = new java.util.HashMap<>();
        c.getVolumeMounts().forEach(m -> mounts.put(m.getMountPath(), m));
        assertEquals(4, mounts.size());
        assertTrue(mounts.containsKey("/dev/shm"));
        assertEquals("workspaces/task_ABC", mounts.get("/workspace").getSubPath());
        assertEquals("agents/task_ABC", mounts.get("/agent").getSubPath());
        assertNull(mounts.get("/tmp").getSubPath());
        var runtimeVol = spec.getVolumes().stream().filter(v -> v.getName().equals("runtime")).findFirst().orElseThrow();
        assertEquals("zhiye-runtime", runtimeVol.getPersistentVolumeClaim().getClaimName());
        assertNotNull(spec.getVolumes().stream().filter(v -> v.getName().equals("tmp")).findFirst().orElseThrow().getEmptyDir());
    }

    @Test
    void allowNetLabelAndNoRuntimeClassByDefault() {
        K8sSandboxProperties p = K8sTestSupport.k8s(Map.of());
        Pod pod = SandboxPodFactory.build(p, new SandboxPodFactory.Input("sb-x", "task_x", "w", "a", true, 60, Map.of()));
        assertEquals("1", pod.getMetadata().getLabels().get("zhiye/allow-net"));
        assertNull(pod.getSpec().getRuntimeClassName());
        assertNull(pod.getSpec().getNodeSelector());
        assertNull(pod.getSpec().getTolerations());
    }

    @Test
    void namesAreDnsSafe() {
        String n = SandboxPodFactory.podName("task_AbC_9", "q1w2e");
        assertEquals("sb-task-abc-9-q1w2e", n);
        assertTrue(SandboxPodFactory.podName("x".repeat(100), "abcde").length() <= 63);
        assertEquals("a_b-c", SandboxPodFactory.labelValue("a_b/c"));
    }
}
