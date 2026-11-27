package com.tcl.csot.zhiye.service.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanChatParseTest {

    @Test
    void parsesExecuteOnlyReply() {
        var res = AskService.parsePlanReply("好的，计划已确认，马上开始生成。\n[[execute]]", "确认无误");
        assertThat(res.startGenerate()).isTrue();
        assertThat(res.planDoc()).isNull();
        assertThat(res.switchProjectKind()).isNull();
        assertThat(res.reply()).doesNotContain("[[execute]]").contains("马上开始生成");
    }

    @Test
    void parsesPlanUpdateWithShapeSwitchAndExecute() {
        String raw = """
                好的，已改为纯静态 HTML 方案并去掉多余后端模块，现在立即开始生成。
                [[shape:static]]
                [[plan]]
                # 2026 年度述职报告
                ## 目标
                纯静态 HTML 汇报展示页
                [[/plan]]
                [[execute]]
                """;
        var res = AskService.parsePlanReply(raw, "");
        assertThat(res.startGenerate()).isTrue();
        assertThat(res.switchProjectKind()).isEqualTo("static");
        assertThat(res.planDoc()).contains("# 2026 年度述职报告").doesNotContain("[[shape:");
        assertThat(res.reply()).doesNotContain("[[execute]]").doesNotContain("[[shape:static]]");
    }

    @Test
    void parsesWebShapeToTemplateAndKeepsNonExecuteFalse() {
        String raw = """
                已切换为 React 网页应用并补充了导出功能。
                [[shape:web]]
                [[plan]]
                # 数据看板
                ## 内容与功能
                - 图表与导出
                [[/plan]]
                [[next]] 开始生成 | 再加筛选条件
                """;
        var res = AskService.parsePlanReply(raw, "");
        assertThat(res.startGenerate()).isFalse();
        assertThat(res.switchProjectKind()).isEqualTo("template");
        assertThat(res.planDoc()).contains("# 数据看板");
        assertThat(res.reply()).contains("[[next]]").doesNotContain("[[shape:web]]");
    }

    @Test
    void fillsFallbackReplyWhenExecuteHasNoProse() {
        var res = AskService.parsePlanReply("[[execute]]", "");
        assertThat(res.startGenerate()).isTrue();
        assertThat(res.reply()).isNotBlank();
    }
}
