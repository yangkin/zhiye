package com.tcl.csot.zhiye.service.template;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.service.app.Views;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.storage.ObjectStore;
import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.service.task.TaskService;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.service.workspace.QuotaService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 灵感社区：把应用当前版本发布成社区作品（文件树 zip + 页面快照），
 * 别人可以看效果、点赞、「使用模板」复刻成自己的应用（clone 任务，保留项目形态，带表结构不带数据）。
 */
@Service
public class TemplateService {
    private static final Set<String> TABS = Set.of("all", "liked", "mine");
    private static final Map<String, String> SORTS = Map.of(
            "new", "t.featured desc, t.pinned_order desc, t.created_at desc",
            "likes", "t.featured desc, t.pinned_order desc, t.like_count desc, t.created_at desc",
            "uses", "t.featured desc, t.pinned_order desc, t.clone_count desc, t.created_at desc");

    private final JdbcClient jdbc;
    private final AppRepository apps;
    private final VersionRepository versions;
    private final GitService git;
    private final ObjectStore store;
    private final TaskService taskService;
    private final QuotaService quota;
    private final Views views;
    private final TemplateRequirementsService requirementsService;
    private final String previewBase;

    public TemplateService(JdbcClient jdbc, AppRepository apps, VersionRepository versions, GitService git,
                           ObjectStore store, TaskService taskService, QuotaService quota, Views views,
                           TemplateRequirementsService requirementsService, ZhiyeProperties props) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.versions = versions;
        this.git = git;
        this.store = store;
        this.taskService = taskService;
        this.quota = quota;
        this.views = views;
        this.requirementsService = requirementsService;
        String templateBase = props.site().templatePreviewBase();
        this.previewBase = (templateBase == null || templateBase.isBlank() ? props.site().previewBase() : templateBase).replaceAll("/+$", "");
    }

    private record Tpl(String id, String ws, String name, String description, String type, String kind, String framework,
                       Integer sourceVersion, String objectKey, int fileCount, Instant createdAt, String previewPrefix,
                       String author, String sourceAppId, int cloneCount, int viewCount, int likeCount, boolean liked,
                       boolean featured, int pinnedOrder, com.fasterxml.jackson.databind.JsonNode requirements) {}

    private static Tpl map(ResultSet rs, int i) throws SQLException {
        return new Tpl(rs.getString("id"), rs.getString("workspace_id"), rs.getString("name"), rs.getString("description"),
                rs.getString("app_type"), rs.getString("project_kind"), rs.getString("framework"),
                rs.getObject("source_version", Integer.class), rs.getString("object_key"), rs.getInt("file_count"),
                Db.ts(rs, "created_at"), rs.getString("preview_prefix"), rs.getString("author"), rs.getString("source_app_id"),
                rs.getInt("clone_count"), rs.getInt("view_count"), rs.getInt("like_count"), rs.getBoolean("liked"),
                rs.getBoolean("featured"), rs.getInt("pinned_order"),
                TemplateRequirementsService.parseOrEmpty(rs.getString("requirements")));
    }

    private static final String SELECT = "select t.*, exists(select 1 from template_like l where l.template_id = t.id and l.workspace_id = :ws) as liked from template t";

    private Optional<Tpl> find(String ws, String id) {
        return jdbc.sql(SELECT + " where t.id = :id").param("ws", ws).param("id", id).query(TemplateService::map).optional();
    }

    /** 发布到灵感社区：快照当前版本的文件树和页面；同一个来源应用只允许发布一个作品。 */
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(String ws, String appId, String name, String description, String author) throws Exception {
        AppRow app = apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
        if (app.currentVersionId() == null) throw ApiException.validation("应用还没有生成内容，不能发布到社区");
        jdbc.sql("select pg_advisory_xact_lock(hashtext(?))").param("template-publish:" + app.id()).query(Object.class).single();
        boolean published = jdbc.sql("select exists(select 1 from template where source_app_id = ?)").param(app.id()).query(Boolean.class).single();
        if (published) throw ApiException.conflict("APP_ALREADY_PUBLISHED", "这个应用已经发布到灵感社区，请到「我发布的」中查看，不要重复发布");
        VersionRow v = versions.find(app.currentVersionId()).orElseThrow();
        String n = clip(name == null || name.isBlank() ? app.name() : name.trim(), 40);
        String d = clip(description == null ? "" : description.trim(), 200);
        String id = Ids.newId("tpl");
        String key = "templates/" + id + "/project.zip";
        Path tmp = Files.createTempFile("zhiye-tpl", ".zip");
        try {
            try (var out = Files.newOutputStream(tmp)) {
                git.exportZip(app.id(), v.gitCommit(), "project", out,
                        p -> !TemplateRequirementsService.isSensitiveTemplateFile(p));
            }
            store.put(key, tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
        int count = (int) git.listFiles(app.id(), v.gitCommit()).stream()
                .filter(f -> !TemplateRequirementsService.isSensitiveTemplateFile(f.path()))
                .count();
        ObjectNode requirements = requirementsService.extract(app, v.gitCommit());
        String preview = snapshot(id, v.buildId());
        jdbc.sql("""
                insert into template (id, workspace_id, name, description, app_type, project_kind, framework, source_version,
                  object_key, file_count, preview_prefix, author, source_app_id, requirements) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)""")
                .params(id, ws, n, d, app.type(), app.projectKind(), app.framework(), v.number(), key, count, preview, author, app.id(),
                        Json.write(requirements)).update();
        return view(find(ws, id).orElseThrow(), ws);
    }

    /**
     * 页面快照：把这个版本的发布产物复制到 templates/{id}/site/。
     * 社区里看效果只走快照（SiteFilter /t/{id}/），不注入令牌，别人碰不到原应用的数据。
     */
    private String snapshot(String id, String buildId) {
        if (buildId == null) return null;
        var build = versions.build(buildId).orElse(null);
        if (build == null) return null;
        String src = build.publishPrefix() != null ? build.publishPrefix() : build.previewPrefix();
        if (src == null) return null;
        String dst = "templates/" + id + "/site";
        try {
            List<String> keys = store.list(src + "/");
            if (keys.stream().noneMatch(k -> k.equals(src + "/index.html"))) return null;
            for (String k : keys) {
                var bytes = store.getBytes(k);
                if (bytes.isPresent()) store.put(dst + k.substring(src.length()), bytes.get());
            }
            return dst;
        } catch (Exception e) {
            try { store.deletePrefix(dst + "/"); } catch (Exception ignore) { /* 尽力清理 */ }
            return null;
        }
    }

    /** 社区列表：tab = all / liked / mine，sort = new / likes / uses，可按类型和关键词筛 */
    public List<Map<String, Object>> list(String ws, String tab, String sort, String type, String q) {
        StringBuilder sql = new StringBuilder(SELECT + " where true");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ws", ws);
        String tb = TABS.contains(tab) ? tab : "all";
        if (tb.equals("mine")) sql.append(" and t.workspace_id = :ws");
        if (tb.equals("liked")) sql.append(" and exists(select 1 from template_like l where l.template_id = t.id and l.workspace_id = :ws)");
        if (type != null && !type.isBlank()) { sql.append(" and t.app_type = :type"); p.put("type", type); }
        if (q != null && !q.isBlank()) {
            sql.append(" and (t.name ilike :q or t.description ilike :q or coalesce(t.author, '') ilike :q)");
            p.put("q", "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        sql.append(" order by ").append(SORTS.getOrDefault(sort, SORTS.get("new"))).append(" limit 300");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tpl t : jdbc.sql(sql.toString()).params(p).query(TemplateService::map).list()) out.add(view(t, ws));
        return out;
    }

    /** 作品详情：打开一次计一次浏览；附带同类型的相关推荐 */
    public Map<String, Object> detail(String ws, String id) {
        jdbc.sql("update template set view_count = view_count + 1 where id = ?").param(id).update();
        Tpl t = find(ws, id).orElseThrow(() -> ApiException.notFound("作品"));
        Map<String, Object> m = view(t, ws);
        String siteUrl = t.sourceAppId() == null ? null : apps.findById(t.sourceAppId())
                .filter(a -> a.deletedAt() == null && a.publishedVersionId() != null)
                .map(a -> (String) views.summary(a).get("siteUrl")).orElse(null);
        m.put("siteUrl", siteUrl);
        List<Map<String, Object>> related = new ArrayList<>();
        for (Tpl r : jdbc.sql(SELECT + " where t.id <> :id order by (t.app_type = :type) desc, t.like_count desc, t.created_at desc limit 4")
                .param("ws", ws).param("id", id).param("type", t.type()).query(TemplateService::map).list())
            related.add(view(r, ws));
        m.put("related", related);
        return m;
    }

    /** 点赞 / 取消点赞（每个工作空间对一个作品最多一次） */
    public Map<String, Object> like(String ws, String id, boolean on) {
        find(ws, id).orElseThrow(() -> ApiException.notFound("作品"));
        int changed = on
                ? jdbc.sql("insert into template_like (template_id, workspace_id) values (?, ?) on conflict do nothing").params(id, ws).update()
                : jdbc.sql("delete from template_like where template_id = ? and workspace_id = ?").params(id, ws).update();
        if (changed > 0)
            jdbc.sql("update template set like_count = greatest(0, like_count + ?) where id = ?").params(on ? 1 : -1, id).update();
        Tpl t = find(ws, id).orElseThrow();
        return Map.of("liked", t.liked(), "likeCount", t.likeCount());
    }

    /** 删除作品：只有发布者可以删 */
    public void remove(String ws, String id) {
        Tpl t = find(ws, id).orElseThrow(() -> ApiException.notFound("作品"));
        if (!t.ws().equals(ws)) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只有发布者可以删除这个作品");
        jdbc.sql("delete from template where id = ?").param(id).update();
        try {
            store.delete(t.objectKey());
            if (t.previewPrefix() != null) store.deletePrefix(t.previewPrefix() + "/");
        } catch (Exception e) { /* 文件没了不影响删除 */ }
    }

    /** 使用模板：创建新应用 + clone 任务 */
    public Map<String, Object> cloneApp(String ws, String id, String name, String idemKey) {
        Optional<TaskRow> dup = taskService.byIdem(ws, idemKey);
        if (dup.isPresent()) {
            AppRow app = apps.findById(dup.get().appId()).orElseThrow();
            return Map.of("app", views.summary(app), "task", views.task(dup.get()));
        }
        Tpl t = find(ws, id).orElseThrow(() -> ApiException.notFound("作品"));
        String appName = clip(name == null || name.isBlank() ? t.name() : name.trim(), 40);
        return quota.withWorkspaceLock(ws, () -> cloneLocked(ws, t, appName, idemKey));
    }

    private Map<String, Object> cloneLocked(String ws, Tpl t, String name, String idemKey) {
        quota.checkCanCreateApp(ws);
        quota.checkCanStart(ws, true);
        String appId = Ids.newId("app");
        AppRow row = new AppRow(appId, ws, name, true, t.type(), "来自灵感社区「" + t.name() + "」", "使用灵感社区的作品「" + t.name() + "」创建并部署。", "",
                "lavender", false, "适中", "auto", false, "generating", "import", t.framework(), t.kind(), false, Ids.slug(), List.of(),
                null, null, null, null, null);
        apps.insert(row);
        if (t.requirements() != null && t.requirements().isObject() && !t.requirements().isEmpty()) {
            jdbc.sql("update app set source_template_id = ?, template_requirements = ?::jsonb where id = ?")
                    .params(t.id(), Json.write(t.requirements()), appId).update();
        } else {
            jdbc.sql("update app set source_template_id = ? where id = ?").params(t.id(), appId).update();
        }
        try { git.initEmpty(appId); } catch (Exception e) { apps.softDelete(appId); throw new IllegalStateException(e); }
        ObjectNode input = Json.obj();
        input.put("objectKey", t.objectKey());
        input.put("templateId", t.id());
        input.put("templateName", t.name());
        TaskRow task;
        try {
            task = taskService.create(ws, appId, "clone", null, input, idemKey, 1, null);
        } catch (RuntimeException e) {
            apps.softDelete(appId);
            git.deleteRepo(appId);
            throw e;
        }
        jdbc.sql("update template set clone_count = clone_count + 1 where id = ?").param(t.id()).update();
        return Map.of("app", views.summary(apps.findById(appId).orElseThrow()), "task", views.task(task));
    }

    private static String clip(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    private Map<String, Object> view(Tpl t, String ws) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("name", t.name());
        m.put("description", t.description());
        m.put("type", t.type());
        m.put("projectKind", t.kind());
        m.put("framework", t.framework());
        m.put("sourceVersion", t.sourceVersion());
        m.put("fileCount", t.fileCount());
        boolean mine = t.ws().equals(ws);
        m.put("mine", mine);
        if (mine && t.sourceAppId() != null) m.put("sourceAppId", t.sourceAppId());
        m.put("author", t.author());
        m.put("previewUrl", t.previewPrefix() == null ? null : previewBase + "/t/" + t.id() + "/");
        m.put("cloneCount", t.cloneCount());
        m.put("viewCount", t.viewCount());
        m.put("likeCount", t.likeCount());
        m.put("liked", t.liked());
        m.put("featured", t.featured());
        m.put("pinnedOrder", t.pinnedOrder());
        m.put("requirements", t.requirements() == null ? Json.obj() : t.requirements());
        m.put("createdAt", Db.iso(t.createdAt()));
        return m;
    }
}
