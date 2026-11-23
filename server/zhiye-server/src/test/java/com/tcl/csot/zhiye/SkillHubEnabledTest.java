package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.FakeSkillHubController;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillHub 开启：按 fivestar-service 真实契约（/{org}/aiToolbox/skills/add|edit、metis Result 信封）
 * 调用测试桩，记录并校验发布请求。
 */
@TestPropertySource(properties = {
        // 配置不同的测试上下文各用一个 schema：否则各上下文的任务工作进程会领走彼此的任务（配置不同，会失败）
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_skillhub", "spring.flyway.schemas=ctx_skillhub",
        "logging.level.com.tcl.csot.zhiye.handler.GlobalExceptionHandler=DEBUG",
        "zhiye.auth.sso.user-info-url={self}/test-skillhub/self",
        "zhiye.skillhub.enabled=true", "zhiye.skillhub.base-url={self}/test-skillhub"})
class SkillHubEnabledTest extends IntegrationTest {

    @Test
    void publishRecordsResult() throws Exception {
        FakeSkillHubController.reset();
        Client c = client();
        JsonNode app = SkillHubTest.skillApp(c);
        String id = app.path("id").asText();
        assertThat(app.path("skillhub").path("enabled").asBoolean()).isTrue();

        // 非法可见范围、超长/泛化显示名称在调 SkillHub 之前就被拦下
        assertThat(c.send("POST", "/api/skillhub/apps/" + id + "/publish", Map.of("visibility", "everyone"), SkillHubTest.bearer()).status()).isEqualTo(400);
        assertThat(c.send("POST", "/api/skillhub/apps/" + id + "/publish", Map.of("displayName", "产品助手", "visibility", "private"), SkillHubTest.bearer()).status()).isEqualTo(400);
        assertThat(c.send("POST", "/api/skillhub/apps/" + id + "/publish", Map.of("displayName", "超过二十个字的超长技能显示名称测试截断拦截校验", "visibility", "private"), SkillHubTest.bearer()).status()).isEqualTo(400);

        // 首次发布 → fivestar /add：直传 zip（fileId/fileUrl 空串）、无 draftJson；/add 不接收更新说明
        JsonNode r = c.ok(c.send("POST", "/api/skillhub/apps/" + id + "/publish",
                Map.of("visibility", "public", "changelog", "首次发布"), SkillHubTest.bearer()));
        assertThat(r.path("skillId").asText()).isEqualTo("12345");
        assertThat(r.path("skillVersion").asText()).startsWith("v");
        assertThat(r.path("url").asText()).contains("12345");
        assertThat(r.path("versionId").asText()).isEqualTo(app.path("currentVersion").path("id").asText());
        assertThat(r.path("publishedAt").asText()).isNotBlank();
        assertThat(r.path("status").asText()).isNotBlank();

        assertThat(FakeSkillHubController.lastAuth).isEqualTo("Bearer tok-tester");
        assertThat(FakeSkillHubController.lastPath).isEqualTo("/add");
        Map<String, String> f = FakeSkillHubController.lastFields;
        assertThat(f.get("displayName")).isEqualTo(app.path("name").asText());
        assertThat(f.get("owner")).isEqualTo("测试用户");
        assertThat(f.get("categoryId")).isEqualTo("c1");
        assertThat(f.get("subReview")).isEqualTo("true");
        assertThat(f).doesNotContainKeys("changeLog", "draftJson");
        assertThat(f.get("fileId")).isEmpty();
        assertThat(f.get("fileUrl")).isEmpty();
        Set<String> tops = new HashSet<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(FakeSkillHubController.lastPackage))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) tops.add(e.getName().split("/")[0]);
        }
        assertThat(tops).containsExactly("weekly-report");

        // 再次发布 → fivestar /edit：带上次 skillId、draftJson 和 changeLog
        JsonNode r2 = c.ok(c.send("POST", "/api/skillhub/apps/" + id + "/publish",
                Map.of("visibility", "public", "changelog", "第二次发布"), SkillHubTest.bearer()));
        assertThat(r2.path("skillId").asText()).isEqualTo("12345");
        assertThat(FakeSkillHubController.lastPath).isEqualTo("/edit");
        assertThat(FakeSkillHubController.lastFields.get("skillId")).isEqualTo("12345");
        assertThat(FakeSkillHubController.lastFields.get("changeLog")).isEqualTo("第二次发布");
        JsonNode draft = Json.read(FakeSkillHubController.lastFields.get("draftJson"));
        assertThat(draft.path("displayName").asText()).isEqualTo(app.path("name").asText());
        assertThat(draft.path("categoryId").asText()).isEqualTo("c1");

        assertThat(c.ok(c.get("/api/skillhub/apps/" + id + "/publications")).size()).isEqualTo(2);
    }

    @Test
    void marketInstallAndSelect() {
        Client c = client();
        JsonNode categories = c.ok(c.send("GET", "/api/skillhub/categories", null, SkillHubTest.bearer()));
        assertThat(categories.at("/0/id").asText()).isEqualTo("c1");
        JsonNode market = c.ok(c.send("GET", "/api/skillhub/market", null, SkillHubTest.bearer()));
        assertThat(market.path("total").asInt()).isEqualTo(1);
        assertThat(market.at("/items/0/name").asText()).isEqualTo("周报助手");

        JsonNode installed = c.ok(c.send("POST", "/api/skillhub/installs", Map.of("skillId", "101", "versionId", "678"), SkillHubTest.bearer()));
        String installId = installed.path("id").asText();
        assertThat(installId).startsWith("hub_");
        assertThat(installed.path("installed").asBoolean()).isTrue();
        assertThat(c.ok(c.send("POST", "/api/skillhub/installs", Map.of("skillId", "101", "versionId", "678"), SkillHubTest.bearer()))
                .path("id").asText()).isEqualTo(installId);

        JsonNode meta = c.ok(c.get("/api/meta"));
        assertThat(meta.path("skillhub").asBoolean()).isTrue();
        assertThat(meta.path("hubSkills").toString()).contains(installId).contains("周报助手");
        assertThat(c.ok(c.get("/api/skillhub/my-skills")).toString()).contains(installId).contains("周报助手");
        JsonNode app = c.ok(c.post("/api/apps", Map.of("type", "开发skill", "prompt", "使用周报技能生成会议纪要 skill",
                "mode", "快速", "model", "auto", "assetIds", List.of(), "skillAppIds", List.of(installId))));
        assertThat(c.waitTask(app.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(c.delete("/api/skillhub/installs/" + installId).status()).isEqualTo(204);
        assertThat(c.ok(c.get("/api/meta")).path("hubSkills").size()).isZero();
    }
}
