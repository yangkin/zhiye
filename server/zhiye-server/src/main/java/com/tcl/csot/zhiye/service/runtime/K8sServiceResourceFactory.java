package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.config.ServiceRuntimeProperties;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.*;

import java.util.*;

/**
 * 为单体后端应用生成生产安全加固的 Kubernetes 资源对象：
 * - Kaniko 无特权构建 Job（推内网 Harbor，支持内网 PyPI / npm / Maven / GoProxy 镜像源）
 * - Secret（密文环境变量 + DATABASE_URL）
 * - PersistentVolumeClaim（按需挂载 /data）
 * - Deployment（非 root、drop ALL capabilities、CPU/内存限额、健康探针）
 * - Service（ClusterIP）
 * - NetworkPolicy（严格隔离：仅允许织页网关入站，禁止 Pod 间互访与扫描内网）
 */
public final class K8sServiceResourceFactory {
    private K8sServiceResourceFactory() {}

    public static String resourceName(String slug) {
        String clean = slug.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("^-+|-+$", "");
        if (clean.isEmpty()) clean = "app";
        String full = "zhiye-svc-" + clean;
        return full.length() > 54 ? full.substring(0, 54).replaceAll("-+$", "") : full;
    }

    public static Map<String, String> labels(String appId, String slug) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("app.kubernetes.io/managed-by", "zhiye");
        m.put("zhiye/component", "user-service");
        m.put("zhiye/app-id", appId);
        m.put("zhiye/slug", slug);
        return m;
    }

    public static String imageRef(ServiceRuntimeProperties props, String slug, String versionTag) {
        String reg = props.harborRegistry().replaceAll("/+$", "");
        String tag = (versionTag == null || versionTag.isBlank() ? "latest" : versionTag)
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        return (reg.isEmpty() ? "zhiye-local" : reg) + "/zhiye-app-" + slug + ":" + tag;
    }

    public static Job buildKanikoJob(ServiceRuntimeProperties props, ServiceRuntimeProvider.DeploySpec spec,
                                     String jobName, String imageRef, String contextUrl) {
        List<String> args = new ArrayList<>();
        args.add("--dockerfile=Dockerfile");
        args.add("--context=" + contextUrl);
        args.add("--destination=" + imageRef);
        if (!props.npmRegistry().isBlank()) args.add("--build-arg=NPM_REGISTRY=" + props.npmRegistry());
        if (!props.pipIndexUrl().isBlank()) args.add("--build-arg=PIP_INDEX_URL=" + props.pipIndexUrl());
        if (!props.mavenMirrorUrl().isBlank()) args.add("--build-arg=MAVEN_MIRROR_URL=" + props.mavenMirrorUrl());
        if (!props.goProxy().isBlank()) args.add("--build-arg=GOPROXY=" + props.goProxy());
        args.addAll(props.kanikoExtraArgs());

        List<Volume> volumes = new ArrayList<>();
        List<VolumeMount> mounts = new ArrayList<>();
        if (!props.harborSecretName().isBlank()) {
            volumes.add(new VolumeBuilder()
                    .withName("docker-config")
                    .withNewSecret()
                    .withSecretName(props.harborSecretName())
                    .withItems(new KeyToPathBuilder().withKey(".dockerconfigjson").withPath("config.json").build())
                    .endSecret()
                    .build());
            mounts.add(new VolumeMountBuilder().withName("docker-config").withMountPath("/kaniko/.docker").withReadOnly(true).build());
        }

        return new JobBuilder()
                .withNewMetadata()
                .withName(jobName)
                .withNamespace(props.namespace())
                .withLabels(labels(spec.appId(), spec.slug()))
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(0)
                .withActiveDeadlineSeconds((long) props.buildTimeoutSeconds())
                .withTtlSecondsAfterFinished(600)
                .withNewTemplate()
                .withNewMetadata().withLabels(labels(spec.appId(), spec.slug())).endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withAutomountServiceAccountToken(false)
                .addNewContainer()
                .withName("kaniko")
                .withImage(props.kanikoImage())
                .withArgs(args)
                .withVolumeMounts(mounts)
                .withNewResources()
                .addToRequests("cpu", new Quantity("500m"))
                .addToRequests("memory", new Quantity("1Gi"))
                .addToLimits("cpu", new Quantity("2000m"))
                .addToLimits("memory", new Quantity("3Gi"))
                .endResources()
                .endContainer()
                .withVolumes(volumes)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    public static Secret buildEnvSecret(ServiceRuntimeProperties props, ServiceRuntimeProvider.DeploySpec spec) {
        Map<String, String> data = new LinkedHashMap<>();
        if (spec.envVars() != null) data.putAll(spec.envVars());
        data.put("PORT", String.valueOf(spec.port()));
        data.put("SERVER_PORT", String.valueOf(spec.port()));
        data.put("DATA_DIR", "/data");
        return new SecretBuilder()
                .withNewMetadata()
                .withName(resourceName(spec.slug()) + "-env")
                .withNamespace(props.namespace())
                .withLabels(labels(spec.appId(), spec.slug()))
                .endMetadata()
                .withType("Opaque")
                .withStringData(data)
                .build();
    }

    public static PersistentVolumeClaim buildPvc(ServiceRuntimeProperties props, ServiceRuntimeProvider.DeploySpec spec) {
        var pvcSpec = new PersistentVolumeClaimSpecBuilder()
                .withAccessModes("ReadWriteOnce")
                .withNewResources()
                .addToRequests("storage", new Quantity(Math.max(1, spec.pvcSizeGi()) + "Gi"))
                .endResources();
        if (!props.storageClass().isBlank()) pvcSpec.withStorageClassName(props.storageClass());
        return new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                .withName(resourceName(spec.slug()) + "-data")
                .withNamespace(props.namespace())
                .withLabels(labels(spec.appId(), spec.slug()))
                .endMetadata()
                .withSpec(pvcSpec.build())
                .build();
    }

    public static Deployment buildDeployment(ServiceRuntimeProperties props, ServiceRuntimeProvider.DeploySpec spec, String imageRef, int replicas) {
        String name = resourceName(spec.slug());
        Map<String, String> lbl = labels(spec.appId(), spec.slug());
        int cpu = Math.max(100, spec.cpuMillicores());
        int mem = Math.max(128, spec.memoryMb());
        int port = spec.port() > 0 ? spec.port() : 8080;
        String health = spec.healthPath() == null || spec.healthPath().isBlank() ? "/" : spec.healthPath();

        List<Volume> volumes = new ArrayList<>();
        List<VolumeMount> mounts = new ArrayList<>();
        if (spec.pvcEnabled()) {
            volumes.add(new VolumeBuilder()
                    .withName("data")
                    .withNewPersistentVolumeClaim(name + "-data", false)
                    .build());
        } else {
            volumes.add(new VolumeBuilder().withName("data").withNewEmptyDir().endEmptyDir().build());
        }
        mounts.add(new VolumeMountBuilder().withName("data").withMountPath("/data").build());

        List<LocalObjectReference> pullSecrets = props.harborSecretName().isBlank()
                ? List.of()
                : List.of(new LocalObjectReference(props.harborSecretName()));

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(props.namespace())
                .withLabels(lbl)
                .endMetadata()
                .withNewSpec()
                .withReplicas(replicas)
                .withNewSelector().withMatchLabels(Map.of("zhiye/app-id", spec.appId())).endSelector()
                .withNewTemplate()
                .withNewMetadata().withLabels(lbl).endMetadata()
                .withNewSpec()
                .withAutomountServiceAccountToken(false)
                .withImagePullSecrets(pullSecrets)
                .withNewSecurityContext()
                .withRunAsNonRoot(true)
                .withRunAsUser(10001L)
                .withRunAsGroup(10001L)
                .withFsGroup(10001L)
                .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
                .endSecurityContext()
                .addNewContainer()
                .withName("app")
                .withImage(imageRef)
                .withImagePullPolicy("IfNotPresent")
                .addNewPort().withContainerPort(port).withName("http").endPort()
                .addNewEnvFrom().withNewSecretRef(name + "-env", false).endEnvFrom()
                .withVolumeMounts(mounts)
                .withNewSecurityContext()
                .withAllowPrivilegeEscalation(false)
                .withNewCapabilities().addToDrop("ALL").endCapabilities()
                .endSecurityContext()
                .withNewResources()
                .addToRequests("cpu", new Quantity(Math.max(50, cpu / 4) + "m"))
                .addToRequests("memory", new Quantity(Math.max(64, mem / 2) + "Mi"))
                .addToLimits("cpu", new Quantity(cpu + "m"))
                .addToLimits("memory", new Quantity(mem + "Mi"))
                .endResources()
                .withNewReadinessProbe()
                .withNewHttpGet().withPath(health).withNewPort(port).endHttpGet()
                .withInitialDelaySeconds(2).withPeriodSeconds(3).withTimeoutSeconds(2).withFailureThreshold(15)
                .endReadinessProbe()
                .withNewLivenessProbe()
                .withNewHttpGet().withPath(health).withNewPort(port).endHttpGet()
                .withInitialDelaySeconds(15).withPeriodSeconds(15).withTimeoutSeconds(3).withFailureThreshold(3)
                .endLivenessProbe()
                .endContainer()
                .withVolumes(volumes)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    public static Service buildService(ServiceRuntimeProperties props, ServiceRuntimeProvider.DeploySpec spec) {
        String name = resourceName(spec.slug());
        int port = spec.port() > 0 ? spec.port() : 8080;
        return new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(props.namespace())
                .withLabels(labels(spec.appId(), spec.slug()))
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")
                .withSelector(Map.of("zhiye/app-id", spec.appId()))
                .addNewPort()
                .withName("http")
                .withPort(80)
                .withNewTargetPort(port)
                .endPort()
                .endSpec()
                .build();
    }

    /** 严格网络隔离策略：只允许织页网关入站；出站仅放行 DNS 与显式白名单 CIDR（防止 Pod 间互访或扫描公司内网）。 */
    public static NetworkPolicy buildNetworkPolicy(ServiceRuntimeProperties props, ServiceRuntimeProvider.DeploySpec spec) {
        String name = resourceName(spec.slug()) + "-netpol";
        List<NetworkPolicyEgressRule> egress = new ArrayList<>();
        // 放行集群 DNS (UDP/TCP 53)
        egress.add(new NetworkPolicyEgressRuleBuilder()
                .addNewPort().withProtocol("UDP").withNewPort(53).endPort()
                .addNewPort().withProtocol("TCP").withNewPort(53).endPort()
                .build());
        // 放行白名单 CIDR（如平台 PostgreSQL / 指定内网网关）
        for (String cidr : props.egressAllowedCidrs()) {
            if (cidr == null || cidr.isBlank()) continue;
            egress.add(new NetworkPolicyEgressRuleBuilder()
                    .addNewTo().withNewIpBlock().withCidr(cidr.trim()).endIpBlock().endTo()
                    .build());
        }
        return new NetworkPolicyBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(props.namespace())
                .withLabels(labels(spec.appId(), spec.slug()))
                .endMetadata()
                .withNewSpec()
                .withNewPodSelector().withMatchLabels(Map.of("zhiye/app-id", spec.appId())).endPodSelector()
                .withPolicyTypes("Ingress", "Egress")
                .addNewIngress()
                .addNewFrom()
                .withNewNamespaceSelector().withMatchLabels(Map.of("kubernetes.io/metadata.name", props.gatewayPodNamespace())).endNamespaceSelector()
                .withNewPodSelector().withMatchLabels(Map.of(props.gatewayPodLabelKey(), props.gatewayPodLabelValue())).endPodSelector()
                .endFrom()
                .addNewPort().withProtocol("TCP").withNewPort(spec.port() > 0 ? spec.port() : 8080).endPort()
                .endIngress()
                .withEgress(egress)
                .endSpec()
                .build();
    }
}
