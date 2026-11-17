package com.tcl.csot.zhiye.sandbox.k8s;

import com.tcl.csot.zhiye.util.Threads;

import com.tcl.csot.zhiye.sandbox.PathMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.sandbox.RunnerException;
import com.tcl.csot.zhiye.sandbox.RunnerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 通过 {@code kubectl exec} 等价的 WebSocket 流与沙箱 Pod 里的 runner 通信。语义与 ProcessRunnerSession 一致：
 * stdout 每行一个 JSON；stderr 追加到任务日志；prompt 期间可以发 abort；close → 结束 exec 并删除 Pod（grace 0）；
 * Pod 失败 / 被驱逐 / 超过 activeDeadlineSeconds → 所有等待中的命令以 {@link RunnerException.Died} 失败，并带清楚的原因。
 */
class K8sRunnerSession implements RunnerSession {
    private static final Logger log = LoggerFactory.getLogger(K8sRunnerSession.class);

    /** 对 Pod 的操作（便于测试替换） */
    interface PodOps {
        /** Pod 健康时返回 null，否则返回中文原因（已删除、被驱逐、超时、OOM…） */
        String failureReason();
        /** 删除 Pod（grace 0，幂等） */
        void delete();
        String name();
    }

    /** exec 连接（便于测试替换） */
    interface Exec {
        OutputStream stdin();
        InputStream stdout();
        InputStream stderr();
        /** 进程退出码（连接断开时以 -1 完成） */
        CompletableFuture<Integer> exitCode();
        void close();
    }

    private final String taskId;
    private final Exec exec;
    private final PodOps pod;
    private final Writer stdin;
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final Consumer<JsonNode> onEvent;
    private final PrintWriter logWriter;
    private final PathMapper paths;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final SandboxDescriptor descriptor;
    private volatile String endReason;

    K8sRunnerSession(String taskId, Exec exec, PodOps pod, Consumer<JsonNode> onEvent, PrintWriter logWriter,
                     PathMapper paths, Duration monitorInterval) {
        this(taskId, exec, pod, onEvent, logWriter, paths, monitorInterval,
                new SandboxDescriptor("k8s", pod == null ? "" : pod.name(), null, "zhiye-runner", null, null));
    }

    K8sRunnerSession(String taskId, Exec exec, PodOps pod, Consumer<JsonNode> onEvent, PrintWriter logWriter,
                     PathMapper paths, Duration monitorInterval, SandboxDescriptor descriptor) {
        this.taskId = taskId;
        this.exec = exec;
        this.pod = pod;
        this.onEvent = onEvent;
        this.logWriter = logWriter;
        this.paths = paths;
        this.descriptor = descriptor;
        this.stdin = new BufferedWriter(new OutputStreamWriter(exec.stdin(), StandardCharsets.UTF_8));
        Threads.start("k8s-runner-out-" + taskId, this::readLoop);
        Threads.start("k8s-runner-err-" + taskId, this::errLoop);
        Threads.start("k8s-runner-mon-" + taskId, () -> monitorLoop(monitorInterval));
    }

    private void readLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(exec.stdout(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode n;
                try { n = Json.M.readTree(line); } catch (Exception e) {
                    logLine("[stdout] " + line);
                    continue;
                }
                String type = n.path("type").asText();
                if ("reply".equals(type)) {
                    if (n.has("result")) paths.rewrite(n.get("result"), false);
                    CompletableFuture<JsonNode> f = pending.remove(n.path("id").asText());
                    if (f != null) f.complete(n);
                } else if ("event".equals(type)) {
                    try { onEvent.accept(n.path("event")); } catch (Exception e) { log.warn("处理 runner 事件失败", e); }
                } else {
                    logLine("[stdout] " + line);
                }
            }
        } catch (IOException e) {
            // exec 连接断开
        } finally {
            Integer code = null;
            try { code = exec.exitCode().get(2, TimeUnit.SECONDS); } catch (Exception ignored) { }
            String reason = closed.get() ? null : pod.failureReason();
            end(reason != null ? "沙箱已终止：" + reason
                    : "runner 进程已退出（退出码 " + (code == null ? "未知" : code) + "）");
        }
    }

    private void errLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(exec.stderr(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) logLine(line);
        } catch (IOException ignored) { }
    }

    /** 定期检查 Pod：被删除、被驱逐、超过截止时间、OOM 时立即让等待中的命令失败（exec 流不一定马上断开）。 */
    private void monitorLoop(Duration interval) {
        while (!ended.get()) {
            try { Thread.sleep(interval.toMillis()); } catch (InterruptedException e) { return; }
            if (ended.get() || closed.get()) return;
            String reason;
            try { reason = pod.failureReason(); } catch (Exception e) { continue; }
            if (reason != null) {
                end("沙箱已终止：" + reason);
                exec.close();
                return;
            }
        }
    }

    private void end(String message) {
        if (!ended.compareAndSet(false, true)) return;
        endReason = message;
        RunnerException.Died died = new RunnerException.Died(message);
        pending.values().forEach(f -> f.completeExceptionally(died));
        pending.clear();
        logLine("[java] " + message);
    }

    private void logLine(String s) {
        synchronized (logWriter) { logWriter.println(s); logWriter.flush(); }
    }

    private synchronized void send(ObjectNode cmd) throws IOException {
        stdin.write(Json.write(cmd));
        stdin.write('\n');
        stdin.flush();
    }

    @Override
    public JsonNode call(String cmd, ObjectNode args, Duration timeout) throws RunnerException, InterruptedException {
        if (ended.get()) throw new RunnerException.Died(endReason == null ? "runner 已退出" : endReason);
        String id = "c" + seq.incrementAndGet();
        ObjectNode msg = Json.obj();
        msg.put("id", id);
        msg.put("cmd", cmd);
        if (args != null) {
            ObjectNode copy = args.deepCopy();
            paths.rewrite(copy, true);
            msg.setAll(copy);
        }
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        if (ended.get()) { pending.remove(id); throw new RunnerException.Died(endReason); }
        logLine("[java] → " + cmd);
        try {
            send(msg);
        } catch (IOException e) {
            pending.remove(id);
            throw new RunnerException.Died("无法写入 runner：" + e.getMessage());
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
            throw new RunnerException.CommandFailed(err.path("code").asText("RUNNER_ERROR"), err.path("message").asText("runner 执行失败"));
        }
        JsonNode result = reply.get("result");
        return result == null ? Json.obj() : result;
    }

    @Override
    public void abort() {
        ObjectNode msg = Json.obj();
        msg.put("id", "abort" + seq.incrementAndGet());
        msg.put("cmd", "abort");
        try { send(msg); logLine("[java] → abort"); } catch (IOException ignored) { }
    }

    @Override
    public void kill() {
        end("runner 已被终止");
        exec.close();
        pod.delete();
    }

    @Override
    public SandboxDescriptor descriptor() { return descriptor; }

    @Override
    public boolean alive() { return !ended.get() && !exec.exitCode().isDone(); }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (alive()) {
            try {
                ObjectNode msg = Json.obj();
                msg.put("id", "shutdown");
                msg.put("cmd", "shutdown");
                send(msg);
                stdin.close();
            } catch (IOException ignored) { }
            try { exec.exitCode().get(3, TimeUnit.SECONDS); } catch (Exception ignored) { }
        }
        end("runner 已关闭");
        exec.close();
        try { pod.delete(); } catch (Exception e) { log.warn("删除沙箱 Pod {} 失败：{}", pod.name(), e.getMessage()); }
        logLine("[java] 已删除沙箱 Pod " + pod.name());
        synchronized (logWriter) { logWriter.close(); }
    }
}
