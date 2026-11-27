package com.tcl.csot.zhiye.service.auth;

import com.tcl.csot.zhiye.client.SsoClient;
import com.tcl.csot.zhiye.config.AuthProperties;
import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.exception.ApiException;

import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.service.app.Views;
import com.tcl.csot.zhiye.service.cloud.AccessService;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.workspace.WorkspaceService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 统一认证登录：校验网关令牌 → 建立织页自己的会话（httpOnly cookie，只存哈希）→ 绑定工作空间。
 * 网关令牌本身不保存。首次登录时，把当前浏览器里尚未归属的匿名工作空间归入该用户。
 * 当开启平台白名单时，未开通平台创作权限的公司员工访问已发布应用（员工登录 / 指定人员）时，
 * 仅签发该已发布应用的一次性站点授权码（不建立平台会话、不创建平台工作空间），确保严格隔离。
 */
@Service
public class AuthService {
    private final AuthProperties props;
    private final ZhiyeProperties zhiye;
    private final JdbcClient jdbc;
    private final SsoClient sso;
    private final WorkspaceService workspaces;
    private final com.tcl.csot.zhiye.service.admin.AdminAuthService adminAuth;

    @Autowired(required = false)
    private AppRepository apps;
    @Autowired(required = false)
    private AccessService access;
    @Autowired(required = false)
    @Lazy
    private Views views;

    public AuthService(AuthProperties props, ZhiyeProperties zhiye, JdbcClient jdbc, SsoClient sso, WorkspaceService workspaces,
                       com.tcl.csot.zhiye.service.admin.AdminAuthService adminAuth) {
        this.props = props;
        this.zhiye = zhiye;
        this.jdbc = jdbc;
        this.sso = sso;
        this.workspaces = workspaces;
        this.adminAuth = adminAuth;
    }

    public boolean ssoEnabled() { return props.ssoEnabled(); }

    /** /api/meta 中的 auth 字段 */
    public Map<String, Object> describe(HttpServletRequest req) {
        var m = new LinkedHashMap<String, Object>();
        m.put("mode", props.ssoEnabled() ? "sso" : "anonymous");
        m.put("user", props.ssoEnabled() ? currentUser(req).map(SsoUser::view).orElse(null) : null);
        return m;
    }

    public String loginUrl() { return props.sso().loginUrl(); }

