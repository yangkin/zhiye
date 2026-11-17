package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.config.AuthProperties;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.TaskRepository;
import com.tcl.csot.zhiye.service.admin.AdminAuthService;
import com.tcl.csot.zhiye.service.admin.AdminService;
import com.tcl.csot.zhiye.service.app.AppService;
import com.tcl.csot.zhiye.service.app.Views;
import com.tcl.csot.zhiye.service.task.TaskService;
import com.tcl.csot.zhiye.service.workspace.QuotaService;
import com.tcl.csot.zhiye.storage.ObjectStore;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import com.tcl.csot.zhiye.util.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 平台管理侧治理与运维接口测试：配额覆盖/生效/重置、应用下线/删除/恢复、任务强停/重跑、社区精选/下架、反馈分诊、限流清理、审计日志及管理员白名单校验。 */
@TestPropertySource(properties = "zhiye.admin.token=test-admin-token")
class AdminGovernanceTest extends IntegrationTest {
    private static final Map<String, String> ADMIN_HEADERS = Map.of("X-Zhiye-Admin-Token", "test-admin-token");
    @Autowired JdbcClient jdbc;
    @Autowired QuotaService quota;
    @Autowired ZhiyeProperties props;
    @Autowired AppRepository apps;
    @Autowired TaskRepository tasks;
    @Autowired AppService appService;
    @Autowired TaskService taskService;
    @Autowired Views views;
    @Autowired ObjectStore store;

