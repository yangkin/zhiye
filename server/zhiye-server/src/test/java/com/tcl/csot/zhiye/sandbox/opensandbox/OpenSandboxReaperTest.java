package com.tcl.csot.zhiye.sandbox.opensandbox;

import com.alibaba.opensandbox.sandbox.SandboxManager;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.PagedSandboxInfos;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.SandboxInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 回收器只删自己的、任务已结束或没有任务编号的沙箱。 */
class OpenSandboxReaperTest {

    private static OpenSandboxProperties cfg() {
        return new OpenSandboxProperties(
                "opensandbox-server.opensandbox-system.svc:80", "http", "ZHIYE_OPENSANDBOX_API_KEY",
                "zhiye-runner:test", "/opt/start.sh", 8790, "/workspace", "/agent",
                "/nas", "zhiye-runtime", "http://gw/llm/v1", null,
                "2", "4Gi", "2", "4Gi", "5Gi", "/usr/bin/chromium",
                "/opt/loc.mjs", "/opt/bridge.js", 120, 300, true, 10001, true, 60, 120, Map.of());
    }

    private static SandboxInfo sandbox(String id, String taskId, long ageMinutes) {
        SandboxInfo info = mock(SandboxInfo.class);
        when(info.getId()).thenReturn(id);
        when(info.getCreatedAt()).thenReturn(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(ageMinutes));
        when(info.getMetadata()).thenReturn(taskId == null ? Map.of("zhiye", "1") : Map.of("zhiye", "1", "zhiye.taskId", taskId));
        return info;
    }

    private static int reap(String taskStatus, SandboxInfo info) {
        SandboxManager manager = mock(SandboxManager.class);
        PagedSandboxInfos paged = mock(PagedSandboxInfos.class);
        when(manager.listSandboxInfos(any())).thenReturn(paged);
        when(paged.getSandboxInfos()).thenReturn(List.of(info));
        int n = new OpenSandboxReaper(id -> taskStatus, cfg(), manager).reapOnce(Instant.now());
        if (n > 0) verify(manager, times(1)).killSandbox(info.getId());
        else verify(manager, never()).killSandbox(any());
        return n;
    }

    @Test
    void keepsSandboxForRunningTask() {
        assertThatZero(reap("RUNNING", sandbox("s1", "t1", 10)));
    }

    @Test
    void deletesSandboxAfterTaskFinished() {
        assertThatOne(reap("SUCCEEDED", sandbox("s2", "t1", 10)));
    }

    @Test
    void deletesSandboxWhoseTaskIsGone() {
        assertThatOne(reap(null, sandbox("s3", "gone", 10)));
    }

    @Test
    void deletesSandboxWithoutTaskId() {
        assertThatOne(reap("RUNNING", sandbox("s4", null, 10)));
    }

    @Test
    void keepsFreshSandboxDuringGracePeriod() {
        assertThatZero(reap("SUCCEEDED", sandbox("s5", "t1", 1)));
    }

    private static void assertThatZero(int n) { org.assertj.core.api.Assertions.assertThat(n).isZero(); }
    private static void assertThatOne(int n) { org.assertj.core.api.Assertions.assertThat(n).isEqualTo(1); }
}