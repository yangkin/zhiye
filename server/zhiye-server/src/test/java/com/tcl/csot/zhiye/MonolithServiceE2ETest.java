package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单体后端应用托管全链路端到端测试（真实运行 Python / Node.js / Go / Java / 前后端一体应用）：
 * - 上传 ZIP → 自动识别 → 构建与启动 → 子域名网关反代（GET/POST）→ SSO 身份头透传与防伪造
 * - 密文环境变量注入 → 独立 PostgreSQL Schema 分配（DATABASE_URL）→ 闲置休眠（Scale-to-Zero）与访问自动唤醒
 * - 代码在线编辑自动重新部署 → 运行日志与 Dockerfile 预览 → 删除自动销毁进程与 Schema
 */
class MonolithServiceE2ETest extends IntegrationTest {
    @Autowired
    JdbcClient jdbc;

    static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (var e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private String siteHost(JsonNode app) {
        return app.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);
    }

    private JsonNode importServiceZip(Client c, String zipName, String appName, Map<String, String> files) throws Exception {
        JsonNode m = c.ok(c.upload("/api/imports/upload", zipName, "application/zip", zip(files), Map.of()));
        assertThat(m.path("deployable").asBoolean()).as(m.toString()).isTrue();
        assertThat(m.path("projectKind").asText()).as(m.toString()).isEqualTo("service");
        JsonNode r = c.ok(c.post("/api/imports", Map.of("uploadId", m.path("uploadId").asText(), "name", appName)));
        JsonNode done = c.waitTask(r.path("task").path("id").asText());
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        return c.ok(c.get("/api/apps/" + r.path("app").path("id").asText()));
    }

