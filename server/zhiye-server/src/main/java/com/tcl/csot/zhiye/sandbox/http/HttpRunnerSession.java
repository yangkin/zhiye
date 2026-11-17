package com.tcl.csot.zhiye.sandbox.http;

import com.tcl.csot.zhiye.util.Threads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.sandbox.PathMapper;
import com.tcl.csot.zhiye.sandbox.RunnerException;
import com.tcl.csot.zhiye.sandbox.RunnerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 通过 HTTP + SSE 与沙箱里的 runner 通信（见 docs/runner-protocol.md）。
 * <p>
 * 命令用短 POST 提交，runner 立即返回 202；回复和事件都从一条有序的 SSE 流回来，
 * 因此「回复到达」等价于「该命令之前产生的所有事件已经处理完」。
 * 连接断开后从 lastSeq+1 续传，runner 的回放缓冲区不够时直接判死，不静默丢事件。
 */
public class HttpRunnerSession implements RunnerSession {
    private static final Logger log = LoggerFactory.getLogger(HttpRunnerSession.class);

    private final String baseUrl;
    private final Map<String, String> extraHeaders;
    private final String token;
    private final HttpRunnerBackend backend;
    private final Consumer<JsonNode> onEvent;
    private final PrintWriter logWriter;
    /** 非空时在命令参数和回复结果里改写工作进程路径 ↔ 沙箱路径 */
    private final PathMapper pathMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger commandSeq = new AtomicInteger();
    private final AtomicLong lastSeq = new AtomicLong(0);
    /** 首次建连前允许的事件起点；为 0 表示从头回放 */
    private volatile boolean closed;
    private volatile boolean dead;
    private volatile String deathReason;
    private volatile InputStream sseBody;
    private final Thread sseThread;

    public HttpRunnerSession(String baseUrl, Map<String, String> extraHeaders, String token,
                             HttpRunnerBackend backend, Consumer<JsonNode> onEvent, PrintWriter logWriter) {
        this(baseUrl, extraHeaders, token, backend, onEvent, logWriter, null);
    }

    public HttpRunnerSession(String baseUrl, Map<String, String> extraHeaders, String token,
                             HttpRunnerBackend backend, Consumer<JsonNode> onEvent, PrintWriter logWriter,
                             PathMapper pathMapper) {
        this.pathMapper = pathMapper;
        this.baseUrl = trimSlash(baseUrl);
        this.extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        this.token = token;
        this.backend = backend;
        this.onEvent = onEvent;
        this.logWriter = logWriter;
        this.sseThread = Threads.start("runner-sse", this::streamLoop);
    }

    private static String trimSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    // ---------------- 就绪与连接 ----------------

