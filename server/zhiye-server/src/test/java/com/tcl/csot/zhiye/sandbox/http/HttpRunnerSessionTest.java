package com.tcl.csot.zhiye.sandbox.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.sandbox.PathMapper;
import com.tcl.csot.zhiye.sandbox.RunnerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTTP + SSE 传输层：顺序、续传、错误分类和关闭语义。 */
class HttpRunnerSessionTest {
    private HttpServer server;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final StringWriter log = new StringWriter();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    /** 最小可用的 runner 控制端：命令处理由测试注入。 */
    private final class Fake {
        final String token = "secret";
        final AtomicLong seq = new AtomicLong();
        final List<String[]> buffer = Collections.synchronizedList(new ArrayList<>());
        final List<BlockingQueue<String>> clients = new CopyOnWriteArrayList<>();
        final Consumer<Fake> onCommand;
        volatile Consumer<Fake> onConnect;
        final AtomicInteger commands = new AtomicInteger();

        Fake(Consumer<Fake> onCommand) { this.onCommand = onCommand; }

        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/healthz", ex -> {
                byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            server.createContext("/v1/events", ex -> {
                if (!auth(ex)) { ex.sendResponseHeaders(401, -1); ex.close(); return; }
                long after = Long.parseLong(ex.getRequestURI().getQuery().replace("after=", ""));
                long oldest = buffer.isEmpty() ? seq.get() + 1 : Long.parseLong(buffer.get(0)[0]);
                if (after < oldest - 1) { ex.sendResponseHeaders(409, -1); ex.close(); return; }
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                BlockingQueue<String> q = new ArrayBlockingQueue<>(1024);
                clients.add(q);
                synchronized (buffer) {
                    for (String[] r : buffer) if (Long.parseLong(r[0]) > after) q.offer(frame(r));
                }
                Consumer<Fake> hook = onConnect;
                if (hook != null && after > 0) Executors.newSingleThreadExecutor().submit(() -> hook.accept(this));
                OutputStream out = ex.getResponseBody();
                Executors.newSingleThreadExecutor().submit(() -> {
                    try {
                        while (true) {
                            String f = q.poll(5, TimeUnit.SECONDS);
                            if (f == null) { out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); continue; }
                            if ("__close__".equals(f)) break;
                            out.write(f.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        clients.remove(q);
                        try { ex.close(); } catch (Exception ignored) { }
                    }
                });
            });
            server.createContext("/v1/commands", ex -> {
                if (!auth(ex)) { ex.sendResponseHeaders(401, -1); ex.close(); return; }
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                commands.incrementAndGet();
                byte[] ok = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(202, ok.length);
                ex.getResponseBody().write(ok);
                ex.close();
                Executors.newSingleThreadExecutor().submit(() -> onCommand.accept(this));
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        boolean auth(HttpExchange ex) {
            String h = ex.getRequestHeaders().getFirst("Authorization");
            return ("Bearer " + token).equals(h);
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        String frame(String[] rec) { return "id: " + rec[0] + "\ndata: " + rec[1] + "\n\n"; }

        void publish(ObjectNode msg) {
            long s = seq.incrementAndGet();
            String payload = Json.write(msg.put("seq", s));
            buffer.add(new String[]{String.valueOf(s), payload});
            String f = frame(new String[]{String.valueOf(s), payload});
            for (var q : clients) q.offer(f);
        }

        void reply(String id, ObjectNode result) {
            ObjectNode m = Json.obj();
            m.put("type", "reply");
            m.put("id", id);
            m.put("ok", true);
            m.set("result", result);
            publish(m);
        }

        void event(String type) {
            ObjectNode m = Json.obj();
            m.put("type", "event");
            m.set("event", Json.obj().put("type", type));
            publish(m);
        }

        void closeClients() { for (var q : clients) q.offer("__close__"); }
    }

    private HttpRunnerSession session(Fake fake, String token, PathMapper mapper) {
        return new HttpRunnerSession(fake.url(), java.util.Map.of(), token, new HttpRunnerBackend() {
            final AtomicBoolean closed = new AtomicBoolean();
            @Override public boolean alive() { return true; }
            @Override public void close() { closed.set(true); }
            @Override public void kill() { closed.set(true); }
        }, n -> events.add(n.path("type").asText()), new PrintWriter(log, true), mapper);
    }

    @Test
    void eventsArriveBeforeCallReturns() throws Exception {
        Fake fake = new Fake(f -> {
            f.event("tool");
            f.reply("c1", Json.obj().put("text", "完成"));
        });
        fake.start();
        HttpRunnerSession s = session(fake, "secret", null);
        s.waitReady(Duration.ofSeconds(5));

        JsonNode r = s.call("prompt", Json.obj().put("text", "hi"), Duration.ofSeconds(5));
        assertThat(r.path("text").asText()).isEqualTo("完成");
        assertThat(events).containsExactly("tool");
        s.close();
    }

    @Test
    void errorCodeIsPreserved() throws Exception {
        Fake fake = new Fake(f -> {
            ObjectNode m = Json.obj();
            m.put("type", "reply");
            m.put("id", "c1");
            m.put("ok", false);
            m.set("error", Json.obj().put("code", "MODEL_ERROR").put("message", "模型挂了"));
            f.publish(m);
        });
        fake.start();
        HttpRunnerSession s = session(fake, "secret", null);
        s.waitReady(Duration.ofSeconds(5));

        assertThatThrownBy(() -> s.call("prompt", Json.obj(), Duration.ofSeconds(5)))
                .isInstanceOf(RunnerException.CommandFailed.class)
                .satisfies(e -> assertThat(((RunnerException.CommandFailed) e).code()).isEqualTo("MODEL_ERROR"));
        s.close();
    }

    @Test
    void reconnectsWithAfterAndDoesNotRepeatEvents() throws Exception {
        AtomicInteger rounds = new AtomicInteger();
        Fake fake = new Fake(f -> {
            f.event("tool");
            f.closeClients();
        });
        fake.onConnect = f -> {
            if (rounds.incrementAndGet() == 1) f.reply("c1", Json.obj().put("text", "断线后继续"));
        };
        fake.start();
        HttpRunnerSession s = session(fake, "secret", null);
        s.waitReady(Duration.ofSeconds(5));

        JsonNode r = s.call("prompt", Json.obj(), Duration.ofSeconds(10));
        assertThat(r.path("text").asText()).isEqualTo("断线后继续");
        assertThat(events).containsExactly("tool");
        s.close();
    }

    @Test
    void wrongTokenFailsAsDied() throws Exception {
        Fake fake = new Fake(f -> f.reply("c1", Json.obj()));
        fake.start();
        HttpRunnerSession s = session(fake, "wrong", null);
        s.waitReady(Duration.ofSeconds(5));

        assertThatThrownBy(() -> s.call("init", Json.obj(), Duration.ofSeconds(5)))
                .isInstanceOf(RunnerException.Died.class);
        s.close();
    }

    @Test
    void rewritesPathsBothWays() throws Exception {
        Fake fake = new Fake(f -> {
            ObjectNode m = Json.obj();
            m.put("type", "reply");
            m.put("id", "c1");
            m.put("ok", true);
            m.set("result", Json.obj().put("sessionFile", "/agent/sessions/a.jsonl"));
            f.publish(m);
        });
        fake.start();
        PathMapper mapper = new PathMapper("/host/ws", "/host/ag", "/workspace", "/agent");
        HttpRunnerSession s = session(fake, "secret", mapper);
        s.waitReady(Duration.ofSeconds(5));

        JsonNode r = s.call("init", Json.obj().put("agentDir", "/host/ag"), Duration.ofSeconds(5));
        assertThat(r.path("sessionFile").asText()).isEqualTo("/host/ag/sessions/a.jsonl");
        s.close();
    }

    @Test
    void closeIsIdempotent() throws Exception {
        Fake fake = new Fake(f -> f.reply("s1", Json.obj()));
        fake.start();
        HttpRunnerSession s = session(fake, "secret", null);
        s.waitReady(Duration.ofSeconds(5));
        s.close();
        s.close();
        assertThat(log.toString()).contains("[java] runner 会话已关闭");
    }
}