    @Test
    void pythonMonolithFullLifecycleEnvDbSleepWakeAndCodeRedeploy() throws Exception {
        Client c = client();
        String pyMain = """
                import json, os, sys
                from http.server import BaseHTTPRequestHandler, HTTPServer

                class Handler(BaseHTTPRequestHandler):
                    def log_message(self, fmt, *args):
                        print(f"[py-svc] {self.command} {self.path}", flush=True)
                    def do_GET(self):
                        if self.path == '/api/info':
                            payload = {
                                "runtime": "python",
                                "version": "v1",
                                "greeting": os.environ.get("APP_GREETING", "default"),
                                "dbUrl": os.environ.get("DATABASE_URL", ""),
                                "dataDir": os.environ.get("DATA_DIR", ""),
                                "appIdHeader": self.headers.get("X-Zhiye-App-Id", ""),
                                "spoofedUser": self.headers.get("X-Zhiye-User-Id", ""),
                            }
                            body = json.dumps(payload).encode("utf-8")
                            self.send_response(200)
                            self.send_header("Content-Type", "application/json")
                            self.send_header("Content-Length", str(len(body)))
                            self.end_headers()
                            self.wfile.write(body)
                            return
                        html = "<!doctype html><html><body><h1>Python Monolith V1</h1></body></html>".encode("utf-8")
                        self.send_response(200)
                        self.send_header("Content-Type", "text/html; charset=utf-8")
                        self.send_header("Content-Length", str(len(html)))
                        self.end_headers()
                        self.wfile.write(html)
                    def do_POST(self):
                        n = int(self.headers.get("Content-Length", "0"))
                        raw = self.rfile.read(n).decode("utf-8") if n > 0 else "{}"
                        out = json.dumps({"echo": json.loads(raw), "method": "POST"}).encode("utf-8")
                        self.send_response(201)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(out)))
                        self.end_headers()
                        self.wfile.write(out)

                port = int(os.environ.get("PORT", "8080"))
                print(f"[py-svc] listening on {port}", flush=True)
                HTTPServer(("127.0.0.1", port), Handler).serve_forever()
                """;

        JsonNode app = importServiceZip(c, "py-app.zip", "Python 良率分析服务", Map.of(
                "py-app/requirements.txt", "# pure stdlib\n",
                "py-app/main.py", pyMain
        ));
        String appId = app.path("id").asText();
        String host = siteHost(app);

        try {
            assertThat(app.path("projectKind").asText()).isEqualTo("service");
            assertThat(app.path("service").path("runtime").asText()).isEqualTo("python");
            assertThat(app.path("service").path("status").asText()).isEqualTo("running");

            // 1. 子域名网关 GET HTML + POST JSON + 防伪造 X-Zhiye-* 头校验
            var home = c.getWithHost("/", host);
            assertThat(home.status()).isEqualTo(200);
            assertThat(home.body()).contains("Python Monolith V1");

            var info = c.send("GET", "/api/info", null, Map.of("Host", host, "X-Zhiye-User-Id", "evil-spoofed"));
            assertThat(info.status()).isEqualTo(200);
            assertThat(info.json().path("runtime").asText()).isEqualTo("python");
            assertThat(info.json().path("appIdHeader").asText()).isEqualTo(appId);
            assertThat(info.json().path("spoofedUser").asText()).as("伪造的 X-Zhiye-User-Id 必须被网关剥离").isEmpty();
            assertThat(info.json().path("dataDir").asText()).isNotBlank();

            var post = c.send("POST", "/api/echo", Map.of("lot", "T11-001", "yield", 98.6), Map.of("Host", host));
            assertThat(post.status()).isEqualTo(201);
            assertThat(post.json().path("echo").path("lot").asText()).isEqualTo("T11-001");

            // 2. 工作台内嵌预览代理 /api/apps/{id}/service/preview/**
            var preview = c.get("/api/apps/" + appId + "/service/preview/api/info");
            assertThat(preview.status()).isEqualTo(200);
            assertThat(preview.json().path("runtime").asText()).isEqualTo("python");

            // 3. 密文环境变量 + 独立 PG Schema + /data PVC 开启与重启生效
            c.ok(c.put("/api/apps/" + appId + "/service/env", Map.of("vars", List.of(
                    Map.of("key", "APP_GREETING", "value", "hello-tcl-csot", "secret", true)
            ))));
            JsonNode svcAfterEnv = c.ok(c.get("/api/apps/" + appId + "/service"));
            assertThat(svcAfterEnv.path("envVars").get(0).path("value").asText()).as("机密变量必须脱敏").isEqualTo("••••••••");

            JsonNode cfg = c.ok(c.patch("/api/apps/" + appId + "/service", Map.of(
                    "pgEnabled", true, "pvcEnabled", true, "pvcSizeGi", 2, "idleSleepMin", 15
            )));
            String pgSchema = cfg.path("pgSchema").asText();
            assertThat(pgSchema).startsWith("zhiye_svc_");
            boolean schemaExists = jdbc.sql("select exists(select 1 from information_schema.schemata where schema_name = ?)")
                    .param(pgSchema).query(Boolean.class).single();
            assertThat(schemaExists).isTrue();

            c.ok(c.post("/api/apps/" + appId + "/service/restart", Map.of()));
            JsonNode info2 = c.getWithHost("/api/info", host).json();
            assertThat(info2.path("greeting").asText()).isEqualTo("hello-tcl-csot");
            assertThat(info2.path("dbUrl").asText()).contains("postgresql://").contains(pgSchema);

            // 4. 闲置休眠（Scale-to-Zero）→ 访问透明自动唤醒
            JsonNode slept = c.ok(c.post("/api/apps/" + appId + "/service/sleep", Map.of()));
            assertThat(slept.path("status").asText()).isEqualTo("sleeping");
            var woken = c.getWithHost("/api/info", host);
            assertThat(woken.status()).isEqualTo(200);
            assertThat(woken.json().path("greeting").asText()).isEqualTo("hello-tcl-csot");
            assertThat(c.ok(c.get("/api/apps/" + appId + "/service")).path("status").asText()).isEqualTo("running");

            // 5. 运行日志 & 自动生成 Dockerfile 预览
            JsonNode logs = c.ok(c.get("/api/apps/" + appId + "/service/logs?tail=100"));
            assertThat(logs.path("runtimeLogs").asText()).contains("[py-svc]");
            assertThat(logs.path("buildLog").asText()).contains("服务启动成功");
            JsonNode df = c.ok(c.get("/api/apps/" + appId + "/service/dockerfile"));
            assertThat(df.path("custom").asBoolean()).isFalse();
            assertThat(df.path("content").asText()).contains("FROM python:").contains("USER 10001");

            // 6. FS-04 预览与线上分离：代码在线编辑 → 仅更新预览槽位 V2，线上公开地址保持 V1；点「发布」后才切流到 V2
            String v1Id = app.path("currentVersion").path("id").asText();
            JsonNode editTask = c.ok(c.post("/api/apps/" + appId + "/code", Map.of(
                    "baseVersionId", v1Id,
                    "files", List.of(Map.of("path", "main.py", "content", pyMain.replace("Python Monolith V1", "Python Monolith V2")))
            )));
            assertThat(c.waitTask(editTask.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
            // 线上仍为 V1，工作台预览已为 V2
            assertThat(c.getWithHost("/", host).body()).contains("Python Monolith V1");
            assertThat(c.get("/api/apps/" + appId + "/service/preview/").body()).contains("Python Monolith V2");
            // 点击「发布」后，线上切流至 V2
            c.ok(c.post("/api/apps/" + appId + "/publish", Map.of()));
            assertThat(c.getWithHost("/", host).body()).contains("Python Monolith V2");

            // 6b. 恢复 V1 → 仅更新预览为 V1，线上保持 V2；再点「发布」切回 V1
            c.ok(c.post("/api/versions/" + v1Id + "/restore", Map.of()));
            assertThat(c.getWithHost("/", host).body()).contains("Python Monolith V2");
            assertThat(c.get("/api/apps/" + appId + "/service/preview/").body()).contains("Python Monolith V1");
            c.ok(c.post("/api/apps/" + appId + "/publish", Map.of()));
            assertThat(c.getWithHost("/", host).body()).contains("Python Monolith V1");

            // 6c. P1-03 手动停止后访客流量绝不唤醒（区别于休眠 sleeping），只有显式启动才恢复
            c.ok(c.post("/api/apps/" + appId + "/service/stop", Map.of()));
            assertThat(c.ok(c.get("/api/apps/" + appId + "/service")).path("status").asText()).isEqualTo("stopped");
            var stoppedVisit = c.getWithHost("/", host);
            assertThat(stoppedVisit.status()).isEqualTo(503);
            assertThat(c.ok(c.get("/api/apps/" + appId + "/service")).path("status").asText()).isEqualTo("stopped");
            c.ok(c.post("/api/apps/" + appId + "/service/start", Map.of()));
            assertThat(c.getWithHost("/", host).status()).isEqualTo(200);

            // 6d. 禁止对单体服务误开启静态云服务 + 校验含中文路径的代理转发 + 校验平台事件日志落库
            var cloudRej = c.post("/api/apps/" + appId + "/cloud/enable", Map.of());
            assertThat(cloudRej.status()).isEqualTo(400);
            assertThat(c.getWithHost("/api/%E6%B5%8B%E8%AF%95%20%E8%B7%AF%E5%BE%84", host).status()).isEqualTo(200);
            long svcEvents = jdbc.sql("select count(*) from platform_event_log where app_id = ? and category = 'service_runtime'")
                    .param(appId).query(Long.class).single();
            assertThat(svcEvents).isGreaterThanOrEqualTo(3);

            // 7. FS-05 软删除进入「最近删除」→ 线上停止（404）但保留 PG Schema 与持久化数据供管理员恢复无损
            assertThat(c.delete("/api/apps/" + appId).status()).isEqualTo(204);
            assertThat(c.getWithHost("/", host).status()).isEqualTo(404);
            boolean schemaPreserved = jdbc.sql("select exists(select 1 from information_schema.schemata where schema_name = ?)")
                    .param(pgSchema).query(Boolean.class).single();
            assertThat(schemaPreserved).isTrue();
            // 清理测试 Schema 避免污染测试库
            jdbc.sql("drop schema if exists " + pgSchema + " cascade").update();
        } finally {
            c.delete("/api/apps/" + appId);
        }
    }

    @Test
    void nodeJsMonolithE2E() throws Exception {
        Client c = client();
        String serverJs = """
                const http = require('http');
                const port = Number(process.env.PORT || 8080);
                const server = http.createServer((req, res) => {
                  if (req.url === '/api/ping') {
                    res.writeHead(200, { 'Content-Type': 'application/json' });
                    res.end(JSON.stringify({ runtime: 'node', ok: true, method: req.method }));
                    return;
                  }
                  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
                  res.end('<h1>Node Monolith Running</h1>');
                });
                server.listen(port, '127.0.0.1', () => console.log('node-svc ready on ' + port));
                """;
        JsonNode app = importServiceZip(c, "node-svc.zip", "Node 工单后端", Map.of(
                "package.json", "{\"name\":\"node-svc\",\"scripts\":{\"start\":\"node server.js\"}}",
                "server.js", serverJs
        ));
        String appId = app.path("id").asText();
        String host = siteHost(app);
        try {
            assertThat(app.path("service").path("runtime").asText()).isEqualTo("node");
            assertThat(c.getWithHost("/", host).body()).contains("Node Monolith Running");
            JsonNode ping = c.getWithHost("/api/ping", host).json();
            assertThat(ping.path("runtime").asText()).isEqualTo("node");
            assertThat(ping.path("ok").asBoolean()).isTrue();
            assertThat(c.ok(c.get("/api/apps/" + appId + "/service/logs")).path("runtimeLogs").asText()).contains("node-svc ready");
        } finally {
            c.delete("/api/apps/" + appId);
        }
    }

    @Test
    void goMonolithE2E() throws Exception {
        Client c = client();
        String mainGo = """
                package main
                import (
                    "fmt"
                    "net/http"
                    "os"
                )
                func main() {
                    port := os.Getenv("PORT")
                    if port == "" { port = "8080" }
                    http.HandleFunc("/api/go", func(w http.ResponseWriter, r *http.Request) {
                        w.Header().Set("Content-Type", "application/json")
                        fmt.Fprintf(w, `{"runtime":"go","app":"%s"}`, r.Header.Get("X-Zhiye-App-Id"))
                    })
                    http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
                        w.Header().Set("Content-Type", "text/html; charset=utf-8")
                        fmt.Fprint(w, "<h1>Go Monolith Live</h1>")
                    })
                    fmt.Println("go-svc listening on", port)
                    http.ListenAndServe("127.0.0.1:"+port, nil)
                }
                """;
        JsonNode app = importServiceZip(c, "go-svc.zip", "Go 采集代理", Map.of(
                "go.mod", "module example.com/gosvc\n\ngo 1.21\n",
                "main.go", mainGo
        ));
        String appId = app.path("id").asText();
        String host = siteHost(app);
        try {
            assertThat(app.path("service").path("runtime").asText()).isEqualTo("go");
            assertThat(c.getWithHost("/", host).body()).contains("Go Monolith Live");
            JsonNode res = c.getWithHost("/api/go", host).json();
            assertThat(res.path("runtime").asText()).isEqualTo("go");
            assertThat(res.path("app").asText()).isEqualTo(appId);
        } finally {
            c.delete("/api/apps/" + appId);
        }
    }

    @Test
    void javaMonolithE2E() throws Exception {
        Client c = client();
        String mainJava = """
                import com.sun.net.httpserver.HttpServer;
                import java.io.OutputStream;
                import java.net.InetSocketAddress;
                import java.nio.charset.StandardCharsets;

                public class Main {
                    public static void main(String[] args) throws Exception {
                        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
                        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
                        s.createContext("/api/java", ex -> {
                            byte[] b = "{\\"runtime\\":\\"java\\",\\"ok\\":true}".getBytes(StandardCharsets.UTF_8);
                            ex.getResponseHeaders().set("Content-Type", "application/json");
                            ex.sendResponseHeaders(200, b.length);
                            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
                        });
                        s.createContext("/", ex -> {
                            byte[] b = "<h1>Java Monolith Ready</h1>".getBytes(StandardCharsets.UTF_8);
                            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                            ex.sendResponseHeaders(200, b.length);
                            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
                        });
                        System.out.println("java-svc started on " + port);
                        s.start();
                    }
                }
                """;
        JsonNode app = importServiceZip(c, "java-svc.zip", "Java 审批引擎", Map.of(
                "Main.java", mainJava
        ));
        String appId = app.path("id").asText();
        String host = siteHost(app);
        try {
            assertThat(app.path("service").path("runtime").asText()).isEqualTo("java");
            assertThat(c.getWithHost("/", host).body()).contains("Java Monolith Ready");
            JsonNode res = c.getWithHost("/api/java", host).json();
            assertThat(res.path("runtime").asText()).isEqualTo("java");
            assertThat(res.path("ok").asBoolean()).isTrue();
        } finally {
            c.delete("/api/apps/" + appId);
        }
    }

    @Test
    void fullStackFrontendPlusBackendSameOriginE2E() throws Exception {
        Client c = client();
        String backendPy = """
                import json, os
                from http.server import BaseHTTPRequestHandler, HTTPServer
                class H(BaseHTTPRequestHandler):
                    def do_GET(self):
                        if self.path == '/api/hello':
                            b = json.dumps({"msg": "from-python-backend"}).encode()
                            self.send_response(200)
                            self.send_header("Content-Type", "application/json")
                            self.send_header("Content-Length", str(len(b)))
                            self.end_headers()
                            self.wfile.write(b)
                            return
                        self.send_response(404)
                        self.end_headers()
                HTTPServer(("127.0.0.1", int(os.environ.get("PORT", "8080"))), H).serve_forever()
                """;
        JsonNode app = importServiceZip(c, "fullstack.zip", "CodeBuddy 前后端一体应用", Map.of(
                "repo/frontend/index.html", "<!doctype html><html><body><h1>Frontend SPA Page</h1></body></html>",
                "repo/backend/requirements.txt", "# stdlib\n",
                "repo/backend/main.py", backendPy
        ));
        String appId = app.path("id").asText();
        String host = siteHost(app);
        try {
            assertThat(app.path("service").path("runtime").asText()).isEqualTo("python");
            assertThat(c.getWithHost("/", host).body()).contains("Frontend SPA Page");
            assertThat(c.getWithHost("/dashboard/detail", host).body()).as("SPA 路由回退").contains("Frontend SPA Page");
            JsonNode apiRes = c.getWithHost("/api/hello", host).json();
            assertThat(apiRes.path("msg").asText()).isEqualTo("from-python-backend");
        } finally {
            c.delete("/api/apps/" + appId);
        }
    }

    @Test
    void messyZipWarningsAndBootCrashRecoveryE2E() throws Exception {
        Client c = client();
        // 1) 上传含敏感 .env、外部 Redis 依赖、前端硬编码 localhost 且三层嵌套套壳的混乱包，验证预检警告完整透出
        String crashUntilEnvPy = """
                import json, os, sys
                if not os.environ.get("REQUIRED_TOKEN"):
                    sys.stderr.write("FATAL: REQUIRED_TOKEN env var is missing\\n")
                    sys.exit(1)
                from http.server import BaseHTTPRequestHandler, HTTPServer
                class H(BaseHTTPRequestHandler):
                    def do_GET(self):
                        b = json.dumps({"ok": True, "token": os.environ["REQUIRED_TOKEN"]}).encode()
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(b)))
                        self.end_headers()
                        self.wfile.write(b)
                HTTPServer(("127.0.0.1", int(os.environ.get("PORT", "8080"))), H).serve_forever()
                """;
        byte[] messyZip = zip(Map.of(
                "outer/client-drop/my-svc/requirements.txt", "# stdlib\n",
                "outer/client-drop/my-svc/docker-compose.yml", "services:\n  redis:\n    image: redis:7\n",
                "outer/client-drop/my-svc/.env", "API_SECRET=should-not-commit\n",
                "outer/client-drop/my-svc/frontend/api.js", "export const u = 'http://localhost:8000/api'\n",
                "outer/client-drop/my-svc/app.py", crashUntilEnvPy,
                "outer/client-drop/my-svc/__pycache__/junk.pyc", "00"
        ));
        JsonNode up = c.ok(c.upload("/api/imports/upload", "messy-svc.zip", "application/zip", messyZip, Map.of()));
        assertThat(up.path("projectKind").asText()).isEqualTo("service");
        List<String> warnCodes = new java.util.ArrayList<>();
        up.path("warnings").forEach(w -> warnCodes.add(w.path("code").asText()));
        assertThat(warnCodes).contains("secret-file", "middleware-deps", "hardcoded-localhost");

        // 2) 首次启动因缺 REQUIRED_TOKEN 崩溃，验证平台优雅失败且保留 V1（不删应用、不炸服务）
        JsonNode confirmed = c.ok(c.post("/api/imports",
                Map.of("uploadId", up.path("uploadId").asText(), "name", "崩溃自愈测试服务")));
        String appId = confirmed.path("app").path("id").asText();
        try {
            JsonNode task = c.waitTask(confirmed.path("task").path("id").asText());
            assertThat(task.path("status").asText()).isEqualTo("SUCCEEDED");
            JsonNode appAfterFail = c.ok(c.get("/api/apps/" + appId));
            assertThat(appAfterFail.path("currentVersion").path("number").asInt()).isEqualTo(1);
            assertThat(appAfterFail.path("service").path("status").asText()).isEqualTo("failed");
            JsonNode svcDetail = c.ok(c.get("/api/apps/" + appId + "/service"));
            assertThat(svcDetail.path("lastError").asText()).contains("REQUIRED_TOKEN");

            // 3) 补齐环境变量 REQUIRED_TOKEN 并重新部署 -> 恢复 running 并正常返回业务 JSON
            c.ok(c.put("/api/apps/" + appId + "/service/env",
                    Map.of("vars", List.of(Map.of("key", "REQUIRED_TOKEN", "value", "recovered-999", "secret", true)))));
            JsonNode svc = c.ok(c.patch("/api/apps/" + appId + "/service",
                    Map.of("buildCmd", "", "startCmd", "python3 app.py", "healthPath", "/")));
            assertThat(svc.path("startCmd").asText()).isEqualTo("python3 app.py");
            JsonNode redeployed = c.ok(c.post("/api/apps/" + appId + "/service/deploy", Map.of()));
            assertThat(redeployed.path("status").asText()).isEqualTo("running");

            // 预览代理立即可访问，发布后线上子域名也可正常返回业务 JSON
            JsonNode previewLive = c.get("/api/apps/" + appId + "/service/preview/").json();
            assertThat(previewLive.path("ok").asBoolean()).isTrue();
            assertThat(previewLive.path("token").asText()).isEqualTo("recovered-999");

            c.ok(c.post("/api/apps/" + appId + "/publish", Map.of()));
            String host = siteHost(c.ok(c.get("/api/apps/" + appId)));
            JsonNode live = c.getWithHost("/", host).json();
            assertThat(live.path("ok").asBoolean()).isTrue();
            assertThat(live.path("token").asText()).isEqualTo("recovered-999");
        } finally {
            c.delete("/api/apps/" + appId);
        }
    }

    @Test
    void conversationalModifyServiceAutoRedeploysAndRollbackRestores() throws Exception {
        Client c = client();
        String py = """
                import json, os
                from http.server import BaseHTTPRequestHandler, HTTPServer
                class H(BaseHTTPRequestHandler):
                    def do_GET(self):
                        b = json.dumps({"version": "v1-greeting"}).encode()
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(b)))
                        self.end_headers()
                        self.wfile.write(b)
                HTTPServer(("127.0.0.1", int(os.environ.get("PORT", "8080"))), H).serve_forever()
                """;
        JsonNode app = importServiceZip(c, "chat-svc.zip", "对话迭代后端服务", Map.of(
                "requirements.txt", "# stdlib\n",
                "app.py", py
        ));
        String appId = app.path("id").asText();
        String v1Id = app.path("currentVersion").path("id").asText();
        String host = siteHost(app);
        try {
            assertThat(c.getWithHost("/", host).json().path("version").asText()).isEqualTo("v1-greeting");

            // 通过对话发送修改指令 -> runner 修改 app.py -> 提交 V2 -> 仅更新预览槽位，线上保持 V1
            JsonNode posted = c.ok(c.post("/api/apps/" + appId + "/messages",
                    Map.of("content", "请把接口返回的字段改成\"v2-from-chat\"")));
            JsonNode done = c.waitTask(posted.path("task").path("id").asText());
            assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");

            JsonNode appV2 = c.ok(c.get("/api/apps/" + appId));
            assertThat(appV2.path("currentVersion").path("number").asInt()).isEqualTo(2);
            assertThat(appV2.path("service").path("status").asText()).isEqualTo("running");
            assertThat(c.getWithHost("/", host).json().path("version").asText()).isEqualTo("v1-greeting");
            assertThat(c.get("/api/apps/" + appId + "/service/preview/").json().path("version").asText()).isEqualTo("v2-from-chat");

            // 点击发布 -> 线上切换为 V2
            c.ok(c.post("/api/apps/" + appId + "/publish", Map.of()));
            assertThat(c.getWithHost("/", host).json().path("version").asText()).isEqualTo("v2-from-chat");

            // 回滚到 V1 -> 预览恢复 v1-greeting，再发布后线上恢复 v1-greeting
            c.ok(c.post("/api/versions/" + v1Id + "/restore", Map.of()));
            assertThat(c.get("/api/apps/" + appId + "/service/preview/").json().path("version").asText()).isEqualTo("v1-greeting");
            c.ok(c.post("/api/apps/" + appId + "/publish", Map.of()));
            assertThat(c.getWithHost("/", host).json().path("version").asText()).isEqualTo("v1-greeting");
        } finally {
            c.delete("/api/apps/" + appId);
        }
    }

    @Test
    void monolithResourceQuotaEnforces1C1GAndWorkspaceLimit() throws Exception {
        Client c = client();
        String py = """
                import json, os
                from http.server import BaseHTTPRequestHandler, HTTPServer
                class H(BaseHTTPRequestHandler):
                    def do_GET(self):
                        b = b'{"ok":true}'
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Content-Length", str(len(b)))
                        self.end_headers()
                        self.wfile.write(b)
                HTTPServer(("127.0.0.1", int(os.environ.get("PORT", "8080"))), H).serve_forever()
                """;
        JsonNode a1 = importServiceZip(c, "q1.zip", "配额测试服务1", Map.of("requirements.txt", "# stdlib\n", "app.py", py));
        JsonNode a2 = importServiceZip(c, "q2.zip", "配额测试服务2", Map.of("requirements.txt", "# stdlib\n", "app.py", py));
        String id1 = a1.path("id").asText();
        String id2 = a2.path("id").asText();
        String id3 = null;
        try {
            // 1. 单应用硬上限 1C 1G（1000 mCPU / 1024 MB）、PVC 上限 5 Gi、禁止设为 0 常驻
            var overMem = c.patch("/api/apps/" + id1 + "/service", Map.of("memoryMb", 2048));
            assertThat(overMem.status()).isEqualTo(400);
            assertThat(overMem.body()).contains("1024 MB");

            var overCpu = c.patch("/api/apps/" + id1 + "/service", Map.of("cpuMillicores", 1500));
            assertThat(overCpu.status()).isEqualTo(400);
            assertThat(overCpu.body()).contains("1000 mCPU");

            var overPvc = c.patch("/api/apps/" + id1 + "/service", Map.of("pvcEnabled", true, "pvcSizeGi", 8));
            assertThat(overPvc.status()).isEqualTo(400);
            assertThat(overPvc.body()).contains("5 Gi");

            var neverSleep = c.patch("/api/apps/" + id1 + "/service", Map.of("idleSleepMin", 0));
            assertThat(neverSleep.status()).isEqualTo(400);

            // 2. 把服务1与服务2都调到 1C 1G（合计 2048 MB = 个人运行内存上限）
            c.ok(c.patch("/api/apps/" + id1 + "/service", Map.of("memoryMb", 1024, "cpuMillicores", 1000)));
            c.ok(c.patch("/api/apps/" + id2 + "/service", Map.of("memoryMb", 1024, "cpuMillicores", 1000)));

            JsonNode detail1 = c.ok(c.get("/api/apps/" + id1 + "/service"));
            assertThat(detail1.path("quota").path("maxMemoryMb").asInt()).isEqualTo(1024);
            assertThat(detail1.path("quota").path("maxCpuMillicores").asInt()).isEqualTo(1000);
            assertThat(detail1.path("quota").path("workspaceRunningMemoryMb").asInt()).isEqualTo(2048);

            // 3. 导入第 3 个服务（默认 512 MB）-> 超过个人 2048 MB 运行上限，首次部署被拒绝（保留 V1，状态 failed，带配额拒绝理由）
            JsonNode up3 = c.ok(c.upload("/api/imports/upload", "q3.zip", "application/zip",
                    zip(Map.of("requirements.txt", "# stdlib\n", "app.py", py)), Map.of()));
            JsonNode conf3 = c.ok(c.post("/api/imports", Map.of("uploadId", up3.path("uploadId").asText(), "name", "配额测试服务3")));
            id3 = conf3.path("app").path("id").asText();
            c.waitTask(conf3.path("task").path("id").asText());

            var blockedDeploy = c.post("/api/apps/" + id3 + "/service/deploy", Map.of());
            assertThat(blockedDeploy.status()).isEqualTo(409);
            assertThat(blockedDeploy.code()).isEqualTo("SERVICE_QUOTA_EXCEEDED");

            // 4. 休眠服务1 释放 1024 MB 后，服务3 立即部署成功
            c.ok(c.post("/api/apps/" + id1 + "/service/sleep", Map.of()));
            JsonNode deployed3 = c.ok(c.post("/api/apps/" + id3 + "/service/deploy", Map.of()));
            assertThat(deployed3.path("status").asText()).isEqualTo("running");
        } finally {
            c.delete("/api/apps/" + id1);
            c.delete("/api/apps/" + id2);
            if (id3 != null) c.delete("/api/apps/" + id3);
        }
    }

    @Test
    void conversationalCreateFullstackAppE2E() throws Exception {
        Client c = client();
        JsonNode created = c.ok(c.post("/api/apps", Map.of(
                "type", "全栈应用",
                "prompt", "帮我创建一个全栈设备点检与异常工单系统，前后端一体，带持久化列表接口",
                "mode", "快速",
                "model", "auto",
                "assetIds", List.of()
        )));
        String appId = created.path("id").asText();
        try {
            assertThat(created.path("projectKind").asText()).isEqualTo("service");
            assertThat(created.path("type").asText()).isEqualTo("全栈应用");
            assertThat(created.path("phase").asText()).isEqualTo("planning");

            // 1. 计划任务完成，仍保持 service 形态
            JsonNode planDone = c.waitTask(created.path("activeTask").path("id").asText());
            assertThat(planDone.path("status").asText()).as(planDone.toString()).isEqualTo("SUCCEEDED");
            JsonNode planned = c.ok(c.get("/api/apps/" + appId));
            assertThat(planned.path("phase").asText()).isEqualTo("plan");
            assertThat(planned.path("projectKind").asText()).isEqualTo("service");

            // 2. 点击「开始生成」→ 自动生成前后端代码、提交 V1、自动启动 Express 服务
            JsonNode genTask = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of()));
            JsonNode genDone = c.waitTask(genTask.path("id").asText(), java.time.Duration.ofMinutes(5));
            assertThat(genDone.path("status").asText()).as(genDone.toString()).isEqualTo("SUCCEEDED");

            JsonNode app = c.ok(c.get("/api/apps/" + appId));
            assertThat(app.path("phase").asText()).isEqualTo("ready");
            assertThat(app.path("projectKind").asText()).isEqualTo("service");
            assertThat(app.path("currentVersion").path("number").asInt()).isEqualTo(1);
            assertThat(app.path("service").path("runtime").asText()).isEqualTo("node");
            assertThat(app.path("service").path("status").asText()).isEqualTo("running");
            assertThat(app.path("service").path("idleSleepMin").asInt()).isEqualTo(2880);

            // 3. 预览代理 /api/apps/{id}/service/preview/* 访问 Express /api/health 与 /api/items CRUD
            var health = c.get("/api/apps/" + appId + "/service/preview/api/health");
            assertThat(health.status()).as(health.body()).isEqualTo(200);
            assertThat(health.json().path("ok").asBoolean()).isTrue();

            var addItem = c.post("/api/apps/" + appId + "/service/preview/api/items", Map.of("title", "T11 曝光机异常点检"));
            assertThat(addItem.status()).as(addItem.body()).isEqualTo(201);
            assertThat(addItem.json().path("title").asText()).isEqualTo("T11 曝光机异常点检");

            var listItems = c.get("/api/apps/" + appId + "/service/preview/api/items");
            assertThat(listItems.status()).isEqualTo(200);
            assertThat(listItems.body()).contains("T11 曝光机异常点检");

            // 4. 发布后通过独立子域名网关访问 SPA 页面与后端接口
            c.ok(c.post("/api/apps/" + appId + "/publish", Map.of(), java.time.Duration.ofMinutes(5)));
            JsonNode pubApp = c.ok(c.get("/api/apps/" + appId));
            String host = siteHost(pubApp);
            var home = c.getWithHost("/", host);
            assertThat(home.status()).isEqualTo(200);
            assertThat(home.body()).contains("<div id=\"root\"></div>").contains("/assets/");
            var missingAsset = c.getWithHost("/assets/does-not-exist.js", host);
            assertThat(missingAsset.status()).isEqualTo(404);
            var liveHealth = c.getWithHost("/api/health", host);
            assertThat(liveHealth.status()).isEqualTo(200);
            assertThat(liveHealth.json().path("ok").asBoolean()).isTrue();
        } finally {
            c.delete("/api/apps/" + appId);
        }
    }

