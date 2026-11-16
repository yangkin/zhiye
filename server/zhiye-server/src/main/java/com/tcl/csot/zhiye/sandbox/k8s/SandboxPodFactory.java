package com.tcl.csot.zhiye.sandbox.k8s;

import io.fabric8.kubernetes.api.model.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 生成沙箱 Pod 的规格（纯函数，便于测试）。
 * <ul>
 *   <li>只把 {@code workspaces/{id}} 挂到 /workspace、{@code agents/{id}} 挂到 /agent（同一个 RWX PVC 的 subPath）</li>
 *   <li>非 root（10001）、只读根文件系统、去掉所有 capability、seccomp RuntimeDefault、不挂 SA 令牌</li>
 *   <li>容器本身只是 {@code sleep} 到截止时间；runner 由工作进程通过 exec 启动</li>
 * </ul>
 */
final class SandboxPodFactory {
    static final String APP_LABEL = "app";
    static final String APP_VALUE = "zhiye-sandbox";
    static final String TASK_LABEL = "zhiye/task-id";
    static final String NET_LABEL = "zhiye/allow-net";
    static final String CREATED_ANNOTATION = "zhiye/deadline-seconds";
    static final String CONTAINER = "runner";
    static final String POD_WORKSPACE = "/workspace";
    static final String POD_AGENT = "/agent";

    private SandboxPodFactory() {}

    record Input(String podName, String taskId, String workspaceSubPath, String agentSubPath, boolean allowNet,
                 long deadlineSeconds, Map<String, String> env) {}

    /** Pod 名：sb-{任务编号，转成 DNS-1123}-{随机后缀} */
    static String podName(String taskId, String suffix) {
        String s = taskId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        if (s.length() > 48) s = s.substring(0, 48);
        return "sb-" + s + "-" + suffix;
    }

    /** 标签值：最长 63，只允许 [A-Za-z0-9_.-]，首尾为字母数字 */
    static String labelValue(String v) {
        String s = v.replaceAll("[^A-Za-z0-9_.-]", "-");
        if (s.length() > 63) s = s.substring(0, 63);
        return s.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
    }

    static Pod build(K8sSandboxProperties p, Input in) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(APP_LABEL, APP_VALUE);
        labels.put(TASK_LABEL, labelValue(in.taskId()));
        labels.put(NET_LABEL, in.allowNet() ? "1" : "0");

        List<EnvVar> env = new ArrayList<>();
        in.env().forEach((k, v) -> env.add(new EnvVar(k, v, null)));

        var res = p.resources();
        Map<String, Quantity> requests = new LinkedHashMap<>();
        requests.put("cpu", new Quantity(res.cpuRequest()));
        requests.put("memory", new Quantity(res.memoryRequest()));
        Map<String, Quantity> limits = new LinkedHashMap<>();
        limits.put("cpu", new Quantity(res.cpuLimit()));
        limits.put("memory", new Quantity(res.memoryLimit()));
        if (res.ephemeralStorageLimit() != null && !res.ephemeralStorageLimit().isBlank())
            limits.put("ephemeral-storage", new Quantity(res.ephemeralStorageLimit()));

        Container c = new ContainerBuilder()
                .withName(CONTAINER)
                .withImage(p.image())
                .withImagePullPolicy(p.imagePullPolicy())
                // 容器只负责「占住」Pod，到截止时间自己退出；runner 通过 exec 启动
                .withCommand("sleep", String.valueOf(in.deadlineSeconds()))
                .withWorkingDir(POD_WORKSPACE)
                .withEnv(env)
                .withNewResources().withRequests(requests).withLimits(limits).endResources()
                .withNewSecurityContext()
                    .withRunAsNonRoot(true)
                    .withRunAsUser(p.runAsUser())
                    .withRunAsGroup(p.runAsUser())
                    .withAllowPrivilegeEscalation(false)
                    .withReadOnlyRootFilesystem(true)
                    .withPrivileged(false)
                    .withNewCapabilities().withDrop("ALL").endCapabilities()
                    .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
                .endSecurityContext()
                .withVolumeMounts(
                        new VolumeMountBuilder().withName("runtime").withMountPath(POD_WORKSPACE).withSubPath(in.workspaceSubPath()).build(),
                        new VolumeMountBuilder().withName("runtime").withMountPath(POD_AGENT).withSubPath(in.agentSubPath()).build(),
                        new VolumeMountBuilder().withName("tmp").withMountPath("/tmp").build(),
                        // Chromium 冒烟测试需要比默认 64Mi 大一些的共享内存
                        new VolumeMountBuilder().withName("dshm").withMountPath("/dev/shm").build())
                .build();

        List<Toleration> tolerations = new ArrayList<>();
        for (var t : p.tolerations()) {
            tolerations.add(new TolerationBuilder().withKey(t.key()).withOperator(t.operator())
                    .withValue("Exists".equals(t.operator()) ? null : t.value()).withEffect(t.effect()).build());
        }

        PodSpecBuilder spec = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withActiveDeadlineSeconds(in.deadlineSeconds())
                .withAutomountServiceAccountToken(false)
                .withEnableServiceLinks(false)
                .withTerminationGracePeriodSeconds(0L)
                .withHostNetwork(false).withHostPID(false).withHostIPC(false)
                .withDnsPolicy("ClusterFirst")
                .withNodeSelector(p.nodeSelector().isEmpty() ? null : new LinkedHashMap<>(p.nodeSelector()))
                .withTolerations(tolerations.isEmpty() ? null : tolerations)
                .withNewSecurityContext()
                    .withRunAsNonRoot(true)
                    .withRunAsUser(p.runAsUser())
                    .withRunAsGroup(p.runAsUser())
                    .withFsGroup(p.fsGroup())
                    .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
                .endSecurityContext()
                .withContainers(c)
                .withVolumes(
                        new VolumeBuilder().withName("runtime")
                                .withNewPersistentVolumeClaim().withClaimName(p.pvcName()).endPersistentVolumeClaim().build(),
                        new VolumeBuilder().withName("tmp")
                                .withNewEmptyDir().withSizeLimit(new Quantity(p.tmpSizeLimit())).endEmptyDir().build(),
                        new VolumeBuilder().withName("dshm")
                                .withNewEmptyDir().withMedium("Memory").withSizeLimit(new Quantity("256Mi")).endEmptyDir().build());
        if (p.runtimeClassName() != null && !p.runtimeClassName().isBlank()) spec.withRuntimeClassName(p.runtimeClassName());
        if (!p.imagePullSecrets().isEmpty())
            spec.withImagePullSecrets(p.imagePullSecrets().stream().map(LocalObjectReference::new).toList());

        return new PodBuilder()
                .withNewMetadata()
                    .withName(in.podName())
                    .withNamespace(p.namespace())
                    .withLabels(labels)
                    .addToAnnotations(CREATED_ANNOTATION, String.valueOf(in.deadlineSeconds()))
                    .addToAnnotations("zhiye/task-id", in.taskId())
                .endMetadata()
                .withSpec(spec.build())
                .build();
    }
}