    @Test
    void governanceLifecycleAndAuditTrail() {
        Client c = client();
        JsonNode app = c.createApp("治理测试应用");
        String appId = app.path("id").asText();
        String wsId = c.ok(c.get("/api/workspace")).path("id").asText();

        // 生成 + 发布
        JsonNode gen = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of()));
        String genTaskId = gen.path("id").asText();
        c.waitTask(genTaskId);
        c.ok(c.post("/api/apps/" + appId + "/publish", Map.of()));
        assertThat(c.ok(c.get("/api/apps/" + appId)).path("publishedVersionId").asText("")).isNotBlank();

        // 未携带管理员凭证时必须拒绝（Fail-Closed 403）
        assertThat(c.get("/api/admin/overview").status()).isEqualTo(403);
        assertThat(c.get("/api/dashboard?scope=platform").status()).isEqualTo(403);

        // 1. 配额覆盖与生效校验：将当前工作空间 maxApps 降为 1，触发 APP_LIMIT 409；再重置回默认，允许创建第 2 个应用
        JsonNode qSet = c.ok(c.send("PUT", "/api/admin/workspaces/" + wsId + "/quota",
                Map.of("maxApps", 1, "dailyTaskLimit", 12, "maxConcurrentTasks", 2, "note", "测试配额收紧"), ADMIN_HEADERS));
        assertThat(qSet.path("maxApps").asInt()).isEqualTo(1);
        assertThat(qSet.path("custom").asBoolean()).isTrue();
        assertThat(c.ok(c.get("/api/dashboard?scope=workspace")).path("overview").path("appsLimit").asInt()).isEqualTo(1);

        Client.Res blockedCreate = c.post("/api/apps", Map.of("type", "开发网站", "prompt", "超出配额的应用", "mode", "快速", "model", "auto", "assetIds", List.of()));
        assertThat(blockedCreate.status()).isEqualTo(409);
        assertThat(blockedCreate.code()).isEqualTo("APP_LIMIT");

        JsonNode qReset = c.ok(c.send("PUT", "/api/admin/workspaces/" + wsId + "/quota",
                Map.of("reset", true), ADMIN_HEADERS));
        assertThat(qReset.path("custom").asBoolean()).isFalse();
        JsonNode app2 = c.createApp("恢复默认后新建应用");
        String app2Id = app2.path("id").asText();

        // 2. 应用强行下线、软删除、回收站恢复
        JsonNode unpub = c.ok(c.send("POST", "/api/admin/apps/" + appId + "/unpublish", Map.of(), ADMIN_HEADERS));
        assertThat(unpub.path("published").asBoolean()).isFalse();
        assertThat(c.ok(c.get("/api/apps/" + appId)).path("publishedVersionId").isNull()).isTrue();

        JsonNode del = c.ok(c.send("DELETE", "/api/admin/apps/" + appId, null, ADMIN_HEADERS));
        assertThat(del.path("deleted").asBoolean()).isTrue();
        assertThat(c.get("/api/apps/" + appId).status()).isEqualTo(404);

        JsonNode midOverview = c.ok(c.send("GET", "/api/admin/overview", null, ADMIN_HEADERS));
        assertThat(midOverview.path("deletedApps").toString()).contains(appId);

        JsonNode restored = c.ok(c.send("POST", "/api/admin/apps/" + appId + "/restore", Map.of(), ADMIN_HEADERS));
        assertThat(restored.path("restored").asBoolean()).isTrue();
        assertThat(c.ok(c.get("/api/apps/" + appId)).path("id").asText()).isEqualTo(appId);

        // 3. 任务强行停止 & 失败任务重跑
        String queuedId = Ids.newId("tsk");
        jdbc.sql("""
                insert into task (id, workspace_id, app_id, type, status, input, phase_label, progress)
                values (?, ?, ?, 'modify', 'QUEUED', '{}'::jsonb, '排队中', 0)
                """).params(queuedId, wsId, app2Id).update();
        JsonNode cancelled = c.ok(c.send("POST", "/api/admin/tasks/" + queuedId + "/cancel", Map.of(), ADMIN_HEADERS));
        assertThat(cancelled.path("status").asText()).isIn("CANCELLED", "CANCELLING");

        jdbc.sql("update task set status = 'FAILED', error_code = 'TEST_ERR', error_message = '模拟失败', finished_at = now() where id = ?")
                .param(genTaskId).update();
        JsonNode retried = c.ok(c.send("POST", "/api/admin/tasks/" + genTaskId + "/retry", Map.of(), ADMIN_HEADERS));
        assertThat(retried.path("id").asText()).isNotEqualTo(genTaskId);
        c.waitTask(retried.path("id").asText());

        // 4. 社区作品精选置顶与管理员移除
        JsonNode tpl = c.ok(c.post("/api/templates", Map.of("appId", appId, "name", "治理模板", "description", "测试精选与移除")));
        String tplId = tpl.path("id").asText();
        JsonNode featured = c.ok(c.send("POST", "/api/admin/templates/" + tplId + "/feature",
                Map.of("featured", true, "pinnedOrder", 88), ADMIN_HEADERS));
        assertThat(featured.path("featured").asBoolean()).isTrue();
        assertThat(featured.path("pinnedOrder").asInt()).isEqualTo(88);
        assertThat(c.ok(c.get("/api/templates/" + tplId)).path("featured").asBoolean()).isTrue();

        JsonNode tplDel = c.ok(c.send("DELETE", "/api/admin/templates/" + tplId, null, ADMIN_HEADERS));
        assertThat(tplDel.path("deleted").asBoolean()).isTrue();
        assertThat(c.get("/api/templates/" + tplId).status()).isEqualTo(404);

        // 5. 反馈分诊状态与备注
        JsonNode fb = c.ok(c.post("/api/help/feedback", Map.of("content", "治理看板反馈分诊测试问题")));
        String fbId = fb.path("id").asText();
        JsonNode triaged = c.ok(c.send("PATCH", "/api/admin/feedbacks/" + fbId,
                Map.of("status", "resolved", "adminNote", "已在 v2.1 修复"), ADMIN_HEADERS));
        assertThat(triaged.path("status").asText()).isEqualTo("resolved");
        assertThat(triaged.path("adminNote").asText()).isEqualTo("已在 v2.1 修复");
        assertThat(triaged.path("resolvedAt").asText()).isNotBlank();

        // 非法状态返回 400
        assertThat(c.send("PATCH", "/api/admin/feedbacks/" + fbId, Map.of("status", "bad_state"), ADMIN_HEADERS).status()).isEqualTo(400);

        // 6. 限流清理与审计日志汇总
        jdbc.sql("insert into rate_limit_hit (bucket) values ('test-bucket')").update();
        JsonNode cleared = c.ok(c.send("POST", "/api/admin/rate-limits/clear", Map.of("bucket", "test-bucket"), ADMIN_HEADERS));
        assertThat(cleared.path("clearedCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode finalOverview = c.ok(c.send("GET", "/api/admin/overview", null, ADMIN_HEADERS));
        String auditJson = finalOverview.path("auditLogs").toString();
        assertThat(auditJson).contains("QUOTA_UPDATE", "QUOTA_RESET", "APP_UNPUBLISH", "APP_DELETE",
                "APP_RESTORE", "TASK_CANCEL", "TASK_RETRY", "TEMPLATE_FEATURE", "TEMPLATE_DELETE",
                "FEEDBACK_TRIAGE", "RATE_LIMIT_CLEAR");
    }

    @Test
    void ssoAdminAllowlistEnforced() {
        AuthProperties ssoAuth = new AuthProperties("sso", null);
        AdminService guarded = new AdminService(jdbc, quota, props, ssoAuth, apps, tasks, appService, taskService, views, store, "alice,bob");

        SsoUser alice = new SsoUser("u_adm_1", "sso1", "alice", "爱丽丝", "a@example.com", "t1");
        SsoUser bob = new SsoUser("u_adm_2", "sso2", "bob", "", "b@example.com", "t1");
        SsoUser charlie = new SsoUser("u_adm_3", "sso3", "charlie", "查理", "c@example.com", "t1");

        assertThat(guarded.requireActor(alice, "ws_1")).isEqualTo("爱丽丝 (alice)");
        assertThat(guarded.requireActor(bob, "ws_1")).isEqualTo("bob (bob)");
        assertThatThrownBy(() -> guarded.requireActor(charlie, "ws_1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("charlie");
        assertThatThrownBy(() -> guarded.requireActor((SsoUser) null, "ws_1"))
                .isInstanceOf(ApiException.class);

        // 白名单留空时默认严格关闭（Fail-Closed），只有数据库 role='admin' 的用户允许进入
        jdbc.sql("""
                insert into app_user (id, sso_user_id, login_name, real_name, role)
                values ('u_role_admin', 'sso_role_admin', 'dbadmin', '库管', 'admin'),
                       ('u_role_user', 'sso_role_user', 'normal_emp', '普通员工', 'user')
                on conflict (sso_user_id) do update set role = excluded.role
                """).update();
        AdminAuthService noAllowlistSso = new AdminAuthService(jdbc, ssoAuth, "", "", "");
        SsoUser dbAdmin = new SsoUser("u_role_admin", "sso_role_admin", "dbadmin", "库管", "", "t1");
        SsoUser normalEmp = new SsoUser("u_role_user", "sso_role_user", "normal_emp", "普通员工", "", "t1");
        assertThat(noAllowlistSso.requireAdminActor(dbAdmin, "ws_1")).isEqualTo("库管 (dbadmin)");
        assertThatThrownBy(() -> noAllowlistSso.requireAdminActor(normalEmp, "ws_1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("normal_emp");

        // 匿名模式下普通工作空间同样默认拒绝（Fail-Closed），仅显式加入工作空间白名单才放行
        AuthProperties anonAuth = new AuthProperties("anonymous", null);
        AdminAuthService anonGuard = new AdminAuthService(jdbc, anonAuth, "", "ws_trusted_admin", "");
        assertThat(anonGuard.requireAdminActor((SsoUser) null, "ws_trusted_admin")).contains("ws_trusted_admin");
        assertThatThrownBy(() -> anonGuard.requireAdminActor((SsoUser) null, "ws_random_guest"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("管理员权限");
    }
}
