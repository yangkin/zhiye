package com.tcl.csot.zhiye.service.ops;

import com.alibaba.opensandbox.sandbox.SandboxManager;
import com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.PagedSandboxInfos;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.PaginationInfo;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxFilter;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.TaskRepository;
import com.tcl.csot.zhiye.sandbox.RunnerSession;
import com.tcl.csot.zhiye.sandbox.k8s.K8sSandboxProperties;
import com.tcl.csot.zhiye.service.common.RuntimeMetricsService;
import com.tcl.csot.zhiye.service.task.TaskSignals;
import com.tcl.csot.zhiye.service.task.TaskWorker;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 集群多副本心跳拓扑 & 沙箱全生命周期台账/对账/采样/运维控制服务：
 * - 多副本节点（api / site）定期上报心跳与 JVM / OS / 并发槽位水位到 {@code cluster_node}；
 * - 记录每个任务的沙箱（local / k8s / opensandbox）从 creating -> active -> destroyed/failed 的完整台账；
 * - 对比内存活跃句柄 / OpenSandbox / K8s 控制面 与 PG 账本，识别 session / unregistered / ledger-ghost；
 * - 支持实时进程 CPU/内存采样、单沙箱深查、强杀销毁（含 409 冲突保护与审计落库）及一键幽灵对账清理。
 */
@Service
public class ClusterSandboxService {
    private static final Logger log = LoggerFactory.getLogger(ClusterSandboxService.class);
    private static final Pattern MEM_SIZE = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(Ki|Mi|Gi|Ti|K|M|G|T)?$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ACTIVE_TASK_STATUSES = Set.of(
            "QUEUED", "PREPARING", "RUNNING", "VERIFYING", "COMMITTING", "CANCELLING");

    private record LiveHandle(
            String sandboxId,
            String taskId,
            String appId,
            String workspaceId,
            String provider,
            RunnerSession session,
            Instant createdAt,
            Instant lastActiveAt
    ) {
        LiveHandle touch() {
            return new LiveHandle(sandboxId, taskId, appId, workspaceId, provider, session, createdAt, Instant.now());
        }
    }

    private record ProcUsage(Double cpuPercent, Long rssBytes) {}

    private final JdbcClient jdbc;
    private final ZhiyeProperties props;
    private final Environment env;
    private final RuntimeMetricsService metrics;
    private final String instanceId;
    private final String role;
    private final String hostname;
    private final Instant startedAt = Instant.now();

    private final Map<String, LiveHandle> liveBySandboxId = new ConcurrentHashMap<>();
    private final Map<String, String> sandboxIdByTaskId = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private ObjectProvider<TaskWorker> taskWorkerProvider;

    @Autowired(required = false)
    private TaskRepository tasks;

    @Autowired(required = false)
    private TaskSignals taskSignals;

    @Autowired(required = false)
    private PlatformLogService platformLogs;

    @Autowired(required = false)
    private com.tcl.csot.zhiye.service.task.TaskService taskService;

    @Autowired(required = false)
    private com.tcl.csot.zhiye.service.task.TaskEvents taskEvents;

    @Autowired(required = false)
    private SandboxManager openSandboxManager;

    @Autowired(required = false)
    private KubernetesClient kubernetesClient;

    @Autowired(required = false)
    private K8sSandboxProperties k8sProps;

    @Autowired
    public ClusterSandboxService(JdbcClient jdbc, ZhiyeProperties props, Environment env,
                                 @Autowired(required = false) RuntimeMetricsService metrics) {
        this.jdbc = jdbc;
        this.props = props;
        this.env = env;
        this.metrics = metrics;
        List<String> profiles = Arrays.asList(env.getActiveProfiles());
        if (profiles.contains("api") && profiles.contains("site")) {
            this.role = "api+site";
        } else if (profiles.contains("site")) {
            this.role = "site";
        } else {
            this.role = "api";
        }
        String base = metrics != null ? metrics.instanceId() : ("node-" + ProcessHandle.current().pid());
        this.instanceId = "site".equals(this.role) && !base.endsWith("-site") ? base + "-site" : base;
        String hn = System.getenv("HOSTNAME");
        if (hn == null || hn.isBlank()) {
            try { hn = InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) { hn = "localhost"; }
        }
        this.hostname = hn;
    }

    public String instanceId() {
        return instanceId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        heartbeat();
    }

    private TaskWorker worker() {
        return taskWorkerProvider == null ? null : taskWorkerProvider.getIfAvailable();
    }

