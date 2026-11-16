package com.tcl.csot.zhiye.service.task;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内信号：唤醒工作进程、通知正在执行的任务停止、转交确认和补充。跨实例的经 task_signal 表由 TaskWorker 心跳转交。 */
@Component
public class TaskSignals {
    private final Map<String, Runnable> cancelHandlers = new ConcurrentHashMap<>();
    private final Object wake = new Object();
    private final Map<String, java.util.function.Function<String, Boolean>> steerHandlers = new ConcurrentHashMap<>();
    private final Map<String, java.nio.file.Path> workspaces = new ConcurrentHashMap<>();

    public void onSteer(String taskId, java.util.function.Function<String, Boolean> handler) { steerHandlers.put(taskId, handler); }

    /** 交给正在执行该任务的 agent；不在 agent 阶段时返回 false */
    public boolean steer(String taskId, String text) {
        var h = steerHandlers.get(taskId);
        return h != null && Boolean.TRUE.equals(h.apply(text));
    }

    /** 确认卡的处理：confirmId、是否接受、用户随确认输入的话（「继续」时可以顺便补充要求，可为空） */
    public interface ConfirmHandler { boolean answer(String confirmId, boolean accept, String text); }

    private final Map<String, ConfirmHandler> confirmHandlers = new ConcurrentHashMap<>();

    public void onConfirm(String taskId, ConfirmHandler handler) { confirmHandlers.put(taskId, handler); }

    /** 用户在确认卡上的选择；没有对应的等待中确认时返回 false */
    public boolean confirm(String taskId, String confirmId, boolean accept) { return confirm(taskId, confirmId, accept, null); }

    public boolean confirm(String taskId, String confirmId, boolean accept, String text) {
        var h = confirmHandlers.get(taskId);
        return h != null && h.answer(confirmId, accept, text);
    }

    /** 任务是不是在本实例里执行（不是的话确认、补充要走数据库转给执行它的实例） */
    public boolean isLocal(String taskId) { return confirmHandlers.containsKey(taskId); }

    public void setWorkspace(String taskId, java.nio.file.Path dir) { if (dir != null) workspaces.put(taskId, dir); }

    public java.nio.file.Path workspace(String taskId) { return workspaces.get(taskId); }

    public void onCancel(String taskId, Runnable handler) { cancelHandlers.put(taskId, handler); }

    public void remove(String taskId) { cancelHandlers.remove(taskId); steerHandlers.remove(taskId); confirmHandlers.remove(taskId); workspaces.remove(taskId); }

    public boolean signalCancel(String taskId) {
        Runnable r = cancelHandlers.get(taskId);
        if (r != null) { r.run(); return true; }
        return false;
    }

    public void wakeWorker() { synchronized (wake) { wake.notifyAll(); } }

    public void awaitWork(long ms) throws InterruptedException { synchronized (wake) { wake.wait(ms); } }
}
