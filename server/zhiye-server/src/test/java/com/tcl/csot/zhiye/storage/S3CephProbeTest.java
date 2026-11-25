package com.tcl.csot.zhiye.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;

/** 真实 Ceph S3 探测：只在提供 ZHIYE_S3_* 环境变量时运行（默认跳过，不进 CI）。 */
@EnabledIfEnvironmentVariable(named = "ZHIYE_S3_ACCESS_KEY", matches = ".+")
class S3CephProbeTest {

    @Test
    void putAndReadSmallObject() {
        S3ObjectStore store = new S3ObjectStore("http://clouds3.csot.tcl.com:8082", "default", "zhiye",
                System.getenv("ZHIYE_S3_ACCESS_KEY"), System.getenv("ZHIYE_S3_SECRET_KEY"), true);
        String key = "probe/checksum-" + System.nanoTime() + ".txt";
        store.put(key, "hello-ceph".getBytes(StandardCharsets.UTF_8));
        try (var in = store.get(key).orElseThrow()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assertions.assertEquals("hello-ceph", text);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        } finally {
            try { store.delete(key); } catch (Exception ignored) { }
        }
    }
}
