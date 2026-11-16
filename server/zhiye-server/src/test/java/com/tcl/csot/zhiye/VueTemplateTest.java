package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 计划判定为 Vue 形态：换成 Vue 模板（projectKind=vue），只支持对话修改。 */
class VueTemplateTest extends IntegrationTest {

    @Test
    void vueShapeSwitchesToVueTemplate() throws Exception {
        Client c = client();
        JsonNode app = c.createApp("[vue] 用 Vue 做一个点击计数器");
        String id = app.path("id").asText();
        assertThat(app.path("projectKind").asText()).isEqualTo("vue");
        assertThat(app.path("visualEditable").asBoolean()).isFalse();

        JsonNode task = c.ok(c.post("/api/apps/" + id + "/generate", java.util.Map.of()));
        JsonNode done = c.waitTask(task.path("id").asText());
        assertThat(done.path("status").asText()).as(done.toString()).isEqualTo("SUCCEEDED");

        JsonNode versions = c.ok(c.get("/api/apps/" + id + "/versions"));
        String vid = versions.get(0).path("id").asText();
        String files = c.ok(c.get("/api/versions/" + vid + "/files")).toString();
        assertThat(files).contains("src/App.vue").contains("src/main.ts").contains("src/env.d.ts");

        // 普通需求仍然是 React 模板
        JsonNode react = c.createApp("做一个点击计数器");
        assertThat(react.path("projectKind").asText()).isEqualTo("template");
    }
}
