package com.tcl.csot.zhiye.filter;

import com.tcl.csot.zhiye.service.workspace.WorkspaceService;

import com.tcl.csot.zhiye.util.RequestPrincipal;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/** /api/** 的匿名工作空间身份：cookie zhiye_ws，首次访问自动创建。 */
@Component
@Profile("api")
@Order(10)
public class AnonymousWorkspaceFilter extends OncePerRequestFilter {
    private final WorkspaceService workspaces;
    private final ZhiyeProperties props;

    public AnonymousWorkspaceFilter(WorkspaceService workspaces, ZhiyeProperties props) {
        this.workspaces = workspaces;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        // SSO 过滤器已经确定了身份（或明确不创建匿名工作空间）
        if (req.getAttribute(RequestPrincipal.ATTR_WS) != null) { chain.doFilter(req, res); return; }
        String name = props.workspace().cookieName();
        String token = null;
        if (req.getCookies() != null) for (Cookie c : req.getCookies()) if (name.equals(c.getName())) token = c.getValue();
        var ws = workspaces.byToken(token);
        // 不需要工作空间的请求不要顺手建一个：恢复链接页面上 /api/meta 和 recover 并发发出，
        // meta 回来的新 cookie 会盖掉 recover 刚设好的那个，恢复就落到一个空工作空间里
        if (ws.isEmpty() && NO_WORKSPACE.contains(req.getRequestURI())) { chain.doFilter(req, res); return; }
        String wsId;
        if (ws.isPresent()) {
            wsId = ws.get().id();
        } else {
            var created = workspaces.create();
            wsId = created.id();
            token = created.token();
            setCookie(res, name, token, props.workspace().secureCookie());
        }
        req.setAttribute(RequestPrincipal.ATTR_WS, wsId);
        req.setAttribute(RequestPrincipal.ATTR_TOKEN, token);
        chain.doFilter(req, res);
    }

    private static final java.util.Set<String> NO_WORKSPACE = java.util.Set.of("/api/meta", "/api/workspace/recover");

    public static void setCookie(HttpServletResponse res, String name, String token, boolean secure) {
        ResponseCookie c = ResponseCookie.from(name, token).httpOnly(true).secure(secure).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(3650)).build();
        res.addHeader("Set-Cookie", c.toString());
    }
}
