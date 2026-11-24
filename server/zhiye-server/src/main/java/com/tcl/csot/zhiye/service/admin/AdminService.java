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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final Set<String> USER_ROLES = Set.of("admin", "user");
    private static final Set<String> SITE_ACCESS_LEVELS = Set.of("public", "login", "members");

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

    @Autowired(required = false)
    private com.tcl.csot.zhiye.service.repo.GitService git;

    @Autowired(required = false)
    private com.tcl.csot.zhiye.service.runtime.MonolithServiceManager monolithService;

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

    public String currentUserId(HttpServletRequest req) {
        return adminAuth.currentUserId(req);
    }

    /** 聚合返回管理控制台治理全貌数据 */
    public Map<String, Object> overview(String actor) {
        return overview(actor, null);
    }

    /** 聚合返回管理控制台治理全貌数据 */
    public Map<String, Object> overview(String actor, String currentUserId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentActor", actor);
        out.put("ssoRestricted", adminAuth.isSsoRestricted());
        out.put("defaultQuota", Map.of(
                "maxApps", props.workspace().maxApps(),
                "dailyTaskLimit", props.workspace().dailyTaskLimit(),
                "maxConcurrentTasks", props.workspace().maxConcurrentTasks()));

        out.put("customQuotas", queryCustomQuotas());
        out.put("users", queryUsers(currentUserId));
        var feedbacks = queryFeedbacks();
        out.put("feedbacks", feedbacks);
        out.put("feedbackOpenCount", feedbacks.stream()
                .filter(f -> "open".equals(f.get("status")) || "in_progress".equals(f.get("status")))
                .count());
        out.put("publishedApps", queryPublishedApps());
        out.put("monolithServices", queryMonolithServices());
        out.put("allApps", queryAllApps());
        out.put("workspaces", queryWorkspaces());
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
            String login = rs.getString("login_name");
            String real = rs.getString("real_name");
            m.put("ownerLogin", login);
            m.put("ownerRealName", real);
            m.put("ownerBound", (login != null && !login.isBlank()) || (real != null && !real.isBlank()));
            m.put("ownerName", displayName(real, login, rs.getString("workspace_id")));
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
            String login = rs.getString("login_name");
            String real = rs.getString("real_name");
            m.put("ownerLogin", login);
            m.put("ownerRealName", real);
            m.put("ownerBound", (login != null && !login.isBlank()) || (real != null && !real.isBlank()));
            m.put("ownerName", displayName(real, login, rs.getString("workspace_id")));
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
                limit 2000
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("workspaceId", rs.getString("workspace_id"));
            String login = rs.getString("login_name");
            String real = rs.getString("real_name");
            m.put("ownerLogin", login);
            m.put("ownerRealName", real);
            m.put("ownerBound", (login != null && !login.isBlank()) || (real != null && !real.isBlank()));
            m.put("ownerName", displayName(real, login, rs.getString("workspace_id")));
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
                limit 200
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

    private List<Map<String, Object>> queryUsers(String currentUserId) {
        return jdbc.sql("""
                select u.id, u.login_name, u.real_name, u.email, u.role, u.created_at, u.last_login_at,
                       (select count(*) from workspace w where w.owner_user_id = u.id) as ws_count,
                       (select count(*) from app a join workspace w on w.id = a.workspace_id
                         where w.owner_user_id = u.id and a.deleted_at is null) as app_count
                from app_user u
                order by case when lower(u.role) = 'admin' then 0 else 1 end, u.last_login_at desc
                limit 200
                """).query((rs, i) -> {
            String id = rs.getString("id");
            String loginName = rs.getString("login_name");
            String dbRole = rs.getString("role") == null ? "user" : rs.getString("role").toLowerCase();
            boolean allowlisted = adminAuth.isAllowlistedLogin(loginName);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("loginName", loginName);
            m.put("realName", displayName(rs.getString("real_name"), loginName, id));
            m.put("email", rs.getString("email"));
            m.put("role", allowlisted ? "admin" : dbRole);
            m.put("dbRole", dbRole);
            m.put("allowlisted", allowlisted);
            m.put("self", currentUserId != null && currentUserId.equals(id));
            m.put("workspaceCount", rs.getInt("ws_count"));
            m.put("appCount", rs.getInt("app_count"));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            m.put("lastLoginAt", Db.iso(Db.ts(rs, "last_login_at")));
            return m;
        }).list();
    }

    private List<Map<String, Object>> queryPublishedApps() {
        return jdbc.sql("""
                select a.id, a.workspace_id, a.name, a.slug, a.type, a.project_kind,
                       a.published_version_id, a.updated_at,
                       coalesce(sa.level, 'public') as access_level,
                       u.login_name, u.real_name,
                       coalesce((select sum(pv) from app_pageview_daily p where p.app_id = a.id), 0) as total_pv,
                       coalesce((select sum(pv) from app_pageview_daily p where p.app_id = a.id and p.day = current_date), 0) as today_pv
                from app a
                left join workspace w on w.id = a.workspace_id
                left join app_user u on u.id = w.owner_user_id
                left join app_site_access sa on sa.app_id = a.id
                where a.deleted_at is null and a.published_version_id is not null
                order by total_pv desc, a.updated_at desc
                limit 2000
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("workspaceId", rs.getString("workspace_id"));
            String login = rs.getString("login_name");
            String real = rs.getString("real_name");
            m.put("ownerLogin", login);
            m.put("ownerRealName", real);
            m.put("ownerBound", (login != null && !login.isBlank()) || (real != null && !real.isBlank()));
            m.put("ownerName", displayName(real, login, rs.getString("workspace_id")));
            m.put("name", rs.getString("name"));
            m.put("slug", rs.getString("slug"));
            m.put("type", rs.getString("type"));
            m.put("projectKind", rs.getString("project_kind"));
            m.put("accessLevel", rs.getString("access_level"));
            m.put("publishedVersionId", rs.getString("published_version_id"));
            m.put("totalPv", rs.getLong("total_pv"));
            m.put("todayPv", rs.getLong("today_pv"));
            m.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
            return m;
        }).list();
    }

    private List<Map<String, Object>> queryAllApps() {
        return jdbc.sql("""
                select a.id, a.workspace_id, a.name, a.slug, a.type, a.project_kind, a.phase,
                       a.published_version_id, a.updated_at, a.created_at,
                       coalesce(sa.level, 'public') as access_level,
                       u.login_name, u.real_name,
                       (select count(*) from version v where v.app_id = a.id) as version_count,
                       exists(select 1 from app_cloud ac where ac.app_id = a.id) as cloud_enabled,
                       coalesce((select sum(pv) from app_pageview_daily p where p.app_id = a.id), 0) as total_pv,
                       coalesce((select sum(pv) from app_pageview_daily p where p.app_id = a.id and p.day = current_date), 0) as today_pv
                from app a
                left join workspace w on w.id = a.workspace_id
                left join app_user u on u.id = w.owner_user_id
                left join app_site_access sa on sa.app_id = a.id
                where a.deleted_at is null
                order by a.updated_at desc
                limit 2000
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("workspaceId", rs.getString("workspace_id"));
            String login = rs.getString("login_name");
            String real = rs.getString("real_name");
            m.put("ownerLogin", login);
            m.put("ownerRealName", real);
            m.put("ownerBound", (login != null && !login.isBlank()) || (real != null && !real.isBlank()));
            m.put("ownerName", displayName(real, login, rs.getString("workspace_id")));
            m.put("name", rs.getString("name"));
            m.put("slug", rs.getString("slug"));
            m.put("type", rs.getString("type"));
            m.put("projectKind", rs.getString("project_kind"));
            m.put("phase", rs.getString("phase"));
            m.put("published", rs.getString("published_version_id") != null);
            m.put("publishedVersionId", rs.getString("published_version_id"));
            m.put("accessLevel", rs.getString("access_level"));
            m.put("versionCount", rs.getInt("version_count"));
            m.put("cloudEnabled", rs.getBoolean("cloud_enabled"));
            m.put("totalPv", rs.getLong("total_pv"));
            m.put("todayPv", rs.getLong("today_pv"));
            m.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).list();
    }

    private List<Map<String, Object>> queryWorkspaces() {
        return jdbc.sql("""
                select w.id, w.owner_user_id, w.created_at,
                       u.login_name, u.real_name,
                       (select count(*) from app a where a.workspace_id = w.id and a.deleted_at is null) as app_count,
                       (select count(*) from app a where a.workspace_id = w.id and a.deleted_at is null and a.published_version_id is not null) as published_count,
                       (select count(*) from app a where a.workspace_id = w.id and a.deleted_at is not null) as deleted_app_count,
                       (select count(*) from conversation c where c.workspace_id = w.id) as conv_count,
                       (select count(*) from data_source ds where ds.workspace_id = w.id and ds.deleted_at is null) as ds_count,
                       greatest(
                           w.created_at,
                           coalesce((select max(a.updated_at) from app a where a.workspace_id = w.id), w.created_at),
                           coalesce((select max(c.updated_at) from conversation c where c.workspace_id = w.id), w.created_at)
                       ) as last_active_at,
                       coalesce(
                           (select jsonb_agg(s.name)::text from (
                               select a.name from app a
                               where a.workspace_id = w.id and a.deleted_at is null
                               order by a.updated_at desc
                               limit 3
                           ) s),
                           '[]'
                       ) as sample_apps
                from workspace w
                left join app_user u on u.id = w.owner_user_id
                where w.owner_user_id is not null
                   or exists(select 1 from app a where a.workspace_id = w.id)
                   or exists(select 1 from conversation c where c.workspace_id = w.id)
                order by case when w.owner_user_id is not null then 0 else 1 end,
                         app_count desc, published_count desc, last_active_at desc
                limit 2000
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String wsId = rs.getString("id");
            String ownerUserId = rs.getString("owner_user_id");
            String login = rs.getString("login_name");
            String real = rs.getString("real_name");
            m.put("workspaceId", wsId);
            m.put("ownerUserId", ownerUserId);
            m.put("ownerLogin", login);
            m.put("ownerRealName", real);
            m.put("ownerBound", ownerUserId != null || (login != null && !login.isBlank()) || (real != null && !real.isBlank()));
            m.put("ownerName", displayName(real, login, wsId));
            m.put("appCount", rs.getInt("app_count"));
            m.put("publishedCount", rs.getInt("published_count"));
            m.put("deletedAppCount", rs.getInt("deleted_app_count"));
            m.put("conversationCount", rs.getInt("conv_count"));
            m.put("dataSourceCount", rs.getInt("ds_count"));
            m.put("sampleApps", Json.read(rs.getString("sample_apps")));
            m.put("lastActiveAt", Db.iso(Db.ts(rs, "last_active_at")));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).list();
    }

    /** 员工 / 工作空间 360° 细查档案（含近 30 日活跃趋势、应用类型分布、名下应用列表与任务/发布活动时间线） */
    public Map<String, Object> workspaceProfile(String wsId) {
        var wsOpt = jdbc.sql("""
                select w.id, w.owner_user_id, w.created_at,
                       u.login_name, u.real_name, u.email, u.last_login_at,
                       (select count(*) from app a where a.workspace_id = w.id and a.deleted_at is null) as app_count,
                       (select count(*) from app a where a.workspace_id = w.id and a.deleted_at is null and a.published_version_id is not null) as published_count,
                       (select count(*) from conversation c where c.workspace_id = w.id and c.deleted_at is null) as conv_count,
                       (select count(*) from data_source ds where ds.workspace_id = w.id and ds.deleted_at is null) as ds_count
                from workspace w
                left join app_user u on u.id = w.owner_user_id
                where w.id = ?
                """).param(wsId).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String login = rs.getString("login_name");
            String real = rs.getString("real_name");
            String uid = rs.getString("owner_user_id");
            m.put("workspaceId", rs.getString("id"));
            m.put("ownerUserId", uid);
            m.put("ownerLogin", login);
            m.put("ownerRealName", real);
            m.put("ownerEmail", rs.getString("email"));
            m.put("ownerBound", uid != null || (login != null && !login.isBlank()) || (real != null && !real.isBlank()));
            m.put("ownerName", displayName(real, login, wsId));
            m.put("appCount", rs.getInt("app_count"));
            m.put("publishedCount", rs.getInt("published_count"));
            m.put("conversationCount", rs.getInt("conv_count"));
            m.put("dataSourceCount", rs.getInt("ds_count"));
            m.put("lastLoginAt", Db.iso(Db.ts(rs, "last_login_at")));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).optional();
        if (wsOpt.isEmpty()) throw ApiException.notFound("工作空间");
        Map<String, Object> owner = wsOpt.get();

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        LocalDate start30 = today.minusDays(29);
        OffsetDateTime startTs = start30.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime endTs = today.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        Map<LocalDate, Long> tasksByDay = new HashMap<>();
        jdbc.sql("""
                select (created_at at time zone 'Asia/Shanghai')::date as d, count(*) as c
                from task where workspace_id = ? and created_at >= ? and created_at < ?
                group by 1
                """).params(wsId, startTs, endTs).query((rs, i) -> {
            tasksByDay.put(rs.getObject("d", LocalDate.class), rs.getLong("c"));
            return null;
        }).list();

        Map<LocalDate, Long> pvByDay = new HashMap<>();
        jdbc.sql("""
                select p.day as d, coalesce(sum(p.pv), 0) as c
                from app_pageview_daily p join app a on a.id = p.app_id
                where a.workspace_id = ? and a.deleted_at is null and p.day >= ? and p.day <= ?
                group by 1
                """).params(wsId, start30, today).query((rs, i) -> {
            pvByDay.put(rs.getObject("d", LocalDate.class), rs.getLong("c"));
            return null;
        }).list();

        List<Map<String, Object>> daily30d = new ArrayList<>(30);
        int activeDays30d = 0;
        long totalPv30d = 0;
        long totalTasks30d = 0;
        for (LocalDate d = start30; !d.isAfter(today); d = d.plusDays(1)) {
            long tc = tasksByDay.getOrDefault(d, 0L);
            long pc = pvByDay.getOrDefault(d, 0L);
            if (tc > 0 || pc > 0) activeDays30d++;
            totalTasks30d += tc;
            totalPv30d += pc;
            daily30d.add(Map.of("date", d.toString(), "tasks", tc, "pv", pc));
        }

        long okTasks30d = jdbc.sql("select count(*) from task where workspace_id = ? and created_at >= ? and created_at < ? and status = 'SUCCEEDED'")
                .params(wsId, startTs, endTs).query(Long.class).single();
        long termTasks30d = jdbc.sql("select count(*) from task where workspace_id = ? and created_at >= ? and created_at < ? and status in ('SUCCEEDED','FAILED','CANCELLED')")
                .params(wsId, startTs, endTs).query(Long.class).single();
        owner.put("activeDays30d", activeDays30d);
        owner.put("totalPv30d", totalPv30d);
        owner.put("totalTasks30d", totalTasks30d);
        owner.put("taskSuccessRate30d", termTasks30d == 0 ? 0.0 : Math.round((double) okTasks30d * 1000.0 / termTasks30d) / 10.0);

        List<Map<String, Object>> typeBreakdown = jdbc.sql("""
                select coalesce(nullif(type, ''), '通用应用') as k, count(*) as c
                from app where workspace_id = ? and deleted_at is null
                group by 1 order by 2 desc
                """).param(wsId).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", rs.getString("k"));
            m.put("count", rs.getLong("c"));
            return m;
        }).list();

        List<Map<String, Object>> wsApps = jdbc.sql("""
                select a.id, a.name, a.slug, a.type, a.phase,
                       (a.published_version_id is not null) as published,
                       coalesce((select sum(p.pv) from app_pageview_daily p where p.app_id = a.id), 0) as total_pv,
                       a.updated_at
                from app a
                where a.workspace_id = ? and a.deleted_at is null
                order by a.updated_at desc
                limit 30
                """).param(wsId).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("name", rs.getString("name"));
            m.put("slug", rs.getString("slug"));
            m.put("type", rs.getString("type"));
            m.put("phase", rs.getString("phase"));
            m.put("published", rs.getBoolean("published"));
            m.put("totalPv", rs.getLong("total_pv"));
            m.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
            return m;
        }).list();

        List<Map<String, Object>> timeline = jdbc.sql("""
                select t.id, t.type, t.status, t.error_code, t.error_message,
                       t.app_id, coalesce(a.name, '') as app_name,
                       coalesce(extract(epoch from (t.finished_at - t.started_at)) * 1000, 0)::bigint as duration_ms,
                       t.created_at
                from task t
                left join app a on a.id = t.app_id
                where t.workspace_id = ?
                order by t.created_at desc
                limit 40
                """).param(wsId).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("kind", "task");
            m.put("taskType", rs.getString("type"));
            m.put("status", rs.getString("status"));
            m.put("errorCode", rs.getString("error_code"));
            m.put("errorMessage", rs.getString("error_message"));
            m.put("appId", rs.getString("app_id"));
            m.put("appName", rs.getString("app_name"));
            m.put("durationMs", rs.getLong("duration_ms"));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).list();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", owner);
        out.put("daily30d", daily30d);
        out.put("typeBreakdown", typeBreakdown);
        out.put("apps", wsApps);
        out.put("timeline", timeline);
        return out;
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

    /** 强行下线已发布站点（不删除应用本体；若为单体服务则同时停止运行实例） */
    public Map<String, Object> unpublishApp(String id, String actor) {
        AppRow app = apps.findById(id).orElseThrow(() -> ApiException.notFound("应用"));
        if (app.deletedAt() != null) throw ApiException.conflict("APP_DELETED", "应用已被删除，无需下线");
        jdbc.sql("update app set published_version_id = null, updated_at = now() where id = ?").param(id).update();
        jdbc.sql("update deployment set status = 'inactive' where app_id = ? and status = 'active'").param(id).update();
        if (app.isService() && monolithService != null) {
            try { monolithService.adminStop(id); } catch (Exception ignored) {}
        }
        audit(actor, "APP_UNPUBLISH", "app", id, Map.of("name", app.name(), "workspaceId", app.workspaceId()));
        return Map.of("id", id, "name", app.name(), "published", false);
    }

    /** 管理员治理操作：强行休眠 / 停止 / 唤醒指定单体服务 */
    public Map<String, Object> controlMonolithService(String appId, String action, String actor) throws Exception {
        if (monolithService == null) throw ApiException.validation("当前环境未启用单体服务托管");
        AppRow app = apps.findById(appId).filter(AppRow::isService).orElseThrow(() -> ApiException.notFound("单体服务"));
        Map<String, Object> res;
        String auditAction;
        switch (action) {
            case "sleep" -> { res = monolithService.adminSleep(appId); auditAction = "SERVICE_SLEEP"; }
            case "stop" -> { res = monolithService.adminStop(appId); auditAction = "SERVICE_STOP"; }
            case "start" -> { res = monolithService.adminStart(appId); auditAction = "SERVICE_START"; }
            default -> throw ApiException.validation("不支持的服务操作：" + action);
        }
        audit(actor, auditAction, "app", appId, Map.of("name", app.name(), "workspaceId", app.workspaceId(), "action", action));
        return res;
    }

    private List<Map<String, Object>> queryMonolithServices() {
        return jdbc.sql("""
                select s.app_id, a.name, a.slug, a.workspace_id, a.published_version_id,
                       s.runtime, s.framework, s.status, s.port, s.memory_mb, s.cpu_millicores,
                       s.idle_sleep_min, s.pg_enabled, s.pg_schema, s.pvc_enabled, s.pvc_size_gi,
                       s.last_access_at, s.last_started_at, s.last_error, s.updated_at,
                       u.login_name, u.real_name
                from app_service s
                join app a on a.id = s.app_id
                left join workspace w on w.id = a.workspace_id
                left join app_user u on u.id = w.owner_user_id
                where a.deleted_at is null
                order by case s.status when 'running' then 0 when 'failed' then 1 when 'building' then 2 when 'sleeping' then 3 else 4 end,
                         s.updated_at desc
                limit 200
                """).query((rs, i) -> {
            String appId = rs.getString("app_id");
            String slug = rs.getString("slug");
            String status = rs.getString("status");
            if ("running".equals(status) && monolithService != null && !monolithService.isRunningNow(appId, slug)) {
                status = "sleeping";
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("appId", appId);
            m.put("name", rs.getString("name"));
            m.put("slug", slug);
            m.put("workspaceId", rs.getString("workspace_id"));
            m.put("published", rs.getString("published_version_id") != null);
            String login = rs.getString("login_name");
            String real = rs.getString("real_name");
            m.put("ownerLogin", login);
            m.put("ownerRealName", real);
            m.put("ownerName", displayName(real, login, rs.getString("workspace_id")));
            m.put("runtime", rs.getString("runtime"));
            m.put("framework", rs.getString("framework"));
            m.put("provider", monolithService == null ? "local" : monolithService.providerName());
            m.put("status", status);
            m.put("port", rs.getInt("port"));
            m.put("memoryMb", rs.getInt("memory_mb"));
            m.put("cpuMillicores", rs.getInt("cpu_millicores"));
            m.put("idleSleepMin", rs.getInt("idle_sleep_min"));
            m.put("pgEnabled", rs.getBoolean("pg_enabled"));
            m.put("pgSchema", rs.getString("pg_schema"));
            m.put("pvcEnabled", rs.getBoolean("pvc_enabled"));
            m.put("pvcSizeGi", rs.getInt("pvc_size_gi"));
            m.put("lastAccessAt", Db.iso(Db.ts(rs, "last_access_at")));
            m.put("lastStartedAt", Db.iso(Db.ts(rs, "last_started_at")));
            m.put("lastError", rs.getString("last_error"));
            m.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
            return m;
        }).list();
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

    /** 分诊与处理用户反馈（同一事务完成状态更新与审计留痕，兼容 open/in_progress 下 resolved_at 为空） */
    @Transactional
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

        List<String> resolvedRows = jdbc.sql("select resolved_at from help_feedback where id = ?")
                .param(id).query((rs, i) -> Db.iso(Db.ts(rs, "resolved_at"))).list();
        String resolvedAt = resolvedRows.isEmpty() ? null : resolvedRows.get(0);

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

    /** 授予或撤销 SSO 用户的平台管理员权限（禁止降级当前操作者本人或固定白名单账号） */
    public Map<String, Object> updateUserRole(String userId, JsonNode body, String actor, String currentUserId) {
        var row = jdbc.sql("select id, login_name, real_name, role from app_user where id = ?")
                .param(userId)
                .query((rs, i) -> Map.of(
                        "id", rs.getString("id"),
                        "loginName", rs.getString("login_name"),
                        "realName", displayName(rs.getString("real_name"), rs.getString("login_name"), rs.getString("id")),
                        "role", rs.getString("role") == null ? "user" : rs.getString("role").toLowerCase()))
                .optional()
                .orElseThrow(() -> ApiException.notFound("用户"));

        String role = body.path("role").asText("").trim().toLowerCase();
        if (!USER_ROLES.contains(role)) {
            throw ApiException.validation("不支持的角色类型：" + role);
        }
        if (currentUserId != null && currentUserId.equals(userId) && !"admin".equals(role)) {
            throw ApiException.conflict("SELF_DEMOTE_FORBIDDEN", "不能撤销当前登录账号自身的管理员权限");
        }
        if (adminAuth.isAllowlistedLogin(row.get("loginName")) && !"admin".equals(role)) {
            throw ApiException.conflict("ALLOWLIST_IMMUTABLE", "该账号属于系统配置白名单固定管理员，无法通过界面降级");
        }

        jdbc.sql("update app_user set role = ? where id = ?").params(role, userId).update();
        audit(actor, "ADMIN_ROLE_UPDATE", "user", userId, Map.of(
                "loginName", row.get("loginName"),
                "previousRole", row.get("role"),
                "role", role));
        return Map.of(
                "id", userId,
                "loginName", row.get("loginName"),
                "realName", row.get("realName"),
                "role", role);
    }

    /** 管理员调整已发布站点的访问权限级别（public / login / members） */
    public Map<String, Object> updateSiteAccess(String appId, JsonNode body, String actor) {
        AppRow app = apps.findById(appId).orElseThrow(() -> ApiException.notFound("应用"));
        if (app.deletedAt() != null) {
            throw ApiException.conflict("APP_DELETED", "应用已被删除，无法修改站点访问权限");
        }
        String level = body.path("level").asText("").trim().toLowerCase();
        if (!SITE_ACCESS_LEVELS.contains(level)) {
            throw ApiException.validation("不支持的站点访问级别：" + level);
        }
        jdbc.sql("""
                insert into app_site_access (app_id, level, updated_at)
                values (?, ?, now())
                on conflict (app_id) do update set level = excluded.level, updated_at = now()
                """).params(appId, level).update();
        audit(actor, "SITE_ACCESS_UPDATE", "app", appId, Map.of(
                "name", app.name(),
                "workspaceId", app.workspaceId(),
                "accessLevel", level));
        return Map.of("id", appId, "name", app.name(), "accessLevel", level);
    }

    /** 单应用归属转移：将指定应用及其版本历史、线上站点、云表、任务、素材与关联会话无损划转给目标工作空间（支持传 workspaceId、userId 或 SSO 登录工号） */
    @Transactional
    public Map<String, Object> transferApp(String appId, JsonNode body, String actor) {
        AppRow app = apps.findById(appId).orElseThrow(() -> ApiException.notFound("应用"));
        String rawTarget = body.path("targetWorkspaceId").asText("").trim();
        if (rawTarget.isEmpty()) {
            throw ApiException.validation("请选择目标接收工作空间或接手人工号");
        }
        String targetWs = resolveOrProvisionWorkspace(rawTarget);
        String fromWs = app.workspaceId();
        if (fromWs.equals(targetWs)) {
            throw ApiException.conflict("SAME_WORKSPACE", "目标工作空间与当前归属空间相同，无需转移");
        }

        boolean includeConv = !body.has("includeConversation") || body.path("includeConversation").asBoolean(true);
        boolean includeDs = !body.has("includeDataSources") || body.path("includeDataSources").asBoolean(true);
        String note = clip(body.path("note").asText("").trim(), 200);

        List<String> unboundSources = transferSingleAppResources(appId, fromWs, targetWs, includeConv, includeDs);

        String fromOwner = workspaceOwnerLabel(fromWs);
        String toOwner = workspaceOwnerLabel(targetWs);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", app.name());
        detail.put("slug", app.slug());
        detail.put("fromWorkspaceId", fromWs);
        detail.put("toWorkspaceId", targetWs);
        detail.put("fromOwner", fromOwner);
        detail.put("toOwner", toOwner);
        detail.put("includeConversation", includeConv);
        detail.put("includeDataSources", includeDs);
        if (!unboundSources.isEmpty()) detail.put("unboundPrivateDataSources", unboundSources);
        if (!note.isEmpty()) detail.put("note", note);
        audit(actor, "APP_TRANSFER", "app", appId, detail);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", appId);
        out.put("name", app.name());
        out.put("fromWorkspaceId", fromWs);
        out.put("toWorkspaceId", targetWs);
        out.put("fromOwner", fromOwner);
        out.put("toOwner", toOwner);
        out.put("transferred", true);
        if (!unboundSources.isEmpty()) out.put("unboundDataSources", unboundSources);
        return out;
    }

    /** 人员离职 / 空间资产批量交接：支持整空间全量交接或勾选指定应用批量交接（支持传 workspaceId、userId 或 SSO 工号） */
    @Transactional
    public Map<String, Object> handoverWorkspace(JsonNode body, String actor) {
        String rawFrom = body.path("fromWorkspaceId").asText("").trim();
        String rawTo = body.path("toWorkspaceId").asText("").trim();
        if (rawFrom.isEmpty() || rawTo.isEmpty()) {
            throw ApiException.validation("请同时选择交出空间与接收空间");
        }
        String fromWs = resolveOrProvisionWorkspace(rawFrom);
        String toWs = resolveOrProvisionWorkspace(rawTo);
        if (fromWs.equals(toWs)) {
            throw ApiException.conflict("SAME_WORKSPACE", "交出空间与接收空间不能相同");
        }

        boolean includeTrash = body.path("includeTrash").asBoolean(false);
        boolean includeConversations = !body.has("includeConversations") || body.path("includeConversations").asBoolean(true);
        boolean includeDataSources = !body.has("includeDataSources") || body.path("includeDataSources").asBoolean(true);
        String note = clip(body.path("note").asText("").trim(), 200);

        Set<String> filterAppIds = new LinkedHashSet<>();
        if (body.has("appIds") && body.get("appIds").isArray()) {
            for (JsonNode n : body.get("appIds")) {
                String aid = n.asText("").trim();
                if (!aid.isEmpty()) filterAppIds.add(aid);
            }
        }

        List<Map<String, String>> sourceApps = jdbc.sql("""
                select id, name, deleted_at::text as deleted_at
                from app
                where workspace_id = ?
                order by updated_at desc
                """).param(fromWs).query((rs, i) -> Map.of(
                "id", rs.getString("id"),
                "name", rs.getString("name"),
                "deleted", rs.getString("deleted_at") != null ? "true" : "false"
        )).list();

        List<String> movedAppIds = new ArrayList<>();
        List<String> movedAppNames = new ArrayList<>();
        List<String> unboundSources = new ArrayList<>();
        for (Map<String, String> row : sourceApps) {
            String aid = row.get("id");
            boolean isDeleted = "true".equals(row.get("deleted"));
            if (!filterAppIds.isEmpty()) {
                if (!filterAppIds.contains(aid)) continue;
            } else if (isDeleted && !includeTrash) {
                continue;
            }
            unboundSources.addAll(transferSingleAppResources(aid, fromWs, toWs, includeConversations, includeDataSources));
            movedAppIds.add(aid);
            movedAppNames.add(row.get("name"));
        }

        int movedStandaloneConvs = 0;
        if (includeConversations && filterAppIds.isEmpty()) {
            movedStandaloneConvs = jdbc.sql("update conversation set workspace_id = ? where workspace_id = ?")
                    .params(toWs, fromWs).update();
            jdbc.sql("update help_conversation set workspace_id = ? where workspace_id = ?")
                    .params(toWs, fromWs).update();
        }

        int movedDataSources = 0;
        if (includeDataSources && filterAppIds.isEmpty()) {
            movedDataSources = jdbc.sql("""
                    update data_source ds
                    set workspace_id = ?
                    where ds.workspace_id = ?
                      and ds.deleted_at is null
                      and not exists (
                          select 1 from data_source target_ds
                          where target_ds.workspace_id = ?
                            and target_ds.key = ds.key
                            and target_ds.deleted_at is null
                      )
                    """).params(toWs, fromWs, toWs).update();
        }

        // 全空间离职交接时，同步划转剩余独立素材、上传包与社区作品归属
        if (filterAppIds.isEmpty()) {
            jdbc.sql("update asset set workspace_id = ? where workspace_id = ?").params(toWs, fromWs).update();
            jdbc.sql("update upload set workspace_id = ? where workspace_id = ?").params(toWs, fromWs).update();
            jdbc.sql("update template set workspace_id = ? where workspace_id = ?").params(toWs, fromWs).update();
        }

        String fromOwner = workspaceOwnerLabel(fromWs);
        String toOwner = workspaceOwnerLabel(toWs);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromWorkspaceId", fromWs);
        detail.put("toWorkspaceId", toWs);
        detail.put("fromOwner", fromOwner);
        detail.put("toOwner", toOwner);
        detail.put("transferredAppsCount", movedAppIds.size());
        detail.put("appNames", movedAppNames.size() > 20 ? movedAppNames.subList(0, 20) : movedAppNames);
        detail.put("movedConversations", movedStandaloneConvs);
        detail.put("movedDataSources", movedDataSources);
        detail.put("includeTrash", includeTrash);
        if (!unboundSources.isEmpty()) detail.put("unboundPrivateDataSources", unboundSources);
        if (!note.isEmpty()) detail.put("note", note);
        audit(actor, "WORKSPACE_HANDOVER", "workspace", fromWs, detail);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fromWorkspaceId", fromWs);
        out.put("toWorkspaceId", toWs);
        out.put("fromOwner", fromOwner);
        out.put("toOwner", toOwner);
        out.put("transferredAppsCount", movedAppIds.size());
        out.put("movedConversations", movedStandaloneConvs);
        out.put("movedDataSources", movedDataSources);
        if (!unboundSources.isEmpty()) out.put("unboundDataSources", unboundSources);
        out.put("appNames", movedAppNames);
        return out;
    }

    /**
     * 解析目标空间标识：
     * 1. 若本身是已存在的 workspace.id（如 ws_xxx），直接返回；
     * 2. 若是 SSO 用户的 app_user.id 或 login_name（工号），查找其名下主工作空间；若该员工尚未初始化空间，则自动为其创建并绑定空间。
     */
    private String resolveOrProvisionWorkspace(String ref) {
        long wsExists = jdbc.sql("select count(*) from workspace where id = ?").param(ref).query(Long.class).single();
        if (wsExists > 0) return ref;

        var userOpt = jdbc.sql("""
                select id from app_user
                where id = ? or lower(login_name) = lower(?)
                order by last_login_at desc
                limit 1
                """).params(ref, ref).query(String.class).optional();
        if (userOpt.isPresent()) {
            String uid = userOpt.get();
            var ownedWs = jdbc.sql("select id from workspace where owner_user_id = ? order by created_at asc limit 1")
                    .param(uid).query(String.class).optional();
            if (ownedWs.isPresent()) return ownedWs.get();

            String newWsId = Ids.newId("ws");
            String token = Ids.token();
            jdbc.sql("insert into workspace (id, token_hash, owner_user_id) values (?, ?, ?)")
                    .params(newWsId, Ids.sha256(token), uid).update();
            return newWsId;
        }
        throw ApiException.notFound("工作空间或员工账号 (" + ref + ")");
    }

    private List<String> transferSingleAppResources(String appId, String fromWs, String toWs, boolean includeConv, boolean includeDs) {
        long activeTasks = jdbc.sql("""
                select count(*) from task
                where app_id = ? and status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')
                """).param(appId).query(Long.class).single();
        if (activeTasks > 0) {
            throw ApiException.conflict("TASK_ACTIVE", "应用仍有正在运行或排队的任务，请等待完成或先停止任务后再执行交接");
        }

        jdbc.sql("update app set workspace_id = ?, updated_at = now() where id = ?")
                .params(toWs, appId).update();
        jdbc.sql("""
                update task
                set workspace_id = ?,
                    idempotency_key = case
                        when idempotency_key is not null and idempotency_key not like (id || ':%')
                        then id || ':' || idempotency_key
                        else idempotency_key
                    end
                where app_id = ?
                """).params(toWs, appId).update();
        jdbc.sql("update task_message_queue set workspace_id = ? where app_id = ?")
                .params(toWs, appId).update();

        // 迁移直接挂载在 app_id 下、或通过 app.asset_ids / message.asset_ids 关联的素材
        jdbc.sql("""
                update asset
                set workspace_id = ?, app_id = coalesce(app_id, ?)
                where app_id = ?
                   or id in (
                       select jsonb_array_elements_text(coalesce(a.asset_ids, '[]'::jsonb))
                       from app a where a.id = ?
                   )
                   or id in (
                       select jsonb_array_elements_text(coalesce(m.asset_ids, '[]'::jsonb))
                       from message m where m.app_id = ? and m.asset_ids is not null
                   )
                """).params(toWs, appId, appId, appId, appId).update();

        if (includeConv) {
            jdbc.sql("""
                    update asset
                    set workspace_id = ?
                    where id in (
                        select jsonb_array_elements_text(coalesce(cm.asset_ids, '[]'::jsonb))
                        from conversation_message cm
                        join app_conversation ac on ac.conversation_id = cm.conversation_id
                        where ac.app_id = ? and cm.asset_ids is not null
                    )
                    """).params(toWs, appId).update();
            jdbc.sql("""
                    update conversation
                    set workspace_id = ?
                    where id in (select conversation_id from app_conversation where app_id = ?)
                    """).params(toWs, appId).update();
        }

        // 私有数据源权限边界控制：
        // 1) 仅当 includeDs=true 时，才将仅被本应用独占且目标空间无同名 key 的私有源随应用迁走；
        // 2) 对于未能迁走的源空间私有数据源（如未勾选迁移、被原空间其他应用共享、或目标空间存在同名冲突），
        //    必须解除该应用对原空间私有源的绑定，严禁接收方应用继续持有原空间凭证。
        if (includeDs) {
            jdbc.sql("""
                    update data_source ds
                    set workspace_id = ?
                    where ds.workspace_id = ?
                      and ds.deleted_at is null
                      and ds.id in (select source_id from app_data_source where app_id = ?)
                      and not exists (
                          select 1 from app_data_source ads
                          join app other_app on other_app.id = ads.app_id
                          where ads.source_id = ds.id
                            and ads.app_id <> ?
                            and other_app.workspace_id = ?
                            and other_app.deleted_at is null
                      )
                      and not exists (
                          select 1 from data_source target_ds
                          where target_ds.workspace_id = ?
                            and target_ds.key = ds.key
                            and target_ds.deleted_at is null
                      )
                    """).params(toWs, fromWs, appId, appId, fromWs, toWs).update();
        }

        List<String> remainingPrivateKeys = jdbc.sql("""
                select ds.key
                from app_data_source ads
                join data_source ds on ds.id = ads.source_id
                where ads.app_id = ? and ds.workspace_id = ?
                """).params(appId, fromWs).query(String.class).list();
        if (!remainingPrivateKeys.isEmpty()) {
            jdbc.sql("""
                    delete from app_data_source ads
                    using data_source ds
                    where ads.source_id = ds.id
                      and ads.app_id = ?
                      and ds.workspace_id = ?
                    """).params(appId, fromWs).update();
        }
        return remainingPrivateKeys;
    }

    /** 彻底永久清除回收站中的应用及其全部关联版本、任务与云存储资源 */
    public Map<String, Object> purgeDeletedApp(String id, String actor) {
        AppRow app = apps.findById(id).orElseThrow(() -> ApiException.notFound("应用"));
        if (app.deletedAt() == null) {
            throw ApiException.conflict("APP_NOT_IN_TRASH", "仅允许彻底清除已移入回收站的应用，请先执行软删除");
        }
        long activeTasks = jdbc.sql("""
                select count(*) from task
                where app_id = ? and status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')
                """).param(id).query(Long.class).single();
        if (activeTasks > 0) {
            throw ApiException.conflict("TASK_ACTIVE", "该应用仍有运行中的任务，无法彻底清除");
        }

        var cloudRow = jdbc.sql("select schema_name, storage_prefix from app_cloud where app_id = ?")
                .param(id)
                .query((rs, i) -> Map.of(
                        "schemaName", rs.getString("schema_name") == null ? "" : rs.getString("schema_name"),
                        "storagePrefix", rs.getString("storage_prefix") == null ? "" : rs.getString("storage_prefix")))
                .optional();

        // 在删除 version 前收集该应用引用的独立 build 记录，随后清理无其他应用引用的孤儿 build 与对象存储预览/发布产物
        List<String> candidateBuildIds = jdbc.sql("""
                select distinct build_id from version
                where app_id = ? and build_id is not null
                """).param(id).query(String.class).list();

        if (app.isService() && monolithService != null) {
            monolithService.destroyForApp(app, true);
        }

        jdbc.sql("delete from task_message_queue where app_id = ?").param(id).update();
        jdbc.sql("delete from draft where app_id = ?").param(id).update();
        jdbc.sql("delete from message where app_id = ?").param(id).update();
        jdbc.sql("delete from task_event where task_id in (select id from task where app_id = ?)").param(id).update();
        jdbc.sql("delete from checkpoint where task_id in (select id from task where app_id = ?)").param(id).update();
        jdbc.sql("delete from task_token where task_id in (select id from task where app_id = ?)").param(id).update();
        jdbc.sql("delete from model_usage where task_id in (select id from task where app_id = ?)").param(id).update();
        jdbc.sql("delete from deployment where app_id = ?").param(id).update();
        jdbc.sql("delete from version where app_id = ?").param(id).update();
        jdbc.sql("delete from task where app_id = ?").param(id).update();
        jdbc.sql("delete from skill_publication where app_id = ?").param(id).update();
        jdbc.sql("delete from data_source_call where app_id = ?").param(id).update();
        jdbc.sql("delete from app_cloud_table where app_id = ?").param(id).update();
        jdbc.sql("delete from app_cloud where app_id = ?").param(id).update();
        jdbc.sql("delete from app_pageview_daily where app_id = ?").param(id).update();
        jdbc.sql("delete from asset where app_id = ?").param(id).update();
        jdbc.sql("delete from app where id = ?").param(id).update();

        List<String> cleanupWarnings = new ArrayList<>();
        int purgedBuilds = 0;
        for (String bid : candidateBuildIds) {
            long refs = jdbc.sql("select count(*) from version where build_id = ?").param(bid).query(Long.class).single();
            if (refs > 0) continue;
            var buildRow = jdbc.sql("select preview_prefix, publish_prefix from build where id = ?")
                    .param(bid)
                    .query((rs, i) -> Map.of(
                            "preview", rs.getString("preview_prefix") == null ? "" : rs.getString("preview_prefix"),
                            "publish", rs.getString("publish_prefix") == null ? "" : rs.getString("publish_prefix")))
                    .optional();
            jdbc.sql("delete from build where id = ?").param(bid).update();
            purgedBuilds++;
            if (buildRow.isPresent()) {
                for (String pfx : List.of(buildRow.get().get("preview"), buildRow.get().get("publish"))) {
                    if (pfx != null && !pfx.isBlank()) {
                        try { store.deletePrefix(pfx.endsWith("/") ? pfx : pfx + "/"); }
                        catch (Exception ex) { cleanupWarnings.add("build对象清理失败(" + pfx + "): " + ex.getMessage()); }
                    }
                }
            }
        }

        if (cloudRow.isPresent()) {
            String schema = cloudRow.get().get("schemaName");
            if (schema != null && schema.matches("^app_[a-z0-9_]+$")) {
                try {
                    jdbc.sql("drop schema if exists " + schema + " cascade").update();
                } catch (Exception ex) {
                    cleanupWarnings.add("云数据库schema清理失败(" + schema + "): " + ex.getMessage());
                }
            }
            String prefix = cloudRow.get().get("storagePrefix");
            if (prefix != null && !prefix.isBlank()) {
                try { store.deletePrefix(prefix); }
                catch (Exception ex) { cleanupWarnings.add("云存储清理失败(" + prefix + "): " + ex.getMessage()); }
            }
        }
        try { store.deletePrefix("apps/" + id + "/"); }
        catch (Exception ex) { cleanupWarnings.add("应用存储清理失败: " + ex.getMessage()); }
        if (git != null) {
            try { git.deleteRepo(id); }
            catch (Exception ex) { cleanupWarnings.add("Git仓库清理失败: " + ex.getMessage()); }
        }

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("name", app.name());
        auditDetail.put("workspaceId", app.workspaceId());
        auditDetail.put("purgedBuilds", purgedBuilds);
        if (!cleanupWarnings.isEmpty()) auditDetail.put("cleanupWarnings", cleanupWarnings);
        audit(actor, "APP_PURGE", "app", id, auditDetail);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", id);
        res.put("name", app.name());
        res.put("purged", true);
        res.put("purgedBuilds", purgedBuilds);
        if (!cleanupWarnings.isEmpty()) res.put("cleanupWarnings", cleanupWarnings);
        return res;
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

    private String workspaceOwnerLabel(String wsId) {
        return jdbc.sql("""
                select u.real_name, u.login_name
                from workspace w
                left join app_user u on u.id = w.owner_user_id
                where w.id = ?
                """).param(wsId).query((rs, i) -> {
            String real = rs.getString("real_name");
            String login = rs.getString("login_name");
            boolean hasReal = real != null && !real.isBlank();
            boolean hasLogin = login != null && !login.isBlank();
            if (hasReal && hasLogin && !real.trim().equalsIgnoreCase(login.trim())) {
                return real.trim() + " (" + login.trim() + ")";
            }
            if (hasReal) return real.trim();
            if (hasLogin) return login.trim();
            return wsId;
        }).optional().orElse(wsId);
    }

    private static String displayName(String realName, String loginName, String fallback) {
        if (realName != null && !realName.isBlank()) return realName.trim();
        if (loginName != null && !loginName.isBlank()) return loginName.trim();
        return fallback;
    }
}
