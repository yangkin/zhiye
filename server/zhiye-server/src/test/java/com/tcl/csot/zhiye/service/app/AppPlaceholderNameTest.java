package com.tcl.csot.zhiye.service.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppPlaceholderNameTest {

    @Test
    void extractsQuotedTitleOrStripsHtmlPrefixCleanly() {
        assertThat(AppService.placeholderName("帮我写一个纯静态 HTML 的「2026 Q4 团队目标看板」展示页，包含 3 张核心 KPI 卡片"))
                .isEqualTo("2026 Q4 团队目标看板");
        assertThat(AppService.placeholderName("帮我做一个纯静态 HTML 个人述职报告展示页，不要后端"))
                .isEqualTo("个人述职报告展示页");
        assertThat(AppService.placeholderName("写一个《华星良率分析》页面"))
                .isEqualTo("华星良率分析");
    }
}
