package com.tcl.csot.zhiye;

import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 提示文字走 i18n/core/messages*.properties：默认中文，Accept-Language: en-US 时英文；没配 key 的照旧用代码里的中文。 */
class I18nMessagesTest extends IntegrationTest {
    @Test
    void messagesFollowRequestLanguage() {
        Client c = client();
        var zh = c.get("/api/no-such-endpoint");
        assertThat(zh.status()).isEqualTo(404);
        assertThat(zh.json().path("message").asText()).isEqualTo("接口不存在");
        var en = c.send("GET", "/api/no-such-endpoint", null, Map.of("Accept-Language", "en-US,en;q=0.9"));
        assertThat(en.json().path("message").asText()).isEqualTo("API not found");
        var unsupported = c.send("GET", "/api/no-such-endpoint", null, Map.of("Accept-Language", "ja-JP"));
        assertThat(unsupported.json().path("message").asText()).isEqualTo("接口不存在");

        var app = c.get("/api/apps/app_does_not_exist");
        assertThat(app.code()).isEqualTo("NOT_FOUND");
        assertThat(app.json().path("message").asText()).isEqualTo("应用不存在");
    }
}
