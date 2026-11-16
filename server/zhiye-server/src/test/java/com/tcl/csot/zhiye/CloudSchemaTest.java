package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** cloud/schema.sql 里的非文本列（int/numeric/date/boolean）能正常写入、按值筛选；新版本新增的列在任务成功后自动补上。 */
class CloudSchemaTest extends IntegrationTest {

    @Test
    void typedColumnsAndSchemaUpgradeOnNewVersion() {
        Client c = client();
        String id = c.createApp("做一个设备报修工单").path("id").asText();
        assertThat(c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        String v1 = c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText();

        String schema = "create table work_order (title varchar(80) not null, qty integer, cost numeric(10,2), due date, done bool default false);";
        JsonNode save = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of(Map.of("path", "cloud/schema.sql", "content", schema)))));
        assertThat(c.waitTask(save.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode on = c.ok(c.post("/api/apps/" + id + "/cloud/enable", Map.of()));
        assertThat(on.path("tables").toString()).contains("work_order");
        Map<String, String> auth = Map.of("Authorization", "Bearer " + on.path("token").asText());

        JsonNode row = c.ok(c.send("POST", "/cloud/" + id + "/db/work_order",
                Map.of("title", "贴合机异响", "qty", 3, "cost", "12.50", "due", "2025-12-01", "done", true), auth));
        assertThat(row.path("qty").asInt()).isEqualTo(3);
        assertThat(row.path("cost").asDouble()).isEqualTo(12.5);
        assertThat(row.path("due").asText()).isEqualTo("2025-12-01");
        assertThat(row.path("done").asBoolean()).isTrue();

        c.ok(c.send("POST", "/cloud/" + id + "/db/work_order", Map.of("title", "另一条", "qty", 7), auth));
        JsonNode filtered = c.ok(c.send("GET", "/cloud/" + id + "/db/work_order?qty=3", null, auth));
        assertThat(filtered.path("total").asInt()).isEqualTo(1);   // total 是满足筛选条件的条数
        assertThat(filtered.path("rows")).hasSize(1);
        assertThat(c.ok(c.send("PATCH", "/cloud/" + id + "/db/work_order/" + row.path("id").asText(), Map.of("qty", 4), auth)).path("qty").asInt()).isEqualTo(4);
        assertThat(c.send("POST", "/cloud/" + id + "/db/work_order", Map.of("qty", "不是数字"), auth).status()).isEqualTo(400);

        // 新版本加一列：保存成功后自动补列，不用重新启用
        String v2 = c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText();
        JsonNode save2 = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v2, "files",
                List.of(Map.of("path", "cloud/schema.sql", "content", schema + "\nalter table work_order add column owner text;")))));
        assertThat(c.waitTask(save2.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(c.ok(c.send("POST", "/cloud/" + id + "/db/work_order", Map.of("title", "带负责人", "owner", "张三"), auth)).path("owner").asText()).isEqualTo("张三");
    }

    /** 先启用（还没有 schema.sql，给一张默认 notes 表），之后 AI 写出正式表结构：空的 notes 自动去掉；表说明来自 create table 上面那行注释 */
    @Test
    void placeholderNotesRemovedWhenRealSchemaArrives() {
        Client c = client();
        String id = c.createApp("做一个待办清单").path("id").asText();
        assertThat(c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode on = c.ok(c.post("/api/apps/" + id + "/cloud/enable", Map.of()));
        assertThat(on.path("tables").toString()).contains("notes");

        String v1 = c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText();
        String schema = "-- 每日待办：按用户隔离\ncreate table todos (title text not null, done boolean default false);";
        JsonNode save = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v1, "files", List.of(Map.of("path", "cloud/schema.sql", "content", schema)))));
        assertThat(c.waitTask(save.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        JsonNode tables = c.ok(c.get("/api/apps/" + id + "/cloud/console")).path("tables");
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).path("name").asText()).isEqualTo("todos");
        assertThat(tables.get(0).path("description").asText()).isEqualTo("每日待办：按用户隔离");
    }

    /** 生成中把云服务关了：新版本带 schema.sql 也不建表、不重新打开，回复里说明要重新启用；重新启用后按 schema 建表 */
    @Test
    void schemaWhileCloudOffKeepsItOffAndSaysSo() {
        Client c = client();
        String id = c.createApp("做一个巡检登记").path("id").asText();
        assertThat(c.waitTask(c.ok(c.post("/api/apps/" + id + "/generate", Map.of())).path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        c.ok(c.post("/api/apps/" + id + "/cloud/enable", Map.of()));
        c.ok(c.post("/api/apps/" + id + "/cloud/disable", Map.of()));
        String v = c.ok(c.get("/api/apps/" + id)).path("currentVersion").path("id").asText();
        JsonNode save = c.ok(c.post("/api/apps/" + id + "/code", Map.of("baseVersionId", v, "files",
                List.of(Map.of("path", "cloud/schema.sql", "content", "create table patrol (spot text);")))));
        assertThat(c.waitTask(save.path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(c.ok(c.get("/api/apps/" + id + "/cloud")).path("enabled").asBoolean()).isFalse();
        assertThat(c.ok(c.get("/api/apps/" + id + "/messages")).toString()).contains("云服务现在是关闭的");
        assertThat(c.ok(c.post("/api/apps/" + id + "/cloud/enable", Map.of())).path("tables").toString()).contains("patrol");
    }
}
