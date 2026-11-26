package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.config.ServiceRuntimeProperties;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.repo.GitService;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 生产 K8s 单体后端运行时（zhiye.service-runtime.provider=k8s）：
 * 使用 Fabric8 KubernetesClient 编排 Kaniko 无特权构建 Job + Deployment + Service + Secret + PVC + NetworkPolicy，
 * 支持闲置自动缩容到 0（replicas=0）与按需唤醒（replicas=1 并等待 Ready）。
 */
@Component
@ConditionalOnProperty(name = "zhiye.service-runtime.provider", havingValue = "k8s")
public class KubernetesServiceRuntimeProvider implements ServiceRuntimeProvider {
    private static final Logger log = LoggerFactory.getLogger(KubernetesServiceRuntimeProvider.class);

    private final KubernetesClient client;
    private final ServiceRuntimeProperties props;
    private final Path runtimeDir;

    @Autowired
    public KubernetesServiceRuntimeProvider(KubernetesClient client, ServiceRuntimeProperties props, ZhiyeProperties zhiyeProps) {
        this.client = client;
        this.props = props;
        this.runtimeDir = zhiyeProps != null && zhiyeProps.runtimePath() != null ? zhiyeProps.runtimePath() : Path.of("runtime");
    }

    public KubernetesServiceRuntimeProvider(KubernetesClient client, ServiceRuntimeProperties props) {
        this(client, props, null);
    }

    @Override
    public String name() { return "k8s"; }

    @Override
    public DeployResult deploy(DeploySpec spec) throws Exception {
        String ns = props.namespace();
        String name = K8sServiceResourceFactory.resourceName(spec.slug());
        String imageRef = K8sServiceResourceFactory.imageRef(props, spec.slug(), spec.versionId());
        StringBuilder buildLog = new StringBuilder();
        buildLog.append("[k8s] 目标命名空间=").append(ns).append(" 镜像=").append(imageRef).append("\n");

        // 1. 若配置了 Harbor 镜像仓，在独立构建命名空间执行 Kaniko 构建 Job（并将版本源码与 Dockerfile 暂存至共享构建卷）
        if (!props.harborRegistry().isBlank()) {
            String buildNs = props.buildNamespace();
            String jobName = (name + "-bld-" + Long.toString(System.currentTimeMillis(), 36));
            if (jobName.length() > 63) jobName = jobName.substring(0, 63).replaceAll("-+$", "");
            Path stageDir = runtimeDir.resolve("k8s-builds").resolve(jobName);
            stageBuildContext(spec, stageDir);
            try {
                String contextUrl = "dir:///workspace";
                Job job = K8sServiceResourceFactory.buildKanikoJob(props, spec, jobName, imageRef, contextUrl);
                client.batch().v1().jobs().inNamespace(buildNs).resource(job).create();
                waitJobComplete(buildNs, jobName, Duration.ofSeconds(props.buildTimeoutSeconds()), buildLog);
            } finally {
                GitService.deleteRecursively(stageDir);
            }
        } else {
            buildLog.append("[k8s] 未配置 harborRegistry，跳过 Kaniko 推送，直接使用目标镜像引用\n");
        }

        // 2. 应用 Secret、可选 PVC、Service、NetworkPolicy、Deployment（兼容真实集群与 Fabric8 MockServer）
        var secret = K8sServiceResourceFactory.buildEnvSecret(props, spec);
        if (client.secrets().inNamespace(ns).withName(secret.getMetadata().getName()).get() == null) {
            client.secrets().inNamespace(ns).resource(secret).create();
        } else {
            client.secrets().inNamespace(ns).resource(secret).update();
        }
        if (spec.pvcEnabled()) {
            var existingPvc = client.persistentVolumeClaims().inNamespace(ns).withName(name + "-data").get();
            if (existingPvc == null) {
                client.persistentVolumeClaims().inNamespace(ns).resource(K8sServiceResourceFactory.buildPvc(props, spec)).create();
            }
        }
        var svc = K8sServiceResourceFactory.buildService(props, spec);
        var existingSvc = client.services().inNamespace(ns).withName(name).get();
        if (existingSvc == null) {
            client.services().inNamespace(ns).resource(svc).create();
        } else {
            if (existingSvc.getSpec() != null && existingSvc.getSpec().getClusterIP() != null) {
                svc.getSpec().setClusterIP(existingSvc.getSpec().getClusterIP());
            }
            client.services().inNamespace(ns).resource(svc).update();
        }
        if (props.networkPolicyEnabled()) {
            var netpol = K8sServiceResourceFactory.buildNetworkPolicy(props, spec);
            if (client.network().v1().networkPolicies().inNamespace(ns).withName(netpol.getMetadata().getName()).get() == null) {
                client.network().v1().networkPolicies().inNamespace(ns).resource(netpol).create();
            } else {
                client.network().v1().networkPolicies().inNamespace(ns).resource(netpol).update();
            }
        }
        var deployment = K8sServiceResourceFactory.buildDeployment(props, spec, imageRef, 1);
        if (client.apps().deployments().inNamespace(ns).withName(name).get() == null) {
            client.apps().deployments().inNamespace(ns).resource(deployment).create();
        } else {
            client.apps().deployments().inNamespace(ns).resource(deployment).update();
        }

        waitDeploymentReady(ns, name, Duration.ofSeconds(props.wakeTimeoutSeconds()));
        String endpoint = "http://" + name + "." + ns + ".svc.cluster.local";
        buildLog.append("[k8s] 部署就绪，内部服务地址: ").append(endpoint).append("\n");
        return new DeployResult(endpoint, imageRef, buildLog.toString());
    }

