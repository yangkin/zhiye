package com.tcl.csot.zhiye.service.task;

import com.tcl.csot.zhiye.util.Threads;

import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.repository.TaskRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.controller.*;
import com.tcl.csot.zhiye.entity.*;
import com.tcl.csot.zhiye.repository.*;
import com.tcl.csot.zhiye.service.app.*;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.ServerPort;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.dev.FaultState;
import com.tcl.csot.zhiye.client.KimiClient;
import com.tcl.csot.zhiye.exception.LlmException;
import com.tcl.csot.zhiye.service.llm.TaskTokenService;
import com.tcl.csot.zhiye.service.llm.UsageService;
import com.tcl.csot.zhiye.service.llm.QuotaText;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.sandbox.RunnerException;
import com.tcl.csot.zhiye.sandbox.SandboxProvider;
import com.tcl.csot.zhiye.storage.ObjectStore;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.service.version.VersionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 任务编排（Java 是唯一的裁判）：准备工作区 → runner → 校验/修复循环 → 提交版本。
 * 版本只在 COMMITTING 阶段、校验通过后创建；失败和停止都不创建版本。
 */
@Component
public class TaskExecutor {
    private static final Logger log = LoggerFactory.getLogger(TaskExecutor.class);

    private final TaskRepository tasks;
    private final TaskEvents events;
    private final TaskSignals signals;
    private final TaskService taskService;
    private final AppRepository apps;
    private final MessageRepository messages;
    private final DraftRepository drafts;
    private final VersionRepository versions;
    private final VersionService versionService;
    private final GitService git;
    private final SandboxProvider sandbox;
    private final TaskTokenService tokens;
    private final KimiClient kimi;
    private final UsageService usage;
    private final FaultState faults;
    private final ObjectStore store;
    private final ZhiyeProperties props;
    private final ServerPort port;
    private final JdbcClient jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.skillhub.SkillRefs skillRefs;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.source.DataSourceService dataSources;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.chat.AskAttachments attachments;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.llm.LlmRouter router;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.common.RuntimeMetricsService metrics;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.ops.PlatformLogService platformLogs;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.ops.ClusterSandboxService clusterSandbox;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.runtime.MonolithServiceManager monolithService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.workspace.WorkspaceMemoryService memories;

    public TaskExecutor(TaskRepository tasks, TaskEvents events, TaskSignals signals, TaskService taskService,
                        AppRepository apps, MessageRepository messages, DraftRepository drafts, VersionRepository versions,
                        VersionService versionService, GitService git, SandboxProvider sandbox, TaskTokenService tokens,
                        KimiClient kimi, UsageService usage, FaultState faults, ObjectStore store, ZhiyeProperties props,
                        ServerPort port, JdbcClient jdbc) {
        this.tasks = tasks;
        this.events = events;
        this.signals = signals;
        this.taskService = taskService;
        this.apps = apps;
        this.messages = messages;
        this.drafts = drafts;
        this.versions = versions;
        this.versionService = versionService;
        this.git = git;
        this.sandbox = sandbox;
        this.tokens = tokens;
        this.kimi = kimi;
        this.usage = usage;
        this.faults = faults;
        this.store = store;
        this.props = props;
        this.port = port;
        this.jdbc = jdbc;
    }

