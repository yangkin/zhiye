package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.admin.AdminService;
import com.tcl.csot.zhiye.service.connector.AutomationTriggerService;
import com.tcl.csot.zhiye.service.connector.EnterpriseConnectorService;
import com.tcl.csot.zhiye.service.connector.FeishuControlPlaneService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Phase 4 REST API：
 * - 企业连接器中心（公域飞书 Bot / 多维表格 / 飞书应用）管理、测试与审计
 * - 应用自动化触发器（Cron / DB 写入钩子 / 签名入站 Webhook）管理、测试与记录
 * - 公域飞书控制面 Webhook（/api/webhooks/feishu/events, /api/webhooks/feishu/card-action）与管理端闭环模拟器
 */
@RestController
@Profile("api")
public class FeishuConnectorController {
    private final EnterpriseConnectorService connectors;
    private final AutomationTriggerService triggers;
    private final FeishuControlPlaneService feishu;
    private final AdminService admin;
    private final CurrentPrincipal principal;

    public FeishuConnectorController(EnterpriseConnectorService connectors,
                                     AutomationTriggerService triggers,
                                     FeishuControlPlaneService feishu,
                                     AdminService admin,
                                     CurrentPrincipal principal) {
        this.connectors = connectors;
        this.triggers = triggers;
        this.feishu = feishu;
        this.admin = admin;
        this.principal = principal;
    }

    private String ws() { return principal.workspaceId(); }

    // ==================== 企业连接器（工作空间可读 + 管理员全量管理） ====================

    @GetMapping("/api/connectors")
    public List<Map<String, Object>> listForWorkspace() {
        String curWs = ws();
        return connectors.list(curWs).stream().map(m -> {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(m);
            copy.put("owned", curWs != null && curWs.equals(m.get("workspaceId")));
            return copy;
        }).toList();
    }

    /** 普通工作空间用户自助登记/修改自己的飞书群机器人 Webhook 或多维表格（加密保存在当前工作空间，免找管理员）。 */
    @PostMapping("/api/connectors")
    public Map<String, Object> upsertForWorkspace(@RequestBody JsonNode body) {
        Map<String, Object> saved = new java.util.LinkedHashMap<>(connectors.upsertForWorkspace(ws(), body));
        saved.put("owned", true);
        return saved;
    }

