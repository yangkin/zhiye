package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限预设与声明式规则（cloud/policies.json）、预览身份、发布站点同源访问、数据控制台。
 * 免登录环境：预览里用「作者 / 普通员工 / 未登录」三种模拟身份。
 */
class CloudAccessTest extends IntegrationTest {
    static final String SCHEMA = """
            create table orders (code text, qty int, status text);
            create table stock (name text, qty int);
            create table feedback (content text);
            """;
    static final String POLICIES = """
            { "tables": {
              "orders": { "preset": "own_admin_read",
                "fields": { "qty": { "required": true, "min": 0 }, "code": { "unique": true }, "status": { "enum": ["待审", "通过", "驳回"] } },
                "transitions": { "status": [ { "from": "待审", "to": ["通过", "驳回"], "by": "admin" } ] } },
              "feedback": { "preset": "anon_submit" } } }
            """;

    /** 生成 → 写入表结构和规则 → 启用云服务；返回应用 id */
    static String cloudApp(Client c) {
        String id = c.createApp("做一个领料申请").path("id").asText();
        assertThat(c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        String v1 = c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText();
        JsonNode save = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of(
                Map.of("path", "cloud/schema.sql", "content", SCHEMA), Map.of("path", "cloud/policies.json", "content", POLICIES)))));
        assertThat(c.waitTask(save.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        c.ok(c.post("/api/apps/" + id + "/cloud/enable", Map.of()));
        return id;
    }

    /** 从预览页里取注入的配置（令牌带着模拟身份） */
    JsonNode previewCfg(Client c, String id, String as) {
        String url = local(c.ok(c.get("/api/apps/" + id)).path("previewUrl").asText());
        String html = c.get(url + (url.contains("?") ? "&" : "?") + "__as=" + as).body();
        Matcher m = Pattern.compile("window.__ZHIYE_CLOUD__=(\\{.*?\\});</script>").matcher(html);
        assertThat(m.find()).as("预览页注入了云服务配置：" + url + "\n" + html).isTrue();
        return Json.read(m.group(1));
    }

    @Test
    void policiesPreviewIdentitiesPublishAndConsole() {
        Client c = client();
        String id = cloudApp(c);

        JsonNode adminCfg = previewCfg(c, id, "admin"), memberCfg = previewCfg(c, id, "member"), anonCfg = previewCfg(c, id, "anon");
        assertThat(adminCfg.path("user").path("admin").asBoolean()).isTrue();
        assertThat(memberCfg.path("user").path("admin").asBoolean()).isFalse();
        assertThat(anonCfg.path("user").isNull()).isTrue();
        Map<String, String> admin = Map.of("Authorization", "Bearer " + adminCfg.path("token").asText());
        Map<String, String> member = Map.of("Authorization", "Bearer " + memberCfg.path("token").asText());
        Map<String, String> anon = Map.of("Authorization", "Bearer " + anonCfg.path("token").asText());
        String db = "/cloud/" + id + "/db/";
        assertThat(c.ok(c.send("GET", "/cloud/" + id + "/me", null, member)).path("user").path("name").asText()).isEqualTo("示例员工");

        // own_admin_read：自己提交、管理员看全部、未登录不能用
        JsonNode a1 = c.ok(c.send("POST", db + "orders", Map.of("code", "A1", "qty", 2, "status", "待审"), admin));
        assertThat(a1.path("created_by_name").asText()).isNotBlank();
        JsonNode m1 = c.ok(c.send("POST", db + "orders", Map.of("code", "M1", "qty", 1, "status", "待审"), member));
        assertThat(m1.path("created_by").asText()).isEqualTo("preview-member");
        assertThat(c.ok(c.send("GET", db + "orders", null, member)).path("total").asInt()).isEqualTo(1);
        assertThat(c.ok(c.send("GET", db + "orders", null, admin)).path("total").asInt()).isEqualTo(2);
        assertThat(c.send("GET", db + "orders", null, anon).status()).isEqualTo(401);
        assertThat(c.send("POST", db + "orders", Map.of("code", "X", "qty", 1), anon).status()).isEqualTo(401);

        // 字段规则：必填、最小值、唯一、枚举、新记录只能是流程起点
        assertThat(c.send("POST", db + "orders", Map.of("code", "M2"), member).body()).contains("必填");
        assertThat(c.send("POST", db + "orders", Map.of("code", "M2", "qty", -1), member).body()).contains("不能小于");
        assertThat(c.send("POST", db + "orders", Map.of("code", "M1", "qty", 1), member).body()).contains("已经存在");
        assertThat(c.send("POST", db + "orders", Map.of("code", "M3", "qty", 1, "status", "随便"), member).body()).contains("只能是");
        assertThat(c.send("POST", db + "orders", Map.of("code", "M4", "qty", 1, "status", "通过"), member).body()).contains("新记录");

        // 状态流转：只有管理员能审批；不能倒退；员工改不了别人的
        String m1Url = db + "orders/" + m1.path("id").asText();
        assertThat(c.send("PATCH", m1Url, Map.of("status", "通过"), member).status()).isEqualTo(403);
        assertThat(c.ok(c.send("PATCH", m1Url, Map.of("status", "通过"), admin)).path("status").asText()).isEqualTo("通过");
        assertThat(c.send("PATCH", m1Url, Map.of("status", "待审"), admin).status()).isEqualTo(400);
        assertThat(c.send("PATCH", db + "orders/" + a1.path("id").asText(), Map.of("qty", 9), member).status()).isEqualTo(403);
        assertThat(c.send("DELETE", db + "orders/" + a1.path("id").asText(), null, member).status()).isEqualTo(403);

        // anon_submit：未登录能提交，只有管理员能看
        c.ok(c.send("POST", db + "feedback", Map.of("content", "食堂太咸"), anon));
        assertThat(c.send("GET", db + "feedback", null, anon).status()).isEqualTo(401);
        assertThat(c.send("GET", db + "feedback", null, member).status()).isEqualTo(403);
        assertThat(c.ok(c.send("GET", db + "feedback", null, admin)).path("total").asInt()).isEqualTo(1);

        // 原子加减不会扣成负数；聚合在数据库里算
        JsonNode s = c.ok(c.send("POST", db + "stock", Map.of("name", "螺丝", "qty", 5), member));
        String inc = db + "stock/" + s.path("id").asText() + "/increment";
        assertThat(c.ok(c.send("POST", inc, Map.of("column", "qty", "delta", -3, "min", 0), member)).path("qty").asInt()).isEqualTo(2);
        assertThat(c.send("POST", inc, Map.of("column", "qty", "delta", -3, "min", 0), member).body()).contains("低于下限");
        c.ok(c.send("POST", db + "stock", Map.of("name", "螺丝", "qty", 4), member));
        JsonNode agg = c.ok(c.send("POST", db + "stock/aggregate", Map.of("groupBy", List.of("name"), "count", true, "sum", List.of("qty")), member));
        assertThat(agg.path("rows").get(0).path("count").asInt()).isEqualTo(2);
        assertThat(agg.path("rows").get(0).path("sum_qty").asInt()).isEqualTo(6);
        assertThat(c.ok(c.send("GET", db + "stock?qty__gte=3", null, member)).path("total").asInt()).isEqualTo(1);

        // 发布：orders 需要登录、应用却是内网公开 → 拦下；免登录环境不能选员工登录
        var blocked = c.post("/api/apps/" + id + "/publish", Map.of());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.body()).contains("ACCESS_MISMATCH").contains("orders");
        assertThat(c.put("/api/apps/" + id + "/access", Map.of("level", "login")).code()).isEqualTo("SSO_UNAVAILABLE");
        JsonNode acc = c.ok(c.get("/api/apps/" + id + "/access"));
        assertThat(acc.path("level").asText()).isEqualTo("public");
        assertThat(acc.path("tablesNeedingLogin").toString()).contains("orders");

        // 控制台：改预设后可以发布
        JsonNode ov = c.ok(c.send("PUT", "/api/apps/" + id + "/cloud/console/orders/preset", Map.of("preset", "shared"), Map.of()));
        assertThat(ov.path("counts").path("orders").asInt()).isEqualTo(2);
        JsonNode pub = c.ok(c.post("/api/apps/" + id + "/publish", Map.of()));

        // 发布站点：注入同源配置，不放令牌；写操作要带 X-Zhiye-Request
        String host = pub.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);
        Client visitor = new Client(port);
        String page = visitor.getWithHost("/", host).body();
        assertThat(page).contains("/__zhiye/sdk.js").contains("\"site\":true").contains("\"user\":null").doesNotContain("\"token\"");
        assertThat(visitor.send("GET", "/__zhiye/db/orders", null, Map.of("Host", host)).json().path("total").asInt()).isEqualTo(2);
        assertThat(visitor.send("POST", "/__zhiye/db/stock", Map.of("name", "垫片", "qty", 1), Map.of("Host", host)).status()).isEqualTo(400);
        JsonNode siteRow = visitor.ok(visitor.send("POST", "/__zhiye/db/stock", Map.of("name", "垫片", "qty", 1), Map.of("Host", host, "X-Zhiye-Request", "1")));
        assertThat(siteRow.path("created_by").isNull()).isTrue();
        assertThat(visitor.send("POST", "/__zhiye/db/feedback", Map.of("content", "匿名建议"), Map.of("Host", host, "X-Zhiye-Request", "1")).status()).isEqualTo(200);
        assertThat(visitor.send("GET", "/__zhiye/db/feedback", null, Map.of("Host", host)).status()).isEqualTo(401);
        assertThat(visitor.send("GET", "/__zhiye/me", null, Map.of("Host", host)).json().path("user").isNull()).isTrue();
        // 同源接口不在 api 域上开放（没有站点上下文）
        assertThat(visitor.get("/__zhiye/db/stock").status()).isEqualTo(404);

        // 控制台：导出（带 BOM 的 CSV）、导入（整批校验）、清空
        var csv = c.get("/api/apps/" + id + "/cloud/console/stock/export");
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.body()).startsWith("\uFEFF").contains("name").contains("螺丝");
        Map<String, Object> bad = new HashMap<>(Map.of("name", "坏行", "qty", "abc"));
        assertThat(c.post("/api/apps/" + id + "/cloud/console/stock/import", Map.of("rows", List.of(Map.of("name", "好行", "qty", 1), bad))).status()).isEqualTo(400);
        assertThat(c.ok(c.post("/api/apps/" + id + "/cloud/console/stock/import", Map.of("rows", List.of(Map.of("name", "导入1", "qty", 1), Map.of("name", "导入2", "qty", 2))))).path("inserted").asInt()).isEqualTo(2);
        assertThat(c.ok(c.get("/api/apps/" + id + "/cloud/console/stock/rows?name__like=导入")).path("total").asInt()).isEqualTo(2);
        assertThat(c.ok(c.post("/api/apps/" + id + "/cloud/console/stock/clear", Map.of())).path("deleted").asInt()).isEqualTo(5);

        // 别人的工作空间看不到控制台
        assertThat(new Client(port).get("/api/apps/" + id + "/cloud/console").status()).isEqualTo(404);
    }
}
