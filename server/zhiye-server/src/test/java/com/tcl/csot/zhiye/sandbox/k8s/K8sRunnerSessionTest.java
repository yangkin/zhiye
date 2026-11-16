package com.tcl.csot.zhiye.sandbox.k8s;

import com.tcl.csot.zhiye.util.Threads;

import com.tcl.csot.zhiye.sandbox.PathMapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.sandbox.RunnerException;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 用管道模拟 exec 流，验证与 ProcessRunnerSession 一致的语义。 */
class K8sRunnerSessionTest {

    static final class FakeExec implements K8sRunnerSession.Exec {
        final PipedOutputStream toRunnerOut = new PipedOutputStream();   // 「runner」写 stdout
        final PipedOutputStream toRunnerErr = new PipedOutputStream();
        final PipedInputStream stdout;
        final PipedInputStream stderr;
        final PipedInputStream runnerStdin;
        final PipedOutputStream stdin;
        final CompletableFuture<Integer> code = new CompletableFuture<>();
        final AtomicBoolean closed = new AtomicBoolean();

        FakeExec() throws IOException {
            stdout = new PipedInputStream(toRunnerOut, 1 << 16);
            stderr = new PipedInputStream(toRunnerErr, 1 << 16);
            stdin = new PipedOutputStream();
            runnerStdin = new PipedInputStream(stdin, 1 << 16);
        }
        void emit(String line) throws IOException { toRunnerOut.write((line + "\n").getBytes(StandardCharsets.UTF_8)); toRunnerOut.flush(); }
        @Override public OutputStream stdin() { return stdin; }
        @Override public InputStream stdout() { return stdout; }
        @Override public InputStream stderr() { return stderr; }
        @Override public CompletableFuture<Integer> exitCode() { return code; }
        @Override public void close() {
            closed.set(true);
            try { toRunnerOut.close(); toRunnerErr.close(); } catch (IOException ignored) { }
            code.complete(-1);
        }
    }

    static final class FakePod implements K8sRunnerSession.PodOps {
        volatile String failure;
        final AtomicBoolean deleted = new AtomicBoolean();
        @Override public String failureReason() { return deleted.get() ? "沙箱 Pod 已被删除" : failure; }
        @Override public void delete() { deleted.set(true); }
        @Override public String name() { return "sb-test"; }
    }

    static Throwable cause(Throwable e) {
        Throwable c = e.getCause();
        return c instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : c;
    }

    record Fixture(K8sRunnerSession session, FakeExec exec, FakePod pod, BufferedReader runnerIn, StringWriter log, List<JsonNode> events) {}

    Fixture fixture() throws IOException {
        FakeExec exec = new FakeExec();
        FakePod pod = new FakePod();
        StringWriter log = new StringWriter();
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        PathMapper m = new PathMapper("/var/lib/zhiye/runtime/workspaces/t1", "/var/lib/zhiye/runtime/agents/t1", "/workspace", "/agent");
        K8sRunnerSession s = new K8sRunnerSession("t1", exec, pod, events::add, new PrintWriter(log), m, Duration.ofMillis(50));
        return new Fixture(s, exec, pod, new BufferedReader(new InputStreamReader(exec.runnerStdin, StandardCharsets.UTF_8)), log, events);
    }

    @Test
    void callReplyEventsAndPathRewrite() throws Exception {
        Fixture f = fixture();
        var reply = CompletableFuture.supplyAsync(() -> {
            try {
                return f.session().call("init", Json.obj().put("agentDir", "/var/lib/zhiye/runtime/agents/t1").put("sessionFile", (String) null), Duration.ofSeconds(5));
            } catch (Exception e) { throw new CompletionException(e); }
        });
        JsonNode cmd = Json.M.readTree(f.runnerIn().readLine());
        assertEquals("init", cmd.path("cmd").asText());
        assertEquals("/agent", cmd.path("agentDir").asText(), "worker 路径要改写成 Pod 内路径");
        f.exec().emit("{\"type\":\"event\",\"event\":{\"type\":\"turn\",\"n\":1}}");
        f.exec().toRunnerErr.write("stderr line\n".getBytes(StandardCharsets.UTF_8));
        f.exec().toRunnerErr.flush();
        f.exec().emit("{\"type\":\"reply\",\"id\":\"" + cmd.path("id").asText() + "\",\"ok\":true,\"result\":{\"sessionFile\":\"/agent/sessions/a.jsonl\"}}");
        JsonNode r = reply.get(5, TimeUnit.SECONDS);
        assertEquals("/var/lib/zhiye/runtime/agents/t1/sessions/a.jsonl", r.path("sessionFile").asText(), "回复里的 Pod 路径要改回 worker 路径");
        assertEquals(1, f.events().size());
        for (int i = 0; i < 50 && !f.log().toString().contains("stderr line"); i++) Thread.sleep(20);
        assertTrue(f.log().toString().contains("stderr line"), "stderr 写入任务日志");
    }

