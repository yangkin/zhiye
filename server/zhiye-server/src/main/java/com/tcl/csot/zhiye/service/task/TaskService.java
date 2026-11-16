package com.tcl.csot.zhiye.service.task;

import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.repository.TaskRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.controller.*;
import com.tcl.csot.zhiye.entity.*;
import com.tcl.csot.zhiye.repository.*;
import com.tcl.csot.zhiye.service.app.*;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.llm.TaskTokenService;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.service.workspace.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 任务的创建、停止、继续、重试，以及结束时的副作用。 */
@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository tasks;
    private final TaskEvents events;
    private final TaskSignals signals;
    private final AppRepository apps;
    private final MessageRepository messages;
    private final QuotaService quota;
    private final TaskTokenService tokens;
    private final GitService git;
    private final ZhiyeProperties props;
    private final TaskQueueService queued;

    public TaskService(TaskRepository tasks, TaskEvents events, TaskSignals signals, AppRepository apps,
                       MessageRepository messages, QuotaService quota, TaskTokenService tokens, GitService git,
                       ZhiyeProperties props, TaskQueueService queued) {
        this.tasks = tasks;
        this.events = events;
        this.signals = signals;
        this.apps = apps;
        this.messages = messages;
        this.quota = quota;
        this.tokens = tokens;
        this.git = git;
        this.props = props;
        this.queued = queued;
    }

    /** 已有同一幂等键的任务 */
    public Optional<TaskRow> byIdem(String ws, String key) { return tasks.findByIdem(ws, blankToNull(key)); }

    public static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** 创建任务（排队）。同一应用已有进行中任务 → APP_BUSY；同一幂等键 → 返回已有任务。 */
    public TaskRow create(String ws, String appId, String type, String baseVersionId, JsonNode input, String idemKey,
                          int attempt, String parentTaskId) {
        return quota.withWorkspaceLock(ws, () -> createLocked(ws, appId, type, baseVersionId, input, idemKey, attempt, parentTaskId));
    }

    private TaskRow createLocked(String ws, String appId, String type, String baseVersionId, JsonNode input, String idemKey,
                                 int attempt, String parentTaskId) {
        String key = blankToNull(idemKey);
        Optional<TaskRow> existing = tasks.findByIdem(ws, key);
        if (existing.isPresent()) return existing.get();
        if (tasks.activeForApp(appId).isPresent()) throw ApiException.busy();
        quota.checkCanStart(ws, !"plan".equals(type));
        String id = Ids.newId("task");
        try {
            tasks.insert(id, ws, appId, type, baseVersionId, input == null ? Json.obj() : input, key, attempt, parentTaskId);
        } catch (DuplicateKeyException e) {
            Optional<TaskRow> again = tasks.findByIdem(ws, key);
            if (again.isPresent()) return again.get();
            throw ApiException.busy();
        }
        events.status(id, "QUEUED", "排队中", 0);
        signals.wakeWorker();
        return tasks.findById(id).orElseThrow();
    }

    public Optional<TaskRow> activeInWorkspace(String ws) { return tasks.activeInWorkspace(ws); }

    public Optional<TaskRow> findById(String id) { return tasks.findById(id); }

    public TaskRow get(String ws, String id) {
        return tasks.find(ws, id).orElseThrow(() -> ApiException.notFound("任务"));
    }

    // ---------- 停止 ----------
    public TaskRow cancel(String ws, String id) {
        TaskRow t = get(ws, id);
        if (t.terminal() || "CANCELLING".equals(t.status()) || "COMMITTING".equals(t.status())) return t;
        if ("QUEUED".equals(t.status())) {
            if (tasks.cancelQueued(id)) {
                afterTerminal(t, "CANCELLED", null, null);
                events.emit(id, "status", Map.of("status", "CANCELLED", "phaseLabel", "已停止", "progress", t.progress()));
                events.emit(id, "finished", Map.of("status", "CANCELLED"));
            }
            return tasks.findById(id).orElseThrow();
        }
        if (tasks.requestCancel(id)) {
            events.status(id, "CANCELLING", "正在停止", t.progress());
            signals.signalCancel(id);
        }
        return tasks.findById(id).orElseThrow();
    }

    // ---------- 继续 ----------
    public TaskRow resume(String ws, String id, String idemKey) {
        Optional<TaskRow> dup = byIdem(ws, idemKey);
        if (dup.isPresent()) return dup.get();
        TaskRow old = get(ws, id);
        var cp = tasks.checkpoint(id);
        if (!"CANCELLED".equals(old.status()) || cp.isEmpty())
            throw ApiException.conflict("NOT_RESUMABLE", "这个任务没有可以继续的进度");
        AppRow app = apps.find(ws, old.appId()).orElseThrow(() -> ApiException.notFound("应用"));
        ObjectNode input = old.input().deepCopy();
        input.set("resume", checkpointJson(cp.get(), false));
        // 手动修改已经保存成版本时，从那个版本继续（不再写回草稿）
        String base = input.hasNonNull("manualVersionId") ? input.path("manualVersionId").asText() : old.baseVersionId();
        TaskRow t = create(ws, app.id(), old.type(), base, input, idemKey, old.attempt(), old.id());
        tasks.deleteCheckpoint(old.id());
        onRestart(app, old, t);
        return t;
    }

    // ---------- 重试 ----------
    public TaskRow retry(String ws, String id, String idemKey) {
        Optional<TaskRow> dup = byIdem(ws, idemKey);
        if (dup.isPresent()) return dup.get();
        TaskRow old = get(ws, id);
        if (!"FAILED".equals(old.status()) || "import".equals(old.type()) || "clone".equals(old.type()))
            throw ApiException.conflict("NOT_RETRYABLE", "这个任务不能重试");
        AppRow app = apps.find(ws, old.appId()).orElseThrow(() -> ApiException.notFound("应用"));
        ObjectNode input = old.input().deepCopy();
        input.remove("resume");
        var cp = tasks.checkpoint(id);
        // 有检查点时从检查点继续（重新发送原始指令），否则从基线版本重新开始
        String base = old.baseVersionId();
        if (cp.isPresent() && ("generate".equals(old.type()) || "modify".equals(old.type()))) {
            ObjectNode resume = checkpointJson(cp.get(), true);
            // 上一轮是做到上限才停的：重试要接着做，不要把原指令当新任务从头做
            if (java.util.Set.of("TOO_LARGE", "TIMEOUT", "PAUSED", "BUDGET_EXCEEDED", "PROVIDER_QUOTA").contains(old.errorCode())) resume.put("continueWork", true);
            input.set("resume", resume);
        } else {
            base = input.hasNonNull("manualVersionId") ? input.path("manualVersionId").asText() : app.currentVersionId();
        }
        TaskRow t = create(ws, app.id(), old.type(), base, input, idemKey, old.attempt() + 1, old.id());
        if (cp.isPresent()) tasks.deleteCheckpoint(old.id());
        // 重试已经开始：上一轮的「生成失败」提示留着会挂在进行中的卡片下面，像是这次也失败了；这次再失败会重新写一条
        messages.deleteFailures(app.id());
        onRestart(app, old, t);
        return t;
    }

    /**
     * 上一轮生成/修改被用户停止、留有检查点，且之后没有换过版本：下一句修改沿用那个工作区和 agent 会话，
     * 先补完上一轮再做新要求（对齐 meoo「上一轮的 X 还没落地，我先把它补完」）。返回要并进新任务 input 的字段，没有就返回 null。
     */
    public ObjectNode continuation(AppRow app) {
        TaskRow last = tasks.latestForApp(app.id()).orElse(null);
        if (last == null || !"CANCELLED".equals(last.status())) return null;
        if (!"modify".equals(last.type()) && !"generate".equals(last.type())) return null;
        if (last.input().hasNonNull("manualVersionId")) return null;
        String base = last.baseVersionId();
        if (base == null ? app.currentVersionId() != null && "modify".equals(last.type()) : !base.equals(app.currentVersionId())) return null;
        var cp = tasks.checkpoint(last.id());
        if (cp.isEmpty() || !java.nio.file.Files.isDirectory(java.nio.file.Path.of(cp.get().workspaceDir()))) return null;
        ObjectNode n = Json.obj();
        n.set("resume", checkpointJson(cp.get(), false));
        n.put("continueFromTask", last.id());
        n.put("continueContent", "modify".equals(last.type()) ? last.input().path("content").asText("") : "按创造计划完成应用的首次生成");
        return n;
    }

    /** 新任务已经接手了上一轮的检查点：上一轮不能再点「继续」 */
    public void consumeCheckpoint(String taskId) { tasks.deleteCheckpoint(taskId); }

    private ObjectNode checkpointJson(TaskRepository.Checkpoint c, boolean retry) {
        ObjectNode n = Json.obj();
        n.put("workspaceDir", c.workspaceDir());
        n.put("agentDir", c.agentDir());
        n.put("sessionFile", c.sessionFile());
        n.put("draftApplied", c.draftApplied());
        n.put("retry", retry);
        return n;
    }

    private void onRestart(AppRow app, TaskRow old, TaskRow t) {
        switch (old.type()) {
            case "plan" -> apps.setPhase(app.id(), "planning");
            case "generate" -> apps.setPhase(app.id(), "generating");
            case "modify" -> messages.retarget(old.id(), t.id());
            default -> { }
        }
    }

    // ---------- 结束时的副作用（执行器和 API 共用） ----------
    public void afterTerminal(TaskRow t, String status, String errorCode, String errorMessage) {
        tokens.revoke(t.id());
        try {
            TaskRow finished = tasks.findById(t.id()).orElse(t);
            boolean autoSaved = finished.resultVersionId() != null;
            // 重试成功之后，之前那些「生成失败」的系统提示已经没有意义，清掉避免误导
            if ("SUCCEEDED".equals(status)) {
                messages.deleteFailures(t.appId());
                return;
            }
            Optional<AppRow> appOpt = apps.findById(t.appId());
            if (appOpt.isEmpty()) return;
            AppRow app = appOpt.get();
            boolean cancelled = "CANCELLED".equals(status);
            switch (t.type()) {
                case "plan" -> {
                    String doc = app.planDoc() == null || app.planDoc().isBlank() ? fallbackPlan(app) : app.planDoc();
                    apps.setPlan(app.id(), null, doc, "plan");
                    if (!cancelled) messages.insert(app.id(), "system", "创造计划生成失败：" + errorMessage + "。已为你准备了一份默认计划，可以编辑后执行，或重试。", null, t.id(), "ok");
                }
                case "generate" -> {
                    // 主动停止且还没有任何版本：记为 stopped，和真正的失败分开显示
                    apps.setPhase(app.id(), app.currentVersionId() != null ? "ready" : cancelled ? "stopped" : "failed");
                    messages.insert(app.id(), "system", cancelled
                            ? autoSaved ? "织页的回复被用户取消。中断前的改动已自动保存，可以点「继续」接着做。" : "织页的回复被用户取消。进度已保存，可以点「继续」接着做。"
                            : "生成失败：" + errorMessage, null, t.id(), "ok");
                }
                case "modify" -> {
                    messages.setStatusByTask(t.id(), "failed");
                    String text = errorMessage != null && errorMessage.startsWith("你的手动修改") ? errorMessage
                            : cancelled ? autoSaved ? "织页的回复被用户取消。中断前的改动已自动保存；直接说下一句，我会先把这一轮补完。" : "织页的回复被用户取消。没有生成新版本。"
                            : "修改失败：" + errorMessage + "。没有生成新版本，你的输入已保留。";
                    messages.insert(app.id(), "system", text, null, t.id(), "ok");
                }
                case "visual_save" -> messages.insert(app.id(), "system",
                        cancelled ? "已停止保存，可视化编辑仍保留在草稿中。" : "保存可视化编辑失败：" + errorMessage + "。修改仍保留在草稿中。", null, t.id(), "ok");
                case "code_save" -> messages.insert(app.id(), "system",
                        cancelled ? "已停止保存代码，没有生成新版本。" : "保存代码失败：" + errorMessage, null, t.id(), "ok");
                case "import", "clone" -> {
                    // 取消或失败的导入/复刻不留下应用
                    apps.softDelete(app.id());
                    git.deleteRepo(app.id());
                }
                default -> { }
            }
        } finally {
            drainQueued(t);
        }
    }

    /** 队列续跑绝不在当前收尾调用栈里递归执行新任务；创建失败只记录，pending 由队列服务保留。 */
    private void drainQueued(TaskRow t) {
        if (!"generate".equals(t.type()) && !"modify".equals(t.type())) return;
        try {
            queued.drainNext(tasks.findById(t.id()).orElse(t), this);
        } catch (Exception e) {
            log.warn("任务 {} 结束后自动续跑排队消息失败，消息仍保留：{}", t.id(), e.getMessage());
        }
    }

    public static String fallbackPlan(AppRow app) {
        if (app.isSkill())
            return "# " + app.name() + "\n\n## 技能目标\n" + app.prompt() + "\n\n## 文件结构\n- SKILL.md：说明与使用步骤\n- references/：规范、模板、示例\n- scripts/：辅助脚本\n\n## 验收标准\n- SKILL.md 头部含 name 与 description\n- 引用的文件都存在\n";
        return "# " + app.name() + "\n\n## 应用目标\n" + app.prompt() + "\n\n## 页面与功能\n- 根据需求实现主要页面与交互\n- 所有按钮有明确反馈\n\n## 业务规则\n无\n\n## 异常与边界情况\n- 空数据时显示空状态\n- 输入为空或超长时给出提示\n\n## 验收标准\n- 不同宽度下布局正常\n- 表单包含空值检查\n\n## 本期不实现\n- 需求之外的功能先不做\n";
    }

    public Map<String, Object> finishedPayload(String status, String versionId, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        if (versionId != null) m.put("versionId", versionId);
        if (code != null) m.put("error", Map.of("code", code, "message", message == null ? "" : message));
        return m;
    }

    public Path logFile(String taskId) { return props.runtimePath().resolve("logs/tasks/" + taskId + ".log"); }
}
