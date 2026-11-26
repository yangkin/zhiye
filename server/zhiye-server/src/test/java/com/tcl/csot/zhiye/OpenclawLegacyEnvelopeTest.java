package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_openclaw_legacy",
        "spring.flyway.schemas=ctx_openclaw_legacy",
        "zhiye.auth.mode=sso", "zhiye.auth.sso.user-info-url={self}/test-sso/self",
        "zhiye.fivestar.enabled=true", "zhiye.fivestar.mock=true", "zhiye.fivestar.organization-id=legacy"})
class OpenclawLegacyEnvelopeTest extends IntegrationTest {
    @Test
    void acceptsCode200Envelope() {
        Client c = new Client(port);
        c.ok(c.send("POST", "/api/auth/sso/callback", Map.of("accessToken", "tok-legacy-user"), Map.of()));
        Map<String, String> auth = Map.of("Authorization", "Bearer tok-legacy-user");

        JsonNode models = c.ok(c.send("GET", "/api/llm/models", null, auth));
        assertThat(models.path("models")).hasSize(5);
        assertThat(models.path("defaultId").asText()).isEqualTo("1:glm-4.5-flash-free");

        JsonNode quota = c.ok(c.send("GET", "/api/llm/quota", null, auth));
        assertThat(quota.path("packageName").asText()).isEqualTo("Lite");
        assertThat(quota.path("remainingTokens").asLong()).isPositive();
    }
}
