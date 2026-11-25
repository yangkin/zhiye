package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.service.runtime.LiveWorkspaceService;
import com.tcl.csot.zhiye.support.IntegrationTest;
import com.tcl.csot.zhiye.util.Ids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2 持久化活体开发环境集成测试：生命周期（启动/休眠/重置）、受控终端安全拦截、Snapshot-to-Deploy 缓存复用。 */
class LiveWorkspaceTest extends IntegrationTest {
    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Autowired LiveWorkspaceService live;
    @Autowired AppRepository apps;
    @Autowired VersionRepository versions;
    @Autowired GitService git;

    @Test
    void lifecycleSafeTerminalAndSnapshotToDeploy(@TempDir Path fakeTaskWs) throws Exception {
        var c = client();
        String wsId = c.ok(c.get("/api/workspace")).path("id").asText();
        String appId = Ids.newId("app");
        var initCommit = git.initFromTemplate(appId, "static-template");
        AppRow app = new AppRow(appId, wsId, "活体沙箱测试应用", false, "开发网站", "", "test", "", "", false,
                "standard", "auto", false, "ready", "create", "静态网页", "static", true, "lw" + appId.substring(4, 10),
                List.of(), null, null, null, null, null);
        apps.insert(app);
        var v1 = versions.insert(appId, "初版 V1", "generate", initCommit.commit(), initCommit.tree(), null, null, null, null);
        apps.update(appId, "current_version_id", v1.id());
        app = apps.findById(appId).orElseThrow();

        // 1. 初始状态 & 启动活体工作区
        JsonNode s0 = c.ok(c.get("/api/apps/" + appId + "/live-workspace"));
        assertThat(s0.path("status").asText()).isEqualTo("idle");

        JsonNode s1 = c.ok(c.post("/api/apps/" + appId + "/live-workspace/start", Map.of()));
        assertThat(s1.path("status").asText()).isEqualTo("running");
        assertThat(s1.path("snapshotReady").asBoolean()).isTrue();

        // 2. 受控安全终端：放行合法诊断命令，拦截高危/越权/外网/.env 命令
        JsonNode okExec = c.ok(c.post("/api/apps/" + appId + "/live-workspace/exec", Map.of("command", "ls -la")));
        assertThat(okExec.path("ok").asBoolean()).isTrue();
        assertThat(okExec.path("stdout").asText()).contains("index.html");

        assertThat(c.post("/api/apps/" + appId + "/live-workspace/exec", Map.of("command", "sudo id")).status()).isEqualTo(403);
        assertThat(c.post("/api/apps/" + appId + "/live-workspace/exec", Map.of("command", "cat .env")).status()).isEqualTo(403);
        assertThat(c.post("/api/apps/" + appId + "/live-workspace/exec", Map.of("command", "curl https://example.com")).status()).isEqualTo(403);
        assertThat(c.post("/api/apps/" + appId + "/live-workspace/exec", Map.of("command", "cat ../../etc/passwd")).status()).isEqualTo(403);

        // 3. Snapshot-to-Deploy：模拟任务完成同步 dist 与 node_modules，确认可被部署层热命中
        Files.writeString(fakeTaskWs.resolve("package.json"), "{\"name\":\"demo\",\"dependencies\":{\"dayjs\":\"^1.0.0\"}}");
        Files.createDirectories(fakeTaskWs.resolve("dist"));
        Files.writeString(fakeTaskWs.resolve("dist/index.html"), "<h1>built</h1>");
        Files.createDirectories(fakeTaskWs.resolve("node_modules/dayjs"));
        Files.writeString(fakeTaskWs.resolve("node_modules/dayjs/index.js"), "module.exports = {}");

        live.syncAfterTask(app, fakeTaskWs, v1.gitCommit());
        var snap = live.findWarmSnapshot(appId, null);
        assertThat(snap).isPresent();
        assertThat(snap.get().distDir()).isNotNull();
        assertThat(Files.isRegularFile(snap.get().distDir().resolve("index.html"))).isTrue();
        assertThat(snap.get().nodeModulesDir()).isNotNull();

        // 4. 休眠保留缓存 & 重置
        JsonNode slept = c.ok(c.post("/api/apps/" + appId + "/live-workspace/sleep", Map.of()));
        assertThat(slept.path("status").asText()).isEqualTo("sleeping");
        assertThat(live.findWarmSnapshot(appId, null)).isPresent();

        JsonNode reset = c.ok(c.post("/api/apps/" + appId + "/live-workspace/reset", Map.of()));
        assertThat(reset.path("status").asText()).isEqualTo("sleeping");
    }
}
