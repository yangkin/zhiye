package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 云服务文件存储端到端测试：
 * - 预览令牌面（/cloud/{appId}/storage）上传 / 下载（query 与子路径）/ 列举 / 删除
 * - Multipart 文件上传（支持自定义 path）与返回值 url 直接可用性验证
 * - 发布站点同源面（/__zhiye/storage）读写与列表
 * - 控制台（/api/apps/{id}/cloud/console/files）查看、下载与删除
 * - 安全防护：MIME 推断、HTML/SVG 强制 attachment 防存储型 XSS、路径穿越拦截、>10MB 拦截与跨应用隔离
 */
class CloudStorageTest extends IntegrationTest {

    private JsonNode previewCfg(Client c, String id) {
        String url = local(c.ok(c.get("/api/apps/" + id)).path("previewUrl").asText());
        String html = c.get(url).body();
        Matcher m = Pattern.compile("window.__ZHIYE_CLOUD__=(\\{.*?\\});</script>").matcher(html);
        assertThat(m.find()).as("预览页注入云服务配置").isTrue();
        return Json.read(m.group(1));
    }

    private HttpResponse<byte[]> raw(String method, String path, byte[] body, Map<String, String> headers) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void cloudStorageFullLifecyclePreviewSiteConsoleAndSecurity() throws Exception {
        Client c = client();
        String appId = CloudAccessTest.cloudApp(c);
        String otherAppId = CloudAccessTest.cloudApp(c);

        JsonNode cfg = previewCfg(c, appId);
        Map<String, String> auth = Map.of("Authorization", "Bearer " + cfg.path("token").asText());

        // 1. 预览面原始字节上传（带嵌套目录 reports/2026/yield.csv）
        byte[] csvBytes = "lot,yield\nT11-001,98.6\n".getBytes(StandardCharsets.UTF_8);
        var up1 = raw("POST", "/cloud/" + appId + "/storage?path=reports/2026/yield.csv", csvBytes,
                Map.of("Authorization", auth.get("Authorization"), "Content-Type", "text/csv"));
        assertThat(up1.statusCode()).isEqualTo(200);
        JsonNode up1Json = Json.read(new String(up1.body(), StandardCharsets.UTF_8));
        assertThat(up1Json.path("path").asText()).isEqualTo("reports/2026/yield.csv");
        assertThat(up1Json.path("size").asInt()).isEqualTo(csvBytes.length);
        assertThat(up1Json.path("contentType").asText()).contains("text/csv");

        // 2. 用返回值里的 url 直接下载（验证返回 url 不再 404），并验证子路径路由也可下载
        String returnedUrl = up1Json.path("url").asText();
        var dlByUrl = raw("GET", returnedUrl, null, auth);
        assertThat(dlByUrl.statusCode()).isEqualTo(200);
        assertThat(dlByUrl.headers().firstValue("content-type").orElse("")).contains("text/csv");
        assertThat(dlByUrl.body()).isEqualTo(csvBytes);

        var dlBySubpath = raw("GET", "/cloud/" + appId + "/storage/reports/2026/yield.csv", null, auth);
        assertThat(dlBySubpath.statusCode()).isEqualTo(200);
        assertThat(dlBySubpath.body()).isEqualTo(csvBytes);

        // 3. Multipart 上传（带自定义 path=docs/spec.pdf）
        byte[] pdfBytes = "%PDF-1.4\nhello-cloud-storage\n".getBytes(StandardCharsets.UTF_8);
        String boundary = "----zhiyeStorageTest";
        byte[] multiBody = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"spec.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"
                + new String(pdfBytes, StandardCharsets.UTF_8) + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        var upMulti = raw("POST", "/cloud/" + appId + "/storage/upload?path=docs/spec.pdf", multiBody,
                Map.of("Authorization", auth.get("Authorization"), "Content-Type", "multipart/form-data; boundary=" + boundary));
        assertThat(upMulti.statusCode()).isEqualTo(200);
        assertThat(Json.read(new String(upMulti.body(), StandardCharsets.UTF_8)).path("path").asText()).isEqualTo("docs/spec.pdf");
        var dlPdf = raw("GET", "/cloud/" + appId + "/storage?path=docs/spec.pdf", null, auth);
        assertThat(dlPdf.statusCode()).isEqualTo(200);
        assertThat(dlPdf.headers().firstValue("content-type").orElse("")).contains("application/pdf");
        assertThat(dlPdf.body()).isEqualTo(pdfBytes);

        // 4. 存储型 XSS 防护：上传 HTML/SVG 文件下载时必须强制 attachment + nosniff
        byte[] htmlBytes = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        assertThat(raw("POST", "/cloud/" + appId + "/storage?path=uploads/poc.html", htmlBytes, auth).statusCode()).isEqualTo(200);
        var dlHtml = raw("GET", "/cloud/" + appId + "/storage?path=uploads/poc.html", null, auth);
        assertThat(dlHtml.statusCode()).isEqualTo(200);
        assertThat(dlHtml.headers().firstValue("content-disposition").orElse("")).contains("attachment");
        assertThat(dlHtml.headers().firstValue("x-content-type-options").orElse("")).isEqualTo("nosniff");

        // 5. 列举文件 & 云服务控制台查看/下载
        JsonNode listed = c.ok(c.send("GET", "/cloud/" + appId + "/storage", null, auth));
        assertThat(listed.toString()).contains("reports/2026/yield.csv").contains("docs/spec.pdf").contains("uploads/poc.html");

        JsonNode consoleFiles = c.ok(c.get("/api/apps/" + appId + "/cloud/console/files"));
        assertThat(consoleFiles.path("files").toString()).contains("reports/2026/yield.csv");
        var consoleDl = c.get("/api/apps/" + appId + "/cloud/console/files/download?path=reports/2026/yield.csv");
        assertThat(consoleDl.status()).isEqualTo(200);
        assertThat(consoleDl.body()).contains("T11-001,98.6");

        // 6. 发布站点同源面 /__zhiye/storage 读写与返回 url 正确指向 /__zhiye/storage?path=...
        c.ok(c.put("/api/apps/" + appId + "/cloud/console/orders/preset", Map.of("preset", "anon_submit")));
        JsonNode pub = c.ok(c.post("/api/apps/" + appId + "/publish", Map.of()));
        String host = pub.path("siteUrl").asText().replace("http://", "").replace("/", "").replace(":8083", ":" + port);
        byte[] siteTxt = "saved-from-published-site".getBytes(StandardCharsets.UTF_8);
        var siteUp = raw("POST", "/__zhiye/storage?path=site/note.txt", siteTxt, Map.of("Host", host, "X-Zhiye-Request", "1"));
        assertThat(siteUp.statusCode()).isEqualTo(200);
        JsonNode siteUpJson = Json.read(new String(siteUp.body(), StandardCharsets.UTF_8));
        assertThat(siteUpJson.path("url").asText()).startsWith("/__zhiye/storage?path=");
        var siteDl = raw("GET", siteUpJson.path("url").asText(), null, Map.of("Host", host));
        assertThat(siteDl.statusCode()).isEqualTo(200);
        assertThat(new String(siteDl.body(), StandardCharsets.UTF_8)).isEqualTo("saved-from-published-site");

        // 7. 删除文件（DELETE /storage?path=...）后 GET 返回 404
        var del = raw("DELETE", "/cloud/" + appId + "/storage?path=uploads/poc.html", null, auth);
        assertThat(del.statusCode()).isEqualTo(204);
        assertThat(raw("GET", "/cloud/" + appId + "/storage?path=uploads/poc.html", null, auth).statusCode()).isEqualTo(404);

        // 8. 安全与配额边界：路径穿越 400、单文件 >10MB 400、跨应用令牌 404
        assertThat(raw("POST", "/cloud/" + appId + "/storage?path=../escape.txt", "x".getBytes(), auth).statusCode()).isEqualTo(400);
        assertThat(raw("POST", "/cloud/" + appId + "/storage?path=a//b.txt", "x".getBytes(), auth).statusCode()).isEqualTo(400);
        byte[] over10Mb = new byte[10 * 1024 * 1024 + 1024];
        assertThat(raw("POST", "/cloud/" + appId + "/storage?path=big.bin", over10Mb, auth).statusCode()).isEqualTo(400);
        assertThat(raw("GET", "/cloud/" + otherAppId + "/storage", null, auth).statusCode()).isEqualTo(404);
    }
}
