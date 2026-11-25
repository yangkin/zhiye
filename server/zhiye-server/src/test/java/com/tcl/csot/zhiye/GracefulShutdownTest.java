package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.task.TaskWorker;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 优雅停机：停止领新任务后，@PreDestroy 会等在跑任务完成（不再直接硬杀出 worker_lost）。 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test?currentSchema=ctx_graceful", "spring.flyway.schemas=ctx_graceful",
        "zhiye.task.shutdown-wait-seconds=40"})
class GracefulShutdownTest extends IntegrationTest {
    @Autowired TaskWorker worker;
    @Autowired JdbcClient jdbc;

    @Test
    void stopWaitsForRunningTaskToFinish() throws Exception {
        Client c = client();
        // [slow] 让假 runner 每轮耗时 20 秒，保证 stop 时任务还在跑
        JsonNode created = c.ok(c.post("/api/apps", Map.of("type", "开发网站", "prompt", "做一个点击计数器 [slow]", "mode", "快速", "model", "auto", "assetIds", List.of())));
        String appId = created.path("id").asText();
        assertThat(c.waitTask(created.path("activeTask").path("id").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode task = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of()));
        String taskId = task.path("id").asText();

        // 等任务进入 RUNNING（正在执行，还没结束）
        long deadline = System.currentTimeMillis() + 15_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = jdbc.sql("select status from task where id = ?").param(taskId).query(String.class).single();
            if ("RUNNING".equals(status)) break;
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("RUNNING");
        assertThat(worker.activeCount()).isGreaterThan(0);

        // 模拟进程收到退出信号：stop 必须等在跑任务做完，而不是立即返回
        long t0 = System.currentTimeMillis();
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(() -> worker.stop());
        stopping.get(35, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - t0;

        String finalStatus = jdbc.sql("select status from task where id = ?").param(taskId).query(String.class).single();
        assertThat(finalStatus).as("stop 返回时在跑任务已正常完成，而不是 worker_lost（耗时 %d ms）", elapsed).isEqualTo("SUCCEEDED");
        assertThat(worker.activeCount()).isEqualTo(0);
        assertThat(elapsed).as("stop 确实等待了慢任务，而不是立刻返回").isGreaterThan(500);
    }
}
