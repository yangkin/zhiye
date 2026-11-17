package com.tcl.csot.zhiye.service.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.TaskEventRepository;
import com.tcl.csot.zhiye.repository.TaskRepository;
import com.tcl.csot.zhiye.service.common.RuntimeMetricsService;
import com.tcl.csot.zhiye.service.site.PageViewRecorder;
import com.tcl.csot.zhiye.service.task.TaskService;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.LogRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 平台运维与开发过程日志排查服务：
 * - 跨工作空间检索 AI 开发任务（含输入 Prompt、错误码、阶段、耗时、Token）；
 * - 还原完整任务过程事件（task_event：thinking / tool / verify / log / steer / confirm）；
 * - 只读读取并搜索沙箱控制日志（runtime/logs/tasks/{taskId}.log）与本地服务日志；
 * - 联动检索云函数日志（app_fn_log）与数据源代理调用日志（data_source_call）。
 */
@Service
public class OpsLogService {
    private static final ZoneId ZONE = PageViewRecorder.ZONE;
    private static final Set<String> ACTIVE_STATUSES = Set.of(
            "QUEUED", "PREPARING", "RUNNING", "VERIFYING", "COMMITTING", "CANCELLING");

    private final JdbcClient jdbc;
    private final TaskRepository tasks;
    private final TaskEventRepository eventRepo;
    private final TaskService taskService;
    private final ZhiyeProperties props;

    @Autowired(required = false)
    private RuntimeMetricsService runtimeMetrics;

    @Autowired(required = false)
    private ClusterSandboxService clusterSandbox;

