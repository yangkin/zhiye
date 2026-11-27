package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.config.ServiceRuntimeProperties;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 4 语言标准 Dockerfile 生成、安全加固 K8s 资源模板及 K8s RuntimeProvider 完整编排（部署/缩容休眠/唤醒/日志/销毁）。 */
@EnableKubernetesMockClient(crud = true)
class K8sServiceRuntimeTest {
    KubernetesClient client;

    private ServiceRuntimeProperties k8sProps() {
        return new ServiceRuntimeProperties(
                "k8s", "zhiye-apps", "", "harbor-secret",
                "harbor.csot.internal/kaniko:v1.23", List.of("--cache=true"),
                "harbor.csot.internal/python:3.11-slim", "harbor.csot.internal/node:22-alpine",
                "harbor.csot.internal/maven:3.9-temurin-21", "harbor.csot.internal/temurin:21-jre",
                "harbor.csot.internal/golang:1.23-alpine", "harbor.csot.internal/alpine:3.20",
                "https://npm.csot.internal", "https://pypi.csot.internal/simple",
                "https://maven.csot.internal/repository/public", "https://goproxy.csot.internal",
                "csi-nas", true, "zhiye", "app.kubernetes.io/part-of", "zhiye",
                List.of("10.96.0.10/32"), 20, 60, 5, 120, 200,
                1024, 1000, 5, 5, false, 5, 2048, 2000, 131072, 64000, "zhiye-builds", "zhiye-runtime",
                "https://cdn.npmmirror.com/packages", "http://zhiye-api.zhiye.svc:8080");
    }

    @Test
    void generatesHardenedDockerfilesForAllFourLanguagesAndFullStack() {
        var props = k8sProps();
        String py = DockerfileGenerator.generate(new ServiceRuntimeDetector.Spec("python", "Python (FastAPI)", "frontend", "backend",
                "pip install -r requirements.txt", "python3 -m uvicorn main:app --host 0.0.0.0 --port ${PORT}", 8080, "/health", 512, 500), props);
        assertThat(py).contains("AS frontend-builder").contains("harbor.csot.internal/python:3.11-slim")
                .contains("PIP_INDEX_URL=https://pypi.csot.internal/simple").contains("USER 10001");

        String node = DockerfileGenerator.generate(new ServiceRuntimeDetector.Spec("node", "Node.js (Express)", "", "",
                "npm run build", "npm start", 8080, "/", 512, 500), props);
        assertThat(node).contains("harbor.csot.internal/node:22-alpine").contains("NPM_REGISTRY=https://npm.csot.internal")
                .contains("NPM_TARBALL_CDN=https://cdn.npmmirror.com/packages").contains("npm install --package-lock-only").contains("USER 10001");

        String java = DockerfileGenerator.generate(new ServiceRuntimeDetector.Spec("java", "Java (Spring Boot)", "", "",
                "mvn -B -DskipTests package", "java -jar app.jar --server.port=${PORT}", 8080, "/actuator/health", 1024, 1000), props);
        assertThat(java).contains("harbor.csot.internal/maven:3.9-temurin-21").contains("harbor.csot.internal/temurin:21-jre")
                .contains("MAVEN_MIRROR_URL=https://maven.csot.internal/repository/public").contains("/root/.m2/settings.xml").contains("USER 10001");

        String go = DockerfileGenerator.generate(new ServiceRuntimeDetector.Spec("go", "Go (Gin)", "", "",
                "go build -o app .", "./app", 8080, "/healthz", 256, 500), props);
        assertThat(go).contains("harbor.csot.internal/golang:1.23-alpine").contains("GOPROXY=https://goproxy.csot.internal").contains("CGO_ENABLED=0").contains("USER 10001");
    }

