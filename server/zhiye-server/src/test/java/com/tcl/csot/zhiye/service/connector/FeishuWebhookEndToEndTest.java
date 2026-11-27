package com.tcl.csot.zhiye.service.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.cloud.CloudService;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.service.runtime.MonolithServiceManager;
import com.tcl.csot.zhiye.service.template.TemplateRequirementsService;
import com.tcl.csot.zhiye.service.workspace.WorkspaceService;
import com.tcl.csot.zhiye.storage.ObjectStore;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 飞书自定义群机器人 Webhook 全链路真实集成测试：
 * 1. 普通网页免后端直发 SDK（预览态 /cloud/{appId}/feishu/send-card + 发布态 /__zhiye/feishu/send-card + sdk.js 自动注入）
 * 2. 单体全栈 Node.js 服务（app_service_env 完全为空时自动注入 ZHIYE_CONNECTOR_API / ZHIYE_CONNECTOR_TOKEN / ZHIYE_FEISHU_BOT_ID，无需 FEISHU_GROUP_ID）
 * 3. inspectSnapshotForAgent 正确暴露 platformInjectedEnv 与 boundToGroup 连接器清单
 * 4. 签名校验、自定义安全关键词自动补齐、19024/19021 中文错误诊断、自定义试发卡片、跨工作空间 403 严格隔离
 * 5. 社区模板依赖识别 window.zhiye.feishu.sendCard
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "zhiye.connectors.allow-loopback-for-test=true",
                "zhiye.site.site-host-suffix=localhost"
        }
)
@ActiveProfiles({"api", "site", "test"})
class FeishuWebhookEndToEndTest {

    @LocalServerPort int port;
    @Autowired EnterpriseConnectorService connectors;
    @Autowired WorkspaceService workspaces;
    @Autowired AppRepository apps;
    @Autowired VersionRepository versions;
    @Autowired GitService git;
    @Autowired ObjectStore store;
    @Autowired CloudService cloud;
    @Autowired MonolithServiceManager monolith;
    @Autowired TemplateRequirementsService requirements;
    @Autowired JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer mockFeishu;
    private String mockWebhookUrl;
    private final List<JsonNode> receivedPayloads = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> mockResponseBody = new AtomicReference<>("{\"code\":0,\"msg\":\"success\"}");

    private final List<String> cleanupConnectors = new ArrayList<>();
    private final List<String> cleanupApps = new ArrayList<>();
    private final List<String> cleanupWs = new ArrayList<>();

