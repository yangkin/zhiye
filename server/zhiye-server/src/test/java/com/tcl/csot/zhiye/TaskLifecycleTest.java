package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 任务状态机：停止 → 继续、失败 → 重试、幂等、APP_BUSY、构建失败轮数。 */
class TaskLifecycleTest extends IntegrationTest {

    @Test
    void cancelThenResumeProducesExactlyOneVersion() {
        Client c = client();
        JsonNode app = c.createApp("做一个极简待办清单 [slow]");
        String id = app.path("id").asText();
        String key = UUID.randomUUID().toString();
        JsonNode task = c.ok(c.post("/api/apps/" + id + "/generate", Map.of(), key));
        JsonNode dup = c.ok(c.post("/api/apps/" + id + "/generate", Map.of(), key));
        assertThat(dup.path("id").asText()).isEqualTo(task.path("id").asText());

        c.waitUntil(task.path("id").asText(), t -> "RUNNING".equals(t.path("status").asText()));
        JsonNode cancel = c.ok(c.post("/api/tasks/" + task.path("id").asText() + "/cancel", Map.of()));
        assertThat(Set.of("CANCELLING", "CANCELLED")).contains(cancel.path("status").asText());
        JsonNode stopped = c.waitTask(task.path("id").asText());
        assertThat(stopped.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(stopped.path("resumable").asBoolean()).isTrue();
        // 停止时把半成品自动保存为「中断」版本，但不顶替当前版本
        JsonNode saved = c.ok(c.get("/api/apps/" + id + "/versions"));
        assertThat(saved.size()).isEqualTo(1);
        assertThat(saved.get(0).path("source").asText()).isEqualTo("interrupted");
        assertThat(saved.get(0).path("isCurrent").asBoolean()).isFalse();
        JsonNode mid = c.ok(c.get("/api/apps/" + id));
        assertThat(mid.path("phase").asText()).isNotEqualTo("ready");
        assertThat(mid.path("activeTask").isNull()).isTrue();
        assertThat(last(c.events(task.path("id").asText(), 0, Map.of())).path("payload").path("status").asText()).isEqualTo("CANCELLED");

        JsonNode resumed = c.ok(c.post("/api/tasks/" + task.path("id").asText() + "/resume", Map.of()));
        assertThat(resumed.path("id").asText()).isNotEqualTo(task.path("id").asText());
        assertThat(c.ok(c.get("/api/tasks/" + task.path("id").asText())).path("resumable").asBoolean()).isFalse();
        JsonNode done = c.waitTask(resumed.path("id").asText());
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        JsonNode all = c.ok(c.get("/api/apps/" + id + "/versions"));
        assertThat(all.size()).isEqualTo(2);
        assertThat(all.findValuesAsText("source").stream().filter("interrupted"::equals).count()).isEqualTo(1);
        // 已结束的任务不能再继续
        assertThat(c.post("/api/tasks/" + task.path("id").asText() + "/resume", Map.of()).status()).isEqualTo(409);
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.tcl.csot.zhiye.repository.TaskRepository taskRepo;

    @Test
    void stoppedModifyIsContinuedByNextMessage() {
        Client c = client();
        JsonNode app = c.createApp("做一个极简待办清单");
        String id = app.path("id").asText();
        assertThat(c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode posted = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "把按钮改成蓝色 [slow]")));
        String first = posted.path("task").path("id").asText();
        c.waitUntil(first, t -> "RUNNING".equals(t.path("status").asText()));
        c.ok(c.post("/api/tasks/" + first + "/cancel", Map.of()));
        JsonNode stopped = c.waitTask(first);
        assertThat(stopped.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(stopped.path("resultVersionId").isTextual()).as("中断前自动保存的版本记在任务上").isTrue();
        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        assertThat(msgs.findValuesAsText("content")).anyMatch(t -> t.startsWith("织页的回复被用户取消") && t.contains("自动保存"));

        // 下一句话沿用上一轮的工作区：先补完上一轮，上一轮不能再点「继续」
        JsonNode next = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "再把标题改成今日待办")));
        String second = next.path("task").path("id").asText();
        JsonNode input = taskRepo.findById(second).orElseThrow().input();
        assertThat(input.path("continueFromTask").asText()).isEqualTo(first);
        assertThat(input.path("continueContent").asText()).contains("按钮改成蓝色");
        assertThat(input.path("resume").path("workspaceDir").asText()).isNotBlank();
        assertThat(c.ok(c.get("/api/tasks/" + first)).path("resumable").asBoolean()).isFalse();
        JsonNode done = c.waitTask(second);
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        JsonNode cur = c.ok(c.get("/api/apps/" + id)).path("currentVersion");
        assertThat(cur.path("id").asText()).isEqualTo(done.path("resultVersionId").asText());
        assertThat(c.ok(c.get("/api/apps/" + id + "/messages")).findValuesAsText("content"))
                .as("接着做成功后，旧的「被用户取消」提示不再挂着").noneMatch(t -> t.startsWith("织页的回复被用户取消"));

        // 再停一次后先「回到」别的版本：版本变了，不再续做
        JsonNode third = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "加一个清空按钮 [slow]")));
        String tid = third.path("task").path("id").asText();
        c.waitUntil(tid, t -> "RUNNING".equals(t.path("status").asText()));
        c.ok(c.post("/api/tasks/" + tid + "/cancel", Map.of()));
        c.waitTask(tid);
        String v1 = c.ok(c.get("/api/apps/" + id + "/versions")).findValuesAsText("id").stream().reduce((a, b) -> b).orElseThrow();
        c.ok(c.post("/api/versions/" + v1 + "/restore", Map.of()));
        JsonNode fourth = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "标题加粗")));
        assertThat(taskRepo.findById(fourth.path("task").path("id").asText()).orElseThrow().input().has("continueFromTask")).isFalse();
        c.waitTask(fourth.path("task").path("id").asText());
    }

    @Test
    void cancelQueuedOrBeforeStart() {
        Client c = client();
        JsonNode app = c.createApp("做一个计数器 [slow]");
        JsonNode task = c.ok(c.post("/api/apps/" + app.path("id").asText() + "/generate", Map.of()));
        c.ok(c.post("/api/tasks/" + task.path("id").asText() + "/cancel", Map.of()));
        JsonNode stopped = c.waitTask(task.path("id").asText());
        assertThat(stopped.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(c.ok(c.get("/api/apps/" + app.path("id").asText() + "/versions")).size()).isEqualTo(0);
    }

    @Test
    void modelFailureThenRetryDoesNotDuplicate() {
        Client c = client();
        JsonNode app = c.createApp("做一个预约表单页面");
        String id = app.path("id").asText();
        faults.set("error", null);
        JsonNode task = c.ok(c.post("/api/apps/" + id + "/generate", Map.of()));
        JsonNode failed = c.waitTask(task.path("id").asText());
        faults.set("none", null);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("retryable").asBoolean()).isTrue();
        assertThat(failed.path("error").path("code").asText()).isEqualTo("MODEL_ERROR");
        assertThat(failed.path("error").path("message").asText()).isNotBlank();
        assertThat(c.ok(c.get("/api/apps/" + id + "/versions")).size()).isEqualTo(0);
        assertThat(c.ok(c.get("/api/apps/" + id)).path("phase").asText()).isEqualTo("failed");

        assertThat(c.ok(c.get("/api/apps/" + id + "/messages")).toString()).contains("生成失败：");
        String key = UUID.randomUUID().toString();
        JsonNode retry = c.ok(c.post("/api/tasks/" + task.path("id").asText() + "/retry", Map.of(), key));
        // 重试一开始就清掉上一轮的失败提示，不挂在进行中的卡片下面
        assertThat(c.ok(c.get("/api/apps/" + id + "/messages")).toString()).doesNotContain("生成失败：");
        assertThat(c.ok(c.post("/api/tasks/" + task.path("id").asText() + "/retry", Map.of(), key)).path("id").asText()).isEqualTo(retry.path("id").asText());
        JsonNode done = c.waitTask(retry.path("id").asText());
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        assertThat(c.ok(c.get("/api/apps/" + id + "/versions")).size()).isEqualTo(1);

        // 对话修改失败：保留用户消息（failed），不产生版本
        faults.set("error", null);
        JsonNode posted = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "把标题改成“今日清单”")));
        JsonNode mf = c.waitTask(posted.path("task").path("id").asText());
        faults.set("none", null);
        assertThat(mf.path("status").asText()).isEqualTo("FAILED");
        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        JsonNode mine = null;
        for (JsonNode m : msgs) if (m.path("id").asText().equals(posted.path("message").path("id").asText())) mine = m;
        assertThat(mine.path("content").asText()).isEqualTo("把标题改成“今日清单”");
        assertThat(mine.path("status").asText()).isEqualTo("failed");
        assertThat(msgs.toString()).contains("\"role\":\"system\"");
        assertThat(c.ok(c.get("/api/apps/" + id + "/versions")).size()).isEqualTo(1);

        // 构建失败：修复轮数用完后失败，有失败的 verify 事件，没有新版本
        faults.set(null, "error");
        JsonNode bt = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "把按钮改成圆角 16px")));
        JsonNode bf = c.waitTask(bt.path("task").path("id").asText());
        faults.set(null, "none");
        assertThat(bf.path("status").asText()).isEqualTo("FAILED");
        assertThat(bf.path("error").path("code").asText()).isEqualTo("VERIFY_FAILED");
        List<JsonNode> events = c.events(bt.path("task").path("id").asText(), 0, Map.of());
        long failedVerifies = events.stream().filter(e -> e.path("type").asText().equals("verify")
                && e.path("payload").path("checks").toString().contains("\"ok\":false")).count();
        assertThat(failedVerifies).isEqualTo(3); // 1 次校验 + 2 轮修复（测试配置 verify-fix-rounds=2）
        assertThat(c.ok(c.get("/api/apps/" + id + "/versions")).size()).isEqualTo(1);

        // 重试失败的修改：成功后用户消息转为 ok，版本 +1
        JsonNode r2 = c.ok(c.post("/api/tasks/" + bt.path("task").path("id").asText() + "/retry", Map.of()));
        assertThat(c.waitTask(r2.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(c.ok(c.get("/api/apps/" + id + "/versions")).size()).isEqualTo(2);
    }

    @Test
    void generateRequiresPlanPhaseAndTaskListing() {
        Client c = client();
        JsonNode app = c.createApp("做一个计数器");
        String id = app.path("id").asText();
        JsonNode t = c.ok(c.post("/api/apps/" + id + "/generate", Map.of()));
        assertThat(c.ok(c.get("/api/apps/" + id + "/tasks?active=true")).toString()).contains(t.path("id").asText());
        c.waitTask(t.path("id").asText());
        assertThat(c.ok(c.get("/api/apps/" + id + "/tasks?active=true")).size()).isEqualTo(0);
        assertThat(c.post("/api/apps/" + id + "/generate", Map.of()).status()).isEqualTo(409);
        // 同一个幂等键创建应用只创建一次
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("type", "日常开发", "prompt", "做一个记账本", "mode", "适中", "model", "auto", "assetIds", List.of());
        JsonNode a1 = c.ok(c.post("/api/apps", body, key));
        JsonNode a2 = c.ok(c.post("/api/apps", body, key));
        assertThat(a2.path("id").asText()).isEqualTo(a1.path("id").asText());
    }

    private static <T> T last(java.util.List<T> list) {
        return list.get(list.size() - 1);
    }
}