    @Test
    void k8sResourceFactoryProducesHardenedManifestsAndNetworkPolicy() {
        var props = k8sProps();
        var spec = new ServiceRuntimeProvider.DeploySpec(
                "app_demo1", "yield-api", "ver_1", "python", "Python (FastAPI)", "", "",
                "", "uvicorn main:app", 8080, "/health", 512, 500, true, 2,
                Path.of("."), "FROM python:3.11-slim", Map.of("DATABASE_URL", "postgresql://u:p@pg/db"));

        Job job = K8sServiceResourceFactory.buildKanikoJob(props, spec, "zhiye-svc-yield-api-bld", "harbor.csot.internal/zhiye-app-yield-api:ver-1", "dir:///workspace");
        assertThat(job.getMetadata().getNamespace()).isEqualTo("zhiye-builds");
        assertThat(job.getSpec().getTemplate().getSpec().getAutomountServiceAccountToken()).isFalse();
        assertThat(job.getSpec().getTemplate().getSpec().getSecurityContext().getSeccompProfile().getType()).isEqualTo("RuntimeDefault");
        assertThat(job.getSpec().getTemplate().getSpec().getContainers().get(0).getVolumeMounts())
                .anySatisfy(vm -> {
                    assertThat(vm.getMountPath()).isEqualTo("/workspace");
                    assertThat(vm.getSubPath()).isEqualTo("k8s-builds/zhiye-svc-yield-api-bld");
                    assertThat(vm.getReadOnly()).isTrue();
                });
        assertThat(job.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs())
                .contains("--build-arg=PIP_INDEX_URL=https://pypi.csot.internal/simple", "--build-arg=GOPROXY=https://goproxy.csot.internal",
                        "--build-arg=NPM_TARBALL_CDN=https://cdn.npmmirror.com/packages");

        Deployment dep = K8sServiceResourceFactory.buildDeployment(props, spec, "harbor.csot.internal/zhiye-app-yield-api:ver-1", 1);
        var podSec = dep.getSpec().getTemplate().getSpec().getSecurityContext();
        var cSec = dep.getSpec().getTemplate().getSpec().getContainers().get(0).getSecurityContext();
        assertThat(podSec.getRunAsNonRoot()).isTrue();
        assertThat(podSec.getRunAsUser()).isEqualTo(10001L);
        assertThat(cSec.getAllowPrivilegeEscalation()).isFalse();
        assertThat(cSec.getCapabilities().getDrop()).contains("ALL");
        assertThat(dep.getSpec().getTemplate().getSpec().getVolumes().get(0).getPersistentVolumeClaim().getClaimName()).isEqualTo("zhiye-svc-yield-api-data");

        NetworkPolicy np = K8sServiceResourceFactory.buildNetworkPolicy(props, spec);
        assertThat(np.getSpec().getPolicyTypes()).containsExactly("Ingress", "Egress");
        assertThat(np.getSpec().getIngress().get(0).getFrom().get(0).getNamespaceSelector().getMatchLabels())
                .containsEntry("kubernetes.io/metadata.name", "zhiye");
        assertThat(np.getSpec().getEgress()).hasSize(3); // DNS + 织页平台连接器网关(8080/8083) + 白名单 CIDR
        assertThat(np.getSpec().getEgress().get(1).getTo().get(0).getNamespaceSelector().getMatchLabels())
                .containsEntry("kubernetes.io/metadata.name", "zhiye");
        assertThat(props.resolvedInternalApiBase()).isEqualTo("http://zhiye-api.zhiye.svc:8080");
        assertThat(props.resolvedInternalApiBase()).doesNotContain("127.0.0.1");
    }

