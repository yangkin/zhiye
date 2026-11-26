package com.tcl.csot.zhiye.service.task;

import com.tcl.csot.zhiye.entity.AppRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentAutonomyTest {

    private static AppRow sampleApp(String type, String projectKind) {
        return new AppRow(
                "app_test1", "ws_test1", "测试全栈系统", false, type, "",
                "做一个前后端一体的设备维修工单系统", null, "lavender", false, "适中", "auto",
                false, "ready", "create", "react", projectKind,
                true, "slug-test", List.of(), null,
                null, null, null, null
        );
    }

    @Test
    void serviceGeneratePromptTargetsFullstackAndDoesNotRestrictToSrcOnly() {
        String p = Prompts.generate(sampleApp("全栈应用", "service"), List.of());
        assertTrue(p.contains("不限定固定语言或固定目录模板"), "full-stack prompt must not require a fixed stack or directory layout");
        assertTrue(p.contains("zhiye.yaml"), "full-stack prompt must support custom runtime workflows");
        assertTrue(p.contains("verify_app"), "full-stack prompt must instruct verify_app self-check");
        assertFalse(p.contains("只修改 `src/`、`public/`、`index.html`"), "service prompt must NOT forbid editing server code");
    }

    @Test
    void modifyAndFixPromptsAdaptToProjectKind() {
        String svcMod = Prompts.modify("修复工单列表接口 500", null, "service");
        assertTrue(svcMod.contains("service_runtime_inspect"));
        assertTrue(svcMod.contains("verify_app"));

        String vueMod = Prompts.modify("调整看板筛选", null, "vue");
        assertTrue(vueMod.contains("vue-tsc"));

        String svcFix = Prompts.fix(com.tcl.csot.zhiye.util.Json.M.createArrayNode(), 1, "service");
        assertTrue(svcFix.contains("/api/health"));
        assertTrue(svcFix.contains("verify_app"));
    }

    @Test
    void repoMapInjectsProjectNotesAndPrioritizesServerEntry(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("docs"));
        Files.createDirectories(tmp.resolve("server"));
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("docs/PROJECT_NOTES.md"), "# 工程笔记\n- API 前缀 /api/tickets\n- 数据存储 DATA_DIR/store.json", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("server/index.mjs"), "export function startServer() {}\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("src/App.tsx"), "export default function App() { return null }\n", StandardCharsets.UTF_8);

        String map = RepoMap.build(tmp, "service", List.of(new RepoMap.VersionNote(1, "generate", "初版")));
        assertTrue(map.contains("项目工程笔记（docs/PROJECT_NOTES.md）"));
        assertTrue(map.contains("API 前缀 /api/tickets"));
        int serverPos = map.indexOf("server/index.mjs");
        int srcPos = map.indexOf("src/App.tsx");
        assertTrue(serverPos > 0 && srcPos > 0);
    }
}
