package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 历史页统计与回收站（当前 workspace 口径）。 */
@RestController
public class HistorySummaryController {
    private final JdbcClient jdbc;
    private final CurrentPrincipal principal;

    public HistorySummaryController(JdbcClient jdbc, CurrentPrincipal principal) {
        this.jdbc = jdbc;
        this.principal = principal;
    }

    @GetMapping("/api/history/summary")
    public Map<String, Object> summary() {
        String ws = principal.workspaceId();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versions", count("""
                select count(*) from version v join app a on a.id = v.app_id
                where a.workspace_id = ? and a.deleted_at is null
                """, ws));
        m.put("apps", count("select count(*) from app where workspace_id = ? and deleted_at is null", ws));
        m.put("published", count("""
                select count(*) from app where workspace_id = ? and deleted_at is null and published_version_id is not null
                """, ws));
        m.put("cloudEnabled", count("""
                select count(*) from app_cloud c join app a on a.id = c.app_id
                where a.workspace_id = ? and a.deleted_at is null and c.status = 'on'
                """, ws));
        m.put("pageViews", count("""
                select coalesce(sum(p.pv), 0) from app_pageview_daily p join app a on a.id = p.app_id
                where a.workspace_id = ? and a.deleted_at is null
                """, ws));
        return m;
    }

    /** 回收站：已软删的应用（只读展示；恢复仍走现有入口时再补） */
    @GetMapping("/api/apps/trash")
    public List<Map<String, Object>> trash() {
        String ws = principal.workspaceId();
        return jdbc.sql("""
                select id, name, type, description, phase, updated_at, deleted_at
                from app where workspace_id = ? and deleted_at is not null
                order by deleted_at desc limit 100
                """).param(ws).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString("id"));
            m.put("name", rs.getString("name"));
            m.put("type", rs.getString("type"));
            m.put("description", rs.getString("description"));
            m.put("phase", rs.getString("phase"));
            m.put("updatedAt", com.tcl.csot.zhiye.util.Db.iso(rs.getTimestamp("updated_at") == null ? null
                    : rs.getTimestamp("updated_at").toInstant()));
            m.put("deletedAt", com.tcl.csot.zhiye.util.Db.iso(rs.getTimestamp("deleted_at") == null ? null
                    : rs.getTimestamp("deleted_at").toInstant()));
            return m;
        }).list();
    }

    private long count(String sql, String ws) {
        return jdbc.sql(sql).param(ws).query(Long.class).single();
    }
}
