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
 * 返回体是 metis Result{code:"ok", data, success:true}；业务异常可能仍使用 HTTP 200。
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
        String url = auth.sso().resolvedUserInfoUrl().replace("{self}", port.selfBase());
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
        return internalPost("/internal/llm/route", body);
    }

    /** 服务间：实际可用的个人模型（已校验网关 Provider、Key 和模型授权），返回裸 JSON 数组。 */
    public JsonNode availableModels(String userAccount) {
        return internalPost("/internal/llm/models", Json.obj().put("userAccount", userAccount));
    }

    private JsonNode internalPost(String path, JsonNode body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("X-Internal-Token", props.internalToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("内部模型接口失败：{}", e.toString());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        JsonNode root;
        try { root = Json.read(res.body()); } catch (RuntimeException e) { throw unavailable(); }
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            String msg = root.path("message").asText("HTTP " + res.statusCode());
            log.warn("内部模型接口失败：HTTP {} code={} message={}", res.statusCode(), root.path("code").asText(""), cut(msg));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "FIVESTAR_ROUTE_ERROR", "五星AI岛模型路由不可用：" + cut(msg));
        }
        return root;
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
        // metis 新旧信封：{code:"ok",data,success:true} 或 {code:200,message:"success",data}
        String code = root.path("code").asText("");
        boolean ok = root.path("success").asBoolean(false) || root.has("data") && (code.equals("ok") || code.equals("200"));
        boolean businessError = root.hasNonNull("type") && root.hasNonNull("message") && !ok;
        if (businessError || !ok) {
            String msg = root.path("message").asText("");
            if (msg.isBlank()) msg = root.path("msg").asText("未知错误");
            log.warn("{}失败：HTTP {} code={} message={}", what, s, root.path("code").asText(""), cut(msg));
            throw new ApiException(businessError ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY, "FIVESTAR_ERROR", "五星AI岛返回错误：" + cut(msg));
        }
        return root.path("data");
    }

    private static String cut(String s) { return s == null ? "" : s.length() > 200 ? s.substring(0, 200) + "…" : s; }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "FIVESTAR_UNAVAILABLE", "五星AI岛服务暂不可用，请稍后再试");
    }
}
