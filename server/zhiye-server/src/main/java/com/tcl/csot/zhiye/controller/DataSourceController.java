package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.source.DataSourceService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 数据源控制面：AGW 添加、试调用、绑定到应用（按工作空间鉴权）；手工登记已关闭（403 SOURCE_MANUAL_DISABLED）。 */
@RestController
@Profile("api")
@RequestMapping("/api")
public class DataSourceController {
    private final DataSourceService sources;
    private final CurrentPrincipal principal;

    public DataSourceController(DataSourceService sources, CurrentPrincipal principal) {
        this.sources = sources;
        this.principal = principal;
    }

    private String ws() { return principal.workspaceId(); }

    @GetMapping("/sources")
    public List<Map<String, Object>> list() { return sources.list(ws()); }

    @PostMapping("/sources")
    public Map<String, Object> create(@RequestBody JsonNode body) { return sources.create(ws(), body); }

    @GetMapping("/sources/{id}")
    public Map<String, Object> get(@PathVariable String id) { return sources.get(ws(), id); }

    @PatchMapping("/sources/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody JsonNode body) { return sources.update(ws(), id, body); }

    @DeleteMapping("/sources/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        sources.delete(ws(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sources/{id}/test")
    public Map<String, Object> test(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        return sources.test(ws(), id, body == null ? null : body.get("params"));
    }

    @GetMapping("/sources/{id}/calls")
    public List<Map<String, Object>> calls(@PathVariable String id) { return sources.recentCalls(ws(), id); }

    @GetMapping("/apps/{appId}/sources")
    public List<Map<String, Object>> bound(@PathVariable String appId) { return sources.bound(ws(), appId); }

    @PostMapping("/apps/{appId}/sources")
    public List<Map<String, Object>> bind(@PathVariable String appId, @RequestBody JsonNode body) {
        return sources.bind(ws(), appId, body.path("sourceId").asText(""));
    }

    @DeleteMapping("/apps/{appId}/sources/{sourceId}")
    public List<Map<String, Object>> unbind(@PathVariable String appId, @PathVariable String sourceId) {
        return sources.unbind(ws(), appId, sourceId);
    }
}
