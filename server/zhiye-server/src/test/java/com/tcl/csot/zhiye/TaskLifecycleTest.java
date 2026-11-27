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
    void stoppedModifyStartsCleanOnNewMessageAndSupportsExplicitResumeOrDiscard() {
        Client c = client();
        JsonNode app = c.createApp("做一个极简待办清单");
        String id = app.path("id").asText();
        assertThat(c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        // 1. 中途停止修改任务：保留中断前快照与可继续检查点
        JsonNode posted = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "把按钮改成蓝色 [slow]")));
        String first = posted.path("task").path("id").asText();
        c.waitUntil(first, t -> "RUNNING".equals(t.path("status").asText()));
        c.ok(c.post("/api/tasks/" + first + "/cancel", Map.of()));
        JsonNode stopped = c.waitTask(first);
        assertThat(stopped.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(stopped.path("resumable").asBoolean()).isTrue();
        assertThat(stopped.path("discarded").asBoolean()).isFalse();
        assertThat(stopped.path("resultVersionId").isTextual()).as("中断前自动保存的版本记在任务上").isTrue();
        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        assertThat(msgs.findValuesAsText("content")).anyMatch(t -> t.startsWith("织页的回复被用户取消") && t.contains("自动保存"));

        // 2. 用户直接发一条全新修改需求：不自动复活已停止任务，而是从当前稳定版本干净开始并清理旧检查点
        JsonNode next = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "再把标题改成今日待办")));
        String second = next.path("task").path("id").asText();
        JsonNode input = taskRepo.findById(second).orElseThrow().input();
        assertThat(input.has("continueFromTask")).as("新修改需求绝不静默复活已停止任务").isFalse();
        assertThat(input.has("resume")).isFalse();
        JsonNode firstAfter = c.ok(c.get("/api/tasks/" + first));
        assertThat(firstAfter.path("resumable").asBoolean()).isFalse();
        assertThat(firstAfter.path("discarded").asBoolean()).isTrue();
        JsonNode done = c.waitTask(second);
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        JsonNode cur = c.ok(c.get("/api/apps/" + id)).path("currentVersion");
        assertThat(cur.path("id").asText()).isEqualTo(done.path("resultVersionId").asText());
        assertThat(c.ok(c.get("/api/apps/" + id + "/messages")).findValuesAsText("content"))
                .as("新任务成功后，旧的「被用户取消」提示不再挂着").noneMatch(t -> t.startsWith("织页的回复被用户取消"));

        // 3. 再次中途停止后主动点「放弃」（POST /discard）：清除检查点、标记 discarded，并收口系统提示
        JsonNode third = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "加一个清空按钮 [slow]")));
        String tid = third.path("task").path("id").asText();
        c.waitUntil(tid, t -> "RUNNING".equals(t.path("status").asText()));
        c.ok(c.post("/api/tasks/" + tid + "/cancel", Map.of()));
        c.waitTask(tid);
        JsonNode discarded = c.ok(c.post("/api/tasks/" + tid + "/discard", Map.of()));
        assertThat(discarded.path("discarded").asBoolean()).isTrue();
        assertThat(discarded.path("resumable").asBoolean()).isFalse();
        assertThat(c.post("/api/tasks/" + tid + "/resume", Map.of()).status()).isEqualTo(409);
        assertThat(c.ok(c.get("/api/apps/" + id + "/messages")).findValuesAsText("content"))
                .anyMatch(t -> t.contains("已放弃未完成进度"));

        // 4. 再次中途停止后显式点「继续」（带补充说明）：沿用检查点并记录 resumeNote
        JsonNode fourth = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "标题加粗 [slow]")));
        String fid = fourth.path("task").path("id").asText();
        c.waitUntil(fid, t -> "RUNNING".equals(t.path("status").asText()));
        c.ok(c.post("/api/tasks/" + fid + "/cancel", Map.of()));
        c.waitTask(fid);
        JsonNode resumed = c.ok(c.post("/api/tasks/" + fid + "/resume", Map.of("content", "顺便把字号调大一点")));
        assertThat(taskRepo.findById(resumed.path("id").asText()).orElseThrow().input().path("resumeNote").asText())
                .isEqualTo("顺便把字号调大一点");
        assertThat(c.waitTask(resumed.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void discardStoppedGenerateReturnsToPlanPhase() {
        Client c = client();
        JsonNode app = c.createApp("做一个项目进度看板 [slow]");
        String id = app.path("id").asText();
        JsonNode task = c.ok(c.post("/api/apps/" + id + "/generate", Map.of()));
        String tid = task.path("id").asText();
        c.waitUntil(tid, t -> "RUNNING".equals(t.path("status").asText()));
        c.ok(c.post("/api/tasks/" + tid + "/cancel", Map.of()));
        JsonNode stopped = c.waitTask(tid);
        assertThat(stopped.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(c.ok(c.get("/api/apps/" + id)).path("phase").asText()).isEqualTo("stopped");

        // 放弃未完成的首次生成 → 回到 plan 阶段可继续调整计划
        JsonNode discarded = c.ok(c.post("/api/tasks/" + tid + "/discard", Map.of()));
        assertThat(discarded.path("discarded").asBoolean()).isTrue();
        assertThat(discarded.path("resumable").asBoolean()).isFalse();
        assertThat(c.ok(c.get("/api/apps/" + id)).path("phase").asText()).isEqualTo("plan");
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
    void generateFromFallbackPlanClearsOldFailure() {
        Client c = client();
        faults.set("error", null);
        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "做一个项目看板",
                "mode", "快速", "model", "auto", "assetIds", List.of())));
        String id = created.path("id").asText();
        JsonNode failed = c.waitTask(created.path("activeTask").path("id").asText());
        faults.set("none", null);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(c.ok(c.get("/api/apps/" + id + "/messages")).toString()).contains("创造计划生成失败：");

        JsonNode generate = c.ok(c.post("/api/apps/" + id + "/generate", Map.of()));
        assertThat(c.ok(c.get("/api/apps/" + id + "/messages")).toString()).doesNotContain("创造计划生成失败：");
        assertThat(c.waitTask(generate.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
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
