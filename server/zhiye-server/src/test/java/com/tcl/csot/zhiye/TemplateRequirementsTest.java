package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.template.TemplateRequirementsService;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 灵感社区依赖清单脱敏提取、私密文件剥离、与复刻后工作台实时配置体检测试：
 * 1. 验证敏感文件判断规则（剥离 .env / .env.local / *.pem / *.key，保留 .env.example）；
 * 2. 验证发布包含 AGW 数据源 + 私有飞书连接器引用 + 本地 .env 私密文件的应用时：
 *    - 模板 requirements 仅记录脱敏元数据（绝不含 URL、Token、Webhook、密钥值或私有连接器名）；
 *    - 复刻出的源码 zip 中不包含 .env 文件，绝不泄露作者密钥；
 * 3. 验证另一工作空间复刻后，GET /api/apps/{id}/setup-check 实时检测缺口并随用户配置动态收敛：
 *    - 未有同名 AGW 数据源 → open_sources；导入同名 AGW 数据源 → bind_source；绑定后 → ready；
 *    - 未有飞书连接器 → open_ecosystem；配置自己的飞书连接器后 → rebind_connector（附本人连接器 ID）。
 */
class TemplateRequirementsTest extends IntegrationTest {

    @Autowired JdbcClient jdbc;

    @Test
    void sensitiveTemplateFileFilterStripsSecretsAndKeepsExamples() {
        assertThat(TemplateRequirementsService.isSensitiveTemplateFile(".env")).isTrue();
        assertThat(TemplateRequirementsService.isSensitiveTemplateFile("backend/.env.local")).isTrue();
        assertThat(TemplateRequirementsService.isSensitiveTemplateFile(".env.production")).isTrue();
        assertThat(TemplateRequirementsService.isSensitiveTemplateFile("certs/private.pem")).isTrue();
        assertThat(TemplateRequirementsService.isSensitiveTemplateFile("certs/tls.key")).isTrue();
        assertThat(TemplateRequirementsService.isSensitiveTemplateFile("secrets.json")).isTrue();

        assertThat(TemplateRequirementsService.isSensitiveTemplateFile(".env.example")).isFalse();
        assertThat(TemplateRequirementsService.isSensitiveTemplateFile("backend/.env.sample")).isFalse();
        assertThat(TemplateRequirementsService.isSensitiveTemplateFile(".env.template")).isFalse();
        assertThat(TemplateRequirementsService.isSensitiveTemplateFile("src/App.tsx")).isFalse();
    }

