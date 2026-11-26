package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.help.HelpKnowledgeService;
import com.tcl.csot.zhiye.service.workspace.WorkspaceMemoryService;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态帮助文档与小织知识库（DB 单一事实来源）+ 个人工作空间长期记忆（跨工作空间隔离 + 自动学习）集成测试。
 */
@TestPropertySource(properties = "zhiye.admin.token=test-admin-token")
class HelpKnowledgeAndMemoryTest extends IntegrationTest {
    private static final Map<String, String> ADMIN_HEADERS = Map.of("X-Zhiye-Admin-Token", "test-admin-token");

    @Autowired HelpKnowledgeService knowledgeService;
    @Autowired WorkspaceMemoryService memoryService;

    @Test
    void helpKnowledgeCrudIsSingleSourceOfTruthForDocsAndBot() {
        Client c = client();

        // 1. V37+V50+V51 初始化的官方文档可直接通过公开接口读取，并已完整注入小织系统提示
        JsonNode initialDocs = c.ok(c.get("/api/help/docs")).path("items");
        assertThat(initialDocs.size()).isGreaterThanOrEqualTo(6);
        assertThat(initialDocs.get(0).path("slug").asText()).isEqualTo("workflow");
        assertThat(initialDocs.toString())
                .contains("织页 AI 完整使用手册")
                .contains("平台资源与配额限制总览")
                .contains("闲置自动休眠")
                .contains("企业生态对接、多员工隔离安全与审核发布");
        String botPrompt = knowledgeService.buildBotSystemPrompt();
        assertThat(botPrompt)
                .contains("1024 MB（1G）")
                .contains("2 天")
                .contains("飞书多维表格")
                .contains("Vibe Coding 应用登记表")
                .contains("不会串数据")
                .contains("脚本运行控制台")
                .contains("Excel / CSV");

        // 2. 非管理员访问后台知识库管理接口必须 Fail-Closed 403
        assertThat(c.get("/api/admin/help-knowledge").status()).isEqualTo(403);
        assertThat(c.post("/api/admin/help-knowledge", Map.of("title", "越权")).status()).isEqualTo(403);

        // 3. 管理员新增条目 → 无需重启，左侧帮助文档与小织系统提示立即生效
        JsonNode created = c.ok(c.send("POST", "/api/admin/help-knowledge", Map.of(
                "slug", "t11-special-guide",
                "title", "T11 产线接入规范",
                "category", "guide",
                "summary", "产线内网对接与权限配置速查",
                "contentMd", "T11 产线 MES 接口默认超时 15 秒，请优先使用环境变量 MES_TOKEN 注入凭证。",
                "sortOrder", 99,
                "forDocs", true,
                "forBot", true,
                "enabled", true
        ), ADMIN_HEADERS));
        String id = created.path("id").asText();
        assertThat(id).startsWith("hk_");

        try {
            assertThat(c.ok(c.get("/api/help/docs")).path("items").toString()).contains("T11 产线接入规范");
            assertThat(knowledgeService.buildBotSystemPrompt()).contains("MES_TOKEN");

            // 4. 在线修改：设为仅机器人可见（forDocs=false, forBot=true），并更新正文
            c.ok(c.send("PATCH", "/api/admin/help-knowledge/" + id, Map.of(
                    "contentMd", "T11 产线已升级至 v2 网关，令牌字段改为 MES_V2_KEY。",
                    "forDocs", false,
                    "forBot", true
            ), ADMIN_HEADERS));
            assertThat(c.ok(c.get("/api/help/docs")).path("items").toString()).doesNotContain("T11 产线接入规范");
            assertThat(knowledgeService.buildBotSystemPrompt()).contains("MES_V2_KEY").doesNotContain("MES_TOKEN");

            // 5. 删除条目 → 文档与机器人知识同步移除
            c.ok(c.send("DELETE", "/api/admin/help-knowledge/" + id, null, ADMIN_HEADERS));
            assertThat(knowledgeService.buildBotSystemPrompt()).doesNotContain("MES_V2_KEY");
        } finally {
            c.send("DELETE", "/api/admin/help-knowledge/" + id, null, ADMIN_HEADERS);
        }
    }

