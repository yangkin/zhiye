package com.tcl.csot.zhiye.sandbox.k8s;

import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.sandbox.LocalProcessSandboxProvider;
import com.tcl.csot.zhiye.sandbox.SandboxProvider;
import com.tcl.csot.zhiye.repository.TaskRepository;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 按 zhiye.sandbox.provider 选择沙箱实现；k8s 时 provider、回收器、客户端都能装配。 */
@EnableKubernetesMockClient(crud = true)
class K8sWiringTest {
    static KubernetesClient client;
    @TempDir Path runtime;

    @Configuration
    @EnableConfigurationProperties(ZhiyeProperties.class)
    static class Base {
        @Bean TaskRepository taskRepository() { return new TaskRepository(Mockito.mock(JdbcClient.class)); }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(Base.class, K8sSandboxConfig.class, LocalProcessSandboxProvider.class,
                        KubernetesSandboxProvider.class, SandboxPodReaper.class)
                .withPropertyValues("zhiye.runtime-dir=" + runtime, "spring.profiles.active=api");
    }

    @Test
    void localByDefault() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SandboxProvider.class)).isInstanceOf(LocalProcessSandboxProvider.class);
            assertThat(ctx).doesNotHaveBean(SandboxPodReaper.class);
            assertThat(ctx).doesNotHaveBean(KubernetesClient.class);
        });
    }

    @Test
    void k8sWhenConfigured() {
        runner().withPropertyValues("zhiye.sandbox.provider=k8s", "zhiye.sandbox.k8s.namespace=sb-ns",
                        "zhiye.sandbox.k8s.node-selector[zhiye/pool]=sandbox")
                .withBean(KubernetesClient.class, () -> client)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(SandboxProvider.class)).isInstanceOf(KubernetesSandboxProvider.class);
                    assertThat(ctx).hasSingleBean(SandboxPodReaper.class);
                    K8sSandboxProperties p = ctx.getBean(K8sSandboxProperties.class);
                    assertThat(p.namespace()).isEqualTo("sb-ns");
                    assertThat(p.nodeSelector()).containsEntry("zhiye/pool", "sandbox");
                    assertThat(p.resources().cpuLimit()).isEqualTo("2");
                    assertThat(p.resources().memoryLimit()).isEqualTo("4Gi");
                    assertThat(p.startupTimeoutSeconds()).isEqualTo(120);
                });
    }

    @Test
    void applicationK8sYmlBinds() throws Exception {
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("k8s", new org.springframework.core.io.ClassPathResource("application-k8s.yml"));
        var env = new org.springframework.core.env.StandardEnvironment();
        sources.forEach(env.getPropertySources()::addFirst);
        var binder = org.springframework.boot.context.properties.bind.Binder.get(env);
        K8sSandboxProperties p = binder.bindOrCreate("zhiye.sandbox.k8s", K8sSandboxProperties.class);
        assertThat(p.nodeSelector()).containsEntry("zhiye/pool", "sandbox");
        assertThat(p.tolerations()).hasSize(1);
        assertThat(p.tolerations().get(0).key()).isEqualTo("zhiye/sandbox");
        assertThat(p.gatewayUrl()).isEqualTo("http://zhiye-api.zhiye.svc:8080/llm/v1");
        assertThat(p.runtimeClassName()).isNullOrEmpty();
        // 生产默认已经切到公司 OpenSandbox；自研 k8s 实现只保留作回滚
        assertThat(env.getProperty("zhiye.sandbox.provider")).isEqualTo("opensandbox");

        var o = binder.bindOrCreate("zhiye.sandbox.opensandbox",
                com.tcl.csot.zhiye.sandbox.opensandbox.OpenSandboxProperties.class);
        assertThat(o.image()).isEqualTo("zhiye-runner:dev");
        assertThat(o.controlPort()).isEqualTo(8790);
        assertThat(o.pvcName()).isEqualTo("zhiye-runtime");
        assertThat(o.workspaceMount()).isEqualTo("/workspace");
        assertThat(o.agentMount()).isEqualTo("/agent");
        assertThat(o.reaperGraceSeconds()).isEqualTo(120);
    }
}
