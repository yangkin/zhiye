package com.tcl.csot.zhiye.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.config.FivestarProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.ServerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 五星AI岛 openclaw-service 客户端。
 * - 用户接口（模型列表、默认模型、额度）：带前端传来的平台令牌，身份由平台网关认定；
 * - 内部接口（取某个员工的模型路由）：带服务间令牌。这个接口需要 openclaw 新增，见 docs/fivestar-integration.md。
 * 返回体都是 openclaw 的 Result{code, message, data}，code=200 为成功。
 */
@Component
public class OpenclawClient {
    private static final Logger log = LoggerFactory.getLogger(OpenclawClient.class);

    private final FivestarProperties props;
    private final com.tcl.csot.zhiye.config.AuthProperties auth;
    private final ServerPort port;
    private final HttpClient http;

    public OpenclawClient(FivestarProperties props, com.tcl.csot.zhiye.config.AuthProperties auth, ServerPort port) {
        this.props = props;
        this.auth = auth;
        this.port = port;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(props.timeoutSeconds())).build();
    }

    private String base() {
        return props.openclawBaseUrl().replace("{self}", port.selfBase()).replaceAll("/+$", "");
    }

    private String org() { return URLEncoder.encode(props.organizationId(), StandardCharsets.UTF_8); }

    /** 当前用户在五星AI岛配置的供应商及模型（LlmProviderVO 列表） */
    public JsonNode providers(String platformToken) { return userGet("/" + org() + "/llm/models/providers", platformToken); }

    /** 当前用户的默认模型（DefaultModelVO，可能为 null） */
    public JsonNode defaultModel(String platformToken) { return userGet("/" + org() + "/llm/models/default", platformToken); }

    /** 当前用户信息（平台 IAM 的 /iam/tcl/v1/users/self：loginName、realName 等），用于 SkillHub 发布时填写作者 */
    public JsonNode userInfo(String platformToken) {
        if (platformToken == null || platformToken.isBlank())
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_MISSING", "请先用公司统一账号登录");
        String url = auth.sso().userInfoUrl();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("Authorization", "Bearer " + platformToken).header("Accept", "application/json").GET().build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("查询用户信息失败：{}", e.toString());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        if (res.statusCode() == 401 || res.statusCode() == 403)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_INVALID", "平台登录已失效，请重新登录");
        if (res.statusCode() >= 400) throw unavailable();
        try {
            JsonNode root = Json.read(res.body());
            JsonNode data = root.path("data");
            return data.isMissingNode() || data.isNull() ? root : data;
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    /** 当前用户的套餐与额度（TokenCostUsageVO） */
    public JsonNode usage(String platformToken) { return userGet("/" + org() + "/tokenCostDetail/usage", platformToken); }

    /**
     * 服务间：取员工在 llm-gateway 上的模型路由。modelRef 为 "providerId:modelId"，为空时用该员工在五星AI岛的默认模型。
     * 返回 {baseUrl, providerModelName, virtualKey, providerId, providerName, modelId, modelName}。
     */
    public JsonNode route(String userAccount, String modelRef) {
        var body = Json.obj().put("userAccount", userAccount);
        if (modelRef != null && !modelRef.isBlank()) {
            int i = modelRef.indexOf(':');
            body.put("providerId", modelRef.substring(0, i)).put("modelId", modelRef.substring(i + 1));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/internal/zhiye/llm/route"))
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("X-Internal-Token", props.internalToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        return send(req, "取模型路由");
    }

    private JsonNode userGet(String path, String platformToken) {
        if (platformToken == null || platformToken.isBlank())
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_MISSING", "请先用公司统一账号登录");
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("Authorization", "Bearer " + platformToken)
                .header("Accept", "application/json")
                .GET().build();
        return send(req, "查询五星AI岛");
    }

    private JsonNode send(HttpRequest req, String what) {
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("{}失败：{}", what, e.toString());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        int s = res.statusCode();
        if (s == 401 || s == 403)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_INVALID", "平台登录已失效，请重新登录");
        if (s >= 400) {
            log.warn("{}返回 HTTP {}：{}", what, s, cut(res.body()));
            throw unavailable();
        }
        JsonNode root;
        try { root = Json.read(res.body()); } catch (RuntimeException e) { throw unavailable(); }
        int code = root.path("code").asInt(200);
        if (code != 200 && code != 0) {
            log.warn("{}失败：code={} message={}", what, code, root.path("message").asText(""));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "FIVESTAR_ERROR", "五星AI岛返回错误：" + cut(root.path("message").asText("未知错误")));
        }
        return root.path("data");
    }

    private static String cut(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s; }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "FIVESTAR_UNAVAILABLE", "五星AI岛服务暂不可用，请稍后再试");
    }
}
