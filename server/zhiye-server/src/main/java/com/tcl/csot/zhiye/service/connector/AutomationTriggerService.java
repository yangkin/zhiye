package com.tcl.csot.zhiye.service.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.service.cloud.CloudPolicy;
import com.tcl.csot.zhiye.service.cloud.FunctionService;
import com.tcl.csot.zhiye.service.runtime.ServiceCrypto;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自动化触发器引擎（Phase 4）：
 * - Cron 定时调度（支持标准 5 段 cron，如 *\/5 * * * *、0 9 * * *）
 * - Cloud DB 事件钩子（db_insert / db_update，异步非阻塞触发）
 * - 签名校验入站 Webhook（POST /api/webhooks/triggers/{triggerId}）
 * - 动作执行器：调用 Deno 云函数（cloud_function）或发送公域飞书卡片（feishu_card）
 */
@Service
public class AutomationTriggerService {
    private static final Logger log = LoggerFactory.getLogger(AutomationTriggerService.class);
    private static final Set<String> ALLOWED_KINDS = Set.of("cron", "db_insert", "db_update", "webhook");
    private static final Set<String> ALLOWED_ACTIONS = Set.of("cloud_function", "feishu_card");

    public record TriggerRow(
            String id, String appId, String workspaceId, String name, String kind, boolean enabled,
            String cronExpr, String tableName, String webhookSecretEnc, String actionType, JsonNode actionConfig,
            Instant lastRunAt, String lastStatus, String lastError, long runCount, Instant createdAt, Instant updatedAt
    ) {}

    private final JdbcClient jdbc;
    private final AppRepository apps;
    private final ServiceCrypto crypto;
    private final EnterpriseConnectorService connectors;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private FunctionService functions;
    private final ConcurrentHashMap<String, Long> lastCronMinute = new ConcurrentHashMap<>();

