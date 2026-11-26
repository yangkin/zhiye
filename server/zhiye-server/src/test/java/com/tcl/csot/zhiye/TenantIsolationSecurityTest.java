package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.service.connector.EnterpriseConnectorService;
import com.tcl.csot.zhiye.service.mcp.McpRegistryService;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多员工（多工作空间）数据隔离与 SSRF 防护专项回归测试：
 * 1. 员工 B 不可枚举、测试、或经由 Agent 代理调用员工 A 的私有知识库与私有 HTTP MCP；
 * 2. 员工 B 的编码沙箱 mcp-servers.json 不包含员工 A 的私有知识库/MCP；
 * 3. 员工 B 不可测试、列出或通过应用代理调用（显式 ID 或缺省 ID 均拦截）员工 A 的私有飞书连接器；
 * 4. MCP 服务创建与远端工具探测严禁 SSRF 访问云元数据地址（169.254.169.254 / 100.100.100.200）。
 */
class TenantIsolationSecurityTest extends IntegrationTest {

    @Autowired McpRegistryService mcp;
    @Autowired EnterpriseConnectorService connectors;
    @Autowired AppRepository apps;

    @Test
    void privateKnowledgeBaseAndMcpAreStrictlyIsolatedAcrossEmployees(@TempDir Path agentDirA, @TempDir Path agentDirB) throws Exception {
        Client alice = client();
        Client bob = client();
        String wsAlice = alice.ok(alice.get("/api/workspace")).path("id").asText();
        String wsBob = bob.ok(bob.get("/api/workspace")).path("id").asText();
        assertThat(wsAlice).isNotEqualTo(wsBob);

        // 员工 A 创建私有知识库（包含机密配方数据）
        JsonNode created = alice.ok(alice.post("/api/mcp-servers", Map.of(
                "mode", "knowledge",
                "name", "Alice 绝密良率配方库",
                "description", "仅 Alice 自己可见",
                "knowledgeContent", "机密参数：ALICE_TOP_SECRET_FORMULA_9981"
        )));
        String kbId = created.path("id").asText();
        String kbKey = created.path("serverKey").asText();

        // 平台管理员创建一条平台共享知识库，验证两位员工均可正常访问平台共享项
        var globalKb = mcp.create(Json.M.valueToTree(Map.of(
                "serverKey", "global-shared-kb-" + Ids.newId("g").substring(2, 7),
                "name", "平台公共规范库",
                "transport", "knowledge",
                "endpointUrl", "kb:全公司通用规范：统一使用 UTF-8 编码。",
                "tools", List.of(Map.of("name", "search_knowledge", "description", "检索公共规范"))
        )), "platform-admin");
        String globalId = String.valueOf(globalKb.get("id"));
        String globalKey = String.valueOf(globalKb.get("serverKey"));

        try {
            // 1. 列表隔离：Alice 可见自己的私有库和平台库；Bob 仅见平台库，绝不可见 Alice 的私有库
            JsonNode aliceList = alice.ok(alice.get("/api/mcp-servers"));
            JsonNode bobList = bob.ok(bob.get("/api/mcp-servers"));
            assertThat(aliceList.toString()).contains(kbKey).contains(globalKey);
            assertThat(bobList.toString()).doesNotContain(kbKey).doesNotContain("ALICE_TOP_SECRET_FORMULA_9981").contains(globalKey);

            // 2. 试调用隔离：Alice 可测自己的库；Bob 即使猜出 serverKey 也必须返回 403 且不泄露正文
            JsonNode aliceTest = alice.ok(alice.post("/api/mcp-servers/" + kbKey + "/test", Map.of("tool", "search_knowledge")));
            assertThat(aliceTest.path("content").asText()).contains("ALICE_TOP_SECRET_FORMULA_9981");

            var bobTest = bob.post("/api/mcp-servers/" + kbKey + "/test", Map.of("tool", "search_knowledge"));
            assertThat(bobTest.status()).isEqualTo(403);
            assertThat(bobTest.body()).doesNotContain("ALICE_TOP_SECRET_FORMULA_9981");

            // Bob 仍可正常测试平台共享知识库
            JsonNode bobGlobalTest = bob.ok(bob.post("/api/mcp-servers/" + globalKey + "/test", Map.of("tool", "search_knowledge")));
            assertThat(bobGlobalTest.path("content").asText()).contains("UTF-8");

            // 3. 沙箱清单隔离：写给 Alice 沙箱的 mcp-servers.json 包含 Alice 私有库，写给 Bob 的绝对不包含
            mcp.writeCatalogForAgent(agentDirA, wsAlice);
            mcp.writeCatalogForAgent(agentDirB, wsBob);
            assertThat(Files.readString(agentDirA.resolve("mcp-servers.json"))).contains(kbKey).contains(globalKey);
            assertThat(Files.readString(agentDirB.resolve("mcp-servers.json"))).doesNotContain(kbKey).contains(globalKey);

            // 4. 工作空间代理调用隔离：模拟 /internal/mcp/call 传入 Bob 的 workspaceId，必须被 403 拒绝
            assertThatThrownBy(() -> mcp.callToolForWorkspace(wsBob, kbKey, "search_knowledge", Json.obj(), "t_bob", "app_bob"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("无权调用其他工作空间");

            // Alice 自己的工作空间代理调用成功
            var aliceProxy = mcp.callToolForWorkspace(wsAlice, kbKey, "search_knowledge", Json.obj(), "t_alice", "app_alice");
            assertThat(aliceProxy.get("ok")).isEqualTo(true);

            // 5. 修改/删除隔离：Bob 不能编辑或删除 Alice 的私有库
            assertThat(bob.put("/api/mcp-servers/" + kbId, Map.of("name", "篡改")).status()).isEqualTo(403);
            assertThat(bob.delete("/api/mcp-servers/" + kbId).status()).isEqualTo(403);
        } finally {
            mcp.delete(kbId);
            mcp.delete(globalId);
        }
    }

    @Test
    void privateFeishuConnectorIsStrictlyIsolatedAcrossEmployeesAndApps() {
        Client alice = client();
        Client bob = client();
        String wsAlice = alice.ok(alice.get("/api/workspace")).path("id").asText();
        String wsBob = bob.ok(bob.get("/api/workspace")).path("id").asText();

        // 员工 A 创建自己的飞书多维表格连接器
        JsonNode created = alice.ok(alice.post("/api/connectors", Map.of(
                "name", "Alice 私有客户多维表格",
                "kind", "feishu_bitable",
                "bitableUrl", "https://demo.feishu.cn/base/bascnAliceSecret?table=tblAliceSecret",
                "config", Map.of("appId", "cli_alice_secret"),
                "secrets", Map.of("appSecret", "sec_alice_secret")
        )));
        String aliceConnId = created.path("id").asText();

        // 为员工 B 创建一个真实应用记录，并签发其应用连接器代理令牌
        String bobAppId = Ids.newId("app");
        AppRow bobApp = new AppRow(bobAppId, wsBob, "Bob 全栈应用", false, "全栈应用", "", "test", "", "", false,
                "适中", "auto", false, "ready", "create", "Node.js", "service", false, "bob" + bobAppId.substring(4, 10),
                List.of(), null, null, null, null, null);
        apps.insert(bobApp);
        String bobToken = connectors.issueAppConnectorToken(bobAppId);

        try {
            // 1. 列表隔离：Bob 看不到 Alice 的连接器
            assertThat(bob.ok(bob.get("/api/connectors")).toString()).doesNotContain(aliceConnId).doesNotContain("bascnAliceSecret");

            // 2. HTTP 试调用隔离：Bob 对 Alice 连接器发起 /api/connectors/{id}/test 必须返回 403
            var bobTestRes = bob.post("/api/connectors/" + aliceConnId + "/test", Map.of());
            assertThat(bobTestRes.status()).isEqualTo(403);

            // 3. 单体应用内部代理列表隔离：/internal/app-connectors/list 绝不泄露 Alice 的私有连接器
            var internalListRes = bob.send("GET", "/internal/app-connectors/list", null, Map.of("Authorization", "Bearer " + bobToken));
            assertThat(internalListRes.status()).isEqualTo(200);
            assertThat(internalListRes.body()).doesNotContain(aliceConnId).doesNotContain("bascnAliceSecret");

            // 4. 应用代理显式传 Alice 的 connectorId 必须被 403 拦截
            var explicitCross = bob.send("POST", "/internal/app-connectors/bitable/list",
                    Map.of("connectorId", aliceConnId), Map.of("Authorization", "Bearer " + bobToken));
            assertThat(explicitCross.status()).isEqualTo(403);

            // 5. 应用代理省略 connectorId（空字符串）时，绝不能静默借用 Alice 的私有连接器
            var implicitCross = bob.send("POST", "/internal/app-connectors/bitable/list",
                    Map.of(), Map.of("Authorization", "Bearer " + bobToken));
            // 若平台无全局 bitable 连接器则返回 404；即使有全局连接器，返回的 connectorId 也绝不可能是 aliceConnId
            assertThat(implicitCross.body()).doesNotContain(aliceConnId);
            if (implicitCross.status() == 200) {
                assertThat(Json.read(implicitCross.body()).path("connectorId").asText()).isNotEqualTo(aliceConnId);
            }
        } finally {
            connectors.delete(aliceConnId);
            apps.softDelete(bobAppId);
        }
    }

    @Test
    void mcpEndpointRejectsCloudMetadataSsrf() {
        Client c = client();
        var awsMeta = c.post("/api/mcp-servers", Map.of(
                "mode", "http",
                "name", "恶意元数据探测",
                "endpointUrl", "http://169.254.169.254/latest/meta-data/"
        ));
        assertThat(awsMeta.status()).isEqualTo(403);

        var aliyunMeta = c.post("/api/mcp-servers/discover", Map.of(
                "endpointUrl", "http://100.100.100.200/latest/meta-data/"
        ));
        assertThat(aliyunMeta.status()).isEqualTo(403);
    }
}
