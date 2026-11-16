package com.tcl.csot.zhiye.client;

import com.tcl.csot.zhiye.config.AuthProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 调用公司统一认证网关，用回调得到的 access_token 查询当前用户。
 * 请求头与 metis 前端脚手架一致：Authorization: Bearer {token}，Client_id: {clientId}。
 */
@Component
public class SsoClient {
    private static final Logger log = LoggerFactory.getLogger(SsoClient.class);

    /** 网关返回的用户信息（只取织页需要的字段） */
    public record Profile(String ssoUserId, String loginName, String realName, String email, String tenantNum) {}

    private final AuthProperties props;
    private final HttpClient http;
    private final com.tcl.csot.zhiye.util.ServerPort port;

    public SsoClient(AuthProperties props, com.tcl.csot.zhiye.util.ServerPort port) {
        this.props = props;
        this.port = port;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(props.sso().timeoutSeconds())).build();
    }

    public Profile fetchProfile(String accessToken) {
        if (accessToken == null || accessToken.isBlank() || accessToken.length() > 4096) throw invalid();
        HttpRequest req = HttpRequest.newBuilder(URI.create(props.sso().resolvedUserInfoUrl().replace("{self}", port.selfBase())))
                .timeout(Duration.ofSeconds(props.sso().timeoutSeconds()))
                .header("Authorization", "Bearer " + accessToken.trim())
                .header("Client_id", props.sso().clientId())
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("统一认证网关不可达：{}", e.toString());
            throw unreachable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unreachable();
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) throw invalid();
        if (res.statusCode() >= 500) throw unreachable();
        if (res.statusCode() >= 400) throw invalid();
        return parse(res.body());
    }

    /** 兼容直接返回用户对象和 {code, data: 用户对象} 两种包装 */
    public static Profile parse(String body) {
        JsonNode root;
        try {
            root = Json.read(body);
        } catch (RuntimeException e) {
            throw invalid();
        }
        JsonNode u = root != null && root.has("data") && root.get("data").isObject() ? root.get("data") : root;
        if (u == null || !u.isObject()) throw invalid();
        String id = text(u, "id", "userId", "employeeId");
        String login = text(u, "loginName", "username", "name");
        if (id == null && login == null) throw invalid();
        return new Profile(id != null ? id : login, login != null ? login : id, text(u, "realName", "actualName"),
                text(u, "email"), text(u, "tenantNum"));
    }

    private static String text(JsonNode n, String... keys) {
        for (String k : keys) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "登录凭证无效或已过期，请重新登录");
    }

    static ApiException unreachable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "SSO_UNREACHABLE", "暂时连不上公司统一认证服务（仅公司内网可用），请确认网络后重试");
    }
}
