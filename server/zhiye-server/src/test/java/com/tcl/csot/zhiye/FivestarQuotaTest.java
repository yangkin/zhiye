package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.controller.mock.MockFivestarController;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.llm.LlmRouter;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 五星AI岛额度对接（全部走内置模拟的 openclaw / llm-gateway）：
 * 模型列表与默认模型来自五星AI岛、调用带 X-User-Id / X-Project-Id 走个人路由、用量计入个人额度、额度用完给出明确提示。
 */
@TestPropertySource(properties = {
        // 配置不同的测试上下文各用一个 schema：否则各上下文的任务工作进程会领走彼此的任务（配置不同，会失败）
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_fivestar", "spring.flyway.schemas=ctx_fivestar",
        "zhiye.auth.mode=sso", "zhiye.auth.sso.user-info-url={self}/test-sso/self",
        "zhiye.workspace.max-apps=1000", "zhiye.fivestar.enabled=true", "zhiye.fivestar.mock=true",
        "zhiye.fivestar.mock-limit-tokens=1000000"})
class FivestarQuotaTest extends IntegrationTest {
    @Autowired MockFivestarController mock;
    @Autowired LlmRouter router;
    @Autowired JdbcClient jdbc;

    private Client login(String who) {
        Client c = new Client(port);
        c.ok(c.send("POST", "/api/auth/sso/callback", Map.of("accessToken", "tok-" + who), Map.of()));
        return c;
    }

    private static Map<String, String> bearer(String who) { return Map.of("Authorization", "Bearer tok-" + who); }

    @Test
    void modelsAndQuotaComeFromFivestar() {
        Client anonymous = new Client(port);
        JsonNode anonymousMeta = anonymous.ok(anonymous.get("/api/meta"));
        assertThat(anonymousMeta.at("/auth/mode").asText()).isEqualTo("sso");
        assertThat(anonymousMeta.at("/auth/user").isNull()).isTrue();
        assertThat(anonymousMeta.path("hubSkills").isEmpty()).isTrue();
        Client c = login("fs-alice");
        mock.setUsed("fs-alice", 1234);
        assertThat(c.ok(c.get("/api/meta")).path("fivestar").asBoolean()).isTrue();

        JsonNode m = c.ok(c.send("GET", "/api/llm/models", null, bearer("fs-alice")));
        assertThat(m.path("source").asText()).isEqualTo("fivestar");
        // 新会话优先免费模型
        assertThat(m.path("defaultId").asText()).isEqualTo("1:glm-4.5-flash-free");
        List<String> ids = m.path("models").findValuesAsText("id");
        assertThat(ids).containsExactly("auto", "11:deepseek-v4-flash", "11:deepseek-v4-pro", "12:kimi-k2.6", "1:glm-4.5-flash-free");
        assertThat(m.path("models").get(0).path("desc").asText()).contains("DeepSeek V4 Flash");
        assertThat(m.path("models").findValuesAsText("billingType")).contains("PAID", "FREE");

        JsonNode q = c.ok(c.send("GET", "/api/llm/quota", null, bearer("fs-alice")));
        assertThat(q.path("packageName").asText()).isEqualTo("Lite");
        assertThat(q.path("usedTokens").asLong()).isEqualTo(1234);
        assertThat(q.path("limitTokens").asLong()).isEqualTo(1_000_000);
        assertThat(q.path("remainingTokens").asLong()).isEqualTo(1_000_000 - 1234);
        assertThat(q.path("resetAt").asText()).endsWith("Z");

        // 前端没带平台令牌 / 令牌无效
        var noToken = c.get("/api/llm/models");
        assertThat(noToken.status()).isEqualTo(401);
        assertThat(noToken.code()).isEqualTo("PLATFORM_TOKEN_MISSING");
        assertThat(c.send("GET", "/api/llm/quota", null, Map.of("Authorization", "Bearer bad")).code()).isEqualTo("PLATFORM_TOKEN_INVALID");
    }

