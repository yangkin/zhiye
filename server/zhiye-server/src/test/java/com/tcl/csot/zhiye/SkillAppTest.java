package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 开发skill：只产出 skill 包文件，没有构建、预览和发布。 */
class SkillAppTest extends IntegrationTest {

    @Test
    void skillAppHasNoBuildPreviewOrPublish() throws Exception {
        Client c = client();
        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发skill", "prompt", "做一个周报生成 skill", "mode", "快速", "model", "auto", "assetIds", List.of())));
        assertThat(created.path("projectKind").asText()).isEqualTo("skill");
        assertThat(created.path("visualEditable").asBoolean()).isFalse();
        String id = created.path("id").asText();
        // 跳过创造计划：直接生成
        assertThat(created.path("phase").asText()).isEqualTo("generating");
        assertThat(created.path("activeTask").path("type").asText()).isEqualTo("generate");
        assertThat(created.path("planDoc").asText()).isEqualTo("做一个周报生成 skill");
        assertThat(created.path("name").asText()).isNotBlank();
        JsonNode done = c.waitTask(created.path("activeTask").path("id").asText());
        JsonNode allTasks = c.ok(c.get("/api/apps/" + id + "/tasks"));
        assertThat(allTasks.size()).isEqualTo(1);
        assertThat(allTasks.findValuesAsText("type")).doesNotContain("plan");
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        JsonNode app = c.ok(c.get("/api/apps/" + id));
        assertThat(app.path("phase").asText()).isEqualTo("ready");
        assertThat(app.path("currentVersion").path("number").asInt()).isEqualTo(1);
        assertThat(app.path("previewUrl").isNull()).isTrue();
        assertThat(app.path("siteUrl").isNull()).isTrue();
        JsonNode versions = c.ok(c.get("/api/apps/" + id + "/versions"));
        assertThat(versions.get(0).path("previewUrl").isNull()).isTrue();
        String vid = versions.get(0).path("id").asText();

        JsonNode files = c.ok(c.get("/api/versions/" + vid + "/files"));
        assertThat(files.toString()).contains("\"SKILL.md\"").contains("scripts/weekly.py").doesNotContain("package.json").doesNotContain("src/");

        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        assertThat(msgs.get(msgs.size() - 1).path("content").asText()).contains("技能包已生成").doesNotContain("预览已就绪");

        var pub = c.post("/api/apps/" + id + "/publish", Map.of());
        assertThat(pub.status()).isEqualTo(400);
        assertThat(pub.code()).isEqualTo("SKILL_NOT_PUBLISHABLE");
        assertThat(pub.json().path("message").asText()).isEqualTo("技能不需要发布，可以下载技能包使用");
        assertThat(c.get("/api/versions/" + vid + "/page").status()).isEqualTo(400);
        assertThat(c.put("/api/apps/" + id + "/draft", Map.of("baseVersionId", vid, "patches", List.of())).status()).isEqualTo(400);
        assertThat(c.post("/api/apps/" + id + "/draft/commit", Map.of()).status()).isEqualTo(400);

        var zip = c.get("/api/versions/" + vid + "/export");
        assertThat(zip.status()).isEqualTo(200);
        Set<String> tops = new HashSet<>();
        List<String> names = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip.raw().body()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) { names.add(e.getName()); tops.add(e.getName().split("/")[0]); }
        }
        assertThat(tops).containsExactly("weekly-report");
        assertThat(names).contains("weekly-report/SKILL.md", "weekly-report/scripts/weekly.py");

        // 对话修改仍然可以产生新版本（同样没有构建）
        JsonNode m = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "增加一个示例")));
        assertThat(c.waitTask(m.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("number").asInt()).isEqualTo(2);
        // 不能切换成网页类型
        assertThat(c.patch("/api/apps/" + id, Map.of("type", "开发网站")).status()).isEqualTo(400);
    }

    @Test
    void skillGenerateFailureRetryAndCancel() {
        Client c = client();
        faults.set("error", null);
        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发skill", "prompt", "做一个翻译 skill", "mode", "快速", "model", "auto", "assetIds", List.of())));
        JsonNode failed = c.waitTask(created.path("activeTask").path("id").asText());
        faults.set("none", null);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("retryable").asBoolean()).isTrue();
        String id = created.path("id").asText();
        assertThat(c.ok(c.get("/api/apps/" + id)).path("phase").asText()).isEqualTo("failed");
        JsonNode retry = c.ok(c.post("/api/tasks/" + failed.path("id").asText() + "/retry", Map.of()));
        assertThat(c.waitTask(retry.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(c.ok(c.get("/api/apps/" + id)).path("phase").asText()).isEqualTo("ready");

        JsonNode slow = c.ok(c.post("/api/apps", Map.of("type", "开发skill", "prompt", "做一个摘要 skill [slow]", "mode", "快速", "model", "auto", "assetIds", List.of())));
        String tid = slow.path("activeTask").path("id").asText();
        c.waitUntil(tid, t -> "RUNNING".equals(t.path("status").asText()));
        c.ok(c.post("/api/tasks/" + tid + "/cancel", Map.of()));
        JsonNode stopped = c.waitTask(tid);
        assertThat(stopped.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(stopped.path("resumable").asBoolean()).isTrue();
        assertThat(c.ok(c.get("/api/apps/" + slow.path("id").asText())).path("phase").asText()).as("主动停止和失败分开").isEqualTo("stopped");
        JsonNode resumed = c.ok(c.post("/api/tasks/" + tid + "/resume", Map.of()));
        assertThat(c.waitTask(resumed.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        // 中断前自动保存一个版本 + 继续完成后的正式版本
        JsonNode vs = c.ok(c.get("/api/apps/" + slow.path("id").asText() + "/versions"));
        assertThat(vs.size()).isEqualTo(2);
        assertThat(vs.findValuesAsText("source").stream().filter("interrupted"::equals).count()).isEqualTo(1);
    }
}
