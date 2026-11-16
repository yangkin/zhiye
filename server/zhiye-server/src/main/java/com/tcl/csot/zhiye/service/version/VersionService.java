package com.tcl.csot.zhiye.service.version;

import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.repository.VersionRepository;

import com.tcl.csot.zhiye.controller.*;
import com.tcl.csot.zhiye.entity.*;
import com.tcl.csot.zhiye.repository.*;
import com.tcl.csot.zhiye.service.app.*;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.storage.ObjectStore;
import com.tcl.csot.zhiye.repository.TaskRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 版本规则：新版本只在任务成功（或恢复）时创建；恢复不改变线上版本；构建按文件树复用。 */
@Service
public class VersionService {
    private final VersionRepository versions;
    private final AppRepository apps;
    private final DraftRepository drafts;
    private final TaskRepository tasks;
    private final GitService git;
    private final ObjectStore store;
    private final Views views;
    private final JdbcClient jdbc;
    private final com.tcl.csot.zhiye.repository.MessageRepository messages;

    public VersionService(VersionRepository versions, AppRepository apps, DraftRepository drafts, TaskRepository tasks,
                          GitService git, ObjectStore store, Views views, JdbcClient jdbc,
                          com.tcl.csot.zhiye.repository.MessageRepository messages) {
        this.messages = messages;
        this.versions = versions;
        this.apps = apps;
        this.drafts = drafts;
        this.tasks = tasks;
        this.git = git;
        this.store = store;
        this.views = views;
        this.jdbc = jdbc;
    }

    /** 把工作区的两套构建产物存入对象存储；同一应用、同一文件树已有构建则复用 */
    public VersionRepository.BuildRow ensureBuild(String appId, String tree, Path previewDir, Path publishDir, boolean editable) throws Exception {
        Optional<VersionRepository.BuildRow> existing = versions.buildByTree(appId, tree);
        if (existing.isPresent()) return existing.get();
        if (!Files.isRegularFile(previewDir.resolve("index.html")) || !Files.isRegularFile(publishDir.resolve("index.html")))
            throw new IllegalStateException("构建产物缺失（没有 index.html）");
        String id = Ids.newId("b");
        String pre = "builds/" + id + "/preview", pub = "builds/" + id + "/publish";
        store.putDirectory(pre, previewDir);
        store.putDirectory(pub, publishDir);
        versions.insertBuild(appId, new VersionRepository.BuildRow(id, tree, "ready", pre, pub, editable));
        return versions.buildByTree(appId, tree).orElseThrow();
    }

    /** COMMITTING：为成功的任务创建版本（同一任务只会创建一次） */
    @Transactional
    public VersionRow createForTask(String appId, String taskId, String title, String source, String commit, String tree,
                                    String buildId, boolean clearDraft) {
        return createForTask(appId, taskId, "main", title, source, commit, tree, buildId, clearDraft);
    }

    /** stage：main = 任务的主结果；visual = 对话修改前先保存的手动修改 */
    @Transactional
    public VersionRow createForTask(String appId, String taskId, String stage, String title, String source, String commit, String tree,
                                    String buildId, boolean clearDraft) {
        apps.lock(appId);
        Optional<VersionRow> already = versions.findByTask(taskId, stage);
        if (already.isPresent()) return already.get();
        AppRow app = apps.findById(appId).orElseThrow();
        VersionRow v = versions.insert(appId, title, source, commit, tree, app.currentVersionId(), null, taskId, buildId, stage);
        jdbc.sql("update app set current_version_id = ?, phase = 'ready', updated_at = now() where id = ?").params(v.id(), appId).update();
        if (clearDraft) drafts.delete(appId);
        return v;
    }

    /**
     * 任务被用户停止时的快照：记录版本但不改变 currentVersion（半成品不自动顶替线上/预览），
     * taskId 传 null，避免和随后「继续」任务的 main 版本触发唯一约束。
     */
    @Transactional
    public VersionRow createInterrupted(String appId, String commit, String tree, String buildId) {
        apps.lock(appId);
        AppRow app = apps.findById(appId).orElseThrow();
        return versions.insert(appId, "任务中断前自动保存", "interrupted", commit, tree,
                app.currentVersionId(), null, null, buildId, "main");
    }

    @Transactional
    public void publishInternal(String appId, String versionId) {
        AppRow app = apps.findById(appId).orElseThrow();
        VersionRow v = versions.find(versionId).orElseThrow();
        jdbc.sql("update app set published_version_id = ?, updated_at = now() where id = ?").params(versionId, appId).update();
        jdbc.sql("update deployment set status = 'inactive' where app_id = ? and status = 'active'").param(appId).update();
        jdbc.sql("insert into deployment (id, app_id, version_id, build_id, status, url) values (?,?,?,?,?,?)")
                .params(Ids.newId("dep"), appId, versionId, v.buildId(), "active", views.siteUrl(app.slug())).update();
    }

