package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GatewayControllerTest {

    @Test
    void stripImagesReplacesOnlyImageParts() {
        JsonNode req = Json.read("""
                {"model":"m","messages":[
                  {"role":"system","content":"你是助手"},
                  {"role":"user","content":[
                    {"type":"text","text":"看这个页面"},
                    {"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}},
                    {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,BBBB"}}
                  ]},
                  {"role":"assistant","content":"好的"}
                ]}
                """);
        GatewayController.stripImages(req);
        assertEquals("你是助手", req.path("messages").get(0).path("content").asText());
        JsonNode parts = req.path("messages").get(1).path("content");
        assertEquals(3, parts.size());
        assertEquals("看这个页面", parts.get(0).path("text").asText());
        assertEquals("text", parts.get(1).path("type").asText());
        assertTrue(parts.get(1).path("text").asText().contains("不支持图片"));
        assertEquals("text", parts.get(2).path("type").asText());
        assertFalse(req.toString().contains("AAAA"));
        assertFalse(req.toString().contains("image_url"));
        assertEquals("好的", req.path("messages").get(2).path("content").asText());
    }

    @Test
    void hasImagesDetectsMultimodalMessages() {
        assertTrue(GatewayController.hasImages(Json.read("""
                {"messages":[{"role":"user","content":[{"type":"text","text":"看"},{"type":"image_url","image_url":{"url":"data:image/png;base64,A"}}]}]}
                """)));
        assertFalse(GatewayController.hasImages(Json.read("""
                {"messages":[{"role":"user","content":"纯文本"},{"role":"user","content":[{"type":"text","text":"还是文本"}]}]}
                """)));
        assertFalse(GatewayController.hasImages(Json.read("{\"model\":\"m\"}")));
    }

    @Test
    void stripImagesToleratesEmptyMessages() {
        JsonNode req = Json.read("{\"model\":\"m\"}");
        GatewayController.stripImages(req);
        assertEquals("m", req.path("model").asText());
    }
}
