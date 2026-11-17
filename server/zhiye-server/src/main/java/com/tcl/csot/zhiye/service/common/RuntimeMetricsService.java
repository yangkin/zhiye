package com.tcl.csot.zhiye.service.common;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内运行期指标汇总（无外部监控中间件依赖）：
 * 统计任务耗时/成功率/失败码分布、沙箱冷启动延迟、上游模型调用延迟与额度错误率，供 /api/meta/metrics 暴露。
 */
@Service
public class RuntimeMetricsService {
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
            long err = errors.sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", n);
            m.put("errors", err);
            m.put("avgMs", n == 0 ? 0L : Math.round((double) totalMs.sum() / n));
            m.put("maxMs", maxMs.get());
            return m;
        }
    }

    private final LongAdder taskSucceeded = new LongAdder();
    private final LongAdder taskFailed = new LongAdder();
    private final LongAdder taskCancelled = new LongAdder();
    private final Map<String, Timing> tasksByType = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> failureCodes = new ConcurrentHashMap<>();

    private final Timing sandbox = new Timing();

    private final Timing llm = new Timing();
    private final LongAdder llmRetries = new LongAdder();
    private final LongAdder llmQuotaErrors = new LongAdder();

    public void recordTask(String type, String status, String errorCode, long durationMs) {
        String key = (type == null || type.isBlank()) ? "unknown" : type;
        boolean ok = "SUCCEEDED".equals(status);
        tasksByType.computeIfAbsent(key, k -> new Timing()).record(durationMs, ok);
        if ("SUCCEEDED".equals(status)) taskSucceeded.increment();
        else if ("CANCELLED".equals(status)) taskCancelled.increment();
        else {
            taskFailed.increment();
            String code = (errorCode == null || errorCode.isBlank()) ? "UNKNOWN" : errorCode;
            failureCodes.computeIfAbsent(code, k -> new LongAdder()).increment();
        }
    }

    public void recordSandboxStart(long durationMs, boolean ok) {
        sandbox.record(durationMs, ok);
    }

    public void recordLlmCall(long durationMs, boolean ok, boolean quotaError, int retries) {
        llm.record(durationMs, ok);
        if (retries > 0) llmRetries.add(retries);
        if (quotaError) llmQuotaErrors.increment();
    }

    public Map<String, Object> snapshot() {
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
        out.put("tasks", tasks);
        out.put("sandbox", sandbox.view());
        out.put("llm", llmView);
        return out;
    }
}