    public Optional<SsoUser> currentUser(HttpServletRequest req) {
        String token = cookie(req, props.sso().sessionCookie());
        if (token == null || token.length() > 200) return Optional.empty();
        return jdbc.sql("""
                select u.id, u.sso_user_id, u.login_name, u.real_name, u.email, u.tenant_num
                from user_session s join app_user u on u.id = s.user_id
                where s.token_hash = ? and s.expires_at > now()""")
                .param(Ids.sha256(token))
                .query((rs, i) -> new SsoUser(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional();
    }

    /** 已登录用户的工作空间（没有则创建） */
    @Transactional
    public String workspaceOf(SsoUser user) {
        return jdbc.sql("select id from workspace where owner_user_id = ? order by created_at limit 1").param(user.id())
                .query(String.class).optional()
                .orElseGet(() -> {
                    String id = workspaces.create().id();
                    jdbc.sql("update workspace set owner_user_id = ? where id = ?").params(user.id(), id).update();
                    return id;
                });
    }

    /** 登录白名单判定：配置的管理员白名单、数据库管理员、platform_login_allowlist 内启用人员均可登录 */
    private boolean loginAllowed(String loginName) {
        if (loginName == null || loginName.isBlank()) return false;
        if (adminAuth.isAllowlistedLogin(loginName)) return true;
        Boolean admin = jdbc.sql("select role = 'admin' from app_user where lower(login_name) = lower(?) limit 1")
                .param(loginName).query(Boolean.class).optional().orElse(false);
        if (Boolean.TRUE.equals(admin)) return true;
        return jdbc.sql("select enabled from platform_login_allowlist where lower(login_name) = lower(?) limit 1")
                .param(loginName).query(Boolean.class).optional().orElse(false);
    }

    private SsoUser upsertUser(SsoClient.Profile p) {
        String userId = jdbc.sql("""
                insert into app_user (id, sso_user_id, login_name, real_name, email, tenant_num)
                values (?, ?, ?, ?, ?, ?)
                on conflict (sso_user_id) do update set login_name = excluded.login_name, real_name = excluded.real_name,
                  email = excluded.email, tenant_num = excluded.tenant_num, last_login_at = now()
                returning id""")
                .params(Ids.newId("u"), p.ssoUserId(), p.loginName(), p.realName(), p.email(), p.tenantNum())
                .query(String.class).single();
        return new SsoUser(userId, p.ssoUserId(), p.loginName(), p.realName(), p.email(), p.tenantNum());
    }

    private SsoUser completePlatformLogin(SsoClient.Profile p, HttpServletRequest req, HttpServletResponse res) {
        SsoUser user = upsertUser(p);
        String userId = user.id();

        // 名单里预授的角色同步到 app_user（支持登录前指定管理员）；配置白名单/数据库管理员不受影响
        if (props.loginAllowlistEnabled()) {
            jdbc.sql("""
                    update app_user set role = (select role from platform_login_allowlist where lower(login_name) = lower(?) limit 1)
                    where id = ? and exists (select 1 from platform_login_allowlist where lower(login_name) = lower(?) and enabled)
                    """).params(p.loginName(), userId, p.loginName()).update();
        }

        // 用户还没有工作空间时，认领当前浏览器的匿名工作空间（之前免登录创建的应用随之归入账号）
        boolean hasOwn = jdbc.sql("select count(*) from workspace where owner_user_id = ?").param(userId).query(Long.class).single() > 0;
        if (!hasOwn) {
            workspaces.byToken(cookie(req, zhiye.workspace().cookieName())).ifPresent(ws ->
                    jdbc.sql("update workspace set owner_user_id = ? where id = ? and owner_user_id is null").params(userId, ws.id()).update());
        }
        workspaceOf(user);

        String token = Ids.token();
        jdbc.sql("insert into user_session (id, user_id, token_hash, expires_at) values (?, ?, ?, ?)")
                .params(Ids.newId("ss"), userId, Ids.sha256(token),
                        OffsetDateTime.now(ZoneOffset.UTC).plusDays(props.sso().sessionDays()))
                .update();
        res.addHeader("Set-Cookie", sessionCookie(token, Duration.ofDays(props.sso().sessionDays())).toString());
        return user;
    }

    @Transactional
    public SsoUser login(String accessToken, HttpServletRequest req, HttpServletResponse res) {
        SsoClient.Profile p = sso.fetchProfile(accessToken);
        // 人员管理：开启登录白名单后，仅名单内启用人员与平台管理员可登录，其他人直接拒绝（不建会话）
        if (props.loginAllowlistEnabled() && !loginAllowed(p.loginName())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ACCESS_DENIED",
                    "账号「" + p.loginName() + "」还没有平台使用权限，请联系平台管理员开通");
        }
        return completePlatformLogin(p, req, res);
    }

    /**
     * 统一认证回调入口：
     * - 平台允许登录的人员：正常建立平台会话与工作空间，前端跳回 redirect；
     * - 未开通平台白名单但正在访问某个已发布应用（redirect 指向 /api/auth/site/start?app=...）：
     *   仅校核该应用的访问权限并签发 60 秒一次性站点授权码（不建立平台 user_session、不创建平台 workspace、不下发平台 cookie），
     *   实现「仅可访问获授权的已发布应用，仍完全隔离于织页创作平台」。
     */
    @Transactional
    public Map<String, Object> callback(String accessToken, String redirect, HttpServletRequest req, HttpServletResponse res) {
        SsoClient.Profile p = sso.fetchProfile(accessToken);
        if (!props.loginAllowlistEnabled() || loginAllowed(p.loginName())) {
            SsoUser user = completePlatformLogin(p, req, res);
            return Map.of("user", user.view());
        }
        SiteTarget target = parseSiteStartRedirect(redirect);
        if (target == null || apps == null || access == null || views == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ACCESS_DENIED",
                    "账号「" + p.loginName() + "」还没有平台使用权限，请联系平台管理员开通");
        }
        var app = apps.findById(target.appId())
                .filter(x -> x.publishedVersionId() != null)
                .orElseThrow(() -> ApiException.notFound("站点"));
        AccessService.Access acc = access.get(app.id());
        if (!acc.needsLogin()) {
            String url = views.siteUrl(app.slug()) + target.nextPath().replaceFirst("^/+", "");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user", null);
            out.put("siteOnly", true);
            out.put("siteRedirectUrl", url);
            return out;
        }
        if (!access.canVisitLogin(app.id(), acc, p.loginName())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SITE_ACCESS_DENIED",
                    "该应用仅对指定人员开放，账号「" + p.loginName() + "」不在访问名单中，请联系应用作者添加");
        }
        // 仅写入/刷新身份档案用于站点会话与业务审计，严禁创建 workspace 与平台 user_session cookie
        SsoUser user = upsertUser(p);
        String code = access.createCode(app.id(), user.id(), target.nextPath());
        String finishUrl = views.siteUrl(app.slug()) + "__zhiye/auth/finish?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", user.view());
        out.put("siteOnly", true);
        out.put("siteRedirectUrl", finishUrl);
        return out;
    }

    private record SiteTarget(String appId, String nextPath) {}

    /** 严格解析 /api/auth/site/start?app=<id>&next=<path>，防止任何其他路径绕过平台白名单 */
    static SiteTarget parseSiteStartRedirect(String redirect) {
        if (redirect == null || !redirect.startsWith("/api/auth/site/start")) return null;
        try {
            URI uri = URI.create("http://local" + redirect);
            if (!"/api/auth/site/start".equals(uri.getPath())) return null;
            String q = uri.getRawQuery();
            if (q == null || q.isBlank()) return null;
            String appId = null;
            String next = "/";
            for (String pair : q.split("&")) {
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if ("app".equals(k)) appId = v.trim();
                else if ("next".equals(k)) next = v;
            }
            if (appId == null || appId.isBlank() || appId.length() > 64) return null;
            String safeNext = next == null || !next.startsWith("/") || next.startsWith("//")
                    || next.contains("\\") || next.startsWith("/__zhiye/") ? "/" : next;
            return new SiteTarget(appId, safeNext);
        } catch (Exception ignored) {
            return null;
        }
    }

    public void logout(HttpServletRequest req, HttpServletResponse res) {
        String token = cookie(req, props.sso().sessionCookie());
        if (token != null) jdbc.sql("delete from user_session where token_hash = ?").param(Ids.sha256(token)).update();
        res.addHeader("Set-Cookie", sessionCookie("", Duration.ZERO).toString());
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(props.sso().sessionCookie(), value).httpOnly(true).secure(props.sso().secureCookie())
                .sameSite("Lax").path("/").maxAge(maxAge).build();
    }

    static String cookie(HttpServletRequest req, String name) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (name.equals(c.getName())) return c.getValue();
        return null;
    }
}
