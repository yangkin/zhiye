package com.tcl.csot.zhiye.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.runtime.ServiceCrypto;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 企业受控 MCP 注册中心与代理执行服务：
 * - 管理员统一登记/启停企业 MCP 服务（支持生产标准 JSON-RPC 2.0 HTTP MCP 与本地仿真 mock MCP）
 * - 认证凭据使用 AES-256-GCM 密文落库，永不进入沙箱
 * - 沙箱 Runner 通过 ZHIYE_TASK_TOKEN 经由 /internal/mcp/call 代理调用，全部写入 mcp_call_audit 审计日志
 */
@Service
public class McpRegistryService {
    private static final Logger log = LoggerFactory.getLogger(McpRegistryService.class);
    private static final Pattern KEY_RE = Pattern.compile("^[a-z][a-z0-9_-]{1,47}$");

    public record ServerRow(
            String id, String serverKey, String name, String description, String transport,
            String endpointUrl, String authHeader, String secretEnc, JsonNode toolsJson,
            boolean enabled, boolean builtin, String createdBy, String createdAt, String updatedAt
    ) {
        static ServerRow map(ResultSet rs, int i) throws SQLException {
            String tj = rs.getString("tools_json");
            JsonNode tools = tj == null || tj.isBlank() ? Json.M.createArrayNode() : Json.read(tj);
            return new ServerRow(
                    rs.getString("id"), rs.getString("server_key"), rs.getString("name"),
                    rs.getString("description"), rs.getString("transport"), rs.getString("endpoint_url"),
                    rs.getString("auth_header"), rs.getString("secret_enc"), tools,
                    rs.getBoolean("enabled"), rs.getBoolean("builtin"), rs.getString("created_by"),
                    Db.iso(Db.ts(rs, "created_at")), Db.iso(Db.ts(rs, "updated_at"))
            );
        }
    }

    private final JdbcClient jdbc;
    private final ServiceCrypto crypto;
    private final HttpClient http;

    public McpRegistryService(JdbcClient jdbc, ServiceCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.sql("select * from enterprise_mcp_server order by builtin desc, created_at desc")
                .query(ServerRow::map).list().stream().map(this::view).toList();
    }

