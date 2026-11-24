package com.tcl.csot.zhiye.service.app;

import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.repository.DraftRepository;
import com.tcl.csot.zhiye.repository.MessageRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.repository.TaskRepository;
import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.entity.VersionRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

/** 把数据库行转换成 docs/api-contract.md 中的数据类型。 */
@Component
public class Views {
    private final ZhiyeProperties props;
    private final VersionRepository versions;
    private final TaskRepository tasks;
    private final DraftRepository drafts;
    private final JdbcClient jdbc;
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy
    private com.tcl.csot.zhiye.service.skillhub.SkillHubService skillHub;
    @org.springframework.beans.factory.annotation.Autowired(required = false) @org.springframework.context.annotation.Lazy
    private com.tcl.csot.zhiye.service.runtime.MonolithServiceManager monolithService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.runtime.ServiceCrypto serviceCrypto;

    public Views(ZhiyeProperties props, VersionRepository versions, TaskRepository tasks, DraftRepository drafts, JdbcClient jdbc) {
        this.props = props;
        this.versions = versions;
        this.tasks = tasks;
        this.drafts = drafts;
        this.jdbc = jdbc;
    }

    public String previewUrl(String buildId) {
        return buildId == null ? null : props.site().previewBase().replaceAll("/+$", "") + "/p/" + buildId + "/index.html";
    }

    public String siteUrl(String slug) {
        return props.site().siteScheme() + "://" + slug + "." + props.site().siteHostSuffix() + "/";
    }