    @Override
    public DeployResult deployPrebuilt(DeploySpec spec, String existingImageRef, Path artifactDir) throws Exception {
        if (existingImageRef == null || existingImageRef.isBlank()) {
            return deploy(spec);
        }
        String ns = props.namespace();
        String name = K8sServiceResourceFactory.resourceName(spec.slug());
        StringBuilder buildLog = new StringBuilder();
        buildLog.append("[k8s] 复用已构建通过的镜像（0 重建晋升/回滚）: ").append(existingImageRef).append("\n");

        var secret = K8sServiceResourceFactory.buildEnvSecret(props, spec);
        if (client.secrets().inNamespace(ns).withName(secret.getMetadata().getName()).get() == null) {
            client.secrets().inNamespace(ns).resource(secret).create();
        } else {
            client.secrets().inNamespace(ns).resource(secret).update();
        }
        if (spec.pvcEnabled()) {
            var existingPvc = client.persistentVolumeClaims().inNamespace(ns).withName(name + "-data").get();
            if (existingPvc == null) {
                client.persistentVolumeClaims().inNamespace(ns).resource(K8sServiceResourceFactory.buildPvc(props, spec)).create();
            }
        }
        var svc = K8sServiceResourceFactory.buildService(props, spec);
        var existingSvc = client.services().inNamespace(ns).withName(name).get();
        if (existingSvc == null) {
            client.services().inNamespace(ns).resource(svc).create();
        } else {
            if (existingSvc.getSpec() != null && existingSvc.getSpec().getClusterIP() != null) {
                svc.getSpec().setClusterIP(existingSvc.getSpec().getClusterIP());
            }
            client.services().inNamespace(ns).resource(svc).update();
        }
        if (props.networkPolicyEnabled()) {
            var netpol = K8sServiceResourceFactory.buildNetworkPolicy(props, spec);
            if (client.network().v1().networkPolicies().inNamespace(ns).withName(netpol.getMetadata().getName()).get() == null) {
                client.network().v1().networkPolicies().inNamespace(ns).resource(netpol).create();
            } else {
                client.network().v1().networkPolicies().inNamespace(ns).resource(netpol).update();
            }
        }
        var deployment = K8sServiceResourceFactory.buildDeployment(props, spec, existingImageRef, 1);
        if (client.apps().deployments().inNamespace(ns).withName(name).get() == null) {
            client.apps().deployments().inNamespace(ns).resource(deployment).create();
        } else {
            client.apps().deployments().inNamespace(ns).resource(deployment).update();
        }
        waitDeploymentReady(ns, name, Duration.ofSeconds(props.wakeTimeoutSeconds()));
        String endpoint = "http://" + name + "." + ns + ".svc.cluster.local";
        buildLog.append("[k8s] 滚动切换就绪，内部服务地址: ").append(endpoint).append("\n");
        return new DeployResult(endpoint, existingImageRef, buildLog.toString());
    }

    @Override
    public String wake(DeploySpec spec, String imageRef) throws Exception {
        String ns = props.namespace();
        String name = K8sServiceResourceFactory.resourceName(spec.slug());
        var dep = client.apps().deployments().inNamespace(ns).withName(name);
        if (dep.get() == null) {
            return deploy(spec).endpointUrl();
        }
        dep.scale(1);
        waitDeploymentReady(ns, name, Duration.ofSeconds(props.wakeTimeoutSeconds()));
        return "http://" + name + "." + ns + ".svc.cluster.local";
    }