    public AppRow app(String ws, String appId) {
        return apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
    }

    public VersionRow version(String ws, String id) {
        return versions.find(ws, id).orElseThrow(() -> ApiException.notFound("版本"));
    }

    public List<Map<String, Object>> list(String ws, String appId) {
        AppRow app = app(ws, appId);
        return versions.listByApp(app.id()).stream().map(v -> views.version(v, app)).toList();
    }

    public List<Map<String, Object>> history(String ws, String q, String appId, String filter) {
        StringBuilder sql = new StringBuilder("""
                select v.*, a.name as app_name, a.type as app_type from version v join app a on a.id = v.app_id
                where a.workspace_id = :ws and a.deleted_at is null and a.visible
                """);
        Map<String, Object> p = new HashMap<>();
        p.put("ws", ws);
        if (q != null && !q.isBlank()) {
            sql.append(" and (a.name ilike :q or v.title ilike :q)");
            p.put("q", "%" + q.trim().replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (appId != null && !appId.isBlank()) { sql.append(" and a.id = :appId"); p.put("appId", appId); }
        if ("favorite".equals(filter)) sql.append(" and v.favorite");
        if ("published".equals(filter)) sql.append(" and a.published_version_id = v.id");
        sql.append(" order by v.created_at desc, v.number desc");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, AppRow> appCache = new HashMap<>();
        jdbc.sql(sql.toString()).params(p).query((rs, i) -> {
            VersionRow v = VersionRow.map(rs, i);
            AppRow a = appCache.computeIfAbsent(v.appId(), id -> apps.findById(id).orElseThrow());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("app", Map.of("id", a.id(), "name", a.name(), "type", a.type()));
            row.put("version", views.version(v, a));
            return row;
        }).list().forEach(rows::add);
        return rows;
    }

    public Map<String, Object> setFavorite(String ws, String id, boolean fav) {
        VersionRow v = version(ws, id);
        versions.setFavorite(v.id(), fav);
        return views.version(versions.find(v.id()).orElseThrow(), apps.findById(v.appId()).orElseThrow());
    }

    /** 版本备注：别名标签，清空传 null；重复无妨（V 号仍是唯一标识） */
    public Map<String, Object> setNote(String ws, String id, String note) {
        VersionRow v = version(ws, id);
        String n = note == null ? null : note.trim();
        if (n != null && n.isEmpty()) n = null;
        if (n != null && n.length() > 60) n = n.substring(0, 60);
        versions.setNote(v.id(), n);
        return views.version(versions.find(v.id()).orElseThrow(), apps.findById(v.appId()).orElseThrow());
    }

    /** 恢复旧版本：用旧文件树新建提交和版本（source=restore），复用旧构建，不改线上版本 */
    @Transactional
    public Map<String, Object> restore(String ws, String id) throws Exception {
        VersionRow target = version(ws, id);
        apps.lock(target.appId());
        AppRow app = apps.findById(target.appId()).orElseThrow();
        if (tasks.activeForApp(app.id()).isPresent()) throw ApiException.busy();
        if (drafts.exists(app.id())) throw ApiException.draftPending();
        if (target.id().equals(app.currentVersionId())) throw ApiException.conflict("ALREADY_CURRENT", "已经是当前版本");
        String title = "恢复自 V" + target.number();
        GitService.CommitResult c = git.commitTree(app.id(), target.gitTree(), title);
        VersionRow v = versions.insert(app.id(), title, "restore", c.commit(), c.tree(), app.currentVersionId(), target.id(), null,
                target.buildId());
        jdbc.sql("update app set current_version_id = ?, phase = 'ready', updated_at = now() where id = ?").params(v.id(), app.id()).update();
        // 对话里留一行记录，方便回看「什么时候回到了哪个版本」
        messages.insert(app.id(), "system", "已回到 V" + target.number() + "，内容已保存为 V" + v.number(), null, null, "ok");
        return views.version(v, apps.findById(app.id()).orElseThrow());
    }

    public List<Map<String, Object>> files(String ws, String id) throws Exception {
        VersionRow v = version(ws, id);
        return git.listFiles(v.appId(), v.gitCommit()).stream()
                .map(f -> Map.<String, Object>of("path", f.path(), "size", f.size())).toList();
    }

    public byte[] file(String ws, String id, String path) throws Exception {
        VersionRow v = version(ws, id);
        return git.readFile(v.appId(), v.gitCommit(), path).orElseThrow(() -> ApiException.notFound("文件"));
    }
}
