package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.cloud.AccessService;
import com.tcl.csot.zhiye.service.cloud.CloudPolicy;
import com.tcl.csot.zhiye.service.cloud.CloudService;
import com.tcl.csot.zhiye.service.cloud.FunctionService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用云函数：预览 /cloud/{appId}/fn/{name}（应用令牌，执行当前版本的函数，返回日志便于调试）；
 * 发布站点 /__zhiye/fn/{name}（SiteFilter 已核对访问级别和会话，执行线上版本的函数）。
 */
@RestController
@RequestMapping({"/cloud/{appId}/fn", "/__zhiye/fn"})
public class FunctionController {
    private final FunctionService fns;
    private final CloudService cloud;
    private final AccessService access;

    public FunctionController(FunctionService fns, CloudService cloud, AccessService access) {
        this.fns = fns;
        this.cloud = cloud;
        this.access = access;
    }

    @PostMapping("/{name}")
    public Map<String, Object> call(@PathVariable(required = false) String appId, @PathVariable String name,
                                    @RequestBody(required = false) JsonNode body, HttpServletRequest req) {
        JsonNode args = body == null ? null : body.get("args");
        Map<String, Object> out = new LinkedHashMap<>();
        if (appId == null) {
            if (!(req.getAttribute(CloudDataController.ATTR_APP) instanceof String siteApp)) throw ApiException.notFound("应用");
            var r = fns.call(siteApp, fns.versionFor(siteApp, true), name, args, (CloudPolicy.Who) req.getAttribute(CloudDataController.ATTR_WHO));
            out.put("result", r.value());
            return out;
        }
        String header = req.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : header;
        if (!cloud.verifyToken(token).equals(appId)) throw ApiException.notFound("应用");
        var r = fns.call(appId, fns.versionFor(appId, false), name, args, access.previewWho(appId, cloud.tokenRole(token)));
        out.put("result", r.value());
        out.put("logs", r.logs());
        return out;
    }
}
