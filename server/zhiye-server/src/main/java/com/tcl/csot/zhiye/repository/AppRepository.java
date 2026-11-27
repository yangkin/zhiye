package com.tcl.csot.zhiye.repository;

import com.tcl.csot.zhiye.entity.AppRow;

import com.tcl.csot.zhiye.util.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AppRepository {
    private final JdbcClient jdbc;

    public AppRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void insert(AppRow a) {
        jdbc.sql("""
                insert into app (id, workspace_id, name, name_locked, type, description, prompt, plan_doc, style, materials,
                  mode, model, favorite, phase, source, framework, project_kind, visible, slug, asset_ids, repo_path)
                values (:id, :ws, :name, :nameLocked, :type, :description, :prompt, :planDoc, :style, :materials,
                  :mode, :model, false, :phase, :source, :framework, :projectKind, :visible, :slug, :assetIds::jsonb, :repo)
                """)
                .param("id", a.id()).param("ws", a.workspaceId()).param("name", a.name()).param("nameLocked", a.nameLocked())
                .param("type", a.type()).param("description", a.description()).param("prompt", a.prompt())
                .param("planDoc", a.planDoc()).param("style", a.style()).param("materials", a.materials())
                .param("mode", a.mode()).param("model", a.model()).param("phase", a.phase()).param("source", a.source())
                .param("framework", a.framework()).param("projectKind", a.projectKind()).param("visible", a.visible())
                .param("slug", a.slug()).param("assetIds", Json.write(a.assetIds() == null ? List.of() : a.assetIds()))
                .param("repo", "repos/" + a.id() + ".git")
                .update();
    }

    /** 按工作空间查找（未删除的）应用 */
    public Optional<AppRow> find(String ws, String id) {
        return jdbc.sql("select * from app where id = ? and workspace_id = ? and deleted_at is null")
                .params(id, ws).query(AppRow::map).optional();
    }

    public Optional<AppRow> findById(String id) {
        return jdbc.sql("select * from app where id = ?").param(id).query(AppRow::map).optional();
    }

    public Optional<AppRow> findBySlug(String slug) {
        return jdbc.sql("select * from app where slug = ? and deleted_at is null").param(slug).query(AppRow::map).optional();
    }

    public List<AppRow> listVisible(String ws) {
        return jdbc.sql("select * from app where workspace_id = ? and deleted_at is null and visible order by updated_at desc")
                .param(ws).query(AppRow::map).list();
    }

    public void setPhase(String id, String phase) {
        jdbc.sql("update app set phase = ?, updated_at = now() where id = ?").params(phase, id).update();
    }

    public void markVisibleAndPhase(String id, String phase) {
        jdbc.sql("update app set visible = true, phase = ?, updated_at = now() where id = ?").params(phase, id).update();
    }

    public void touch(String id) { jdbc.sql("update app set updated_at = now() where id = ?").param(id).update(); }

    public void update(String id, String column, Object value) {
        // column 只来自代码常量
        jdbc.sql("update app set " + column + " = ?, updated_at = now() where id = ?").params(value, id).update();
    }

    public void setPlan(String id, String name, String planDoc, String phase) {
        if (name != null)
            jdbc.sql("update app set name = case when name_locked then name else ? end, plan_doc = ?, phase = ?, updated_at = now() where id = ?")
                    .params(name, planDoc, phase, id).update();
        else
            jdbc.sql("update app set plan_doc = ?, phase = ?, updated_at = now() where id = ?").params(planDoc, phase, id).update();
    }

    public void softDelete(String id) {
        jdbc.sql("update app set deleted_at = now() where id = ?").param(id).update();
    }

    /** 锁住应用行（版本号分配、写指针时用） */
    public void lock(String id) {
        jdbc.sql("select id from app where id = ? for update").param(id).query(String.class).optional();
    }
}
