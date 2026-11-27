package com.tcl.csot.zhiye.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.config.SourceProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.runtime.ServiceCrypto;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
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
 * - 管理员与工作空间可登记真实 Streamable HTTP / JSON-RPC 2.0 MCP 服务，以及团队知识库（knowledge）
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
    @Autowired(required = false)
    private SourceProperties sourceProps;

    public McpRegistryService(JdbcClient jdbc, ServiceCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static boolean isWorkspacePrivate(ServerRow r) {
        return r != null && r.createdBy() != null && r.createdBy().startsWith("ws:");
    }

    private static boolean isVisibleToWorkspace(ServerRow r, String ws) {
        if (!isWorkspacePrivate(r)) return true;
        return ws != null && !ws.isBlank() && ("ws:" + ws).equals(r.createdBy());
    }

    public String workspaceOfTaskOrApp(String taskId, String appId) {
        if (appId != null && !appId.isBlank()) {
            var ws = jdbc.sql("select workspace_id from app where id = ?").param(appId).query(String.class).optional();
            if (ws.isPresent()) return ws.get();
        }
        if (taskId != null && !taskId.isBlank()) {
            var ws = jdbc.sql("select a.workspace_id from task t join app a on a.id = t.app_id where t.id = ?")
                    .param(taskId).query(String.class).optional();
            if (ws.isPresent()) return ws.get();
        }
        return null;
    }

    /** 校验远端 HTTP MCP 地址，防止 SSRF 探测云元数据、链路本地及（生产环境）本机回环服务。 */
    public void validateMcpEndpointUrl(String rawUrl, boolean resolveDns) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw ApiException.validation("请提供合法的 http:// 或 https:// MCP 地址");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw ApiException.validation("MCP Endpoint 地址格式无效");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw ApiException.validation("HTTP MCP 服务必须提供合法的 http:// 或 https:// 地址");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isBlank()) {
            throw ApiException.validation("MCP Endpoint 缺少有效主机名");
        }
        boolean allowLoopback = sourceProps != null && sourceProps.allowLoopback();
        if (host.equals("169.254.169.254") || host.equals("100.100.100.200") || host.equals("0.0.0.0")
                || host.equals("::") || host.startsWith("169.254.") || host.startsWith("fe80:")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MCP_HOST_BLOCKED", "SSRF 安全拦截：禁止访问元数据或链路本地地址 " + host);
        }
        if (!allowLoopback && (host.equals("localhost") || host.startsWith("127.") || host.equals("::1") || host.endsWith(".localhost"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MCP_HOST_BLOCKED", "SSRF 安全拦截：生产环境禁止 MCP 访问本机回环地址 " + host);
        }
        if (resolveDns) {
            InetAddress[] addrs;
            try {
                addrs = InetAddress.getAllByName(host);
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_UNREACHABLE", "无法解析 MCP 主机地址：" + host);
            }
            for (InetAddress a : addrs) {
                byte[] b = a.getAddress();
                boolean aliyunMeta = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xff) == 100 && (b[2] & 0xff) == 100 && (b[3] & 0xff) == 200;
                if (a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress() || aliyunMeta) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "MCP_HOST_BLOCKED", "SSRF 安全拦截：目标地址解析到受限网段 " + host);
                }
                if (a.isLoopbackAddress() && !allowLoopback) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "MCP_HOST_BLOCKED", "SSRF 安全拦截：目标地址解析到本机回环地址 " + host);
                }
            }
        }
    }

    public List<Map<String, Object>> listAll() {
        return jdbc.sql("select * from enterprise_mcp_server order by builtin desc, created_at desc")
                .query(ServerRow::map).list().stream().map(this::view).toList();
    }

    /** 面向普通工作空间用户的已启用企业 MCP / 知识库目录（严格按工作空间隔离：仅返回平台全局 + 当前工作空间自建，完全脱敏）。 */
    public List<Map<String, Object>> listEnabledForWorkspace(String ws) {
        String ownerTag = ws != null && !ws.isBlank() ? "ws:" + ws : "";
        return jdbc.sql("""
                select * from enterprise_mcp_server
                where enabled = true
                  and (created_by is null or created_by not like 'ws:%' or created_by = ?)
                order by builtin desc, created_at desc
                """).param(ownerTag)
                .query(ServerRow::map).list().stream().map(r -> {
                    boolean owned = !ownerTag.isEmpty() && ownerTag.equals(r.createdBy());
                    boolean isKb = "knowledge".equals(r.transport()) && r.endpointUrl() != null && r.endpointUrl().startsWith("kb:");
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
            norm.put("transport", "knowledge");
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
                || ("knowledge".equals(cur.transport()) && cur.endpointUrl() != null && cur.endpointUrl().startsWith("kb:"));
        if (isKb) {
            String kbContent = norm.path("knowledgeContent").asText("").trim();
            if (kbContent.isEmpty() && cur.endpointUrl() != null && cur.endpointUrl().startsWith("kb:")) {
                kbContent = cur.endpointUrl().substring(3);
            }
            if (kbContent.isEmpty()) throw ApiException.validation("知识库内容不能为空");
            if (kbContent.length() > 60_000) throw ApiException.validation("单条知识库内容最多 60,000 字");
            norm.put("transport", "knowledge");
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
        if (!Set.of("http", "knowledge").contains(transport)) throw ApiException.validation("传输方式仅支持 http 或 knowledge");
        String url = body.path("endpointUrl").asText("").trim();
        if ("http".equals(transport)) {
            validateMcpEndpointUrl(url, false);
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
        if (!Set.of("http", "knowledge").contains(transport)) throw ApiException.validation("传输方式仅支持 http 或 knowledge");
        String url = body.has("endpointUrl") ? body.path("endpointUrl").asText("").trim() : cur.endpointUrl();
        if ("http".equals(transport)) {
            validateMcpEndpointUrl(url, false);
        }
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
        findById(id);
        jdbc.sql("delete from enterprise_mcp_server where id = ?").param(id).update();
    }

    /** 将平台全局已启用 MCP 清单写入沙箱（仅含全局条目，不含任何个人工作空间私有条目）。 */
    public void writeCatalogForAgent(Path agentDir) {
        writeCatalogForAgent(agentDir, null);
    }

    /** 将对当前工作空间可见的已启用 MCP / 知识库清单（脱敏、不含凭据、严格隔离他人的私有知识库）写入沙箱 {agentDir}/mcp-servers.json。 */
    public void writeCatalogForAgent(Path agentDir, String ws) {
        try {
            String ownerTag = ws != null && !ws.isBlank() ? "ws:" + ws : "";
            List<ServerRow> rows = jdbc.sql("""
                    select * from enterprise_mcp_server
                    where enabled = true
                      and (created_by is null or created_by not like 'ws:%' or created_by = ?)
                    order by builtin desc, created_at desc
                    """).param(ownerTag).query(ServerRow::map).list();
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

    /**
     * 为对话助手或编码智能体生成脱敏的「当前工作空间可用企业 MCP / 知识库清单」提示段落（不含任何 URL/密钥）。
     */
    public String promptGuideForWorkspace(String ws, boolean chatMode) {
        List<Map<String, Object>> servers = listEnabledForWorkspace(ws);
        if (servers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (chatMode) {
            sb.append("\n\n【企业知识库与受控 MCP 工具】（可通过 `mcp_call` 实时查询，严禁凭空编造或声称无法调用 MCP）\n");
            sb.append("当用户询问下方知识库/MCP 可能涵盖的内部系统、名词、文档、规范、数据，或消息中已勾选某个 MCP 时，必须先调用 `mcp_call` 获取真实内容再作答：\n");
        } else {
            sb.append("\n\n## 当前工作空间已授权企业知识库与 MCP 服务（可通过 `mcp_list` / `mcp_call` 免密直连调用）\n");
        }
        for (Map<String, Object> s : servers) {
            String key = String.valueOf(s.get("serverKey"));
            String name = String.valueOf(s.get("name"));
            String transport = String.valueOf(s.get("transport"));
            String desc = s.get("description") == null ? "" : String.valueOf(s.get("description")).trim();
            sb.append("- `").append(key).append("` — **").append(name).append("**（")
                    .append("knowledge".equals(transport) ? "知识库" : "HTTP MCP").append("）");
            if (!desc.isEmpty()) sb.append("：").append(desc.length() > 100 ? desc.substring(0, 100) + "…" : desc);
            Object toolsObj = s.get("tools");
            if (toolsObj instanceof JsonNode tn && tn.isArray() && !tn.isEmpty()) {
                List<String> toolParts = new ArrayList<>();
                for (JsonNode t : tn) {
                    String tName = t.path("name").asText("").trim();
                    if (tName.isEmpty()) continue;
                    String paramsHint = summarizeInputSchema(t.path("inputSchema"));
                    toolParts.add(paramsHint.isEmpty() ? "`" + tName + "`" : "`" + tName + "(" + paramsHint + ")`");
                    if (toolParts.size() >= 6) break;
                }
                if (!toolParts.isEmpty()) sb.append("；工具：").append(String.join("、", toolParts));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 格式化工作空间可见的 MCP 目录（供对话工具 mcp_list 返回）。 */
    public String formatCatalogTextForWorkspace(String ws) {
        List<Map<String, Object>> servers = listEnabledForWorkspace(ws);
        if (servers.isEmpty()) return "当前工作空间未配置或未启用任何企业知识库或 MCP 服务。";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> s : servers) {
            String key = String.valueOf(s.get("serverKey"));
            String name = String.valueOf(s.get("name"));
            String transport = String.valueOf(s.get("transport"));
            String desc = s.get("description") == null ? "" : String.valueOf(s.get("description")).trim();
            sb.append("### ").append(name).append(" (`").append(key).append("` · ")
                    .append("knowledge".equals(transport) ? "知识库" : "http").append(")\n");
            if (!desc.isEmpty()) sb.append(desc).append('\n');
            Object toolsObj = s.get("tools");
            if (toolsObj instanceof JsonNode tn && tn.isArray() && !tn.isEmpty()) {
                for (JsonNode t : tn) {
                    String tName = t.path("name").asText("").trim();
                    if (tName.isEmpty()) continue;
                    String tDesc = t.path("description").asText("").trim();
                    String paramsHint = summarizeInputSchema(t.path("inputSchema"));
                    sb.append("  - `").append(tName).append('`');
                    if (!paramsHint.isEmpty()) sb.append(" 参数(").append(paramsHint).append(')');
                    if (!tDesc.isEmpty()) sb.append("：").append(tDesc);
                    sb.append('\n');
                }
            } else if ("knowledge".equals(transport)) {
                sb.append("  - `search_knowledge` 参数(query)：按关键词检索知识库内容\n");
            } else {
                sb.append("  - （通用工具调用）\n");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String summarizeInputSchema(JsonNode schema) {
        if (schema == null || !schema.isObject()) return "";
        JsonNode props = schema.path("properties");
        if (!props.isObject() || props.isEmpty()) return "";
        Set<String> req = new HashSet<>();
        JsonNode reqNode = schema.path("required");
        if (reqNode.isArray()) reqNode.forEach(n -> req.add(n.asText("")));
        List<String> fields = new ArrayList<>();
        props.fields().forEachRemaining(e -> {
            if (fields.size() >= 6) return;
            String k = e.getKey();
            String type = e.getValue().path("type").asText("");
            fields.add(k + (req.contains(k) ? "*" : "") + (type.isEmpty() ? "" : ":" + type));
        });
        return String.join(", ", fields);
    }

    /** 管理员/受信任内部代理执行 MCP 工具调用并记录完整审计日志。 */
    public Map<String, Object> callTool(String serverKey, String toolName, JsonNode arguments, String taskId, String appId) {
        return callToolInternal(null, false, serverKey, toolName, arguments, taskId, appId);
    }

    /** 工作空间受控代理调用 MCP 工具：严格校验归属权，禁止跨工作空间访问其他员工私有知识库或 MCP。 */
    public Map<String, Object> callToolForWorkspace(String callerWs, String serverKey, String toolName, JsonNode arguments, String taskId, String appId) {
        return callToolInternal(callerWs, true, serverKey, toolName, arguments, taskId, appId);
    }

    private Map<String, Object> callToolInternal(String callerWs, boolean enforceWorkspace,
                                                 String serverKey, String toolName, JsonNode arguments, String taskId, String appId) {
        long t0 = System.currentTimeMillis();
        String rawKey = serverKey == null ? "" : serverKey.trim();
        String key = rawKey.toLowerCase(Locale.ROOT);
        if (key.isEmpty()) throw ApiException.validation("缺少 serverKey");

        Optional<ServerRow> optSrv = jdbc.sql("select * from enterprise_mcp_server where server_key = ?")
                .param(key).query(ServerRow::map).optional();
        // 兼容模型偶尔直接传服务显示名（仅在当前工作空间可见服务里精确匹配）
        if (optSrv.isEmpty()) {
            String ownerTag = callerWs != null && !callerWs.isBlank() ? "ws:" + callerWs : "";
            optSrv = jdbc.sql("""
                    select * from enterprise_mcp_server
                     where lower(name) = ?
                       and (? = false or created_by is null or created_by not like 'ws:%' or created_by = ?)
                     order by enabled desc, created_at desc
                     limit 1
                    """).params(key, enforceWorkspace, ownerTag).query(ServerRow::map).optional();
        }
        ServerRow srv = optSrv.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_NOT_FOUND", "未找到 MCP 服务：" + rawKey));
        key = srv.serverKey();
        if (enforceWorkspace && !isVisibleToWorkspace(srv, callerWs)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MCP_FORBIDDEN", "无权调用其他工作空间的私有知识库或 MCP 服务");
        }
        if (!srv.enabled()) throw new ApiException(HttpStatus.FORBIDDEN, "MCP_DISABLED", "MCP 服务「" + key + "」已被管理员停用");

        String tool = toolName == null ? "" : toolName.trim();
        if (tool.isEmpty()) {
            if ("knowledge".equals(srv.transport())) {
                tool = "search_knowledge";
            } else if (srv.toolsJson() != null && srv.toolsJson().isArray() && srv.toolsJson().size() == 1) {
                tool = srv.toolsJson().get(0).path("name").asText("").trim();
            }
        }
        if (tool.isEmpty()) throw ApiException.validation("缺少 tool（请先通过 mcp_list 查看该 MCP 支持的工具名）");

        try {
            String content = "knowledge".equals(srv.transport())
                    ? executeKnowledgeBase(srv, tool, arguments)
                    : executeHttpJsonRpc(srv, tool, arguments);
            int ms = (int) (System.currentTimeMillis() - t0);
            audit(key, tool, taskId, appId, true, ms, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("serverKey", key);
            out.put("serverName", srv.name());
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

    /** 向远端 Streamable HTTP / JSON-RPC 2.0 MCP 服务发 tools/list 探测，返回标准工具列表（保留紧凑 inputSchema）。 */
    public JsonNode discoverRemoteTools(String endpointUrl, String authHeader, String secret) throws Exception {
        validateMcpEndpointUrl(endpointUrl, true);
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
                JsonNode schema = t.get("inputSchema");
                if (schema != null && schema.isObject()) {
                    String serialized = Json.write(schema);
                    if (serialized.length() <= 1600) {
                        item.set("inputSchema", schema);
                    }
                }
            }
        }
        return out;
    }

    /** 面向工作台卡片的连通性试调用：严格校验工作空间隔离后执行 HTTP tools/list 或知识库检索。 */
    public Map<String, Object> testForWorkspace(String ws, String serverKey, String toolName, JsonNode arguments) {
        String key = serverKey == null ? "" : serverKey.trim().toLowerCase(Locale.ROOT);
        ServerRow srv = jdbc.sql("select * from enterprise_mcp_server where server_key = ?")
                .param(key).query(ServerRow::map).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MCP_NOT_FOUND", "未找到 MCP 服务：" + key));
        if (!isVisibleToWorkspace(srv, ws)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MCP_FORBIDDEN", "无权测试其他工作空间的私有知识库或 MCP 服务");
        }
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
            } catch (ApiException e) {
                int ms = (int) (System.currentTimeMillis() - t0);
                audit(key, "tools/list", "ws-test", null, false, ms, e.getMessage());
                throw e;
            } catch (Exception e) {
                int ms = (int) (System.currentTimeMillis() - t0);
                String msg = Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
                audit(key, "tools/list", "ws-test", null, false, ms, msg);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "MCP_CALL_FAILED", "连接远端 MCP 失败：" + msg);
            }
        }
        String effectiveTool = (toolName == null || toolName.isBlank() || "__ping__".equals(toolName)) ? "search_knowledge" : toolName;
        return callToolForWorkspace(ws, key, effectiveTool, arguments, "ws-test", null);
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
            String sessionId = res.headers().firstValue("mcp-session-id").orElse(null);
            // 严格遵守 MCP 2025-03-26 Streamable HTTP 握手：initialize 后补发 notifications/initialized（尽力发送，失败不阻断）
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                try {
                    ObjectNode notif = Json.obj();
                    notif.put("jsonrpc", "2.0");
                    notif.put("method", "notifications/initialized");
                    HttpRequest.Builder nb = HttpRequest.newBuilder(URI.create(endpointUrl))
                            .timeout(Duration.ofSeconds(6))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .header("MCP-Protocol-Version", "2025-03-26")
                            .POST(HttpRequest.BodyPublishers.ofString(Json.write(notif), StandardCharsets.UTF_8));
                    if (sessionId != null && !sessionId.isBlank()) nb.header("Mcp-Session-Id", sessionId);
                    if (secret != null && !secret.isBlank()) {
                        nb.header(authHeader == null || authHeader.isBlank() ? "Authorization" : authHeader, secret);
                    }
                    http.send(nb.build(), HttpResponse.BodyHandlers.discarding());
                } catch (Exception ignored) {}
            }
            return sessionId;
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
        validateMcpEndpointUrl(srv.endpointUrl(), true);
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

    /** 检索团队私有知识库（真实用户录入的 Markdown / 文档内容，支持多关键词分词相关性排序）。 */
    private String executeKnowledgeBase(ServerRow srv, String tool, JsonNode args) {
        if (srv.endpointUrl() == null || !srv.endpointUrl().startsWith("kb:")) {
            throw ApiException.validation("知识库「" + srv.name() + "」尚未录入正文内容");
        }
        String body = srv.endpointUrl().substring(3).trim();
        String query = "";
        if (args != null && args.isObject()) {
            for (String field : List.of("query", "q", "keyword", "keywords", "search", "topic", "question", "text")) {
                String v = args.path(field).asText("").trim();
                if (!v.isEmpty()) { query = v.toLowerCase(Locale.ROOT); break; }
            }
        }
        if (!query.isEmpty() && body.length() > 6000) {
            final String qFinal = query;
            String[] tokens = Arrays.stream(qFinal.split("[\\s,，、；;]+"))
                    .map(String::trim).filter(s -> s.length() >= 2 || qFinal.length() < 4).toArray(String[]::new);
            if (tokens.length == 0) tokens = new String[]{query};
            record Scored(int score, int idx, String text) {}
            String[] paras = body.split("\\r?\\n\\s*\\r?\\n");
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < paras.length; i++) {
                String p = paras[i].trim();
                if (p.isEmpty()) continue;
                String low = p.toLowerCase(Locale.ROOT);
                int hits = 0;
                if (low.contains(query)) hits += 5;
                for (String tk : tokens) {
                    if (!tk.isEmpty() && low.contains(tk)) hits++;
                }
                if (hits > 0) scored.add(new Scored(hits, i, p));
            }
            if (!scored.isEmpty()) {
                scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparingInt(Scored::idx));
                StringBuilder matched = new StringBuilder();
                for (Scored sc : scored) {
                    if (matched.length() + sc.text().length() > 6000 && !matched.isEmpty()) break;
                    if (!matched.isEmpty()) matched.append("\n\n");
                    matched.append(sc.text());
                }
                return "【知识库：" + srv.name() + "】\n" + matched;
            }
        }
        if (body.length() <= 6000) {
            return "【知识库：" + srv.name() + "（已返回完整正文，共 " + body.length() + " 字，无需重复检索）】\n" + body;
        }
        return "【知识库：" + srv.name() + "】\n" + body.substring(0, 6000) + "\n…(已截断)";
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
