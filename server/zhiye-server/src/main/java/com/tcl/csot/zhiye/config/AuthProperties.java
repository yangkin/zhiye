package com.tcl.csot.zhiye.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 登录配置。默认 {@code anonymous}（免登录），设为 {@code sso} 才启用公司统一认证。
 * 统一认证网关只能在公司内网访问。
 */
@ConfigurationProperties("zhiye.auth")
public record AuthProperties(
        @DefaultValue("anonymous") String mode,
        @DefaultValue Sso sso) {

    public boolean ssoEnabled() { return "sso".equalsIgnoreCase(mode); }

    public record Sso(
            /** 统一认证网关基地址，如 http://testmetis.csot.tcl.com */
            @DefaultValue("http://testmetis.csot.tcl.com") String gatewayUrl,
            /** 网关登录路径 */
            @DefaultValue("/oauth/csot/login") String oauthPath,
            /** 技术平台客户端 ID */
            @DefaultValue("SSO-METIS") String clientId,
            /** 认证成功后网关回调的织页地址（前端路由 /access_token），需与网关登记一致 */
            @DefaultValue("http://127.0.0.1:8080/access_token") String redirectUrl,
            /** 用令牌查询当前用户的接口；为空时使用 gatewayUrl + /iam/tcl/v1/users/self */
            String userInfoUrl,
            /** 调用网关的超时（秒） */
            @DefaultValue("10") int timeoutSeconds,
            /** 织页会话 cookie 名称与有效期 */
            @DefaultValue("zhiye_session") String sessionCookie,
            @DefaultValue("7") int sessionDays,
            @DefaultValue("false") boolean secureCookie) {

        public String resolvedUserInfoUrl() {
            return userInfoUrl != null && !userInfoUrl.isBlank() ? userInfoUrl : trimSlash(gatewayUrl) + "/iam/tcl/v1/users/self";
        }

        /** 与 metis 前端脚手架一致：{gateway}{oauthPath}?rc={redirectUrl}&client_id={clientId} */
        public String loginUrl() {
            return trimSlash(gatewayUrl) + oauthPath + "?rc=" + redirectUrl + "&client_id=" + clientId;
        }

        private static String trimSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    }
}
