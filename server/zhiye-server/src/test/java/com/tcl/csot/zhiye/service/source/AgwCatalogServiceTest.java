package com.tcl.csot.zhiye.service.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.tcl.csot.zhiye.config.AgwClientProperties;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgwCatalogServiceTest {
    @Test
    void bodyExamplesStayValidJson() {
        assertThat(AgwCatalogService.jsonBodyExample("{\n  \"appId\": \"app-1\"\n}")).isEqualTo("{\"appId\":\"app-1\"}");
        assertThat(AgwCatalogService.jsonBodyExample("{\"appId\":\"" + "x".repeat(300) + "\"}")).isEmpty();
        assertThat(AgwCatalogService.jsonBodyExample("{\"appId\":\"broken")).isEmpty();
    }

    @Test
    void addsOnlyCallableApiWithNormalizedContract() throws IOException {
        AtomicReference<JsonNode> imported = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/agw-admin/apiInfo/me/callable-apis", exchange -> respond(exchange, """
                {"code":"ok","success":true,"data":[{"projectCode":"proj","projectName":"查询项目","projectPublicId":"public-1","apiId":"api-1","apiName":"账号校验","apiCode":"account-check","apiMethod":"POST","apiPubUrl":"/agw-api/account-check","grantTier":"project","matchedBy":"PROJECT_GRANT"}]}
                """));
        server.createContext("/api/agw-admin/apiInfo/api-1", exchange -> respond(exchange, """
                {"code":"ok","success":true,"data":{"apiName":"账号校验","apiCode":"account-check","apiMethod":"POST","apiPubUrl":"/agw-api/account-check","reqBodyExample":"[]","resReturnExample":"{}","restfulParams":[{"inParamName":"top","inPosition":"QUERY","bgDataType":"int","required":0}]}}
                """));
        server.start();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            AgwClientProperties props = new AgwClientProperties("client", "secret", root + "/oauth/token", root + "/api/agw-admin", null);
            AgwClientTokenService tokens = mock(AgwClientTokenService.class);
            when(tokens.authorizationForUser("yangkai37")).thenReturn("agw-jwt");
            JdbcClient jdbc = mock(JdbcClient.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
            when(jdbc.sql(any()).param("ws").query(String.class).optional()).thenReturn(Optional.of("yangkai37"));
            DataSourceService sources = mock(DataSourceService.class);
            when(sources.importExternal(eq("ws"), eq("agw"), eq("proj:api-1"), any())).thenAnswer(invocation -> {
                imported.set(invocation.getArgument(3));
                return Map.of("id", "src-1");
            });
            AgwCatalogService catalog = new AgwCatalogService(props, tokens, sources, jdbc);
            assertThat(catalog.callableApis("ws")).hasSize(1);
            assertThat(catalog.addApi("ws", "proj", "api-1")).containsEntry("id", "src-1");
            JsonNode body = imported.get();
            assertThat(body.path("url").asText()).isEqualTo(root + "/agw-api/account-check");
            assertThat(body.path("authType").asText()).isEqualTo("agw_user");
            assertThat(body.path("authHeader").asText()).isEqualTo("public-1");
            assertThat(body.path("params").get(0).path("type").asText()).isEqualTo("integer");
            assertThat(body.path("params").get(1).path("name").asText()).isEqualTo("_body");
            assertThat(body.path("params").get(1).path("type").asText()).isEqualTo("json");
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        assertThat(authorization).isEqualTo("agw-jwt");
        byte[] bytes = body.strip().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
