package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.cloud.DatabaseQuotaService;
import com.tcl.csot.zhiye.service.runtime.ServiceCrypto;
import com.tcl.csot.zhiye.service.runtime.ServiceDatabaseService;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import com.tcl.csot.zhiye.util.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三层数据库容量治理与防刷保护集成测试（直连真实 PostgreSQL）：
 * 1) 共享云服务：单字段/单行体积上限、单表行数上限、删除/TRUNCATE 自助恢复、按应用写入速率限流；
 * 2) 全栈托管 PG：隔离角色连接数与超时参数加固、内核级限写（禁 INSERT/UPDATE/CREATE 但保留 SELECT/DELETE/TRUNCATE）与恢复；
 * 3) 管理治理面：全平台 Schema 巡检排行、工作空间与单应用配额优先级覆写、一键限写/解封及审计日志留痕。
 */
@TestPropertySource(properties = {
        "zhiye.admin.token=test-db-quota-token",
        "zhiye.database-quota.cloud-max-rows-per-table=4",
        "zhiye.database-quota.cloud-max-row-bytes=2048",
        "zhiye.database-quota.cloud-max-field-bytes=1024",
        "zhiye.database-quota.cloud-writes-per-second=6",
        "zhiye.database-quota.cloud-writes-per-minute=50",
        "zhiye.database-quota.service-role-connection-limit=10",
        "zhiye.database-quota.service-statement-timeout-ms=15000",
        "zhiye.database-quota.service-idle-tx-timeout-ms=30000"
})
class DatabaseQuotaProtectionTest extends IntegrationTest {
    private static final Map<String, String> ADMIN = Map.of("X-Zhiye-Admin-Token", "test-db-quota-token");

    @Autowired JdbcClient jdbc;
    @Autowired DatabaseQuotaService dbQuota;
    @Autowired ServiceDatabaseService serviceDb;
    @Autowired ServiceCrypto crypto;

    @Test
    void layer1CloudLimitsAndLayer3AdminGovernance() {
        Client c = client();
        JsonNode app = c.createApp("数据库防刷测试应用");
        String appId = app.path("id").asText();
        String wsId = c.ok(c.get("/api/workspace")).path("id").asText();

        // 启用共享云服务（自动建默认 notes 表）
        c.ok(c.post("/api/apps/" + appId + "/cloud/enable", Map.of()));
        JsonNode ov = c.ok(c.get("/api/apps/" + appId + "/cloud/console"));
        assertThat(ov.path("dbQuota").path("quotaMb").asInt()).isEqualTo(200);
        assertThat(ov.path("dbQuota").path("maxRowsPerTable").asInt()).isEqualTo(4);
        assertThat(ov.path("dbQuota").path("maxRowBytes").asInt()).isEqualTo(2048);
        assertThat(ov.path("dbQuota").path("writeBlocked").asBoolean()).isFalse();

        // 1a. 单字段超限（> 1024 B）拦截 413
        String tooBigField = "x".repeat(1200);
        Client.Res fieldReject = c.post("/api/apps/" + appId + "/cloud/console/notes/rows",
                Map.of("title", "t1", "content", tooBigField));
        assertThat(fieldReject.status()).isEqualTo(413);
        assertThat(fieldReject.code()).isEqualTo("CLOUD_ROW_TOO_LARGE");

        // 1b. 单行总 JSON 超限（> 2048 B，但单字段 < 1024 B）拦截 413 — 正常先验证单行不超限写入
        dbQuota.clearWriteRateLimits(appId);

        // 1c. 单表行数上限（测试配额设为 4 行）
        List<Long> rowIds = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            JsonNode row = c.ok(c.post("/api/apps/" + appId + "/cloud/console/notes/rows",
                    Map.of("title", "row-" + i, "content", "ok-" + i)));
            rowIds.add(row.path("id").asLong());
        }
        Client.Res fifth = c.post("/api/apps/" + appId + "/cloud/console/notes/rows",
                Map.of("title", "row-5", "content", "should-fail"));
        assertThat(fifth.status()).isEqualTo(409);
        assertThat(fifth.code()).isEqualTo("CLOUD_TABLE_ROW_LIMIT");

