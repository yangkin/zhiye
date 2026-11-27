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

        // 回归：助手在正文中间输出 [[build:开发网站]] 也能识别；模型误输出 [[build:全栈应用]] 时不得劫持纯页面请求
        var midTag = List.of(
                msg("user", "帮我写一个介绍我们后端接口规范与全栈架构的展示页面"),
                msg("assistant", "好的，马上开始\n[[build:开发网站]]\n这是一个展示页面。"));
        assertThat(ConversationService.inferType(midTag, "开发网站")).isEqualTo("开发网站");

        var hallucinatedFullstack = List.of(
                msg("user", "帮我写一个 HTML 页面展示个人周报，不需要后端"),
                msg("assistant", "[[build:全栈应用]]\n好的，开始制作。"));
        assertThat(ConversationService.inferType(hallucinatedFullstack, "开发网站")).isEqualTo("开发网站");

        var descriptiveBackendPage = List.of(
                msg("user", "帮我写一个页面，用来介绍部门后端接口规范与全栈研发流程"),
                msg("assistant", "好的，可以立即开始。"));
        assertThat(ConversationService.inferType(descriptiveBackendPage, "开发网站")).isEqualTo("开发网站");
    }
}