    /** 每 10 秒上报副本节点心跳与本地活跃沙箱资源采样 */
    @Scheduled(initialDelay = 3000, fixedDelay = 10_000)
    public void heartbeat() {
        if (jdbc == null) return;
        try {
            int port = resolvePort();
            TaskWorker tw = worker();
            int activeTasks = tw != null ? tw.activeCount() : 0;
            String workerOwner = tw != null ? tw.owner() : "";
            int concurrency = tw != null ? props.task().concurrency() : 0;
            int activeSandboxes = liveBySandboxId.size();

            var memBean = ManagementFactory.getMemoryMXBean();
            long heapUsed = memBean.getHeapMemoryUsage().getUsed();
            long heapMax = Math.max(memBean.getHeapMemoryUsage().getMax(), memBean.getHeapMemoryUsage().getCommitted());
            Double cpuPercent = null;
            long sysTotal = 0L;
            long sysUsed = 0L;
            var osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                double load = sunOs.getCpuLoad();
                if (load >= 0) cpuPercent = Math.round(load * 1000.0) / 10.0;
                sysTotal = Math.max(0L, sunOs.getTotalMemorySize());
                long free = Math.max(0L, sunOs.getFreeMemorySize());
                sysUsed = Math.max(0L, sysTotal - free);
            }

            jdbc.sql("""
                    insert into cluster_node (
                      instance_id, role, hostname, port, sandbox_provider, worker_owner, worker_concurrency,
                      active_tasks, active_sandboxes, cpu_percent, heap_used_bytes, heap_max_bytes,
                      sys_mem_used_bytes, sys_mem_total_bytes, status, started_at, heartbeat_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'online', ?, now())
                    on conflict (instance_id) do update set
                      role = excluded.role,
                      hostname = excluded.hostname,
                      port = excluded.port,
                      sandbox_provider = excluded.sandbox_provider,
                      worker_owner = excluded.worker_owner,
                      worker_concurrency = excluded.worker_concurrency,
                      active_tasks = excluded.active_tasks,
                      active_sandboxes = excluded.active_sandboxes,
                      cpu_percent = excluded.cpu_percent,
                      heap_used_bytes = excluded.heap_used_bytes,
                      heap_max_bytes = excluded.heap_max_bytes,
                      sys_mem_used_bytes = excluded.sys_mem_used_bytes,
                      sys_mem_total_bytes = excluded.sys_mem_total_bytes,
                      status = 'online',
                      heartbeat_at = now()
                    """)
                    .params(instanceId, role, hostname, port, props.sandbox().provider(), workerOwner, concurrency,
                            activeTasks, activeSandboxes, cpuPercent, heapUsed, heapMax,
                            sysUsed, sysTotal, Db.odt(startedAt))
                    .update();

            sampleLiveLocalSandboxes();
        } catch (Exception e) {
            log.debug("上报集群节点心跳失败：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void onShutdown() {
        if (jdbc == null) return;
        try {
            jdbc.sql("update cluster_node set status = 'offline', active_tasks = 0, active_sandboxes = 0 where instance_id = ?")
                    .param(instanceId).update();
        } catch (Exception ignored) {}
    }

    private int resolvePort() {
        String p = env.getProperty("local.server.port");
        if (p == null || p.isBlank()) p = env.getProperty("server.port", "8080");
        try { return Integer.parseInt(p.trim()); } catch (Exception e) { return 8080; }
    }

    // ================= 沙箱生命周期追踪 =================

    public String registerCreating(TaskRow task, boolean allowNet) {
        String sandboxId = "sbx_" + Ids.random(12);
        if (jdbc == null || task == null) return sandboxId;
        TaskWorker tw = worker();
        String workerOwner = tw != null ? tw.owner() : (task.leaseOwner() == null ? "" : task.leaseOwner());
        try {
            jdbc.sql("""
                    insert into sandbox_instance (
                      sandbox_id, provider, kind, task_id, app_id, workspace_id, user_id,
                      owner_instance, worker_owner, state, allow_net, created_at
                    ) values (?, ?, 'task', ?, ?, ?, ?, ?, ?, 'creating', ?, now())
                    """)
                    .params(sandboxId, props.sandbox().provider(), task.id(), task.appId(), task.workspaceId(),
                            resolveWorkspaceUserId(task.workspaceId()), instanceId, workerOwner, allowNet)
                    .update();
        } catch (Exception e) {
            log.debug("记录沙箱 creating 状态失败：{}", e.getMessage());
        }
        return sandboxId;
    }

    public void markReady(String sandboxId, TaskRow task, RunnerSession session, int startupMs) {
        if (sandboxId == null || session == null) return;
        Instant now = Instant.now();
        RunnerSession.SandboxDescriptor desc = session.descriptor();
        String provider = desc != null && desc.provider() != null ? desc.provider() : props.sandbox().provider();
        String externalRef = desc != null && desc.externalRef() != null ? desc.externalRef() : sandboxId;
        Long pid = desc != null ? desc.pid() : null;
        String imageOrCmd = desc != null && desc.imageOrCmd() != null ? desc.imageOrCmd() : provider;
        String cpuLimit = desc != null ? desc.cpuLimit() : null;
        String memoryLimit = desc != null ? desc.memoryLimit() : null;
        Long memoryLimitBytes = parseMemBytes(memoryLimit);

        LiveHandle handle = new LiveHandle(
                sandboxId,
                task == null ? null : task.id(),
                task == null ? null : task.appId(),
                task == null ? null : task.workspaceId(),
                provider,
                session,
                now,
                now);
        liveBySandboxId.put(sandboxId, handle);
        if (task != null) sandboxIdByTaskId.put(task.id(), sandboxId);

        if (jdbc == null) return;
        try {
            jdbc.sql("""
                    update sandbox_instance set
                      provider = ?,
                      state = 'active',
                      external_ref = ?,
                      image_or_cmd = ?,
                      cpu_limit = ?,
                      memory_limit = ?,
                      memory_limit_bytes = ?,
                      pid = ?,
                      startup_ms = ?,
                      ready_at = now(),
                      last_active_at = now()
                    where sandbox_id = ?
                    """)
                    .params(provider, externalRef, imageOrCmd, cpuLimit, memoryLimit, memoryLimitBytes,
                            pid, Math.max(0, startupMs), sandboxId)
                    .update();
            if (platformLogs != null && task != null) {
                platformLogs.info("sandbox", "SANDBOX_READY", task.workspaceId(), null, task.appId(), task.id(),
                        "沙箱就绪 (" + provider + " · " + externalRef + " · 冷启动 " + startupMs + "ms)",
                        null, Map.of("sandboxId", sandboxId, "provider", provider, "externalRef", externalRef,
                                "startupMs", startupMs, "instanceId", instanceId));
            }
            sampleLiveLocalSandboxes();
        } catch (Exception e) {
            log.debug("记录沙箱 ready 失败：{}", e.getMessage());
        }
    }

    public void touchActivity(String sandboxId) {
        if (sandboxId == null) return;
        LiveHandle h = liveBySandboxId.get(sandboxId);
        if (h == null) return;
        Instant now = Instant.now();
        if (Duration.between(h.lastActiveAt(), now).getSeconds() < 15) return;
        liveBySandboxId.put(sandboxId, h.touch());
        if (jdbc != null) {
            try {
                jdbc.sql("update sandbox_instance set last_active_at = now() where sandbox_id = ?").param(sandboxId).update();
            } catch (Exception ignored) {}
        }
    }

    public void markClosed(String sandboxId, TaskRow task, String reason) {
        if (sandboxId == null) return;
        LiveHandle prev = liveBySandboxId.remove(sandboxId);
        if (task != null) sandboxIdByTaskId.remove(task.id(), sandboxId);
        else if (prev != null && prev.taskId() != null) sandboxIdByTaskId.remove(prev.taskId(), sandboxId);

        if (jdbc == null) return;
        try {
            String cleanReason = reason == null || reason.isBlank() ? "task_finished" : reason;
            jdbc.sql("""
                    update sandbox_instance set
                      state = 'destroyed',
                      destroyed_at = coalesce(destroyed_at, now()),
                      destroy_reason = coalesce(destroy_reason, ?)
                    where sandbox_id = ? and state in ('creating', 'active')
                    """)
                    .params(cleanReason, sandboxId)
                    .update();
            if (platformLogs != null && task != null) {
                platformLogs.info("sandbox", "SANDBOX_DESTROY", task.workspaceId(), null, task.appId(), task.id(),
                        "沙箱已销毁回收 (" + cleanReason + ")",
                        null, Map.of("sandboxId", sandboxId, "reason", cleanReason, "instanceId", instanceId));
            }
        } catch (Exception e) {
            log.debug("更新沙箱销毁状态失败：{}", e.getMessage());
        }
    }

    public void markFailed(String sandboxId, TaskRow task, String reason) {
        if (sandboxId == null) return;
        liveBySandboxId.remove(sandboxId);
        if (task != null) sandboxIdByTaskId.remove(task.id(), sandboxId);
        if (jdbc == null) return;
        try {
            jdbc.sql("""
                    update sandbox_instance set
                      state = 'failed',
                      destroyed_at = coalesce(destroyed_at, now()),
                      destroy_reason = coalesce(destroy_reason, ?)
                    where sandbox_id = ?
                    """)
                    .params(reason == null ? "start_failed" : reason, sandboxId)
                    .update();
        } catch (Exception ignored) {}
    }

    // ================= 集群全景 & 沙箱实时采样对账 =================

    public Map<String, Object> clusterOverview(boolean withUsage) {
        List<Map<String, Object>> nodes = listNodes();
        Map<String, Object> queue = queryQueueStats();
        Map<String, Object> sandboxes = listSandboxes(null, null, null, null, withUsage);

        long onlineNodes = nodes.stream().filter(n -> "online".equals(n.get("status"))).count();
        long degradedNodes = nodes.stream().filter(n -> "degraded".equals(n.get("status"))).count();
        long totalConcurrency = nodes.stream()
                .filter(n -> !"offline".equals(n.get("status")))
                .mapToLong(n -> ((Number) n.getOrDefault("workerConcurrency", 0)).longValue())
                .sum();
        long totalActiveTasksOnNodes = nodes.stream()
                .filter(n -> !"offline".equals(n.get("status")))
                .mapToLong(n -> ((Number) n.getOrDefault("activeTasks", 0)).longValue())
                .sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("currentInstanceId", instanceId);
        out.put("sandboxProvider", props.sandbox().provider());
        out.put("nodesSummary", Map.of(
                "total", nodes.size(),
                "online", onlineNodes,
                "degraded", degradedNodes,
                "offline", Math.max(0, nodes.size() - onlineNodes - degradedNodes),
                "workerSlotsTotal", totalConcurrency,
                "workerSlotsUsed", totalActiveTasksOnNodes
        ));
        out.put("nodes", nodes);
        out.put("queue", queue);
        out.put("sandboxes", sandboxes);
        out.put("runtimeMetrics", metrics == null ? Map.of() : metrics.snapshot());
        return out;
    }

    public List<Map<String, Object>> listNodes() {
        return jdbc.sql("""
                select *, extract(epoch from (now() - heartbeat_at)) as hb_age_sec
                from cluster_node
                order by (case when heartbeat_at >= now() - interval '120 seconds' then 0 else 1 end), heartbeat_at desc
                """).query((rs, i) -> {
            double ageSec = Math.max(0.0, rs.getDouble("hb_age_sec"));
            String persistedStatus = rs.getString("status");
            boolean gracefulStop = "offline".equals(persistedStatus);
            boolean staleHeartbeat = !gracefulStop && ageSec > 120;
            String liveStatus;
            if (gracefulStop || staleHeartbeat) {
                liveStatus = "offline";
            } else if (ageSec > 35) {
                liveStatus = "degraded";
            } else {
                liveStatus = "online";
            }
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("instanceId", rs.getString("instance_id"));
            n.put("isCurrent", instanceId.equals(rs.getString("instance_id")));
            n.put("role", rs.getString("role"));
            n.put("hostname", rs.getString("hostname"));
            n.put("port", rs.getInt("port"));
            n.put("sandboxProvider", rs.getString("sandbox_provider"));
            n.put("workerOwner", rs.getString("worker_owner"));
            n.put("workerConcurrency", rs.getInt("worker_concurrency"));
            n.put("activeTasks", "offline".equals(liveStatus) ? 0 : rs.getInt("active_tasks"));
            n.put("activeSandboxes", "offline".equals(liveStatus) ? 0 : rs.getInt("active_sandboxes"));
            n.put("cpuPercent", rs.getObject("cpu_percent"));
            long heapUsed = rs.getLong("heap_used_bytes");
            long heapMax = rs.getLong("heap_max_bytes");
            n.put("heapUsedBytes", heapUsed);
            n.put("heapMaxBytes", heapMax);
            n.put("heapPercent", heapMax > 0 ? Math.round((double) heapUsed * 1000.0 / heapMax) / 10.0 : 0.0);
            n.put("sysMemUsedBytes", rs.getLong("sys_mem_used_bytes"));
            n.put("sysMemTotalBytes", rs.getLong("sys_mem_total_bytes"));
            n.put("status", liveStatus);
            n.put("persistedStatus", persistedStatus);
            n.put("gracefulStop", gracefulStop);
            n.put("staleHeartbeat", staleHeartbeat);
            n.put("heartbeatAgeSeconds", Math.round(ageSec * 10.0) / 10.0);
            n.put("startedAt", Db.iso(Db.ts(rs, "started_at")));
            n.put("heartbeatAt", Db.iso(Db.ts(rs, "heartbeat_at")));
            return n;
        }).list();
    }

    private Map<String, Object> queryQueueStats() {
        return jdbc.sql("""
                select
                  count(*) filter (where status = 'QUEUED') as queued,
                  count(*) filter (where status = 'PREPARING') as preparing,
                  count(*) filter (where status = 'RUNNING') as running,
                  count(*) filter (where status = 'VERIFYING') as verifying,
                  count(*) filter (where status = 'COMMITTING') as committing,
                  count(*) filter (where status = 'CANCELLING') as cancelling,
                  count(*) filter (where status in ('PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')
                                     and (lease_until is null or lease_until < now())) as expired_leases,
                  coalesce(round(extract(epoch from (now() - min(created_at) filter (where status = 'QUEUED'))))::bigint, 0) as oldest_queued_sec
                from task
                """).query((rs, i) -> {
            Map<String, Object> q = new LinkedHashMap<>();
            long queued = rs.getLong("queued");
            long preparing = rs.getLong("preparing");
            long running = rs.getLong("running");
            long verifying = rs.getLong("verifying");
            long committing = rs.getLong("committing");
            long cancelling = rs.getLong("cancelling");
            q.put("queued", queued);
            q.put("preparing", preparing);
            q.put("running", running);
            q.put("verifying", verifying);
            q.put("committing", committing);
            q.put("cancelling", cancelling);
            q.put("activeTotal", queued + preparing + running + verifying + committing + cancelling);
            q.put("runningTotal", preparing + running + verifying + committing + cancelling);
            q.put("expiredLeases", rs.getLong("expired_leases"));
            q.put("oldestQueuedSeconds", rs.getLong("oldest_queued_sec"));
            return q;
        }).single();
    }

    /**
     * 全量沙箱对账视图：合并 PG 账本（creating/active + 最近 destroyed/failed）与控制面（本地进程 / K8s Pod / OpenSandbox）。
     */
    public Map<String, Object> listSandboxes(String kindFilter, String providerFilter, String workspaceIdFilter,
                                             String qFilter, boolean withUsage) {
        if (withUsage) {
            sampleLiveLocalSandboxes();
        }
        Map<String, String> nodeStatusMap = new HashMap<>();
        jdbc.sql("""
                select instance_id,
                       case when status = 'offline' or heartbeat_at < now() - interval '120 seconds' then 'offline'
                            when heartbeat_at < now() - interval '35 seconds' then 'degraded'
                            else 'online' end as st
                from cluster_node
                """).query(rs -> { nodeStatusMap.put(rs.getString("instance_id"), rs.getString("st")); });

        // 全量读取活跃/创建中账本（不设 limit 截断），避免控制面比对把真实在跑沙箱误判为孤儿
        List<Map<String, Object>> activeLedgerRows = jdbc.sql("""
                select s.*,
                       coalesce(a.name, s.app_id) as app_name,
                       coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name,
                       coalesce(u.login_name, '') as owner_login_name,
                       t.status as task_status,
                       t.progress as task_progress,
                       t.phase_label as task_phase,
                       extract(epoch from (coalesce(s.destroyed_at, now()) - s.created_at)) as age_sec,
                       extract(epoch from (coalesce(s.destroyed_at, now()) - coalesce(s.last_active_at, s.ready_at, s.created_at))) as idle_sec
                from sandbox_instance s
                left join app a on a.id = s.app_id
                left join workspace w on w.id = s.workspace_id
                left join app_user u on u.id = w.owner_user_id
                left join task t on t.id = s.task_id
                where s.state in ('creating', 'active')
                order by s.created_at desc
                """).query((rs, i) -> mapSandboxRow(rs)).list();

        List<Map<String, Object>> recentClosedRows = jdbc.sql("""
                select s.*,
                       coalesce(a.name, s.app_id) as app_name,
                       coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name,
                       coalesce(u.login_name, '') as owner_login_name,
                       t.status as task_status,
                       t.progress as task_progress,
                       t.phase_label as task_phase,
                       extract(epoch from (coalesce(s.destroyed_at, now()) - s.created_at)) as age_sec,
                       extract(epoch from (coalesce(s.destroyed_at, now()) - coalesce(s.last_active_at, s.ready_at, s.created_at))) as idle_sec
                from sandbox_instance s
                left join app a on a.id = s.app_id
                left join workspace w on w.id = s.workspace_id
                left join app_user u on u.id = w.owner_user_id
                left join task t on t.id = s.task_id
                where s.state not in ('creating', 'active')
                  and s.created_at >= now() - interval '24 hours'
                order by s.created_at desc
                limit 200
                """).query((rs, i) -> mapSandboxRow(rs)).list();

        long closed24hCount = jdbc.sql("""
                select count(*) from sandbox_instance
                where state not in ('creating', 'active')
                  and created_at >= now() - interval '24 hours'
                """).query(Long.class).single();
        boolean ledgerHistoryTruncated = closed24hCount > recentClosedRows.size();

        List<Map<String, Object>> ledgerRows = new ArrayList<>(activeLedgerRows.size() + recentClosedRows.size());
        ledgerRows.addAll(activeLedgerRows);
        ledgerRows.addAll(recentClosedRows);

        // 控制面实况探测（全分页 OpenSandbox / K8s / 本地 ProcessHandle）
        ControlPlaneSnapshot cp = probeControlPlane(activeLedgerRows);

        List<Map<String, Object>> enriched = new ArrayList<>();
        Map<String, Long> byKind = new LinkedHashMap<>();
        Map<String, Long> byProvider = new LinkedHashMap<>();
        long activeCount = 0;
        long onThisReplica = 0;
        long runningTasks = 0;
        long memUsedTotal = 0;
        long memLimitTotal = 0;
        double cpuSum = 0;
        int cpuSamples = 0;
        long startupSum = 0;
        int startupCnt = 0;

        for (Map<String, Object> item : ledgerRows) {
            String sbId = (String) item.get("sandboxId");
            String state = (String) item.get("state");
            String owner = (String) item.get("ownerInstance");
            boolean isLocal = instanceId.equals(owner);
            boolean isActiveLedger = "creating".equals(state) || "active".equals(state);
            LiveHandle live = liveBySandboxId.get(sbId);
            String taskStatus = (String) item.get("taskStatus");

            String kind;
            if (!isActiveLedger) {
                kind = "failed".equals(state) ? "failed" : "destroyed";
            } else if (isLocal) {
                boolean alive = live != null && live.session().alive();
                if (!alive && item.get("pid") instanceof Number n) {
                    alive = ProcessHandle.of(n.longValue()).map(ProcessHandle::isAlive).orElse(false);
                }
                kind = (alive || "creating".equals(state)) ? "session" : "ledger-ghost";
            } else {
                String ownerSt = nodeStatusMap.getOrDefault(owner, "offline");
                if ("opensandbox".equals(item.get("provider")) || "k8s".equals(item.get("provider"))) {
                    if (!cp.available() || !cp.complete()) {
                        // 控制面不可达或分页不完整时，不误判活跃容器为幽灵
                        kind = "offline".equals(ownerSt) ? "unknown" : "session";
                    } else {
                        String extRef = String.valueOf(item.getOrDefault("externalRef", ""));
                        boolean onControlPlane = cp.liveRefs().contains(extRef);
                        kind = onControlPlane ? "session" : ("offline".equals(ownerSt) || (item.get("ageSeconds") instanceof Number a && a.doubleValue() > 20) ? "ledger-ghost" : "session");
                    }
                } else {
                    kind = "offline".equals(ownerSt) || (taskStatus != null && !ACTIVE_TASK_STATUSES.contains(taskStatus))
                            ? "ledger-ghost" : "session";
                }
            }

            boolean taskRunning = isActiveLedger && taskStatus != null && ACTIVE_TASK_STATUSES.contains(taskStatus);
            item.put("kind", kind);
            item.put("onThisReplica", isLocal);
            item.put("ownerReplicaStatus", nodeStatusMap.getOrDefault(owner, "unknown"));
            item.put("taskRunning", taskRunning);

            if (isActiveLedger) {
                activeCount++;
                if (isLocal && live != null) onThisReplica++;
                if (taskRunning) runningTasks++;
                if (item.get("memoryUsedBytes") instanceof Number mu) memUsedTotal += mu.longValue();
                if (item.get("memoryLimitBytes") instanceof Number ml) memLimitTotal += ml.longValue();
                if (item.get("cpuPercent") instanceof Number cpct) {
                    cpuSum += cpct.doubleValue();
                    cpuSamples++;
                }
            }
            if (item.get("startupMs") instanceof Number sm && sm.longValue() > 0) {
                startupSum += sm.longValue();
                startupCnt++;
            }
            byKind.merge(kind, 1L, Long::sum);
            byProvider.merge(String.valueOf(item.get("provider")), 1L, Long::sum);
            enriched.add(item);
        }

        // 添加未登记在 PG 账本上的控制面孤儿容器/Pod
        for (Map<String, Object> orphan : cp.unregistered()) {
            byKind.merge("unregistered", 1L, Long::sum);
            byProvider.merge(String.valueOf(orphan.get("provider")), 1L, Long::sum);
            activeCount++;
            enriched.add(0, orphan);
        }

        String kf = clean(kindFilter);
        String pf = clean(providerFilter);
        String wsf = clean(workspaceIdFilter);
        String qf = clean(qFilter);
        List<Map<String, Object>> filtered = enriched.stream().filter(it -> {
            if (kf != null && !"ALL".equalsIgnoreCase(kf) && !kf.equalsIgnoreCase(String.valueOf(it.get("kind")))) return false;
            if (pf != null && !"ALL".equalsIgnoreCase(pf) && !pf.equalsIgnoreCase(String.valueOf(it.get("provider")))) return false;
            if (wsf != null && !wsf.equals(it.get("workspaceId"))) return false;
            if (qf != null) {
                String hay = (it.get("sandboxId") + " " + it.get("externalRef") + " " + it.get("taskId") + " "
                        + it.get("appName") + " " + it.get("workspaceId") + " " + it.get("ownerName") + " "
                        + it.get("ownerInstance")).toLowerCase(Locale.ROOT);
                if (!hay.contains(qf.toLowerCase(Locale.ROOT))) return false;
            }
            return true;
        }).collect(Collectors.toList());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", activeCount);
        summary.put("totalRows", enriched.size());
        summary.put("onThisReplica", onThisReplica);
        summary.put("runningTasks", runningTasks);
        summary.put("byKind", byKind);
        summary.put("byProvider", byProvider);
        summary.put("ghostCount", byKind.getOrDefault("ledger-ghost", 0L));
        summary.put("unregisteredCount", byKind.getOrDefault("unregistered", 0L));
        summary.put("memoryUsedBytesTotal", memUsedTotal);
        summary.put("memoryLimitBytesTotal", memLimitTotal);
        summary.put("cpuPercentAvg", cpuSamples == 0 ? null : Math.round(cpuSum / cpuSamples * 10.0) / 10.0);
        summary.put("avgStartupMs", startupCnt == 0 ? 0L : Math.round((double) startupSum / startupCnt));
        summary.put("controlPlaneAvailable", cp.available());
        summary.put("controlPlaneComplete", cp.complete());
        summary.put("controlPlaneMessage", cp.message());
        summary.put("ledgerHistoryTruncated", ledgerHistoryTruncated);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("replicaId", instanceId);
        out.put("provider", props.sandbox().provider());
        out.put("summary", summary);
        out.put("items", filtered);
        return out;
    }

    public Map<String, Object> getSandboxDetail(String ref) {
        String key = clean(ref);
        if (key == null) throw ApiException.validation("缺少沙箱标识");
        Map<String, Object> all = listSandboxes(null, null, null, null, true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) all.getOrDefault("items", List.of());
        Map<String, Object> found = items.stream()
                .filter(it -> key.equals(it.get("sandboxId")) || key.equals(it.get("taskId")) || key.equals(it.get("externalRef")))
                .findFirst()
                .orElse(null);

        if (found == null) {
            found = jdbc.sql("""
                    select s.*,
                           coalesce(a.name, s.app_id) as app_name,
                           coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name,
                           coalesce(u.login_name, '') as owner_login_name,
                           t.status as task_status,
                           t.progress as task_progress,
                           t.phase_label as task_phase,
                           extract(epoch from (coalesce(s.destroyed_at, now()) - s.created_at)) as age_sec,
                           extract(epoch from (coalesce(s.destroyed_at, now()) - coalesce(s.last_active_at, s.ready_at, s.created_at))) as idle_sec
                    from sandbox_instance s
                    left join app a on a.id = s.app_id
                    left join workspace w on w.id = s.workspace_id
                    left join app_user u on u.id = w.owner_user_id
                    left join task t on t.id = s.task_id
                    where s.sandbox_id = ? or s.task_id = ? or s.external_ref = ?
                    order by s.created_at desc limit 1
                    """).params(key, key, key).query((rs, i) -> mapSandboxRow(rs)).optional()
                    .orElseThrow(() -> ApiException.notFound("沙箱实例"));
        }

        String taskId = (String) found.get("taskId");
        List<Map<String, Object>> events = taskId == null ? List.of() : jdbc.sql("""
                select id, category, level, action, ok, status_code, duration_ms, summary, detail, meta, created_at
                from platform_event_log
                where category = 'sandbox' and (task_id = ? or meta->>'sandboxId' = ?)
                order by created_at desc, id desc limit 30
                """).params(taskId, String.valueOf(found.get("sandboxId"))).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("level", rs.getString("level"));
            m.put("action", rs.getString("action"));
            m.put("ok", rs.getBoolean("ok"));
            m.put("durationMs", rs.getObject("duration_ms"));
            m.put("summary", rs.getString("summary"));
            m.put("detail", rs.getString("detail"));
            m.put("meta", Json.read(rs.getString("meta")));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).list();

        Map<String, Object> out = new LinkedHashMap<>(found);
        out.put("events", events);
        return out;
    }

