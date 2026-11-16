package com.tcl.csot.zhiye.util;

/**
 * 当前请求的身份。现在由 {@code AnonymousWorkspaceFilter} 根据 cookie 解析；
 * 以后接入 SSO 时换成 OIDC 资源服务器，业务代码只依赖这个接口。
 */
public interface CurrentPrincipal {
    String workspaceId();

    /** 没有工作空间身份时返回 null（/api/meta 匿名引导、SSO 未登录放行的路径用） */
    default String workspaceIdOrNull() { return workspaceId(); }

    /** 当前请求携带的原始令牌（用于生成恢复链接），没有时为 null */
    String rawToken();
}
