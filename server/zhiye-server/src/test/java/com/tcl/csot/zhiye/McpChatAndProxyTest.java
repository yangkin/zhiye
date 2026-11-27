package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tcl.csot.zhiye.service.mcp.McpRegistryService;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.FakeUpstreamController;
import com.tcl.csot.zhiye.support.IntegrationTest;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 企业知识库 & 真实 Streamable HTTP MCP 在对话（创造页/会话页/问答）中的端到端工具调用回归测试：
 * 1. 验证工作空间知识库 MCP 在 /api/conversations/{id}/ask 中可通过 mcp_call 实时查证并返回真实知识内容；
 * 2. 验证真实 Streamable HTTP JSON-RPC 2.0 MCP 服务（initialize -> notifications/initialized -> tools/list 保留 inputSchema -> tools/call）
 *    在 /api/chat/ask 中能被模型直接调用并落审计日志；
 * 3. 验证多工作空间严格隔离：员工 B 在对话里即使指定员工 A 的私有 serverKey，也会被拒绝且不泄露知识内容。
 */
class McpChatAndProxyTest extends IntegrationTest {

    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Autowired McpRegistryService mcp;
    @Autowired JdbcClient jdbc;

    @Test
    void conversationAndAskCanInvokeKnowledgeBaseMcpWithTenantIsolation() {
        Client alice = client();
        Client bob = client();

        JsonNode kb = alice.ok(alice.post("/api/mcp-servers", Map.of(
                "mode", "knowledge",
                "name", "测试",
                "description", "五星AI岛内部介绍资料",
                "knowledgeContent", "五星AI岛是 TCL华星 内部统一的企业级 AI 模型网关与智能应用平台，提供个人模型额度、统一鉴权与知识工具托管。"
        )));
        String kbId = kb.path("id").asText();
        String kbKey = kb.path("serverKey").asText();

        try {
            // 1. Alice 在会话页真实发起对话（触发 mcp_call 工具循环）
            JsonNode conv = alice.ok(alice.post("/api/conversations", Map.of()));
            String convId = conv.path("id").asText();
            Client.Res sse = alice.send("POST", "/api/conversations/" + convId + "/ask", Map.of(
                    "content", "介绍一下五星AI岛 [mcp_call:" + kbKey + "::五星AI岛]"
            ), Map.of("Accept", "text/event-stream"));
            assertThat(sse.status()).isEqualTo(200);
            assertThat(sse.body())
                    .contains("正在调用企业 MCP")
                    .contains("五星AI岛是 TCL华星 内部统一的企业级 AI 模型网关");

            // 校验：送给上游模型的第二轮 assistant tool_calls 保留了 reasoning_content，兼容思考模型
            JsonNode secondRoundReq = FakeUpstreamController.BODIES.stream()
                    .filter(b -> b.toString().contains(kbKey) && b.path("messages").toString().contains("\"role\":\"tool\""))
                    .reduce((first, second) -> second).orElseThrow();
            assertThat(secondRoundReq.path("messages").toString()).contains("reasoning_content");

            // 校验：mcp_call_audit 写入了真实调用记录
            long auditCnt = jdbc.sql("select count(*) from mcp_call_audit where server_key = ? and ok = true")
                    .param(kbKey).query(Long.class).single();
            assertThat(auditCnt).isGreaterThanOrEqualTo(1L);

            // 2. 多租户隔离：Bob 即使猜出 Alice 的 kbKey 发起对话，也绝拿不到 Alice 的私有知识内容
            JsonNode bobKb = bob.ok(bob.post("/api/mcp-servers", Map.of(
                    "mode", "knowledge",
                    "name", "Bob知识库",
                    "knowledgeContent", "仅 Bob 自己的普通资料"
            )));
            try {
                Client.Res bobSse = bob.send("POST", "/api/chat/ask", Map.of(
                        "content", "查一下 [mcp_call:" + kbKey + ":search_knowledge:五星AI岛]"
                ), Map.of("Accept", "text/event-stream"));
                assertThat(bobSse.status()).isEqualTo(200);
                assertThat(bobSse.body())
                        .contains("无权调用其他工作空间")
                        .doesNotContain("五星AI岛是 TCL华星 内部统一的企业级 AI 模型网关");
            } finally {
                mcp.delete(bobKb.path("id").asText());
            }
        } finally {
            mcp.delete(kbId);
        }
    }

    @Test
    void chatAskCanDiscoverAndInvokeRealStreamableHttpMcpServer() throws Exception {
        List<String> receivedMethods = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = Json.read(raw);
            String method = req.path("method").asText("");
            receivedMethods.add(method);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if ("initialize".equals(method)) {
                ex.getResponseHeaders().add("Mcp-Session-Id", "sess-test-001");
                String body = "{\"jsonrpc\":\"2.0\",\"id\":\"" + req.path("id").asText() + "\",\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{\"tools\":{}}}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
            } else if ("notifications/initialized".equals(method)) {
                ex.sendResponseHeaders(202, -1);
            } else if ("tools/list".equals(method)) {
                String body = """
                        {"jsonrpc":"2.0","id":"%s","result":{"tools":[{
                          "name":"lookup_product",
                          "description":"查询内部产品详情",
                          "inputSchema":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}
                        }]}}
                        """.formatted(req.path("id").asText());
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
            } else if ("tools/call".equals(method)) {
                String q = req.path("params").path("arguments").path("query").asText("");
                String body = """
                        {"jsonrpc":"2.0","id":"%s","result":{"content":[{"type":"text","text":"HTTP_MCP_LIVE_DATA:%s=五星AI岛2.0"}]}}
                        """.formatted(req.path("id").asText(), q);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
            } else {
                ex.sendResponseHeaders(400, -1);
            }
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

        Client alice = client();
        String mcpId = null;
        try {
            JsonNode created = alice.ok(alice.post("/api/mcp-servers", Map.of(
                    "mode", "http",
                    "name", "产品信息HTTP-MCP",
                    "endpointUrl", url
            )));
            mcpId = created.path("id").asText();
            String serverKey = created.path("serverKey").asText();

            // 校验 tools/list 自动发现了 lookup_product 且完整保留了 inputSchema
            assertThat(created.path("tools")).hasSize(1);
            assertThat(created.path("tools").get(0).path("name").asText()).isEqualTo("lookup_product");
            assertThat(created.path("tools").get(0).path("inputSchema").path("properties").has("query")).isTrue();

            // 通过 /api/chat/ask 触发真实 HTTP MCP tools/call
            Client.Res sse = alice.send("POST", "/api/chat/ask", Map.of(
                    "content", "查产品 [mcp_call:" + serverKey + ":lookup_product:五星AI岛]"
            ), Map.of("Accept", "text/event-stream"));
            assertThat(sse.status()).isEqualTo(200);
            assertThat(sse.body()).contains("HTTP_MCP_LIVE_DATA:五星AI岛=五星AI岛2.0");
            assertThat(receivedMethods).contains("initialize", "notifications/initialized", "tools/list", "tools/call");
        } finally {
            if (mcpId != null) mcp.delete(mcpId);
            server.stop(0);
        }
    }
}
