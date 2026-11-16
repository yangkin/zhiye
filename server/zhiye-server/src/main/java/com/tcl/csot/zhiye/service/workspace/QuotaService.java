package com.tcl.csot.zhiye.service.workspace;

import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.repository.TaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 个人（工作空间）配额：每日任务数、同时进行的任务数（每人 1 个）、应用数量（每人 10 个，已删除的不算）。 */
@Service
public class QuotaService {
    private final TaskRepository tasks;
    private final ZhiyeProperties props;
    private final JdbcClient jdbc;

    public QuotaService(TaskRepository tasks, ZhiyeProperties props, JdbcClient jdbc) {
        this.tasks = tasks;
        this.props = props;
        this.jdbc = jdbc;
    }

    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> locks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 同一工作空间的「检查配额 + 写入」串行执行，避免并发请求同时通过检查（单个 api 实例内有效；
     * 多实例部署时由 task_one_active_per_app 索引兜底同一应用，跨应用的并发上限可能短暂超出 1）。
     */
    public <T> T withWorkspaceLock(String ws, java.util.function.Supplier<T> body) {
        var lock = locks.computeIfAbsent(ws, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try { return body.get(); } finally { lock.unlock(); }
    }

    /** 创建或导入应用前检查数量上限。导入中（尚未显示）的应用也计入，失败或取消的导入会被删除、不计入。 */
    public void checkCanCreateApp(String ws) {
        long n = jdbc.sql("select count(*) from app where workspace_id = ? and deleted_at is null").param(ws).query(Long.class).single();
        int max = props.workspace().maxApps();
        if (n >= max)
            throw new ApiException(HttpStatus.CONFLICT, "APP_LIMIT", "每人最多 " + max + " 个应用（已删除的不算），请先删除不用的应用再创建");
    }

    /** 当前在用的应用数与上限 */
    public long[] appUsage(String ws) {
        long n = jdbc.sql("select count(*) from app where workspace_id = ? and deleted_at is null").param(ws).query(Long.class).single();
        return new long[]{n, props.workspace().maxApps()};
    }

    public void checkCanStart(String ws, boolean writeTask) {
        if (tasks.countCreatedToday(ws) >= props.workspace().dailyTaskLimit())
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "今天的任务次数已用完，请明天再试");
        int max = props.workspace().maxConcurrentTasks();
        if (tasks.countActiveAll(ws) >= max)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TASK_LIMIT",
                    "每人同时只能运行 " + max + " 个任务，请等当前任务完成或停止后再试");
    }
}
