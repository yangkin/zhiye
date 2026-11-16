package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 对话附带素材、引用自己的技能（skillAppIds）。 */
class ChatAttachmentsTest extends IntegrationTest {

    static String agentText(Client c, String taskId) {
        return c.events(taskId, 0, Map.of()).stream().filter(e -> e.path("type").asText().equals("agent_text"))
                .map(e -> e.path("payload").path("delta").asText()).collect(Collectors.joining());
    }

    JsonNode skill(Client c, String prompt) {
        JsonNode s = c.ok(c.post("/api/apps", Map.of("type", "开发skill", "prompt", prompt, "mode", "快速", "model", "auto", "assetIds", List.of())));
        assertThat(c.waitTask(s.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        return s;
    }

    @Test
    void assetsAndSkillsInChat() {
        Client c = client();
        JsonNode sk = skill(c, "做一个周报生成 skill");
        JsonNode web = c.createApp("做一个计数器");
        String id = web.path("id").asText();
        c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText());

        JsonNode asset = c.ok(c.upload("/api/assets", "logo 图.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3}, Map.of()));
        JsonNode r = c.ok(c.post("/api/apps/" + id + "/messages", Map.of("content", "把 logo 放到标题旁边",
                "assetIds", List.of(asset.path("id").asText()), "skillAppIds", List.of(sk.path("id").asText()))));
        assertThat(r.path("message").path("assetIds").toString()).contains(asset.path("id").asText());
        String tid = r.path("task").path("id").asText();
        assertThat(c.waitTask(tid).path("status").asText()).isEqualTo("SUCCEEDED");
        String text = agentText(c, tid);
        assertThat(text).contains("已读取技能 weekly-report").contains("素材已就绪 public/assets/logo-图.png");

        JsonNode cur = c.ok(c.get("/api/apps/" + id));
        String files = c.ok(c.get("/api/versions/" + cur.path("currentVersion").path("id").asText() + "/files")).toString();
        assertThat(files).contains("public/assets/logo-图.png").doesNotContain("skills/").doesNotContain("SKILL.md");
        JsonNode msgs = c.ok(c.get("/api/apps/" + id + "/messages"));
        JsonNode mine = null;
        for (JsonNode m : msgs) if (m.path("id").asText().equals(r.path("message").path("id").asText())) mine = m;
        assertThat(mine.path("assetIds").get(0).asText()).isEqualTo(asset.path("id").asText());
        assertThat(mine.path("skillAppIds").get(0).asText()).isEqualTo(sk.path("id").asText());

        // skill 应用：素材放进 references/assets/
        JsonNode r2 = c.ok(c.post("/api/apps/" + sk.path("id").asText() + "/messages", Map.of("content", "加一个示例",
                "assetIds", List.of(asset.path("id").asText()))));
        assertThat(c.waitTask(r2.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        String skFiles = c.ok(c.get("/api/versions/" + c.ok(c.get("/api/apps/" + sk.path("id").asText())).path("currentVersion").path("id").asText() + "/files")).toString();
        assertThat(skFiles).contains("references/assets/logo-图.png");
    }

    @Test
    void createWithSkillsAndValidation() {
        Client c = client();
        JsonNode sk = skill(c, "做一个周报生成 skill");
        // 创建时引用技能：生成任务的指令里带上技能
        JsonNode app = c.ok(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "做一个周报页面", "mode", "快速", "model", "auto",
                "assetIds", List.of(), "skillAppIds", List.of(sk.path("id").asText()))));
        c.waitTask(app.path("activeTask").path("id").asText());
        JsonNode g = c.ok(c.post("/api/apps/" + app.path("id").asText() + "/generate", Map.of()));
        assertThat(c.waitTask(g.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(agentText(c, g.path("id").asText())).contains("已读取技能 weekly-report");

        // 别的工作空间的技能 / 素材 → 400
        Client other = client();
        JsonNode otherSkill = skill(other, "做一个翻译 skill");
        JsonNode otherAsset = other.ok(other.upload("/api/assets", "a.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8), Map.of()));
        var bad = c.post("/api/apps", Map.of("type", "开发网站", "prompt", "x", "mode", "快速", "model", "auto", "assetIds", List.of(),
                "skillAppIds", List.of(otherSkill.path("id").asText())));
        assertThat(bad.status()).isEqualTo(400);
        String appId = app.path("id").asText();
        assertThat(c.post("/api/apps/" + appId + "/messages", Map.of("content", "x", "skillAppIds", List.of(otherSkill.path("id").asText()))).status()).isEqualTo(400);
        assertThat(c.post("/api/apps/" + appId + "/messages", Map.of("content", "x", "assetIds", List.of(otherAsset.path("id").asText()))).status()).isEqualTo(400);
        // 模型只能是 meta 里列出的
        assertThat(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "x", "mode", "快速", "model", "gpt-9", "assetIds", List.of())).status()).isEqualTo(400);
        assertThat(c.post("/api/apps/" + appId + "/messages", Map.of("content", "x", "model", "gpt-9")).status()).isEqualTo(400);
        JsonNode models = c.ok(c.get("/api/meta")).path("models");
        // 只列真正会被任务用到的模型：选了不生效的选项不展示；旧数据里的 kimi-for-coding 仍然合法
        assertThat(models.findValuesAsText("id")).containsExactly("auto");
        assertThat(models.get(0).path("desc").asText()).contains("平台统一配置");
        assertThat(models.get(0).path("cost").asInt()).isEqualTo(2);
        assertThat(c.patch("/api/apps/" + appId, Map.of("model", "kimi-for-coding")).status()).isEqualTo(200);
        // 不是 skill 应用 → 400；超过 5 个 → 400
        assertThat(c.post("/api/apps/" + appId + "/messages", Map.of("content", "x", "skillAppIds", List.of(appId))).status()).isEqualTo(400);
        String s = sk.path("id").asText();
        assertThat(c.post("/api/apps/" + appId + "/messages", Map.of("content", "x", "skillAppIds", List.of(s, s, s, s, s, s))).status()).isEqualTo(400);
        // 校验失败没有留下任务
        assertThat(c.ok(c.get("/api/apps/" + appId + "/tasks?active=true")).size()).isZero();
    }

    @Test
    void skillsPersistOnApp() {
        Client c = client();
        JsonNode sk = skill(c, "做一个周报生成 skill");
        String s = sk.path("id").asText();
        JsonNode web = c.createApp("做一个计数器");
        String id = web.path("id").asText();
        assertThat(c.ok(c.get("/api/apps/" + id)).path("skills").size()).isZero();

        JsonNode after = c.ok(c.patch("/api/apps/" + id, Map.of("skillAppIds", List.of(s))));
        assertThat(after.path("skills").get(0).path("id").asText()).isEqualTo(s);
        assertThat(after.path("skills").get(0).path("name").asText()).isEqualTo(sk.path("name").asText());
        assertThat(c.ok(c.get("/api/apps/" + id)).path("skills").size()).isEqualTo(1);

        // 别人的技能、引用自己 → 400；清空
        Client other = client();
        String otherSkill = skill(other, "做一个翻译 skill").path("id").asText();
        assertThat(c.patch("/api/apps/" + id, Map.of("skillAppIds", List.of(otherSkill))).status()).isEqualTo(400);
        assertThat(c.patch("/api/apps/" + s, Map.of("skillAppIds", List.of(s))).status()).isEqualTo(400);
        assertThat(c.ok(c.patch("/api/apps/" + id, Map.of("skillAppIds", List.of()))).path("skills").size()).isZero();

        // 技能被删除后不再返回
        c.ok(c.patch("/api/apps/" + id, Map.of("skillAppIds", List.of(s))));
        assertThat(c.delete("/api/apps/" + s).status()).isLessThan(300);
        assertThat(c.ok(c.get("/api/apps/" + id)).path("skills").size()).isZero();
    }
}
