package com.tcl.csot.zhiye.sandbox.opensandbox;

import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkRule;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.PVC;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxEndpoint;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.Volume;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.sandbox.PathMapper;
import com.tcl.csot.zhiye.sandbox.RunnerSession;
import com.tcl.csot.zhiye.sandbox.SandboxProvider;
import com.tcl.csot.zhiye.sandbox.http.HttpRunnerBackend;
import com.tcl.csot.zhiye.sandbox.http.HttpRunnerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公司 OpenSandbox 沙箱：每个任务一个沙箱，runner 直接在沙箱里以 HTTP 服务运行。
 * 工作区和 agent 目录通过同一块 RWX 卷（NAS）的 subPath 挂载；默认拒绝出网，只放行模型网关。
 */
@Component
@ConditionalOnProperty(name = "zhiye.sandbox.provider", havingValue = "opensandbox")
public class OpenSandboxProvider implements SandboxProvider {
    private static final Logger log = LoggerFactory.getLogger(OpenSandboxProvider.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ZhiyeProperties props;
    private final OpenSandboxProperties sbx;
    private final ConnectionConfig connection;

    public OpenSandboxProvider(ZhiyeProperties props, OpenSandboxProperties sbx, ConnectionConfig connection) {
        this.props = props;
        this.sbx = sbx;
        this.connection = connection;
    }

    @Override
    public RunnerSession start(Spec spec) throws Exception {
        Path volumeRoot = requireVolumeRoot();
        Path workspace = spec.workspace().toAbsolutePath().normalize();
        Path agentDir = spec.agentDir().toAbsolutePath().normalize();
        String workspaceSub = subPath(volumeRoot, workspace);
        String agentSub = subPath(volumeRoot, agentDir);

        Files.createDirectories(agentDir);
        Files.createDirectories(spec.logFile().getParent());

        String controlToken = HexFormat.of().formatHex(newToken());
        String gateway = sbx.effectiveGatewayUrl(spec.gatewayUrl());
        PrintWriter logWriter = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(spec.logFile().toFile(), true), StandardCharsets.UTF_8), true);
        logWriter.println("[java] 创建 OpenSandbox 沙箱：image=" + sbx.image()
                + " pvc=" + sbx.pvcName() + " workspaceSub=" + workspaceSub + " agentSub=" + agentSub);

