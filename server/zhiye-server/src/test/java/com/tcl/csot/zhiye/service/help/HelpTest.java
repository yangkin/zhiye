package com.tcl.csot.zhiye.service.help;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 小织帮助助手：对话 CRUD、工作空间隔离、SSE 协议、停止、次数限制、问题上报。 */
class HelpTest extends IntegrationTest {
    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Autowired JdbcClient jdbc;
    @Autowired HelpService help;

    @org.junit.jupiter.api.BeforeEach
    void useFakeUpstream() { help.testBaseUrl = "http://127.0.0.1:" + port + "/test-help-upstream/v1"; }

    @org.junit.jupiter.api.AfterEach
    void restoreUpstream() { help.testBaseUrl = null; }

    record Sse(int status, List<String[]> events, String body) {
        List<JsonNode> of(String name) {
            return events.stream().filter(e -> e[0].equals(name)).map(e -> Json.read(e[1])).toList();
        }
        String text() {
            StringBuilder b = new StringBuilder();
            for (JsonNode d : of("delta")) b.append(d.path("text").asText());
            return b.toString();
        }
    }

    static List<String[]> parse(String body) {
        List<String[]> out = new ArrayList<>();
        for (String block : body.replace("\r", "").split("\n\n")) {
            String name = "message";
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) name = line.substring(6).trim();
                else if (line.startsWith("data:")) data.append(line.substring(5).trim());
            }
            if (!data.isEmpty()) out.add(new String[]{name, data.toString()});
        }
        return out;
    }

    Sse send(Client c, String conv, Object body) {
        Client.Res r = c.send("POST", "/api/help/conversations/" + conv + "/messages", body, Map.of("Accept", "text/event-stream"));
        if (r.status() == 200) assertThat(r.raw().headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        return new Sse(r.status(), r.status() == 200 ? parse(r.body()) : List.of(), r.body());
    }

    String newConv(Client c) { return c.ok(c.post("/api/help/conversations", Map.of())).path("id").asText(); }

    static byte[] png() throws Exception {
        var img = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }

    @Test
    void crudAndStreaming() throws Exception {
        Client c = client();
        assertThat(c.ok(c.get("/api/help/conversations")).size()).isZero();
        JsonNode conv = c.ok(c.post("/api/help/conversations", Map.of()));
        String id = conv.path("id").asText();
        assertThat(conv.path("title").asText()).isEqualTo("新对话");

        Sse s = send(c, id, Map.of("content", "怎么开始创造一个应用？我想做一个良率看板给同事用"));
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.of("user")).hasSize(1);
        assertThat(s.text()).isEqualTo("你好，我是小织"); // 推理内容不输出
        assertThat(s.of("error")).isEmpty();
        JsonNode done = s.of("done").get(0);
        assertThat(done.path("status").asText()).isEqualTo("done");
        String msgId = done.path("messageId").asText();
        assertThat(msgId).startsWith("hm_");
        assertThat(s.events().get(s.events().size() - 1)[0]).isEqualTo("done");

        // 上游请求：系统提示词、max_tokens、流式
        JsonNode up = FakeHelpUpstreamController.lastBody;
        assertThat(up.path("stream").asBoolean()).isTrue();
        assertThat(up.path("max_tokens").asInt()).isEqualTo(1500);
        assertThat(up.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(up.path("messages").get(0).path("content").asText()).contains("小织").contains("开发skill").contains("50 MB");

        // 标题 = 第一条消息前 20 个字
        JsonNode list = c.ok(c.get("/api/help/conversations"));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("title").asText()).isEqualTo("怎么开始创造一个应用？我想做一个良率看板");

        JsonNode msgs = c.ok(c.get("/api/help/conversations/" + id + "/messages"));
        assertThat(msgs.size()).isEqualTo(2);
        assertThat(msgs.get(0).path("role").asText()).isEqualTo("user");
        assertThat(msgs.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(msgs.get(1).path("id").asText()).isEqualTo(msgId);
        assertThat(msgs.get(1).path("content").asText()).isEqualTo("你好，我是小织");
        assertThat(msgs.get(1).path("status").asText()).isEqualTo("done");

        // 第二轮带上下文 + 图片
        JsonNode asset = c.ok(c.upload("/api/assets", "shot.png", "image/png", png(), Map.of()));
        Sse s2 = send(c, id, Map.of("content", "这个截图是什么问题", "assetIds", List.of(asset.path("id").asText())));
        assertThat(s2.of("done")).hasSize(1);
        JsonNode upMsgs = FakeHelpUpstreamController.lastBody.path("messages");
        assertThat(upMsgs.size()).isEqualTo(4); // system + user + assistant + user
        JsonNode parts = upMsgs.get(3).path("content");
        assertThat(parts.isArray()).isTrue();
        assertThat(parts.get(0).path("type").asText()).isEqualTo("image_url");
        assertThat(parts.get(0).path("image_url").path("url").asText()).startsWith("data:image/png;base64,");
        JsonNode msgs2 = c.ok(c.get("/api/help/conversations/" + id + "/messages"));
        assertThat(msgs2.get(2).path("assets").get(0).path("url").asText()).contains(asset.path("id").asText());
        // 标题不再变化
        assertThat(c.ok(c.get("/api/help/conversations")).get(0).path("title").asText()).startsWith("怎么开始");

        // 校验
        assertThat(send(c, id, Map.of("content", "x".repeat(2001))).status()).isEqualTo(400);
        assertThat(send(c, id, Map.of("content", "  ")).status()).isEqualTo(400);
        assertThat(send(c, id, Map.of("content", "hi", "assetIds", List.of("a", "b", "c", "d"))).status()).isEqualTo(400);
        assertThat(send(c, id, Map.of("content", "hi", "assetIds", List.of("as_nope"))).status()).isEqualTo(400);

        // 新对话排在最前；删除
        String id2 = newConv(c);
        assertThat(c.ok(c.get("/api/help/conversations")).get(0).path("id").asText()).isEqualTo(id2);
        c.ok(c.delete("/api/help/conversations/" + id));
        assertThat(c.get("/api/help/conversations/" + id + "/messages").status()).isEqualTo(404);
        assertThat(c.delete("/api/help/conversations/" + id).status()).isEqualTo(404);
        assertThat(c.ok(c.get("/api/help/conversations")).size()).isEqualTo(1);
    }

    @Test
    void upstreamErrorBecomesErrorEvent() {
        Client c = client();
        String id = newConv(c);
        Sse s = send(c, id, Map.of("content", "[fail] 你好"));
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.of("done")).isEmpty();
        assertThat(s.of("error").get(0).path("message").asText()).isEqualTo("小织暂时没连上，请稍后再试");
        // 用户消息保留，失败的回答不保存
        JsonNode msgs = c.ok(c.get("/api/help/conversations/" + id + "/messages"));
        assertThat(msgs.size()).isEqualTo(1);

        faults.set("error", null);
        Sse s2 = send(c, id, Map.of("content", "再试一次"));
        assertThat(s2.of("error")).hasSize(1);
    }

    @Test
    void workspaceIsolation() {
        Client a = client(), b = client();
        String id = newConv(a);
        assertThat(send(a, id, Map.of("content", "你好")).of("done")).hasSize(1);
        assertThat(b.ok(b.get("/api/help/conversations")).size()).isZero();
        assertThat(b.get("/api/help/conversations/" + id + "/messages").status()).isEqualTo(404);
        assertThat(send(b, id, Map.of("content", "偷看")).status()).isEqualTo(404);
        assertThat(b.post("/api/help/conversations/" + id + "/stop", Map.of()).status()).isEqualTo(404);
        assertThat(b.post("/api/help/feedback", Map.of("content", "x", "conversationId", id)).status()).isEqualTo(404);
        assertThat(b.delete("/api/help/conversations/" + id).status()).isEqualTo(404);
        assertThat(a.ok(a.get("/api/help/conversations/" + id + "/messages")).size()).isEqualTo(2);
        // 别人的图片不能引用
        assertThat(a.get("/api/help/conversations/nope/messages").status()).isEqualTo(404);
    }

    @Test
    void rateLimit() {
        Client c = client();
        String ws = c.ok(c.get("/api/workspace")).path("id").asText();
        String id = newConv(c);
        for (int i = 0; i < HelpService.DAILY_LIMIT; i++)
            jdbc.sql("insert into help_message (id, conversation_id, role, content) values (?, ?, 'user', 'q')").params("hm_rl" + i + ws, id).update();
        assertThat(ws).isNotBlank();
        Sse s = send(c, id, Map.of("content", "第 61 个问题"));
        assertThat(s.status()).isEqualTo(429);
        assertThat(Json.read(s.body()).path("code").asText()).isEqualTo("RATE_LIMITED");
        // 另一个工作空间不受影响
        Client other = client();
        assertThat(send(other, newConv(other), Map.of("content", "你好")).status()).isEqualTo(200);
    }

    /** 读取 SSE 直到第一个 delta，然后 stop=true 调停止接口，否则直接断开连接 */
    void stopMidway(boolean viaEndpoint) throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String base = "http://127.0.0.1:" + port;
        HttpResponse<String> created = http.send(HttpRequest.newBuilder(URI.create(base + "/api/help/conversations"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        String id = Json.read(created.body()).path("id").asText();
        HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(base + "/api/help/conversations/" + id + "/messages"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("content", "[slow] 讲个长故事"))))
                .build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(res.statusCode()).isEqualTo(200);
        BufferedReader r = new BufferedReader(new InputStreamReader(res.body(), StandardCharsets.UTF_8));
        int deltas = 0;
        String line;
        StringBuilder rest = new StringBuilder();
        while ((line = r.readLine()) != null) {
            if (line.startsWith("event:delta") || line.startsWith("event: delta")) deltas++;
            if (deltas >= 3) break;
        }
        assertThat(deltas).isEqualTo(3);
        if (viaEndpoint) {
            HttpResponse<String> stop = http.send(HttpRequest.newBuilder(URI.create(base + "/api/help/conversations/" + id + "/stop"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(Json.read(stop.body()).path("stopped").asBoolean()).isTrue();
            while ((line = r.readLine()) != null) rest.append(line).append('\n');
            List<String[]> ev = parse(rest.toString());
            assertThat(ev.get(ev.size() - 1)[0]).isEqualTo("done");
            assertThat(Json.read(ev.get(ev.size() - 1)[1]).path("status").asText()).isEqualTo("stopped");
        } else {
            res.body().close();
        }
        // 部分回答以 stopped 保存
        JsonNode last = null;
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            HttpResponse<String> m = http.send(HttpRequest.newBuilder(URI.create(base + "/api/help/conversations/" + id + "/messages")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode arr = Json.read(m.body());
            if (arr.size() == 2) { last = arr.get(1); break; }
            Thread.sleep(100);
        }
        assertThat(last).isNotNull();
        assertThat(last.path("status").asText()).isEqualTo("stopped");
        assertThat(last.path("content").asText()).startsWith("块0 块1 块2");
        assertThat(last.path("content").asText()).doesNotContain("块99");
        assertThat(FakeHelpUpstreamController.slowFinished).isFalse();
    }

    @Test
    void stopViaEndpoint() throws Exception { stopMidway(true); }

    @Test
    void stopByDisconnect() throws Exception { stopMidway(false); }

    @Test
    void feedback() throws Exception {
        Client c = client();
        assertThat(c.post("/api/help/feedback", Map.of("content", " ")).status()).isEqualTo(400);
        JsonNode asset = c.ok(c.upload("/api/assets", "bug.png", "image/png", png(), Map.of()));
        JsonNode r = c.ok(c.post("/api/help/feedback", Map.of("content", "预览一直白屏", "assetIds", List.of(asset.path("id").asText()))));
        String fb = r.path("id").asText();
        assertThat(fb).startsWith("FB");
        assertThat(r.path("reply").asText()).contains("已记录你的反馈，编号").contains(fb);
        String ws = c.ok(c.get("/api/workspace")).path("id").asText();
        var row = jdbc.sql("select workspace_id, content, asset_ids::text from help_feedback where id = ?").param(fb)
                .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}).single();
        assertThat(row[0]).isEqualTo(ws);
        assertThat(row[1]).isEqualTo("预览一直白屏");
        assertThat(row[2]).contains(asset.path("id").asText());

        // 带对话：记进对话
        String id = newConv(c);
        JsonNode r2 = c.ok(c.post("/api/help/feedback", Map.of("content", "发布按钮点不动", "conversationId", id)));
        assertThat(r2.path("messages").size()).isEqualTo(2);
        JsonNode msgs = c.ok(c.get("/api/help/conversations/" + id + "/messages"));
        assertThat(msgs.size()).isEqualTo(2);
        assertThat(msgs.get(1).path("content").asText()).contains(r2.path("id").asText());
        assertThat(c.ok(c.get("/api/help/conversations")).get(0).path("title").asText()).startsWith("问题上报");
        // 上报不计入每日提问次数
        assertThat(jdbc.sql("select count(*) from help_message where conversation_id = ? and status = 'feedback'").param(id).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void titleTruncation() {
        assertThat(HelpService.title("短")).isEqualTo("短");
        assertThat(HelpService.title("一二三四五六七八九十一二三四五六七八九十多出来")).isEqualTo("一二三四五六七八九十一二三四五六七八九十");
        assertThat(HelpService.title("  a\n b ")).isEqualTo("a b");
        assertThat(HelpService.title("")).isEqualTo("图片提问");
    }

    @Test
    void imageDataUrlResizesLargeImages() throws Exception {
        var big = new java.awt.image.BufferedImage(3000, 2000, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(big, "png", bo);
        String url = HelpImages.dataUrl("image/png", bo.toByteArray());
        assertThat(url).startsWith("data:image/jpeg;base64,");
        assertThat(HelpImages.dataUrl("image/svg+xml", "<svg/>".getBytes())).isNull();
        assertThat(HelpImages.dataUrl("image/png", png())).startsWith("data:image/png;base64,");
    }
}
