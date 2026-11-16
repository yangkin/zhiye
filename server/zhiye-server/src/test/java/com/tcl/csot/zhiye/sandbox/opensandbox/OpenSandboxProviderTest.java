package com.tcl.csot.zhiye.sandbox.opensandbox;

import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkRule;
import com.tcl.csot.zhiye.sandbox.SandboxProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OpenSandbox 请求参数：环境变量、出网策略和 NAS 子路径。 */
class OpenSandboxProviderTest {

    private static OpenSandboxProperties props(String gateway, String npm, String volumeRoot) {
        return new OpenSandboxProperties(
                "opensandbox-server.opensandbox-system.svc:80", "http", "ZHIYE_OPENSANDBOX_API_KEY",
                "zhiye-runner:test", "/opt/zhiye/bin/start-runner.sh", 8790, "/workspace", "/agent",
                volumeRoot, "zhiye-runtime", gateway, npm,
                "2", "4Gi", "2", "4Gi", "5Gi",
                "/usr/bin/chromium", "/opt/zhiye/packages/vite-plugin-loc/index.mjs",
                "/opt/zhiye/packages/editor-bridge/bridge.js",
                120, 300, true, 10001, true, 60, 120, Map.of());
    }

    private static SandboxProvider.Spec spec(boolean allowNet) {
        return new SandboxProvider.Spec("task-1", Path.of("/nas/workspaces/task-1"), Path.of("/nas/agents/task-1"),
                allowNet, "http://zhiye-api.zhiye.svc:8080/llm/v1", "model-token",
                Path.of("/tmp/task-1.log"), n -> { });
    }

    @Test
    void envHasNoModelKeyAndSeparateControlToken() {
        var p = props("http://zhiye-api.zhiye.svc:8080/llm/v1", "https://registry.npmmirror.com", "/nas");
        var env = new OpenSandboxProvider(null, p, null).env(spec(false), "control-token", p.gatewayUrl());

        assertThat(env).containsEntry("ZHIYE_RUNNER_TOKEN", "control-token");
        assertThat(env).containsEntry("ZHIYE_TASK_TOKEN", "model-token");
        assertThat(env).containsEntry("ZHIYE_RUNNER_PORT", "8790");
        assertThat(env).containsEntry("HOME", "/agent");
        assertThat(env).doesNotContainKeys("KIMI_API_KEY", "ZHIYE_OPENSANDBOX_API_KEY");
        assertThat(env).doesNotContainKey("npm_config_registry");
    }

    @Test
    void importTasksAlsoAllowNpmMirror() {
        var p = props("http://zhiye-api.zhiye.svc:8080/llm/v1", "https://registry.npmmirror.com", "/nas");
        var env = new OpenSandboxProvider(null, p, null).env(spec(true), "t", p.gatewayUrl());
        assertThat(env).containsEntry("ALLOW_NET", "1");
        assertThat(env).containsEntry("npm_config_registry", "https://registry.npmmirror.com");
    }

    @Test
    void networkPolicyDefaultsToDenyAndOnlyAllowsGateway() {
        var p = props("http://zhiye-api.zhiye.svc:8080/llm/v1", null, "/nas");
        NetworkPolicy policy = new OpenSandboxProvider(null, p, null).policy(p.gatewayUrl(), false);

        assertThat(policy.getDefaultAction()).isEqualTo(NetworkPolicy.DefaultAction.DENY);
        List<String> targets = policy.getEgress().stream().map(NetworkRule::getTarget).toList();
        assertThat(targets).contains("zhiye-api.zhiye.svc", "127.0.0.1");
        assertThat(policy.getEgress()).allMatch(r -> r.getAction() == NetworkRule.Action.ALLOW);
    }

    @Test
    void subPathMustStayInsideVolumeRoot() {
        assertThat(OpenSandboxProvider.subPath(Path.of("/nas"), Path.of("/nas/workspaces/t1")))
                .isEqualTo("workspaces/t1");
        assertThatThrownBy(() -> OpenSandboxProvider.subPath(Path.of("/nas"), Path.of("/etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void endpointUrlGetsScheme() {
        assertThat(OpenSandboxProvider.endpointUrl("10.0.0.1:8790")).isEqualTo("http://10.0.0.1:8790");
        assertThat(OpenSandboxProvider.endpointUrl("https://gw/x")).isEqualTo("https://gw/x");
    }
}