    @Test
    void publishSanitizesSecretsAndCloneSetupCheckTracksLiveResolution() {
        Client alice = client();
        Client bob = client();

        // 1. Alice 创建应用并生成初始版本
        JsonNode app = alice.createApp("做一个产线良率看板");
        String appId = app.path("id").asText();
        alice.waitTask(alice.ok(alice.post("/api/apps/" + appId + "/generate", Map.of())).path("id").asText());
        JsonNode srcApp = alice.ok(alice.get("/api/apps/" + appId));
        String baseVerId = srcApp.path("currentVersion").path("id").asText();

        // 2. Alice 绑定一个 AGW 数据源（带机密 Token 与内网地址）
        JsonNode aliceSource = registerSource(alice, Map.of(
                "key", "yield_metrics",
                "name", "产线良率汇总接口",
                "description", "按日查询良率指标",
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/fake-upstream/items",
                "authType", "bearer",
                "secret", "ALICE_SUPER_SECRET_BEARER_TOKEN_7788"
        ));
        alice.ok(alice.post("/api/apps/" + appId + "/sources", Map.of("sourceId", aliceSource.path("id").asText())));

        // 3. Alice 创建一个私有飞书群机器人连接器（带私有名称与敏感 Webhook）
        JsonNode aliceConn = alice.ok(alice.post("/api/connectors", Map.of(
                "name", "Alice 绝密研发告警群",
                "kind", "feishu_bot",
                "secrets", Map.of(
                        "webhookUrl", "https://open.feishu.cn/open-apis/bot/v2/hook/alice-secret-hook-9999",
                        "webhookSignSecret", "ALICE_FEISHU_SIGN_SECRET_5566"
                )
        )));
        String aliceConnId = aliceConn.path("id").asText();

        // 4. Alice 往代码里写入：调用数据源 + 引用私有飞书连接器 + 误提交的 .env 私密文件
        String existingAppTsx = alice.get("/api/versions/" + baseVerId + "/file?path=src/App.tsx").body();
        String updatedAppTsx = "// connector: " + aliceConnId + "\n" + existingAppTsx;
        JsonNode saveTask = alice.ok(alice.post("/api/apps/" + appId + "/code", Map.of(
                "baseVersionId", baseVerId,
                "files", List.of(
                        Map.of("path", "src/App.tsx", "content", updatedAppTsx),
                        Map.of("path", ".env", "content", "OPENAI_API_KEY=sk-alice-top-secret-42\nPORT=8080\n")
                )
        )));
        assertThat(alice.waitTask(saveTask.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        // 5. Alice 发布到灵感社区
        JsonNode tpl = alice.ok(alice.post("/api/templates", Map.of(
                "appId", appId,
                "name", "产线良率监控模板",
                "description", "带数据源与飞书通知"
        )));
        String tplId = tpl.path("id").asText();

        try {
            // 校验依赖清单：完整记录非敏感依赖，且绝对不含任何密钥/URL/私有群名
            JsonNode req = tpl.path("requirements");
            String reqJson = req.toString();
            assertThat(req.path("hasExternalDeps").asBoolean()).isTrue();
            assertThat(req.path("strippedSecretFileCount").asInt()).isEqualTo(1);
            assertThat(req.path("dataSources")).hasSize(1);
            assertThat(req.path("dataSources").get(0).path("key").asText()).isEqualTo("yield_metrics");
            assertThat(req.path("connectors")).hasSize(1);
            assertThat(req.path("connectors").get(0).path("scope").asText()).isEqualTo("workspace");
            assertThat(req.path("connectors").get(0).path("sourceConnectorId").asText()).isEqualTo(aliceConnId);
            assertThat(req.path("envVars")).hasSize(1);
            assertThat(req.path("envVars").get(0).path("key").asText()).isEqualTo("OPENAI_API_KEY");

            assertThat(reqJson)
                    .doesNotContain("ALICE_SUPER_SECRET_BEARER_TOKEN_7788")
                    .doesNotContain("fake-upstream")
                    .doesNotContain("alice-secret-hook-9999")
                    .doesNotContain("ALICE_FEISHU_SIGN_SECRET_5566")
                    .doesNotContain("Alice 绝密研发告警群")
                    .doesNotContain("sk-alice-top-secret-42");

            // 6. Bob 复刻该模板
            JsonNode cloneRes = bob.ok(bob.post("/api/templates/" + tplId + "/clone", Map.of("name", "Bob 的良率看板")));
            String bobAppId = cloneRes.path("app").path("id").asText();
            assertThat(bob.waitTask(cloneRes.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
            JsonNode bobApp = bob.ok(bob.get("/api/apps/" + bobAppId));
            String bobVerId = bobApp.path("currentVersion").path("id").asText();

            // 校验：Bob 复刻出的项目文件中绝不包含 .env 密文文件
            JsonNode bobFiles = bob.ok(bob.get("/api/versions/" + bobVerId + "/files"));
            assertThat(bobFiles.toString()).doesNotContain("\".env\"");
            assertThat(bob.get("/api/versions/" + bobVerId + "/file?path=.env").status()).isEqualTo(404);

            // 校验：Bob 应用不会继承 Alice 的数据源绑定
            assertThat(bob.ok(bob.get("/api/apps/" + bobAppId + "/sources"))).isEmpty();

            // 7. Bob 首次运行配置体检：3 项全部缺失（open_sources + open_ecosystem + open_service）
            JsonNode c1 = bob.ok(bob.get("/api/apps/" + bobAppId + "/setup-check"));
            assertThat(c1.path("needsSetup").asBoolean()).isTrue();
            assertThat(c1.path("sourceTemplateId").asText()).isEqualTo(tplId);
            assertThat(c1.path("summary").path("pending").asInt()).isEqualTo(3);
            assertThat(findItem(c1, "dataSource", "yield_metrics").path("action").asText()).isEqualTo("open_sources");
            assertThat(findItem(c1, "connector", aliceConnId).path("action").asText()).isEqualTo("open_ecosystem");
            assertThat(findItem(c1, "envVar", "OPENAI_API_KEY").path("action").asText()).isEqualTo("open_service");

            // 8. Bob 导入了自己的同名 AGW 数据源，并配置了自己的飞书群机器人
            JsonNode bobSource = registerSource(bob, Map.of(
                    "key", "yield_metrics",
                    "name", "Bob 的产线良率接口",
                    "method", "GET",
                    "url", "http://127.0.0.1:" + port + "/fake-upstream/items",
                    "authType", "none"
            ));
            JsonNode bobConn = bob.ok(bob.post("/api/connectors", Map.of(
                    "name", "Bob 通知群机器人",
                    "kind", "feishu_bot",
                    "secrets", Map.of("webhookUrl", "https://open.feishu.cn/open-apis/bot/v2/hook/bob-hook-1234")
            )));

            JsonNode c2 = bob.ok(bob.get("/api/apps/" + bobAppId + "/setup-check"));
            JsonNode dsItem2 = findItem(c2, "dataSource", "yield_metrics");
            assertThat(dsItem2.path("action").asText()).isEqualTo("bind_source");
            assertThat(dsItem2.path("matchingSourceId").asText()).isEqualTo(bobSource.path("id").asText());
            JsonNode connItem2 = findItem(c2, "connector", aliceConnId);
            assertThat(connItem2.path("action").asText()).isEqualTo("rebind_connector");
            assertThat(connItem2.path("matchingConnectorId").asText()).isEqualTo(bobConn.path("id").asText());

            // 9. Bob 一键绑定数据源后，该项自动转为 ready
            bob.ok(bob.post("/api/apps/" + bobAppId + "/sources", Map.of("sourceId", bobSource.path("id").asText())));
            JsonNode c3 = bob.ok(bob.get("/api/apps/" + bobAppId + "/setup-check"));
            assertThat(findItem(c3, "dataSource", "yield_metrics").path("status").asText()).isEqualTo("ready");
            assertThat(c3.path("summary").path("ready").asInt()).isEqualTo(1);
            assertThat(c3.path("summary").path("pending").asInt()).isEqualTo(2);

            // 10. 越权防护：Alice 不能查 Bob 应用的 setup-check
            assertThat(alice.get("/api/apps/" + bobAppId + "/setup-check").status()).isEqualTo(404);
        } finally {
            alice.delete("/api/templates/" + tplId);
        }
    }

    private static JsonNode findItem(JsonNode check, String category, String matchKeyOrConnId) {
        for (JsonNode item : check.path("items")) {
            if (!category.equals(item.path("category").asText())) continue;
            if (matchKeyOrConnId.equals(item.path("key").asText())
                    || matchKeyOrConnId.equals(item.path("sourceConnectorId").asText())) {
                return item;
            }
        }
        throw new AssertionError("Missing setup-check item " + category + ":" + matchKeyOrConnId + " in " + check);
    }
}
