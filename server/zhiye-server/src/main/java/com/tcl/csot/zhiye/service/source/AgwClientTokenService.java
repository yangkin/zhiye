package com.tcl.csot.zhiye.service.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.config.AgwClientProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgwClientTokenService {
    private record Cached(String token, long refreshAt) {}

    private final AgwClientProperties props;
    private final HttpClient http;
    private final ConcurrentHashMap<String, Cached> agwCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Cached> metisCache = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public AgwClientTokenService(AgwClientProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(java.net.ProxySelector.of(null))
                .build();
    }

    public String authorization() {
        return authorization(null);
    }

    public String authorizationForUser(String loginName) {
        requireUser(loginName);
        return authorization(normalizeUser(loginName));
    }

    public String metisAccessTokenForUser(String loginName) {
        requireUser(loginName);
        return metisAccessToken(normalizeUser(loginName));
    }

    /** 上游返回 401 时驱逐该用户的本地 Token 缓存，确保重试时重新向 Metis / AGW 换取新令牌。 */
    public void invalidateUser(String loginName) {
        if (blank(loginName)) return;
        String key = cacheKey(normalizeUser(loginName));
        agwCache.remove(key);
        metisCache.remove(key);
    }

    public String dataAuthMode() {
        return props == null || blank(props.dataAuthMode()) ? "auto" : props.dataAuthMode().trim();
    }

    private String authorization(String loginName) {
        String cacheKey = cacheKey(loginName);
        Cached hit = agwCache.get(cacheKey);
        if (hit != null && hit.refreshAt() > System.currentTimeMillis()) return hit.token();
        synchronized (lock) {
            hit = agwCache.get(cacheKey);
            if (hit != null && hit.refreshAt() > System.currentTimeMillis()) return hit.token();
            String agwToken = exchange(metisAccessToken(loginName));
            long refreshAt = jwtExpiry(agwToken, System.currentTimeMillis() + 300_000) - 60_000;
            agwCache.put(cacheKey, new Cached(agwToken, refreshAt));
            return agwToken;
        }
    }

    private String metisAccessToken(String loginName) {
        String cacheKey = cacheKey(loginName);
        Cached hit = metisCache.get(cacheKey);
        if (hit != null && hit.refreshAt() > System.currentTimeMillis()) return hit.token();
        synchronized (lock) {
            hit = metisCache.get(cacheKey);
            if (hit != null && hit.refreshAt() > System.currentTimeMillis()) return hit.token();
            JsonNode metis = requestMetisToken(loginName);
            String token = metis.path("access_token").asText("").trim();
            if (token.isEmpty()) throw unavailable();
            long refreshAt = System.currentTimeMillis() + Math.max(1, metis.path("expires_in").asLong(300) - 60) * 1000L;
            metisCache.put(cacheKey, new Cached(token, refreshAt));
            return token;
        }
    }

    private JsonNode requestMetisToken(String loginName) {
        if (blank(props.clientId()) || blank(props.clientSecret())) throw unavailable();
        String body = "grant_type=" + (loginName == null ? "client_credentials" : "username");
        if (loginName != null) body += "&username=" + java.net.URLEncoder.encode(loginName, StandardCharsets.UTF_8);
        if (!blank(props.scope())) body += "&scope=" + java.net.URLEncoder.encode(props.scope(), StandardCharsets.UTF_8);
        String basic = Base64.getEncoder().encodeToString((props.clientId() + ":" + props.clientSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.tokenUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        JsonNode response = send(request);
        if (response.path("access_token").asText("").isBlank()) throw unavailable();
        return response;
    }

    private String exchange(String metisToken) {
        String base = props.baseUrl().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/auth/metis"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(java.util.Map.of("code", metisToken)))).build();
        JsonNode response = send(request);
        if (response.path("failed").asBoolean() || (response.has("success") && !response.path("success").asBoolean())) throw unavailable();
        String token = response.path("data").path("accessToken").asText("").trim();
        if (token.isEmpty()) throw unavailable();
        return token;
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable();
            return Json.read(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_INTERRUPTED", "请求被中断");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private static long jwtExpiry(String token, long fallback) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return fallback;
            JsonNode payload = Json.read(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            long exp = payload.path("exp").asLong(0);
            return exp > 0 ? exp * 1000L : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String cacheKey(String loginName) { return loginName == null ? "client" : "user:" + loginName; }
    private static String normalizeUser(String loginName) { return loginName.trim().toLowerCase(Locale.ROOT); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static void requireUser(String loginName) {
        if (blank(loginName)) throw new ApiException(HttpStatus.BAD_REQUEST, "LOGIN_REQUIRED", "按用户调用 API 网关需要统一认证身份");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGW_AUTH_FAILED", "API 网关认证失败，请检查客户端配置或联系平台管理员");
    }
}
