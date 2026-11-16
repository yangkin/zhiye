package com.tcl.csot.zhiye.entity;

import com.tcl.csot.zhiye.util.Db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

public record VersionRow(String id, String appId, int number, String title, String source, String gitCommit, String gitTree,
                         String parentVersionId, String restoredFromId, String taskId, String buildId, boolean favorite,
                         String note, Instant createdAt) {
    public static VersionRow map(ResultSet rs, int i) throws SQLException {
        return new VersionRow(rs.getString("id"), rs.getString("app_id"), rs.getInt("number"), rs.getString("title"),
                rs.getString("source"), rs.getString("git_commit"), rs.getString("git_tree"), rs.getString("parent_version_id"),
                rs.getString("restored_from_id"), rs.getString("task_id"), rs.getString("build_id"), rs.getBoolean("favorite"),
                rs.getString("note"), Db.ts(rs, "created_at"));
    }
}
