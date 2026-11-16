package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 创造闭环：计划 → 生成 → 修改 → 可视化保存 → 发布 → 恢复 → 历史 / 导出（假 runner） */
class PipelineTest extends IntegrationTest {

    @Test
    void fullCreateFlow() throws Exception {
        Client c = client();
        // 元信息与工作空间
        JsonNode meta = c.ok(c.get("/api/meta"));
        assertThat(meta.path("modes").toString()).isEqualTo("[\"快速\",\"适中\",\"深度\"]");
        JsonNode ws = c.ok(c.get("/api/workspace"));
        assertThat(ws.path("recoveryUrl").asText()).contains("/recover#");

        // 空白需求 → 400
        var blank = c.post("/api/apps", Map.of("type", "开发网站", "prompt", "   ", "mode", "快速", "model", "auto", "assetIds", List.of()));
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.json().path("message").asText()).isNotBlank();

        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "做一个点击计数器", "mode", "快速", "model", "auto", "assetIds", List.of())));
        assertThat(created.path("phase").asText()).isEqualTo("planning");
        assertThat(created.path("activeTask").path("type").asText()).isEqualTo("plan");
        String appId = created.path("id").asText();
        assertThat(c.waitTask(created.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode app = c.ok(c.get("/api/apps/" + appId));
        assertThat(app.path("phase").asText()).isEqualTo("plan");
        assertThat(app.path("name").asText()).isEqualTo("点击计数器");
        assertThat(app.path("planDoc").asText()).contains("## 应用目标");

        // 编辑计划
        JsonNode edited = c.ok(c.patch("/api/apps/" + appId, Map.of("planDoc", app.path("planDoc").asText() + "\n补充：按钮圆角", "style", "mint")));
        assertThat(edited.path("style").asText()).isEqualTo("mint");

        // 生成
        JsonNode task = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of()));
        var busy = c.post("/api/apps/" + appId + "/messages", Map.of("content", "把标题改成红色"));
        assertThat(busy.status()).isEqualTo(409);
        assertThat(busy.code()).isEqualTo("APP_BUSY");
        JsonNode done = c.waitTask(task.path("id").asText());
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        assertThat(done.path("progress").asInt()).isEqualTo(100);
        app = c.ok(c.get("/api/apps/" + appId));
        assertThat(app.path("phase").asText()).isEqualTo("ready");
        assertThat(app.path("currentVersion").path("number").asInt()).isEqualTo(1);
        assertThat(app.path("previewUrl").asText()).startsWith("http://127.0.0.1:8083/p/");
        assertThat(app.path("activeTask").isNull()).isTrue();

        // 事件与断线续传
        List<JsonNode> events = c.events(task.path("id").asText(), 0, Map.of());
        assertThat(events.size()).isGreaterThan(3);
        for (int i = 1; i < events.size(); i++) assertThat(events.get(i).path("seq").asInt()).isGreaterThan(events.get(i - 1).path("seq").asInt());
        assertThat(events).anyMatch(e -> e.path("type").asText().equals("tool"));
        assertThat(events).anyMatch(e -> e.path("type").asText().equals("verify"));
        assertThat(events.get(events.size() - 1).path("type").asText()).isEqualTo("finished");
        assertThat(events.get(events.size() - 1).path("payload").path("versionId").asText()).isEqualTo(done.path("resultVersionId").asText());
        int mid = events.get(events.size() / 2).path("seq").asInt();
        assertThat(c.events(task.path("id").asText(), mid, Map.of())).allMatch(e -> e.path("seq").asInt() > mid);
        assertThat(c.events(task.path("id").asText(), 0, Map.of("Last-Event-ID", String.valueOf(mid)))).allMatch(e -> e.path("seq").asInt() > mid);

        // 预览版带 data-loc，经站点网关提供
        var preview = c.get(local(app.path("previewUrl").asText()));
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.raw().headers().firstValue("content-type").orElse("")).contains("text/html");
        assertThat(preview.body()).contains("data-loc=\"src/App.tsx:").contains("加一");
        assertThat(c.get(local(app.path("previewUrl").asText()).replace("index.html", "assets/app.css")).raw().headers()
                .firstValue("cache-control").orElse("")).contains("immutable");

        // 对话：用户与助手消息都在
        JsonNode msgs = c.ok(c.get("/api/apps/" + appId + "/messages"));
        assertThat(msgs.toString()).contains("\"role\":\"user\"").contains("\"role\":\"assistant\"");

        // 元素上下文：其他应用 → 400；文件不存在 → 400；正确 → 新版本
        String loc = "src/App.tsx:8:6";
        String v1 = app.path("currentVersion").path("id").asText();
        assertThat(c.post("/api/apps/" + appId + "/messages", Map.of("content", "把字体颜色改为蓝色",
                "elementContext", Map.of("appId", "other", "baseVersionId", v1, "loc", loc, "tag", "P", "text", "x"))).status()).isEqualTo(400);
        assertThat(c.post("/api/apps/" + appId + "/messages", Map.of("content", "把字体颜色改为蓝色",
                "elementContext", Map.of("appId", appId, "baseVersionId", v1, "loc", "src/Nope.tsx:1:0", "tag", "P", "text", "x"))).status()).isEqualTo(400);
        JsonNode posted = c.ok(c.post("/api/apps/" + appId + "/messages", Map.of("content", "把字体颜色改为蓝色",
                "elementContext", Map.of("appId", appId, "baseVersionId", v1, "loc", loc, "tag", "P", "text", "点一下按钮，数字就加一。"))));
        assertThat(posted.path("message").path("elementContext").path("loc").asText()).isEqualTo(loc);
        assertThat(posted.path("message").path("status").asText()).isEqualTo("pending");
        JsonNode mdone = c.waitTask(posted.path("task").path("id").asText());
        assertThat(mdone.path("status").asText()).as(mdone.toString()).isEqualTo("SUCCEEDED");
        app = c.ok(c.get("/api/apps/" + appId));
        assertThat(app.path("currentVersion").path("number").asInt()).isEqualTo(2);
        String v2 = app.path("currentVersion").path("id").asText();
        String src = c.get("/api/versions/" + v2 + "/file?path=src/App.tsx").body();
        assertThat(src).contains("<p style={{ color: '#2563eb' }}");

        // 草稿：发布 / 恢复被拦截；基线不对 → 409
        JsonNode draft = c.ok(c.put("/api/apps/" + appId + "/draft", Map.of("baseVersionId", v2,
                "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "灵感在这里发生", "styles", Map.of("fontWeight", "700"))))));
        assertThat(draft.path("patches").size()).isEqualTo(1);
        assertThat(c.ok(c.get("/api/apps/" + appId)).path("hasDraft").asBoolean()).isTrue();
        assertThat(c.ok(c.get("/api/apps/" + appId + "/draft")).path("baseVersionId").asText()).isEqualTo(v2);
        assertThat(c.post("/api/apps/" + appId + "/publish", Map.of()).code()).isEqualTo("DRAFT_PENDING");
        assertThat(c.post("/api/versions/" + v1 + "/restore", Map.of()).code()).isEqualTo("DRAFT_PENDING");
        assertThat(c.put("/api/apps/" + appId + "/draft", Map.of("baseVersionId", v1, "patches", List.of())).status()).isEqualTo(409);

        // 列表项补丁：index / match 校验
        for (Object badIndex : List.of(-1, "1", 1.5))
            assertThat(c.put("/api/apps/" + appId + "/draft", Map.of("baseVersionId", v2,
                    "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "x", "index", badIndex)))).status()).as(String.valueOf(badIndex)).isEqualTo(400);
        assertThat(c.put("/api/apps/" + appId + "/draft", Map.of("baseVersionId", v2,
                "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "x", "match", "长".repeat(201))))).status()).isEqualTo(400);
        // runner 拒绝补丁 → 任务失败（runner 的中文原因，index/match 原样传给 runner），草稿保留，没有新版本
        c.ok(c.put("/api/apps/" + appId + "/draft", Map.of("baseVersionId", v2,
                "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "x", "index", 2, "match", "__reject__")))));
        JsonNode rejected = c.ok(c.post("/api/apps/" + appId + "/draft/commit", Map.of()));
        JsonNode rj = c.waitTask(rejected.path("id").asText());
        assertThat(rj.path("status").asText()).isEqualTo("FAILED");
        assertThat(rj.path("error").path("message").asText()).contains("列表第 2 项是动态生成的，不能直接修改");
        assertThat(c.ok(c.get("/api/apps/" + appId + "/draft")).path("patches").get(0).path("index").asInt()).isEqualTo(2);
        assertThat(c.ok(c.get("/api/apps/" + appId + "/versions")).size()).isEqualTo(2);
        // 合法的 index / match 可以保存
        c.ok(c.put("/api/apps/" + appId + "/draft", Map.of("baseVersionId", v2,
                "patches", List.of(Map.of("loc", "src/App.tsx:7:6", "text", "灵感在这里发生", "styles", Map.of("fontWeight", "700"), "index", 0, "match", "点击计数器")))));
        JsonNode save = c.ok(c.post("/api/apps/" + appId + "/draft/commit", Map.of()));
        assertThat(save.path("type").asText()).isEqualTo("visual_save");
        assertThat(c.waitTask(save.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        app = c.ok(c.get("/api/apps/" + appId));
        assertThat(app.path("currentVersion").path("number").asInt()).isEqualTo(3);
        assertThat(app.path("currentVersion").path("title").asText()).isEqualTo("可视化编辑 · 1 处修改");
        assertThat(app.path("hasDraft").asBoolean()).isFalse();
        assertThat(c.get("/api/apps/" + appId + "/draft").status()).isEqualTo(404);
        String v3 = app.path("currentVersion").path("id").asText();
        assertThat(c.get("/api/versions/" + v3 + "/file?path=src/App.tsx").body()).contains("灵感在这里发生");

        // 发布 → 站点按 Host 提供发布版（无 data-loc）
        JsonNode pub = c.ok(c.post("/api/apps/" + appId + "/publish", Map.of()));
        assertThat(pub.path("versionId").asText()).isEqualTo(v3);
        String siteUrl = pub.path("siteUrl").asText();
        assertThat(siteUrl).matches("http://s[a-z0-9]+\\.localhost:8083/");
        String host = siteUrl.replace("http://", "").replace("/", "").replace(":8083", ":" + port);
        var site = c.getWithHost("/", host);
        assertThat(site.status()).isEqualTo(200);
        assertThat(site.body()).contains("灵感在这里发生").doesNotContain("data-loc");
        assertThat(c.getWithHost("/deep/link", host).body()).contains("灵感在这里发生"); // SPA 回退
        assertThat(c.getWithHost("/", "snotexist1.localhost:" + port).status()).isEqualTo(404);

        // 恢复 V1：新建 V4，线上版本仍是 V3，构建按文件树复用
        assertThat(c.post("/api/versions/" + v3 + "/restore", Map.of()).code()).isEqualTo("ALREADY_CURRENT");
        JsonNode versionsBefore = c.ok(c.get("/api/apps/" + appId + "/versions"));
        JsonNode restored = c.ok(c.post("/api/versions/" + v1 + "/restore", Map.of()));
        assertThat(restored.path("number").asInt()).isEqualTo(4);
        assertThat(restored.path("source").asText()).isEqualTo("restore");
        assertThat(restored.path("restoredFromNumber").asInt()).isEqualTo(1);
        assertThat(restored.path("isCurrent").asBoolean()).isTrue();
        JsonNode versions = c.ok(c.get("/api/apps/" + appId + "/versions"));
        assertThat(versions.size()).isEqualTo(4);
        assertThat(versions.get(0).path("number").asInt()).isEqualTo(4); // 编号降序
        for (int i = 0; i < versionsBefore.size(); i++) assertThat(versions.get(i + 1).path("id").asText()).isEqualTo(versionsBefore.get(i).path("id").asText());
        JsonNode published = null, v1row = null;
        for (JsonNode v : versions) { if (v.path("isPublished").asBoolean()) published = v; if (v.path("number").asInt() == 1) v1row = v; }
        assertThat(published.path("number").asInt()).isEqualTo(3);
        assertThat(restored.path("previewUrl").asText()).isEqualTo(v1row.path("previewUrl").asText());
        assertThat(c.getWithHost("/", host).body()).contains("灵感在这里发生");
        assertThat(c.get("/api/versions/" + restored.path("id").asText() + "/file?path=src/App.tsx").body()).doesNotContain("灵感在这里发生");

        // 历史
        JsonNode hist = c.ok(c.get("/api/history?appId=" + appId));
        assertThat(hist.size()).isEqualTo(4);
        for (int i = 1; i < hist.size(); i++)
            assertThat(hist.get(i - 1).path("version").path("createdAt").asText()).isGreaterThanOrEqualTo(hist.get(i).path("version").path("createdAt").asText());
        assertThat(c.ok(c.get("/api/history?filter=published")).toString()).contains(v3);
        c.ok(c.patch("/api/versions/" + v2, Map.of("favorite", true)));
        JsonNode fav = c.ok(c.get("/api/history?filter=favorite&appId=" + appId));
        assertThat(fav.size()).isEqualTo(1);
        assertThat(fav.get(0).path("version").path("number").asInt()).isEqualTo(2);
        assertThat(c.ok(c.get("/api/history?q=" + java.net.URLEncoder.encode("恢复自", "UTF-8"))).toString()).contains(restored.path("id").asText());

        // 文件、导出、页面下载
        JsonNode files = c.ok(c.get("/api/versions/" + v3 + "/files"));
        assertThat(files.toString()).contains("src/App.tsx").contains("package.json").doesNotContain(".zhiye").doesNotContain("node_modules");
        var zip = c.get("/api/versions/" + v3 + "/export");
        assertThat(zip.raw().body()[0]).isEqualTo((byte) 'P');
        assertThat(zip.raw().body()[1]).isEqualTo((byte) 'K');
        String page = c.get("/api/versions/" + v3 + "/page").body();
        assertThat(page).contains("<html").contains("<style>").doesNotContainPattern("<script[^>]+src=");

        // 应用管理：重命名、收藏、筛选、排序
        assertThat(c.patch("/api/apps/" + appId, Map.of("name", "")).status()).isEqualTo(400);
        c.ok(c.patch("/api/apps/" + appId, Map.of("name", "我的计数器", "favorite", true)));
        assertThat(c.ok(c.get("/api/apps?favorite=true")).toString()).contains(appId);
        assertThat(c.ok(c.get("/api/apps?published=published")).toString()).contains(appId);
        assertThat(c.ok(c.get("/api/apps?published=unpublished")).toString()).doesNotContain(appId);
        assertThat(c.ok(c.get("/api/apps?type=" + java.net.URLEncoder.encode("技术文档", "UTF-8"))).toString()).doesNotContain(appId);
        assertThat(c.ok(c.get("/api/apps?q=" + java.net.URLEncoder.encode("我的计数", "UTF-8"))).toString()).contains(appId);

        // 工作空间隔离
        Client stranger = client();
        assertThat(stranger.get("/api/apps/" + appId).status()).isEqualTo(404);
        assertThat(stranger.get("/api/versions/" + v1 + "/files").status()).isEqualTo(404);
        assertThat(stranger.get("/api/tasks/" + task.path("id").asText()).status()).isEqualTo(404);
        assertThat(stranger.post("/api/versions/" + v1 + "/restore", Map.of()).status()).isEqualTo(404);
        assertThat(stranger.ok(stranger.get("/api/apps")).size()).isEqualTo(0);

        // 恢复链接：新浏览器打开恢复页时 /api/meta 和 recover 一起发出，meta 不能顺手建工作空间、发新 cookie 盖掉恢复结果
        Client fresh = client();
        assertThat(fresh.get("/api/meta").raw().headers().firstValue("set-cookie")).isEmpty();
        String token = ws.path("recoveryUrl").asText().split("#")[1];
        assertThat(fresh.ok(fresh.post("/api/workspace/recover", Map.of("token", token))).path("id").asText()).isEqualTo(ws.path("id").asText());
        assertThat(fresh.ok(fresh.get("/api/workspace")).path("id").asText()).isEqualTo(ws.path("id").asText());
        assertThat(stranger.ok(stranger.post("/api/workspace/recover", Map.of("token", token))).path("id").asText()).isEqualTo(ws.path("id").asText());
        assertThat(stranger.ok(stranger.get("/api/apps")).toString()).contains(appId);
        assertThat(stranger.post("/api/workspace/recover", Map.of("token", "bad")).status()).isEqualTo(404);

        // 下线
        assertThat(c.post("/api/apps/" + appId + "/unpublish", Map.of()).status()).isEqualTo(204);
        assertThat(c.getWithHost("/", host).status()).isEqualTo(404);
        assertThat(c.ok(c.get("/api/apps/" + appId)).path("siteUrl").isNull()).isTrue();

        // 删除
        assertThat(c.delete("/api/apps/" + appId).status()).isEqualTo(204);
        assertThat(c.get("/api/apps/" + appId).status()).isEqualTo(404);
    }
}
