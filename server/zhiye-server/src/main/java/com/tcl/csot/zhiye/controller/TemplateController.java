package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.template.TemplateService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 灵感社区：发布作品、浏览、点赞、使用模板（复刻）。 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {
    private final TemplateService templates;
    private final CurrentPrincipal principal;
    private final com.tcl.csot.zhiye.service.auth.AuthService auth;

    public TemplateController(TemplateService templates, CurrentPrincipal principal, com.tcl.csot.zhiye.service.auth.AuthService auth) {
        this.templates = templates;
        this.principal = principal;
        this.auth = auth;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "all") String tab, @RequestParam(defaultValue = "new") String sort,
                                          @RequestParam(required = false) String type, @RequestParam(required = false) String q) {
        return templates.list(principal.workspaceId(), tab, sort, type, q);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return templates.detail(principal.workspaceId(), id);
    }

    @PostMapping("/{id}/like")
    public Map<String, Object> like(@PathVariable String id) {
        return templates.like(principal.workspaceId(), id, true);
    }

    @DeleteMapping("/{id}/like")
    public Map<String, Object> unlike(@PathVariable String id) {
        return templates.like(principal.workspaceId(), id, false);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody JsonNode body, jakarta.servlet.http.HttpServletRequest req) throws Exception {
        String author = auth.ssoEnabled() ? auth.currentUser(req).map(u -> u.realName() == null || u.realName().isBlank() ? u.loginName() : u.realName()).orElse(null) : null;
        return templates.create(principal.workspaceId(), body.path("appId").asText(""),
                body.path("name").asText(null), body.path("description").asText(null), author);
    }

    @PostMapping("/{id}/clone")
    public Map<String, Object> cloneApp(@PathVariable String id, @RequestBody(required = false) JsonNode body,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        String name = body == null ? null : body.path("name").asText(null);
        return templates.cloneApp(principal.workspaceId(), id, name, idemKey);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable String id) {
        templates.remove(principal.workspaceId(), id);
        return Map.of("ok", true);
    }
}
