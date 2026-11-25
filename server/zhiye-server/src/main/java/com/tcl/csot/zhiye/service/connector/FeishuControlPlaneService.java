package com.tcl.csot.zhiye.service.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.MessageRepository;
import com.tcl.csot.zhiye.service.app.AppService;
import com.tcl.csot.zhiye.service.runtime.MonolithServiceManager;
import com.tcl.csot.zhiye.service.task.TaskService;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 公域飞书直接控制面（Phase 4）：
 * 1. 接收公域飞书事件订阅（POST /api/webhooks/feishu/events）：
 *    - 自动响应 url_verification challenge
 *    - 解析群聊/私聊 @织页 指令：
 *      ·「状态」/「list」→ 返回工作空间应用运行态总览卡片
 *      ·「诊断 <应用>」→ 实时拉取 Deploy Engine V2 结构化根因诊断并回发卡片
 *      ·「修改 <应用> <需求>」→ 投递 Coding Agent Harness V2 异步任务并生成带「批准上线 / 驳回」按钮的交互审批卡片
 * 2. 接收公域飞书交互卡片按钮回调（POST /api/webhooks/feishu/card-action）：
 *    - 点击「批准上线」→ 直接调用 AppService.publish 完成正式发布与蓝绿切流！
 *    - 点击「驳回」→ 标记驳回并更新卡片状态
 * 3. 支持管理后台控制面本地模拟器，免公网隧道即可全链路验证完整飞书审批上线闭环！
 */
@Service
public class FeishuControlPlaneService {
    private static final Logger log = LoggerFactory.getLogger(FeishuControlPlaneService.class);

    private final JdbcClient jdbc;
    private final AppRepository apps;
    private final AppService appService;
    private final TaskService tasks;
    private final MessageRepository messages;
    private final MonolithServiceManager monolith;
    private final EnterpriseConnectorService connectors;

