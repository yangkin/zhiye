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
import com.tcl.csot.zhiye.service.ops.ClusterSandboxService;
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
import org.springframework.test.util.ReflectionTestUtils;

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
        if (!"CANCELLED".equals(cancelled.path("status").asText())) {
            c.waitTask(queuedId);
        }

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

        // 6. 站点访问风控、回收站彻底清除、角色授权、离线节点清理与超时租约回收
        JsonNode accessUpdated = c.ok(c.send("PATCH", "/api/admin/apps/" + appId + "/access",
                Map.of("level", "login"), ADMIN_HEADERS));
        assertThat(accessUpdated.path("accessLevel").asText()).isEqualTo("login");
        assertThat(c.send("PATCH", "/api/admin/apps/" + appId + "/access", Map.of("level", "invalid"), ADMIN_HEADERS).status()).isEqualTo(400);

        // 未软删除前禁止直接 purge（409 APP_NOT_IN_TRASH），软删除后可彻底清除
        assertThat(c.send("DELETE", "/api/admin/apps/" + app2Id + "/purge", null, ADMIN_HEADERS).status()).isEqualTo(409);
        c.ok(c.send("DELETE", "/api/admin/apps/" + app2Id, null, ADMIN_HEADERS));
        JsonNode purged = c.ok(c.send("DELETE", "/api/admin/apps/" + app2Id + "/purge", null, ADMIN_HEADERS));
        assertThat(purged.path("purged").asBoolean()).isTrue();
        assertThat(c.send("POST", "/api/admin/apps/" + app2Id + "/restore", Map.of(), ADMIN_HEADERS).status()).isEqualTo(404);

        // SSO 账号管理员角色授予与撤销
        String testUserId = Ids.newId("usr");
        jdbc.sql("""
                insert into app_user (id, sso_user_id, login_name, real_name, role)
                values (?, ?, 'ops_candidate', '候选管理员', 'user')
                """).params(testUserId, "sso_" + testUserId).update();
        JsonNode promoted = c.ok(c.send("PATCH", "/api/admin/users/" + testUserId + "/role",
                Map.of("role", "admin"), ADMIN_HEADERS));
        assertThat(promoted.path("role").asText()).isEqualTo("admin");
        JsonNode demoted = c.ok(c.send("PATCH", "/api/admin/users/" + testUserId + "/role",
                Map.of("role", "user"), ADMIN_HEADERS));
        assertThat(demoted.path("role").asText()).isEqualTo("user");

        // 离线集群节点清理（在线节点拒绝 409，离线节点成功删除）
        jdbc.sql("""
                insert into cluster_node (instance_id, role, hostname, port, status, heartbeat_at)
                values ('node-online-guard', 'api', 'h1', 8080, 'online', now()),
                       ('node-offline-stale', 'api', 'h2', 8080, 'offline', now() - interval '10 minutes')
                on conflict (instance_id) do update set status = excluded.status, heartbeat_at = excluded.heartbeat_at
                """).update();
        assertThat(c.send("DELETE", "/api/admin/cluster/nodes?instanceId=node-online-guard", null, ADMIN_HEADERS).status()).isEqualTo(409);
        JsonNode nodePurged = c.ok(c.send("DELETE", "/api/admin/cluster/nodes?instanceId=node-offline-stale", null, ADMIN_HEADERS));
        assertThat(nodePurged.path("purgedCount").asInt()).isEqualTo(1);
        jdbc.sql("delete from cluster_node where instance_id = 'node-online-guard'").update();

        // 超时任务租约一键回收
        String expiredTaskId = Ids.newId("tsk");
        jdbc.sql("""
                insert into task (id, workspace_id, app_id, type, status, input, phase_label, progress, lease_owner, lease_until)
                values (?, ?, ?, 'modify', 'RUNNING', '{}'::jsonb, '编写代码中', 35, 'dead-worker', now() - interval '5 minutes')
                """).params(expiredTaskId, wsId, appId).update();
        JsonNode reclaimed = c.ok(c.send("POST", "/api/admin/cluster/reclaim-leases", Map.of(), ADMIN_HEADERS));
        assertThat(reclaimed.path("reclaimedCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(reclaimed.path("taskIds").toString()).contains(expiredTaskId);

        // 7. 应用归属转移与人员离职整空间批量交接（支持直接传 SSO 登录工号自动初始化接收空间）
        JsonNode transferred = c.ok(c.send("POST", "/api/admin/apps/" + appId + "/transfer",
                Map.of("targetWorkspaceId", "ops_candidate", "note", "单应用移交测试"), ADMIN_HEADERS));
        assertThat(transferred.path("transferred").asBoolean()).isTrue();
        String candidateWsId = transferred.path("toWorkspaceId").asText();
        assertThat(candidateWsId).startsWith("ws_");
        assertThat(jdbc.sql("select workspace_id from app where id = ?").param(appId).query(String.class).single())
                .isEqualTo(candidateWsId);

        // 整空间离职批量交接回原空间 wsId
        JsonNode handedOver = c.ok(c.send("POST", "/api/admin/workspaces/handover",
                Map.of(
                        "fromWorkspaceId", candidateWsId,
                        "toWorkspaceId", wsId,
                        "includeConversations", true,
                        "includeDataSources", true,
                        "note", "离职整空间交接测试"
                ), ADMIN_HEADERS));
        assertThat(handedOver.path("transferredAppsCount").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("select workspace_id from app where id = ?").param(appId).query(String.class).single())
                .isEqualTo(wsId);

        // 8. 限流清理与审计日志汇总
        jdbc.sql("insert into rate_limit_hit (bucket) values ('test-bucket')").update();
        JsonNode cleared = c.ok(c.send("POST", "/api/admin/rate-limits/clear", Map.of("bucket", "test-bucket"), ADMIN_HEADERS));
        assertThat(cleared.path("clearedCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode finalOverview = c.ok(c.send("GET", "/api/admin/overview", null, ADMIN_HEADERS));
        assertThat(finalOverview.path("users").toString()).contains("ops_candidate");
        assertThat(finalOverview.path("allApps").isArray()).isTrue();
        assertThat(finalOverview.path("workspaces").isArray()).isTrue();
        String auditJson = finalOverview.path("auditLogs").toString();
        assertThat(auditJson).contains("QUOTA_UPDATE", "QUOTA_RESET", "APP_UNPUBLISH", "APP_DELETE",
                "APP_RESTORE", "APP_PURGE", "SITE_ACCESS_UPDATE", "ADMIN_ROLE_UPDATE",
                "APP_TRANSFER", "WORKSPACE_HANDOVER",
                "CLUSTER_NODE_PURGE", "TASK_LEASE_RECLAIM", "TASK_CANCEL", "TASK_RETRY",
                "TEMPLATE_FEATURE", "TEMPLATE_DELETE", "FEEDBACK_TRIAGE", "RATE_LIMIT_CLEAR");
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

    @Test
    void clusterTopologyAndSandboxReconciliationAndDestroyGuard() throws Exception {
        Client guest = client();
        Client c = guest;
        JsonNode app = guest.createApp("集群沙箱演练应用");
        String appId = app.path("id").asText();
        String wsId = guest.ok(guest.get("/api/workspace")).path("id").asText();

        // 非管理员访问集群拓扑 / 沙箱运维接口一律 403
        assertThat(guest.get("/api/admin/cluster").status()).isEqualTo(403);
        assertThat(guest.get("/api/admin/sandboxes").status()).isEqualTo(403);
        assertThat(guest.post("/api/admin/sandboxes/reap", Map.of()).status()).isEqualTo(403);
        assertThat(guest.send("DELETE", "/api/admin/sandboxes/sbx_any", null, Map.of()).status()).isEqualTo(403);

        // 构造一个已失联副本下的“账面幽灵沙箱”，以及一个关联进行中任务的活跃沙箱
        jdbc.sql("""
                insert into cluster_node (instance_id, role, hostname, port, sandbox_provider, worker_concurrency, status, heartbeat_at)
                values ('dead-replica-1', 'api', 'node-dead', 8080, 'local', 4, 'online', now() - interval '10 minutes')
                on conflict (instance_id) do update set heartbeat_at = excluded.heartbeat_at
                """).update();

        String activeTaskId = "task_sbx_guard_" + System.nanoTime();
        Process dummyProc = new ProcessBuilder("sleep", "60").start();
        try {
            JsonNode preCluster = c.ok(c.send("GET", "/api/admin/cluster", null, ADMIN_HEADERS));
            String currentReplica = preCluster.path("sandboxes").path("replicaId").asText();
            assertThat(currentReplica).isNotBlank();

            jdbc.sql("""
                    insert into task (id, app_id, workspace_id, type, status, phase_label, progress, lease_owner, lease_until)
                    values (?, ?, ?, 'generate', 'RUNNING', '正在编写代码', 40, 'worker-live', now() + interval '5 minutes')
                    """).params(activeTaskId, appId, wsId).update();

            jdbc.sql("""
                    insert into sandbox_instance (sandbox_id, provider, task_id, app_id, workspace_id, owner_instance, state, external_ref, pid, startup_ms)
                    values ('sbx_ghost_1', 'local', null, ?, ?, 'dead-replica-1', 'active', 'pid-dead-1', null, 350),
                           ('sbx_remote_1', 'local', null, ?, ?, 'other-host-replica', 'active', 'pid-remote-1', 99999, 410),
                           ('sbx_running_1', 'local', ?, ?, ?, ?, 'active', 'pid-live-1', ?, 510)
                    on conflict (sandbox_id) do update set state = excluded.state, task_id = excluded.task_id,
                      owner_instance = excluded.owner_instance, pid = excluded.pid
                    """).params(appId, wsId, appId, wsId, activeTaskId, appId, wsId, currentReplica, dummyProc.pid()).update();

            JsonNode cluster = c.ok(c.send("GET", "/api/admin/cluster", null, ADMIN_HEADERS));
            assertThat(cluster.path("nodes").size()).isGreaterThanOrEqualTo(1);
            assertThat(cluster.path("nodes").toString()).contains("dead-replica-1");
            assertThat(cluster.path("queue").path("running").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(cluster.path("sandboxes").path("summary").path("ghostCount").asLong()).isGreaterThanOrEqualTo(1);

            // 1. 跨机本地 PID 沙箱严禁强杀 -> 409 CONFLICT 且不污染账本（F05）
            var crossHost = c.send("DELETE", "/api/admin/sandboxes/sbx_remote_1?force=true", null, ADMIN_HEADERS);
            assertThat(crossHost.status()).isEqualTo(409);
            assertThat(jdbc.sql("select state from sandbox_instance where sandbox_id = 'sbx_remote_1'").query(String.class).single())
                    .isEqualTo("active");

            // 2. 进行中任务的沙箱，未传 force=true -> 409 冲突保护
            var conflict = c.send("DELETE", "/api/admin/sandboxes/sbx_running_1", null, ADMIN_HEADERS);
            assertThat(conflict.status()).isEqualTo(409);

            // 3. 本节点活跃本地沙箱 force=true 强行销毁 -> 真杀进程返回 destroyed 且取消关联任务（F04）
            JsonNode forced = c.ok(c.send("DELETE", "/api/admin/sandboxes/sbx_running_1?force=true&reason=qa-force-kill", null, ADMIN_HEADERS));
            assertThat(forced.path("status").asText()).isEqualTo("destroyed");
            assertThat(forced.path("killedRuntime").asBoolean()).isTrue();
            assertThat(forced.path("cancelledTask").asBoolean()).isTrue();
            assertThat(jdbc.sql("select state from sandbox_instance where sandbox_id = 'sbx_running_1'").query(String.class).single())
                    .isEqualTo("destroyed");

            // 4. 一键对账清理幽灵账本行
            JsonNode reaped = c.ok(c.send("POST", "/api/admin/sandboxes/reap", Map.of(), ADMIN_HEADERS));
            assertThat(reaped.path("ghostsSettled").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.sql("select state from sandbox_instance where sandbox_id = 'sbx_ghost_1'").query(String.class).single())
                    .isEqualTo("destroyed");

            JsonNode auditOverview = c.ok(c.send("GET", "/api/admin/overview", null, ADMIN_HEADERS));
            assertThat(auditOverview.path("auditLogs").toString()).contains("SANDBOX_DESTROY", "SANDBOX_REAP");
        } finally {
            dummyProc.destroyForcibly();
            jdbc.sql("delete from sandbox_instance where sandbox_id = 'sbx_remote_1'").update();
            jdbc.sql("update task set status = 'CANCELLED', finished_at = now(), lease_owner = null, lease_until = null where id = ?")
                    .param(activeTaskId).update();
        }
    }

    @Test
    void openSandboxKillFailureDoesNotFalseDestroyAndIncompleteControlPlaneBlocksReap() {
        var mockOs = org.mockito.Mockito.mock(com.alibaba.opensandbox.sandbox.SandboxManager.class);
        ClusterSandboxService mocked = new ClusterSandboxService(jdbc, props,
                new org.springframework.mock.env.MockEnvironment(), null);
        ReflectionTestUtils.setField(mocked, "openSandboxManager", mockOs);

        Client guest = client();
        JsonNode app = guest.createApp("OpenSandbox对账防护测试");
        String appId = app.path("id").asText();
        String wsId = guest.ok(guest.get("/api/workspace")).path("id").asText();
        String sbxId = "sbx_os_fail_" + System.nanoTime();
        String sbxGhostId = "sbx_os_404_" + System.nanoTime();

        try {
            jdbc.sql("""
                    insert into sandbox_instance (sandbox_id, provider, app_id, workspace_id, owner_instance, state, external_ref)
                    values (?, 'opensandbox', ?, ?, 'other-pod-1', 'active', 'os-ref-fail'),
                           (?, 'opensandbox', ?, ?, 'other-pod-1', 'active', 'os-ref-404')
                    """).params(sbxId, appId, wsId, sbxGhostId, appId, wsId).update();

            // F04: 控制面 kill 抛 502/网络异常时，严禁把账本标成 destroyed 且不得返回 ok:true
            org.mockito.Mockito.doThrow(new RuntimeException("control-plane timeout"))
                    .when(mockOs).killSandbox("os-ref-fail");
            assertThatThrownBy(() -> mocked.destroySandbox(sbxId, true, "admin", "test"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("控制面销毁沙箱失败");
            assertThat(jdbc.sql("select state from sandbox_instance where sandbox_id = ?").param(sbxId).query(String.class).single())
                    .isEqualTo("active");

            // 控制面明确 404 不存在时，核销账本且返回 reconcile_settled 区别于真销毁
            var ex404 = org.mockito.Mockito.mock(com.alibaba.opensandbox.sandbox.domain.exceptions.SandboxApiException.class);
            org.mockito.Mockito.when(ex404.getStatusCode()).thenReturn(404);
            org.mockito.Mockito.doThrow(ex404)
                    .when(mockOs).killSandbox("os-ref-404");
            Map<String, Object> settled = mocked.destroySandbox(sbxGhostId, true, "admin", "test");
            assertThat(settled.get("status")).isEqualTo("reconcile_settled");
            assertThat(settled.get("killedRuntime")).isEqualTo(false);

            // F06: 控制面列表多页分页时完整读取第 1、2 页，不把第 2 页的活跃沙箱误判为孤儿/幽灵；不可达时阻止对账
            var infoP1 = org.mockito.Mockito.mock(com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo.class);
            org.mockito.Mockito.when(infoP1.getId()).thenReturn("os-orphan-p1");
            var infoP2 = org.mockito.Mockito.mock(com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo.class);
            org.mockito.Mockito.when(infoP2.getId()).thenReturn("os-ref-fail");
            var paged1 = new com.alibaba.opensandbox.sandbox.domain.models.sandboxes.PagedSandboxInfos(
                    List.of(infoP1), new com.alibaba.opensandbox.sandbox.domain.models.sandboxes.PaginationInfo(1, 200, 2, 2, true));
            var paged2 = new com.alibaba.opensandbox.sandbox.domain.models.sandboxes.PagedSandboxInfos(
                    List.of(infoP2), new com.alibaba.opensandbox.sandbox.domain.models.sandboxes.PaginationInfo(2, 200, 2, 2, false));
            org.mockito.Mockito.when(mockOs.listSandboxInfos(org.mockito.Mockito.any())).thenReturn(paged1, paged2);
            Map<String, Object> multiPage = mocked.listSandboxes(null, null, null, null, false);
            @SuppressWarnings("unchecked")
            Map<String, Object> multiSum = (Map<String, Object>) multiPage.get("summary");
            assertThat(multiSum.get("controlPlaneComplete")).isEqualTo(true);
            assertThat(multiSum.get("unregisteredCount")).isEqualTo(1L);
            assertThat(multiSum.get("ghostCount")).isEqualTo(0L);

            org.mockito.Mockito.when(mockOs.listSandboxInfos(org.mockito.Mockito.any()))
                    .thenThrow(new RuntimeException("connection refused"));
            assertThatThrownBy(() -> mocked.reapOrphansAndGhosts("admin"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("已阻止批量对账清理");
        } finally {
            jdbc.sql("delete from sandbox_instance where sandbox_id in (?, ?)").params(sbxId, sbxGhostId).update();
        }
    }
}
