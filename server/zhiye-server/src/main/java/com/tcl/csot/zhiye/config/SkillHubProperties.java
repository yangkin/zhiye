package com.tcl.csot.zhiye.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 公司 SkillHub（fivestar-service 的 aiToolbox Skill 市场）。
 * 接口见 fivestar-service 的 AiToolboxSkillController / AiSkillCategoryController；调用时带用户的平台令牌，身份由平台网关认定。
 * mock=true 时用本服务内置的模拟 SkillHub（/mock/fivestar-service），外网开发和联调前跑通整条链路。
 */
@ConfigurationProperties("zhiye.skillhub")
public record SkillHubProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean mock,
        /** fivestar-service 网关根地址（context-path 为 /fivestar-service），例如 http://网关/fivestar-service；{self} 表示本服务 */
        String baseUrl,
        /** 技能包文件地址所在的服务根地址（fivestar-service 调平台文件服务取回 skillPackageUrl 后返回）；通常与 baseUrl 相同 */
        String fileBaseUrl,
        /** SkillHub 页面地址（发布结果里给用户看的链接） */
        String portalUrl,
        /** 平台租户（organizationId），接口路径都带它 */
        @DefaultValue("0") String organizationId,
        /** OSS 上传用的 bucket / storageCode（预留：目前直接把包作为 multipart 传给 fivestar） */
        @DefaultValue("") String bucketName,
        @DefaultValue("") String storageCode,
        /** 服务间令牌所在的环境变量名（预留） */
        @DefaultValue("ZHIYE_SKILLHUB_INTERNAL_TOKEN") String internalTokenEnv,
        @DefaultValue("60") int timeoutSeconds) {

    public String internalToken() {
        String k = System.getenv(internalTokenEnv);
        if (k == null || k.isBlank()) k = System.getProperty(internalTokenEnv);
        return k == null ? "" : k.trim();
    }
}
