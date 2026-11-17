package com.tcl.csot.zhiye.service.ops;

import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全链路平台事件与诊断日志记录服务（写入 PostgreSQL `platform_event_log`）：
 * 覆盖云数据库 Schema/DDL 执行、模型网关调用与报错、全局 API 异常堆栈、沙箱启动故障及应用生命周期事件。
 * 写入全程 fail-safe，不影响主业务流程。
 */
@Service
public class PlatformLogService {
    private static final Logger log = LoggerFactory.getLogger(PlatformLogService.class);
    private static final int MAX_SUMMARY = 500;
    private static final int MAX_DETAIL = 32_000;

    private final JdbcClient jdbc;
    private final AtomicInteger ops = new AtomicInteger();

    public PlatformLogService(@Autowired(required = false) JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void info(String category, String action, String workspaceId, String userId, String appId, String taskId,
                     String summary, String detail, Map<String, Object> meta) {
        record(category, "INFO", action, workspaceId, userId, appId, taskId, true, null, null, summary, detail, meta);
    }

    public void warn(String category, String action, String workspaceId, String userId, String appId, String taskId,
                     Integer statusCode, Integer durationMs, String summary, String detail, Map<String, Object> meta) {
        record(category, "WARN", action, workspaceId, userId, appId, taskId, false, statusCode, durationMs, summary, detail, meta);
    }

    public void error(String category, String action, String workspaceId, String userId, String appId, String taskId,
                      Integer statusCode, Integer durationMs, String summary, String detail, Map<String, Object> meta) {
        record(category, "ERROR", action, workspaceId, userId, appId, taskId, false, statusCode, durationMs, summary, detail, meta);
    }

    public void record(String category, String level, String action, String workspaceId, String userId, String appId,
                       String taskId, boolean ok, Integer statusCode, Integer durationMs, String summary, String detail,
                       Map<String, Object> meta) {
        if (jdbc == null) return;
        try {
            String safeSummary = cleanText(summary, MAX_SUMMARY);
            String safeDetail = cleanText(detail, MAX_DETAIL);
            String metaJson = meta == null || meta.isEmpty() ? "{}" : cleanNullBytes(Json.write(meta));
            jdbc.sql("""
                    insert into platform_event_log
                      (category, level, action, workspace_id, user_id, app_id, task_id, ok, status_code, duration_ms, summary, detail, meta)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """)
                    .params(
                            safeStr(category, "system"),
                            safeStr(level, ok ? "INFO" : "ERROR"),
                            safeStr(action, "EVENT"),
                            blankToNull(workspaceId),
                            blankToNull(userId),
                            blankToNull(appId),
                            blankToNull(taskId),
                            ok,
                            statusCode,
                            durationMs,
                            safeSummary == null ? "" : safeSummary,
                            safeDetail,
                            metaJson)
                    .update();
            if ((ops.incrementAndGet() & 255) == 0) {
                jdbc.sql("delete from platform_event_log where created_at < now() - interval '30 days'").update();
            }
        } catch (Exception e) {
            log.debug("写入平台事件日志失败（不影响主流程）：{}", e.getMessage());
        }
    }

    public static String stackTrace(Throwable t) {
        if (t == null) return null;
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return cleanText(sw.toString(), MAX_DETAIL);
    }

    private static String safeStr(String s, String def) {
        return (s == null || s.isBlank()) ? def : s.trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String cleanNullBytes(String s) {
        return s == null ? null : s.replace("\u0000", "");
    }

    private static String cleanText(String s, int max) {
        if (s == null) return null;
        String cleaned = cleanNullBytes(s).trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "\n…（内容过长已截断）";
    }
}
