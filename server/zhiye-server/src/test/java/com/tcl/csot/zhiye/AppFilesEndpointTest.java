package com.tcl.csot.zhiye;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.support.Client;
import com.tcl.csot.zhiye.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 工作台「文件」页签接口：汇总创建/对话/工作台消息上传的附件与会话文档，且支持带原文件名的附件下载。 */
class AppFilesEndpointTest extends IntegrationTest {

    static { System.setProperty("KIMI_API_KEY", System.getenv().getOrDefault("KIMI_API_KEY", "test-key")); }

    @Test
    void listsUploadedAssetsFromWorkbenchAndSupportsAttachmentDownload() {
        Client c = client();
        JsonNode initialAsset = c.ok(c.upload("/api/assets", "初始需求.txt", "text/plain",
                "初始需求正文".getBytes(StandardCharsets.UTF_8), Map.of()));
        String appId = c.ok(c.post("/api/apps", Map.of(
                "type", "开发网站",
                "prompt", "做一个展示页",
                "mode", "快速",
                "model", "auto",
                "assetIds", List.of(initialAsset.path("id").asText())
        ))).path("id").asText();
        c.waitTask(c.ok(c.get("/api/apps/" + appId)).path("activeTask").path("id").asText());
        String genTaskId = c.ok(c.post("/api/apps/" + appId + "/generate", Map.of())).path("id").asText();
        c.waitTask(genTaskId);

        // 在工作台对话里再上传一个附件
        JsonNode chatAsset = c.ok(c.upload("/api/assets", "补充配图.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3}, Map.of()));
        assertThat(c.send("POST", "/api/chat/ask",
                Map.of("content", "这张图合适吗", "appId", appId, "assetIds", List.of(chatAsset.path("id").asText())),
                Map.of("Accept", "text/event-stream")).status()).isEqualTo(200);

        // GET /api/apps/{id}/files 同时返回初始上传和工作台聊天上传的文件，并带 downloadUrl
        JsonNode files = c.ok(c.get("/api/apps/" + appId + "/files"));
        JsonNode uploaded = files.path("uploaded");
        assertThat(uploaded.isArray()).isTrue();
        assertThat(uploaded.size()).isEqualTo(2);
        List<String> names = List.of(uploaded.get(0).path("name").asText(), uploaded.get(1).path("name").asText());
        assertThat(names).containsExactlyInAnyOrder("初始需求.txt", "补充配图.png");
        assertThat(uploaded.get(0).path("downloadUrl").asText()).endsWith("/content?download=1");

        // 校验 download=1 返回 attachment，不带则返回 inline
        Client.Res inlineRes = c.get("/api/assets/" + initialAsset.path("id").asText() + "/content");
        assertThat(inlineRes.status()).isEqualTo(200);
        assertThat(inlineRes.raw().headers().firstValue("Content-Disposition").orElse("")).startsWith("inline");

        Client.Res dlRes = c.get("/api/assets/" + initialAsset.path("id").asText() + "/content?download=1");
        assertThat(dlRes.status()).isEqualTo(200);
        assertThat(dlRes.raw().headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment");
        assertThat(dlRes.body()).isEqualTo("初始需求正文");

        // 校验版本单文件支持 download=true 下载
        String versionId = c.ok(c.get("/api/apps/" + appId)).path("currentVersion").path("id").asText();
        Client.Res verDl = c.get("/api/versions/" + versionId + "/file?path=package.json&download=true");
        assertThat(verDl.status()).isEqualTo(200);
        assertThat(verDl.raw().headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment").contains("package.json");
    }
}
