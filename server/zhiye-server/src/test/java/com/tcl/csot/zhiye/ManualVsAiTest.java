package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** AI 修改与手动修改不冲突：修改串行；有草稿时先把手动修改保存成独立版本，再做 AI 修改。 */
class ManualVsAiTest extends IntegrationTest {

    static String agentText(Client c, String taskId) {
        return c.events(taskId, 0, Map.of()).stream().filter(e -> e.path("type").asText().equals("agent_text"))
                .map(e -> e.path("payload").path("delta").asText()).collect(Collectors.joining());
    }

    JsonNode readyApp(Client c, String prompt) {
        JsonNode app = c.createApp(prompt);
        c.waitTask(c.ok(c.post("/api/apps/" + app.path("id").asText() + "/generate", Map.of())).path("id").asText());
        return c.ok(c.get("/api/apps/" + app.path("id").asText()));
    }

    static String cur(Client c, String id) { return c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText(); }

    @Test
    void editsAreSerialized() {
        Client c = client();
        JsonNode app = readyApp(c, "做一个计数器");
        String id = app.path("id").asText(), v = cur(c, id);
        c.ok(c.put("/api/apps/" + id + "/draft", Map.of("baseVersionId", v, "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "草稿")))));
        JsonNode t = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "把副标题改成蓝色 [slow]")));
        // AI 任务进行中：草稿、代码、发布、恢复全部 409 APP_BUSY
        assertThat(c.put("/api/apps/" + id + "/draft", Map.of("baseVersionId", v, "patches", List.of())).code()).isEqualTo("APP_BUSY");
        assertThat(c.delete("/api/apps/" + id + "/draft").status()).isEqualTo(409);
        assertThat(c.post("/api/apps/" + id + "/draft/commit", Map.of()).code()).isEqualTo("APP_BUSY");
        assertThat(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v, "files", List.of(Map.of("path", "a.md", "content", "x")))).code()).isEqualTo("APP_BUSY");
        assertThat(c.post("/api/apps/" + id + "/messages", Map.of("content", "再改")).code()).isEqualTo("APP_BUSY");
        assertThat(c.post("/api/apps/" + id + "/publish", Map.of()).code()).isEqualTo("APP_BUSY");
        assertThat(c.post("/api/versions/" + v + "/restore", Map.of()).code()).isEqualTo("APP_BUSY");
        c.waitTask(t.path("task").path("id").asText());
    }

    @Test
    void draftThenChatGivesTwoVersionsAndRemapsLoc() {
        Client c = client();
        JsonNode app = readyApp(c, "做一个计数器");
        String id = app.path("id").asText(), v1 = cur(c, id);
        // 草稿：改标题文字（__shift__ 让 fake runner 报告该元素下移一行）
        c.ok(c.put("/api/apps/" + id + "/draft", Map.of("baseVersionId", v1,
                "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "手动标题", "match", "__shift__")))));
        JsonNode detail = c.ok(c.get("/api/apps/" + id));
        assertThat(detail.path("hasDraft").asBoolean()).isTrue();
        assertThat(detail.path("draftBaseVersionId").asText()).isEqualTo(v1);

        JsonNode r = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "把字体颜色改为蓝色",
                "elementContext", Map.of("appId", id, "baseVersionId", v1, "loc", "src/App.tsx:7:6", "tag", "H1", "text", "点击计数器",
                        "outerHTML", "<h1>点击计数器</h1>"))));
        String tid = r.path("task").path("id").asText();
        JsonNode done = c.waitTask(tid);
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");

        JsonNode versions = c.ok(c.get("/api/apps/" + id + "/versions"));
        assertThat(versions.size()).isEqualTo(3);
        assertThat(versions.get(1).path("source").asText()).isEqualTo("visual");
        assertThat(versions.get(1).path("title").asText()).isEqualTo("可视化编辑 · 1 处修改");
        assertThat(versions.get(0).path("source").asText()).isEqualTo("modify");
        assertThat(done.path("resultVersionId").asText()).isEqualTo(versions.get(0).path("id").asText());
        String manualSrc = c.get("/api/versions/" + versions.get(1).path("id").asText() + "/file?path=src/App.tsx").body();
        assertThat(manualSrc).contains("手动标题").doesNotContain("#2563eb");
        String finalSrc = c.get("/api/versions/" + versions.get(0).path("id").asText() + "/file?path=src/App.tsx").body();
        assertThat(finalSrc).contains("手动标题").contains("#2563eb");
        var emptyDraft = c.get("/api/apps/" + id + "/draft");
        assertThat(emptyDraft.status()).isEqualTo(200);
        assertThat(emptyDraft.json().isNull()).isTrue();
        JsonNode after = c.ok(c.get("/api/apps/" + id));
        assertThat(after.path("hasDraft").asBoolean()).isFalse();
        assertThat(after.path("draftBaseVersionId").isNull()).isTrue();

        // 事件：先保存手动修改；指令里的位置已按 locMap 改写，并带偏移提示
        assertThat(c.events(tid, 0, Map.of()).stream().anyMatch(e -> e.path("type").asText().equals("status")
                && e.path("payload").path("phaseLabel").asText().equals("正在保存你的手动修改"))).isTrue();
        String text = agentText(c, tid);
        assertThat(text).contains("收到元素位置 src/App.tsx:8:6").contains("已知行号可能偏移");
        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        assertThat(msgs.get(msgs.size() - 1).path("content").asText()).startsWith("已先把你的手动修改保存为 V2，AI 修改保存为 V3。");
    }

    @Test
    void manualStageFailureKeepsDraftAndSkipsAi() {
        Client c = client();
        JsonNode app = readyApp(c, "做一个计数器");
        String id = app.path("id").asText(), v1 = cur(c, id);
        c.ok(c.put("/api/apps/" + id + "/draft", Map.of("baseVersionId", v1,
                "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "x", "index", 1, "match", "__reject__")))));
        JsonNode r = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "把标题改成红色")));
        String tid = r.path("task").path("id").asText();
        JsonNode done = c.waitTask(tid);
        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(done.path("error").path("message").asText()).startsWith("你的手动修改没能保存：")
                .endsWith("AI 修改没有开始，手动修改仍保留在草稿中。");
        assertThat(c.ok(c.get("/api/apps/" + id + "/versions")).size()).isEqualTo(1);
        assertThat(c.ok(c.get("/api/apps/" + id + "/draft")).path("patches").get(0).path("match").asText()).isEqualTo("__reject__");
        assertThat(c.events(tid, 0, Map.of()).stream().noneMatch(e -> e.path("type").asText().equals("tool"))).isTrue(); // AI 没有开始
        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        JsonNode user = null;
        for (JsonNode m : msgs) if (m.path("id").asText().equals(r.path("message").path("id").asText())) user = m;
        assertThat(user.path("status").asText()).isEqualTo("failed");
        assertThat(msgs.get(msgs.size() - 1).path("content").asText()).startsWith("你的手动修改没能保存：");
    }

    @Test
    void aiStageFailureKeepsManualVersionAndRetryStartsThere() {
        Client c = client();
        JsonNode app = readyApp(c, "做一个计数器");
        String id = app.path("id").asText(), v1 = cur(c, id);
        c.ok(c.put("/api/apps/" + id + "/draft", Map.of("baseVersionId", v1, "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "手动标题")))));
        faults.set("error", null);
        JsonNode r = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "把标题改成“今日清单”")));
        JsonNode failed = c.waitTask(r.path("task").path("id").asText());
        faults.set("none", null);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("error").path("message").asText()).startsWith("你的手动修改已保存为 V2；AI 修改没有完成：");
        JsonNode versions = c.ok(c.get("/api/apps/" + id + "/versions"));
        assertThat(versions.size()).isEqualTo(2);
        assertThat(versions.get(0).path("source").asText()).isEqualTo("visual");
        var emptyDraft = c.get("/api/apps/" + id + "/draft");
        assertThat(emptyDraft.status()).isEqualTo(200);
        assertThat(emptyDraft.json().isNull()).isTrue();
        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        assertThat(msgs.get(msgs.size() - 1).path("content").asText()).startsWith("你的手动修改已保存为 V2；AI 修改没有完成：");

        // 重试：从 V2 开始，不再写回草稿（不会出现第二个可视化版本）
        JsonNode retry = c.ok(c.post("/api/tasks/" + failed.path("id").asText() + "/retry", Map.of()));
        JsonNode done = c.waitTask(retry.path("id").asText());
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        versions = c.ok(c.get("/api/apps/" + id + "/versions"));
        assertThat(versions.size()).isEqualTo(3);
        assertThat(versions.get(0).path("source").asText()).isEqualTo("modify");
        assertThat(versions.findValuesAsText("source").stream().filter("visual"::equals).count()).isEqualTo(1);
        String src = c.get("/api/versions/" + versions.get(0).path("id").asText() + "/file?path=src/App.tsx").body();
        assertThat(src).contains("今日清单");
        assertThat(c.events(retry.path("id").asText(), 0, Map.of()).stream().noneMatch(e ->
                e.path("payload").path("phaseLabel").asText().equals("正在保存你的手动修改"))).isTrue();
    }
}
