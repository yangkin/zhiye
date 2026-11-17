package com.tcl.csot.zhiye.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 测试用 HTTP 客户端：每个实例一个 cookie 罐（= 一个工作空间）。 */
public class Client {
    public record Res(int status, String body, HttpResponse<byte[]> raw) {
        public JsonNode json() { return body == null || body.isEmpty() ? null : Json.read(body); }
        public String code() { JsonNode j = json(); return j == null ? null : j.path("code").asText(null); }
    }

    private final String base;
    private final HttpClient http;
    private final Map<String, String> defaultHeaders;

    public Client(int port) {
        this(port, HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(5)).build(), Map.of());
    }

    private Client(String base, HttpClient http, Map<String, String> defaultHeaders) {
        this.base = base;
        this.http = http;
        this.defaultHeaders = defaultHeaders;
    }

    private Client(int port, HttpClient http, Map<String, String> defaultHeaders) {
        this("http://127.0.0.1:" + port, http, defaultHeaders);
    }

    /** 复用同一 cookie 会话，并附带平台管理员测试密钥请求头。 */
    public Client asAdmin() {
        Map<String, String> merged = new LinkedHashMap<>(defaultHeaders);
        merged.put("X-Zhiye-Admin-Token", "test-admin-token");
        return new Client(base, http, Map.copyOf(merged));
    }

    public String base() { return base; }

    public Res send(String method, String path, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(path.startsWith("http") ? path : base + path)).timeout(Duration.ofSeconds(60));
            defaultHeaders.forEach(b::header);
            headers.forEach(b::header);
            if (body != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : Json.write(body)));
            } else b.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Res(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8), r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 流式 POST：读到 until 出现就断开连接（模拟浏览器中途关掉 / 停止），返回已读到的内容 */
    public String postStreamUntil(String path, Object body, String until) {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
            HttpResponse<java.io.InputStream> res = http.send(r, HttpResponse.BodyHandlers.ofInputStream());
            StringBuilder seen = new StringBuilder();
            try (java.io.InputStream in = res.body()) {
                byte[] buf = new byte[512];
                for (int n; (n = in.read(buf)) > 0; ) {
                    seen.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (seen.indexOf(until) >= 0) break;
                }
            }
            return seen.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Res get(String path) { return send("GET", path, null, Map.of()); }
    public Res post(String path, Object body) { return send("POST", path, body == null ? Map.of() : body, Map.of("Idempotency-Key", UUID.randomUUID().toString())); }
    public Res post(String path, Object body, String idemKey) { return send("POST", path, body, Map.of("Idempotency-Key", idemKey)); }
    public Res put(String path, Object body) { return send("PUT", path, body, Map.of()); }
    public Res patch(String path, Object body) { return send("PATCH", path, body, Map.of()); }
    public Res delete(String path) { return send("DELETE", path, null, Map.of()); }

    public JsonNode ok(Res r) {
        if (r.status() >= 300) throw new AssertionError("HTTP " + r.status() + ": " + r.body());
        return r.json();
    }

    public Res upload(String path, String fileName, String mime, byte[] data, Map<String, String> fields) {
        String boundary = "----zhiye" + UUID.randomUUID();
        var out = new java.io.ByteArrayOutputStream();
        try {
            for (var f : fields.entrySet()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n" + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            return new Res(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8), r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 带自定义 Host 头访问站点网关 */
    public Res getWithHost(String path, String host) {
        return send("GET", path, null, Map.of("Host", host));
    }

    public JsonNode waitTask(String id, Duration timeout) {
        long end = System.currentTimeMillis() + timeout.toMillis();
        JsonNode last = null;
        while (System.currentTimeMillis() < end) {
            last = ok(get("/api/tasks/" + id));
            if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(last.path("status").asText())) return last;
            try { Thread.sleep(150); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
        throw new AssertionError("任务超时：" + last);
    }

    public JsonNode waitTask(String id) { return waitTask(id, Duration.ofSeconds(60)); }

    public JsonNode waitUntil(String id, java.util.function.Predicate<JsonNode> p) {
        long end = System.currentTimeMillis() + 30_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < end) {
            last = ok(get("/api/tasks/" + id));
            if (p.test(last)) return last;
            try { Thread.sleep(100); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
        throw new AssertionError("等待超时：" + last);
    }

    /** 读取 SSE（任务已结束时服务端会关闭连接） */
    public List<JsonNode> events(String id, int after, Map<String, String> headers) {
        Res r = send("GET", "/api/tasks/" + id + "/events?after=" + after, null, headers);
        if (r.status() != 200) throw new AssertionError("SSE " + r.status() + " " + r.body());
        if (!r.raw().headers().firstValue("content-type").orElse("").contains("text/event-stream")) throw new AssertionError("不是 SSE");
        List<JsonNode> out = new ArrayList<>();
        for (String block : r.body().split("\n\n")) {
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) if (line.startsWith("data:")) data.append(line.substring(5).trim());
            if (!data.isEmpty()) out.add(Json.read(data.toString()));
        }
        return out;
    }

    /** 创建应用并等待计划完成 */
    public JsonNode createApp(String prompt) {
        JsonNode app = ok(post("/api/apps", Map.of("type", "开发网站", "prompt", prompt, "mode", "快速", "model", "auto", "assetIds", List.of())));
        String appId = app.path("id").asText();
        String taskId = app.path("activeTask").path("id").asText("");
        if (!taskId.isBlank()) {
            JsonNode t = waitTask(taskId);
            if (!"SUCCEEDED".equals(t.path("status").asText())) throw new AssertionError("计划失败 " + t);
        }
        JsonNode latest = ok(get("/api/apps/" + appId));
        if ("failed".equals(latest.path("phase").asText())) throw new AssertionError("计划失败 " + latest);
        if ("planning".equals(latest.path("phase").asText())) {
            String pendingTask = latest.path("activeTask").path("id").asText("");
            if (!pendingTask.isBlank()) {
                JsonNode t = waitTask(pendingTask);
                if (!"SUCCEEDED".equals(t.path("status").asText())) throw new AssertionError("计划失败 " + t);
                latest = ok(get("/api/apps/" + appId));
            }
        }
        return latest;
    }
}
