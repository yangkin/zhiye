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

    /** 普通用户生态管理页/聊天栏读取已启用的企业知识库与 MCP 列表（脱敏，无密钥）。 */
    @GetMapping("/api/mcp-servers")
    public List<Map<String, Object>> listForWorkspace() {
        return mcp.listEnabledForWorkspace(principal.workspaceId());
    }

    /** 普通用户自助新增本地知识库或 HTTP MCP 服务（仅归属当前工作空间，免找管理员）。 */
    @PostMapping("/api/mcp-servers")
    public Map<String, Object> createForWorkspace(@RequestBody JsonNode body) {
        return mcp.createForWorkspace(principal.workspaceId(), body);
    }

    /** 普通用户修改自己创建的知识库或 HTTP MCP 服务。 */
    @PutMapping("/api/mcp-servers/{id}")
    public Map<String, Object> updateForWorkspace(@PathVariable String id, @RequestBody JsonNode body) {
        return mcp.updateForWorkspace(principal.workspaceId(), id, body);
    }

    /** 在线探测远端 Streamable HTTP MCP 服务的真实工具列表（tools/list）。 */
    @PostMapping("/api/mcp-servers/discover")
    public Map<String, Object> discoverForWorkspace(@RequestBody JsonNode body) throws Exception {
        principal.workspaceId();
        String url = body == null ? "" : body.path("endpointUrl").asText("").trim();
        String authHeader = body == null ? "Authorization" : body.path("authHeader").asText("Authorization").trim();
        String secret = body == null ? "" : body.path("secret").asText("");
        JsonNode tools = mcp.discoverRemoteTools(url, authHeader, secret);
        return Map.of("ok", true, "tools", tools);
    }

    @DeleteMapping("/api/mcp-servers/{id}")
    public Map<String, Object> deleteForWorkspace(@PathVariable String id) {
        mcp.deleteForWorkspace(principal.workspaceId(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/api/mcp-servers/{key}/test")
    public Map<String, Object> testForWorkspace(@PathVariable String key, @RequestBody(required = false) JsonNode body) {
        principal.workspaceId();
        String tool = body == null ? "" : body.path("tool").asText("").trim();
        JsonNode args = body == null ? null : body.get("arguments");
        return mcp.testForWorkspace(key, tool, args);
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
