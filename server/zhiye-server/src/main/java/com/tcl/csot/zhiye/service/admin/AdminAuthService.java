package com.tcl.csot.zhiye.service.admin;

import com.tcl.csot.zhiye.config.AuthProperties;
import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.filter.SsoAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 平台管理员统一鉴权服务（默认严格关闭 Fail-Closed）：
 * <ul>
 *   <li>无论匿名模式还是 SSO 模式，未显式授予管理员身份的普通用户/普通工作空间均拒绝跨空间与全平台查询（HTTP 403）。</li>
 *   <li>支持以下授权来源：SSO 登录名白名单（{@code zhiye.admin.allow-login-names}）、数据库用户角色（{@code app_user.role = 'admin'}）、
 *       工作空间白名单（{@code zhiye.admin.allow-workspaces}）或管理员密钥请求头（{@code X-Zhiye-Admin-Token}）。</li>
 * </ul>
 */
@Service
public class AdminAuthService {
    public static final String HEADER_ADMIN_TOKEN = "X-Zhiye-Admin-Token";

    public record DashboardAccess(boolean admin, String scope, String workspaceId, String userId) {}

    private final JdbcClient jdbc;
    private final AuthProperties auth;
    private final Set<String> allowLoginNames;
    private final Set<String> allowWorkspaces;
    private final String adminToken;

