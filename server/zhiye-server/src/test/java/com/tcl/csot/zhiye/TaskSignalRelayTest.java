package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.repository.TaskRepository;
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
 * 多实例部署：确认卡的选择落到没在跑这个任务的实例上时，经 task_signal 表转给执行它的实例。
 * 这里直接按「另一个实例」的做法写信号（和 TaskController 的远端分支一致），看任务能不能收到。
 */
class TaskSignalRelayTest extends IntegrationTest {
    @Autowired JdbcClient jdbc;
    @Autowired TaskRepository taskRepo;

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
        throw new AssertionError("没有等到确认卡");
    }

    boolean relay(String taskId, String confirmId, boolean accept, String text) {
        return taskRepo.confirmPending(taskId, confirmId) && taskRepo.postSignal(taskId, "confirm", "confirm:" + confirmId,
                Json.obj().put("confirmId", confirmId).put("accept", accept).put("text", text));
    }

    @Test
    void cloudConfirmFromAnotherInstanceReachesTheTask() {
        Client c = client();
        String id = c.createApp("做一个设备报修工单").path("id").asText();
        c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText());
        String tid = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "[confirm] 数据要存下来"))).path("task").path("id").asText();
        String cid = waitPending(tid).path("id").asText();

        assertThat(taskRepo.confirmPending(tid, "cf_nope")).isFalse();
        assertThat(relay(tid, cid, false, null)).isTrue();
        assertThat(relay(tid, cid, true, null)).as("同一张卡只能答一次").isFalse();

        assertThat(c.waitTask(tid).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(c.ok(c.get("/api/apps/" + id + "/cloud")).path("enabled").asBoolean()).isFalse();
        assertThat(confirms(tid)).extracting(e -> e.path("state").asText()).containsExactly("pending", "declined");
        assertThat(taskRepo.confirmPending(tid, cid)).isFalse();
    }

    @Test
    void continueFromAnotherInstanceKeepsGoing() {
        Client c = client();
        String id = c.createApp("做一个巡检记录 [turnlimit]").path("id").asText();
        String tid = c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText();
        JsonNode card = waitPending(tid);
        assertThat(card.path("kind").asText()).isEqualTo("continue");

        assertThat(relay(tid, card.path("id").asText(), true, "继续，顺便把标题改成巡检台账")).isTrue();
        JsonNode done = c.waitTask(tid);
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        assertThat(confirms(tid)).extracting(e -> e.path("state").asText()).containsExactly("pending", "accepted");
    }
}
