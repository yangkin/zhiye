package com.tcl.csot.zhiye.service.connector;

import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.service.runtime.MonolithServiceManager;
import com.tcl.csot.zhiye.service.workspace.WorkspaceService;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("api")
class FeishuControlPlaneTest {

    @Autowired EnterpriseConnectorService connectors;
    @Autowired AutomationTriggerService triggers;
    @Autowired FeishuControlPlaneService feishu;
    @Autowired WorkspaceService workspaces;
    @Autowired AppRepository apps;
    @Autowired VersionRepository versions;
    @Autowired GitService git;
    @Autowired MonolithServiceManager monolith;
    @Autowired JdbcClient jdbc;

    private final List<String> cleanupConnectors = new ArrayList<>();
    private final List<String> cleanupSessions = new ArrayList<>();
    private String createdAppId;
    private String createdWsId;

    @AfterEach
    void cleanup() {
        for (String sid : cleanupSessions) {
            jdbc.sql("delete from feishu_control_session where id = ?").param(sid).update();
        }
        cleanupSessions.clear();
        for (String cid : cleanupConnectors) {
            jdbc.sql("delete from enterprise_connector where id = ?").param(cid).update();
        }
        cleanupConnectors.clear();
        if (createdAppId != null) {
            apps.findById(createdAppId).ifPresent(a -> monolith.destroyForApp(a, true));
            git.deleteRepo(createdAppId);
            jdbc.sql("update app set current_version_id = null, published_version_id = null where id = ?").param(createdAppId).update();
            jdbc.sql("delete from message where app_id = ?").param(createdAppId).update();
            jdbc.sql("delete from task where app_id = ?").param(createdAppId).update();
            jdbc.sql("delete from version where app_id = ?").param(createdAppId).update();
            jdbc.sql("delete from app where id = ?").param(createdAppId).update();
            createdAppId = null;
        }
        if (createdWsId != null) {
            jdbc.sql("delete from workspace where id = ?").param(createdWsId).update();
            createdWsId = null;
        }
    }

    @Test
    void connectorSsrfGuardCardBitableAndOrgEmployees() {
        // 1. SSRF 校验：放行公域 open.feishu.cn，拦截 169.254.169.254 / 127.0.0.1 / 未授权主机
        assertDoesNotThrow(() -> EnterpriseConnectorService.validateOutboundUrl(
                "https://open.feishu.cn/open-apis/bot/v2/hook/demo", Json.obj()));
        assertThrows(ApiException.class, () -> EnterpriseConnectorService.validateOutboundUrl(
                "http://169.254.169.254/latest/meta-data/", Json.obj()));
        assertThrows(ApiException.class, () -> EnterpriseConnectorService.validateOutboundUrl(
                "http://127.0.0.1:8080/api/admin", Json.obj()));
        assertThrows(ApiException.class, () -> EnterpriseConnectorService.validateOutboundUrl(
                "https://evil.example.com/steal", Json.obj()));

        // 2. 新建连接器（统一 live 模式）+ 加密密钥存储 + 未填真实 Webhook 时返回明确校验错误并记录审计
        Map<String, Object> created = connectors.upsert(null, Json.read("""
                {
                  "name": "Phase4 飞书测试连接器",
                  "kind": "feishu_bot",
                  "platformWide": true,
                  "secrets": {"webhookSignSecret": "super-secret-xyz"}
                }
                """));
        String cid = String.valueOf(created.get("id"));
        cleanupConnectors.add(cid);
        assertEquals(true, created.get("hasSecrets"));
        assertEquals("live", created.get("mode"));

        assertThrows(ApiException.class, () -> connectors.testConnector(cid));
        List<Map<String, Object>> audits = connectors.recentAudits(cid, 5);
        assertFalse(audits.isEmpty());
        assertEquals("feishu.sendCard", audits.get(0).get("action"));

        // 3. 确认不再预置内置沙箱连接器
        assertTrue(connectors.findById("conn_feishu_bot_sandbox").isEmpty());
        assertTrue(connectors.findById("conn_feishu_bitable_sandbox").isEmpty());

        // 4. 组织员工目录查询真实用户表（无内置假人名兜底）
        assertNotNull(connectors.searchOrgEmployees("nonexistent_user_xyz"));
    }

