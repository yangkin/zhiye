package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 个人配额（使用真实默认值：同时 1 个任务、最多 10 个应用）。 */
class QuotaTest extends IntegrationTest {
    @Autowired JdbcClient jdbc;

    static final String HTML = "<!doctype html><html><body><h1>你好</h1></body></html>";

    Client.Res newApp(Client c, String prompt) {
        return c.post("/api/apps", Map.of("type", "开发网站", "prompt", prompt, "mode", "快速", "model", "auto", "assetIds", List.of()));
    }

    @Test
    void oneTaskAtATimePerPerson() {
        Client c = client();
        JsonNode a = c.createApp("做一个计数器 [slow]");
        JsonNode b = c.createApp("做一个待办");
        JsonNode t = c.ok(c.post("/api/apps/" + a.path("id").asText() + "/generate", Map.of()));
        // 同一应用 → APP_BUSY；其他应用 / 新建应用 / 导入 → TASK_LIMIT
        assertThat(c.post("/api/apps/" + a.path("id").asText() + "/messages", Map.of("content", "x")).code()).isEqualTo("APP_BUSY");
        var other = c.post("/api/apps/" + b.path("id").asText() + "/generate", Map.of());
        assertThat(other.status()).isEqualTo(429);
        assertThat(other.code()).isEqualTo("TASK_LIMIT");
        assertThat(other.json().path("message").asText()).startsWith("每人同时只能运行 1 个任务");
        assertThat(newApp(c, "再做一个").code()).isEqualTo("TASK_LIMIT");
        JsonNode m = c.ok(c.upload("/api/imports/upload", "a.html", "text/html", HTML.getBytes(StandardCharsets.UTF_8), Map.of()));
        assertThat(c.post("/api/imports", Map.of("uploadId", m.path("uploadId").asText(), "name", "导入")).code()).isEqualTo("TASK_LIMIT");
        // 别人的工作空间不受影响
        assertThat(newApp(client(), "别人的应用").status()).isEqualTo(200);

        JsonNode summary = c.ok(c.get("/api/apps/summary"));
        assertThat(summary.path("taskLimit").asInt()).isEqualTo(1);

        // 第一个任务结束后可以继续
        c.ok(c.post("/api/tasks/" + t.path("id").asText() + "/cancel", Map.of()));
        assertThat(c.waitTask(t.path("id").asText()).path("status").asText()).isEqualTo("CANCELLED");
        JsonNode g = c.ok(c.post("/api/apps/" + b.path("id").asText() + "/generate", Map.of()));
        assertThat(c.waitTask(g.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void tenAppsPerPerson() {
        Client c = client();
        String first = null;
        for (int i = 1; i <= 10; i++) {
            JsonNode a = c.createApp("应用 " + i);
            if (first == null) first = a.path("id").asText();
        }
        JsonNode sum = c.ok(c.get("/api/apps/summary"));
        assertThat(sum.path("appLimit").asInt()).isEqualTo(10);
        assertThat(sum.path("appsUsed").asInt()).isEqualTo(10);
        var over = newApp(c, "第 11 个");
        assertThat(over.status()).isEqualTo(409);
        assertThat(over.code()).isEqualTo("APP_LIMIT");
        assertThat(over.json().path("message").asText()).startsWith("每人最多 10 个应用（已删除的不算）");
        JsonNode m = c.ok(c.upload("/api/imports/upload", "a.html", "text/html", HTML.getBytes(StandardCharsets.UTF_8), Map.of()));
        assertThat(c.post("/api/imports", Map.of("uploadId", m.path("uploadId").asText(), "name", "导入")).code()).isEqualTo("APP_LIMIT");

        // 删除一个 → 剩 9 个
        assertThat(c.delete("/api/apps/" + first).status()).isEqualTo(204);
        // 失败的导入不计入
        faults.set(null, "error");
        JsonNode r = c.ok(c.post("/api/imports", Map.of("uploadId", m.path("uploadId").asText(), "name", "坏导入")));
        assertThat(c.waitTask(r.path("task").path("id").asText()).path("status").asText()).isEqualTo("FAILED");
        faults.set(null, "none");
        // 取消的导入不计入（排队中或执行中取消都会删除隐藏应用）
        JsonNode r2 = c.ok(c.post("/api/imports", Map.of("uploadId", m.path("uploadId").asText(), "name", "取消的导入")));
        c.ok(c.post("/api/tasks/" + r2.path("task").path("id").asText() + "/cancel", Map.of()));
        String st = c.waitTask(r2.path("task").path("id").asText()).path("status").asText();
        if ("SUCCEEDED".equals(st)) c.delete("/api/apps/" + r2.path("app").path("id").asText()); // 太快时已完成，删掉保持 9 个
        assertThat(c.ok(c.get("/api/apps/summary")).path("appsUsed").asInt()).isEqualTo(9);
        // 第 10 个可以创建
        assertThat(newApp(c, "新的第 10 个").status()).isEqualTo(200);
        assertThat(newApp(c, "第 11 个").code()).isIn("APP_LIMIT", "TASK_LIMIT");
    }
}
