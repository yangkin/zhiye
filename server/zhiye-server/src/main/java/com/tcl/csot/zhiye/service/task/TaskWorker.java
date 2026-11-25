package com.tcl.csot.zhiye.service.task;

import com.tcl.csot.zhiye.util.Threads;

import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.repository.TaskRepository;

import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 基于 PostgreSQL 的任务队列工作进程：SELECT … FOR UPDATE SKIP LOCKED 领取，租约 + 心跳，虚拟线程执行。
 */
@Component
@Profile("api")
public class TaskWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);

    private final TaskRepository tasks;
    private final TaskExecutor executor;
    private final TaskSignals signals;
    private final TaskEvents events;
    private final TaskService taskService;
    private final ZhiyeProperties props;
    private final String owner = "worker-" + ProcessHandle.current().pid() + "-" + Ids.random(6);
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.ops.PlatformLogService platformLogs;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.common.RuntimeMetricsService metrics;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Semaphore permits;
    private volatile boolean stopped;

    public TaskWorker(TaskRepository tasks, TaskExecutor executor, TaskSignals signals, TaskEvents events,
                      TaskService taskService, ZhiyeProperties props) {
        this.tasks = tasks;
        this.executor = executor;
        this.signals = signals;
        this.events = events;
        this.taskService = taskService;
        this.props = props;
        this.permits = new Semaphore(Math.max(1, props.task().concurrency()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Threads.start("task-poller", this::loop);
        log.info("任务工作进程 {} 已启动，并发 {}", owner, props.task().concurrency());
    }

    @PreDestroy
    public void stop() {
        stopped = true;
        signals.wakeWorker();
        // 优雅停机：停止领新任务后等在跑任务做完（滚动升级不再硬杀出 worker_lost）。
        // 等待期间手动续约：Spring 关闭会先停调度器（heartbeat @Scheduled 不再执行），
        // 不续约的话其他实例会按租约过期把任务误判成 worker_lost。
        long waitSeconds = Math.max(1, props.task().shutdownWaitSeconds());
        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        // 注意：Spring 在 ContextClosedEvent 时就拆了 logback，@PreDestroy 阶段 log.* 不可见，只能用 stdout。
        if (!running.isEmpty()) System.out.println("优雅停机：等待 " + running.size() + " 个在跑任务完成（最多 " + waitSeconds + " 秒）" + running);
        long lastBeat = 0;
        while (!running.isEmpty()) {
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                System.out.println("优雅停机超时，仍有 " + running.size() + " 个任务未完成，退出后将被标记 worker_lost：" + running);
                break;
            }
            try {
                if (now - lastBeat > 10_000) {
                    for (String id : running) tasks.heartbeat(id, owner, props.task().leaseSeconds());
                    lastBeat = now;
                }
            } catch (Exception e) {
                System.out.println("停机等待期间续约失败：" + e.getMessage());
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        if (running.isEmpty()) System.out.println("优雅停机：在跑任务已全部完成");
    }

    private void loop() {
        while (!stopped) {
            boolean acquired = false;
            boolean handedOff = false;
            try {
                if (!permits.tryAcquire()) { signals.awaitWork(props.task().pollMillis()); continue; }
                acquired = true;
                var claimed = tasks.claimNext(owner, props.task().leaseSeconds());
                if (claimed.isEmpty()) {
                    permits.release();
                    acquired = false;
                    signals.awaitWork(props.task().pollMillis());
                    continue;
                }
                TaskRow t = claimed.get();
                running.add(t.id());
                try {
                    Threads.start("task-" + t.id(), () -> {
                        try { executor.execute(t); }
                        finally { running.remove(t.id()); permits.release(); signals.wakeWorker(); }
                    });
                    handedOff = true;
                } catch (Throwable startErr) {
                    running.remove(t.id());
                    throw startErr;
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.warn("领取任务失败：{}", e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ie) { return; }
            } finally {
                if (acquired && !handedOff) {
                    permits.release();
                }
            }
        }
    }

    /** 续约，并检查其他实例发来的停止请求（数据库轮询，约 1 秒） */
    @Scheduled(fixedDelay = 1000)
    public void heartbeat() {
        if (running.isEmpty()) return;
        try {
            long now = System.currentTimeMillis();
            if (now - lastLease > 10_000) {
                for (String id : running) tasks.heartbeat(id, owner, props.task().leaseSeconds());
                lastLease = now;
            }
            for (String id : tasks.cancellingOwnedBy(owner)) signals.signalCancel(id);
            for (var sig : tasks.takeSignals(owner)) deliver(sig);
        } catch (Exception e) {
            log.warn("心跳失败：{}", e.getMessage());
        }
    }

    private volatile long lastLease = 0;

    /** 其他实例收到的确认、补充：交给本实例里正在执行的任务；已经过时的（选过了、不在编写阶段）记一行日志 */
    private void deliver(TaskRepository.Signal sig) {
        var p = sig.payload();
        boolean ok = switch (sig.kind()) {
            case "confirm" -> signals.confirm(sig.taskId(), p.path("confirmId").asText(), p.path("accept").asBoolean(false),
                    p.hasNonNull("text") ? p.get("text").asText() : null);
            case "steer" -> signals.steer(sig.taskId(), p.path("text").asText());
            default -> false;
        };
        if (!ok) log.info("任务 {} 的{}信号已过时，忽略", sig.taskId(), sig.kind());
    }

    /** 租约过期的任务（工作进程挂掉）→ FAILED(worker_lost)，按持有者与过期时间原子接管避免多副本重复终态 */
    @Scheduled(initialDelay = 2000, fixedDelay = 15000)
    public void reapExpired() {
        try {
            for (TaskRow t : tasks.expiredLeases()) {
                if (running.contains(t.id())) continue;
                String status = "CANCELLING".equals(t.status()) ? "CANCELLED" : "FAILED";
                String phaseLabel = "FAILED".equals(status) ? "失败" : "已停止";
                String code = "FAILED".equals(status) ? "worker_lost" : null;
                String msg = "FAILED".equals(status) ? "执行任务的工作进程已退出，请重试" : null;
                if (!tasks.reclaimExpiredAtomically(t.id(), t.leaseOwner(), status, phaseLabel, t.progress(), code, msg)) {
                    continue;
                }
                log.warn("任务 {} 租约过期，已原子回收为 {}", t.id(), status);
                if (metrics != null) {
                    java.time.Instant ref = t.startedAt() != null ? t.startedAt() : t.createdAt();
                    long ms = ref == null ? 0L : Math.max(0L, java.time.Duration.between(ref, java.time.Instant.now()).toMillis());
                    metrics.recordTask(t.type(), status, code, ms);
                }
                if (platformLogs != null && "FAILED".equals(status)) {
                    platformLogs.error("sandbox", "WORKER_LOST", t.workspaceId(), null, t.appId(), t.id(), null, null,
                            "任务租约过期（原执行节点 " + t.leaseOwner() + " 可能已重启/退出），已标记为 worker_lost",
                            null, Map.of("leaseOwner", String.valueOf(t.leaseOwner()), "progress", t.progress()));
                }
                taskService.afterTerminal(t, status, code, msg);
                events.emit(t.id(), "status", Map.of("status", status, "phaseLabel", phaseLabel, "progress", t.progress()));
                events.emit(t.id(), "finished", taskService.finishedPayload(status, null, code, msg));
            }
        } catch (Exception e) {
            log.warn("回收过期任务失败：{}", e.getMessage());
        }
    }

    public String owner() { return owner; }

    public int activeCount() { return running.size(); }
}
