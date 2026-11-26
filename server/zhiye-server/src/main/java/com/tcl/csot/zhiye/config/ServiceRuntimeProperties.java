package com.tcl.csot.zhiye.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 单体后端应用托管配置（zhiye.service-runtime.*）。
 * - provider=local：本地开发与端到端测试模式，直接在本机隔离目录编译运行 Python / Node / Java / Go 单体进程
 * - provider=k8s：生产 K8s 模式，使用 Kaniko 构建 Job 推内网 Harbor，并创建受限 Deployment / Service / Secret / PVC / NetworkPolicy
 */
@ConfigurationProperties(prefix = "zhiye.service-runtime")
public record ServiceRuntimeProperties(
        String provider,
        String namespace,
        String harborRegistry,
        String harborSecretName,
        String kanikoImage,
        List<String> kanikoExtraArgs,
        String baseImagePython,
        String baseImageNode,
        String baseImageJavaBuild,
        String baseImageJavaRuntime,
        String baseImageGoBuild,
        String baseImageGoRuntime,
        String npmRegistry,
        String pipIndexUrl,
        String mavenMirrorUrl,
        String goProxy,
        String storageClass,
        boolean networkPolicyEnabled,
        String gatewayPodNamespace,
        String gatewayPodLabelKey,
        String gatewayPodLabelValue,
        List<String> egressAllowedCidrs,
        Integer defaultIdleSleepMinutes,
        int idleSweepSeconds,
        int wakeTimeoutSeconds,
        int buildTimeoutSeconds,
        int maxLogLines,
        Integer maxMemoryMb,
        Integer maxCpuMillicores,
        Integer maxPvcSizeGi,
        Integer minIdleSleepMinutes,
        Boolean allowNeverSleep,
        Integer maxServicesPerWorkspace,
        Integer maxWorkspaceRunningMemoryMb,
        Integer maxWorkspaceRunningCpuMillicores,
        Integer maxPlatformRunningMemoryMb,
        Integer maxPlatformRunningCpuMillicores,
        String buildNamespace,
        String buildContextPvc,
        String npmTarballCdn
) {
    public ServiceRuntimeProperties {
        if (provider == null || provider.isBlank()) provider = "local";
        if (namespace == null || namespace.isBlank()) namespace = "zhiye-apps";
        if (buildNamespace == null || buildNamespace.isBlank()) buildNamespace = "zhiye-builds";
        if (buildContextPvc == null || buildContextPvc.isBlank()) buildContextPvc = "zhiye-runtime";
        if (harborRegistry == null) harborRegistry = "";
        if (harborSecretName == null) harborSecretName = "harbor-registry-secret";
        if (kanikoImage == null || kanikoImage.isBlank()) kanikoImage = "gcr.io/kaniko-project/executor:v1.23.2";
        if (kanikoExtraArgs == null) kanikoExtraArgs = List.of("--cache=true", "--compressed-caching=false");
        if (baseImagePython == null || baseImagePython.isBlank()) baseImagePython = "python:3.11-slim";
        if (baseImageNode == null || baseImageNode.isBlank()) baseImageNode = "node:22-alpine";
        if (baseImageJavaBuild == null || baseImageJavaBuild.isBlank()) baseImageJavaBuild = "maven:3.9-eclipse-temurin-21-alpine";
        if (baseImageJavaRuntime == null || baseImageJavaRuntime.isBlank()) baseImageJavaRuntime = "eclipse-temurin:21-jre-alpine";
        if (baseImageGoBuild == null || baseImageGoBuild.isBlank()) baseImageGoBuild = "golang:1.23-alpine";
        if (baseImageGoRuntime == null || baseImageGoRuntime.isBlank()) baseImageGoRuntime = "alpine:3.20";
        if (npmRegistry == null) npmRegistry = "";
        if (npmTarballCdn == null) npmTarballCdn = "";
        if (pipIndexUrl == null) pipIndexUrl = "";
        if (mavenMirrorUrl == null) mavenMirrorUrl = "";
        if (goProxy == null) goProxy = "";
        if (storageClass == null) storageClass = "";
        if (gatewayPodNamespace == null || gatewayPodNamespace.isBlank()) gatewayPodNamespace = "zhiye";
        if (gatewayPodLabelKey == null || gatewayPodLabelKey.isBlank()) gatewayPodLabelKey = "app.kubernetes.io/part-of";
        if (gatewayPodLabelValue == null || gatewayPodLabelValue.isBlank()) gatewayPodLabelValue = "zhiye";
        if (egressAllowedCidrs == null) egressAllowedCidrs = List.of();
        if (defaultIdleSleepMinutes == null || defaultIdleSleepMinutes < 0) defaultIdleSleepMinutes = 2880;
        if (idleSweepSeconds <= 0) idleSweepSeconds = 60;
        if (wakeTimeoutSeconds <= 0) wakeTimeoutSeconds = 30;
        if (buildTimeoutSeconds <= 0) buildTimeoutSeconds = 600;
        if (maxLogLines <= 0) maxLogLines = 500;
        if (maxMemoryMb == null || maxMemoryMb <= 0) maxMemoryMb = 1024;
        if (maxCpuMillicores == null || maxCpuMillicores <= 0) maxCpuMillicores = 1000;
        if (maxPvcSizeGi == null || maxPvcSizeGi <= 0) maxPvcSizeGi = 5;
        if (minIdleSleepMinutes == null || minIdleSleepMinutes < 0) minIdleSleepMinutes = 5;
        if (allowNeverSleep == null) allowNeverSleep = false;
        if (maxServicesPerWorkspace == null || maxServicesPerWorkspace <= 0) maxServicesPerWorkspace = 5;
        if (maxWorkspaceRunningMemoryMb == null || maxWorkspaceRunningMemoryMb <= 0) maxWorkspaceRunningMemoryMb = 2048;
        if (maxWorkspaceRunningCpuMillicores == null || maxWorkspaceRunningCpuMillicores <= 0) maxWorkspaceRunningCpuMillicores = 2000;
        if (maxPlatformRunningMemoryMb == null || maxPlatformRunningMemoryMb <= 0) maxPlatformRunningMemoryMb = 131072;
        if (maxPlatformRunningCpuMillicores == null || maxPlatformRunningCpuMillicores <= 0) maxPlatformRunningCpuMillicores = 64000;
    }

    public boolean isK8s() { return "k8s".equalsIgnoreCase(provider); }
}
