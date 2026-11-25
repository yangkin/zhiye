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

        // 非法参数返回 400（普通用户 scope=workspace 与管理员 scope=platform 均校参数）
        assertThat(c.get("/api/dashboard?range=bad&scope=workspace").status()).isEqualTo(400);
        assertThat(c.get("/api/dashboard?scope=bad").status()).isEqualTo(400);
        assertThat(c.get("/api/dashboard?startDate=2026-11-10&endDate=2026-11-01&scope=workspace").status()).isEqualTo(400);
        assertThat(c.get("/api/dashboard?startDate=bad-date&scope=workspace").status()).isEqualTo(400);
        assertThat(c.asAdmin().get("/api/dashboard?range=bad").status()).isEqualTo(400);

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
        JsonNode ds = registerSource(c, Map.of(
                "key", "dash_test_src",
                "name", "看板测试数据源",
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/api/meta",
                "authType", "none"
        ));
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

        // 另一普通工作空间（无管理员权限）：
        // 1) scope=workspace 严格隔离自身数据，不泄漏全局 actors 与 platformPulse；
        // 2) scope=platform、跨空间 workspaceId、他人 userId 与跨空间运维日志一律返回 403；
        // 3) 具备管理员身份（X-Zhiye-Admin-Token）时允许看全平台与跨空间下钻。
        String wsId = fullWsId(c);
        Client other = client();
        String otherWsId = fullWsId(other);

        assertThat(other.ok(other.get("/api/meta")).at("/auth/admin").asBoolean()).isFalse();
        JsonNode otherWs = other.ok(other.get("/api/dashboard?range=today&scope=workspace"));
        assertThat(otherWs.path("trends").path("daily").size()).isEqualTo(1);
        assertThat(otherWs.path("overview").path("appsTotal").asLong()).isZero();
        assertThat(otherWs.path("overview").path("tokensTotal").asLong()).isZero();
        assertThat(otherWs.path("apps").path("topApps").size()).isZero();
        assertThat(otherWs.path("actors").size()).isZero();
        assertThat(otherWs.path("system").path("platformPulse").path("totalApps").asLong()).isZero();

        // 传入本人自己的 workspaceId + scope=workspace 允许访问
        assertThat(other.get("/api/dashboard?range=today&scope=workspace&workspaceId=" + otherWsId).status()).isEqualTo(200);

        // 普通用户越权访问全平台/他人空间/他人人员/运维日志均返回 403
        assertThat(other.get("/api/dashboard?range=30d").status()).isEqualTo(403);
        assertThat(other.get("/api/dashboard?range=30d&scope=platform").status()).isEqualTo(403);
        assertThat(other.get("/api/dashboard?scope=workspace&workspaceId=" + wsId).status()).isEqualTo(403);
        assertThat(other.get("/api/dashboard?workspaceId=" + wsId).status()).isEqualTo(403);
        assertThat(other.get("/api/dashboard?scope=workspace&userId=u_someone").status()).isEqualTo(403);
        assertThat(other.get("/api/logs/runtime").status()).isEqualTo(403);
        assertThat(other.get("/api/logs/tasks?workspaceId=" + wsId).status()).isEqualTo(403);
        assertThat(other.get("/api/logs/tasks/" + taskId).status()).isEqualTo(403);
        assertThat(other.get("/api/logs/functions?appId=" + appId).status()).isEqualTo(403);

        // 管理员身份放行全平台聚合与跨空间下钻
        Client admin = other.asAdmin();
        assertThat(admin.ok(admin.get("/api/meta")).at("/auth/admin").asBoolean()).isTrue();
        JsonNode platform = admin.ok(admin.get("/api/dashboard?range=30d"));
        assertThat(platform.path("meta").path("scope").asText()).isEqualTo("platform");
        assertThat(platform.path("overview").path("appsTotal").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(platform.path("overview").path("tokensTotal").asLong()).isGreaterThanOrEqualTo(1540);
        assertThat(platform.path("overview").has("taskP90DurationSec")).isTrue();
        assertThat(platform.path("agentTelemetry").has("tools")).isTrue();
        assertThat(platform.path("agentTelemetry").has("verifyChecks")).isTrue();
        assertThat(platform.path("agentTelemetry").has("eventBreakdown")).isTrue();
        assertThat(platform.path("workspaces").size()).isGreaterThanOrEqualTo(1);
        assertThat(platform.path("tasks").path("recentTasks").toString()).contains(taskId);
        assertThat(platform.path("actors").size()).isGreaterThanOrEqualTo(1);

        // 管理员自定义起止日期 + 按工作空间 ID 下钻筛选
        String todayStr = LocalDate.now(PageViewRecorder.ZONE).toString();
        String yesterdayStr = LocalDate.now(PageViewRecorder.ZONE).minusDays(1).toString();
        JsonNode customWs = admin.ok(admin.get("/api/dashboard?startDate=" + yesterdayStr + "&endDate=" + todayStr + "&workspaceId=" + wsId));
        assertThat(customWs.path("meta").path("range").asText()).isEqualTo("custom");
        assertThat(customWs.path("meta").path("filterWorkspaceId").asText()).isEqualTo(wsId);
        assertThat(customWs.path("trends").path("daily").size()).isEqualTo(2);
        assertThat(customWs.path("overview").path("appsTotal").asLong()).isEqualTo(1);
        assertThat(customWs.path("overview").path("tokensInput").asLong()).isGreaterThanOrEqualTo(1200);
        assertThat(admin.get("/api/logs/tasks/" + taskId).status()).isEqualTo(200);
        assertThat(admin.get("/api/logs/runtime").status()).isEqualTo(200);

        // 未来空时间段返回 0
        String future = LocalDate.now(PageViewRecorder.ZONE).plusDays(5).toString();
        JsonNode futureDash = admin.ok(admin.get("/api/dashboard?startDate=" + future + "&endDate=" + future + "&workspaceId=" + wsId));
        assertThat(futureDash.path("overview").path("appsTotal").asLong()).isZero();
        assertThat(futureDash.path("overview").path("tasksTotal").asLong()).isZero();
    }

    private String fullWsId(Client c) {
        return c.ok(c.get("/api/workspace")).path("id").asText();
    }
}
