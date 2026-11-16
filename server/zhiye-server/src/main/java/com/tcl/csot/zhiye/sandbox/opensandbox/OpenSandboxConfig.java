package com.tcl.csot.zhiye.sandbox.opensandbox;

import com.alibaba.opensandbox.sandbox.SandboxManager;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** provider=opensandbox 时创建 SDK 客户端；site 进程用 provider=local，不会构造这些 Bean。 */
@Configuration
@ConditionalOnProperty(name = "zhiye.sandbox.provider", havingValue = "opensandbox")
@EnableConfigurationProperties(OpenSandboxProperties.class)
public class OpenSandboxConfig {

    @Bean
    public ConnectionConfig openSandboxConnectionConfig(OpenSandboxProperties props) {
        String key = System.getenv(props.apiKeyEnv());
        if (key == null || key.isBlank()) key = System.getProperty(props.apiKeyEnv());
        if (props.domain() == null || props.domain().isBlank())
            throw new IllegalStateException("未配置 zhiye.sandbox.opensandbox.domain");
        if (key == null || key.isBlank())
            throw new IllegalStateException("缺少 OpenSandbox API key（环境变量 " + props.apiKeyEnv() + "）");
        return ConnectionConfig.builder()
                .domain(props.domain())
                .protocol(props.protocol())
                .apiKey(key.trim())
                // 创建沙箱是长轮询，等镜像拉取和 Pod 就绪可能要一两分钟；命令 POST 另有自己的短超时
                .requestTimeout(Duration.ofMinutes(5))
                .build();
    }

    @Bean
    public SandboxManager openSandboxManager(ConnectionConfig connectionConfig) {
        return SandboxManager.Companion.builder().connectionConfig(connectionConfig).build();
    }
}