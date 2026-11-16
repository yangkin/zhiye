package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用可选员工登录（20.4.1）：指定人员的应用，访问者经平台统一登录换到只属于该子域名的会话；
 * 页面注入当前用户，云数据库记下提交人；不在名单里的人看到无权限页。
 */
@TestPropertySource(properties = {
        // 配置不同的测试上下文各用一个 schema：否则各上下文的任务工作进程会领走彼此的任务（配置不同，会失败）
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_sitelogin", "spring.flyway.schemas=ctx_sitelogin",
        "zhiye.auth.mode=sso", "zhiye.auth.sso.user-info-url={self}/test-sso/self",
        // 登录用户的应用会在 zhiye_test 里跨多次运行累积，放宽上限避免撞默认的 10 个
        "zhiye.workspace.max-apps=1000"})
class CloudSiteLoginTest extends IntegrationTest {

    private Client login(String who) {
        Client c = new Client(port);
        c.ok(c.send("POST", "/api/auth/sso/callback", Map.of("accessToken", "tok-" + who), Map.of()));
        return c;
    }

    private static String location(Client.Res r) {
        return r.raw().headers().firstValue("Location").orElseThrow();
    }

    private static String pathOf(String url) {
        URI u = URI.create(url);
        return u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }

    /** 访问者：打开站点 → 去平台登录入口 → 拿一次性码 → 回站点换会话 */
    private Client.Res enter(Client visitor, String host, String path) {
        var first = visitor.getWithHost(path, host);
        if (first.status() != 302) return first;
        String start = location(first);
        assertThat(start).contains("/api/auth/site/start?app=");
        var toSite = visitor.get(pathOf(start));
        assertThat(toSite.status()).isEqualTo(302);
        String finish = location(toSite);
        if (finish.startsWith("/login")) return toSite;
        assertThat(finish).contains("/__zhiye/auth/finish?code=");
        var back = visitor.getWithHost(pathOf(finish), host);
        assertThat(back.status()).isEqualTo(302);
        String cookie = back.raw().headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("zhiye_site=").contains("HttpOnly").doesNotContainIgnoringCase("Domain=");
        assertThat(pathOf(location(back))).isEqualTo(path);
        // 一次性码不能再用
        assertThat(visitor.getWithHost(pathOf(finish), host).status()).isEqualTo(400);
        return visitor.getWithHost(path, host);
    }

    @Test
    void membersOnlyAppWithSiteLogin() {
        Client alice = login("alice");
        String id = CloudAccessTest.cloudApp(alice);
        // orders 需要登录：内网公开时不能发布，改成指定人员后可以
        assertThat(alice.post("/api/apps/" + id + "/publish", Map.of()).status()).isEqualTo(409);
        assertThat(alice.put("/api/apps/" + id + "/access", Map.of("level", "members", "members", List.of())).status()).isEqualTo(400);
        JsonNode acc = alice.ok(alice.put("/api/apps/" + id + "/access", Map.of("level", "members", "members", List.of("bob"))));
        assertThat(acc.path("level").asText()).isEqualTo("members");
        assertThat(acc.path("author").path("loginName").asText()).isEqualTo("alice");
        JsonNode pub = alice.ok(alice.post("/api/apps/" + id + "/publish", Map.of()));
        String host = pub.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);

        // 未登录：页面跳到平台登录入口，静态资源直接 401，接口 401
        Client anon = new Client(port);
        var r = anon.getWithHost("/orders?tab=1", host);
        assertThat(r.status()).isEqualTo(302);
        assertThat(location(r)).contains("next=%2Forders%3Ftab%3D1");
        assertThat(anon.getWithHost("/assets/index.js", host).status()).isEqualTo(401);
        assertThat(anon.send("GET", "/__zhiye/db/orders", null, Map.of("Host", host)).status()).isEqualTo(401);
        // 平台也没登录：先去登录页，登录后回到入口
        var notLogged = enter(anon, host, "/");
        assertThat(location(notLogged)).startsWith("/login?site=1&redirect=");

        // 不在名单里
        Client carol = login("carol");
        var denied = enter(carol, host, "/");
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body()).contains("carol");
        assertThat(carol.send("GET", "/__zhiye/db/orders", null, Map.of("Host", host)).status()).isEqualTo(403);

        // 名单里的人：页面注入当前用户，写入记下提交人，只看到自己的
        Client bob = login("bob");
        var page = enter(bob, host, "/");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).contains("\"loginName\":\"bob\"").contains("\"admin\":false").doesNotContain("\"token\"");
        assertThat(bob.send("GET", "/__zhiye/me", null, Map.of("Host", host)).json().path("user").path("name").asText()).isEqualTo("员工bob");
        Map<String, String> h = Map.of("Host", host, "X-Zhiye-Request", "1");
        JsonNode row = bob.ok(bob.send("POST", "/__zhiye/db/orders", Map.of("code", "B1", "qty", 3, "status", "待审"), h));
        assertThat(row.path("created_by_name").asText()).isEqualTo("员工bob");
        assertThat(bob.send("PATCH", "/__zhiye/db/orders/" + row.path("id").asText(), Map.of("status", "通过"), h).status()).isEqualTo(403);

        // 作者是管理员：能看到全部、能审批
        var alicePage = enter(alice, host, "/");
        assertThat(alicePage.body()).contains("\"loginName\":\"alice\"").contains("\"admin\":true");
        assertThat(alice.ok(alice.send("PATCH", "/__zhiye/db/orders/" + row.path("id").asText(), Map.of("status", "通过"), h)).path("status").asText()).isEqualTo("通过");

        // 退出后回到需要登录的状态
        var out = bob.send("GET", "/__zhiye/auth/logout", null, Map.of("Host", host));
        assertThat(out.status()).isEqualTo(302);
        assertThat(bob.getWithHost("/", host).status()).isEqualTo(302);
    }
}
