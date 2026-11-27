package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.cloud.CloudService;
import com.tcl.csot.zhiye.service.connector.EnterpriseConnectorService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网页应用前端免后端直连飞书交互卡片接口（同时在 api 与 site Profile 生效）：
 * - 预览态：POST /cloud/{appId}/feishu/send-card（带短时效应用令牌，无需开启云服务，也无需编写后端/云函数）
 * - 发布站点：POST /__zhiye/feishu/send-card（同源 + X-Zhiye-Request 防 CSRF + SiteFilter 访问权限与构建产物校验）
 * - 严格按 appId 所属工作空间隔离企业连接器、执行限流并落库审计，Webhook 地址与签名密钥永不进入前端或源码。
 */
@RestController
@RequestMapping({"/cloud/{appId}/feishu", "/__zhiye/feishu"})
public class AppFeishuController {
    private final EnterpriseConnectorService connectors;
    private final CloudService cloud;

    public AppFeishuController(EnterpriseConnectorService connectors, CloudService cloud) {
        this.connectors = connectors;
        this.cloud = cloud;
    }

    @PostMapping("/send-card")
    public Map<String, Object> sendCard(@PathVariable(required = false) String appId,
                                        @RequestBody(required = false) JsonNode body,
                                        HttpServletRequest req) {
        String resolvedAppId;
        if (appId == null) {
            if (!(req.getAttribute(CloudDataController.ATTR_APP) instanceof String siteApp) || siteApp.isBlank()) {
                throw ApiException.notFound("应用");
            }
            resolvedAppId = siteApp;
        } else {
            String header = req.getHeader("Authorization");
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
            if (!cloud.verifyToken(token).equals(appId)) {
                throw ApiException.notFound("应用");
            }
            resolvedAppId = appId;
        }
        Map<String, Object> raw = connectors.sendFeishuCardFromPayload(resolvedAppId, body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE.equals(raw.get("ok")));
        out.put("mode", raw.getOrDefault("mode", "live"));
        out.put("connectorId", raw.getOrDefault("connectorId", ""));
        out.put("connectorName", raw.getOrDefault("connectorName", ""));
        if (raw.containsKey("messageId")) out.put("messageId", raw.get("messageId"));
        return out;
    }
}
