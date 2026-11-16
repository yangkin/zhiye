package com.tcl.csot.zhiye.service.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeKindTest {
    @Test
    void mapsPlanShapesToProjectKindsAndTemplates() {
        assertThat(TaskExecutor.kindForShape("web")).isEqualTo("template");
        assertThat(TaskExecutor.kindForShape("doc")).isEqualTo("template");
        assertThat(TaskExecutor.kindForShape(" Vue ")).isEqualTo("vue");
        assertThat(TaskExecutor.kindForShape("static")).isEqualTo("static");
        assertThat(TaskExecutor.kindForShape("unknown")).isNull();
        assertThat(TaskExecutor.templateFor("vue")).isEqualTo("vue-template");
        assertThat(TaskExecutor.templateFor("template")).isEqualTo("app-template");
        assertThat(TaskExecutor.shapeLabel("vue")).isEqualTo("网页应用（Vue）");
    }
}
