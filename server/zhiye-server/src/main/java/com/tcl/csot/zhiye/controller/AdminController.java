package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.admin.AdminService;
import com.tcl.csot.zhiye.service.ops.ClusterSandboxService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 平台管理员治理与运维接口：工作空间配额、应用/站点下线与恢复、任务强停与重跑、社区作品精选/删除、反馈分诊与操作审计。 */
@RestController
@Profile("api")
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminService admin;
    private final CurrentPrincipal principal;

    @Autowired(required = false)
    private ClusterSandboxService clusterSandbox;
    @Autowired(required = false)
    private com.tcl.csot.zhiye.service.app.PublishReviewService publishReviews;
    @Autowired(required = false)
    private com.tcl.csot.zhiye.service.cloud.DatabaseQuotaService dbQuota;

    public AdminController(AdminService admin, CurrentPrincipal principal) {
        this.admin = admin;
        this.principal = principal;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.overview(actor, admin.currentUserId(req));
    }

    @PatchMapping("/users/{id}/role")
    public Map<String, Object> updateUserRole(@PathVariable String id,
                                              @RequestBody(required = false) JsonNode body,
                                              HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.updateUserRole(id, body == null ? Json.obj() : body, actor, admin.currentUserId(req));
    }

    @PutMapping("/workspaces/{wsId}/quota")
    public Map<String, Object> updateQuota(@PathVariable String wsId,
                                            @RequestBody(required = false) JsonNode body,
                                            HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.updateQuota(wsId, body == null ? Json.obj() : body, actor);
    }

    @GetMapping("/workspaces/{wsId}/profile")
    public Map<String, Object> workspaceProfile(@PathVariable String wsId, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        return admin.workspaceProfile(wsId);
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

    @DeleteMapping("/apps/{id}/purge")
    public Map<String, Object> purgeApp(@PathVariable String id, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.purgeDeletedApp(id, actor);
    }

    @PatchMapping("/apps/{id}/access")
    public Map<String, Object> updateSiteAccess(@PathVariable String id,
                                                @RequestBody(required = false) JsonNode body,
                                                HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.updateSiteAccess(id, body == null ? Json.obj() : body, actor);
    }

    @PostMapping("/apps/{id}/transfer")
    public Map<String, Object> transferApp(@PathVariable String id,
                                           @RequestBody(required = false) JsonNode body,
                                           HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.transferApp(id, body == null ? Json.obj() : body, actor);
    }

    @PostMapping("/workspaces/handover")
    public Map<String, Object> handoverWorkspace(@RequestBody(required = false) JsonNode body,
                                                 HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.handoverWorkspace(body == null ? Json.obj() : body, actor);
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

    // ---------- 数据库容量监控、扩容与一键限写治理 ----------
    @GetMapping("/database-usage")
    public Map<String, Object> databaseUsage(@RequestParam(defaultValue = "false") boolean scan, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        return dbQuota == null ? Map.of("items", java.util.List.of(), "summary", Map.of(), "defaults", Map.of())
                : dbQuota.adminDatabaseUsage(scan);
    }

    @PostMapping("/database-usage/scan")
    public Map<String, Object> scanDatabaseUsage(HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        if (dbQuota == null) return Map.of("items", java.util.List.of(), "summary", Map.of(), "defaults", Map.of());
        Map<String, Object> scanResult = dbQuota.scanAllSchemas();
        Map<String, Object> usage = new java.util.LinkedHashMap<>(dbQuota.adminDatabaseUsage(false));
        usage.put("scanResult", scanResult);
        return usage;
    }

    @PutMapping("/apps/{id}/db-quota")
    public Map<String, Object> updateAppDbQuota(@PathVariable String id,
                                                @RequestBody(required = false) JsonNode body,
                                                HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.updateAppDbQuota(id, body == null ? Json.obj() : body, actor);
    }

    @GetMapping("/cluster")
    public Map<String, Object> cluster(@RequestParam(defaultValue = "true") boolean withUsage, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        return clusterSandbox == null ? Map.of() : clusterSandbox.clusterOverview(withUsage);
    }

    @GetMapping("/sandboxes")
    public Map<String, Object> sandboxes(@RequestParam(required = false) String kind,
                                         @RequestParam(required = false) String provider,
                                         @RequestParam(required = false) String workspaceId,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "true") boolean withUsage,
                                         HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        return clusterSandbox == null ? Map.of("ok", true, "items", java.util.List.of(), "summary", Map.of())
                : clusterSandbox.listSandboxes(kind, provider, workspaceId, q, withUsage);
    }

    @GetMapping("/sandboxes/{ref}")
    public Map<String, Object> sandboxDetail(@PathVariable String ref, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        if (clusterSandbox == null) return Map.of();
        return clusterSandbox.getSandboxDetail(ref);
    }

    @DeleteMapping("/sandboxes/{ref}")
    public Map<String, Object> destroySandbox(@PathVariable String ref,
                                              @RequestParam(defaultValue = "false") boolean force,
                                              @RequestParam(required = false) String reason,
                                              HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        if (clusterSandbox == null) return Map.of("ok", false);
        return clusterSandbox.destroySandbox(ref, force, actor, reason);
    }

    @PostMapping("/sandboxes/reap")
    public Map<String, Object> reapSandboxes(HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        if (clusterSandbox == null) return Map.of("ok", false);
        return clusterSandbox.reapOrphansAndGhosts(actor);
    }

    @DeleteMapping("/cluster/nodes")
    public Map<String, Object> purgeOfflineNodes(@RequestParam(required = false) String instanceId,
                                                 HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        if (clusterSandbox == null) return Map.of("ok", false, "purgedCount", 0);
        return clusterSandbox.purgeOfflineNodes(instanceId, actor);
    }

    @PostMapping("/cluster/reclaim-leases")
    public Map<String, Object> reclaimExpiredLeases(HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        if (clusterSandbox == null) return Map.of("ok", false, "reclaimedCount", 0);
        return clusterSandbox.reclaimExpiredLeases(actor);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.help.HelpKnowledgeService helpKnowledge;

    @PostMapping("/services/{appId}/{action}")
    public Map<String, Object> controlService(@PathVariable String appId,
                                              @PathVariable String action,
                                              HttpServletRequest req) throws Exception {
        String actor = admin.requireActor(req, principal.workspaceId());
        return admin.controlMonolithService(appId, action, actor);
    }

    // ---------- 发布审核（Vibe Coding 应用登记表审批） ----------
    @GetMapping("/publish-requests")
    public Map<String, Object> listPublishRequests(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String q,
                                                   HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        return publishReviews == null ? Map.of("pendingCount", 0, "items", java.util.List.of())
                : publishReviews.adminList(status, q);
    }

    @PostMapping("/publish-requests/{reqId}/approve")
    public Map<String, Object> approvePublishRequest(@PathVariable String reqId,
                                                     @RequestBody(required = false) JsonNode body,
                                                     HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        String comment = body == null ? "" : body.path("comment").asText("");
        return publishReviews.adminApprove(reqId, comment, actor);
    }

    @PostMapping("/publish-requests/{reqId}/reject")
    public Map<String, Object> rejectPublishRequest(@PathVariable String reqId,
                                                    @RequestBody(required = false) JsonNode body,
                                                    HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        String comment = body == null ? "" : body.path("comment").asText("");
        return publishReviews.adminReject(reqId, comment, actor);
    }

    // ---------- 帮助知识库在线维护（实时同步帮助文档 + 小织机器人） ----------
    @GetMapping("/help-knowledge")
    public Map<String, Object> listHelpKnowledge(HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        return Map.of("items", helpKnowledge == null ? java.util.List.of() : helpKnowledge.adminList());
    }

    @PostMapping("/help-knowledge")
    public Map<String, Object> createHelpKnowledge(@RequestBody JsonNode body, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return helpKnowledge.create(body, actor);
    }

    @PatchMapping("/help-knowledge/{id}")
    public Map<String, Object> updateHelpKnowledge(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return helpKnowledge.update(id, body, actor);
    }

    @DeleteMapping("/help-knowledge/{id}")
    public Map<String, Object> deleteHelpKnowledge(@PathVariable String id, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        helpKnowledge.delete(id);
        return Map.of("ok", true);
    }
}
