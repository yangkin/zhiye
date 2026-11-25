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

    @Autowired
    protected com.tcl.csot.zhiye.service.source.DataSourceService sourceService;

    protected Client client() { return new Client(port); }

    @AfterEach
    void resetFaults() { faults.set("none", "none"); FakeUpstreamController.failNext = 0; }

    /** 把 http://127.0.0.1:8083/p/... 换成测试端口 */
    protected String local(String url) { return url.replaceFirst("^https?://[^/]+", "http://127.0.0.1:" + port); }

    /** 手工登记已关闭（SOURCE_MANUAL_DISABLED）；测试和 AGW 添加一样走 importExternal 种入数据源。 */
    protected com.fasterxml.jackson.databind.JsonNode registerSource(Client c, java.util.Map<String, Object> body) {
        String ws = c.ok(c.get("/api/workspace")).path("id").asText();
        return com.tcl.csot.zhiye.util.Json.read(com.tcl.csot.zhiye.util.Json.write(
                sourceService.importExternal(ws, "test", "ext_" + java.util.UUID.randomUUID(),
                        com.tcl.csot.zhiye.util.Json.read(com.tcl.csot.zhiye.util.Json.write(body)))));
    }
}
