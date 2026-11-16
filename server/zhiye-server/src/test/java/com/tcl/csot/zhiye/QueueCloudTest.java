package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 任务进行中点「共享云服务」：启用跟着排队消息走，轮到它才启用；撤回就不启用。 */
class QueueCloudTest extends IntegrationTest {

    private static boolean cloudOn(Client c, String appId) {
        return c.ok(c.get("/api/apps/" + appId + "/cloud")).path("enabled").asBoolean();
    }

    @Test
    void cloudEnablesOnlyWhenQueuedMessageRuns() {
        Client c = client();
        String appId = c.createApp("做一个点击计数器").path("id").asText();
        c.waitTask(c.ok(c.post("/api/apps/" + appId + "/generate", Map.of())).path("id").asText());

        String running = c.ok(c.post("/api/apps/" + appId + "/messages", Map.of("content", "加一个说明文字 [slow]")))
                .path("task").path("id").asText();

        // 撤回：不启用
        JsonNode first = c.ok(c.post("/api/tasks/" + running + "/queued", Map.of("content", "设计表结构", "enableCloud", true)));
        assertThat(first.path("enableCloud").asBoolean()).isTrue();
        assertThat(cloudOn(c, appId)).isFalse();
        assertThat(c.send("DELETE", "/api/queued/" + first.path("id").asText(), null, Map.of()).status()).isEqualTo(204);
        assertThat(cloudOn(c, appId)).isFalse();

        // 不撤回：排队期间仍未启用，轮到它时才启用
        c.ok(c.post("/api/tasks/" + running + "/queued", Map.of("content", "设计表结构", "enableCloud", true)));
        assertThat(cloudOn(c, appId)).isFalse();
        c.ok(c.post("/api/tasks/" + running + "/cancel", Map.of()));
        c.waitTask(running);
        JsonNode next = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            next = c.ok(c.get("/api/apps/" + appId)).path("activeTask");
            if (next.hasNonNull("id") && !next.path("id").asText().equals(running)) break;
            try { Thread.sleep(200); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
        assertThat(next).isNotNull();
        assertThat(cloudOn(c, appId)).isTrue();
        c.waitTask(next.path("id").asText(), Duration.ofSeconds(60));
    }
}
