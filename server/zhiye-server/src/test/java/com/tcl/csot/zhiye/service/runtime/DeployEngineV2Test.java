package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.config.ServiceRuntimeProperties;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.service.workspace.WorkspaceService;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("api")
class DeployEngineV2Test {

    @Autowired MonolithServiceManager manager;
    @Autowired DeployOpsService ops;
    @Autowired DeployDiagnosisService diagnosisService;
    @Autowired WorkspaceService workspaces;
    @Autowired AppRepository apps;
    @Autowired VersionRepository versions;
    @Autowired GitService git;
    @Autowired JdbcClient jdbc;

    private String createdAppId;
    private String createdWsId;

    @AfterEach
    void cleanup() {
        if (createdAppId != null) {
            apps.findById(createdAppId).ifPresent(a -> manager.destroyForApp(a, true));
            git.deleteRepo(createdAppId);
            jdbc.sql("update app set current_version_id = null, published_version_id = null where id = ?").param(createdAppId).update();
            jdbc.sql("delete from message where app_id = ?").param(createdAppId).update();
            jdbc.sql("delete from task where app_id = ?").param(createdAppId).update();
            jdbc.sql("delete from version where app_id = ?").param(createdAppId).update();
            jdbc.sql("delete from app where id = ?").param(createdAppId).update();
            createdAppId = null;
        }
        if (createdWsId != null) {
            jdbc.sql("delete from workspace where id = ?").param(createdWsId).update();
            createdWsId = null;
        }
    }

    @Test
    void layeredDockerfilesAndKanikoCache() {
        ServiceRuntimeProperties props = new ServiceRuntimeProperties(
                "k8s", "zhiye-apps", "harbor.csot.local/zhiye", "harbor-sec",
                null, null, null, null, null, null, null, null,
                "https://npm.csot.local", "https://pypi.csot.local/simple",
                "https://mvn.csot.local", "https://goproxy.csot.local",
                null, true, null, null, null, List.of(),
                2880, 60, 30, 600, 500, 1024, 1000, 5, 5, false, 5, 2048, 2000, 131072, 64000,
                "zhiye-builds", "zhiye-runtime"
        );

        String nodeDf = DockerfileGenerator.generate(
                new ServiceRuntimeDetector.Spec("node", "Express", "web", "server", "npm run build", "node index.js", 8080, "/health", 512, 500),
                props);
        assertTrue(nodeDf.indexOf("COPY web/package*.json ./") < nodeDf.indexOf("COPY web/ ./"));
        assertTrue(nodeDf.indexOf("COPY server/package*.json ./") < nodeDf.indexOf("COPY server/ ./"));
        assertTrue(nodeDf.contains("USER 10001"));

        String pyDf = DockerfileGenerator.generate(
                new ServiceRuntimeDetector.Spec("python", "FastAPI", "", "", "", "uvicorn main:app", 8000, "/health", 512, 500),
                props);
        assertTrue(pyDf.indexOf("COPY requirements*.txt pyproject.toml* ./") < pyDf.indexOf("COPY . ./"));

        String javaDf = DockerfileGenerator.generate(
                new ServiceRuntimeDetector.Spec("java", "Spring Boot", "", "", "", "", 8080, "/actuator/health", 1024, 1000),
                props);
        assertTrue(javaDf.contains("mvn -B dependency:go-offline"));
        assertTrue(javaDf.indexOf("COPY pom.xml*") < javaDf.indexOf("COPY . ./"));

        String goDf = DockerfileGenerator.generate(
                new ServiceRuntimeDetector.Spec("go", "Go HTTP", "", "", "", "", 8080, "/health", 256, 500),
                props);
        assertTrue(goDf.contains("go mod download"));
        assertTrue(goDf.indexOf("COPY go.mod*") < goDf.indexOf("COPY . ./"));

        ServiceRuntimeProvider.DeploySpec spec = new ServiceRuntimeProvider.DeploySpec(
                "app_1", "demo", "v1", "node", "Express", "", "", "", "node server.js",
                8080, "/health", 512, 500, false, 1, null, nodeDf, Map.of());
        Job kaniko = K8sServiceResourceFactory.buildKanikoJob(props, spec, "zhiye-svc-demo-bld-1", "harbor.csot.local/zhiye/app:v1", "dir:///workspace");
        List<String> args = kaniko.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs();
        assertTrue(args.contains("--cache=true"));
        assertTrue(args.contains("--cache-copy-layers=true"));
        assertTrue(args.contains("--cache-repo=harbor.csot.local/zhiye/zhiye-build-cache"));
    }

