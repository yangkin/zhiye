package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.auth.AuthService;

import com.tcl.csot.zhiye.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/** 统一认证接口。免登录模式下除 /me 外都返回 AUTH_DISABLED。 */
@RestController
@Profile("api")
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    public record CallbackBody(String accessToken, Long expiresIn, String redirect) {}

    /** 跳转到统一认证网关登录页 */
    @GetMapping("/sso/login")
    public ResponseEntity<Void> login() {
        requireSso();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(auth.loginUrl())).build();
    }

    /** 前端 /access_token 页面拿到网关令牌后调用：校验令牌并建立织页会话（非平台白名单员工访问已发布应用时仅签发站点一次性授权码） */
    @PostMapping("/sso/callback")
    public Map<String, Object> callback(@RequestBody CallbackBody body, HttpServletRequest req, HttpServletResponse res) {
        requireSso();
        return auth.callback(body.accessToken(), body.redirect(), req, res);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest req, HttpServletResponse res) {
        auth.logout(req, res);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) { return auth.describe(req); }

    private void requireSso() {
        if (!auth.ssoEnabled()) throw new ApiException(HttpStatus.NOT_FOUND, "AUTH_DISABLED", "当前环境未开启统一登录");
    }
}
