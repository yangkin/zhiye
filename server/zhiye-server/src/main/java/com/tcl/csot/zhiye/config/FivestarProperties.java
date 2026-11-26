package com.tcl.csot.zhiye.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 五星AI岛对接：模型列表、默认模型和额度以五星AI岛（openclaw-service）为准，模型调用走 llm-gateway 的用户路由。
 * enabled=false（默认）时沿用 zhiye.llm 的本地上游，便于外网开发。
 * mock=true 时启用本地模拟的 openclaw 与 llm-gateway（/mock/openclaw、/mock/llm-gateway），内网联调前用它把整条链路跑通。
 */
@ConfigurationProperties("zhiye.fivestar")
public record FivestarProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean mock,
        /** 平台租户（organizationId），五星AI岛的接口路径都带它 */
        @DefaultValue("31") String organizationId,
        /** openclaw-service 根地址（含 context-path），例如 http://网关/openclaw-service；{self} 表示本服务 */
        @DefaultValue("{self}/mock/openclaw") String openclawBaseUrl,
        /** 模拟 openclaw 下发路由时用的 llm-gateway 地址；真实环境由 openclaw 返回 */
        @DefaultValue("{self}/mock/llm-gateway") String llmGatewayBaseUrl,
        /** 发往 llm-gateway 的 X-Project-Id（只用于区分来源，额度仍按个人） */
        @DefaultValue("zhiye") String projectId,
        /** 服务间令牌所在的环境变量名（调用 openclaw 内部接口取用户路由） */
        @DefaultValue("ZHIYE_OPENCLAW_INTERNAL_TOKEN") String internalTokenEnv,
        /** 公共免费虚拟 Key 所在的环境变量名：billingType=FREE 的模型用它调网关，付费模型才用个人 virtualKey */
        @DefaultValue("ZHIYE_FIVESTAR_PUBLIC_KEY") String publicKeyEnv,
        @DefaultValue("600") int routeCacheSeconds,
        @DefaultValue("10") int timeoutSeconds,
        /** 模拟网关的每周额度（tokens） */
        @DefaultValue("2000000") long mockLimitTokens) {

    public String internalToken() {
        String k = System.getenv(internalTokenEnv);
        if (k == null || k.isBlank()) k = System.getProperty(internalTokenEnv);
        return k == null ? "" : k.trim();
    }

    /** 公共免费虚拟 Key；未配置时返回空串（免费模型会退回路由下发的个人 Key） */
    public String publicKey() {
        String k = System.getenv(publicKeyEnv);
        if (k == null || k.isBlank()) k = System.getProperty(publicKeyEnv);
        return k == null ? "" : k.trim();
    }
}
