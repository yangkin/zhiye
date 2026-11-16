package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 确认卡：agent 调 request_confirm → 卡片等用户选 → 确认就启用云服务并把结果交回 agent；拒绝则不启用；已启用直接放行。 */
class ConfirmCardTest extends IntegrationTest {
    @Autowired JdbcClient jdbc;

    String webApp(Client c) {
        String id = c.createApp("做一个设备报修工单").path("id").asText();
        c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText());
        return id;
    }

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

    String send(Client c, String appId, String text) {
        return c.ok(c.post("/api/apps/" + appId + "/messages", Map.of("content", text))).path("task").path("id").asText();
    }

    @Test
    void acceptEnablesCloudAndContinues() {
        Client c = client();
        String id = webApp(c);
        String tid = send(c, id, "[confirm] 请帮我开启云服务并设计对应的表结构");
        JsonNode card = waitPending(tid);
        assertThat(card.path("kind").asText()).isEqualTo("cloud");
        assertThat(card.path("title").asText()).isEqualTo("是否要启用织页云服务？");
        assertThat(card.path("items").size()).isEqualTo(3);
        assertThat(card.path("reason").asText()).isEqualTo("工单数据要多人共用");
        assertThat(c.ok(c.get("/api/apps/" + id + "/cloud")).path("enabled").asBoolean()).isFalse();

        assertThat(c.post("/api/tasks/" + tid + "/confirm", Map.of("confirmId", "cf_nope", "accept", true)).code()).isEqualTo("CONFIRM_EXPIRED");
        c.ok(c.post("/api/tasks/" + tid + "/confirm", Map.of("confirmId", card.path("id").asText(), "accept", true)));
        assertThat(c.post("/api/tasks/" + tid + "/confirm", Map.of("confirmId", card.path("id").asText(), "accept", true)).code()).isEqualTo("CONFIRM_EXPIRED");

        assertThat(c.waitTask(tid).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(c.ok(c.get("/api/apps/" + id + "/cloud")).path("enabled").asBoolean()).isTrue();
        assertThat(confirms(tid)).extracting(e -> e.path("state").asText()).containsExactly("pending", "accepted");
        String text = c.events(tid, 0, Map.of()).stream().filter(e -> e.path("type").asText().equals("agent_text"))
                .map(e -> e.path("payload").path("delta").asText()).reduce("", String::concat);
        assertThat(text).contains("云服务已启用（当前已有的表：notes。");

        // 已启用的应用：agent 再请求时直接放行，不弹卡
        String t2 = send(c, id, "[confirm] 再加一个附件上传");
        assertThat(c.waitTask(t2).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(confirms(t2)).extracting(e -> e.path("state").asText()).containsExactly("accepted");
    }

    @Test
    void declineKeepsCloudOffAndOtherWorkspaceCannotAnswer() {
        Client c = client();
        String id = webApp(c);
        String tid = send(c, id, "[confirm] 数据要存下来");
        JsonNode card = waitPending(tid);
        assertThat(client().post("/api/tasks/" + tid + "/confirm", Map.of("confirmId", card.path("id").asText(), "accept", true)).status()).isEqualTo(404);
        c.ok(c.post("/api/tasks/" + tid + "/confirm", Map.of("confirmId", card.path("id").asText(), "accept", false)));
        assertThat(c.waitTask(tid).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(c.ok(c.get("/api/apps/" + id + "/cloud")).path("enabled").asBoolean()).isFalse();
        assertThat(confirms(tid)).extracting(e -> e.path("state").asText()).containsExactly("pending", "declined");
    }

    @Test
    void cancelWhileWaitingStopsTheTask() {
        Client c = client();
        String id = webApp(c);
        String tid = send(c, id, "[confirm] 数据要存下来");
        JsonNode card = waitPending(tid);
        c.ok(c.post("/api/tasks/" + tid + "/cancel", Map.of()));
        assertThat(c.waitTask(tid).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(c.post("/api/tasks/" + tid + "/confirm", Map.of("confirmId", card.path("id").asText(), "accept", true)).code()).isEqualTo("CONFIRM_EXPIRED");
        assertThat(c.ok(c.get("/api/apps/" + id + "/cloud")).path("enabled").asBoolean()).isFalse();
    }
}
