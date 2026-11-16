package com.tcl.csot.zhiye.util;

/** 后台线程（Java 17 没有虚拟线程）：守护线程，和原来的虚拟线程一样不阻止进程退出。 */
public final class Threads {
    private Threads() {}

    public static Thread start(String name, Runnable task) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static Thread start(Runnable task) {
        return start("zhiye-worker", task);
    }
}
