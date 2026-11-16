package com.tcl.csot.zhiye.sandbox.http;

import java.time.Duration;

/** HTTP runner 背后的进程或远程沙箱；传输层只通过它判断存活和回收资源。 */
public interface HttpRunnerBackend {
    boolean alive();

    /** 等待 runner 正常退出；不支持等待的远程实现可以直接返回。 */
    default void awaitExit(Duration timeout) throws InterruptedException { }

    /** 正常关闭（远程实现通常删除沙箱）。 */
    void close();

    /** 立即强杀或删除。 */
    void kill();
}
