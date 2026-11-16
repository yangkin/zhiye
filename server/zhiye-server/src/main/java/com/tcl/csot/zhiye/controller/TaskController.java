package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.util.Threads;

import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.repository.TaskEventRepository;
import com.tcl.csot.zhiye.service.task.TaskEvents;
import com.tcl.csot.zhiye.service.task.TaskService;
import com.tcl.csot.zhiye.service.task.TaskSignals;

import com.tcl.csot.zhiye.service.app.Views;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.exception.ApiException;
import org.springframework.http.ResponseEntity;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Profile("api")
@RequestMapping("/api/tasks")
public class TaskController {
    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskService tasks;
    private final TaskEventRepository eventRepo;
    private final TaskEvents events;
    private final Views views;
    private final CurrentPrincipal principal;
    private final java.util.function.Function<String, String> appName;

    private final TaskSignals signals;
    private final com.tcl.csot.zhiye.repository.MessageRepository messages;
    private final com.tcl.csot.zhiye.config.ZhiyeProperties props;
    private final com.tcl.csot.zhiye.repository.TaskRepository taskRepo;

    public TaskController(TaskService tasks, TaskEventRepository eventRepo, TaskEvents events, Views views, CurrentPrincipal principal,
                          com.tcl.csot.zhiye.repository.AppRepository apps, TaskSignals signals, com.tcl.csot.zhiye.repository.MessageRepository messages,
                          com.tcl.csot.zhiye.config.ZhiyeProperties props, com.tcl.csot.zhiye.repository.TaskRepository taskRepo) {
        this.taskRepo = taskRepo;
        this.signals = signals;
        this.messages = messages;
        this.props = props;
        this.tasks = tasks;
        this.eventRepo = eventRepo;
        this.events = events;
        this.views = views;
        this.principal = principal;
        this.appName = id -> apps.findById(id).map(com.tcl.csot.zhiye.entity.AppRow::name).orElse("");
    }

