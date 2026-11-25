package com.tcl.csot.zhiye.service.auth;

import com.tcl.csot.zhiye.client.SsoClient;
import com.tcl.csot.zhiye.config.AuthProperties;
import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.exception.ApiException;

import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.workspace.WorkspaceService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 统一认证登录：校验网关令牌 → 建立织页自己的会话（httpOnly cookie，只存哈希）→ 绑定工作空间。
 * 网关令牌本身不保存。首次登录时，把当前浏览器里尚未归属的匿名工作空间归入该用户。
 */
@Service
public class AuthService {
    private final AuthProperties props;
    private final ZhiyeProperties zhiye;
    private final JdbcClient jdbc;
    private final SsoClient sso;
    private final WorkspaceService workspaces;
    private final com.tcl.csot.zhiye.service.admin.AdminAuthService adminAuth;

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
        if (adminAuth.isAllowlistedLogin(loginName)) return true;
        Boolean admin = jdbc.sql("select role = 'admin' from app_user where login_name = ?")
                .param(loginName).query(Boolean.class).optional().orElse(false);
        if (Boolean.TRUE.equals(admin)) return true;
        return jdbc.sql("select enabled from platform_login_allowlist where login_name = ?")
                .param(loginName).query(Boolean.class).optional().orElse(false);
    }

    @Transactional
    public SsoUser login(String accessToken, HttpServletRequest req, HttpServletResponse res) {
        SsoClient.Profile p = sso.fetchProfile(accessToken);
        // 人员管理：开启登录白名单后，仅名单内启用人员与平台管理员可登录，其他人直接拒绝（不建会话）
        if (props.loginAllowlistEnabled() && !loginAllowed(p.loginName())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ACCESS_DENIED",
                    "账号「" + p.loginName() + "」还没有平台使用权限，请联系平台管理员开通");
        }
        String userId = jdbc.sql("""
                insert into app_user (id, sso_user_id, login_name, real_name, email, tenant_num)
                values (?, ?, ?, ?, ?, ?)
                on conflict (sso_user_id) do update set login_name = excluded.login_name, real_name = excluded.real_name,
                  email = excluded.email, tenant_num = excluded.tenant_num, last_login_at = now()
                returning id""")
                .params(Ids.newId("u"), p.ssoUserId(), p.loginName(), p.realName(), p.email(), p.tenantNum())
                .query(String.class).single();
        SsoUser user = new SsoUser(userId, p.ssoUserId(), p.loginName(), p.realName(), p.email(), p.tenantNum());

        // 名单里预授的角色同步到 app_user（支持登录前指定管理员）；配置白名单/数据库管理员不受影响
        if (props.loginAllowlistEnabled()) {
            jdbc.sql("""
                    update app_user set role = (select role from platform_login_allowlist where login_name = ?)
                    where id = ? and exists (select 1 from platform_login_allowlist where login_name = ? and enabled)
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
