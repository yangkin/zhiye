package com.tcl.csot.zhiye.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 公司 SkillHub（fivestar-service aiToolbox Skill 市场）客户端。
 * 所有调用都带用户的平台令牌（Authorization: Bearer），身份、可见范围和额度由平台侧按令牌判定；织页不保存平台令牌。
 * 接口形状见 fivestar-service：AiToolboxSkillController、AiSkillCategoryController。
 */
public interface SkillHubClient {

    record Category(String id, String name, String description, Long skillCount) {}

    record Item(String skillId, String slug, String name, String summary, String owner, String ownerUserName, String categoryId,
                java.util.List<String> tags, String version, Long downloads, Long favorites, String riskLevel,
                String visibleOrgNamePath, String updatedAt) {}

    record Page<T>(java.util.List<T> items, long total, int pageNo, int limit) {}

    record Detail(Item item, String content, java.util.List<FileEntry> files, Long versionId, String reviewStatus, String categoryName,
                  Long downloads, java.util.List<VersionRow> versions, Boolean installed, Boolean premium) {}

    record VersionRow(Long versionId, String version, Integer reviewStatus, String changelog, Long packageSize, Integer fileCount) {}

    record FileEntry(String path, long size) {}

    record MySkill(String skillId, String slug, String name, String summary, String categoryId, String owner, java.util.List<String> tags,
                   String version, Long versionId, String latestVersion, Integer status, Integer reviewStatus,
                   boolean hasDraft, Integer fileCount, String updatedAt) {}


    /** 组织节点：level 从 1 开始，codePath 形如 "100_200"，namePath 是展示用的名称路径 */
    record ScopeNode(String orgCode, String orgName, int level, String codePath, String namePath) {}

    record PackageFile(String fileName, byte[] bytes) {}

    record Submission(Long skillId, Long versionId, String submissionKey, boolean reused) {}

    record PublishInput(String categoryId, String displayName, String owner, String slug, String summary, java.util.List<String> tags,
                        String version, boolean submitReview, String visibleOrgCodePath, String changeLog, Long skillId) {}

    record ReviewState(boolean inFlight, Long skillId, Long versionId, String version, Integer reviewStatus, Integer scanStatus) {}

    java.util.List<Category> categories(String token);

    /** 市场搜索：只返回已上架、当前用户可见的 skill */
    Page<Item> market(String token, String keyword, String categoryId, int pageNo, int limit);

    Detail detail(String token, long skillId);

    /** 我的 Skill（自己发布的，含审核中 / 草稿） */
    Page<MySkill> mySkills(String token, String keyword, int pageNo, int limit);

    /** 下载某个版本的 skill 包（zip 字节） */
    PackageFile packageFile(String token, long skillId, Long versionId);

    /** 可见范围选项：当前用户组织路径上的各级节点 */
    java.util.List<ScopeNode> scopeOptions(String token);

    /** 新增（skillId 为空）或编辑 skill 并提交审核 / 暂存；返回提交回执 */
    Submission publish(String token, PublishInput in, byte[] zip, String zipName);

    /** 某个版本的审核状态 */
    ReviewState reviewState(String token, long skillId, String version);

    /** 修改某个版本的可见范围（限定范围发布，覆盖即生效） */
    void setVisibleScope(String token, long skillId, long versionId, String visibleOrgCodePath);

    /** 撤回审核中的版本 */
    void withdraw(String token, long skillId, long versionId, String submissionKey);
}
