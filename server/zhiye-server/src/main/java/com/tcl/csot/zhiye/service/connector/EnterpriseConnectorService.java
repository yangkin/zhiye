package com.tcl.csot.zhiye.service.connector;

import com.fasterxml.jackson.databind.JsonNode;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 企业连接器中心 & 公域飞书（open.feishu.cn）生产级运行时客户端：
 * - 支持 feishu_bot（群机器人 Webhook + 签名）、feishu_app（开放平台 IM 交互卡片）、feishu_bitable（多维表格 CRUD）、webhook_http
 * - 支持 live（直连公域 open.feishu.cn / 企内白名单主机）与 sandbox（本地完整模拟闭环，免外部凭证即可全链路验证）
 * - 所有密钥均经 AES-256-GCM 加密存储，绝不进入前端、云函数或 Runner 沙箱
 * - 内置 SSRF 主机校验、每分钟限流与全量调用审计日志
 */
@Service
public class EnterpriseConnectorService {
    private static final Logger log = LoggerFactory.getLogger(EnterpriseConnectorService.class);
    private static final Set<String> ALLOWED_KINDS = Set.of("feishu_bot", "feishu_bitable", "feishu_app", "webhook_http");
    private static final Set<String> DEFAULT_FEISHU_HOSTS = Set.of("open.feishu.cn", "open.larksuite.com");

    public record ConnectorRow(
            String id, String workspaceId, String name, String kind, String mode, boolean enabled,
            JsonNode config, String secretsEnc, int rateLimitPerMin, Instant createdAt, Instant updatedAt
    ) {}

    private record CachedToken(String token, Instant expiresAt) {}
    private record RateWindow(long minuteBucket, AtomicInteger count) {}

    private final JdbcClient jdbc;
    private final ServiceCrypto crypto;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    // 本地沙箱模式下的多维表格模拟存储（connectorId -> recordId -> fields）
    private final ConcurrentHashMap<String, LinkedHashMap<String, Map<String, Object>>> sandboxBitables = new ConcurrentHashMap<>();
    private final String appTokenSigningKey;

    public EnterpriseConnectorService(JdbcClient jdbc, ServiceCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        String envKey = System.getenv("ZHIYE_CONNECTOR_TOKEN_SECRET");
        this.appTokenSigningKey = (envKey != null && !envKey.isBlank()) ? envKey : "zhiye-app-connector-bridge-v1-dev";
        seedSandboxBitable();
    }

    /** 为单体全栈应用签发受控连接器网关令牌（仅标识 appId，不含任何飞书真实密钥）。 */
    public String issueAppConnectorToken(String appId) {
        if (appId == null || appId.isBlank()) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appTokenSigningKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = HexFormat.of().formatHex(mac.doFinal(appId.getBytes(StandardCharsets.UTF_8)));
            return "zct1." + appId + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 校验单体应用连接器网关令牌并返回其 appId。 */
    public String verifyAppConnectorToken(String authHeaderOrToken) {
        String raw = authHeaderOrToken == null ? "" : authHeaderOrToken.trim();
        if (raw.startsWith("Bearer ")) raw = raw.substring(7).trim();
        String[] parts = raw.split("\\.");
        if (parts.length != 3 || !"zct1".equals(parts[0]) || parts[1].isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CONNECTOR_TOKEN", "无效的应用连接器令牌");
        }
        String expected = issueAppConnectorToken(parts[1]);
        if (!java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), raw.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CONNECTOR_TOKEN", "应用连接器令牌验签失败");
        }
        return parts[1];
    }

