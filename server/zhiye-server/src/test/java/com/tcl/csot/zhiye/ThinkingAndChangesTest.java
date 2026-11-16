package com.tcl.csot.zhiye;

import com.tcl.csot.zhiye.util.Threads;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 思考过程（合并成一行、结束后省略文字）与版本改动（查看改动 / 回到此版本）。 */
class ThinkingAndChangesTest extends IntegrationTest {

    @Test
    void thinkingIsOneEventPerBlockAndLazyAfterFinish() {
        Client c = client();
        JsonNode app = c.createApp("做一个计数器");
        JsonNode t = c.ok(c.post("/api/apps/" + app.path("id").asText() + "/generate", Map.of()));
        String tid = t.path("id").asText();
        assertThat(c.waitTask(tid).path("status").asText()).isEqualTo("SUCCEEDED");
        List<JsonNode> events = c.events(tid, 0, Map.of());
        List<JsonNode> thinking = events.stream().filter(e -> e.path("type").asText().equals("thinking")).toList();
        assertThat(thinking).hasSize(1); // 3 段文字合并成一个思考块
        JsonNode th = thinking.get(0);
        assertThat(th.path("payload").path("textOmitted").asBoolean()).isTrue();
        assertThat(th.path("payload").has("text")).isFalse();
        assertThat(th.path("payload").path("durationMs").asLong()).isEqualTo(1200);
        assertThat(events).anyMatch(e -> e.path("type").asText().equals("thinking_end")
                && e.path("payload").path("blockSeq").asInt() == th.path("seq").asInt());
        JsonNode d = c.ok(c.get("/api/tasks/" + tid + "/events/" + th.path("seq").asInt() + "/detail"));
        assertThat(d.path("type").asText()).isEqualTo("thinking");
        assertThat(d.path("detail").asText()).isEqualTo("先看看项目结构，再动手。");
        assertThat(d.path("durationMs").asLong()).isEqualTo(1200);
    }

    @Test
    void thinkingStreamsWhileRunning() throws Exception {
        Client c = client();
        JsonNode app = c.createApp("做一个计数器 [slow]");
        JsonNode t = c.ok(c.post("/api/apps/" + app.path("id").asText() + "/generate", Map.of()));
        String tid = t.path("id").asText();
        c.waitUntil(tid, x -> x.path("todos").size() > 0); // 思考已结束、agent 正在写
        // 进行中的任务补发的思考块带完整文字
        // （SSE 连接会一直开着，这里用后台线程读取，取消任务后连接关闭）
        var reader = new Thread(() -> {});
        List<JsonNode>[] holder = new List[1];
        reader = Threads.start(() -> holder[0] = c.events(tid, 0, Map.of()));
        Thread.sleep(500);
        c.ok(c.post("/api/tasks/" + tid + "/cancel", Map.of()));
        reader.join(30_000);
        JsonNode th = holder[0].stream().filter(e -> e.path("type").asText().equals("thinking")).findFirst().orElseThrow();
        assertThat(th.path("payload").path("text").asText()).isEqualTo("先看看项目结构，再动手。");
        assertThat(th.path("payload").path("done").asBoolean()).isTrue();
    }

    @Test
    void versionChangesAndRestoreFromMessage() {
        Client c = client();
        JsonNode app = c.createApp("做一个计数器");
        String id = app.path("id").asText();
        c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText());
        String v1 = c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText();

        // 第一个版本：与空树比较
        JsonNode first = c.ok(c.get("/api/apps/" + id + "/versions/" + v1 + "/changes"));
        assertThat(first.path("base").isNull()).isTrue();
        assertThat(first.path("files").findValuesAsText("status")).containsOnly("A");
        assertThat(first.path("files").findValuesAsText("path")).contains("src/App.tsx", "package.json").noneMatch(p -> p.startsWith(".zhiye"));

        // 代码编辑：修改 1 个、新增 1 个、删除 1 个
        String app1 = c.get("/api/versions/" + v1 + "/file?path=src/App.tsx").body();
        JsonNode save = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of(
                Map.of("path", "src/App.tsx", "content", app1.replace("点击计数器", "我的计数器")),
                Map.of("path", "src/new.ts", "content", "export const a = 1\nexport const b = 2\n"),
                Map.of("path", "README.md", "delete", true)))));
        assertThat(c.waitTask(save.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        String v2 = c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText();
        JsonNode ch = c.ok(c.get("/api/apps/" + id + "/versions/" + v2 + "/changes"));
        assertThat(ch.path("base").asText()).isEqualTo(v1);
        Map<String, JsonNode> byPath = new java.util.HashMap<>();
        for (JsonNode f : ch.path("files")) byPath.put(f.path("path").asText(), f);
        assertThat(byPath.keySet()).containsExactlyInAnyOrder("src/App.tsx", "src/new.ts", "README.md");
        assertThat(byPath.get("src/App.tsx").path("status").asText()).isEqualTo("M");
        assertThat(byPath.get("src/App.tsx").path("added").asInt()).isEqualTo(1);
        assertThat(byPath.get("src/App.tsx").path("removed").asInt()).isEqualTo(1);
        assertThat(byPath.get("src/new.ts").path("status").asText()).isEqualTo("A");
        assertThat(byPath.get("src/new.ts").path("added").asInt()).isEqualTo(2);
        assertThat(byPath.get("README.md").path("status").asText()).isEqualTo("D");
        assertThat(ch.path("totalAdded").asInt()).isEqualTo(3);

        JsonNode fd = c.ok(c.get("/api/apps/" + id + "/versions/" + v2 + "/changes/file?path=src/App.tsx"));
        assertThat(fd.path("status").asText()).isEqualTo("M");
        assertThat(fd.path("diff").asText()).contains("@@").contains("-      <h1").contains("+      <h1").contains("我的计数器");
        assertThat(fd.path("truncated").asBoolean()).isFalse();
        assertThat(c.get("/api/apps/" + id + "/versions/" + v2 + "/changes/file?path=nope.txt").status()).isEqualTo(404);
        assertThat(client().get("/api/apps/" + id + "/versions/" + v2 + "/changes").status()).isEqualTo(404);
        JsonNode other = c.createApp("别的应用");
        assertThat(c.get("/api/apps/" + other.path("id").asText() + "/versions/" + v2 + "/changes").status()).isEqualTo(404);

        // 助手消息带 versionId
        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        List<String> linked = new java.util.ArrayList<>();
        for (JsonNode m : msgs) if (m.path("role").asText().equals("assistant") && !m.path("versionId").isNull()) linked.add(m.path("versionId").asText());
        assertThat(linked).containsExactly(v1, v2);

        // 回到此版本：任务进行中 409，否则新建版本
        JsonNode busy = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "改一下 [slow]")));
        assertThat(c.post("/api/versions/" + v1 + "/restore", Map.of()).code()).isEqualTo("APP_BUSY");
        c.waitTask(busy.path("task").path("id").asText());
        JsonNode restored = c.ok(c.post("/api/versions/" + v1 + "/restore", Map.of()));
        assertThat(restored.path("number").asInt()).isEqualTo(4);
        assertThat(restored.path("restoredFromNumber").asInt()).isEqualTo(1);
        JsonNode rch = c.ok(c.get("/api/apps/" + id + "/versions/" + restored.path("id").asText() + "/changes"));
        assertThat(rch.path("files").findValuesAsText("path")).contains("src/App.tsx", "src/new.ts", "README.md");
    }
}