    @Test
    void k8sProviderLifecycleDeploySleepWakeAndDestroy() throws Exception {
        var props = k8sProps();
        var provider = new KubernetesServiceRuntimeProvider(client, props);
        var spec = new ServiceRuntimeProvider.DeploySpec(
                "app_k8s_1", "k8s-demo", "ver_1", "go", "Go", "", "",
                "go build -o app .", "./app", 8080, "/health", 256, 500, true, 1,
                Path.of("."), "FROM alpine", Map.of("API_KEY", "secret-val"));

        // 预先在后台线程模拟 K8s 控制器将 Deployment 标记为 Ready
        String depName = K8sServiceResourceFactory.resourceName("k8s-demo");
        Thread controller = new Thread(() -> {
            for (int i = 0; i < 40; i++) {
                Deployment d = client.apps().deployments().inNamespace("zhiye-apps").withName(depName).get();
                if (d != null && d.getSpec().getReplicas() != null && d.getSpec().getReplicas() > 0) {
                    client.apps().deployments().inNamespace("zhiye-apps").withName(depName).editStatus(x ->
                            new DeploymentBuilder(x).editOrNewStatus().withReadyReplicas(1).withAvailableReplicas(1).endStatus().build());
                    break;
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
            }
        });
        controller.start();

        var res = provider.deploy(spec);
        controller.join(2000);
        assertThat(res.endpointUrl()).isEqualTo("http://zhiye-svc-k8s-demo.zhiye-apps.svc.cluster.local");
        assertThat(client.services().inNamespace("zhiye-apps").withName(depName).get()).isNotNull();
        assertThat(client.secrets().inNamespace("zhiye-apps").withName(depName + "-env").get()).isNotNull();
        assertThat(client.persistentVolumeClaims().inNamespace("zhiye-apps").withName(depName + "-data").get()).isNotNull();
        assertThat(client.network().v1().networkPolicies().inNamespace("zhiye-apps").withName(depName + "-netpol").get()).isNotNull();
        assertThat(provider.isRunning("app_k8s_1", "k8s-demo")).isTrue();

        // 模拟 Pod 存在并检查 sleep（缩容到 0）与 destroy（全量清理）
        client.pods().inNamespace("zhiye-apps").resource(new PodBuilder()
                .withNewMetadata().withName("zhiye-svc-k8s-demo-abc").withLabels(K8sServiceResourceFactory.labels("app_k8s_1", "k8s-demo")).endMetadata()
                .build()).create();

        provider.sleep("app_k8s_1", "k8s-demo");
        assertThat(client.apps().deployments().inNamespace("zhiye-apps").withName(depName).get().getSpec().getReplicas()).isEqualTo(0);

        // 唤醒时应刷新 Secret（含最新的 ZHIYE_CONNECTOR_API 与 ZHIYE_CONNECTOR_TOKEN）并拉起副本
        var updatedSpec = new ServiceRuntimeProvider.DeploySpec(
                "app_k8s_1", "k8s-demo", "ver_1", "go", "Go", "", "",
                "go build -o app .", "./app", 8080, "/health", 256, 500, true, 1,
                Path.of("."), "FROM alpine", Map.of(
                        "ZHIYE_CONNECTOR_API", "http://zhiye-api.zhiye.svc:8080/internal/app-connectors",
                        "ZHIYE_CONNECTOR_TOKEN", "zct1.app_k8s_1.sig"));
        Thread wakeCtrl = new Thread(() -> {
            for (int i = 0; i < 40; i++) {
                Deployment d = client.apps().deployments().inNamespace("zhiye-apps").withName(depName).get();
                if (d != null && d.getSpec().getReplicas() != null && d.getSpec().getReplicas() > 0) {
                    client.apps().deployments().inNamespace("zhiye-apps").withName(depName).editStatus(x ->
                            new DeploymentBuilder(x).editOrNewStatus().withReadyReplicas(1).withAvailableReplicas(1).endStatus().build());
                    break;
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
            }
        });
        wakeCtrl.start();
        provider.wake(updatedSpec, res.imageRef());
        wakeCtrl.join(2000);
        var refreshedSecret = client.secrets().inNamespace("zhiye-apps").withName(depName + "-env").get();
        assertThat(refreshedSecret.getStringData())
                .containsEntry("ZHIYE_CONNECTOR_API", "http://zhiye-api.zhiye.svc:8080/internal/app-connectors")
                .containsEntry("ZHIYE_CONNECTOR_TOKEN", "zct1.app_k8s_1.sig");

        provider.destroy("app_k8s_1", "k8s-demo");
        assertThat(client.apps().deployments().inNamespace("zhiye-apps").withName(depName).get()).isNull();
        assertThat(client.services().inNamespace("zhiye-apps").withName(depName).get()).isNull();
        assertThat(client.secrets().inNamespace("zhiye-apps").withName(depName + "-env").get()).isNull();
        assertThat(client.network().v1().networkPolicies().inNamespace("zhiye-apps").withName(depName + "-netpol").get()).isNull();
    }
}
