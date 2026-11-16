package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话里「停止回答」：服务端真的停下，只保存用户已经看到的部分，且不留 [[build]] 标记（停了不能再被当成「开始做」）。 */
class ConversationStopTest extends IntegrationTest {

    private static Client.Res ask(Client c, String cid, String content) {
        return c.send("POST", "/api/conversations/" + cid + "/ask", Map.of("content", content), Map.of("Accept", "text/event-stream"));
    }

    private static JsonNode lastAssistant(Client c, String cid) {
        JsonNode msgs = c.ok(c.get("/api/conversations/" + cid)).path("messages");
        JsonNode last = null;
        for (JsonNode m : msgs) if ("assistant".equals(m.path("role").asText())) last = m;
        return last;
    }

    @Test
    void stopKeepsOnlyWhatWasShown() throws Exception {
        Client c = client();
        String cid = c.ok(c.post("/api/conversations", Map.of())).path("id").asText();
        CompletableFuture<Client.Res> stream = CompletableFuture.supplyAsync(() -> ask(c, cid, "做个看板 [slowstream]"));
        Thread.sleep(700);
        JsonNode stop = c.ok(c.post("/api/conversations/" + cid + "/ask/stop", Map.of()));
        assertThat(stop.path("finished").asBoolean()).isFalse();
        assertThat(stream.get().body()).contains("第一段").doesNotContain("第二段");
        Thread.sleep(1500);
        JsonNode a = lastAssistant(c, cid);
        assertThat(a).isNotNull();
        assertThat(a.path("content").asText()).isEqualTo("第一段，\n\n（已停止回答）");
        assertThat(a.path("thinking").asText()).contains("想一想");
    }

    /** 多实例时「停止」请求可能落在别的实例上：浏览器断开流，回答所在的实例也要停下并保存已显示的部分 */
    @Test
    void clientDisconnectKeepsWhatWasShown() throws Exception {
        Client c = client();
        String cid = c.ok(c.post("/api/conversations", Map.of())).path("id").asText();
        String seen = c.postStreamUntil("/api/conversations/" + cid + "/ask", Map.of("content", "做个看板 [slowstream]"), "第一段");
        assertThat(seen).contains("第一段");
        JsonNode a = null;
        for (int i = 0; i < 40 && a == null; i++) { Thread.sleep(200); a = lastAssistant(c, cid); }
        assertThat(a).as("断开后应保存已显示的部分").isNotNull();
        assertThat(a.path("content").asText()).isEqualTo("第一段，\n\n（已停止回答）");
    }

    /** 停止请求所在的实例上没有这个回答：按数据库判断回答是否已经存好 */
    @Test
    void stopWithoutLocalStreamUsesDatabase() {
        Client c = client();
        String cid = c.ok(c.post("/api/conversations", Map.of())).path("id").asText();
        assertThat(c.ok(c.post("/api/conversations/" + cid + "/ask/stop", Map.of())).path("finished").asBoolean()).isFalse();
        assertThat(ask(c, cid, "你好").status()).isEqualTo(200);
        assertThat(c.ok(c.post("/api/conversations/" + cid + "/ask/stop", Map.of())).path("finished").asBoolean()).isTrue();
    }

    @Test
    void lateStopReportsFinished() {
        Client c = client();
        String cid = c.ok(c.post("/api/conversations", Map.of())).path("id").asText();
        assertThat(ask(c, cid, "你好").status()).isEqualTo(200);
        assertThat(c.ok(c.post("/api/conversations/" + cid + "/ask/stop", Map.of())).path("finished").asBoolean()).isTrue();
        assertThat(lastAssistant(c, cid).path("content").asText()).isEqualTo("好");
        // 别人的会话不能停
        assertThat(client().post("/api/conversations/" + cid + "/ask/stop", Map.of()).status()).isEqualTo(404);
    }
}
