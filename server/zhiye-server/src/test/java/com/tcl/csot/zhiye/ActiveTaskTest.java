package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 每人同时一个任务，并且可见：GET /api/apps/{id}.activeTask 与 GET /api/tasks/active 覆盖所有任务类型。 */
class ActiveTaskTest extends IntegrationTest {

    /** 任务进行中：应用详情和工作空间的 active 都指向它；结束后都为空 */
    void assertActive(Client c, String appId, String appName, JsonNode task, String type) {
        String tid = task.path("id").asText();
        assertThat(task.path("type").asText()).isEqualTo(type);
        JsonNode detail = c.ok(c.get("/api/apps/" + appId));
        assertThat(detail.path("activeTask").path("id").asText()).as(type).isEqualTo(tid);
        assertThat(detail.path("activeTask").path("type").asText()).isEqualTo(type);
        JsonNode active = c.ok(c.get("/api/tasks/active"));
        assertThat(active.path("task").path("id").asText()).isEqualTo(tid);
        assertThat(active.path("task").path("type").asText()).isEqualTo(type);
        assertThat(active.path("appId").asText()).isEqualTo(appId);
        assertThat(active.path("appName").asText()).isEqualTo(appName == null ? detail.path("name").asText() : appName);
        JsonNode done = c.waitTask(tid);
        assertThat(done.path("status").asText()).as(type + " " + done).isEqualTo("SUCCEEDED");
        assertThat(c.ok(c.get("/api/apps/" + appId)).path("activeTask").isNull()).isTrue();
        assertThat(c.get("/api/tasks/active").body()).isIn("", "null");
    }

    @Test
    void activeTaskForEveryType() {
        Client c = client();
        assertThat(c.get("/api/tasks/active").body()).isIn("", "null");

        // plan
        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "做一个计数器 [slow]", "mode", "快速", "model", "auto", "assetIds", List.of())));
        String id = created.path("id").asText(), name = created.path("name").asText();
        assertActive(c, id, name, created.path("activeTask"), "plan");
        // generate（[slow]）
        assertActive(c, id, null, c.ok(c.post("/api/apps/" + id + "/generate", Map.of())), "generate");
        // modify（[slow]）
        JsonNode m = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "改一下副标题 [slow]")));
        assertActive(c, id, null, m.path("task"), "modify");
        // 别的应用在进行中时，这里是 TASK_LIMIT，另一个应用的 activeTask 为空
        // visual_save（写回 [slow] → 校验耗时）
        JsonNode app = c.ok(c.get("/api/apps/" + id));
        String v = app.path("currentVersion").path("id").asText();
        c.ok(c.put("/api/apps/" + id + "/draft", Map.of("baseVersionId", v, "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "标题 [slow]")))));
        assertActive(c, id, null, c.ok(c.post("/api/apps/" + id + "/draft/commit", Map.of())), "visual_save");
        // code_save
        v = c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText();
        String src = c.get("/api/versions/" + v + "/file?path=src/App.tsx").body();
        assertActive(c, id, null, c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v,
                "files", List.of(Map.of("path", "src/App.tsx", "content", src + "\n// [slow]\n"))))), "code_save");
        // import（隐藏应用也能看到）
        JsonNode up = c.ok(c.upload("/api/imports/upload", "a.html", "text/html",
                "<!doctype html><html><body><h1>你好 [slow]</h1></body></html>".getBytes(StandardCharsets.UTF_8), Map.of()));
        JsonNode r = c.ok(c.post("/api/imports", Map.of("uploadId", up.path("uploadId").asText(), "name", "导入的页面")));
        assertActive(c, r.path("app").path("id").asText(), "导入的页面", r.path("task"), "import");

        // 其他工作空间看不到
        assertThat(client().get("/api/tasks/active").body()).isIn("", "null");
    }

    @Test
    void otherAppSeesWhichAppIsBusy() {
        Client c = client();
        JsonNode a = c.createApp("应用甲 [slow]");
        JsonNode b = c.createApp("应用乙");
        JsonNode t = c.ok(c.post("/api/apps/" + a.path("id").asText() + "/generate", Map.of()));
        assertThat(c.ok(c.get("/api/apps/" + b.path("id").asText())).path("activeTask").isNull()).isTrue();
        JsonNode active = c.ok(c.get("/api/tasks/active"));
        assertThat(active.path("appId").asText()).isEqualTo(a.path("id").asText());
        assertThat(active.path("appName").asText()).isEqualTo(a.path("name").asText());
        assertThat(c.post("/api/apps/" + b.path("id").asText() + "/generate", Map.of()).code()).isEqualTo("TASK_LIMIT");
        c.ok(c.post("/api/tasks/" + t.path("id").asText() + "/cancel", Map.of()));
        c.waitTask(t.path("id").asText());
        assertThat(c.get("/api/tasks/active").body()).isIn("", "null");
    }
}
