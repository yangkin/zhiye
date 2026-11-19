package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.admin.AdminAuthService;
import com.tcl.csot.zhiye.service.ops.OpsLogService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * 平台运维与开发过程日志排查接口：
 * 管理员可跨工作空间排查并查看宿主机运行日志；普通用户强制鉴权隔离到本人工作空间（越权返回 403）。
 */
@RestController
@Profile("api")
@RequestMapping("/api/logs")
public class OpsLogController {
    private final OpsLogService opsLogs;
    private final CurrentPrincipal principal;
    private final AdminAuthService adminAuth;

    public OpsLogController(OpsLogService opsLogs, CurrentPrincipal principal, AdminAuthService adminAuth) {
        this.opsLogs = opsLogs;
        this.principal = principal;
        this.adminAuth = adminAuth;
    }

    @GetMapping("/tasks")
    public Map<String, Object> tasks(@RequestParam(defaultValue = "7d") String range,
                                     @RequestParam(required = false) String startDate,
                                     @RequestParam(required = false) String endDate,
                                     @RequestParam(defaultValue = "ALL") String status,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(required = false) String workspaceId,
                                     @RequestParam(required = false) String userId,
                                     @RequestParam(required = false) String errorCode,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "40") int limit,
                                     @RequestParam(defaultValue = "0") int offset,
                                     HttpServletRequest req) {
        String ws = principal.workspaceId();
        boolean isAdmin = adminAuth.isAdmin(req, ws);
        String effectiveWs = adminAuth.authorizeTaskLogWorkspace(req, ws, workspaceId, userId);
        return opsLogs.listTasks(ws, range, startDate, endDate, status, type, effectiveWs, isAdmin ? userId : null, errorCode, q, limit, offset, isAdmin);
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> taskDetail(@PathVariable String id,
                                          @RequestParam(defaultValue = "800") int tailLines,
                                          HttpServletRequest req) {
        String ws = principal.workspaceId();
        adminAuth.requireTaskAccess(req, ws, id);
        return opsLogs.taskDetail(ws, id, tailLines);
    }

    @GetMapping("/tasks/{id}/events")
    public Map<String, Object> taskEvents(@PathVariable String id,
                                          @RequestParam(defaultValue = "0") int after,
                                          HttpServletRequest req) {
        String ws = principal.workspaceId();
        adminAuth.requireTaskAccess(req, ws, id);
        return opsLogs.taskEventsAfter(id, after);
    }

    @GetMapping("/tasks/{id}/runner-log")
    public Map<String, Object> runnerLog(@PathVariable String id,
                                         @RequestParam(required = false) Integer tail,
                                         @RequestParam(required = false) Integer offset,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) String level,
                                         HttpServletRequest req) {
        String ws = principal.workspaceId();
        adminAuth.requireTaskAccess(req, ws, id);
        return opsLogs.runnerLog(id, tail, offset, q, level);
    }

    @GetMapping("/tasks/{id}/runner-log/download")
    public ResponseEntity<StreamingResponseBody> downloadRunnerLog(@PathVariable String id, HttpServletRequest req) {
        String ws = principal.workspaceId();
        boolean isAdmin = adminAuth.isAdmin(req, ws);
        adminAuth.requireTaskAccess(req, ws, id);
        Path file = opsLogs.runnerLogFilePath(id);
        String safeName = file.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
        opsLogs.recordOpsAudit(isAdmin ? adminAuth.requireAdminActor(req, ws) : "ws:" + ws,
                "RUNNER_LOG_DOWNLOAD", "task", id, Map.of("workspaceId", ws, "admin", isAdmin));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(safeName, StandardCharsets.UTF_8).build());
        headers.setCacheControl("no-store");
        StreamingResponseBody body = out -> opsLogs.streamRedactedLogFile(file, out);
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(body);
    }

    @GetMapping("/functions")
    public Map<String, Object> functions(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String appId,
                                         @RequestParam(required = false) Boolean ok,
                                         @RequestParam(defaultValue = "7d") String range,
                                         @RequestParam(defaultValue = "40") int limit,
                                         @RequestParam(defaultValue = "0") int offset,
                                         HttpServletRequest req) {
        String ws = principal.workspaceId();
        String scopeWs = adminAuth.authorizeAppLogWorkspace(req, ws, appId);
        return opsLogs.listFunctionLogs(q, appId, scopeWs, ok, range, limit, offset);
    }

    @GetMapping("/sources")
    public Map<String, Object> sources(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String appId,
                                       @RequestParam(required = false) Boolean ok,
                                       @RequestParam(required = false) String origin,
                                       @RequestParam(defaultValue = "7d") String range,
                                       @RequestParam(defaultValue = "40") int limit,
                                       @RequestParam(defaultValue = "0") int offset,
                                       HttpServletRequest req) {
        String ws = principal.workspaceId();
        String scopeWs = adminAuth.authorizeAppLogWorkspace(req, ws, appId);
        return opsLogs.listSourceCalls(q, appId, scopeWs, ok, origin, range, limit, offset);
    }

    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "ALL") String category,
                                      @RequestParam(defaultValue = "ALL") String level,
                                      @RequestParam(required = false) String appId,
                                      @RequestParam(required = false) String taskId,
                                      @RequestParam(required = false) Boolean ok,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "7d") String range,
                                      @RequestParam(defaultValue = "50") int limit,
                                      @RequestParam(defaultValue = "0") int offset,
                                      HttpServletRequest req) {
        String ws = principal.workspaceId();
        String scopeWs = adminAuth.authorizeAppLogWorkspace(req, ws, appId);
        return opsLogs.listPlatformEvents(category, level, scopeWs, appId, taskId, ok, q, range, limit, offset);
    }

    @GetMapping("/schemas")
    public Map<String, Object> schemas(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) Boolean ok,
                                       @RequestParam(defaultValue = "7d") String range,
                                       @RequestParam(defaultValue = "50") int limit,
                                       @RequestParam(defaultValue = "0") int offset,
                                       HttpServletRequest req) {
        String ws = principal.workspaceId();
        String scopeWs = adminAuth.isAdmin(req, ws) ? null : ws;
        return opsLogs.listCloudSchemas(scopeWs, q, ok, range, limit, offset);
    }

    @GetMapping("/sandboxes")
    public Map<String, Object> sandboxes(@RequestParam(defaultValue = "7d") String range,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String provider,
                                         @RequestParam(required = false) String workspaceId,
                                         @RequestParam(required = false) Boolean onlyErrors,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "40") int limit,
                                         @RequestParam(defaultValue = "0") int offset,
                                         HttpServletRequest req) {
        String ws = principal.workspaceId();
        String scopeWs = adminAuth.authorizeTaskLogWorkspace(req, ws, workspaceId, null);
        return opsLogs.listSandboxLogs(scopeWs, range, state, provider, onlyErrors, q, limit, offset);
    }

    @GetMapping("/runtime")
    public Map<String, Object> runtime(@RequestParam(defaultValue = "api") String target,
                                       @RequestParam(defaultValue = "400") int tail,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String level,
                                       HttpServletRequest req) {
        String actor = adminAuth.requireAdminActor(req, principal.workspaceId());
        opsLogs.recordOpsAudit(actor, "RUNTIME_LOG_VIEW", "runtime_log", target,
                Map.of("tail", tail, "hasQuery", q != null && !q.isBlank()));
        return opsLogs.runtimeOverview(target, tail, q, level);
    }
}
