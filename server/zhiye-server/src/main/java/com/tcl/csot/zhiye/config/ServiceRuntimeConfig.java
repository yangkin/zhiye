package com.tcl.csot.zhiye.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ServiceRuntimeProperties.class)
public class ServiceRuntimeConfig {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "zhiye.service-runtime.provider", havingValue = "k8s")
    @ConditionalOnMissingBean(KubernetesClient.class)
    public KubernetesClient zhiyeServiceKubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
