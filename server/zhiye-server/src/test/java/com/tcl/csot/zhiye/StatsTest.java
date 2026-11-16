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

/** 数据统计（PV）、应用汇总、删除即下线。 */
class StatsTest extends IntegrationTest {
    @Autowired PageViewRecorder recorder;
    @Autowired JdbcClient jdbc;

    @Test
    void pageViewsStatsSummaryAndDelete() {
        Client c = client();
        JsonNode app = c.createApp("做一个计数器");
        String id = app.path("id").asText();
        // 未发布：全是 0
        JsonNode s0 = c.ok(c.get("/api/apps/" + id + "/stats?range=7d"));
        assertThat(s0.path("totalPv").asLong()).isZero();
        assertThat(s0.path("daily").size()).isEqualTo(7);

        JsonNode g = c.ok(c.post("/api/apps/" + id + "/generate", Map.of()));
        c.waitTask(g.path("id").asText());
        JsonNode pub = c.ok(c.post("/api/apps/" + id + "/publish", Map.of()));
        String host = pub.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);
        JsonNode detail = c.ok(c.get("/api/apps/" + id));

        assertThat(c.getWithHost("/", host).status()).isEqualTo(200);           // +1
        assertThat(c.getWithHost("/about", host).status()).isEqualTo(200);      // SPA 回退 HTML +1
        assertThat(c.getWithHost("/assets/app.css", host).status()).isEqualTo(200); // 资源不计
        c.send("GET", "/", null, Map.of("Host", host, "User-Agent", "Googlebot/2.1"));   // 爬虫不计
        c.send("HEAD", "/", null, Map.of("Host", host));                                 // HEAD 不计
        c.get(local(detail.path("previewUrl").asText()));                                // 预览不计
        recorder.flush();

        JsonNode s = c.ok(c.get("/api/apps/" + id + "/stats?range=3d"));
        assertThat(s.path("todayPv").asLong()).isEqualTo(2);
        assertThat(s.path("totalPv").asLong()).isEqualTo(2);
        assertThat(s.path("rangePv").asLong()).isEqualTo(2);
        assertThat(s.path("daily").size()).isEqualTo(3);
        String today = LocalDate.now(PageViewRecorder.ZONE).toString();
        assertThat(s.path("daily").get(2).path("date").asText()).isEqualTo(today);
        assertThat(s.path("daily").get(2).path("pv").asLong()).isEqualTo(2);
        assertThat(s.path("daily").get(0).path("pv").asLong()).isZero();
        assertThat(c.ok(c.get("/api/apps/" + id + "/stats?range=today")).path("daily").size()).isEqualTo(1);

        // 历史数据：5 天前 10 次 → all 从那天起补零
        jdbc.sql("insert into app_pageview_daily (app_id, day, pv) values (?, ?, 10)").params(id, LocalDate.now(PageViewRecorder.ZONE).minusDays(5)).update();
        JsonNode all = c.ok(c.get("/api/apps/" + id + "/stats?range=all"));
        assertThat(all.path("daily").size()).isEqualTo(6);
        assertThat(all.path("daily").get(0).path("pv").asLong()).isEqualTo(10);
        assertThat(all.path("daily").get(1).path("pv").asLong()).isZero();
        assertThat(all.path("rangePv").asLong()).isEqualTo(12);
        assertThat(c.ok(c.get("/api/apps/" + id + "/stats?range=7d")).path("rangePv").asLong()).isEqualTo(12);
        assertThat(c.ok(c.get("/api/apps/" + id + "/stats?range=3d")).path("rangePv").asLong()).isEqualTo(2);
        assertThat(c.get("/api/apps/" + id + "/stats?range=1y").status()).isEqualTo(400);
        assertThat(client().get("/api/apps/" + id + "/stats").status()).isEqualTo(404);

        // 汇总：再建一个未发布的应用
        c.createApp("做一个待办");
        JsonNode sum = c.ok(c.get("/api/apps/summary"));
        assertThat(sum.path("total").asLong()).isEqualTo(2);
        assertThat(sum.path("published").asLong()).isEqualTo(1);
        assertThat(sum.path("totalPv").asLong()).isEqualTo(12);

        // 删除：有进行中任务 → 409；删除后站点 404、历史和汇总里都没有
        JsonNode busyApp = c.createApp("做一个计时器 [slow]");
        c.ok(c.post("/api/apps/" + busyApp.path("id").asText() + "/generate", Map.of()));
        assertThat(c.delete("/api/apps/" + busyApp.path("id").asText()).status()).isEqualTo(409);

        assertThat(c.delete("/api/apps/" + id).status()).isEqualTo(204);
        assertThat(c.getWithHost("/", host).status()).isEqualTo(404);
        assertThat(c.ok(c.get("/api/history?appId=" + id)).size()).isZero();
        assertThat(c.ok(c.get("/api/history")).toString()).doesNotContain(id);
        JsonNode sum2 = c.ok(c.get("/api/apps/summary"));
        assertThat(sum2.path("total").asLong()).isEqualTo(2);
        assertThat(sum2.path("published").asLong()).isZero();
        assertThat(sum2.path("totalPv").asLong()).isZero();
        assertThat(c.ok(c.get("/api/apps")).toString()).doesNotContain(id);
    }
}