    @Test
    void previewProxyStripsPlatformCredentialsAndSetCookieAndEnforcesTenantDbRole() throws Exception {
        Client c = client();
        String py = """
                import json, os
                from http.server import BaseHTTPRequestHandler, HTTPServer
                class H(BaseHTTPRequestHandler):
                    def do_GET(self):
                        body = json.dumps({
                            "cookie": self.headers.get("Cookie", ""),
                            "auth": self.headers.get("Authorization", ""),
                            "adminHeader": self.headers.get("X-Zhiye-Admin-Token", ""),
                        }).encode()
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json")
                        self.send_header("Set-Cookie", "evil_session=hijack; Path=/")
                        self.send_header("Content-Length", str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)
                HTTPServer(("127.0.0.1", int(os.environ.get("PORT", "8080"))), H).serve_forever()
                """;
        JsonNode app = importServiceZip(c, "sec-proxy.zip", "安全边界校验服务", Map.of(
                "requirements.txt", "# stdlib\n",
                "main.py", py
        ));
        String appId = app.path("id").asText();
        try {
            // FS-02：同域预览代理绝不透传平台 Cookie / Authorization / Admin Token，且剥离上游 Set-Cookie
            var prev = c.send("GET", "/api/apps/" + appId + "/service/preview/api/check", null, Map.of(
                    "Authorization", "Bearer super-secret-platform-token",
                    "X-Zhiye-Admin-Token", "secret-admin-token"
            ));
            assertThat(prev.status()).isEqualTo(200);
            assertThat(prev.json().path("cookie").asText()).isEmpty();
            assertThat(prev.json().path("auth").asText()).isEmpty();
            assertThat(prev.json().path("adminHeader").asText()).isEmpty();
            assertThat(prev.raw().headers().allValues("set-cookie")).isEmpty();

            // FS-01：开启 PG 托管后必须签发独立最小权限角色（绝非平台默认主账号）
            JsonNode cfg = c.ok(c.patch("/api/apps/" + appId + "/service", Map.of("pgEnabled", true)));
            String pgSchema = cfg.path("pgSchema").asText();
            String pgRole = jdbc.sql("select pg_role from app_service where app_id = ?").param(appId).query(String.class).single();
            assertThat(pgRole).isEqualTo(pgSchema + "_u");
            assertThat(pgRole).isNotEqualTo("zhiye");
            jdbc.sql("drop schema if exists " + pgSchema + " cascade").update();
        } finally {
            c.delete("/api/apps/" + appId);
        }
    }
}
