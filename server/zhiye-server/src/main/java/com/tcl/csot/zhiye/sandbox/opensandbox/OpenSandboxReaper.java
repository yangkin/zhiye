package com.tcl.csot.zhiye.sandbox.opensandbox;

import com.alibaba.opensandbox.sandbox.SandboxManager;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.PagedSandboxInfos;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxFilter;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo;
import com.tcl.csot.zhiye.repository.TaskRepository;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 孤儿 OpenSandbox 沙箱回收（每个 api 实例都跑，删除是幂等的）：只处理带 {@code zhiye=1} 元数据的沙箱，
 * 删除任务不存在、任务已结束或缺少任务编号的沙箱。刚创建的沙箱有宽限期，避免和正在启动的任务抢。
 */
@Component
@Profile("api")
@ConditionalOnProperty(name = "zhiye.sandbox.provider", havingValue = "opensandbox")
public class OpenSandboxReaper {
    private static final Logger log = LoggerFactory.getLogger(OpenSandboxReaper.class);
    static final Set<String> ACTIVE = Set.of("QUEUED", "PREPARING", "RUNNING", "VERIFYING", "COMMITTING", "CANCELLING");

    private final Function<String, String> taskStatus;
    private final OpenSandboxProperties cfg;
    private final SandboxManager manager;

    @Autowired
    public OpenSandboxReaper(TaskRepository tasks, OpenSandboxProperties cfg, SandboxManager manager) {
        this(tasks::status, cfg, manager);
    }

    OpenSandboxReaper(Function<String, String> taskStatus, OpenSandboxProperties cfg, SandboxManager manager) {
        this.taskStatus = taskStatus;
        this.cfg = cfg;
        this.manager = manager;
    }

    @Scheduled(initialDelayString = "#{${zhiye.sandbox.opensandbox.reaper-interval-seconds:60} * 1000}",
            fixedDelayString = "#{${zhiye.sandbox.opensandbox.reaper-interval-seconds:60} * 1000}")
    public void scheduled() {
        if (!cfg.reaperEnabled()) return;
        try {
            int n = reapOnce(Instant.now());
            if (n > 0) log.info("回收了 {} 个孤儿沙箱", n);
        } catch (Exception e) {
            log.warn("回收沙箱失败：{}", e.getMessage());
        }
    }

    /** 返回删除的沙箱数 */
    int reapOnce(Instant now) {
        int n = 0;
        int page = 1;
        while (page <= 50) {
            PagedSandboxInfos paged = manager.listSandboxInfos(SandboxFilter.builder()
                    .metadata(Map.of("zhiye", "1"))
                    .pageSize(200)
                    .page(page)
                    .build());
            var infos = paged == null ? null : paged.getSandboxInfos();
            if (infos == null || infos.isEmpty()) break;
            for (SandboxInfo info : infos) {
                String why = reason(info, now);
                if (why == null) continue;
                log.info("删除沙箱 {}：{}", info.getId(), why);
                try {
                    manager.killSandbox(info.getId());
                    n++;
                } catch (Exception e) {
                    log.warn("删除沙箱 {} 失败（可能已过期）：{}", info.getId(), e.getMessage());
                }
            }
            if (infos.size() < 200) break;
            page++;
        }
        return n;
    }

    String reason(SandboxInfo info, Instant now) {
        if (info == null || info.getId() == null) return null;
        Instant created = info.getCreatedAt() == null ? now : toInstant(info.getCreatedAt());
        long age = Duration.between(created, now).getSeconds();
        if (age < cfg.reaperGraceSeconds()) return null;
        Map<String, String> md = info.getMetadata();
        String taskId = md == null ? null : md.get("zhiye.taskId");
        if (taskId == null || taskId.isBlank()) return "没有任务编号";
        String status = taskStatus.apply(taskId);
        if (status == null) return "任务不存在";
        if (!ACTIVE.contains(status)) return "任务已结束（" + status + "）";
        return null;
    }

    private static Instant toInstant(OffsetDateTime t) {
        return t.toInstant();
    }
}