    // ================= 入口 =================
    public void execute(TaskRow task) {
        TaskContext ctx = new TaskContext(task, Instant.now().plus(Duration.ofMinutes(props.task().maxDurationMinutes())));
        signals.onCancel(task.id(), () -> requestCancel(ctx));
        signals.onSteer(task.id(), text -> steer(ctx, text));
        signals.onConfirm(task.id(), (id, accept, text) -> answerConfirm(ctx, id, accept, text));
        MDCish.set(task.id());
        try {
            events.status(task.id(), "PREPARING", "正在准备工作区", 5);
            if ("CANCELLING".equals(tasks.status(task.id()))) ctx.cancelRequested = true;
            ctx.checkCancelled();
            switch (task.type()) {
                case "plan" -> runPlan(ctx);
                case "generate", "modify" -> runAgent(ctx);
                case "visual_save" -> runVisualSave(ctx);
                case "code_save" -> runCodeSave(ctx);
                case "import" -> runImport(ctx);
                case "clone" -> runClone(ctx);
                default -> throw new TaskContext.Fail("INTERNAL", "未知的任务类型");
            }
        } catch (TaskContext.Cancelled c) {
            onCancelled(ctx);
        } catch (TaskContext.Fail f) {
            onFailed(ctx, f.code, f.getMessage());
        } catch (Throwable e) {
            log.error("任务 {} 执行出错", task.id(), e);
            if (ctx.cancelRequested) onCancelled(ctx);
            else onFailed(ctx, "INTERNAL", "执行出错：" + Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        } finally {
            flushText(ctx, true);
            endThinking(ctx, null);
            if (ctx.runner != null) ctx.runner.close();
            if (clusterSandbox != null && ctx.sandboxId != null) {
                clusterSandbox.markClosed(ctx.sandboxId, task, ctx.cancelRequested ? "cancelled" : "task_finished");
            }
            tokens.revoke(task.id());
            signals.remove(task.id());
            MDCish.clear();
        }
    }

    /** 对话中途补充：只在 agent 正在执行 prompt 时转给 runner；正在等「继续」或「方案确认」时，补充的话直接作为用户的回复 */
    private boolean steer(TaskContext ctx, String text) {
        String waiting = ctx.pendingConfirmId;
        if (waiting != null && ("continue".equals(ctx.pendingConfirmKind) || "choice".equals(ctx.pendingConfirmKind))) {
            return answerConfirm(ctx, waiting, true, text);
        }
        var r = ctx.runner;
        if (r == null || ctx.cancelRequested || !"prompt".equals(ctx.currentCommand)) return false;
        r.sendAsync("steer", Json.obj().put("text", text));
        return true;
    }

    static final List<String> CLOUD_CONFIRM_ITEMS = List.of("PostgreSQL 数据库（这个应用独立的一份表）", "文件存储（图片、附件、导出文件）", "预览页自动注入 SDK，不需要自己部署服务端");

    /** runner 的 request_confirm：cloud 已启用就直接放行；cloud/choice 发确认卡等用户选 */
    private void onConfirmEvent(TaskContext ctx, JsonNode ev) {
        String id = ev.path("id").asText(), kind = ev.path("kind").asText(), state = ev.path("state").asText();
        ObjectNode p = Json.obj().put("id", id).put("kind", kind).put("state", state).put("reason", ev.path("reason").asText(""));
        if (ev.has("title")) p.put("title", ev.path("title").asText(""));
        if (!"pending".equals(state)) {
            if (id.equals(ctx.pendingConfirmId)) { ctx.pendingConfirmId = null; ctx.pendingConfirmKind = null; }
            events.emit(ctx.id(), "confirm", p);
            return;
        }
        var r = ctx.runner;
        if (r == null) return;
        if ("choice".equals(kind)) {
            ctx.pendingConfirmId = id;
            ctx.pendingConfirmKind = kind;
            if (!p.has("title") || p.path("title").asText("").isBlank()) p.put("title", "需要你确认一个方案细节");
            if (ev.has("options") && ev.get("options").isArray()) p.set("options", ev.get("options"));
            events.emit(ctx.id(), "confirm", p);
            return;
        }
        if (!"cloud".equals(kind)) { r.sendAsync("confirm", Json.obj().put("confirmId", id).put("accept", false)); return; }
        if (cloud != null && cloud.isOn(ctx.task.appId())) {
            r.sendAsync("confirm", Json.obj().put("confirmId", id).put("accept", true).put("note", "这个应用之前已经启用了云服务。"));
            return;
        }
        ctx.pendingConfirmId = id;
        ctx.pendingConfirmKind = kind;
        p.put("title", "是否要启用织页云服务？");
        com.fasterxml.jackson.databind.node.ArrayNode items = p.putArray("items");
        CLOUD_CONFIRM_ITEMS.forEach(items::add);
        events.emit(ctx.id(), "confirm", p);
    }

    /** 用户在确认卡上的选择：cloud 先启用云服务；choice 透传用户选项；continue 唤醒等待中的执行线程 */
    private boolean answerConfirm(TaskContext ctx, String id, boolean accept, String text) {
        if ("continue".equals(ctx.pendingConfirmKind)) {
            synchronized (ctx) {
                var wait = ctx.continueWait;
                if (wait == null || ctx.cancelRequested || id == null || !id.equals(ctx.pendingConfirmId)) return false;
                ctx.pendingConfirmId = null;
                return wait.complete(accept ? java.util.Optional.ofNullable(text) : null);
            }
        }
        var r = ctx.runner;
        if (r == null || ctx.cancelRequested || id == null || !id.equals(ctx.pendingConfirmId)) return false;
        String kind;
        synchronized (ctx) {
            if (!id.equals(ctx.pendingConfirmId)) return false;
            kind = ctx.pendingConfirmKind;
            ctx.pendingConfirmId = null;
        }
        String note = "";
        if ("choice".equals(kind)) {
            note = text == null ? "" : text.trim();
        } else if (accept && "cloud".equals(kind)) {
            AppRow app = apps.findById(ctx.task.appId()).orElseThrow();
            Map<String, Object> on = cloud.enable(app.workspaceId(), app.id());
            StringBuilder names = new StringBuilder();
            if (on.get("tables") instanceof List<?> ts) for (Object t : ts)
                if (t instanceof Map<?, ?> m) { if (names.length() > 0) names.append("、"); names.append(m.get("name")); }
            note = "当前已有的表：" + names + "。在 cloud/schema.sql 里声明这个应用需要的表，任务成功后平台会自动建表。";
        }
        r.sendAsync("confirm", Json.obj().put("confirmId", id).put("accept", accept).put("note", note));
        return true;
    }

    private void requestCancel(TaskContext ctx) {
        if (ctx.cancelRequested) return;
        ctx.cancelRequested = true;
        var wait = ctx.continueWait;
        if (wait != null) wait.complete(null);
        var r = ctx.runner;
        if (r == null) return;
        if ("prompt".equals(ctx.currentCommand)) {
            r.abort();
            Threads.start(() -> {
                try { Thread.sleep(15_000); } catch (InterruptedException ignored) { }
                if ("prompt".equals(ctx.currentCommand) && r.alive()) r.kill();
            });
        } else {
            r.kill();
        }
    }

    // ================= 阶段 =================
    private void phase(TaskContext ctx, String status, String label, int progress) throws TaskContext.Cancelled, TaskContext.Fail {
        ctx.checkCancelled();
        flushText(ctx, true);
        if (!tasks.advance(ctx.id(), status, label, progress)) {
            String s = tasks.status(ctx.id());
            if ("CANCELLING".equals(s)) { ctx.cancelRequested = true; throw new TaskContext.Cancelled(); }
            throw new TaskContext.Fail("worker_lost", "任务状态已改变（" + s + "）");
        }
        events.status(ctx.id(), status, label, progress);
    }

    /** 用户停止任务时保存「任务中断前自动保存」版本；失败只记日志，不影响停止流程。 */
    private String snapshotInterrupted(TaskContext ctx, TaskRow t) {
        try {
            AppRow app = apps.findById(t.appId()).orElse(null);
            if (app == null || ctx.workspace == null) return null;
            String title = "任务中断前自动保存";
            GitService.CommitResult c = git.commitWorkspace(app.id(), ctx.workspace, title + "\n\n织页任务 " + ctx.id(), ctx.id());
            String buildId = null;
            if (!app.noPreview()) {
                try {
                    Path dist = ctx.workspace.resolve(".zhiye");
                    buildId = versionService.ensureBuild(app.id(), c.tree(), dist.resolve("dist-preview"), dist.resolve("dist-publish"), app.visualEditable()).id();
                } catch (Exception ignored) {
                    // 半成品可能编译不过：只存源码快照
                }
            }
            return versionService.createInterrupted(app.id(), c.commit(), c.tree(), buildId).id();
        } catch (Exception e) {
            log.warn("任务 {} 中断前自动保存失败：{}", t.id(), e.getMessage());
            return null;
        }
    }

    private void onCancelled(TaskContext ctx) {
        TaskRow t = ctx.task;
        if (ctx.manualVersionNumber > 0) {
            String msg = "你的手动修改已保存为 V" + ctx.manualVersionNumber + "；AI 修改没有完成：已停止";
            tasks.saveCheckpoint(new TaskRepository.Checkpoint(t.id(), ctx.workspace.toString(),
                    ctx.agentDir == null ? null : ctx.agentDir.toString(), ctx.sessionFile, true));
            tasks.finish(t.id(), "CANCELLED", "已停止", currentProgress(t.id()), "AI_NOT_FINISHED", msg, null, null);
            taskService.afterTerminal(t, "CANCELLED", "AI_NOT_FINISHED", msg);
            events.emit(t.id(), "status", Map.of("status", "CANCELLED", "phaseLabel", "已停止", "progress", currentProgress(t.id())));
            events.emit(t.id(), "finished", taskService.finishedPayload("CANCELLED", null, "AI_NOT_FINISHED", msg));
            return;
        }
        boolean keep = ctx.prepared && ("generate".equals(t.type()) || "modify".equals(t.type())) && ctx.workspace != null;
        String savedVersion = null;
        if (keep) {
            // 中断前把工作区快照存成一个版本；构建失败也保留源码快照，不顶替当前版本
            savedVersion = snapshotInterrupted(ctx, t);
            tasks.saveCheckpoint(new TaskRepository.Checkpoint(t.id(), ctx.workspace.toString(),
                    ctx.agentDir == null ? null : ctx.agentDir.toString(), ctx.sessionFile, ctx.draftApplied));
        } else cleanup(ctx);
        boolean won = tasks.finish(t.id(), "CANCELLED", "已停止", currentProgress(t.id()), null, null, savedVersion, null);
        if (!won) return;
        recordTaskMetric(t, "CANCELLED", null);
        taskService.afterTerminal(t, "CANCELLED", null, null);
        events.emit(t.id(), "status", Map.of("status", "CANCELLED", "phaseLabel", "已停止", "progress", currentProgress(t.id())));
        events.emit(t.id(), "finished", taskService.finishedPayload("CANCELLED", null, null, null));
    }

    private void onFailed(TaskContext ctx, String code, String message) {
        TaskRow t = ctx.task;
        if (ctx.manualVersionNumber > 0 && !message.startsWith("你的手动修改"))
            message = "你的手动修改已保存为 V" + ctx.manualVersionNumber + "；AI 修改没有完成：" + message;
        boolean keep = ctx.prepared && ctx.sessionFile != null && ("generate".equals(t.type()) || "modify".equals(t.type()));
        if (keep) {
            tasks.saveCheckpoint(new TaskRepository.Checkpoint(t.id(), ctx.workspace.toString(),
                    ctx.agentDir == null ? null : ctx.agentDir.toString(), ctx.sessionFile, ctx.draftApplied));
        } else cleanup(ctx);
        // 先原子落终态，再执行 afterTerminal（以便排队消息 drainNext 能看到 current.terminal() == true 并可靠派发）
        boolean won = tasks.finish(t.id(), "FAILED", "失败", currentProgress(t.id()), code, message, null, null);
        if (!won) return;
        taskService.afterTerminal(t, "FAILED", code, message);
        recordTaskMetric(t, "FAILED", code);
        events.emit(t.id(), "status", Map.of("status", "FAILED", "phaseLabel", "失败", "progress", currentProgress(t.id())));
        events.emit(t.id(), "finished", taskService.finishedPayload("FAILED", null, code, message));
    }

    private void succeed(TaskContext ctx, String versionId, JsonNode result) {
        boolean won = tasks.finish(ctx.id(), "SUCCEEDED", "已完成", 100, null, null, versionId, result);
        if (!won) {
            cleanup(ctx);
            return;
        }
        taskService.afterTerminal(ctx.task, "SUCCEEDED", null, null);
        recordTaskMetric(ctx.task, "SUCCEEDED", null);
        events.emit(ctx.id(), "status", Map.of("status", "SUCCEEDED", "phaseLabel", "已完成", "progress", 100));
        events.emit(ctx.id(), "finished", taskService.finishedPayload("SUCCEEDED", versionId, null, null));
        cleanup(ctx);
    }

    private void recordTaskMetric(TaskRow t, String status, String code) {
        if (metrics == null || t == null) return;
        Instant ref = t.startedAt() != null ? t.startedAt() : t.createdAt();
        long ms = ref == null ? 0L : Math.max(0L, Duration.between(ref, Instant.now()).toMillis());
        metrics.recordTask(t.type(), status, code, ms);
    }

    private int currentProgress(String id) { return tasks.findById(id).map(TaskRow::progress).orElse(0); }

    private void cleanup(TaskContext ctx) {
        if (!ctx.ownsDirs) return;
        try {
            GitService.deleteRecursively(ctx.workspace);
            GitService.deleteRecursively(ctx.agentDir);
        } catch (Exception e) {
            log.warn("清理工作区失败：{}", e.getMessage());
        }
    }

    // ================= plan =================
    private void runPlan(TaskContext ctx) throws Exception {
        AppRow app = apps.findById(ctx.task.appId()).orElseThrow(() -> new TaskContext.Fail("NOT_FOUND", "应用不存在"));
        phase(ctx, "RUNNING", "正在生成创造计划", 30);
        String system = app.isService() ? """
                你是「织页」的全栈应用规划助手。根据用户的需求写一份清晰的前后端一体创造计划，用来指导编码 agent 在 React + Vite + TypeScript（src/）与 Node.js Express 后端（server/index.mjs）骨架上完成真实可运行的全栈应用。
                只输出一个 JSON 对象，不要输出其他文字：
                {"name": "简洁的中文应用名，不超过 16 字", "shape": "service", "scale": "normal", "planDoc": "Markdown 格式的创造计划"}
                planDoc 结构：# 应用名称 / ## 应用目标 / ## 前端页面与交互（路由、核心组件、图表/看板/表单交互）/ ## 后端 REST API 契约（列出每条 GET/POST/PATCH/DELETE /api/... 路径、入参、出参及校验规则，含 GET /api/health）/ ## 数据持久化方案（DATA_DIR 文件存储或 PG 表结构、种子数据）/ ## 验收标准。
                要求：前端全部通过相对路径 /api/... 调用后端，后端绑定 0.0.0.0:${PORT:-8080}，遵守单应用 1C 1G 约束，不引入 Redis/Kafka/微服务。总字数 400～1000 字。
                """ : app.isSkill() ? """
                你是「织页」的技能规划助手。根据用户的需求写一份简洁的中文创造计划，用来指导编码 agent 编写一个符合 SkillHub 规范的 skill 包（只有文件，不是网页）。
                只输出一个 JSON 对象，不要输出其他文字：
                {"name": "SkillHub 显示名称（建议按「厂区/业务域/系统 - 场景用途」命名，不超过 20 字，避免仅用 SPC、助手等简称泛称）", "planDoc": "Markdown 格式的创造计划"}
                planDoc 结构：# 技能名称 / ## 技能目标（功能与适用场景，对应 description ≤120 字）/ ## 文件结构（根目录 SKILL.md ≤500 行、references/、scripts/ 下的每个文件及用途）/ ## 使用流程（输入、步骤、输出格式）/ ## 依赖与权限（运行环境 compatibility、依赖的系统权限或其它技能）/ ## 验收标准（含 SkillHub 命名、无敏感信息、无临时文件）。
                总字数 200～500 字。不要描述页面、界面或交互。
                """ : """
                你是「织页」的产品规划助手。先判断这个需求**应该做成什么形态**，再写一份简洁的中文创造计划。
                形态只能是下面五种之一，自己按需求判断，不要被用户选的类型限制：
                - "web"：需要界面和交互的网页应用（Vite + React + Tailwind），网页应用默认选这个；
                - "vue"：用户**明确要求用 Vue** 开发的网页应用（Vite + Vue 3 + Tailwind）；用户没提 Vue 时不要选；
                - "static"：内容简单、不需要构建的静态页面（单页官网、活动页、说明页）；
                - "script"：命令行程序 / 数据处理脚本 / 定时任务，只交付可运行的代码，没有页面；
                - "doc"：用户明确要**网页形式**的文档站（带目录、搜索的在线文档网站）；只是要一份文档时不会走到这里（会话里直接写成文件）。
                只输出一个 JSON 对象，不要输出其他文字：
                {"name": "名称（2～12 个字）", "shape": "web|vue|static|script|doc", "reason": "一句话说明为什么选这个形态", "scale": "normal|phased", "planDoc": "Markdown 格式的创造计划"}
                planDoc 结构（按顺序）：
                # 名称
                ## 目标（2～3 句，写清给谁用、解决什么）
                ## 内容与功能（列表；web/vue/static 写页面与交互，doc 写 docs/ 章节结构，script 写入口脚本、参数、输入输出与退出码）
                ## 业务规则（如有，用表格：规则 / 说明；没有就写「无」）
                ## 异常与边界情况（列表：空数据、超长输入、重复、失败重试等；至少 2 条）
                ## 验收标准（列表，每条都能直接勾验）
                ## 本期不实现（列表，明确砍掉的范围；没有就写「无」）
                计划要能被评审直接看懂；总字数 400～900 字，能写成表格的用表格。不要在计划里写代码或 diff。
                """ + com.tcl.csot.zhiye.constants.Capabilities.PLAN;
        // 创建时点选的技能：计划阶段就要遵循技能里的规范，否则计划和后续生成会打架
        List<String> planSkillIds = jdbc.sql("select skill_app_ids from app where id = ?").param(app.id())
                .query(String.class).optional()
                .map(js -> Json.read(js, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}))
                .orElse(List.of());
        String skillText = planSkillIds.isEmpty() ? "" : skillRefs.planContext(app.workspaceId(), planSkillIds);
        String user = "用户选的类型（只是参考，不要被它限制）：" + app.type() + "\n类型指引：" + Catalog.typeHint(app.type())
                + "\n生成模式：" + app.mode() + "\n用户需求：\n" + app.prompt()
                + (skillText.isBlank() ? "" : "\n\n## 参考技能（计划必须遵循其中的规范、格式与流程）\n" + skillText)
                + dataSources.summary(app.id())
                + (memories == null ? "" : memories.promptSection(app.workspaceId()));
        // 会话里附的截图、需求文档也要给计划看：只给文字时模型会写「未提供截图」，计划只能靠复述猜
        List<com.tcl.csot.zhiye.service.chat.AskAttachments.Attached> planAssets = planAttachments(app);
        if (!planAssets.isEmpty()) user += "\n\n用户附上了下面的素材（截图请按图里的界面理解需求，计划里不要再写「未提供截图」）：";
        String content;
        try {
            content = kimi.chat(ctx.id(), system, com.tcl.csot.zhiye.service.chat.AskService.userMessage(user, planAssets), 16000);
        } catch (LlmException e) {
            ctx.checkCancelled();
            if (platformLogs != null) {
                platformLogs.error("llm_gateway", "PLAN_LLM_FAILED", app.workspaceId(), null, app.id(), ctx.id(),
                        null, null, "需求规划调用模型失败：" + e.code() + " · " + e.getMessage(),
                        com.tcl.csot.zhiye.service.ops.PlatformLogService.stackTrace(e),
                        Map.of("code", e.code()));
            }
            // 与生成阶段一致：供应商配额 / 任务预算要分开，否则用户会被提示「简化需求」
            throw modelFailure(ctx, e.code(), e.getMessage());
        }
        ctx.checkCancelled();
        JsonNode j = KimiClient.extractJson(content);
        String name = j == null ? null : Json.text(j, "name");
        String doc = j == null ? null : Json.text(j, "planDoc");
        if (doc == null || doc.isBlank()) doc = content.trim();
        if (name != null) {
            name = name.trim().replaceAll("[\\r\\n]", "");
            if (name.isEmpty()) name = null;
            else if (name.length() > 40) name = name.substring(0, 40);
        }
        // 形态由模型决定：web/doc 用 React 模板，vue 用 Vue 模板，static 用静态模板，script 用脚本模板，service 用全栈模板
        String shape = j == null ? null : Json.text(j, "shape");
        String kind = kindForShape(shape);
        // 用户显式选择了「全栈应用」时，不降级回纯前端模板
        if (app.isService() && !"service".equals(kind)) kind = null;
        // 只在还没有任何版本时换模板（换模板会覆盖仓库的初始提交）
        if (kind != null && app.currentVersionId() != null) kind = null;
        if (kind != null && !kind.equals(app.projectKind())) {
            try {
                git.initFromTemplate(app.id(), templateFor(kind));
                apps.update(app.id(), "project_kind", kind);
                events.emit(ctx.id(), "stage", Map.of("label", "按「" + shapeLabel(kind) + "」准备项目结构"));
            } catch (Exception e) {
                throw new TaskContext.Fail("TEMPLATE_ERROR", "初始化「" + shapeLabel(kind) + "」项目结构失败：" + e.getMessage());
            }
        }
        // 超过单次规模：计划只写第一期，过程里提示一句，计划卡上也会显示分期说明
        boolean phased = (j != null && "phased".equalsIgnoreCase(Json.text(j, "scale"))) || doc.contains("## 分期计划");
        if (phased) events.emit(ctx.id(), "stage", Map.of("label", "需求比较大，先做第一期，其余排进分期计划"));
        events.emit(ctx.id(), "agent_text", Map.of("delta", doc));
        apps.setPlan(app.id(), name, doc, "plan");
        succeed(ctx, null, Json.obj().put("name", name).put("shape", kind == null ? app.projectKind() : kind).put("phased", phased));
    }

