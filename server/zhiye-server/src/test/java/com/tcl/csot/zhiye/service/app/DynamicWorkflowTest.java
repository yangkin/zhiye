package com.tcl.csot.zhiye.service.app;

import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.service.runtime.ServiceRuntimeDetector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("api")
class DynamicWorkflowTest {

    @Autowired GitService git;

    @Test
    void customStacksUseCleanWorkspaceInsteadOfForcedTemplate() throws Exception {
        assertTrue(Catalog.prefersCleanWorkspace("用 Python FastAPI + Vue 做一个设备告警后台"));
        assertTrue(Catalog.prefersCleanWorkspace("用 Go Gin 做一个库存接口服务"));
        assertTrue(Catalog.prefersCleanWorkspace("Spring Boot + React 做一个审批系统"));
        assertTrue(Catalog.prefersCleanWorkspace("不要模板，从零搭建一个文档管理服务"));
        assertFalse(Catalog.prefersCleanWorkspace("用 Vue + Spring Boot 做一个设备台账"));
        assertTrue(Catalog.looksLikeVueSpringBoot("用 Vue + Spring Boot 做一个设备台账"));
        assertFalse(Catalog.looksLikeVueSpringBoot("Spring Boot + React 做一个审批系统"));
        // 回归：login / margin / plugin / origin / begin / trust 不得误匹配 gin 或 rust
        assertFalse(Catalog.prefersCleanWorkspace("帮我写一个 HTML 登录页面，包含 login 表单、margin 布局、plugin 展示、origin 校验和 trust 标志"));
        assertFalse(Catalog.shouldForceFullstack("帮我写一个 HTML 登录页面，包含 login 表单、margin 布局、plugin 展示、origin 校验和 trust 标志"));
        // 回归：多轮对话中助手回复里提到 Spring Boot / FastAPI 或纯 HTML 需求，绝不强行升格成全栈服务托管
        String dialogueBrief = "请根据以下按时间顺序排列的多轮对话整理并创建应用。\n\n对话如下：\n\n"
                + "user（用户）：\n帮我写一个 HTML 展示页\n\n"
                + "assistant（助手）：\n这是一个纯静态页面，不需要 Spring Boot 或 FastAPI 后端，直接带 login 样式即可。";
        assertFalse(Catalog.shouldForceFullstack(dialogueBrief));
        assertFalse(Catalog.shouldForceFullstack("帮我把这份 Word 内容生成一个 HTML 页面：文档介绍了某系统的 Spring Boot 与 FastAPI 架构"));
        assertTrue(Catalog.shouldForceFullstack("用 Python FastAPI + Vue 做一个设备告警后台"));
        assertTrue(Catalog.shouldForceFullstack("用 Go Gin 做一个库存接口服务"));
        assertTrue(Catalog.shouldForceFullstack("用 Vue + Spring Boot 做一个设备台账"));

        String appId = "app_clean_test_" + System.currentTimeMillis();
        try {
            var commit = git.initCleanWorkspace(appId, "自由架构测试");
            assertNotNull(commit.commit());
            var files = git.listFiles(appId, commit.commit());
            assertTrue(files.stream().anyMatch(f -> ".gitignore".equals(f.path())));
            assertTrue(files.stream().anyMatch(f -> "README.md".equals(f.path())));
            assertFalse(files.stream().anyMatch(f -> f.path().equals("server/index.mjs")));
            assertFalse(files.stream().anyMatch(f -> f.path().equals("package.json")));
        } finally {
            git.deleteRepo(appId);
        }
    }

    @Test
    void serviceRuntimeDetectorHandlesArbitraryCustomSubdirs() {
        Map<String, String> files = Map.of(
                "api/requirements.txt", "fastapi\nuvicorn\n",
                "api/main.py", "from fastapi import FastAPI\napp = FastAPI()\n@app.get('/api/health')\ndef h(): return {'ok': True}\n",
                "web/package.json", "{\"dependencies\":{\"vue\":\"^3.5.0\"},\"devDependencies\":{\"vite\":\"^7.0.0\"}}"
        );
        var specOpt = ServiceRuntimeDetector.detect(Set.copyOf(files.keySet()), files::get);
        assertTrue(specOpt.isPresent());
        var spec = specOpt.get();
        assertEquals("python", spec.runtime());
        assertEquals("api", spec.backendDir());
        assertEquals("web", spec.frontendDir());
        assertTrue(spec.framework().contains("FastAPI") && spec.framework().contains("Vue"));
    }
}
