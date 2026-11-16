package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.skillhub.SkillMeta;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SkillHub 未开启（默认）：返回 503「即将接入」；非 skill 应用 → 400。 */
class SkillHubTest extends IntegrationTest {

    static JsonNode skillApp(Client c) {
        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发skill", "prompt", "做一个周报生成 skill", "mode", "快速", "model", "auto", "assetIds", List.of())));
        String id = created.path("id").asText();
        assertThat(c.waitTask(created.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        return c.ok(c.get("/api/apps/" + id));
    }

    @Test
    void disabledAndNonSkill() {
        Client c = client();
        JsonNode app = skillApp(c);
        String id = app.path("id").asText();
        assertThat(app.path("skillhub").path("enabled").asBoolean()).isFalse();
        assertThat(app.path("skillhub").path("lastPublishedAt").isNull()).isTrue();
        var r = c.post("/api/apps/" + id + "/skillhub/publish", Map.of("visibility", "team"));
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("SKILLHUB_NOT_CONFIGURED");
        assertThat(r.json().path("message").asText()).isEqualTo("SkillHub 发布即将接入，现在可以先下载技能包使用");
        assertThat(c.ok(c.get("/api/apps/" + id + "/skillhub/publications")).size()).isZero();
        assertThat(c.post("/api/apps/" + id + "/skillhub/publish", Map.of("visibility", "everyone")).status()).isEqualTo(400);

        JsonNode web = c.createApp("做一个计数器");
        assertThat(web.path("skillhub").isNull()).isTrue();
        assertThat(c.post("/api/apps/" + web.path("id").asText() + "/skillhub/publish", Map.of()).status()).isEqualTo(400);
        assertThat(client().post("/api/apps/" + id + "/skillhub/publish", Map.of()).status()).isEqualTo(404);
    }

    @Test
    void parsesFrontmatter() {
        SkillMeta m = SkillMeta.parse("---\nname: weekly-report\ndescription: \"生成周报\"  # 注释\n---\n正文");
        assertThat(m.validName()).isTrue();
        assertThat(m.description()).isEqualTo("生成周报");
        SkillMeta block = SkillMeta.parse("---\nname: 周报 Skill\ndescription: >\n  第一行\n  第二行\n---\n");
        assertThat(block.validName()).isFalse();
        assertThat(block.folderName()).isEqualTo("skill");
        assertThat(block.description()).isEqualTo("第一行 第二行");
        assertThat(SkillMeta.parse("没有头部").rawName()).isNull();
    }
}
