package com.tcl.csot.zhiye.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;

/** 与一个 runner 进程的会话（stdin/stdout JSON 行协议，见 docs/runner-protocol.md）。 */
public interface RunnerSession extends AutoCloseable {
    /** 发送命令并等待回复；ok:false 时抛 {@link RunnerException.CommandFailed} */
    JsonNode call(String cmd, ObjectNode args, Duration timeout) throws RunnerException, InterruptedException;

    /** 发送一条可以在 prompt 执行期间插入的命令（如 steer），不等待回复。不支持时抛 UnsupportedOperationException */
    default void sendAsync(String cmd, ObjectNode args) {
        throw new UnsupportedOperationException("当前沙箱不支持 " + cmd);
    }

    /** 发送 abort（不等待回复） */
    void abort();

    /** 立即结束进程 */
    void kill();

    boolean alive();

    @Override
    void close();
}
