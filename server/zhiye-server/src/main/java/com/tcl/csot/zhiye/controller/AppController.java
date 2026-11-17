package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.app.AppService;
import com.tcl.csot.zhiye.service.app.StatsService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Profile("api")
@RequestMapping("/api/apps")
public class AppController {
    private final AppService apps;
    private final CurrentPrincipal principal;
    private final StatsService stats;
    private final com.tcl.csot.zhiye.service.workspace.QuotaService quota;
    private final com.tcl.csot.zhiye.config.ZhiyeProperties props;

    public AppController(AppService apps, CurrentPrincipal principal, StatsService stats,
                         com.tcl.csot.zhiye.service.workspace.QuotaService quota, com.tcl.csot.zhiye.config.ZhiyeProperties props) {
        this.stats = stats;
        this.quota = quota;
        this.props = props;
        this.apps = apps;
        this.principal = principal;
    }

    private String ws() { return principal.workspaceId(); }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String q, @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String favorite, @RequestParam(required = false) String published,
                                          @RequestParam(required = false) String sort) {
        return apps.list(ws(), q, type, favorite, published, sort);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody AppService.CreateRequest req,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) throws Exception {
        return apps.create(ws(), req, key);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(stats.summary(ws()));
        long[] usage = quota.appUsage(ws());
        m.put("appLimit", usage[1]);
        m.put("appsUsed", usage[0]);
        m.put("taskLimit", props.workspace().maxConcurrentTasks());
        return m;
    }

    @GetMapping("/{id}/stats")
    public Map<String, Object> stats(@PathVariable String id, @RequestParam(defaultValue = "7d") String range) {
        return stats.stats(ws(), id, range);
    }

    @PostMapping("/{id}/plan-feedback")
    public Map<String, Object> planFeedback(@PathVariable String id, @RequestBody JsonNode body) {
        return apps.planFeedback(ws(), id, body == null ? "" : body.path("content").asText(""));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) { return apps.detail(ws(), id); }

    @PatchMapping("/{id}")
    public Map<String, Object> patch(@PathVariable String id, @RequestBody JsonNode body) { return apps.patch(ws(), id, body); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        apps.delete(ws(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/generate")
    public Map<String, Object> generate(@PathVariable String id, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return apps.generate(ws(), id, key);
    }

    @GetMapping("/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable String id) { return apps.messages(ws(), id); }

    @PostMapping("/{id}/messages")
    public Map<String, Object> post(@PathVariable String id, @RequestBody JsonNode body,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) throws Exception {
        return apps.postMessage(ws(), id, body, key);
    }

    @GetMapping("/{id}/tasks")
    public List<Map<String, Object>> tasks(@PathVariable String id, @RequestParam(defaultValue = "false") boolean active) {
        return apps.tasks(ws(), id, active);
    }

    @GetMapping("/{id}/draft")
    public Map<String, Object> draft(@PathVariable String id) { return apps.draft(ws(), id); }

    @PutMapping("/{id}/draft")
    public Map<String, Object> putDraft(@PathVariable String id, @RequestBody JsonNode body) { return apps.putDraft(ws(), id, body); }

    @DeleteMapping("/{id}/draft")
    public ResponseEntity<Void> deleteDraft(@PathVariable String id) {
        apps.deleteDraft(ws(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/draft/commit")
    public Map<String, Object> commitDraft(@PathVariable String id, @RequestBody(required = false) JsonNode body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return apps.commitDraft(ws(), id, body == null ? Json.obj() : body, key);
    }

    @PostMapping("/{id}/code")
    public Map<String, Object> saveCode(@PathVariable String id, @RequestBody JsonNode body,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return apps.saveCode(ws(), id, body, key);
    }

    @PostMapping("/{id}/publish")
    public Map<String, Object> publish(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        String v = body == null ? null : body.path("versionId").asText(null);
        return apps.publish(ws(), id, v);
    }

    @PostMapping("/{id}/unpublish")
    public ResponseEntity<Void> unpublish(@PathVariable String id) {
        apps.unpublish(ws(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/deployments")
    public List<Map<String, Object>> deployments(@PathVariable String id) { return apps.deployments(ws(), id); }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SkillHubController skillHub;

    /** 兼容前端 SkillhubModal 与既有接口约定：/api/apps/{id}/skillhub/* 委托给 SkillHubController */
    @PostMapping("/{id}/skillhub/publish")
    public Map<String, Object> skillhubPublish(jakarta.servlet.http.HttpServletRequest req, @PathVariable String id,
                                               @RequestBody(required = false) JsonNode body) {
        return skillHub.publish(req, id, body);
    }

    @GetMapping("/{id}/skillhub/publications")
    public List<Map<String, Object>> skillhubPublications(@PathVariable String id) {
        return skillHub.publicationsLegacy(id);
    }
}
