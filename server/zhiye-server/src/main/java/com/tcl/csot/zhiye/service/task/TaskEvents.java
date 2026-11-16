package com.tcl.csot.zhiye.service.task;

import com.tcl.csot.zhiye.repository.TaskEventRepository;

import com.tcl.csot.zhiye.util.Json;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 写入任务事件并唤醒等待中的 SSE 连接。 */
@Component
public class TaskEvents {
    private final TaskEventRepository repo;
    private final Object signal = new Object();

    public TaskEvents(TaskEventRepository repo) { this.repo = repo; }

    public int emit(String taskId, String type, Object payload) {
        int seq = repo.append(taskId, type, payload == null ? Json.obj() : payload);
        synchronized (signal) { signal.notifyAll(); }
        return seq;
    }

    // ---------- 思考过程：一个思考块 = 一行事件，文字原地追加；增量只在内存里推给 SSE ----------
    public record ThinkingDelta(int blockSeq, int offset, String delta) {}

    private final java.util.Map<String, java.util.List<java.util.concurrent.BlockingQueue<ThinkingDelta>>> subscribers = new java.util.concurrent.ConcurrentHashMap<>();

    public java.util.concurrent.BlockingQueue<ThinkingDelta> subscribe(String taskId) {
        var q = new java.util.concurrent.LinkedBlockingQueue<ThinkingDelta>(10_000);
        subscribers.computeIfAbsent(taskId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(q);
        return q;
    }

    public void unsubscribe(String taskId, java.util.concurrent.BlockingQueue<ThinkingDelta> q) {
        var l = subscribers.get(taskId);
        if (l != null) { l.remove(q); if (l.isEmpty()) subscribers.remove(taskId); }
    }

    /** 进行中的思考块的完整文字（内存），SSE 发送未结束的思考块时用它，避免与增量之间出现缺口 */
    private final java.util.Map<String, StringBuilder> liveThinking = new java.util.concurrent.ConcurrentHashMap<>();

    public String liveThinking(String taskId, int seq) {
        StringBuilder sb = liveThinking.get(taskId + "#" + seq);
        if (sb == null) return null;
        synchronized (sb) { return sb.toString(); }
    }

    public void endLiveThinking(String taskId, int seq) { liveThinking.remove(taskId + "#" + seq); }

    public void thinkingDelta(String taskId, ThinkingDelta d) {
        StringBuilder sb = liveThinking.computeIfAbsent(taskId + "#" + d.blockSeq(), k -> new StringBuilder());
        synchronized (sb) { sb.append(d.delta()); }
        var l = subscribers.get(taskId);
        if (l != null) for (var q : l) q.offer(d);
        synchronized (signal) { signal.notifyAll(); }
    }

    public void updatePayload(String taskId, int seq, Object payload) { repo.updatePayload(taskId, seq, payload); }

    public void status(String taskId, String status, String label, int progress) {
        emit(taskId, "status", Map.of("status", status, "phaseLabel", label, "progress", progress));
    }

    /** 等待新事件（最多 timeoutMs），用于 SSE 轮询 */
    public void await(long timeoutMs) throws InterruptedException {
        synchronized (signal) { signal.wait(timeoutMs); }
    }
}
