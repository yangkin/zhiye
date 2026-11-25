package com.tcl.csot.zhiye.support;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 测试用的统一认证「查询当前用户」接口：令牌 tok-{登录名} 代表这个人。 */
@RestController
@Profile("test")
@RequestMapping("/test-sso")
public class FakeSsoController {
    @GetMapping("/self")
    public ResponseEntity<Map<String, Object>> self(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth == null || !auth.startsWith("Bearer tok-")) return ResponseEntity.status(401).build();
        String login = auth.substring("Bearer tok-".length());
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("id", "sso-" + login, "loginName", login, "realName", "员工" + login, "email", login + "@tcl.com")));
    }

    /** 模拟公司 TPFM 员工目录：只认 Authorization 头里带平台令牌（tok- 开头）的调用 */
    @GetMapping("/tpfm/v2/31/employeeVos")
    public ResponseEntity<Map<String, Object>> employees(@RequestHeader(value = "Authorization", required = false) String auth,
                                                         @RequestParam(required = false) String name,
                                                         @RequestParam(required = false) String accounts) {
        if (auth == null || !auth.startsWith("Bearer tok-")) return ResponseEntity.status(401).build();
        String q = name != null ? name : accounts;
        List<Map<String, Object>> all = new java.util.ArrayList<>(List.of(
                Map.of("userAccount", "yangkai37", "userName", "杨凯"),
                Map.of("userAccount", "zhangsan01", "userName", "张三"),
                Map.of("userAccount", "lisi02", "userName", "李四")));
        all.removeIf(e -> {
            if (q == null || q.isBlank()) return false;
            String userName = String.valueOf(e.get("userName"));
            String userAccount = String.valueOf(e.get("userAccount"));
            return !userName.contains(q) && !userAccount.contains(q);
        });
        return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("content", all, "totalElements", all.size())));
    }
}
