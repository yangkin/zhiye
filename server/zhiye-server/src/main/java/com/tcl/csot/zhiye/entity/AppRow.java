package com.tcl.csot.zhiye.entity;

import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Json;
import com.fasterxml.jackson.core.type.TypeReference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

public record AppRow(String id, String workspaceId, String name, boolean nameLocked, String type, String description,
                     String prompt, String planDoc, String style, boolean materials, String mode, String model,
                     boolean favorite, String phase, String source, String framework, String projectKind,
                     boolean visible, String slug, List<String> assetIds, String currentVersionId,
                     String publishedVersionId, Instant createdAt, Instant updatedAt, Instant deletedAt) {

    public static AppRow map(ResultSet rs, int i) throws SQLException {
        return new AppRow(rs.getString("id"), rs.getString("workspace_id"), rs.getString("name"),
                rs.getBoolean("name_locked"), rs.getString("type"), rs.getString("description"), rs.getString("prompt"),
                rs.getString("plan_doc"), rs.getString("style"), rs.getBoolean("materials"), rs.getString("mode"),
                rs.getString("model"), rs.getBoolean("favorite"), rs.getString("phase"), rs.getString("source"),
                rs.getString("framework"), rs.getString("project_kind"), rs.getBoolean("visible"), rs.getString("slug"),
                Json.read(rs.getString("asset_ids"), new TypeReference<List<String>>() {}),
                rs.getString("current_version_id"), rs.getString("published_version_id"),
                Db.ts(rs, "created_at"), Db.ts(rs, "updated_at"), Db.ts(rs, "deleted_at"));
    }

    public boolean isSkill() { return "skill".equals(projectKind); }
    /** 纯脚本项目：没有页面，和技能包一样只交付代码 */
    public boolean isScript() { return "script".equals(projectKind); }

    public boolean isVue() { return "vue".equals(projectKind); }

    /** 平台模板生成的前端项目（依赖固定、预装缓存）：React 模板或 Vue 模板 */
    public boolean isTemplateLike() { return "template".equals(projectKind) || isVue(); }
    public boolean noPreview() { return isSkill() || isScript(); }

    public boolean visualEditable() { return "template".equals(projectKind) || "static".equals(projectKind); }
}
