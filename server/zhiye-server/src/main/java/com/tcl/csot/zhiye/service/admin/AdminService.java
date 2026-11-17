package com.tcl.csot.zhiye.service.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.config.AuthProperties;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.filter.SsoAuthFilter;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.TaskRepository;
import com.tcl.csot.zhiye.service.app.AppService;
import com.tcl.csot.zhiye.service.app.Views;
import com.tcl.csot.zhiye.service.task.TaskService;
import com.tcl.csot.zhiye.service.workspace.QuotaService;
import com.tcl.csot.zhiye.storage.ObjectStore;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 平台管理侧治理与运维服务：
 * 1. 工作空间配额覆盖与重置（应用上限、每日任务上限、并发上限）
 * 2. 应用与已发布站点治理（强行下线站点、从回收站恢复、管理员下架删除）
 * 3. 任务队列干预（强行停止、失败任务运维重跑）
 * 4. 灵感社区治理（精选置顶、违规作品移除）
 * 5. 用户反馈分诊（待处理/跟进中/已解决/已忽略 + 管理员备注）
 * 6. 限流窗口清理与不可篡改管理操作审计日志
 */
@Service
public class AdminService {
    private static final Set<String> FEEDBACK_STATUSES = Set.of("open", "in_progress", "resolved", "ignored");

    private final JdbcClient jdbc;
    private final QuotaService quota;
    private final ZhiyeProperties props;
    private final AppRepository apps;
    private final TaskRepository tasks;
    private final AppService appService;
    private final TaskService taskService;
    private final Views views;
    private final ObjectStore store;
    private final AdminAuthService adminAuth;

    @Autowired
    public AdminService(JdbcClient jdbc,
                        QuotaService quota,
                        ZhiyeProperties props,
                        AppRepository apps,
                        TaskRepository tasks,
                        AppService appService,
                        TaskService taskService,
                        Views views,
                        ObjectStore store,
                        AdminAuthService adminAuth) {
        this.jdbc = jdbc;
        this.quota = quota;
        this.props = props;
        this.apps = apps;
        this.tasks = tasks;
        this.appService = appService;
        this.taskService = taskService;
        this.views = views;
        this.store = store;
        this.adminAuth = adminAuth;
    }

    public AdminService(JdbcClient jdbc,
                        QuotaService quota,
                        ZhiyeProperties props,
                        AuthProperties auth,
                        AppRepository apps,
                        TaskRepository tasks,
                        AppService appService,
                        TaskService taskService,
                        Views views,
                        ObjectStore store,
                        String allowLoginNamesRaw) {
        this(jdbc, quota, props, apps, tasks, appService, taskService, views, store,
                new AdminAuthService(jdbc, auth, allowLoginNamesRaw, "", ""));
    }

    /** 校验当前请求是否具备管理操作权限，并返回用于审计日志的操作者标识。 */
    public String requireActor(HttpServletRequest req, String fallbackWorkspaceId) {
        return adminAuth.requireAdminActor(req, fallbackWorkspaceId);
    }

    public String requireActor(SsoUser user, String fallbackWorkspaceId) {
        return adminAuth.requireAdminActor(user, fallbackWorkspaceId);
    }

    /** 聚合返回管理控制台治理全貌数据 */
    public Map<String, Object> overview(String actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentActor", actor);
        out.put("ssoRestricted", adminAuth.isSsoRestricted());
        out.put("defaultQuota", Map.of(
                "maxApps", props.workspace().maxApps(),
                "dailyTaskLimit", props.workspace().dailyTaskLimit(),
                "maxConcurrentTasks", props.workspace().maxConcurrentTasks()));

        out.put("customQuotas", queryCustomQuotas());
        var feedbacks = queryFeedbacks();
        out.put("feedbacks", feedbacks);
        out.put("feedbackOpenCount", feedbacks.stream()
                .filter(f -> "open".equals(f.get("status")) || "in_progress".equals(f.get("status")))
                .count());
        out.put("deletedApps", queryDeletedApps());
        out.put("templates", queryTemplates());
        out.put("rateLimitStats", queryRateLimitStats());
        out.put("auditLogs", queryAuditLogs());
        return out;
    }

