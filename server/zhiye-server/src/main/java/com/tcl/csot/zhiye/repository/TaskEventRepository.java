package com.tcl.csot.zhiye.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class TaskEventRepository {
    public record EventRow(String taskId, int seq, String type, JsonNode payload, Instant createdAt) {}

    private final JdbcClient jdbc;

    public TaskEventRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public int append(String taskId, String type, Object payload) {
        String json = clean(payload);
        for (int attempt = 0; ; attempt++) {
            try {
                return jdbc.sql("""
                        insert into task_event (task_id, seq, type, payload, created_at)
                        values (?, (select coalesce(max(seq), 0) + 1 from task_event where task_id = ?), ?, ?::jsonb, clock_timestamp())
                        returning seq
                        """).params(taskId, taskId, type, json).query(Integer.class).single();
            } catch (DuplicateKeyException e) {
                if (attempt > 20) throw e;
            }
        }
    }

    public void updatePayload(String taskId, int seq, Object payload) {
        jdbc.sql("update task_event set payload = ?::jsonb where task_id = ? and seq = ?").params(clean(payload), taskId, seq).update();
    }

    /**
     * 事件里可能带着命令输出的控制字符（最常见的是 \u0000），
     * Postgres 的 jsonb 不接受它们，会整条写不进去、把任务卡死。这里统一去掉。
     */
    static String clean(Object payload) {
        String json = Json.write(payload);
        if (json.indexOf('\\') < 0) return json;
        StringBuilder out = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 5 < json.length() && json.charAt(i + 1) == 'u' && json.regionMatches(i + 2, "0000", 0, 4)) {
                i += 5; // 丢掉这个转义
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    public long count(String taskId) {
        return jdbc.sql("select count(*) from task_event where task_id = ?").param(taskId).query(Long.class).single();
    }

    public java.util.Optional<EventRow> get(String taskId, int seq) {
        return jdbc.sql("select * from task_event where task_id = ? and seq = ?").params(taskId, seq)
                .query((rs, i) -> new EventRow(rs.getString("task_id"), rs.getInt("seq"), rs.getString("type"),
                        Json.read(rs.getString("payload")), Db.ts(rs, "created_at"))).optional();
    }

    public List<EventRow> after(String taskId, int after) {
        return jdbc.sql("select * from task_event where task_id = ? and seq > ? order by seq").params(taskId, after)
                .query((rs, i) -> new EventRow(rs.getString("task_id"), rs.getInt("seq"), rs.getString("type"),
                        Json.read(rs.getString("payload")), Db.ts(rs, "created_at"))).list();
    }
}
