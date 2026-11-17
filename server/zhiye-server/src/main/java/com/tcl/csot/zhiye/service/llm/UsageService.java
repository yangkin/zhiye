package com.tcl.csot.zhiye.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 模型用量：写 model_usage，累加 task.token_used。 */
@Service
public class UsageService {
    private final JdbcClient jdbc;

    public UsageService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void record(String taskId, String provider, String model, JsonNode usage) {
        if (usage == null || usage.isNull() || !usage.isObject()) return;
        int in = usage.path("prompt_tokens").asInt(usage.path("input_tokens").asInt(0));
        int cached = usage.path("prompt_tokens_details").path("cached_tokens")
                .asInt(usage.path("cache_read_input_tokens").asInt(0));
        int out = usage.path("completion_tokens").asInt(usage.path("output_tokens").asInt(0));
        int uncachedIn = Math.max(0, in - Math.max(0, cached));
        // 缓存命中按 10% 估算输入成本，其余按标准输入/输出估算
        double cost = uncachedIn * 3e-6 + Math.max(0, cached) * 0.3e-6 + out * 15e-6;
        jdbc.sql("insert into model_usage (task_id, provider, model, input_tokens, output_tokens, cost) values (?,?,?,?,?,?)")
                .params(taskId, provider, model, in, out, cost).update();
        if (taskId != null)
            jdbc.sql("update task set token_used = token_used + ? where id = ?").params((long) in + out, taskId).update();
    }

    public long used(String taskId) {
        return jdbc.sql("select coalesce(sum(input_tokens + output_tokens), 0) from model_usage where task_id = ?")
                .param(taskId).query(Long.class).single();
    }

    /** 从 JSON 响应或 SSE 数据块里找 usage（顶层或 choices[0].usage） */
    public static JsonNode findUsage(JsonNode n) {
        if (n == null) return null;
        JsonNode u = n.get("usage");
        if (u != null && u.isObject()) return u;
        JsonNode ch = n.get("choices");
        if (ch != null && ch.isArray() && !ch.isEmpty()) {
            JsonNode cu = ch.get(0).get("usage");
            if (cu != null && cu.isObject()) return cu;
        }
        return null;
    }
}
