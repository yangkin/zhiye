package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.FakeUpstreamController;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 问答消息附带素材：文档提取成文本片段、图片走视觉输入，消息里记录附件并能在历史上显示。 */
class AskAttachmentTest extends IntegrationTest {

    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    private static Client.Res ask(Client c, String path, Map<String, Object> body) {
        return c.send("POST", path, body, Map.of("Accept", "text/event-stream"));
    }

    private static JsonNode lastUser() {
        JsonNode msgs = FakeUpstreamController.lastBody.path("messages");
        return msgs.get(msgs.size() - 1);
    }

    @Test
    void conversationAskReadsAttachedFiles() {
        Client c = client();
        String cid = c.ok(c.post("/api/conversations", Map.of())).path("id").asText();
        JsonNode txt = c.ok(c.upload("/api/assets", "设计说明.txt", "text/plain",
                "点检表包含设备编号和结果两个字段 ABC123".getBytes(StandardCharsets.UTF_8), Map.of()));
        JsonNode img = c.ok(c.upload("/api/assets", "截图.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3}, Map.of()));

        // 别人工作空间的素材不能引用
        Client other = client();
        String foreign = other.ok(other.upload("/api/assets", "x.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8), Map.of()))
                .path("id").asText();
        assertThat(ask(c, "/api/conversations/" + cid + "/ask",
                Map.of("content", "看附件", "assetIds", List.of(foreign))).status()).isEqualTo(404);

        // 只带文档：文本片段接在问题后面
        assertThat(ask(c, "/api/conversations/" + cid + "/ask",
                Map.of("content", "参考附件回答", "assetIds", List.of(txt.path("id").asText()))).status()).isEqualTo(200);
        String text = lastUser().path("content").asText();
        assertThat(text).contains("参考附件回答").contains("附件「设计说明.txt」").contains("ABC123");

        // 带图片：多模态 content 数组，每个附件先出名字再给内容
        assertThat(ask(c, "/api/conversations/" + cid + "/ask",
                Map.of("content", "这张图里是什么", "assetIds", List.of(txt.path("id").asText(), img.path("id").asText()))).status()).isEqualTo(200);
        JsonNode parts = lastUser().path("content");
        assertThat(parts.isArray()).isTrue();
        assertThat(parts.get(0).path("type").asText()).isEqualTo("text");
        assertThat(parts.get(0).path("text").asText()).contains("这张图里是什么");
        assertThat(parts.get(1).path("text").asText()).contains("设计说明.txt").contains("ABC123");
        assertThat(parts.get(2).path("text").asText()).contains("截图.png");
        assertThat(parts.get(3).path("type").asText()).isEqualTo("image_url");
        assertThat(parts.get(3).path("image_url").path("url").asText())
                .startsWith("data:image/png;base64,")
                .contains(Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3}));

        // 消息记录素材并在会话详情里显示文件名和类型
        JsonNode msgs = c.ok(c.get("/api/conversations/" + cid)).path("messages");
        JsonNode mine = msgs.get(msgs.size() - 2);
        assertThat(mine.path("role").asText()).isEqualTo("user");
        assertThat(mine.path("assets").get(0).path("name").asText()).isEqualTo("设计说明.txt");
        assertThat(mine.path("assets").get(1).path("name").asText()).isEqualTo("截图.png");
        assertThat(mine.path("assets").get(1).path("mime").asText()).isEqualTo("image/png");
    }

    @Test
    void workbenchAskReadsAttachedFiles() {
        Client c = client();
        String appId = c.createApp("做一个点击计数器").path("id").asText();
        c.waitTask(c.ok(c.post("/api/apps/" + appId + "/generate", Map.of())).path("id").asText());
        JsonNode txt = c.ok(c.upload("/api/assets", "需求.md", "text/markdown",
                "# 需求\n加一个深色模式开关 MARK42".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertThat(ask(c, "/api/chat/ask", Map.of("content", "附件里说了什么",
                "appId", appId, "assetIds", List.of(txt.path("id").asText()))).status()).isEqualTo(200);
        String text = lastUser().path("content").asText();
        assertThat(text).contains("附件「需求.md」").contains("MARK42");

        // 工作台消息也记录附件（含文件名），刷新后还能看到
        JsonNode msgs = c.ok(c.get("/api/apps/" + appId + "/messages"));
        JsonNode mine = null;
        for (JsonNode m : msgs) if (m.path("content").asText().equals("附件里说了什么")) mine = m;
        assertThat(mine).isNotNull();
        assertThat(mine.path("assetIds").get(0).asText()).isEqualTo(txt.path("id").asText());
        assertThat(mine.path("assets").get(0).path("name").asText()).isEqualTo("需求.md");
    }
}
