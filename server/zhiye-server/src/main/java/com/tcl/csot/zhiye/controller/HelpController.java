package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.util.Threads;

import com.tcl.csot.zhiye.service.help.HelpService;

import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** 小织帮助助手：/api/help/**，全部限定在当前工作空间（别的工作空间的对话一律 404）。 */
@RestController
@Profile("api")
@RequestMapping("/api/help")
public class HelpController {
    private final HelpService help;
    private final CurrentPrincipal principal;

    public HelpController(HelpService help, CurrentPrincipal principal) {
        this.help = help;
        this.principal = principal;
    }

    public record SendBody(String content, List<String> assetIds) {}

    public record FeedbackBody(String content, List<String> assetIds, String conversationId) {}

    @GetMapping("/conversations")
    public List<Map<String, Object>> list() { return help.list(principal.workspaceId()); }

    @PostMapping("/conversations")
    public Map<String, Object> create() { return help.create(principal.workspaceId()); }

    @GetMapping("/conversations/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable String id) { return help.messages(principal.workspaceId(), id); }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        help.delete(principal.workspaceId(), id);
        return Map.of("ok", true);
    }

    /** 停止正在进行的回答（客户端断开连接也会停止） */
    @PostMapping("/conversations/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        return Map.of("stopped", help.stop(principal.workspaceId(), id, false));
    }

    /**
     * 发送消息并流式返回回答（SSE）：
     * 先发 {@code user {message,title}}，然后若干 {@code delta {text}}，最后 {@code done {messageId,status,title}} 或 {@code error {message}}。
     * 校验失败、超出次数等在开始流之前以普通 JSON 错误返回。
     */
    @PostMapping("/conversations/{id}/messages")
    public SseEmitter send(@PathVariable String id, @RequestBody SendBody body) throws Exception {
        String ws = principal.workspaceId();
        HelpService.Prepared p = help.prepare(ws, id, body == null ? null : body.content(), body == null ? null : body.assetIds());
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onError(e -> p.stream().cancel());
        emitter.onTimeout(() -> p.stream().cancel());
        HelpService.Sink sink = new HelpService.Sink() {
            @Override public void event(String name, Object data) throws Exception {
                emitter.send(SseEmitter.event().name(name).data(Json.write(data), MediaType.APPLICATION_JSON));
            }
            @Override public void ping() throws Exception { emitter.send(SseEmitter.event().comment("ping")); }
        };
        Threads.start("help-" + id, () -> {
            try {
                sink.event("user", Map.of("message", p.userMessage(), "title", p.title()));
            } catch (Exception e) {
                p.stream().cancel();
            }
            help.run(p, sink);
            try { emitter.complete(); } catch (Exception ignored) { }
        });
        return emitter;
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody FeedbackBody body) {
        return help.feedback(principal.workspaceId(), body.content(), body.assetIds(), body.conversationId());
    }
}
