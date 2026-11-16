package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不限制执行步数：每做一段（runner 报 TURN_LIMIT）暂停并发「要继续吗？」确认卡，
 * 点「继续」或在输入框里说话就在同一会话里接着做；「先到这里」暂停为 PAUSED，重试接着做。
 */
class ContinueCheckpointTest extends IntegrationTest {
    @Autowired JdbcClient jdbc;

    List<JsonNode> confirms(String taskId) {
        return jdbc.sql("select payload from task_event where task_id = ? and type = 'confirm' order by seq").param(taskId)
                .query((rs, i) -> Json.read(rs.getString(1))).list();
    }

    JsonNode waitPending(String taskId) {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            for (JsonNode e : confirms(taskId)) if ("pending".equals(e.path("state").asText())) return e;
            try { Thread.sleep(100); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
        throw new AssertionError("没有等到「要继续吗？」");
    }

    String startBig(Client c) {
        String id = c.createApp("做一个巡检记录 [turnlimit]").path("id").asText();
        return c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText();
    }

    @Test
    void clickContinueKeepsGoingInSameTask() {
        Client c = client();
        String tid = startBig(c);
        JsonNode card = waitPending(tid);
        assertThat(card.path("kind").asText()).isEqualTo("continue");
        assertThat(card.path("title").asText()).contains("步").contains("要继续吗");
        assertThat(card.path("acceptLabel").asText()).isEqualTo("继续");
        assertThat(card.path("declineLabel").asText()).isEqualTo("先到这里");
        // 等待时任务仍在进行，不算失败
        assertThat(c.ok(c.get("/api/tasks/" + tid)).path("status").asText()).isEqualTo("RUNNING");
        // 别人的工作空间不能替他点
        assertThat(client().post("/api/tasks/" + tid + "/confirm", Map.of("confirmId", card.path("id").asText(), "accept", true)).status()).isEqualTo(404);

        c.ok(c.post("/api/tasks/" + tid + "/confirm", Map.of("confirmId", card.path("id").asText(), "accept", true)));
        JsonNode done = c.waitTask(tid);
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        assertThat(confirms(tid)).extracting(e -> e.path("state").asText()).containsExactly("pending", "accepted");
    }

    @Test
    void typingInComposerCountsAsContinueWithExtraRequest() {
        Client c = client();
        String tid = startBig(c);
        JsonNode card = waitPending(tid);
        c.ok(c.post("/api/tasks/" + tid + "/confirm", Map.of("confirmId", card.path("id").asText(), "accept", true, "text", "继续，顺便把标题改成蓝色")));
        assertThat(c.waitTask(tid).path("status").asText()).isEqualTo("SUCCEEDED");
        String appId = jdbc.sql("select app_id from task where id = ?").param(tid).query(String.class).single();
        assertThat(c.ok(c.get("/api/apps/" + appId + "/messages")).findValuesAsText("content")).contains("继续，顺便把标题改成蓝色");
    }

    @Test
    void stopHerePausesAndRetryContinues() {
        Client c = client();
        String tid = startBig(c);
        JsonNode card = waitPending(tid);
        c.ok(c.post("/api/tasks/" + tid + "/confirm", Map.of("confirmId", card.path("id").asText(), "accept", false)));
        JsonNode paused = c.waitTask(tid);
        assertThat(paused.path("status").asText()).isEqualTo("FAILED");
        assertThat(paused.path("error").path("code").asText()).isEqualTo("PAUSED");
        assertThat(paused.path("error").path("message").asText()).contains("已做的部分都保留着").contains("重试");
        assertThat(paused.path("retryable").asBoolean()).isTrue();

        JsonNode retry = c.ok(c.post("/api/tasks/" + tid + "/retry", Map.of()));
        assertThat(c.waitTask(retry.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void cancelWhileWaitingStopsTheTask() {
        Client c = client();
        String tid = startBig(c);
        waitPending(tid);
        c.ok(c.post("/api/tasks/" + tid + "/cancel", Map.of()));
        assertThat(c.waitTask(tid).path("status").asText()).isEqualTo("CANCELLED");
    }
}
