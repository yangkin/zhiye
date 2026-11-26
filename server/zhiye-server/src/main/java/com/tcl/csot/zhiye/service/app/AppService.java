package com.tcl.csot.zhiye.service.app;

import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.DraftRepository;
import com.tcl.csot.zhiye.repository.MessageRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.repository.TaskRepository;
import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.service.task.TaskService;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.service.version.VersionService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.util.*;

@Service
public class AppService {
    private final AppRepository apps;
    private final TaskService taskService;
    private final TaskRepository tasks;
    private final MessageRepository messages;
    private final DraftRepository drafts;
    private final VersionRepository versions;
    private final VersionService versionService;
    private final GitService git;
    private final Views views;
    private final JdbcClient jdbc;
    private final com.tcl.csot.zhiye.service.workspace.QuotaService quota;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.cloud.AccessService accessService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.skillhub.SkillRefs skillRefs;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.source.DataSourceService dataSources;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.runtime.MonolithServiceManager monolithService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.workspace.WorkspaceMemoryService memories;

    public AppService(AppRepository apps, TaskService taskService, TaskRepository tasks, MessageRepository messages,
                      DraftRepository drafts, VersionRepository versions, VersionService versionService, GitService git,
                      Views views, JdbcClient jdbc, com.tcl.csot.zhiye.service.workspace.QuotaService quota) {
        this.apps = apps;
        this.taskService = taskService;
        this.tasks = tasks;
        this.messages = messages;
        this.drafts = drafts;
        this.versions = versions;
        this.versionService = versionService;
        this.git = git;
        this.views = views;
        this.jdbc = jdbc;
        this.quota = quota;
    }

    public AppRow require(String ws, String id) {
        return apps.find(ws, id).orElseThrow(() -> ApiException.notFound("应用"));
    }

    public record CreateRequest(String type, String prompt, String mode, String model, List<String> assetIds, String name,
                                List<String> skillAppIds, List<String> sourceIds) {}

    public Map<String, Object> create(String ws, CreateRequest req, String idemKey) throws Exception {
        try {
            return quota.withWorkspaceLock(ws, () -> {
                try { return createLocked(ws, req, idemKey); }
                catch (RuntimeException e) { throw e; }
                catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
            });
        } catch (java.util.concurrent.CompletionException e) {
            throw (Exception) e.getCause();
        }
    }

