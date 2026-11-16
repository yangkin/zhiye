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

/** SkillHub 开启：调用（测试桩）SkillHub，记录发布结果。 */
@TestPropertySource(properties = {
        // 配置不同的测试上下文各用一个 schema：否则各上下文的任务工作进程会领走彼此的任务（配置不同，会失败）
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_skillhub", "spring.flyway.schemas=ctx_skillhub",
        "zhiye.skillhub.enabled=true", "zhiye.skillhub.base-url={self}/test-skillhub",
        "zhiye.skillhub.token-env=ZHIYE_TEST_SKILLHUB_TOKEN"})
class SkillHubEnabledTest extends IntegrationTest {
    static { System.setProperty("ZHIYE_TEST_SKILLHUB_TOKEN", "tok-123"); }

    @Test
    void publishRecordsResult() throws Exception {
        Client c = client();
        JsonNode app = SkillHubTest.skillApp(c);
        String id = app.path("id").asText();
        assertThat(app.path("skillhub").path("enabled").asBoolean()).isTrue();

        JsonNode r = c.ok(c.post("/api/apps/" + id + "/skillhub/publish", Map.of("visibility", "public", "changelog", "首次发布")));
        assertThat(r.path("skillId").asText()).isEqualTo("sk_123");
        assertThat(r.path("skillVersion").asText()).isEqualTo("1.0.0");
        assertThat(r.path("url").asText()).isEqualTo("https://skillhub.example/skills/sk_123");
        assertThat(r.path("versionId").asText()).isEqualTo(app.path("currentVersion").path("id").asText());
        assertThat(r.path("publishedAt").asText()).isNotBlank();

        assertThat(FakeSkillHubController.lastAuth).isEqualTo("Bearer tok-123");
        JsonNode meta = Json.read(FakeSkillHubController.lastMetadata);
        assertThat(meta.path("name").asText()).isEqualTo("weekly-report");
        assertThat(meta.path("visibility").asText()).isEqualTo("public");
        assertThat(meta.path("changelog").asText()).isEqualTo("首次发布");
        Set<String> tops = new HashSet<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(FakeSkillHubController.lastPackage))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) tops.add(e.getName().split("/")[0]);
        }
        assertThat(tops).containsExactly("weekly-report");

        JsonNode pubs = c.ok(c.get("/api/apps/" + id + "/skillhub/publications"));
        assertThat(pubs.size()).isEqualTo(1);
        assertThat(pubs.get(0).path("versionNumber").asInt()).isEqualTo(1);
        JsonNode after = c.ok(c.get("/api/apps/" + id));
        assertThat(after.path("skillhub").path("url").asText()).isEqualTo("https://skillhub.example/skills/sk_123");
        assertThat(after.path("skillhub").path("lastPublishedAt").asText()).isEqualTo(r.path("publishedAt").asText());

        // SKILL.md 头部不合法 → 400
        String v1 = after.path("currentVersion").path("id").asText();
        JsonNode save = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1,
                "files", List.of(Map.of("path", "SKILL.md", "content", "---\nname: Weekly Report\ndescription: x\n---\n")))));
        assertThat(c.waitTask(save.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        var bad = c.post("/api/apps/" + id + "/skillhub/publish", Map.of());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().path("message").asText()).contains("name");
        // 旧版本仍可指定发布
        assertThat(c.post("/api/apps/" + id + "/skillhub/publish", Map.of("versionId", v1)).status()).isEqualTo(200);
    }
}
