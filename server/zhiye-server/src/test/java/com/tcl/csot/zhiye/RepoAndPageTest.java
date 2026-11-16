package com.tcl.csot.zhiye;

import com.tcl.csot.zhiye.client.KimiClient;
import com.tcl.csot.zhiye.service.version.PageExporter;
import com.tcl.csot.zhiye.storage.LocalFsObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepoAndPageTest {
    @TempDir
    Path dir;

    @Test
    void inlinesScriptsAndStyles() throws Exception {
        var store = new LocalFsObjectStore(dir);
        store.put("b/index.html", ("<!doctype html><html><head><script type=\"module\" crossorigin src=\"./assets/i.js\"></script>"
                + "<link rel=\"modulepreload\" href=\"./assets/v.js\"><link rel=\"stylesheet\" crossorigin href=\"./assets/i.css\"></head>"
                + "<body><div id=root></div><script src=\"https://cdn.example/x.js\"></script></body></html>").getBytes());
        store.put("b/assets/i.js", "console.log('</script>')".getBytes());
        store.put("b/assets/i.css", "body{color:red}".getBytes());
        String html = new PageExporter(store).inline("b");
        assertThat(html).contains("<script type=\"module\">console.log('<\\/script>')</script>")
                .contains("<style>body{color:red}</style>").doesNotContain("modulepreload")
                .contains("https://cdn.example/x.js");
    }

    @Test
    void extractsJsonFromModelOutput() {
        assertThat(KimiClient.extractJson("好的\n```json\n{\"name\":\"a\"}\n```").path("name").asText()).isEqualTo("a");
        assertThat(KimiClient.extractJson("结果：{\"name\":\"b\",\"planDoc\":\"# x {y}\"} 完毕").path("planDoc").asText()).isEqualTo("# x {y}");
        assertThat(KimiClient.extractJson("没有 JSON")).isNull();
    }
}
