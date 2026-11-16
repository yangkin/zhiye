package com.tcl.csot.zhiye.repository;

import com.tcl.csot.zhiye.entity.TaskRow;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TaskRepository {
    private final JdbcClient jdbc;

    public TaskRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 插入任务；违反「每个应用一个进行中任务」或幂等键约束时抛 DuplicateKeyException */
    public void insert(String id, String ws, String appId, String type, String baseVersionId, JsonNode input,
                       String idemKey, int attempt, String parentTaskId) {
        jdbc.sql("""
                insert into task (id, workspace_id, app_id, type, status, base_version_id, input, idempotency_key, attempt,
                  parent_task_id, phase_label, progress, created_at)
                values (?,?,?,?, 'QUEUED', ?, ?::jsonb, ?, ?, ?, '排队中', 0, clock_timestamp())
                """).params(id, ws, appId, type, baseVersionId, Json.write(input), idemKey, attempt, parentTaskId).update();
    }

    public Optional<TaskRow> find(String ws, String id) {
        return jdbc.sql("select * from task where id = ? and workspace_id = ?").params(id, ws).query(TaskRow::map).optional();
    }

    public Optional<TaskRow> findById(String id) {
        return jdbc.sql("select * from task where id = ?").param(id).query(TaskRow::map).optional();
    }

    public Optional<TaskRow> findByIdem(String ws, String key) {
        if (key == null) return Optional.empty();
        return jdbc.sql("select * from task where workspace_id = ? and idempotency_key = ?").params(ws, key).query(TaskRow::map).optional();
    }

    public Optional<TaskRow> activeForApp(String appId) {
        return jdbc.sql("select * from task where app_id = ? and status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING') limit 1")
                .param(appId).query(TaskRow::map).optional();
    }

    /** 工作空间内最早的进行中任务（不含已删除应用的） */
    public Optional<TaskRow> activeInWorkspace(String ws) {
        return jdbc.sql("""
                select t.* from task t join app a on a.id = t.app_id
                where t.workspace_id = ? and a.deleted_at is null
                  and t.status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')
                order by t.created_at limit 1
                """).param(ws).query(TaskRow::map).optional();
    }

    public List<TaskRow> listByApp(String appId, boolean activeOnly) {
        String sql = "select * from task where app_id = ?" + (activeOnly ? " and status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')" : "") + " order by created_at desc";
        return jdbc.sql(sql).param(appId).query(TaskRow::map).list();
    }

    public Optional<TaskRow> latestForApp(String appId) {
        return jdbc.sql("select * from task where app_id = ? order by created_at desc limit 1").param(appId).query(TaskRow::map).optional();
    }

    /** 领取一个排队中的任务（SKIP LOCKED），写入租约 */
    public Optional<TaskRow> claimNext(String owner, int leaseSeconds) {
        return jdbc.sql("""
                update task set status = 'PREPARING', phase_label = '正在准备工作区', progress = 5, lease_owner = ?,
                  lease_until = now() + make_interval(secs => ?), started_at = now()
                where id = (select id from task where status = 'QUEUED' order by created_at for update skip locked limit 1)
                returning *
                """).params(owner, leaseSeconds).query(TaskRow::map).optional();
    }

    public void heartbeat(String id, String owner, int leaseSeconds) {
        jdbc.sql("update task set lease_until = now() + make_interval(secs => ?) where id = ? and lease_owner = ? and status in ('PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')")
                .params(leaseSeconds, id, owner).update();
    }

    /** 推进阶段；任务已在停止中或已结束时不覆盖，返回是否成功 */
    public boolean advance(String id, String status, String label, int progress) {
        return jdbc.sql("update task set status = ?, phase_label = ?, progress = ? where id = ? and status in ('PREPARING','RUNNING','VERIFYING','COMMITTING')")
                .params(status, label, progress, id).update() > 0;
    }

    public String status(String id) {
        return jdbc.sql("select status from task where id = ?").param(id).query(String.class).optional().orElse(null);
    }

    /** QUEUED → CANCELLED */
    public boolean cancelQueued(String id) {
        return jdbc.sql("update task set status = 'CANCELLED', phase_label = '已停止', finished_at = now() where id = ? and status = 'QUEUED'")
                .param(id).update() > 0;
    }

    /** 运行中 → CANCELLING */
    public boolean requestCancel(String id) {
        return jdbc.sql("update task set status = 'CANCELLING', phase_label = '正在停止' where id = ? and status in ('PREPARING','RUNNING','VERIFYING')")
                .param(id).update() > 0;
    }

    public void finish(String id, String status, String label, int progress, String errorCode, String errorMessage,
                       String resultVersionId, JsonNode result) {
        jdbc.sql("""
                update task set status = ?, phase_label = ?, progress = ?, error_code = ?, error_message = ?, result_version_id = ?,
                  result = ?::jsonb, finished_at = now(), lease_owner = null, lease_until = null
                where id = ? and status not in ('SUCCEEDED','FAILED','CANCELLED')
                """).params(status, label, progress, errorCode, errorMessage, resultVersionId,
                result == null ? null : Json.write(result), id).update();
    }

    public List<TaskRow> expiredLeases() {
        return jdbc.sql("select * from task where status in ('PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING') and (lease_until is null or lease_until < now())")
                .query(TaskRow::map).list();
    }

    public List<String> cancellingOwnedBy(String owner) {
        return jdbc.sql("select id from task where status = 'CANCELLING' and lease_owner = ?").param(owner).query(String.class).list();
    }

    /** 跨实例信号：写一条给任务所在实例；dedupe 相同（同一张确认卡）时不写，返回 false */
    public boolean postSignal(String taskId, String kind, String dedupe, JsonNode payload) {
        return jdbc.sql("insert into task_signal (task_id, kind, dedupe, payload) values (?,?,?,?::jsonb) on conflict (task_id, dedupe) do nothing")
                .params(taskId, kind, dedupe, Json.write(payload)).update() > 0;
    }

    public record Signal(String taskId, String kind, JsonNode payload) { }

    /** 取走本实例正在执行的任务的信号（按写入顺序） */
    public List<Signal> takeSignals(String owner) {
        return jdbc.sql("""
                update task_signal s set consumed_at = now() from task t
                where s.task_id = t.id and t.lease_owner = ? and s.consumed_at is null
                returning s.id, s.task_id, s.kind, s.payload
                """).param(owner)
                .query((rs, i) -> new Object[]{rs.getLong(1), new Signal(rs.getString(2), rs.getString(3), Json.read(rs.getString(4)))}).list()
                .stream().sorted(java.util.Comparator.comparingLong(o -> (Long) o[0])).map(o -> (Signal) o[1]).toList();
    }

    /** 这张确认卡最后一次的状态还是 pending（没选过、任务也没把它关掉） */
    public boolean confirmPending(String taskId, String confirmId) {
        return jdbc.sql("""
                select payload->>'state' from task_event where task_id = ? and type = 'confirm' and payload->>'id' = ?
                order by seq desc limit 1
                """).params(taskId, confirmId).query(String.class).optional().map("pending"::equals).orElse(false);
    }

    public long countCreatedToday(String ws) {
        return jdbc.sql("select count(*) from task where workspace_id = ? and created_at >= date_trunc('day', now())").param(ws).query(Long.class).single();
    }

    /** 进行中的任务（含生成计划） */
    public long countActiveAll(String ws) {
        return jdbc.sql("select count(*) from task where workspace_id = ? and status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')")
                .param(ws).query(Long.class).single();
    }

    public long countActive(String ws) {
        return jdbc.sql("select count(*) from task where workspace_id = ? and type <> 'plan' and status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')")
                .param(ws).query(Long.class).single();
    }

    public void addTokens(String id, long tokens) {
        jdbc.sql("update task set token_used = token_used + ? where id = ?").params(tokens, id).update();
    }

    // ---------- 检查点 ----------
    public record Checkpoint(String taskId, String workspaceDir, String agentDir, String sessionFile, boolean draftApplied) {}

    public void saveCheckpoint(Checkpoint c) {
        jdbc.sql("""
                insert into checkpoint (task_id, workspace_dir, agent_dir, session_file, draft_applied) values (?,?,?,?,?)
                on conflict (task_id) do update set workspace_dir = excluded.workspace_dir, agent_dir = excluded.agent_dir,
                  session_file = excluded.session_file, draft_applied = excluded.draft_applied
                """).params(c.taskId(), c.workspaceDir(), c.agentDir(), c.sessionFile(), c.draftApplied()).update();
    }

    public Optional<Checkpoint> checkpoint(String taskId) {
        return jdbc.sql("select * from checkpoint where task_id = ?").param(taskId)
                .query((rs, i) -> new Checkpoint(rs.getString("task_id"), rs.getString("workspace_dir"), rs.getString("agent_dir"),
                        rs.getString("session_file"), rs.getBoolean("draft_applied"))).optional();
    }

    public void deleteCheckpoint(String taskId) { jdbc.sql("delete from checkpoint where task_id = ?").param(taskId).update(); }
}
