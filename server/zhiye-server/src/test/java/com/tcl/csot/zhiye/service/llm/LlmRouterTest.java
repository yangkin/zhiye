package com.tcl.csot.zhiye.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.client.OpenclawClient;
import com.tcl.csot.zhiye.client.UpstreamClient;
import com.tcl.csot.zhiye.config.FivestarProperties;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmRouterTest {
    OpenclawClient openclaw;
    LlmRouter router;

    @BeforeEach
    void setUp() {
        FivestarProperties fs = mock(FivestarProperties.class);
        when(fs.projectId()).thenReturn("zhiye");
        when(fs.routeCacheSeconds()).thenReturn(60);
        when(fs.publicKey()).thenReturn("sk-public");
        when(fs.publicKeyEnv()).thenReturn("ZHIYE_FIVESTAR_PUBLIC_KEY");
        openclaw = mock(OpenclawClient.class);
        router = new LlmRouter(fs, mock(UpstreamClient.class), openclaw, mock(JdbcClient.class));
        when(openclaw.route(anyString(), anyString(), anyString(), anyString())).thenAnswer(inv -> Json.obj()
                .put("baseUrl", "http://llm-gateway").put("virtualKey", "vk").put("providerName", "fivestar")
                .put("providerModelName", "routed-" + inv.getArgument(1, String.class) + ":" + inv.getArgument(2, String.class)));
    }

    private void models(String account, String json) {
        when(openclaw.availableModels(account)).thenReturn(Json.read(json));
    }

    @Test
    void visionForPrefersPreferredMultimodal() {
        models("u1", """
                [{"providerId":"sz","modelId":"deepseek","multimodal":false,"preferred":true},
                 {"providerId":"sz","modelId":"kimi","multimodal":true},
                 {"providerId":"other","modelId":"qwen-vl","multimodal":true,"preferred":true}]
                """);
        LlmRoute r = router.visionFor("u1");
        assertNotNull(r);
        assertEquals("routed-other:qwen-vl", r.model());
        assertTrue(r.multimodal());
    }

    @Test
    void visionForFallsBackToAnyMultimodal() {
        models("u2", """
                [{"providerId":"sz","modelId":"deepseek","multimodal":false,"preferred":true},
                 {"providerId":"sz","modelId":"kimi","multimodal":true}]
                """);
        LlmRoute r = router.visionFor("u2");
        assertNotNull(r);
        assertEquals("routed-sz:kimi", r.model());
    }

    @Test
    void visionForNullWhenNoMultimodalModel() {
        models("u3", "[{\"providerId\":\"sz\",\"modelId\":\"deepseek\",\"multimodal\":false,\"preferred\":true}]");
        assertNull(router.visionFor("u3"));
    }

    @Test
    void forAccountMarksModelCapability() {
        models("u4", """
                [{"providerId":"sz","modelId":"deepseek","multimodal":false,"preferred":true},
                 {"providerId":"sz","modelId":"kimi","multimodal":true}]
                """);
        assertFalse(router.forAccount("u4", "sz:deepseek").multimodal());
        assertTrue(router.forAccount("u4", "sz:kimi").multimodal());
    }

    @Test
    void forAccountAutoUsesPreferredModelCapability() {
        models("u5", """
                [{"providerId":"sz","modelId":"deepseek","multimodal":false,"preferred":true},
                 {"providerId":"sz","modelId":"kimi","multimodal":true}]
                """);
        LlmRoute r = router.forAccount("u5", "auto");
        assertEquals("routed-sz:deepseek", r.model());
        assertFalse(r.multimodal());
    }

    @Test
    void freeModelUsesPublicKeyAndPaidUsesPersonalKey() {
        models("u6", """
                [{"providerId":"p","modelId":"free-model","billingType":"FREE","multimodal":true,"preferred":false},
                 {"providerId":"sz","modelId":"paid-model","billingType":"PAID","multimodal":true,"preferred":true}]
                """);
        assertEquals("sk-public", router.forAccount("u6", "p:free-model", "FREE").apiKey());
        assertEquals("vk", router.forAccount("u6", "sz:paid-model", "PAID").apiKey());
        // 前端没带 billingType 时按模型列表判断
        assertEquals("sk-public", router.forAccount("u6", "p:free-model").apiKey());
        assertEquals("vk", router.forAccount("u6", "sz:paid-model").apiKey());
    }

    @Test
    void autoPrefersFreeModel() {
        models("u7", """
                [{"providerId":"sz","modelId":"paid-model","billingType":"PAID","multimodal":true,"preferred":true},
                 {"providerId":"p","modelId":"free-model","billingType":"FREE","multimodal":true,"preferred":false}]
                """);
        LlmRoute r = router.forAccount("u7", "auto");
        assertEquals("routed-p:free-model", r.model());
        assertEquals("sk-public", r.apiKey());
    }
}
