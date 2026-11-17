package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.admin.AdminService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 平台管理员治理与运维接口：工作空间配额、应用/站点下线与恢复、任务强停与重跑、社区作品精选/删除、反馈分诊与操作审计。 */
@RestController
@Profile("api")
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService admin;
    private final CurrentPrincipal principal;

    public AdminController(AdminService admin, CurrentPrincipal principal) {
        this.admin = admin;
        this.principal = principal;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.overview(actor);
    }

    @PutMapping("/workspaces/{wsId}/quota")
    public Map<String, Object> updateQuota(@PathVariable String wsId,
                                            @RequestBody(required = false) JsonNode body,
                                            HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.updateQuota(wsId, body == null ? Json.obj() : body, actor);
    }

    @PostMapping("/apps/{id}/unpublish")
    public Map<String, Object> unpublishApp(@PathVariable String id, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.unpublishApp(id, actor);
    }

    @PostMapping("/apps/{id}/restore")
    public Map<String, Object> restoreApp(@PathVariable String id, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.restoreApp(id, actor);
    }

    @DeleteMapping("/apps/{id}")
    public Map<String, Object> deleteApp(@PathVariable String id, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.deleteApp(id, actor);
    }

    @PostMapping("/tasks/{id}/cancel")
    public Map<String, Object> cancelTask(@PathVariable String id, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.cancelTask(id, actor);
    }

    @PostMapping("/tasks/{id}/retry")
    public Map<String, Object> retryTask(@PathVariable String id, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.retryTask(id, actor);
    }

    @PostMapping("/templates/{id}/feature")
    public Map<String, Object> featureTemplate(@PathVariable String id,
                                                @RequestBody(required = false) JsonNode body,
                                                HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.featureTemplate(id, body == null ? Json.obj() : body, actor);
    }

    @DeleteMapping("/templates/{id}")
    public Map<String, Object> deleteTemplate(@PathVariable String id, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.deleteTemplate(id, actor);
    }

    @PatchMapping("/feedbacks/{id}")
    public Map<String, Object> triageFeedback(@PathVariable String id,
                                               @RequestBody(required = false) JsonNode body,
                                               HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.triageFeedback(id, body == null ? Json.obj() : body, actor);
    }

    @PostMapping("/rate-limits/clear")
    public Map<String, Object> clearRateLimits(@RequestBody(required = false) JsonNode body, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.clearRateLimits(body == null ? Json.obj() : body, actor);
    }
}