    private List<Map<String, Object>> queryCustomQuotas() {
        return jdbc.sql("""
                select q.workspace_id, q.max_apps, q.daily_task_limit, q.max_concurrent_tasks,
                       q.note, q.updated_by, q.updated_at,
                       u.login_name, u.real_name,
                       (select count(*) from app a where a.workspace_id = q.workspace_id and a.deleted_at is null) as app_count,
                       (select count(*) from task t where t.workspace_id = q.workspace_id and t.created_at >= date_trunc('day', now())) as tasks_today,
                       (select count(*) from task t where t.workspace_id = q.workspace_id
                         and t.status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')) as active_tasks
                from workspace_quota q
                left join workspace w on w.id = q.workspace_id
                left join app_user u on u.id = w.owner_user_id
                order by q.updated_at desc
                limit 100
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workspaceId", rs.getString("workspace_id"));
            m.put("ownerLogin", rs.getString("login_name"));
            m.put("ownerName", displayName(rs.getString("real_name"), rs.getString("login_name"), rs.getString("workspace_id")));
            m.put("maxApps", rs.getObject("max_apps", Integer.class));
            m.put("dailyTaskLimit", rs.getObject("daily_task_limit", Integer.class));
            m.put("maxConcurrentTasks", rs.getObject("max_concurrent_tasks", Integer.class));
            m.put("note", rs.getString("note"));
            m.put("updatedBy", rs.getString("updated_by"));
            m.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
            m.put("appCount", rs.getInt("app_count"));
            m.put("tasksToday", rs.getInt("tasks_today"));
            m.put("activeTasks", rs.getInt("active_tasks"));
            return m;
        }).list();
    }

    private List<Map<String, Object>> queryFeedbacks() {
        return jdbc.sql("""
                select f.id, f.workspace_id, f.conversation_id, f.content,
                       jsonb_array_length(coalesce(f.asset_ids, '[]'::jsonb)) as asset_count,
                       f.status, f.admin_note, f.resolved_at, f.created_at,
                       u.login_name, u.real_name
                from help_feedback f
                left join workspace w on w.id = f.workspace_id
                left join app_user u on u.id = w.owner_user_id
                order by case f.status when 'open' then 0 when 'in_progress' then 1 when 'resolved' then 2 else 3 end,
                         f.created_at desc
                limit 100
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("workspaceId", rs.getString("workspace_id"));
            m.put("ownerLogin", rs.getString("login_name"));
            m.put("ownerName", displayName(rs.getString("real_name"), rs.getString("login_name"), rs.getString("workspace_id")));
            m.put("conversationId", rs.getString("conversation_id"));
            m.put("content", rs.getString("content"));
            m.put("assetCount", rs.getInt("asset_count"));
            m.put("status", rs.getString("status"));
            m.put("adminNote", rs.getString("admin_note"));
            m.put("resolvedAt", Db.iso(Db.ts(rs, "resolved_at")));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).list();
    }

