package com.tcl.csot.zhiye.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 测试用的「公司接口」：回显路径参数、查询参数和鉴权头，便于断言平台代理的拼装。 */
@RestController
@Profile("test")
@RequestMapping("/test-source")
public class FakeSourceController {
    @GetMapping("/daily/{line}")
    public Map<String, Object> daily(@PathVariable String line, @RequestParam Map<String, String> q,
                                     @RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
                                     @RequestHeader(value = "X-Zhiye-App", required = false) String app) {
        return Map.of("line", line, "query", q, "auth", String.valueOf(auth), "apiKey", String.valueOf(apiKey), "app", String.valueOf(app),
                "rows", java.util.List.of(Map.of("hour", 8, "output", 120), Map.of("hour", 9, "output", 135)));
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody JsonNode body) { return Map.of("echo", body); }

    @GetMapping("/fail")
    public void fail(HttpServletResponse res) throws Exception { res.setStatus(500); res.getWriter().write("boom"); }

    @GetMapping("/redirect")
    public void redirect(HttpServletResponse res) throws Exception { res.sendRedirect("/test-source/fail"); }

    @GetMapping("/big")
    public String big() { return "x".repeat(2 * 1024 * 1024 + 10); }

    @GetMapping("/text")
    public String text() { return "plain " + Json.write(Map.of("ok", true)); }
}
