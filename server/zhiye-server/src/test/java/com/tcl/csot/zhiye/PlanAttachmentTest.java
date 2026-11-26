package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.FakeUpstreamController;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 创建应用时带的截图要给计划看：计划请求是多模态消息，图片在里面。 */
class PlanAttachmentTest extends IntegrationTest {

    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Test
    void planSeesAttachedScreenshot() {
        Client c = client();
        JsonNode img = c.ok(c.upload("/api/assets", "工作台截图.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 9, 9, 9}, Map.of()));
        JsonNode app = c.ok(c.post("/api/apps", Map.of("type", "日常开发", "prompt", "照着截图做一个工作台 PLAN-IMG-7",
                "mode", "快速", "model", "auto", "assetIds", List.of(img.path("id").asText()))));
        assertThat(c.waitTask(app.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode plan = FakeUpstreamController.BODIES.stream()
                .filter(b -> b.toString().contains("PLAN-IMG-7") && b.toString().contains("用户需求"))
                .reduce((a, b) -> b).orElseThrow();
        JsonNode msgs = plan.path("messages");
        JsonNode parts = msgs.get(msgs.size() - 1).path("content");
        assertThat(parts.isArray()).isTrue();
        assertThat(parts.get(0).path("text").asText()).contains("PLAN-IMG-7").contains("用户附上了下面的素材");
        assertThat(parts.get(1).path("text").asText()).contains("工作台截图.png");
        assertThat(parts.get(2).path("type").asText()).isEqualTo("image_url");
        assertThat(parts.get(2).path("image_url").path("url").asText()).startsWith("data:image/png;base64,");
    }

    @Test
    void truncatedPlanRetriesWithLargerBudget() {
        FakeUpstreamController.TRUNCATED_CALLS.set(0);
        Client c = client();
        JsonNode app = c.createApp("做一个研发项目看板 [truncated] PLAN-TRUNC-1");

        List<Integer> budgets = FakeUpstreamController.BODIES.stream()
                .filter(b -> b.toString().contains("PLAN-TRUNC-1") && b.toString().contains("用户需求"))
                .map(b -> b.path("max_tokens").asInt()).toList();
        assertThat(budgets).containsExactly(16000, 32000);
    }
}
