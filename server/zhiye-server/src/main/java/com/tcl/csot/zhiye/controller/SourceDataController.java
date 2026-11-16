package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.source.DataSourceService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.cloud.CloudService;
import com.tcl.csot.zhiye.exception.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 数据源数据面（预览页）：带应用令牌调用 /cloud/{appId}/source/{key}。
 * 发布站点走站点网关同源的 /__zhiye/source/{key}（见 SiteFilter），页面里不放令牌。
 */
@RestController
@RequestMapping("/cloud/{appId}/source")
public class SourceDataController {
    private final DataSourceService sources;
    private final CloudService cloud;

    public SourceDataController(DataSourceService sources, CloudService cloud) {
        this.sources = sources;
        this.cloud = cloud;
    }

    @PostMapping("/{key}")
    public Map<String, Object> query(@PathVariable String appId, @PathVariable String key,
                                     @RequestBody(required = false) JsonNode body,
                                     @RequestHeader(value = "Authorization", required = false) String h) {
        String token = h != null && h.startsWith("Bearer ") ? h.substring(7) : h;
        if (!cloud.verifyToken(token).equals(appId)) throw ApiException.notFound("应用");
        return sources.query(appId, key, body == null ? null : body.get("params"), "preview");
    }
}
