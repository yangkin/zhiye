package com.tcl.csot.zhiye.service.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 多实例共享 + 持久化运行期监控指标服务（基于 PostgreSQL `runtime_metric_event`，不依赖外部时序库）：
 * - 汇总跨实例/重启后的任务耗时（含 p95 / max）、成功率、失败码分布、沙箱冷启动、模型延迟与额度错误率；
 * - 提供近 24 小时小时级趋势（trend）与阈值告警（alerts），以及 Prometheus 文本指标导出。
 */
@Service
public class RuntimeMetricsService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeMetricsService.class);

    private static final class Timing {
        final LongAdder count = new LongAdder();
        final LongAdder errors = new LongAdder();
        final LongAdder totalMs = new LongAdder();
        final AtomicLong maxMs = new AtomicLong();

        void record(long durationMs, boolean ok) {
            long ms = Math.max(0L, durationMs);
            count.increment();
            if (!ok) errors.increment();
            totalMs.add(ms);
            maxMs.accumulateAndGet(ms, Math::max);
        }

        Map<String, Object> view() {
            long n = count.sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", n);
            m.put("errors", errors.sum());
            m.put("avgMs", n == 0 ? 0L : Math.round((double) totalMs.sum() / n));
            m.put("maxMs", maxMs.get());
            return m;
        }
    }

    private final JdbcClient jdbc;
    private final String instanceId;
    private final AtomicInteger ops = new AtomicInteger();

    private final LongAdder taskSucceeded = new LongAdder();
    private final LongAdder taskFailed = new LongAdder();
    private final LongAdder taskCancelled = new LongAdder();
    private final Map<String, Timing> tasksByType = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> failureCodes = new ConcurrentHashMap<>();
    private final Timing sandbox = new Timing();
    private final Timing llm = new Timing();
    private final LongAdder llmRetries = new LongAdder();
    private final LongAdder llmQuotaErrors = new LongAdder();

    public RuntimeMetricsService() {
        this(null);
    }

    @Autowired
    public RuntimeMetricsService(@Autowired(required = false) JdbcClient jdbc) {
        this.jdbc = jdbc;
        String host = System.getenv("HOSTNAME");
        this.instanceId = (host != null && !host.isBlank()) ? host : "local-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public void recordTask(String type, String status, String errorCode, long durationMs) {
        String key = (type == null || type.isBlank()) ? "unknown" : type;
        boolean ok = "SUCCEEDED".equals(status);
        long ms = Math.max(0L, durationMs);
        tasksByType.computeIfAbsent(key, k -> new Timing()).record(ms, ok);
        if ("SUCCEEDED".equals(status)) taskSucceeded.increment();
        else if ("CANCELLED".equals(status)) taskCancelled.increment();
        else {
            taskFailed.increment();
            String code = (errorCode == null || errorCode.isBlank()) ? "UNKNOWN" : errorCode;
            failureCodes.computeIfAbsent(code, k -> new LongAdder()).increment();
        }
        persist("task", key, ok, "FAILED".equals(status) ? errorCode : status, ms, 0, false);
    }

    public void recordSandboxStart(long durationMs, boolean ok) {
        long ms = Math.max(0L, durationMs);
        sandbox.record(ms, ok);
        persist("sandbox", "sandbox", ok, ok ? null : "SANDBOX_FAILED", ms, 0, false);
    }

    public void recordLlmCall(long durationMs, boolean ok, boolean quotaError, int retries) {
        long ms = Math.max(0L, durationMs);
        llm.record(ms, ok);
        if (retries > 0) llmRetries.add(retries);
        if (quotaError) llmQuotaErrors.increment();
        persist("llm", "upstream", ok, quotaError ? "PROVIDER_QUOTA" : (ok ? null : "LLM_ERROR"), ms, retries, quotaError);
    }

    private void persist(String category, String name, boolean ok, String errorCode, long durationMs, int retries, boolean quotaError) {
        if (jdbc == null) return;
        try {
            jdbc.sql("""
                    insert into runtime_metric_event (instance_id, category, name, ok, error_code, duration_ms, retries, quota_error)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """)
                    .params(instanceId, category, name, ok, errorCode, durationMs, retries, quotaError)
                    .update();
            if ((ops.incrementAndGet() & 255) == 0) {
                jdbc.sql("delete from runtime_metric_event where created_at < now() - interval '7 days'").update();
            }
        } catch (Exception e) {
            log.debug("写入运行期指标失败（不影响业务流程）：{}", e.getMessage());
        }
    }

    public Map<String, Object> snapshot() {
        if (jdbc == null) return localSnapshot();
        try {
            return dbSnapshot();
        } catch (Exception e) {
            log.debug("查询库内指标汇总失败，回退进程内视图：{}", e.getMessage());
            return localSnapshot();
        }
    }

    private Map<String, Object> dbSnapshot() {
        Map<String, Object> taskAgg = jdbc.sql("""
                select count(*) as total,
                       coalesce(sum(case when ok then 1 else 0 end), 0) as succeeded,
                       coalesce(sum(case when not ok and coalesce(error_code, '') <> 'CANCELLED' then 1 else 0 end), 0) as failed,
                       coalesce(sum(case when error_code = 'CANCELLED' then 1 else 0 end), 0) as cancelled,
                       coalesce(round(avg(duration_ms))::bigint, 0) as avg_ms,
                       coalesce(round(percentile_cont(0.95) within group (order by duration_ms))::bigint, 0) as p95_ms,
                       coalesce(max(duration_ms), 0) as max_ms
                from runtime_metric_event where category = 'task'
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", rs.getLong("total"));
            m.put("succeeded", rs.getLong("succeeded"));
            m.put("failed", rs.getLong("failed"));
            m.put("cancelled", rs.getLong("cancelled"));
            m.put("avgMs", rs.getLong("avg_ms"));
            m.put("p95Ms", rs.getLong("p95_ms"));
            m.put("maxMs", rs.getLong("max_ms"));
            return m;
        }).single();

        Map<String, Object> byType = new LinkedHashMap<>();
        jdbc.sql("""
                select name,
                       count(*) as cnt,
                       coalesce(sum(case when not ok then 1 else 0 end), 0) as err,
                       coalesce(round(avg(duration_ms))::bigint, 0) as avg_ms,
                       coalesce(round(percentile_cont(0.95) within group (order by duration_ms))::bigint, 0) as p95_ms,
                       coalesce(max(duration_ms), 0) as max_ms
                from runtime_metric_event where category = 'task' group by name order by name
                """).query(rs -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("count", rs.getLong("cnt"));
            t.put("errors", rs.getLong("err"));
            t.put("avgMs", rs.getLong("avg_ms"));
            t.put("p95Ms", rs.getLong("p95_ms"));
            t.put("maxMs", rs.getLong("max_ms"));
            byType.put(rs.getString("name"), t);
        });
        Map<String, Long> codes = new LinkedHashMap<>();
        jdbc.sql("""
                select coalesce(error_code, 'UNKNOWN') as code, count(*) as cnt
                from runtime_metric_event
                where category = 'task' and not ok and coalesce(error_code, '') <> 'CANCELLED'
                group by coalesce(error_code, 'UNKNOWN') order by cnt desc
                """).query(rs -> { codes.put(rs.getString("code"), rs.getLong("cnt")); });
        taskAgg.put("byType", byType);
        taskAgg.put("failureCodes", codes);

        Map<String, Object> sbAgg = jdbc.sql("""
                select count(*) as cnt,
                       coalesce(sum(case when not ok then 1 else 0 end), 0) as err,
                       coalesce(round(avg(duration_ms))::bigint, 0) as avg_ms,
                       coalesce(round(percentile_cont(0.95) within group (order by duration_ms))::bigint, 0) as p95_ms,
                       coalesce(max(duration_ms), 0) as max_ms
                from runtime_metric_event where category = 'sandbox'
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", rs.getLong("cnt"));
            m.put("errors", rs.getLong("err"));
            m.put("avgMs", rs.getLong("avg_ms"));
            m.put("p95Ms", rs.getLong("p95_ms"));
            m.put("maxMs", rs.getLong("max_ms"));
            return m;
        }).single();

        Map<String, Object> llmAgg = jdbc.sql("""
                select count(*) as cnt,
                       coalesce(sum(case when not ok then 1 else 0 end), 0) as err,
                       coalesce(sum(retries), 0) as retries,
                       coalesce(sum(case when quota_error then 1 else 0 end), 0) as quota_err,
                       coalesce(round(avg(duration_ms))::bigint, 0) as avg_ms,
                       coalesce(round(percentile_cont(0.95) within group (order by duration_ms))::bigint, 0) as p95_ms,
                       coalesce(max(duration_ms), 0) as max_ms
                from runtime_metric_event where category = 'llm'
                """).query((rs, i) -> {
            long cnt = rs.getLong("cnt");
            long q = rs.getLong("quota_err");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", cnt);
            m.put("errors", rs.getLong("err"));
            m.put("retries", rs.getLong("retries"));
            m.put("quotaErrors", q);
            m.put("quotaErrorRate", cnt == 0 ? 0.0 : Math.round((double) q / cnt * 10000.0) / 10000.0);
            m.put("avgMs", rs.getLong("avg_ms"));
            m.put("p95Ms", rs.getLong("p95_ms"));
            m.put("maxMs", rs.getLong("max_ms"));
            return m;
        }).single();

        List<Map<String, Object>> trend = jdbc.sql("""
                select to_char(date_trunc('hour', created_at), 'YYYY-MM-DD HH24:00') as bucket,
                       coalesce(sum(case when category = 'task' then 1 else 0 end), 0) as tasks,
                       coalesce(sum(case when category = 'task' and not ok and coalesce(error_code, '') <> 'CANCELLED' then 1 else 0 end), 0) as task_failures,
                       coalesce(sum(case when category = 'llm' then 1 else 0 end), 0) as llm_calls,
                       coalesce(sum(case when category = 'llm' and quota_error then 1 else 0 end), 0) as quota_errors
                from runtime_metric_event
                where created_at >= now() - interval '24 hours'
                group by date_trunc('hour', created_at)
                order by date_trunc('hour', created_at) asc
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", rs.getString("bucket"));
            m.put("tasks", rs.getLong("tasks"));
            m.put("taskFailures", rs.getLong("task_failures"));
            m.put("llmCalls", rs.getLong("llm_calls"));
            m.put("quotaErrors", rs.getLong("quota_errors"));
            return m;
        }).list();

        List<String> instances = jdbc.sql("""
                select distinct instance_id from runtime_metric_event
                where created_at >= now() - interval '24 hours' order by instance_id
                """).query(String.class).list();

        List<Map<String, Object>> alerts = evaluateAlerts();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "cluster");
        out.put("instances", instances);
        out.put("tasks", taskAgg);
        out.put("sandbox", sbAgg);
        out.put("llm", llmAgg);
        out.put("trend", trend);
        out.put("alerts", alerts);
        return out;
    }

    /** 最近 1 小时窗口阈值告警评估：任务失败率 > 25%、模型额度错误率 > 15%、沙箱冷启动均值 > 15s */
    private List<Map<String, Object>> evaluateAlerts() {
        List<Map<String, Object>> alerts = new ArrayList<>();
        var task1h = jdbc.sql("""
                select count(*) as total,
                       coalesce(sum(case when not ok and coalesce(error_code, '') <> 'CANCELLED' then 1 else 0 end), 0) as failed
                from runtime_metric_event where category = 'task' and created_at >= now() - interval '1 hour'
                """).query((rs, i) -> new long[]{rs.getLong("total"), rs.getLong("failed")}).single();
        if (task1h[0] >= 5 && (double) task1h[1] / task1h[0] > 0.25) {
            alerts.add(alert("WARN", "TASK_FAILURE_RATE_HIGH",
                    "近 1 小时任务失败率过高", (double) task1h[1] / task1h[0], 0.25));
        }

        var llm1h = jdbc.sql("""
                select count(*) as total,
                       coalesce(sum(case when quota_error then 1 else 0 end), 0) as quota_err
                from runtime_metric_event where category = 'llm' and created_at >= now() - interval '1 hour'
                """).query((rs, i) -> new long[]{rs.getLong("total"), rs.getLong("quota_err")}).single();
        if (llm1h[0] >= 10 && (double) llm1h[1] / llm1h[0] > 0.15) {
            alerts.add(alert("WARN", "LLM_QUOTA_ERROR_HIGH",
                    "近 1 小时模型额度拒绝率过高", (double) llm1h[1] / llm1h[0], 0.15));
        }

        var sb1h = jdbc.sql("""
                select count(*) as total, coalesce(round(avg(duration_ms))::bigint, 0) as avg_ms
                from runtime_metric_event where category = 'sandbox' and created_at >= now() - interval '1 hour'
                """).query((rs, i) -> new long[]{rs.getLong("total"), rs.getLong("avg_ms")}).single();
        if (sb1h[0] >= 3 && sb1h[1] > 15000) {
            alerts.add(alert("WARN", "SANDBOX_COLD_START_SLOW",
                    "近 1 小时沙箱平均启动耗时超过 15 秒", sb1h[1], 15000));
        }
        return alerts;
    }

    private static Map<String, Object> alert(String level, String code, String message, double value, double threshold) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("code", code);
        m.put("message", message);
        m.put("value", Math.round(value * 10000.0) / 10000.0);
        m.put("threshold", threshold);
        return m;
    }

    private Map<String, Object> localSnapshot() {
        Map<String, Object> taskTypes = new LinkedHashMap<>();
        tasksByType.forEach((k, v) -> taskTypes.put(k, v.view()));
        Map<String, Long> codes = new LinkedHashMap<>();
        failureCodes.forEach((k, v) -> codes.put(k, v.sum()));
        Map<String, Object> tasks = new LinkedHashMap<>();
        long ok = taskSucceeded.sum(), fail = taskFailed.sum(), cancel = taskCancelled.sum();
        tasks.put("total", ok + fail + cancel);
        tasks.put("succeeded", ok);
        tasks.put("failed", fail);
        tasks.put("cancelled", cancel);
        tasks.put("byType", taskTypes);
        tasks.put("failureCodes", codes);

        Map<String, Object> llmView = new LinkedHashMap<>(llm.view());
        long llmCount = llm.count.sum();
        long quota = llmQuotaErrors.sum();
        llmView.put("retries", llmRetries.sum());
        llmView.put("quotaErrors", quota);
        llmView.put("quotaErrorRate", llmCount == 0 ? 0.0 : Math.round((double) quota / llmCount * 10000.0) / 10000.0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "local");
        out.put("instances", List.of(instanceId));
        out.put("tasks", tasks);
        out.put("sandbox", sandbox.view());
        out.put("llm", llmView);
        out.put("trend", List.of());
        out.put("alerts", List.of());
        return out;
    }

    @SuppressWarnings("unchecked")
    public String prometheus() {
        Map<String, Object> s = snapshot();
        Map<String, Object> t = (Map<String, Object>) s.getOrDefault("tasks", Map.of());
        Map<String, Object> sb = (Map<String, Object>) s.getOrDefault("sandbox", Map.of());
        Map<String, Object> l = (Map<String, Object>) s.getOrDefault("llm", Map.of());
        List<?> alerts = (List<?>) s.getOrDefault("alerts", List.of());
        return String.join("\n",
                "# TYPE zhiye_tasks_total counter",
                "zhiye_tasks_total{status=\"succeeded\"} " + t.getOrDefault("succeeded", 0),
                "zhiye_tasks_total{status=\"failed\"} " + t.getOrDefault("failed", 0),
                "zhiye_tasks_total{status=\"cancelled\"} " + t.getOrDefault("cancelled", 0),
                "# TYPE zhiye_sandbox_starts_total counter",
                "zhiye_sandbox_starts_total " + sb.getOrDefault("count", 0),
                "# TYPE zhiye_sandbox_start_avg_ms gauge",
                "zhiye_sandbox_start_avg_ms " + sb.getOrDefault("avgMs", 0),
                "# TYPE zhiye_llm_calls_total counter",
                "zhiye_llm_calls_total " + l.getOrDefault("count", 0),
                "# TYPE zhiye_llm_quota_errors_total counter",
                "zhiye_llm_quota_errors_total " + l.getOrDefault("quotaErrors", 0),
                "# TYPE zhiye_alerts_active gauge",
                "zhiye_alerts_active " + alerts.size(),
                "");
    }
}
