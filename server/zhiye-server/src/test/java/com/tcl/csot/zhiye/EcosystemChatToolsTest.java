package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tcl.csot.zhiye.service.connector.EnterpriseConnectorService;
import com.tcl.csot.zhiye.service.llm.TaskTokenService;
import com.tcl.csot.zhiye.service.source.DataSourceService;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一企业生态能力（AGW/HTTP 数据源 + 飞书多维表格/机器人 + Runner 安全探测代理）的对话与多租户隔离回归测试：
 * 1. 对话中可直接调用 source_query 获取实时数据并写入 data_source_call 审计，且其他工作空间严禁越权调用；
 * 2. 对话里引用或调用过的数据源，在从对话创建应用时会自动绑定到 app_data_source；
 * 3. 对话中可通过 connector_list 查看本工作空间飞书连接器（脱敏无密钥），跨工作空间调 bitable_list 被严格拒绝（CONNECTOR_FORBIDDEN）；
 * 4. Coding Runner 内部代理 /internal/sources/query 与 /internal/connectors/bitable/inspect 严格校验 ZHIYE_TASK_TOKEN 与工作空间隔离。
 */
class EcosystemChatToolsTest extends IntegrationTest {

    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Autowired JdbcClient jdbc;
    @Autowired EnterpriseConnectorService connectors;
    @Autowired DataSourceService dataSources;
    @Autowired TaskTokenService taskTokens;

