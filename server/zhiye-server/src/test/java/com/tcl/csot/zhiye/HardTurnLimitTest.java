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
 * 方案 B 默认行为：取消中途「要继续吗？」确认弹窗，连续执行直至完成或触及单次步数硬上限（默认 200 步 / 45 分钟）；
 * 若触及硬上限则不弹确认卡，直接保留进度暂停为可重试 PAUSED，点「重试」可继续完成。
 */
class HardTurnLimitTest extends IntegrationTest {
    @Autowired JdbcClient jdbc;

    @Test
    void defaultModeDoesNotEmitContinueConfirmAndPausesCleanlyOnTurnLimit() {
        Client c = client();
        String appId = c.createApp("做一个巡检记录 [turnlimit]").path("id").asText();
        String tid = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of())).path("id").asText();

        JsonNode paused = c.waitTask(tid);
        assertThat(paused.path("status").asText()).isEqualTo("FAILED");
        assertThat(paused.path("error").path("code").asText()).isEqualTo("PAUSED");
        assertThat(paused.path("error").path("message").asText()).contains("单次步数上限").contains("重试");
        assertThat(paused.path("retryable").asBoolean()).isTrue();

        // 绝不发出「要继续吗？」确认卡事件
        List<JsonNode> confirms = jdbc.sql("select payload from task_event where task_id = ? and type = 'confirm'")
                .param(tid).query((rs, i) -> Json.read(rs.getString(1))).list();
        assertThat(confirms).noneMatch(e -> "continue".equals(e.path("kind").asText()));

        // 点「重试」保留已有进度并完成
        JsonNode retry = c.ok(c.post("/api/tasks/" + tid + "/retry", Map.of()));
        assertThat(c.waitTask(retry.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
    }
}