    /** 将工作空间可用企业连接器清单（完全脱敏，无密钥）写入 .zhiye/connectors.json 供编码智能体查阅。 */
    public void writeConnectorsCatalogForAgent(java.nio.file.Path agentDir, String ws) {
        try {
            List<Map<String, Object>> enabled = list(ws).stream()
                    .filter(c -> Boolean.TRUE.equals(c.get("enabled")))
                    .toList();
            java.nio.file.Files.createDirectories(agentDir);
            java.nio.file.Files.writeString(agentDir.resolve("connectors.json"),
                    Json.write(Map.of("connectors", enabled)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("写入连接器清单失败: {}", e.getMessage());
        }
    }

    /** 为编码智能体提示词生成简洁清晰的「平台已托管企业连接器与调用方式」段落，确保 AI 直接调用真连接器而非瞎写 fetch。 */
    public String promptGuideForAgent(String ws, boolean isService, boolean cloudEnabled) {
        List<Map<String, Object>> enabled = list(ws).stream()
                .filter(c -> Boolean.TRUE.equals(c.get("enabled")))
                .toList();
        if (enabled.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## 平台已托管企业连接器（免密直连，严禁在源码硬编码飞书密钥或前端直连 open.feishu.cn）\n");
        sb.append("当前可用连接器清单（详单见 `.zhiye/connectors.json`）：\n");
        for (Map<String, Object> c : enabled) {
            sb.append("- `").append(c.get("id")).append("` — **").append(c.get("name")).append("**")
                    .append("（类型：`").append(c.get("kind")).append("`，模式：`").append(c.get("mode")).append("`）\n");
        }
        if (isService) {
            sb.append("""
                    - **单体全栈后端调用方式（已自动注入环境变量 `ZHIYE_CONNECTOR_API` 与 `ZHIYE_CONNECTOR_TOKEN`，无需用户手动配密钥）**：
                      - 发飞书卡片：`POST ${ZHIYE_CONNECTOR_API}/feishu/send-card`，Header `Authorization: Bearer ${ZHIYE_CONNECTOR_TOKEN}`，Body `{ connectorId, title, content, templateColor: "blue"|"green"|"orange"|"red", fields: [{label, value}] }`
                      - 飞书多维表格 CRUD：`POST ${ZHIYE_CONNECTOR_API}/bitable/list`、`.../bitable/create`（`{ connectorId, fields }`）、`.../bitable/update`（`{ connectorId, recordId, fields }`）
                      - 查企业员工目录：`POST ${ZHIYE_CONNECTOR_API}/org/employees`（`{ q }`）
                    """);
        }
        if (cloudEnabled || !isService) {
            sb.append("""
                    - **云函数（`functions/*.ts`）调用方式（Deno 宿主已原生注入，零密钥泄露）**：
                      - `await ctx.feishu.sendCard({ connectorId, title, content, templateColor, fields })`
                      - `await ctx.bitable.list(connectorId)` / `await ctx.bitable.create(connectorId, fields)` / `await ctx.bitable.update(connectorId, recordId, fields)`
                      - `await ctx.org.searchEmployees(q)`
                    """);
        }
        return sb.toString();
    }

    private void seedSandboxBitable() {
        LinkedHashMap<String, Map<String, Object>> init = new LinkedHashMap<>();
        init.put("rec_demo_001", new LinkedHashMap<>(Map.of(
                "工单号", "WO-2026-001", "产线", "T9-Array", "状态", "待处理", "负责人", "张工", "良率(%)", 98.4)));
        init.put("rec_demo_002", new LinkedHashMap<>(Map.of(
                "工单号", "WO-2026-002", "产线", "T9-Cell", "状态", "处理中", "负责人", "李工", "良率(%)", 99.2)));
        sandboxBitables.put("conn_feishu_bitable_sandbox", init);
    }

    // ==================== CRUD 管理 ====================

    public List<Map<String, Object>> list(String ws) {
        return jdbc.sql("""
                select * from enterprise_connector
                 where workspace_id is null or workspace_id = ?
                 order by (workspace_id is null) desc, updated_at desc
                """).param(ws == null ? "" : ws)
                .query((rs, i) -> toView(mapRow(rs))).list();
    }

    public List<Map<String, Object>> listAllAdmin() {
        return jdbc.sql("select * from enterprise_connector order by updated_at desc")
                .query((rs, i) -> toView(mapRow(rs))).list();
    }

    public Optional<ConnectorRow> findById(String id) {
        return jdbc.sql("select * from enterprise_connector where id = ?")
                .param(id).query((rs, i) -> mapRow(rs)).optional();
    }

    public Map<String, Object> upsert(String ws, JsonNode body) {
        String id = body.path("id").asText("").trim();
        if (id.isEmpty()) id = Ids.newId("conn");
        String name = body.path("name").asText("").trim();
        if (name.isEmpty()) throw ApiException.validation("连接器名称不能为空");
        String kind = body.path("kind").asText("feishu_bot").trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_KINDS.contains(kind)) {
            throw ApiException.validation("连接器类型不支持：" + kind + "，允许：" + ALLOWED_KINDS);
        }
        String mode = body.path("mode").asText("sandbox").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("live", "sandbox").contains(mode)) mode = "sandbox";
        boolean enabled = body.path("enabled").asBoolean(true);
        int rateLimit = Math.max(5, Math.min(600, body.path("rateLimitPerMin").asInt(60)));
        JsonNode config = body.has("config") && body.get("config").isObject() ? body.get("config") : Json.obj();

        // 保留既有密文，若传入新 secrets 则与旧密文合并后重新加密
        Optional<ConnectorRow> existing = findById(id);
        ObjectNode mergedSecrets = Json.obj();
        if (existing.isPresent() && existing.get().secretsEnc() != null && !existing.get().secretsEnc().isBlank()) {
            try {
                JsonNode old = Json.read(crypto.decrypt(existing.get().secretsEnc()));
                if (old != null && old.isObject()) old.fields().forEachRemaining(e -> mergedSecrets.set(e.getKey(), e.getValue()));
            } catch (Exception ignored) {}
        }
        if (body.has("secrets") && body.get("secrets").isObject()) {
            body.get("secrets").fields().forEachRemaining(e -> {
                String v = e.getValue().asText("");
                if (!v.isBlank()) mergedSecrets.put(e.getKey(), v);
            });
        }
        String secretsEnc = mergedSecrets.isEmpty() ? null : crypto.encrypt(Json.write(mergedSecrets));
        String effectiveWs = body.path("platformWide").asBoolean(false) ? null : (ws != null && !ws.isBlank() ? ws : null);

        jdbc.sql("""
                insert into enterprise_connector
                  (id, workspace_id, name, kind, mode, enabled, config_json, secrets_enc, rate_limit_per_min, updated_at)
                values (?,?,?,?,?,?,?::jsonb,?,?,now())
                on conflict (id) do update
                   set name = excluded.name,
                       kind = excluded.kind,
                       mode = excluded.mode,
                       enabled = excluded.enabled,
                       config_json = excluded.config_json,
                       secrets_enc = excluded.secrets_enc,
                       rate_limit_per_min = excluded.rate_limit_per_min,
                       updated_at = now()
                """).params(id, effectiveWs, name, kind, mode, enabled, Json.write(config), secretsEnc, rateLimit).update();
        tokenCache.remove(id);
        return toView(findById(id).orElseThrow());
    }

    /**
     * 普通工作空间用户自助添加/编辑自己的飞书群机器人或飞书多维表格（仅作用于当前工作空间，绝不能修改平台全局或其他工作空间连接器）。
     * 支持直接粘贴飞书多维表格完整链接（自动解析 appToken 与 tableId），大幅降低使用门槛。
     */
    public Map<String, Object> upsertForWorkspace(String ws, JsonNode body) {
        if (ws == null || ws.isBlank()) throw ApiException.validation("缺少工作空间上下文");
        ObjectNode normalized = body != null && body.isObject() ? (ObjectNode) body.deepCopy() : Json.obj();
        normalized.put("platformWide", false);
        String id = normalized.path("id").asText("").trim();
        if (!id.isEmpty()) {
            Optional<ConnectorRow> existing = findById(id);
            if (existing.isPresent() && !ws.equals(existing.get().workspaceId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "CONNECTOR_READONLY", "平台托管连接器仅管理员可修改，请新建自己的工作空间连接器");
            }
        }
        // 支持用户直接粘贴飞书多维表格浏览器链接，自动提取 appToken & tableId（同时兼容 bitableAppToken/bitableTableId）
        String bitableUrl = normalized.path("bitableUrl").asText("").trim();
        if (!bitableUrl.isEmpty()) {
            ObjectNode cfg = normalized.has("config") && normalized.get("config").isObject()
                    ? (ObjectNode) normalized.get("config") : Json.obj();
            var mApp = java.util.regex.Pattern.compile("/base/([A-Za-z0-9_-]+)").matcher(bitableUrl);
            if (mApp.find()) {
                if (cfg.path("appToken").asText("").isBlank()) cfg.put("appToken", mApp.group(1));
                if (cfg.path("bitableAppToken").asText("").isBlank()) cfg.put("bitableAppToken", mApp.group(1));
            }
            var mTbl = java.util.regex.Pattern.compile("[?&]table=([A-Za-z0-9_-]+)").matcher(bitableUrl);
            if (mTbl.find()) {
                if (cfg.path("tableId").asText("").isBlank()) cfg.put("tableId", mTbl.group(1));
                if (cfg.path("bitableTableId").asText("").isBlank()) cfg.put("bitableTableId", mTbl.group(1));
            }
            normalized.set("config", cfg);
        }
        // 若用户填写了真实的公域飞书 Webhook URL，自动切换为 live 模式；否则允许 sandbox 模式先演练
        String wh = normalized.path("secrets").path("webhookUrl").asText("").trim();
        if (!wh.isEmpty()) {
            validateOutboundUrl(wh, normalized.path("config"));
            if (!normalized.has("mode") || normalized.path("mode").asText("").isBlank()) {
                normalized.put("mode", "live");
            }
        }
        return upsert(ws, normalized);
    }

    /** 普通工作空间用户删除自己创建的连接器（不可删除平台全局连接器）。 */
    public void deleteForWorkspace(String ws, String id) {
        ConnectorRow row = findById(id).orElseThrow(() -> ApiException.notFound("企业连接器"));
        if (ws == null || !ws.equals(row.workspaceId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CONNECTOR_READONLY", "平台全局连接器不可删除");
        }
        delete(id);
    }

    public void delete(String id) {
        jdbc.sql("delete from enterprise_connector where id = ?").param(id).update();
        tokenCache.remove(id);
    }

    public List<Map<String, Object>> recentAudits(String connectorId, int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 200));
        if (connectorId != null && !connectorId.isBlank()) {
            return jdbc.sql("select * from connector_call_audit where connector_id = ? order by created_at desc limit ?")
                    .params(connectorId, lim).query((rs, i) -> mapAudit(rs)).list();
        }
        return jdbc.sql("select * from connector_call_audit order by created_at desc limit ?")
                .param(lim).query((rs, i) -> mapAudit(rs)).list();
    }

