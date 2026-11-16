package com.tcl.csot.zhiye.sandbox.k8s;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * Kubernetes 沙箱配置（前缀 zhiye.sandbox.k8s.*），只在 zhiye.sandbox.provider=k8s 时使用。
 * 每个任务一个 Pod，工作区通过同一块 RWX 卷的 subPath 挂进去，见 deploy/k8s/README.md。
 */
@ConfigurationProperties("zhiye.sandbox.k8s")
public record K8sSandboxProperties(
        /** 沙箱 Pod 所在命名空间 */
        @DefaultValue("zhiye-sandbox") String namespace,
        /** runner 镜像 */
        @DefaultValue("zhiye-runner:dev") String image,
        @DefaultValue("IfNotPresent") String imagePullPolicy,
        /** 拉镜像用的 Secret 名称（沙箱命名空间内） */
        @DefaultValue List<String> imagePullSecrets,
        /** RuntimeClass（gvisor / runv / kata）；为空不设置 */
        String runtimeClassName,
        /** 沙箱节点池的 nodeSelector */
        @DefaultValue Map<String, String> nodeSelector,
        /** 沙箱节点池污点对应的容忍 */
        @DefaultValue List<Toleration> tolerations,
        /** 沙箱命名空间里指向共享运行时卷（NAS）的 PVC */
        @DefaultValue("zhiye-runtime") String pvcName,
        /**
         * 工作进程本地看到的共享卷挂载点；工作区/agent 目录相对它算 subPath。
         * 为空时取 zhiye.runtime-dir（工作进程把整个运行时目录放在这块卷上）。
         */
        String volumeRoot,
        /** subPath 前缀映射：TaskExecutor 把目录放在别处时，用 "worker 路径前缀=卷内前缀" 改写 */
        @DefaultValue Map<String, String> subPathRewrites,
        /** runner 访问模型网关的集群内地址；为空时用 TaskExecutor 给的地址 */
        String gatewayUrl,
        @DefaultValue Resources resources,
        /** 等待 Pod Running 的最长时间 */
        @DefaultValue("120") int startupTimeoutSeconds,
        /** activeDeadlineSeconds = 任务最长时长 + 这个余量 */
        @DefaultValue("300") int deadlineMarginSeconds,
        @DefaultValue("10001") long runAsUser,
        @DefaultValue("10001") long fsGroup,
        /** /tmp emptyDir 上限（含 Chrome profile、npm 缓存） */
        @DefaultValue("2Gi") String tmpSizeLimit,
        /** 镜像内的 runner 启动脚本（修正 node_modules 链接后 exec node runner） */
        @DefaultValue("/opt/zhiye/bin/start-runner.sh") String startScript,
        @DefaultValue("/opt/zhiye/runner/src/main.mjs") String runnerScript,
        @DefaultValue("/opt/zhiye/template-cache/node_modules") String templateNodeModules,
        /** Vue 模板的预装依赖（工作进程链接到 {runtime}/vue-template-cache/node_modules 的工作区改指到这里） */
        @DefaultValue("/opt/zhiye/vue-template-cache/node_modules") String vueTemplateNodeModules,
        @DefaultValue("/opt/zhiye/packages/vite-plugin-loc/index.mjs") String locPlugin,
        @DefaultValue("/opt/zhiye/packages/editor-bridge/bridge.js") String bridgePath,
        @DefaultValue("/usr/bin/chromium") String chromePath,
        /** 导入任务（allow-net=1）用的 npm 镜像；为空不设置 */
        String npmRegistry,
        /** 其他附加环境变量 */
        @DefaultValue Map<String, String> extraEnv,
        /** 孤儿 Pod 回收 */
        @DefaultValue("true") boolean reaperEnabled,
        @DefaultValue("60") int reaperIntervalSeconds,
        /** 刚创建的 Pod 在这段时间内不回收（任务可能还没写进数据库或刚领取） */
        @DefaultValue("60") int reaperGraceSeconds,
        /**
         * 启动 runner 前确认 NetworkPolicy 已对这个 Pod 生效：在 Pod 里反复连 isolationCheckTarget（默认拒绝的集群内地址），
         * 直到连不上为止。很多 CNI 在 Pod Running 后要几百毫秒到几秒才下发策略（kind 实测约 1.3 秒），这段时间 Pod 可以出网。
         */
        @DefaultValue("true") boolean isolationCheck,
        @DefaultValue("kubernetes.default.svc:443") String isolationCheckTarget,
        @DefaultValue("30") int isolationCheckTimeoutSeconds,
        /** 启动时在共享卷上建空的 template-cache/node_modules，使 TaskExecutor 为 template 项目建链接 */
        @DefaultValue("true") boolean ensureTemplateCacheStub) {

    public record Toleration(String key, @DefaultValue("Equal") String operator, String value,
                             @DefaultValue("NoSchedule") String effect) {}

    public record Resources(@DefaultValue("2") String cpuRequest,
                            @DefaultValue("2") String cpuLimit,
                            @DefaultValue("4Gi") String memoryRequest,
                            @DefaultValue("4Gi") String memoryLimit,
                            @DefaultValue("5Gi") String ephemeralStorageLimit) {}
}
