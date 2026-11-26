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

/** 工作台 /p 预览可独立配置为主站同源地址，旧预览域名仍保留；两类作品文档都必须带 CSP sandbox。 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_previewhost", "spring.flyway.schemas=ctx_previewhost",
        "zhiye.site.preview-base=http://preview.localhost:8083",
        "zhiye.site.site-host-suffix=localhost:8083",
        "zhiye.site.template-preview-base=https://zhiye.example.com/",
        "zhiye.site.build-preview-base=https://zhiye.example.com/"})
class SitePreviewHostTest extends IntegrationTest {

    private record Res(int status, String body, String csp) {}

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
            String csp = headers.lines().filter(x -> x.toLowerCase(java.util.Locale.ROOT).startsWith("content-security-policy:"))
                    .map(x -> x.substring(x.indexOf(':') + 1).trim()).findFirst().orElse("");
            return new Res(status, body, csp);
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
    void previewAndTemplateRoutesUseIndependentBases() throws Exception {
        Client c = client();
        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "做一个点击计数器", "mode", "快速", "model", "auto", "assetIds", List.of())));
        String appId = created.path("id").asText();
        assertThat(c.waitTask(created.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode task = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of()));
        assertThat(c.waitTask(task.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode app = c.ok(c.get("/api/apps/" + appId));
        String previewUrl = app.path("previewUrl").asText();
        assertThat(previewUrl).startsWith("https://zhiye.example.com/p/");
        String buildId = previewUrl.replaceAll(".*/p/([^/]+)/index.html", "$1");

        // 主站同源 + /p/{buildId}/index.html：200，仍带 data-loc，并用 CSP sandbox 隔离主站登录态
        Res preview = getWithHost(local(previewUrl), "zhiye.example.com");
        assertThat(preview.status()).as(preview.body()).isEqualTo(200);
        assertThat(preview.body()).contains("data-loc=\"src/App.tsx:");
        assertThat(preview.csp()).contains("sandbox").doesNotContain("allow-same-origin");

        // 原预览域名仍能直接服务预览（供尚未切换的部署平滑回退）
        Res legacy = getWithHost(local("http://preview.localhost:8083/p/" + buildId + "/index.html"), "preview.localhost:8083");
        assertThat(legacy.status()).as(legacy.body()).isEqualTo(200);
        assertThat(legacy.body()).contains("data-loc=\"src/App.tsx:");
        assertThat(legacy.csp()).contains("sandbox").doesNotContain("allow-same-origin");

        JsonNode template = c.ok(c.post("/api/templates", Map.of("appId", appId)));
        String templateId = template.path("id").asText();
        assertThat(template.path("previewUrl").asText()).isEqualTo("https://zhiye.example.com/t/" + templateId + "/");
        Res snapshot = getWithHost(local(template.path("previewUrl").asText()), "zhiye.example.com");
        assertThat(snapshot.status()).as(snapshot.body()).isEqualTo(200);
        assertThat(snapshot.body()).contains("<html").doesNotContain("__ZHIYE_CLOUD__");
        assertThat(snapshot.csp()).contains("sandbox").doesNotContain("allow-same-origin");
    }
}
