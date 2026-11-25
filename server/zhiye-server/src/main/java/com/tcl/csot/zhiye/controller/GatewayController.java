package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.client.UpstreamClient;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.llm.LlmRoute;
import com.tcl.csot.zhiye.service.llm.LlmRouter;
import com.tcl.csot.zhiye.service.llm.TaskTokenService;
import com.tcl.csot.zhiye.service.llm.UsageService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.dev.FaultState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 模型网关（OpenAI 兼容）：runner 用任务临时令牌访问，网关按任务找到上游路由（本地统一上游，
 * 或五星模式下员工自己的 llm-gateway 路由），带真实密钥转发，原样透传（含流式），统计 usage。
 * 不设任务级 token 上限；额度由五星AI岛 / llm-gateway 按人控制。
 */
@RestController
@Profile("api")
@RequestMapping("/llm/v1")
public class GatewayController {
    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final TaskTokenService tokens;
    private final UpstreamClient upstream;
    private final UsageService usage;
    private final FaultState faults;
    private final LlmRouter router;
    private final ZhiyeProperties props;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.ops.PlatformLogService platformLogs;

    public GatewayController(TaskTokenService tokens, UpstreamClient upstream, UsageService usage, FaultState faults,
                             LlmRouter router, ZhiyeProperties props) {
        this.tokens = tokens;
        this.upstream = upstream;
        this.usage = usage;
        this.faults = faults;
        this.router = router;
        this.props = props;
    }

    /** 路由取不到（未登录、五星AI岛不可用、没有配置模型）时按 OpenAI 错误格式返回，runner 会把 message 带回任务 */
    private LlmRoute route(String taskId, HttpServletResponse res) throws IOException {
        try {
            return router.forTask(taskId);
        } catch (ApiException e) {
            if (platformLogs != null) {
                platformLogs.error("llm_gateway", "GATEWAY_ROUTE_FAILED", null, null, null, taskId,
                        e.status().value(), null, "模型路由解析失败：" + e.code() + " · " + e.getMessage(), null,
                        java.util.Map.of("code", e.code()));
            }
            error(res, e.status().value(), "invalid_request_error", e.code(), e.getMessage());
            return null;
        }
    }

    private static void error(HttpServletResponse res, int status, String type, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        var n = Json.obj();
        n.putObject("error").put("message", message).put("type", type).put("code", code);
        res.getOutputStream().write(Json.write(n).getBytes(StandardCharsets.UTF_8));
    }

