package com.tcl.csot.zhiye.service.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.DraftRepository;
import com.tcl.csot.zhiye.repository.TaskRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.cloud.AccessService;
import com.tcl.csot.zhiye.service.ops.PlatformLogService;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 应用发布审核服务（Vibe Coding 应用登记表）：
 * - 用户发起发布时填写《Vibe Coding 应用登记表》（9 项必填 + 2 项选填），生成 pending 审核单；
 * - 管理员在平台管理控制台审批：通过后立即触发正式发布上线（含单体服务切流或静态/云应用发布）；驳回后原线上状态不受影响，用户可查看驳回意见并修改重提；
 * - 全流程写入 admin_audit_log 与 platform_log 不可篡改审计记录。
 */
@Service
public class PublishReviewService {
    private static final Set<String> VALID_STATUSES = Set.of("pending", "approved", "rejected", "cancelled");

    private final JdbcClient jdbc;
    private final AppRepository apps;
    private final VersionRepository versions;
    private final TaskRepository tasks;
    private final DraftRepository drafts;
    private final AccessService accessService;
    private final ZhiyeProperties props;
    private final Views views;
    private final AppService appService;

    @Autowired(required = false)
    private PlatformLogService platformLogs;
    @Autowired(required = false)
    private com.tcl.csot.zhiye.config.AuthProperties authProps;
    @Autowired(required = false)
    private com.tcl.csot.zhiye.service.admin.AdminAuthService adminAuth;

