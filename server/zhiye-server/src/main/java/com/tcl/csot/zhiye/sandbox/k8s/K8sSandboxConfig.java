package com.tcl.csot.zhiye.sandbox.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * zhiye.sandbox.provider=k8s 时创建 Kubernetes 客户端。
 * 集群内用 ServiceAccount 令牌（/var/run/secrets/kubernetes.io/serviceaccount），本机用 ~/.kube/config。
 * 客户端创建时不连接 API Server。
 */
@Configuration
@ConditionalOnProperty(name = "zhiye.sandbox.provider", havingValue = "k8s")
@EnableConfigurationProperties(K8sSandboxProperties.class)
public class K8sSandboxConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(KubernetesClient.class)
    public KubernetesClient zhiyeSandboxKubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
