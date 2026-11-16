package com.tcl.csot.zhiye.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Json;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;

public record TaskRow(String id, String workspaceId, String appId, String type, String status, String baseVersionId,
                      JsonNode input, JsonNode result, String errorCode, String errorMessage, String idempotencyKey,
                      int attempt, String parentTaskId, String phaseLabel, int progress, String resultVersionId,
                      String leaseOwner, Instant leaseUntil, long tokenUsed, long tokenBudget,
                      Instant createdAt, Instant startedAt, Instant finishedAt, JsonNode todos) {

    public static final Set<String> ACTIVE = Set.of("QUEUED", "PREPARING", "RUNNING", "VERIFYING", "COMMITTING", "CANCELLING");
    public static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    public static TaskRow map(ResultSet rs, int i) throws SQLException {
        return new TaskRow(rs.getString("id"), rs.getString("workspace_id"), rs.getString("app_id"), rs.getString("type"),
                rs.getString("status"), rs.getString("base_version_id"), Json.read(rs.getString("input")),
                Json.read(rs.getString("result")), rs.getString("error_code"), rs.getString("error_message"),
                rs.getString("idempotency_key"), rs.getInt("attempt"), rs.getString("parent_task_id"),
                rs.getString("phase_label"), rs.getInt("progress"), rs.getString("result_version_id"),
                rs.getString("lease_owner"), Db.ts(rs, "lease_until"), rs.getLong("token_used"), rs.getLong("token_budget"),
                Db.ts(rs, "created_at"), Db.ts(rs, "started_at"), Db.ts(rs, "finished_at"), Json.read(rs.getString("todos")));
    }

    public boolean active() { return ACTIVE.contains(status); }

    public boolean terminal() { return TERMINAL.contains(status); }
}
