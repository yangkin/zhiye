package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.repository.TaskEventRepository;
import com.tcl.csot.zhiye.service.cloud.CloudService;
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
    @Autowired CloudService cloudService;
    @Autowired JdbcClient jdbc;

    @Test
    void taskDiagnosticsRunnerLogAndRuntimeViews() throws Exception {
        Client owner = client();
        JsonNode app = owner.createApp("日志排查测试应用");
        String appId = app.path("id").asText();
        JsonNode gen = owner.ok(owner.post("/api/apps/" + appId + "/generate", Map.of()));
        String taskId = gen.path("id").asText();
        owner.waitTask(taskId);
        // 启用云服务（触发 create schema + create table DDL 日志）并发布应用（触发 lifecycle 日志）
        String ownerWs = owner.ok(owner.get("/api/workspace")).path("id").asText();
        cloudService.enable(ownerWs, appId);
        owner.ok(owner.post("/api/apps/" + appId + "/publish", Map.of()));
        // 触发一次参数校验报错，验证 GlobalExceptionHandler api_error 采集
        assertThat(owner.post("/api/apps/" + appId + "/plan-feedback", Map.of("content", "")).status()).isEqualTo(400);

        // 补充写入一条命令 log 事件、model_usage、app_fn_log、data_source_call 与 runner 日志文件（含敏感凭证测试脱敏 L02）
        eventRepo.append(taskId, "log", Map.of("line", "$ vite build\nerror TS2304: Cannot find name 'Foo'"));
        jdbc.sql("insert into model_usage (task_id, provider, model, input_tokens, output_tokens, cost) values (?, 'kimi', 'kimi-diag', 800, 120, 0.0042)")
                .param(taskId).update();
        jdbc.sql("insert into app_fn_log (app_id, name, ok, duration_ms, error, logs, caller) values (?, 'syncData', false, 65, 'TypeError: boom', 'Authorization: Bearer secret-fn-token-123456', 'u1')")
                .param(appId).update();
        JsonNode ds = owner.ok(owner.post("/api/sources", Map.of(
                "key", "ops_log_src",
                "name", "日志测试数据源",
                "method", "GET",
                "url", "http://127.0.0.1:" + port + "/api/meta",
                "authType", "none"
        )));
        jdbc.sql("insert into data_source_call (source_id, app_id, origin, status, ok, duration_ms, error) values (?, ?, 'preview', 502, false, 1200, 'upstream timeout sk-secretkey9876543210abcd')")
                .params(ds.path("id").asText(), appId).update();

        // F07: 仅带 task_id、workspace_id/app_id 为空的 llm_gateway 历史事件也必须正确归属工作空间并隔离他人
        jdbc.sql("""
                insert into platform_event_log (category, level, action, workspace_id, user_id, app_id, task_id, ok, status_code, duration_ms, summary, detail)
                values ('llm_gateway', 'ERROR', 'GATEWAY_UPSTREAM_IO', null, null, null, ?, false, 502, 230,
                        '模型网关连接失败', 'jdbc=postgresql://pguser:superSecretPass@10.0.0.5:5432/zhiye')
                """).param(taskId).update();

        Path logFile = taskService.logFile(taskId);
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, """
                [java] 创建沙箱成功
                [runner] start prompt
                [runner] WARN retry upstream 429
                [runner] ERROR gateway 502 bad gateway Authorization: Bearer super-runner-secret-jwt-9999
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

        // 单任务详情（含云库 Schema、平台链路事件等）
        JsonNode detail = ops.ok(ops.get("/api/logs/tasks/" + taskId));
        assertThat(detail.path("task").path("id").asText()).isEqualTo(taskId);
        assertThat(detail.path("events").toString()).contains("Cannot find name 'Foo'");
        assertThat(detail.path("modelUsages").toString()).contains("kimi-diag");
        assertThat(detail.path("fnLogs").get(0).path("name").asText()).isEqualTo("syncData");
        assertThat(detail.path("sourceCalls").get(0).path("error").asText()).isEqualTo("upstream timeout sk-***");
        assertThat(detail.path("cloudStatus").path("schemaName").asText()).startsWith("app_");
        assertThat(detail.path("cloudTables").size()).isGreaterThanOrEqualTo(1);
        assertThat(detail.path("runnerLog").path("exists").asBoolean()).isTrue();
        assertThat(detail.path("runnerLog").path("totalLines").asInt()).isGreaterThanOrEqualTo(4);

        // 云库 Schema / DDL 日志检索
        JsonNode schemas = ops.ok(ops.get("/api/logs/schemas?q=" + appId));
        assertThat(schemas.path("schemas").size()).isGreaterThanOrEqualTo(1);
        assertThat(schemas.path("items").toString()).contains("CLOUD_ENABLE");
        assertThat(schemas.path("items").toString()).contains("create schema if not exists");

        // 全链路网关、API 报错与生命周期日志检索（F07 + L02 脱敏）
        JsonNode apiErrs = ops.ok(ops.get("/api/logs/events?category=api_error&q=plan-feedback"));
        assertThat(apiErrs.path("total").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode lifecycle = ops.ok(ops.get("/api/logs/events?category=lifecycle&appId=" + appId));
        assertThat(lifecycle.path("items").toString()).contains("APP_PUBLISH");

        JsonNode ownerGw = owner.ok(owner.get("/api/logs/events?category=llm_gateway&taskId=" + taskId));
        assertThat(ownerGw.path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(ownerGw.path("items").toString()).doesNotContain("superSecretPass").contains("pguser:***@");
        JsonNode otherGw = other.ok(other.get("/api/logs/events?category=llm_gateway&taskId=" + taskId));
        assertThat(otherGw.path("total").asLong()).isZero();

        // 增量事件
        JsonNode delta = ops.ok(ops.get("/api/logs/tasks/" + taskId + "/events?after=0"));
        assertThat(delta.path("events").size()).isEqualTo(detail.path("events").size());
        assertThat(delta.path("task").path("terminal").asBoolean()).isTrue();

        // Runner 日志关键字过滤、凭证脱敏及按脱敏后行反查拦截（L02）
        JsonNode filteredLog = ops.ok(ops.get("/api/logs/tasks/" + taskId + "/runner-log?q=429"));
        assertThat(filteredLog.path("matchedLines").asInt()).isEqualTo(1);
        assertThat(filteredLog.path("lines").get(0).path("no").asInt()).isEqualTo(3);
        assertThat(filteredLog.path("lines").get(0).path("text").asText()).contains("429");
        JsonNode secretProbe = ops.ok(ops.get("/api/logs/tasks/" + taskId + "/runner-log?q=super-runner-secret"));
        assertThat(secretProbe.path("matchedLines").asInt()).isZero();

        var dl = ops.get("/api/logs/tasks/" + taskId + "/runner-log/download");
        assertThat(dl.status()).isEqualTo(200);
        assertThat(dl.body()).contains("[runner] WARN retry upstream 429").doesNotContain("super-runner-secret-jwt-9999");

        // 全平台云函数与数据源日志检索（L02 脱敏验证）
        JsonNode fnRes = ops.ok(ops.get("/api/logs/functions?ok=false&q=syncData"));
        assertThat(fnRes.path("errorCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(fnRes.path("items").get(0).path("error").asText()).contains("TypeError: boom");
        assertThat(fnRes.path("items").get(0).path("logs").asText()).doesNotContain("secret-fn-token-123456");

        JsonNode srcRes = ops.ok(ops.get("/api/logs/sources?ok=false&origin=preview&q=timeout"));
        assertThat(srcRes.path("errorCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(srcRes.path("items").get(0).path("sourceKey").asText()).isEqualTo("ops_log_src");
        assertThat(srcRes.path("items").get(0).path("error").asText()).doesNotContain("sk-secretkey9876543210abcd");

        // Runner 日志级别筛选（ERROR / WARN）与关键字过滤
        JsonNode errLevelLog = ops.ok(ops.get("/api/logs/tasks/" + taskId + "/runner-log?level=ERROR"));
        assertThat(errLevelLog.path("matchedLines").asInt()).isEqualTo(1);
        assertThat(errLevelLog.path("errorLines").asInt()).isEqualTo(1);
        assertThat(errLevelLog.path("warnLines").asInt()).isEqualTo(1);
        assertThat(errLevelLog.path("lines").get(0).path("text").asText()).contains("502 bad gateway");

        // 沙箱生命周期日志：真实任务自动落库、本人工作空间可见、跨空间 403 隔离
        assertThat(other.get("/api/logs/sandboxes?workspaceId=" + ownerWs).status()).isEqualTo(403);
        JsonNode ownerSbx = owner.ok(owner.get("/api/logs/sandboxes?q=" + taskId));
        assertThat(ownerSbx.path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(ownerSbx.path("items").get(0).path("taskId").asText()).isEqualTo(taskId);
        assertThat(ownerSbx.path("items").get(0).path("state").asText()).isEqualTo("destroyed");

        JsonNode detailWithSbx = ops.ok(ops.get("/api/logs/tasks/" + taskId));
        assertThat(detailWithSbx.path("sandboxes").size()).isGreaterThanOrEqualTo(1);
        assertThat(detailWithSbx.path("sandboxes").get(0).path("startupMs").asInt()).isGreaterThanOrEqualTo(0);

        // 服务运行状态与参数校验 + L06 下载与运维查看审计留痕
        JsonNode rt = ops.ok(ops.get("/api/logs/runtime?target=api&tail=100"));
        assertThat(rt.path("prometheusEndpoint").asText()).isEqualTo("/api/meta/metrics/prometheus");
        assertThat(rt.has("clusterNodes")).isTrue();
        long auditCnt = jdbc.sql("select count(*) from admin_audit_log where action in ('RUNNER_LOG_DOWNLOAD', 'RUNTIME_LOG_VIEW')")
                .query(Long.class).single();
        assertThat(auditCnt).isGreaterThanOrEqualTo(2L);
        assertThat(ops.get("/api/logs/tasks/task_missing").status()).isEqualTo(404);
        assertThat(ops.get("/api/logs/tasks?range=bad").status()).isEqualTo(400);
        assertThat(ops.get("/api/logs/runtime?target=bad").status()).isEqualTo(400);
    }
}
