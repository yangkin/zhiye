package com.tcl.csot.zhiye.controller.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.exception.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本地 API 网关：目录使用 SSO 令牌，业务调用校验项目签名。 */
@RestController
@Profile({"mock", "test"})
public class MockAgwController {
    public static volatile String lastAuthorization;
    public static volatile Map<String, String> lastListQuery;

    @GetMapping("/mock/agw/api/agw-admin/apiInfo/list")
    public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam Map<String, String> query) {
        account(authorization);
        lastAuthorization = authorization;
        lastListQuery = new LinkedHashMap<>(query);
        Map<String, Object> api = api();
        api.put("byteStreamReturn", 0);
        api.put("dynamicRouteEnabled", 0);
        Map<String, Object> other = Map.of("id", "api-delete", "apiName", "不支持的方法", "apiCode", "delete_order",
                "apiMethod", "DELETE", "apiPubUrl", "/mock/agw-public/delete", "enableStatus", 1, "shelvesStatus", 1);
        List<Map<String, Object>> rows = query.getOrDefault("apiName", "").isBlank() || api.toString().contains(query.get("apiName"))
                ? List.of(api, other) : List.of();
        return ok(Map.of("content", rows, "totalElements", rows.size(), "totalPages", 1,
                "number", Integer.parseInt(query.getOrDefault("page", "0")), "size", Integer.parseInt(query.getOrDefault("size", "20"))));
    }

    @GetMapping("/mock/agw/api/agw-admin/apiInfo/{id}")
    public Map<String, Object> detail(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        account(authorization);
        if (!id.equals("api-daily")) throw new ApiException(HttpStatus.NOT_FOUND, "AGW_NOT_FOUND", "API 不存在");
        return ok(api());
    }

    @GetMapping("/mock/agw-public/daily/{line}")
    public Map<String, Object> daily(@PathVariable String line, @RequestParam Map<String, String> query,
                                     @RequestHeader("x-agw-app-key") String appKey,
                                     @RequestHeader("x-agw-time-stamp") String timestamp,
                                     @RequestHeader("x-agw-signature") String signature,
                                     @RequestHeader("x-agw-request-id") String requestId) {
        if (!appKey.equals("project-demo")) throw new ApiException(HttpStatus.UNAUTHORIZED, "AGW_AUTH", "AppKey 不正确");
        String expected = signature(appKey, "demo-secret", timestamp);
        if (!expected.equals(signature)) throw new ApiException(HttpStatus.UNAUTHORIZED, "AGW_AUTH", "签名不正确");
        return Map.of("line", line, "query", query, "appKey", appKey, "timestamp", timestamp, "signature", signature,
                "requestId", requestId, "rows", List.of(Map.of("hour", 8, "output", 120)));
    }

    public static String signature(String appKey, String secret, String timestamp) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join(";", appKey, secret, timestamp).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void account(String authorization) {
        if (authorization == null || !authorization.startsWith("tok-")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AGW_AUTH", "请先登录");
        }
    }

    private static Map<String, Object> api() {
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("id", "api-daily");
        api.put("apiName", "产线日产量");
        api.put("apiCode", "mes_daily_output");
        api.put("apiDescribe", "按产线和日期查询每小时产量");
        api.put("apiMethod", "GET");
        api.put("apiPubUrl", "/mock/agw-public/daily/{line}");
        api.put("appName", "MES");
        api.put("appCode", "mes");
        api.put("dataSourceName", "MES 生产数据");
        api.put("enableStatus", 1);
        api.put("shelvesStatus", 1);
        api.put("resReturnExample", "{\"rows\":[{\"hour\":8,\"output\":120}]}");
        api.put("restfulParams", List.of(
                Map.of("inParamName", "line", "inPosition", "PATH", "bgDataType", "String", "required", 1, "paramDescribe", "产线", "paramExample", "L1"),
                Map.of("inParamName", "date", "inPosition", "QUERY", "bgDataType", "Date", "required", 1, "paramExample", "2026-01-31"),
                Map.of("inParamName", "top", "inPosition", "QUERY", "bgDataType", "Integer", "required", 0, "paramExample", "5")));
        return api;
    }

    private static Map<String, Object> ok(Object data) {
        return Map.of("code", "ok", "success", true, "data", data);
    }
}