    public AutomationTriggerService(JdbcClient jdbc, AppRepository apps, ServiceCrypto crypto,
                                    EnterpriseConnectorService connectors) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.crypto = crypto;
        this.connectors = connectors;
    }

    // ==================== CRUD ====================

    public List<Map<String, Object>> list(String ws, String appId) {
        requireApp(ws, appId);
        return jdbc.sql("select * from app_trigger where app_id = ? order by created_at desc")
                .param(appId).query((rs, i) -> toView(mapRow(rs))).list();
    }

    public List<Map<String, Object>> listRuns(String ws, String appId, String triggerId, int limit) {
        requireApp(ws, appId);
        int lim = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        if (triggerId != null && !triggerId.isBlank()) {
            return jdbc.sql("select * from app_trigger_run where app_id = ? and trigger_id = ? order by created_at desc limit ?")
                    .params(appId, triggerId, lim).query((rs, i) -> mapRun(rs)).list();
        }
        return jdbc.sql("select * from app_trigger_run where app_id = ? order by created_at desc limit ?")
                .params(appId, lim).query((rs, i) -> mapRun(rs)).list();
    }

    public Map<String, Object> createOrUpdate(String ws, String appId, JsonNode body) {
        AppRow app = requireApp(ws, appId);
        String id = body.path("id").asText("").trim();
        boolean isNew = id.isEmpty();
        if (isNew) id = Ids.newId("trg");
        String name = body.path("name").asText("").trim();
        if (name.isEmpty()) throw ApiException.validation("触发器名称不能为空");
        String kind = body.path("kind").asText("cron").trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_KINDS.contains(kind)) throw ApiException.validation("触发类型仅支持：" + ALLOWED_KINDS);
        String actionType = body.path("actionType").asText("feishu_card").trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_ACTIONS.contains(actionType)) throw ApiException.validation("动作类型仅支持：" + ALLOWED_ACTIONS);
        boolean enabled = body.path("enabled").asBoolean(true);
        String cronExpr = body.path("cronExpr").asText("").trim();
        String tableName = body.path("tableName").asText("").trim();
        if ("cron".equals(kind)) {
            if (cronExpr.isEmpty()) cronExpr = "*/5 * * * *";
            validateCron(cronExpr);
        }
        if (("db_insert".equals(kind) || "db_update".equals(kind)) && tableName.isEmpty()) {
            throw ApiException.validation("数据库事件触发器必须指定监听表名 tableName");
        }
        JsonNode actionConfig = body.has("actionConfig") && body.get("actionConfig").isObject() ? body.get("actionConfig") : Json.obj();

        String generatedSecret = null;
        String whEnc = null;
        if (!isNew) {
            whEnc = jdbc.sql("select webhook_secret_enc from app_trigger where id = ? and app_id = ?")
                    .params(id, app.id()).query(String.class).optional().orElse(null);
        }
        String providedSecret = body.path("webhookSecret").asText("").trim();
        if (!providedSecret.isEmpty()) {
            whEnc = crypto.encrypt(providedSecret);
        } else if ("webhook".equals(kind) && (whEnc == null || whEnc.isBlank())) {
            generatedSecret = "trgsec_" + Ids.newId("wh").replace("wh_", "");
            whEnc = crypto.encrypt(generatedSecret);
        }

        jdbc.sql("""
                insert into app_trigger
                  (id, app_id, workspace_id, name, kind, enabled, cron_expr, table_name, webhook_secret_enc, action_type, action_config, updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?::jsonb,now())
                on conflict (id) do update
                   set name = excluded.name,
                       kind = excluded.kind,
                       enabled = excluded.enabled,
                       cron_expr = excluded.cron_expr,
                       table_name = excluded.table_name,
                       webhook_secret_enc = excluded.webhook_secret_enc,
                       action_type = excluded.action_type,
                       action_config = excluded.action_config,
                       updated_at = now()
                """).params(id, app.id(), ws, name, kind, enabled, cronExpr, tableName, whEnc, actionType, Json.write(actionConfig)).update();

        TriggerRow saved = findById(id).orElseThrow();
        Map<String, Object> view = new LinkedHashMap<>(toView(saved));
        if (generatedSecret != null) view.put("generatedWebhookSecret", generatedSecret);
        return view;
    }

    public void delete(String ws, String appId, String triggerId) {
        requireApp(ws, appId);
        jdbc.sql("delete from app_trigger where id = ? and app_id = ?").params(triggerId, appId).update();
    }

    // ==================== 1. 手动测试执行 ====================

    public Map<String, Object> runNow(String ws, String appId, String triggerId, JsonNode payload) {
        requireApp(ws, appId);
        TriggerRow t = findById(triggerId).filter(r -> r.appId().equals(appId))
                .orElseThrow(() -> ApiException.notFound("自动化触发器"));
        return executeTrigger(t, "manual_test", payload == null ? Json.obj() : payload);
    }

    // ==================== 2. 入站签名 Webhook 触发 ====================

    public Map<String, Object> handleWebhookTrigger(String triggerId, String tokenHeader, String sigHeader,
                                                    String rawBody, JsonNode payload) {
        TriggerRow t = findById(triggerId).orElseThrow(() -> ApiException.notFound("自动化触发器"));
        if (!"webhook".equals(t.kind())) throw ApiException.validation("该触发器不是 Webhook 类型");
        if (!t.enabled()) throw ApiException.conflict("TRIGGER_DISABLED", "该触发器已禁用");
        if (t.webhookSecretEnc() == null || t.webhookSecretEnc().isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_UNCONFIGURED", "触发器未配置签名密钥");
        }
        String secret = crypto.decrypt(t.webhookSecretEnc());
        boolean verified = false;
        if (tokenHeader != null && !tokenHeader.isBlank()) {
            verified = MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), tokenHeader.trim().getBytes(StandardCharsets.UTF_8));
        } else if (sigHeader != null && sigHeader.startsWith("sha256=")) {
            String expected = "sha256=" + hmacSha256Hex(secret, rawBody == null ? "" : rawBody);
            verified = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sigHeader.trim().getBytes(StandardCharsets.UTF_8));
        }
        if (!verified) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE", "Webhook 签名或 Token 校验失败");
        }
        return executeTrigger(t, "webhook", payload == null ? Json.obj() : payload);
    }

    // ==================== 3. Cloud DB 行写入异步钩子 ====================

    public void onCloudRowEvent(String appId, String table, String eventKind, Map<String, Object> row) {
        if (appId == null || table == null) return;
        List<TriggerRow> matching;
        try {
            matching = jdbc.sql("select * from app_trigger where app_id = ? and kind = ? and enabled = true and lower(table_name) = lower(?)")
                    .params(appId, eventKind, table).query((rs, i) -> mapRow(rs)).list();
        } catch (Exception e) {
            return;
        }
        if (matching.isEmpty()) return;
        Map<String, Object> evtPayload = Map.of("event", eventKind, "table", table, "row", row == null ? Map.of() : row);
        JsonNode payloadNode = Json.read(Json.write(evtPayload));
        for (TriggerRow t : matching) {
            CompletableFuture.runAsync(() -> {
                try {
                    executeTrigger(t, eventKind + ":" + table, payloadNode);
                } catch (Exception ex) {
                    log.warn("DB 触发器执行异常 trigger={}: {}", t.id(), ex.getMessage());
                }
            });
        }
    }

    // ==================== 4. Cron 定时调度器 ====================

    @Scheduled(fixedDelayString = "${zhiye.triggers.cron-poll-ms:30000}")
    public void pollCronTriggers() {
        List<TriggerRow> crons;
        try {
            crons = jdbc.sql("select * from app_trigger where kind = 'cron' and enabled = true")
                    .query((rs, i) -> mapRow(rs)).list();
        } catch (Exception e) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        long minuteKey = now.toEpochSecond() / 60L;
        for (TriggerRow t : crons) {
            if (!cronMatches(t.cronExpr(), now)) continue;
            Long prev = lastCronMinute.put(t.id(), minuteKey);
            if (prev != null && prev == minuteKey) continue;
            CompletableFuture.runAsync(() -> {
                try {
                    executeTrigger(t, "cron:" + t.cronExpr(), Json.read(Json.write(Map.of("triggeredAt", now.toInstant().toString(), "cron", t.cronExpr()))));
                } catch (Exception ex) {
                    log.warn("Cron 触发器执行失败 trigger={}: {}", t.id(), ex.getMessage());
                }
            });
        }
    }

    // ==================== 核心动作执行器 ====================

    public Map<String, Object> executeTrigger(TriggerRow t, String eventType, JsonNode payload) {
        long t0 = System.currentTimeMillis();
        String runId = Ids.newId("trun");
        String payloadSummary = truncate(Json.write(payload), 400);
        try {
            Object result;
            if ("feishu_card".equals(t.actionType())) {
                String connId = t.actionConfig().path("connectorId").asText("");
                String titleTpl = t.actionConfig().path("title").asText("织页自动化通知：" + t.name());
                String bodyTpl = t.actionConfig().path("content").asText("触发事件 `{{event}}` 已执行。\n载荷摘要：`{{payload}}`");
                String color = t.actionConfig().path("templateColor").asText("blue");
                String title = interpolate(titleTpl, eventType, payload);
                String content = interpolate(bodyTpl, eventType, payload);
                result = connectors.sendFeishuCard(connId, t.appId(), title, content, color,
                        List.of(Map.of("label", "触发器", "value", t.name()), Map.of("label", "事件", "value", eventType)),
                        List.of());
            } else if ("cloud_function".equals(t.actionType())) {
                if (functions == null) throw new IllegalStateException("云函数服务未就绪");
                String fnName = t.actionConfig().path("functionName").asText("").trim();
                if (fnName.isEmpty()) throw ApiException.validation("cloud_function 触发器缺少 functionName");
                String vid = functions.versionFor(t.appId(), false);
                var who = new CloudPolicy.Who("trigger:" + t.id(), "automation", "自动化触发器", false, "fn:" + fnName);
                var r = functions.call(t.appId(), vid, fnName, payload, who);
                result = Map.of("value", r.value() == null ? Map.of() : r.value(), "logs", r.logs());
            } else {
                throw ApiException.validation("不支持的动作类型：" + t.actionType());
            }
            int dur = (int) (System.currentTimeMillis() - t0);
            String resSummary = truncate(Json.write(result), 500);
            jdbc.sql("insert into app_trigger_run (id, trigger_id, app_id, event_type, status, duration_ms, payload_summary, result_summary) values (?,?,?,?,?,?,?,?)")
                    .params(runId, t.id(), t.appId(), eventType, "ok", dur, payloadSummary, resSummary).update();
            jdbc.sql("update app_trigger set last_run_at = now(), last_status = 'ok', last_error = null, run_count = run_count + 1, updated_at = now() where id = ?")
                    .param(t.id()).update();
            return Map.of("runId", runId, "status", "ok", "durationMs", dur, "result", result);
        } catch (Exception e) {
            int dur = (int) (System.currentTimeMillis() - t0);
            String err = truncate(e.getMessage(), 1000);
            jdbc.sql("insert into app_trigger_run (id, trigger_id, app_id, event_type, status, duration_ms, payload_summary, error) values (?,?,?,?,?,?,?,?)")
                    .params(runId, t.id(), t.appId(), eventType, "error", dur, payloadSummary, err).update();
            jdbc.sql("update app_trigger set last_run_at = now(), last_status = 'error', last_error = ?, run_count = run_count + 1, updated_at = now() where id = ?")
                    .params(err, t.id()).update();
            throw e instanceof ApiException ae ? ae : new ApiException(HttpStatus.BAD_REQUEST, "TRIGGER_EXEC_FAILED", err);
        }
    }

    // ==================== Cron 解析 & 工具 ====================

    static void validateCron(String expr) {
        String[] p = expr.trim().split("\\s+");
        if (p.length != 5) throw ApiException.validation("Cron 表达式必须为 5 段（分 时 日 月 周），例如 */5 * * * *");
    }

    static boolean cronMatches(String expr, ZonedDateTime dt) {
        if (expr == null || expr.isBlank()) return false;
        String[] p = expr.trim().split("\\s+");
        if (p.length != 5) return false;
        return fieldMatch(p[0], dt.getMinute())
                && fieldMatch(p[1], dt.getHour())
                && fieldMatch(p[2], dt.getDayOfMonth())
                && fieldMatch(p[3], dt.getMonthValue())
                && fieldMatch(p[4], dt.getDayOfWeek().getValue() % 7);
    }

    private static boolean fieldMatch(String spec, int value) {
        if ("*".equals(spec)) return true;
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.startsWith("*/")) {
                try {
                    int step = Integer.parseInt(part.substring(2));
                    if (step > 0 && value % step == 0) return true;
                } catch (NumberFormatException ignored) {}
            } else if (part.contains("-")) {
                String[] rng = part.split("-");
                if (rng.length == 2) {
                    try {
                        int a = Integer.parseInt(rng[0]);
                        int b = Integer.parseInt(rng[1]);
                        if (value >= a && value <= b) return true;
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                try {
                    if (Integer.parseInt(part) == value) return true;
                } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    private static String interpolate(String tpl, String eventType, JsonNode payload) {
        if (tpl == null) return "";
        String out = tpl.replace("{{event}}", eventType == null ? "" : eventType)
                .replace("{{payload}}", truncate(Json.write(payload), 240));
        if (payload != null && payload.isObject()) {
            out = out.replace("{{table}}", payload.path("table").asText(""));
            JsonNode row = payload.path("row");
            if (row.isObject()) {
                var it = row.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    out = out.replace("{{row." + e.getKey() + "}}", e.getValue().asText(""));
                }
            }
        }
        return out;
    }

    private Optional<TriggerRow> findById(String id) {
        return jdbc.sql("select * from app_trigger where id = ?").param(id).query((rs, i) -> mapRow(rs)).optional();
    }

    private AppRow requireApp(String ws, String appId) {
        return apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
    }

    private TriggerRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TriggerRow(
                rs.getString("id"), rs.getString("app_id"), rs.getString("workspace_id"),
                rs.getString("name"), rs.getString("kind"), rs.getBoolean("enabled"),
                rs.getString("cron_expr"), rs.getString("table_name"), rs.getString("webhook_secret_enc"),
                rs.getString("action_type"), Json.read(rs.getString("action_config")),
                Db.ts(rs, "last_run_at"), rs.getString("last_status"), rs.getString("last_error"),
                rs.getLong("run_count"), Db.ts(rs, "created_at"), Db.ts(rs, "updated_at")
        );
    }

    private Map<String, Object> toView(TriggerRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("appId", r.appId());
        m.put("name", r.name());
        m.put("kind", r.kind());
        m.put("enabled", r.enabled());
        m.put("cronExpr", r.cronExpr());
        m.put("tableName", r.tableName());
        m.put("hasWebhookSecret", r.webhookSecretEnc() != null && !r.webhookSecretEnc().isBlank());
        m.put("webhookPath", "/api/webhooks/triggers/" + r.id());
        m.put("actionType", r.actionType());
        m.put("actionConfig", r.actionConfig() == null ? Json.obj() : r.actionConfig());
        m.put("lastRunAt", Db.iso(r.lastRunAt()));
        m.put("lastStatus", r.lastStatus());
        m.put("lastError", r.lastError());
        m.put("runCount", r.runCount());
        m.put("createdAt", Db.iso(r.createdAt()));
        return m;
    }

    private static Map<String, Object> mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("triggerId", rs.getString("trigger_id"));
        m.put("eventType", rs.getString("event_type"));
        m.put("status", rs.getString("status"));
        m.put("durationMs", rs.getInt("duration_ms"));
        m.put("payloadSummary", rs.getString("payload_summary"));
        m.put("resultSummary", rs.getString("result_summary"));
        m.put("error", rs.getString("error"));
        m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
        return m;
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