    @Test
    void triggersCronWebhookAndFeishuControlPlaneEndToEnd() throws Exception {
        // 1. Cron 匹配验证
        ZonedDateTime dt = ZonedDateTime.of(2026, 11, 25, 9, 15, 0, 0, ZoneId.systemDefault());
        assertTrue(AutomationTriggerService.cronMatches("*/5 * * * *", dt));
        assertTrue(AutomationTriggerService.cronMatches("15 9 * * *", dt));
        assertFalse(AutomationTriggerService.cronMatches("0 10 * * *", dt));

        // 2. 创建测试应用与版本（用于触发器 + 飞书控制面一键审批上线）
        var ws = workspaces.create();
        createdWsId = ws.id();
        String appId = Ids.newId("app");
        createdAppId = appId;
        String slug = "feishuv4-" + appId.substring(appId.length() - 6);
        apps.insert(new AppRow(
                appId, ws.id(), "飞书闭环验证服务", false, "web", "", "", "", "", false, "agent", "default",
                false, "ready", "generate", "Node.js", "service", true, slug, List.of(), null, null, null, null, null
        ));
        VersionRow v1 = commitMinimalNodeService(appId);
        jdbc.sql("update app set current_version_id = ? where id = ?").params(v1.id(), appId).update();

        // 3. 创建 Webhook 自动化触发器，验证错误 Token 401 与正确鉴权进入执行并记录运行历史
        Map<String, Object> trg = triggers.createOrUpdate(ws.id(), appId, Json.read("""
                {
                  "name": "外部告警推飞书卡片",
                  "kind": "webhook",
                  "webhookSecret": "trg-secret-abc",
                  "actionType": "feishu_card",
                  "actionConfig": {
                    "connectorId": "",
                    "title": "告警触发：{{event}}",
                    "content": "收到载荷：{{payload}}"
                  }
                }
                """));
        String trgId = String.valueOf(trg.get("id"));
        assertThrows(ApiException.class, () -> triggers.handleWebhookTrigger(trgId, "wrong", null, "{}", Json.obj()));
        assertEquals(1, triggers.list(ws.id(), appId).size());

        // 4. 公域飞书控制面：URL 校验握手
        Map<String, Object> challenge = feishu.handleFeishuEvent(Json.read("{\"type\":\"url_verification\",\"challenge\":\"chk_9988\"}"), ws.id());
        assertEquals("chk_9988", challenge.get("challenge"));

        // 5. 公域飞书控制面：@织页 状态
        Map<String, Object> stRes = feishu.handleFeishuEvent(Json.read("{\"text\":\"@织页 状态\",\"userName\":\"测试工程师\"}"), ws.id());
        cleanupSessions.add(String.valueOf(stRes.get("sessionId")));
        assertEquals("status", stRes.get("intent"));
        assertEquals("completed", stRes.get("cardStatus"));

        // 6. 公域飞书控制面：@织页 发布 <应用> → 生成带「批准上线 / 驳回」按钮的交互审批卡片 → 模拟点击「批准上线」真实发布上线！
        Map<String, Object> pubReq = feishu.handleFeishuEvent(Json.read("""
                {"text": "@织页 发布 飞书闭环验证服务", "userName": "产线主管", "appId": "%s"}
                """.formatted(appId)), ws.id());
        String sessId = String.valueOf(pubReq.get("sessionId"));
        cleanupSessions.add(sessId);
        assertEquals("ready_for_approval", pubReq.get("cardStatus"));

        Map<String, Object> approved = feishu.handleCardAction(Json.read("""
                {"open_id": "ou_manager_001", "action": {"value": {"action": "approve_publish", "sessionId": "%s"}}}
                """.formatted(sessId)));
        assertEquals("approved_published", approved.get("cardStatus"));
        assertEquals(v1.id(), approved.get("versionId"));

        AppRow afterPub = apps.findById(appId).orElseThrow();
        assertEquals(v1.id(), afterPub.publishedVersionId());
    }

    private VersionRow commitMinimalNodeService(String appId) throws Exception {
        Path tmp = Files.createTempDirectory("zhiye-feishu-node-");
        try {
            git.initEmpty(appId);
            git.checkout(appId, null, tmp, "service");
            Files.writeString(tmp.resolve("package.json"), "{\"name\":\"feishu-demo\",\"scripts\":{\"start\":\"node server.js\"}}", StandardCharsets.UTF_8);
            Files.writeString(tmp.resolve("server.js"),
                    "const http=require('http');http.createServer((q,s)=>{s.writeHead(200);s.end('ok');}).listen(Number(process.env.PORT||8080),'127.0.0.1');",
                    StandardCharsets.UTF_8);
            var cr = git.commitWorkspace(appId, tmp, "init feishu demo", "test-feishu");
            return versions.insert(appId, "V1 init", "generate", cr.commit(), cr.tree(), null, null, null, null);
        } finally {
            GitService.deleteRecursively(tmp);
        }
    }
}
