package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.TaskRepository;
import com.tcl.csot.zhiye.service.admin.AdminService;
import com.tcl.csot.zhiye.service.llm.TaskTokenService;
import com.tcl.csot.zhiye.service.mcp.McpRegistryService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业受控 MCP 控制器：
 * 1. /api/admin/mcp-servers**：管理员配置、启停、试调用、查看调用审计
 * 2. /internal/mcp/call：供沙箱内 Runner 凭 ZHIYE_TASK_TOKEN 代理调用白名单 MCP（凭据不出服务端）
 */
@RestController
@Profile("api")
public class McpController {
    private final McpRegistryService mcp;
    private final AdminService admin;
    private final CurrentPrincipal principal;
    private final TaskTokenService tokens;
    private final TaskRepository tasks;

    public McpController(McpRegistryService mcp, AdminService admin, CurrentPrincipal principal,
                         TaskTokenService tokens, TaskRepository tasks) {
        this.mcp = mcp;
        this.admin = admin;
        this.principal = principal;
        this.tokens = tokens;
        this.tasks = tasks;
    }

    @GetMapping("/api/admin/mcp-servers")
    public Map<String, Object> list(HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("servers", mcp.listAll());
        out.put("audits", mcp.recentAudits(40));
        return out;
    }

    @PostMapping("/api/admin/mcp-servers")
    public Map<String, Object> create(@RequestBody JsonNode body, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        return mcp.create(body, actor);
    }

    @PutMapping("/api/admin/mcp-servers/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody JsonNode body, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        return mcp.update(id, body);
    }

    @DeleteMapping("/api/admin/mcp-servers/{id}")
    public Map<String, Object> delete(@PathVariable String id, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        mcp.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/admin/mcp-servers/{key}/test")
    public Map<String, Object> test(@PathVariable String key, @RequestBody(required = false) JsonNode body, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        String tool = body == null ? "" : body.path("tool").asText("search_specs");
        JsonNode args = body == null ? null : body.get("arguments");
        return mcp.callTool(key, tool, args, "admin-test", null);
    }

    @PostMapping("/internal/mcp/call")
    public Map<String, Object> proxyCall(@RequestBody JsonNode body,
                                         @RequestHeader(value = "Authorization", required = false) String auth) {
        String raw = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : (auth == null ? "" : auth.trim());
        String taskId = tokens.validate(raw).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TASK_TOKEN", "无效或已过期的任务令牌"));
        String appId = tasks.findById(taskId).map(t -> t.appId()).orElse(null);
        return mcp.callTool(body.path("serverKey").asText(""), body.path("tool").asText(""), body.get("arguments"), taskId, appId);
    }
}
