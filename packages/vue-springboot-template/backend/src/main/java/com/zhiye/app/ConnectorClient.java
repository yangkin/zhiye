package com.zhiye.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 织页托管企业连接器零配置客户端（自动读取平台注入的 ZHIYE_CONNECTOR_API 与 ZHIYE_CONNECTOR_TOKEN）：
 * 无需在源码或环境变量存放任何飞书密钥，一行即可发飞书交互卡片、读写飞书多维表格与检索企业员工目录。
 */
@Component
public class ConnectorClient {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public boolean available() {
        String api = System.getenv("ZHIYE_CONNECTOR_API");
        String tok = System.getenv("ZHIYE_CONNECTOR_TOKEN");
        return api != null && !api.isBlank() && tok != null && !tok.isBlank();
    }

    public Map<String, Object> sendFeishuCard(String connectorId, String title, String content,
                                              String templateColor, List<Map<String, String>> fields) throws Exception {
        if (!available()) {
            return Map.of("ok", true, "simulated", true, "title", title, "content", content);
        }
        return post("/feishu/send-card", Map.of(
                "connectorId", connectorId == null ? "" : connectorId,
                "title", title,
                "content", content,
                "templateColor", templateColor == null ? "blue" : templateColor,
                "fields", fields == null ? List.of() : fields
        ));
    }

    public Map<String, Object> bitableList(String connectorId) throws Exception {
        if (!available()) return Map.of("ok", true, "simulated", true, "items", List.of());
        return post("/bitable/list", Map.of("connectorId", connectorId == null ? "" : connectorId));
    }

    public Map<String, Object> bitableCreate(String connectorId, Map<String, Object> fields) throws Exception {
        if (!available()) return Map.of("ok", true, "simulated", true, "fields", fields);
        return post("/bitable/create", Map.of("connectorId", connectorId == null ? "" : connectorId, "fields", fields));
    }

    public Map<String, Object> searchEmployees(String q) throws Exception {
        if (!available()) return Map.of("ok", true, "simulated", true, "employees", List.of());
        return post("/org/employees", Map.of("q", q == null ? "" : q));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) throws Exception {
        String base = System.getenv("ZHIYE_CONNECTOR_API").replaceAll("/+$", "");
        String token = System.getenv("ZHIYE_CONNECTOR_TOKEN");
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("企业连接器调用失败 HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return mapper.readValue(resp.body(), Map.class);
    }
}