    private List<Map<String, Object>> queryDeletedApps() {
        return jdbc.sql("""
                select a.id, a.workspace_id, a.name, a.type, a.project_kind, a.phase, a.deleted_at, a.updated_at,
                       u.login_name, u.real_name,
                       (select count(*) from version v where v.app_id = a.id) as version_count
                from app a
                left join workspace w on w.id = a.workspace_id
                left join app_user u on u.id = w.owner_user_id
                where a.deleted_at is not null
                order by a.deleted_at desc
                limit 100
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("workspaceId", rs.getString("workspace_id"));
            m.put("ownerLogin", rs.getString("login_name"));
            m.put("ownerName", displayName(rs.getString("real_name"), rs.getString("login_name"), rs.getString("workspace_id")));
            m.put("name", rs.getString("name"));
            m.put("type", rs.getString("type"));
            m.put("projectKind", rs.getString("project_kind"));
            m.put("phase", rs.getString("phase"));
            m.put("versionCount", rs.getInt("version_count"));
            m.put("deletedAt", Db.iso(Db.ts(rs, "deleted_at")));
            m.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
            return m;
        }).list();
    }

    private List<Map<String, Object>> queryTemplates() {
        return jdbc.sql("""
                select id, workspace_id, name, description, app_type, project_kind, author,
                       clone_count, view_count, like_count, featured, pinned_order, created_at
                from template
                order by featured desc, pinned_order desc, created_at desc
                limit 200
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("workspaceId", rs.getString("workspace_id"));
            m.put("name", rs.getString("name"));
            m.put("description", rs.getString("description"));
            m.put("type", rs.getString("app_type"));
            m.put("projectKind", rs.getString("project_kind"));
            m.put("author", rs.getString("author"));
            m.put("cloneCount", rs.getInt("clone_count"));
            m.put("viewCount", rs.getInt("view_count"));
            m.put("likeCount", rs.getInt("like_count"));
            m.put("featured", rs.getBoolean("featured"));
            m.put("pinnedOrder", rs.getInt("pinned_order"));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).list();
    }