        // 删除 1 行后允许再写 1 行（确保删除始终可用）
        dbQuota.clearWriteRateLimits(appId);
        assertThat(c.send("DELETE", "/api/apps/" + appId + "/cloud/console/notes/rows/" + rowIds.get(0), null, Map.of()).status())
                .isEqualTo(204);
        c.ok(c.post("/api/apps/" + appId + "/cloud/console/notes/rows",
                Map.of("title", "row-5-ok", "content", "after-delete")));

        // 批量导入超过单表上限整批拒绝（不产生部分写入）
        dbQuota.clearWriteRateLimits(appId);
        Client.Res badImport = c.post("/api/apps/" + appId + "/cloud/console/notes/import",
                Map.of("rows", List.of(Map.of("title", "b1", "content", "c1"))));
        assertThat(badImport.status()).isEqualTo(409);
        assertThat(badImport.code()).isEqualTo("CLOUD_TABLE_ROW_LIMIT");

        // 1d. 清空数据使用 TRUNCATE 立刻释放表空间，清空后可重新写入
        JsonNode cleared = c.ok(c.post("/api/apps/" + appId + "/cloud/console/notes/clear", Map.of()));
        assertThat(cleared.path("deleted").asLong()).isEqualTo(4L);
        dbQuota.clearWriteRateLimits(appId);
        c.ok(c.post("/api/apps/" + appId + "/cloud/console/notes/rows",
                Map.of("title", "after-truncate", "content", "clean")));

        // 1e. 按应用写入速率限流（每秒上限 6 次）
        dbQuota.clearWriteRateLimits(appId);
        for (int i = 0; i < 6; i++) {
            c.post("/api/apps/" + appId + "/cloud/console/notes/rows", Map.of("title", "r" + i, "content", "c"));
        }
        Client.Res throttled = c.post("/api/apps/" + appId + "/cloud/console/notes/rows",
                Map.of("title", "too-fast", "content", "c"));
        assertThat(throttled.status()).isEqualTo(429);
        assertThat(throttled.code()).isEqualTo("CLOUD_WRITE_RATE_LIMITED");
        // 管理员清理限流窗口可立即恢复
        c.ok(c.send("POST", "/api/admin/rate-limits/clear", Map.of(), ADMIN));

        // 3a. 管理员非授权访问必须被拒绝
        assertThat(c.get("/api/admin/database-usage").status()).isEqualTo(403);

        // 3b. 工作空间级容量配额覆写 -> 单应用级配额覆写 -> 重置逐级回落
        JsonNode wsQuota = c.ok(c.send("PUT", "/api/admin/workspaces/" + wsId + "/quota",
                Map.of("cloudDbQuotaMb", 350, "serviceDbQuotaMb", 800, "note", "部门专项扩容"), ADMIN));
        assertThat(wsQuota.path("cloudDbQuotaMb").asInt()).isEqualTo(350);
        assertThat(wsQuota.path("serviceDbQuotaMb").asInt()).isEqualTo(800);
        assertThat(c.ok(c.get("/api/apps/" + appId + "/cloud/console")).path("dbQuota").path("quotaMb").asInt())
                .isEqualTo(350);

        JsonNode appQuota = c.ok(c.send("PUT", "/api/admin/apps/" + appId + "/db-quota",
                Map.of("quotaMb", 600), ADMIN));
        assertThat(appQuota.path("quotaMb").asInt()).isEqualTo(600);
        assertThat(c.ok(c.get("/api/apps/" + appId + "/cloud/console")).path("dbQuota").path("quotaMb").asInt())
                .isEqualTo(600);

        // 3c. 管理员一键限写（禁止新增/更新，但保留查询与删除） -> 再恢复写入
        JsonNode blocked = c.ok(c.send("PUT", "/api/admin/apps/" + appId + "/db-quota",
                Map.of("writeBlocked", true, "reason", "测试异常刷库临时限写"), ADMIN));
        assertThat(blocked.path("writeBlocked").asBoolean()).isTrue();
        assertThat(blocked.path("status").asText()).isEqualTo("manual_blocked");

        Client.Res blockedInsert = c.post("/api/apps/" + appId + "/cloud/console/notes/rows",
                Map.of("title", "blocked", "content", "nope"));
        assertThat(blockedInsert.status()).isEqualTo(403);
        assertThat(blockedInsert.code()).isEqualTo("CLOUD_WRITE_BLOCKED");