    private Optional<String> auth(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        String token = h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).trim() : req.getHeader("x-api-key");
        return tokens.validate(token);
    }

    @GetMapping("/models")
    public void models(HttpServletRequest req, HttpServletResponse res) throws Exception {
        Optional<String> taskId = auth(req);
        if (taskId.isEmpty()) { error(res, 401, "invalid_request_error", "UNAUTHORIZED", "网关令牌无效或任务已结束"); return; }
        LlmRoute route = route(taskId.get(), res);
        if (route == null) return;
        HttpResponse<InputStream> up = upstream.send(route, "GET", "/models", null);
        res.setStatus(up.statusCode());
        res.setContentType(up.headers().firstValue("content-type").orElse("application/json"));
        try (InputStream in = up.body()) { in.transferTo(res.getOutputStream()); }
    }

    @PostMapping("/chat/completions")
    public void chat(HttpServletRequest req, HttpServletResponse res) throws Exception {
        long t0 = System.currentTimeMillis();
        Optional<String> taskId = auth(req);
        if (taskId.isEmpty()) {
            if (platformLogs != null) {
                platformLogs.warn("llm_gateway", "GATEWAY_UNAUTHORIZED", null, null, null, null,
                        401, null, "模型网关鉴权失败：任务令牌无效或已结束", null, java.util.Map.of());
            }
            error(res, 401, "invalid_request_error", "UNAUTHORIZED", "网关令牌无效或任务已结束");
            return;
        }
        String tid = taskId.get();
        if (faults.llmError()) {
            if (platformLogs != null) {
                platformLogs.error("llm_gateway", "GATEWAY_FAULT_INJECTED", null, null, null, tid,
                        500, null, "模型网关返回注入故障 500", null, java.util.Map.of());
            }
            error(res, 500, "server_error", "MODEL_ERROR", "模型服务暂不可用（注入的故障）");
            return;
        }
        byte[] body = req.getInputStream().readAllBytes();
        JsonNode parsed;
        try { parsed = Json.M.readTree(body); } catch (Exception e) { error(res, 400, "invalid_request_error", "VALIDATION", "请求体不是 JSON"); return; }
        LlmRoute route = route(tid, res);
        if (route == null) return;
        boolean stream = parsed.path("stream").asBoolean(false);
        String model = parsed.path("model").asText(props.llm().model());
        // 五星模式：runner 不知道员工的路由，模型名在这里换成 llm-gateway 上的 xclaw-{工号}-{供应商}/{模型}
        if (!route.local() && parsed.isObject()) {
            // runner 侧模型定义固定是多模态，实际路由可能不是：带图的调用自动切到该员工的多模态模型
            if (!route.multimodal() && hasImages(parsed)) {
                LlmRoute vision = router.visionFor(route.account());
                if (vision != null) route = vision;
            }
            ((com.fasterxml.jackson.databind.node.ObjectNode) parsed).put("model", route.model());
            // 没有多模态模型可切时兜底：图片降级成文本，避免上游 400 毁掉整个任务
            if (!route.multimodal()) stripImages(parsed);
            body = Json.write(parsed).getBytes(StandardCharsets.UTF_8);
            model = route.model();
        }

        HttpResponse<InputStream> up;
        try {
            up = upstream.send(route, "POST", "/chat/completions", body);
        } catch (IOException e) {
            int ms = (int) Math.max(0L, System.currentTimeMillis() - t0);
            if (platformLogs != null) {
                platformLogs.error("llm_gateway", "GATEWAY_UPSTREAM_IO", null, null, null, tid,
                        502, ms, "连接上游模型服务失败：" + e.getMessage(),
                        com.tcl.csot.zhiye.service.ops.PlatformLogService.stackTrace(e),
                        java.util.Map.of("provider", route.provider(), "model", model, "stream", stream));
            }
            error(res, 502, "server_error", "MODEL_ERROR", "模型服务暂不可用：" + e.getMessage());
            return;
        }
        res.setStatus(up.statusCode());
        res.setContentType(up.headers().firstValue("content-type").orElse(stream ? "text/event-stream" : "application/json"));
        if (stream) res.setHeader("Cache-Control", "no-cache");
        OutputStream out = res.getOutputStream();
        JsonNode lastUsage = null;
        String errBody = null;
        String streamErr = null;
        try (InputStream in = up.body()) {
            if (stream && up.statusCode() < 400) {
                byte[] buf = new byte[8192];
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                    for (int i = 0; i < n; i++) {
                        if (buf[i] == '\n') {
                            JsonNode u = usageFromLine(line.toString(StandardCharsets.UTF_8));
                            if (u != null) lastUsage = u;
                            line.reset();
                        } else line.write(buf[i]);
                    }
                }
                JsonNode u = usageFromLine(line.toString(StandardCharsets.UTF_8));
                if (u != null) lastUsage = u;
            } else {
                byte[] all = in.readAllBytes();
                out.write(all);
                if (up.statusCode() < 400) {
                    try { lastUsage = UsageService.findUsage(Json.M.readTree(all)); } catch (Exception ignored) { }
                } else {
                    errBody = new String(all, 0, Math.min(all.length, 4000), StandardCharsets.UTF_8);
                    log.warn("上游模型错误 {}：{}", up.statusCode(), errBody.substring(0, Math.min(errBody.length(), 500)));
                    if (up.statusCode() == 401 && !route.local()) router.evict(route.account());
                }
            }
        } catch (IOException e) {
            streamErr = e.getMessage();
            log.info("网关流中断（任务 {}）：{}", tid, e.getMessage());
        } finally {
            if (lastUsage != null) usage.record(tid, route.provider(), model, lastUsage);
            if (platformLogs != null) {
                int ms = (int) Math.max(0L, System.currentTimeMillis() - t0);
                boolean ok = up.statusCode() < 400 && streamErr == null;
                String action = !ok ? (streamErr != null ? "GATEWAY_STREAM_BROKEN" : "GATEWAY_UPSTREAM_ERROR") : "GATEWAY_CHAT";
                String summary = ok
                        ? String.format("%s / %s · HTTP %d · %dms", route.provider(), model, up.statusCode(), ms)
                        : String.format("%s / %s · 异常 HTTP %d · %s", route.provider(), model, up.statusCode(), streamErr != null ? streamErr : "上游报错");
                platformLogs.record("llm_gateway", ok ? "INFO" : "ERROR", action, null, null, null, tid, ok,
                        up.statusCode(), ms, summary, errBody != null ? errBody : streamErr,
                        java.util.Map.of("provider", route.provider(), "model", model, "stream", stream, "local", route.local()));
            }
        }
    }

    /** 请求里是否带图片（OpenAI 多模态消息）。 */
    static boolean hasImages(JsonNode parsed) {
        for (JsonNode msg : parsed.path("messages")) {
            JsonNode content = msg.path("content");
            if (!content.isArray()) continue;
            for (JsonNode part : content) if ("image_url".equals(part.path("type").asText())) return true;
        }
        return false;
    }

    /** 把消息里的图片改写成文本占位（非多模态路由用），其余内容原样保留。 */
    static void stripImages(JsonNode parsed) {
        for (JsonNode msg : parsed.path("messages")) {
            JsonNode content = msg.path("content");
            if (!content.isArray()) continue;
            com.fasterxml.jackson.databind.node.ArrayNode arr = (com.fasterxml.jackson.databind.node.ArrayNode) content;
            for (int i = 0; i < arr.size(); i++) {
                if (!"image_url".equals(arr.get(i).path("type").asText())) continue;
                var text = Json.obj();
                text.put("type", "text");
                text.put("text", "[图片已省略：当前模型不支持图片输入]");
                arr.set(i, text);
            }
        }
    }

    private static JsonNode usageFromLine(String line) {
        String l = line.trim();
        if (!l.startsWith("data:")) return null;
        String data = l.substring(5).trim();
        if (data.isEmpty() || data.equals("[DONE]") || !data.contains("usage")) return null;
        try { return UsageService.findUsage(Json.M.readTree(data)); } catch (Exception e) { return null; }
    }
}
