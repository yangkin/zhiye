package com.tcl.csot.zhiye.support;

import com.tcl.csot.zhiye.service.dev.FaultState;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/** 基类：真实 PostgreSQL（zhiye_test）+ 随机端口 + 假 runner。api 与 site 两个 profile 同时启用。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"api", "site", "test"})
public abstract class IntegrationTest {
    @LocalServerPort
    protected int port;

    @Autowired
    protected FaultState faults;

    protected Client client() { return new Client(port); }

    @AfterEach
    void resetFaults() { faults.set("none", "none"); FakeUpstreamController.failNext = 0; }

    /** 把 http://127.0.0.1:8083/p/... 换成测试端口 */
    protected String local(String url) { return url.replaceFirst("^https?://[^/]+", "http://127.0.0.1:" + port); }
}