    /** 面向普通工作空间用户的已启用企业 MCP / 知识库目录（完全脱敏，不含密钥）。 */
    public List<Map<String, Object>> listEnabledForWorkspace(String ws) {
        String ownerTag = ws != null && !ws.isBlank() ? "ws:" + ws : "";
        return jdbc.sql("select * from enterprise_mcp_server where enabled = true order by builtin desc, created_at desc")
                .query(ServerRow::map).list().stream().map(r -> {
                    boolean owned = !ownerTag.isEmpty() && ownerTag.equals(r.createdBy());
                    boolean isKb = "mock".equals(r.transport()) && r.endpointUrl() != null && r.endpointUrl().startsWith("kb:");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.id());
                    m.put("serverKey", r.serverKey());
                    m.put("name", r.name());
                    m.put("description", r.description());
                    m.put("transport", r.transport());
                    m.put("builtin", r.builtin());
                    m.put("owned", owned);
                    m.put("isKnowledgeBase", isKb);
                    m.put("hasSecret", r.secretEnc() != null && !r.secretEnc().isBlank());
                    if (owned) {
                        if (isKb) m.put("knowledgeContent", r.endpointUrl().substring(3));
                        else m.put("endpointUrl", r.endpointUrl());
                    }
                    m.put("tools", r.toolsJson());
                    return m;
                }).toList();
    }

    /** 普通工作空间用户自助新增「本地知识库」或「远端 HTTP MCP 服务」。 */
    public Map<String, Object> createForWorkspace(String ws, JsonNode body) {
        if (ws == null || ws.isBlank()) throw ApiException.validation("缺少工作空间上下文");
        ObjectNode norm = body != null && body.isObject() ? (ObjectNode) body.deepCopy() : Json.obj();
        String name = norm.path("name").asText("").trim();
        if (name.isEmpty()) throw ApiException.validation("名称不能为空");

        // 自动生成合法 serverKey（用户不想手动想英文标识时免填）
        String key = norm.path("serverKey").asText("").trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) slug = "kb";
            key = (slug.length() > 28 ? slug.substring(0, 28) : slug) + "-" + Ids.newId("m").substring(2, 8);
            norm.put("serverKey", key);
        }

        String mode = norm.path("mode").asText("").trim().toLowerCase(Locale.ROOT);
        String kbContent = norm.path("knowledgeContent").asText("").trim();
        if ("knowledge".equals(mode) || !kbContent.isEmpty()) {
            if (kbContent.isEmpty()) throw ApiException.validation("请填写知识库内容（如业务规范、字段字典、SOP 说明等）");
            if (kbContent.length() > 60_000) throw ApiException.validation("单条知识库内容最多 60,000 字");
            norm.put("transport", "mock");
            norm.put("endpointUrl", "kb:" + kbContent);
            if (!norm.has("tools") || !norm.get("tools").isArray() || norm.get("tools").isEmpty()) {
                ArrayNode tools = Json.M.createArrayNode();
                tools.addObject().put("name", "search_knowledge").put("description", "按关键词检索知识库「" + name + "」的内容与规范");
                norm.set("tools", tools);
            }
        } else {
            norm.put("transport", "http");
            String toolNames = norm.path("toolNames").asText("").trim();
            if ((!norm.has("tools") || !norm.get("tools").isArray() || norm.get("tools").isEmpty()) && !toolNames.isEmpty()) {
                ArrayNode tools = Json.M.createArrayNode();
                for (String t : toolNames.split("[,，\\s]+")) {
                    String clean = t.trim();
                    if (!clean.isEmpty()) tools.addObject().put("name", clean).put("description", "调用 MCP 工具 " + clean);
                }
                norm.set("tools", tools);
            }
            // 未手填工具名时，自动向真实 HTTP MCP 服务发 tools/list 自动发现工具
            if (!norm.has("tools") || !norm.get("tools").isArray() || norm.get("tools").isEmpty()) {
                try {
                    JsonNode discovered = discoverRemoteTools(
                            norm.path("endpointUrl").asText("").trim(),
                            norm.path("authHeader").asText("Authorization").trim(),
                            norm.path("secret").asText(""));
                    if (discovered != null && discovered.isArray() && !discovered.isEmpty()) {
                        norm.set("tools", discovered);
                    }
                } catch (Exception e) {
                    log.debug("自动拉取 MCP tools/list 跳过：{}", e.getMessage());
                }
            }
        }
        Map<String, Object> created = new LinkedHashMap<>(create(norm, "ws:" + ws));
        created.put("owned", true);
        created.remove("secretEnc");
        created.remove("authHeader");
        return created;
    }

    /** 普通工作空间用户修改自己创建的知识库或 HTTP MCP 服务。 */
    public Map<String, Object> updateForWorkspace(String ws, String id, JsonNode body) {
        ServerRow cur = findById(id);
        if (cur.builtin() || ws == null || !("ws:" + ws).equals(cur.createdBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MCP_READONLY", "平台内置或管理员托管的 MCP 服务不可修改");
        }
        ObjectNode norm = body != null && body.isObject() ? (ObjectNode) body.deepCopy() : Json.obj();
        String mode = norm.path("mode").asText("").trim().toLowerCase(Locale.ROOT);
        boolean isKb = "knowledge".equals(mode)
                || norm.has("knowledgeContent")
                || ("mock".equals(cur.transport()) && cur.endpointUrl() != null && cur.endpointUrl().startsWith("kb:"));
        if (isKb) {
            String kbContent = norm.path("knowledgeContent").asText("").trim();
            if (kbContent.isEmpty() && cur.endpointUrl() != null && cur.endpointUrl().startsWith("kb:")) {
                kbContent = cur.endpointUrl().substring(3);
            }
            if (kbContent.isEmpty()) throw ApiException.validation("知识库内容不能为空");
            if (kbContent.length() > 60_000) throw ApiException.validation("单条知识库内容最多 60,000 字");
            norm.put("transport", "mock");
            norm.put("endpointUrl", "kb:" + kbContent);
        } else {
            norm.put("transport", "http");
            if (norm.has("toolNames")) {
                String toolNames = norm.path("toolNames").asText("").trim();
                ArrayNode tools = Json.M.createArrayNode();
                for (String t : toolNames.split("[,，\\s]+")) {
                    String clean = t.trim();
                    if (!clean.isEmpty()) tools.addObject().put("name", clean).put("description", "调用 MCP 工具 " + clean);
                }
                if (!tools.isEmpty()) norm.set("tools", tools);
            }
        }
        Map<String, Object> updated = new LinkedHashMap<>(update(id, norm));
        updated.put("owned", true);
        updated.remove("secretEnc");
        updated.remove("authHeader");
        return updated;
    }

    /** 普通工作空间用户删除自己创建的知识库 / MCP（内置或他人的不可删）。 */
    public void deleteForWorkspace(String ws, String id) {
        ServerRow cur = findById(id);
        if (cur.builtin() || ws == null || !("ws:" + ws).equals(cur.createdBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MCP_READONLY", "平台内置或管理员托管的 MCP 服务不可删除");
        }
        delete(id);
    }

    public List<Map<String, Object>> recentAudits(int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        return jdbc.sql("select id, server_key, tool_name, task_id, app_id, ok, duration_ms, error, created_at from mcp_call_audit order by id desc limit ?")
                .param(lim)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("serverKey", rs.getString("server_key"));
                    m.put("toolName", rs.getString("tool_name"));
                    m.put("taskId", rs.getString("task_id"));
                    m.put("appId", rs.getString("app_id"));
                    m.put("ok", rs.getBoolean("ok"));
                    m.put("durationMs", rs.getInt("duration_ms"));
                    m.put("error", rs.getString("error"));
                    m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
                    return m;
                }).list();
    }

    public Map<String, Object> create(JsonNode body, String actor) {
        String key = body.path("serverKey").asText("").trim().toLowerCase(Locale.ROOT);
        if (!KEY_RE.matcher(key).matches()) throw ApiException.validation("服务标识仅支持小写字母、数字、下划线和连字符（2~48 位）");
        String name = body.path("name").asText("").trim();
        if (name.isEmpty() || name.length() > 80) throw ApiException.validation("服务名称长度需在 1~80 字之间");
        String transport = body.path("transport").asText("http").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "mock").contains(transport)) throw ApiException.validation("传输方式仅支持 http 或 mock");
        String url = body.path("endpointUrl").asText("").trim();
        if ("http".equals(transport) && !url.startsWith("http://") && !url.startsWith("https://")) {
            throw ApiException.validation("HTTP MCP 服务必须提供合法的 http:// 或 https:// 地址");
        }
        String desc = body.path("description").asText("").trim();
        String authHeader = body.path("authHeader").asText("Authorization").trim();
        if (authHeader.isEmpty()) authHeader = "Authorization";
        String secret = body.path("secret").asText("");
        String secretEnc = secret.isBlank() ? "" : crypto.encrypt(secret);
        JsonNode tools = body.has("tools") && body.get("tools").isArray() ? body.get("tools") : Json.M.createArrayNode();
        boolean enabled = !body.has("enabled") || body.path("enabled").asBoolean(true);

        long dup = jdbc.sql("select count(*) from enterprise_mcp_server where server_key = ?").param(key).query(Long.class).single();
        if (dup > 0) throw ApiException.conflict("MCP_KEY_TAKEN", "MCP 服务标识「" + key + "」已存在");

        String id = Ids.newId("mcp");
        jdbc.sql("""
                insert into enterprise_mcp_server
                (id, server_key, name, description, transport, endpoint_url, auth_header, secret_enc, tools_json, enabled, builtin, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, false, ?)
                """).params(id, key, name, desc, transport, url, authHeader, secretEnc, Json.write(tools), enabled, actor).update();
        return view(findById(id));
    }

    public Map<String, Object> update(String id, JsonNode body) {
        ServerRow cur = findById(id);
        String name = body.has("name") ? body.path("name").asText("").trim() : cur.name();
        if (name.isEmpty() || name.length() > 80) throw ApiException.validation("服务名称长度需在 1~80 字之间");
        String desc = body.has("description") ? body.path("description").asText("").trim() : cur.description();
        String transport = body.has("transport") ? body.path("transport").asText(cur.transport()).trim().toLowerCase(Locale.ROOT) : cur.transport();
        if (!Set.of("http", "mock").contains(transport)) throw ApiException.validation("传输方式仅支持 http 或 mock");
        String url = body.has("endpointUrl") ? body.path("endpointUrl").asText("").trim() : cur.endpointUrl();
        String authHeader = body.has("authHeader") ? body.path("authHeader").asText("Authorization").trim() : cur.authHeader();
        String secretEnc = cur.secretEnc();
        if (body.has("secret") && !body.path("secret").asText("").isBlank()) {
            secretEnc = crypto.encrypt(body.path("secret").asText(""));
        }
        JsonNode tools = body.has("tools") && body.get("tools").isArray() ? body.get("tools") : cur.toolsJson();
        boolean enabled = body.has("enabled") ? body.path("enabled").asBoolean(cur.enabled()) : cur.enabled();

        jdbc.sql("""
                update enterprise_mcp_server
                set name = ?, description = ?, transport = ?, endpoint_url = ?, auth_header = ?,
                    secret_enc = ?, tools_json = ?::jsonb, enabled = ?, updated_at = now()
                where id = ?
                """).params(name, desc, transport, url, authHeader, secretEnc, Json.write(tools), enabled, id).update();
        return view(findById(id));
    }

    public void delete(String id) {
        ServerRow cur = findById(id);
        if (cur.builtin()) throw ApiException.validation("内置仿真 MCP 服务不支持删除，可以点击停用");
        jdbc.sql("delete from enterprise_mcp_server where id = ?").param(id).update();
    }

    /** 将所有已启用的 MCP 服务清单（脱敏、不含凭据）写入沙箱 {agentDir}/mcp-servers.json。 */
    public void writeCatalogForAgent(Path agentDir) {
        try {
            List<ServerRow> rows = jdbc.sql("select * from enterprise_mcp_server where enabled = true order by builtin desc, created_at desc")
                    .query(ServerRow::map).list();
            ObjectNode root = Json.obj();
            ArrayNode arr = root.putArray("servers");
            for (ServerRow r : rows) {
                ObjectNode s = arr.addObject();
                s.put("key", r.serverKey());
                s.put("name", r.name());
                s.put("description", r.description());
                s.put("transport", r.transport());
                s.set("tools", r.toolsJson() != null ? r.toolsJson() : Json.M.createArrayNode());
            }
            Files.createDirectories(agentDir);
            Files.writeString(agentDir.resolve("mcp-servers.json"), Json.write(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("写入 MCP 清单快照失败：{}", e.getMessage());
        }
    }

    /** 代理执行 MCP 工具调用并记录完整审计日志。 */
    public Map<String, Object> callTool(String serverKey, String toolName, JsonNode arguments, String taskId, String appId) {
        long t0 = System.currentTimeMillis();
        String key = serverKey == null ? "" : serverKey.trim().toLowerCase(Locale.ROOT);
        String tool = toolName == null ? "" : toolName.trim();
        if (key.isEmpty() || tool.isEmpty()) throw ApiException.validation("缺少 serverKey 或 tool");

        ServerRow srv = jdbc.sql("select * from enterprise_mcp_server where server_key = ?")
                .param(key).query(ServerRow::map).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_NOT_FOUND", "未找到 MCP 服务：" + key));
        if (!srv.enabled()) throw new ApiException(HttpStatus.FORBIDDEN, "MCP_DISABLED", "MCP 服务「" + key + "」已被管理员停用");

        try {
            String content = "mock".equals(srv.transport())
                    ? executeMock(srv, tool, arguments)
                    : executeHttpJsonRpc(srv, tool, arguments);
            int ms = (int) (System.currentTimeMillis() - t0);
            audit(key, tool, taskId, appId, true, ms, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("serverKey", key);
            out.put("tool", tool);
            out.put("durationMs", ms);
            out.put("content", content);
            return out;
        } catch (ApiException e) {
            audit(key, tool, taskId, appId, false, (int) (System.currentTimeMillis() - t0), e.getMessage());
            throw e;
        } catch (Exception e) {
            String msg = Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
            audit(key, tool, taskId, appId, false, (int) (System.currentTimeMillis() - t0), msg);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_CALL_FAILED", "调用 MCP 失败：" + msg);
        }
    }

    /** 向远端 Streamable HTTP / JSON-RPC 2.0 MCP 服务发 tools/list 探测，返回标准工具列表。 */
    public JsonNode discoverRemoteTools(String endpointUrl, String authHeader, String secret) throws Exception {
        if (endpointUrl == null || (!endpointUrl.startsWith("http://") && !endpointUrl.startsWith("https://"))) {
            throw ApiException.validation("请提供合法的 http:// 或 https:// MCP 地址");
        }
        String sessionId = tryInitializeSession(endpointUrl, authHeader, secret);
        ObjectNode rpc = Json.obj();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", Ids.newId("rpc"));
        rpc.put("method", "tools/list");
        rpc.set("params", Json.obj());
        JsonNode root = postMcpJsonRpc(endpointUrl, authHeader, secret, sessionId, rpc);
        JsonNode result = root.has("result") ? root.get("result") : root;
        JsonNode tools = result.path("tools");
        ArrayNode out = Json.M.createArrayNode();
        if (tools.isArray()) {
            for (JsonNode t : tools) {
                ObjectNode item = out.addObject();
                item.put("name", t.path("name").asText(""));
                String desc = t.path("description").asText("");
                item.put("description", desc.length() > 160 ? desc.substring(0, 160) + "…" : desc);
            }
        }
        return out;
    }

    /** 面向工作台卡片的连通性试调用：HTTP MCP 默认走真实的 tools/list 协议握手（并自动同步最新工具列表），知识库/仿真 MCP 直接执行工具调用。 */
    public Map<String, Object> testForWorkspace(String serverKey, String toolName, JsonNode arguments) {
        String key = serverKey == null ? "" : serverKey.trim().toLowerCase(Locale.ROOT);
        ServerRow srv = jdbc.sql("select * from enterprise_mcp_server where server_key = ?")
                .param(key).query(ServerRow::map).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_NOT_FOUND", "未找到 MCP 服务：" + key));
        if (!srv.enabled()) throw new ApiException(HttpStatus.FORBIDDEN, "MCP_DISABLED", "MCP 服务已停用");

        if ("http".equals(srv.transport()) && (toolName == null || toolName.isBlank() || "__ping__".equals(toolName))) {
            long t0 = System.currentTimeMillis();
            try {
                String secret = (srv.secretEnc() != null && !srv.secretEnc().isBlank()) ? crypto.decrypt(srv.secretEnc()) : "";
                JsonNode discovered = discoverRemoteTools(srv.endpointUrl(), srv.authHeader(), secret);
                int ms = (int) (System.currentTimeMillis() - t0);
                if (discovered.isArray() && !discovered.isEmpty()) {
                    jdbc.sql("update enterprise_mcp_server set tools_json = ?::jsonb, updated_at = now() where id = ?")
                            .params(Json.write(discovered), srv.id()).update();
                }
                List<String> names = new ArrayList<>();
                if (discovered.isArray()) discovered.forEach(n -> names.add(n.path("name").asText("")));
                audit(key, "tools/list", "ws-test", null, true, ms, null);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("serverKey", key);
                out.put("tool", "tools/list");
                out.put("durationMs", ms);
                out.put("tools", discovered);
                out.put("content", "真实 Streamable HTTP MCP 握手成功，发现 " + names.size() + " 个工具：" + String.join(", ", names.stream().limit(8).toList()));
                return out;
            } catch (Exception e) {
                int ms = (int) (System.currentTimeMillis() - t0);
                String msg = Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
                audit(key, "tools/list", "ws-test", null, false, ms, msg);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_CALL_FAILED", "连接远端 MCP 失败：" + msg);
            }
        }
        String effectiveTool = (toolName == null || toolName.isBlank() || "__ping__".equals(toolName)) ? "search_knowledge" : toolName;
        return callTool(key, effectiveTool, arguments, "ws-test", null);
    }

    private String tryInitializeSession(String endpointUrl, String authHeader, String secret) {
        try {
            ObjectNode init = Json.obj();
            init.put("jsonrpc", "2.0");
            init.put("id", Ids.newId("init"));
            init.put("method", "initialize");
            ObjectNode params = init.putObject("params");
            params.put("protocolVersion", "2025-03-26");
            params.set("capabilities", Json.obj());
            params.putObject("clientInfo").put("name", "zhiye").put("version", "2.0");

            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpointUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", "2025-03-26")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(init), StandardCharsets.UTF_8));
            if (secret != null && !secret.isBlank()) {
                rb.header(authHeader == null || authHeader.isBlank() ? "Authorization" : authHeader, secret);
            }
            HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.headers().firstValue("mcp-session-id").orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode postMcpJsonRpc(String endpointUrl, String authHeader, String secret, String sessionId, ObjectNode rpc) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpointUrl))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-03-26")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(rpc), StandardCharsets.UTF_8));
        if (sessionId != null && !sessionId.isBlank()) {
            rb.header("Mcp-Session-Id", sessionId);
        }
        if (secret != null && !secret.isBlank()) {
            rb.header(authHeader == null || authHeader.isBlank() ? "Authorization" : authHeader, secret);
        }
        HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_UPSTREAM_ERROR", "远端 MCP 返回 HTTP " + res.statusCode());
        }
        String rawBody = res.body() == null ? "" : res.body().trim();
        JsonNode root = parseJsonOrSse(rawBody);
        if (root == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_BAD_PAYLOAD", "远端 MCP 返回了无法解析的响应格式");
        }
        if (root.has("error") && !root.get("error").isNull()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_RPC_ERROR", root.path("error").path("message").asText("MCP 返回错误"));
        }
        return root;
    }

    /** 兼容标准 application/json 与 Streamable HTTP text/event-stream (event: message / data: {...}) 两种 MCP 响应格式。 */
    public static JsonNode parseJsonOrSse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return null;
        if (rawBody.startsWith("{") || rawBody.startsWith("[")) {
            return Json.read(rawBody);
        }
        JsonNode last = null;
        for (String line : rawBody.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                String payload = trimmed.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                try {
                    JsonNode parsed = Json.read(payload);
                    if (parsed != null && parsed.isObject()) last = parsed;
                } catch (Exception ignored) {}
            }
        }
        return last;
    }

    private String executeHttpJsonRpc(ServerRow srv, String tool, JsonNode args) throws Exception {
        String secret = (srv.secretEnc() != null && !srv.secretEnc().isBlank()) ? crypto.decrypt(srv.secretEnc()) : "";
        String sessionId = tryInitializeSession(srv.endpointUrl(), srv.authHeader(), secret);
        ObjectNode rpc = Json.obj();
        rpc.put("jsonrpc", "2.0");
        rpc.put("id", Ids.newId("rpc"));
        rpc.put("method", "tools/call");
        ObjectNode p = rpc.putObject("params");
        p.put("name", tool);
        p.set("arguments", args == null || args.isNull() ? Json.obj() : args);

        JsonNode root = postMcpJsonRpc(srv.endpointUrl(), srv.authHeader(), secret, sessionId, rpc);
        JsonNode result = root.has("result") ? root.get("result") : root;
        if (result.has("content") && result.get("content").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode c : result.get("content")) {
                if (c.has("text")) sb.append(c.get("text").asText()).append('\n');
            }
            if (!sb.isEmpty()) return sb.toString().trim();
        }
        return Json.write(result);
    }

    /** 内置本地仿真 MCP：零外网凭据即可在本地端到端验证编码智能体调用 MCP 获取企业文档/接口契约。 */
    private String executeMock(ServerRow srv, String tool, JsonNode args) {
        String q = args == null ? "" : args.toString().toLowerCase(Locale.ROOT);
        if ("csot-docs-mcp".equals(srv.serverKey())) {
            if ("get_design_tokens".equals(tool)) {
                return """
                        华星内网业务工作台视觉规范（仿真知识库）：
                        - 主色：#6941D8（品牌紫），浅底：#F7F4FE，深墨：#1F1C29
                        - 状态语义：正常/已闭环 #15803D（底 #ECFDF5）、预警/跟进中 #B45309（底 #FFFBEB）、严重/阻断 #DC2626（底 #FEF2F2）
                        - 桌面端规范：全宽流式铺展（禁止外层 max-w-6xl mx-auto），表格包 overflow-x-auto，状态胶囊与操作列加 whitespace-nowrap
                        """.trim();
            }
            if (q.contains("8d") || q.contains("良率") || q.contains("spc")) {
                return """
                        【华星产线良率与 8D 异常闭环规范】
                        1. 关键字段：lotId（批次号）、fab（厂别：T1/T2/T6/T7/T9）、stage（工序：Array/Cell/Module）、yieldRate（良率%）、oee（设备综合效率%）、defectTop（缺陷帕累托分类）
                        2. 8D 流程节点：D1组建小组 → D2问题描述 → D3临时围堵(ICA) → D4根因分析(RCA) → D5永久措施(PCA) → D6验证效果 → D7防再发 → D8结案
                        3. 告警红线：Critical（良率低于目标 1.5% 以上需 2h 内响应该产线责任人）；Warning（连续 3 批下降）
                        """.trim();
            }
            return """
                    【华星企业研发规范摘要（csot-docs-mcp 本地仿真）】
                    - 身份透传：后端服务统一读取网关头 X-Zhiye-User-Id / X-Zhiye-User-Login / X-Zhiye-User-Name / X-Zhiye-User-Admin
                    - 外部接口：只读查询走 AGW 网关（数据源调用名），飞书通知走平台托管 Webhook/OpenAPI 代理
                    - 可用查询关键字：8D、良率、SPC、SSO、AGW、飞书
                    """.trim();
        }
        if ("csot-api-catalog-mcp".equals(srv.serverKey())) {
            if (q.contains("feishu") || q.contains("飞书")) {
                return """
                        飞书开放平台标准契约（公域 open.feishu.cn）：
                        - 群机器人交互卡片：POST https://open.feishu.cn/open-apis/bot/v2/hook/{token}，msg_type="interactive"，加签字段 timestamp + sign(HMAC-SHA256)
                        - 多维表格读写：GET/POST /open-apis/bitable/v1/apps/{app_token}/tables/{table_id}/records
                        """.trim();
            }
            return """
                    华星 MES/EAP 标准接口契约（csot-api-catalog-mcp 本地仿真）：
                    - GET /api/mes/yield/daily?fab=T1&days=7 -> { items: [{ date, fab, stage, inputQty, outputQty, yieldRate, oee }] }
                    - GET /api/eap/equipment/status?fab=T1 -> { items: [{ eqpId, line, status, uptimePct, alarmCount, lastAlarmAt }] }
                    - GET /api/itsm/tickets/mine?loginName=xxx -> { items: [{ ticketNo, title, priority, status, assignee, slaDueAt }] }
                    """.trim();
        }
        if (srv.endpointUrl() != null && srv.endpointUrl().startsWith("kb:")) {
            String body = srv.endpointUrl().substring(3).trim();
            String query = args != null ? args.path("query").asText(args.path("q").asText("")).trim().toLowerCase(Locale.ROOT) : "";
            if (!query.isEmpty() && body.length() > 1500) {
                StringBuilder matched = new StringBuilder();
                for (String para : body.split("\\r?\\n\\s*\\r?\\n")) {
                    if (para.toLowerCase(Locale.ROOT).contains(query)) {
                        if (!matched.isEmpty()) matched.append("\n\n");
                        matched.append(para.trim());
                    }
                }
                if (!matched.isEmpty()) return "【知识库：" + srv.name() + "】\n" + matched;
            }
            return "【知识库：" + srv.name() + "】\n" + (body.length() > 4000 ? body.substring(0, 4000) + "\n…(已截断)" : body);
        }
        return "Mock MCP [" + srv.serverKey() + "/" + tool + "] 调用成功，参数：" + (args == null ? "{}" : Json.write(args));
    }

    private void audit(String serverKey, String tool, String taskId, String appId, boolean ok, int durationMs, String err) {
        try {
            jdbc.sql("insert into mcp_call_audit (server_key, tool_name, task_id, app_id, ok, duration_ms, error) values (?, ?, ?, ?, ?, ?, ?)")
                    .params(serverKey, tool, taskId, appId, ok, durationMs, err == null ? null : (err.length() > 500 ? err.substring(0, 500) : err))
                    .update();
        } catch (Exception ignored) {}
    }

    private ServerRow findById(String id) {
        return jdbc.sql("select * from enterprise_mcp_server where id = ?").param(id)
                .query(ServerRow::map).optional()
                .orElseThrow(() -> ApiException.notFound("MCP 服务"));
    }

    private Map<String, Object> view(ServerRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("serverKey", r.serverKey());
        m.put("name", r.name());
        m.put("description", r.description());
        m.put("transport", r.transport());
        m.put("endpointUrl", r.endpointUrl());
        m.put("authHeader", r.authHeader());
        m.put("hasSecret", r.secretEnc() != null && !r.secretEnc().isBlank());
        m.put("tools", r.toolsJson());
        m.put("enabled", r.enabled());
        m.put("builtin", r.builtin());
        m.put("createdBy", r.createdBy());
        m.put("createdAt", r.createdAt());
        m.put("updatedAt", r.updatedAt());
        return m;
    }
}