    /** 当前工作空间进行中的任务（每人同时只有 1 个），没有时返回 null */
    @GetMapping("/active")
    public Map<String, Object> active() {
        return tasks.activeInWorkspace(principal.workspaceId()).map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("task", views.task(t));
            m.put("appId", t.appId());
            m.put("appName", appName.apply(t.appId()));
            return m;
        }).orElse(null);
    }

    /** 工具结果的完整详情（SSE 补发时省略的 detail） */
    @GetMapping("/{id}/events/{seq}/detail")
    public Map<String, Object> detail(@PathVariable String id, @PathVariable int seq) {
        TaskRow t = tasks.get(principal.workspaceId(), id);
        var e = eventRepo.get(t.id(), seq).filter(x -> x.payload() != null && (x.payload().has("detail") || "thinking".equals(x.type())))
                .orElseThrow(() -> ApiException.notFound("详情"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("type", e.type());
        if ("thinking".equals(e.type())) {
            m.put("detail", e.payload().path("text").asText(""));
            if (e.payload().has("durationMs")) m.put("durationMs", e.payload().get("durationMs"));
        } else m.put("detail", e.payload().get("detail"));
        m.put("detailTruncated", e.payload().path("detailTruncated").asBoolean(false));
        return m;
    }

    /** 对话中途补充要求：只在 agent 正在编写时可用，其余情况 409 */
    @PostMapping("/{id}/steer")
    public Map<String, Object> steer(@PathVariable String id, @RequestBody JsonNode body) {
        TaskRow t = tasks.get(principal.workspaceId(), id);
        String text = body == null ? "" : body.path("text").asText("").trim();
        if (text.isEmpty()) throw ApiException.validation("请输入要补充的内容");
        if (text.length() > 2000) throw ApiException.validation("补充内容最多 2000 字");
        if (!"RUNNING".equals(t.status()) || !Set.of("generate", "modify").contains(t.type()))
            throw ApiException.conflict("NOT_STEERABLE", "现在不能补充要求：只有 AI 正在编写时才可以");
        boolean ok;
        try {
            // 任务在另一个实例里跑：经数据库转过去（那边约 1 秒内交给 agent）
            ok = signals.isLocal(t.id()) ? signals.steer(t.id(), text)
                    : taskRepo.postSignal(t.id(), "steer", null, com.tcl.csot.zhiye.util.Json.obj().put("text", text));
        } catch (UnsupportedOperationException | IllegalStateException e) {
            throw ApiException.conflict("NOT_STEERABLE", "现在不能补充要求：" + e.getMessage());
        }
        if (!ok) throw ApiException.conflict("NOT_STEERABLE", "现在不能补充要求：AI 不在编写阶段（正在准备、校验或保存）");
        var msg = messages.insertSteer(t.appId(), t.id(), text);
        events.emit(t.id(), "steer", Map.of("text", text, "messageId", msg.id()));
        return Map.of("message", views.message(msg), "task", views.task(tasks.get(principal.workspaceId(), id)));
    }

    /** 确认卡：用户对 agent 请求的操作（如启用云服务）做出选择 */
    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable String id, @RequestBody JsonNode body) {
        TaskRow t = tasks.get(principal.workspaceId(), id);
        String confirmId = body == null ? "" : body.path("confirmId").asText("");
        boolean accept = body != null && body.path("accept").asBoolean(false);
        // 「继续」确认可以带一句话（用户在输入框里说的「继续，顺便把…」）
        String text = body == null ? "" : body.path("text").asText("").trim();
        if (text.length() > 3000) throw ApiException.validation("补充内容最多 3000 字");
        if (confirmId.isEmpty()) throw ApiException.validation("缺少 confirmId");
        boolean ok;
        try {
            String extra = text.isEmpty() ? null : text;
            // 任务在另一个实例里跑：确认卡确实还在等，就经数据库转过去；同一张卡只转一次
            ok = !t.terminal() && (signals.isLocal(t.id()) ? signals.confirm(t.id(), confirmId, accept, extra)
                    : taskRepo.confirmPending(t.id(), confirmId) && taskRepo.postSignal(t.id(), "confirm", "confirm:" + confirmId,
                            com.tcl.csot.zhiye.util.Json.obj().put("confirmId", confirmId).put("accept", accept).put("text", extra)));
        } catch (UnsupportedOperationException | IllegalStateException e) {
            throw ApiException.conflict("CONFIRM_EXPIRED", "这个确认已经失效：" + e.getMessage());
        }
        if (!ok) throw ApiException.ofKey(org.springframework.http.HttpStatus.CONFLICT, "CONFIRM_EXPIRED", "zhiye.error.confirm-expired", "这个确认已经失效（任务已结束或已经选过）");
        // 随「继续」说的话记成补充要求，对话和进度里都能看到
        if (accept && !text.isEmpty()) {
            var msg = messages.insertSteer(t.appId(), t.id(), text);
            events.emit(t.id(), "steer", Map.of("text", text, "messageId", msg.id()));
        }
        return Map.of("ok", true, "accept", accept);
    }

    /** 任务进行中的工作区文件（代码页实时查看）；任务结束后 409，改用版本文件 */
    @GetMapping("/{id}/files")
    public List<Map<String, Object>> files(@PathVariable String id) throws Exception {
        Path root = liveWorkspace(id);
        List<Map<String, Object>> out = new ArrayList<>();
        if (root == null) return out;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(root) && LIVE_SKIP.contains(n)) return FileVisitResult.SKIP_SUBTREE;
                return out.size() >= 5000 ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                if (a.isRegularFile() && !LIVE_SKIP.contains(f.getFileName().toString()))
                    out.add(Map.of("path", root.relativize(f).toString().replace('\\', '/'), "size", a.size()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, java.io.IOException e) { return FileVisitResult.CONTINUE; }
        });
        out.sort(Comparator.comparing(m -> (String) m.get("path")));
        return out;
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String id, @RequestParam String path) throws Exception {
        String rel = com.tcl.csot.zhiye.service.app.AppService.validCodePath(path);
        Path root = liveWorkspace(id);
        if (root == null) throw ApiException.notFound("文件");
        for (String part : rel.split("/")) if (LIVE_SKIP.contains(part)) throw ApiException.validation("不能读取 " + part + " 下的文件");
        Path f = root.resolve(rel).normalize();
        if (!Files.isRegularFile(f) || !f.toRealPath().startsWith(root.toRealPath())) throw ApiException.notFound("文件");
        if (Files.size(f) > (2 << 20)) throw ApiException.validation("文件太大，暂时无法查看");
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store").body(Files.readAllBytes(f));
    }

    static final Set<String> LIVE_SKIP = Set.of(".git", "node_modules", ".zhiye", ".DS_Store");

    private Path liveWorkspace(String id) {
        TaskRow t = tasks.get(principal.workspaceId(), id);
        if (!t.active()) throw ApiException.conflict("TASK_FINISHED", "任务已结束，请查看版本中的文件");
        Path p = signals.workspace(t.id());
        if (p == null) p = props.runtimePath().resolve("workspaces/" + t.id());
        return Files.isDirectory(p) ? p : null;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) { return views.task(tasks.get(principal.workspaceId(), id)); }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) { return views.task(tasks.cancel(principal.workspaceId(), id)); }

    @PostMapping("/{id}/resume")
    public Map<String, Object> resume(@PathVariable String id, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return views.task(tasks.resume(principal.workspaceId(), id, key));
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable String id, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return views.task(tasks.retry(principal.workspaceId(), id, key));
    }

    /** SSE：先补发 after / Last-Event-ID 之后的事件，再推送新事件；发出 finished 后关闭 */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, @RequestParam(required = false) Integer after,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        TaskRow t = tasks.get(principal.workspaceId(), id);
        int from = after == null ? 0 : after;
        if (lastEventId != null) {
            try { from = Math.max(from, Integer.parseInt(lastEventId.trim())); } catch (NumberFormatException ignored) { }
        }
        SseEmitter emitter = new SseEmitter(0L);
        final int start = from;
        Threads.start("sse-" + id, () -> stream(t.id(), start, emitter));
        return emitter;
    }

    static final int REPLAY_DETAIL_LIMIT = 200;

    private void stream(String taskId, int after, SseEmitter emitter) {
        int last = after;
        long idleSince = System.currentTimeMillis();
        // 事件很多时，首次补发去掉工具详情（detailOmitted），展开时再按需加载
        boolean omitInReplay = eventRepo.count(taskId) > REPLAY_DETAIL_LIMIT;
        boolean replay = true;
        // 先订阅思考增量再读事件；客户端按 offset 去重（offset 等于已有文字长度时才追加）
        var deltas = events.subscribe(taskId);
        Map<Integer, Integer> sentLen = new HashMap<>();   // 每个思考块已发给客户端的文字长度（服务端去重）
        List<TaskEvents.ThinkingDelta> pending = new ArrayList<>();
        try {
            while (true) {
                for (TaskEvents.ThinkingDelta d; (d = deltas.poll()) != null; ) pending.add(d);
                for (var it = pending.iterator(); it.hasNext(); ) {
                    var d = it.next();
                    if (d.blockSeq() > last) continue; // 思考块事件还没发出：等它发出后再补
                    it.remove();
                    int have = sentLen.getOrDefault(d.blockSeq(), -1);
                    if (have < 0) continue; // 块已结束或不在本连接里
                    int end = d.offset() + d.delta().length();
                    if (end <= have) continue;
                    String delta = d.offset() >= have ? d.delta() : d.delta().substring(have - d.offset());
                    sentLen.put(d.blockSeq(), end);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "thinking_delta");
                    data.put("payload", Map.of("blockSeq", d.blockSeq(), "delta", delta));
                    emitter.send(SseEmitter.event().name("task").data(Json.write(data), MediaType.APPLICATION_JSON));
                }
                var batch = eventRepo.after(taskId, last);
                boolean finished = false;
                for (var e : batch) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("seq", e.seq());
                    data.put("type", e.type());
                    data.put("createdAt", Db.iso(e.createdAt()));
                    JsonNode payload = e.payload();
                    if ("thinking".equals(e.type()) && payload != null && !payload.path("done").asBoolean(false)) {
                        String live = events.liveThinking(taskId, e.seq());
                        ObjectNode p = payload.deepCopy();
                        if (live != null) p.put("text", live);
                        sentLen.put(e.seq(), p.path("text").asText("").length());
                        payload = p;
                    } else if ("thinking_end".equals(e.type())) {
                        sentLen.remove(e.payload().path("blockSeq").asInt());
                    }
                    if ("thinking".equals(e.type()) && payload != null && payload.path("done").asBoolean(false) && (replay && taskDone(taskId))) {
                        // 已结束的任务只显示「已思考 Ns」，文字展开时再从 detail 接口加载
                        ObjectNode p = Json.obj();
                        p.put("done", true);
                        if (payload.has("durationMs")) p.set("durationMs", payload.get("durationMs"));
                        p.put("textOmitted", true);
                        payload = p;
                    } else if (replay && omitInReplay && payload != null && payload.has("detail")) {
                        ObjectNode p = payload.deepCopy();
                        p.remove("detail");
                        p.put("detailOmitted", true);
                        payload = p;
                    }
                    data.put("payload", payload);
                    emitter.send(SseEmitter.event().id(String.valueOf(e.seq())).name("task").data(Json.write(data), MediaType.APPLICATION_JSON));
                    last = e.seq();
                    if ("finished".equals(e.type())) finished = true;
                }
                replay = false;
                if (finished) break;
                if (!batch.isEmpty()) idleSince = System.currentTimeMillis();
                else if (System.currentTimeMillis() - idleSince > 15_000) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    idleSince = System.currentTimeMillis();
                }
                events.await(1000);
            }
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        } finally {
            events.unsubscribe(taskId, deltas);
        }
    }

    private boolean taskDone(String taskId) {
        return tasks.findById(taskId).map(TaskRow::terminal).orElse(true);
    }
}
