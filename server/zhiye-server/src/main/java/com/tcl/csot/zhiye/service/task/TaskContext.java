package com.tcl.csot.zhiye.service.task;

import com.tcl.csot.zhiye.entity.TaskRow;

import com.tcl.csot.zhiye.sandbox.RunnerSession;

import java.nio.file.Path;
import java.time.Instant;

/** 一个正在执行的任务的进程内状态。 */
class TaskContext {
    final TaskRow task;
    /** 本段的运行时限（防止执行环境卡死）；用户点「继续」后重新计时 */
    volatile Instant deadline;
    volatile boolean cancelRequested;
    volatile RunnerSession runner;
    volatile String sandboxId;
    volatile String currentCommand;
    /** 等用户在确认卡上选择的请求（runner 的 request_confirm）：id 和类型 */
    volatile String pendingConfirmId;
    volatile String pendingConfirmKind;
    /** 到了步数检查点、等用户说「继续」：完成值是用户随「继续」补充的话（可为空），null 表示「先到这里」 */
    volatile java.util.concurrent.CompletableFuture<java.util.Optional<String>> continueWait;
    int continues;
    Path workspace;
    Path agentDir;
    String sessionFile;
    boolean prepared;
    boolean draftApplied;
    /** 对话修改先保存的手动修改版本号（0 = 没有） */
    int manualVersionNumber;
    boolean ownsDirs = true;
    int turns;
    /** 编码代理刚调过 verify_app 且通过、之后未再修改文件，服务端可直接复用该次校验结果避免重复构建 */
    volatile boolean freshVerifyOk;
    volatile com.fasterxml.jackson.databind.JsonNode freshVerifyChecks;
    final StringBuilder textBuf = new StringBuilder();
    /** 当前思考块：事件行号、已累计的文字、开始时间、上次写库时间 */
    int thinkingSeq;
    final StringBuilder thinking = new StringBuilder();
    long thinkingStart;
    long thinkingFlushed;
    int thinkingPersistedLen;
    long lastFlush = System.currentTimeMillis();

    TaskContext(TaskRow task, Instant deadline) {
        this.task = task;
        this.deadline = deadline;
    }

    String id() { return task.id(); }

    void checkCancelled() throws Cancelled {
        if (cancelRequested) throw new Cancelled();
    }

    static class Cancelled extends Exception {
        Cancelled() { super("cancelled", null, false, false); }
    }

    static class Fail extends Exception {
        final String code;
        Fail(String code, String message) { super(message); this.code = code; }
    }
}
