package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.mcp.McpRegistryService;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.service.task.CheckpointService;
import com.tcl.csot.zhiye.service.workspace.QuotaService;
import com.tcl.csot.zhiye.support.IntegrationTest;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 1 Harness V2 集成测试：企业受控 MCP 注册与审计、检查点时间旅行（含 QA 报告）、工作空间任务配额。 */
class AgentHarnessV2Test extends IntegrationTest {
    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Autowired McpRegistryService mcp;
    @Autowired CheckpointService checkpoints;
    @Autowired QuotaService quota;
    @Autowired AppRepository apps;
    @Autowired VersionRepository versions;
    @Autowired GitService git;

    @Test
    void mcpRegistryKnowledgeAndHttpCrudAuditAndZeroSecretLeak(@TempDir Path agentDir) throws Exception {
        // 确认不再预置本地仿真 mock MCP
        var initial = mcp.listAll();
        assertThat(initial).extracting(m -> m.get("serverKey")).doesNotContain("csot-docs-mcp", "csot-api-catalog-mcp");

        // 创建团队知识库（knowledge）并验证真实检索与审计日志
        var kb = mcp.createForWorkspace("ws_harness_v2", Json.M.valueToTree(Map.of(
                "mode", "knowledge",
                "name", "产线 8D 规范知识库",
                "description", "记录 8D 与良率字段规范",
                "knowledgeContent", "【8D 规范】关键字段：lotId、yieldRate、oee；良率低于目标 1.5% 触发 Critical 告警。"
        )));
        String kbId = String.valueOf(kb.get("id"));
        String kbKey = String.valueOf(kb.get("serverKey"));
        try {
            var call = mcp.callTool(kbKey, "search_knowledge", Json.obj().put("query", "8D"), "task_test_1", "app_test_1");
            assertThat(call.get("ok")).isEqualTo(true);
            assertThat(String.valueOf(call.get("content"))).contains("8D").contains("yieldRate");
            assertThat(mcp.recentAudits(10)).anySatisfy(a -> {
                assertThat(a.get("serverKey")).isEqualTo(kbKey);
                assertThat(a.get("toolName")).isEqualTo("search_knowledge");
                assertThat(a.get("ok")).isEqualTo(true);
            });

            // 注册自定义 HTTP MCP（带密钥）并确认写入沙箱的 mcp-servers.json 绝不泄露密钥
            var created = mcp.create(Json.M.valueToTree(Map.of(
                    "serverKey", "test-custom-mcp",
                    "name", "测试自定义 MCP",
                    "transport", "http",
                    "endpointUrl", "https://mcp.example.local/rpc",
                    "secret", "Bearer super-secret-token-xyz",
                    "tools", List.of(Map.of("name", "ping", "description", "健康探测"))
            )), "admin-test");
            String id = String.valueOf(created.get("id"));
            assertThat(created.get("hasSecret")).isEqualTo(true);

            mcp.writeCatalogForAgent(agentDir);
            String catalogJson = Files.readString(agentDir.resolve("mcp-servers.json"));
            assertThat(catalogJson).contains("test-custom-mcp").doesNotContain("super-secret-token-xyz");

            // 停用后禁止调用
            mcp.update(id, Json.obj().put("enabled", false));
            assertThatThrownBy(() -> mcp.callTool("test-custom-mcp", "ping", null, "t1", "a1"))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("已被管理员停用");

            mcp.delete(id);
            assertThat(mcp.listAll()).extracting(m -> m.get("serverKey")).doesNotContain("test-custom-mcp");

            // /internal/mcp/call 拒绝非法任务令牌
            var unauth = client().post("/internal/mcp/call", Map.of("serverKey", kbKey, "tool", "search_knowledge"));
            assertThat(unauth.status()).isEqualTo(401);
        } finally {
            mcp.delete(kbId);
        }
    }

    @Test
    void checkpointRecordQaReportAndTimeTravelRestore() throws Exception {
        var c = client();
        String wsId = c.ok(c.get("/api/workspace")).path("id").asText();
        String appId = Ids.newId("app");
        var initCommit = git.initFromTemplate(appId, "static-template");
        AppRow app = new AppRow(appId, wsId, "检查点测试应用", false, "开发网站", "", "test", "", "", false,
                "standard", "auto", false, "ready", "create", "静态网页", "static", true, "cp" + appId.substring(4, 10),
                List.of(), null, null, null, null, null);
        apps.insert(app);
        var v1 = versions.insert(appId, "初版 V1", "generate", initCommit.commit(), initCommit.tree(), null, null, null, null);
        apps.update(appId, "current_version_id", v1.id());

        JsonNode qaReport = Json.M.valueToTree(Map.of(
                "passed", true,
                "score", 94,
                "summary", "核心交互实测全部通过",
                "checklist", List.of(Map.of("item", "提交表单", "passed", true, "evidence", "browser_test 0 error")),
                "defects", List.of()
        ));
        String cpId = checkpoints.record(appId, wsId, "task_cp_1", v1.id(), "post_task", "V1 · 首次完成",
                v1.gitCommit(), v1.gitTree(), "create table demo(id serial primary key);", qaReport);
        assertThat(cpId).isNotBlank();

        // 通过 HTTP 接口读取检查点列表
        JsonNode listed = c.ok(c.get("/api/apps/" + appId + "/checkpoints"));
        assertThat(listed.isArray()).isTrue();
        assertThat(listed.size()).isGreaterThanOrEqualTo(1);
        assertThat(listed.get(0).path("qaScore").asInt()).isEqualTo(94);
        assertThat(listed.get(0).path("qaPassed").asBoolean()).isTrue();

        // 时间旅行一键回退，创建新版本 V2
        JsonNode restored = c.ok(c.post("/api/apps/" + appId + "/checkpoints/" + cpId + "/restore", Map.of()));
        assertThat(restored.path("ok").asBoolean()).isTrue();
        assertThat(restored.path("versionNumber").asInt()).isEqualTo(2);
        assertThat(restored.path("restoredFrom").asText()).isEqualTo(cpId);
    }

    @Test
    void workspaceDefaultsToOneActiveTaskPerPerson() {
        var c = client();
        String wsId = c.ok(c.get("/api/workspace")).path("id").asText();
        assertThat(quota.effectiveLimits(wsId).maxConcurrentTasks()).isEqualTo(1);
    }
}
