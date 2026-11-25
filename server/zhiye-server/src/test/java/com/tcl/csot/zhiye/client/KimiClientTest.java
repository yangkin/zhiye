package com.tcl.csot.zhiye.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KimiClientTest {

    @Test
    void perMinuteRateLimitIsNotQuotaExhaustion() {
        assertEquals("PROVIDER_RATE_LIMIT", KimiClient.codeFor(429,
                "{\"type\":\"TPM Ratelimit\",\"message\":\"TPM (Tokens Per Minute) limit is exceeded for model: qwen3.8-flash\"}"));
        assertEquals("PROVIDER_RATE_LIMIT", KimiClient.codeFor(429, "RPM limit exceeded"));
        assertEquals("PROVIDER_RATE_LIMIT", KimiClient.codeFor(429, "too many requests per minute"));
    }

    @Test
    void quotaErrorsKeepOriginalClassification() {
        assertEquals("PROVIDER_QUOTA", KimiClient.codeFor(429, "usage limit exceeded"));
        assertEquals("PROVIDER_QUOTA", KimiClient.codeFor(429, "exceeded_current_quota_error"));
        assertEquals("PROVIDER_QUOTA", KimiClient.codeFor(402, "Insufficient Balance"));
        assertEquals("MODEL_ERROR", KimiClient.codeFor(500, "internal error"));
        assertEquals("MODEL_ERROR", KimiClient.codeFor(400, "DeepSeek-V4-Flash-0731 is not a multimodal model"));
    }
}
