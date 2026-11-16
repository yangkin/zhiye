package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.auth.AuthService;

import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.service.app.Views;
import com.tcl.csot.zhiye.service.cloud.AccessService;
import com.tcl.csot.zhiye.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 发布站点的统一登录入口（20.4.1）：平台只有一个统一认证客户端，各应用不用单独登记。
 * 站点网关把未登录的访问者带到这里 → 已登录平台就发一次性码回站点 → 否则先去登录页，登录后再回到这里。
 * 一次性码在站点的 /__zhiye/auth/finish 换成只属于该子域名的会话 cookie。
 */
@RestController
@Profile("api")
@RequestMapping("/api/auth/site")
public class SiteLoginController {
    private final AuthService auth;
    private final AccessService access;
    private final AppRepository apps;
    private final Views views;

    public SiteLoginController(AuthService auth, AccessService access, AppRepository apps, Views views) {
        this.auth = auth;
        this.access = access;
        this.apps = apps;
        this.views = views;
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam String app, @RequestParam(required = false) String next, HttpServletRequest req) {
        if (!auth.ssoEnabled()) throw new ApiException(HttpStatus.NOT_FOUND, "AUTH_DISABLED", "当前环境未开启统一登录");
        var a = apps.findById(app).filter(x -> x.publishedVersionId() != null).orElseThrow(() -> ApiException.notFound("站点"));
        String safe = next == null || !next.startsWith("/") || next.startsWith("//") || next.contains("\\") ? "/" : next;
        var user = auth.currentUser(req);
        if (user.isEmpty()) {
            String self = "/api/auth/site/start?app=" + enc(a.id()) + "&next=" + enc(safe);
            return redirect("/login?site=1&redirect=" + enc(self));
        }
        String code = access.createCode(a.id(), user.get().id(), safe);
        return redirect(views.siteUrl(a.slug()) + "__zhiye/auth/finish?code=" + enc(code));
    }

    private static ResponseEntity<Void> redirect(String to) {
        return ResponseEntity.status(HttpStatus.FOUND).header("Cache-Control", "no-store").location(URI.create(to)).build();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
