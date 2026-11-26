package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.util.Threads;

import com.tcl.csot.zhiye.service.chat.AskAttachments;
import com.tcl.csot.zhiye.service.chat.AskService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.MessageRepository;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 创造页的「问一句」：只回答，不创建应用。
 * POST /api/chat/ask { content, skillAppIds? } → SSE：delta / done / error
 */
@RestController
@RequestMapping("/api/chat")
public class AskController {
    private final AskService ask;
    private final AskAttachments attachments;
    private final CurrentPrincipal principal;
    private final AppRepository apps;
    private final MessageRepository messages;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.common.RateLimitService rateLimit;

    public AskController(AskService ask, AskAttachments attachments, CurrentPrincipal principal, AppRepository apps, MessageRepository messages) {
        this.ask = ask;
        this.attachments = attachments;
        this.principal = principal;
        this.apps = apps;
        this.messages = messages;
    }

    private void checkRateLimit(String ws) {
        if (rateLimit != null) {
            rateLimit.check("ws-ask:" + ws, 60, 60, "RATE_LIMITED", "提问太频繁，请稍后再试");
        }
    }

    /** 创造页「润色」：把粗略需求改写得更清楚，不创建应用 */
    @PostMapping("/polish")
    public Map<String, Object> polish(@RequestBody JsonNode body) {
        checkRateLimit(principal.workspaceId());
        try {
            return Map.of("text", ask.polish(principal.workspaceId(), body == null ? "" : body.path("content").asText("")));
        } catch (com.tcl.csot.zhiye.exception.LlmException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "POLISH_FAILED", userFacing(e) ? e.getMessage() : "润色暂时不可用，请稍后再试");
        }
    }

    /** 额度用完、未登录这类原因要原样告诉用户，其余笼统提示 */
    private static boolean userFacing(com.tcl.csot.zhiye.exception.LlmException e) {
        return "PROVIDER_QUOTA".equals(e.code()) || "MODEL_ROUTE".equals(e.code());
    }

    @PostMapping("/intent")
    public Map<String, Object> intent(@RequestBody JsonNode body) {
        checkRateLimit(principal.workspaceId());
        try {
            return Map.of("intent", ask.intent(principal.workspaceId(), body == null ? "" : body.path("content").asText(""), body != null && body.path("hasElement").asBoolean(false),
                    body == null ? null : body.path("appName").asText(null), body == null ? null : body.path("appBrief").asText(null)));
        } catch (com.tcl.csot.zhiye.exception.LlmException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTENT_FAILED", userFacing(e) ? e.getMessage() : "暂时无法判断意图");
        }
    }

    @PostMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody JsonNode body) {
        String ws = principal.workspaceId();
        String appId = body.path("appId").asText("");
        // 工作台里问一句：回答存成一条助手消息，刷新后还在；不创建任务、不改代码
        String app = null;
        if (!appId.isBlank()) {
            app = apps.findById(appId).filter(a -> a.workspaceId().equals(ws) && a.deletedAt() == null)
                    .orElseThrow(() -> ApiException.notFound("应用")).id();
        }
        String content = body.path("content").asText("");
        // 多轮：保留最新历史由 AskService.normalizeHistory 统一去重、折叠旧文件块并裁剪最近 12 条
        java.util.List<JsonNode> history = new java.util.ArrayList<>();
        if (body.has("history") && body.get("history").isArray()) {
            for (JsonNode h : body.get("history")) {
                if (history.size() >= 100) break;
                if (h.hasNonNull("role") && h.hasNonNull("content")) history.add(h);
            }
        }
        // 校验放在请求线程：内容为空/超长/没有密钥时直接返回 400/503，而不是 SSE 错误事件
        String c = ask.validate(content);
        checkRateLimit(ws);
        java.util.List<String> skillIds = AskService.ids(body);
        // 消息里附带的素材：图片走视觉、文档提取文本，问答时能读
        java.util.List<String> assetIds = AskService.assetIds(body);
        java.util.List<AskAttachments.Attached> attached = attachments.load(ws, assetIds);
        if (app != null) messages.insert(app, "user", c, null, null, "ok", assetIds.isEmpty() ? null : assetIds);
        final String appForSave = app;
        final java.util.List<JsonNode> hist = java.util.List.copyOf(history);
        final String modelRef = body.path("model").asText("");
        final String billingType = body.path("billingType").asText("");
        SseEmitter emitter = new SseEmitter(0L);
        Threads.start("ask", () -> {
            try {
                // appId 非空时走只读工具循环（模型可按需读代码），否则一次普通回答
                ask.answer(ws, c, skillIds, appForSave, hist, null, attached, "", modelRef, billingType,
                        (reply) -> {
                            if (appForSave != null) messages.insert(appForSave, "assistant", reply.content(), null, null, "ok");
                        },
                        (name, data) -> emitter.send(SseEmitter.event().name(name).data(Json.write(data), MediaType.APPLICATION_JSON)));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}