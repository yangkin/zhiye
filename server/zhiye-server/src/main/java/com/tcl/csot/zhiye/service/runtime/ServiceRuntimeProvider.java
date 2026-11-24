package com.tcl.csot.zhiye.service.runtime;

import java.nio.file.Path;
import java.util.Map;

/**
 * 单体后端应用运行时抽象：本地开发/E2E 模式（LocalServiceRuntimeProvider）与生产 K8s 模式（KubernetesServiceRuntimeProvider）共用同一契约。
 */
public interface ServiceRuntimeProvider {
    String name();

    record DeploySpec(
            String appId,
            String slug,
            String versionId,
            String runtime,
            String framework,
            String frontendDir,
            String backendDir,
            String buildCmd,
            String startCmd,
            int port,
            String healthPath,
            int memoryMb,
            int cpuMillicores,
            boolean pvcEnabled,
            int pvcSizeGi,
            Path sourceDir,
            String dockerfile,
            Map<String, String> envVars
    ) {}

    record DeployResult(String endpointUrl, String imageRef, String buildLog) {}

    /** 构建并启动应用；返回可供网关反代的内部 endpointUrl 与构建日志。 */
    DeployResult deploy(DeploySpec spec) throws Exception;

    /** 从休眠（0 副本）或停止态重新拉起已部署版本，等待健康就绪后返回 endpointUrl。 */
    String wake(DeploySpec spec, String imageRef) throws Exception;

    /** 闲置缩容到 0（保留持久化卷与构建产物，下次访问可秒级唤醒）。 */
    void sleep(String appId, String slug) throws Exception;

    /** 停止运行实例。 */
    void stop(String appId, String slug) throws Exception;

    /** 销毁所有运行态资源（应用删除或下线清理）。 */
    void destroy(String appId, String slug) throws Exception;

    /** 获取最近 tail 行运行日志。 */
    String logs(String appId, String slug, int tail);

    /** 当前实例是否存活。 */
    boolean isRunning(String appId, String slug);
}
