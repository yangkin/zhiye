package com.tcl.csot.zhiye.service.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateDepsSyncTest {
    @TempDir Path dir;

    @Test
    void addsMissingTemplateDepsWithoutTouchingExistingVersions() throws Exception {
        Path pkg = dir.resolve("package.json"), tpl = dir.resolve("tpl.json");
        Files.writeString(pkg, "{\n  \"name\": \"app\",\n  \"dependencies\": {\n    \"react\": \"~19.0.0\"\n  }\n}\n");
        Files.writeString(tpl, "{\"dependencies\":{\"react\":\"~19.2.0\",\"recharts\":\"3.10.1\"},\"devDependencies\":{\"vite\":\"~7.2.0\"}}");
        assertThat(TaskExecutor.syncTemplateDeps(pkg, tpl)).isTrue();
        String out = Files.readString(pkg);
        assertThat(out).contains("\"react\": \"~19.0.0\"", "\"recharts\": \"3.10.1\"", "\"devDependencies\": {", "\"vite\": \"~7.2.0\"");
        assertThat(out).startsWith("{\n  \"name\": \"app\",").endsWith("}\n");
        assertThat(TaskExecutor.syncTemplateDeps(pkg, tpl)).isFalse();
    }
}
