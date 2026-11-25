package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.config.AuthProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.admin.AdminService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.PlatformToken;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 员工目录搜索（人员管理用）：浏览器不直连公司网关（跨域/混合内容会被拦），
 * 由服务端用调用者自己的平台令牌代理查询 TPFM employeeVos；令牌不保存、不打印。
 */
@RestController
@Profile("api")
public class EmployeeSearchController {
    private final AdminService admin;
    private final CurrentPrincipal principal;
    private final AuthProperties auth;
    private final com.tcl.csot.zhiye.util.ServerPort serverPort;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public EmployeeSearchController(AdminService admin, CurrentPrincipal principal, AuthProperties auth,
                                    com.tcl.csot.zhiye.util.ServerPort serverPort) {
        this.admin = admin;
        this.principal = principal;
        this.auth = auth;
        this.serverPort = serverPort;
    }

    @GetMapping("/api/admin/employees/search")
    public List<Map<String, String>> search(@RequestParam String q, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        String token = PlatformToken.of(req);
        if (token == null)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_MISSING", "请先用公司统一账号登录");
        if (q == null || q.isBlank()) return List.of();

        String base = auth.sso().gatewayUrl().replace("{self}", serverPort.selfBase()).replaceAll("/+$", "");
        // 租户与 AGW 控制台一致（公司 TPFM 组织 31）
        String org = "31";
        List<Map<String, String>> byName = query(base, org, "name", q.trim(), token);
        return byName.isEmpty() ? query(base, org, "accounts", q.trim(), token) : byName;
    }

    private List<Map<String, String>> query(String base, String org, String param, String q, String token) {
        try {
            String url = base + "/tpfm/v2/" + org + "/employeeVos?page=0&size=50&" + param + "="
                    + URLEncoder.encode(q, StandardCharsets.UTF_8);
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400)
                throw new ApiException(HttpStatus.BAD_GATEWAY, "EMPLOYEE_SEARCH_FAILED", "员工目录查询失败（HTTP " + res.statusCode() + "）");
            JsonNode root = Json.read(res.body());
            JsonNode d = root.has("data") && !root.get("data").isNull() ? root.get("data") : root;
            JsonNode arr = d.isArray() ? d
                    : d.path("content").isArray() ? d.get("content")
                    : d.path("records").isArray() ? d.get("records")
                    : d.path("list").isArray() ? d.get("list") : null;
            List<Map<String, String>> out = new ArrayList<>();
            if (arr == null) return out;
            for (JsonNode e : arr) {
                String loginName = first(e, "userAccount", "account", "loginName", "uid", "employeeCode", "empCode");
                if (loginName.isEmpty()) continue;
                String realName = first(e, "userName", "name", "actualName", "realName");
                out.add(Map.of("loginName", loginName, "realName", realName));
            }
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EMPLOYEE_SEARCH_FAILED", "员工目录查询失败：" + e.getMessage());
        }
    }

    private static String first(JsonNode e, String... keys) {
        for (String k : keys) {
            String v = e.path(k).asText("");
            if (!v.isBlank()) return v;
        }
        return "";
    }
}
