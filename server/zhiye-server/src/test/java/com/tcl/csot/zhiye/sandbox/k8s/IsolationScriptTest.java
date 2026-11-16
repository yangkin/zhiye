package com.tcl.csot.zhiye.sandbox.k8s;

import com.tcl.csot.zhiye.util.Threads;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 网络隔离检查脚本（在 Pod 里用 node 执行）：目标连不上 → 退出码 0；一直能连上 → 超时后退出码 3。 */
class IsolationScriptTest {
    static String node() {
        for (String p : new String[]{System.getenv("ZHIYE_NODE_BINARY"), System.getProperty("user.home") + "/.nvm/versions/node/v24.14.1/bin/node",
                "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node"}) {
            if (p != null && Files.isExecutable(Path.of(p))) return p;
        }
        return null;
    }

    static Process run(String target, long timeoutMs) throws Exception {
        String n = node();
        Assumptions.assumeTrue(n != null, "没有 node");
        return new ProcessBuilder(n, "-e", KubernetesSandboxProvider.ISOLATION_SCRIPT, target, String.valueOf(timeoutMs))
                .redirectErrorStream(true).start();
    }

    @Test
    void blockedTargetPasses() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); } // 已关闭，连接被拒绝
        Process p = run("127.0.0.1:" + port, 3000);
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, p.exitValue(), out);
        assertTrue(out.matches("\\d+"), out);
    }

    @Test
    void reachableTargetFailsAfterTimeout() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            Threads.start(() -> { try { while (true) s.accept().close(); } catch (Exception ignored) { } });
            Process p = run("127.0.0.1:" + s.getLocalPort(), 1200);
            assertTrue(p.waitFor(10, TimeUnit.SECONDS));
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertEquals(3, p.exitValue(), out);
            assertTrue(out.contains("网络隔离"), out);
        }
    }
}