    @Test
    void tasksUsePersonalRouteAndCountAgainstQuota() {
        // 每次运行一个新账号：测试库跨运行保留，避免撞上次没跑完的任务（每人同时 1 个任务）
        String bob = "fs-bob-" + System.nanoTime();
        Client c = login(bob);
        mock.setUsed(bob, 0);
        MockFivestarController.LAST_HEADERS.clear();
        JsonNode app = c.ok(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "做一个计数器", "mode", "快速",
                "model", "12:kimi-k2.6", "assetIds", List.of())));
        String appId = app.path("id").asText();
        assertThat(c.waitTask(app.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode g = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of()));
        assertThat(c.waitTask(g.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        // 计划（Java 侧）与生成（runner 经网关）都走 fs-bob 的个人路由，模型是应用上选的 Kimi
        List<String> models = jdbc.sql("select distinct u.model from model_usage u join task t on t.id = u.task_id where t.app_id = ?")
                .param(appId).query(String.class).list();
        assertThat(models).containsExactly("xclaw-" + bob + "-moonshot/kimi-k2.6");
        assertThat(MockFivestarController.LAST_HEADERS).isNotEmpty()
                .allSatisfy(h -> assertThat(h).containsEntry("X-User-Id", bob).containsEntry("X-Project-Id", "zhiye"));
        long used = mock.used(bob);
        assertThat(used).isPositive();
        assertThat(c.ok(c.send("GET", "/api/llm/quota", null, bearer(bob))).path("usedTokens").asLong()).isEqualTo(used);

        // 额度用完：付费模型的任务失败为 PROVIDER_QUOTA，提示里有重置时间，进度保留可重试；
        // auto / 免费模型走公共 Key，不受个人额度影响
        mock.setUsed(bob, 1_000_000);
        var polish = c.post("/api/chat/polish", Map.of("content", "做个看板"));
        assertThat(polish.status()).isEqualTo(200);
        JsonNode t = c.ok(c.post("/api/apps/" + appId + "/messages", Map.of("content", "把按钮改成绿色")));
        JsonNode done = c.waitTask(t.path("task").path("id").asText());
        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(done.path("error").path("code").asText()).isEqualTo("PROVIDER_QUOTA");
        assertThat(done.path("error").path("message").asText()).contains("你在五星AI岛的本周模型额度已用完").contains("重置").contains("重试");
    }

    @Test
    void freeModelUsesPublicKeyAndDoesNotConsumeQuota() {
        String dan = "fs-dan-" + System.nanoTime();
        Client c = login(dan);
        mock.setUsed(dan, 0);
        MockFivestarController.LAST_HEADERS.clear();
        JsonNode app = c.ok(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "做一个计数器", "mode", "快速",
                "model", "1:glm-4.5-flash-free", "billingType", "FREE", "assetIds", List.of())));
        String appId = app.path("id").asText();
        assertThat(c.waitTask(app.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode g = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of()));
        assertThat(c.waitTask(g.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        // 免费模型走公共 Key：模型名是 free/…，不扣个人额度
        List<String> models = jdbc.sql("select distinct u.model from model_usage u join task t on t.id = u.task_id where t.app_id = ?")
                .param(appId).query(String.class).list();
        assertThat(models).containsExactly("free/glm-4.5-flash-free");
        assertThat(mock.used(dan)).isZero();
        assertThat(MockFivestarController.LAST_HEADERS).isNotEmpty()
                .allSatisfy(h -> assertThat(h).containsEntry("X-User-Id", dan).containsEntry("X-Project-Id", "zhiye"));
    }

    @Test
    void anonymousWorkspaceCannotUseModels() {
        assertThatThrownBy(() -> router.forWorkspace("ws_nobody_" + System.nanoTime(), LlmRouter.AUTO))
                .isInstanceOf(ApiException.class).hasMessageContaining("先用公司统一账号登录");
    }

    @Test
    void unknownModelIsRejectedByFivestar() {
        assertThatThrownBy(() -> router.forAccount("fs-carol", "11:no-such-model"))
                .isInstanceOf(ApiException.class).hasMessageContaining("模型不存在");
    }
}
