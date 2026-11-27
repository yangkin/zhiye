package com.tcl.csot.zhiye.service.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceKindReconcilerTest {

    @Test
    void downgradesServiceToStaticAndPromotesPublicWhenBackendRemoved(@TempDir Path ws) throws Exception {
        // 复现真实场景：原 fullstack/Node 应用删掉了 server/server.js，仅剩 public/index.html + public/assets/app.js，
        // 即使根目录还残留带 express 依赖的旧 package.json，也能识别为已无真实后端并自动转为 static
        Files.createDirectories(ws.resolve("public/assets"));
        Files.writeString(ws.resolve("public/index.html"), "<!doctype html><html><body><script src=\"./assets/app.js\"></script></body></html>");
        Files.writeString(ws.resolve("public/assets/app.js"), "console.log('pure static report');");
        Files.writeString(ws.resolve("package.json"), """
                {"name":"report","scripts":{"start":"node server/server.js"},"dependencies":{"express":"^4.19.0"}}
                """);

        var d = WorkspaceKindReconciler.inspect(ws, "service");
        assertThat(d.changed()).isTrue();
        assertThat(d.targetKind()).isEqualTo("static");
        assertThat(d.promotePublicDir()).isTrue();

        boolean promoted = WorkspaceKindReconciler.promotePublicToRoot(ws);
        assertThat(promoted).isTrue();
        assertThat(ws.resolve("index.html")).isRegularFile();
        assertThat(ws.resolve("assets/app.js")).isRegularFile();
        assertThat(ws.resolve("public")).doesNotExist();
    }

    @Test
    void downgradesServiceToStaticWhenRootIndexHtmlOnly(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("index.html"), "<!doctype html><html><body>Hello</body></html>");
        Files.writeString(ws.resolve("styles.css"), "body{margin:0}");

        var d = WorkspaceKindReconciler.inspect(ws, "service");
        assertThat(d.changed()).isTrue();
        assertThat(d.targetKind()).isEqualTo("static");
        assertThat(d.promotePublicDir()).isFalse();
    }

    @Test
    void downgradesServiceToViteWhenOnlyFrontendViteRemains(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("src"));
        Files.writeString(ws.resolve("index.html"), "<!doctype html><html><body><div id='root'></div></body></html>");
        Files.writeString(ws.resolve("vite.config.ts"), "export default {}");
        Files.writeString(ws.resolve("src/App.tsx"), "export default function App(){ return null }");
        Files.writeString(ws.resolve("package.json"), """
                {"name":"fe","scripts":{"build":"vite build"},"devDependencies":{"vite":"^5.0.0"}}
                """);

        var d = WorkspaceKindReconciler.inspect(ws, "service");
        assertThat(d.changed()).isTrue();
        assertThat(d.targetKind()).isEqualTo("vite");
        assertThat(d.promotePublicDir()).isFalse();
    }

    @Test
    void keepsServiceWhenRealBackendRemains(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("server"));
        Files.createDirectories(ws.resolve("public"));
        Files.writeString(ws.resolve("public/index.html"), "<html></html>");
        Files.writeString(ws.resolve("server/server.js"), """
                import express from 'express';
                const app = express();
                app.get('/api/health', (_, res) => res.json({ ok: true }));
                app.listen(process.env.PORT || 8080);
                """);
        Files.writeString(ws.resolve("package.json"), """
                {"name":"svc","scripts":{"start":"node server/server.js"},"dependencies":{"express":"^4.19.0"}}
                """);

        var d = WorkspaceKindReconciler.inspect(ws, "service");
        assertThat(d.changed()).isFalse();
        assertThat(d.targetKind()).isEqualTo("service");
    }

    @Test
    void upgradesStaticToServiceWhenRealBackendAdded(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("server"));
        Files.writeString(ws.resolve("index.html"), "<html></html>");
        Files.writeString(ws.resolve("server/index.mjs"), """
                import http from 'node:http';
                const srv = http.createServer((req, res) => res.end('ok'));
                srv.listen(process.env.PORT || 8080);
                """);

        var d = WorkspaceKindReconciler.inspect(ws, "static");
        assertThat(d.changed()).isTrue();
        assertThat(d.targetKind()).isEqualTo("service");
    }

    @Test
    void neverMutatesFrontendTemplateOrSkillOrScript(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("src"));
        Files.writeString(ws.resolve("index.html"), "<html></html>");
        Files.writeString(ws.resolve("vite.config.ts"), "export default {}");
        Files.writeString(ws.resolve("src/App.tsx"), "export default function App(){ return null }");
        Files.writeString(ws.resolve("package.json"), """
                {"name":"tpl","scripts":{"build":"vite build"},"dependencies":{"react":"^18.0.0"},"devDependencies":{"vite":"^5.0.0"}}
                """);

        assertThat(WorkspaceKindReconciler.inspect(ws, "template").changed()).isFalse();
        assertThat(WorkspaceKindReconciler.inspect(ws, "skill").changed()).isFalse();
        assertThat(WorkspaceKindReconciler.inspect(ws, "script").changed()).isFalse();
    }
}