    @Test
    void conversationCanQueryDataSourceLiveWithTenantIsolationAndAutoBindOnBuild() throws Exception {
        HttpServer mockApi = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockApi.createContext("/kpi", ex -> {
            byte[] bytes = """
                    {"line":"T9-ARRAY","yieldRate":98.76,"defectCount":4,"shift":"A班"}
                    """.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        mockApi.start();
        String apiUrl = "http://127.0.0.1:" + mockApi.getAddress().getPort() + "/kpi";

        Client alice = client();
        Client bob = client();
        JsonNode conv = alice.ok(alice.post("/api/conversations", Map.of()));
        String convId = conv.path("id").asText();
        JsonNode bobConv = bob.ok(bob.post("/api/conversations", Map.of()));
        String aliceWs = jdbc.sql("select workspace_id from conversation where id = ?").param(convId).query(String.class).single();
        String bobWs = jdbc.sql("select workspace_id from conversation where id = ?").param(bobConv.path("id").asText()).query(String.class).single();
        String srcId = Ids.newId("src");
        String srcKey = "t9_yield_" + srcId.substring(srcId.length() - 6).toLowerCase();
        String bobSrcId = Ids.newId("src");
        String bobSrcKey = "bob_src_" + bobSrcId.substring(bobSrcId.length() - 6).toLowerCase();

        try {
            jdbc.sql("""
                    insert into data_source (id, workspace_id, key, name, description, method, url, params, sample, auth_type, provider)
                    values (?,?,?,?,?,?,?,'[]'::jsonb,'','none','agw')
                    """).params(srcId, aliceWs, srcKey, "T9产线良率实时接口", "实时查询T9产线良率与缺陷数", "GET", apiUrl).update();
            jdbc.sql("""
                    insert into data_source (id, workspace_id, key, name, description, method, url, params, sample, auth_type, provider)
                    values (?,?,?,?,?,?,?,'[]'::jsonb,'','none','agw')
                    """).params(bobSrcId, bobWs, bobSrcKey, "Bob私有接口", "仅Bob可见", "GET", apiUrl).update();

            // 1. Alice 在会话里直接通过 source_query 实时调用自己的数据源
            Client.Res sse = alice.send("POST", "/api/conversations/" + convId + "/ask", Map.of(
                    "content", "帮我调用一下 T9产线良率实时接口 [source_query:" + srcKey + "] 并准备做成看板"
            ), Map.of("Accept", "text/event-stream"));
            assertThat(sse.status()).isEqualTo(200);
            assertThat(sse.body())
                    .contains("正在实时调用企业数据源")
                    .contains("T9-ARRAY")
                    .contains("98.76");

            // 审计日志已落库
            long auditCnt = jdbc.sql("select count(*) from data_source_call where source_id = ? and origin = 'ask_chat' and ok = true")
                    .param(srcId).query(Long.class).single();
            assertThat(auditCnt).isGreaterThanOrEqualTo(1L);

            // 2. 即使 Alice 没手动把数据源加进 conversation.sourceIds，从对话 build 创建应用时也会自动绑定该数据源
            JsonNode builtApp = alice.ok(alice.post("/api/conversations/" + convId + "/build", Map.of("type", "日常开发")));
            String appId = builtApp.path("id").asText();
            List<DataSourceService.Source> bound = dataSources.boundSources(appId);
            assertThat(bound).extracting(DataSourceService.Source::id).contains(srcId);

            // 3. 多租户隔离：Bob 即使知道 Alice 的 srcKey，在对话里调用 source_query 也绝对拿不到 Alice 的数据
            Client.Res bobSse = bob.send("POST", "/api/chat/ask", Map.of(
                    "content", "越权查询 [source_query:" + srcKey + "]"
            ), Map.of("Accept", "text/event-stream"));
            assertThat(bobSse.status()).isEqualTo(200);
            assertThat(bobSse.body())
                    .doesNotContain("T9-ARRAY")
                    .doesNotContain("98.76");

            // 4. Runner 内部代理 /internal/sources/query 鉴权与租户隔离校验（任务处于 RUNNING 状态时令牌有效）
            String taskId = jdbc.sql("select id from task where app_id = ? order by created_at desc limit 1")
                    .param(appId).query(String.class).single();
            jdbc.sql("update task set status = 'RUNNING' where id = ?").param(taskId).update();
            String validToken = taskTokens.issue(taskId, java.time.Duration.ofMinutes(5));
            Client.Res proxyOk = alice.send("POST", "/internal/sources/query",
                    Map.of("key", srcKey), Map.of("Authorization", "Bearer " + validToken));
            assertThat(proxyOk.status()).isEqualTo(200);
            assertThat(proxyOk.body()).contains("T9-ARRAY").contains("98.76");

            // 用 Alice 的任务令牌尝试查 Bob 的私有数据源 -> 拒绝（404）
            Client.Res proxyCross = alice.send("POST", "/internal/sources/query",
                    Map.of("key", bobSrcKey), Map.of("Authorization", "Bearer " + validToken));
            assertThat(proxyCross.status()).isEqualTo(404);

            // 无效任务令牌 -> 401
            Client.Res proxyUnauth = alice.send("POST", "/internal/sources/query",
                    Map.of("key", srcKey), Map.of("Authorization", "Bearer invalid-token"));
            assertThat(proxyUnauth.status()).isEqualTo(401);
        } finally {
            jdbc.sql("delete from app_data_source where source_id in (?, ?)").params(srcId, bobSrcId).update();
            jdbc.sql("delete from data_source_call where source_id in (?, ?)").params(srcId, bobSrcId).update();
            jdbc.sql("delete from data_source where id in (?, ?)").params(srcId, bobSrcId).update();
            mockApi.stop(0);
        }
    }

    @Test
    void feishuConnectorCatalogAndBitableTenantIsolationWorkInChatAndRunnerProxy() {
        Client alice = client();
        Client bob = client();

        JsonNode aliceConn = alice.ok(alice.post("/api/connectors", Map.of(
                "name", "Alice设备点检多维表格",
                "kind", "feishu_bitable",
                "bitableUrl", "https://example.feishu.cn/base/bascnAlice01?table=tblAlice01",
                "config", Map.of("appId", "cli_alice_demo"),
                "secrets", Map.of("appSecret", "sec_SUPER_SECRET_ALICE_999")
        )));
        String aliceWs = aliceConn.path("workspaceId").asText();
        JsonNode bobConn = bob.ok(bob.post("/api/connectors", Map.of(
                "name", "Bob工单多维表格",
                "kind", "feishu_bitable",
                "bitableUrl", "https://example.feishu.cn/base/bascnBob01?table=tblBob01",
                "config", Map.of("appId", "cli_bob_demo"),
                "secrets", Map.of("appSecret", "sec_SUPER_SECRET_BOB_888")
        )));
        String aliceConnId = aliceConn.path("id").asText();
        String bobConnId = bobConn.path("id").asText();

        try {
            // 1. Alice 查 connector_list：只看到自己的连接器，绝不出现 Bob 的连接器，也绝不泄露 AppSecret
            Client.Res listSse = alice.send("POST", "/api/chat/ask", Map.of(
                    "content", "列出我的飞书连接器 [connector_list]"
            ), Map.of("Accept", "text/event-stream"));
            assertThat(listSse.status()).isEqualTo(200);
            assertThat(listSse.body())
                    .contains("Alice设备点检多维表格")
                    .contains(aliceConnId)
                    .doesNotContain("Bob工单多维表格")
                    .doesNotContain("sec_SUPER_SECRET_ALICE_999");

            // 2. Bob 在对话里尝试调 bitable_list 访问 Alice 的私有连接器 -> 严格返回无权访问
            Client.Res crossSse = bob.send("POST", "/api/chat/ask", Map.of(
                    "content", "越权读多维表格 [bitable_list:" + aliceConnId + "]"
            ), Map.of("Accept", "text/event-stream"));
            assertThat(crossSse.status()).isEqualTo(200);
            assertThat(crossSse.body()).contains("无权调用其他工作空间的私有连接器");

            // 3. Runner 内部探测代理 /internal/connectors/bitable/inspect 多租户隔离校验
            JsonNode bobApp = bob.ok(bob.post("/api/apps", Map.of("type", "日常开发", "prompt", "简单计数器")));
            String bobTaskId = jdbc.sql("select id from task where app_id = ? order by created_at desc limit 1")
                    .param(bobApp.path("id").asText()).query(String.class).single();
            jdbc.sql("update task set status = 'RUNNING' where id = ?").param(bobTaskId).update();
            String bobTaskTok = taskTokens.issue(bobTaskId, java.time.Duration.ofMinutes(5));
            Client.Res proxyForbidden = bob.send("POST", "/internal/connectors/bitable/inspect",
                    Map.of("connectorId", aliceConnId), Map.of("Authorization", "Bearer " + bobTaskTok));
            assertThat(proxyForbidden.status()).isEqualTo(403);
            assertThat(proxyForbidden.body()).contains("CONNECTOR_FORBIDDEN");

            // 4. 多维表格返回格式化工具正确提取真实字段名与样本数据
            String formatted = EnterpriseConnectorService.formatBitableResultForModel(Map.of(
                    "connectorId", aliceConnId,
                    "connectorName", "Alice设备点检多维表格",
                    "total", 2,
                    "items", Json.read("""
                            [
                              {"record_id":"rec1","fields":{"产线":"T9","设备编号":"EQ-101","良率":99.2,"状态":"正常"}},
                              {"record_id":"rec2","fields":{"产线":"T10","设备编号":"EQ-205","良率":96.8,"状态":"待复检"}}
                            ]
                            """)
            ));
            assertThat(formatted)
                    .contains("真实字段名（表头）：产线、设备编号、良率、状态")
                    .contains("EQ-101")
                    .contains("99.2");
            assertThat(connectors.promptGuideForChat(aliceWs))
                    .contains("Alice设备点检多维表格")
                    .contains("bitable_list")
                    .doesNotContain("sec_SUPER_SECRET_ALICE_999");
        } finally {
            connectors.delete(aliceConnId);
            connectors.delete(bobConnId);
        }
    }
}