    @Override
    public void sleep(String appId, String slug) {
        String name = K8sServiceResourceFactory.resourceName(slug);
        var dep = client.apps().deployments().inNamespace(props.namespace()).withName(name);
        if (dep.get() != null) dep.scale(0);
    }

    @Override
    public void stop(String appId, String slug) {
        sleep(appId, slug);
    }

    @Override
    public void destroy(String appId, String slug) {
        String ns = props.namespace();
        String name = K8sServiceResourceFactory.resourceName(slug);
        client.apps().deployments().inNamespace(ns).withName(name).delete();
        client.services().inNamespace(ns).withName(name).delete();
        client.secrets().inNamespace(ns).withName(name + "-env").delete();
        client.network().v1().networkPolicies().inNamespace(ns).withName(name + "-netpol").delete();
        client.persistentVolumeClaims().inNamespace(ns).withName(name + "-data").delete();
    }

    @Override
    public String logs(String appId, String slug, int tail) {
        try {
            List<Pod> pods = client.pods().inNamespace(props.namespace()).withLabel("zhiye/app-id", appId).list().getItems();
            if (pods.isEmpty()) return "";
            String podName = pods.get(0).getMetadata().getName();
            int lines = Math.max(1, Math.min(tail <= 0 ? props.maxLogLines() : tail, 2000));
            String text = client.pods().inNamespace(props.namespace()).withName(podName).tailingLines(lines).getLog();
            return text == null ? "" : text;
        } catch (Exception e) {
            log.warn("读取 K8s Pod 日志失败 app={}: {}", appId, e.getMessage());
            return "";
        }
    }

    @Override
    public boolean isRunning(String appId, String slug) {
        String name = K8sServiceResourceFactory.resourceName(slug);
        Deployment d = client.apps().deployments().inNamespace(props.namespace()).withName(name).get();
        if (d == null || d.getStatus() == null) return false;
        Integer ready = d.getStatus().getReadyReplicas();
        return ready != null && ready > 0;
    }

    private void stageBuildContext(DeploySpec spec, Path stageDir) throws IOException {
        Files.createDirectories(stageDir);
        if (spec.sourceDir() != null && Files.isDirectory(spec.sourceDir())) {
            try (var stream = Files.walk(spec.sourceDir())) {
                for (Path src : (Iterable<Path>) stream::iterator) {
                    Path rel = spec.sourceDir().relativize(src);
                    if (rel.toString().isEmpty()) continue;
                    String first = rel.getName(0).toString();
                    if (".git".equals(first) || "node_modules".equals(first)) continue;
                    Path dst = stageDir.resolve(rel);
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else if (Files.isRegularFile(src)) {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        if (spec.dockerfile() != null && !spec.dockerfile().isBlank()) {
            Files.writeString(stageDir.resolve("Dockerfile"), spec.dockerfile(), StandardCharsets.UTF_8);
        }
        NpmTarballMirror.rewriteLocks(stageDir, props.npmTarballCdn());
    }

    private void waitJobComplete(String ns, String jobName, Duration timeout, StringBuilder buildLog) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Job j = client.batch().v1().jobs().inNamespace(ns).withName(jobName).get();
            if (j != null && j.getStatus() != null) {
                if (j.getStatus().getSucceeded() != null && j.getStatus().getSucceeded() > 0) {
                    appendJobLogs(ns, jobName, buildLog);
                    return;
                }
                if (j.getStatus().getFailed() != null && j.getStatus().getFailed() > 0) {
                    appendJobLogs(ns, jobName, buildLog);
                    throw new IllegalStateException("K8s 镜像构建失败（Job=" + jobName + "）\n" + buildLog);
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("K8s 镜像构建超时（Job=" + jobName + "）");
    }

    private void appendJobLogs(String ns, String jobName, StringBuilder out) {
        try {
            List<Pod> pods = client.pods().inNamespace(ns).withLabel("job-name", jobName).list().getItems();
            if (!pods.isEmpty()) {
                String l = client.pods().inNamespace(ns).withName(pods.get(0).getMetadata().getName()).getLog();
                if (l != null) out.append(l).append('\n');
            }
        } catch (Exception ignored) {}
    }

    private void waitDeploymentReady(String ns, String name, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Deployment d = client.apps().deployments().inNamespace(ns).withName(name).get();
            if (d != null && d.getStatus() != null && d.getStatus().getReadyReplicas() != null && d.getStatus().getReadyReplicas() > 0) {
                return;
            }
            Thread.sleep(300);
        }
        throw new IllegalStateException("K8s 服务启动就绪超时（Deployment=" + name + "）");
    }
}
