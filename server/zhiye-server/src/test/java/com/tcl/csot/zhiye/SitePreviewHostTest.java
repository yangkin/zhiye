package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 预览域名 preview.{siteHostSuffix} 落在发布站点泛域名里时，/p/{buildId} 仍要走预览，不能被当成 slug=preview 的应用站点。 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_previewhost", "spring.flyway.schemas=ctx_previewhost",
        "zhiye.site.preview-base=http://preview.localhost:8083",
        "zhiye.site.site-host-suffix=localhost:8083"})
class SitePreviewHostTest extends IntegrationTest {

    private record Res(int status, String body) {}

    private Res getWithHost(String url, String host) throws Exception {
        URI u = URI.create(url);
        try (var socket = new java.net.Socket(u.getHost(), u.getPort())) {
            socket.setSoTimeout(15000);
            var out = socket.getOutputStream();
            out.write(("GET " + u.getPath() + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(9, 12));
            String headers = raw.substring(0, raw.indexOf("\r\n\r\n"));
            String body = raw.substring(raw.indexOf("\r\n\r\n") + 4);
            if (headers.toLowerCase(java.util.Locale.ROOT).contains("transfer-encoding: chunked")) body = dechunk(body);
            return new Res(status, body);
        }
    }

    private static String dechunk(String raw) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            int eol = raw.indexOf("\r\n", i);
            if (eol < 0) break;
            int size;
            try { size = Integer.parseInt(raw.substring(i, eol).trim(), 16); } catch (NumberFormatException e) { break; }
            if (size == 0) break;
            out.append(raw, eol + 2, Math.min(eol + 2 + size, raw.length()));
            i = eol + 2 + size + 2;
        }
        return out.toString();
    }

    @Test
    void previewDomainServesBuildPreviewInsteadOfSlugSite() throws Exception {
        Client c = client();
        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "做一个点击计数器", "mode", "快速", "model", "auto", "assetIds", List.of())));
        String appId = created.path("id").asText();
        assertThat(c.waitTask(created.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode task = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of()));
        assertThat(c.waitTask(task.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode app = c.ok(c.get("/api/apps/" + appId));
        String previewUrl = app.path("previewUrl").asText();
        assertThat(previewUrl).startsWith("http://preview.localhost:8083/p/");

        // 预览域名 + /p/{buildId}/index.html：200，且是带 data-loc 的预览版
        Res preview = getWithHost(local(previewUrl), "preview.localhost:8083");
        assertThat(preview.status()).as(preview.body()).isEqualTo(200);
        assertThat(preview.body()).contains("data-loc=\"src/App.tsx:");

        // 其他 slug 仍按发布站点处理（不存在的站点 404），互不影响
        Res slug = getWithHost(local(previewUrl), "nosuch.localhost:8083");
        assertThat(slug.status()).isEqualTo(404);
    }
}
