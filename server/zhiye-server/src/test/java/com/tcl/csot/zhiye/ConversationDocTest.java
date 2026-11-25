package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.FakeUpstreamController;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话：先判断成品形态；文字材料直接写成文件块，思考过程流给界面并保存。 */
class ConversationDocTest extends IntegrationTest {
    @Autowired JdbcClient jdbc;

    /** 记忆提炼等附加调用也会进 BODIES，按会话系统提示词找回真正的问答请求 */
    private static JsonNode latestAskBody() {
        var it = FakeUpstreamController.BODIES.descendingIterator();
        while (it.hasNext()) {
            JsonNode b = it.next();
            if (b.toString().contains("先判断要交付什么")) return b;
        }
        throw new AssertionError("没有找到会话问答的上游请求");
    }

    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Test
    void documentRequestStreamsThinkingAndSavesFile() {
        Client c = client();
        String id = c.ok(c.post("/api/conversations", Map.of())).path("id").asText();
        Client.Res r = c.send("POST", "/api/conversations/" + id + "/ask",
                Map.of("content", "[doc] 帮我写一份设备点检系统的技术设计文档", "type", "技术文档"), Map.of("Accept", "text/event-stream"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("event:thinking").contains("用户要的是一份文档").contains("event:done");

        JsonNode up = latestAskBody();
        String system = up.path("messages").get(0).path("content").asText();
        assertThat(system).contains("先判断要交付什么").contains("不要输出 [[next]]").contains("[[file:文件名.md]]").contains("用户在创造页选的类型：技术文档");
        assertThat(up.path("max_tokens").asInt()).isEqualTo(16000);
        assertThat(up.path("reasoning_effort").asText()).isEqualTo("medium");

        JsonNode msgs = c.ok(c.get("/api/conversations/" + id)).path("messages");
        JsonNode reply = msgs.get(msgs.size() - 1);
        assertThat(reply.path("role").asText()).isEqualTo("assistant");
        assertThat(reply.path("content").asText()).contains("[[file:示例设计文档.md]]").contains("[[/file]]").doesNotContain("[[build]]");
        assertThat(reply.path("thinking").asText()).isEqualTo("用户要的是一份文档，不是应用。");
        assertThat(msgs.get(0).has("thinking")).isFalse();

        // 下一轮：带文件的回答整段进历史（超过普通消息的 2000 字上限也不截断）
        c.send("POST", "/api/conversations/" + id + "/ask", Map.of("content", "第二节再展开"), Map.of("Accept", "text/event-stream"));
        String history = latestAskBody().path("messages").toString();
        assertThat(history).contains("[[/file]]");

        // 右侧文件面板：文件块存成版本，同名再写一次是 V2，列表只给最新版本
        JsonNode files = c.ok(c.get("/api/conversations/" + id + "/files"));
        assertThat(files.size()).isEqualTo(1);
        assertThat(files.get(0).path("name").asText()).isEqualTo("示例设计文档.md");
        assertThat(files.get(0).path("version").asInt()).isEqualTo(1);
        assertThat(files.get(0).path("messageId").asText()).isEqualTo(reply.path("id").asText());
        c.send("POST", "/api/conversations/" + id + "/ask", Map.of("content", "[doc] 再改一版"), Map.of("Accept", "text/event-stream"));
        files = c.ok(c.get("/api/conversations/" + id + "/files"));
        assertThat(files.size()).isEqualTo(1);
        assertThat(files.get(0).path("version").asInt()).isEqualTo(2);
        String fid = files.get(0).path("id").asText();
        // 历史页的会话记录：没建应用的会话也在，带文件数和消息数
        JsonNode row = null;
        for (JsonNode x : c.ok(c.get("/api/conversations"))) if (x.path("id").asText().equals(id)) row = x;
        assertThat(row).isNotNull();
        assertThat(row.path("fileCount").asInt()).isEqualTo(1);
        assertThat(row.path("messageCount").asInt()).isEqualTo(6);
        assertThat(row.has("appId")).isFalse();
        JsonNode f = c.ok(c.get("/api/conversations/" + id + "/files/" + fid));
        assertThat(f.path("content").asText()).startsWith("# 示例设计文档").doesNotContain("[[file:").doesNotContain("[[/file]]");
        // 别人的会话看不到
        assertThat(client().get("/api/conversations/" + id + "/files").status()).isEqualTo(404);
        assertThat(c.ok(c.delete("/api/conversations/" + id + "/files/" + fid)).path("ok").asBoolean()).isTrue();
        assertThat(c.ok(c.get("/api/conversations/" + id + "/files")).size()).isZero();
        assertThat(c.get("/api/conversations/" + id + "/files/" + fid).status()).isEqualTo(404);
    }

    /** V19 之前的会话：文件只在消息正文里，第一次打开文件面板时补记 */
    @Test
    void filesWrittenBeforeFileTableAreBackfilled() {
        Client c = client();
        String id = c.ok(c.post("/api/conversations", Map.of())).path("id").asText();
        jdbc.sql("insert into conversation_message (id, conversation_id, role, content) values (?, ?, 'assistant', ?)")
                .params("msg_old_" + id, id, "好的。\n[[file:旧文档]]\n# 旧文档\n\n正文\n[[/file]]").update();
        JsonNode files = c.ok(c.get("/api/conversations/" + id + "/files"));
        assertThat(files.size()).isEqualTo(1);
        assertThat(files.get(0).path("name").asText()).isEqualTo("旧文档.md");
        assertThat(c.ok(c.get("/api/conversations/" + id + "/files")).size()).isEqualTo(1);
    }

    /** 与前端输入框 maxLength=3000 对齐：2500 字正常受理，超过 3000 字返回 400。 */
    @Test
    void askLengthLimitMatchesFrontend3000() {
        Client c = client();
        String id = c.ok(c.post("/api/conversations", Map.of())).path("id").asText();
        Client.Res ok = c.send("POST", "/api/conversations/" + id + "/ask",
                Map.of("content", "测".repeat(2500)), Map.of("Accept", "text/event-stream"));
        assertThat(ok.status()).isEqualTo(200);
        Client.Res tooLong = c.send("POST", "/api/conversations/" + id + "/ask",
                Map.of("content", "测".repeat(3001)), Map.of("Accept", "text/event-stream"));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body()).contains("3000");
    }
}