    /** 等 runner 的 /v1/healthz 可用（沙箱启动、本地进程启动后调用）。 */
    public void waitReady(Duration timeout) throws RunnerException.Died, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            if (dead) throw new RunnerException.Died(deathReason);
            try {
                HttpResponse<String> res = http.send(request("/v1/healthz", null).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    logLine("[java] runner 已就绪");
                    return;
                }
                last = new IOException("healthz 返回 " + res.statusCode());
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(100);
        }
        throw new RunnerException.Died("等待 runner 就绪超时" + (last == null ? "" : "：" + last.getMessage()));
    }

    private HttpRequest.Builder request(String path, String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("User-Agent", "zhiye-runner-client/0.1");
        extraHeaders.forEach(b::header);
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return b;
    }

    private void streamLoop() {
        int failures = 0;
        while (!closed) {
            try {
                long after = lastSeq.get();
                HttpResponse<InputStream> res = http.send(
                        request("/v1/events?after=" + after, token).header("Accept", "text/event-stream")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() == 409) {
                    try (InputStream in = res.body()) { in.readAllBytes(); }
                    fail("事件回放缓冲区已失效，任务事件可能丢失");
                    return;
                }
                if (res.statusCode() != 200) {
                    try (InputStream in = res.body()) { in.readAllBytes(); }
                    throw new IOException("events 返回 " + res.statusCode());
                }
                failures = 0;
                sseBody = res.body();
                readStream(sseBody);
            } catch (InterruptedException e) {
                if (!closed) fail("连接被中断");
                return;
            } catch (IOException e) {
                if (closed) return;
                failures++;
                logLine("[java] 事件流断开（" + e.getMessage() + "），尝试续传");
                if (failures > 20 || !backend.alive()) {
                    fail("事件流无法恢复：" + e.getMessage());
                    return;
                }
                try { Thread.sleep(Math.min(1000, 100L * failures)); } catch (InterruptedException ie) { return; }
            } finally {
                sseBody = null;
            }
        }
    }

    private void readStream(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String id = null;
        StringBuilder data = new StringBuilder();
        String line;
        while (!closed && (line = r.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) dispatch(id, data.toString());
                id = null;
                data.setLength(0);
            } else if (line.startsWith(":")) {
                // keepalive
            } else if (line.startsWith("id:")) {
                id = line.substring(3).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(line.substring(5).trim());
            }
        }
    }

    private void dispatch(String id, String data) {
        long seq = -1;
        if (id != null && !id.isBlank()) {
            try { seq = Long.parseLong(id.trim()); } catch (NumberFormatException ignored) { }
        }
        if (seq > 0) {
            long expect = lastSeq.get() + 1;
            if (seq != expect && lastSeq.get() != 0) {
                // 序号不连续：关闭当前连接，让外层按 lastSeq 重新续传
                logLine("[java] 事件序号跳跃：" + expect + " -> " + seq + "，重新连接");
                closeQuietly(sseBody);
                return;
            }
            lastSeq.set(seq);
        }
        JsonNode n;
        try { n = Json.M.readTree(data); } catch (Exception e) {
            logLine("[java] 无法解析事件：" + data);
            return;
        }
        String type = n.path("type").asText();
        switch (type) {
            case "reply" -> {
                String cid = n.path("id").asText();
                CompletableFuture<JsonNode> f = pending.remove(cid);
                if (f != null) f.complete(n);
                else logLine("[java] 收到无法匹配的回复：" + cid);
            }
            case "event" -> {
                try { onEvent.accept(n.path("event")); } catch (Exception e) { log.warn("处理 runner 事件失败", e); }
            }
            case "log" -> logLine(n.path("line").asText(""));
            default -> logLine("[java] 未知消息：" + data);
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) return;
        try { in.close(); } catch (IOException ignored) { }
    }

    // ---------------- RunnerSession ----------------

    @Override
    public JsonNode call(String cmd, ObjectNode args, Duration timeout) throws RunnerException, InterruptedException {
        if (dead) throw new RunnerException.Died(deathReason);
        String id = "c" + commandSeq.incrementAndGet();
        ObjectNode body = Json.obj();
        body.put("id", id);
        body.put("cmd", cmd);
        if (args != null) body.setAll(args);
        if (pathMapper != null) pathMapper.rewrite(body, true);
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        logLine("[java] → " + cmd);
        try {
            post(body);
        } catch (IOException e) {
            pending.remove(id);
            throw new RunnerException.Died("无法提交 runner 命令：" + e.getMessage());
        }
        JsonNode reply;
        try {
            reply = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new RunnerException.Died("runner 命令 " + cmd + " 超时");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RunnerException re) throw re;
            throw new RunnerException.Died(String.valueOf(e.getCause()));
        }
        logLine("[java] ← " + cmd + " ok=" + reply.path("ok").asBoolean());
        if (!reply.path("ok").asBoolean(false)) {
            JsonNode err = reply.path("error");
            throw new RunnerException.CommandFailed(err.path("code").asText("RUNNER_ERROR"),
                    err.path("message").asText("runner 执行失败"));
        }
        JsonNode result = reply.get("result");
        if (result == null) return Json.obj();
        if (pathMapper != null) pathMapper.rewrite(result, false);
        return result;
    }

    @Override
    public void sendAsync(String cmd, ObjectNode args) {
        ObjectNode body = Json.obj();
        body.put("id", cmd + commandSeq.incrementAndGet());
        body.put("cmd", cmd);
        if (args != null) body.setAll(args);
        try {
            post(body);
            logLine("[java] → " + cmd);
        } catch (IOException e) {
            throw new IllegalStateException("无法提交 runner 命令：" + e.getMessage());
        }
    }

    private void post(ObjectNode body) throws IOException {
        try {
            HttpResponse<String> res = http.send(request("/v1/commands", token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 202) {
                throw new IOException("runner 拒绝命令：" + res.statusCode() + " " + res.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("提交命令被中断", e);
        }
    }

    @Override
    public void abort() {
        ObjectNode body = Json.obj();
        body.put("id", "abort" + commandSeq.incrementAndGet());
        body.put("cmd", "abort");
        try { post(body); logLine("[java] → abort"); } catch (IOException e) { logLine("[java] abort 提交失败：" + e.getMessage()); }
    }

    @Override
    public void kill() {
        fail("runner 已被强制结束");
        try { backend.kill(); } catch (Exception e) { log.warn("结束 runner 失败：{}", e.getMessage()); }
    }

    @Override
    public SandboxDescriptor descriptor() { return backend.descriptor(); }

    @Override
    public boolean alive() { return !closed && !dead && backend.alive(); }

    @Override
    public void close() {
        if (closed) return;
        if (!dead && backend.alive()) {
            ObjectNode body = Json.obj();
            body.put("id", "shutdown");
            body.put("cmd", "shutdown");
            try { post(body); } catch (IOException ignored) { }
            try { backend.awaitExit(Duration.ofSeconds(3)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        closed = true;
        closeQuietly(sseBody);
        logLine("[java] runner 会话已关闭");
        try { backend.close(); } catch (Exception e) { log.warn("关闭沙箱失败：{}", e.getMessage()); }
        failPending(new RunnerException.Died("runner 会话已关闭"));
        synchronized (logWriter) { logWriter.close(); }
    }

    private void fail(String reason) {
        deathReason = reason;
        dead = true;
        closeQuietly(sseBody);
        failPending(new RunnerException.Died(reason));
        logLine("[java] runner 会话失败：" + reason);
    }

    private void failPending(RunnerException e) {
        pending.values().forEach(f -> f.completeExceptionally(e));
        pending.clear();
    }

    private void logLine(String s) {
        synchronized (logWriter) { logWriter.println(s); logWriter.flush(); }
    }

    @Override
    public String toString() { return "HttpRunnerSession[" + baseUrl + "]"; }
}