package com.tcl.csot.zhiye.sandbox.k8s;

import com.tcl.csot.zhiye.repository.TaskRepository;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.function.Function;

/**
 * 孤儿沙箱 Pod 回收（每个 api 实例都跑，删除是幂等的）：按标签 app=zhiye-sandbox 列出 Pod，删除
 * ① 已结束（Succeeded/Failed）的；② 任务已不在执行中的（工作进程崩溃、close 没执行到）；③ 超过截止时间的。
 * 刚创建的 Pod 有一段宽限期，避免和正在启动的任务抢。
 */
@Component
@Profile("api")
@ConditionalOnProperty(name = "zhiye.sandbox.provider", havingValue = "k8s")
public class SandboxPodReaper {
    private static final Logger log = LoggerFactory.getLogger(SandboxPodReaper.class);
    static final Set<String> ACTIVE = Set.of("PREPARING", "RUNNING", "VERIFYING", "COMMITTING", "CANCELLING");

    private final KubernetesClient client;
    private final K8sSandboxProperties cfg;
    private final Function<String, String> taskStatus;
    private final Set<String> live;
    private final long deadlineSeconds;

    @Autowired
    public SandboxPodReaper(KubernetesClient client, K8sSandboxProperties cfg, TaskRepository tasks, KubernetesSandboxProvider provider) {
        this(client, cfg, tasks::status, provider.livePods(), provider.deadlineSeconds());
    }

    SandboxPodReaper(KubernetesClient client, K8sSandboxProperties cfg, Function<String, String> taskStatus, Set<String> live, long deadlineSeconds) {
        this.client = client;
        this.cfg = cfg;
        this.taskStatus = taskStatus;
        this.live = live;
        this.deadlineSeconds = deadlineSeconds;
    }

    @Scheduled(initialDelayString = "#{${zhiye.sandbox.k8s.reaper-interval-seconds:60} * 1000}",
            fixedDelayString = "#{${zhiye.sandbox.k8s.reaper-interval-seconds:60} * 1000}")
    public void scheduled() {
        if (!cfg.reaperEnabled()) return;
        try {
            int n = reapOnce(Instant.now());
            if (n > 0) log.info("回收了 {} 个孤儿沙箱 Pod", n);
        } catch (Exception e) {
            log.warn("回收沙箱 Pod 失败：{}", e.getMessage());
        }
    }

    /** 返回删除的 Pod 数 */
    int reapOnce(Instant now) {
        int n = 0;
        for (Pod p : client.pods().inNamespace(cfg.namespace()).withLabel(SandboxPodFactory.APP_LABEL, SandboxPodFactory.APP_VALUE).list().getItems()) {
            String why = reason(p, now);
            if (why == null) continue;
            String name = p.getMetadata().getName();
            log.info("删除沙箱 Pod {}：{}", name, why);
            try {
                client.pods().inNamespace(cfg.namespace()).withName(name).withGracePeriod(0).delete();
                live.remove(name);
                n++;
            } catch (Exception e) {
                log.warn("删除沙箱 Pod {} 失败：{}", name, e.getMessage());
            }
        }
        return n;
    }

    String reason(Pod p, Instant now) {
        var md = p.getMetadata();
        if (md.getDeletionTimestamp() != null) return null;
        Instant created = md.getCreationTimestamp() == null ? now : OffsetDateTime.parse(md.getCreationTimestamp()).toInstant();
        Duration age = Duration.between(created, now);
        String phase = p.getStatus() == null ? null : p.getStatus().getPhase();
        if ("Succeeded".equals(phase) || "Failed".equals(phase)) return "Pod 已结束（" + phase + "）";
        long deadline = deadlineSeconds;
        String ann = md.getAnnotations() == null ? null : md.getAnnotations().get(SandboxPodFactory.CREATED_ANNOTATION);
        if (ann != null) try { deadline = Long.parseLong(ann); } catch (NumberFormatException ignored) { }
        if (age.getSeconds() > deadline + cfg.reaperGraceSeconds()) return "超过截止时间（已运行 " + age.toMinutes() + " 分钟）";
        if (age.getSeconds() < cfg.reaperGraceSeconds()) return null;
        if (live.contains(md.getName())) return null;
        String taskId = md.getAnnotations() == null ? null : md.getAnnotations().get(SandboxPodFactory.TASK_LABEL);
        if (taskId == null && md.getLabels() != null) taskId = md.getLabels().get(SandboxPodFactory.TASK_LABEL);
        if (taskId == null) return "没有任务编号";
        String status = taskStatus.apply(taskId);
        if (status == null) return "任务不存在";
        if (!ACTIVE.contains(status)) return "任务已结束（" + status + "）";
        return null;
    }
}
