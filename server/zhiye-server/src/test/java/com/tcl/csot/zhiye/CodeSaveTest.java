package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 代码页编辑：POST /api/apps/{id}/code → code_save 任务 → 校验 → 新版本。 */
class CodeSaveTest extends IntegrationTest {

    JsonNode skillApp(Client c) {
        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发skill", "prompt", "做一个周报生成 skill", "mode", "快速", "model", "auto", "assetIds", List.of())));
        String id = created.path("id").asText();
        assertThat(c.waitTask(created.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        return c.ok(c.get("/api/apps/" + id));
    }

    @Test
    void saveSkillFiles() {
        Client c = client();
        JsonNode app = skillApp(c);
        String id = app.path("id").asText(), v1 = app.path("currentVersion").path("id").asText();
        String md = "---\nname: weekly-report\ndescription: 生成周报。\n---\n\n# 周报（手动修改）\n";

        // 校验
        assertThat(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of())).status()).isEqualTo(400);
        for (String bad : List.of("../x.md", "/etc/x", "a\\b.md", ".git/config", "node_modules/a.js", ".zhiye/x", "a//b"))
            assertThat(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of(Map.of("path", bad, "content", "x")))).status())
                    .as(bad).isEqualTo(400);
        assertThat(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of(Map.of("path", "a.md")))).status()).isEqualTo(400);
        assertThat(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", "ver_nope", "files", List.of(Map.of("path", "SKILL.md", "content", md)))).code())
                .isEqualTo("VERSION_MISMATCH");

        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("baseVersionId", v1, "files", List.of(
                Map.of("path", "SKILL.md", "content", md),
                Map.of("path", "references/new.md", "content", "# 新增"),
                Map.of("path", "scripts/weekly.py", "delete", true)));
        JsonNode task = c.ok(c.post("/api/apps/" + id + "/code", body, key));
        assertThat(task.path("type").asText()).isEqualTo("code_save");
        assertThat(c.ok(c.post("/api/apps/" + id + "/code", body, key)).path("id").asText()).isEqualTo(task.path("id").asText());
        assertThat(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of(Map.of("path", "x.md", "content", "x")))).code())
                .isIn("APP_BUSY", "VERSION_MISMATCH");
        JsonNode done = c.waitTask(task.path("id").asText());
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");

        JsonNode cur = c.ok(c.get("/api/apps/" + id));
        assertThat(cur.path("currentVersion").path("number").asInt()).isEqualTo(2);
        assertThat(cur.path("currentVersion").path("title").asText()).isEqualTo("代码编辑 · 3 个文件");
        String v2 = cur.path("currentVersion").path("id").asText();
        assertThat(c.ok(c.get("/api/apps/" + id + "/versions")).get(0).path("source").asText()).isEqualTo("code");
        assertThat(c.get("/api/versions/" + v2 + "/file?path=SKILL.md").body()).isEqualTo(md);
        JsonNode files = c.ok(c.get("/api/versions/" + v2 + "/files"));
        assertThat(files.toString()).contains("references/new.md").doesNotContain("scripts/weekly.py");
        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        assertThat(msgs.get(msgs.size() - 1).path("content").asText()).isEqualTo("已保存你对 3 个文件的修改（V2）");
    }

    @Test
    void verifyFailureSavesNothing() {
        Client c = client();
        JsonNode app = skillApp(c);
        String id = app.path("id").asText(), v1 = app.path("currentVersion").path("id").asText();
        faults.set(null, "error");
        JsonNode task = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1,
                "files", List.of(Map.of("path", "SKILL.md", "content", "坏内容")))));
        JsonNode done = c.waitTask(task.path("id").asText());
        faults.set(null, "none");
        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(done.path("error").path("message").asText()).startsWith("检查没有通过，修改未保存：");
        assertThat(c.events(task.path("id").asText(), 0, Map.of())).anyMatch(e -> e.path("type").asText().equals("verify")
                && e.path("payload").path("checks").toString().contains("\"ok\":false"));
        assertThat(c.ok(c.get("/api/apps/" + id + "/versions")).size()).isEqualTo(1);
        assertThat(c.get("/api/versions/" + v1 + "/file?path=SKILL.md").body()).doesNotContain("坏内容");

        // 有可视化草稿时 → DRAFT_PENDING（网页应用）
        JsonNode web = c.createApp("做一个计数器");
        JsonNode g = c.ok(c.post("/api/apps/" + web.path("id").asText() + "/generate", Map.of()));
        c.waitTask(g.path("id").asText());
        String wv = c.ok(c.get("/api/apps/" + web.path("id").asText())).path("currentVersion").path("id").asText();
        c.ok(c.put("/api/apps/" + web.path("id").asText() + "/draft", Map.of("baseVersionId", wv, "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "x")))));
        assertThat(c.post("/api/apps/" + web.path("id").asText() + "/code", Map.of("baseVersionId", wv,
                "files", List.of(Map.of("path", "src/App.tsx", "content", "x")))).code()).isEqualTo("DRAFT_PENDING");
        c.delete("/api/apps/" + web.path("id").asText() + "/draft");
        // 网页应用的代码编辑同样产生带构建的新版本
        JsonNode src = c.ok(c.post("/api/apps/" + web.path("id").asText() + "/code", Map.of("baseVersionId", wv,
                "files", List.of(Map.of("path", "src/App.tsx", "content", c.get("/api/versions/" + wv + "/file?path=src/App.tsx").body().replace("点击计数器", "我的计数器"))))));
        assertThat(c.waitTask(src.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode after = c.ok(c.get("/api/apps/" + web.path("id").asText()));
        assertThat(c.get(local(after.path("previewUrl").asText())).body()).contains("我的计数器");
    }
}
