package com.tcl.csot.zhiye.service.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.config.AgwClientProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgwCatalogService {
    private final AgwClientProperties props;
    private final AgwClientTokenService tokens;
    private final DataSourceService sources;
    private final JdbcClient jdbc;
    private final HttpClient http;

    public AgwCatalogService(AgwClientProperties props, AgwClientTokenService tokens, DataSourceService sources, JdbcClient jdbc) {
        this.props = props;
        this.tokens = tokens;
        this.sources = sources;
        this.jdbc = jdbc;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(java.net.ProxySelector.of(null)).build();
    }

    public List<Map<String, Object>> callableApis(String ws) {
        JsonNode data = request(ws, "/apiInfo/me/callable-apis");
        if (!data.isArray()) throw requestFailed();
        return Json.M.convertValue(data, Json.M.getTypeFactory().constructCollectionType(List.class, Map.class));
    }

    public Map<String, Object> addApi(String ws, String projectCode, String apiId) {
        JsonNode selected = callableApis(ws).stream().<JsonNode>map(x -> Json.M.valueToTree(x))
                .filter(x -> projectCode.equals(x.path("projectCode").asText()) && apiId.equals(x.path("apiId").asText()))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "AGW_API_NOT_CALLABLE", "当前用户在这个项目下没有该 API 的有效调用授权"));
        JsonNode detail = request(ws, "/apiInfo/" + apiId);
        String method = detail.path("apiMethod").asText("GET").toUpperCase();
        if (!method.equals("GET") && !method.equals("POST")) throw ApiException.validation("只支持 GET 或 POST 查询类 API");

        List<Map<String, Object>> params = new ArrayList<>();
        readParams(detail.path("restfulParams"), params);
        JsonNode database = detail.path("dataBaseParams");
        if (database.isObject()) database.fields().forEachRemaining(e -> readParams(e.getValue(), params));
        String bodyExample = detail.path("reqBodyExample").asText("").trim();
        if ("POST".equals(method) && !bodyExample.isEmpty() && params.stream().noneMatch(x -> "_body".equals(x.get("name")))) {
            Map<String, Object> body = new HashMap<>();
            body.put("name", "_body");
            body.put("type", "json");
            body.put("position", "body");
            body.put("description", "完整 JSON 请求体（填写后覆盖其他参数）");
            body.put("example", jsonBodyExample(bodyExample));
            params.add(body);
        }

        String name = detail.path("apiName").asText(selected.path("apiName").asText("API"));
        if (name.length() > 40) name = name.substring(0, 40);
        String description = detail.path("apiDescribe").asText("").trim();
        if (description.isEmpty()) description = selected.path("projectName").asText("") + " · " + selected.path("grantTier").asText("") + " 授权";
        if (description.length() > 500) description = description.substring(0, 500);
        String sample = detail.path("resReturnExample").asText("");
        if (sample.length() > 20000) sample = sample.substring(0, 20000);
        String externalId = projectCode + ":" + apiId;
        Map<String, Object> body = new HashMap<>();
        body.put("key", callKey(selected.path("apiCode").asText("api"), externalId));
        body.put("name", name);
        body.put("description", description);
        body.put("method", method);
        body.put("url", dataPlaneUrl(detail));
        body.put("params", params);
        body.put("sample", sample);
        body.put("authType", "agw_user");
        body.put("authHeader", selected.path("projectPublicId").asText(""));
        return sources.importExternal(ws, "agw", externalId, Json.M.valueToTree(body));
    }

    private JsonNode request(String ws, String path) {
        String loginName = jdbc.sql("select u.login_name from workspace w join app_user u on u.id = w.owner_user_id where w.id = ?")
                .param(ws).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "LOGIN_REQUIRED", "查询可调用 API 需要统一认证身份"));
        String base = props.baseUrl().replaceAll("/+$", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", tokens.authorizationForUser(loginName))
                .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = Json.read(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || body.path("failed").asBoolean()
                    || (body.has("success") && !body.path("success").asBoolean())) throw requestFailed();
            return body.path("data");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_INTERRUPTED", "请求被中断");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw requestFailed();
        }
    }

    private String dataPlaneUrl(JsonNode detail) {
        String apiCode = detail.path("apiCode").asText("").trim();
        String publicPath = detail.path("apiPubUrl").asText("").trim();
        if (apiCode.isEmpty()) throw ApiException.validation("API 详情缺少 apiCode");
        String path;
        if (publicPath.startsWith("/agw-api/")) path = publicPath;
        else path = "/agw-api/" + apiCode + (publicPath.startsWith("/") ? publicPath : "/" + publicPath);
        String admin = props.baseUrl().replaceAll("/+$", "");
        String marker = "/api/agw-admin";
        String gateway = admin.endsWith(marker) ? admin.substring(0, admin.length() - marker.length()) : admin;
        return gateway + path;
    }

    private static void readParams(JsonNode rows, List<Map<String, Object>> out) {
        if (!rows.isArray()) return;
        for (JsonNode row : rows) {
            String name = row.path("inParamName").asText("").trim();
            if (name.isEmpty() || out.stream().anyMatch(x -> name.equals(x.get("name")))) continue;
            Map<String, Object> param = new HashMap<>();
            param.put("name", name);
            param.put("type", paramType(row.path("bgDataType").asText("")));
            param.put("required", row.path("required").asInt() == 1);
            param.put("description", row.path("paramDescribe").asText(""));
            param.put("example", row.path("paramExample").asText(""));
            param.put("position", row.path("inPosition").asText("query").toLowerCase());
            out.add(param);
        }
    }

    private static String paramType(String type) {
        String t = type.toLowerCase();
        if (t.contains("int") || t.contains("long")) return "integer";
        if (t.contains("double") || t.contains("float") || t.contains("decimal") || t.contains("number")) return "number";
        if (t.contains("bool")) return "boolean";
        return "string";
    }

    static String jsonBodyExample(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            String compact = Json.write(Json.read(raw));
            return compact.length() <= 200 ? compact : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String callKey(String apiCode, String externalId) {
        String key = apiCode.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (key.isEmpty() || !Character.isLetter(key.charAt(0))) key = "api_" + key;
        String suffix = Integer.toHexString(externalId.hashCode());
        return "agw_" + key.substring(0, Math.min(32, key.length())) + "_" + suffix;
    }

    private static ApiException requestFailed() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AGW_REQUEST_FAILED", "API 网关查询失败，请稍后重试或联系管理员检查权限");
    }
}