    public Map<String, Object> versionRef(VersionRow v) {
        if (v == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("number", v.number());
        m.put("title", v.title());
        m.put("createdAt", Db.iso(v.createdAt()));
        return m;
    }

    public Map<String, Object> taskRef(TaskRow t) {
        if (t == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("type", t.type());
        m.put("status", t.status());
        m.put("todos", t.todos() == null ? Json.M.createArrayNode() : t.todos());
        return m;
    }

    public Map<String, Object> task(TaskRow t) {
        Map<String, Object> m = taskRef(t);
        m.put("appId", t.appId());
        m.put("phaseLabel", t.phaseLabel());
        m.put("progress", t.progress());
        m.put("error", t.errorCode() == null ? null : Map.of("code", t.errorCode(), "message", Objects.toString(t.errorMessage(), "")));
        m.put("resultVersionId", t.resultVersionId());
        m.put("tokenUsed", t.tokenUsed());
        m.put("resumable", "CANCELLED".equals(t.status()) && tasks.checkpoint(t.id()).isPresent());
        m.put("retryable", "FAILED".equals(t.status()) && !"import".equals(t.type()) && !"clone".equals(t.type()));
        m.put("createdAt", Db.iso(t.createdAt()));
        m.put("startedAt", Db.iso(t.startedAt()));
        m.put("finishedAt", Db.iso(t.finishedAt()));
        return m;
    }

    public Map<String, Object> summary(AppRow a) {
        Map<String, Object> m = new LinkedHashMap<>();
        VersionRow cur = a.currentVersionId() == null ? null : versions.find(a.currentVersionId()).orElse(null);
        m.put("id", a.id());
        m.put("name", a.name());
        m.put("type", a.type());
        m.put("description", a.description());
        m.put("favorite", a.favorite());
        m.put("phase", a.phase());
        m.put("source", a.source());
        m.put("framework", a.framework());
        m.put("projectKind", a.projectKind());
        m.put("createdAt", Db.iso(a.createdAt()));
        m.put("updatedAt", Db.iso(a.updatedAt()));
        m.put("currentVersion", versionRef(cur));
        m.put("publishedVersionId", a.publishedVersionId());
        String svcPreview = a.isService() ? siteUrl(a.slug()) + (serviceCrypto == null ? "" : "?_zhiye_preview=" + serviceCrypto.signPreviewToken(a.id())) : null;
        m.put("previewUrl", cur == null || a.isSkill() ? null : a.isService() ? svcPreview : previewUrl(cur.buildId()));
        m.put("siteUrl", a.publishedVersionId() == null || a.isSkill() ? null : siteUrl(a.slug()));
        m.put("hasDraft", drafts.exists(a.id()));
        m.put("visualEditable", a.visualEditable());
        m.put("cloudEnabled", !a.isSkill() && !a.isService() && jdbc.sql("select exists(select 1 from app_cloud where app_id = ? and status = 'on')")
                .params(a.id()).query(Boolean.class).single());
        m.put("activeTask", taskRef(tasks.activeForApp(a.id()).orElse(null)));
        m.put("skillhub", skillHub == null ? null : skillHub.summary(a));
        m.put("service", a.isService() && monolithService != null ? monolithService.summaryForApp(a.id()) : null);
        return m;
    }

    public Map<String, Object> detail(AppRow a) {
        Map<String, Object> m = summary(a);
        m.put("prompt", a.prompt());
        m.put("planDoc", a.planDoc());
        m.put("draftBaseVersionId", drafts.find(a.id()).map(DraftRepository.DraftRow::baseVersionId).orElse(null));
        m.put("style", a.style());
        m.put("materials", a.materials());
        m.put("mode", a.mode());
        m.put("model", a.model());
        List<Map<String, Object>> assets = new ArrayList<>();
        if (a.assetIds() != null && !a.assetIds().isEmpty()) {
            for (String id : a.assetIds()) {
                jdbc.sql("select * from asset where id = ? and workspace_id = ?").params(id, a.workspaceId())
                        .query((rs, i) -> asset(rs)).optional().ifPresent(assets::add);
            }
        }
        m.put("assets", assets);
        // 应用上挂载的技能：持久保存，生成/修改/问答都会带上；已删除的技能不再返回
        List<Map<String, Object>> skills = new ArrayList<>();
        String ids = jdbc.sql("select skill_app_ids::text from app where id = ?").param(a.id()).query(String.class).optional().orElse(null);
        if (ids != null) {
            for (com.fasterxml.jackson.databind.JsonNode n : com.tcl.csot.zhiye.util.Json.read(ids)) {
                jdbc.sql("select id, name from app where id = ? and workspace_id = ? and deleted_at is null").params(n.asText(), a.workspaceId())
                        .query((rs, i) -> Map.<String, Object>of("id", rs.getString("id"), "name", rs.getString("name"))).optional().ifPresent(skills::add);
            }
        }
        m.put("skills", skills);
        return m;
    }

    public Map<String, Object> asset(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getString("id"));
        m.put("name", rs.getString("name"));
        m.put("mime", rs.getString("mime"));
        m.put("size", rs.getLong("size"));
        m.put("url", "/api/assets/" + rs.getString("id") + "/content");
        m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
        return m;
    }

    public Map<String, Object> version(VersionRow v, AppRow a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("appId", v.appId());
        m.put("number", v.number());
        m.put("title", v.title());
        m.put("source", v.source());
        m.put("createdAt", Db.iso(v.createdAt()));
        m.put("favorite", v.favorite());
        m.put("note", v.note());
        m.put("isCurrent", v.id().equals(a.currentVersionId()));
        m.put("isPublished", v.id().equals(a.publishedVersionId()));
        m.put("restoredFromNumber", v.restoredFromId() == null ? null : versions.find(v.restoredFromId()).map(VersionRow::number).orElse(null));
        m.put("previewUrl", a.isSkill() ? null : previewUrl(v.buildId()));
        return m;
    }

    public Map<String, Object> message(MessageRepository.MessageRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("role", r.role());
        m.put("content", r.content());
        m.put("elementContext", r.elementContext());
        m.put("assetIds", r.assetIds() == null ? java.util.List.of() : r.assetIds());
        // 附件名和类型一并返回，消息上直接显示 / 预览
        if (r.assetIds() != null && !r.assetIds().isEmpty()) {
            List<Map<String, Object>> files = new ArrayList<>();
            for (JsonNode n : r.assetIds()) {
                String aid = n.asText();
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", aid);
                jdbc.sql("select name, mime from asset where id = ?").param(aid)
                        .query((rs, i) -> Map.of("name", rs.getString(1), "mime", rs.getString(2))).optional()
                        .ifPresentOrElse(meta -> { f.putAll(meta); files.add(f); },
                                () -> { f.put("name", aid); files.add(f); });
            }
            m.put("assets", files);
        }
        m.put("skillAppIds", r.skillAppIds() == null ? java.util.List.of() : r.skillAppIds());
        m.put("steer", r.steer());
        m.put("taskId", r.taskId());
        // 助手消息关联它产生的版本（「查看改动」「回到此版本」用）
        m.put("versionId", "assistant".equals(r.role()) && r.taskId() != null
                ? tasks.findById(r.taskId()).map(TaskRow::resultVersionId).orElse(null) : null);
        m.put("status", r.status());
        m.put("createdAt", Db.iso(r.createdAt()));
        return m;
    }

    public Map<String, Object> draft(DraftRepository.DraftRow d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseVersionId", d.baseVersionId());
        m.put("patches", d.patches() == null ? Json.M.createArrayNode() : d.patches());
        m.put("updatedAt", Db.iso(d.updatedAt()));
        return m;
    }
}