    @Test
    void failureDiagnosisClassifiesRootCauses() {
        assertEquals("OOM_KILLED", diagnosisService.diagnose("FATAL ERROR: JavaScript heap out of memory (exit=137)", "", "", "node", 8080, "/").category());
        var modDiag = diagnosisService.diagnose("Error: Cannot find module 'pg'", "", "", "node", 8080, "/");
        assertEquals("MISSING_DEPENDENCY", modDiag.category());
        assertTrue(modDiag.missingModules().contains("pg"));
        assertEquals("PERMISSION_DENIED_NONROOT", diagnosisService.diagnose("EACCES: permission denied, open '/app/data.db'", "", "", "node", 8080, "/").category());
        assertEquals("ENV_MISSING", diagnosisService.diagnose("connect ECONNREFUSED 127.0.0.1:5432 DATABASE_URL missing", "", "", "node", 8080, "/").category());
        assertEquals("BUILD_SYNTAX_ERROR", diagnosisService.diagnose("SyntaxError: Unexpected token '}'", "", "", "node", 8080, "/").category());
        assertEquals("PORT_BIND_ERROR", diagnosisService.diagnose("服务启动失败或监听端口超时（PORT=50123）", "", "", "node", 8080, "/health").category());
    }

    @Test
    void endToEndPromoteRollbackMetricsTopologyCicdAndBundleExport() throws Exception {
        var ws = workspaces.create();
        createdWsId = ws.id();
        String appId = Ids.newId("app");
        createdAppId = appId;
        String slug = "deployv2-" + appId.substring(appId.length() - 6);
        apps.insert(new AppRow(
                appId, ws.id(), "DeployV2 Test App", false, "web", "", "", "", "", false, "agent", "default",
                false, "ready", "generate", "Node.js", "service", true, slug, List.of(), null, null, null, null, null
        ));

        VersionRow v1 = commitNodeVersion(appId, 1, "v1-hello");
        jdbc.sql("update app set current_version_id = ? where id = ?").params(v1.id(), appId).update();
        AppRow app = apps.findById(appId).orElseThrow();

        // 1. 预览槽位部署 → 产生 preview release + 不可变产物快照
        var prevSvc = manager.deployPreview(app, v1);
        assertEquals("running", prevSvc.previewStatus());
        assertNotNull(prevSvc.previewEndpointUrl());
        assertEquals("v1-hello", httpGet(prevSvc.previewEndpointUrl() + "/api/version"));

        // 2. 一键 0 重建晋升到线上槽位
        Map<String, Object> promoted = manager.promotePreview(ws.id(), appId);
        assertEquals("running", promoted.get("status"));
        assertEquals(v1.id(), promoted.get("runningVersionId"));
        var liveAfterPromote = manager.find(appId).orElseThrow();
        assertEquals("v1-hello", httpGet(liveAfterPromote.endpointUrl() + "/api/version"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relsAfterPromote = (List<Map<String, Object>>) promoted.get("releases");
        assertTrue(relsAfterPromote.stream().anyMatch(r -> r.get("promotedFromReleaseId") != null));

        String v1LiveReleaseId = relsAfterPromote.stream()
                .filter(r -> "live".equals(r.get("slot")) && "succeeded".equals(r.get("status")) && v1.id().equals(r.get("versionId")))
                .map(r -> String.valueOf(r.get("id"))).findFirst().orElseThrow();

        // 3. 提交 V2 并蓝绿零停机重新部署到线上
        VersionRow v2 = commitNodeVersion(appId, 2, "v2-bluegreen");
        jdbc.sql("update app set current_version_id = ? where id = ?").params(v2.id(), appId).update();
        manager.redeploy(ws.id(), appId, v2.id());
        var liveV2 = manager.find(appId).orElseThrow();
        assertEquals("v2-bluegreen", httpGet(liveV2.endpointUrl() + "/api/version"));

        // 4. 秒级回滚回 V1 发布记录（0 重建零停机切流）
        Map<String, Object> rolledBack = manager.rollbackToRelease(ws.id(), appId, v1LiveReleaseId);
        assertEquals(v1.id(), rolledBack.get("runningVersionId"));
        var liveRolledBack = manager.find(appId).orElseThrow();
        assertEquals("v1-hello", httpGet(liveRolledBack.endpointUrl() + "/api/version"));

        // 5. 实时运行指标验证
        Map<String, Object> metrics = manager.metricsView(ws.id(), appId);
        assertEquals(true, metrics.get("running"));
        assertNotNull(metrics.get("pid"));
        assertNotNull(metrics.get("port"));

        // 6. 声明式多服务拓扑 zhiye.yaml 验证
        Map<String, Object> defTopo = ops.getTopology(ws.id(), appId);
        assertFalse((Boolean) defTopo.get("customized"));
        String customYaml = """
                version: "2"
                app: %s
                services:
                  api:
                    type: api
                    command: "node server.js"
                    port: 8080
                    replicas: 2
                  worker:
                    type: worker
                    command: "node worker.js"
                    replicas: 1
                  nightly:
                    type: cron
                    schedule: "0 2 * * *"
                    command: "node report.js"
                """.formatted(slug);
        Map<String, Object> savedTopo = ops.saveTopology(ws.id(), appId, customYaml);
        assertEquals(true, savedTopo.get("customized"));
        assertEquals(3, ((List<?>) savedTopo.get("services")).size());
        assertThrows(ApiException.class, () -> ops.saveTopology(ws.id(), appId, "services:\n  bad:\n    type: invalid_type\n"));

        // 7. Git CI/CD 配置 + Webhook 严格验签 + 自动部署触发
        Map<String, Object> savedCicd = ops.saveGitCicd(ws.id(), appId, Json.read("""
                {"provider":"gitlab","repoUrl":"https://gitlab.example.local/team/demo.git","branch":"main","deploySlot":"preview","autoDeploy":true,"webhookSecret":"test-gitlab-secret-999"}
                """));
        assertEquals(true, savedCicd.get("configured"));
        assertThrows(ApiException.class, () -> ops.handleGitWebhook(appId, "wrong-token", null, "{}", Json.obj()));
        Map<String, Object> hookRes = ops.handleGitWebhook(appId, "test-gitlab-secret-999", null,
                "{\"ref\":\"refs/heads/main\",\"after\":\"abc123456789\",\"commits\":[{\"message\":\"feat: webhook deploy\"}]}",
                Json.read("{\"ref\":\"refs/heads/main\",\"after\":\"abc123456789\",\"commits\":[{\"message\":\"feat: webhook deploy\"}]}"));
        assertEquals(true, hookRes.get("accepted"));
        assertEquals(false, hookRes.get("skipped"));
        assertEquals("abc123456789", hookRes.get("commitSha"));

        // 8. 导出标准 Docker Compose + Helm Chart ZIP 包
        byte[] zipBytes = ops.exportDeployBundle(ws.id(), appId);
        assertTrue(zipBytes.length > 500);
        Set<String> entryNames = new LinkedHashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) entryNames.add(ze.getName());
        }
        assertTrue(entryNames.contains("Dockerfile"));
        assertTrue(entryNames.contains("docker-compose.yml"));
        assertTrue(entryNames.contains(".env.example"));
        assertTrue(entryNames.contains("zhiye.yaml"));
        assertTrue(entryNames.contains("helm/Chart.yaml"));
        assertTrue(entryNames.contains("helm/values.yaml"));
        assertTrue(entryNames.contains("helm/templates/deployment.yaml"));
        assertTrue(entryNames.contains("helm/templates/service.yaml"));
        assertTrue(entryNames.contains("helm/templates/networkpolicy.yaml"));

        // 9. AI 一键自愈修复任务创建
        Map<String, Object> heal = ops.triggerAutoHeal(ws.id(), appId);
        assertNotNull(heal.get("taskId"));
        jdbc.sql("update task set status = 'CANCELLED' where id = ?").param(heal.get("taskId")).update();
    }

