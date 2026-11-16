package com.tcl.csot.zhiye.sandbox.k8s;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnableKubernetesMockClient(crud = true)
class SandboxPodReaperTest {
    static KubernetesClient client;
    static final String NS = "zhiye-sandbox";
    final Map<String, String> statuses = new HashMap<>();
    final Set<String> live = new HashSet<>();
    SandboxPodReaper reaper;

    @BeforeEach
    void setUp() {
        client.pods().inNamespace(NS).delete();
        reaper = new SandboxPodReaper(client, K8sTestSupport.k8s(Map.of("zhiye.sandbox.k8s.reaper-grace-seconds", "60")),
                statuses::get, live, 1200);
    }

    private void pod(String name, String taskId, String phase, boolean sandboxLabel) {
        Pod p = new PodBuilder().withNewMetadata().withName(name).withNamespace(NS)
                .addToLabels("app", sandboxLabel ? "zhiye-sandbox" : "other")
                .addToLabels("zhiye/task-id", taskId)
                .addToAnnotations("zhiye/task-id", taskId)
                .addToAnnotations("zhiye/deadline-seconds", "1200")
                .endMetadata()
                .withNewSpec().addNewContainer().withName("runner").withImage("x").endContainer().endSpec()
                .withNewStatus().withPhase(phase).endStatus().build();
        client.pods().inNamespace(NS).resource(p).create();
    }

    private Set<String> names() {
        Set<String> s = new TreeSet<>();
        client.pods().inNamespace(NS).list().getItems().forEach(p -> s.add(p.getMetadata().getName()));
        return s;
    }

    @Test
    void reapsOrphansOnly() {
        statuses.put("t_active", "RUNNING");
        statuses.put("t_cancelling", "CANCELLING");
        statuses.put("t_done", "SUCCEEDED");
        statuses.put("t_failed", "FAILED");
        pod("sb-active", "t_active", "Running", true);
        pod("sb-cancelling", "t_cancelling", "Running", true);
        pod("sb-done", "t_done", "Running", true);
        pod("sb-failed", "t_failed", "Running", true);
        pod("sb-unknown", "t_unknown", "Running", true);
        pod("sb-finished", "t_active2", "Failed", true);
        pod("other-app", "t_done", "Running", false);

        Instant created = Instant.parse(client.pods().inNamespace(NS).withName("sb-done").get().getMetadata().getCreationTimestamp());
        // 宽限期内只删已结束的 Pod
        assertEquals(1, reaper.reapOnce(created.plusSeconds(10)));
        assertFalse(names().contains("sb-finished"));

        // 宽限期后：删任务已结束 / 不存在的；保留执行中和停止中的、以及其他应用的 Pod
        assertEquals(3, reaper.reapOnce(created.plusSeconds(120)));
        assertEquals(new TreeSet<>(List.of("other-app", "sb-active", "sb-cancelling")), names());

        // 超过截止时间：即使任务还在执行也删
        live.add("sb-active");
        assertEquals(2, reaper.reapOnce(created.plusSeconds(1200 + 61 + 5)));
        assertEquals(Set.of("other-app"), names());
        assertFalse(live.contains("sb-active"));
    }

    @Test
    void livePodsOfThisInstanceAreKeptUntilDeadline() {
        pod("sb-mine", "t_missing", "Running", true);
        live.add("sb-mine");
        Instant created = Instant.parse(client.pods().inNamespace(NS).withName("sb-mine").get().getMetadata().getCreationTimestamp());
        assertEquals(0, reaper.reapOnce(created.plusSeconds(300)));
        assertEquals(Set.of("sb-mine"), names());
    }
}
