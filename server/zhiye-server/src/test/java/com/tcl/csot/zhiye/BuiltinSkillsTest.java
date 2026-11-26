package com.tcl.csot.zhiye;

import com.tcl.csot.zhiye.service.skillhub.SkillMeta;
import com.tcl.csot.zhiye.service.skillhub.SkillRefs;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 织页内置技能：目录、按需放置、问答 read_skill 的读取边界。 */
class BuiltinSkillsTest extends IntegrationTest {
    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Autowired
    SkillRefs refs;

    @Test
    void catalogPlaceAndRead(@TempDir Path agentDir) throws Exception {
        var names = refs.builtinCatalog().stream().map(SkillMeta::rawName).toList();
        assertThat(names).contains(
                "charts-dashboard", "excel-import-export", "forms-and-tables", "multi-page-app", "zhiye-deploy-preflight",
                "enterprise-ui-craft", "excel-csv-to-dashboard", "mfg-spc-quality-closed-loop",
                "vue-springboot-enterprise", "python-js-automation-scripts");

        var placed = refs.placeBuiltin(agentDir, Set.of("forms-and-tables"));
        assertThat(placed).extracting(SkillRefs.Builtin::name).doesNotContain("forms-and-tables").contains("charts-dashboard");
        assertThat(Files.isRegularFile(agentDir.resolve("skills/charts-dashboard/SKILL.md"))).isTrue();
        String ins = SkillRefs.catalogInstruction(placed);
        assertThat(ins).contains("按需加载").contains("charts-dashboard")
                .contains(agentDir.resolve("skills/charts-dashboard/SKILL.md").toString().replace('\\', '/'));
        assertThat(SkillRefs.catalogInstruction(java.util.List.of())).isEmpty();

        assertThat(refs.readBuiltin("charts-dashboard", null)).startsWith("---");
        assertThat(refs.readBuiltin("charts-dashboard", "../multi-page-app/SKILL.md")).isNull();
        assertThat(refs.readBuiltin("../charts-dashboard", "SKILL.md")).isNull();
        assertThat(refs.readBuiltin("charts-dashboard", "../../../server/pom.xml")).isNull();
        assertThat(refs.readBuiltin("no-such-skill", "SKILL.md")).isNull();
    }

    @Test
    void askCanReadBuiltinSkill() {
        var c = client();
        var r = c.post("/api/chat/ask", java.util.Map.of("content", "[read_skill] 看板怎么做"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).contains("技能内容：---").contains("charts-dashboard").contains("event:done");
        var meta = c.ok(c.get("/api/meta")).path("builtinSkills");
        assertThat(meta.findValuesAsText("name")).contains("charts-dashboard");
        assertThat(meta.get(0).path("title").asText()).doesNotStartWith("#").isNotBlank();
    }
}
