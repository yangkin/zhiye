package com.tcl.csot.zhiye.repository;

import com.tcl.csot.zhiye.entity.VersionRow;

import com.tcl.csot.zhiye.util.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class VersionRepository {
    public record BuildRow(String id, String gitTree, String status, String previewPrefix, String publishPrefix, boolean editable) {}

    private final JdbcClient jdbc;

    public VersionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<VersionRow> find(String id) {
        return jdbc.sql("select * from version where id = ?").param(id).query(VersionRow::map).optional();
    }

    /** 带工作空间校验 */
    public Optional<VersionRow> find(String ws, String id) {
        return jdbc.sql("select v.* from version v join app a on a.id = v.app_id where v.id = ? and a.workspace_id = ? and a.deleted_at is null")
                .params(id, ws).query(VersionRow::map).optional();
    }

    public Optional<VersionRow> findByTask(String taskId) {
        return findByTask(taskId, "main");
    }

    public Optional<VersionRow> findByTask(String taskId, String stage) {
        return jdbc.sql("select * from version where task_id = ? and task_stage = ?").params(taskId, stage).query(VersionRow::map).optional();
    }

    public List<VersionRow> listByApp(String appId) {
        return jdbc.sql("select * from version where app_id = ? order by number desc").param(appId).query(VersionRow::map).list();
    }

    public int nextNumber(String appId) {
        return jdbc.sql("select coalesce(max(number), 0) + 1 from version where app_id = ?").param(appId).query(Integer.class).single();
    }

    public VersionRow insert(String appId, String title, String source, String commit, String tree, String parentId,
                             String restoredFromId, String taskId, String buildId) {
        return insert(appId, title, source, commit, tree, parentId, restoredFromId, taskId, buildId, "main");
    }

    public VersionRow insert(String appId, String title, String source, String commit, String tree, String parentId,
                             String restoredFromId, String taskId, String buildId, String stage) {
        String id = Ids.newId("ver");
        int number = nextNumber(appId);
        jdbc.sql("""
                insert into version (id, app_id, number, title, source, git_commit, git_tree, parent_version_id, restored_from_id, task_id, build_id, task_stage, created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?, clock_timestamp())
                """).params(id, appId, number, title, source, commit, tree, parentId, restoredFromId, taskId, buildId, stage).update();
        return find(id).orElseThrow();
    }

    public void setFavorite(String id, boolean fav) {
        jdbc.sql("update version set favorite = ? where id = ?").params(fav, id).update();
    }

    public void setNote(String id, String note) {
        jdbc.sql("update version set note = ? where id = ?").params(note, id).update();
    }

    // ---------- 构建（同一应用内按文件树复用） ----------
    public Optional<BuildRow> buildByTree(String appId, String tree) {
        return jdbc.sql("select * from build where app_id = ? and git_tree = ? and status = 'ready'").params(appId, tree).query(this::mapBuild).optional();
    }

    /** 构建属于哪个应用；旧数据里多个应用共用的构建返回空（不注入任何应用的令牌） */
    public Optional<String> buildApp(String buildId) {
        return jdbc.sql("select app_id from build where id = ?").param(buildId).query(String.class).optional().filter(java.util.Objects::nonNull);
    }

    public Optional<BuildRow> build(String id) {
        if (id == null) return Optional.empty();
        return jdbc.sql("select * from build where id = ?").param(id).query(this::mapBuild).optional();
    }

    private BuildRow mapBuild(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new BuildRow(rs.getString("id"), rs.getString("git_tree"), rs.getString("status"), rs.getString("preview_prefix"),
                rs.getString("publish_prefix"), rs.getBoolean("editable"));
    }

    public void insertBuild(String appId, BuildRow b) {
        jdbc.sql("insert into build (id, app_id, git_tree, status, preview_prefix, publish_prefix, editable) values (?,?,?,?,?,?,?) on conflict (app_id, git_tree) do nothing")
                .params(b.id(), appId, b.gitTree(), b.status(), b.previewPrefix(), b.publishPrefix(), b.editable()).update();
    }

    public long countByApp(String appId) {
        return jdbc.sql("select count(*) from version where app_id = ?").param(appId).query(Long.class).single();
    }
}