    // ==================== 连通性测试 ====================

    public Map<String, Object> testConnector(String connectorId) {
        ConnectorRow conn = requireEnabledConnector(connectorId);
        if ("feishu_bitable".equals(conn.kind())) {
            return bitableList(conn.id(), null, Map.of("pageSize", 5));
        }
        return sendFeishuCard(conn.id(), null,
                "织页 AI 连接器连通性自检",
                "连接器「" + conn.name() + "」（模式：" + conn.mode() + "）连通性验证通过。",
                "blue",
                List.of(Map.of("label", "连接器 ID", "value", conn.id()), Map.of("label", "时间", "value", Instant.now().toString())),
                List.of());
    }

    // ==================== 1. 发送公域飞书交互卡片 ====================

    public Map<String, Object> sendFeishuCard(String connectorId, String appId, String title, String content,
                                              String templateColor, List<Map<String, String>> fields,
                                              List<Map<String, Object>> buttons) {
        ConnectorRow conn = resolveConnector(connectorId, "feishu_bot", "feishu_app");
        enforceRateLimit(conn);
        long t0 = System.currentTimeMillis();

        Map<String, Object> card = buildInteractiveCard(title, content, templateColor, fields, buttons);
        String reqSummary = "title=" + truncate(title, 80) + " mode=" + conn.mode();

        try {
            if ("sandbox".equals(conn.mode())) {
                String msgId = "om_sandbox_" + Ids.newId("msg").replace("msg_", "");
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("ok", true);
                res.put("mode", "sandbox");
                res.put("connectorId", conn.id());
                res.put("messageId", msgId);
                res.put("card", card);
                recordAudit(conn.id(), appId, "feishu.sendCard", "ok", System.currentTimeMillis() - t0, reqSummary, "sandbox messageId=" + msgId, null);
                return res;
            }

            JsonNode secrets = decryptSecrets(conn);
            String webhookUrl = secrets.path("webhookUrl").asText(conn.config().path("webhookUrl").asText("")).trim();
            if ("feishu_bot".equals(conn.kind()) && !webhookUrl.isEmpty()) {
                validateOutboundUrl(webhookUrl, conn.config());
                Map<String, Object> payload = new LinkedHashMap<>();
                String signSecret = secrets.path("webhookSignSecret").asText(secrets.path("webhookSecret").asText("")).trim();
                if (!signSecret.isEmpty()) {
                    long ts = Instant.now().getEpochSecond();
                    payload.put("timestamp", String.valueOf(ts));
                    payload.put("sign", feishuBotSign(ts, signSecret));
                }
                payload.put("msg_type", "interactive");
                payload.put("card", card);
                HttpResponse<String> resp = postJson(webhookUrl, Json.write(payload), Map.of());
                if (resp.statusCode() >= 400) {
                    throw new IllegalStateException("飞书机器人接口返回 HTTP " + resp.statusCode() + ": " + truncate(resp.body(), 300));
                }
                JsonNode parsed = Json.read(resp.body());
                int code = parsed != null ? parsed.path("code").asInt(parsed.path("StatusCode").asInt(0)) : 0;
                if (code != 0) {
                    throw new IllegalStateException("飞书机器人返回错误码 " + code + ": " + parsed.path("msg").asText(resp.body()));
                }
                Map<String, Object> out = Map.of("ok", true, "mode", "live", "connectorId", conn.id(), "response", parsed == null ? resp.body() : parsed);
                recordAudit(conn.id(), appId, "feishu.sendCard", "ok", System.currentTimeMillis() - t0, reqSummary, truncate(resp.body(), 300), null);
                return out;
            }

            // feishu_app：使用 tenant_access_token 调用 open.feishu.cn/open-apis/im/v1/messages
            String apiBase = apiBaseOf(conn);
            String token = getTenantAccessToken(conn, apiBase, secrets);
            String chatId = conn.config().path("defaultChatId").asText("").trim();
            if (chatId.isEmpty()) throw ApiException.validation("飞书应用连接器未配置 defaultChatId");
            String url = apiBase + "/open-apis/im/v1/messages?receive_id_type=chat_id";
            validateOutboundUrl(url, conn.config());
            Map<String, Object> body = Map.of(
                    "receive_id", chatId,
                    "msg_type", "interactive",
                    "content", Json.write(card)
            );
            HttpResponse<String> resp = postJson(url, Json.write(body), Map.of("Authorization", "Bearer " + token));
            JsonNode parsed = Json.read(resp.body());
            if (resp.statusCode() >= 400 || (parsed != null && parsed.path("code").asInt(0) != 0)) {
                throw new IllegalStateException("飞书 IM 发卡失败: " + truncate(resp.body(), 300));
            }
            String messageId = parsed.path("data").path("message_id").asText("");
            recordAudit(conn.id(), appId, "feishu.sendCard", "ok", System.currentTimeMillis() - t0, reqSummary, "messageId=" + messageId, null);
            return Map.of("ok", true, "mode", "live", "connectorId", conn.id(), "messageId", messageId);
        } catch (Exception e) {
            recordAudit(conn.id(), appId, "feishu.sendCard", "error", System.currentTimeMillis() - t0, reqSummary, null, e.getMessage());
            throw e instanceof ApiException ae ? ae : new ApiException(HttpStatus.BAD_GATEWAY, "CONNECTOR_CALL_FAILED", e.getMessage());
        }
    }