    /** 计划里给的形态 → 内部构建方式（projectKind） */
    static String kindForShape(String shape) {
        if (shape == null) return null;
        return switch (shape.trim().toLowerCase()) {
            case "web", "doc" -> "template";
            case "vue" -> "vue";
            case "static" -> "static";
            case "script" -> "script";
            case "skill" -> "skill";
            case "service", "fullstack" -> "service";
            default -> null;
        };
    }

    static String templateFor(String kind) {
        return switch (kind) {
            case "skill" -> "skill-template";
            case "script" -> "script-template";
            case "static" -> "static-template";
            case "vue" -> "vue-template";
            case "service" -> "fullstack-template";
            default -> "app-template";
        };
    }

    static String shapeLabel(String kind) {
        return switch (kind) {
            case "script" -> "命令行脚本";
            case "service" -> "前后端全栈应用";
            case "static" -> "静态页面";
            case "skill" -> "技能包";
            case "vue" -> "网页应用（Vue）";
            default -> "网页应用";
        };
    }

    // ================= generate / modify =================
    private void runAgent(TaskContext ctx) throws Exception {
        TaskRow t = ctx.task;
        AppRow app = apps.findById(t.appId()).orElseThrow(() -> new TaskContext.Fail("NOT_FOUND", "应用不存在"));
        JsonNode input = t.input();
        JsonNode resume = input.get("resume");
        boolean resumed = resume != null && Files.isDirectory(Path.of(resume.path("workspaceDir").asText("/nonexistent")));
        boolean retry = resumed && resume.path("retry").asBoolean(false);
        if (resumed) {
            ctx.workspace = Path.of(resume.path("workspaceDir").asText());
            ctx.agentDir = Path.of(resume.path("agentDir").asText(props.runtimePath().resolve("agents/" + t.id()).toString()));
            ctx.sessionFile = resume.path("sessionFile").isTextual() ? resume.path("sessionFile").asText() : null;
            ctx.draftApplied = resume.path("draftApplied").asBoolean(false);
        } else {
            prepareWorkspace(ctx, app, baseCommit(app, t.baseVersionId()));
        }
        // 之前的任务已经保存过手动修改（重试 / 继续）：不再写回草稿
        if (input.has("manualVersionId")) { ctx.draftApplied = true; ctx.manualVersionNumber = input.path("manualVersionNumber").asInt(0); }
        if ("generate".equals(t.type()) && !resumed) copyAssets(ctx, app, app.assetIds());
        List<String> msgAssets = Json.M.convertValue(input.path("assetIds").isArray() ? input.get("assetIds") : Json.M.createArrayNode(),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        boolean continuing = input.hasNonNull("continueContent");
        if ("modify".equals(t.type()) && (!resumed || continuing)) copyAssets(ctx, app, msgAssets);
        // 引用的技能：生成用创建时选的，对话修改用这条消息选的
        List<String> skillIds = "modify".equals(t.type())
                ? Json.M.convertValue(input.path("skillAppIds").isArray() ? input.get("skillAppIds") : Json.M.createArrayNode(),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {})
                : jdbc.sql("select skill_app_ids from app where id = ?").param(app.id()).query(String.class).optional()
                        .map(js -> Json.read(js, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {})).orElse(List.of());
        List<com.tcl.csot.zhiye.service.skillhub.SkillRefs.Placed> skills = skillIds.isEmpty() ? List.of() : skillRefs.place(ctx.agentDir, skillIds);
        if (app.isService() && monolithService != null) {
            try {
                ObjectNode snap = monolithService.inspectSnapshotForAgent(app);
                if (snap != null) {
                    Files.writeString(ctx.agentDir.resolve("runtime-inspect.json"), Json.write(snap), java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                log.warn("写入全栈运行态快照失败 app={}：{}", app.id(), e.getMessage());
            }
        }
        startRunner(ctx, false);
        boolean modify = "modify".equals(t.type());
        JsonNode elementContext = input.get("elementContext");
        JsonNode patches = input.path("draftPatches");
        if (modify && patches.isArray() && !patches.isEmpty() && !ctx.draftApplied)
            elementContext = saveManualFirst(ctx, app, patches, elementContext);
        ObjectNode init = Json.obj();
        init.put("agentDir", ctx.agentDir.toString());
        if (ctx.sessionFile != null) init.put("sessionFile", ctx.sessionFile); else init.putNull("sessionFile");
        init.putObject("model").put("provider", props.runner().modelProvider()).put("id", props.runner().modelId());
        init.put("thinking", Catalog.thinking(app.mode()));
        init.put("projectKind", app.projectKind());
        // 启用了云服务的应用：告诉 agent 怎么用（预览页已经注入了 SDK 和令牌）
        boolean cloudOn = jdbc.sql("select count(*) from app_cloud where app_id = ? and status = 'on'").params(app.id()).query(Long.class).single() > 0;
        init.put("cloudEnabled", cloudOn);
        init.put("maxTurns", props.task().maxAgentTurns());
        JsonNode ir = call(ctx, "init", init);
        if (ir.hasNonNull("sessionFile")) ctx.sessionFile = ir.get("sessionFile").asText();
        ctx.prepared = true;
        // 技能已放进 agent 目录：等 init 成功（agent 真的就绪）才打这一行，
        // 手动修改写回失败、init 失败等「AI 还没开始」的路径不该留下已加载的假象
        if (!skills.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (com.tcl.csot.zhiye.service.skillhub.SkillRefs.Placed s : skills) {
                if (!names.isEmpty()) names.append('、');
                names.append(s.name());
            }
            events.emit(ctx.id(), "tool", Map.of("id", "skills", "name", "skill",
                    "summary", "加载技能 · " + names, "state", "end", "ok", true));
        }
        List<com.tcl.csot.zhiye.service.source.DataSourceService.Source> bound = app.isSkill() ? List.of() : dataSources.boundSources(app.id());
        if (!bound.isEmpty())
            events.emit(ctx.id(), "tool", Map.of("id", "sources", "name", "source",
                    "summary", "读取数据源 · " + String.join("、", bound.stream().map(com.tcl.csot.zhiye.service.source.DataSourceService.Source::name).toList()),
                    "state", "end", "ok", true));

        phase(ctx, "RUNNING", "正在编写代码", 20);
        String instruction = modify ? Prompts.modify(input.path("content").asText(), elementContext, app.projectKind())
                : Prompts.generate(app, assetPaths(app, app.assetIds()));
        if (modify && !msgAssets.isEmpty()) {
            StringBuilder sb = new StringBuilder("\n\n## 用户这次附带的素材（已放进项目）\n");
            for (String p : assetPaths(app, msgAssets)) {
                sb.append("- ").append(p);
                if (!app.isSkill()) sb.append("（代码中用相对路径 `./").append(p.replaceFirst("^public/", "")).append("` 引用）");
                sb.append('\n');
            }
            instruction += sb;
        }
        instruction += com.tcl.csot.zhiye.service.skillhub.SkillRefs.instruction(skills);
        if (!bound.isEmpty()) instruction += dataSources.instruction(app.id());
        if (modify && !app.isSkill()) {
            String repoMap = RepoMap.build(ctx.workspace, app.projectKind(), recentVersions(app.id()));
            if (!repoMap.isEmpty()) instruction += repoMap;
            String qa = recentWorkbenchQa(app.id(), t);
            if (!qa.isEmpty()) instruction += qa;
            String fnErrors = functions.recentErrors(app.id());
            if (!fnErrors.isEmpty()) {
                instruction += fnErrors;
                events.emit(ctx.id(), "tool", Map.of("id", "fn-logs", "name", "read", "summary", "读取云函数报错日志", "state", "end", "ok", true));
            }
        }
        if (app.isTemplateLike() || app.isService()) {
            Set<String> taken = new HashSet<>();
            for (com.tcl.csot.zhiye.service.skillhub.SkillRefs.Placed s : skills) taken.add(s.name());
            // React / Vue / 全栈按项目形态过滤不相关的内置技能，避免误导
            if (app.isVue()) taken.addAll(List.of("charts-dashboard", "multi-page-app", "fullstack-app"));
            else if (app.isService()) taken.addAll(List.of("cloud-functions", "app-ai", "data-sources"));
            else taken.add("fullstack-app");
            instruction += com.tcl.csot.zhiye.service.skillhub.SkillRefs.catalogInstruction(skillRefs.placeBuiltin(ctx.agentDir, taken));
        }
        if (memories != null) {
            String memSection = memories.promptSection(app.workspaceId());
            if (!memSection.isBlank()) instruction += memSection;
        }
        String text = continuing && resumed ? Prompts.continuePrevious(input.path("continueContent").asText(), instruction)
                : resumed && (!retry || resume.path("continueWork").asBoolean(false)) ? Prompts.resume(instruction) : instruction;
        String summary = prompt(ctx, text);

        int fixRounds = props.task().verifyFixRounds();
        JsonNode passed = null;
        List<String> fixes = new ArrayList<>();
        for (int round = 0; ; round++) {
            phase(ctx, "VERIFYING", "正在校验：类型检查、构建、冒烟测试", 70);
            JsonNode v = withFunctionCheck(ctx, withSchemaCheck(ctx, verify(ctx, app, round)));
            if (v.path("ok").asBoolean(false)) { passed = v; break; }
            if (round >= fixRounds)
                throw new TaskContext.Fail("VERIFY_FAILED", "校验未通过（已修复 " + fixRounds + " 轮）：" + failedSummary(v));
            phase(ctx, "RUNNING", "校验未通过，正在修复（第 " + (round + 1) + " 轮）", 50);
            String fixSummary = prompt(ctx, Prompts.fix(v.path("checks"), round + 1, app.projectKind()));
            if (fixSummary != null && !fixSummary.isBlank()) fixes.add(fixSummary.trim());
        }

        String title = modify ? modifyTitle(input.path("content").asText()) : "页面与交互生成完成";
        VersionRow ver = commit(ctx, app, title, modify ? "modify" : "generate", false);
        if (app.isService() && monolithService != null) {
            phase(ctx, "COMMITTING", "正在构建并更新预览服务", 92);
            try {
                monolithService.deployPreview(app, ver);
            } catch (Exception e) {
                throw new TaskContext.Fail("BUILD_FAILED", "单体服务预览部署失败（已发布线上版本不受影响）：" + e.getMessage());
            }
        }
        String cloudNote = app.isService() ? null : applyCloudSchema(ctx, app);
        String finalSummary = summary == null || summary.isBlank() ? (modify ? "已完成修改。" : "已完成生成。") : summary.trim();
        if (ctx.manualVersionNumber > 0)
            finalSummary = "已先把你的手动修改保存为 V" + ctx.manualVersionNumber + "，AI 修改保存为 V" + ver.number() + "。\n\n" + finalSummary;
        if (app.isSkill()) finalSummary = finalSummary.replace("预览已就绪", "").trim() + "\n\n技能包已" + (modify ? "更新" : "生成") + "，可在「代码」中查看。";
        // 校验通过后由系统补「检查结果」（失败路径不走这里）；模型给的 [[next]] 下一步始终放在最后一行
        finalSummary = composeSummary(finalSummary, fixes, checkSummary(passed == null ? null : passed.path("checks")));
        if (cloudNote != null) finalSummary = withNoteBeforeNext(finalSummary, cloudNote);
        if (modify) messages.setStatusByTask(t.id(), "ok");
        messages.insert(app.id(), "assistant", finalSummary, null, t.id(), "ok");
        tasks.deleteCheckpoint(t.id());
        succeed(ctx, ver.id(), Json.obj().put("summary", finalSummary).put("turns", ctx.turns));
    }

    static String modifyTitle(String content) {
        String c = content.trim().replaceAll("\\s+", " ");
        return c.length() > 24 ? c.substring(0, 24) + "…" : c;
    }

    // ================= visual_save =================
    private void runVisualSave(TaskContext ctx) throws Exception {
        TaskRow t = ctx.task;
        AppRow app = apps.findById(t.appId()).orElseThrow(() -> new TaskContext.Fail("NOT_FOUND", "应用不存在"));
        JsonNode patches = t.input().path("patches");
        if (!Objects.equals(app.currentVersionId(), t.baseVersionId()))
            throw new TaskContext.Fail("VERSION_MISMATCH", "当前版本已变化，请刷新后重新编辑");
        prepareWorkspace(ctx, app, baseCommit(app, t.baseVersionId()));
        startRunner(ctx, false);
        ctx.prepared = true;
        phase(ctx, "RUNNING", "正在把修改写回源码", 30);
        applyVisual(ctx, app, patches);
        phase(ctx, "VERIFYING", "正在校验与构建", 60);
        JsonNode v = verify(ctx, app.projectKind());
        if (!v.path("ok").asBoolean(false)) throw new TaskContext.Fail("VERIFY_FAILED", "校验未通过：" + failedSummary(v));
        String title = t.input().hasNonNull("title") && !t.input().path("title").asText().isBlank()
                ? t.input().path("title").asText() : "可视化编辑 · " + patches.size() + " 处修改";
        VersionRow ver = commit(ctx, app, title, "visual", true);
        succeed(ctx, ver.id(), Json.obj().put("applied", patches.size()));
    }

    // ================= code_save =================
    private void runCodeSave(TaskContext ctx) throws Exception {
        TaskRow t = ctx.task;
        AppRow app = apps.findById(t.appId()).orElseThrow(() -> new TaskContext.Fail("NOT_FOUND", "应用不存在"));
        if (!Objects.equals(app.currentVersionId(), t.baseVersionId()))
            throw new TaskContext.Fail("VERSION_MISMATCH", "当前版本已变化，请刷新后重新编辑");
        prepareWorkspace(ctx, app, baseCommit(app, t.baseVersionId()));
        JsonNode files = t.input().path("files");
        phase(ctx, "RUNNING", "正在写入文件", 25);
        Path root = ctx.workspace.toRealPath();
        for (JsonNode f : files) {
            Path target = root.resolve(f.path("path").asText()).normalize();
            if (!target.startsWith(root) || target.startsWith(root.resolve(".git")) || target.startsWith(root.resolve("node_modules")))
                throw new TaskContext.Fail("VALIDATION", "文件路径不合法：" + f.path("path").asText());
            if (f.path("delete").asBoolean(false)) {
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) Files.delete(target);
            } else {
                if (Files.isDirectory(target)) throw new TaskContext.Fail("VALIDATION", "「" + f.path("path").asText() + "」是一个目录");
                Files.createDirectories(target.getParent());
                Files.writeString(target, f.path("content").asText(), java.nio.charset.StandardCharsets.UTF_8);
            }
            events.emit(ctx.id(), "tool", Map.of("id", "code-" + f.path("path").asText(), "name", "write",
                    "summary", (f.path("delete").asBoolean(false) ? "删除 " : "修改 ") + f.path("path").asText(), "state", "end", "ok", true));
        }
        startRunner(ctx, false);
        ctx.prepared = true;
        phase(ctx, "VERIFYING", "正在检查", 60);
        if (app.isService()) {
            VersionRow ver = commit(ctx, app, "代码编辑 · " + files.size() + " 个文件", "code", false);
            if (monolithService != null) {
                phase(ctx, "RUNNING", "正在构建并更新预览服务", 85);
                try {
                    monolithService.deployPreview(app, ver);
                } catch (Exception e) {
                    throw new TaskContext.Fail("BUILD_FAILED", "代码已保存，但预览服务启动失败（已发布线上版本不受影响）：" + e.getMessage());
                }
            }
            messages.insert(app.id(), "assistant",
                    "已保存你对 " + files.size() + " 个文件的修改（V" + ver.number() + "），工作台预览已更新；线上已发布版本保持不变，确认无误后点击右上角 **「发布」** 切流上线。",
                    null, t.id(), "ok");
            succeed(ctx, ver.id(), Json.obj().put("files", files.size()));
            return;
        }
        JsonNode v = withFunctionCheck(ctx, withSchemaCheck(ctx, verify(ctx, app.projectKind())));
        if (!v.path("ok").asBoolean(false)) throw new TaskContext.Fail("VERIFY_FAILED", "检查没有通过，修改未保存：" + failedSummary(v));
        VersionRow ver = commit(ctx, app, "代码编辑 · " + files.size() + " 个文件", "code", false);
        String cloudNote = applyCloudSchema(ctx, app);
        messages.insert(app.id(), "assistant", "已保存你对 " + files.size() + " 个文件的修改（V" + ver.number() + "）" + (cloudNote == null ? "" : "\n\n" + cloudNote), null, t.id(), "ok");
        succeed(ctx, ver.id(), Json.obj().put("files", files.size()));
    }

    private JsonNode applyVisual(TaskContext ctx, AppRow app, JsonNode patches) throws Exception {
        ObjectNode args = Json.obj();
        args.put("projectKind", app.projectKind());
        args.set("patches", patches);
        JsonNode r = call(ctx, "visual", args);
        JsonNode failures = r.path("failures");
        if (failures.isArray() && !failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode f : failures) sb.append(f.path("reason").asText("无法写回")).append("（").append(f.path("loc").asText()).append("）；");
            throw new TaskContext.Fail("VISUAL_FAILED", "可视化编辑没能写回源码：" + sb);
        }
        return r;
    }

    // ================= clone（从模板复刻） =================
    private void runClone(TaskContext ctx) throws Exception {
        TaskRow t = ctx.task;
        AppRow app = apps.findById(t.appId()).orElseThrow(() -> new TaskContext.Fail("NOT_FOUND", "应用不存在"));
        prepareWorkspace(ctx, app, null);
        phase(ctx, "RUNNING", "正在复制模板文件", 20);
        Path root = ctx.workspace.toRealPath();
        int count = 0;
        try (InputStream in = store.get(t.input().path("objectKey").asText()).orElseThrow(() -> new TaskContext.Fail("NOT_FOUND", "模板文件不存在，可能已被删除"));
             java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(in)) {
            for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;
                Path target = root.resolve(name.substring(slash + 1)).normalize();
                if (!target.startsWith(root) || target.startsWith(root.resolve(".git")) || target.startsWith(root.resolve("node_modules")))
                    throw new TaskContext.Fail("VALIDATION", "模板里有不合法的路径：" + name);
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        if (count == 0) throw new TaskContext.Fail("VALIDATION", "模板里没有文件");
        events.emit(ctx.id(), "tool", Map.of("id", "clone-files", "name", "write", "summary", "复制模板文件 · " + count + " 个", "state", "end", "ok", true));
        if (app.isService()) {
            phase(ctx, "VERIFYING", "正在提交单体项目源码", 45);
            VersionRow ver = commit(ctx, app, "从模板「" + t.input().path("templateName").asText("") + "」复刻", "import", false);
            phase(ctx, "COMMITTING", "正在构建并部署单体服务", 75);
            if (monolithService == null) {
                throw new TaskContext.Fail("SERVICE_RUNTIME_DISABLED", "当前环境未启用单体应用托管服务");
            }
            try {
                monolithService.initializeAndDeploy(app, ver);
            } catch (Exception e) {
                throw new TaskContext.Fail("BUILD_FAILED", "单体服务部署失败：" + e.getMessage());
            }
            versionService.publishInternal(app.id(), ver.id());
            jdbc.sql("update app set visible = true, phase = 'ready', updated_at = now() where id = ?").param(app.id()).update();
            succeed(ctx, ver.id(), Json.obj().put("files", count).put("projectKind", "service"));
            return;
        }
        startRunner(ctx, false);
        ctx.prepared = true;
        if ("vite".equals(app.projectKind())) {
            phase(ctx, "RUNNING", "正在安装依赖", 35);
            JsonNode ins = call(ctx, "install", Json.obj());
            if (ins.has("ok") && !ins.path("ok").asBoolean()) throw new TaskContext.Fail("INSTALL_FAILED", "安装依赖失败：" + ins.path("log").asText(""));
        }
        phase(ctx, "VERIFYING", "正在构建与校验", 60);
        JsonNode v = withFunctionCheck(ctx, withSchemaCheck(ctx, verify(ctx, app.projectKind())));
        if (!v.path("ok").asBoolean(false)) throw new TaskContext.Fail("BUILD_FAILED", "构建或校验失败：" + failedSummary(v));
        VersionRow ver = commit(ctx, app, "从模板「" + t.input().path("templateName").asText("") + "」复刻", "import", false);
        if ("vite".equals(app.projectKind())) saveDeps(app.id(), ctx.workspace);
        phase(ctx, "COMMITTING", "正在部署", 95);
        versionService.publishInternal(app.id(), ver.id());
        boolean hasSchema = Files.isRegularFile(ctx.workspace.resolve("cloud/schema.sql"));
        if (hasSchema && cloud != null && !app.isSkill()) {
            try {
                cloud.enable(app.workspaceId(), app.id());
                events.emit(ctx.id(), "tool", Map.of("id", "cloud-schema", "name", "cloud",
                        "summary", "复刻自动启用云服务并创建 Schema", "state", "end", "ok", true));
            } catch (Exception e) {
                log.warn("复刻 {} 启用云服务失败：{}", app.id(), e.getMessage());
                events.emit(ctx.id(), "tool", Map.of("id", "cloud-schema", "name", "cloud",
                        "summary", "复刻启用云服务失败：" + e.getMessage(),
                        "detail", com.tcl.csot.zhiye.service.ops.PlatformLogService.stackTrace(e),
                        "state", "end", "ok", false));
            }
        }
        jdbc.sql("update app set visible = true, phase = 'ready', updated_at = now() where id = ?").param(app.id()).update();
        succeed(ctx, ver.id(), Json.obj().put("files", count).put("cloud", hasSchema));
    }

    // ================= import =================
    private void runImport(TaskContext ctx) throws Exception {
        TaskRow t = ctx.task;
        AppRow app = apps.findById(t.appId()).orElseThrow(() -> new TaskContext.Fail("NOT_FOUND", "应用不存在"));
        JsonNode input = t.input();
        ctx.workspace = props.runtimePath().resolve("workspaces/" + t.id());
        ctx.agentDir = props.runtimePath().resolve("agents/" + t.id());
        GitService.deleteRecursively(ctx.workspace);
        git.checkout(app.id(), null, ctx.workspace, input.path("projectKind").asText("static"));
        Files.createDirectories(ctx.agentDir);
        String format = input.path("format").asText("zip");
        Path archive = ctx.agentDir.resolve("upload." + ("html".equals(format) ? "html" : "zip"));
        try (InputStream in = store.get(input.path("objectKey").asText()).orElseThrow(() -> new TaskContext.Fail("IMPORT_INVALID", "上传的文件已过期，请重新选择"))) {
            Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
        }
        String requestedKind = input.path("projectKind").asText("static");
        if ("service".equals(requestedKind)) {
            runServiceImport(ctx, app, input, archive);
            return;
        }
        boolean vite = "vite".equals(requestedKind);
        startRunner(ctx, vite);
        ctx.prepared = true;

        phase(ctx, "RUNNING", "正在解包并识别项目", 15);
        ObjectNode args = Json.obj();
        args.put("archivePath", archive.toString());
        args.put("format", format);
        if (input.hasNonNull("folderName")) args.put("folderName", input.path("folderName").asText());
        if (!input.path("rootDir").asText("").isEmpty()) args.put("rootDir", input.path("rootDir").asText());
        JsonNode r = call(ctx, "import", args);
        if (!r.path("deployable").asBoolean(false))
            throw new TaskContext.Fail("IMPORT_UNSUPPORTED", r.path("unsupportedReason").asText("暂不支持部署这个项目"));
        String kind = r.path("projectKind").asText(vite ? "vite" : "static");
        String framework = r.path("framework").asText(app.framework());
        jdbc.sql("update app set project_kind = ?, framework = ? where id = ?").params(kind, framework, app.id()).update();
        ArrayNode notes = Json.M.createArrayNode();
        String prebuiltHint = input.hasNonNull("prebuiltDir")
                ? "\n\n压缩包里有已经构建好的 " + input.path("prebuiltDir").asText() + "/，可以重新导入并选择直接部署这个目录（不需要安装依赖和构建）。" : "";
        if ("vite".equals(kind)) {
            phase(ctx, "RUNNING", "正在安装依赖", 30);
            JsonNode ins = call(ctx, "install", Json.obj());
            ins.path("notes").forEach(notes::add);
            if (ins.has("ok") && !ins.path("ok").asBoolean())
                throw new TaskContext.Fail("INSTALL_FAILED", "安装依赖失败：" + ins.path("log").asText(ins.path("detail").asText("")) + prebuiltHint);
        }
        phase(ctx, "VERIFYING", "正在构建与校验", 60);
        JsonNode v = verify(ctx, kind);
        v.path("notes").forEach(notes::add);
        if (!v.path("ok").asBoolean(false)) throw new TaskContext.Fail("BUILD_FAILED", "构建或校验失败：" + failedSummary(v) + prebuiltHint);
        app = apps.findById(app.id()).orElseThrow();
        VersionRow ver = commit(ctx, app, "导入代码并部署", "import", false);
        if ("vite".equals(kind)) saveDeps(app.id(), ctx.workspace);
        phase(ctx, "COMMITTING", "正在部署", 95);
        versionService.publishInternal(app.id(), ver.id());
        jdbc.sql("update app set visible = true, phase = 'ready', updated_at = now() where id = ?").param(app.id()).update();
        ObjectNode result = Json.obj().put("framework", framework).put("projectKind", kind);
        result.set("notes", notes);
        succeed(ctx, ver.id(), result);
    }

    /** 单体后端 / 前后端一体应用导入流程：安全解包 → 提交 V1 → 自动编译构建并启动托管实例 → 发布上线。 */
    private void runServiceImport(TaskContext ctx, AppRow app, JsonNode input, Path archive) throws Exception {
        if (monolithService == null) throw new TaskContext.Fail("SERVICE_DISABLED", "当前环境未启用单体后端托管服务");
        phase(ctx, "RUNNING", "正在解包并识别单体应用", 15);
        var imp = props.importer();
        var limits = new com.tcl.csot.zhiye.service.importer.ImportInspector.Limits(
                imp.maxUploadBytes(), imp.maxFiles(), imp.maxUnpackedBytes(), imp.maxFileBytes());
        String rootDir = input.path("rootDir").asText("");
        int extracted = com.tcl.csot.zhiye.service.importer.ImportInspector.extractZipToDir(archive, ctx.workspace, rootDir, limits);
        events.emit(ctx.id(), "tool", Map.of("id", "svc-unpack", "name", "import", "summary", "解包单体项目 · " + extracted + " 个文件", "state", "end", "ok", true));

        jdbc.sql("update app set project_kind = 'service' where id = ?").param(app.id()).update();
        app = apps.findById(app.id()).orElseThrow();
        VersionRow ver = commit(ctx, app, "导入单体项目并部署", "import", false);

        phase(ctx, "VERIFYING", "正在构建并启动单体服务", 55);
        com.tcl.csot.zhiye.service.runtime.MonolithServiceManager.ServiceRow svc = null;
        String deployError = null;
        try {
            svc = monolithService.initializeAndDeploy(app, ver);
        } catch (Exception e) {
            deployError = e.getMessage();
            svc = monolithService.find(app.id()).orElse(null);
            log.warn("单体应用首次启动未就绪（保留源码 V1 供用户在「服务托管」补全环境变量或命令）app={}: {}", app.id(), deployError);
        }
        String fw = svc != null && svc.framework() != null ? svc.framework() : app.framework();
        String rt = svc != null && svc.runtime() != null ? svc.runtime() : "service";
        if (deployError == null) {
            events.emit(ctx.id(), "tool", Map.of("id", "svc-deploy", "name", "deploy",
                    "summary", "单体服务启动就绪（" + fw + "）", "state", "end", "ok", true));
            phase(ctx, "COMMITTING", "正在上线网关路由", 95);
            versionService.publishInternal(app.id(), ver.id());
        } else {
            events.emit(ctx.id(), "tool", Map.of("id", "svc-deploy", "name", "deploy",
                    "summary", "源码已导入为 V1，但首次启动未就绪，请前往「服务托管」配置环境变量/数据库或启动命令后重新部署",
                    "detail", deployError, "state", "end", "ok", false));
            String shortErr = deployError.length() > 600 ? deployError.substring(0, 600) + "…" : deployError;
            messages.insert(app.id(), "assistant",
                    "源码已导入保存为 **V1**，但首次启动未就绪：\n\n```\n" + shortErr
                            + "\n```\n\n请切换到右上角 **「服务托管」** 面板，按需补充环境变量（如 `STORAGE=sqlite`、密钥等）、开启独立 PostgreSQL Schema 或调整构建/启动命令后，点击 **「重新部署」** 即可上线。",
                    null, ctx.id(), "ok");
        }
        jdbc.sql("update app set visible = true, phase = 'ready', framework = ?, updated_at = now() where id = ?")
                .params(fw, app.id()).update();
        ObjectNode result = Json.obj()
                .put("framework", fw)
                .put("projectKind", "service")
                .put("runtime", rt)
                .put("status", deployError == null ? "running" : "failed")
                .put("deployPending", deployError != null);
        succeed(ctx, ver.id(), result);
    }

    // ================= 公共步骤 =================
    private String baseCommit(AppRow app, String baseVersionId) throws Exception {
        if (baseVersionId != null) {
            Optional<VersionRow> v = versions.find(baseVersionId);
            if (v.isPresent()) return v.get().gitCommit();
        }
        return git.head(app.id()).map(GitService.CommitResult::commit).orElse(null);
    }

    private void prepareWorkspace(TaskContext ctx, AppRow app, String commit) throws Exception {
        ctx.workspace = props.runtimePath().resolve("workspaces/" + ctx.id());
        ctx.agentDir = props.runtimePath().resolve("agents/" + ctx.id());
        GitService.deleteRecursively(ctx.workspace);
        git.checkout(app.id(), commit, ctx.workspace, app.projectKind());
        signals.setWorkspace(ctx.id(), ctx.workspace);
        Files.createDirectories(ctx.agentDir);
        if (app.isTemplateLike()) {
            // React 模板和 Vue 模板各有一份预装依赖缓存，工作区的 node_modules 链接过去
            boolean vue = app.isVue();
            Path nm = props.runtimePath().resolve(vue ? "vue-template-cache/node_modules" : "template-cache/node_modules");
            Path link = ctx.workspace.resolve("node_modules");
            if (Files.isDirectory(nm) && !Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, nm);
            syncTemplateDeps(ctx.workspace.resolve("package.json"), props.rootPath().resolve(vue ? "packages/vue-template/package.json" : "packages/app-template/package.json"));
        } else if ("vite".equals(app.projectKind())) {
            Path saved = depsDir(app.id()).resolve("node_modules");
            Path target = ctx.workspace.resolve("node_modules");
            if (Files.isDirectory(saved, LinkOption.NOFOLLOW_LINKS) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) linkTree(saved, target);
        } else if (app.isService() && Files.isRegularFile(ctx.workspace.resolve("package.json")) && Files.isRegularFile(ctx.workspace.resolve("server/index.mjs"))) {
            Path nm = props.runtimePath().resolve("fullstack-template-cache/node_modules");
            Path link = ctx.workspace.resolve("node_modules");
            if (Files.isDirectory(nm) && !Files.exists(link, LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, nm);
        }
    }

    private Path depsDir(String appId) { return props.runtimePath().resolve("deps/" + appId); }

    private void saveDeps(String appId, Path workspace) {
        Path nm = workspace.resolve("node_modules");
        if (!Files.isDirectory(nm, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Path dir = depsDir(appId);
            GitService.deleteRecursively(dir);
            Files.createDirectories(dir);
            Files.move(nm, dir.resolve("node_modules"));
        } catch (Exception e) {
            log.warn("保存导入项目的依赖失败 app={}: {}", appId, e.toString());
        }
    }

    static void linkTree(Path src, Path dst) throws java.io.IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes a) throws java.io.IOException {
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) throws java.io.IOException {
                Path t = dst.resolve(src.relativize(f).toString());
                if (a.isSymbolicLink()) Files.createSymbolicLink(t, Files.readSymbolicLink(f));
                else if (a.isRegularFile()) {
                    try { Files.createLink(t, f); } catch (java.io.IOException | UnsupportedOperationException e) { Files.copy(f, t, StandardCopyOption.COPY_ATTRIBUTES); }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static boolean syncTemplateDeps(Path pkg, Path templatePkg) throws Exception {
        if (!Files.isRegularFile(pkg) || !Files.isRegularFile(templatePkg)) return false;
        ObjectNode cur = (ObjectNode) Json.M.readTree(pkg.toFile());
        JsonNode tpl = Json.M.readTree(templatePkg.toFile());
        boolean changed = false;
        for (String sec : List.of("dependencies", "devDependencies")) {
            JsonNode want = tpl.path(sec);
            if (!want.isObject()) continue;
            ObjectNode have = cur.path(sec).isObject() ? (ObjectNode) cur.get(sec) : cur.putObject(sec);
            for (var it = want.fields(); it.hasNext(); ) {
                var e = it.next();
                if (!have.has(e.getKey())) { have.set(e.getKey(), e.getValue()); changed = true; }
            }
        }
        if (!changed) return false;
        var printer = new com.fasterxml.jackson.core.util.DefaultPrettyPrinter()
                .withSeparators(com.fasterxml.jackson.core.util.Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(com.fasterxml.jackson.core.util.Separators.Spacing.AFTER))
                .withArrayIndenter(com.fasterxml.jackson.core.util.DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        Files.writeString(pkg, Json.M.writer(printer).writeValueAsString(cur).replace("\r\n", "\n") + "\n");
        return true;
    }

    /** 计划阶段带上应用素材（图片走多模态、文档提取文字）；读不到就不带，不影响出计划 */
    private List<com.tcl.csot.zhiye.service.chat.AskAttachments.Attached> planAttachments(AppRow app) {
        if (app.assetIds() == null || app.assetIds().isEmpty()) return List.of();
        try {
            return attachments.load(app.workspaceId(), app.assetIds());
        } catch (Exception e) {
            log.warn("计划阶段读取素材失败 app={}：{}", app.id(), e.getMessage());
            return List.of();
        }
    }

    private List<String> assetPaths(AppRow app, List<String> assetIds) {
        List<String> out = new ArrayList<>();
        for (String id : assetIds == null ? List.<String>of() : assetIds) {
            jdbc.sql("select name from asset where id = ?").param(id).query(String.class).optional()
                    .ifPresent(n -> out.add(assetDir(app) + safeName(n)));
        }
        return out;
    }

    static String assetDir(AppRow app) { return app.isSkill() ? "references/assets/" : "public/assets/"; }

    static String safeName(String n) {
        String s = n.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return s.isBlank() ? "asset" : s;
    }

    private void copyAssets(TaskContext ctx, AppRow app, List<String> assetIds) throws Exception {
        if (assetIds == null) return;
        for (String id : assetIds) {
            var row = jdbc.sql("select name, object_key from asset where id = ?").param(id)
                    .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).optional();
            if (row.isEmpty()) continue;
            Path target = ctx.workspace.resolve(assetDir(app) + safeName(row.get()[0]));
            Files.createDirectories(target.getParent());
            var in = store.get(row.get()[1]);
            if (in.isPresent()) try (InputStream s = in.get()) { Files.copy(s, target, StandardCopyOption.REPLACE_EXISTING); }
        }
    }

    private void startRunner(TaskContext ctx, boolean allowNet) throws Exception {
        ctx.checkCancelled();
        signals.setWorkspace(ctx.id(), ctx.workspace);
        // 令牌只在任务进行中有效（结束即失效）；任务可以多次「继续」，总时长不固定，有效期给足
        String token = tokens.issue(ctx.id(), Duration.ofHours(24));
        String gw = props.llm().gatewayUrl();
        if (gw == null || gw.isBlank()) gw = port.selfBase() + "/llm/v1";
        long startedMs = System.currentTimeMillis();
        String sbxId = clusterSandbox == null ? null : clusterSandbox.registerCreating(ctx.task, allowNet);
        ctx.sandboxId = sbxId;
        try {
            ctx.runner = sandbox.start(new SandboxProvider.Spec(ctx.id(), ctx.workspace, ctx.agentDir, allowNet, gw, token,
                    taskService.logFile(ctx.id()), ev -> onRunnerEvent(ctx, ev)));
            long dur = System.currentTimeMillis() - startedMs;
            if (metrics != null) metrics.recordSandboxStart(dur, true);
            if (clusterSandbox != null && sbxId != null) {
                clusterSandbox.markReady(sbxId, ctx.task, ctx.runner, (int) Math.max(0L, dur));
            }
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - startedMs;
            if (metrics != null) metrics.recordSandboxStart(dur, false);
            if (clusterSandbox != null && sbxId != null) {
                clusterSandbox.markFailed(sbxId, ctx.task, e.getMessage());
            }
            if (platformLogs != null) {
                platformLogs.error("sandbox", "SANDBOX_START_FAILED", ctx.task.workspaceId(), null, ctx.task.appId(), ctx.id(),
                        null, (int) Math.max(0L, dur), "沙箱启动失败：" + e.getMessage(),
                        com.tcl.csot.zhiye.service.ops.PlatformLogService.stackTrace(e), Map.of());
            }
            throw new TaskContext.Fail("SANDBOX_FAILED", "沙箱启动失败：" + e.getMessage());
        }
        if (ctx.cancelRequested) {
            ctx.runner.kill();
            if (clusterSandbox != null && sbxId != null) clusterSandbox.markClosed(sbxId, ctx.task, "cancelled_before_run");
            throw new TaskContext.Cancelled();
        }
    }

    private JsonNode call(TaskContext ctx, String cmd, ObjectNode args) throws Exception {
        ctx.checkCancelled();
        Duration remaining = Duration.between(Instant.now(), ctx.deadline);
        if (remaining.isNegative() || remaining.isZero()) throw timeout();
        Duration timeout = "prompt".equals(cmd) || "verify".equals(cmd) || "install".equals(cmd) ? remaining
                : Duration.ofSeconds(Math.min(remaining.toSeconds(), props.runner().commandTimeoutSeconds()));
        ctx.currentCommand = cmd;
        try {
            return ctx.runner.call(cmd, args, timeout);
        } catch (RunnerException.CommandFailed e) {
            if (ctx.cancelRequested) throw new TaskContext.Cancelled();
            throw new TaskContext.Fail(e.code(), e.getMessage());
        } catch (RunnerException e) {
            if (ctx.cancelRequested) throw new TaskContext.Cancelled();
            if (Instant.now().isAfter(ctx.deadline)) throw timeout();
            throw new TaskContext.Fail("RUNNER_ERROR", "执行环境出错：" + e.getMessage());
        } finally {
            ctx.currentCommand = null;
            flushText(ctx, true);
        }
    }

    /** 运行一轮 agent，返回 agent 的总结 */
    private String prompt(TaskContext ctx, String text) throws Exception {
        JsonNode r;
        try {
            r = call(ctx, "prompt", Json.obj().put("text", text));
        } catch (TaskContext.Fail f) {
            if ("MODEL_ERROR".equals(f.code) || "BUDGET_EXCEEDED".equals(f.code) || "PROVIDER_QUOTA".equals(f.code)) throw modelFailure(ctx, f.code, f.getMessage());
            throw f;
        }
        ctx.turns += r.path("turns").asInt(0);
        if (r.path("aborted").asBoolean(false)) {
            if (ctx.cancelRequested) throw new TaskContext.Cancelled();
            throw new TaskContext.Fail("MODEL_ERROR", "agent 被中断");
        }
        JsonNode err = r.get("error");
        if (err != null && !err.isNull()) {
            String msg = err.isTextual() ? err.asText() : err.path("message").asText("模型调用失败");
            String code = err.isObject() ? err.path("code").asText("MODEL_ERROR") : "MODEL_ERROR";
            // 到了步数检查点：不算失败，暂停下来问用户要不要继续；继续就在同一个 agent 会话里接着做
            if ("TURN_LIMIT".equals(code) || msg.startsWith("超过最大轮数")) return prompt(ctx, Prompts.keepGoing(awaitContinue(ctx)));
            throw modelFailure(ctx, code, msg);
        }
        ctx.checkCancelled();
        return r.path("text").asText("");
    }

    /**
     * 每做 maxAgentTurns 步停一下：发「要继续吗？」确认卡，等用户点「继续」或在输入框里说话（说的话当作补充要求一起带上）。
     * 「先到这里」或长时间没人回应：任务暂停为 PAUSED，已做的部分保留，重试会接着做。
     */
    private String awaitContinue(TaskContext ctx) throws Exception {
        ctx.checkCancelled();
        flushText(ctx, true);
        var wait = new java.util.concurrent.CompletableFuture<java.util.Optional<String>>();
        String id = "continue-" + (++ctx.continues);
        synchronized (ctx) {
            ctx.continueWait = wait;
            ctx.pendingConfirmId = id;
            ctx.pendingConfirmKind = "continue";
        }
        ObjectNode p = Json.obj().put("id", id).put("kind", "continue").put("state", "pending")
                .put("title", "已经连续做了 " + ctx.turns + " 步，还没做完，要继续吗？")
                .put("reason", "已做的部分都保留着。点「继续」，或者直接在输入框里说「继续」（也可以顺便补充要求），AI 会接着刚才的进度做。")
                .put("acceptLabel", "继续").put("declineLabel", "先到这里");
        List<String> progressItems = continueProgressItems(ctx.id());
        if (!progressItems.isEmpty()) {
            var arr = p.putArray("items");
            progressItems.forEach(arr::add);
        }
        events.emit(ctx.id(), "confirm", p);
        events.status(ctx.id(), "RUNNING", "等你确认是否继续", tasks.findById(ctx.id()).map(TaskRow::progress).orElse(50));
        java.util.Optional<String> answer;
        String state;
        try {
            answer = wait.get(Math.max(1, props.task().continueWaitMinutes()), java.util.concurrent.TimeUnit.MINUTES);
            state = answer == null ? "declined" : "accepted";
        } catch (java.util.concurrent.TimeoutException e) {
            answer = null;
            state = "timeout";
        } finally {
            synchronized (ctx) {
                ctx.continueWait = null;
                if (id.equals(ctx.pendingConfirmId)) ctx.pendingConfirmId = null;
                ctx.pendingConfirmKind = null;
            }
        }
        events.emit(ctx.id(), "confirm", Json.obj().put("id", id).put("kind", "continue").put("state", state));
        ctx.checkCancelled();
        if ("timeout".equals(state))
            throw new TaskContext.Fail("PAUSED", "等了 " + props.task().continueWaitMinutes() + " 分钟没有收到「继续」，先暂停在这里。已做的部分都保留着，点「重试」会接着做。");
        if (answer == null) throw new TaskContext.Fail("PAUSED", "按你的选择先停在这里。已做的部分都保留着，想接着做时点「重试」。");
        ctx.deadline = Instant.now().plus(Duration.ofMinutes(props.task().maxDurationMinutes()));
        events.status(ctx.id(), "RUNNING", "AI 正在继续编写", tasks.findById(ctx.id()).map(TaskRow::progress).orElse(50));
        return answer.orElse("");
    }

    /** 从 task.todos 提取本段已完成/进行中摘要，放在「继续」确认卡上帮助用户判断是否继续。 */
    private List<String> continueProgressItems(String taskId) {
        String raw = jdbc.sql("select todos::text from task where id = ?").param(taskId).query(String.class).optional().orElse(null);
        if (raw == null || raw.isBlank()) return List.of();
        JsonNode todos = Json.read(raw);
        if (!todos.isArray() || todos.isEmpty()) return List.of();
        List<String> done = new ArrayList<>();
        List<String> doing = new ArrayList<>();
        for (JsonNode item : todos) {
            String text = item.path("content").asText("").trim();
            if (text.isEmpty()) continue;
            String st = item.path("status").asText("");
            if ("completed".equals(st)) done.add(text);
            else if ("in_progress".equals(st)) doing.add(text);
        }
        List<String> out = new ArrayList<>();
        out.add("当前进度：已完成 " + done.size() + " / " + todos.size() + " 项待办");
        for (int i = Math.max(0, done.size() - 2); i < done.size(); i++) out.add("已完成：" + done.get(i));
        if (!doing.isEmpty()) out.add("正在做：" + doing.get(0));
        return out;
    }

    private TaskContext.Fail timeout() {
        return new TaskContext.Fail("TIMEOUT", "这一段运行超过了 " + props.task().maxDurationMinutes() + " 分钟还没结束（执行环境可能卡住了）。已做的部分都保留着，点「重试」会接着做。");
    }

    private TaskContext.Fail modelFailure(TaskContext ctx, String code, String detail) {
        // 额度用完（五星AI岛个人额度或供应商配额）：进度已留检查点，额度恢复后重试会接着做
        if ("PROVIDER_QUOTA".equals(code) || "BUDGET_EXCEEDED".equals(code)) {
            String msg = detail != null && detail.startsWith("你在五星AI岛") ? detail : QuotaText.exhausted(router.fivestar(), detail);
            return new TaskContext.Fail("PROVIDER_QUOTA", msg + (router.fivestar() ? "已做的部分都保留着，额度恢复后点「重试」会接着做。" : ""));
        }
        // 取不到模型路由（未登录、五星AI岛不可用、没有配置模型）：原样告诉用户
        if ("MODEL_ROUTE".equals(code)) return new TaskContext.Fail("MODEL_ROUTE", detail);
        return new TaskContext.Fail("MODEL_ERROR", "模型服务暂不可用，请稍后重试" + (detail == null || detail.isBlank() ? "" : "（" + truncate(detail, 200) + "）"));
    }

    private JsonNode verify(TaskContext ctx, String projectKind) throws Exception {
        return verify(ctx, null, -1, projectKind);
    }

    private JsonNode verify(TaskContext ctx, AppRow app, int round) throws Exception {
        return verify(ctx, app, round, app.projectKind());
    }

    private JsonNode verify(TaskContext ctx, AppRow app, int round, String projectKind) throws Exception {
        JsonNode v;
        try {
            v = call(ctx, "verify", Json.obj().put("projectKind", projectKind));
        } catch (TaskContext.Fail f) {
            if (!"RUNNER_ERROR".equals(f.code) && !"TIMEOUT".equals(f.code)) {
                ObjectNode n = Json.obj();
                n.put("ok", false);
                n.putArray("checks").addObject().put("name", "构建").put("ok", false).put("detail", f.getMessage());
                v = n;
            } else throw f;
        }
        if (faults.buildError()) {
            ObjectNode n = Json.obj();
            n.put("ok", false);
            ArrayNode checks = n.putArray("checks");
            checks.addObject().put("name", "类型检查").put("ok", true).put("detail", "");
            checks.addObject().put("name", "构建").put("ok", false).put("detail", "构建失败：注入的构建故障（开发环境故障开关 build=error）");
            v = n;
        }
        ArrayNode checks = v.path("checks").isArray() ? (ArrayNode) v.get("checks") : Json.M.createArrayNode();
        String shot = extractAndStripScreenshot(checks);
        if (round == 0 && shot != null && app != null && (app.isTemplateLike() || app.isService()) && visualCritique != null && v.path("ok").asBoolean(false)) {
            Optional<String> issue = visualCritique.review(ctx.id(), app, shot);
            if (issue.isPresent()) {
                if (v.isObject()) ((ObjectNode) v).put("ok", false);
                checks.addObject().put("name", "视觉自查").put("ok", false).put("detail", issue.get());
            } else {
                checks.addObject().put("name", "视觉自查").put("ok", true).put("detail", "");
            }
        }
        events.emit(ctx.id(), "verify", Map.of("checks", checks));
        return v;
    }

    private static String extractAndStripScreenshot(ArrayNode checks) {
        String shot = null;
        for (JsonNode c : checks) {
            if (c.isObject() && c.has("screenshot")) {
                if (shot == null && c.get("screenshot").isTextual()) shot = c.get("screenshot").asText();
                ((ObjectNode) c).remove("screenshot");
            }
        }
        return shot;
    }

    /** 校验通过后的一行结论：通过的检查名称拼在一起，跳过项单独列出。 */
    private static String checkSummary(JsonNode checks) {
        if (checks == null || !checks.isArray()) return "";
        StringBuilder ok = new StringBuilder();
        StringBuilder skipped = new StringBuilder();
        for (JsonNode c : checks) {
            String name = c.path("name").asText("").trim();
            if (name.isEmpty()) continue;
            if (c.path("skipped").asBoolean(false)) {
                if (!skipped.isEmpty()) skipped.append('、');
                skipped.append(name);
            } else if (c.path("ok").asBoolean(false)) {
                if (!ok.isEmpty()) ok.append('、');
                ok.append(name);
            }
        }
        if (ok.isEmpty() && skipped.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("检查：");
        if (!ok.isEmpty()) sb.append(ok).append("均通过");
        if (!skipped.isEmpty()) {
            if (!ok.isEmpty()) sb.append("；");
            sb.append(skipped).append("已跳过");
        }
        return sb.toString();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.cloud.CloudService cloud;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private VisualCritiqueService visualCritique;

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.cloud.FunctionService functions;

    /** 工作区里有 functions/*.ts 时：逐个加载，确认能编译并有默认导出（不执行处理函数） */
    private JsonNode withFunctionCheck(TaskContext ctx, JsonNode v) {
        Path dir = ctx.workspace == null ? null : ctx.workspace.resolve("functions");
        if (dir == null || !Files.isDirectory(dir) || !functions.available()) return v;
        List<String> errors = new ArrayList<>();
        int n = 0;
        try (var s = Files.list(dir)) {
            for (Path f : s.sorted().toList()) {
                String file = f.getFileName().toString();
                if (!file.endsWith(".ts") || file.endsWith(".d.ts")) continue;
                String name = file.substring(0, file.length() - 3);
                n++;
                if (!com.tcl.csot.zhiye.service.cloud.FunctionService.NAME.matcher(name).matches()) { errors.add(file + "：文件名只能用小写字母、数字、- 和 _"); continue; }
                String err = functions.check(name, Files.readString(f));
                if (err != null) errors.add(file + "：" + err);
            }
        } catch (Exception e) {
            errors.add("读取 functions/ 失败：" + e.getMessage());
        }
        if (n == 0 && errors.isEmpty()) return v;
        com.fasterxml.jackson.databind.node.ObjectNode out = v.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode checks = out.has("checks") && out.get("checks").isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) out.get("checks") : out.putArray("checks");
        com.fasterxml.jackson.databind.node.ObjectNode c = checks.addObject().put("name", "云函数").put("ok", errors.isEmpty());
        if (!errors.isEmpty()) {
            c.put("detail", String.join("；", errors));
            out.put("ok", false);
        }
        return out;
    }

    /** 工作区里有 cloud/schema.sql 时，平台不接受的写法当作一项校验失败，交给 agent 修 */
    private JsonNode withSchemaCheck(TaskContext ctx, JsonNode v) {
        Path f = ctx.workspace == null ? null : ctx.workspace.resolve("cloud/schema.sql");
        if (f == null || !Files.isRegularFile(f)) return v;
        String err;
        Path pf = ctx.workspace.resolve("cloud/policies.json");
        try { err = com.tcl.csot.zhiye.service.cloud.CloudService.checkSchema(Files.readString(f), Files.isRegularFile(pf) ? Files.readString(pf) : null); }
        catch (Exception e) { err = "读取 cloud/schema.sql 失败：" + e.getMessage(); }
        com.fasterxml.jackson.databind.node.ObjectNode out = v.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode checks = out.has("checks") && out.get("checks").isArray()
                ? (com.fasterxml.jackson.databind.node.ArrayNode) out.get("checks") : out.putArray("checks");
        com.fasterxml.jackson.databind.node.ObjectNode c = checks.addObject().put("name", "云数据库表结构").put("ok", err == null);
        if (err != null) {
            c.put("detail", err + (err.contains("policies.json") ? "" : "（只允许 create table / alter table add column / create index，类型用 text/int/bigint/real/boolean/date/timestamp/jsonb）"));
            out.put("ok", false);
        }
        return out;
    }

    static final String CLOUD_OFF_NOTE = "**注意：这一版用到了云数据库，但云服务现在是关闭的**，页面读写数据会报错。到「云服务」里重新启用后，会按 `cloud/schema.sql` 自动建表。";

    /** 提示插在 [[next]] 那一行前面（[[next]] 必须留在最后一行） */
    static String withNoteBeforeNext(String summary, String note) {
        java.util.regex.Matcher m = NEXT_LINE.matcher(summary);
        if (!m.find()) return summary + "\n\n" + note;
        return summary.substring(0, m.start()).stripTrailing() + "\n\n" + note + "\n\n" + summary.substring(m.start()).trim();
    }

    /**
     * 已启用云服务的应用：任务成功后按新版本的 cloud/schema.sql 补建表和列，不用再手动「重新启用」。
     * 新版本带了 schema.sql 但云服务没开（例如生成中被用户关掉了）：不建表，返回一句提示给回复，避免回复说「已存到云端」而实际读写都报错。
     */
    private String applyCloudSchema(TaskContext ctx, AppRow app) {
        if (cloud == null || app.isSkill()) return null;
        if (!cloud.isOn(app.id()))
            return ctx.workspace != null && Files.isRegularFile(ctx.workspace.resolve("cloud/schema.sql")) ? CLOUD_OFF_NOTE : null;
        try {
            int n = cloud.applySchema(app.id());
            if (n >= 0) {
                List<String> tnames = cloud.tables(app.id()).stream().map(m -> String.valueOf(m.get("name"))).toList();
                events.emit(ctx.id(), "tool", Map.of("id", "cloud-schema", "name", "cloud",
                        "summary", "更新云数据库表结构 · " + n + " 张表",
                        "detail", "同步表：" + String.join(", ", tnames),
                        "state", "end", "ok", true));
            }
        } catch (Exception e) {
            log.warn("任务 {} 应用云表结构失败：{}", ctx.id(), e.getMessage());
            events.emit(ctx.id(), "tool", Map.of("id", "cloud-schema", "name", "cloud",
                    "summary", "更新云数据库表结构失败：" + e.getMessage(),
                    "detail", com.tcl.csot.zhiye.service.ops.PlatformLogService.stackTrace(e),
                    "state", "end", "ok", false));
            return "⚠️ 云数据库表结构更新失败（已自动回滚，未产生半迁移状态）：" + e.getMessage() + "。请修正 `cloud/schema.sql` 后重试。";
        }
        return null;
    }

    /** 取最近几次版本演进摘要（跨任务保留演进轨迹） */
    private List<RepoMap.VersionNote> recentVersions(String appId) {
        return jdbc.sql("select number, source, title from version where app_id = ? order by number desc limit 3")
                .param(appId)
                .query((rs, i) -> new RepoMap.VersionNote(rs.getInt("number"), rs.getString("source"), rs.getString("title")))
                .list();
    }

    /** 取上一个任务之后、本次修改之前的最近几条工作台问答（task_id 为空），避免「先讨论再按讨论改」断片 */
    public String recentWorkbenchQa(String appId, TaskRow current) {
        if (current == null || current.createdAt() == null) return "";
        Instant after = jdbc.sql(
                        "select max(created_at) as ts from task where app_id = ? and id <> ? and status in ('SUCCEEDED','FAILED','CANCELLED') and created_at <= ?")
                .params(appId, current.id(), java.sql.Timestamp.from(current.createdAt()))
                .query((rs, i) -> Db.ts(rs, "ts")).optional().orElse(null);
        List<MessageRepository.MessageRow> rows = (after == null
                ? jdbc.sql("select id, app_id, role, content, status, created_at from message where app_id = ? and task_id is null and status = 'ok' and role in ('user','assistant') and created_at <= ? order by created_at desc, id desc limit 6")
                        .params(appId, java.sql.Timestamp.from(current.createdAt()))
                : jdbc.sql("select id, app_id, role, content, status, created_at from message where app_id = ? and task_id is null and status = 'ok' and role in ('user','assistant') and created_at > ? and created_at <= ? order by created_at desc, id desc limit 6")
                        .params(appId, java.sql.Timestamp.from(after), java.sql.Timestamp.from(current.createdAt())))
                .query((rs, i) -> new MessageRepository.MessageRow(rs.getString("id"), rs.getString("app_id"), rs.getString("role"), rs.getString("content"),
                        null, null, rs.getString("status"), Db.ts(rs, "created_at"), null, null, false))
                .list();
        if (rows.isEmpty()) return "";
        List<MessageRepository.MessageRow> ordered = new ArrayList<>(rows);
        Collections.reverse(ordered);
        StringBuilder sb = new StringBuilder("\n\n## 工作台最近讨论记录（先问答后修改，供参考）\n");
        int remaining = 2400;
        for (MessageRepository.MessageRow m : ordered) {
            String content = m.content() == null ? "" : m.content().trim();
            if (content.isEmpty()) continue;
            String body = com.tcl.csot.zhiye.service.chat.AskService.cap(content, Math.min(600, remaining));
            if (body.length() < content.length()) body += "…";
            String line = "- " + ("assistant".equals(m.role()) ? "助手" : "用户") + "：" + body + "\n";
            if (line.length() > remaining) break;
            sb.append(line);
            remaining -= line.length();
        }
        return remaining == 2400 ? "" : sb.toString();
    }

    private static final java.util.regex.Pattern NEXT_LINE = java.util.regex.Pattern.compile("(?m)^\\s*\\[\\[next\\]\\].*$");

    /** 正文 + 检查结果（含自动修复说明）+ 末尾一行 [[next]]；正文里模型自己写的「检查结果」小节去掉，以系统校验为准 */
    static String composeSummary(String summary, List<String> fixes, String checkLine) {
        java.util.regex.Matcher m = NEXT_LINE.matcher(summary);
        String next = null;
        while (m.find()) next = m.group().trim();
        for (String f : fixes) {
            java.util.regex.Matcher fm = NEXT_LINE.matcher(f);
            while (fm.find()) next = fm.group().trim();
        }
        String body = NEXT_LINE.matcher(summary).replaceAll("")
                .replaceAll("(?ms)^#{2,4}\\s*检查结果\\s*$.*?(?=^#{2,4}\\s|\\z)", "").trim();
        StringBuilder sb = new StringBuilder(body);
        StringBuilder check = new StringBuilder();
        if (!checkLine.isEmpty()) check.append(checkLine.replaceFirst("^检查：", ""));
        if (!fixes.isEmpty()) {
            if (!check.isEmpty()) check.append("。");
            check.append("中途校验未通过 ").append(fixes.size()).append(" 次，已自动修复：");
            String last = NEXT_LINE.matcher(fixes.get(fixes.size() - 1)).replaceAll("").trim().replaceAll("\\s*\\n+\\s*", " ");
            check.append(last.length() > 200 ? last.substring(0, 200) + "…" : last);
        }
        if (!check.isEmpty()) sb.append("\n\n### 检查结果\n").append(check);
        if (next != null) sb.append("\n\n").append(next);
        return sb.toString();
    }

    private static String failedSummary(JsonNode v) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : v.path("checks")) {
            if (c.path("ok").asBoolean(true)) continue;
            sb.append(c.path("name").asText()).append("：").append(truncate(c.path("detail").asText(""), 300)).append("；");
        }
        return sb.isEmpty() ? "未知错误" : sb.toString();
    }

    static String truncate(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0, n) + "…" : s; }

    /**
     * 对话修改的第一步：先把草稿（手动修改）写回源码、校验并保存成独立的版本（source=visual），再清空草稿。
     * 失败时任务直接失败、草稿保留、AI 不开始。返回按 locMap 改写后的元素上下文。
     */
    private JsonNode saveManualFirst(TaskContext ctx, AppRow app, JsonNode patches, JsonNode elementContext) throws Exception {
        phase(ctx, "RUNNING", "正在保存你的手动修改", 10);
        JsonNode r;
        try {
            r = applyVisual(ctx, app, patches);
            phase(ctx, "VERIFYING", "正在检查你的手动修改", 14);
            JsonNode v = verify(ctx, app.projectKind());
            if (!v.path("ok").asBoolean(false)) throw new TaskContext.Fail("VERIFY_FAILED", "检查没有通过：" + failedSummary(v));
        } catch (TaskContext.Fail f) {
            String reason = f.getMessage().replaceFirst("^可视化编辑没能写回源码：", "").replaceAll("[；。]+$", "");
            throw new TaskContext.Fail("MANUAL_SAVE_FAILED", "你的手动修改没能保存：" + reason + "。AI 修改没有开始，手动修改仍保留在草稿中。");
        }
        VersionRow mv = commit(ctx, app, "可视化编辑 · " + patches.size() + " 处修改", "visual", true, "visual");
        ctx.draftApplied = true;
        ctx.manualVersionNumber = mv.number();
        events.emit(ctx.id(), "agent_text", Map.of("delta", "已把你的手动修改保存为 V" + mv.number() + "。\n"));

        // 元素上下文：手动修改可能让行号偏移，按 runner 返回的 locMap 改写；被删除的元素明确告诉 agent
        JsonNode remapped = elementContext;
        if (elementContext != null && !elementContext.isNull()) {
            ObjectNode ec = elementContext.deepCopy();
            String loc = ec.path("loc").asText();
            JsonNode locMap = r.path("locMap");
            if (locMap.has(loc)) {
                if (locMap.get(loc).isNull()) ec.put("removedByDraft", true);
                else ec.put("loc", locMap.get(loc).asText());
            }
            for (JsonNode p : patches)
                if (loc.equals(p.path("loc").asText()) && p.path("removed").asBoolean(false) && !p.has("index")) ec.put("removedByDraft", true);
            remapped = ec;
        }
        // 记进任务输入：重试 / 继续从这个手动修改版本开始，且不再重复写回草稿
        ObjectNode newInput = ctx.task.input().deepCopy();
        newInput.remove("draftPatches");
        newInput.put("manualVersionId", mv.id());
        newInput.put("manualVersionNumber", mv.number());
        if (remapped != null && !remapped.isNull()) newInput.set("elementContext", remapped);
        jdbc.sql("update task set input = ?::jsonb where id = ?").params(Json.write(newInput), ctx.id()).update();
        return remapped;
    }

    private VersionRow commit(TaskContext ctx, AppRow app, String title, String source, boolean clearDraft) throws Exception {
        return commit(ctx, app, title, source, clearDraft, "main");
    }

    private VersionRow commit(TaskContext ctx, AppRow app, String title, String source, boolean clearDraft, String stage) throws Exception {
        phase(ctx, "COMMITTING", "visual".equals(stage) ? "正在保存你的手动修改" : "正在保存版本", "visual".equals(stage) ? 18 : 90);
        GitService.CommitResult c = git.commitWorkspace(app.id(), ctx.workspace, title + "\n\n织页任务 " + ctx.id(), ctx.id());
        if (app.noPreview() || app.isService()) // 技能包、脚本和单体后端项目没有静态前端构建产物，版本不关联 build
            return versionService.createForTask(app.id(), ctx.id(), stage, title, source, c.commit(), c.tree(), null, clearDraft);
        Path dist = ctx.workspace.resolve(".zhiye");
        VersionRepository.BuildRow build;
        try {
            build = versionService.ensureBuild(app.id(), c.tree(), dist.resolve("dist-preview"), dist.resolve("dist-publish"), app.visualEditable());
        } catch (IllegalStateException e) {
            throw new TaskContext.Fail("BUILD_MISSING", e.getMessage());
        }
        return versionService.createForTask(app.id(), ctx.id(), stage, title, source, c.commit(), c.tree(), build.id(), clearDraft);
    }

    // ================= runner 事件 → TaskEvent =================
    private void onRunnerEvent(TaskContext ctx, JsonNode ev) {
        if (clusterSandbox != null && ctx.sandboxId != null) clusterSandbox.touchActivity(ctx.sandboxId);
        String type = ev.path("type").asText();
        if (!"thinking".equals(type) && !"thinking_end".equals(type)) endThinking(ctx, null);
        switch (type) {
            case "thinking" -> thinking(ctx, ev.path("text").asText(""));
            case "thinking_end" -> endThinking(ctx, ev.hasNonNull("durationMs") ? ev.get("durationMs").asLong() : null);
            case "agent_text" -> {
                synchronized (ctx.textBuf) { ctx.textBuf.append(ev.path("delta").asText("")); }
                flushText(ctx, false);
            }
            case "tool" -> {
                flushText(ctx, true);
                ObjectNode p = Json.obj();
                p.put("id", ev.path("id").asText());
                p.put("name", ev.path("name").asText());
                p.put("summary", ev.path("summary").asText());
                p.put("state", ev.path("state").asText());
                if (ev.has("ok")) p.set("ok", ev.get("ok"));
                if (ev.hasNonNull("detail")) capDetail(p, ev.get("detail"));
                events.emit(ctx.id(), "tool", p);
            }
            case "confirm" -> { flushText(ctx, true); onConfirmEvent(ctx, ev); }
            case "todo" -> {
                flushText(ctx, true);
                JsonNode items = ev.path("items").isArray() ? ev.get("items") : Json.M.createArrayNode();
                jdbc.sql("update task set todos = ?::jsonb where id = ?").params(Json.write(items), ctx.id()).update();
                events.emit(ctx.id(), "todo", Map.of("items", items));
            }
            case "log" -> events.emit(ctx.id(), "log", Map.of("line", ev.path("line").asText()));
            case "turn" -> {
                int n = ev.path("n").asInt(0);
                if ("RUNNING".equals(tasks.status(ctx.id()))) {
                    int prog = Math.min(65, 20 + n * 3);
                    jdbc.sql("update task set progress = greatest(progress, ?) where id = ? and status = 'RUNNING'").params(prog, ctx.id()).update();
                }
            }
            default -> events.emit(ctx.id(), "log", Map.of("line", ev.toString()));
        }
    }

    static final int THINKING_MAX_CHARS = 200_000;

    /** 思考块：第一段文字时建一行事件，之后原地追加（每 500ms 写一次库），增量实时推给 SSE */
    private void thinking(TaskContext ctx, String chunk) {
        if (chunk.isEmpty()) return;
        synchronized (ctx.thinking) {
            long now = System.currentTimeMillis();
            if (ctx.thinkingSeq == 0) {
                flushText(ctx, true);
                ctx.thinking.setLength(0);
                ctx.thinkingStart = now;
                ctx.thinkingSeq = events.emit(ctx.id(), "thinking", thinkingPayload("", false, null));
                ctx.thinkingFlushed = now;
                ctx.thinkingPersistedLen = 0;
            }
            if (ctx.thinking.length() >= THINKING_MAX_CHARS) return;
            int offset = ctx.thinking.length();
            ctx.thinking.append(chunk);
            events.thinkingDelta(ctx.id(), new TaskEvents.ThinkingDelta(ctx.thinkingSeq, offset, chunk));
            if (now - ctx.thinkingFlushed > 500) {
                events.updatePayload(ctx.id(), ctx.thinkingSeq, thinkingPayload(ctx.thinking.toString(), false, null));
                ctx.thinkingFlushed = now;
                ctx.thinkingPersistedLen = ctx.thinking.length();
            }
        }
    }

    private void endThinking(TaskContext ctx, Long durationMs) {
        synchronized (ctx.thinking) {
            if (ctx.thinkingSeq == 0) return;
            long d = durationMs != null ? durationMs : System.currentTimeMillis() - ctx.thinkingStart;
            int blockSeq = ctx.thinkingSeq;
            events.updatePayload(ctx.id(), blockSeq, thinkingPayload(ctx.thinking.toString(), true, d));
            events.endLiveThinking(ctx.id(), blockSeq);
            ctx.thinkingSeq = 0;
            ctx.thinking.setLength(0);
            events.emit(ctx.id(), "thinking_end", Map.of("blockSeq", blockSeq, "durationMs", d));
        }
    }

    private static ObjectNode thinkingPayload(String text, boolean done, Long durationMs) {
        ObjectNode p = Json.obj();
        p.put("text", text);
        p.put("done", done);
        if (durationMs != null) p.put("durationMs", durationMs);
        return p;
    }

    static final int DETAIL_MAX_BYTES = 16 * 1024;

    /** 工具结果详情最多存 16 KB：文字截断，结构化内容超限时改成截断后的 JSON 文本 */
    static void capDetail(ObjectNode p, JsonNode detail) {
        String json = Json.write(detail);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= DETAIL_MAX_BYTES) { p.set("detail", detail); return; }
        if (detail.isTextual()) p.put("detail", utf8Prefix(detail.asText(), DETAIL_MAX_BYTES - 64));
        else if (detail.isObject()) {
            ObjectNode d = detail.deepCopy();
            // 先截断对象里最长的文字字段
            String longest = null;
            int max = 0;
            for (var it = d.fields(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue().isTextual() && e.getValue().asText().length() > max) { max = e.getValue().asText().length(); longest = e.getKey(); }
            }
            if (longest != null) {
                int other = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length - d.get(longest).asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                d.put(longest, utf8Prefix(d.get(longest).asText(), Math.max(0, DETAIL_MAX_BYTES - other - 64)));
            }
            if (Json.write(d).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= DETAIL_MAX_BYTES) p.set("detail", d);
            else p.put("detail", utf8Prefix(json, DETAIL_MAX_BYTES - 64));
        } else p.put("detail", utf8Prefix(json, DETAIL_MAX_BYTES - 64));
        p.put("detailTruncated", true);
    }

    static String utf8Prefix(String s, int maxBytes) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return s;
        int n = maxBytes;
        while (n > 0 && (b[n] & 0xC0) == 0x80) n--;
        return new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8) + "…";
    }

    private void flushText(TaskContext ctx, boolean force) {
        String delta;
        synchronized (ctx.textBuf) {
            long now = System.currentTimeMillis();
            if (ctx.textBuf.isEmpty() || (!force && now - ctx.lastFlush < 300 && ctx.textBuf.length() < 400)) return;
            delta = ctx.textBuf.toString();
            ctx.textBuf.setLength(0);
            ctx.lastFlush = now;
        }
        events.emit(ctx.id(), "agent_text", Map.of("delta", delta));
    }

    /** 把任务编号放进日志上下文 */
    static final class MDCish {
        static void set(String id) { org.slf4j.MDC.put("task", id); }
        static void clear() { org.slf4j.MDC.remove("task"); }
    }
}
