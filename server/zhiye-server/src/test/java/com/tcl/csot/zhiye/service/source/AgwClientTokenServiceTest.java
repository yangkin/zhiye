package com.tcl.csot.zhiye.service.source;

import com.tcl.csot.zhiye.config.AgwClientProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgwClientTokenServiceTest {
    @Test
    void obtainsExchangesAndCachesClientAndUserTokens() throws IOException {
        AtomicInteger tokenCalls = new AtomicInteger();
        AtomicInteger exchangeCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/oauth/token", exchange -> {
            tokenCalls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String basic = exchange.getRequestHeaders().getFirst("Authorization");
            assertThat(basic).isEqualTo("Basic " + Base64.getEncoder().encodeToString("client:secret".getBytes(StandardCharsets.UTF_8)));
            if (body.equals("grant_type=client_credentials")) {
                respond(exchange, "{\"access_token\":\"metis-token\",\"expires_in\":299}");
            } else {
                assertThat(body).isEqualTo("grant_type=username&username=yangkai37");
                respond(exchange, "{\"access_token\":\"user-metis-token\",\"expires_in\":14302}");
            }
        });
        server.createContext("/api/agw-admin/api/auth/metis", exchange -> {
            exchangeCalls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String token = body.contains("user-metis-token") ? "agw-user-jwt" : "agw-jwt";
            respond(exchange, "{\"code\":\"ok\",\"success\":true,\"data\":{\"accessToken\":\"" + token + "\"}}");
        });
        server.start();
        try {
            String root = "http://127.0.0.1:" + server.getAddress().getPort();
            AgwClientProperties props = new AgwClientProperties("client", "secret", root + "/oauth/oauth/token", root + "/api/agw-admin", null);
            AgwClientTokenService service = new AgwClientTokenService(props);
            assertThat(service.authorization()).isEqualTo("agw-jwt");
            assertThat(service.authorization()).isEqualTo("agw-jwt");
            assertThat(service.authorizationForUser("yangkai37")).isEqualTo("agw-user-jwt");
            assertThat(service.authorizationForUser("yangkai37")).isEqualTo("agw-user-jwt");
            assertThat(tokenCalls).hasValue(2);
            assertThat(exchangeCalls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
