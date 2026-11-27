package com.tcl.csot.zhiye.service.task;

import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.repository.TaskQueueRepository;
import com.tcl.csot.zhiye.repository.TaskRepository;

import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.MessageRepository;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.repository.TaskQueueRepository.QueuedMessage;
import com.tcl.csot.zhiye.service.workspace.QuotaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 任务中补充消息的持久队列；新 modify 任务、用户消息和 sent 标记在同一事务里落库。 */
@Service
public class TaskQueueService {
    private static final Set<String> QUEUEABLE = Set.of("generate", "modify");

    private final TaskQueueRepository queued;
    private final TaskRepository tasks;
    private final AppRepository apps;
    private final MessageRepository messages;
    private final QuotaService quota;
    private final TransactionTemplate tx;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TaskQueueService.class);
    @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy
    private com.tcl.csot.zhiye.service.cloud.CloudService cloud;

    public TaskQueueService(TaskQueueRepository queued, TaskRepository tasks, AppRepository apps,
                            MessageRepository messages, QuotaService quota, PlatformTransactionManager txManager) {
        this.queued = queued;
        this.tasks = tasks;
        this.apps = apps;
        this.messages = messages;
        this.quota = quota;
        this.tx = new TransactionTemplate(txManager);
    }

    public List<Map<String, Object>> list(String workspaceId, String appId) {
        apps.find(workspaceId, appId).orElseThrow(() -> ApiException.notFound("应用"));
        return queued.listPending(workspaceId, appId).stream().map(TaskQueueService::view).toList();
    }

    public Map<String, Object> enqueue(String workspaceId, String taskId, String content) {
        return enqueue(workspaceId, taskId, content, false);
    }

    /** enableCloud：轮到这条消息时先启用云服务再执行；撤回就不启用 */
    public Map<String, Object> enqueue(String workspaceId, String taskId, String content, boolean enableCloud) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) throw ApiException.validation("请输入要补充的内容");
        if (text.length() > 2000) throw ApiException.validation("补充内容最多 2000 字");
        QueuedMessage row = quota.withWorkspaceLock(workspaceId, () -> tx.execute(status -> {
            TaskRow task = tasks.find(workspaceId, taskId).orElseThrow(() -> ApiException.notFound("任务"));
            apps.find(workspaceId, task.appId()).orElseThrow(() -> ApiException.notFound("应用"));
            if (!task.active() || !QUEUEABLE.contains(task.type()))
                throw ApiException.conflict("NOT_QUEUEABLE", "只有进行中的生成或修改任务可以接收排队消息");
            return queued.insert(Ids.newId("queue"), task.id(), task.appId(), workspaceId, text, enableCloud);
        }));
        return view(row);
    }

    public void cancel(String workspaceId, String id) {
        queued.find(workspaceId, id).orElseThrow(() -> ApiException.notFound("排队消息"));
        quota.withWorkspaceLock(workspaceId, () -> tx.execute(status -> {
            if (!queued.cancelPending(workspaceId, id))
                throw ApiException.conflict("QUEUE_NOT_PENDING", "这条消息已经发送或撤回，不能再次撤回");
            return null;
        }));
    }

    /**
     * 每个终态最多消费一条。TaskService 通过参数传入，避免两个 Service 构造循环；
     * 创建失败（APP_BUSY / 配额等）会回滚本次事务，pending 行原样保留。
     */
    public void drainNext(TaskRow ended, TaskService taskService) {
        if (ended == null || !QUEUEABLE.contains(ended.type())) return;
        quota.withWorkspaceLock(ended.workspaceId(), () -> tx.execute(status -> {
            TaskRow current = tasks.findById(ended.id()).orElse(null);
            if (current == null || !current.terminal() || !QUEUEABLE.contains(current.type())) return null;
            var app = apps.find(current.workspaceId(), current.appId()).orElse(null);
            // 删除应用、已有新任务或手工继续/重试抢占时，不丢消息，也不强行创建。
            if (app == null || tasks.activeForApp(app.id()).isPresent()) return null;
            QueuedMessage next = queued.firstPendingForUpdate(current.workspaceId(), app.id()).orElse(null);
            if (next == null) return null;

            if (next.enableCloud()) {
                // 启用失败不挡住这条消息：AI 照样能写表结构，用户之后可以在「云服务」里重新启用
                try {
                    cloud.enable(current.workspaceId(), app.id());
                } catch (RuntimeException e) {
                    log.warn("排队消息 {} 启用云服务失败：{}", next.id(), e.getMessage());
                }
            }
            var input = Json.obj().put("content", next.content()).put("queuedMessageId", next.id());
            String baseVer = "SUCCEEDED".equals(current.status()) && current.resultVersionId() != null
                    ? current.resultVersionId()
                    : app.currentVersionId();
            TaskRow created = taskService.create(current.workspaceId(), app.id(), "modify", baseVer,
                    input, null, 1, current.id());
            messages.insert(app.id(), "user", next.content(), null, created.id(), "pending");
            if (!queued.markSent(current.workspaceId(), next.id(), created.id()))
                throw new IllegalStateException("排队消息状态已变化，取消本次自动续跑");
            apps.touch(app.id());
            return null;
        }));
    }

    public static Map<String, Object> view(QueuedMessage row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.id());
        m.put("taskId", row.taskId());
        m.put("appId", row.appId());
        m.put("content", row.content());
        m.put("status", row.status());
        m.put("createdAt", Db.iso(row.createdAt()));
        m.put("enableCloud", row.enableCloud());
        return m;
    }
}
