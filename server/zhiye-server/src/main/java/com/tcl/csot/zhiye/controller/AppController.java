package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.app.AppService;
import com.tcl.csot.zhiye.service.app.StatsService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Profile("api")
@RequestMapping("/api/apps")
public class AppController {
    private final AppService apps;
    private final CurrentPrincipal principal;
    private final StatsService stats;
    private final com.tcl.csot.zhiye.service.workspace.QuotaService quota;
    private final com.tcl.csot.zhiye.config.ZhiyeProperties props;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.ops.PlatformLogService platformLogs;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.chat.AskService askService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.chat.AskAttachments askAttachments;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.common.RateLimitService rateLimit;

    public AppController(AppService apps, CurrentPrincipal principal, StatsService stats,
                         com.tcl.csot.zhiye.service.workspace.QuotaService quota, com.tcl.csot.zhiye.config.ZhiyeProperties props) {
        this.stats = stats;
        this.quota = quota;
        this.props = props;
        this.apps = apps;
        this.principal = principal;
    }

    private String ws() { return principal.workspaceId(); }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String q, @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String favorite, @RequestParam(required = false) String published,
                                          @RequestParam(required = false) String sort) {
        return apps.list(ws(), q, type, favorite, published, sort);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody AppService.CreateRequest req,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String key) throws Exception {
        return apps.create(ws(), req, key);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> m = new java.util.LinkedHashMap<>(stats.summary(ws()));
        long[] usage = quota.appUsage(ws());
        m.put("appLimit", usage[1]);
        m.put("appsUsed", usage[0]);
        m.put("taskLimit", props.workspace().maxConcurrentTasks());
        return m;
    }

    @GetMapping("/{id}/stats")
    public Map<String, Object> stats(@PathVariable String id, @RequestParam(defaultValue = "7d") String range) {
        return stats.stats(ws(), id, range);
    }

    @PostMapping("/{id}/plan-feedback")
    public Map<String, Object> planFeedback(@PathVariable String id, @RequestBody JsonNode body) {
        return apps.planFeedback(ws(), id, body == null ? "" : body.path("content").asText(""));
    }

    /** 进行中的计划阶段对话（每个应用同一时间最多一条）：用户点停止或发起新消息时立即终止旧流 */
    private record PlanChatRun(java.util.concurrent.atomic.AtomicBoolean stop,
                               java.util.concurrent.atomic.AtomicBoolean saved,
                               java.util.concurrent.atomic.AtomicBoolean stopRecorded,
                               java.util.concurrent.atomic.AtomicReference<java.io.Closeable> stream,
                               java.util.concurrent.atomic.AtomicReference<Runnable> onStop) {
        void abort() {
            stop.set(true);
            java.io.Closeable c = stream.getAndSet(null);
            if (c != null) {
                try { c.close(); } catch (Exception ignored) { }
            }
            Runnable r = onStop.get();
            if (r != null) r.run();
        }
    }
    private final java.util.concurrent.ConcurrentHashMap<String, PlanChatRun> planChatRunning = new java.util.concurrent.ConcurrentHashMap<>();

    /** 计划待确认阶段的流式 AI 对话与计划修订（SSE：thinking / delta / plan_updating / plan_updated / done / error） */
    @PostMapping(value = "/{id}/plan-chat", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter planChat(@PathVariable String id, @RequestBody JsonNode body) {
        String workspaceId = ws();
        var app = apps.requirePlanApp(workspaceId, id);
        String content = body == null ? "" : body.path("content").asText("");
        String c = askService.validate(content);
        if (rateLimit != null) {
            rateLimit.check("ws-ask:" + workspaceId, 60, 60, "RATE_LIMITED", "提问太频繁，请稍后再试");
        }
        java.util.List<JsonNode> history = new java.util.ArrayList<>();
        if (body != null && body.has("history") && body.get("history").isArray()) {
            for (JsonNode h : body.get("history")) {
                if (history.size() >= 100) break;
                if (h.hasNonNull("role") && h.hasNonNull("content")) history.add(h);
            }
        }
        java.util.List<String> skillIds = com.tcl.csot.zhiye.service.chat.AskService.ids(body);
        java.util.List<String> assetIds = com.tcl.csot.zhiye.service.chat.AskService.assetIds(body);
        java.util.List<com.tcl.csot.zhiye.service.chat.AskAttachments.Attached> attached =
                askAttachments == null ? java.util.List.of() : askAttachments.load(workspaceId, assetIds);

        PlanChatRun run = new PlanChatRun(
                new java.util.concurrent.atomic.AtomicBoolean(false),
                new java.util.concurrent.atomic.AtomicBoolean(false),
                new java.util.concurrent.atomic.AtomicBoolean(false),
                new java.util.concurrent.atomic.AtomicReference<>(),
                new java.util.concurrent.atomic.AtomicReference<>());
        PlanChatRun prev = planChatRunning.put(app.id(), run);
        if (prev != null) prev.abort();

        String userMsgId = apps.recordPlanUserMessage(app.id(), c, assetIds);
        final java.util.List<JsonNode> hist = java.util.List.copyOf(history);
        final StringBuilder shown = new StringBuilder();
        Runnable markStoppedOnce = () -> {
            if (run.stop().get() && !run.saved().get() && run.stopRecorded().compareAndSet(false, true)) {
                try { apps.recordPlanChatStopped(app.id(), userMsgId, shown.toString()); } catch (Exception ignored) { }
            }
        };
        run.onStop().set(markStoppedOnce);
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        emitter.onCompletion(() -> { if (!run.saved().get()) run.abort(); });
        emitter.onTimeout(run::abort);
        emitter.onError((ex) -> run.abort());

        com.tcl.csot.zhiye.util.Threads.start("plan-chat", () -> {
            try {
                askService.planChat(workspaceId, app, c, hist, skillIds, attached,
                        run.stop()::get,
                        (in) -> {
                            run.stream().set(in);
                            if (run.stop().get()) run.abort();
                        },
                        (res) -> {
                            if (run.stop().get()) return null;
                            run.saved().set(true);
                            return apps.applyPlanChatResult(workspaceId, app.id(), res);
                        },
                        (name, data) -> {
                            if (run.stop().get()) throw new java.io.IOException("用户停止了回答");
                            try {
                                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                                        .name(name).data(Json.write(data), org.springframework.http.MediaType.APPLICATION_JSON));
                            } catch (java.io.IOException | IllegalStateException gone) {
                                run.abort();
                                throw gone;
                            }
                            if ("delta".equals(name)) {
                                String t = Json.M.valueToTree(data).path("text").asText("");
                                if (!t.isEmpty()) shown.append(t);
                            }
                        });
                markStoppedOnce.run();
                emitter.complete();
            } catch (Exception e) {
                markStoppedOnce.run();
                emitter.completeWithError(e);
            } finally {
                run.stream().set(null);
                planChatRunning.remove(app.id(), run);
            }
        });
        return emitter;
    }

    /** 立即停止当前应用的计划阶段流式回答 */
    @PostMapping("/{id}/plan-chat/stop")
    public Map<String, Object> stopPlanChat(@PathVariable String id) {
        var app = apps.require(ws(), id);
        PlanChatRun run = planChatRunning.get(app.id());
        if (run == null || run.saved().get()) return Map.of("stopped", false, "finished", true);
        run.abort();
        return Map.of("stopped", true, "finished", false);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) { return apps.detail(ws(), id); }

    @PatchMapping("/{id}")
    public Map<String, Object> patch(@PathVariable String id, @RequestBody JsonNode body) { return apps.patch(ws(), id, body); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        String workspaceId = ws();
        apps.delete(workspaceId, id);
        if (platformLogs != null) {
            platformLogs.info("lifecycle", "APP_DELETE", workspaceId, null, id, null, "删除应用（移入回收站）", null, Map.of());
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/generate")
    public Map<String, Object> generate(@PathVariable String id, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return apps.generate(ws(), id, key);
    }

    @GetMapping("/{id}/messages")
    public List<Map<String, Object>> messages(@PathVariable String id) { return apps.messages(ws(), id); }

    @PostMapping("/{id}/messages")
    public Map<String, Object> post(@PathVariable String id, @RequestBody JsonNode body,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) throws Exception {
        return apps.postMessage(ws(), id, body, key);
    }

    @GetMapping("/{id}/tasks")
    public List<Map<String, Object>> tasks(@PathVariable String id, @RequestParam(defaultValue = "false") boolean active) {
        return apps.tasks(ws(), id, active);
    }

    @GetMapping("/{id}/draft")
    public ResponseEntity<String> draft(@PathVariable String id) {
        Map<String, Object> d = apps.draft(ws(), id);
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(d == null ? "null" : Json.write(d));
    }

    @PutMapping("/{id}/draft")
    public Map<String, Object> putDraft(@PathVariable String id, @RequestBody JsonNode body) { return apps.putDraft(ws(), id, body); }

    @DeleteMapping("/{id}/draft")
    public ResponseEntity<Void> deleteDraft(@PathVariable String id) {
        apps.deleteDraft(ws(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/draft/commit")
    public Map<String, Object> commitDraft(@PathVariable String id, @RequestBody(required = false) JsonNode body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return apps.commitDraft(ws(), id, body == null ? Json.obj() : body, key);
    }

    @PostMapping("/{id}/code")
    public Map<String, Object> saveCode(@PathVariable String id, @RequestBody JsonNode body,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return apps.saveCode(ws(), id, body, key);
    }

    @PostMapping("/{id}/publish")
    public Map<String, Object> publish(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        String workspaceId = ws();
        String v = body == null ? null : body.path("versionId").asText(null);
        Map<String, Object> res = apps.publish(workspaceId, id, v);
        if (platformLogs != null) {
            platformLogs.info("lifecycle", "APP_PUBLISH", workspaceId, null, id, null,
                    "发布应用版本 " + res.getOrDefault("versionId", "") + " → " + res.getOrDefault("siteUrl", ""),
                    null, res);
        }
        return res;
    }

    @PostMapping("/{id}/unpublish")
    public ResponseEntity<Void> unpublish(@PathVariable String id) {
        String workspaceId = ws();
        apps.unpublish(workspaceId, id);
        if (platformLogs != null) {
            platformLogs.info("lifecycle", "APP_UNPUBLISH", workspaceId, null, id, null, "取消发布应用站点", null, Map.of());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/deployments")
    public List<Map<String, Object>> deployments(@PathVariable String id) { return apps.deployments(ws(), id); }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SkillHubController skillHub;

    /** 兼容前端 SkillhubModal 与既有接口约定：/api/apps/{id}/skillhub/* 委托给 SkillHubController */
    @PostMapping("/{id}/skillhub/publish")
    public Map<String, Object> skillhubPublish(jakarta.servlet.http.HttpServletRequest req, @PathVariable String id,
                                               @RequestBody(required = false) JsonNode body) {
        return skillHub.publish(req, id, body);
    }

    @GetMapping("/{id}/skillhub/publications")
    public List<Map<String, Object>> skillhubPublications(@PathVariable String id) {
        return skillHub.publications(null, id);
    }
}
