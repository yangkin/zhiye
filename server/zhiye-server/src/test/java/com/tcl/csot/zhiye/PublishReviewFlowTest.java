package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 发布审核门禁与《Vibe Coding 应用登记表》全链路集成测试（开启 reviewRequired=true）。 */
@TestPropertySource(properties = "zhiye.publish.review-required=true")
class PublishReviewFlowTest extends IntegrationTest {

    private static final Map<String, String> ADMIN_HEADERS = Map.of("X-Zhiye-Admin-Token", "test-admin-token");

    private Map<String, Object> validForm(String appName) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("appName", appName);
        f.put("department", "数字化与工业智能中心");
        f.put("businessBg", "当前各厂区巡检与点检台账分散在本地表格，难以统一追溯与统计。");
        f.put("painPoints", "人工汇总耗时长且易出错，异常响应滞后。");
        f.put("solution", "建设内部在线点检与异常闭环工具，仅处理本部门脱敏点检数据，不接入核心财务库。");
        f.put("expectedBenefit", "每周节省 6 小时手工整理工时，降低异常漏跟率 80%。");
        f.put("targetUsers", "科室使用");
        f.put("deployMode", "托管部署");
        f.put("contacts", "杨凯");
        f.put("expectedUntil", "2027-12-31");
        f.put("techAndData", "React + 平台云数据库，内网隔离托管。");
        return f;
    }

    @Test
    void fullPublishReviewLifecycleGateRejectWithdrawResubmitAndApprove() {
        Client c = client();

        // 1. /api/meta 声明已开启发布审核门禁
        JsonNode meta = c.ok(c.get("/api/meta"));
        assertThat(meta.path("publishReviewRequired").asBoolean()).isTrue();

        // 2. 导入 HTML 页面：开启审核门禁时不会跳过审核直接公开发布上线
        JsonNode up = c.ok(c.upload("/api/imports/upload", "review-app.html", "text/html",
                ImportFlowTest.GARDEN.getBytes(StandardCharsets.UTF_8), Map.of()));
        JsonNode imp = c.ok(c.post("/api/imports", Map.of("uploadId", up.path("uploadId").asText(), "name", "发布审核测试站点")));
        String appId = imp.path("app").path("id").asText();
        assertThat(c.waitTask(imp.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode appBefore = c.ok(c.get("/api/apps/" + appId));
        assertThat(appBefore.path("publishedVersionId").isNull()).isTrue();
        assertThat(appBefore.path("siteUrl").isNull()).isTrue();

        // 3. 禁止绕过审核直接调 POST /api/apps/{id}/publish
        var bypass = c.post("/api/apps/" + appId + "/publish", Map.of());
        assertThat(bypass.status()).isEqualTo(403);
        assertThat(bypass.code()).isEqualTo("PUBLISH_REVIEW_REQUIRED");

        // 4. 必填项校验（缺少应用所属部门 / 非法日期）
        Map<String, Object> badForm = new LinkedHashMap<>(validForm("发布审核测试站点"));
        badForm.remove("department");
        assertThat(c.post("/api/apps/" + appId + "/publish-requests", Map.of("form", badForm)).status()).isEqualTo(400);

        Map<String, Object> badDate = new LinkedHashMap<>(validForm("发布审核测试站点"));
        badDate.put("expectedUntil", "not-a-date");
        assertThat(c.post("/api/apps/" + appId + "/publish-requests", Map.of("form", badDate)).status()).isEqualTo(400);

        // 5. 提交完整《Vibe Coding 应用登记表》 -> 生成 pending 申请，禁止重复提交
        JsonNode req1 = c.ok(c.post("/api/apps/" + appId + "/publish-requests", Map.of("form", validForm("发布审核测试站点"))));
        String req1Id = req1.path("id").asText();
        assertThat(req1.path("status").asText()).isEqualTo("pending");
        assertThat(req1.path("department").asText()).isEqualTo("数字化与工业智能中心");
        assertThat(req1.path("contacts").asText()).isEqualTo("杨凯");
        assertThat(req1.path("expectedUntil").asText()).isEqualTo("2027-12-31");

        var dup = c.post("/api/apps/" + appId + "/publish-requests", Map.of("form", validForm("发布审核测试站点")));
        assertThat(dup.status()).isEqualTo(409);

        // 应用摘要已带上 pendingPublishRequest 且仍未上线
        JsonNode appPending = c.ok(c.get("/api/apps/" + appId));
        assertThat(appPending.path("pendingPublishRequest").path("id").asText()).isEqualTo(req1Id);
        assertThat(appPending.path("publishedVersionId").isNull()).isTrue();

        // 6. 申请人撤回申请 -> cancelled
        JsonNode cancelled = c.ok(c.post("/api/apps/" + appId + "/publish-requests/" + req1Id + "/cancel", Map.of()));
        assertThat(cancelled.path("status").asText()).isEqualTo("cancelled");

        // 7. 重新提交 -> 管理员无权限被拒绝；管理员驳回必须填原因
        JsonNode req2 = c.ok(c.post("/api/apps/" + appId + "/publish-requests", Map.of("form", validForm("发布审核测试站点"))));
        String req2Id = req2.path("id").asText();

        assertThat(c.get("/api/admin/publish-requests").status()).isEqualTo(403);
        JsonNode adminList = c.ok(c.send("GET", "/api/admin/publish-requests?status=pending", null, ADMIN_HEADERS));
        assertThat(adminList.path("pendingCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(adminList.path("items").toString()).contains(req2Id);

        assertThat(c.send("POST", "/api/admin/publish-requests/" + req2Id + "/reject", Map.of("comment", "   "),
                ADMIN_HEADERS).status()).isEqualTo(400);

        JsonNode rejRes = c.ok(c.send("POST", "/api/admin/publish-requests/" + req2Id + "/reject",
                Map.of("comment", "请补充数据脱敏与留存周期说明"),
                ADMIN_HEADERS));
        assertThat(rejRes.path("request").path("status").asText()).isEqualTo("rejected");
        assertThat(rejRes.path("request").path("reviewComment").asText()).contains("数据脱敏");

        // 驳回后仍未上线，且 defaults 自动预填上次表单
        JsonNode appRejected = c.ok(c.get("/api/apps/" + appId));
        assertThat(appRejected.path("publishedVersionId").isNull()).isTrue();
        assertThat(appRejected.path("latestPublishRequest").path("status").asText()).isEqualTo("rejected");
        JsonNode listAfterReject = c.ok(c.get("/api/apps/" + appId + "/publish-requests"));
        assertThat(listAfterReject.path("defaults").path("department").asText()).isEqualTo("数字化与工业智能中心");

        // 8. 补充说明后再次提交 -> 管理员审核通过 -> 立即正式发布上线！
        Map<String, Object> revised = new LinkedHashMap<>(validForm("发布审核测试站点"));
        revised.put("techAndData", "数据按 180 天归档，已做字段脱敏，仅科室人员内网可见。");
        JsonNode req3 = c.ok(c.post("/api/apps/" + appId + "/publish-requests", Map.of("form", revised)));
        String req3Id = req3.path("id").asText();

        JsonNode approveRes = c.ok(c.send("POST", "/api/admin/publish-requests/" + req3Id + "/approve",
                Map.of("comment", "核验通过，准予正式发布"),
                ADMIN_HEADERS));
        assertThat(approveRes.path("ok").asBoolean()).isTrue();
        assertThat(approveRes.path("request").path("status").asText()).isEqualTo("approved");
        String siteUrl = approveRes.path("siteUrl").asText();
        assertThat(siteUrl).isNotBlank();

        // 9. 验证应用已正式上线且公网/内网站点可正常访问，管理审计日志完整留存
        JsonNode appLive = c.ok(c.get("/api/apps/" + appId));
        assertThat(appLive.path("publishedVersionId").asText()).isNotBlank();
        assertThat(appLive.path("siteUrl").asText()).isEqualTo(siteUrl);
        String host = siteUrl.replace("http://", "").replace("/", "").replace(":8083", ":" + port);
        assertThat(c.getWithHost("/", host).body()).contains("种下一份灵感");

        JsonNode overview = c.ok(c.send("GET", "/api/admin/overview", null, ADMIN_HEADERS));
        assertThat(overview.path("auditLogs").toString()).contains("PUBLISH_APPROVE").contains("PUBLISH_REJECT");
    }
}