    private Map<String, Object> createLocked(String ws, CreateRequest req, String idemKey) throws Exception {
        Optional<TaskRow> dup = taskService.byIdem(ws, idemKey);
        if (dup.isPresent()) return views.detail(require(ws, dup.get().appId()));
        String prompt = req.prompt() == null ? "" : req.prompt().trim();
        if (prompt.isEmpty()) throw ApiException.validation("请先描述你的应用需求");
        if (prompt.length() > 5000) throw ApiException.validation("需求描述最多 5000 字");
        String type = req.type() == null || req.type().isBlank() ? Catalog.DEFAULT_TYPE : req.type();
        if (!Catalog.TYPES.contains(type)) throw ApiException.validation(Catalog.TYPE_ERROR);
        quota.checkCanCreateApp(ws);
        quota.checkCanStart(ws, false);
        String mode = req.mode() == null || !Catalog.MODES.contains(req.mode()) ? "适中" : req.mode();
        String model = req.model() == null || req.model().isBlank() ? "auto" : req.model();
        if (!Catalog.validModel(model)) throw ApiException.validation("不支持的模型：" + model);
        List<String> assetIds = validAssets(ws, req.assetIds());
        List<String> skillAppIds = skillRefs.validate(ws, req.skillAppIds());
        List<String> sourceIds = dataSources.validateIds(ws, req.sourceIds());
        boolean named = req.name() != null && !req.name().isBlank();
        String name = named ? req.name().trim() : placeholderName(prompt);
        String id = Ids.newId("app");
        // 开发skill 跳过创造计划：计划文档就是用户需求，直接进入生成
        boolean skill = Catalog.SKILL_TYPE.equals(type);
        // 明显是命令行/脚本的需求（例如「写一个 python 程序清洗 excel」）→ 脚本项目，没有页面预览
        boolean script = "日常开发".equals(type) && Catalog.looksLikeScript(prompt);
        String kind = script ? "script" : Catalog.projectKindFor(type);
        boolean cleanWorkspace = "service".equals(kind) && Catalog.prefersCleanWorkspace(prompt);
        boolean vueSpringBoot = "service".equals(kind) && !cleanWorkspace && Catalog.looksLikeVueSpringBoot(prompt);
        String desc = userText(prompt);
        AppRow row = new AppRow(id, ws, name, named, type, desc.length() > 60 ? desc.substring(0, 60) : desc, prompt,
                skill ? prompt : "", "lavender", false, mode, model, false,
                skill ? "generating" : "planning", "create", vueSpringBoot ? "Java (Spring Boot) + Vue" : null,
                kind, true, Ids.slug(), assetIds, null, null, null, null, null);
        apps.insert(row);
        if (memories != null) memories.learnFromPrompt(ws, prompt);
        if (!skillAppIds.isEmpty())
            jdbc.sql("update app set skill_app_ids = ?::jsonb where id = ?").params(Json.write(skillAppIds), id).update();
        dataSources.bindAll(id, sourceIds);
        try {
            if (cleanWorkspace) {
                git.initCleanWorkspace(id, name);
            } else {
                String tpl = row.isSkill() ? "skill-template"
                        : row.isScript() ? "script-template"
                        : row.isService() ? (vueSpringBoot ? "vue-springboot-template" : "fullstack-template") : "app-template";
                git.initFromTemplate(id, tpl);
            }
        } catch (Exception e) {
            apps.softDelete(id);
            git.deleteRepo(id);
            throw e;
        }
        try {
            taskService.create(ws, id, skill ? "generate" : "plan", null, Json.obj(), idemKey, 1, null);
        } catch (RuntimeException e) {
            apps.softDelete(id);
            git.deleteRepo(id);
            throw e;
        }
        messages.insert(id, "user", prompt, null, null, "ok");
        return views.detail(require(ws, id));
    }

    /** 从对话创建时 prompt 是「请根据以下…多轮对话…」的整理稿：名称和简介取首条用户消息，而不是这句固定开头 */
    static String userText(String prompt) {
        if (!prompt.startsWith("请根据以下按时间顺序排列的多轮对话")) return prompt;
        int i = prompt.indexOf("user（用户）：");
        if (i < 0) return prompt;
        String rest = prompt.substring(i + "user（用户）：".length()).strip();
        int end = rest.indexOf("\n\nassistant（助手）：");
        return end > 0 ? rest.substring(0, end).strip() : rest;
    }

    static String placeholderName(String prompt) {
        prompt = userText(prompt);
        String p = prompt.replaceAll("\\s+", " ").replaceFirst("^(帮我|请|我想|我要)?(做|创建|生成|设计|写)?(一个|一款|个)?", "");
        p = p.split("[，,。.：:；;！!？?]")[0].trim();
        if (p.isEmpty()) p = "我的应用";
        return p.length() > 12 ? p.substring(0, 12) : p;
    }

    public List<Map<String, Object>> list(String ws, String q, String type, String favorite, String published, String sort) {
        List<AppRow> rows = new ArrayList<>(apps.listVisible(ws));
        if (q != null && !q.isBlank()) {
            String k = q.trim().toLowerCase();
            rows.removeIf(a -> !(a.name().toLowerCase().contains(k) || a.description().toLowerCase().contains(k)));
        }
        if (type != null && !type.isBlank() && !"all".equals(type)) rows.removeIf(a -> !a.type().equals(type));
        if ("true".equals(favorite)) rows.removeIf(a -> !a.favorite());
        if ("published".equals(published)) rows.removeIf(a -> a.publishedVersionId() == null);
        if ("unpublished".equals(published)) rows.removeIf(a -> a.publishedVersionId() != null);
        Collator zh = Collator.getInstance(Locale.CHINA);
        switch (sort == null ? "updated" : sort) {
            case "created" -> rows.sort(Comparator.comparing(AppRow::createdAt).reversed());
            case "name" -> rows.sort((a, b) -> zh.compare(a.name(), b.name()));
            default -> rows.sort(Comparator.comparing(AppRow::updatedAt).reversed());
        }
        return rows.stream().map(views::summary).toList();
    }

