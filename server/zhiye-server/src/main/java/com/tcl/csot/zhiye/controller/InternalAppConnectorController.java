package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.connector.EnterpriseConnectorService;
import com.tcl.csot.zhiye.service.source.DataSourceService;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 单体全栈应用受控连接器与数据源代理网关（同时在 api 与 site Profile 生效）：
 * - 无论应用由 api 实例（:8080）部署还是由 site 网关（:8083）按需冷启动拉起，
 *   均通过 HMAC 签名的 ZHIYE_CONNECTOR_TOKEN 访问 ${ZHIYE_CONNECTOR_API}/...，
 *   实现零明文密钥直连公域飞书多维表格、飞书机器人卡片与企业数据源。
 */
@RestController
public class InternalAppConnectorController {
    private final EnterpriseConnectorService connectors;
    private final DataSourceService sources;

    public InternalAppConnectorController(EnterpriseConnectorService connectors, DataSourceService sources) {
        this.connectors = connectors;
        this.sources = sources;
    }

    @GetMapping("/internal/app-connectors/list")
    public Map<String, Object> internalList(@RequestHeader(value = "Authorization", required = false) String auth) {
        connectors.verifyAppConnectorToken(auth);
        return Map.of("ok", true, "connectors", connectors.list(null));
    }

    @PostMapping("/internal/app-connectors/feishu/send-card")
    @SuppressWarnings("unchecked")
    public Map<String, Object> internalSendCard(@RequestHeader(value = "Authorization", required = false) String auth,
                                                @RequestBody JsonNode b) {
        String appId = connectors.verifyAppConnectorToken(auth);
        List<Map<String, String>> fields = new java.util.ArrayList<>();
        if (b != null && b.has("fields") && b.get("fields").isArray()) {
            b.get("fields").forEach(n -> fields.add(Map.of("label", n.path("label").asText(""), "value", n.path("value").asText(""))));
        }
        List<Map<String, Object>> buttons = new java.util.ArrayList<>();
        if (b != null && b.has("buttons") && b.get("buttons").isArray()) {
            b.get("buttons").forEach(n -> buttons.add(Json.convert(n, Map.class)));
        }
        return connectors.sendFeishuCard(
                b == null ? "" : b.path("connectorId").asText(""),
                appId,
                b == null ? "织页应用通知" : b.path("title").asText("织页应用通知"),
                b == null ? "" : b.path("content").asText(""),
                b == null ? "blue" : b.path("templateColor").asText("blue"),
                fields, buttons);
    }

    @PostMapping("/internal/app-connectors/bitable/{op}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> internalBitable(@RequestHeader(value = "Authorization", required = false) String auth,
                                               @PathVariable String op,
                                               @RequestBody(required = false) JsonNode b) {
        String appId = connectors.verifyAppConnectorToken(auth);
        String connId = b == null ? "" : b.path("connectorId").asText("");
        Map<String, Object> opts = new java.util.LinkedHashMap<>();
        if (b != null && b.has("appToken")) opts.put("appToken", b.path("appToken").asText(""));
        if (b != null && b.has("tableId")) opts.put("tableId", b.path("tableId").asText(""));
        if (b != null && b.has("pageSize")) opts.put("pageSize", b.path("pageSize").asInt(20));
        return switch (op) {
            case "list" -> connectors.bitableList(connId, appId, opts);
            case "create" -> connectors.bitableCreate(connId, appId,
                    b != null && b.has("fields") ? Json.convert(b.get("fields"), Map.class) : Map.of(), opts);
            case "update" -> connectors.bitableUpdate(connId, appId, b == null ? "" : b.path("recordId").asText(""),
                    b != null && b.has("fields") ? Json.convert(b.get("fields"), Map.class) : Map.of(), opts);
            default -> throw com.tcl.csot.zhiye.exception.ApiException.notFound("多维表格操作");
        };
    }

    @PostMapping("/internal/app-connectors/org/employees")
    public Map<String, Object> internalOrgEmployees(@RequestHeader(value = "Authorization", required = false) String auth,
                                                    @RequestBody(required = false) JsonNode b) {
        connectors.verifyAppConnectorToken(auth);
        return Map.of("ok", true, "employees", connectors.searchOrgEmployees(b == null ? "" : b.path("q").asText("")));
    }

    @PostMapping("/internal/app-connectors/source/query")
    public Map<String, Object> internalSourceQuery(@RequestHeader(value = "Authorization", required = false) String auth,
                                                   @RequestBody(required = false) JsonNode b) {
        String appId = connectors.verifyAppConnectorToken(auth);
        String key = b == null ? "" : b.path("key").asText(b.path("name").asText("")).trim();
        JsonNode params = b == null ? null : b.get("params");
        return sources.query(appId, key, params, "service");
    }
}
