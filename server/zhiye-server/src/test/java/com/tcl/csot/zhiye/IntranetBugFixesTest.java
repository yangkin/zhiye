package com.tcl.csot.zhiye;

import com.sun.net.httpserver.HttpServer;
import com.tcl.csot.zhiye.config.AgwClientProperties;
import com.tcl.csot.zhiye.config.SourceProperties;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.service.app.Catalog;
import com.tcl.csot.zhiye.service.cloud.CloudService;
import com.tcl.csot.zhiye.service.runtime.MonolithServiceManager;
import com.tcl.csot.zhiye.service.runtime.ScriptRunnerService;
import com.tcl.csot.zhiye.service.runtime.ServiceDatabaseService;
import com.tcl.csot.zhiye.service.runtime.ServiceRuntimeDetector;
import com.tcl.csot.zhiye.service.source.AgwClientTokenService;
import com.tcl.csot.zhiye.service.source.DataSourceService;
import com.tcl.csot.zhiye.service.task.Prompts;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.LogRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 覆盖内网实测发现的 5 项核心缺陷回归测试：
 * 1) 脚本运行时检测（缺失二进制不抛原始 500）
 * 2) Vue + Java 技术栈识别、强约束提示词与 framework 永不为 null
 * 3) PG 密码彻底脱敏 + 官方 Vue/Spring Boot 模板不误开 PG 托管
 * 4) AGW agw_user 收到 401 invalid request header 时自动清缓存并协商请求头
 */
class IntranetBugFixesTest {

    @Test
    void logRedactorAndDbErrorSanitizerNeverLeakGeneratedPassword() {
        String generatedPass = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
        String rawSql = "create role zhiye_svc_demo_u login password '" + generatedPass
                + "' nosuperuser nocreatedb nocreaterole noinherit";
        String redacted = LogRedactor.redact("StatementCallback; bad SQL grammar [" + rawSql + "]; ERROR: permission denied to create role");
        assertThat(redacted).doesNotContain(generatedPass);
        assertThat(redacted).contains("password '***'");

        SQLException psql = new SQLException("ERROR: permission denied to create role");
        PermissionDeniedDataAccessException springEx = new PermissionDeniedDataAccessException(
                "StatementCallback; bad SQL grammar [" + rawSql + "]", psql);
        String sanitized = ServiceDatabaseService.sanitizeDbError(springEx, generatedPass);
        assertThat(sanitized).doesNotContain(generatedPass);
        assertThat(sanitized).doesNotContain("create role zhiye_svc_demo_u");
        assertThat(sanitized).contains("permission denied to create role");

        // 即使没有底层 SQLException、只有外层包含密码 SQL 的异常，也绝不能泄露明文密码
        RuntimeException rawOnly = new RuntimeException("StatementCallback; bad SQL grammar [" + rawSql + "]; permission denied");
        String sanitizedRaw = ServiceDatabaseService.sanitizeDbError(rawOnly, generatedPass);
        assertThat(sanitizedRaw).doesNotContain(generatedPass);
        assertThat(sanitizedRaw).contains("[REDACTED SQL]");
    }

    @Test
    void vueSpringBootTemplateDoesNotAutoEnableManagedPostgresWhenDataSourceExcluded() {
        Map<String, String> officialTemplate = Map.of(
                "backend/pom.xml", "<dependency><artifactId>postgresql</artifactId></dependency><artifactId>spring-boot-starter-jdbc</artifactId>",
                "backend/src/main/resources/application.properties", """
                        server.port=${PORT:8080}
                        spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
                        """
        );
        assertThat(MonolithServiceManager.needsManagedPostgres(officialTemplate::get)).isFalse();

        Map<String, String> realPgJavaApp = Map.of(
                "backend/pom.xml", "<dependency><artifactId>postgresql</artifactId></dependency>",
                "backend/src/main/resources/application.properties", """
                        server.port=${PORT:8080}
                        spring.datasource.url=${JDBC_DATABASE_URL}
                        """
        );
        assertThat(MonolithServiceManager.needsManagedPostgres(realPgJavaApp::get)).isTrue();

        Map<String, String> nodePgApp = Map.of(
                "backend/package.json", "{\"dependencies\":{\"express\":\"^4.19.0\",\"pg\":\"^8.11.0\"}}"
        );
        assertThat(MonolithServiceManager.needsManagedPostgres(nodePgApp::get)).isTrue();
    }

    @Test
    void runtimeDetectorFindsBackendServerIndexMjsAndNeverReturnsNullFramework() {
        Set<String> files = Set.of(
                "frontend/package.json",
                "frontend/index.html",
                "backend/package.json",
                "backend/server/index.mjs"
        );
        Map<String, String> content = Map.of(
                "frontend/package.json", "{\"dependencies\":{\"vue\":\"^3.5.0\",\"vite\":\"^6.0.0\"}}",
                "backend/package.json", "{\"name\":\"demo-backend\",\"scripts\":{\"start\":\"node server/index.mjs\"},\"dependencies\":{\"express\":\"^4.19.0\"}}",
                "backend/server/index.mjs", "import express from 'express'; const app = express(); app.get('/api/health', (_,r)=>r.json({ok:true})); app.listen(process.env.PORT || 8080);"
        );
        Optional<ServiceRuntimeDetector.Spec> detected = ServiceRuntimeDetector.detect(files, content::get);
        assertThat(detected).isPresent();
        assertThat(detected.get().runtime()).isEqualTo("node");
        assertThat(detected.get().framework()).isEqualTo("Node.js (Express) + Vue");
        assertThat(detected.get().backendDir()).isEqualTo("backend");
        assertThat(detected.get().frontendDir()).isEqualTo("frontend");
        assertThat(detected.get().healthPath()).isEqualTo("/api/health");

        // 显式传 null framework 时 Spec 构造器自动兜底为非空值，杜绝 app_service.framework NOT NULL 违约
        ServiceRuntimeDetector.Spec fallback = new ServiceRuntimeDetector.Spec("node", null, null, null, null, "npm start", 8080, null, 512, 500);
        assertThat(fallback.framework()).isNotBlank().isEqualTo("Node.js 服务端");
        assertThat(fallback.healthPath()).isEqualTo("/");
    }

