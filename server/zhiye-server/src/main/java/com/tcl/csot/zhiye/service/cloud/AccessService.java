package com.tcl.csot.zhiye.service.cloud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.config.AuthProperties;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 发布访问级别（20.4.1）与「谁在用」：
 * - public 内网公开（默认）/ login 员工登录 / members 指定人员（登录名名单）
 * - 管理员 = 作者（工作空间的主人）+ 管理员名单；权限预设里的「管理员」指这些人
 * - 站点会话：cookie 只写在应用自己的子域名上，库里只存哈希
 */
@Service
public class AccessService {
    public static final Set<String> LEVELS = Set.of("public", "login", "members");
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9._@\\-]{1,64}");
    public static final int SESSION_DAYS = 7;

    public record Access(String level, List<String> members, List<String> admins) {
        public boolean needsLogin() { return !"public".equals(level); }
    }

    private final JdbcClient jdbc;
    private final AppRepository apps;
    private final AuthProperties auth;
    private final ZhiyeProperties props;

    public AccessService(JdbcClient jdbc, AppRepository apps, AuthProperties auth, ZhiyeProperties props) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.auth = auth;
        this.props = props;
    }

    public boolean ssoAvailable() { return auth.ssoEnabled(); }

    public Access get(String appId) {
        return jdbc.sql("select level, members, admins from app_site_access where app_id = ?").param(appId)
                .query((rs, i) -> new Access(rs.getString(1),
                        Json.read(rs.getString(2), new TypeReference<List<String>>() {}),
                        Json.read(rs.getString(3), new TypeReference<List<String>>() {})))
                .optional().orElse(new Access("public", List.of(), List.of()));
    }

    public Access update(AppRow app, String level, List<String> members, List<String> admins) {
        if (level == null || !LEVELS.contains(level)) throw ApiException.validation("访问级别只能是：内网公开 / 员工登录 / 指定人员");
        if (!"public".equals(level) && !auth.ssoEnabled())
            throw new ApiException(HttpStatus.CONFLICT, "SSO_UNAVAILABLE", "当前环境还没有接入公司统一登录，暂时只能选「内网公开」");
        List<String> m = cleanLogins(members, "成员"), a = cleanLogins(admins, "管理员");
        if ("members".equals(level) && m.isEmpty() && a.isEmpty()) throw ApiException.validation("「指定人员」至少要填一个登录名");
        jdbc.sql("""
                insert into app_site_access (app_id, level, members, admins, updated_at) values (?, ?, ?::jsonb, ?::jsonb, now())
                on conflict (app_id) do update set level = excluded.level, members = excluded.members, admins = excluded.admins, updated_at = now()
                """).params(app.id(), level, Json.write(m), Json.write(a)).update();
        return get(app.id());
    }

    private static List<String> cleanLogins(List<String> in, String what) {
        if (in == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : in) {
            if (s == null || s.isBlank()) continue;
            String t = s.trim();
            if (!LOGIN.matcher(t).matches()) throw ApiException.validation(what + "「" + t + "」不是合法的登录名（字母、数字、. _ - @）");
            out.add(t);
        }
        if (out.size() > 500) throw ApiException.validation(what + "最多 500 人");
        return new ArrayList<>(out);
    }

    /** 作者：应用所在工作空间的主人（免登录环境下没有） */
    public Optional<SsoUser> author(String appId) {
        return jdbc.sql("""
                select u.id, u.sso_user_id, u.login_name, u.real_name, u.email, u.tenant_num
                from app a join workspace w on w.id = a.workspace_id join app_user u on u.id = w.owner_user_id where a.id = ?""")
                .param(appId)
                .query((rs, i) -> new SsoUser(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional();
    }

    public boolean isAdmin(String appId, SsoUser user) {
        if (user == null) return false;
        if (author(appId).map(a -> a.id().equals(user.id())).orElse(false)) return true;
        return get(appId).admins().contains(user.loginName());
    }

    public boolean canVisit(String appId, Access access, SsoUser user) {
        if (!access.needsLogin()) return true;
        if (user == null) return false;
        if ("login".equals(access.level())) return true;
        return access.members().contains(user.loginName()) || isAdmin(appId, user);
    }

    public CloudPolicy.Who who(String appId, SsoUser user) {
        if (user == null) return CloudPolicy.Who.ANON;
        return new CloudPolicy.Who(user.id(), user.loginName(), user.realName() == null ? user.loginName() : user.realName(), isAdmin(appId, user));
    }

    /** 预览里令牌代表的身份：admin = 作者本人，member = 模拟的普通员工，anon = 模拟未登录 */
    public CloudPolicy.Who previewWho(String appId, String role) {
        return switch (role) {
            case "member" -> new CloudPolicy.Who("preview-member", "preview-member", "示例员工", false);
            case "anon" -> CloudPolicy.Who.ANON;
            default -> author(appId).map(a -> new CloudPolicy.Who(a.id(), a.loginName(), a.realName() == null ? a.loginName() : a.realName(), true))
                    .orElse(new CloudPolicy.Who("author", "author", "我（作者）", true));
        };
    }

    /** 表的预设需要知道「是谁」、访问级别却是内网公开：发布前拦下 */
    public List<String> tablesNeedingLogin(String appId) {
        return jdbc.sql("select name, policy from app_cloud_table t join app_cloud c on c.app_id = t.app_id where t.app_id = ? and c.status = 'on' order by name")
                .param(appId)
                .query((rs, i) -> CloudPolicy.preset(Json.read(rs.getString(2))).needsLogin() ? rs.getString(1) : null)
                .list().stream().filter(Objects::nonNull).toList();
    }

    // ---------- 站点会话 ----------

    public static final String SITE_COOKIE = "zhiye_site";

    /** 发布站点请求里的登录用户（cookie 只在应用子域名上） */
    public Optional<SsoUser> siteUser(String appId, jakarta.servlet.http.HttpServletRequest req) {
        if (req.getCookies() == null) return Optional.empty();
        for (var c : req.getCookies()) if (SITE_COOKIE.equals(c.getName())) return sessionUser(appId, c.getValue());
        return Optional.empty();
    }

    /** 不写 Domain：host-only cookie，只在这个应用的子域名上有效，其他应用和平台拿不到；按站点协议决定 Secure，兼容主站 HTTPS + 站点 HTTP 部署 */
    public String siteCookie(String value, java.time.Duration maxAge) {
        boolean siteHttps = props != null && props.site() != null && "https".equalsIgnoreCase(props.site().siteScheme());
        boolean secure = siteHttps && auth.sso().secureCookie();
        return org.springframework.http.ResponseCookie.from(SITE_COOKIE, value).httpOnly(true).secure(secure)
                .sameSite("Lax").path("/").maxAge(maxAge).build().toString();
    }

    /** 注入到页面的 window.zhiye.user：{id, loginName, name, email, admin}；未登录为 null */
    public Map<String, Object> userView(String appId, SsoUser user) {
        if (user == null) return null;
        Map<String, Object> m = new LinkedHashMap<>(user.view());
        m.put("name", m.remove("realName"));
        m.put("admin", isAdmin(appId, user));
        return m;
    }

    public String createSession(String appId, String userId) {
        String token = Ids.token();
        jdbc.sql("insert into site_session (token_hash, app_id, user_id, expires_at) values (?, ?, ?, ?)")
                .params(Ids.sha256(token), appId, userId, OffsetDateTime.now(ZoneOffset.UTC).plusDays(SESSION_DAYS)).update();
        return token;
    }

    public Optional<SsoUser> sessionUser(String appId, String token) {
        if (token == null || token.length() > 200) return Optional.empty();
        return jdbc.sql("""
                select u.id, u.sso_user_id, u.login_name, u.real_name, u.email, u.tenant_num
                from site_session s join app_user u on u.id = s.user_id
                where s.token_hash = ? and s.app_id = ? and s.expires_at > now()""")
                .params(Ids.sha256(token), appId)
                .query((rs, i) -> new SsoUser(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .optional();
    }

    public void deleteSession(String token) {
        if (token != null) jdbc.sql("delete from site_session where token_hash = ?").param(Ids.sha256(token)).update();
    }

    /** 统一登录回调后发的一次性码：60 秒、只能用一次 */
    public String createCode(String appId, String userId, String next) {
        String code = Ids.token();
        jdbc.sql("insert into site_sso_code (code_hash, app_id, user_id, next_path, expires_at) values (?, ?, ?, ?, ?)")
                .params(Ids.sha256(code), appId, userId, next, OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(60)).update();
        return code;
    }

    public record Redeemed(String userId, String next) {}

    @Transactional
    public Optional<Redeemed> redeemCode(String appId, String code) {
        if (code == null || code.length() > 200) return Optional.empty();
        return jdbc.sql("""
                update site_sso_code set used_at = now()
                where code_hash = ? and app_id = ? and used_at is null and expires_at > now()
                returning user_id, next_path""")
                .params(Ids.sha256(code), appId)
                .query((rs, i) -> new Redeemed(rs.getString(1), rs.getString(2))).optional();
    }

    public Map<String, Object> view(AppRow app) {
        Access a = get(app.id());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", a.level());
        m.put("members", a.members());
        m.put("admins", a.admins());
        m.put("ssoAvailable", auth.ssoEnabled());
        m.put("author", author(app.id()).map(SsoUser::view).orElse(null));
        m.put("tablesNeedingLogin", tablesNeedingLogin(app.id()));
        m.put("presets", CloudPolicy.presetViews());
        return m;
    }
}
