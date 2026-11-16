package com.tcl.csot.zhiye.support;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
