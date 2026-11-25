package com.tcl.csot.zhiye.service.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.cloud.CloudService;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 检查点时间旅行服务（Code + Schema + QA 验收报告联合快照）：
 * 每次任务前、构建/QA 验收通过后自动落盘检查点，支持在工作台一键回退到任意历史检查点。
 */
@Service
public class CheckpointService {
    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    public record CheckpointRow(
            String id, String appId, String workspaceId, String taskId, String versionId,
            String stage, String title, String gitCommit, String treeId, String schemaSql,
            Boolean qaPassed, Integer qaScore, JsonNode qaReport, String createdAt
    ) {
        static CheckpointRow map(ResultSet rs, int i) throws SQLException {
            String qr = rs.getString("qa_report_json");
            return new CheckpointRow(
                    rs.getString("id"), rs.getString("app_id"), rs.getString("workspace_id"),
                    rs.getString("task_id"), rs.getString("version_id"), rs.getString("stage"),
                    rs.getString("title"), rs.getString("git_commit"), rs.getString("tree_id"),
                    rs.getString("schema_sql"),
                    rs.getObject("qa_passed", Boolean.class),
                    rs.getObject("qa_score", Integer.class),
                    qr == null || qr.isBlank() ? null : Json.read(qr),
                    Db.iso(Db.ts(rs, "created_at"))
            );
        }
    }

    private final JdbcClient jdbc;
    private final AppRepository apps;
    private final VersionRepository versions;
    private final GitService git;
    @Autowired(required = false)
    private CloudService cloud;

    public CheckpointService(JdbcClient jdbc, AppRepository apps, VersionRepository versions, GitService git) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.versions = versions;
        this.git = git;
    }

    public String record(String appId, String workspaceId, String taskId, String versionId,
                         String stage, String title, String gitCommit, String treeId,
                         String schemaSql, JsonNode qaReport) {
        if (gitCommit == null || gitCommit.isBlank() || treeId == null || treeId.isBlank()) return null;
        String id = Ids.newId("cp");
        Boolean qaPassed = qaReport != null && qaReport.has("passed") ? qaReport.path("passed").asBoolean() : null;
        Integer qaScore = qaReport != null && qaReport.has("score") ? qaReport.path("score").asInt() : null;
        String safeTitle = title == null || title.isBlank() ? "检查点快照" : (title.length() > 150 ? title.substring(0, 150) : title);
        try {
            jdbc.sql("""
                    insert into app_checkpoint
                    (id, app_id, workspace_id, task_id, version_id, stage, title, git_commit, tree_id, schema_sql, qa_passed, qa_score, qa_report_json)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """).params(id, appId, workspaceId, taskId, versionId, stage, safeTitle, gitCommit, treeId,
                    schemaSql, qaPassed, qaScore, qaReport == null ? null : Json.write(qaReport)).update();
            return id;
        } catch (Exception e) {
            log.warn("写入检查点失败 app={} stage={}: {}", appId, stage, e.getMessage());
            return null;
        }
    }

    public List<Map<String, Object>> list(String workspaceId, String appId) {
        apps.find(workspaceId, appId).orElseThrow(() -> ApiException.notFound("应用"));
        return jdbc.sql("select * from app_checkpoint where app_id = ? order by created_at desc limit 30")
                .param(appId).query(CheckpointRow::map).list().stream().map(CheckpointService::view).toList();
    }

    /** 一键时间旅行回退到指定检查点（无损创建新版本指向该检查点文件树，同时恢复云数据库 Schema 声明）。 */
    public Map<String, Object> restore(String workspaceId, String appId, String checkpointId) throws Exception {
        AppRow app = apps.find(workspaceId, appId).orElseThrow(() -> ApiException.notFound("应用"));
        CheckpointRow cp = jdbc.sql("select * from app_checkpoint where id = ? and app_id = ?")
                .params(checkpointId, appId).query(CheckpointRow::map).optional()
                .orElseThrow(() -> ApiException.notFound("检查点"));

        String msg = "回退至检查点：" + cp.title();
        GitService.CommitResult committed = git.commitTree(appId, cp.treeId(), msg);
        // 复用原版本（若有）的 buildId，确保静态预览立即可用
        VersionRow orig = cp.versionId() == null ? null : versions.find(cp.versionId()).orElse(null);
        String buildId = orig != null ? orig.buildId()
                : versions.buildByTree(appId, cp.treeId()).map(VersionRepository.BuildRow::id).orElse(null);
        VersionRow created = versions.insert(
                appId, msg, "restore",
                committed.commit(), committed.tree(),
                app.currentVersionId(), cp.versionId(),
                null, buildId
        );
        apps.update(appId, "current_version_id", created.id());
        if (cp.schemaSql() != null && !cp.schemaSql().isBlank() && cloud != null && cloud.isOn(appId)) {
            try {
                cloud.applySchema(appId);
            } catch (Exception e) {
                log.warn("回退检查点时同步 cloud schema 跳过 app={}: {}", appId, e.getMessage());
            }
        }
        record(appId, workspaceId, null, created.id(), "restore", "从「" + cp.title() + "」恢复", committed.commit(), committed.tree(), cp.schemaSql(), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("versionId", created.id());
        out.put("versionNumber", created.number());
        out.put("title", created.title());
        out.put("restoredFrom", cp.id());
        return out;
    }

    public String readSchemaSqlFromCommit(String appId, String gitCommit) {
        if (gitCommit == null || gitCommit.isBlank()) return null;
        try {
            return git.readFile(appId, gitCommit, "cloud/schema.sql")
                    .map(b -> new String(b, StandardCharsets.UTF_8))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public static Map<String, Object> view(CheckpointRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("appId", r.appId());
        m.put("taskId", r.taskId());
        m.put("versionId", r.versionId());
        m.put("stage", r.stage());
        m.put("title", r.title());
        m.put("gitCommit", r.gitCommit());
        m.put("hasSchema", r.schemaSql() != null && !r.schemaSql().isBlank());
        m.put("qaPassed", r.qaPassed());
        m.put("qaScore", r.qaScore());
        m.put("qaReport", r.qaReport());
        m.put("createdAt", r.createdAt());
        return m;
    }
}