    public OpsLogService(JdbcClient jdbc, TaskRepository tasks, TaskEventRepository eventRepo,
                         TaskService taskService, ZhiyeProperties props) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.eventRepo = eventRepo;
        this.taskService = taskService;
        this.props = props;
    }

    public Map<String, Object> listTasks(String currentWs, String rangeParam, String statusParam, String typeParam,
                                         String workspaceIdParam, String userIdParam, String errorCodeParam,
                                         String qParam, int limitParam, int offsetParam) {
        return listTasks(currentWs, rangeParam, statusParam, typeParam, workspaceIdParam, userIdParam, errorCodeParam, qParam, limitParam, offsetParam, true);
    }

    public Map<String, Object> listTasks(String currentWs, String rangeParam, String statusParam, String typeParam,
                                         String workspaceIdParam, String userIdParam, String errorCodeParam,
                                         String qParam, int limitParam, int offsetParam, boolean includeActors) {
        String range = normalizeRange(rangeParam, "7d");
        OffsetDateTime sinceTs = sinceTimestamp(range);
        String status = clean(statusParam);
        String type = clean(typeParam);
        String wsId = clean(workspaceIdParam);
        String userId = clean(userIdParam);
        String errorCode = clean(errorCodeParam);
        String q = clean(qParam);
        int limit = Math.max(1, Math.min(100, limitParam));
        int offset = Math.max(0, offsetParam);

        StringBuilder baseWhere = new StringBuilder(" where 1=1 ");
        List<Object> baseParams = new ArrayList<>();
        if (sinceTs != null) {
            baseWhere.append(" and t.created_at >= ? ");
            baseParams.add(sinceTs);
        }
        if (wsId != null) {
            baseWhere.append(" and t.workspace_id = ? ");
            baseParams.add(wsId);
        }
        if (userId != null) {
            baseWhere.append(" and w.owner_user_id = ? ");
            baseParams.add(userId);
        }
        if (type != null && !"ALL".equalsIgnoreCase(type)) {
            baseWhere.append(" and t.type = ? ");
            baseParams.add(type);
        }
        if (errorCode != null) {
            baseWhere.append(" and t.error_code = ? ");
            baseParams.add(errorCode);
        }
        if (q != null) {
            String pat = "%" + escapeLike(q) + "%";
            baseWhere.append("""
                     and (
                       t.id ilike ? escape '\\'
                       or t.app_id ilike ? escape '\\'
                       or t.workspace_id ilike ? escape '\\'
                       or coalesce(a.name, '') ilike ? escape '\\'
                       or coalesce(t.error_code, '') ilike ? escape '\\'
                       or coalesce(t.error_message, '') ilike ? escape '\\'
                       or coalesce(t.phase_label, '') ilike ? escape '\\'
                       or coalesce(t.input->>'prompt', t.input->>'content', a.prompt, '') ilike ? escape '\\'
                       or coalesce(u.login_name, '') ilike ? escape '\\'
                       or coalesce(u.real_name, '') ilike ? escape '\\'
                     )
                    """);
            for (int i = 0; i < 10; i++) baseParams.add(pat);
        }

        String fromSql = """
                from task t
                left join app a on a.id = t.app_id
                left join workspace w on w.id = t.workspace_id
                left join app_user u on u.id = w.owner_user_id
                """;

        Map<String, Object> summary = jdbc.sql("""
                select
                  count(*) as total,
                  count(*) filter (where t.status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')) as active,
                  count(*) filter (where t.status = 'FAILED') as failed,
                  count(*) filter (where t.status = 'SUCCEEDED') as succeeded,
                  count(*) filter (where t.status = 'CANCELLED') as cancelled
                """ + fromSql + baseWhere)
                .params(baseParams)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("total", rs.getLong("total"));
                    m.put("active", rs.getLong("active"));
                    m.put("failed", rs.getLong("failed"));
                    m.put("succeeded", rs.getLong("succeeded"));
                    m.put("cancelled", rs.getLong("cancelled"));
                    return m;
                }).single();

        List<Map<String, Object>> errorCodes = jdbc.sql("""
                select coalesce(nullif(t.error_code, ''), 'UNKNOWN') as code, count(*) as cnt
                """ + fromSql + baseWhere + """
                 and t.status = 'FAILED'
                group by 1 order by cnt desc limit 10
                """)
                .params(baseParams)
                .query((rs, i) -> Map.<String, Object>of(
                        "code", rs.getString("code"),
                        "count", rs.getLong("cnt")))
                .list();

        StringBuilder fullWhere = new StringBuilder(baseWhere);
        List<Object> fullParams = new ArrayList<>(baseParams);
        if (status != null && !"ALL".equalsIgnoreCase(status)) {
            if ("ACTIVE".equalsIgnoreCase(status)) {
                fullWhere.append(" and t.status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING') ");
            } else {
                fullWhere.append(" and t.status = ? ");
                fullParams.add(status.toUpperCase(Locale.ROOT));
            }
        }

        long total = jdbc.sql("select count(*) " + fromSql + fullWhere)
                .params(fullParams)
                .query(Long.class).single();

        List<Object> pageParams = new ArrayList<>(fullParams);
        pageParams.add(limit);
        pageParams.add(offset);

        List<Map<String, Object>> items = jdbc.sql("""
                select
                  t.*,
                  coalesce(a.name, t.app_id) as app_name,
                  coalesce(a.type, '') as app_type,
                  coalesce(a.project_kind, 'template') as project_kind,
                  coalesce(a.model, 'auto') as app_model,
                  coalesce(a.mode, '适中') as app_mode,
                  coalesce(a.prompt, '') as app_prompt,
                  u.id as owner_user_id,
                  coalesce(u.login_name, '') as owner_login_name,
                  coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name,
                  (select count(*) from task_event te where te.task_id = t.id) as event_count,
                  (select count(*) from task_event te where te.task_id = t.id and te.type = 'log') as log_event_count,
                  (select count(*) from task_event te where te.task_id = t.id and te.type = 'tool' and coalesce((te.payload->>'ok')::boolean, true) = false) as tool_error_count
                """ + fromSql + fullWhere + """
                order by t.created_at desc
                limit ? offset ?
                """)
                .params(pageParams)
                .query((rs, i) -> mapTaskSummary(rs, currentWs))
                .list();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", Map.of(
                "range", range,
                "currentWorkspaceId", currentWs,
                "generatedAt", Instant.now().toString()));
        out.put("summary", summary);
        out.put("errorCodes", errorCodes);
        out.put("actors", includeActors ? actorsDirectory() : List.of());
        out.put("items", items);
        out.put("total", total);
        out.put("limit", limit);
        out.put("offset", offset);
        return out;
    }

    public Map<String, Object> taskDetail(String currentWs, String taskId, int tailLines) {
        String id = clean(taskId);
        if (id == null) throw ApiException.validation("缺少 taskId");

        Map<String, Object> task = jdbc.sql("""
                select
                  t.*,
                  coalesce(a.name, t.app_id) as app_name,
                  coalesce(a.type, '') as app_type,
                  coalesce(a.project_kind, 'template') as project_kind,
                  coalesce(a.model, 'auto') as app_model,
                  coalesce(a.mode, '适中') as app_mode,
                  coalesce(a.prompt, '') as app_prompt,
                  a.deleted_at as app_deleted_at,
                  u.id as owner_user_id,
                  coalesce(u.login_name, '') as owner_login_name,
                  coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name,
                  (select count(*) from task_event te where te.task_id = t.id) as event_count,
                  (select count(*) from task_event te where te.task_id = t.id and te.type = 'log') as log_event_count,
                  (select count(*) from task_event te where te.task_id = t.id and te.type = 'tool' and coalesce((te.payload->>'ok')::boolean, true) = false) as tool_error_count
                from task t
                left join app a on a.id = t.app_id
                left join workspace w on w.id = t.workspace_id
                left join app_user u on u.id = w.owner_user_id
                where t.id = ?
                """)
                .param(id)
                .query((rs, i) -> {
                    Map<String, Object> m = mapTaskSummary(rs, currentWs);
                    m.put("baseVersionId", rs.getString("base_version_id"));
                    m.put("resultVersionId", rs.getString("result_version_id"));
                    m.put("parentTaskId", rs.getString("parent_task_id"));
                    m.put("idempotencyKey", rs.getString("idempotency_key"));
                    m.put("leaseUntil", Db.iso(Db.ts(rs, "lease_until")));
                    m.put("tokenBudget", rs.getLong("token_budget"));
                    m.put("input", Json.read(rs.getString("input")));
                    m.put("result", Json.read(rs.getString("result")));
                    m.put("todos", Json.read(rs.getString("todos")));
                    m.put("appDeleted", rs.getObject("app_deleted_at") != null);
                    return m;
                })
                .optional()
                .orElseThrow(() -> ApiException.notFound("任务"));

        String appId = String.valueOf(task.get("appId"));
        var events = eventRepo.after(id, 0).stream().map(e -> {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("seq", e.seq());
            ev.put("type", e.type());
            ev.put("payload", e.payload());
            ev.put("createdAt", Db.iso(e.createdAt()));
            return ev;
        }).toList();

        Map<String, Long> eventCounts = new LinkedHashMap<>();
        for (var e : events) {
            String t = String.valueOf(e.get("type"));
            eventCounts.merge(t, 1L, Long::sum);
        }

        List<Map<String, Object>> modelUsages = jdbc.sql("""
                select id, provider, model, input_tokens, output_tokens, cost, created_at
                from model_usage
                where task_id = ?
                order by created_at asc, id asc
                """)
                .param(id)
                .query((rs, i) -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("id", rs.getLong("id"));
                    u.put("provider", rs.getString("provider"));
                    u.put("model", rs.getString("model"));
                    u.put("inputTokens", rs.getInt("input_tokens"));
                    u.put("outputTokens", rs.getInt("output_tokens"));
                    u.put("cost", rs.getBigDecimal("cost"));
                    u.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
                    return u;
                }).list();

        List<Map<String, Object>> messages = jdbc.sql("""
                select id, role, content, steer, element_context, created_at
                from message
                where task_id = ?
                order by created_at asc
                """)
                .param(id)
                .query((rs, i) -> {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("id", rs.getString("id"));
                    msg.put("role", rs.getString("role"));
                    msg.put("content", rs.getString("content"));
                    msg.put("steer", rs.getBoolean("steer"));
                    msg.put("elementContext", Json.read(rs.getString("element_context")));
                    msg.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
                    return msg;
                }).list();

        List<Map<String, Object>> fnLogs = queryFnLogs(null, appId, null, null, null, 30, 0);
        List<Map<String, Object>> sourceCalls = querySourceCalls(null, appId, null, null, null, null, 30, 0);
        List<Map<String, Object>> platformEvents = jdbc.sql("""
                select p.*,
                       coalesce(p.workspace_id, a.workspace_id, t.workspace_id) as effective_workspace_id,
                       coalesce(p.app_id, t.app_id) as effective_app_id,
                       coalesce(a.name, coalesce(p.app_id, t.app_id)) as app_name,
                       coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name
                from platform_event_log p
                left join task t on t.id = p.task_id
                left join app a on a.id = coalesce(p.app_id, t.app_id)
                left join workspace w on w.id = coalesce(p.workspace_id, a.workspace_id, t.workspace_id)
                left join app_user u on u.id = w.owner_user_id
                where p.task_id = ?
                   or (coalesce(p.app_id, t.app_id) = ? and p.category in ('cloud_schema', 'lifecycle')
                       and p.created_at >= (select created_at - interval '10 minutes' from task where id = ?)
                       and p.created_at <= coalesce((select finished_at + interval '2 minutes' from task where id = ?), now()))
                order by p.created_at asc, p.id asc
                limit 120
                """)
                .params(id, appId, id, id)
                .query(OpsLogService::mapPlatformEvent)
                .list();

        Map<String, Object> cloudStatus = jdbc.sql("""
                select app_id, status, schema_name, storage_prefix, enabled_at, updated_at
                from app_cloud where app_id = ?
                """)
                .param(appId)
                .query((rs, i) -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("appId", rs.getString("app_id"));
                    c.put("status", rs.getString("status"));
                    c.put("schemaName", rs.getString("schema_name"));
                    c.put("storagePrefix", rs.getString("storage_prefix"));
                    c.put("enabledAt", Db.iso(Db.ts(rs, "enabled_at")));
                    c.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
                    return c;
                }).optional().orElse(null);
        List<Map<String, Object>> cloudTables = jdbc.sql("""
                select name, columns, policy from app_cloud_table where app_id = ? order by name
                """).param(appId).query((rs, i) -> Map.<String, Object>of(
                "name", rs.getString("name"),
                "columns", Json.read(rs.getString("columns")),
                "policy", Json.read(rs.getString("policy")))).list();

        Map<String, Object> checkpoint = tasks.checkpoint(id).map(cp -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workspaceDir", cp.workspaceDir());
            m.put("agentDir", cp.agentDir());
            m.put("sessionFile", cp.sessionFile());
            m.put("draftApplied", cp.draftApplied());
            return m;
        }).orElse(null);

        Map<String, Object> runnerLog = readLogFile(taskService.logFile(id), Math.max(50, Math.min(2000, tailLines)), null, null, null);

        List<Map<String, Object>> sandboxes = jdbc.sql("""
                select sandbox_id, provider, owner_instance, worker_owner, state, external_ref, image_or_cmd,
                       cpu_limit, memory_limit, allow_net, pid, startup_ms, cpu_percent, memory_used_bytes,
                       memory_limit_bytes, created_at, ready_at, last_active_at, destroyed_at, destroyed_by, destroy_reason
                from sandbox_instance
                where task_id = ?
                order by created_at desc
                """).param(id).query((rs, i) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("sandboxId", rs.getString("sandbox_id"));
            s.put("provider", rs.getString("provider"));
            s.put("ownerInstance", rs.getString("owner_instance"));
            s.put("workerOwner", rs.getString("worker_owner"));
            s.put("state", rs.getString("state"));
            s.put("externalRef", rs.getString("external_ref"));
            s.put("imageOrCmd", rs.getString("image_or_cmd"));
            s.put("cpuLimit", rs.getString("cpu_limit"));
            s.put("memoryLimit", rs.getString("memory_limit"));
            s.put("allowNet", rs.getBoolean("allow_net"));
            s.put("pid", rs.getObject("pid"));
            s.put("startupMs", rs.getObject("startup_ms"));
            s.put("cpuPercent", rs.getObject("cpu_percent"));
            s.put("memoryUsedBytes", rs.getObject("memory_used_bytes"));
            s.put("memoryLimitBytes", rs.getObject("memory_limit_bytes"));
            s.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            s.put("readyAt", Db.iso(Db.ts(rs, "ready_at")));
            s.put("lastActiveAt", Db.iso(Db.ts(rs, "last_active_at")));
            s.put("destroyedAt", Db.iso(Db.ts(rs, "destroyed_at")));
            s.put("destroyedBy", rs.getString("destroyed_by"));
            s.put("destroyReason", rs.getString("destroy_reason"));
            return s;
        }).list();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", task);
        out.put("eventCounts", eventCounts);
        out.put("events", events);
        out.put("modelUsages", modelUsages);
        out.put("messages", messages);
        out.put("checkpoint", checkpoint);
        out.put("runnerLog", runnerLog);
        out.put("sandboxes", sandboxes);
        out.put("fnLogs", fnLogs);
        out.put("sourceCalls", sourceCalls);
        out.put("platformEvents", platformEvents);
        out.put("cloudStatus", cloudStatus);
        out.put("cloudTables", cloudTables);
        return out;
    }

    public Map<String, Object> taskEventsAfter(String taskId, int afterSeq) {
        var row = tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("任务"));
        var batch = eventRepo.after(taskId, Math.max(0, afterSeq)).stream().map(e -> {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("seq", e.seq());
            ev.put("type", e.type());
            ev.put("payload", e.payload());
            ev.put("createdAt", Db.iso(e.createdAt()));
            return ev;
        }).toList();
        int lastSeq = batch.isEmpty() ? afterSeq : ((Number) batch.get(batch.size() - 1).get("seq")).intValue();
        Map<String, Object> taskState = new LinkedHashMap<>();
        taskState.put("id", row.id());
        taskState.put("status", row.status());
        taskState.put("phaseLabel", row.phaseLabel());
        taskState.put("progress", row.progress());
        taskState.put("errorCode", row.errorCode());
        taskState.put("errorMessage", row.errorMessage());
        taskState.put("tokenUsed", row.tokenUsed());
        taskState.put("startedAt", Db.iso(row.startedAt()));
        taskState.put("finishedAt", Db.iso(row.finishedAt()));
        taskState.put("terminal", row.terminal());
        return Map.of("task", taskState, "events", batch, "lastSeq", lastSeq);
    }

    public Map<String, Object> runnerLog(String taskId, Integer tail, Integer offset, String q) {
        return runnerLog(taskId, tail, offset, q, null);
    }

    public Map<String, Object> runnerLog(String taskId, Integer tail, Integer offset, String q, String level) {
        tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("任务"));
        int maxTail = tail == null ? 800 : Math.max(1, Math.min(5000, tail));
        return readLogFile(taskService.logFile(taskId), maxTail, offset, clean(q), clean(level));
    }

    public Path runnerLogFilePath(String taskId) {
        tasks.findById(taskId).orElseThrow(() -> ApiException.notFound("任务"));
        Path p = taskService.logFile(taskId);
        if (!Files.isRegularFile(p)) throw ApiException.notFound("沙箱日志文件");
        return p;
    }

    public Map<String, Object> listFunctionLogs(String qParam, String appIdParam, Boolean okParam,
                                                String rangeParam, int limitParam, int offsetParam) {
        return listFunctionLogs(qParam, appIdParam, null, okParam, rangeParam, limitParam, offsetParam);
    }

    public Map<String, Object> listFunctionLogs(String qParam, String appIdParam, String scopeWs, Boolean okParam,
                                                String rangeParam, int limitParam, int offsetParam) {
        String q = clean(qParam);
        String appId = clean(appIdParam);
        String ws = clean(scopeWs);
        String range = normalizeRange(rangeParam, "7d");
        OffsetDateTime sinceTs = sinceTimestamp(range);
        int limit = Math.max(1, Math.min(100, limitParam));
        int offset = Math.max(0, offsetParam);

        WhereClause w = fnLogWhere(q, appId, ws, okParam, sinceTs);
        String fromSql = """
                from app_fn_log l
                left join app a on a.id = l.app_id
                left join workspace w on w.id = a.workspace_id
                left join app_user u on u.id = w.owner_user_id
                """;
        long total = jdbc.sql("select count(*) " + fromSql + w.sql()).params(w.params()).query(Long.class).single();
        long errors = jdbc.sql("select count(*) " + fromSql + w.sql() + " and l.ok = false ").params(w.params()).query(Long.class).single();
        List<Map<String, Object>> items = queryFnLogs(q, appId, ws, okParam, sinceTs, limit, offset);
        return Map.of("range", range, "total", total, "errorCount", errors, "items", items, "limit", limit, "offset", offset);
    }

    public Map<String, Object> listSourceCalls(String qParam, String appIdParam, Boolean okParam,
                                               String originParam, String rangeParam, int limitParam, int offsetParam) {
        return listSourceCalls(qParam, appIdParam, null, okParam, originParam, rangeParam, limitParam, offsetParam);
    }

    public Map<String, Object> listSourceCalls(String qParam, String appIdParam, String scopeWs, Boolean okParam,
                                               String originParam, String rangeParam, int limitParam, int offsetParam) {
        String q = clean(qParam);
        String appId = clean(appIdParam);
        String ws = clean(scopeWs);
        String origin = clean(originParam);
        String range = normalizeRange(rangeParam, "7d");
        OffsetDateTime sinceTs = sinceTimestamp(range);
        int limit = Math.max(1, Math.min(100, limitParam));
        int offset = Math.max(0, offsetParam);

        WhereClause w = sourceCallWhere(q, appId, ws, okParam, origin, sinceTs);
        String fromSql = """
                from data_source_call c
                left join data_source ds on ds.id = c.source_id
                left join app a on a.id = c.app_id
                """;
        long total = jdbc.sql("select count(*) " + fromSql + w.sql()).params(w.params()).query(Long.class).single();
        long errors = jdbc.sql("select count(*) " + fromSql + w.sql() + " and c.ok = false ").params(w.params()).query(Long.class).single();
        List<Map<String, Object>> items = querySourceCalls(q, appId, ws, okParam, origin, sinceTs, limit, offset);
        return Map.of("range", range, "total", total, "errorCount", errors, "items", items, "limit", limit, "offset", offset);
    }

    public Map<String, Object> listPlatformEvents(String categoryParam, String levelParam, String scopeWs,
                                                  String appIdParam, String taskIdParam, Boolean okParam,
                                                  String qParam, String rangeParam, int limitParam, int offsetParam) {
        String category = clean(categoryParam);
        String level = clean(levelParam);
        String ws = clean(scopeWs);
        String appId = clean(appIdParam);
        String taskId = clean(taskIdParam);
        String q = clean(qParam);
        String range = normalizeRange(rangeParam, "7d");
        OffsetDateTime sinceTs = sinceTimestamp(range);
        int limit = Math.max(1, Math.min(100, limitParam));
        int offset = Math.max(0, offsetParam);

        StringBuilder baseWhere = new StringBuilder(" where 1=1 ");
        List<Object> baseParams = new ArrayList<>();
        if (sinceTs != null) { baseWhere.append(" and p.created_at >= ? "); baseParams.add(sinceTs); }
        if (ws != null) { baseWhere.append(" and coalesce(p.workspace_id, a.workspace_id, t.workspace_id) = ? "); baseParams.add(ws); }
        if (appId != null) { baseWhere.append(" and coalesce(p.app_id, t.app_id) = ? "); baseParams.add(appId); }
        if (taskId != null) { baseWhere.append(" and p.task_id = ? "); baseParams.add(taskId); }
        if (okParam != null) { baseWhere.append(" and p.ok = ? "); baseParams.add(okParam); }
        if (level != null && !"ALL".equalsIgnoreCase(level)) {
            baseWhere.append(" and p.level = ? ");
            baseParams.add(level.toUpperCase(Locale.ROOT));
        }
        if (q != null) {
            String pat = "%" + escapeLike(q) + "%";
            baseWhere.append("""
                     and (
                       p.action ilike ? escape '\\'
                       or p.summary ilike ? escape '\\'
                       or coalesce(p.detail, '') ilike ? escape '\\'
                       or coalesce(p.app_id, t.app_id, '') ilike ? escape '\\'
                       or coalesce(a.name, '') ilike ? escape '\\'
                       or coalesce(p.task_id, '') ilike ? escape '\\'
                       or coalesce(p.workspace_id, a.workspace_id, t.workspace_id, '') ilike ? escape '\\'
                     )
                    """);
            for (int i = 0; i < 7; i++) baseParams.add(pat);
        }

        String fromSql = """
                from platform_event_log p
                left join task t on t.id = p.task_id
                left join app a on a.id = coalesce(p.app_id, t.app_id)
                left join workspace w on w.id = coalesce(p.workspace_id, a.workspace_id, t.workspace_id)
                left join app_user u on u.id = w.owner_user_id
                """;

        List<Map<String, Object>> byCategory = jdbc.sql("""
                select p.category, count(*) as cnt, count(*) filter (where p.ok = false) as err_cnt
                """ + fromSql + baseWhere + " group by p.category order by cnt desc")
                .params(baseParams)
                .query((rs, i) -> Map.<String, Object>of(
                        "category", rs.getString("category"),
                        "count", rs.getLong("cnt"),
                        "errors", rs.getLong("err_cnt")))
                .list();

        StringBuilder fullWhere = new StringBuilder(baseWhere);
        List<Object> fullParams = new ArrayList<>(baseParams);
        if (category != null && !"ALL".equalsIgnoreCase(category)) {
            fullWhere.append(" and p.category = ? ");
            fullParams.add(category);
        }

        long total = jdbc.sql("select count(*) " + fromSql + fullWhere).params(fullParams).query(Long.class).single();
        long errorCount = jdbc.sql("select count(*) " + fromSql + fullWhere + " and p.ok = false ").params(fullParams).query(Long.class).single();

        List<Object> pageParams = new ArrayList<>(fullParams);
        pageParams.add(limit);
        pageParams.add(offset);
        List<Map<String, Object>> items = jdbc.sql("""
                select p.*,
                       coalesce(p.workspace_id, a.workspace_id, t.workspace_id) as effective_workspace_id,
                       coalesce(p.app_id, t.app_id) as effective_app_id,
                       coalesce(a.name, coalesce(p.app_id, t.app_id)) as app_name,
                       coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name
                """ + fromSql + fullWhere + """
                order by p.created_at desc, p.id desc
                limit ? offset ?
                """)
                .params(pageParams)
                .query(OpsLogService::mapPlatformEvent)
                .list();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", range);
        out.put("total", total);
        out.put("errorCount", errorCount);
        out.put("byCategory", byCategory);
        out.put("items", items);
        out.put("limit", limit);
        out.put("offset", offset);
        return out;
    }

    public Map<String, Object> listCloudSchemas(String scopeWs, String qParam, Boolean okParam,
                                                String rangeParam, int limitParam, int offsetParam) {
        Map<String, Object> events = listPlatformEvents("cloud_schema", null, scopeWs, null, null, okParam, qParam, rangeParam, limitParam, offsetParam);

        String ws = clean(scopeWs);
        String q = clean(qParam);
        StringBuilder regWhere = new StringBuilder(" where 1=1 ");
        List<Object> regParams = new ArrayList<>();
        if (ws != null) { regWhere.append(" and a.workspace_id = ? "); regParams.add(ws); }
        if (q != null) {
            String pat = "%" + escapeLike(q) + "%";
            regWhere.append(" and (c.app_id ilike ? escape '\\' or c.schema_name ilike ? escape '\\' or coalesce(a.name, '') ilike ? escape '\\') ");
            regParams.add(pat); regParams.add(pat); regParams.add(pat);
        }
        List<Map<String, Object>> schemas = jdbc.sql("""
                select c.app_id, c.status, c.schema_name, c.storage_prefix, c.enabled_at, c.updated_at,
                       coalesce(a.name, c.app_id) as app_name, a.workspace_id,
                       coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name,
                       (select count(*) from app_cloud_table t where t.app_id = c.app_id) as table_count,
                       (select coalesce(jsonb_agg(t.name order by t.name), '[]'::jsonb) from app_cloud_table t where t.app_id = c.app_id) as table_names
                from app_cloud c
                left join app a on a.id = c.app_id
                left join workspace w on w.id = a.workspace_id
                left join app_user u on u.id = w.owner_user_id
                """ + regWhere + """
                order by c.updated_at desc
                limit 50
                """)
                .params(regParams)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("appId", rs.getString("app_id"));
                    m.put("appName", rs.getString("app_name"));
                    m.put("workspaceId", rs.getString("workspace_id"));
                    m.put("ownerName", rs.getString("owner_name"));
                    m.put("status", rs.getString("status"));
                    m.put("schemaName", rs.getString("schema_name"));
                    m.put("tableCount", rs.getLong("table_count"));
                    m.put("tableNames", Json.read(rs.getString("table_names")));
                    m.put("enabledAt", Db.iso(Db.ts(rs, "enabled_at")));
                    m.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
                    return m;
                }).list();

        Map<String, Object> out = new LinkedHashMap<>(events);
        out.put("schemas", schemas);
        return out;
    }

    public Map<String, Object> listSandboxLogs(String scopeWs, String rangeParam, String stateParam, String providerParam,
                                             Boolean onlyErrors, String qParam, int limitParam, int offsetParam) {
        String range = normalizeRange(rangeParam, "7d");
        OffsetDateTime sinceTs = sinceTimestamp(range);
        String ws = clean(scopeWs);
        String state = clean(stateParam);
        String provider = clean(providerParam);
        String q = clean(qParam);
        int limit = Math.max(1, Math.min(100, limitParam));
        int offset = Math.max(0, offsetParam);

        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> params = new ArrayList<>();
        if (sinceTs != null) { where.append(" and s.created_at >= ? "); params.add(sinceTs); }
        if (ws != null) { where.append(" and s.workspace_id = ? "); params.add(ws); }
        if (state != null && !"ALL".equalsIgnoreCase(state)) {
            where.append(" and s.state = ? ");
            params.add(state.toLowerCase(Locale.ROOT));
        }
        if (provider != null && !"ALL".equalsIgnoreCase(provider)) {
            where.append(" and s.provider = ? ");
            params.add(provider.toLowerCase(Locale.ROOT));
        }
        if (Boolean.TRUE.equals(onlyErrors)) {
            where.append(" and (s.state = 'failed' or s.destroy_reason ilike '%kill%' or s.destroy_reason ilike '%destroy%' or s.destroy_reason ilike '%reap%') ");
        }
        if (q != null) {
            String pat = "%" + escapeLike(q) + "%";
            where.append("""
                     and (
                       s.sandbox_id ilike ? escape '\\'
                       or coalesce(s.task_id, '') ilike ? escape '\\'
                       or coalesce(s.external_ref, '') ilike ? escape '\\'
                       or coalesce(s.workspace_id, '') ilike ? escape '\\'
                       or coalesce(a.name, '') ilike ? escape '\\'
                       or coalesce(s.owner_instance, '') ilike ? escape '\\'
                       or coalesce(s.destroy_reason, '') ilike ? escape '\\'
                       or coalesce(u.real_name, '') ilike ? escape '\\'
                     )
                    """);
            for (int i = 0; i < 8; i++) params.add(pat);
        }

        String fromSql = """
                from sandbox_instance s
                left join app a on a.id = s.app_id
                left join workspace w on w.id = s.workspace_id
                left join app_user u on u.id = w.owner_user_id
                left join task t on t.id = s.task_id
                """;

        Map<String, Object> summary = jdbc.sql("""
                select
                  count(*) as total,
                  count(*) filter (where s.state in ('creating', 'active')) as active,
                  count(*) filter (where s.state = 'failed') as failed,
                  count(*) filter (where s.state = 'destroyed') as destroyed,
                  coalesce(round(avg(s.startup_ms))::bigint, 0) as avg_startup_ms,
                  coalesce(round(percentile_cont(0.95) within group (order by s.startup_ms))::bigint, 0) as p95_startup_ms
                """ + fromSql + where)
                .params(params)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("total", rs.getLong("total"));
                    m.put("active", rs.getLong("active"));
                    m.put("failed", rs.getLong("failed"));
                    m.put("destroyed", rs.getLong("destroyed"));
                    m.put("avgStartupMs", rs.getLong("avg_startup_ms"));
                    m.put("p95StartupMs", rs.getLong("p95_startup_ms"));
                    return m;
                }).single();

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(limit);
        pageParams.add(offset);

        List<Map<String, Object>> items = jdbc.sql("""
                select s.*,
                       coalesce(a.name, s.app_id) as app_name,
                       coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name,
                       coalesce(u.login_name, '') as owner_login_name,
                       t.status as task_status,
                       extract(epoch from (coalesce(s.destroyed_at, now()) - s.created_at)) as duration_sec
                """ + fromSql + where + """
                order by s.created_at desc
                limit ? offset ?
                """)
                .params(pageParams)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sandboxId", rs.getString("sandbox_id"));
                    m.put("provider", rs.getString("provider"));
                    m.put("state", rs.getString("state"));
                    m.put("taskId", rs.getString("task_id"));
                    m.put("taskStatus", rs.getString("task_status"));
                    m.put("appId", rs.getString("app_id"));
                    m.put("appName", rs.getString("app_name"));
                    m.put("workspaceId", rs.getString("workspace_id"));
                    m.put("ownerName", rs.getString("owner_name"));
                    m.put("ownerLoginName", rs.getString("owner_login_name"));
                    m.put("ownerInstance", rs.getString("owner_instance"));
                    m.put("workerOwner", rs.getString("worker_owner"));
                    m.put("externalRef", rs.getString("external_ref"));
                    m.put("imageOrCmd", rs.getString("image_or_cmd"));
                    m.put("allowNet", rs.getBoolean("allow_net"));
                    m.put("pid", rs.getObject("pid"));
                    m.put("startupMs", rs.getObject("startup_ms"));
                    m.put("cpuPercent", rs.getObject("cpu_percent"));
                    m.put("memoryUsedBytes", rs.getObject("memory_used_bytes"));
                    m.put("memoryLimitBytes", rs.getObject("memory_limit_bytes"));
                    m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
                    m.put("readyAt", Db.iso(Db.ts(rs, "ready_at")));
                    m.put("destroyedAt", Db.iso(Db.ts(rs, "destroyed_at")));
                    m.put("destroyedBy", rs.getString("destroyed_by"));
                    m.put("destroyReason", rs.getString("destroy_reason"));
                    m.put("durationSeconds", Math.round(Math.max(0.0, rs.getDouble("duration_sec")) * 10.0) / 10.0);
                    return m;
                }).list();

        Map<String, Object> events = listPlatformEvents("sandbox", "ALL", ws, null, null,
                Boolean.TRUE.equals(onlyErrors) ? Boolean.FALSE : null, q, range, 30, 0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("range", range);
        out.put("summary", summary);
        out.put("total", summary.get("total"));
        out.put("items", items);
        out.put("events", events.getOrDefault("items", List.of()));
        out.put("limit", limit);
        out.put("offset", offset);
        return out;
    }

    public Map<String, Object> runtimeOverview(String targetParam, int tailParam, String qParam) {
        return runtimeOverview(targetParam, tailParam, qParam, null);
    }

    public Map<String, Object> runtimeOverview(String targetParam, int tailParam, String qParam, String levelParam) {
        String target = (targetParam == null || targetParam.isBlank()) ? "api" : targetParam.trim().toLowerCase(Locale.ROOT);
        String filename = switch (target) {
            case "api" -> "api.log";
            case "site" -> "site.log";
            case "web-build" -> "web-build.log";
            default -> throw ApiException.validation("target 只能是 api、site 或 web-build");
        };
        Path logPath = props.runtimePath().resolve("logs").resolve(filename);
        int tail = Math.max(20, Math.min(3000, tailParam));
        Map<String, Object> fileView = readLogFile(logPath, tail, null, clean(qParam), clean(levelParam));

        List<Map<String, Object>> rateLimitHits = jdbc.sql("""
                select bucket, outcome, code, window_seconds, max_requests, current_count, created_at
                from rate_limit_event
                order by created_at desc
                limit 80
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", rs.getString("bucket"));
            m.put("outcome", rs.getString("outcome"));
            m.put("code", rs.getString("code"));
            m.put("windowSeconds", rs.getInt("window_seconds"));
            m.put("maxRequests", rs.getInt("max_requests"));
            m.put("currentCount", rs.getLong("current_count"));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).list();

        List<Map<String, Object>> auditLogs = jdbc.sql("""
                select id, actor, action, target_type, target_id, detail, created_at
                from admin_audit_log order by created_at desc limit 50
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("actor", rs.getString("actor"));
            m.put("action", rs.getString("action"));
            m.put("targetType", rs.getString("target_type"));
            m.put("targetId", rs.getString("target_id"));
            m.put("detail", Json.read(rs.getString("detail")));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).list();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", target);
        out.put("sandboxProvider", props.sandbox().provider());
        out.put("runtimeDir", props.runtimePath().toString());
        out.put("prometheusEndpoint", "/api/meta/metrics/prometheus");
        out.put("metrics", runtimeMetrics == null ? Map.of() : runtimeMetrics.snapshot());
        out.put("clusterNodes", clusterSandbox == null ? List.of() : clusterSandbox.listNodes());
        out.put("file", fileView);
        out.put("rateLimitHits", rateLimitHits);
        out.put("auditLogs", auditLogs);
        return out;
    }

    private static Map<String, Object> mapPlatformEvent(ResultSet rs, int idx) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("category", rs.getString("category"));
        m.put("level", rs.getString("level"));
        m.put("action", rs.getString("action"));
        m.put("workspaceId", rs.getString("effective_workspace_id"));
        m.put("userId", rs.getString("user_id"));
        m.put("appId", rs.getString("effective_app_id"));
        m.put("appName", rs.getString("app_name"));
        m.put("ownerName", rs.getString("owner_name"));
        m.put("taskId", rs.getString("task_id"));
        m.put("ok", rs.getBoolean("ok"));
        m.put("statusCode", rs.getObject("status_code"));
        m.put("durationMs", rs.getObject("duration_ms"));
        m.put("summary", LogRedactor.redact(rs.getString("summary")));
        m.put("detail", LogRedactor.redact(rs.getString("detail")));
        m.put("meta", Json.read(LogRedactor.redact(rs.getString("meta"))));
        m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
        return m;
    }

    // ================= 内部查询与工具方法 =================

    private record WhereClause(String sql, List<Object> params) {}

    private static WhereClause fnLogWhere(String q, String appId, String scopeWs, Boolean ok, OffsetDateTime sinceTs) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> params = new ArrayList<>();
        if (sinceTs != null) { where.append(" and l.created_at >= ? "); params.add(sinceTs); }
        if (scopeWs != null) { where.append(" and a.workspace_id = ? "); params.add(scopeWs); }
        if (appId != null) { where.append(" and l.app_id = ? "); params.add(appId); }
        if (ok != null) { where.append(" and l.ok = ? "); params.add(ok); }
        if (q != null) {
            String pat = "%" + escapeLike(q) + "%";
            where.append("""
                     and (
                       l.app_id ilike ? escape '\\'
                       or coalesce(a.name, '') ilike ? escape '\\'
                       or l.name ilike ? escape '\\'
                       or coalesce(l.error, '') ilike ? escape '\\'
                       or coalesce(l.logs, '') ilike ? escape '\\'
                       or coalesce(l.caller, '') ilike ? escape '\\'
                     )
                    """);
            for (int i = 0; i < 6; i++) params.add(pat);
        }
        return new WhereClause(where.toString(), params);
    }

    private static WhereClause sourceCallWhere(String q, String appId, String scopeWs, Boolean ok, String origin, OffsetDateTime sinceTs) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> params = new ArrayList<>();
        if (sinceTs != null) { where.append(" and c.created_at >= ? "); params.add(sinceTs); }
        if (scopeWs != null) { where.append(" and coalesce(ds.workspace_id, a.workspace_id) = ? "); params.add(scopeWs); }
        if (appId != null) { where.append(" and c.app_id = ? "); params.add(appId); }
        if (ok != null) { where.append(" and c.ok = ? "); params.add(ok); }
        if (origin != null && !"ALL".equalsIgnoreCase(origin)) { where.append(" and c.origin = ? "); params.add(origin); }
        if (q != null) {
            String pat = "%" + escapeLike(q) + "%";
            where.append("""
                     and (
                       coalesce(c.app_id, '') ilike ? escape '\\'
                       or coalesce(a.name, '') ilike ? escape '\\'
                       or coalesce(ds.key, '') ilike ? escape '\\'
                       or coalesce(ds.name, '') ilike ? escape '\\'
                       or coalesce(c.error, '') ilike ? escape '\\'
                     )
                    """);
            for (int i = 0; i < 5; i++) params.add(pat);
        }
        return new WhereClause(where.toString(), params);
    }

    private List<Map<String, Object>> queryFnLogs(String q, String appId, String scopeWs, Boolean ok, OffsetDateTime sinceTs, int limit, int offset) {
        WhereClause w = fnLogWhere(q, appId, scopeWs, ok, sinceTs);
        List<Object> params = new ArrayList<>(w.params());
        params.add(limit);
        params.add(offset);
        return jdbc.sql("""
                select l.id, l.app_id, coalesce(a.name, l.app_id) as app_name, a.workspace_id,
                       coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name,
                       l.name, l.version_id, l.ok, l.duration_ms, l.error, l.logs, l.caller, l.created_at
                from app_fn_log l
                left join app a on a.id = l.app_id
                left join workspace w on w.id = a.workspace_id
                left join app_user u on u.id = w.owner_user_id
                """ + w.sql() + """
                order by l.created_at desc, l.id desc
                limit ? offset ?
                """)
                .params(params)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("appId", rs.getString("app_id"));
                    m.put("appName", rs.getString("app_name"));
                    m.put("workspaceId", rs.getString("workspace_id"));
                    m.put("ownerName", rs.getString("owner_name"));
                    m.put("name", rs.getString("name"));
                    m.put("versionId", rs.getString("version_id"));
                    m.put("ok", rs.getBoolean("ok"));
                    m.put("durationMs", rs.getInt("duration_ms"));
                    m.put("error", LogRedactor.redact(rs.getString("error")));
                    m.put("logs", LogRedactor.redact(rs.getString("logs")));
                    m.put("caller", rs.getString("caller"));
                    m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
                    return m;
                }).list();
    }

    private List<Map<String, Object>> querySourceCalls(String q, String appId, String scopeWs, Boolean ok, String origin, OffsetDateTime sinceTs, int limit, int offset) {
        WhereClause w = sourceCallWhere(q, appId, scopeWs, ok, origin, sinceTs);
        List<Object> params = new ArrayList<>(w.params());
        params.add(limit);
        params.add(offset);
        return jdbc.sql("""
                select c.id, c.source_id, coalesce(ds.key, '') as source_key, coalesce(ds.name, c.source_id) as source_name,
                       coalesce(ds.method, '') as method, c.app_id, coalesce(a.name, coalesce(c.app_id, '')) as app_name,
                       c.origin, c.status, c.ok, c.duration_ms, c.error, c.created_at
                from data_source_call c
                left join data_source ds on ds.id = c.source_id
                left join app a on a.id = c.app_id
                """ + w.sql() + """
                order by c.created_at desc, c.id desc
                limit ? offset ?
                """)
                .params(params)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("sourceId", rs.getString("source_id"));
                    m.put("sourceKey", rs.getString("source_key"));
                    m.put("sourceName", rs.getString("source_name"));
                    m.put("method", rs.getString("method"));
                    m.put("appId", rs.getString("app_id"));
                    m.put("appName", rs.getString("app_name"));
                    m.put("origin", rs.getString("origin"));
                    m.put("status", rs.getObject("status"));
                    m.put("ok", rs.getBoolean("ok"));
                    m.put("durationMs", rs.getInt("duration_ms"));
                    m.put("error", LogRedactor.redact(rs.getString("error")));
                    m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
                    return m;
                }).list();
    }

    private Map<String, Object> mapTaskSummary(ResultSet rs, String currentWs) throws SQLException {
        String id = rs.getString("id");
        String status = rs.getString("status");
        Instant createdAt = Db.ts(rs, "created_at");
        Instant startedAt = Db.ts(rs, "started_at");
        Instant finishedAt = Db.ts(rs, "finished_at");
        long durationMs = 0L;
        if (startedAt != null && finishedAt != null) {
            durationMs = Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
        } else if (createdAt != null && finishedAt != null) {
            durationMs = Math.max(0L, finishedAt.toEpochMilli() - createdAt.toEpochMilli());
        } else if (startedAt != null && ACTIVE_STATUSES.contains(status)) {
            durationMs = Math.max(0L, Instant.now().toEpochMilli() - startedAt.toEpochMilli());
        }
        JsonNode input = Json.read(rs.getString("input"));
        String prompt = input == null ? "" : input.path("prompt").asText(input.path("content").asText(""));
        if (prompt.isBlank()) prompt = Objects.toString(rs.getString("app_prompt"), "");
        String promptPreview = prompt.length() > 160 ? prompt.substring(0, 160) + "…" : prompt;

        Path logPath = taskService.logFile(id);
        boolean hasLogFile = Files.isRegularFile(logPath);
        long logFileBytes = 0L;
        if (hasLogFile) {
            try { logFileBytes = Files.size(logPath); } catch (IOException ignored) { }
        }

        String ws = rs.getString("workspace_id");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("workspaceId", ws);
        m.put("isMine", Objects.equals(ws, currentWs));
        m.put("appId", rs.getString("app_id"));
        m.put("appName", rs.getString("app_name"));
        m.put("appType", rs.getString("app_type"));
        m.put("projectKind", rs.getString("project_kind"));
        m.put("model", rs.getString("app_model"));
        m.put("mode", rs.getString("app_mode"));
        m.put("ownerUserId", rs.getString("owner_user_id"));
        m.put("ownerLoginName", rs.getString("owner_login_name"));
        m.put("ownerName", rs.getString("owner_name"));
        m.put("type", rs.getString("type"));
        m.put("status", status);
        m.put("active", ACTIVE_STATUSES.contains(status));
        m.put("phaseLabel", rs.getString("phase_label"));
        m.put("progress", rs.getInt("progress"));
        m.put("attempt", rs.getInt("attempt"));
        m.put("errorCode", rs.getString("error_code"));
        m.put("errorMessage", LogRedactor.redact(rs.getString("error_message")));
        m.put("leaseOwner", rs.getString("lease_owner"));
        m.put("tokenUsed", rs.getLong("token_used"));
        m.put("durationMs", durationMs);
        m.put("eventCount", rs.getLong("event_count"));
        m.put("logEventCount", rs.getLong("log_event_count"));
        m.put("toolErrorCount", rs.getLong("tool_error_count"));
        m.put("hasLogFile", hasLogFile);
        m.put("logFileBytes", logFileBytes);
        m.put("promptPreview", promptPreview);
        m.put("createdAt", Db.iso(createdAt));
        m.put("startedAt", Db.iso(startedAt));
        m.put("finishedAt", Db.iso(finishedAt));
        return m;
    }

    private List<Map<String, Object>> actorsDirectory() {
        List<Map<String, Object>> users = jdbc.sql("""
                select u.id as user_id, u.login_name, coalesce(nullif(u.real_name, ''), u.login_name) as real_name,
                  coalesce(sum(tc.tasks), 0) as tasks,
                  coalesce(sum(tc.failed), 0) as failed
                from app_user u
                join workspace w on w.owner_user_id = u.id
                left join (
                  select workspace_id, count(*) as tasks, count(*) filter (where status = 'FAILED') as failed
                  from task group by 1
                ) tc on tc.workspace_id = w.id
                group by u.id, u.login_name, u.real_name
                having coalesce(sum(tc.tasks), 0) > 0
                order by tasks desc
                limit 50
                """).query((rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "user");
            r.put("value", "user:" + rs.getString("user_id"));
            r.put("userId", rs.getString("user_id"));
            r.put("workspaceId", null);
            r.put("loginName", rs.getString("login_name"));
            r.put("realName", rs.getString("real_name"));
            r.put("tasks", rs.getLong("tasks"));
            r.put("failed", rs.getLong("failed"));
            return r;
        }).list();

        List<Map<String, Object>> spaces = jdbc.sql("""
                select w.id as workspace_id,
                  w.owner_user_id,
                  coalesce(nullif(u.real_name, ''), u.login_name, '') as owner_name,
                  coalesce(u.login_name, '') as login_name,
                  coalesce(tc.tasks, 0) as tasks,
                  coalesce(tc.failed, 0) as failed
                from workspace w
                left join app_user u on u.id = w.owner_user_id
                left join (
                  select workspace_id, count(*) as tasks, count(*) filter (where status = 'FAILED') as failed
                  from task group by 1
                ) tc on tc.workspace_id = w.id
                where coalesce(tc.tasks, 0) > 0
                order by tasks desc
                limit 80
                """).query((rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "workspace");
            r.put("value", "ws:" + rs.getString("workspace_id"));
            r.put("userId", rs.getString("owner_user_id"));
            r.put("workspaceId", rs.getString("workspace_id"));
            r.put("loginName", rs.getString("login_name"));
            r.put("realName", rs.getString("owner_name"));
            r.put("tasks", rs.getLong("tasks"));
            r.put("failed", rs.getLong("failed"));
            return r;
        }).list();

        List<Map<String, Object>> out = new ArrayList<>(users.size() + spaces.size());
        out.addAll(users);
        out.addAll(spaces);
        return out;
    }

    /** 读取日志文件（支持尾部 N 行、行偏移分页、级别筛选及关键字检索），流式遍历避免大文件 OOM。 */
    static Map<String, Object> readLogFile(Path path, int tailLines, Integer offset, String query) {
        return readLogFile(path, tailLines, offset, query, null);
    }

    static Map<String, Object> readLogFile(Path path, int tailLines, Integer offset, String query, String levelFilter) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileName", path.getFileName().toString());
        if (!Files.isRegularFile(path)) {
            out.put("exists", false);
            out.put("sizeBytes", 0L);
            out.put("updatedAt", null);
            out.put("totalLines", 0);
            out.put("matchedLines", 0);
            out.put("errorLines", 0);
            out.put("warnLines", 0);
            out.put("truncated", false);
            out.put("lines", List.of());
            return out;
        }
        try {
            long size = Files.size(path);
            Instant mtime = Files.getLastModifiedTime(path).toInstant();
            String qLower = query == null ? null : query.toLowerCase(Locale.ROOT);
            String lvl = levelFilter == null || levelFilter.isBlank() || "ALL".equalsIgnoreCase(levelFilter)
                    ? null : levelFilter.trim().toUpperCase(Locale.ROOT);
            int totalLines = 0;
            int matchedLines = 0;
            int errorLines = 0;
            int warnLines = 0;

            List<Map<String, Object>> lines;
            boolean truncated = false;

            if (offset != null && offset >= 0) {
                lines = new ArrayList<>(Math.min(tailLines, 500));
                try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        totalLines++;
                        String safeLine = LogRedactor.redact(line);
                        String lineLvl = classifyLineLevel(safeLine);
                        if ("ERROR".equals(lineLvl)) errorLines++;
                        else if ("WARN".equals(lineLvl)) warnLines++;
                        if (qLower != null && !safeLine.toLowerCase(Locale.ROOT).contains(qLower)) continue;
                        if (lvl != null && !lvl.equals(lineLvl)) continue;
                        int matchIdx = matchedLines++;
                        if (matchIdx >= offset && lines.size() < tailLines) {
                            lines.add(Map.of("no", totalLines, "level", lineLvl, "text", safeLine));
                        }
                    }
                }
                truncated = matchedLines > offset + lines.size();
            } else {
                ArrayDeque<Map<String, Object>> ring = new ArrayDeque<>(tailLines + 1);
                try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        totalLines++;
                        String safeLine = LogRedactor.redact(line);
                        String lineLvl = classifyLineLevel(safeLine);
                        if ("ERROR".equals(lineLvl)) errorLines++;
                        else if ("WARN".equals(lineLvl)) warnLines++;
                        if (qLower != null && !safeLine.toLowerCase(Locale.ROOT).contains(qLower)) continue;
                        if (lvl != null && !lvl.equals(lineLvl)) continue;
                        matchedLines++;
                        if (ring.size() >= tailLines) {
                            ring.removeFirst();
                            truncated = true;
                        }
                        ring.addLast(Map.of("no", totalLines, "level", lineLvl, "text", safeLine));
                    }
                }
                lines = new ArrayList<>(ring);
            }

            out.put("exists", true);
            out.put("sizeBytes", size);
            out.put("updatedAt", Db.iso(mtime));
            out.put("totalLines", totalLines);
            out.put("matchedLines", matchedLines);
            out.put("errorLines", errorLines);
            out.put("warnLines", warnLines);
            out.put("truncated", truncated);
            out.put("lines", lines);
            return out;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "读取日志文件失败：" + e.getMessage());
        }
    }

    public void streamRedactedLogFile(Path path, OutputStream out) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                writer.write(LogRedactor.redact(line));
                writer.write('\n');
            }
            writer.flush();
        }
    }

    public void recordOpsAudit(String actor, String action, String targetType, String targetId, Map<String, Object> detail) {
        if (jdbc == null) return;
        try {
            jdbc.sql("""
                    insert into admin_audit_log (id, actor, action, target_type, target_id, detail)
                    values (?, ?, ?, ?, ?, ?::jsonb)
                    """)
                    .params(
                            Ids.newId("aud"),
                            (actor == null || actor.isBlank()) ? "system" : actor,
                            action,
                            targetType,
                            targetId,
                            Json.write(detail == null ? Map.of() : detail))
                    .update();
        } catch (Exception ignored) {
            // 审计落库失败不阻断排障读取，生产库中正常留痕
        }
    }

    private static String classifyLineLevel(String line) {
        String low = line.toLowerCase(Locale.ROOT);
        if (low.contains(" error ") || low.startsWith("error") || low.contains("[error]")
                || low.contains("exception") || low.contains("fatal") || low.contains("uncaught")
                || low.contains("failed:") || low.contains(" 500 ")) {
            return "ERROR";
        }
        if (low.contains(" warn ") || low.startsWith("warn") || low.contains("[warn]")
                || low.contains("timeout") || low.contains("retry")) {
            return "WARN";
        }
        return "INFO";
    }

    private static String normalizeRange(String raw, String def) {
        if (raw == null || raw.isBlank()) return def;
        String r = raw.trim().toLowerCase(Locale.ROOT);
        return switch (r) {
            case "1h", "24h", "7d", "30d", "all" -> r;
            default -> throw ApiException.validation("range 只能是 1h、24h、7d、30d 或 all");
        };
    }

    private static OffsetDateTime sinceTimestamp(String range) {
        OffsetDateTime now = OffsetDateTime.now(ZONE);
        return switch (range) {
            case "1h" -> now.minusHours(1);
            case "24h" -> now.minusHours(24);
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            default -> null;
        };
    }

    private static String clean(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
