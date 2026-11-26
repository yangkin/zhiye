package com.tcl.csot.zhiye.service.connector;

import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.app.Catalog;
import com.tcl.csot.zhiye.service.mcp.McpRegistryService;
import com.tcl.csot.zhiye.service.runtime.ServiceRuntimeDetector;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("api")
class PolyglotAndEcosystemClosingTest {

    @Autowired EnterpriseConnectorService connectors;
    @Autowired McpRegistryService mcp;
    @Autowired ZhiyeProperties props;

    @Test
    void workspaceSelfServiceConnectorAndMcpListingWorkSafely() {
        String ws = "ws_self_service_test";
        var body = Json.obj()
                .put("name", "我的产线多维表格")
                .put("kind", "feishu_bitable")
                .put("bitableUrl", "https://demo.feishu.cn/base/bascnAuto999?table=tblAuto888&view=vew1");
        var saved = connectors.upsertForWorkspace(ws, body);
        String id = String.valueOf(saved.get("id"));
        try {
            assertEquals(ws, saved.get("workspaceId"));
            var cfg = (com.fasterxml.jackson.databind.JsonNode) saved.get("config");
            assertEquals("bascnAuto999", cfg.path("appToken").asText());
            assertEquals("tblAuto888", cfg.path("tableId").asText());

            // 其他工作空间或平台全局连接器不能被普通工作空间删除
            assertThrows(ApiException.class, () -> connectors.deleteForWorkspace("ws_other", id));
            assertThrows(ApiException.class, () -> connectors.deleteForWorkspace(ws, "conn_feishu_bot_sandbox"));

            // 普通用户可见脱敏 MCP 列表（不含密钥/内部地址）
            var mcps = mcp.listEnabledForWorkspace();
            assertNotNull(mcps);
            for (var m : mcps) {
                assertFalse(m.containsKey("secretEnc"));
                assertFalse(m.containsKey("authHeader"));
            }
        } finally {
            connectors.deleteForWorkspace(ws, id);
        }
    }

    @Test
    void appConnectorTokenAndPromptGuideWorkEndToEnd() throws Exception {
        String tok = connectors.issueAppConnectorToken("app_demo_123");
        assertTrue(tok.startsWith("zct1.app_demo_123."));
        assertEquals("app_demo_123", connectors.verifyAppConnectorToken("Bearer " + tok));
        assertThrows(ApiException.class, () -> connectors.verifyAppConnectorToken("Bearer zct1.app_demo_123.tampered"));

        Path tmp = Files.createTempDirectory("zhiye-conn-agent-");
        try {
            connectors.writeConnectorsCatalogForAgent(tmp, null);
            String json = Files.readString(tmp.resolve("connectors.json"), StandardCharsets.UTF_8);
            assertTrue(json.contains("conn_feishu_bot_sandbox"));
            assertTrue(json.contains("conn_feishu_bitable_sandbox"));
        } finally {
            com.tcl.csot.zhiye.service.repo.GitService.deleteRecursively(tmp);
        }

        String svcGuide = connectors.promptGuideForAgent(null, true, false);
        assertTrue(svcGuide.contains("ZHIYE_CONNECTOR_API"));
        assertTrue(svcGuide.contains("conn_feishu_bot_sandbox"));
        String cloudGuide = connectors.promptGuideForAgent(null, false, true);
        assertTrue(cloudGuide.contains("ctx.feishu.sendCard"));
        assertTrue(cloudGuide.contains("ctx.bitable.create"));
    }

    @Test
    void vueSpringBootTemplateIsDetectedCleanly() throws Exception {
        assertTrue(Catalog.looksLikeVueSpringBoot("用 Vue + Spring Boot 做一个产线备件审批系统"));
        assertTrue(Catalog.looksLikeVueSpringBoot("SpringBoot + Vue3 全栈设备台账"));
        assertFalse(Catalog.looksLikeVueSpringBoot("做一个 React + Node 看板"));

        Path tpl = props.rootPath().resolve("packages/vue-springboot-template");
        assertTrue(Files.isRegularFile(tpl.resolve("backend/pom.xml")));
        assertTrue(Files.isRegularFile(tpl.resolve("frontend/package.json")));

        Set<String> relFiles = new LinkedHashSet<>();
        try (var stream = Files.walk(tpl)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = tpl.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith("backend/target/") && !rel.startsWith("frontend/node_modules/") && !rel.startsWith("frontend/dist/")) {
                    relFiles.add(rel);
                }
            });
        }
        var specOpt = ServiceRuntimeDetector.detect(relFiles, rel -> {
            try { return Files.readString(tpl.resolve(rel), StandardCharsets.UTF_8); } catch (Exception e) { return null; }
        });
        assertTrue(specOpt.isPresent());
        var spec = specOpt.get();
        assertEquals("java", spec.runtime());
        assertEquals("frontend", spec.frontendDir());
        assertEquals("backend", spec.backendDir());
        assertTrue(spec.framework().contains("Spring Boot") && spec.framework().contains("Vue"));
    }
}
