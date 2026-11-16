package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 导入代码并部署（假 runner）：HTML、ZIP、失败、不支持、取消。 */
class ImportFlowTest extends IntegrationTest {
    static final String GARDEN = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>小小花园</title></head><body>\n"
            + "<h1>让灵感，自然生长。</h1>\n<p>这是真实导入的 HTML 页面。</p>\n<button onclick=\"this.textContent='已记录一份灵感'\">种下一份灵感</button>\n</body></html>\n";

    List<String> ids(Client c) {
        return c.ok(c.get("/api/apps")).findValues("id").stream().map(JsonNode::asText).toList();
    }

    static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (var e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    void htmlImportDeploysAndIsEditable() {
        Client c = client();
        var up = c.upload("/api/imports/upload", "小小花园.html", "text/html", GARDEN.getBytes(StandardCharsets.UTF_8), Map.of());
        JsonNode m = c.ok(up);
        assertThat(m.path("framework").asText()).isEqualTo("HTML");
        assertThat(m.path("deployable").asBoolean()).isTrue();
        assertThat(m.path("fileCount").asInt()).isEqualTo(1);
        assertThat(m.path("name").asText()).isEqualTo("小小花园");

        JsonNode r = c.ok(c.post("/api/imports", Map.of("uploadId", m.path("uploadId").asText(), "name", "小小花园")));
        String appId = r.path("app").path("id").asText();
        assertThat(ids(c)).doesNotContain(appId);
        JsonNode done = c.waitTask(r.path("task").path("id").asText());
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");
        JsonNode app = c.ok(c.get("/api/apps/" + appId));
        assertThat(ids(c)).contains(appId);
        assertThat(app.path("source").asText()).isEqualTo("import");
        assertThat(app.path("framework").asText()).isEqualTo("HTML");
        assertThat(app.path("visualEditable").asBoolean()).isTrue();
        assertThat(app.path("siteUrl").asText()).isNotBlank();
        JsonNode versions = c.ok(c.get("/api/apps/" + appId + "/versions"));
        assertThat(versions.size()).isEqualTo(1);
        assertThat(versions.get(0).path("isPublished").asBoolean()).isTrue();
        assertThat(versions.get(0).path("title").asText()).isEqualTo("导入代码并部署");
        assertThat(c.ok(c.get("/api/history?appId=" + appId)).size()).isEqualTo(1);

        String host = app.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);
        assertThat(c.getWithHost("/", host).body()).contains("种下一份灵感").doesNotContain("data-loc");
        String preview = c.get(local(app.path("previewUrl").asText())).body();
        assertThat(preview).contains("data-loc=\"index.html:2:0\"");

        c.ok(c.put("/api/apps/" + appId + "/draft", Map.of("baseVersionId", app.path("currentVersion").path("id").asText(),
                "patches", List.of(Map.of("loc", "index.html:2:0", "text", "让想法，自然生长。", "styles", Map.of("color", "#4f78eb"))))));
        JsonNode save = c.ok(c.post("/api/apps/" + appId + "/draft/commit", Map.of()));
        assertThat(c.waitTask(save.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode cur = c.ok(c.get("/api/apps/" + appId));
        String src = c.get("/api/versions/" + cur.path("currentVersion").path("id").asText() + "/file?path=index.html").body();
        assertThat(src).contains("让想法，自然生长。").contains("onclick=").doesNotContain("data-loc").contains("color:#4f78eb");
        // 线上版本还是 V1
        assertThat(c.getWithHost("/", host).body()).contains("让灵感，自然生长。");
    }

    @Test
    void folderZipImport() throws Exception {
        Client c = client();
        byte[] data = zip(Map.of("HTML项目/index.html", GARDEN, "HTML项目/about.html", "<p>关于</p>"));
        JsonNode m = c.ok(c.upload("/api/imports/upload", "HTML项目.zip", "application/zip", data, Map.of("folderName", "HTML项目")));
        assertThat(m.path("name").asText()).isEqualTo("HTML项目");
        assertThat(m.path("framework").asText()).isEqualTo("静态站点");
        JsonNode r = c.ok(c.post("/api/imports", Map.of("uploadId", m.path("uploadId").asText(), "name", "文件夹花园")));
        assertThat(c.waitTask(r.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode files = c.ok(c.get("/api/versions/" + c.ok(c.get("/api/apps/" + r.path("app").path("id").asText())).path("currentVersion").path("id").asText() + "/files"));
        assertThat(files.toString()).contains("\"index.html\"").contains("\"about.html\"").doesNotContain("HTML项目/");
    }

    @Test
    void fullStackRepoImportsOnlyChosenFrontendDir() throws Exception {
        Client c = client();
        byte[] data = zip(Map.of("repo/pom.xml", "<project/>", "repo/src/main/java/App.java", "class App {}",
                "repo/web/index.html", GARDEN, "repo/web/app.js", "fetch('/api/list')"));
        JsonNode m = c.ok(c.upload("/api/imports/upload", "repo.zip", "application/zip", data, Map.of()));
        assertThat(m.path("deployable").asBoolean()).isTrue();
        assertThat(m.path("rootDir").asText()).isEqualTo("web");
        assertThat(m.path("warnings").findValuesAsText("code")).contains("backend", "api");
        var bad = c.post("/api/imports", Map.of("uploadId", m.path("uploadId").asText(), "name", "x", "rootDir", "src"));
        assertThat(bad.status()).isEqualTo(400);
        JsonNode r = c.ok(c.post("/api/imports", Map.of("uploadId", m.path("uploadId").asText(), "name", "前后端仓库", "rootDir", "web")));
        assertThat(c.waitTask(r.path("task").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode files = c.ok(c.get("/api/versions/" + c.ok(c.get("/api/apps/" + r.path("app").path("id").asText())).path("currentVersion").path("id").asText() + "/files"));
        assertThat(files.toString()).contains("\"index.html\"").contains("\"app.js\"").doesNotContain("pom.xml").doesNotContain("web/");
    }

    @Test
    void invalidAndUnsupported() throws Exception {
        Client c = client();
        var bad = c.upload("/api/imports/upload", "坏文件.zip", "application/zip", "这不是 zip".getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.code()).isEqualTo("IMPORT_INVALID");
        assertThat(c.upload("/api/imports/upload", "slip.zip", "application/zip", zip(Map.of("../evil.html", "x", "index.html", "ok")), Map.of()).status()).isEqualTo(400);
        assertThat(c.upload("/api/imports/upload", "empty.html", "text/html", "   ".getBytes(), Map.of()).status()).isEqualTo(400);
        assertThat(c.upload("/api/imports/upload", "x.exe", "application/octet-stream", "MZ".getBytes(), Map.of()).status()).isEqualTo(400);

        JsonNode n = c.ok(c.upload("/api/imports/upload", "next-app.zip", "application/zip",
                zip(Map.of("next-app/package.json", "{\"dependencies\":{\"next\":\"15.0.0\",\"react\":\"19.0.0\"}}")), Map.of()));
        assertThat(n.path("framework").asText()).isEqualTo("Next.js");
        assertThat(n.path("deployable").asBoolean()).isFalse();
        List<String> before = ids(c);
        var rej = c.post("/api/imports", Map.of("uploadId", n.path("uploadId").asText(), "name", "next"));
        assertThat(rej.status()).isEqualTo(400);
        assertThat(rej.code()).isEqualTo("IMPORT_UNSUPPORTED");
        assertThat(ids(c)).isEqualTo(before);
        // 其他工作空间看不到这个上传
        Client other = client();
        assertThat(other.post("/api/imports", Map.of("uploadId", n.path("uploadId").asText(), "name", "x")).status()).isEqualTo(404);
    }

    @Test
    void failedBuildAndCancelLeaveNoApp() throws Exception {
        Client c = client();
        // 构建失败
        JsonNode m = c.ok(c.upload("/api/imports/upload", "a.html", "text/html", GARDEN.getBytes(StandardCharsets.UTF_8), Map.of()));
        List<String> before = ids(c);
        faults.set(null, "error");
        JsonNode r = c.ok(c.post("/api/imports", Map.of("uploadId", m.path("uploadId").asText(), "name", "坏项目")));
        JsonNode done = c.waitTask(r.path("task").path("id").asText());
        faults.set(null, "none");
        assertThat(done.path("status").asText()).isEqualTo("FAILED");
        assertThat(done.path("error").path("message").asText()).isNotBlank();
        assertThat(done.path("retryable").asBoolean()).isFalse();
        assertThat(ids(c)).isEqualTo(before);
        assertThat(c.get("/api/apps/" + r.path("app").path("id").asText()).status()).isEqualTo(404);
        // 取消
        JsonNode m2 = c.ok(c.upload("/api/imports/upload", "b.html", "text/html", GARDEN.getBytes(StandardCharsets.UTF_8), Map.of()));
        JsonNode r2 = c.ok(c.post("/api/imports", Map.of("uploadId", m2.path("uploadId").asText(), "name", "要取消的导入")));
        c.ok(c.post("/api/tasks/" + r2.path("task").path("id").asText() + "/cancel", Map.of()));
        JsonNode t2 = c.waitTask(r2.path("task").path("id").asText());
        assertThat(t2.path("status").asText()).isIn("CANCELLED", "SUCCEEDED");
        if ("CANCELLED".equals(t2.path("status").asText())) {
            assertThat(c.get("/api/apps/" + r2.path("app").path("id").asText()).status()).isEqualTo(404);
            assertThat(ids(c)).isEqualTo(before);
        }
    }
}
