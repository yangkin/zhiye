package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.repository.ConversationRepository;
import com.tcl.csot.zhiye.repository.TaskRepository;
import com.tcl.csot.zhiye.service.chat.AskService;
import com.tcl.csot.zhiye.service.common.RateLimitService;
import com.tcl.csot.zhiye.service.conversation.ConversationService;
import com.tcl.csot.zhiye.service.task.Prompts;
import com.tcl.csot.zhiye.service.task.RepoMap;
import com.tcl.csot.zhiye.service.task.TaskExecutor;
import com.tcl.csot.zhiye.service.task.VisualCritiqueService;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.FakeUpstreamController;
import com.tcl.csot.zhiye.support.IntegrationTest;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 上下文压缩、角色交替归一化、多文件折叠与工作台问答→修改上下文连续性测试。 */
class ContextHistoryTest extends IntegrationTest {
    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Test
    void normalizeHistoryAlternatesRolesCollapsesOldFilesAndBoundsTotal() {
        List<JsonNode> raw = List.of(
                Json.obj().put("role", "assistant").put("content", "开头的孤立助手消息应被跳过"),
                Json.obj().put("role", "user").put("content", "第一句需求"),
                Json.obj().put("role", "user").put("content", "补充第二句"),
                Json.obj().put("role", "assistant").put("content", "旧方案\n[[file:方案.md]]\n" + "A".repeat(25_000) + "\n[[/file]]"),
                Json.obj().put("role", "assistant").put("content", "追加说明"),
                Json.obj().put("role", "user").put("content", "第 2 节再改"),
                Json.obj().put("role", "assistant").put("content", "最新方案\n[[file:方案.md]]\n" + "B".repeat(30_000) + "\n[[/file]]")
        );
        List<JsonNode> norm = AskService.normalizeHistory(raw);
        assertThat(norm).hasSize(4);
        assertThat(norm.get(0).path("role").asText()).isEqualTo("user");
        assertThat(norm.get(0).path("content").asText()).contains("第一句需求").contains("补充第二句");
        // 旧文件块被折叠，不再占用几万字；最新文件块保留完整内容
        assertThat(norm.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(norm.get(1).path("content").asText()).contains("旧版本文件内容已省略").doesNotContain("AAAA");
        assertThat(norm.get(2).path("role").asText()).isEqualTo("user");
        assertThat(norm.get(3).path("role").asText()).isEqualTo("assistant");
        assertThat(norm.get(3).path("content").asText()).contains("B".repeat(1000)).contains("[[/file]]");
        int total = norm.stream().mapToInt(n -> n.path("content").asText().length()).sum();
        assertThat(total).isLessThanOrEqualTo(AskService.MAX_HISTORY_TOTAL_CHARS);
    }

    @Test
    void askControllerKeepsNewestHistoryAndGuaranteesStrictAlternation() {
        Client c = client();
        String appId = c.createApp("做一个巡检工具").path("id").asText();
        assertThat(c.waitTask(c.ok(c.post("/api/apps/" + appId + "/generate", Map.of())).path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");

        List<Map<String, String>> hist = new ArrayList<>();
        hist.add(Map.of("role", "assistant", "content", "最初的开场应被跳过"));
        for (int i = 1; i <= 16; i++) {
            hist.add(Map.of("role", "user", "content", "第" + i + "问"));
            hist.add(Map.of("role", "assistant", "content", "第" + i + "答"));
        }
        // 最后再来一条未回答的 user，验证也会合并到本轮 user 上而不出现连续两条 user
        hist.add(Map.of("role", "user", "content", "孤立追问"));

        Client.Res r = c.send("POST", "/api/chat/ask",
                Map.of("appId", appId, "content", "这次的最终问题", "history", hist),
                Map.of("Accept", "text/event-stream"));
        assertThat(r.status()).isEqualTo(200);
        JsonNode msgs = FakeUpstreamController.lastBody.path("messages");
        assertThat(msgs.get(0).path("role").asText()).isEqualTo("system");
        assertThat(msgs.get(1).path("role").asText()).isEqualTo("user");
        // 丢掉最早的第 1~10 轮，保留最新窗口内的轮次
        assertThat(msgs.toString()).doesNotContain("第1问").contains("第16答");
        // 严格交替 user / assistant，最后一条是合并后的 user
        for (int i = 1; i < msgs.size(); i++) {
            String expected = (i % 2 == 1) ? "user" : "assistant";
            assertThat(msgs.get(i).path("role").asText()).isEqualTo(expected);
        }
        String lastUser = msgs.get(msgs.size() - 1).path("content").asText();
        assertThat(lastUser).contains("孤立追问").contains("这次的最终问题");
    }

    @Autowired
    private TaskExecutor executor;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RateLimitService rateLimit;

    @Test
    void dialogueBriefWaterFillingPreservesLongDesignSpec() throws Exception {
        Method m = ConversationService.class.getDeclaredMethod("dialogueBrief", List.class, int.class);
        m.setAccessible(true);
        List<ConversationRepository.MessageRow> rows = List.of(
                new ConversationRepository.MessageRow("m1", "conv", "user", "做一个设备点检系统", Instant.now(), null, null),
                new ConversationRepository.MessageRow("m2", "conv", "assistant", "方案细节：" + "字段说明。".repeat(700), Instant.now(), null, null),
                new ConversationRepository.MessageRow("m3", "conv", "user", "确认开工\n[[build:日常开发]]", Instant.now(), null, null)
        );
        String brief = (String) m.invoke(null, rows, 5000);
        assertThat(brief.length()).isLessThanOrEqualTo(5000);
        // 短消息「确认开工」不浪费固定均分额度，长助手方案尽量保留到 3000+ 字而非被均分成 ~1500 字
        assertThat(brief).contains("做一个设备点检系统").contains("确认开工").doesNotContain("[[build:");
        assertThat(brief).contains("字段说明。".repeat(500));
    }

    @Test
    void recentWorkbenchQaIncludesPostTaskQaAndExcludesOlderQa() {
        Client c = client();
        String appId = c.createApp("做一个值班记录表").path("id").asText();
        String firstTaskId = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of())).path("id").asText();
        assertThat(c.waitTask(firstTaskId).path("status").asText()).isEqualTo("SUCCEEDED");
        TaskRow firstTask = tasks.findById(firstTaskId).orElseThrow();

        // 在第一个任务之前的陈旧问答不应被带入后续修改任务
        jdbc.sql("insert into message(id, app_id, role, content, status, created_at) values (?, ?, 'user', ?, 'ok', ?)")
                .params(java.util.UUID.randomUUID().toString(), appId, "上一轮任务之前的旧问题", java.sql.Timestamp.from(firstTask.createdAt().minusSeconds(30)))
                .update();

        // 第一个任务结束后的工作台实时问答，应被后续 modify 任务带入
        Client.Res askRes = c.send("POST", "/api/chat/ask",
                Map.of("appId", appId, "content", "我想把导出按钮改成按月导出 Excel"),
                Map.of("Accept", "text/event-stream"));
        assertThat(askRes.status()).isEqualTo(200);

        String modTaskId = c.ok(c.post("/api/apps/" + appId + "/messages", Map.of("content", "按刚才聊的改一下")))
                .path("task").path("id").asText();
        assertThat(c.waitTask(modTaskId).path("status").asText()).isEqualTo("SUCCEEDED");
        TaskRow modTask = tasks.findById(modTaskId).orElseThrow();

        String qaSection = executor.recentWorkbenchQa(appId, modTask);
        assertThat(qaSection)
                .contains("工作台最近讨论记录")
                .contains("我想把导出按钮改成按月导出 Excel")
                .doesNotContain("上一轮任务之前的旧问题");
    }

    @Test
    void repoMapSummarizesWorkspaceSymbolsAndRecentVersions() throws Exception {
        Path dir = Files.createTempDirectory("repomap-");
        try {
            Files.createDirectories(dir.resolve("src/components"));
            Files.createDirectories(dir.resolve("cloud"));
            Files.createDirectories(dir.resolve("node_modules/pkg"));
            Files.writeString(dir.resolve("src/App.tsx"), "export function App() {\n  return <div />;\n}\n");
            Files.writeString(dir.resolve("src/components/InspectionTable.tsx"), "export interface Item { id: string }\nexport function InspectionTable() {}\n");
            Files.writeString(dir.resolve("cloud/schema.sql"), "create table inspection_log (id text primary key);\n");
            Files.writeString(dir.resolve("node_modules/pkg/index.js"), "export function ignored() {}\n");

            String map = RepoMap.build(dir, "template", List.of(
                    new RepoMap.VersionNote(2, "modify", "增加按月筛选"),
                    new RepoMap.VersionNote(1, "generate", "页面与交互生成完成")
            ));
            assertThat(map)
                    .contains("最近版本演进")
                    .contains("V2 [modify]：增加按月筛选")
                    .contains("当前项目地图（template）")
                    .contains("src/App.tsx（4 行）：App")
                    .contains("src/components/InspectionTable.tsx（3 行）：Item, InspectionTable")
                    .contains("cloud/schema.sql（2 行）：table inspection_log")
                    .doesNotContain("node_modules");
        } finally {
            org.springframework.util.FileSystemUtils.deleteRecursively(dir);
        }
    }

    @Test
    void promptsFixAndEstimateTokensAdaptToProjectAndLanguage() {
        var checks = Json.M.createArrayNode().add(Json.obj().put("name", "类型检查").put("ok", false).put("detail", "error TS1"));
        assertThat(Prompts.fix(checks, 1, "vue")).contains("vue-tsc --noEmit");
        assertThat(Prompts.fix(checks, 1, "script")).contains("脚本自查").doesNotContain("tsc --noEmit");
        assertThat(Prompts.fix(checks, 1, "template")).contains("tsc --noEmit");

        assertThat(AskService.estimateTokens("中文密度高的长文本")).isGreaterThan(AskService.estimateTokens("english"));
        // 超过 token 预算的多轮高密度中文历史同样会被裁减
        List<JsonNode> dense = new ArrayList<>();
        String bigCn = "需求说明：".repeat(250);
        for (int i = 0; i < 20; i++) {
            dense.add(Json.obj().put("role", "user").put("content", "第" + i + "轮：" + bigCn));
            dense.add(Json.obj().put("role", "assistant").put("content", "答复" + i + "：" + bigCn));
        }
        List<JsonNode> norm = AskService.normalizeHistory(dense);
        int est = norm.stream().mapToInt(n -> AskService.estimateTokens(n.path("content").asText())).sum();
        assertThat(est).isLessThanOrEqualTo(AskService.MAX_HISTORY_TOTAL_TOKENS);
    }

    @Test
    void distributedRateLimitRejectsOverflowWith429() {
        String bucket = "test-bucket-" + java.util.UUID.randomUUID();
        rateLimit.check(bucket, 2, 60, "RATE_LIMITED", "太快了");
        rateLimit.check(bucket, 2, 60, "RATE_LIMITED", "太快了");
        assertThatThrownBy(() -> rateLimit.check(bucket, 2, 60, "RATE_LIMITED", "太快了"))
                .isInstanceOf(com.tcl.csot.zhiye.exception.ApiException.class)
                .hasMessageContaining("太快了");
    }

    @Test
    void crossInstanceStopAbortsStreamingConversation() throws Exception {
        Client c = client();
        String cid = c.ok(c.post("/api/conversations", Map.of())).path("id").asText();
        CompletableFuture<Client.Res> stream = CompletableFuture.supplyAsync(() ->
                c.send("POST", "/api/conversations/" + cid + "/ask", Map.of("content", "做个看板 [slowstream]"), Map.of("Accept", "text/event-stream")));
        Thread.sleep(500);
        // 模拟「停止请求落到另一台实例」：直接向共享库写入停止信号，而不是命中本机内存 running 表
        jdbc.sql("insert into conversation_stop (conversation_id, requested_at) values (?, clock_timestamp()) "
                + "on conflict (conversation_id) do update set requested_at = excluded.requested_at")
                .param(cid).update();
        assertThat(stream.get().body()).contains("第一段").doesNotContain("第二段");
        Thread.sleep(400);
        JsonNode msgs = c.ok(c.get("/api/conversations/" + cid)).path("messages");
        JsonNode last = msgs.get(msgs.size() - 1);
        assertThat(last.path("role").asText()).isEqualTo("assistant");
        assertThat(last.path("content").asText()).isEqualTo("第一段，\n\n（已停止回答）");
    }

    @Test
    void visualCritiqueParsesPassAndFixResponses() {
        assertThat(VisualCritiqueService.parseCritique("PASS")).isEmpty();
        assertThat(VisualCritiqueService.parseCritique("pass\n画面正常")).isEmpty();
        assertThat(VisualCritiqueService.parseCritique(null)).isEmpty();
        assertThat(VisualCritiqueService.parseCritique("FIX: 顶部导出按钮与筛选框发生重叠，请增大间距"))
                .contains("顶部导出按钮与筛选框发生重叠，请增大间距");
        assertThat(VisualCritiqueService.parseCritique("FIX：主区域空白"))
                .contains("主区域空白");
    }
}
