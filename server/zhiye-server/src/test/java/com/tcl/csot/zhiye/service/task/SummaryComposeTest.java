package com.tcl.csot.zhiye.service.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 完成总结：系统补「检查结果」，模型给的 [[next]] 始终放在最后一行，模型自己写的检查结果以系统为准。 */
class SummaryComposeTest {

    @Test
    void checksAppendedAndNextMovedToEnd() {
        String body = "已经写好了。\n\n### 已完成\n- `src/App.tsx` —— 页面\n\n[[next]] 接上云端数据 | 导出 Excel\n\n### 检查结果\n全部通过（模型自己编的）\n";
        String out = TaskExecutor.composeSummary(body, List.of(), "检查：类型检查、构建均通过");
        assertThat(out).startsWith("已经写好了。");
        assertThat(out).doesNotContain("模型自己编的");
        assertThat(out).contains("### 检查结果\n类型检查、构建均通过");
        assertThat(out).endsWith("[[next]] 接上云端数据 | 导出 Excel");
        assertThat(out.split("\\[\\[next]]", -1)).hasSize(2);
    }

    @Test
    void fixRoundsNotedWithoutNext() {
        String out = TaskExecutor.composeSummary("改好了。", List.of("修复了类型错误：\n补上了 import。"), "检查：类型检查均通过");
        assertThat(out).contains("中途校验未通过 1 次，已自动修复：修复了类型错误： 补上了 import。");
        assertThat(out).doesNotContain("[[next]]");
        assertThat(TaskExecutor.composeSummary("只有正文", List.of(), "")).isEqualTo("只有正文");
    }
}