    // ==================== 2. 公域飞书多维表格 (Bitable) CRUD ====================

    public Map<String, Object> bitableList(String connectorId, String appId, Map<String, Object> opts) {
        ConnectorRow conn = resolveConnector(connectorId, "feishu_bitable", "feishu_app");
        enforceRateLimit(conn);
        long t0 = System.currentTimeMillis();
        try {
            if ("sandbox".equals(conn.mode())) {
                var store = sandboxBitables.computeIfAbsent(conn.id(), k -> new LinkedHashMap<>());
                List<Map<String, Object>> items = new ArrayList<>();
                synchronized (store) {
                    store.forEach((recId, fields) -> items.add(Map.of("record_id", recId, "fields", new LinkedHashMap<>(fields))));
                }
                recordAudit(conn.id(), appId, "bitable.list", "ok", System.currentTimeMillis() - t0, "sandbox list", "count=" + items.size(), null);
                return Map.of("ok", true, "mode", "sandbox", "connectorId", conn.id(), "total", items.size(), "items", items);
            }

            JsonNode secrets = decryptSecrets(conn);
            String apiBase = apiBaseOf(conn);
            String token = getTenantAccessToken(conn, apiBase, secrets);
            String appToken = optStr(opts, "appToken", conn.config().path("bitableAppToken").asText(conn.config().path("appToken").asText("")));
            String tableId = optStr(opts, "tableId", conn.config().path("bitableTableId").asText(conn.config().path("tableId").asText("")));
            if (appToken.isBlank() || tableId.isBlank()) throw ApiException.validation("缺少多维表格 bitableAppToken 或 bitableTableId");
            int pageSize = Math.max(1, Math.min(100, Integer.parseInt(String.valueOf(opts.getOrDefault("pageSize", 20)))));
            String url = apiBase + "/open-apis/bitable/v1/apps/" + appToken + "/tables/" + tableId + "/records?page_size=" + pageSize;
            validateOutboundUrl(url, conn.config());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = Json.read(resp.body());
            if (resp.statusCode() >= 400 || (parsed != null && parsed.path("code").asInt(0) != 0)) {
                throw new IllegalStateException("多维表格查询失败: " + truncate(resp.body(), 300));
            }
            recordAudit(conn.id(), appId, "bitable.list", "ok", System.currentTimeMillis() - t0, "appToken=" + appToken, "total=" + parsed.path("data").path("total").asInt(0), null);
            return Map.of("ok", true, "mode", "live", "connectorId", conn.id(), "data", parsed.path("data"));
        } catch (Exception e) {
            recordAudit(conn.id(), appId, "bitable.list", "error", System.currentTimeMillis() - t0, "list", null, e.getMessage());
            throw e instanceof ApiException ae ? ae : new ApiException(HttpStatus.BAD_GATEWAY, "BITABLE_FAILED", e.getMessage());
        }
    }

