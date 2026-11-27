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
    @org.springframework.beans.factory.annotation.Autowired(required = false) @org.springframework.context.annotation.Lazy
    private PublishReviewService publishReviews;

    public Views(ZhiyeProperties props, VersionRepository versions, TaskRepository tasks, DraftRepository drafts, JdbcClient jdbc) {
        this.props = props;
        this.versions = versions;
        this.tasks = tasks;
        this.drafts = drafts;
        this.jdbc = jdbc;
    }

    public String previewUrl(String buildId) {
        String base = props.site().buildPreviewBase();
        String root = base == null || base.isBlank() ? props.site().previewBase() : base;
        return buildId == null ? null : root.replaceAll("/+$", "") + "/p/" + buildId + "/index.html";
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
        if (t.result() != null && !t.result().isNull()) m.put("result", t.result());
        m.put("tokenUsed", t.tokenUsed());
        boolean discarded = t.result() != null && t.result().path("discarded").asBoolean(false);
        m.put("discarded", discarded);
        m.put("resumable", "CANCELLED".equals(t.status()) && !discarded && tasks.checkpoint(t.id()).isPresent());
        m.put("retryable", "FAILED".equals(t.status()) && !discarded && !"import".equals(t.type()) && !"clone".equals(t.type()));
        m.put("createdAt", Db.iso(t.createdAt()));
        m.put("startedAt", Db.iso(t.startedAt()));
        m.put("finishedAt", Db.iso(t.finishedAt()));
        return m;
    }

    public Map<String, Object> summary(AppRow a) {
        VersionRow cur = a.currentVersionId() == null ? null : versions.find(a.currentVersionId()).orElse(null);
        boolean hasDraft = drafts.exists(a.id());
        boolean cloudEnabled = !a.isSkill() && !a.isService() && jdbc.sql("select exists(select 1 from app_cloud where app_id = ? and status = 'on')")
                .params(a.id()).query(Boolean.class).single();
        TaskRow activeTask = tasks.activeForApp(a.id()).orElse(null);
        Map<String, Object> rev = (publishReviews != null && !a.isSkill() && !a.isScript())
                ? publishReviews.summaryForApp(a.id()) : null;
        return buildSummary(a, cur, hasDraft, cloudEnabled, activeTask, rev);
    }

    /** 批量构造应用列表摘要：将 version / draft / app_cloud / activeTask / publish_request 合并为 5 条批量 SQL，消除 /api/apps 的 N+1 查询。 */
    public List<Map<String, Object>> summaries(List<AppRow> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        if (rows.size() == 1) return List.of(summary(rows.get(0)));

        List<String> appIds = rows.stream().map(AppRow::id).toList();
        List<String> curVersionIds = rows.stream().map(AppRow::currentVersionId)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();

        Map<String, VersionRow> verById = new HashMap<>();
        if (!curVersionIds.isEmpty()) {
            jdbc.sql("select * from version where id in (:ids)")
                    .param("ids", curVersionIds).query(VersionRow::map).list()
                    .forEach(v -> verById.put(v.id(), v));
        }

        Set<String> draftAppIds = new HashSet<>(jdbc.sql("select app_id from draft where app_id in (:ids)")
                .param("ids", appIds).query(String.class).list());

        Set<String> cloudAppIds = new HashSet<>(jdbc.sql("select app_id from app_cloud where app_id in (:ids) and status = 'on'")
                .param("ids", appIds).query(String.class).list());

        Map<String, TaskRow> activeTaskByApp = new HashMap<>();
        jdbc.sql("select * from task where app_id in (:ids) and status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')")
                .param("ids", appIds).query(TaskRow::map).list()
                .forEach(t -> activeTaskByApp.putIfAbsent(t.appId(), t));

        Map<String, Map<String, Object>> reviewByApp = new HashMap<>();
        if (publishReviews != null) {
            List<String> reviewAppIds = rows.stream().filter(a -> !a.isSkill() && !a.isScript()).map(AppRow::id).toList();
            if (!reviewAppIds.isEmpty()) {
                jdbc.sql("""
                        select app_id, id, version_id, version_number, status, review_comment, reviewer_actor, reviewed_at, created_at
                        from publish_request
                        where app_id in (:ids)
                        order by created_at desc
                        """).param("ids", reviewAppIds).query(rs -> {
                    String aid = rs.getString("app_id");
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", rs.getString("id"));
                    item.put("versionId", rs.getString("version_id"));
                    item.put("versionNumber", rs.getInt("version_number"));
                    item.put("status", rs.getString("status"));
                    item.put("reviewComment", rs.getString("review_comment"));
                    item.put("reviewerActor", rs.getString("reviewer_actor"));
                    item.put("reviewedAt", Db.iso(Db.ts(rs, "reviewed_at")));
                    item.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
                    Map<String, Object> bucket = reviewByApp.computeIfAbsent(aid, k -> {
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("pending", null);
                        b.put("latest", null);
                        return b;
                    });
                    if (bucket.get("latest") == null) bucket.put("latest", item);
                    if (bucket.get("pending") == null && "pending".equals(item.get("status"))) bucket.put("pending", item);
                });
            }
        }

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (AppRow a : rows) {
            VersionRow cur = a.currentVersionId() == null ? null : verById.get(a.currentVersionId());
            boolean cloudEnabled = !a.isSkill() && !a.isService() && cloudAppIds.contains(a.id());
            Map<String, Object> rev = (!a.isSkill() && !a.isScript()) ? reviewByApp.get(a.id()) : null;
            out.add(buildSummary(a, cur, draftAppIds.contains(a.id()), cloudEnabled, activeTaskByApp.get(a.id()), rev));
        }
        return out;
    }

    private Map<String, Object> buildSummary(AppRow a, VersionRow cur, boolean hasDraft, boolean cloudEnabled,
                                             TaskRow activeTask, Map<String, Object> rev) {
        Map<String, Object> m = new LinkedHashMap<>();
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
        m.put("hasDraft", hasDraft);
        m.put("visualEditable", a.visualEditable());
        m.put("cloudEnabled", cloudEnabled);
        m.put("activeTask", taskRef(activeTask));
        m.put("skillhub", skillHub == null ? null : skillHub.summary(a));
        m.put("service", a.isService() && monolithService != null ? monolithService.summaryForApp(a.id()) : null);
        if (rev != null) {
            m.put("pendingPublishRequest", rev.get("pending"));
            m.put("latestPublishRequest", rev.get("latest"));
        } else {
            m.put("pendingPublishRequest", null);
            m.put("latestPublishRequest", null);
        }
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
