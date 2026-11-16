package com.tcl.csot.zhiye.service.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 工作台意图：modify / new / ask；拿不准时按修改处理 */
class AskIntentTest {
    @Test
    void parsesModelAnswer() {
        assertThat(AskService.parseIntent("new")).isEqualTo("new");
        assertThat(AskService.parseIntent(" New\n")).isEqualTo("new");
        assertThat(AskService.parseIntent("ask")).isEqualTo("ask");
        assertThat(AskService.parseIntent("modify")).isEqualTo("modify");
        assertThat(AskService.parseIntent("new 或 modify")).isEqualTo("modify");
        assertThat(AskService.parseIntent("")).isEqualTo("modify");
    }
}
