package com.tcl.csot.zhiye.service.runtime;

import java.nio.file.Path;
import java.util.Map;

/**
 * 单体后端应用运行时抽象：本地开发/E2E 模式（LocalServiceRuntimeProvider）与生产 K8s 模式（KubernetesServiceRuntimeProvider）共用同一契约。
 * Deploy Engine V2 新增：
 * - deployPrebuilt：复用已构建通过的镜像或本地产物快照，实现预览晋升线上（0 重建）与秒级回滚
 * - metrics：采集实时进程/容器运行指标（RSS 内存、CPU、端口、运行时长）
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

    record DeployResult(String endpointUrl, String imageRef, String buildLog, String artifactDir) {
        public DeployResult(String endpointUrl, String imageRef, String buildLog) {
            this(endpointUrl, imageRef, buildLog, null);
        }
    }

    record RuntimeMetrics(
            boolean running,
            Long pid,
            Integer port,
            Double rssMb,
            Double cpuPercent,
            Long uptimeSeconds,
            String provider
    ) {}

    /** 构建并启动应用；返回可供网关反代的内部 endpointUrl、镜像引用、构建日志与不可变产物目录。 */
    DeployResult deploy(DeploySpec spec) throws Exception;

    /** 免重建直接部署已构建通过的镜像（K8s）或不可变产物快照目录（Local），用于预览晋升线上与秒级回滚。 */
    default DeployResult deployPrebuilt(DeploySpec spec, String existingImageRef, Path artifactDir) throws Exception {
        return deploy(spec);
    }

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

    /** 采集当前运行实例的资源与存活指标。 */
    default RuntimeMetrics metrics(String appId, String slug) {
        return new RuntimeMetrics(isRunning(appId, slug), null, null, null, null, null, name());
    }
}