        // 限写状态下查询与清空数据仍可用
        JsonNode listWhileBlocked = c.ok(c.get("/api/apps/" + appId + "/cloud/console/notes/rows"));
        assertThat(listWhileBlocked.path("rows").isArray()).isTrue();
        c.ok(c.post("/api/apps/" + appId + "/cloud/console/notes/clear", Map.of()));

        // 恢复写入 + 重置配额
        JsonNode unblocked = c.ok(c.send("PUT", "/api/admin/apps/" + appId + "/db-quota",
                Map.of("writeBlocked", false, "resetQuota", true), ADMIN));
        assertThat(unblocked.path("writeBlocked").asBoolean()).isFalse();
        assertThat(unblocked.path("quotaMb").asInt()).isEqualTo(350); // 回落到工作空间覆写

        dbQuota.clearWriteRateLimits(appId);
        c.ok(c.post("/api/apps/" + appId + "/cloud/console/notes/rows",
                Map.of("title", "recovered", "content", "ok")));

        // 3d. 全平台扫描与排名接口 + 审计日志留痕
        JsonNode scan = c.ok(c.send("POST", "/api/admin/database-usage/scan", Map.of(), ADMIN));
        assertThat(scan.path("summary").path("totalApps").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(scan.path("items").toString()).contains(appId);

        JsonNode overview = c.ok(c.send("GET", "/api/admin/overview", null, ADMIN));
        String audits = overview.path("auditLogs").toString();
        assertThat(audits).contains("DB_QUOTA_UPDATE", "DB_WRITE_BLOCK", "DB_WRITE_UNBLOCK");
    }

    @Test
    void layer2HostedServiceRoleHardeningAndKernelWriteBlock() throws Exception {
        String fakeAppId = Ids.newId("app");
        ServiceDatabaseService.ProvisionedDb db = serviceDb.ensureSchema(fakeAppId, null, null, null);
        String plainPass = crypto.decrypt(db.passwordEnc());
        try {
            // 2a. 验证隔离角色已施加连接数上限与超时硬参数
            Integer connLimit = jdbc.sql("select rolconnlimit from pg_roles where rolname = ?")
                    .param(db.role()).query(Integer.class).single();
            assertThat(connLimit).isEqualTo(10);

            String rolConfig = jdbc.sql("select coalesce(array_to_string(rolconfig, ','), '') from pg_roles where rolname = ?")
                    .param(db.role()).query(String.class).single();
            assertThat(rolConfig).contains("statement_timeout=15000ms");
            assertThat(rolConfig).contains("idle_in_transaction_session_timeout=30000ms");

            // 2b. 以隔离角色直连 PostgreSQL 建表并写入
            try (Connection conn = DriverManager.getConnection(db.jdbcUrl(), db.role(), plainPass);
                 Statement st = conn.createStatement()) {
                st.execute("create table items (id serial primary key, val text)");
                st.execute("insert into items (val) values ('hello')");
            }

            // 2c. 触发内核级限写：禁止 INSERT / UPDATE / CREATE TABLE，但保留 SELECT / DELETE / TRUNCATE
            serviceDb.enforceWriteBlock(db.schema(), db.role(), true);

            try (Connection conn = DriverManager.getConnection(db.jdbcUrl(), db.role(), plainPass);
                 Statement st = conn.createStatement()) {
                assertThatThrownBy(() -> st.execute("insert into items (val) values ('blocked')"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> st.execute("update items set val = 'hacked'"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> st.execute("create table rogue_tbl (id int)"))
                        .isInstanceOf(SQLException.class);
                // 查询、删除与清空仍被显式保留，允许清理空间自救
                try (var rs = st.executeQuery("select count(*) from items")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
                st.execute("delete from items");
                st.execute("truncate table items");
            }

            // 2d. 解封后立刻恢复 INSERT / UPDATE / CREATE TABLE
            serviceDb.enforceWriteBlock(db.schema(), db.role(), false);
            try (Connection conn = DriverManager.getConnection(db.jdbcUrl(), db.role(), plainPass);
                 Statement st = conn.createStatement()) {
                st.execute("insert into items (val) values ('restored')");
                st.execute("create table items_v2 (id serial primary key)");
            }
        } finally {
            serviceDb.dropSchemaAndRole(db.schema(), db.role());
        }
    }
}
