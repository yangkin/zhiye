package com.tcl.csot.zhiye.repository;

import com.tcl.csot.zhiye.util.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class TaskQueueRepository {
    public record QueuedMessage(String id, String taskId, String appId, String workspaceId, String content,
                                String status, Instant createdAt, String sentTaskId, boolean enableCloud) {
        private static QueuedMessage map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
            return new QueuedMessage(rs.getString("id"), rs.getString("task_id"), rs.getString("app_id"),
                    rs.getString("workspace_id"), rs.getString("content"), rs.getString("status"),
                    Db.ts(rs, "created_at"), rs.getString("sent_task_id"), rs.getBoolean("enable_cloud"));
        }
    }

    private final JdbcClient jdbc;

    public TaskQueueRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public QueuedMessage insert(String id, String taskId, String appId, String workspaceId, String content, boolean enableCloud) {
        jdbc.sql("""
                insert into task_message_queue (id, task_id, app_id, workspace_id, content, status, created_at, enable_cloud)
                values (?, ?, ?, ?, ?, 'pending', clock_timestamp(), ?)
                """).params(id, taskId, appId, workspaceId, content, enableCloud).update();
        return find(workspaceId, id).orElseThrow();
    }

    public List<QueuedMessage> listPending(String workspaceId, String appId) {
        return jdbc.sql("""
                select * from task_message_queue
                where workspace_id = ? and app_id = ? and status = 'pending'
                order by created_at, id
                """).params(workspaceId, appId).query(QueuedMessage::map).list();
    }

    public Optional<QueuedMessage> find(String workspaceId, String id) {
        return jdbc.sql("select * from task_message_queue where id = ? and workspace_id = ?")
                .params(id, workspaceId).query(QueuedMessage::map).optional();
    }

    /** 与续跑事务共用行锁，避免撤回和发送同时成功。 */
    public Optional<QueuedMessage> firstPendingForUpdate(String workspaceId, String appId) {
        return jdbc.sql("""
                select * from task_message_queue
                where workspace_id = ? and app_id = ? and status = 'pending'
                order by created_at, id
                limit 1
                for update
                """).params(workspaceId, appId).query(QueuedMessage::map).optional();
    }

    public boolean markSent(String workspaceId, String id, String sentTaskId) {
        return jdbc.sql("""
                update task_message_queue set status = 'sent', sent_task_id = ?
                where id = ? and workspace_id = ? and status = 'pending'
                """).params(sentTaskId, id, workspaceId).update() > 0;
    }

    public boolean cancelPending(String workspaceId, String id) {
        return jdbc.sql("""
                update task_message_queue set status = 'cancelled'
                where id = ? and workspace_id = ? and status = 'pending'
                """).params(id, workspaceId).update() > 0;
    }
}
