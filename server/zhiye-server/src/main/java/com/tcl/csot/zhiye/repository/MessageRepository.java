package com.tcl.csot.zhiye.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MessageRepository {
    public record MessageRow(String id, String appId, String role, String content, JsonNode elementContext,
                             String taskId, String status, java.time.Instant createdAt, JsonNode assetIds, JsonNode skillAppIds,
                             boolean steer) {}

    private final JdbcClient jdbc;

    public MessageRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static MessageRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new MessageRow(rs.getString("id"), rs.getString("app_id"), rs.getString("role"), rs.getString("content"),
                Json.read(rs.getString("element_context")), rs.getString("task_id"), rs.getString("status"), Db.ts(rs, "created_at"),
                Json.read(rs.getString("asset_ids")), Json.read(rs.getString("skill_app_ids")), rs.getBoolean("steer"));
    }

    public MessageRow insert(String appId, String role, String content, JsonNode elementContext, String taskId, String status) {
        return insert(appId, role, content, elementContext, taskId, status, null);
    }

    public MessageRow insert(String appId, String role, String content, JsonNode elementContext, String taskId, String status,
                             List<String> assetIds) {
        return insert(appId, role, content, elementContext, taskId, status, assetIds, null);
    }

    public MessageRow insert(String appId, String role, String content, JsonNode elementContext, String taskId, String status,
                             List<String> assetIds, List<String> skillAppIds) {
        String id = Ids.newId("msg");
        jdbc.sql("insert into message (id, app_id, role, content, element_context, task_id, status, asset_ids, skill_app_ids) values (?,?,?,?,?::jsonb,?,?,?::jsonb,?::jsonb)")
                .params(id, appId, role, content, elementContext == null || elementContext.isNull() ? null : Json.write(elementContext), taskId, status,
                        assetIds == null || assetIds.isEmpty() ? null : Json.write(assetIds),
                        skillAppIds == null || skillAppIds.isEmpty() ? null : Json.write(skillAppIds))
                .update();
        return find(id).orElseThrow();
    }

    /** 任务进行中用户补充的话（steer），显示在对话里 */
    public MessageRow insertSteer(String appId, String taskId, String content) {
        String id = Ids.newId("msg");
        jdbc.sql("insert into message (id, app_id, role, content, task_id, status, steer) values (?,?,'user',?,?,'ok',true)")
                .params(id, appId, content, taskId).update();
        return find(id).orElseThrow();
    }

    public Optional<MessageRow> find(String id) {
        return jdbc.sql("select * from message where id = ?").param(id).query(MessageRepository::map).optional();
    }

    public Optional<MessageRow> findUserByTask(String taskId) {
        return jdbc.sql("select * from message where task_id = ? and role = 'user' and not steer order by created_at limit 1").param(taskId)
                .query(MessageRepository::map).optional();
    }

    public List<MessageRow> list(String appId) {
        return jdbc.sql("select * from message where app_id = ? order by created_at, id").param(appId).query(MessageRepository::map).list();
    }

    public void setStatusByTask(String taskId, String status) {
        jdbc.sql("update message set status = ? where task_id = ? and role = 'user' and not steer").params(status, taskId).update();
    }

    public void retarget(String oldTaskId, String newTaskId) {
        jdbc.sql("update message set task_id = ?, status = 'pending' where task_id = ? and role = 'user' and not steer").params(newTaskId, oldTaskId).update();
    }

    /** 成功之后清掉同一应用里遗留的失败提示（重试成功的场景） */
    public int deleteFailures(String appId) {
        return jdbc.sql("""
                delete from message
                 where app_id = ? and role = 'system'
                   and (content like '生成失败：%' or content like '创造计划生成失败：%' or content like '修改失败：%'
                        or content like '织页的回复被用户取消%')
                """).param(appId).update();
    }
}
