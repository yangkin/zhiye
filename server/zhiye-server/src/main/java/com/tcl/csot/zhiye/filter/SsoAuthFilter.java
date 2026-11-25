package com.tcl.csot.zhiye.filter;

import com.tcl.csot.zhiye.service.auth.AuthService;

import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.RequestPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 统一认证模式下的 /api/** 身份过滤器（排在匿名工作空间过滤器之前）。
 * 免登录模式（默认）下什么也不做。
 * 已登录：把用户的工作空间写入请求属性，匿名过滤器看到后直接放行；
 * 未登录：/api/meta 与 /api/auth/** 放行，其余返回 401。
 */
@Component
@Profile("api")
@Order(5)
public class SsoAuthFilter extends OncePerRequestFilter {
    public static final String ATTR_USER = "zhiye.user";
    private final AuthService auth;

    private final org.springframework.context.MessageSource messages;

    public SsoAuthFilter(AuthService auth, org.springframework.context.MessageSource messages) { this.auth = auth; this.messages = messages; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !auth.ssoEnabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var user = auth.currentUser(req);
        if (user.isPresent()) {
            req.setAttribute(ATTR_USER, user.get());
            req.setAttribute(RequestPrincipal.ATTR_WS, auth.workspaceOf(user.get()));
            chain.doFilter(req, res);
            return;
        }
        String uri = req.getRequestURI();
        if (uri.equals("/api/meta") || uri.startsWith("/api/auth/") || uri.startsWith("/api/webhooks/")) {
            req.setAttribute(RequestPrincipal.ATTR_WS, "");   // 不创建匿名工作空间
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        res.getOutputStream().write(Json.write(Map.of("code", "UNAUTHORIZED", "message",
                messages.getMessage("zhiye.error.unauthorized", null, "请先使用公司统一账号登录", req.getLocale().getLanguage().equals("en") ? java.util.Locale.US : java.util.Locale.SIMPLIFIED_CHINESE))).getBytes(StandardCharsets.UTF_8));
    }
}
