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
        /** fivestar-service 网关根地址（网关前缀为 /fivestar-new），例如 http://网关/fivestar-new；{self} 表示本服务 */
        String baseUrl,
        /** 技能包文件地址所在的服务根地址（fivestar-service 调平台文件服务取回 skillPackageUrl 后返回）；通常与 baseUrl 相同 */
        String fileBaseUrl,
        /** TFLE 文件服务网关根地址，例如 http://网关/tfle/v1；为空时从标准 fivestar-new 地址推导 */
        String uploadBaseUrl,
        /** SkillHub 详情页地址，{id} 会替换为技能 ID */
        String portalUrl,
        /** 平台租户（organizationId），接口路径都带它 */
        @DefaultValue("31") String organizationId,
        /** TFLE 上传的 bucket / storageCode 覆盖值；默认从 fivestar-service 的存储配置接口获取 */
        @DefaultValue("") String bucketName,
        @DefaultValue("") String storageCode,
        /** 服务间令牌所在的环境变量名（预留） */
        @DefaultValue("ZHIYE_SKILLHUB_INTERNAL_TOKEN") String internalTokenEnv,
        /** 分类按中心过滤（平台 queryByCenter 的 centerName）；为空时由平台按用户中心解析，失败回退 queryAll */
        @DefaultValue("") String centerName,
        @DefaultValue("60") int timeoutSeconds) {

    public String internalToken() {
        String k = System.getenv(internalTokenEnv);
        if (k == null || k.isBlank()) k = System.getProperty(internalTokenEnv);
        return k == null ? "" : k.trim();
    }
}
