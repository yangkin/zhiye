package com.zhiye.app;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "ok", true,
                "runtime", "java",
                "framework", "Vue 3 + Spring Boot",
                "time", Instant.now().toString()
        );
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", headerOr(req, "X-Zhiye-User-Id", "local-user"));
        user.put("loginName", headerOr(req, "X-Zhiye-User-Login", "demo"));
        user.put("name", headerOr(req, "X-Zhiye-User-Name", "演示用户"));
        user.put("admin", "true".equalsIgnoreCase(headerOr(req, "X-Zhiye-User-Admin", "true")));
        return user;
    }

    private static String headerOr(HttpServletRequest req, String name, String def) {
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? def : v;
    }
}