    /**
     * 管理员销毁/强杀沙箱：支持 sandboxId / taskId / externalRef。
     * 若任务仍在活跃且未传 force=true，返回 409 Conflict 防误杀。
     */
    public Map<String, Object> destroySandbox(String ref, boolean force, String operator, String reason) {
        String key = clean(ref);
        if (key == null) throw ApiException.validation("缺少沙箱标识");
        String op = (operator == null || operator.isBlank()) ? "admin" : operator.trim();
        String rsn = (reason == null || reason.isBlank()) ? "管理员手动销毁释放" : reason.trim();

        Map<String, Object> row = jdbc.sql("""
                select s.*, t.status as task_status
                from sandbox_instance s
                left join task t on t.id = s.task_id
                where s.sandbox_id = ? or s.task_id = ? or s.external_ref = ?
                order by (case when s.state in ('creating', 'active') then 0 else 1 end), s.created_at desc
                limit 1
                """).params(key, key, key).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sandboxId", rs.getString("sandbox_id"));
            m.put("provider", rs.getString("provider"));
            m.put("taskId", rs.getString("task_id"));
            m.put("appId", rs.getString("app_id"));
            m.put("workspaceId", rs.getString("workspace_id"));
            m.put("ownerInstance", rs.getString("owner_instance"));
            m.put("state", rs.getString("state"));
            m.put("externalRef", rs.getString("external_ref"));
            m.put("pid", rs.getObject("pid"));
            m.put("taskStatus", rs.getString("task_status"));
            return m;
        }).optional().orElse(null);

        // 若不在账本，检查是否是 OpenSandbox/K8s 未登记容器
        if (row == null) {
            return destroyServerOnly(key, op, rsn);
        }

        String sandboxId = (String) row.get("sandboxId");
        String taskId = (String) row.get("taskId");
        String provider = (String) row.get("provider");
        String externalRef = (String) row.get("externalRef");
        String taskStatus = (String) row.get("taskStatus");
        String state = (String) row.get("state");
        LiveHandle live = liveBySandboxId.get(sandboxId);

        boolean activeNow = ("creating".equals(state) || "active".equals(state))
                && ((live != null && live.session().alive())
                || (taskStatus != null && ACTIVE_TASK_STATUSES.contains(taskStatus)));

        if (activeNow && !force) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                    "沙箱 " + sandboxId + " 关联的任务仍在执行中（状态 " + taskStatus + "），如需强行终止并销毁请确认 force=true");
        }

        String ownerInstance = (String) row.get("ownerInstance");
        boolean isActiveLedger = "creating".equals(state) || "active".equals(state);
        String path;
        boolean killedProcessOrContainer = false;
        String resultStatus;
        String effectiveReason = rsn;

        if (live != null) {
            path = "local-runtime";
            try {
                live.session().kill();
                killedProcessOrContainer = true;
                resultStatus = "destroyed";
            } catch (Exception e) {
                log.warn("强杀本副本沙箱会话 {} 失败：{}", sandboxId, e.getMessage());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_KILL_FAILED",
                        "停止本副本沙箱运行时失败，沙箱状态保持未销毁：" + e.getMessage());
            }
            liveBySandboxId.remove(sandboxId);
            if (taskId != null) sandboxIdByTaskId.remove(taskId, sandboxId);
        } else if (!isActiveLedger) {
            path = "ledger-already-closed";
            resultStatus = "already_destroyed";
        } else {
            path = "ledger-remote";
            KillOutcome ko = killRemoteOrPid(provider, externalRef, row.get("pid"), ownerInstance);
            if (ko.unsupportedRemote()) {
                throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", ko.detail());
            }
            if (!ko.killed() && !ko.notFound()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_KILL_FAILED",
                        "控制面销毁沙箱失败，沙箱状态保持未销毁：" + ko.detail());
            }
            killedProcessOrContainer = ko.killed();
            if (ko.killed()) {
                resultStatus = "destroyed";
            } else {
                resultStatus = "reconcile_settled";
                effectiveReason = rsn + "（控制面已不存在，账本核销）";
            }
        }

        // 若关联任务仍在运行，发取消信号避免任务挂起
        boolean cancelledTask = false;
        if (taskId != null && tasks != null && taskStatus != null && ACTIVE_TASK_STATUSES.contains(taskStatus)) {
            cancelledTask = tasks.requestCancel(taskId);
            if (taskSignals != null) taskSignals.signalCancel(taskId);
        }

        if (!"already_destroyed".equals(resultStatus)) {
            jdbc.sql("""
                    update sandbox_instance set
                      state = 'destroyed',
                      destroyed_at = coalesce(destroyed_at, now()),
                      destroyed_by = ?,
                      destroy_reason = ?
                    where sandbox_id = ?
                    """).params(op, effectiveReason, sandboxId).update();
        }

        recordDestroyAudit(sandboxId, taskId, (String) row.get("workspaceId"), (String) row.get("appId"),
                provider, externalRef, path, op, effectiveReason, cancelledTask);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("status", resultStatus);
        res.put("path", path);
        res.put("sandboxId", sandboxId);
        res.put("taskId", taskId);
        res.put("killedRuntime", killedProcessOrContainer);
        res.put("cancelledTask", cancelledTask);
        res.put("operator", op);
        res.put("reason", effectiveReason);
        return res;
    }

    /**
     * 对账清理全部幽灵账本行（ledger-ghost）及控制面孤儿容器/Pod。
     * 控制面不可达或分页结果不完整时拒绝批量对账，防止误杀正常在跑沙箱。
     */
    public Map<String, Object> reapOrphansAndGhosts(String operator) {
        String op = (operator == null || operator.isBlank()) ? "admin" : operator.trim();
        Map<String, Object> view = listSandboxes(null, null, null, null, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) view.getOrDefault("summary", Map.of());
        if (!Boolean.TRUE.equals(summary.get("controlPlaneAvailable"))
                || !Boolean.TRUE.equals(summary.get("controlPlaneComplete"))) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                    "控制面探测不可达或不完整，已阻止批量对账清理：" + summary.get("controlPlaneMessage"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) view.getOrDefault("items", List.of());

        int ghostsSettled = 0;
        int orphansKilled = 0;
        for (Map<String, Object> it : items) {
            String kind = String.valueOf(it.get("kind"));
            if ("ledger-ghost".equals(kind)) {
                String sbId = String.valueOf(it.get("sandboxId"));
                int updated = jdbc.sql("""
                        update sandbox_instance set
                          state = 'destroyed',
                          destroyed_at = coalesce(destroyed_at, now()),
                          destroyed_by = ?,
                          destroy_reason = coalesce(destroy_reason, 'reap_ledger_ghost')
                        where sandbox_id = ? and state in ('creating', 'active')
                        """).params(op, sbId).update();
                if (updated > 0) {
                    ghostsSettled++;
                    liveBySandboxId.remove(sbId);
                }
            } else if ("unregistered".equals(kind)) {
                String provider = String.valueOf(it.get("provider"));
                String extRef = String.valueOf(it.get("externalRef"));
                String ownerInst = it.get("ownerInstance") == null ? null : String.valueOf(it.get("ownerInstance"));
                if (killRemoteOrPid(provider, extRef, it.get("pid"), ownerInst).killed()) {
                    orphansKilled++;
                }
            }
        }

        jdbc.sql("""
                insert into admin_audit_log (id, actor, action, target_type, target_id, detail)
                values (?, ?, 'SANDBOX_REAP', 'cluster', ?, ?::jsonb)
                """).params("aud_" + Ids.random(10), op, instanceId,
                Json.write(Map.of("ghostsSettled", ghostsSettled, "orphansKilled", orphansKilled))).update();

        if (platformLogs != null) {
            platformLogs.info("sandbox", "SANDBOX_REAP_ORPHAN", null, null, null, null,
                    "管理员 " + op + " 执行沙箱对账清理：平账幽灵 " + ghostsSettled + " 条，清理孤儿 " + orphansKilled + " 个",
                    null, Map.of("operator", op, "ghostsSettled", ghostsSettled, "orphansKilled", orphansKilled));
        }

        return Map.of("ok", true, "ghostsSettled", ghostsSettled, "orphansKilled", orphansKilled, "operator", op);
    }

    /** 清理已离线的集群历史节点记录（禁止清理本实例或仍在线/心跳未超时的节点） */
    public Map<String, Object> purgeOfflineNodes(String targetInstanceId, String actor) {
        String cleanTarget = clean(targetInstanceId);
        int purged;
        if (cleanTarget != null) {
            if (instanceId.equals(cleanTarget)) {
                throw new ApiException(HttpStatus.CONFLICT, "NODE_IS_CURRENT", "不能清理当前正在响应的本机实例节点");
            }
            var nodeOpt = jdbc.sql("""
                    select status, extract(epoch from (now() - heartbeat_at)) as hb_age_sec
                    from cluster_node where instance_id = ?
                    """).param(cleanTarget).query((rs, i) -> {
                String st = rs.getString("status");
                double age = Math.max(0.0, rs.getDouble("hb_age_sec"));
                return "offline".equals(st) || age > 120;
            }).optional();
            if (nodeOpt.isEmpty()) {
                throw ApiException.notFound("集群节点");
            }
            if (!nodeOpt.get()) {
                throw new ApiException(HttpStatus.CONFLICT, "NODE_STILL_ONLINE", "该节点心跳正常仍在线，仅允许清理已离线节点");
            }
            purged = jdbc.sql("delete from cluster_node where instance_id = ?").param(cleanTarget).update();
        } else {
            purged = jdbc.sql("""
                    delete from cluster_node
                    where instance_id <> ?
                      and (status = 'offline' or heartbeat_at < now() - interval '120 seconds')
                    """).param(instanceId).update();
        }
        try {
            jdbc.sql("""
                    insert into admin_audit_log (id, actor, action, target_type, target_id, detail)
                    values (?, ?, 'CLUSTER_NODE_PURGE', 'cluster_node', ?, ?::jsonb)
                    """).params("aud_" + Ids.random(10), actor, cleanTarget == null ? "offline_all" : cleanTarget,
                    Json.write(Map.of("purgedCount", purged, "instanceId", cleanTarget == null ? "all_offline" : cleanTarget))).update();
        } catch (Exception ignored) {}
        return Map.of("ok", true, "purgedCount", purged, "instanceId", cleanTarget == null ? "all_offline" : cleanTarget);
    }

    /** 立即回收所有租约已超时的卡死任务（原子清除租约、触发终态收尾与队列续跑，并记入操作审计） */
    public Map<String, Object> reclaimExpiredLeases(String actor) {
        List<com.tcl.csot.zhiye.entity.TaskRow> expired = jdbc.sql("""
                select * from task
                where status in ('PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')
                  and (lease_until is null or lease_until < now())
                order by created_at asc
                limit 100
                """).query(com.tcl.csot.zhiye.entity.TaskRow::map).list();
        List<String> reclaimedIds = new ArrayList<>();
        for (var t : expired) {
            int n = jdbc.sql("""
                    update task set
                      status = 'FAILED',
                      error_code = coalesce(error_code, 'WORKER_LOST'),
                      error_message = coalesce(error_message, '任务租约已超时，由管理员手动回收释放应用写锁'),
                      phase_label = '租约超时已回收',
                      finished_at = coalesce(finished_at, now()),
                      lease_owner = null,
                      lease_until = null
                    where id = ?
                      and status in ('PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')
                      and lease_owner is not distinct from ?
                      and (lease_until is null or lease_until < now())
                    """).params(t.id(), t.leaseOwner()).update();
            if (n > 0) {
                reclaimedIds.add(t.id());
                try {
                    if (taskService != null) {
                        taskService.afterTerminal(t, "FAILED", "WORKER_LOST", "任务租约已超时，由管理员手动回收释放应用写锁");
                    }
                    if (taskEvents != null && taskService != null) {
                        taskEvents.emit(t.id(), "status", Map.of("status", "FAILED", "phaseLabel", "租约超时已回收", "progress", t.progress()));
                        taskEvents.emit(t.id(), "finished", taskService.finishedPayload("FAILED", null, "WORKER_LOST", "任务租约已超时，由管理员手动回收释放应用写锁"));
                    }
                } catch (Exception ignored) {}
            }
        }
        int reclaimed = reclaimedIds.size();
        try {
            jdbc.sql("""
                    insert into admin_audit_log (id, actor, action, target_type, target_id, detail)
                    values (?, ?, 'TASK_LEASE_RECLAIM', 'task', 'expired_leases', ?::jsonb)
                    """).params("aud_" + Ids.random(10), actor,
                    Json.write(Map.of("reclaimedCount", reclaimed, "taskIds", reclaimedIds))).update();
        } catch (Exception ignored) {}
        return Map.of("ok", true, "reclaimedCount", reclaimed, "taskIds", reclaimedIds);
    }

    // ================= 内部采样与控制面辅助 =================

    private void sampleLiveLocalSandboxes() {
        if (liveBySandboxId.isEmpty() || jdbc == null) return;
        Map<Long, String> pidToSandbox = new LinkedHashMap<>();
        for (LiveHandle h : liveBySandboxId.values()) {
            if (!h.session().alive()) continue;
            RunnerSession.SandboxDescriptor d = h.session().descriptor();
            if (d != null && d.pid() != null && d.pid() > 0) {
                pidToSandbox.put(d.pid(), h.sandboxId());
            }
        }
        if (pidToSandbox.isEmpty()) return;

        Map<Long, ProcUsage> usage = readProcessUsageBatch(pidToSandbox.keySet());
        for (var e : pidToSandbox.entrySet()) {
            ProcUsage pu = usage.get(e.getKey());
            if (pu == null) continue;
            jdbc.sql("""
                    update sandbox_instance set
                      cpu_percent = ?,
                      memory_used_bytes = ?,
                      sampled_at = now()
                    where sandbox_id = ? and state in ('creating', 'active')
                    """).params(pu.cpuPercent(), pu.rssBytes(), e.getValue()).update();
        }
    }

    private Map<Long, ProcUsage> readProcessUsageBatch(Set<Long> pids) {
        Map<Long, ProcUsage> result = new LinkedHashMap<>();
        if (pids.isEmpty()) return result;
        String arg = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            Process p = new ProcessBuilder("ps", "-o", "pid=,%cpu=,rss=", "-p", arg)
                    .redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
            }
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return result;
            }
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 3) continue;
                try {
                    long pid = Long.parseLong(parts[0]);
                    double cpu = Double.parseDouble(parts[1]);
                    long rssKb = Long.parseLong(parts[2]);
                    result.put(pid, new ProcUsage(Math.round(cpu * 10.0) / 10.0, rssKb * 1024L));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {
            // 非 Unix 环境或 ps 不可用时静默跳过采样
        }
        return result;
    }

    private record ControlPlaneSnapshot(boolean available, boolean complete, String message, Set<String> liveRefs, List<Map<String, Object>> unregistered) {}

    private ControlPlaneSnapshot probeControlPlane(List<Map<String, Object>> ledgerRows) {
        Set<String> knownActiveRefs = ledgerRows.stream()
                .filter(it -> "creating".equals(it.get("state")) || "active".equals(it.get("state")))
                .map(it -> String.valueOf(it.getOrDefault("externalRef", "")))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());

        if (openSandboxManager != null) {
            try {
                Set<String> refs = new HashSet<>();
                List<Map<String, Object>> orphans = new ArrayList<>();
                boolean complete = true;
                int maxPages = 50;
                for (int page = 1; page <= maxPages; page++) {
                    PagedSandboxInfos paged = openSandboxManager.listSandboxInfos(
                            SandboxFilter.builder().metadata(Map.of("zhiye", "1")).pageSize(200).page(page).build());
                    if (paged == null) {
                        complete = false;
                        break;
                    }
                    if (paged.getSandboxInfos() != null) {
                        for (SandboxInfo info : paged.getSandboxInfos()) {
                            if (info.getId() == null) continue;
                            refs.add(info.getId());
                            if (!knownActiveRefs.contains(info.getId())) {
                                Map<String, String> md = info.getMetadata() == null ? Map.of() : info.getMetadata();
                                Map<String, Object> o = new LinkedHashMap<>();
                                o.put("sandboxId", info.getId());
                                o.put("externalRef", info.getId());
                                o.put("provider", "opensandbox");
                                o.put("kind", "unregistered");
                                o.put("state", "active");
                                o.put("taskId", md.get("zhiye.taskId"));
                                o.put("ownerInstance", "external");
                                o.put("onThisReplica", false);
                                o.put("taskRunning", false);
                                o.put("createdAt", info.getCreatedAt() == null ? null : info.getCreatedAt().toInstant().toString());
                                o.put("ageSeconds", info.getCreatedAt() == null ? 0 : Math.max(0, Duration.between(info.getCreatedAt().toInstant(), Instant.now()).getSeconds()));
                                orphans.add(o);
                            }
                        }
                    }
                    PaginationInfo pi = paged.getPagination();
                    if (pi == null || !pi.getHasNextPage()) {
                        break;
                    }
                    if (page == maxPages) {
                        complete = false;
                    }
                }
                String msg = complete ? "OpenSandbox 控制面正常" : "OpenSandbox 沙箱数量超过安全分页上限，控制面快照未完整";
                return new ControlPlaneSnapshot(true, complete, msg, refs, orphans);
            } catch (Exception e) {
                return new ControlPlaneSnapshot(false, false, "OpenSandbox 控制面不可达：" + e.getMessage(), Set.of(), List.of());
            }
        }

        if (kubernetesClient != null && k8sProps != null) {
            try {
                List<Pod> pods = kubernetesClient.pods().inNamespace(k8sProps.namespace())
                        .withLabel("app", "zhiye-sandbox")
                        .list().getItems();
                Set<String> refs = new HashSet<>();
                List<Map<String, Object>> orphans = new ArrayList<>();
                for (Pod p : pods) {
                    String podName = p.getMetadata().getName();
                    String fullRef = k8sProps.namespace() + "/" + podName;
                    refs.add(fullRef);
                    refs.add(podName);
                    if (!knownActiveRefs.contains(fullRef) && !knownActiveRefs.contains(podName)) {
                        String taskId = p.getMetadata().getLabels() == null ? null : p.getMetadata().getLabels().get("zhiye/task-id");
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("sandboxId", fullRef);
                        o.put("externalRef", fullRef);
                        o.put("provider", "k8s");
                        o.put("kind", "unregistered");
                        o.put("state", "active");
                        o.put("taskId", taskId);
                        o.put("ownerInstance", "k8s-cluster");
                        o.put("onThisReplica", false);
                        o.put("taskRunning", false);
                        o.put("createdAt", p.getMetadata().getCreationTimestamp());
                        orphans.add(o);
                    }
                }
                return new ControlPlaneSnapshot(true, true, "Kubernetes API 控制面正常", refs, orphans);
            } catch (Exception e) {
                return new ControlPlaneSnapshot(false, false, "Kubernetes 控制面不可达：" + e.getMessage(), Set.of(), List.of());
            }
        }

        return new ControlPlaneSnapshot(true, true, "本地进程沙箱模式 (ProcessHandle)", Set.of(), List.of());
    }

    private record KillOutcome(boolean killed, boolean notFound, boolean unsupportedRemote, String detail) {}

    private Map<String, Object> destroyServerOnly(String key, String op, String rsn) {
        String provider = props.sandbox().provider();
        if (openSandboxManager != null) {
            KillOutcome k = killRemoteOrPid("opensandbox", key, null, null);
            if (k.killed()) {
                recordDestroyAudit(key, null, null, null, "opensandbox", key, "server-only", op, rsn, false);
                return Map.of("ok", true, "status", "destroyed", "path", "server-only",
                        "sandboxId", key, "killedRuntime", true, "cancelledTask", false, "operator", op, "reason", rsn);
            }
            if (!k.notFound()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_KILL_FAILED", "控制面销毁沙箱失败：" + k.detail());
            }
        }
        if (kubernetesClient != null && k8sProps != null) {
            KillOutcome k = killRemoteOrPid("k8s", key, null, null);
            if (k.killed()) {
                recordDestroyAudit(key, null, null, null, "k8s", key, "server-only", op, rsn, false);
                return Map.of("ok", true, "status", "destroyed", "path", "server-only",
                        "sandboxId", key, "killedRuntime", true, "cancelledTask", false, "operator", op, "reason", rsn);
            }
            if (!k.notFound()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_KILL_FAILED", "Kubernetes 删除沙箱失败：" + k.detail());
            }
        }
        throw ApiException.notFound("沙箱实例");
    }

    private KillOutcome killRemoteOrPid(String provider, String externalRef, Object pidObj, String ownerInstance) {
        if ("opensandbox".equals(provider)) {
            if (openSandboxManager == null) {
                return new KillOutcome(false, false, true, "当前节点未配置 OpenSandbox 管理客户端，无法远程销毁");
            }
            if (externalRef == null || externalRef.isBlank()) {
                return new KillOutcome(false, true, false, "沙箱缺少 externalRef，按不存在核销");
            }
            try {
                openSandboxManager.killSandbox(externalRef);
                return new KillOutcome(true, false, false, "OpenSandbox 已销毁 " + externalRef);
            } catch (Exception e) {
                if (e instanceof SandboxApiException sae && Integer.valueOf(404).equals(sae.getStatusCode())) {
                    return new KillOutcome(false, true, false, "OpenSandbox 返回 404");
                }
                return new KillOutcome(false, false, false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        if ("k8s".equals(provider)) {
            if (kubernetesClient == null || k8sProps == null) {
                return new KillOutcome(false, false, true, "当前节点未配置 Kubernetes 客户端，无法远程销毁");
            }
            if (externalRef == null || externalRef.isBlank()) {
                return new KillOutcome(false, true, false, "Pod 缺少 externalRef，按不存在核销");
            }
            String podName = externalRef.contains("/") ? externalRef.substring(externalRef.indexOf('/') + 1) : externalRef;
            try {
                var del = kubernetesClient.pods().inNamespace(k8sProps.namespace()).withName(podName).withGracePeriod(0).delete();
                if (del != null && !del.isEmpty()) {
                    return new KillOutcome(true, false, false, "已删除 Pod " + podName);
                }
                var existing = kubernetesClient.pods().inNamespace(k8sProps.namespace()).withName(podName).get();
                if (existing == null) {
                    return new KillOutcome(false, true, false, "Pod 已不存在");
                }
                return new KillOutcome(false, false, false, "Pod 删除未确认完成：" + podName);
            } catch (Exception e) {
                return new KillOutcome(false, false, false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        // 本地进程沙箱：严禁跨副本按 PID 强杀（F05）
        if (!instanceId.equals(ownerInstance)) {
            return new KillOutcome(false, false, true,
                    "本地进程沙箱运行在节点 " + ownerInstance + "，当前节点 " + instanceId + " 禁止跨机按 PID 强杀");
        }
        if (pidObj instanceof Number n && n.longValue() > 0) {
            var phOpt = ProcessHandle.of(n.longValue());
            if (phOpt.isEmpty() || !phOpt.get().isAlive()) {
                return new KillOutcome(false, true, false, "本机进程已退出");
            }
            ProcessHandle ph = phOpt.get();
            ph.descendants().forEach(ProcessHandle::destroyForcibly);
            boolean destroyed = ph.destroyForcibly();
            if (destroyed || !ph.isAlive()) {
                return new KillOutcome(true, false, false, "已强杀本机进程 PID " + n.longValue());
            }
            return new KillOutcome(false, false, false, "无法终止本机进程 PID " + n.longValue());
        }
        return new KillOutcome(false, true, false, "无活跃 PID");
    }

    private void recordDestroyAudit(String sandboxId, String taskId, String workspaceId, String appId,
                                    String provider, String externalRef, String path,
                                    String operator, String reason, boolean cancelledTask) {
        try {
            jdbc.sql("""
                    insert into admin_audit_log (id, actor, action, target_type, target_id, detail)
                    values (?, ?, 'SANDBOX_DESTROY', 'sandbox', ?, ?::jsonb)
                    """).params("aud_" + Ids.random(10), operator, sandboxId, Json.write(Map.of(
                    "path", path,
                    "provider", provider == null ? "" : provider,
                    "externalRef", externalRef == null ? "" : externalRef,
                    "taskId", taskId == null ? "" : taskId,
                    "reason", reason,
                    "cancelledTask", cancelledTask))).update();
        } catch (Exception ignored) {}
        if (platformLogs != null) {
            platformLogs.warn("sandbox", "ADMIN_SANDBOX_DESTROY", workspaceId, operator, appId, taskId, null, null,
                    "管理员 " + operator + " 销毁沙箱 " + sandboxId + "（路径：" + path + "，原因：" + reason + "）",
                    null, Map.of("sandboxId", sandboxId, "externalRef", externalRef == null ? "" : externalRef,
                            "path", path, "reason", reason, "cancelledTask", cancelledTask));
        }
    }

    private static Map<String, Object> mapSandboxRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sandboxId", rs.getString("sandbox_id"));
        m.put("provider", rs.getString("provider"));
        m.put("state", rs.getString("state"));
        m.put("taskId", rs.getString("task_id"));
        m.put("taskStatus", rs.getString("task_status"));
        m.put("taskProgress", rs.getObject("task_progress"));
        m.put("taskPhase", rs.getString("task_phase"));
        m.put("appId", rs.getString("app_id"));
        m.put("appName", rs.getString("app_name"));
        m.put("workspaceId", rs.getString("workspace_id"));
        m.put("userId", rs.getString("user_id"));
        m.put("ownerName", rs.getString("owner_name"));
        m.put("ownerLoginName", rs.getString("owner_login_name"));
        m.put("ownerInstance", rs.getString("owner_instance"));
        m.put("workerOwner", rs.getString("worker_owner"));
        m.put("externalRef", rs.getString("external_ref"));
        m.put("imageOrCmd", rs.getString("image_or_cmd"));
        m.put("cpuLimit", rs.getString("cpu_limit"));
        m.put("memoryLimit", rs.getString("memory_limit"));
        m.put("allowNet", rs.getBoolean("allow_net"));
        m.put("pid", rs.getObject("pid"));
        m.put("startupMs", rs.getObject("startup_ms"));
        m.put("cpuPercent", rs.getObject("cpu_percent"));
        Long memUsed = rs.getObject("memory_used_bytes") instanceof Number n ? n.longValue() : null;
        Long memLimit = rs.getObject("memory_limit_bytes") instanceof Number n ? n.longValue() : null;
        m.put("memoryUsedBytes", memUsed);
        m.put("memoryLimitBytes", memLimit);
        m.put("memoryPercent", (memUsed != null && memLimit != null && memLimit > 0)
                ? Math.round((double) memUsed * 1000.0 / memLimit) / 10.0 : null);
        m.put("sampledAt", Db.iso(Db.ts(rs, "sampled_at")));
        m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
        m.put("readyAt", Db.iso(Db.ts(rs, "ready_at")));
        m.put("lastActiveAt", Db.iso(Db.ts(rs, "last_active_at")));
        m.put("destroyedAt", Db.iso(Db.ts(rs, "destroyed_at")));
        m.put("destroyedBy", rs.getString("destroyed_by"));
        m.put("destroyReason", rs.getString("destroy_reason"));
        m.put("ageSeconds", Math.round(Math.max(0.0, rs.getDouble("age_sec")) * 10.0) / 10.0);
        m.put("idleSeconds", Math.round(Math.max(0.0, rs.getDouble("idle_sec")) * 10.0) / 10.0);
        return m;
    }

    private String resolveWorkspaceUserId(String workspaceId) {
        if (workspaceId == null || jdbc == null) return null;
        try {
            return jdbc.sql("select owner_user_id from workspace where id = ?").param(workspaceId).query(String.class).optional().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    static Long parseMemBytes(String s) {
        if (s == null || s.isBlank()) return null;
        Matcher m = MEM_SIZE.matcher(s.trim());
        if (!m.matches()) return null;
        double val = Double.parseDouble(m.group(1));
        String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        long mul = switch (unit) {
            case "ki", "k" -> 1024L;
            case "mi", "m" -> 1024L * 1024;
            case "gi", "g" -> 1024L * 1024 * 1024;
            case "ti", "t" -> 1024L * 1024 * 1024 * 1024;
            default -> 1L;
        };
        return Math.round(val * mul);
    }

    private static String clean(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
