package com.tcl.csot.zhiye.controller.mock;

import com.tcl.csot.zhiye.config.AuthProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地模拟的平台统一认证（zhiye.mock.sso=true 时启用），路径与真实网关一致：
 * - GET  /mock/sso/oauth/csot/login?rc=回调地址&client_id=... → 选一个员工身份登录
 * - 登录后跳回 rc?access_token=tok-{工号}&expires_in=7200（与网关回调一致，由前端 /access_token 页接住）
 * - GET  /mock/sso/iam/tcl/v1/users/self（Authorization: Bearer tok-{工号}）→ 当前用户
 * 配置：zhiye.auth.mode=sso，zhiye.auth.sso.gateway-url={本服务}/mock/sso。内网换成真实网关地址并关掉 mock 即可。
 */
@RestController
@Profile("api")
@ConditionalOnProperty(prefix = "zhiye.mock", name = "sso", havingValue = "true")
@RequestMapping("/mock/sso")
public class MockSsoController {
    private static final String LOGIN_RE = "[A-Za-z0-9._-]{1,64}";
    private final AuthProperties auth;
    private final Map<String, String> names = new ConcurrentHashMap<>();

    public MockSsoController(AuthProperties auth) { this.auth = auth; }

    /** 回调地址只允许配置里登记的那个（和真实网关一样，防止把令牌带去别处） */
    private boolean allowed(String rc) {
        return rc != null && rc.equals(auth.sso().redirectUrl());
    }

    @GetMapping(value = "/oauth/csot/login", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> loginPage(@RequestParam(required = false) String rc, @RequestParam(value = "client_id", required = false) String clientId) {
        if (!allowed(rc)) return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body("回调地址未登记：" + rc);
        String esc = HtmlUtils.htmlEscape(rc);
        String html = """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>统一认证（本地模拟）</title>
                <style>
                body{margin:0;min-height:100vh;display:grid;place-items:center;background:#f6f5fa;font:14px/1.6 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;color:#2c2838}
                form{width:min(360px,calc(100vw - 32px));background:#fff;border:1px solid #ebe7f3;border-radius:16px;padding:28px 26px;box-shadow:0 12px 40px #2c1f5a10}
                h1{font-size:18px;margin:0 0 4px}p{margin:0 0 20px;color:#8a8499;font-size:12.5px}
                label{display:block;font-size:12.5px;color:#5c5670;margin:12px 0 6px}
                input{width:100%%;box-sizing:border-box;padding:10px 12px;border:1px solid #e3deee;border-radius:10px;font:inherit}
                input:focus{outline:2px solid #b49ce8;border-color:#b49ce8}
                button{margin-top:20px;width:100%%;padding:11px;border:0;border-radius:10px;background:#7953dc;color:#fff;font:inherit;font-weight:600;cursor:pointer}
                .tag{display:inline-block;margin-bottom:12px;padding:2px 8px;border-radius:6px;background:#fdf3e4;color:#b2722a;font-size:11.5px}
                </style></head><body>
                <form method="get" action="/mock/sso/oauth/csot/authorize">
                <span class="tag">本地模拟 · 不连接公司网关</span>
                <h1>TCL华星 统一认证</h1><p>选一个员工身份登录织页。令牌为 tok-{工号}，模拟的五星AI岛也认这个令牌。</p>
                <input type="hidden" name="rc" value="%s">
                <label for="login">工号</label><input id="login" name="login" value="zhangsan" pattern="[A-Za-z0-9._-]{1,64}" required autofocus>
                <label for="name">姓名</label><input id="name" name="name" value="张三" maxlength="20">
                <button type="submit">登录</button>
                </form></body></html>
                """.formatted(esc);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @GetMapping("/oauth/csot/authorize")
    public ResponseEntity<Void> authorize(@RequestParam String rc, @RequestParam String login, @RequestParam(required = false) String name) {
        if (!allowed(rc) || !login.matches(LOGIN_RE)) return ResponseEntity.badRequest().build();
        if (name != null && !name.isBlank()) names.put(login, name.trim().length() > 20 ? name.trim().substring(0, 20) : name.trim());
        URI back = UriComponentsBuilder.fromUriString(rc).queryParam("access_token", "tok-" + login).queryParam("expires_in", 7200).build().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(back).build();
    }

    @GetMapping("/iam/tcl/v1/users/self")
    public ResponseEntity<Map<String, Object>> self(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String login = MockFivestarController.account(authorization);
        if (login == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(Map.of("code", 200, "data", Map.of("id", "sso-" + login, "loginName", login,
                "realName", names.getOrDefault(login, "员工" + login), "email", login + "@tcl.com", "tenantNum", "0")));
    }
}
