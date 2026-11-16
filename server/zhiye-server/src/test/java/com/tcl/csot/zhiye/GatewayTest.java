package com.tcl.csot.zhiye;

import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.service.llm.TaskTokenService;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.FakeUpstreamController;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 模型网关：临时令牌鉴权、流式透传、用量统计、不设任务上限、重试、故障注入。 */
class GatewayTest extends IntegrationTest {
    @Autowired TaskTokenService tokens;
    @Autowired JdbcClient jdbc;

    String runningTask() {
        String id = Ids.newId("task");
        jdbc.sql("insert into task (id, workspace_id, app_id, type, status) values (?, 'ws_x', ?, 'generate', 'RUNNING')")
                .params(id, Ids.newId("app")).update();
        return id;
    }

    Client.Res chat(Client c, String token, boolean stream) {
        return c.send("POST", "/llm/v1/chat/completions", Map.of("model", "kimi-for-coding", "stream", stream,
                "messages", new Object[]{Map.of("role", "user", "content", "hi")}), Map.of("Authorization", "Bearer " + token));
    }

    long used(String task) {
        return jdbc.sql("select coalesce(sum(input_tokens + output_tokens),0) from model_usage where task_id = ?").param(task).query(Long.class).single();
    }

    @Test
    void unknownPathsAre404Not500() {
        Client c = client();
        assertThat(c.get("/api/no-such-endpoint").status()).isEqualTo(404);
    }

    @Test
    void authStreamUsageNoTaskLimit() {
        Client c = client();
        assertThat(chat(c, "nope", false).status()).isEqualTo(401);
        String task = runningTask();
        String token = tokens.issue(task, Duration.ofMinutes(5));

        var stream = chat(c, token, true);
        assertThat(stream.status()).isEqualTo(200);
        assertThat(stream.body()).contains("data: [DONE]").contains("\"content\":\"好\"");
        assertThat(used(task)).isEqualTo(15); // 只记最后一次 usage，不重复累计

        var plain = chat(c, token, false);
        assertThat(plain.status()).isEqualTo(200);
        assertThat(used(task)).isEqualTo(135);
        assertThat(jdbc.sql("select token_used from task where id = ?").param(task).query(Long.class).single()).isEqualTo(135);

        // 上游 503 一次 → 网关重试后成功
        FakeUpstreamController.failNext = 1;
        assertThat(chat(c, token, false).status()).isEqualTo(200);

        // 不设任务级 token 上限：用量再多也照常转发
        jdbc.sql("update task set token_used = 100000000 where id = ?").param(task).update();
        assertThat(chat(c, token, false).status()).isEqualTo(200);

        // 故障注入
        String t2 = runningTask();
        String tok2 = tokens.issue(t2, Duration.ofMinutes(5));
        faults.set("error", null);
        assertThat(chat(c, tok2, false).status()).isEqualTo(500);
        faults.set("none", null);
        assertThat(c.send("GET", "/llm/v1/models", null, Map.of("Authorization", "Bearer " + tok2)).body()).contains("kimi-for-coding");

        // 任务结束后令牌失效
        jdbc.sql("update task set status = 'SUCCEEDED' where id = ?").param(t2).update();
        assertThat(chat(c, tok2, false).status()).isEqualTo(401);
        tokens.revoke(task);
        assertThat(chat(c, token, false).status()).isEqualTo(401);
    }
}
