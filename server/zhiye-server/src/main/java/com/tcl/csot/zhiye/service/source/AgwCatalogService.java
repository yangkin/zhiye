package com.tcl.csot.zhiye.service.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.config.AgwProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.ServerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** API 网关公开目录：SSO 令牌只用于浏览和读取详情，运行时使用调用项目签名凭证。 */
@Service
public class AgwCatalogService {
    private static final Logger log = LoggerFactory.getLogger(AgwCatalogService.class);
    private static final String ADMIN = "/api/agw-admin/apiInfo";
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;

    private final AgwProperties props;
    private final DataSourceService sources;
    private final ServerPort port;
    private final HttpClient http;

    public AgwCatalogService(AgwProperties props, DataSourceService sources, ServerPort port) {
        this.props = props;
        this.sources = sources;
        this.port = port;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(props.timeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(java.net.ProxySelector.of(null)).build();
    }

    public Map<String, Object> list(String ws, String token, String keyword, int page, int size) {
        requireConfigured(token);
        page = Math.max(0, page);
        size = Math.max(1, Math.min(100, size));
        StringBuilder path = new StringBuilder(ADMIN).append("/list?page=").append(page).append("&size=").append(size)
                .append("&enableStatus=1&shelvesStatus=1");
        if (keyword != null && !keyword.isBlank()) path.append("&apiName=").append(encode(keyword.trim()));
        JsonNode data = fetch(token, path.toString());
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode n : data.path("content")) items.add(view(ws, n));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("totalElements", data.path("totalElements").asLong(items.size()));
        out.put("totalPages", data.path("totalPages").asInt(1));
        out.put("number", data.path("number").asInt(page));
        out.put("size", data.path("size").asInt(size));
        return out;
    }

    public Map<String, Object> importApi(String ws, String token, String apiId, String appKey, String appSecret) {
        requireConfigured(token);
        if (apiId == null || apiId.isBlank() || apiId.length() > 128) throw ApiException.validation("API ID 不合法");
        JsonNode api = fetch(token, ADMIN + "/" + encode(apiId));
        if (api == null || !api.isObject() || api.path("id").asText().isBlank()) throw ApiException.notFound("API 网关接口");
        if (api.path("enableStatus").asInt() != 1 || api.path("shelvesStatus").asInt() != 1)
            throw ApiException.validation("这个 API 已停用或下架，不能导入");
        String method = api.path("apiMethod").asText("").trim().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) throw ApiException.validation("只支持导入 GET 或 POST 查询接口");
        if (api.path("byteStreamReturn").asInt() == 1) throw ApiException.validation("字节流返回的 API 暂不支持作为数据源");
        if (api.path("dynamicRouteEnabled").asInt() == 1) throw ApiException.validation("动态路由 API 请先手动登记最终调用地址和参数");

        String publicPath = api.path("apiPubUrl").asText("").trim();
        if (publicPath.isEmpty()) throw ApiException.validation("这个 API 没有公开调用地址");
        String base = props.publicBase().replace("{self}", port.selfBase()).replaceAll("/+$", "");
        String url = publicPath.matches("^https?://.*") ? publicPath : base + (publicPath.startsWith("/") ? "" : "/") + publicPath;
        List<Map<String, Object>> params = params(api, method, url);
        boolean hasBody = params.stream().anyMatch(p -> "body".equals(p.get("position")));
        if ("POST".equals(method) && !hasBody && !api.path("reqBodyExample").asText("").isBlank()
                && api.path("dataBaseParams").isNull() && api.path("restfulParams").isEmpty())
            throw ApiException.validation("这个 API 使用自由 JSON 请求体，请手动登记请求参数");