    public Map<String, Object> detail(String ws, String id) { return views.detail(require(ws, id)); }

    /** 技能选择器用：自己的「开发skill」应用（已生成）→ {id, name, description, version, source: app} */
    public List<Map<String, Object>> listSkillsForPicker(String ws) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppRow a : apps.listVisible(ws)) {
            if (!a.isSkill() || a.currentVersionId() == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("name", a.name());
            m.put("description", a.description());
            m.put("version", versions.find(a.currentVersionId()).map(VersionRow::number).map(n -> "V" + n).orElse(null));
            m.put("source", "app");
            out.add(m);
        }
        return out;
    }

    /** 计划阶段用对话补充计划：追加到「补充反馈」，不重新生成整份计划（保留兼容旧调用） */
    public Map<String, Object> planFeedback(String ws, String id, String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) throw ApiException.validation("请输入对计划的补充");
        if (text.length() > 2000) throw ApiException.validation("补充最多 2000 字");
        AppRow app = requirePlanApp(ws, id);
        String doc = app.planDoc() == null ? "" : app.planDoc();
        String next = doc + "\n\n## 补充反馈\n- " + text.replace("\n", "\n- ") + "\n";
        apps.setPlan(app.id(), null, next, "plan");
        return detail(ws, id);
    }

    /** 校验应用存在且处于「待确认计划」阶段 */
    public AppRow requirePlanApp(String ws, String id) {
        AppRow app = apps.find(ws, id).orElseThrow(() -> ApiException.notFound("应用"));
        if (!"plan".equals(app.phase())) throw ApiException.conflict("NOT_IN_PLAN", "当前不在计划阶段，直接发送修改需求即可");
        return app;
    }

    /** 计划阶段对话：先落库用户消息并返回消息 ID */
    public String recordPlanUserMessage(String appId, String content, List<String> assetIds) {
        return messages.insert(appId, "user", content, null, null, "ok", assetIds == null || assetIds.isEmpty() ? null : assetIds).id();
    }

    /** 计划阶段对话被用户手动停止：标记用户消息为已取消并写入轻量停止记录（不污染后续历史、不覆盖计划） */
    public void recordPlanChatStopped(String appId, String userMsgId, String partialShown) {
        if (userMsgId != null && !userMsgId.isBlank()) {
            messages.setStatus(userMsgId, "cancelled");
        }
        String shown = partialShown == null ? "" : partialShown.trim();
        if (!shown.isEmpty()) {
            messages.insert(appId, "assistant", shown + "\n\n（已停止回答）", null, null, "cancelled");
        } else {
            messages.insert(appId, "system", "织页的回复被用户取消", null, null, "ok");
        }
    }

    /** 计划阶段对话完成：按需更新 planDoc 并落库助手回复；若更新了计划则返回最新应用详情 */
    public Map<String, Object> applyPlanChatResult(String ws, String appId, com.tcl.csot.zhiye.service.chat.AskService.PlanChatResult res) {
        boolean updated = res.planDoc() != null && !res.planDoc().isBlank();
        if (updated) {
            // 仅在仍处于 plan 阶段时更新，避免用户中途点「立即执行」后覆盖状态
            AppRow cur = apps.find(ws, appId).orElse(null);
            if (cur != null && "plan".equals(cur.phase())) {
                apps.setPlan(appId, null, res.planDoc(), "plan");
            } else {
                updated = false;
            }
        }
        String reply = res.reply() == null ? "" : res.reply().trim();
        if (updated) {
            String badge = "> ✓ 已同步更新上方「创造计划」，可继续讨论或点击「立即执行」。";
            int nextIdx = reply.indexOf("\n[[next]]");
            if (nextIdx >= 0) {
                reply = reply.substring(0, nextIdx).trim() + "\n\n" + badge + "\n" + reply.substring(nextIdx + 1);
            } else if (reply.startsWith("[[next]]")) {
                reply = badge + "\n" + reply;
            } else if (reply.isEmpty()) {
                reply = badge;
            } else {
                reply = reply + "\n\n" + badge;
            }
        }
        if (!reply.isEmpty()) {
            messages.insert(appId, "assistant", reply, null, null, "ok");
        }
        return updated ? detail(ws, appId) : null;
    }

    public Map<String, Object> patch(String ws, String id, JsonNode body) {
        AppRow app = require(ws, id);
        if (body.has("name")) {
            String n = body.path("name").asText("").trim();
            if (n.isEmpty() || n.length() > 40) throw ApiException.validation("应用名称需要 1～40 个字");
            jdbc.sql("update app set name = ?, name_locked = true, updated_at = now() where id = ?").params(n, id).update();
        }
        if (body.has("type")) {
            String t = body.path("type").asText();
            if (!Catalog.TYPES.contains(t)) throw ApiException.validation(Catalog.TYPE_ERROR);
            if ("create".equals(app.source()) && !Catalog.projectKindFor(t).equals(app.projectKind()))
                throw ApiException.validation("开发skill 与其他类型之间不能互相切换，请新建应用");
            apps.update(id, "type", t);
        }
        if (body.has("favorite")) jdbc.sql("update app set favorite = ? where id = ?").params(body.path("favorite").asBoolean(), id).update();
        if (body.has("planDoc")) {
            String doc = body.path("planDoc").asText("");
            if (doc.length() > 20000) throw ApiException.validation("创造计划最多 20000 字");
            apps.update(id, "plan_doc", doc);
        }
        if (body.has("style")) {
            String s = body.path("style").asText();
            if (!Catalog.STYLES.containsKey(s)) throw ApiException.validation("不支持的设计风格");
            apps.update(id, "style", s);
        }
        if (body.has("materials")) apps.update(id, "materials", body.path("materials").asBoolean());
        // 工作台里随时切换模型与强度：只影响之后的修改任务
        if (body.has("model")) {
            String m = body.path("model").asText("auto");
            if (!Catalog.validModel(m)) throw ApiException.validation("不支持的模型：" + (m.length() > 40 ? m.substring(0, 40) + "…" : m));
            apps.update(id, "model", m);
        }
        if (body.has("skillAppIds")) {
            List<String> ids = skillRefs.validate(ws, body.get("skillAppIds"));
            if (ids.contains(app.id())) throw ApiException.validation("不能引用自己");
            jdbc.sql("update app set skill_app_ids = ?::jsonb where id = ?").params(Json.write(ids), id).update();
        }
        if (body.has("mode")) {
            String m = body.path("mode").asText("");
            if (!Catalog.MODES.contains(m)) throw ApiException.validation("不支持的生成强度");
            apps.update(id, "mode", m);
        }
        return views.detail(require(ws, app.id()));
    }

    public void delete(String ws, String id) {
        AppRow app = require(ws, id);
        if (tasks.activeForApp(app.id()).isPresent())
            throw ApiException.conflict("APP_BUSY", "应用有正在进行的任务，请先停止任务再删除");
        // 软删除进入「最近删除」：线上链接立即停服下线，单体应用仅停进程、保留持久化 Schema 与 /data 卷，供管理员恢复时数据无损；永久清除（purge）时才彻底销毁
        if (app.isService() && monolithService != null) monolithService.stopForTrash(app);
        jdbc.sql("update app set published_version_id = null where id = ?").param(app.id()).update();
        jdbc.sql("update deployment set status = 'inactive' where app_id = ? and status = 'active'").param(app.id()).update();
        drafts.delete(app.id());
        apps.softDelete(app.id());
    }

    // ---------- 任务入口 ----------
    public Map<String, Object> generate(String ws, String id, String idemKey) {
        Optional<TaskRow> dup = taskService.byIdem(ws, idemKey);
        if (dup.isPresent()) return views.task(dup.get());
        AppRow app = require(ws, id);
        if (tasks.activeForApp(app.id()).isPresent()) throw ApiException.busy();
        if (!"plan".equals(app.phase()) && !"failed".equals(app.phase()) && !"stopped".equals(app.phase()))
            throw ApiException.conflict("INVALID_PHASE", "当前阶段不能执行生成");
        TaskRow t = taskService.create(ws, app.id(), "generate", app.currentVersionId(), Json.obj(), idemKey, 1, null);
        apps.setPhase(app.id(), "generating");
        return views.task(t);
    }

    public Map<String, Object> postMessage(String ws, String id, JsonNode body, String idemKey) throws Exception {
        Optional<TaskRow> dup = taskService.byIdem(ws, idemKey);
        if (dup.isPresent()) {
            var msg = messages.findUserByTask(dup.get().id());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", msg.map(views::message).orElse(null));
            r.put("task", views.task(dup.get()));
            return r;
        }
        AppRow app = require(ws, id);
        String content = body.path("content").asText("").trim();
        if (content.isEmpty()) throw ApiException.validation("请输入要修改的内容");
        if (content.length() > 5000) throw ApiException.validation("内容最多 5000 字");
        JsonNode ctx = body.get("elementContext");
        if (ctx != null && ctx.isNull()) ctx = null;
        if (ctx != null) {
            if (!app.id().equals(ctx.path("appId").asText())) throw ApiException.validation("选中的元素不属于当前应用，请重新选择");
            String loc = ctx.path("loc").asText("");
            if (!loc.matches("^[^:]+:\\d+:\\d+$")) throw ApiException.validation("元素位置格式不正确");
        }
        if (tasks.activeForApp(app.id()).isPresent()) throw ApiException.busy();
        if (app.currentVersionId() == null) throw ApiException.conflict("INVALID_PHASE", "应用还没有生成完成，暂时不能修改");
        VersionRow base = versions.find(app.currentVersionId()).orElseThrow();
        if (ctx != null) {
            String file = ctx.path("loc").asText().replaceFirst(":\\d+:\\d+$", "");
            if (!git.fileExists(app.id(), base.gitCommit(), file)) throw ApiException.validation("选中元素所在的文件在当前版本中不存在，请刷新后重新选择");
        }
        ObjectNode input = Json.obj();
        input.put("content", content);
        if (ctx != null) input.set("elementContext", ctx);
        drafts.find(app.id()).ifPresent(d -> input.set("draftPatches", d.patches()));
        List<String> msgAssets = validAssets(ws, body.has("assetIds") && !body.get("assetIds").isNull()
                ? Json.M.convertValue(body.get("assetIds"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}) : null);
        List<String> msgSkills = skillRefs.validate(ws, body.get("skillAppIds"));
        if (body.hasNonNull("model")) {
            String model = body.path("model").asText();
            if (!Catalog.validModel(model)) throw ApiException.validation("不支持的模型：" + model);
            input.put("model", model);
        }
        if (!msgAssets.isEmpty()) input.set("assetIds", Json.M.valueToTree(msgAssets));
        if (!msgSkills.isEmpty()) input.set("skillAppIds", Json.M.valueToTree(msgSkills));
        // 上一轮被停止：没有手动修改草稿和选中元素时，沿用上一轮的工作区先补完再做这句
        ObjectNode cont = ctx == null && !input.has("draftPatches") ? taskService.continuation(app) : null;
        if (cont != null) input.setAll(cont);
        TaskRow t = taskService.create(ws, app.id(), "modify", base.id(), input, idemKey, 1, null);
        if (cont != null && t.input().has("continueFromTask")) taskService.consumeCheckpoint(cont.path("continueFromTask").asText());
        var msg = messages.insert(app.id(), "user", content, ctx, t.id(), "pending", msgAssets.isEmpty() ? null : msgAssets,
                msgSkills.isEmpty() ? null : msgSkills);
        if (memories != null) memories.learnFromPrompt(ws, content);
        apps.touch(app.id());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("message", views.message(msg));
        r.put("task", views.task(t));
        return r;
    }

    /** 素材必须属于当前工作空间 */
    List<String> validAssets(String ws, List<String> ids) {
        List<String> out = new ArrayList<>();
        if (ids == null) return out;
        if (ids.size() > 20) throw ApiException.validation("一次最多附带 20 个素材");
        for (String a : ids) {
            boolean ok = a != null && jdbc.sql("select count(*) from asset where id = ? and workspace_id = ?").params(a, ws).query(Long.class).single() > 0;
            if (!ok) throw ApiException.validation("素材不存在或不属于当前工作空间");
            if (!out.contains(a)) out.add(a);
        }
        return out;
    }

    public List<Map<String, Object>> messages(String ws, String id) {
        AppRow app = require(ws, id);
        return messages.list(app.id()).stream().map(views::message).toList();
    }

    public List<Map<String, Object>> tasks(String ws, String id, boolean active) {
        AppRow app = require(ws, id);
        return tasks.listByApp(app.id(), active).stream().map(views::task).toList();
    }

    // ---------- 草稿 ----------
    public Map<String, Object> draft(String ws, String id) {
        AppRow app = require(ws, id);
        return drafts.find(app.id()).map(views::draft).orElse(null);
    }

    public Map<String, Object> putDraft(String ws, String id, JsonNode body) {
        AppRow app = require(ws, id);
        // 修改一律串行：AI 任务进行中不接受手动修改，避免补丁落在过期的位置上或被悄悄丢掉
        if (tasks.activeForApp(app.id()).isPresent()) throw ApiException.busy();
        String base = body.path("baseVersionId").asText(null);
        if (base == null || !base.equals(app.currentVersionId()))
            throw ApiException.conflict("VERSION_MISMATCH", "编辑的基线不是当前版本，请刷新后重新编辑");
        if (app.isSkill()) throw skillNoVisual();
        if (!app.visualEditable()) throw ApiException.validation("这个应用不支持可视化编辑");
        JsonNode patches = body.path("patches");
        if (!patches.isArray()) throw ApiException.validation("patches 必须是数组");
        if (patches.size() > 500) throw ApiException.validation("修改太多，请先保存一次");
        for (JsonNode p : patches) {
            if (!p.path("loc").asText("").matches("^[^:]+:\\d+:\\d+$")) throw ApiException.validation("修改中的元素位置格式不正确");
            // 列表项（.map() 渲染）的单项修改：index = 同一 loc 的第几个元素，match = 该项的文字，用于 runner 定位
            if (p.has("index") && !p.get("index").isNull()
                    && (!p.get("index").isIntegralNumber() || p.get("index").asLong() < 0 || p.get("index").asLong() > 100000))
                throw ApiException.validation("修改中的 index 必须是不小于 0 的整数");
            if (p.has("match") && !p.get("match").isNull()
                    && (!p.get("match").isTextual() || p.get("match").asText().length() > 200))
                throw ApiException.validation("修改中的 match 必须是不超过 200 字的文字");
            if (p.has("href") && !p.path("href").asText().matches("(?i)^(https?://|mailto:|tel:|#|/).*"))
                throw ApiException.validation("请输入网址或页面锚点");
            JsonNode st = p.path("styles");
            if (st.isObject()) st.fields().forEachRemaining(e -> {
                String v = e.getValue().asText();
                if (v.length() > 120 || v.matches("(?i).*(url\\(|expression|javascript:).*")) throw ApiException.validation("样式值不合法");
            });
        }
        drafts.upsert(app.id(), base, patches);
        apps.touch(app.id());
        return views.draft(drafts.find(app.id()).orElseThrow());
    }

    public void deleteDraft(String ws, String id) {
        AppRow app = require(ws, id);
        if (tasks.activeForApp(app.id()).isPresent()) throw ApiException.busy();
        drafts.delete(app.id());
    }

    public Map<String, Object> commitDraft(String ws, String id, JsonNode body, String idemKey) {
        Optional<TaskRow> dup = taskService.byIdem(ws, idemKey);
        if (dup.isPresent()) return views.task(dup.get());
        AppRow app = require(ws, id);
        if (app.isSkill()) throw skillNoVisual();
        if (tasks.activeForApp(app.id()).isPresent()) throw ApiException.busy();
        var d = drafts.find(app.id()).orElseThrow(() -> ApiException.validation("没有需要保存的可视化编辑"));
        if (!d.baseVersionId().equals(app.currentVersionId()))
            throw ApiException.conflict("VERSION_MISMATCH", "草稿的基线不是当前版本，请放弃后重新编辑");
        if (!d.patches().isArray() || d.patches().isEmpty()) {
            drafts.delete(app.id());
            throw ApiException.validation("没有需要保存的可视化编辑");
        }
        ObjectNode input = Json.obj();
        input.set("patches", d.patches());
        String title = body == null ? null : body.path("title").asText(null);
        if (title != null && !title.isBlank()) input.put("title", title.length() > 40 ? title.substring(0, 40) : title);
        TaskRow t = taskService.create(ws, app.id(), "visual_save", d.baseVersionId(), input, idemKey, 1, null);
        return views.task(t);
    }

    // ---------- 代码编辑 ----------
    public Map<String, Object> saveCode(String ws, String id, JsonNode body, String idemKey) {
        Optional<TaskRow> dup = taskService.byIdem(ws, idemKey);
        if (dup.isPresent()) return views.task(dup.get());
        AppRow app = require(ws, id);
        JsonNode files = body == null ? null : body.get("files");
        if (files == null || !files.isArray() || files.isEmpty()) throw ApiException.validation("没有要保存的文件");
        if (files.size() > 50) throw ApiException.validation("一次最多保存 50 个文件");
        Set<String> seen = new HashSet<>();
        com.fasterxml.jackson.databind.node.ArrayNode clean = Json.M.createArrayNode();
        for (JsonNode f : files) {
            String path = validCodePath(f.path("path").asText(""));
            if (!seen.add(path)) throw ApiException.validation("文件「" + path + "」重复出现");
            boolean del = f.path("delete").asBoolean(false);
            ObjectNode n = clean.addObject().put("path", path);
            if (del) { n.put("delete", true); continue; }
            JsonNode content = f.get("content");
            if (content == null || !content.isTextual()) throw ApiException.validation("文件「" + path + "」缺少内容");
            String text = content.asText();
            if (text.indexOf('\0') >= 0) throw ApiException.validation("文件「" + path + "」不是文本文件");
            if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > (1 << 20)) throw ApiException.validation("文件「" + path + "」超过 1 MB");
            n.put("content", text);
        }
        if (tasks.activeForApp(app.id()).isPresent()) throw ApiException.busy();
        String base = body.path("baseVersionId").asText("");
        if (app.currentVersionId() == null || !app.currentVersionId().equals(base))
            throw ApiException.conflict("VERSION_MISMATCH", "代码的基线不是当前版本，请刷新后重新编辑");
        if (drafts.exists(app.id())) throw ApiException.draftPending();
        ObjectNode input = Json.obj();
        input.set("files", clean);
        TaskRow t = taskService.create(ws, app.id(), "code_save", base, input, idemKey, 1, null);
        return views.task(t);
    }

    public static String validCodePath(String raw) {
        String p = raw.trim();
        if (p.isEmpty()) throw ApiException.validation("文件路径不能为空");
        if (p.contains("\\")) throw ApiException.validation("文件路径不能包含反斜杠：" + p);
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) throw ApiException.validation("只能使用相对路径：" + p);
        if (p.length() > 300 || p.indexOf('\0') >= 0) throw ApiException.validation("文件路径不合法：" + p);
        String[] parts = p.split("/");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw ApiException.validation("文件路径不合法：" + p);
        }
        if (Set.of(".git", "node_modules", ".zhiye").contains(parts[0])) throw ApiException.validation("不能修改 " + parts[0] + "/ 下的文件");
        return p;
    }

    // ---------- 发布 ----------
    public Map<String, Object> publish(String ws, String id, String versionId) {
        AppRow app = require(ws, id);
        if (app.isSkill()) throw skillNotPublishable();
        if (app.isScript()) throw scriptNotPublishable();
        if (tasks.activeForApp(app.id()).isPresent()) throw ApiException.busy();
        if (drafts.exists(app.id())) throw ApiException.draftPending();
        String vid = versionId == null || versionId.isBlank() ? app.currentVersionId() : versionId;
        if (vid == null) throw ApiException.conflict("INVALID_PHASE", "请先完成生成再发布");
        VersionRow v = versions.find(vid).filter(x -> x.appId().equals(app.id())).orElseThrow(() -> ApiException.notFound("版本"));
        if (app.isService()) {
            if (monolithService == null) throw ApiException.conflict("SERVICE_DISABLED", "当前环境未启用单体后端托管服务");
            try {
                monolithService.publishDeploy(app, v);
            } catch (Exception e) {
                throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "DEPLOY_FAILED", "单体服务部署失败：" + e.getMessage());
            }
            versionService.publishInternal(app.id(), v.id());
            return Map.of("siteUrl", views.siteUrl(app.slug()), "versionId", v.id());
        }
        if (versions.build(v.buildId()).isEmpty()) throw ApiException.conflict("BUILD_MISSING", "这个版本没有可用的构建产物");
        // 20.4.3：表的权限需要知道「是谁」，但应用是内网公开（谁都能打开、没人登录）——发布出去就会全部报错，先拦下
        var acc = accessService.get(app.id());
        List<String> needLogin = acc.needsLogin() ? List.of() : accessService.tablesNeedingLogin(app.id());
        if (!needLogin.isEmpty())
            throw ApiException.conflict("ACCESS_MISMATCH", "数据表 " + String.join("、", needLogin)
                    + " 的权限需要知道是谁在用，但应用现在是「内网公开」。请在发布设置里改成「员工登录」，或在数据控制台把这些表改成「所有人可看可改」/「免登录可提交」");
        versionService.publishInternal(app.id(), v.id());
        return Map.of("siteUrl", views.siteUrl(app.slug()), "versionId", v.id());
    }

    public static ApiException skillNotPublishable() {
        return new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "SKILL_NOT_PUBLISHABLE", "技能不需要发布，可以下载技能包使用");
    }

    /** 脚本项目没有页面，不能发布成网站 */
    public static ApiException scriptNotPublishable() {
        return new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "SCRIPT_NOT_PUBLISHABLE", "脚本项目没有页面，不能发布成网站，可以用「导出源码」下载");
    }

    static ApiException skillNoVisual() {
        return new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "SKILL_NOT_EDITABLE", "技能包没有页面，不支持可视化编辑，请在对话中描述修改");
    }

    public void unpublish(String ws, String id) {
        AppRow app = require(ws, id);
        if (app.isService() && monolithService != null) {
            try { monolithService.stop(ws, id); } catch (Exception ignored) {}
        }
        jdbc.sql("update app set published_version_id = null, updated_at = now() where id = ?").param(app.id()).update();
        jdbc.sql("update deployment set status = 'inactive' where app_id = ? and status = 'active'").param(app.id()).update();
    }

    public List<Map<String, Object>> deployments(String ws, String id) {
        AppRow app = require(ws, id);
        return jdbc.sql("select * from deployment where app_id = ? order by created_at desc").param(app.id())
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("versionId", rs.getString("version_id"));
                    m.put("status", rs.getString("status"));
                    m.put("url", rs.getString("url"));
                    m.put("createdAt", com.tcl.csot.zhiye.util.Db.iso(com.tcl.csot.zhiye.util.Db.ts(rs, "created_at")));
                    return m;
                }).list();
    }
}