    @Test
    void commandFailedAndAbortDuringPrompt() throws Exception {
        Fixture f = fixture();
        var prompt = CompletableFuture.supplyAsync(() -> {
            try { return f.session().call("prompt", Json.obj().put("text", "做一个页面"), Duration.ofSeconds(5)); }
            catch (Exception e) { throw new CompletionException(e); }
        });
        JsonNode cmd = Json.M.readTree(f.runnerIn().readLine());
        f.session().abort();
        JsonNode abort = Json.M.readTree(f.runnerIn().readLine());
        assertEquals("abort", abort.path("cmd").asText());
        f.exec().emit("{\"type\":\"reply\",\"id\":\"" + cmd.path("id").asText() + "\",\"ok\":false,\"error\":{\"code\":\"MODEL_ERROR\",\"message\":\"上游错误\"}}");
        var e = assertThrows(ExecutionException.class, () -> prompt.get(5, TimeUnit.SECONDS));
        assertInstanceOf(RunnerException.CommandFailed.class, cause(e));
        assertEquals("MODEL_ERROR", ((RunnerException.CommandFailed) cause(e)).code());
    }

    @Test
    void podFailureFailsPendingCommandWithClearReason() throws Exception {
        Fixture f = fixture();
        var prompt = CompletableFuture.supplyAsync(() -> {
            try { return f.session().call("prompt", Json.obj().put("text", "x"), Duration.ofSeconds(10)); }
            catch (Exception e) { throw new CompletionException(e); }
        });
        f.runnerIn().readLine();
        f.pod().failure = "沙箱 Pod 被驱逐（node low on memory）";
        var e = assertThrows(ExecutionException.class, () -> prompt.get(5, TimeUnit.SECONDS));
        Throwable cause = cause(e);
        assertInstanceOf(RunnerException.Died.class, cause);
        assertTrue(cause.getMessage().contains("被驱逐"), cause.getMessage());
        assertFalse(f.session().alive());
        assertTrue(f.exec().closed.get());
        assertThrows(RunnerException.Died.class, () -> f.session().call("verify", Json.obj(), Duration.ofSeconds(1)));
    }

    @Test
    void execEndFailsPendingWithExitCode() throws Exception {
        Fixture f = fixture();
        var call = CompletableFuture.supplyAsync(() -> {
            try { return f.session().call("verify", Json.obj(), Duration.ofSeconds(10)); }
            catch (Exception e) { throw new CompletionException(e); }
        });
        f.runnerIn().readLine();
        f.exec().code.complete(1);
        f.exec().toRunnerOut.close();
        var e = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
        assertTrue(cause(e).getMessage().contains("退出码 1"));
    }

    @Test
    void closeSendsShutdownAndDeletesPod() throws Exception {
        Fixture f = fixture();
        AtomicReference<String> got = new AtomicReference<>();
        Thread t = Threads.start(() -> {
            try { got.set(f.runnerIn().readLine()); f.exec().code.complete(0); } catch (IOException ignored) { }
        });
        f.session().close();
        t.join(5000);
        assertEquals("shutdown", Json.M.readTree(got.get()).path("cmd").asText());
        assertTrue(f.pod().deleted.get(), "close 后删除 Pod");
        assertTrue(f.exec().closed.get());
        assertFalse(f.session().alive());
    }

    @Test
    void killDeletesPodImmediately() throws Exception {
        Fixture f = fixture();
        f.session().kill();
        assertTrue(f.pod().deleted.get());
        assertFalse(f.session().alive());
    }
}