    public Map<String, Object> bitableCreate(String connectorId, String appId, Map<String, Object> fields, Map<String, Object> opts) {
        ConnectorRow conn = resolveConnector(connectorId, "feishu_bitable", "feishu_app");
        enforceRateLimit(conn);
        long t0 = System.currentTimeMillis();
        try {
            if ("sandbox".equals(conn.mode())) {
                var store = sandboxBitables.computeIfAbsent(conn.id(), k -> new LinkedHashMap<>());
                String recId = "rec_" + Ids.newId("bt").replace("bt_", "");
                Map<String, Object> copy = new LinkedHashMap<>(fields == null ? Map.of() : fields);
                synchronized (store) {
                    store.put(recId, copy);
                }
                recordAudit(conn.id(), appId, "bitable.create", "ok", System.currentTimeMillis() - t0, "fields=" + copy.keySet(), "record_id=" + recId, null);
                return Map.of("ok", true, "mode", "sandbox", "connectorId", conn.id(), "record_id", recId, "fields", copy);
            }

            JsonNode secrets = decryptSecrets(conn);
            String apiBase = apiBaseOf(conn);
            String token = getTenantAccessToken(conn, apiBase, secrets);
            String appToken = optStr(opts, "appToken", conn.config().path("bitableAppToken").asText(conn.config().path("appToken").asText("")));
            String tableId = optStr(opts, "tableId", conn.config().path("bitableTableId").asText(conn.config().path("tableId").asText("")));
            String url = apiBase + "/open-apis/bitable/v1/apps/" + appToken + "/tables/" + tableId + "/records";
            validateOutboundUrl(url, conn.config());
            HttpResponse<String> resp = postJson(url, Json.write(Map.of("fields", fields == null ? Map.of() : fields)), Map.of("Authorization", "Bearer " + token));
            JsonNode parsed = Json.read(resp.body());
            if (resp.statusCode() >= 400 || (parsed != null && parsed.path("code").asInt(0) != 0)) {
                throw new IllegalStateException("多维表格写入失败: " + truncate(resp.body(), 300));
            }
            recordAudit(conn.id(), appId, "bitable.create", "ok", System.currentTimeMillis() - t0, "create", truncate(resp.body(), 200), null);
            return Map.of("ok", true, "mode", "live", "connectorId", conn.id(), "data", parsed.path("data"));
        } catch (Exception e) {
            recordAudit(conn.id(), appId, "bitable.create", "error", System.currentTimeMillis() - t0, "create", null, e.getMessage());
            throw e instanceof ApiException ae ? ae : new ApiException(HttpStatus.BAD_GATEWAY, "BITABLE_FAILED", e.getMessage());
        }
    }

