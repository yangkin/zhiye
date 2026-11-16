package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.FakeUpstreamController;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 能力上限：计划按上限拆期；一次做不完时提示「太大、已保留进度」而不是「模型不可用」，重试接着做。 */
class CapabilityLimitTest extends IntegrationTest {

    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Test
    void planCarriesLimitsAndPhasesBigRequests() {
        Client c = client();
        JsonNode app = c.createApp("做一整套设备管理平台 [big] CAP-PLAN-1");
        assertThat(app.path("planDoc").asText()).contains("## 分期计划");

        JsonNode plan = FakeUpstreamController.BODIES.stream()
                .filter(b -> b.toString().contains("CAP-PLAN-1")).reduce((a, b) -> b).orElseThrow();
        String system = plan.path("messages").get(0).path("content").asText();
        assertThat(system).contains("# 能力范围").contains("页面数量不限").contains("云数据库表不超过 50 张").contains("## 分期计划").contains("做不到的需求");

        JsonNode task = c.ok(c.get("/api/apps/" + app.path("id").asText() + "/tasks")).get(0);
        assertThat(c.events(task.path("id").asText(), 0, Map.of()).toString()).contains("先做第一期");
    }
}
