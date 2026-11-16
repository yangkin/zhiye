package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 应用内 AI：预览带令牌调用、发布站点同源调用；没用到 zhiye.ai 的站点不开放；参数校验。 */
class AppAiTest extends IntegrationTest {

    @Test
    void previewAndPublishedChatOnlyWhenAppUsesAi() {
        Client c = client();
        String id = c.createApp("做一个合同字段抽取工具").path("id").asText();
        assertThat(c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        // 还没用到 AI：发布站点的同源接口不开放，页面也不注入 SDK
        JsonNode pub = c.ok(c.post("/api/apps/" + id + "/publish", Map.of()));
        String host = pub.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);
        Map<String, String> h = Map.of("Host", host, "X-Zhiye-Request", "1");
        assertThat(c.getWithHost("/", host).body()).doesNotContain("/__zhiye/sdk.js");
        assertThat(c.send("POST", "/__zhiye/ai/chat", Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))), h).code()).isEqualTo("AI_NOT_USED");

        // 页面里调用 zhiye.ai：重新构建后预览注入令牌，发布站点开放同源接口
        String v1 = c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText();
        String lib = "export const extract = (t: string) => window.zhiye.ai.chat(t);\n";
        JsonNode save = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of(Map.of("path", "src/lib/ai.ts", "content", lib)))));
        assertThat(c.waitTask(save.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        String preview = local(c.ok(c.get("/api/apps/" + id)).path("previewUrl").asText());
        String page = c.get(preview).body();
        assertThat(page).contains("window.__ZHIYE_CLOUD__").contains("\"token\"");
        String token = page.replaceAll("(?s).*\"token\":\"([^\"]+)\".*", "$1");
        Map<String, String> auth = Map.of("Authorization", "Bearer " + token);
        JsonNode r = c.ok(c.send("POST", "/cloud/" + id + "/ai/chat", Map.of("system", "你是助手", "messages", List.of(Map.of("role", "user", "content", "总结一下"))), auth));
        assertThat(r.path("content").asText()).isEqualTo("好");
        assertThat(r.path("usage").path("completionTokens").asInt()).isEqualTo(20);

        // 校验：空消息、角色、图片格式、令牌不对应
        assertThat(c.send("POST", "/cloud/" + id + "/ai/chat", Map.of("messages", List.of()), auth).status()).isEqualTo(400);
        assertThat(c.send("POST", "/cloud/" + id + "/ai/chat", Map.of("messages", List.of(Map.of("role", "system", "content", "x"))), auth).status()).isEqualTo(400);
        assertThat(c.send("POST", "/cloud/" + id + "/ai/chat", Map.of("messages", List.of(Map.of("role", "user", "content", "看图")), "images", List.of("http://evil/x.png")), auth).status()).isEqualTo(400);
        assertThat(c.ok(c.send("POST", "/cloud/" + id + "/ai/chat", Map.of("messages", List.of(Map.of("role", "user", "content", "看图")),
                "images", List.of("data:image/png;base64,iVBORw0KGgo=")), auth)).path("content").asText()).isNotNull();
        assertThat(c.send("POST", "/cloud/app_other/ai/chat", Map.of("messages", List.of(Map.of("role", "user", "content", "x"))), auth).status()).isEqualTo(404);
        assertThat(c.send("POST", "/cloud/" + id + "/ai/chat", Map.of("messages", List.of(Map.of("role", "user", "content", "x"))), Map.of()).status()).isEqualTo(401);

        // 上游出错：502 + 中文说明
        com.tcl.csot.zhiye.support.FakeUpstreamController.failNext = 5;
        var bad = c.send("POST", "/cloud/" + id + "/ai/chat", Map.of("messages", List.of(Map.of("role", "user", "content", "x"))), auth);
        assertThat(bad.status()).isEqualTo(502);
        assertThat(bad.code()).isEqualTo("AI_UNAVAILABLE");
        com.tcl.csot.zhiye.support.FakeUpstreamController.failNext = 0;

        // 重新发布后站点可用，页面里没有令牌
        c.ok(c.post("/api/apps/" + id + "/publish", Map.of()));
        String site = c.getWithHost("/", host).body();
        assertThat(site).contains("/__zhiye/sdk.js").doesNotContain("\"token\"");
        assertThat(c.ok(c.send("POST", "/__zhiye/ai/chat", Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))), h)).path("content").asText()).isEqualTo("好");
        assertThat(c.send("POST", "/__zhiye/ai/chat", Map.of("messages", List.of(Map.of("role", "user", "content", "hi"))), Map.of("Host", host)).status()).isEqualTo(400);
    }
}