    @PutMapping("/api/connectors/{id}")
    public Map<String, Object> updateForWorkspace(@PathVariable String id, @RequestBody JsonNode body) {
        com.fasterxml.jackson.databind.node.ObjectNode obj = body != null && body.isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) body.deepCopy() : Json.obj();
        obj.put("id", id);
        Map<String, Object> saved = new java.util.LinkedHashMap<>(connectors.upsertForWorkspace(ws(), obj));
        saved.put("owned", true);
        return saved;
    }

    @DeleteMapping("/api/connectors/{id}")
    public Map<String, Object> deleteForWorkspace(@PathVariable String id) {
        connectors.deleteForWorkspace(ws(), id);
        return Map.of("ok", true);
    }

    /** 普通工作空间用户也可以直接自检连接器连通性或试发自定义测试卡片（仅限平台全局或当前工作空间自建连接器）。 */
    @PostMapping("/api/connectors/{id}/test")
    public Map<String, Object> testForWorkspace(@PathVariable String id,
                                                @RequestBody(required = false) JsonNode body) {
        return connectors.testConnectorForWorkspace(ws(), id, body);
    }

    @GetMapping("/api/admin/connectors")
    public List<Map<String, Object>> listAdmin(HttpServletRequest req) {
        admin.requireActor(req, ws());
        return connectors.listAllAdmin();
    }

    @PostMapping("/api/admin/connectors")
    public Map<String, Object> upsertAdmin(@RequestBody JsonNode body, HttpServletRequest req) {
        admin.requireActor(req, ws());
        return connectors.upsert(ws(), body == null ? Json.obj() : body);
    }

    @DeleteMapping("/api/admin/connectors/{id}")
    public Map<String, Object> deleteAdmin(@PathVariable String id, HttpServletRequest req) {
        admin.requireActor(req, ws());
        connectors.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/admin/connectors/{id}/test")
    public Map<String, Object> testAdmin(@PathVariable String id,
                                         @RequestBody(required = false) JsonNode body,
                                         HttpServletRequest req) {
        admin.requireActor(req, ws());
        return connectors.testConnector(id, body);
    }

    @GetMapping("/api/admin/connectors/audits")
    public List<Map<String, Object>> auditsAdmin(@RequestParam(required = false) String connectorId,
                                                 @RequestParam(defaultValue = "30") int limit,
                                                 HttpServletRequest req) {
        admin.requireActor(req, ws());
        return connectors.recentAudits(connectorId, limit);
    }

    // ==================== 应用自动化触发器 ====================

    @GetMapping("/api/apps/{appId}/triggers")
    public List<Map<String, Object>> listTriggers(@PathVariable String appId) {
        return triggers.list(ws(), appId);
    }

    @PostMapping("/api/apps/{appId}/triggers")
    public Map<String, Object> upsertTrigger(@PathVariable String appId, @RequestBody JsonNode body) {
        return triggers.createOrUpdate(ws(), appId, body == null ? Json.obj() : body);
    }

    @DeleteMapping("/api/apps/{appId}/triggers/{triggerId}")
    public Map<String, Object> deleteTrigger(@PathVariable String appId, @PathVariable String triggerId) {
        triggers.delete(ws(), appId, triggerId);
        return Map.of("ok", true);
    }

    @PostMapping("/api/apps/{appId}/triggers/{triggerId}/run")
    public Map<String, Object> runTriggerNow(@PathVariable String appId, @PathVariable String triggerId,
                                             @RequestBody(required = false) JsonNode payload) {
        return triggers.runNow(ws(), appId, triggerId, payload);
    }

    @GetMapping("/api/apps/{appId}/triggers/runs")
    public List<Map<String, Object>> triggerRuns(@PathVariable String appId,
                                                 @RequestParam(required = false) String triggerId,
                                                 @RequestParam(defaultValue = "30") int limit) {
        return triggers.listRuns(ws(), appId, triggerId, limit);
    }

    /** 公开签名入站 Webhook 触发器端点（免浏览器 Cookie，通过 X-Zhiye-Token 或 X-Zhiye-Signature 严格验签）。 */
    @PostMapping("/api/webhooks/triggers/{triggerId}")
    public Map<String, Object> triggerWebhook(@PathVariable String triggerId,
                                              @RequestHeader(value = "X-Zhiye-Token", required = false) String token,
                                              @RequestHeader(value = "X-Zhiye-Signature", required = false) String sig,
                                              @RequestBody(required = false) String rawBody) {
        JsonNode payload = (rawBody == null || rawBody.isBlank()) ? Json.obj() : Json.read(rawBody);
        return triggers.handleWebhookTrigger(triggerId, token, sig, rawBody, payload);
    }

    // ==================== 公域飞书直接控制面 Webhooks & 管理端模拟器 ====================

    /** 公域飞书事件订阅 Webhook（url_verification + im.message.receive_v1 @织页 指令）。 */
    @PostMapping("/api/webhooks/feishu/events")
    public Map<String, Object> feishuEvents(@RequestBody(required = false) JsonNode payload) {
        return feishu.handleFeishuEvent(payload, null);
    }

    /** 公域飞书交互式卡片按钮回调（批准上线 / 驳回）。 */
    @PostMapping("/api/webhooks/feishu/card-action")
    public Map<String, Object> feishuCardAction(@RequestBody(required = false) JsonNode payload) {
        return feishu.handleCardAction(payload);
    }

    @GetMapping("/api/admin/feishu/sessions")
    public List<Map<String, Object>> feishuSessions(@RequestParam(defaultValue = "25") int limit, HttpServletRequest req) {
        admin.requireActor(req, ws());
        return feishu.recentSessions(limit);
    }

    @PostMapping("/api/admin/feishu/simulate")
    public Map<String, Object> simulateFeishuCommand(@RequestBody JsonNode body, HttpServletRequest req) {
        admin.requireActor(req, ws());
        return feishu.handleFeishuEvent(body == null ? Json.obj() : body, ws());
    }

    @PostMapping("/api/admin/feishu/card-action")
    public Map<String, Object> simulateFeishuCardAction(@RequestBody JsonNode body, HttpServletRequest req) {
        admin.requireActor(req, ws());
        return feishu.handleCardAction(body == null ? Json.obj() : body);
    }
}
