package com.tcl.csot.zhiye.service.conversation;

import com.tcl.csot.zhiye.repository.ConversationRepository;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InferTypeTest {
    private static ConversationRepository.MessageRow msg(String role, String content) {
        return new ConversationRepository.MessageRow("m", "c", role, content, null, null, null);
    }

    @Test
    void screenshotDescriptionDoesNotTurnIntoSkill() {
        // 助手复述截图时提到了「开发skill」标签，用户要的是工作台
        var messages = List.of(
                msg("user", "可以帮我跟这个一样的工作台不"),
                msg("assistant", "照着图片复刻一个「创造工作台」首页：含 日常开发 / 开发网站 / 开发skill / 技术文档 四个标签切换……"));
        assertThat(ConversationService.inferType(messages, "开发网站")).isEqualTo("日常开发");
    }

    @Test
    void modelDeclaredTypeWins() {
        var messages = List.of(
                msg("user", "照着这张图做一个"),
                msg("assistant", "[[build:日常开发]]\n我来照着截图搭一个工作台，标签里有开发skill。"));
        assertThat(ConversationService.inferType(messages, "开发网站")).isEqualTo("日常开发");
        var skill = List.of(msg("user", "帮我整理成一个可复用的东西"), msg("assistant", "[[build:开发skill]]\n做成技能包。"));
        assertThat(ConversationService.inferType(skill, "开发网站")).isEqualTo("开发skill");
    }

    @Test
    void unknownDeclaredTypeFallsBack() {
        var messages = List.of(msg("user", "做一个点检记录"), msg("assistant", "[[build:乱写]]\n好的"));
        assertThat(ConversationService.inferType(messages, "开发网站")).isEqualTo("日常开发");
        var plain = List.of(msg("user", "帮我做个东西"), msg("assistant", "[[build]]\n好的"));
        assertThat(ConversationService.inferType(plain, "技术文档")).isEqualTo("技术文档");
    }

    @Test
    void vueSpringBootInfersFullstack() {
        var messages = List.of(
                msg("user", "用 Vue 3 + Java Spring Boot 开发一个晶圆良率异常处置看板"),
                msg("assistant", "好的，可以立即开始。"));
        assertThat(ConversationService.inferType(messages, "开发网站")).isEqualTo("全栈应用");
    }

    @Test
    void htmlPageWithLoginMarginOrDocKeywordsDoesNotBecomeFullstack() {
        var loginHtml = List.of(
                msg("user", "帮我写一个 HTML 登录页面，包含 login 表单、margin 布局调整、origin 校验和 trust 品牌徽章"),
                msg("assistant", "好的，我来帮你制作这个页面。"));
        assertThat(ConversationService.inferType(loginHtml, "开发网站")).isEqualTo("开发网站");

        var wordToHtml = List.of(
                msg("user", "帮我根据下面 Word 文档内容生成一个 HTML 展示页面：\n系统总体架构基于 Spring Boot 后端接口与 FastAPI 推理服务……"),
                msg("assistant", "好的，我来整理成静态展示页。"));
        assertThat(ConversationService.inferType(wordToHtml, "开发网站")).isEqualTo("开发网站");
    }

    @Test
    void parsesDirectBuildAndPreferredShapeFromModelMarker() {
        var staticDirect = List.of(
                msg("user", "帮我写一个纯静态 HTML 年度述职汇报页面"),
                msg("assistant", "[[build:开发网站|static|direct]]\n好的，需求很清晰，直接用纯静态 HTML 为你生成。"));
        var d1 = ConversationService.inferBuildDirective(staticDirect, "开发网站");
        assertThat(d1.type()).isEqualTo("开发网站");
        assertThat(d1.preferredShape()).isEqualTo("static");
        assertThat(d1.skipPlan()).isTrue();

        var toolDirect = List.of(
                msg("user", "帮我做一个极简番茄钟计算小工具"),
                msg("assistant", "[[build:日常开发|direct]]\n明白，直接生成。"));
        var d2 = ConversationService.inferBuildDirective(toolDirect, "开发网站");
        assertThat(d2.type()).isEqualTo("日常开发");
        assertThat(d2.preferredShape()).isNull();
        assertThat(d2.skipPlan()).isTrue();

        var complexPlan = List.of(
                msg("user", "帮我搭一个多角色设备维保工单管理系统，含审批流与统计报表"),
                msg("assistant", "[[build:日常开发]]\n这是一个多模块系统，我先整理一份创造计划供确认。"));
        var d3 = ConversationService.inferBuildDirective(complexPlan, "开发网站");
        assertThat(d3.type()).isEqualTo("日常开发");
        assertThat(d3.skipPlan()).isFalse();

        // 全栈应用即使有「直接生成」字样也不跳过全栈形态约束
        var fullstack = List.of(
                msg("user", "直接生成 Vue 3 + Java Spring Boot 工单系统"),
                msg("assistant", "[[build:全栈应用]]\n好的。"));
        var d4 = ConversationService.inferBuildDirective(fullstack, "全栈应用");
        assertThat(d4.type()).isEqualTo("全栈应用");
        assertThat(d4.preferredShape()).isNull();
        assertThat(d4.skipPlan()).isFalse();
    }
}