    public PublishReviewService(JdbcClient jdbc,
                                AppRepository apps,
                                VersionRepository versions,
                                TaskRepository tasks,
                                DraftRepository drafts,
                                AccessService accessService,
                                ZhiyeProperties props,
                                @Lazy Views views,
                                @Lazy AppService appService) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.versions = versions;
        this.tasks = tasks;
        this.drafts = drafts;
        this.accessService = accessService;
        this.props = props;
        this.views = views;
        this.appService = appService;
    }

    public boolean isReviewRequired() {
        return props.publish().reviewRequired();
    }

    public long pendingCount() {
        return jdbc.sql("select count(*) from publish_request where status = 'pending'").query(Long.class).single();
    }

    /** 供 Views.summary 附带当前应用的待审/最近审核状态摘要。 */
    public Map<String, Object> summaryForApp(String appId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                select id, version_id, version_number, status, review_comment, reviewer_actor, reviewed_at, created_at
                from publish_request
                where app_id = ?
                order by created_at desc
                limit 5
                """).param(appId).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("versionId", rs.getString("version_id"));
            m.put("versionNumber", rs.getInt("version_number"));
            m.put("status", rs.getString("status"));
            m.put("reviewComment", rs.getString("review_comment"));
            m.put("reviewerActor", rs.getString("reviewer_actor"));
            m.put("reviewedAt", Db.iso(Db.ts(rs, "reviewed_at")));
            m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return m;
        }).list();
        Map<String, Object> pending = rows.stream().filter(r -> "pending".equals(r.get("status"))).findFirst().orElse(null);
        Map<String, Object> latest = rows.isEmpty() ? null : rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pending", pending);
        out.put("latest", latest);
        return out;
    }

    /** 用户侧获取当前应用的审核状态、历史记录与登记表预填默认值。 */
    public Map<String, Object> listForApp(String ws, String appId, SsoUser user) {
        AppRow app = apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
        List<Map<String, Object>> history = jdbc.sql("""
                select * from publish_request
                where app_id = ? and workspace_id = ?
                order by created_at desc
                limit 20
                """).params(app.id(), ws).query((rs, i) -> mapRequest(rs)).list();
        enrichAccessMembers(history);

        Map<String, Object> pending = history.stream().filter(r -> "pending".equals(r.get("status"))).findFirst().orElse(null);
        Map<String, Object> latest = history.isEmpty() ? null : history.get(0);

        Map<String, Object> defaults = buildDefaults(app, latest, user);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviewRequired", isReviewRequired());
        out.put("currentPending", pending);
        out.put("latestRequest", latest);
        out.put("history", history);
        out.put("defaults", defaults);
        return out;
    }

    /** 提交《Vibe Coding 应用登记表》发起发布审核申请。 */
    public Map<String, Object> submit(String ws, String appId, JsonNode body, SsoUser user) {
        AppRow app = apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
        if (app.isSkill()) throw AppService.skillNotPublishable();
        if (app.isScript()) throw AppService.scriptNotPublishable();
        if (tasks.activeForApp(app.id()).isPresent()) throw ApiException.busy();
        if (drafts.exists(app.id())) throw ApiException.draftPending();

        String vid = body == null ? null : body.path("versionId").asText(null);
        if (vid == null || vid.isBlank()) vid = app.currentVersionId();
        if (vid == null || vid.isBlank()) throw ApiException.conflict("INVALID_PHASE", "请先完成应用生成后再提交发布申请");
        VersionRow ver = versions.find(vid).filter(v -> v.appId().equals(app.id()))
                .orElseThrow(() -> ApiException.notFound("版本"));

        // 静态/云应用前置校验构建产物与表权限，避免无效单流入管理员队列
        AccessService.Access acc = accessService.get(app.id());
        String accessLevel = acc.level();
        if (!app.isService()) {
            if (versions.build(ver.buildId()).isEmpty()) {
                throw ApiException.conflict("BUILD_MISSING", "当前版本缺少构建产物，无法提交发布申请");
            }
            List<String> needLogin = acc.needsLogin() ? List.of() : accessService.tablesNeedingLogin(app.id());
            if (!needLogin.isEmpty()) {
                throw ApiException.conflict("ACCESS_MISMATCH", "数据表 " + String.join("、", needLogin)
                        + " 的权限需要登录身份，请先在访问范围中改为「员工登录」后再提交发布审核");
            }
        }

        // 防止同应用并发提交多条 pending 申请
        long existingPending = jdbc.sql("select count(*) from publish_request where app_id = ? and status = 'pending'")
                .param(app.id()).query(Long.class).single();
        if (existingPending > 0) {
            throw ApiException.conflict("PUBLISH_REVIEW_PENDING", "该应用已有待审核的发布申请，请等待管理员审批或先撤回原申请");
        }

        JsonNode form = body != null && body.has("form") && body.get("form").isObject() ? body.get("form") : (body == null ? Json.obj() : body);
        String appName = requireField(form, "appName", "应用名称", 80);
        String department = requireField(form, "department", "应用所属部门", 120);
        String businessBg = requireField(form, "businessBg", "业务背景", 2000);
        String painPoints = optField(form, "painPoints", 2000);
        String solution = requireField(form, "solution", "解决方案", 3000);
        String expectedBenefit = requireField(form, "expectedBenefit", "预期收益", 2000);
        String targetUsers = requireField(form, "targetUsers", "预期用户与范围", 200);
        String deployMode = requireField(form, "deployMode", "部署方式", 100);
        String contacts = requireField(form, "contacts", "主备联系人", 200);
        String expectedUntil = requireDate(form, "expectedUntil", "预计使用周期");
        String techAndData = optField(form, "techAndData", 4000);

        Map<String, Object> formMap = new LinkedHashMap<>();
        formMap.put("appName", appName);
        formMap.put("department", department);
        formMap.put("businessBg", businessBg);
        formMap.put("painPoints", painPoints);
        formMap.put("solution", solution);
        formMap.put("expectedBenefit", expectedBenefit);
        formMap.put("targetUsers", targetUsers);
        formMap.put("deployMode", deployMode);
        formMap.put("contacts", contacts);
        formMap.put("expectedUntil", expectedUntil);
        formMap.put("techAndData", techAndData);
        formMap.put("accessLevel", accessLevel);
        formMap.put("accessMembers", acc.members());
        formMap.put("accessAdmins", acc.admins());

        String id = Ids.newId("preq");
        String submitterId = user != null ? user.id() : null;
        String submitterName = resolveSubmitterName(user, ws, contacts);

        jdbc.sql("""
                insert into publish_request (
                    id, app_id, workspace_id, version_id, version_number, access_level, status,
                    app_name, department, business_bg, pain_points, solution, expected_benefit,
                    target_users, deploy_mode, contacts, expected_until, tech_and_data, form_json,
                    submitter_id, submitter_name
                ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
                """).params(
                id, app.id(), ws, ver.id(), ver.number(), accessLevel, "pending",
                appName, department, businessBg, painPoints, solution, expectedBenefit,
                targetUsers, deployMode, contacts, expectedUntil, techAndData, Json.write(formMap),
                submitterId, submitterName
        ).update();

        if (platformLogs != null) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("requestId", id);
            meta.put("versionId", ver.id());
            meta.put("department", department);
            meta.put("contacts", contacts);
            meta.put("accessLevel", accessLevel);
            meta.put("accessMembers", acc.members());
            platformLogs.info("lifecycle", "PUBLISH_REVIEW_SUBMIT", ws, null, app.id(), null,
                    "提交发布审核申请（V" + ver.number() + " · " + appName + " · 部门：" + department + "）",
                    null, meta);
        }
        return getById(id);
    }

    /** 申请人撤回自己的待审核申请。 */
    public Map<String, Object> cancel(String ws, String appId, String reqId) {
        AppRow app = apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
        int updated = jdbc.sql("""
                update publish_request
                set status = 'cancelled', updated_at = now()
                where id = ? and app_id = ? and workspace_id = ? and status = 'pending'
                """).params(reqId, app.id(), ws).update();
        if (updated == 0) {
            throw ApiException.conflict("INVALID_STATE", "该发布申请不存在或已处理，无法撤回");
        }
        if (platformLogs != null) {
            platformLogs.info("lifecycle", "PUBLISH_REVIEW_CANCEL", ws, null, app.id(), null,
                    "撤回发布审核申请 " + reqId, null, Map.of("requestId", reqId));
        }
        return getById(reqId);
    }

    /** 管理员查看发布审核队列（支持按状态筛选与关键词搜索）。 */
    public Map<String, Object> adminList(String status, String q) {
        String st = status == null || status.isBlank() || "all".equalsIgnoreCase(status) ? null : status.trim().toLowerCase(Locale.ROOT);
        if (st != null && !VALID_STATUSES.contains(st)) {
            throw ApiException.validation("不支持的审核状态：" + status);
        }
        String kw = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);

        List<Map<String, Object>> all = jdbc.sql("""
                select pr.*, a.name as current_app_name, a.type as app_type, a.framework, a.project_kind,
                       a.slug, a.published_version_id, a.current_version_id,
                       v.build_id as version_build_id, v.title as version_title,
                       u.login_name as owner_login, u.real_name as owner_real_name
                from publish_request pr
                join app a on a.id = pr.app_id
                left join version v on v.id = pr.version_id
                left join workspace w on w.id = pr.workspace_id
                left join app_user u on u.id = w.owner_user_id
                order by case pr.status when 'pending' then 0 when 'rejected' then 1 when 'approved' then 2 else 3 end,
                         pr.created_at desc
                limit 200
                """).query((rs, i) -> {
            Map<String, Object> m = mapRequest(rs);
            String appId = rs.getString("app_id");
            String kind = rs.getString("project_kind");
            String slug = rs.getString("slug");
            String buildId = rs.getString("version_build_id");
            String pubVid = rs.getString("published_version_id");
            m.put("currentAppName", rs.getString("current_app_name"));
            m.put("appType", rs.getString("app_type"));
            m.put("framework", rs.getString("framework"));
            m.put("projectKind", kind);
            m.put("slug", slug);
            m.put("versionTitle", rs.getString("version_title"));
            m.put("isPublished", pubVid != null && !pubVid.isBlank());
            m.put("siteUrl", pubVid != null && !pubVid.isBlank() ? views.siteUrl(slug) : null);
            String previewUrl = null;
            if ("service".equals(kind)) {
                previewUrl = "/apps/" + appId;
            } else if (buildId != null && !buildId.isBlank()) {
                previewUrl = views.previewUrl(buildId);
            }
            m.put("previewUrl", previewUrl);
            m.put("ownerLogin", rs.getString("owner_login"));
            m.put("ownerRealName", rs.getString("owner_real_name"));
            return m;
        }).list();
        enrichAccessMembers(all);

        long pendingCount = all.stream().filter(x -> "pending".equals(x.get("status"))).count();
        long approvedCount = all.stream().filter(x -> "approved".equals(x.get("status"))).count();
        long rejectedCount = all.stream().filter(x -> "rejected".equals(x.get("status"))).count();
        long cancelledCount = all.stream().filter(x -> "cancelled".equals(x.get("status"))).count();

        List<Map<String, Object>> filtered = all.stream().filter(x -> {
            if (st != null && !st.equals(x.get("status"))) return false;
            if (kw.isEmpty()) return true;
            String hay = String.join(" ",
                    Objects.toString(x.get("appName"), ""),
                    Objects.toString(x.get("currentAppName"), ""),
                    Objects.toString(x.get("department"), ""),
                    Objects.toString(x.get("contacts"), ""),
                    Objects.toString(x.get("submitterName"), ""),
                    Objects.toString(x.get("businessBg"), ""),
                    Objects.toString(x.get("id"), ""),
                    memberSearchText(x)
            ).toLowerCase(Locale.ROOT);
            return hay.contains(kw);
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pendingCount", pendingCount);
        out.put("approvedCount", approvedCount);
        out.put("rejectedCount", rejectedCount);
        out.put("cancelledCount", cancelledCount);
        out.put("total", all.size());
        out.put("items", filtered);
        return out;
    }

    /** 管理员审核通过并立即正式发布上线。 */
    public Map<String, Object> adminApprove(String reqId, String comment, String actor) {
        Map<String, Object> existing = getById(reqId);
        if (!"pending".equals(existing.get("status"))) {
            throw ApiException.conflict("INVALID_STATE", "该申请当前状态为「" + existing.get("status") + "」，无法重复审批");
        }
        String ws = String.valueOf(existing.get("workspaceId"));
        String appId = String.valueOf(existing.get("appId"));
        String versionId = String.valueOf(existing.get("versionId"));

        // 审批通过前固化当前实时访问权限快照，确保历史审批记录与实际生效权限一致
        AccessService.Access liveAcc = accessService.get(appId);
        // 真正执行发布上线（支持静态/云应用与单体全栈服务）
        Map<String, Object> pubRes = appService.publishApproved(ws, appId, versionId);
        String siteUrl = Objects.toString(pubRes.get("siteUrl"), "");
        String cleanComment = comment == null ? "" : comment.trim();
        if (cleanComment.length() > 1000) cleanComment = cleanComment.substring(0, 1000);

        String accessPatchJson = Json.write(Map.of(
                "accessLevel", liveAcc.level(),
                "accessMembers", liveAcc.members(),
                "accessAdmins", liveAcc.admins()));

        jdbc.sql("""
                update publish_request
                set status = 'approved', reviewer_actor = ?, review_comment = ?, reviewed_at = now(),
                    published_site_url = ?, access_level = ?,
                    form_json = coalesce(form_json, '{}'::jsonb) || ?::jsonb,
                    updated_at = now()
                where id = ?
                """).params(actor, cleanComment, siteUrl, liveAcc.level(), accessPatchJson, reqId).update();

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("appId", appId);
        auditDetail.put("appName", Objects.toString(existing.get("appName"), ""));
        auditDetail.put("department", Objects.toString(existing.get("department"), ""));
        auditDetail.put("versionId", versionId);
        auditDetail.put("siteUrl", siteUrl);
        auditDetail.put("accessLevel", liveAcc.level());
        auditDetail.put("accessMembers", liveAcc.members());
        auditDetail.put("comment", cleanComment);
        audit(actor, "PUBLISH_APPROVE", "publish_request", reqId, auditDetail);

        if (platformLogs != null) {
            platformLogs.info("lifecycle", "APP_PUBLISH", ws, null, appId, null,
                    "管理员审核通过并正式发布（" + existing.get("appName") + " V" + existing.get("versionNumber") + " → " + siteUrl + "）",
                    null, Map.of("requestId", reqId, "actor", actor, "siteUrl", siteUrl, "versionId", versionId));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("request", getById(reqId));
        out.put("siteUrl", siteUrl);
        out.put("versionId", versionId);
        return out;
    }

    /** 管理员驳回发布申请（必须说明原因，不影响原有线上状态）。 */
    public Map<String, Object> adminReject(String reqId, String comment, String actor) {
        Map<String, Object> existing = getById(reqId);
        if (!"pending".equals(existing.get("status"))) {
            throw ApiException.conflict("INVALID_STATE", "该申请当前状态为「" + existing.get("status") + "」，无法驳回");
        }
        String cleanComment = comment == null ? "" : comment.trim();
        if (cleanComment.isEmpty()) {
            throw ApiException.validation("请填写驳回原因，便于申请人按意见修改后重新提交");
        }
        if (cleanComment.length() > 1000) cleanComment = cleanComment.substring(0, 1000);

        jdbc.sql("""
                update publish_request
                set status = 'rejected', reviewer_actor = ?, review_comment = ?, reviewed_at = now(), updated_at = now()
                where id = ?
                """).params(actor, cleanComment, reqId).update();

        String ws = String.valueOf(existing.get("workspaceId"));
        String appId = String.valueOf(existing.get("appId"));
        audit(actor, "PUBLISH_REJECT", "publish_request", reqId, Map.of(
                "appId", appId,
                "appName", Objects.toString(existing.get("appName"), ""),
                "department", Objects.toString(existing.get("department"), ""),
                "comment", cleanComment));

        if (platformLogs != null) {
            platformLogs.warn("lifecycle", "PUBLISH_REVIEW_REJECT", ws, null, appId, null,
                    null, null,
                    "管理员驳回发布申请（" + existing.get("appName") + " V" + existing.get("versionNumber") + "）：" + cleanComment,
                    null, Map.of("requestId", reqId, "actor", actor, "comment", cleanComment));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("request", getById(reqId));
        return out;
    }

    private Map<String, Object> getById(String id) {
        Map<String, Object> m = jdbc.sql("select * from publish_request where id = ?").param(id)
                .query((rs, i) -> mapRequest(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("发布审核申请"));
        enrichAccessMembers(List.of(m));
        return m;
    }

    /**
     * 为发布审核单补充「指定人员 / 管理员」完整名单与身份摘要：
     * - 待审核单（pending）实时对齐 app_site_access，避免作者审批前增减人员导致审核页看到的不是最终生效名单；
     * - 历史已处理单（approved / rejected / cancelled）优先保留 form_json 中的审批时快照，老数据缺失时兜底读实时配置；
     * - 批量反查 app_user 与 platform_login_allowlist，无 N+1 查询，并标记非平台白名单的「仅应用访客」员工。
     */
    private void enrichAccessMembers(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) return;
        Set<String> needLiveAppIds = new LinkedHashSet<>();
        Set<String> allAppIds = new LinkedHashSet<>();
        for (Map<String, Object> it : items) {
            String appId = Objects.toString(it.get("appId"), "");
            if (!appId.isBlank()) {
                allAppIds.add(appId);
                boolean pending = "pending".equals(it.get("status"));
                boolean hasSnap = Boolean.TRUE.equals(it.get("_hasAccessSnapshot"));
                if (pending || !hasSnap) needLiveAppIds.add(appId);
            }
        }
        Map<String, AccessService.Access> liveAccessByApp = new HashMap<>();
        if (!needLiveAppIds.isEmpty()) {
            jdbc.sql("select app_id, level, members, admins from app_site_access where app_id in (:ids)")
                    .param("ids", List.copyOf(needLiveAppIds))
                    .query(rs -> {
                        String aid = rs.getString("app_id");
                        List<String> m = Json.read(rs.getString("members"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                        List<String> a = Json.read(rs.getString("admins"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                        liveAccessByApp.put(aid, new AccessService.Access(rs.getString("level"), m == null ? List.of() : m, a == null ? List.of() : a));
                    });
        }
        Map<String, Map<String, Object>> authorByApp = new HashMap<>();
        if (!allAppIds.isEmpty()) {
            jdbc.sql("""
                    select a.id as app_id, u.login_name, u.real_name
                    from app a
                    join workspace w on w.id = a.workspace_id
                    join app_user u on u.id = w.owner_user_id
                    where a.id in (:ids)
                    """).param("ids", List.copyOf(allAppIds)).query(rs -> {
                String login = rs.getString("login_name");
                if (login != null && !login.isBlank()) {
                    Map<String, Object> au = new LinkedHashMap<>();
                    au.put("loginName", login);
                    au.put("realName", rs.getString("real_name"));
                    authorByApp.put(rs.getString("app_id"), au);
                }
            });
        }

        Set<String> allLowerLogins = new LinkedHashSet<>();
        for (Map<String, Object> it : items) {
            String appId = Objects.toString(it.get("appId"), "");
            boolean pending = "pending".equals(it.get("status"));
            boolean hasSnap = Boolean.TRUE.equals(it.get("_hasAccessSnapshot"));
            List<String> members;
            List<String> admins;
            if (pending || !hasSnap) {
                AccessService.Access live = liveAccessByApp.get(appId);
                if (live != null) {
                    if (pending) it.put("accessLevel", live.level());
                    members = live.members();
                    admins = live.admins();
                } else {
                    members = stringList(it.get("_snapshotMembers"));
                    admins = stringList(it.get("_snapshotAdmins"));
                }
            } else {
                members = stringList(it.get("_snapshotMembers"));
                admins = stringList(it.get("_snapshotAdmins"));
            }
            it.put("accessMembers", members);
            it.put("accessAdmins", admins);
            it.put("accessAuthor", authorByApp.get(appId));
            it.remove("_hasAccessSnapshot");
            it.remove("_snapshotMembers");
            it.remove("_snapshotAdmins");
            for (String l : members) allLowerLogins.add(l.toLowerCase(Locale.ROOT));
            for (String l : admins) allLowerLogins.add(l.toLowerCase(Locale.ROOT));
        }

        boolean allowlistActive = authProps != null && authProps.ssoEnabled() && authProps.loginAllowlistEnabled();
        Map<String, String> realNameByLower = new HashMap<>();
        Set<String> adminUserLowers = new HashSet<>();
        Set<String> allowlistedLowers = new HashSet<>();
        if (!allLowerLogins.isEmpty()) {
            List<String> lowers = List.copyOf(allLowerLogins);
            jdbc.sql("select lower(login_name) as k, real_name, role from app_user where lower(login_name) in (:logins)")
                    .param("logins", lowers).query(rs -> {
                String k = rs.getString("k");
                String rn = rs.getString("real_name");
                if (rn != null && !rn.isBlank()) realNameByLower.put(k, rn);
                if ("admin".equals(rs.getString("role"))) adminUserLowers.add(k);
            });
            jdbc.sql("select lower(login_name) as k, real_name, enabled from platform_login_allowlist where lower(login_name) in (:logins)")
                    .param("logins", lowers).query(rs -> {
                String k = rs.getString("k");
                String rn = rs.getString("real_name");
                if (rn != null && !rn.isBlank()) realNameByLower.putIfAbsent(k, rn);
                if (rs.getBoolean("enabled")) allowlistedLowers.add(k);
            });
        }

        for (Map<String, Object> it : items) {
            it.put("accessMemberDetails", buildMemberDetails(stringList(it.get("accessMembers")), realNameByLower, allowlistActive, allowlistedLowers, adminUserLowers));
            it.put("accessAdminDetails", buildMemberDetails(stringList(it.get("accessAdmins")), realNameByLower, allowlistActive, allowlistedLowers, adminUserLowers));
        }
    }

    private List<Map<String, Object>> buildMemberDetails(List<String> logins, Map<String, String> realNameByLower,
                                                         boolean allowlistActive, Set<String> allowlistedLowers, Set<String> adminUserLowers) {
        if (logins == null || logins.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(logins.size());
        for (String login : logins) {
            String lower = login.toLowerCase(Locale.ROOT);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("loginName", login);
            String rn = realNameByLower.get(lower);
            d.put("realName", rn != null && !rn.isBlank() ? rn : null);
            if (allowlistActive) {
                boolean allowed = allowlistedLowers.contains(lower)
                        || adminUserLowers.contains(lower)
                        || (adminAuth != null && adminAuth.isAllowlistedLogin(login));
                d.put("inPlatformAllowlist", allowed);
            }
            out.add(d);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object obj) {
        if (!(obj instanceof List<?> raw) || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(raw.size());
        for (Object x : raw) {
            if (x != null && !String.valueOf(x).isBlank()) out.add(String.valueOf(x));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String memberSearchText(Map<String, Object> item) {
        StringBuilder sb = new StringBuilder();
        for (String m : stringList(item.get("accessMembers"))) sb.append(' ').append(m);
        for (String a : stringList(item.get("accessAdmins"))) sb.append(' ').append(a);
        Object details = item.get("accessMemberDetails");
        if (details instanceof List<?> list) {
            for (Object d : list) {
                if (d instanceof Map<?, ?> dm && dm.get("realName") != null) sb.append(' ').append(dm.get("realName"));
            }
        }
        return sb.toString();
    }

    private Map<String, Object> buildDefaults(AppRow app, Map<String, Object> latest, SsoUser user) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("appName", latest != null && nonBlank(latest.get("appName")) ? latest.get("appName") : app.name());
        d.put("department", latest != null ? Objects.toString(latest.get("department"), "") : "");
        d.put("businessBg", latest != null ? Objects.toString(latest.get("businessBg"), "") : "");
        d.put("painPoints", latest != null ? Objects.toString(latest.get("painPoints"), "") : "");
        d.put("solution", latest != null ? Objects.toString(latest.get("solution"), "") : "");
        d.put("expectedBenefit", latest != null ? Objects.toString(latest.get("expectedBenefit"), "") : "");
        d.put("targetUsers", latest != null && nonBlank(latest.get("targetUsers")) ? latest.get("targetUsers") : "个人使用");
        d.put("deployMode", latest != null && nonBlank(latest.get("deployMode")) ? latest.get("deployMode") : "托管部署");
        String defaultContact = userDisplayName(user);
        d.put("contacts", latest != null && nonBlank(latest.get("contacts")) ? latest.get("contacts") : defaultContact);
        String defaultDate = LocalDate.now().plusYears(1).toString();
        d.put("expectedUntil", latest != null && nonBlank(latest.get("expectedUntil")) ? latest.get("expectedUntil") : defaultDate);
        d.put("techAndData", latest != null ? Objects.toString(latest.get("techAndData"), "") : "");
        d.put("suggestedTechAndData", buildSuggestedTechAndData(app));
        return d;
    }

    private String buildSuggestedTechAndData(AppRow app) {
        List<String> parts = new ArrayList<>();
        parts.add("应用形态：" + app.type() + "（" + (app.framework() != null && !app.framework().isBlank() ? app.framework() : app.projectKind()) + "）");
        if (app.isService()) {
            Boolean pgOn = jdbc.sql("select pg_enabled from app_service where app_id = ?").param(app.id())
                    .query(Boolean.class).optional().orElse(false);
            parts.add("运行与存储：单体容器/隔离进程托管" + (Boolean.TRUE.equals(pgOn) ? " + 独立托管 PostgreSQL Schema" : " + /data 本地持久化"));
        } else {
            Boolean cloudOn = jdbc.sql("select exists(select 1 from app_cloud where app_id = ? and status = 'on')")
                    .param(app.id()).query(Boolean.class).single();
            parts.add("运行与存储：静态前端站点" + (Boolean.TRUE.equals(cloudOn) ? " + 平台云数据库与 Deno 云函数" : "（无后端数据库）"));
        }
        List<String> dsNames = jdbc.sql("""
                select ds.name from app_data_source ads
                join data_source ds on ds.id = ads.source_id
                where ads.app_id = ?
                """).param(app.id()).query(String.class).list();
        if (!dsNames.isEmpty()) {
            parts.add("已绑定数据源：" + String.join("、", dsNames));
        }
        return String.join("\n", parts);
    }

    private static String userDisplayName(SsoUser user) {
        if (user == null) return "";
        if (user.realName() != null && !user.realName().isBlank()) return user.realName();
        return user.loginName() == null ? "" : user.loginName();
    }

    private String resolveSubmitterName(SsoUser user, String ws, String fallbackContacts) {
        String uName = userDisplayName(user);
        if (!uName.isBlank()) return uName;
        Optional<String> owner = jdbc.sql("""
                select coalesce(nullif(u.real_name,''), nullif(u.login_name,''))
                from workspace w join app_user u on u.id = w.owner_user_id
                where w.id = ?
                """).param(ws).query(String.class).optional();
        return owner.filter(s -> !s.isBlank()).orElse(fallbackContacts);
    }

    private static Map<String, Object> mapRequest(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("appId", rs.getString("app_id"));
        m.put("workspaceId", rs.getString("workspace_id"));
        m.put("versionId", rs.getString("version_id"));
        m.put("versionNumber", rs.getInt("version_number"));
        m.put("accessLevel", rs.getString("access_level"));
        m.put("status", rs.getString("status"));
        m.put("appName", rs.getString("app_name"));
        m.put("department", rs.getString("department"));
        m.put("businessBg", rs.getString("business_bg"));
        m.put("painPoints", rs.getString("pain_points"));
        m.put("solution", rs.getString("solution"));
        m.put("expectedBenefit", rs.getString("expected_benefit"));
        m.put("targetUsers", rs.getString("target_users"));
        m.put("deployMode", rs.getString("deploy_mode"));
        m.put("contacts", rs.getString("contacts"));
        m.put("expectedUntil", rs.getString("expected_until"));
        m.put("techAndData", rs.getString("tech_and_data"));
        m.put("submitterId", rs.getString("submitter_id"));
        m.put("submitterName", rs.getString("submitter_name"));
        m.put("reviewerActor", rs.getString("reviewer_actor"));
        m.put("reviewComment", rs.getString("review_comment"));
        m.put("reviewedAt", Db.iso(Db.ts(rs, "reviewed_at")));
        m.put("publishedSiteUrl", rs.getString("published_site_url"));
        m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
        m.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
        String formJson = rs.getString("form_json");
        if (formJson != null && !formJson.isBlank()) {
            try {
                JsonNode fj = Json.M.readTree(formJson);
                if (fj != null && fj.has("accessMembers")) {
                    m.put("_hasAccessSnapshot", true);
                    m.put("_snapshotMembers", jsonStringList(fj.get("accessMembers")));
                    m.put("_snapshotAdmins", jsonStringList(fj.get("accessAdmins")));
                }
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static List<String> jsonStringList(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            if (n.isTextual() && !n.asText().isBlank()) out.add(n.asText());
        }
        return out;
    }

    private static String requireField(JsonNode form, String key, String label, int maxLen) {
        String v = form == null ? "" : form.path(key).asText("").trim();
        if (v.isEmpty()) throw ApiException.validation("请填写必填项「" + label + "」");
        if (v.length() > maxLen) throw ApiException.validation("「" + label + "」最多 " + maxLen + " 字");
        return v;
    }

    private static String optField(JsonNode form, String key, int maxLen) {
        String v = form == null ? "" : form.path(key).asText("").trim();
        if (v.length() > maxLen) throw ApiException.validation("选填说明内容过长（最多 " + maxLen + " 字）");
        return v;
    }

    private static String requireDate(JsonNode form, String key, String label) {
        String v = form == null ? "" : form.path(key).asText("").trim();
        if (v.isEmpty()) throw ApiException.validation("请选择必填项「" + label + "」");
        try {
            return LocalDate.parse(v).toString();
        } catch (DateTimeParseException e) {
            throw ApiException.validation("「" + label + "」日期格式无效，请使用 YYYY-MM-DD");
        }
    }

    private static boolean nonBlank(Object v) {
        return v != null && !String.valueOf(v).isBlank();
    }

    private void audit(String actor, String action, String targetType, String targetId, Map<String, Object> detail) {
        jdbc.sql("""
                insert into admin_audit_log (id, actor, action, target_type, target_id, detail)
                values (?, ?, ?, ?, ?, ?::jsonb)
                """).params(Ids.newId("aud"), actor, action, targetType, targetId, Json.write(detail == null ? Map.of() : detail)).update();
    }
}
