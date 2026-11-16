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

/** 数据源：登记（凭证不外泄）→ 试调用 → 绑定应用 → 预览带令牌调用 → 发布站点同源调用 → 编码指令。 */
class DataSourceTest extends IntegrationTest {

    Map<String, Object> daily(String key) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", key);
        m.put("name", "产线日产量");
        m.put("description", "按产线和日期查询每小时产量");
        m.put("url", "http://127.0.0.1:" + port + "/test-source/daily/{line}?format=json");
        m.put("params", List.of(
                Map.of("name", "line", "type", "string", "description", "产线编号", "example", "L1"),
                Map.of("name", "date", "type", "date", "required", true, "description", "日期"),
                Map.of("name", "top", "type", "integer")));
        m.put("sample", "{\"rows\":[{\"hour\":8,\"output\":120}]}");
        m.put("authType", "bearer");
        m.put("secret", "s3cret-token");
        return m;
    }

    @Test
    void registerBindAndQuery() {
        Client c = client();
        JsonNode src = c.ok(c.post("/api/sources", daily("mes_daily")));
        String sid = src.path("id").asText();
        assertThat(src.path("hasSecret").asBoolean()).isTrue();
        assertThat(src.toString()).doesNotContain("s3cret").doesNotContain("secret_enc").doesNotContain("v1:");
        // 地址里的占位参数自动变成必填
        assertThat(src.path("params").get(0).path("required").asBoolean()).isTrue();
        assertThat(c.ok(c.get("/api/sources")).toString()).contains("mes_daily").doesNotContain("s3cret");

        // 试调用：路径参数、查询参数、原有查询串、鉴权头都由平台拼装
        JsonNode t = c.ok(c.post("/api/sources/" + sid + "/test", Map.of("params", Map.of("line", "L 1", "date", "2026-01-31", "top", 5))));
        assertThat(t.path("status").asInt()).isEqualTo(200);
        JsonNode d = t.path("data");
        assertThat(d.path("line").asText()).isEqualTo("L 1");
        assertThat(d.path("query").path("date").asText()).isEqualTo("2026-01-31");
        assertThat(d.path("query").path("top").asText()).isEqualTo("5");
        assertThat(d.path("query").path("format").asText()).isEqualTo("json");
        assertThat(d.path("auth").asText()).isEqualTo("Bearer s3cret-token");
        // 参数校验：缺必填、未知参数、类型不对
        assertThat(c.post("/api/sources/" + sid + "/test", Map.of("params", Map.of("line", "L1"))).body()).contains("缺少参数 date");
        assertThat(c.post("/api/sources/" + sid + "/test", Map.of("params", Map.of("line", "L1", "date", "2026-01-31", "x", 1))).body()).contains("没有参数 x");
        assertThat(c.post("/api/sources/" + sid + "/test", Map.of("params", Map.of("line", "L1", "date", "昨天"))).status()).isEqualTo(400);
        assertThat(c.post("/api/sources/" + sid + "/test", Map.of("params", Map.of("line", "L1", "date", "2026-01-31", "top", "abc"))).body()).contains("整数");
        assertThat(c.ok(c.get("/api/sources/" + sid + "/calls")).size()).isGreaterThanOrEqualTo(1);

        // 绑定到应用
        JsonNode app = c.createApp("做一个产线产量看板");
        String appId = app.path("id").asText();
        c.waitTask(c.ok(c.post("/api/apps/" + appId + "/generate", Map.of())).path("id").asText());
        JsonNode bound = c.ok(c.post("/api/apps/" + appId + "/sources", Map.of("sourceId", sid)));
        assertThat(bound.size()).isEqualTo(1);
        assertThat(c.ok(c.get("/api/apps/" + appId + "/sources")).get(0).path("key").asText()).isEqualTo("mes_daily");

        // 修改任务：指令里带上数据源，过程里有「读取数据源」
        JsonNode r = c.ok(c.post("/api/apps/" + appId + "/messages", Map.of("content", "用产线日产量做一个折线图")));
        String tid = r.path("task").path("id").asText();
        assertThat(c.waitTask(tid).path("status").asText()).isEqualTo("SUCCEEDED");
        String text = ChatAttachmentsTest.agentText(c, tid);
        assertThat(text).contains("已知数据源 mes_daily");
        assertThat(c.events(tid, 0, Map.of()).toString()).contains("读取数据源 · 产线日产量");

        // 预览：注入令牌，cloud=false（没启用云服务）
        JsonNode cur = c.ok(c.get("/api/apps/" + appId));
        String html = c.get(local(cur.path("previewUrl").asText())).body();
        assertThat(html).contains("__ZHIYE_CLOUD__").contains("\"cloud\":false").contains("/cloud/sdk.js");
        Matcher m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(html);
        assertThat(m.find()).isTrue();
        String token = m.group(1);
        var q = c.send("POST", "/cloud/" + appId + "/source/mes_daily", Map.of("params", Map.of("line", "L2", "date", "2026-02-01")),
                Map.of("Authorization", "Bearer " + token));
        assertThat(q.status()).isEqualTo(200);
        assertThat(Json.read(q.body()).path("data").path("line").asText()).isEqualTo("L2");
        assertThat(Json.read(q.body()).path("data").path("app").asText()).isEqualTo(appId);
        assertThat(c.send("POST", "/cloud/" + appId + "/source/other_key", Map.of(), Map.of("Authorization", "Bearer " + token)).status()).isEqualTo(404);
        assertThat(c.send("POST", "/cloud/" + appId + "/source/mes_daily", Map.of(), Map.of()).status()).isEqualTo(401);

        // 发布站点：同源调用，页面里没有令牌
        JsonNode pub = c.ok(c.post("/api/apps/" + appId + "/publish", Map.of()));
        String host = pub.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);
        String site = c.getWithHost("/", host).body();
        assertThat(site).contains("/__zhiye/sdk.js").contains("/__zhiye/source").doesNotContain("\"token\"");
        // 写请求要带 SDK 的自定义头（跨站表单发不出来）
        assertThat(c.send("POST", "/__zhiye/source/mes_daily", Map.of("params", Map.of("line", "L3", "date", "2026-02-02")), Map.of("Host", host)).status()).isEqualTo(400);
        Map<String, String> sh = Map.of("Host", host, "X-Zhiye-Request", "1");
        var sq = c.send("POST", "/__zhiye/source/mes_daily", Map.of("params", Map.of("line", "L3", "date", "2026-02-02")), sh);
        assertThat(sq.status()).isEqualTo(200);
        assertThat(Json.read(sq.body()).path("data").path("line").asText()).isEqualTo("L3");
        var bad = c.send("POST", "/__zhiye/source/mes_daily", Map.of("params", Map.of("line", "L3")), sh);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body()).contains("缺少参数 date");
        assertThat(c.send("GET", "/__zhiye/source/mes_daily", null, Map.of("Host", host)).status()).isEqualTo(404);

        // 解绑后不能再调用
        c.ok(c.delete("/api/apps/" + appId + "/sources/" + sid));
        assertThat(c.send("POST", "/__zhiye/source/mes_daily", Map.of("params", Map.of("line", "L3", "date", "2026-02-02")), sh).status()).isEqualTo(404);
        assertThat(c.getWithHost("/", host).body()).doesNotContain("/__zhiye/sdk.js");
    }

    @Test
    void validationIsolationAndUpstreamErrors() {
        Client c = client();
        String base = "http://127.0.0.1:" + port + "/test-source";
        Map<String, Object> m = daily("Bad-Key");
        assertThat(c.post("/api/sources", m).status()).isEqualTo(400);
        m = daily("k_userinfo"); m.put("url", "http://u:p@127.0.0.1/x");
        assertThat(c.post("/api/sources", m).body()).contains("账号密码");
        m = daily("k_ftp"); m.put("url", "ftp://127.0.0.1/x");
        assertThat(c.post("/api/sources", m).status()).isEqualTo(400);
        m = daily("k_ph"); m.put("url", base + "/daily/{nope}");
        assertThat(c.post("/api/sources", m).body()).contains("{nope}");
        m = daily("k_nosecret"); m.remove("secret");
        assertThat(c.post("/api/sources", m).body()).contains("凭证");
        m = daily("k_hdr"); m.put("authType", "header"); m.put("authHeader", "Host");
        assertThat(c.post("/api/sources", m).status()).isEqualTo(400);

        // 自定义请求头鉴权 + 修改时不传 secret 保留原凭证
        m = daily("k_header"); m.put("authType", "header"); m.put("authHeader", "X-Api-Key"); m.put("secret", "key-1");
        String sid = c.ok(c.post("/api/sources", m)).path("id").asText();
        assertThat(c.post("/api/sources", m).status()).isEqualTo(409); // 调用名重复
        c.ok(c.patch("/api/sources/" + sid, Map.of("name", "改名了")));
        JsonNode t = c.ok(c.post("/api/sources/" + sid + "/test", Map.of("params", Map.of("line", "L1", "date", "2026-01-01"))));
        assertThat(t.path("data").path("apiKey").asText()).isEqualTo("key-1");
        assertThat(t.path("data").path("auth").asText()).isEqualTo("null");

        // POST 查询：参数按类型放进 JSON
        String post = c.ok(c.post("/api/sources", Map.of("key", "k_post", "name", "搜索", "method", "POST", "url", base + "/search",
                "params", List.of(Map.of("name", "q"), Map.of("name", "n", "type", "number"), Map.of("name", "all", "type", "boolean"))))).path("id").asText();
        JsonNode echo = c.ok(c.post("/api/sources/" + post + "/test", Map.of("params", Map.of("q", "面板", "n", "2.5", "all", true)))).path("data").path("echo");
        assertThat(echo.path("q").asText()).isEqualTo("面板");
        assertThat(echo.path("n").isNumber()).isTrue();
        assertThat(echo.path("all").asBoolean()).isTrue();

        // 上游错误：500、跳转、超大、非 JSON
        String fail = c.ok(c.post("/api/sources", Map.of("key", "k_fail", "name", "失败", "url", base + "/fail"))).path("id").asText();
        var f = c.post("/api/sources/" + fail + "/test", Map.of());
        assertThat(f.status()).isEqualTo(502);
        assertThat(f.body()).contains("数据源返回 500").contains("boom");
        String redir = c.ok(c.post("/api/sources", Map.of("key", "k_redir", "name", "跳转", "url", base + "/redirect"))).path("id").asText();
        assertThat(c.post("/api/sources/" + redir + "/test", Map.of()).body()).contains("跳转");
        String big = c.ok(c.post("/api/sources", Map.of("key", "k_big", "name", "超大", "url", base + "/big"))).path("id").asText();
        assertThat(c.post("/api/sources/" + big + "/test", Map.of()).body()).contains("超过 2 MB");
        String text = c.ok(c.post("/api/sources", Map.of("key", "k_text", "name", "文本", "url", base + "/text"))).path("id").asText();
        assertThat(c.ok(c.post("/api/sources/" + text + "/test", Map.of())).path("data").asText()).startsWith("plain ");
        // 链路本地 / 元数据地址
        String meta = c.ok(c.post("/api/sources", Map.of("key", "k_meta", "name", "元数据", "url", "http://169.254.169.254/latest"))).path("id").asText();
        assertThat(c.post("/api/sources/" + meta + "/test", Map.of()).status()).isEqualTo(403);

        // 其他工作空间看不到、不能试调用、不能绑定
        Client other = client();
        assertThat(other.ok(other.get("/api/sources")).toString()).doesNotContain("k_header");
        assertThat(other.post("/api/sources/" + sid + "/test", Map.of()).status()).isEqualTo(404);
        JsonNode oapp = other.createApp("别人的应用");
        assertThat(other.post("/api/apps/" + oapp.path("id").asText() + "/sources", Map.of("sourceId", sid)).status()).isEqualTo(404);
        assertThat(other.patch("/api/sources/" + sid, Map.of("name", "x")).status()).isEqualTo(404);

        // 创建应用时带数据源 → 计划阶段就已绑定；别人的数据源 → 400
        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "做一个产量看板", "mode", "快速", "model", "auto",
                "assetIds", List.of(), "sourceIds", List.of(sid))));
        assertThat(c.ok(c.get("/api/apps/" + created.path("id").asText() + "/sources")).size()).isEqualTo(1);
        assertThat(other.post("/api/apps", Map.of("type", "开发网站", "prompt", "x", "mode", "快速", "model", "auto",
                "assetIds", List.of(), "sourceIds", List.of(sid))).status()).isEqualTo(400);

        // 删除数据源：绑定一起移除
        assertThat(c.delete("/api/sources/" + sid).status()).isEqualTo(204);
        assertThat(c.ok(c.get("/api/apps/" + created.path("id").asText() + "/sources")).size()).isZero();
    }
}
