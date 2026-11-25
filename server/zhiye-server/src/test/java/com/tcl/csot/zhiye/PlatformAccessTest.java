package com.tcl.csot.zhiye;

import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 人员管理：开启登录白名单后，仅名单内启用人员与管理员可登录；管理员可增删改名单。 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_access", "spring.flyway.schemas=ctx_access",
        "zhiye.auth.mode=sso", "zhiye.auth.sso.user-info-url={self}/test-sso/self",
        "zhiye.auth.sso.gateway-url={self}/test-sso",
        "zhiye.auth.login-allowlist-enabled=true", "zhiye.admin.allow-login-names=admin"})
class PlatformAccessTest extends IntegrationTest {

    private Client.Res callback(Client c, String who) {
        return c.send("POST", "/api/auth/sso/callback", Map.of("accessToken", "tok-" + who), Map.of());
    }

    @Test
    void allowlistControlsLoginAndAdminManagesPeople() {
        Client c = client();

        // 默认拒绝：不在名单里的普通员工不能登录，也不会建立会话
        Client.Res denied = callback(c, "zhangsan01");
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body()).contains("PLATFORM_ACCESS_DENIED");
        assertThat(c.ok(c.get("/api/meta")).path("auth").path("user").isNull()).isTrue();

        // 配置里的管理员始终可以登录
        Client admin = client();
        assertThat(callback(admin, "admin").status()).isEqualTo(200);
        assertThat(admin.ok(admin.get("/api/meta")).path("auth").path("user").path("loginName").asText()).isEqualTo("admin");

        // 未登录请求管理接口返回 401；登录后的非管理员返回 403（见下文）
        assertThat(c.get("/api/admin/login-allowlist").status()).isEqualTo(401);

        // 管理员添加人员 → 该员工可以登录
        assertThat(admin.ok(admin.post("/api/admin/login-allowlist",
                Map.of("loginName", "zhangsan01", "realName", "张三", "note", "试点部门"))).path("ok").asBoolean()).isTrue();
        assertThat(admin.ok(admin.get("/api/admin/login-allowlist")).toString()).contains("zhangsan01").contains("张三");
        Client user = client();
        assertThat(callback(user, "zhangsan01").status()).isEqualTo(200);
        assertThat(user.ok(user.get("/api/meta")).path("auth").path("user").path("loginName").asText()).isEqualTo("zhangsan01");
        // 已登录但不是管理员：不能维护名单
        assertThat(user.get("/api/admin/login-allowlist").status()).isEqualTo(403);

        // 停用后不能再登录
        assertThat(admin.ok(admin.patch("/api/admin/login-allowlist/zhangsan01", Map.of("enabled", false))).path("ok").asBoolean()).isTrue();
        Client.Res disabled = callback(client(), "zhangsan01");
        assertThat(disabled.status()).isEqualTo(403);

        // 重新启用 → 再登录；删除后又被拒
        assertThat(admin.ok(admin.patch("/api/admin/login-allowlist/zhangsan01", Map.of("enabled", true))).path("ok").asBoolean()).isTrue();
        assertThat(callback(client(), "zhangsan01").status()).isEqualTo(200);
        assertThat(admin.ok(admin.delete("/api/admin/login-allowlist/zhangsan01")).path("ok").asBoolean()).isTrue();
        assertThat(callback(client(), "zhangsan01").status()).isEqualTo(403);

        // 工号格式校验：批量语义下无效工号进入 failed 列表，不阻塞其他人
        var bad = admin.ok(admin.post("/api/admin/login-allowlist", Map.of("loginName", "bad name!!")));
        assertThat(bad.path("added").asInt()).isZero();
        assertThat(bad.path("failed").toString()).contains("bad name");
    }

    @Test
    void batchAddAndAdminRoleAssignment() {
        Client admin = client();
        assertThat(callback(admin, "admin").status()).isEqualTo(200);

        // 批量添加：一次多个工号；无效工号进 failed，不阻塞其他人
        var r = admin.ok(admin.post("/api/admin/login-allowlist",
                Map.of("loginNames", java.util.List.of("lisi02", "wangwu03", "bad name"), "realName", "", "note", "批量试点", "role", "user")));
        assertThat(r.path("added").asInt()).isEqualTo(2);
        assertThat(r.path("failed").toString()).contains("bad name");
        assertThat(callback(client(), "lisi02").status()).isEqualTo(200);
        assertThat(callback(client(), "wangwu03").status()).isEqualTo(200);

        // 添加时指定管理员：登录后即可访问管理接口（app_user.role 同步）
        assertThat(admin.ok(admin.post("/api/admin/login-allowlist",
                Map.of("loginName", "admin01", "role", "admin"))).path("added").asInt()).isEqualTo(1);
        Client newAdmin = client();
        assertThat(callback(newAdmin, "admin01").status()).isEqualTo(200);
        assertThat(newAdmin.get("/api/admin/login-allowlist").status()).isEqualTo(200);

        // 降为普通用户后立即失去管理权限
        assertThat(admin.ok(admin.patch("/api/admin/login-allowlist/admin01", Map.of("role", "user"))).path("ok").asBoolean()).isTrue();
        assertThat(newAdmin.get("/api/admin/login-allowlist").status()).isEqualTo(403);
    }

    @Test
    void employeeSearchProxiesCompanyDirectory() {
        Client admin = client();
        assertThat(callback(admin, "admin").status()).isEqualTo(200);
        Map<String, String> token = Map.of("Authorization", "Bearer tok-admin");

        // 按姓名搜索（中文）与按工号搜索都能命中；服务端代理公司 TPFM，浏览器不直连
        assertThat(admin.send("GET", "/api/admin/employees/search?q=杨", null, token).body())
                .contains("yangkai37").contains("杨凯");
        assertThat(admin.send("GET", "/api/admin/employees/search?q=zhangsan01", null, token).body())
                .contains("zhangsan01").contains("张三");
        assertThat(admin.send("GET", "/api/admin/employees/search?q=不存在的人", null, token).body()).doesNotContain("loginName");

        // 非管理员不能搜索；没带平台令牌时提示先登录
        Client nobody = client();
        assertThat(nobody.send("GET", "/api/admin/employees/search?q=杨", null, token).status()).isEqualTo(401);
    }
}
