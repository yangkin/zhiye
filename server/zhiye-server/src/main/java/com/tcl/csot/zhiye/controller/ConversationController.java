package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.util.Threads;

import com.tcl.csot.zhiye.service.conversation.ConversationService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.chat.AskService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 创造页的独立会话：问答持久化、从对话创建应用。
 * 会话按匿名 workspace 隔离；回答沿用 AskService（无 appId，不能读项目文件）。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationService conversations;
    private final AskService ask;
    private final CurrentPrincipal principal;
    /** 进行中的回答（每个会话同一时间一个）：用户点「停止」时置 stop，saved 表示完整回答已经落库 */
    private record Running(java.util.concurrent.atomic.AtomicBoolean stop, java.util.concurrent.atomic.AtomicBoolean saved) {}
    private final Map<String, Running> running = new java.util.concurrent.ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.common.RateLimitService rateLimit;

    public ConversationController(ConversationService conversations, AskService ask, CurrentPrincipal principal) {
        this.conversations = conversations;
        this.ask = ask;
        this.principal = principal;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody(required = false) JsonNode body) {
        return conversations.create(principal.workspaceId(), body == null ? Json.obj() : body);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return conversations.list(principal.workspaceId());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        return conversations.detail(principal.workspaceId(), id);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        conversations.delete(principal.workspaceId(), id);
        return Map.of("ok", true);
    }

    @PutMapping("/{id}/sources")
    public Map<String, Object> updateSources(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        return Map.of("sources", conversations.updateSources(principal.workspaceId(), id, body == null ? List.of() : list(body, "sourceIds")));
    }

    /** 右侧「文件」面板：AI 在这段会话里写出的文件（每个名称的最新版本） */
    @GetMapping("/{id}/files")
    public List<Map<String, Object>> files(@PathVariable String id) {
        return conversations.files(principal.workspaceId(), id);
    }

    @GetMapping("/{id}/files/{fileId}")
    public Map<String, Object> file(@PathVariable String id, @PathVariable String fileId) {
        return conversations.file(principal.workspaceId(), id, fileId);
    }

    @DeleteMapping("/{id}/files/{fileId}")
    public Map<String, Object> deleteFile(@PathVariable String id, @PathVariable String fileId) {
        conversations.deleteFile(principal.workspaceId(), id, fileId);
        return Map.of("ok", true);
    }

    /**
     * 会话里问一句：先落 user 消息，流式回答，完整结束后落 assistant 消息。
     * 用户点「停止」（POST …/ask/stop）后不再往下推送，也不落完整回答，只保存用户已经看到的那部分（去掉 [[build]] 标记，避免停了还被当成「开始做」）。
     */
    @PostMapping(value = "/{id}/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@PathVariable String id, @RequestBody JsonNode body) {
        String ws = principal.workspaceId();
        if (rateLimit != null) rateLimit.check("ws-ask:" + ws, 60, 60, "RATE_LIMITED", "提问太频繁，请稍后再试");
        ConversationService.Prepared p = conversations.prepareAsk(ws, id, body);
        SseEmitter emitter = new SseEmitter(0L);
        Running run = new Running(new java.util.concurrent.atomic.AtomicBoolean(), new java.util.concurrent.atomic.AtomicBoolean());
        Running old = running.put(id, run);
        if (old != null) old.stop().set(true);
        java.time.Instant startedAt = clearRemoteStop(id);
        StringBuilder shown = new StringBuilder(), thought = new StringBuilder();
        long[] lastSendAt = { System.currentTimeMillis() };
        long[] lastStopPollAt = { 0L };
        final String modelRef = body.path("model").asText("");
        final String billingType = body.path("billingType").asText("");
        Threads.start("conv-ask", () -> {
            try {
                ask.answer(ws, p.content(), p.skillAppIds(), null, p.history(), p.type(), p.assets(), p.sourceContext(),
                        modelRef, billingType,
                        (reply) -> {
                            if (run.stop().get() || remoteStopped(id, startedAt)) {
                                run.stop().set(true);
                                return;
                            }
                            run.saved().set(true);
                            conversations.saveAssistant(ws, id, reply.content(), reply.thinking());
                        },
                        (name, data) -> {
                            long now = System.currentTimeMillis();
                            if (now - lastStopPollAt[0] >= 250) {
                                lastStopPollAt[0] = now;
                                if (remoteStopped(id, startedAt)) run.stop().set(true);
                            }
                            if (run.stop().get()) throw new java.io.IOException("用户停止了回答");
                            String text = Json.M.valueToTree(data).path("text").asText("");
                            try {
                                if (now - lastSendAt[0] > 300) {
                                    // 长时间停顿后先发注释心跳触发内核 RST 检测，再发真实事件，避免断开后的末段内容落库
                                    emitter.send(SseEmitter.event().comment("hb"));
                                    try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                                }
                                emitter.send(SseEmitter.event().name(name).data(Json.write(data), MediaType.APPLICATION_JSON));
                                lastSendAt[0] = System.currentTimeMillis();
                            } catch (java.io.IOException | IllegalStateException gone) {
                                // 浏览器断开：点了停止（多实例时停止请求可能落在别的实例上）、关了页面或网络断了
                                run.stop().set(true);
                                throw gone;
                            }
                            // 发出去了才算用户看到的
                            if ("delta".equals(name)) shown.append(text);
                            else if ("thinking".equals(name) && thought.length() < 20000) thought.append(text);
                        });
                if (run.stop().get() && !run.saved().get() && !shown.toString().isBlank())
                    conversations.saveAssistant(ws, id, ConversationService.stripBuildMarker(shown.toString()) + "\n\n（已停止回答）", thought.toString());
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                running.remove(id, run);
            }
        });
        return emitter;
    }

    /** 停止回答。finished=true 表示回答其实已经完整结束并保存（停止来晚了），前端应重新加载会话显示完整回答 */
    @PostMapping("/{id}/ask/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        conversations.require(principal.workspaceId(), id);
        if (jdbc != null) {
            jdbc.sql("insert into conversation_stop (conversation_id, requested_at) values (?, clock_timestamp()) "
                    + "on conflict (conversation_id) do update set requested_at = excluded.requested_at")
                    .param(id).update();
        }
        Running run = running.get(id);
        // 回答不在本实例上：已写入跨实例 stop 信号，同时看数据库里最后一条是不是已经存好的回答
        if (run == null) return Map.of("finished", conversations.lastIsAssistant(principal.workspaceId(), id));
        if (run.saved().get()) return Map.of("finished", true);
        run.stop().set(true);
        return Map.of("finished", false);
    }

    private java.time.Instant clearRemoteStop(String id) {
        if (jdbc == null) return java.time.Instant.now();
        jdbc.sql("delete from conversation_stop where conversation_id = ?").param(id).update();
        return jdbc.sql("select clock_timestamp() as ts").query((rs, i) -> com.tcl.csot.zhiye.util.Db.ts(rs, "ts")).single();
    }

    private boolean remoteStopped(String id, java.time.Instant startedAt) {
        if (jdbc == null) return false;
        return jdbc.sql("select requested_at from conversation_stop where conversation_id = ?")
                .param(id).query((rs, i) -> com.tcl.csot.zhiye.util.Db.ts(rs, "requested_at"))
                .optional().map(req -> !req.isBefore(startedAt)).orElse(false);
    }

    /** 聊清楚后开始做：把整段对话（含 assistant 结论）整理成需求 */
    @PostMapping("/{id}/build")
    public Map<String, Object> build(@PathVariable String id, @RequestBody(required = false) JsonNode body,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String key) throws Exception {
        JsonNode b = body == null ? Json.obj() : body;
        ConversationService.BuildRequest req = new ConversationService.BuildRequest(
                text(b, "type"), text(b, "mode"), text(b, "model"),
                list(b, "assetIds"), list(b, "skillAppIds"), list(b, "sourceIds"), text(b, "billingType"));
        return conversations.build(principal.workspaceId(), id, req, key);
    }

    private static String text(JsonNode b, String key) {
        String v = b.path(key).asText("");
        return v.isBlank() ? null : v;
    }

    private static List<String> list(JsonNode b, String key) {
        if (!b.has(key) || !b.get(key).isArray()) return null;
        return java.util.stream.StreamSupport.stream(b.get(key).spliterator(), false)
                .map(JsonNode::asText).filter(s -> !s.isBlank()).toList();
    }
}
