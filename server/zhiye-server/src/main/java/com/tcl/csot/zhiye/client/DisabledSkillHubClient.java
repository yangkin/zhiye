package com.tcl.csot.zhiye.client;

import com.tcl.csot.zhiye.exception.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/** 未开启 SkillHub 时使用：所有调用都返回 503。 */
@Component
@ConditionalOnProperty(name = "zhiye.skillhub.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledSkillHubClient implements SkillHubClient {
    private static ApiException off() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SKILLHUB_NOT_CONFIGURED", "SkillHub 还没有接入，配置 zhiye.skillhub 后即可浏览、安装和发布技能");
    }

    @Override public List<Category> categories(String token) { throw off(); }
    @Override public Page<Item> market(String token, String keyword, String categoryId, int pageNo, int limit) { throw off(); }
    @Override public Detail detail(String token, long skillId) { throw off(); }
    @Override public Page<MySkill> mySkills(String token, String keyword, int pageNo, int limit) { throw off(); }
    @Override public PackageFile packageFile(String token, long skillId, Long versionId) { throw off(); }
    @Override public List<ScopeNode> scopeOptions(String token) { throw off(); }
    @Override public Submission publish(String token, PublishInput in, byte[] zip, String zipName) { throw off(); }
    @Override public ReviewState reviewState(String token, long skillId, String version) { throw off(); }
    @Override public void setVisibleScope(String token, long skillId, long versionId, String visibleOrgCodePath) { throw off(); }
    @Override public void withdraw(String token, long skillId, long versionId, String submissionKey) { throw off(); }
}