    public Map<String, Object> bitableUpdate(String connectorId, String appId, String recordId, Map<String, Object> fields, Map<String, Object> opts) {
        ConnectorRow conn = resolveConnector(connectorId, "feishu_bitable", "feishu_app");
        enforceRateLimit(conn);
        long t0 = System.currentTimeMillis();
        try {
            if ("sandbox".equals(conn.mode())) {
                var store = sandboxBitables.computeIfAbsent(conn.id(), k -> new LinkedHashMap<>());
                Map<String, Object> updated;
                synchronized (store) {
                    Map<String, Object> cur = store.get(recordId);
                    if (cur == null) throw ApiException.notFound("多维表格记录 " + recordId);
                    if (fields != null) cur.putAll(fields);
                    updated = new LinkedHashMap<>(cur);
                }
                recordAudit(conn.id(), appId, "bitable.update", "ok", System.currentTimeMillis() - t0, "recordId=" + recordId, "updated", null);
                return Map.of("ok", true, "mode", "sandbox", "connectorId", conn.id(), "record_id", recordId, "fields", updated);
            }

            JsonNode secrets = decryptSecrets(conn);
            String apiBase = apiBaseOf(conn);
            String token = getTenantAccessToken(conn, apiBase, secrets);
            String appToken = optStr(opts, "appToken", conn.config().path("bitableAppToken").asText(conn.config().path("appToken").asText("")));
            String tableId = optStr(opts, "tableId", conn.config().path("bitableTableId").asText(conn.config().path("tableId").asText("")));
            String url = apiBase + "/open-apis/bitable/v1/apps/" + appToken + "/tables/" + tableId + "/records/" + recordId;
            validateOutboundUrl(url, conn.config());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .PUT(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("fields", fields == null ? Map.of() : fields))))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = Json.read(resp.body());
            if (resp.statusCode() >= 400 || (parsed != null && parsed.path("code").asInt(0) != 0)) {
                throw new IllegalStateException("多维表格更新失败: " + truncate(resp.body(), 300));
            }
            recordAudit(conn.id(), appId, "bitable.update", "ok", System.currentTimeMillis() - t0, "recordId=" + recordId, "ok", null);
            return Map.of("ok", true, "mode", "live", "connectorId", conn.id(), "data", parsed.path("data"));
        } catch (Exception e) {
            recordAudit(conn.id(), appId, "bitable.update", "error", System.currentTimeMillis() - t0, "update", null, e.getMessage());
            throw e instanceof ApiException ae ? ae : new ApiException(HttpStatus.BAD_GATEWAY, "BITABLE_FAILED", e.getMessage());
        }
    }

    // ==================== 3. 企业组织员工目录查询（云函数 ctx.org.searchEmployees） ====================

    public List<Map<String, String>> searchOrgEmployees(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        // 优先查询平台已登录过的真实 SSO 用户表，再辅以示例工号兜底（免登录开发模式亦可用）
        List<Map<String, String>> out = new ArrayList<>();
        try {
            jdbc.sql("""
                    select login_name, coalesce(nullif(display_name, ''), login_name) as name,
                           coalesce(department, '研发中心') as dept
                      from sso_user
                     where lower(login_name) like ? or lower(coalesce(display_name, '')) like ?
                     limit 20
                    """).params("%" + q + "%", "%" + q + "%").query(rs -> {
                out.add(Map.of(
                        "loginName", rs.getString("login_name"),
                        "realName", rs.getString("name"),
                        "department", rs.getString("dept")
                ));
            });
        } catch (Exception ignored) {}
        if (out.isEmpty()) {
            List<Map<String, String>> fallback = List.of(
                    Map.of("loginName", "zhangwei", "realName", "张伟", "department", "制造信息化部"),
                    Map.of("loginName", "lina", "realName", "李娜", "department", "Array 工艺工程部"),
                    Map.of("loginName", "wangqiang", "realName", "王强", "department", "平台运维组")
            );
            for (var e : fallback) {
                if (q.isEmpty() || e.get("loginName").toLowerCase(Locale.ROOT).contains(q)
                        || e.get("realName").toLowerCase(Locale.ROOT).contains(q)
                        || e.get("department").toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    // ==================== 卡片构建 & 内部工具 ====================

    public static Map<String, Object> buildInteractiveCard(String title, String content, String templateColor,
                                                           List<Map<String, String>> fields, List<Map<String, Object>> buttons) {
        String tpl = (templateColor == null || templateColor.isBlank()) ? "blue" : templateColor;
        List<Map<String, Object>> elements = new ArrayList<>();
        if (content != null && !content.isBlank()) {
            elements.add(Map.of("tag", "markdown", "content", content));
        }
        if (fields != null && !fields.isEmpty()) {
            List<Map<String, Object>> fList = new ArrayList<>();
            for (var f : fields) {
                fList.add(Map.of(
                        "is_short", true,
                        "text", Map.of("tag", "lark_md", "content", "**" + f.getOrDefault("label", "") + "**\n" + f.getOrDefault("value", ""))
                ));
            }
            elements.add(Map.of("tag", "div", "fields", fList));
        }
        if (buttons != null && !buttons.isEmpty()) {
            List<Map<String, Object>> actions = new ArrayList<>();
            for (var b : buttons) {
                Map<String, Object> btn = new LinkedHashMap<>();
                btn.put("tag", "button");
                btn.put("text", Map.of("tag", "plain_text", "content", String.valueOf(b.getOrDefault("label", "确认"))));
                btn.put("type", String.valueOf(b.getOrDefault("type", "primary")));
                if (b.containsKey("url") && b.get("url") != null) {
                    btn.put("url", String.valueOf(b.get("url")));
                } else {
                    btn.put("value", b.getOrDefault("value", Map.of()));
                }
                actions.add(btn);
            }
            elements.add(Map.of("tag", "action", "actions", actions));
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("header", Map.of("template", tpl, "title", Map.of("tag", "plain_text", "content", title == null ? "织页 AI 通知" : title)));
        card.put("elements", elements);
        return card;
    }

    private ConnectorRow resolveConnector(String connectorId, String primaryKind, String fallbackKind) {
        if (connectorId != null && !connectorId.isBlank()) {
            return requireEnabledConnector(connectorId);
        }
        Optional<ConnectorRow> found = jdbc.sql("""
                select * from enterprise_connector
                 where enabled = true and kind in (?, ?)
                 order by (mode = 'live') desc, updated_at desc
                 limit 1
                """).params(primaryKind, fallbackKind).query((rs, i) -> mapRow(rs)).optional();
        return found.orElseThrow(() -> ApiException.notFound("可用的 " + primaryKind + " 连接器"));
    }

    private ConnectorRow requireEnabledConnector(String id) {
        ConnectorRow conn = findById(id).orElseThrow(() -> ApiException.notFound("企业连接器"));
        if (!conn.enabled()) throw ApiException.conflict("CONNECTOR_DISABLED", "该企业连接器已禁用：" + conn.name());
        return conn;
    }

    private void enforceRateLimit(ConnectorRow conn) {
        long bucket = Instant.now().getEpochSecond() / 60L;
        RateWindow rw = rateWindows.compute(conn.id(), (k, old) ->
                (old == null || old.minuteBucket() != bucket) ? new RateWindow(bucket, new AtomicInteger(0)) : old);
        if (rw.count().incrementAndGet() > conn.rateLimitPerMin()) {
            recordAudit(conn.id(), null, "rate_limit", "blocked", 0, "exceeded", null, "超出每分钟限流阈值 " + conn.rateLimitPerMin());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CONNECTOR_RATE_LIMITED",
                    "连接器「" + conn.name() + "」触发限流（" + conn.rateLimitPerMin() + " 次/分钟）");
        }
    }

    private String getTenantAccessToken(ConnectorRow conn, String apiBase, JsonNode secrets) throws Exception {
        CachedToken cached = tokenCache.get(conn.id());
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.token();
        }
        String appId = conn.config().path("appId").asText(secrets.path("appId").asText("")).trim();
        String appSecret = secrets.path("appSecret").asText("").trim();
        if (appId.isEmpty() || appSecret.isEmpty()) {
            throw ApiException.validation("生产模式（live）调用公域飞书需配置 appId 与 appSecret");
        }
        String url = apiBase + "/open-apis/auth/v3/tenant_access_token/internal";
        validateOutboundUrl(url, conn.config());
        HttpResponse<String> resp = postJson(url, Json.write(Map.of("app_id", appId, "app_secret", appSecret)), Map.of());
        JsonNode parsed = Json.read(resp.body());
        if (resp.statusCode() >= 400 || parsed == null || parsed.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("获取飞书 tenant_access_token 失败: " + truncate(resp.body(), 300));
        }
        String tok = parsed.path("tenant_access_token").asText("");
        int expireSec = Math.max(60, parsed.path("expire").asInt(7200) - 120);
        tokenCache.put(conn.id(), new CachedToken(tok, Instant.now().plusSeconds(expireSec)));
        return tok;
    }

    public static void validateOutboundUrl(String rawUrl, JsonNode config) {
        try {
            URI u = URI.create(rawUrl);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw ApiException.validation("SSRF 拦截：仅允许 HTTP/HTTPS 协议");
            }
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) throw ApiException.validation("非法目标 URL");
            // 严禁访问云元数据地址与本机回环地址
            if (host.equals("169.254.169.254") || host.equals("localhost") || host.startsWith("127.") || host.equals("::1")) {
                throw ApiException.validation("SSRF 安全拦截：禁止访问元数据或回环地址 " + host);
            }
            Set<String> allowed = new HashSet<>(DEFAULT_FEISHU_HOSTS);
            if (config != null && config.has("allowedHosts") && config.get("allowedHosts").isArray()) {
                config.get("allowedHosts").forEach(h -> allowed.add(h.asText("").trim().toLowerCase(Locale.ROOT)));
            }
            if (!allowed.contains(host)) {
                InetAddress addr = InetAddress.getByName(host);
                if (addr.isLinkLocalAddress() || addr.isLoopbackAddress() || addr.isAnyLocalAddress()) {
                    throw ApiException.validation("SSRF 安全拦截：目标域名解析到受限地址");
                }
                throw ApiException.validation("SSRF 白名单拦截：主机 " + host + " 未在允许列表（默认允许 open.feishu.cn / open.larksuite.com）");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.validation("目标 URL 校验失败：" + e.getMessage());
        }
    }

    private HttpResponse<String> postJson(String url, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json; charset=utf-8");
        headers.forEach(b::header);
        return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode decryptSecrets(ConnectorRow conn) {
        if (conn.secretsEnc() == null || conn.secretsEnc().isBlank()) return Json.obj();
        try {
            JsonNode n = Json.read(crypto.decrypt(conn.secretsEnc()));
            return n == null ? Json.obj() : n;
        } catch (Exception e) {
            return Json.obj();
        }
    }

    private static String apiBaseOf(ConnectorRow conn) {
        String base = conn.config().path("apiBase").asText("https://open.feishu.cn").trim();
        if (base.isEmpty()) base = "https://open.feishu.cn";
        return base.replaceAll("/+$", "");
    }

    private static String feishuBotSign(long timestamp, String secret) throws Exception {
        String stringToSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signData = mac.doFinal(new byte[0]);
        return Base64.getEncoder().encodeToString(signData);
    }

    private void recordAudit(String connectorId, String appId, String action, String status, long durationMs,
                             String reqSummary, String resSummary, String error) {
        try {
            jdbc.sql("""
                    insert into connector_call_audit
                      (id, connector_id, app_id, action, status, duration_ms, request_summary, response_summary, error)
                    values (?,?,?,?,?,?,?,?,?)
                    """).params(Ids.newId("caud"), connectorId, appId, action, status, (int) durationMs,
                    truncate(reqSummary, 400), truncate(resSummary, 400), truncate(error, 1000)).update();
        } catch (Exception ignored) {}
    }

    private ConnectorRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConnectorRow(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("name"),
                rs.getString("kind"), rs.getString("mode"), rs.getBoolean("enabled"),
                Json.read(rs.getString("config_json")), rs.getString("secrets_enc"),
                rs.getInt("rate_limit_per_min"), Db.ts(rs, "created_at"), Db.ts(rs, "updated_at")
        );
    }

    private Map<String, Object> toView(ConnectorRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("workspaceId", r.workspaceId());
        m.put("platformWide", r.workspaceId() == null);
        m.put("name", r.name());
        m.put("kind", r.kind());
        m.put("mode", r.mode());
        m.put("enabled", r.enabled());
        m.put("config", r.config() == null ? Json.obj() : r.config());
        m.put("hasSecrets", r.secretsEnc() != null && !r.secretsEnc().isBlank());
        m.put("rateLimitPerMin", r.rateLimitPerMin());
        m.put("createdAt", Db.iso(r.createdAt()));
        m.put("updatedAt", Db.iso(r.updatedAt()));
        return m;
    }

    private static Map<String, Object> mapAudit(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("connectorId", rs.getString("connector_id"));
        m.put("appId", rs.getString("app_id"));
        m.put("action", rs.getString("action"));
        m.put("status", rs.getString("status"));
        m.put("durationMs", rs.getInt("duration_ms"));
        m.put("requestSummary", rs.getString("request_summary"));
        m.put("responseSummary", rs.getString("response_summary"));
        m.put("error", rs.getString("error"));
        m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
        return m;
    }

    private static String optStr(Map<String, Object> opts, String key, String def) {
        if (opts != null && opts.get(key) != null && !String.valueOf(opts.get(key)).isBlank()) {
            return String.valueOf(opts.get(key)).trim();
        }
        return def == null ? "" : def.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