    @Autowired
    public AdminAuthService(JdbcClient jdbc,
                            AuthProperties auth,
                            @Value("${zhiye.admin.allow-login-names:}") String allowLoginNamesRaw,
                            @Value("${zhiye.admin.allow-workspaces:}") String allowWorkspacesRaw,
                            @Value("${zhiye.admin.token:}") String adminToken) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.allowLoginNames = parseSet(allowLoginNamesRaw);
        this.allowWorkspaces = parseSet(allowWorkspacesRaw);
        this.adminToken = clean(adminToken);
    }

    public boolean isSsoRestricted() {
        return auth.ssoEnabled() && !allowLoginNames.isEmpty();
    }

    public boolean isAllowlistedLogin(String loginName) {
        String cleanLogin = clean(loginName);
        return cleanLogin != null && allowLoginNames.contains(cleanLogin);
    }

    public String currentUserId(HttpServletRequest req) {
        if (req == null) return null;
        SsoUser user = (SsoUser) req.getAttribute(SsoAuthFilter.ATTR_USER);
        return user == null ? null : clean(user.id());
    }

    public boolean isAdmin(HttpServletRequest req, String currentWs) {
        if (hasValidAdminToken(req)) return true;
        SsoUser user = req == null ? null : (SsoUser) req.getAttribute(SsoAuthFilter.ATTR_USER);
        return isAdmin(user, currentWs);
    }

    public boolean isAdmin(SsoUser user, String currentWs) {
        if (auth.ssoEnabled()) {
            if (user == null) return false;
            if (allowLoginNames.contains(user.loginName())) return true;
            return "admin".equalsIgnoreCase(jdbc.sql("select role from app_user where id = ?")
                    .param(user.id()).query(String.class).optional().orElse("user"));
        }
        String ws = clean(currentWs);
        return ws != null && allowWorkspaces.contains(ws);
    }

    public String requireAdminActor(HttpServletRequest req, String currentWs) {
        if (hasValidAdminToken(req)) {
            SsoUser user = req == null ? null : (SsoUser) req.getAttribute(SsoAuthFilter.ATTR_USER);
            if (user != null) return formatSsoActor(user);
            String ws = clean(currentWs);
            return "platform-admin (" + (ws == null ? "system" : ws) + ")";
        }
        SsoUser user = req == null ? null : (SsoUser) req.getAttribute(SsoAuthFilter.ATTR_USER);
        return requireAdminActor(user, currentWs);
    }

    public String requireAdminActor(SsoUser user, String currentWs) {
        if (auth.ssoEnabled()) {
            if (user == null) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "请先登录后再进行平台管理操作");
            }
            if (!isAdmin(user, currentWs)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "当前账号「" + user.loginName() + "」不具备平台管理员权限");
            }
            return formatSsoActor(user);
        }
        if (!isAdmin((SsoUser) null, currentWs)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前工作空间不具备平台管理员权限");
        }
        String ws = clean(currentWs);
        return "platform-admin (" + (ws == null ? "system" : ws) + ")";
    }

    /** 校验看板访问权限：普通用户仅允许查看 scope=workspace 且绑定自身工作空间的数据，跨空间或 platform 视角统一返回 403。 */
    public DashboardAccess authorizeDashboard(HttpServletRequest req, String currentWs,
                                              String scopeParam, String workspaceIdParam, String userIdParam) {
        String scope = (scopeParam == null || scopeParam.isBlank()) ? "platform" : scopeParam.trim();
        if (!"platform".equals(scope) && !"workspace".equals(scope)) {
            throw ApiException.validation("scope 只能是 platform 或 workspace");
        }
        String reqWs = clean(workspaceIdParam);
        String reqUser = clean(userIdParam);
        if (isAdmin(req, currentWs)) {
            return new DashboardAccess(true, scope, reqWs, reqUser);
        }
        if ("platform".equals(scope)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "仅平台管理员可查看全平台管理看板，请使用当前工作空间视角（scope=workspace）");
        }
        if (reqWs != null && !reqWs.equals(currentWs)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权跨工作空间查看其他空间的统计数据");
        }
        SsoUser user = req == null ? null : (SsoUser) req.getAttribute(SsoAuthFilter.ATTR_USER);
        if (reqUser != null && (user == null || !reqUser.equals(user.id()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权查看其他人员的统计数据");
        }
        return new DashboardAccess(false, "workspace", currentWs, null);
    }

    /** 校验任务日志列表筛选权限：管理员可跨空间排查，普通用户强隔离到本人工作空间且禁止指定他人 workspaceId / userId。 */
    public String authorizeTaskLogWorkspace(HttpServletRequest req, String currentWs, String workspaceIdParam, String userIdParam) {
        String reqWs = clean(workspaceIdParam);
        String reqUser = clean(userIdParam);
        if (isAdmin(req, currentWs)) return reqWs;
        if (reqWs != null && !reqWs.equals(currentWs)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权查看其他工作空间的开发任务日志");
        }
        SsoUser user = req == null ? null : (SsoUser) req.getAttribute(SsoAuthFilter.ATTR_USER);
        if (reqUser != null && (user == null || !reqUser.equals(user.id()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权查看其他人员的任务日志");
        }
        return currentWs;
    }

    /** 校验单任务运维详情/日志访问权限：不存在返回 404，普通用户跨空间访问返回 403。 */
    public void requireTaskAccess(HttpServletRequest req, String currentWs, String taskId) {
        if (isAdmin(req, currentWs)) return;
        String ownerWs = jdbc.sql("select workspace_id from task where id = ?")
                .param(taskId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("任务"));
        if (!ownerWs.equals(currentWs)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问其他工作空间的任务日志");
        }
    }

    /** 校验云函数/数据源日志访问权限：非管理员强隔离到本人工作空间；指定他人 appId 时返回 403。 */
    public String authorizeAppLogWorkspace(HttpServletRequest req, String currentWs, String appIdParam) {
        if (isAdmin(req, currentWs)) return null;
        String appId = clean(appIdParam);
        if (appId != null) {
            String ownerWs = jdbc.sql("select workspace_id from app where id = ?")
                    .param(appId).query(String.class).optional()
                    .orElseThrow(() -> ApiException.notFound("应用"));
            if (!ownerWs.equals(currentWs)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问其他工作空间的应用运行日志");
            }
        }
        return currentWs;
    }

    private boolean hasValidAdminToken(HttpServletRequest req) {
        if (adminToken == null || req == null) return false;
        String provided = clean(req.getHeader(HEADER_ADMIN_TOKEN));
        if (provided == null) return false;
        return MessageDigest.isEqual(
                adminToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String formatSsoActor(SsoUser user) {
        String display = user.realName() != null && !user.realName().isBlank() ? user.realName().trim() : user.loginName();
        return display + " (" + user.loginName() + ")";
    }

    private static Set<String> parseSet(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String clean(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