    @Test
    void vueJavaDetectionAndPromptMandate() {
        assertThat(Catalog.looksLikeVueSpringBoot("请用前端 Vue 3，后端 Java Spring Boot 做一个设备工单平台")).isTrue();
        assertThat(Catalog.prefersCleanWorkspace("请用前端 Vue 3，后端 Java Spring Boot 做一个设备工单平台")).isFalse();
        assertThat(Catalog.looksLikeVueSpringBoot("前端用 Vue 3，后端用 Java 实现良率追踪（不要 React）")).isTrue();
        assertThat(Catalog.prefersCleanWorkspace("前端用 Vue 3，后端用 Java 实现良率追踪（不要 React）")).isFalse();
        assertThat(Catalog.looksLikeVueSpringBoot("Spring Boot + React 做一个审批系统")).isFalse();
        assertThat(Catalog.prefersCleanWorkspace("Spring Boot + React 做一个审批系统")).isTrue();

        AppRow app = new AppRow(
                "app_test", "ws_1", "工单平台", true, "全栈应用", "工单",
                "前端用 Vue 3，后端用 Java Spring Boot 做一个设备工单平台",
                "", "lavender", false, "适中", "auto", false,
                "generating", "create", "Java (Spring Boot) + Vue", "service", true,
                "demo-slug", List.of(), null, null, null, null, null
        );
        String prompt = Prompts.generate(app, List.of());
        assertThat(prompt).contains("Vue 3 + Java Spring Boot");
        assertThat(prompt).contains("严禁偷换后端技术栈").doesNotContain("仅作为可选起点");
        assertThat(prompt).contains("backend/pom.xml");
    }

    @Test
    void scriptRunnerFindExecutableReturnsNullWhenMissing() {
        String missing = ScriptRunnerService.findExecutable(
                List.of("/non-existent-zhiye-dir/definitely-missing-bin"),
                List.of("definitely-missing-zhiye-bin-xyz"));
        assertThat(missing).isNull();
    }

    @Test
    void agwUser401NegotiatesBearerHeaderAndCachesWinningMode() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agw-api/demo/query", exchange -> {
            attempts.incrementAndGet();
            String authz = exchange.getRequestHeaders().getFirst("Authorization");
            String proj = exchange.getRequestHeaders().getFirst("X-Project-Id");
            if ("Bearer fresh-metis-token".equals(authz) && "proj-pub-1".equals(proj)) {
                byte[] ok = "{\"code\":0,\"data\":{\"items\":[1,2,3]}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(ok); }
            } else {
                byte[] err = "invalid request header".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(401, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
            when(jdbc.sql(contains("select u.login_name")).param(anyString()).query(String.class).optional())
                    .thenReturn(Optional.of("yangkai37"));
            when(jdbc.sql(contains("insert into data_source_call")).params(any(Object[].class)).update()).thenReturn(1);

            AgwClientTokenService tokens = mock(AgwClientTokenService.class);
            when(tokens.dataAuthMode()).thenReturn("auto");
            when(tokens.metisAccessTokenForUser("yangkai37"))
                    .thenReturn("stale-metis-token", "fresh-metis-token", "fresh-metis-token", "fresh-metis-token");
            when(tokens.authorizationForUser("yangkai37")).thenReturn("agw-jwt-token");

            SourceProperties sp = new SourceProperties(List.of(), true, 5, 2 * 1024 * 1024, null);
            DataSourceService svc = new DataSourceService(jdbc, mock(AppRepository.class), mock(CloudService.class), sp, tokens);
            DataSourceService.Source src = new DataSourceService.Source(
                    "src_1", "ws_1", "agw_demo", "Demo API", "", "GET",
                    "http://127.0.0.1:" + port + "/agw-api/demo/query",
                    List.of(), null, "agw_user", "proj-pub-1", null, null, null, "agw", "proj:api1");

            // 第一次调用：首选 Authentication 返回 401 -> 清缓存重刷 -> 自动协商 Authorization: Bearer + X-Project-Id 成功
            Map<String, Object> first = svc.call(src, Json.obj(), null, "test");
            assertThat(first.get("status")).isEqualTo(200);
            verify(tokens, times(1)).invalidateUser("yangkai37");
            int countAfterFirst = attempts.get();
            assertThat(countAfterFirst).isGreaterThanOrEqualTo(2);

            // 第二次调用：已缓存该网关主机协商成功的模式，1 次 HTTP 请求直接成功！
            Map<String, Object> second = svc.call(src, Json.obj(), null, "test");
            assertThat(second.get("status")).isEqualTo(200);
            assertThat(attempts.get()).isEqualTo(countAfterFirst + 1);
        } finally {
            server.stop(0);
        }
    }
}
