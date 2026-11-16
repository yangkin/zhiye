package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.cloud.AccessService;
import com.tcl.csot.zhiye.service.cloud.AppAiService;
import com.tcl.csot.zhiye.service.cloud.CloudPolicy;
import com.tcl.csot.zhiye.service.cloud.CloudService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 应用内 AI：预览 /cloud/{appId}/ai/chat（应用令牌，不要求启用云服务）；发布站点 /__zhiye/ai/chat（SiteFilter 已核对访问级别） */
@RestController
@RequestMapping({"/cloud/{appId}/ai", "/__zhiye/ai"})
public class AppAiController {
    private final AppAiService ai;
    private final CloudService cloud;
    private final AccessService access;

    public AppAiController(AppAiService ai, CloudService cloud, AccessService access) {
        this.ai = ai;
        this.cloud = cloud;
        this.access = access;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@PathVariable(required = false) String appId, @RequestBody JsonNode body, HttpServletRequest req) {
        if (appId == null) {
            if (!(req.getAttribute(CloudDataController.ATTR_APP) instanceof String siteApp)) throw ApiException.notFound("应用");
            return ai.chat(siteApp, (CloudPolicy.Who) req.getAttribute(CloudDataController.ATTR_WHO), body);
        }
        String header = req.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
        if (!cloud.verifyToken(token).equals(appId)) throw ApiException.notFound("应用");
        String role = cloud.tokenRole(token);
        return ai.chat(appId, access.previewWho(appId, role), body);
    }
}