    @Test
    void workspaceMemoryIsolationCrudAndAutoLearning() {
        Client c1 = client();
        Client c2 = client();
        String ws1Id = c1.ok(c1.get("/api/workspace")).path("id").asText();
        String ws2Id = c2.ok(c2.get("/api/workspace")).path("id").asText();
        assertThat(ws1Id).isNotEqualTo(ws2Id);

        // 1. 工作空间 1 手动新增偏好记忆
        JsonNode m1 = c1.ok(c1.post("/api/workspace/memories", Map.of(
                "category", "preference",
                "content", "默认使用浅色工业仪表盘风格，主色调采用深青蓝，表格支持导出 Excel"
        )));
        String m1Id = m1.path("id").asText();
        assertThat(m1Id).startsWith("mem_");
        assertThat(m1.path("source").asText()).isEqualTo("manual");
        assertThat(m1.path("enabled").asBoolean()).isTrue();

        // 2. 跨工作空间严格隔离：工作空间 2 看不到也无法篡改/删除工作空间 1 的记忆
        assertThat(c2.ok(c2.get("/api/workspace/memories")).path("items").size()).isEqualTo(0);
        assertThat(c2.patch("/api/workspace/memories/" + m1Id, Map.of("content", "篡改")).status()).isEqualTo(404);
        assertThat(c2.delete("/api/workspace/memories/" + m1Id).status()).isEqualTo(404);

        // 3. 启用状态注入 promptSection；停用后不再注入
        assertThat(memoryService.promptSection(ws1Id)).contains("浅色工业仪表盘风格");
        assertThat(memoryService.promptSection(ws2Id)).isBlank();

        c1.ok(c1.patch("/api/workspace/memories/" + m1Id, Map.of("enabled", false)));
        assertThat(memoryService.promptSection(ws1Id)).isBlank();
        c1.ok(c1.patch("/api/workspace/memories/" + m1Id, Map.of("enabled", true)));
        assertThat(memoryService.promptSection(ws1Id)).contains("浅色工业仪表盘风格");

        // 4. 自动学习开关（默认开启，关闭后停止自动提炼，重新开启恢复）
        assertThat(c1.ok(c1.get("/api/workspace/memories")).path("autoLearnEnabled").asBoolean()).isTrue();
        c1.ok(c1.patch("/api/workspace/memories/settings", Map.of("autoLearnEnabled", false)));
        assertThat(c1.ok(c1.get("/api/workspace/memories")).path("autoLearnEnabled").asBoolean()).isFalse();
        memoryService.learnFromPrompt(ws1Id, "记住：关闭开关时绝不应该记住这条规则。");
        assertThat(c1.ok(c1.get("/api/workspace/memories")).path("items").toString()).doesNotContain("绝不应该记住这条规则");

        c1.ok(c1.patch("/api/workspace/memories/settings", Map.of("autoLearnEnabled", true)));
        assertThat(c1.ok(c1.get("/api/workspace/memories")).path("autoLearnEnabled").asBoolean()).isTrue();

        // 4b. 自动学习：从自然语言需求中提取角色与固定规则，并去重
        memoryService.learnFromPrompt(ws1Id, "我是T11厂Array制程良率工程师，请帮我做一个看板，记住以后所有页面都用中文且不要暗色背景。");
        memoryService.learnFromPrompt(ws1Id, "我是T11厂Array制程良率工程师，再次确认。");
        JsonNode list = c1.ok(c1.get("/api/workspace/memories")).path("items");
        assertThat(list.toString()).contains("T11厂Array制程良率工程师");
        assertThat(list.toString()).contains("所有页面都用中文且不要暗色背景");
        // 去重校验：不会因为重复一句话写入两条完全相同的 auto 记忆
        long roleCount = 0;
        for (JsonNode n : list) {
            if (n.path("content").asText().contains("T11厂Array制程良率工程师")) roleCount++;
        }
        assertThat(roleCount).isEqualTo(1);

        // 5. 一键清空工作空间 1 的记忆
        JsonNode cleared = c1.ok(c1.delete("/api/workspace/memories"));
        assertThat(cleared.path("deleted").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(c1.ok(c1.get("/api/workspace/memories")).path("items").size()).isEqualTo(0);
    }
}
