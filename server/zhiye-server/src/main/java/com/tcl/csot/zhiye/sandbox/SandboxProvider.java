package com.tcl.csot.zhiye.sandbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.function.Consumer;

/** 沙箱调度抽象：本地进程（+Seatbelt）；以后加 Kubernetes / Docker 实现。 */
public interface SandboxProvider {
    record Spec(String taskId, Path workspace, Path agentDir, boolean allowNet, String gatewayUrl, String taskToken,
                Path logFile, Consumer<JsonNode> onEvent) {}

    RunnerSession start(Spec spec) throws Exception;
}
