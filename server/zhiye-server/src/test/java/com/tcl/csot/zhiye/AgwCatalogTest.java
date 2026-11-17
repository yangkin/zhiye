package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.controller.mock.MockAgwController;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** API 网关目录 → 导入 → 参数映射与项目签名调用。 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_agw", "spring.flyway.schemas=ctx_agw",
        "zhiye.agw.enabled=true", "zhiye.agw.base-url={self}/mock/agw", "zhiye.agw.public-base-url={self}"})
class AgwCatalogTest extends IntegrationTest {
    private static Map<String, String> bearer() { return Map.of("Authorization", "Bearer tok-agw"); }

    @Test
    void catalogImportAndSignedQuery() {
        Client c = client();
        assertThat(c.get("/api/sources/agw/apis").status()).isEqualTo(401);
        JsonNode page = c.ok(c.send("GET", "/api/sources/agw/apis?page=0&size=20", null, bearer()));
        assertThat(page.path("totalElements").asInt()).isEqualTo(2);
        assertThat(page.at("/items/0/supported").asBoolean()).isTrue();
        assertThat(page.at("/items/1/supported").asBoolean()).isFalse();
        assertThat(MockAgwController.lastAuthorization).isEqualTo("tok-agw");
        assertThat(MockAgwController.lastListQuery).containsEntry("enableStatus", "1").containsEntry("shelvesStatus", "1");

        JsonNode source = c.ok(c.send("POST", "/api/sources/agw/apis/api-daily/import",
                Map.of("appKey", "project-demo", "appSecret", "demo-secret"), bearer()));
        String id = source.path("id").asText();
        assertThat(source.path("provider").asText()).isEqualTo("agw");
        assertThat(source.path("externalId").asText()).isEqualTo("api-daily");
        assertThat(source.path("key").asText()).startsWith("agw_mes_daily_output_");
        assertThat(source.toString()).doesNotContain("demo-secret").doesNotContain("v1:");
        assertThat(source.at("/params/0/position").asText()).isEqualTo("path");
        assertThat(source.at("/params/0/required").asBoolean()).isTrue();
        assertThat(source.at("/params/2/type").asText()).isEqualTo("integer");

        JsonNode result = c.ok(c.post("/api/sources/" + id + "/test", Map.of("params", Map.of("line", "L1", "date", "2026-01-31", "top", 5))));
        JsonNode data = result.path("data");
        assertThat(data.path("line").asText()).isEqualTo("L1");
        assertThat(data.at("/query/date").asText()).isEqualTo("2026-01-31");
        assertThat(data.at("/query/top").asText()).isEqualTo("5");
        assertThat(data.path("appKey").asText()).isEqualTo("project-demo");
        assertThat(data.path("signature").asText()).isEqualTo(MockAgwController.signature("project-demo", "demo-secret", data.path("timestamp").asText()));
        assertThat(data.path("requestId").asText()).isNotBlank();

        // 重复导入更新同一条；凭证留空时保留
        JsonNode again = c.ok(c.send("POST", "/api/sources/agw/apis/api-daily/import", Map.of(), bearer()));
        assertThat(again.path("id").asText()).isEqualTo(id);
        assertThat(c.ok(c.get("/api/sources")).size()).isEqualTo(1);
        assertThat(c.ok(c.send("GET", "/api/sources/agw/apis", null, bearer())).at("/items/0/importedSourceId").asText()).isEqualTo(id);
        assertThat(c.ok(c.post("/api/sources/" + id + "/test", Map.of("params", Map.of("line", "L2", "date", "2026-02-01"))))
                .path("data").path("line").asText()).isEqualTo("L2");

        Client other = client();
        assertThat(other.get("/api/sources/" + id).status()).isEqualTo(404);
        assertThat(other.ok(other.send("GET", "/api/sources/agw/apis", null, bearer())).at("/items/0/importedSourceId").isNull()).isTrue();
    }
}
