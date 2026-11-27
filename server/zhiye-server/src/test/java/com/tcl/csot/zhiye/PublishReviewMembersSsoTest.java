package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产完整组合场景端到端集成测试：
 * SSO + 平台登录白名单 + 发布审核门禁全开时，验证：
 * 1. 用户按「指定人员」提交发布审核，管理员审核队列与详情清晰看到完整指定人员名单、真实姓名及「是否平台白名单内」标识，且支持按指定人员检索；
 * 2. 审批通过后，未开通织页创作平台权限的指定员工可通过公司 SSO 免平台白名单直接访问已发布应用，且仍无法进入创作平台。
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_pubmembers",
        "spring.flyway.schemas=ctx_pubmembers",
        "zhiye.auth.mode=sso",
        "zhiye.auth.sso.user-info-url={self}/test-sso/self",
        "zhiye.auth.sso.gateway-url={self}/test-sso",
        "zhiye.auth.login-allowlist-enabled=true",
        "zhiye.admin.allow-login-names=admin",
        "zhiye.publish.review-required=true",
        "zhiye.workspace.max-apps=1000"
})
class PublishReviewMembersSsoTest extends IntegrationTest {

    private Map<String, Object> regForm(String appName) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("appName", appName);
        f.put("department", "品质管理部");
        f.put("businessBg", "跨产线品质异常处理需要定向共享给产线班组长与非平台协作同事。");
        f.put("painPoints", "邮件附件版本混乱，非平台员工无法查看实时页面。");
        f.put("solution", "建设内部品质异常跟踪页，按指定人员名单定向开放，不涉及敏感财务数据。");
        f.put("expectedBenefit", "每周节约 5 小时人工同步成本。");
        f.put("targetUsers", "科室使用");
        f.put("deployMode", "托管部署");
        f.put("contacts", "管理员");
        f.put("expectedUntil", "2027-12-31");
        f.put("techAndData", "静态页面 + SSO 指定人员访问控制。");
        return f;
    }

    @Test
    void adminReviewShowsSpecifiedMembersAndApprovedNonPlatformMemberCanOpenSite() {
        Client admin = client();
        assertThat(admin.send("POST", "/api/auth/sso/callback", Map.of("accessToken", "tok-admin"), Map.of()).status()).isEqualTo(200);

        // 把 zhangsan01 加入平台白名单；ext_lisi02 故意不加入平台白名单（仅作为已发布应用访客）
        admin.ok(admin.post("/api/admin/login-allowlist",
                Map.of("loginName", "zhangsan01", "realName", "张三", "note", "平台用户")));

        // 导入页面并设置「指定人员」访问范围
        JsonNode up = admin.ok(admin.upload("/api/imports/upload", "quality-page.html", "text/html",
                ImportFlowTest.GARDEN.getBytes(StandardCharsets.UTF_8), Map.of()));
        JsonNode imp = admin.ok(admin.post("/api/imports", Map.of("uploadId", up.path("uploadId").asText(), "name", "品质异常跟踪页")));
        String appId = imp.path("app").path("id").asText();
        assertThat(admin.waitTask(imp.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        admin.ok(admin.put("/api/apps/" + appId + "/access", Map.of(
                "level", "members",
                "members", List.of("zhangsan01", "ext_lisi02"),
                "admins", List.of("wangwu03")
        )));

        // 提交发布审核申请
        JsonNode submitted = admin.ok(admin.post("/api/apps/" + appId + "/publish-requests", Map.of("form", regForm("品质异常跟踪页"))));
        String reqId = submitted.path("id").asText();
        assertThat(submitted.path("accessLevel").asText()).isEqualTo("members");
        assertThat(submitted.path("accessMembers").toString()).contains("zhangsan01").contains("ext_lisi02");
        assertThat(submitted.path("accessAdmins").toString()).contains("wangwu03");

        // 管理侧查询审核列表：必须完整返回指定人员名单、真实姓名、是否平台白名单，并支持按指定人员检索
        JsonNode list = admin.ok(admin.get("/api/admin/publish-requests?status=pending"));
        JsonNode found = null;
        for (JsonNode it : list.path("items")) {
            if (reqId.equals(it.path("id").asText())) { found = it; break; }
        }
        assertThat(found).isNotNull();
        assertThat(found.path("accessLevel").asText()).isEqualTo("members");
        assertThat(found.path("accessMembers").size()).isEqualTo(2);
        assertThat(found.path("accessMemberDetails").size()).isEqualTo(2);

        JsonNode d0 = found.path("accessMemberDetails").get(0);
        JsonNode d1 = found.path("accessMemberDetails").get(1);
        assertThat(d0.path("loginName").asText()).isEqualTo("zhangsan01");
        assertThat(d0.path("realName").asText()).isEqualTo("张三");
        assertThat(d0.path("inPlatformAllowlist").asBoolean()).isTrue();
        assertThat(d1.path("loginName").asText()).isEqualTo("ext_lisi02");
        assertThat(d1.path("inPlatformAllowlist").asBoolean()).isFalse();
        assertThat(found.path("accessAuthor").path("loginName").asText()).isEqualTo("admin");

        // 按指定人员工号与中文名检索审核单均能命中
        assertThat(admin.ok(admin.get("/api/admin/publish-requests?status=pending&q=ext_lisi02")).path("items").toString()).contains(reqId);
        assertThat(admin.ok(admin.get("/api/admin/publish-requests?status=pending&q=" + java.net.URLEncoder.encode("张三", StandardCharsets.UTF_8))).path("items").toString()).contains(reqId);

        // 管理员审核通过上线
        JsonNode approved = admin.ok(admin.post("/api/admin/publish-requests/" + reqId + "/approve", Map.of("comment", "名单核验通过")));
        assertThat(approved.path("ok").asBoolean()).isTrue();
        assertThat(approved.path("request").path("accessMembers").toString()).contains("ext_lisi02");
        String host = approved.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);

        // 未开通平台白名单的 ext_lisi02 可通过站点 SSO 打开上线应用，且仍无法进入创作平台
        Client extLisi = client();
        var r1 = extLisi.getWithHost("/", host);
        assertThat(r1.status()).isEqualTo(302);
        URI startUri = URI.create(r1.raw().headers().firstValue("Location").orElseThrow());
        String startPath = startUri.getRawPath() + "?" + startUri.getRawQuery();
        var cb = extLisi.send("POST", "/api/auth/sso/callback", Map.of("accessToken", "tok-ext_lisi02", "redirect", startPath), Map.of());
        assertThat(cb.status()).isEqualTo(200);
        assertThat(cb.json().path("siteOnly").asBoolean()).isTrue();
        assertThat(extLisi.get("/api/apps").status()).isEqualTo(401);

        URI finishUri = URI.create(cb.json().path("siteRedirectUrl").asText());
        assertThat(extLisi.getWithHost(finishUri.getRawPath() + "?" + finishUri.getRawQuery(), host).status()).isEqualTo(302);
        var sitePage = extLisi.getWithHost("/", host);
        assertThat(sitePage.status()).isEqualTo(200);
        assertThat(sitePage.body()).contains("种下一份灵感").contains("\"loginName\":\"ext_lisi02\"");
    }
}
