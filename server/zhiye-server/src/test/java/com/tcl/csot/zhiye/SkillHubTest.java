package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SkillHub 未接入（默认 DisabledSkillHubClient）：浏览/安装/发布都返回 SKILLHUB_NOT_CONFIGURED。 */
class SkillHubTest extends IntegrationTest {

    static Map<String, String> bearer() { return Map.of("Authorization", "Bearer tok-tester"); }

    /** 生成一个「开发skill」应用并跑完生成任务（技能选择器与发布共用） */
    static JsonNode skillApp(Client c) {
        JsonNode app = c.ok(c.post("/api/apps", Map.of("type", "开发skill", "prompt", "做一个生成周报的 skill",
                "mode", "快速", "model", "auto", "assetIds", List.of())));
        assertThat(c.waitTask(app.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode detail = c.ok(c.get("/api/apps/" + app.path("id").asText()));
        assertThat(detail.path("projectKind").asText()).isEqualTo("skill");
        return detail;
    }

    @Test
    void disabledAndNonSkill() {
        Client c = client();
        JsonNode app = skillApp(c);
        String id = app.path("id").asText();
        assertThat(app.path("skillhub").path("enabled").asBoolean()).isFalse();
        assertThat(app.path("skillhub").path("lastPublishedAt").isNull()).isTrue();
        assertThat(c.send("GET", "/api/skillhub/market", null, bearer()).status()).isEqualTo(503);
        assertThat(c.send("GET", "/api/skillhub/market", null, bearer()).code()).isEqualTo("SKILLHUB_NOT_CONFIGURED");
        var r = c.send("POST", "/api/skillhub/apps/" + id + "/publish", Map.of("visibility", "team"), bearer());
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("SKILLHUB_NOT_CONFIGURED");
        assertThat(c.ok(c.get("/api/skillhub/apps/" + id + "/publications")).size()).isZero();

        JsonNode web = c.createApp("做一个计数器");
        assertThat(web.path("skillhub").isNull()).isTrue();
        assertThat(c.post("/api/skillhub/apps/" + web.path("id").asText() + "/publish", Map.of()).status()).isEqualTo(400);
        assertThat(client().post("/api/skillhub/apps/" + id + "/publish", Map.of()).status()).isEqualTo(404);
    }

    @Test
    void parsesFrontmatter() {
        com.tcl.csot.zhiye.service.skillhub.SkillMeta m = com.tcl.csot.zhiye.service.skillhub.SkillMeta.parse("---\nname: t11-stk-alarm-query\ndescription: \"生成周报\"  # 注释\ncompatibility: Python 3.10+ 标准库\n---\n正文");
        assertThat(m.validName()).isTrue();
        assertThat(m.hasVersionInName()).isFalse();
        assertThat(m.description()).isEqualTo("生成周报");
        assertThat(m.vagueCompatibility()).isFalse();

        com.tcl.csot.zhiye.service.skillhub.SkillMeta versioned = com.tcl.csot.zhiye.service.skillhub.SkillMeta.parse("---\nname: t11-stk-alarm-v2\ndescription: 查告警\ncompatibility: 兼容飞书\n---\n正文");
        assertThat(versioned.hasVersionInName()).isTrue();
        assertThat(versioned.validName()).isFalse();
        assertThat(versioned.vagueCompatibility()).isTrue();

        com.tcl.csot.zhiye.service.skillhub.SkillMeta block = com.tcl.csot.zhiye.service.skillhub.SkillMeta.parse("---\nname: 周报 Skill\ndescription: >\n  第一行\n  第二行\n---\n");
        assertThat(block.validName()).isFalse();
        assertThat(block.description()).isEqualTo("第一行 第二行");

        assertThat(com.tcl.csot.zhiye.service.skillhub.SkillMeta.isGenericDisplayName("SPC")).isTrue();
        assertThat(com.tcl.csot.zhiye.service.skillhub.SkillMeta.isGenericDisplayName("产品助手")).isTrue();
        assertThat(com.tcl.csot.zhiye.service.skillhub.SkillMeta.isGenericDisplayName("t11 - 储位告警查询")).isFalse();
        assertThat(com.tcl.csot.zhiye.service.skillhub.SkillMeta.isJunkPath("debug.log")).isTrue();
        assertThat(com.tcl.csot.zhiye.service.skillhub.SkillMeta.isJunkPath("tmp/result.json")).isTrue();
        assertThat(com.tcl.csot.zhiye.service.skillhub.SkillMeta.isJunkPath("references/guide.md")).isFalse();
        assertThat(com.tcl.csot.zhiye.service.skillhub.SkillMeta.scanSecrets("联系人 ou_1234567890abcdef1234")).isNotEmpty();
        assertThat(com.tcl.csot.zhiye.service.skillhub.SkillMeta.scanSecrets("token = \"<YOUR_TOKEN>\"")).isEmpty();
    }
}
