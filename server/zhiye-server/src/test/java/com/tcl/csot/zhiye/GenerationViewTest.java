package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 生成视图：todo 事件与 Task.todos、工具详情（截断 / 补发省略 / 按需加载）、中途补充（steer）、实时工作区文件。 */
class GenerationViewTest extends IntegrationTest {

    @Test
    void todosSteerAndLiveFiles() {
        Client c = client();
        JsonNode app = c.createApp("做一个计数器 [slow]");
        String id = app.path("id").asText();
        JsonNode t = c.ok(c.post("/api/apps/" + id + "/generate", Map.of()));
        String tid = t.path("id").asText();
        // 等到 agent 写出待办清单（此时在 prompt 中）
        JsonNode running = c.waitUntil(tid, x -> x.path("todos").size() > 0);
        assertThat(running.path("todos").get(0).path("content").asText()).isEqualTo("编写页面");
        assertThat(c.ok(c.get("/api/apps/" + id)).path("activeTask").path("todos").get(0).path("status").asText()).isEqualTo("in_progress");

        // 实时文件
        JsonNode files = c.ok(c.get("/api/tasks/" + tid + "/files"));
        assertThat(files.toString()).contains("\"src/App.tsx\"").contains("package.json").doesNotContain("node_modules").doesNotContain(".git/");
        assertThat(c.get("/api/tasks/" + tid + "/file?path=package.json").body()).contains("\"name\"");
        assertThat(c.get("/api/tasks/" + tid + "/file?path=../etc/passwd").status()).isEqualTo(400);
        assertThat(c.get("/api/tasks/" + tid + "/file?path=.git/config").status()).isEqualTo(400);
        assertThat(c.get("/api/tasks/" + tid + "/file?path=nope.txt").status()).isEqualTo(404);
        assertThat(client().get("/api/tasks/" + tid + "/files").status()).isEqualTo(404);

        // 中途补充
        assertThat(c.post("/api/tasks/" + tid + "/steer", Map.of("text", "")).status()).isEqualTo(400);
        assertThat(c.post("/api/tasks/" + tid + "/steer", Map.of("text", "长".repeat(2001))).status()).isEqualTo(400);
        JsonNode s = c.ok(c.post("/api/tasks/" + tid + "/steer", Map.of("text", "按钮再大一点")));
        assertThat(s.path("message").path("steer").asBoolean()).isTrue();
        JsonNode done = c.waitTask(tid);
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.path("todos").get(1).path("status").asText()).isEqualTo("completed");

        List<JsonNode> events = c.events(tid, 0, Map.of());
        assertThat(events).anyMatch(e -> e.path("type").asText().equals("steer") && e.path("payload").path("text").asText().equals("按钮再大一点"));
        assertThat(events).anyMatch(e -> e.path("type").asText().equals("todo"));
        String text = events.stream().filter(e -> e.path("type").asText().equals("agent_text"))
                .map(e -> e.path("payload").path("delta").asText()).collect(Collectors.joining());
        assertThat(text).contains("收到补充：按钮再大一点");
        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        assertThat(msgs.toString()).contains("按钮再大一点");

        // 结束后：实时文件 409，steer 409
        assertThat(c.get("/api/tasks/" + tid + "/files").status()).isEqualTo(409);
        assertThat(c.get("/api/tasks/" + tid + "/file?path=package.json").status()).isEqualTo(409);
        assertThat(c.post("/api/tasks/" + tid + "/steer", Map.of("text", "再改")).code()).isEqualTo("NOT_STEERABLE");

        // 对话修改仍然只把第一条（非补充）消息当作用户输入
        JsonNode m = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "把标题改成红色")));
        assertThat(c.waitTask(m.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void toolDetailCappedOmittedAndLazyLoaded() {
        Client c = client();
        JsonNode app = c.createApp("做一个计数器 [bigdetail] [many]");
        String id = app.path("id").asText();
        JsonNode t = c.ok(c.post("/api/apps/" + id + "/generate", Map.of()));
        String tid = t.path("id").asText();
        assertThat(c.waitTask(tid).path("status").asText()).isEqualTo("SUCCEEDED");
        List<JsonNode> events = c.events(tid, 0, Map.of());
        assertThat(events.size()).isGreaterThan(200);
        JsonNode tool = events.stream().filter(e -> e.path("type").asText().equals("tool") && e.path("payload").path("state").asText().equals("end"))
                .findFirst().orElseThrow();
        assertThat(tool.path("payload").has("detail")).isFalse();
        assertThat(tool.path("payload").path("detailOmitted").asBoolean()).isTrue();
        JsonNode d = c.ok(c.get("/api/tasks/" + tid + "/events/" + tool.path("seq").asInt() + "/detail"));
        assertThat(d.path("detailTruncated").asBoolean()).isTrue();
        assertThat(d.path("detail").path("path").asText()).isEqualTo("src/App.tsx");
        assertThat(d.path("detail").toString().length()).isLessThanOrEqualTo(16 * 1024 + 100);
        assertThat(d.path("detail").path("content").asText()).startsWith("xxxx");
        assertThat(c.get("/api/tasks/" + tid + "/events/1/detail").status()).isEqualTo(404);
        assertThat(client().get("/api/tasks/" + tid + "/events/" + tool.path("seq").asInt() + "/detail").status()).isEqualTo(404);

        // 事件不多时：补发包含 detail
        JsonNode small = c.createApp("做一个待办");
        JsonNode t2 = c.ok(c.post("/api/apps/" + small.path("id").asText() + "/generate", Map.of()));
        c.waitTask(t2.path("id").asText());
        JsonNode tool2 = c.events(t2.path("id").asText(), 0, Map.of()).stream().filter(e -> e.path("type").asText().equals("tool")
                && e.path("payload").path("state").asText().equals("end")).findFirst().orElseThrow();
        assertThat(tool2.path("payload").path("detail").path("content").asText()).isEqualTo("export default function App() {}");
        assertThat(tool2.path("payload").has("detailTruncated")).isFalse();
    }
}
