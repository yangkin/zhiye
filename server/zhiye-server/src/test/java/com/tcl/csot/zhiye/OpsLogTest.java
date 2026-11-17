package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.repository.TaskEventRepository;
import com.tcl.csot.zhiye.service.task.TaskService;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 运维与开发过程日志排查接口测试。 */
class OpsLogTest extends IntegrationTest {
    @Autowired TaskService taskService;
    @Autowired TaskEventRepository eventRepo;
    @Autowired JdbcClient jdbc;

    @Test
    void taskDiagnosticsRunnerLogAndRuntimeViews() throws Exception {
        Client owner = client();
        JsonNode app = owner.createApp("日志排查测试应用");
        String appId = app.path("id").asText();
        JsonNode gen = owner.ok(owner.post("/api/apps/" + appId + "/generate", Map.of()));
        String taskId = gen.path("id").asText();
        owner.waitTask(taskId);

        // 补充写入一条命令 log 事件、model_usage、app_fn_log、data_source_call 与 runner 日志文件
        eventRepo.append(taskId, "log", Map.of("line", "$ vite build\nerror TS2304: Cannot find name 'Foo'"));
        jdbc.sql("insert into model_usage (task_id, provider, model, input_tokens, output_tokens, cost) values (?, 'kimi', 'kimi-diag', 800, 120, 0.0042)")
                .param(taskId).update();
        jdbc.sql("insert into app_fn_log (app_id, name, ok, duration_ms, error, logs, caller) values (?, 'syncData', false, 65, 'TypeError: boom', 'step 1 ok', 'u1')")
                .param(appId).update();
        JsonNode ds = owner.ok(owner.post("/api/sources", Map.of(
                "key", "ops_log_src",
                "name", "日志测试数据源",
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/api/meta",
                "authType", "none"
        )));
        jdbc.sql("insert into data_source_call (source_id, app_id, origin, status, ok, duration_ms, error) values (?, ?, 'preview', 502, false, 1200, 'upstream timeout')")
                .params(ds.path("id").asText(), appId).update();

        Path logFile = taskService.logFile(taskId);
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, """
                [java] 创建沙箱成功
                [runner] start prompt
                [runner] WARN retry upstream 429
                [runner] cmd prompt#c1 ok 1200ms
                """);

        // 普通非管理员：只能看到自己工作空间的任务，跨工作空间或查看宿主机运行日志返回 403
        Client other = client();
        assertThat(other.get("/api/logs/tasks/" + taskId).status()).isEqualTo(403);
        assertThat(other.get("/api/logs/runtime?target=api").status()).isEqualTo(403);
        JsonNode ownerSelfList = owner.ok(owner.get("/api/logs/tasks?range=7d&q=" + taskId));
        assertThat(ownerSelfList.path("total").asLong()).isEqualTo(1);

        // 管理员跨工作空间排查
        Client ops = client().asAdmin();
        JsonNode list = ops.ok(ops.get("/api/logs/tasks?range=7d&q=" + taskId));
        assertThat(list.path("total").asLong()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("id").asText()).isEqualTo(taskId);
        assertThat(list.path("items").get(0).path("hasLogFile").asBoolean()).isTrue();
        assertThat(list.path("items").get(0).path("logEventCount").asLong()).isGreaterThanOrEqualTo(1);

        // 单任务详情
        JsonNode detail = ops.ok(ops.get("/api/logs/tasks/" + taskId));
        assertThat(detail.path("task").path("id").asText()).isEqualTo(taskId);
        assertThat(detail.path("events").toString()).contains("Cannot find name 'Foo'");
        assertThat(detail.path("modelUsages").toString()).contains("kimi-diag");
        assertThat(detail.path("fnLogs").get(0).path("name").asText()).isEqualTo("syncData");
        assertThat(detail.path("sourceCalls").get(0).path("error").asText()).isEqualTo("upstream timeout");
        assertThat(detail.path("runnerLog").path("exists").asBoolean()).isTrue();
        assertThat(detail.path("runnerLog").path("totalLines").asInt()).isGreaterThanOrEqualTo(4);

        // 增量事件
        JsonNode delta = ops.ok(ops.get("/api/logs/tasks/" + taskId + "/events?after=0"));
        assertThat(delta.path("events").size()).isEqualTo(detail.path("events").size());
        assertThat(delta.path("task").path("terminal").asBoolean()).isTrue();

        // Runner 日志关键字过滤 & 原文件下载
        JsonNode filteredLog = ops.ok(ops.get("/api/logs/tasks/" + taskId + "/runner-log?q=429"));
        assertThat(filteredLog.path("matchedLines").asInt()).isEqualTo(1);
        assertThat(filteredLog.path("lines").get(0).path("no").asInt()).isEqualTo(3);
        assertThat(filteredLog.path("lines").get(0).path("text").asText()).contains("429");

        var dl = ops.get("/api/logs/tasks/" + taskId + "/runner-log/download");
        assertThat(dl.status()).isEqualTo(200);
        assertThat(dl.body()).contains("[runner] WARN retry upstream 429");

        // 全平台云函数与数据源日志检索
        JsonNode fnRes = ops.ok(ops.get("/api/logs/functions?ok=false&q=syncData"));
        assertThat(fnRes.path("errorCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(fnRes.path("items").get(0).path("error").asText()).contains("TypeError: boom");

        JsonNode srcRes = ops.ok(ops.get("/api/logs/sources?ok=false&origin=preview&q=timeout"));
        assertThat(srcRes.path("errorCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(srcRes.path("items").get(0).path("sourceKey").asText()).isEqualTo("ops_log_src");

        // 服务运行状态与参数校验
        JsonNode rt = ops.ok(ops.get("/api/logs/runtime?target=api&tail=100"));
        assertThat(rt.path("prometheusEndpoint").asText()).isEqualTo("/api/meta/metrics/prometheus");
        assertThat(ops.get("/api/logs/tasks/task_missing").status()).isEqualTo(404);
        assertThat(ops.get("/api/logs/tasks?range=bad").status()).isEqualTo(400);
        assertThat(ops.get("/api/logs/runtime?target=bad").status()).isEqualTo(400);
    }
}
