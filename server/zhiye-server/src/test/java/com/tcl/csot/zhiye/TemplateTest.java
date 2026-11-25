package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 灵感社区：发布作品（含页面快照）→ 同事浏览、点赞、使用模板复刻（形态、文件一致）→ 只有发布者能删。 */
class TemplateTest extends IntegrationTest {

    @Test
    void saveCloneAndRemove() {
        Client alice = client();
        JsonNode app = alice.createApp("做一个计数器");
        String appId = app.path("id").asText();
        assertThat(alice.post("/api/templates", Map.of("appId", appId)).code()).isEqualTo("VALIDATION");
        alice.waitTask(alice.ok(alice.post("/api/apps/" + appId + "/generate", Map.of())).path("id").asText());
        JsonNode src = alice.ok(alice.get("/api/apps/" + appId));

        JsonNode tpl = alice.ok(alice.post("/api/templates", Map.of("appId", appId, "name", "计数器模板", "description", "点一下加一")));
        assertThat(tpl.path("name").asText()).isEqualTo("计数器模板");
        assertThat(tpl.path("description").asText()).isEqualTo("点一下加一");
        assertThat(tpl.path("mine").asBoolean()).isTrue();
        assertThat(tpl.path("fileCount").asInt()).isPositive();
        String tplId = tpl.path("id").asText();

        Client.Res duplicate = alice.post("/api/templates", Map.of("appId", appId, "name", "重复计数器"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("APP_ALREADY_PUBLISHED");
        assertThat(ids(alice.ok(alice.get("/api/templates?tab=mine"))).stream().filter(tplId::equals).count()).isEqualTo(1);

        // 页面快照：静态页面，不注入云服务令牌
        assertThat(tpl.path("previewUrl").asText()).endsWith("/t/" + tplId + "/");
        Client.Res page = alice.get("/t/" + tplId + "/");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("<html").doesNotContain("__ZHIYE_CLOUD__");
        assertThat(alice.get("/t/tpl_nope/").status()).isEqualTo(404);

        Client bob = client();
        assertThat(bob.post("/api/templates", Map.of("appId", appId)).status()).isEqualTo(404);
        JsonNode seen = null;
        for (JsonNode t : bob.ok(bob.get("/api/templates"))) if (t.path("id").asText().equals(tplId)) seen = t;
        assertThat(seen).isNotNull();
        assertThat(seen.path("mine").asBoolean()).isFalse();
        assertThat(seen.path("liked").asBoolean()).isFalse();

        // 点赞：每个工作空间最多一次，可以取消；「我点赞的」「我发布的」
        assertThat(bob.ok(bob.post("/api/templates/" + tplId + "/like", Map.of())).path("likeCount").asInt()).isEqualTo(1);
        assertThat(bob.ok(bob.post("/api/templates/" + tplId + "/like", Map.of())).path("likeCount").asInt()).isEqualTo(1);
        assertThat(ids(bob.ok(bob.get("/api/templates?tab=liked")))).containsExactly(tplId);
        assertThat(ids(bob.ok(bob.get("/api/templates?tab=mine")))).doesNotContain(tplId);
        assertThat(ids(alice.ok(alice.get("/api/templates?tab=mine")))).contains(tplId);
        assertThat(ids(alice.ok(alice.get("/api/templates?q=" + enc("计数器") + "&type=" + enc(src.path("type").asText()))))).contains(tplId);
        assertThat(ids(alice.ok(alice.get("/api/templates?q=" + enc("不存在的作品"))))).isEmpty();
        assertThat(bob.ok(bob.delete("/api/templates/" + tplId + "/like")).path("liked").asBoolean()).isFalse();
        assertThat(ids(bob.ok(bob.get("/api/templates?tab=liked")))).isEmpty();

        // 详情：每打开一次计一次浏览
        JsonNode d1 = bob.ok(bob.get("/api/templates/" + tplId));
        JsonNode d2 = bob.ok(bob.get("/api/templates/" + tplId));
        assertThat(d2.path("viewCount").asInt()).isEqualTo(d1.path("viewCount").asInt() + 1);
        assertThat(d2.path("related").isArray()).isTrue();

        JsonNode r = bob.ok(bob.post("/api/templates/" + tplId + "/clone", Map.of("name", "我的计数器")));
        assertThat(r.path("task").path("type").asText()).isEqualTo("clone");
        JsonNode done = bob.waitTask(r.path("task").path("id").asText());
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        JsonNode copy = bob.ok(bob.get("/api/apps/" + r.path("app").path("id").asText()));
        assertThat(copy.path("name").asText()).isEqualTo("我的计数器");
        assertThat(copy.path("phase").asText()).isEqualTo("ready");
        assertThat(copy.path("projectKind").asText()).isEqualTo(src.path("projectKind").asText());
        assertThat(copy.path("publishedVersionId").isNull()).isFalse();
        String a = alice.get("/api/versions/" + src.path("currentVersion").path("id").asText() + "/file?path=src/App.tsx").body();
        String b = bob.get("/api/versions/" + copy.path("currentVersion").path("id").asText() + "/file?path=src/App.tsx").body();
        assertThat(b).isEqualTo(a);
        assertThat(bob.ok(bob.get("/api/templates/" + tplId)).path("cloneCount").asInt()).isEqualTo(1);

        assertThat(bob.delete("/api/templates/" + tplId).status()).isEqualTo(403);
        alice.ok(alice.delete("/api/templates/" + tplId));
        assertThat(bob.post("/api/templates/" + tplId + "/clone", Map.of()).status()).isEqualTo(404);
        assertThat(alice.get("/t/" + tplId + "/").status()).isEqualTo(404);
    }

    private static String enc(String s) { return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8); }

    private static java.util.List<String> ids(JsonNode list) {
        java.util.List<String> out = new java.util.ArrayList<>();
        list.forEach(t -> out.add(t.path("id").asText()));
        return out;
    }
}
