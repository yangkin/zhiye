package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.site.PageViewRecorder;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 系统全景统计看板接口测试：覆盖全平台管理视角（platform）与工作空间隔离视角（workspace）。 */
class DashboardTest extends IntegrationTest {
    @Autowired PageViewRecorder recorder;
    @Autowired JdbcClient jdbc;

    @Test
    void platformAndWorkspaceDashboardDimensions() {
        Client c = client();
        // 空工作空间在 workspace 视角下全部归零，同时 funnel=6 步、hourly=24 小时；全部时间（range=all）无数据时也不能抛 NPE
        for (String r : new String[]{"today", "7d", "30d", "90d", "all"}) {
            JsonNode emptyRange = c.ok(c.get("/api/dashboard?range=" + r + "&scope=workspace"));
            assertThat(emptyRange.path("meta").path("range").asText()).isEqualTo(r);
            assertThat(emptyRange.path("overview").path("appsTotal").asLong()).isZero();
        }
        JsonNode emptyWs = c.ok(c.get("/api/dashboard?range=7d&scope=workspace"));
        assertThat(emptyWs.path("meta").path("scope").asText()).isEqualTo("workspace");
        assertThat(emptyWs.path("meta").path("range").asText()).isEqualTo("7d");
        assertThat(emptyWs.path("trends").path("daily").size()).isEqualTo(7);
        assertThat(emptyWs.path("hourly").size()).isEqualTo(24);
        assertThat(emptyWs.path("funnel").size()).isEqualTo(6);
        assertThat(emptyWs.path("overview").path("appsTotal").asLong()).isZero();
        assertThat(emptyWs.path("overview").path("appsLimit").asLong()).isGreaterThan(0);

        // 非法参数返回 400
        assertThat(c.get("/api/dashboard?range=bad").status()).isEqualTo(400);
        assertThat(c.get("/api/dashboard?scope=bad").status()).isEqualTo(400);

        // 创建应用、生成、发布、记录 PV、创建会话与数据源
        JsonNode app = c.createApp("看板测试应用");
        String appId = app.path("id").asText();
        JsonNode gen = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of()));
        c.waitTask(gen.path("id").asText());
        JsonNode pub = c.ok(c.post("/api/apps/" + appId + "/publish", Map.of()));
        String host = pub.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);
        assertThat(c.getWithHost("/", host).status()).isEqualTo(200);
        recorder.flush();

        String taskId = gen.path("id").asText();
        jdbc.sql("insert into model_usage (task_id, provider, model, input_tokens, output_tokens, cost) values (?, 'kimi', 'kimi-test', 1200, 340, 0.015)")
                .param(taskId).update();
        jdbc.sql("insert into app_fn_log (app_id, name, ok, duration_ms) values (?, 'calcStats', true, 42), (?, 'calcStats', false, 88)")
                .params(appId, appId).update();
        JsonNode ds = c.ok(c.post("/api/sources", Map.of(
                "key", "dash_test_src",
                "name", "看板测试数据源",
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/api/meta",
                "authType", "none"
        )));
        jdbc.sql("insert into data_source_call (source_id, app_id, origin, status, ok, duration_ms) values (?, ?, 'preview', 200, true, 18)")
                .params(ds.path("id").asText(), appId).update();
        c.ok(c.post("/api/conversations", Map.of()));

        // 工作空间视角
        JsonNode wsFull = c.ok(c.get("/api/dashboard?range=30d&scope=workspace"));
        assertThat(wsFull.path("trends").path("daily").size()).isEqualTo(30);
        assertThat(wsFull.path("overview").path("appsTotal").asLong()).isEqualTo(1);
        assertThat(wsFull.path("overview").path("appsPublished").asLong()).isEqualTo(1);
        assertThat(wsFull.path("overview").path("pvToday").asLong()).isEqualTo(1);
        assertThat(wsFull.path("overview").path("tasksSucceeded").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(wsFull.path("overview").path("tokensInput").asLong()).isGreaterThanOrEqualTo(1200);
        assertThat(wsFull.path("overview").path("tokensOutput").asLong()).isGreaterThanOrEqualTo(340);
        assertThat(wsFull.path("overview").path("fnCallsTotal").asLong()).isEqualTo(2);
        assertThat(wsFull.path("overview").path("sourceCallsTotal").asLong()).isEqualTo(1);
        assertThat(wsFull.path("overview").path("conversationsTotal").asLong()).isEqualTo(1);
        assertThat(wsFull.path("apps").path("topApps").size()).isEqualTo(1);
        assertThat(wsFull.path("llm").path("byModel").toString()).contains("kimi-test");
        assertThat(wsFull.path("workspaces").size()).isEqualTo(1);

        // 历史 PV 与 range=all
        jdbc.sql("insert into app_pageview_daily (app_id, day, pv) values (?, ?, 7)")
                .params(appId, LocalDate.now(PageViewRecorder.ZONE).minusDays(4)).update();
        JsonNode all = c.ok(c.get("/api/dashboard?range=all&scope=workspace"));
        assertThat(all.path("overview").path("pvTotal").asLong()).isEqualTo(8);
        assertThat(all.path("trends").path("daily").size()).isGreaterThanOrEqualTo(5);

        // 另一工作空间：workspace 视角严格隔离，platform 默认视角可看到全系统聚合数据
        Client other = client();
        JsonNode otherWs = other.ok(other.get("/api/dashboard?range=today&scope=workspace"));
        assertThat(otherWs.path("trends").path("daily").size()).isEqualTo(1);
        assertThat(otherWs.path("overview").path("appsTotal").asLong()).isZero();
        assertThat(otherWs.path("overview").path("tokensTotal").asLong()).isZero();
        assertThat(otherWs.path("apps").path("topApps").size()).isZero();

        JsonNode platform = other.ok(other.get("/api/dashboard?range=30d"));
        assertThat(platform.path("meta").path("scope").asText()).isEqualTo("platform");
        assertThat(platform.path("overview").path("appsTotal").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(platform.path("overview").path("tokensTotal").asLong()).isGreaterThanOrEqualTo(1540);
        assertThat(platform.path("overview").has("taskP90DurationSec")).isTrue();
        assertThat(platform.path("agentTelemetry").has("tools")).isTrue();
        assertThat(platform.path("agentTelemetry").has("verifyChecks")).isTrue();
        assertThat(platform.path("agentTelemetry").has("eventBreakdown")).isTrue();
        assertThat(platform.path("workspaces").size()).isGreaterThanOrEqualTo(1);
        assertThat(platform.path("tasks").path("recentTasks").toString()).contains(taskId);
    }
}
