package com.tcl.csot.zhiye.sandbox.opensandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * 公司 OpenSandbox 平台配置（前缀 zhiye.sandbox.opensandbox.*），只在 provider=opensandbox 时使用。
 * 每个任务一个沙箱；工作区和 agent 目录通过同一块 RWX 卷（NAS）的 subPath 挂进去。
 */
@ConfigurationProperties("zhiye.sandbox.opensandbox")
public record OpenSandboxProperties(
        /** OpenSandbox Server 域名，如 opensandbox-server.opensandbox-system.svc:80 */
        String domain,
        @DefaultValue("http") String protocol,
        /** 读取 API key 的环境变量名（值只放 Secret，不进配置和日志） */
        @DefaultValue("ZHIYE_OPENSANDBOX_API_KEY") String apiKeyEnv,
        /** runner 镜像 */
        @DefaultValue("zhiye-runner:dev") String image,
        @DefaultValue("/opt/zhiye/bin/start-runner.sh") String entrypoint,
        /** 容器内 runner 控制端口 */
        @DefaultValue("8790") int controlPort,
        /** 容器内挂载点 */
        @DefaultValue("/workspace") String workspaceMount,
        @DefaultValue("/agent") String agentMount,
        /** NAS 上的运行时根目录（工作进程视角），工作区/agent 目录必须在其下 */
        String volumeRoot,
        /** OpenSandbox 命名空间里指向同一块 NAS 的 PVC 名称 */
        String pvcName,
        /** runner 访问模型网关的地址；为空时用 zhiye.llm.gateway-url 或本机网关 */
        String gatewayUrl,
        /** 导入任务安装依赖用的 npm 镜像域名；为空则不额外放行 */
        String npmRegistry,
        @DefaultValue("2") String cpu,
        @DefaultValue("4Gi") String memory,
        @DefaultValue("2") String cpuRequest,
        @DefaultValue("4Gi") String memoryRequest,
        @DefaultValue("5Gi") String ephemeralStorage,
        @DefaultValue("/usr/bin/chromium") String chromePath,
        @DefaultValue("/opt/zhiye/packages/vite-plugin-loc/index.mjs") String locPlugin,
        @DefaultValue("/opt/zhiye/packages/editor-bridge/bridge.js") String bridgePath,
        @DefaultValue("120") int startupTimeoutSeconds,
        /** 沙箱 TTL = 任务最长时长 + 余量 */
        @DefaultValue("300") int deadlineMarginSeconds,
        /** 是否给沙箱设置默认拒绝的出网策略；本地 kind 里集群 DNS 会被 sidecar 拦掉，测试时可关掉 */
        @DefaultValue("true") boolean networkPolicyEnabled,
        @DefaultValue("10001") long runAsUser,
        @DefaultValue("true") boolean reaperEnabled,
        @DefaultValue("60") int reaperIntervalSeconds,
        @DefaultValue("120") int reaperGraceSeconds,
        @DefaultValue Map<String, String> extraEnv) {

    public String effectiveGatewayUrl(String fallback) {
        return gatewayUrl != null && !gatewayUrl.isBlank() ? gatewayUrl : fallback;
    }
}