    private VersionRow commitNodeVersion(String appId, int num, String marker) throws Exception {
        Path tmp = Files.createTempDirectory("zhiye-test-node-");
        try {
            if (num == 1) git.initEmpty(appId);
            git.checkout(appId, null, tmp, "service");
            Files.writeString(tmp.resolve("package.json"), """
                    {
                      "name": "deploy-v2-demo",
                      "version": "1.0.0",
                      "scripts": { "start": "node server.js" }
                    }
                    """, StandardCharsets.UTF_8);
            Files.writeString(tmp.resolve("server.js"), """
                    const http = require('http');
                    const port = Number(process.env.PORT || 8080);
                    const server = http.createServer((req, res) => {
                      if (req.url === '/api/version') {
                        res.writeHead(200, { 'Content-Type': 'text/plain' });
                        res.end('%s');
                        return;
                      }
                      res.writeHead(200, { 'Content-Type': 'application/json' });
                      res.end(JSON.stringify({ ok: true, version: '%s' }));
                    });
                    server.listen(port, '127.0.0.1');
                    """.formatted(marker, marker), StandardCharsets.UTF_8);
            var cr = git.commitWorkspace(appId, tmp, "commit " + marker, "test-" + num);
            return versions.insert(appId, "V" + num + " " + marker, "generate", cr.commit(), cr.tree(), null, null, null, null);
        } finally {
            GitService.deleteRecursively(tmp);
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Expected 200 from " + url);
        return resp.body();
    }
}
