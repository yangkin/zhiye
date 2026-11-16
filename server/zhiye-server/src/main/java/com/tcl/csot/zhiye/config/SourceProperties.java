package com.tcl.csot.zhiye.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * zhiye.sources.*：数据源代理。
 * - allowedHosts：允许访问的主机（精确匹配或 *.后缀）；为空表示不限制（仍然拒绝链路本地/元数据地址）
 * - allowLoopback：是否允许 127.0.0.1 / localhost（本地开发、测试用；生产关闭）
 * - file：平台登记的数据源（JSON 数组），启动时同步，凭证用 secretEnv 指向环境变量
 */
@ConfigurationProperties("zhiye.sources")
public record SourceProperties(List<String> allowedHosts,
                               @DefaultValue("false") boolean allowLoopback,
                               @DefaultValue("10") int timeoutSeconds,
                               @DefaultValue("2097152") int maxResponseBytes,
                               String file) {
    public List<String> hosts() { return allowedHosts == null ? List.of() : allowedHosts; }
}
