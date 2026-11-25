package com.tcl.csot.zhiye.service.runtime;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 实时构建与部署日志 SSE 广播器：
 * - 为每个应用保留最近 240 条部署流水事件（stage / log / done / error）
 * - 支持多标签页实时订阅（GET /api/apps/{appId}/service/deploy-logs/stream）
 */
@Component
public class DeployLogBroadcaster {
    private static final int MAX_HISTORY = 240;

    public record DeployEvent(String type, String stage, String line, String ts) {}

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<DeployEvent>> history = new ConcurrentHashMap<>();

    public static String canonicalAppId(String appId) {
        if (appId == null) return "";
        return appId.endsWith("-prev") ? appId.substring(0, appId.length() - 5) : appId;
    }

    public void reset(String appId, String slot, String versionId) {
        String key = canonicalAppId(appId);
        Deque<DeployEvent> dq = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            dq.clear();
        }
        emitStage(key, "prepare", "[deploy] 开始部署槽位=" + slot + (versionId == null ? "" : " version=" + versionId));
    }

    public void emitStage(String appId, String stage, String line) {
        broadcast(canonicalAppId(appId), new DeployEvent("stage", stage, line, Instant.now().toString()));
    }

    public void emitLog(String appId, String line) {
        if (line == null) return;
        broadcast(canonicalAppId(appId), new DeployEvent("log", "build", line, Instant.now().toString()));
    }

    public void emitDone(String appId, String summary) {
        broadcast(canonicalAppId(appId), new DeployEvent("done", "ready", summary, Instant.now().toString()));
    }

    public void emitError(String appId, String error) {
        broadcast(canonicalAppId(appId), new DeployEvent("error", "failed", error, Instant.now().toString()));
    }

    public List<DeployEvent> recent(String appId) {
        Deque<DeployEvent> dq = history.get(canonicalAppId(appId));
        if (dq == null) return List.of();
        synchronized (dq) {
            return new ArrayList<>(dq);
        }
    }

    public SseEmitter subscribe(String appId) {
        String key = canonicalAppId(appId);
        SseEmitter emitter = new SseEmitter(180_000L);
        var list = subscribers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> list.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        // 先补发当前缓存历史，确保刚打开页面也能看到完整流水
        for (DeployEvent ev : recent(key)) {
            try {
                emitter.send(SseEmitter.event().name(ev.type()).data(ev));
            } catch (Exception e) {
                list.remove(emitter);
                return emitter;
            }
        }
        return emitter;
    }

    private void broadcast(String key, DeployEvent ev) {
        Deque<DeployEvent> dq = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (dq) {
            if (dq.size() >= MAX_HISTORY) dq.removeFirst();
            dq.addLast(ev);
        }
        var list = subscribers.get(key);
        if (list == null || list.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter em : list) {
            try {
                em.send(SseEmitter.event().name(ev.type()).data(ev));
            } catch (Exception e) {
                dead.add(em);
            }
        }
        if (!dead.isEmpty()) list.removeAll(dead);
    }
}