    private Map<String, Object> queryRateLimitStats() {
        long totalWindow = jdbc.sql("select count(*) from rate_limit_hit").query(Long.class).single();
        long activeWindow = jdbc.sql("select count(*) from rate_limit_hit where created_at >= now() - interval '10 minutes'")
                .query(Long.class).single();
        long rejected24h = jdbc.sql("select count(*) from rate_limit_event where outcome = 'rejected' and created_at >= now() - interval '24 hours'")
                .query(Long.class).single();
        long allowed24h = jdbc.sql("select count(*) from rate_limit_event where outcome = 'allowed' and created_at >= now() - interval '24 hours'")
                .query(Long.class).single();
        var buckets = jdbc.sql("""
                with w as (
                    select bucket, count(*) as active_hits, max(created_at) as last_w
                    from rate_limit_hit
                    group by bucket
                ),
                ev as (
                    select bucket,
                           count(*) filter (where outcome = 'rejected' and created_at >= now() - interval '24 hours') as rejected_24h,
                           count(*) filter (where outcome = 'allowed' and created_at >= now() - interval '24 hours') as allowed_24h,
                           count(*) filter (where outcome = 'rejected') as total_rejected,
                           max(created_at) filter (where outcome = 'rejected') as last_rejected_at,
                           max(created_at) as last_ev
                    from rate_limit_event
                    group by bucket
                ),
                b as (
                    select bucket from w
                    union
                    select bucket from ev
                )
                select b.bucket,
                       coalesce(w.active_hits, 0) as active_hits,
                       coalesce(ev.rejected_24h, 0) as rejected_24h,
                       coalesce(ev.allowed_24h, 0) as allowed_24h,
                       coalesce(ev.total_rejected, 0) as total_rejected,
                       greatest(w.last_w, ev.last_ev) as last_hit_at,
                       ev.last_rejected_at
                from b
                left join w on w.bucket = b.bucket
                left join ev on ev.bucket = b.bucket
                order by rejected_24h desc, active_hits desc, last_hit_at desc nulls last
                limit 30
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bucket", rs.getString("bucket"));
            m.put("activeHits", rs.getLong("active_hits"));
            m.put("rejected24h", rs.getLong("rejected_24h"));
            m.put("allowed24h", rs.getLong("allowed_24h"));
            m.put("hits24h", rs.getLong("rejected_24h"));
            m.put("totalHits", rs.getLong("active_hits"));
            m.put("totalRejected", rs.getLong("total_rejected"));
            m.put("lastHitAt", Db.iso(Db.ts(rs, "last_hit_at")));
            m.put("lastRejectedAt", Db.iso(Db.ts(rs, "last_rejected_at")));
            return m;
        }).list();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalHits", totalWindow);
        m.put("activeWindowHits", activeWindow);
        m.put("rejected24h", rejected24h);
        m.put("allowed24h", allowed24h);
        m.put("recentHits24h", rejected24h);
        m.put("buckets", buckets);
        return m;
    }

    private List<Map<String, Object>> queryAuditLogs() {
        return jdbc.sql("""
                select id, actor, action, target_type, target_id, detail, created_at
                from admin_audit_log
                order by created_at desc
                limit 60
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
    }

    /** 设置或重置指定工作空间的自定义配额 */
    public Map<String, Object> updateQuota(String wsId, JsonNode body, String actor) {
        long exists = jdbc.sql("select count(*) from workspace where id = ?").param(wsId).query(Long.class).single();
        if (exists == 0) throw ApiException.notFound("工作空间");

        boolean reset = body.path("reset").asBoolean(false);
        Integer maxApps = optInt(body, "maxApps", 1, 500, "应用数量上限");
        Integer dailyTaskLimit = optInt(body, "dailyTaskLimit", 1, 5000, "每日任务上限");
        Integer maxConcurrentTasks = optInt(body, "maxConcurrentTasks", 1, 50, "同时运行任务上限");
        String note = clip(body.path("note").asText("").trim(), 200);
        if (note.isEmpty()) note = null;

        if (reset || (maxApps == null && dailyTaskLimit == null && maxConcurrentTasks == null)) {
            jdbc.sql("delete from workspace_quota where workspace_id = ?").param(wsId).update();
            audit(actor, "QUOTA_RESET", "workspace", wsId, Map.of("reset", true));
        } else {
            jdbc.sql("""
                    insert into workspace_quota (workspace_id, max_apps, daily_task_limit, max_concurrent_tasks, note, updated_by, updated_at)
                    values (?, ?, ?, ?, ?, ?, now())
                    on conflict (workspace_id) do update set
                      max_apps = excluded.max_apps,
                      daily_task_limit = excluded.daily_task_limit,
                      max_concurrent_tasks = excluded.max_concurrent_tasks,
                      note = excluded.note,
                      updated_by = excluded.updated_by,
                      updated_at = now()
                    """).params(wsId, maxApps, dailyTaskLimit, maxConcurrentTasks, note, actor).update();

            Map<String, Object> detail = new LinkedHashMap<>();
            if (maxApps != null) detail.put("maxApps", maxApps);
            if (dailyTaskLimit != null) detail.put("dailyTaskLimit", dailyTaskLimit);
            if (maxConcurrentTasks != null) detail.put("maxConcurrentTasks", maxConcurrentTasks);
            if (note != null) detail.put("note", note);
            audit(actor, "QUOTA_UPDATE", "workspace", wsId, detail);
        }

        var lim = quota.effectiveLimits(wsId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workspaceId", wsId);
        out.put("maxApps", lim.maxApps());
        out.put("dailyTaskLimit", lim.dailyTaskLimit());
        out.put("maxConcurrentTasks", lim.maxConcurrentTasks());
        out.put("custom", lim.custom());
        out.put("note", lim.note());
        return out;
    }

    /** 强行下线已发布站点（不删除应用本体） */
    public Map<String, Object> unpublishApp(String id, String actor) {
        AppRow app = apps.findById(id).orElseThrow(() -> ApiException.notFound("应用"));
        if (app.deletedAt() != null) throw ApiException.conflict("APP_DELETED", "应用已被删除，无需下线");
        jdbc.sql("update app set published_version_id = null, updated_at = now() where id = ?").param(id).update();
        jdbc.sql("update deployment set status = 'inactive' where app_id = ? and status = 'active'").param(id).update();
        audit(actor, "APP_UNPUBLISH", "app", id, Map.of("name", app.name(), "workspaceId", app.workspaceId()));
        return Map.of("id", id, "name", app.name(), "published", false);
    }

    /** 从回收站恢复被软删除的应用 */
    public Map<String, Object> restoreApp(String id, String actor) {
        AppRow app = apps.findById(id).orElseThrow(() -> ApiException.notFound("应用"));
        if (app.deletedAt() == null) throw ApiException.conflict("APP_NOT_DELETED", "应用未被删除，无需恢复");
        jdbc.sql("update app set deleted_at = null, updated_at = now() where id = ?").param(id).update();
        audit(actor, "APP_RESTORE", "app", id, Map.of("name", app.name(), "workspaceId", app.workspaceId()));
        return Map.of("id", id, "name", app.name(), "restored", true);
    }

    /** 管理员软删除应用（含下线站点与清草稿；存在进行中任务时拒绝） */
    public Map<String, Object> deleteApp(String id, String actor) {
        AppRow app = apps.findById(id).orElseThrow(() -> ApiException.notFound("应用"));
        if (app.deletedAt() != null) return Map.of("id", id, "name", app.name(), "deleted", true);
        appService.delete(app.workspaceId(), id);
        audit(actor, "APP_DELETE", "app", id, Map.of("name", app.name(), "workspaceId", app.workspaceId()));
        return Map.of("id", id, "name", app.name(), "deleted", true);
    }

    /** 管理员强行停止进行中或排队中的任务 */
    public Map<String, Object> cancelTask(String id, String actor) {
        TaskRow t = tasks.findById(id).orElseThrow(() -> ApiException.notFound("任务"));
        TaskRow cancelled = taskService.cancel(t.workspaceId(), id);
        audit(actor, "TASK_CANCEL", "task", id, Map.of("appId", t.appId(), "workspaceId", t.workspaceId(), "status", cancelled.status()));
        return views.task(cancelled);
    }

    /** 管理员运维重跑失败或被取消的任务 */
    public Map<String, Object> retryTask(String id, String actor) {
        TaskRow t = tasks.findById(id).orElseThrow(() -> ApiException.notFound("任务"));
        TaskRow retried = taskService.retry(t.workspaceId(), id, "admin-" + UUID.randomUUID());
        audit(actor, "TASK_RETRY", "task", id, Map.of("newTaskId", retried.id(), "appId", t.appId(), "workspaceId", t.workspaceId()));
        return views.task(retried);
    }

    /** 设置或取消灵感社区作品的精选置顶权重 */
    public Map<String, Object> featureTemplate(String id, JsonNode body, String actor) {
        String name = jdbc.sql("select name from template where id = ?").param(id).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("作品"));
        boolean featured = body.path("featured").asBoolean(true);
        int pinnedOrder = body.has("pinnedOrder") && !body.get("pinnedOrder").isNull()
                ? body.get("pinnedOrder").asInt(featured ? 10 : 0)
                : (featured ? 10 : 0);
        if (pinnedOrder < 0 || pinnedOrder > 999) {
            throw ApiException.validation("置顶权重需要在 0～999 之间");
        }
        jdbc.sql("update template set featured = ?, pinned_order = ? where id = ?")
                .params(featured, featured ? pinnedOrder : 0, id).update();
        audit(actor, "TEMPLATE_FEATURE", "template", id,
                Map.of("name", name, "featured", featured, "pinnedOrder", featured ? pinnedOrder : 0));
        return Map.of("id", id, "featured", featured, "pinnedOrder", featured ? pinnedOrder : 0);
    }

    /** 管理员从灵感社区删除违规或低质作品 */
    public Map<String, Object> deleteTemplate(String id, String actor) {
        var row = jdbc.sql("select name, object_key, preview_prefix from template where id = ?")
                .param(id)
                .query((rs, i) -> Map.of(
                        "name", rs.getString("name"),
                        "objectKey", rs.getString("object_key"),
                        "previewPrefix", rs.getString("preview_prefix") == null ? "" : rs.getString("preview_prefix")))
                .optional()
                .orElseThrow(() -> ApiException.notFound("作品"));
        jdbc.sql("delete from template where id = ?").param(id).update();
        try {
            if (!row.get("objectKey").isBlank()) store.delete(row.get("objectKey"));
            if (!row.get("previewPrefix").isBlank()) store.deletePrefix(row.get("previewPrefix") + "/");
        } catch (Exception ignore) { /* 对象存储文件不存在不影响记录删除 */ }
        audit(actor, "TEMPLATE_DELETE", "template", id, Map.of("name", row.get("name")));
        return Map.of("id", id, "deleted", true);
    }

    /** 分诊与处理用户反馈 */
    public Map<String, Object> triageFeedback(String id, JsonNode body, String actor) {
        long exists = jdbc.sql("select count(*) from help_feedback where id = ?").param(id).query(Long.class).single();
        if (exists == 0) throw ApiException.notFound("反馈");
        String status = body.path("status").asText("").trim();
        if (!FEEDBACK_STATUSES.contains(status)) {
            throw ApiException.validation("不支持的反馈状态：" + status);
        }
        String adminNote = body.has("adminNote") && !body.get("adminNote").isNull()
                ? clip(body.path("adminNote").asText("").trim(), 500)
                : null;
        if (adminNote != null && adminNote.isEmpty()) adminNote = null;

        boolean resolved = "resolved".equals(status) || "ignored".equals(status);
        jdbc.sql("""
                update help_feedback
                set status = ?, admin_note = ?, resolved_at = case when ? then coalesce(resolved_at, now()) else null end
                where id = ?
                """).params(status, adminNote, resolved, id).update();

        String resolvedAt = jdbc.sql("select resolved_at from help_feedback where id = ?")
                .param(id).query((rs, i) -> Db.iso(Db.ts(rs, "resolved_at"))).single();

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", status);
        if (adminNote != null) detail.put("adminNote", adminNote);
        audit(actor, "FEEDBACK_TRIAGE", "feedback", id, detail);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", status);
        out.put("adminNote", adminNote);
        out.put("resolvedAt", resolvedAt);
        return out;
    }

    /** 清理限流触发记录（全部或按指定 bucket） */
    public Map<String, Object> clearRateLimits(JsonNode body, String actor) {
        String bucket = body.path("bucket").asText("").trim();
        int cleared;
        if (bucket.isEmpty()) {
            cleared = jdbc.sql("delete from rate_limit_hit").update();
        } else {
            cleared = jdbc.sql("delete from rate_limit_hit where bucket = ?").param(bucket).update();
        }
        audit(actor, "RATE_LIMIT_CLEAR", "rate_limit", bucket.isEmpty() ? "all" : bucket, Map.of("clearedCount", cleared));
        return Map.of("clearedCount", cleared, "bucket", bucket.isEmpty() ? "all" : bucket);
    }

    private void audit(String actor, String action, String targetType, String targetId, Map<String, Object> detail) {
        jdbc.sql("""
                insert into admin_audit_log (id, actor, action, target_type, target_id, detail)
                values (?, ?, ?, ?, ?, ?::jsonb)
                """).params(Ids.newId("aud"), actor, action, targetType, targetId, Json.write(detail == null ? Map.of() : detail)).update();
    }

    private static Integer optInt(JsonNode body, String field, int min, int max, String label) {
        if (!body.has(field) || body.get(field).isNull()) return null;
        if (!body.get(field).canConvertToInt() && !body.get(field).isInt()) {
            throw ApiException.validation(label + " 必须是整数");
        }
        int v = body.get(field).asInt();
        if (v < min || v > max) {
            throw ApiException.validation(label + " 需要在 " + min + "～" + max + " 之间");
        }
        return v;
    }

    private static String clip(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private static String displayName(String realName, String loginName, String fallback) {
        if (realName != null && !realName.isBlank()) return realName.trim();
        if (loginName != null && !loginName.isBlank()) return loginName.trim();
        return fallback;
    }
}