    @BeforeEach
    void startMockFeishu() throws Exception {
        receivedPayloads.clear();
        mockResponseBody.set("{\"code\":0,\"msg\":\"success\"}");
        mockFeishu = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockFeishu.createContext("/open-apis/bot/v2/hook/test-bot", ex -> {
            byte[] raw = ex.getRequestBody().readAllBytes();
            receivedPayloads.add(Json.read(new String(raw, StandardCharsets.UTF_8)));
            byte[] resp = mockResponseBody.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(200, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        mockFeishu.start();
        mockWebhookUrl = "http://127.0.0.1:" + mockFeishu.getAddress().getPort() + "/open-apis/bot/v2/hook/test-bot";
    }

    @AfterEach
    void tearDown() {
        if (mockFeishu != null) mockFeishu.stop(0);
        for (String appId : cleanupApps) {
            try {
                apps.findById(appId).ifPresent(a -> {
                    if (a.isService()) monolith.destroyForApp(a, true);
                });
            } catch (Exception ignored) {}
            try { git.deleteRepo(appId); } catch (Exception ignored) {}
            try {
                jdbc.sql("update app set current_version_id = null, published_version_id = null where id = ?").param(appId).update();
                jdbc.sql("delete from app_service_release where app_id = ?").param(appId).update();
                jdbc.sql("delete from app_service_env where app_id = ?").param(appId).update();
                jdbc.sql("delete from app_service where app_id = ?").param(appId).update();
                jdbc.sql("delete from version where app_id = ?").param(appId).update();
                jdbc.sql("delete from build where app_id = ?").param(appId).update();
                jdbc.sql("delete from app where id = ?").param(appId).update();
            } catch (Exception ignored) {}
        }
        cleanupApps.clear();
        for (String cid : cleanupConnectors) {
            try {
                jdbc.sql("delete from connector_call_audit where connector_id = ?").param(cid).update();
                jdbc.sql("delete from enterprise_connector where id = ?").param(cid).update();
            } catch (Exception ignored) {}
        }
        cleanupConnectors.clear();
        for (String wsId : cleanupWs) {
            try { jdbc.sql("delete from workspace where id = ?").param(wsId).update(); } catch (Exception ignored) {}
        }
        cleanupWs.clear();
    }

    @Test
    void zeroBackendFrontendSdkPreviewPublishedAndTenantIsolation() throws Exception {
        var wsA = workspaces.create();
        var wsB = workspaces.create();
        cleanupWs.add(wsA.id());
        cleanupWs.add(wsB.id());

        String signSecret = "feishu-sign-secret-123";
        Map<String, Object> connA = connectors.upsertForWorkspace(wsA.id(), Json.read("""
                {
                  "name": "测试222",
                  "kind": "feishu_bot",
                  "config": {"keyword": "告警"},
                  "secrets": {"webhookUrl": "%s", "webhookSecret": "%s"}
                }
                """.formatted(mockWebhookUrl, signSecret)));
        String connIdA = String.valueOf(connA.get("id"));
        cleanupConnectors.add(connIdA);

        // 1. 创建一个纯网页应用（未开启云服务），前端源码直接调用 window.zhiye.feishu.sendCard
        String appIdA = Ids.newId("app");
        cleanupApps.add(appIdA);
        String slugA = "feishuweb-" + appIdA.substring(appIdA.length() - 6);
        String buildIdA = Ids.newId("bld");
        String prefixA = "builds/" + buildIdA + "/publish";
        store.put(prefixA + "/index.html", "<!doctype html><html><head><title>告警页</title></head><body><script src=\"./app.js\"></script></body></html>".getBytes(StandardCharsets.UTF_8));
        store.put(prefixA + "/app.js", ("async function submit(){return window.zhiye.feishu.sendCard({connectorId:'" + connIdA + "',title:'故障上报',content:'T11异常'});}").getBytes(StandardCharsets.UTF_8));

        apps.insert(new AppRow(
                appIdA, wsA.id(), "异常上报网页", false, "web", "", "", "", "", false, "agent", "default",
                false, "ready", "generate", "React", "template", true, slugA, List.of(), null, null, null, null, null
        ));
        VersionRow verA = commitFileWithBuild(appIdA, "src/App.tsx",
                "export default function App(){ window.zhiye?.feishu?.sendCard({ connectorId: '" + connIdA + "', title: '故障', content: '详情' }); return null; }",
                buildIdA, prefixA);
        jdbc.sql("update app set current_version_id = ?, published_version_id = ? where id = ?")
                .params(verA.id(), verA.id(), appIdA).update();

        // 2. 社区模板依赖提取能够识别 window.zhiye.feishu.sendCard
        ObjectNode reqs = requirements.extract(apps.findById(appIdA).orElseThrow(), verA.gitCommit());
        assertTrue(reqs.path("connectors").size() >= 1);
        assertEquals("feishu_bot", reqs.path("connectors").get(0).path("kind").asText());

        // 3. 预览态 HTML 自动注入 sdk.js（无需开启云服务），并验证 /cloud/{appId}/feishu/send-card 真发成功 + 自动补齐关键词 + 正确 HMAC 签名
        HttpResponse<String> prevHtml = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/p/" + buildIdA + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, prevHtml.statusCode());
        assertTrue(prevHtml.body().contains("/cloud/sdk.js"), "预览页应自动注入 sdk.js");

        String previewToken = cloud.mint(appIdA, 600, "admin");
        HttpResponse<String> prevSend = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/cloud/" + appIdA + "/feishu/send-card"))
                .header("Authorization", "Bearer " + previewToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"connectorId":"%s","title":"产线故障上报","content":"Array 3号机温度偏高","templateColor":"red","fields":[{"label":"机台","value":"EQP-03"}]}
                        """.formatted(connIdA)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, prevSend.statusCode(), "预览免后端发卡失败: " + prevSend.body());
        JsonNode prevJson = Json.read(prevSend.body());
        assertTrue(prevJson.path("ok").asBoolean());
        assertEquals("测试222", prevJson.path("connectorName").asText());
        assertEquals(1, receivedPayloads.size());

        JsonNode delivered = receivedPayloads.get(0);
        assertEquals("interactive", delivered.path("msg_type").asText());
        // 自动补齐安全关键词 [告警]
        assertEquals("[告警] 产线故障上报", delivered.path("card").path("header").path("title").path("content").asText());
        assertEquals("red", delivered.path("card").path("header").path("template").asText());
        // 核对真实 HMAC-SHA256 签名
        String ts = delivered.path("timestamp").asText();
        assertFalse(ts.isBlank());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec((ts + "\n" + signSecret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expectedSign = Base64.getEncoder().encodeToString(mac.doFinal(new byte[0]));
        assertEquals(expectedSign, delivered.path("sign").asText());

        // 4. 发布站点同源 /__zhiye/feishu/send-card 真发成功（无需令牌，带 X-Zhiye-Request 防 CSRF）
        HttpResponse<String> siteHtml = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                .header("Host", slugA + ".localhost:" + port).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, siteHtml.statusCode());
        assertTrue(siteHtml.body().contains("/__zhiye/sdk.js"), "发布站点应自动注入 /__zhiye/sdk.js");

        // 缺少 X-Zhiye-Request 被 CSRF 拦截
        HttpResponse<String> noCsrf = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__zhiye/feishu/send-card"))
                .header("Host", slugA + ".localhost:" + port)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"告警测试\",\"content\":\"内容\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, noCsrf.statusCode());

        // 带 X-Zhiye-Request 且不传 connectorId（自动回退工作空间默认机器人）真发成功！
        HttpResponse<String> siteSend = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__zhiye/feishu/send-card"))
                .header("Host", slugA + ".localhost:" + port)
                .header("X-Zhiye-Request", "1")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"告警：良率恢复正常\",\"content\":\"已恢复至 99.2%\",\"templateColor\":\"green\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, siteSend.statusCode(), "发布态免后端发卡失败: " + siteSend.body());
        assertEquals(2, receivedPayloads.size());
        // 标题已含关键词「告警」时不再重复加前缀
        assertEquals("告警：良率恢复正常", receivedPayloads.get(1).path("card").path("header").path("title").path("content").asText());

        // 5. 跨工作空间严格隔离：Workspace B 的应用无权调用 Workspace A 的私有机器人
        String appIdB = Ids.newId("app");
        cleanupApps.add(appIdB);
        apps.insert(new AppRow(
                appIdB, wsB.id(), "外部工作空间应用", false, "web", "", "", "", "", false, "agent", "default",
                false, "ready", "generate", "React", "template", true, "other-" + appIdB.substring(appIdB.length() - 6),
                List.of(), null, null, null, null, null
        ));
        String tokB = cloud.mint(appIdB, 600, "admin");
        HttpResponse<String> crossResp = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/cloud/" + appIdB + "/feishu/send-card"))
                .header("Authorization", "Bearer " + tokB)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"connectorId\":\"" + connIdA + "\",\"title\":\"越权尝试\",\"content\":\"x\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, crossResp.statusCode(), "跨工作空间调用必须被 403 拦截");

        // 6. 自定义试发卡片 + 飞书 19024 关键词错误友好诊断
        Map<String, Object> customTest = connectors.testConnectorForWorkspace(wsA.id(), connIdA,
                Json.read("{\"title\":\"日报汇总\",\"content\":\"今日一切正常\",\"templateColor\":\"orange\"}"));
        assertEquals(true, customTest.get("ok"));
        assertEquals(3, receivedPayloads.size());
        assertEquals("orange", receivedPayloads.get(2).path("card").path("header").path("template").asText());

        mockResponseBody.set("{\"code\":19024,\"msg\":\"Key Words Not Found\"}");
        var err = assertThrows(com.tcl.csot.zhiye.exception.ApiException.class,
                () -> connectors.testConnectorForWorkspace(wsA.id(), connIdA, null));
        assertTrue(err.getMessage().contains("自定义关键词"), "19024 应返回清晰的关键词指引: " + err.getMessage());
    }

    @Test
    void monolithServiceAutoInjectsConnectorEnvWithoutUserEnvVarsOrGroupId() throws Exception {
        var ws = workspaces.create();
        cleanupWs.add(ws.id());

        Map<String, Object> conn = connectors.upsertForWorkspace(ws.id(), Json.read("""
                {
                  "name": "产线值班机器人",
                  "kind": "feishu_bot",
                  "secrets": {"webhookUrl": "%s"}
                }
                """.formatted(mockWebhookUrl)));
        String connId = String.valueOf(conn.get("id"));
        cleanupConnectors.add(connId);

        String appId = Ids.newId("app");
        cleanupApps.add(appId);
        String slug = "feishusvc-" + appId.substring(appId.length() - 6);
        apps.insert(new AppRow(
                appId, ws.id(), "全栈飞书告警服务", false, "web", "", "", "", "", false, "agent", "default",
                false, "ready", "generate", "Node.js", "service", true, slug, List.of(), null, null, null, null, null
        ));

        // 模拟真实全栈服务（app_service_env 为空，且代码里完全没有 FEISHU_GROUP_ID）
        String serverJs = """
                const http = require('http');
                const port = Number(process.env.PORT || 8080);
                http.createServer(async (req, res) => {
                  if (req.url === '/api/health') {
                    res.writeHead(200, {'Content-Type': 'application/json'});
                    return res.end(JSON.stringify({
                      ok: true,
                      hasConnectorApi: Boolean(process.env.ZHIYE_CONNECTOR_API),
                      hasConnectorToken: Boolean(process.env.ZHIYE_CONNECTOR_TOKEN),
                      defaultBotId: process.env.ZHIYE_FEISHU_BOT_ID || ''
                    }));
                  }
                  if (req.method === 'POST' && req.url === '/api/send-card') {
                    const api = process.env.ZHIYE_CONNECTOR_API || '';
                    const tok = process.env.ZHIYE_CONNECTOR_TOKEN || '';
                    if (!api || !tok) {
                      res.writeHead(502, {'Content-Type': 'application/json'});
                      return res.end(JSON.stringify({ error: 'connector unavailable' }));
                    }
                    let raw = '';
                    for await (const c of req) raw += c;
                    const body = raw ? JSON.parse(raw) : {};
                    const r = await fetch(api + '/feishu/send-card', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + tok },
                      body: JSON.stringify({
                        connectorId: body.connectorId || process.env.ZHIYE_FEISHU_BOT_ID || '',
                        title: body.title || '全栈告警',
                        content: body.content || '测试内容',
                        templateColor: body.templateColor || 'blue'
                      })
                    });
                    const txt = await r.text();
                    res.writeHead(r.status, {'Content-Type': 'application/json'});
                    return res.end(txt);
                  }
                  res.writeHead(404); res.end('not found');
                }).listen(port, '127.0.0.1');
                """;
        VersionRow v1 = commitNodeService(appId, serverJs);
        jdbc.sql("update app set current_version_id = ? where id = ?").params(v1.id(), appId).update();

        // 部署到预览槽位（确认 app_service_env 完全为空）
        long userEnvCount = jdbc.sql("select count(*) from app_service_env where app_id = ?").param(appId).query(Long.class).single();
        assertEquals(0L, userEnvCount);

        var svc = monolith.deployPreview(apps.findById(appId).orElseThrow(), v1);
        assertNotNull(svc.previewEndpointUrl());

        // 1. 验证运行态真实拿到了 ZHIYE_CONNECTOR_API / ZHIYE_CONNECTOR_TOKEN / ZHIYE_FEISHU_BOT_ID
        HttpResponse<String> healthRes = http.send(HttpRequest.newBuilder(URI.create(svc.previewEndpointUrl() + "/api/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, healthRes.statusCode());
        JsonNode health = Json.read(healthRes.body());
        assertTrue(health.path("hasConnectorApi").asBoolean(), "运行态必须自动注入 ZHIYE_CONNECTOR_API");
        assertTrue(health.path("hasConnectorToken").asBoolean(), "运行态必须自动注入 ZHIYE_CONNECTOR_TOKEN");
        assertEquals(connId, health.path("defaultBotId").asText(), "运行态应自动注入 ZHIYE_FEISHU_BOT_ID");

        // 2. 验证 inspectSnapshotForAgent 暴露 platformInjectedEnv 与 connectors，避免 AI 误判 envKeys 为空
        ObjectNode snap = monolith.inspectSnapshotForAgent(apps.findById(appId).orElseThrow());
        assertNotNull(snap);
        String snapJson = Json.write(snap);
        assertTrue(snapJson.contains("ZHIYE_CONNECTOR_API"));
        assertTrue(snapJson.contains("ZHIYE_CONNECTOR_TOKEN"));
        assertTrue(snapJson.contains("boundToGroup"));

        // 3. 真实调用全栈 /api/send-card → 经由平台内部网关成功发往飞书 Webhook（零 502、零 groupId！）
        HttpResponse<String> sendRes = http.send(HttpRequest.newBuilder(URI.create(svc.previewEndpointUrl() + "/api/send-card"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"全栈端到端告警\",\"content\":\"无需配置群ID直接发送\",\"templateColor\":\"red\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, sendRes.statusCode(), "全栈服务调用连接器网关失败: " + sendRes.body());
        assertEquals(1, receivedPayloads.size());
        assertEquals("全栈端到端告警", receivedPayloads.get(0).path("card").path("header").path("title").path("content").asText());
        assertEquals("red", receivedPayloads.get(0).path("card").path("header").path("template").asText());
    }

    private VersionRow commitFileWithBuild(String appId, String relPath, String content, String buildId, String prefix) throws Exception {
        Path tmp = Files.createTempDirectory("zhiye-feishu-web-");
        try {
            git.initEmpty(appId);
            git.checkout(appId, null, tmp, "static");
            Path target = tmp.resolve(relPath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            var cr = git.commitWorkspace(appId, tmp, "init feishu web", "test");
            versions.insertBuild(appId, new VersionRepository.BuildRow(buildId, cr.tree(), "ready", prefix, prefix, false));
            return versions.insert(appId, "V1", "generate", cr.commit(), cr.tree(), null, null, null, buildId);
        } finally {
            GitService.deleteRecursively(tmp);
        }
    }

    private VersionRow commitNodeService(String appId, String serverJs) throws Exception {
        Path tmp = Files.createTempDirectory("zhiye-feishu-svc-");
        try {
            git.initEmpty(appId);
            git.checkout(appId, null, tmp, "service");
            Files.writeString(tmp.resolve("package.json"), "{\"name\":\"feishu-svc\",\"scripts\":{\"start\":\"node server.js\"}}", StandardCharsets.UTF_8);
            Files.writeString(tmp.resolve("server.js"), serverJs, StandardCharsets.UTF_8);
            var cr = git.commitWorkspace(appId, tmp, "init feishu svc", "test");
            return versions.insert(appId, "V1", "generate", cr.commit(), cr.tree(), null, null, null, null);
        } finally {
            GitService.deleteRecursively(tmp);
        }
    }
}