    public FeishuControlPlaneService(JdbcClient jdbc, AppRepository apps, AppService appService,
                                     TaskService tasks, MessageRepository messages,
                                     MonolithServiceManager monolith, EnterpriseConnectorService connectors) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.appService = appService;
        this.tasks = tasks;
        this.messages = messages;
        this.monolith = monolith;
        this.connectors = connectors;
    }

    /** 处理公域飞书事件订阅回调（含 url_verification 握手与 im.message.receive_v1 消息指令）。 */
    public Map<String, Object> handleFeishuEvent(JsonNode payload, String fallbackWorkspaceId) {
        if (payload == null) return Map.of("ok", true);
        // 1. 公域飞书事件订阅 URL 校验握手
        if ("url_verification".equals(payload.path("type").asText(""))) {
            return Map.of("challenge", payload.path("challenge").asText(""));
        }

        // 2. 解析飞书 2.0 事件体或控制面模拟器简化载荷
        JsonNode event = payload.has("event") ? payload.get("event") : payload;
        String chatId = event.path("message").path("chat_id").asText(event.path("chatId").asText("oc_zhiye_demo_group"));
        String openId = event.path("sender").path("sender_id").path("open_id").asText(event.path("openId").asText("ou_demo_user"));
        String userName = event.path("userName").asText("飞书用户");
        String connectorId = event.path("connectorId").asText("conn_feishu_bot_sandbox");
        String ws = event.path("workspaceId").asText(fallbackWorkspaceId == null ? "" : fallbackWorkspaceId).trim();

        String rawText = extractMessageText(event);
        String command = rawText.replaceAll("@_user_\\d+", "").replaceAll("@织页(?:AI)?", "").trim();
        if (command.isEmpty()) command = "状态";

        return dispatchCommand(ws, connectorId, chatId, openId, userName, command, event.path("appId").asText("").trim());
    }

    /** 解析并执行 @织页 控制面指令，落库会话并发送/返回交互式飞书卡片。 */
    public Map<String, Object> dispatchCommand(String workspaceId, String connectorId, String chatId,
                                               String openId, String userName, String command, String explicitAppId) {
        String sessionId = Ids.newId("fses");
        String ws = resolveWorkspaceId(workspaceId, explicitAppId);
        String lower = command.toLowerCase(Locale.ROOT);

        // 意图 1：查看平台/工作空间应用状态
        if (lower.startsWith("状态") || lower.startsWith("status") || lower.startsWith("list") || lower.equals("帮助") || lower.equals("help")) {
            List<AppRow> appList = apps.listVisible(ws);
            StringBuilder md = new StringBuilder("当前工作空间共有 **" + appList.size() + "** 个应用：\n");
            for (AppRow a : appList.stream().limit(8).toList()) {
                String pub = a.publishedVersionId() != null ? "已上线" : "草稿";
                md.append("- **").append(a.name()).append("** (`").append(a.id()).append("`) · ").append(a.projectKind()).append(" · ").append(pub).append("\n");
            }
            md.append("\n可用指令：`@织页 状态` · `@织页 诊断 <应用名/ID>` · `@织页 修改 <应用名/ID> <需求>`");
            Map<String, Object> card = EnterpriseConnectorService.buildInteractiveCard(
                    "织页 AI 控制面 · 应用状态总览", md.toString(), "blue",
                    List.of(Map.of("label", "工作空间", "value", ws), Map.of("label", "应用数", "value", String.valueOf(appList.size()))),
                    List.of());
            saveSession(sessionId, connectorId, ws, chatId, openId, userName, command, "status", null, null, null, "completed", card, null);
            tryPushCard(connectorId, null, "织页 AI 控制面 · 应用状态总览", md.toString(), "blue", List.of(), List.of());
            return Map.of("ok", true, "sessionId", sessionId, "intent", "status", "cardStatus", "completed", "card", card);
        }

        // 意图 2：诊断指定应用部署/运行状态
        if (lower.startsWith("诊断") || lower.startsWith("diagnose")) {
            String target = command.replaceFirst("^(?:诊断|diagnose)\\s*", "").trim();
            AppRow app = resolveApp(ws, explicitAppId, target);
            Map<String, Object> diag;
            if (app.isService() && monolith.find(app.id()).isPresent()) {
                diag = monolith.diagnosisView(app.workspaceId(), app.id());
            } else {
                diag = Map.of("category", "HEALTHY", "title", "应用状态正常", "summary", "应用「" + app.name() + "」当前未检测到部署错误。", "suggestedFixes", List.of());
            }
            String md = "**应用**：" + app.name() + " (`" + app.id() + "`)\n"
                    + "**诊断分类**：`" + diag.get("category") + "` — " + diag.get("title") + "\n"
                    + "**摘要**：" + diag.get("summary");
            Map<String, Object> card = EnterpriseConnectorService.buildInteractiveCard(
                    "织页 AI · 运行态诊断报告", md, "HEALTHY".equals(diag.get("category")) ? "green" : "orange",
                    List.of(Map.of("label", "应用", "value", app.name()), Map.of("label", "分类", "value", String.valueOf(diag.get("category")))),
                    List.of());
            saveSession(sessionId, connectorId, app.workspaceId(), chatId, openId, userName, command, "diagnose", app.id(), null, app.currentVersionId(), "completed", card, null);
            tryPushCard(connectorId, app.id(), "织页 AI · 运行态诊断报告", md, "orange", List.of(), List.of());
            return Map.of("ok", true, "sessionId", sessionId, "intent", "diagnose", "appId", app.id(), "diagnosis", diag, "card", card);
        }

        // 意图 3：发起修改/发布审批（@织页 修改 <应用> <需求> 或 @织页 发布 <应用>）
        boolean directPublishRequest = lower.startsWith("发布") || lower.startsWith("publish");
        AppRow app = resolveApp(ws, explicitAppId, command.replaceFirst("^(?:修改|modify|发布|publish)\\s*", "").trim());
        String taskId = null;
        String instruction = command.replaceFirst("^(?:修改|modify|发布|publish)\\s*", "").trim();
        if (!directPublishRequest && app.currentVersionId() != null && !app.currentVersionId().isBlank()) {
            ObjectNode input = Json.obj();
            input.put("instruction", "【来自公域飞书控制面 " + userName + "】" + instruction);
            input.put("source", "feishu_control_plane");
            input.put("feishuSessionId", sessionId);
            String idemKey = "feishu-" + sessionId;
            try {
                TaskRow t = tasks.create(app.workspaceId(), app.id(), "modify", app.currentVersionId(), input, idemKey, 1, null);
                taskId = t.id();
                messages.insert(app.id(), "user", "【飞书控制面 · " + userName + "】" + instruction, null, t.id(), "pending");
            } catch (Exception e) {
                log.info("飞书指令跳过重复/忙碌任务创建，直接进入版本审批卡片 app={}: {}", app.id(), e.getMessage());
            }
        }

        String cardTitle = directPublishRequest ? "织页 AI · 飞书发布上线审批" : "织页 AI · 编码任务已受理（可一键审批上线）";
        String md = "**应用**：" + app.name() + " (`" + app.id() + "`)\n"
                + "**发起人**：" + userName + "\n"
                + "**指令内容**：" + instruction + "\n"
                + (taskId != null ? "**编码任务 ID**：`" + taskId + "`（Harness V2 执行中）\n" : "")
                + "**待发布版本**：`" + (app.currentVersionId() == null ? "最新版本" : app.currentVersionId()) + "`\n"
                + "确认预览与 QA 检查通过后，点击下方按钮即可直接在飞书群内触发蓝绿切流上线：";

        List<Map<String, Object>> buttons = List.of(
                Map.of("label", "批准上线（蓝绿发布）", "type", "primary",
                        "value", Map.of("action", "approve_publish", "sessionId", sessionId, "appId", app.id())),
                Map.of("label", "驳回本次发布", "type", "danger",
                        "value", Map.of("action", "reject", "sessionId", sessionId, "appId", app.id()))
        );
        List<Map<String, String>> fields = List.of(
                Map.of("label", "应用", "value", app.name()),
                Map.of("label", "审批状态", "value", "待审批")
        );
        Map<String, Object> card = EnterpriseConnectorService.buildInteractiveCard(cardTitle, md, "indigo", fields, buttons);
        saveSession(sessionId, connectorId, app.workspaceId(), chatId, openId, userName, command,
                directPublishRequest ? "publish_approval" : "modify", app.id(), taskId, app.currentVersionId(),
                "ready_for_approval", card, null);
        tryPushCard(connectorId, app.id(), cardTitle, md, "indigo", fields, buttons);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionId", sessionId);
        out.put("intent", directPublishRequest ? "publish_approval" : "modify");
        out.put("appId", app.id());
        out.put("taskId", taskId);
        out.put("cardStatus", "ready_for_approval");
        out.put("card", card);
        return out;
    }

    /** 处理公域飞书交互式卡片按钮回调（批准上线 / 驳回）。 */
    public Map<String, Object> handleCardAction(JsonNode payload) {
        if (payload == null) throw ApiException.validation("缺少卡片回调载荷");
        if ("url_verification".equals(payload.path("type").asText(""))) {
            return Map.of("challenge", payload.path("challenge").asText(""));
        }
        JsonNode actionVal = payload.path("action").path("value");
        if (actionVal.isMissingNode() || actionVal.isNull()) actionVal = payload;

        String action = actionVal.path("action").asText("").trim();
        String sessionId = actionVal.path("sessionId").asText("").trim();
        String operator = payload.path("open_id").asText(payload.path("operator").asText("feishu_approver"));
        if (sessionId.isEmpty()) throw ApiException.validation("缺少 sessionId");

        record Sess(String id, String workspaceId, String appId, String versionId, String commandText, String cardStatus) {}
        Sess s = jdbc.sql("select id, workspace_id, app_id, version_id, command_text, card_status from feishu_control_session where id = ?")
                .param(sessionId)
                .query((rs, i) -> new Sess(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional().orElseThrow(() -> ApiException.notFound("飞书控制面会话"));

        if ("reject".equals(action)) {
            String msg = "已由 " + operator + " 在飞书卡片驳回本次发布。";
            Map<String, Object> updatedCard = EnterpriseConnectorService.buildInteractiveCard(
                    "织页 AI · 发布已驳回", msg + "\n原始指令：" + s.commandText(), "grey",
                    List.of(Map.of("label", "状态", "value", "已驳回"), Map.of("label", "审批人", "value", operator)),
                    List.of());
            jdbc.sql("update feishu_control_session set card_status = 'rejected', approval_result = ?, card_json = ?::jsonb, updated_at = now() where id = ?")
                    .params(msg, Json.write(updatedCard), sessionId).update();
            return Map.of("ok", true, "sessionId", sessionId, "cardStatus", "rejected", "card", updatedCard);
        }

        if ("approve_publish".equals(action)) {
            AppRow app = apps.findById(s.appId()).orElseThrow(() -> ApiException.notFound("应用"));
            String targetVid = (app.currentVersionId() != null && !app.currentVersionId().isBlank())
                    ? app.currentVersionId() : s.versionId();
            if (targetVid == null || targetVid.isBlank()) {
                throw ApiException.conflict("NO_VERSION", "该应用尚无可发布的版本");
            }
            Map<String, Object> pubRes = appService.publish(app.workspaceId(), app.id(), targetVid);
            String siteUrl = String.valueOf(pubRes.getOrDefault("siteUrl", ""));
            String msg = "已由 " + operator + " 批准并完成正式发布（版本 `" + targetVid + "`）！"
                    + (siteUrl.isBlank() ? "" : "\n访问地址：" + siteUrl);
            Map<String, Object> updatedCard = EnterpriseConnectorService.buildInteractiveCard(
                    "织页 AI · 审批通过并已上线", msg, "green",
                    List.of(Map.of("label", "应用", "value", app.name()), Map.of("label", "状态", "value", "已正式上线")),
                    List.of());
            jdbc.sql("update feishu_control_session set card_status = 'approved_published', version_id = ?, approval_result = ?, card_json = ?::jsonb, updated_at = now() where id = ?")
                    .params(targetVid, msg, Json.write(updatedCard), sessionId).update();
            return Map.of("ok", true, "sessionId", sessionId, "cardStatus", "approved_published",
                    "appId", app.id(), "versionId", targetVid, "siteUrl", siteUrl, "card", updatedCard);
        }

        throw ApiException.validation("不支持的飞书卡片动作：" + action);
    }

    public List<Map<String, Object>> recentSessions(int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 30 : limit, 100));
        return jdbc.sql("select * from feishu_control_session order by created_at desc limit ?")
                .param(lim).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("connectorId", rs.getString("connector_id"));
                    m.put("workspaceId", rs.getString("workspace_id"));
                    m.put("chatId", rs.getString("chat_id"));
                    m.put("userName", rs.getString("user_name"));
                    m.put("commandText", rs.getString("command_text"));
                    m.put("intent", rs.getString("intent"));
                    m.put("appId", rs.getString("app_id"));
                    m.put("taskId", rs.getString("task_id"));
                    m.put("versionId", rs.getString("version_id"));
                    m.put("cardStatus", rs.getString("card_status"));
                    m.put("card", Json.read(rs.getString("card_json")));
                    m.put("approvalResult", rs.getString("approval_result"));
                    m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
                    m.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
                    return m;
                }).list();
    }

    private void saveSession(String id, String connectorId, String ws, String chatId, String openId, String userName,
                             String command, String intent, String appId, String taskId, String versionId,
                             String cardStatus, Map<String, Object> card, String approvalResult) {
        jdbc.sql("""
                insert into feishu_control_session
                  (id, connector_id, workspace_id, chat_id, open_id, user_name, command_text, intent, app_id, task_id, version_id, card_status, card_json, approval_result)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)
                """).params(id, connectorId, ws, chatId, openId, userName, command, intent, appId, taskId, versionId,
                cardStatus, Json.write(card), approvalResult).update();
    }

    private void tryPushCard(String connectorId, String appId, String title, String md, String color,
                             List<Map<String, String>> fields, List<Map<String, Object>> buttons) {
        try {
            connectors.sendFeishuCard(connectorId, appId, title, md, color, fields, buttons);
        } catch (Exception e) {
            log.warn("回发飞书卡片失败（会话已落库）: {}", e.getMessage());
        }
    }

    private String resolveWorkspaceId(String ws, String explicitAppId) {
        if (explicitAppId != null && !explicitAppId.isBlank()) {
            var a = apps.findById(explicitAppId);
            if (a.isPresent()) return a.get().workspaceId();
        }
        if (ws != null && !ws.isBlank()) return ws;
        return jdbc.sql("select workspace_id from app where deleted_at is null order by updated_at desc limit 1")
                .query(String.class).optional().orElse("");
    }

    private AppRow resolveApp(String ws, String explicitAppId, String hint) {
        if (explicitAppId != null && !explicitAppId.isBlank()) {
            return apps.findById(explicitAppId).orElseThrow(() -> ApiException.notFound("应用"));
        }
        List<AppRow> candidates = (ws != null && !ws.isBlank()) ? apps.listVisible(ws)
                : jdbc.sql("select * from app where deleted_at is null order by updated_at desc limit 20").query(AppRow::map).list();
        if (hint != null && !hint.isBlank()) {
            String h = hint.toLowerCase(Locale.ROOT);
            for (AppRow a : candidates) {
                if (h.contains(a.id().toLowerCase(Locale.ROOT)) || h.contains(a.name().toLowerCase(Locale.ROOT))
                        || h.contains(a.slug().toLowerCase(Locale.ROOT))) {
                    return a;
                }
            }
        }
        if (!candidates.isEmpty()) return candidates.get(0);
        throw ApiException.notFound("可操作的目标应用");
    }

    private static String extractMessageText(JsonNode event) {
        if (event.has("text")) return event.path("text").asText("");
        String content = event.path("message").path("content").asText("");
        if (content.startsWith("{")) {
            try {
                JsonNode parsed = Json.read(content);
                if (parsed != null && parsed.has("text")) return parsed.path("text").asText("");
            } catch (Exception ignored) {}
        }
        return content;
    }
}