        Sandbox sandbox = null;
        try {
            Sandbox.Builder builder = Sandbox.builder()
                    .connectionConfig(connection)
                    .image(sbx.image())
                    .entrypoint(List.of(sbx.entrypoint()))
                    .resource(Map.of("cpu", sbx.cpu(), "memory", sbx.memory()))
                    .resourceRequests(Map.of("cpu", sbx.cpuRequest(), "memory", sbx.memoryRequest()))
                    .env(env(spec, controlToken, gateway))
                    .metadata(metadata(spec))
                    .volumes(List.of(
                            volume("workspace", sbx.pvcName(), workspaceSub, sbx.workspaceMount()),
                            volume("agent", sbx.pvcName(), agentSub, sbx.agentMount())))
                    .timeout(Duration.ofHours(props.task().sandboxMaxHours()).plusSeconds(sbx.deadlineMarginSeconds()))
                    .readyTimeout(Duration.ofSeconds(sbx.startupTimeoutSeconds()));
            if (sbx.networkPolicyEnabled()) builder.networkPolicy(policy(gateway, spec.allowNet()));
            sandbox = builder.build();
            logWriter.println("[java] 沙箱已创建：" + sandbox.getId());

            SandboxEndpoint endpoint = sandbox.getEndpoint(sbx.controlPort());
            String baseUrl = endpointUrl(endpoint.getEndpoint());
            logWriter.println("[java] runner 入口：" + baseUrl);
            PathMapper mapper = new PathMapper(workspace.toString(), agentDir.toString(),
                    sbx.workspaceMount(), sbx.agentMount());
            HttpRunnerSession session = new HttpRunnerSession(baseUrl, endpoint.getHeaders(), controlToken,
                    new SandboxBackend(sandbox, sbx), spec.onEvent(), logWriter, mapper);
            session.waitReady(Duration.ofSeconds(sbx.startupTimeoutSeconds()));
            return session;
        } catch (Exception e) {
            log.warn("创建 OpenSandbox 沙箱失败：{}", e.getMessage());
            logWriter.println("[java] 创建失败：" + e.getMessage());
            if (sandbox != null) {
                try { sandbox.kill(); } catch (Exception k) { log.warn("清理失败的沙箱出错：{}", k.getMessage()); }
            }
            logWriter.close();
            throw e;
        }
    }

    private Path requireVolumeRoot() {
        if (sbx.volumeRoot() == null || sbx.volumeRoot().isBlank())
            throw new IllegalStateException("未配置 zhiye.sandbox.opensandbox.volume-root（NAS 运行时目录）");
        return Path.of(sbx.volumeRoot()).toAbsolutePath().normalize();
    }

    static String subPath(Path root, Path dir) {
        Path rel = root.relativize(dir);
        String s = rel.toString().replace('\\', '/');
        if (s.startsWith("..")) throw new IllegalArgumentException(dir + " 不在 NAS 运行时目录 " + root + " 下");
        return s;
    }

    Map<String, String> env(Spec spec, String controlToken, String gateway) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ZHIYE_GATEWAY_URL", gateway == null ? "" : gateway);
        env.put("ZHIYE_TASK_TOKEN", spec.taskToken());
        env.put("ZHIYE_RUNNER_TOKEN", controlToken);
        env.put("ZHIYE_RUNNER_HOST", "0.0.0.0");
        env.put("ZHIYE_RUNNER_PORT", String.valueOf(sbx.controlPort()));
        env.put("ZHIYE_LOC_PLUGIN", sbx.locPlugin());
        env.put("ZHIYE_BRIDGE_PATH", sbx.bridgePath());
        env.put("ZHIYE_CHROME_PATH", sbx.chromePath());
        env.put("ZHIYE_TASK_ID", spec.taskId());
        env.put("HOME", sbx.agentMount());
        env.put("TMPDIR", "/tmp");
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.put("LANG", "en_US.UTF-8");
        if (spec.allowNet()) {
            env.put("ALLOW_NET", "1");
            env.put("npm_config_cache", "/tmp/zhiye-npm-cache");
            String registry = registryUrl();
            if (registry != null) env.put("npm_config_registry", registry);
        }
        env.putAll(sbx.extraEnv());
        return env;
    }

    Map<String, String> metadata(Spec spec) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("zhiye", "1");
        m.put("zhiye.taskId", spec.taskId());
        m.put("zhiye.protocol", "v2");
        return m;
    }

    Volume volume(String name, String claim, String subPath, String mountPath) {
        return Volume.builder()
                .name(name)
                .pvc(PVC.builder().claimName(claim).createIfNotExists(false).deleteOnSandboxTermination(false).build())
                .subPath(subPath)
                .mountPath(mountPath)
                .build();
    }

    NetworkPolicy policy(String gateway, boolean allowNet) {
        NetworkPolicy.Builder b = NetworkPolicy.builder().defaultAction(NetworkPolicy.DefaultAction.DENY);
        allowHost(b, gateway);
        b.addEgress(NetworkRule.builder().action(NetworkRule.Action.ALLOW).target("127.0.0.1").build());
        if (allowNet) {
            allowHost(b, registryUrl());
        }
        return b.build();
    }

    private static void allowHost(NetworkPolicy.Builder b, String url) {
        if (url == null || url.isBlank()) return;
        try {
            String host = URI.create(url.contains("://") ? url : "http://" + url).getHost();
            if (host != null && !host.isBlank())
                b.addEgress(NetworkRule.builder().action(NetworkRule.Action.ALLOW).target(host).build());
        } catch (Exception e) {
            log.warn("无法解析出网白名单地址 {}：{}", url, e.getMessage());
        }
    }

    private String registryUrl() {
        String r = sbx.npmRegistry();
        return r == null || r.isBlank() ? null : r;
    }

    static String endpointUrl(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalStateException("OpenSandbox 没有返回 runner 入口地址");
        return endpoint.contains("://") ? endpoint : "http://" + endpoint;
    }

    private static byte[] newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return b;
    }

    /** OpenSandbox 沙箱的生命周期。 */
    private static final class SandboxBackend implements HttpRunnerBackend {
        private final Sandbox sandbox;
        private final OpenSandboxProperties sbx;
        private volatile boolean destroyed;

        SandboxBackend(Sandbox sandbox, OpenSandboxProperties sbx) {
            this.sandbox = sandbox;
            this.sbx = sbx;
        }

        @Override
        public RunnerSession.SandboxDescriptor descriptor() {
            return new RunnerSession.SandboxDescriptor("opensandbox", sandbox.getId(), null, sbx.image(), sbx.cpu(), sbx.memory());
        }

        @Override
        public boolean alive() {
            if (destroyed) return false;
            try {
                SandboxInfo info = sandbox.getInfo();
                String state = info == null || info.getStatus() == null ? null : info.getStatus().getState();
                return "running".equalsIgnoreCase(state);
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void close() { destroy(); }

        @Override
        public void kill() { destroy(); }

        private synchronized void destroy() {
            if (destroyed) return;
            destroyed = true;
            try { sandbox.kill(); } catch (Exception e) {
                log.warn("删除 OpenSandbox 沙箱 {} 失败（可能已过期）：{}", sandbox.getId(), e.getMessage());
            }
        }
    }
}