        ObjectNode body = Json.obj();
        body.put("key", key(api));
        body.put("name", cut(api.path("apiName").asText(api.path("apiCode").asText("API 网关接口")), 40));
        body.put("description", description(api, method, publicPath));
        body.put("method", method);
        body.put("url", url);
        body.set("params", Json.M.valueToTree(params));
        String sample = api.path("resReturnExample").asText(null);
        if (sample != null && !sample.isBlank()) body.put("sample", cut(sample, 20000));
        body.put("authType", "agw");
        if (appKey != null && !appKey.isBlank()) body.put("authHeader", appKey.trim());
        if (appSecret != null && !appSecret.isBlank()) body.put("secret", appSecret.trim());
        return sources.importExternal(ws, "agw", apiId, body);
    }

    private Map<String, Object> view(String ws, JsonNode n) {
        String method = n.path("apiMethod").asText("").toUpperCase(Locale.ROOT);
        String unsupported = null;
        if (!method.equals("GET") && !method.equals("POST")) unsupported = "仅支持 GET / POST";
        else if (n.path("byteStreamReturn").asInt() == 1) unsupported = "字节流返回暂不支持";
        else if (n.path("dynamicRouteEnabled").asInt() == 1) unsupported = "动态路由请先手动登记";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.path("id").asText());
        m.put("name", n.path("apiName").asText(""));
        m.put("code", n.path("apiCode").asText(""));
        m.put("description", n.path("apiDescribe").asText(""));
        m.put("method", method);
        m.put("path", n.path("apiPubUrl").asText(""));
        m.put("appName", n.path("appName").asText(null));
        m.put("appCode", n.path("appCode").asText(null));
        m.put("dataSourceName", n.path("dataSourceName").asText(null));
        m.put("paramCount", paramNodes(n).size());
        m.put("supported", unsupported == null);
        m.put("unsupportedReason", unsupported);
        m.put("importedSourceId", sources.importedId(ws, "agw", n.path("id").asText()));
        return m;
    }

    private static List<Map<String, Object>> params(JsonNode api, String method, String url) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode p : paramNodes(api)) {
            String name = p.path("inParamName").asText("").trim();
            if (name.isEmpty()) continue;
            String rawPosition = p.path("inPosition").asText("").trim().toUpperCase(Locale.ROOT);
            String position = switch (rawPosition) {
                case "PATH", "PARAMETERPATH" -> "path";
                case "QUERY" -> "query";
                case "HEADER" -> "header";
                case "BODY" -> "body";
                default -> "GET".equals(method) ? "query" : "body";
            };
            if ("path".equals(position) && !url.contains("{" + name + "}"))
                throw ApiException.validation("路径参数 " + name + " 没有在公开地址里用 {" + name + "} 声明，请手动登记");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("type", type(p.path("bgDataType").asText("")));
            m.put("required", p.path("required").asBoolean(false) || p.path("required").asInt() == 1);
            m.put("description", cut(p.path("paramDescribe").asText(""), 200));
            m.put("example", cut(p.path("paramExample").asText(""), 200));
            m.put("position", position);
            out.add(m);
        }
        return out;
    }

    private static List<JsonNode> paramNodes(JsonNode api) {
        List<JsonNode> out = new ArrayList<>();
        if (api.path("restfulParams").isArray()) api.path("restfulParams").forEach(out::add);
        JsonNode db = api.path("dataBaseParams");
        if (db.isObject()) for (String key : List.of("path", "query", "header", "body")) {
            if (db.path(key).isArray()) for (JsonNode p : db.path(key)) {
                ObjectNode copy = p.deepCopy();
                if (copy.path("inPosition").asText().isBlank()) copy.put("inPosition", key.toUpperCase(Locale.ROOT));
                out.add(copy);
            }
        }
        return out;
    }

    private static String type(String raw) {
        String t = raw.toLowerCase(Locale.ROOT);
        if (t.contains("bool")) return "boolean";
        if (t.contains("int") || t.contains("long")) return "integer";
        if (t.contains("double") || t.contains("float") || t.contains("decimal") || t.contains("number")) return "number";
        if (t.contains("date") || t.contains("time")) return "date";
        return "string";
    }

    private static String key(JsonNode api) {
        String base = api.path("apiCode").asText("api").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "api";
        if (!Character.isLetter(base.charAt(0))) base = "api_" + base;
        base = cut(base, 34);
        return "agw_" + base + "_" + Ids.sha256(api.path("id").asText()).substring(0, 6);
    }

    private static String description(JsonNode api, String method, String path) {
        StringBuilder s = new StringBuilder(api.path("apiDescribe").asText(""));
        if (!s.isEmpty()) s.append('\n');
        s.append("API 网关 · ").append(method).append(' ').append(path);
        if (!api.path("appName").asText("").isBlank()) s.append(" · 提供方 ").append(api.path("appName").asText());
        if (!api.path("dataSourceName").asText("").isBlank()) s.append(" · 数据源 ").append(api.path("dataSourceName").asText());
        return cut(s.toString(), 500);
    }

    private void requireConfigured(String token) {
        if (!props.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGW_NOT_CONFIGURED", "API 网关目录还没有配置");
        if (token == null || token.isBlank())
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_MISSING", "浏览 API 网关需要先用公司统一账号登录");
    }

    private JsonNode fetch(String token, String path) {
        String base = props.baseUrl().replace("{self}", port.selfBase()).replaceAll("/+$", "");
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("Authorization", token.trim())
                .header("X-Metis-Authorization", "Bearer " + token.trim())
                .header("X-Agw-Client", "console")
                .header("X-Tenant-Id", props.tenantId())
                .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream in = res.body()) { bytes = in.readNBytes(MAX_RESPONSE + 1); }
            if (bytes.length > MAX_RESPONSE) throw new ApiException(HttpStatus.BAD_GATEWAY, "AGW_RESPONSE_TOO_LARGE", "API 网关返回内容过大");
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (res.statusCode() == 401 || res.statusCode() == 403)
                throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_INVALID", "API 网关登录已失效或没有目录权限，请重新登录");
            if (res.statusCode() >= 400)
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AGW_ERROR", "API 网关返回 HTTP " + res.statusCode());
            JsonNode root = Json.read(text);
            if (root.path("failed").asBoolean(false) || !root.path("success").asBoolean(false)) {
                String message = root.path("message").asText("");
                if (message.isBlank()) message = root.path("msg").asText("API 网关业务处理失败");
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AGW_ERROR", message);
            }
            return root.path("data");
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AGW_UNAVAILABLE", "API 网关请求被中断");
        } catch (Exception e) {
            log.warn("API 网关目录请求失败：{}", e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AGW_UNAVAILABLE", "暂时无法访问 API 网关目录");
        }
    }

    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String cut(String s, int max) { return s == null ? "" : s.length() <= max ? s : s.substring(0, max); }
}
