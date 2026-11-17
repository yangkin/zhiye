package com.tcl.csot.zhiye.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** API 网关目录（agw-admin）和业务调用入口。 */
@ConfigurationProperties("zhiye.agw")
public record AgwProperties(
        @DefaultValue("false") boolean enabled,
        /** 管理端与业务入口的共同根地址；{self} 用于本地测试 */
        @DefaultValue("http://testmetis.csot.tcl.com") String baseUrl,
        /** 业务 API 调用根地址；为空时使用 baseUrl */
        String publicBaseUrl,
        @DefaultValue("31") String tenantId,
        @DefaultValue("10") int timeoutSeconds) {
    public String publicBase() { return publicBaseUrl == null || publicBaseUrl.isBlank() ? baseUrl : publicBaseUrl; }
}
