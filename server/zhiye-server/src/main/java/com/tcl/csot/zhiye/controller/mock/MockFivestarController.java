package com.tcl.csot.zhiye.controller.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.client.UpstreamClient;
import com.tcl.csot.zhiye.config.FivestarProperties;
import com.tcl.csot.zhiye.service.llm.UsageService;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.ServerPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地模拟的五星AI岛 openclaw-service 与 llm-gateway（zhiye.fivestar.mock=true 时启用），内网联调前用它跑通整条链路。
 * 接口形状照 openclaw-service 的 LlmModelController / TokenCostDetailController 和 llm-gateway 的 OpenAI 兼容接口：
 * - 平台令牌：模拟统一认证发的 tok-{工号}；
 * - 虚拟密钥：vk-{工号}，模型名 xclaw-{工号}-{供应商}/{模型ID}；
 * - 额度：每人每周 zhiye.fivestar.mock-limit-tokens，周一 00:00 UTC 重置，超出返回 429。
 * 模拟网关收到请求后转给本地统一上游（zhiye.llm），模型名换成本地模型。
 */
@RestController
@Profile("api")
@ConditionalOnProperty(prefix = "zhiye.fivestar", name = "mock", havingValue = "true")
public class MockFivestarController {
    record Provider(String id, String name, String type, List<String[]> models) {}

    /** 每个员工看到的供应商与模型（id, modelId, modelName, 是否多模态） */
    static final List<Provider> PROVIDERS = List.of(
            new Provider("11", "deepseek", "deepseek", List.of(new String[]{"101", "deepseek-v4-flash", "DeepSeek V4 Flash", "true"},
                    new String[]{"102", "deepseek-v4-pro", "DeepSeek V4 Pro", "false"})),
            new Provider("12", "moonshot", "moonshot", List.<String[]>of(new String[]{"201", "kimi-k2.6", "Kimi K2.6", "true"})));

    private final FivestarProperties fs;
    private final UpstreamClient upstream;
    private final ServerPort port;
    private final Map<String, AtomicLong> used = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AtomicLong>> byModel = new ConcurrentHashMap<>();
    /** 最近收到的网关请求头（测试核对 X-User-Id / X-Project-Id） */
    public static final Deque<Map<String, String>> LAST_HEADERS = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public MockFivestarController(FivestarProperties fs, UpstreamClient upstream, ServerPort port) {
        this.fs = fs;
        this.upstream = upstream;
        this.port = port;
    }

    /** 测试与本地演示用：直接设置某人本周已用量 */
    public void setUsed(String account, long tokens) { used.computeIfAbsent(account, k -> new AtomicLong()).set(tokens); }

    public long used(String account) { return used.getOrDefault(account, new AtomicLong()).get(); }

    static String resetAt() {
        return LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay().toInstant(ZoneOffset.UTC).toString();
    }

    /** 与真实 fivestar/openclaw 的 metis 信封一致：成功 {code:"ok", data, success:true} */
    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", "ok");
        r.put("data", data);
        r.put("success", true);
        r.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(r);
    }

    /** 业务异常：HTTP 200 + {code, message, type}（metis GlobalExceptionHandler 的形态） */
    private static ResponseEntity<Map<String, Object>> fail(int code, String message) {
        return ResponseEntity.ok(Map.of("code", String.valueOf(code), "message", message, "type", "ERROR", "timestamp", System.currentTimeMillis()));
    }

    /** 平台令牌 → 工号；无效返回 null（真实环境由平台网关鉴权） */
    static String account(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer tok-")) return null;
        String a = authorization.substring("Bearer tok-".length()).trim();
        return a.matches("[A-Za-z0-9._-]{1,64}") ? a : null;
    }

    private static Map<String, Object> modelVo(Provider p, String[] m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m[0]);
        v.put("providerId", p.id());
        v.put("providerName", p.name());
        v.put("providerType", p.type());
        v.put("modelName", m[2]);
        v.put("modelId", m[1]);
        v.put("isMultimodal", Boolean.parseBoolean(m[3]));
        v.put("enabled", true);
        return v;
    }

    private static Map<String, Object> providerVo(Provider p, String account) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", p.id());
        v.put("userAccount", account);
        v.put("providerName", p.name());
        v.put("providerType", p.type());
        v.put("enabled", true);
        v.put("isBuiltin", true);
        v.put("models", p.models().stream().map(m -> modelVo(p, m)).toList());
        return v;
    }

    // ---------------- openclaw-service ----------------

    @GetMapping("/mock/openclaw/{org}/llm/models/providers")
    public ResponseEntity<Map<String, Object>> providers(@RequestHeader(value = "Authorization", required = false) String auth) {
        String a = account(auth);
        if (a == null) return ResponseEntity.status(401).build();
        return ok(PROVIDERS.stream().map(p -> providerVo(p, a)).toList());
    }

    @GetMapping("/mock/openclaw/{org}/llm/models/default")
    public ResponseEntity<Map<String, Object>> defaultModel(@RequestHeader(value = "Authorization", required = false) String auth) {
        String a = account(auth);
        if (a == null) return ResponseEntity.status(401).build();
        Provider p = PROVIDERS.get(0);
        return ok(Map.of("model", Map.of("provider", providerVo(p, a), "model", modelVo(p, p.models().get(0)))));
    }

    @GetMapping("/mock/openclaw/{org}/tokenCostDetail/usage")
    public ResponseEntity<Map<String, Object>> usage(@RequestHeader(value = "Authorization", required = false) String auth) {
        String a = account(auth);
        if (a == null) return ResponseEntity.status(401).build();
        long u = used(a), limit = fs.mockLimitTokens();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("totalTokens", u);
        v.put("packageId", 1);
        v.put("packageName", "Lite");
        v.put("packageUsableModels", PROVIDERS.stream().flatMap(p -> p.models().stream().map(m -> m[1])).toList());
        v.put("packageSyncStatus", "SYNCED");
        v.put("packageSource", "DEFAULT");
        v.put("packageTotalTokens", limit);
        v.put("remainingTokens", Math.max(0, limit - u));
        v.put("resetAt", resetAt());
        v.put("quotaStatus", "ACTIVE");
        long total = Math.max(1, byModel.getOrDefault(a, Map.of()).values().stream().mapToLong(AtomicLong::get).sum());
        v.put("shareData", byModel.getOrDefault(a, Map.of()).entrySet().stream().map(e -> Map.of("name", e.getKey(), "tokens", e.getValue().get(),
                "pct", Math.round(e.getValue().get() * 10000.0 / total) / 100.0)).toList());
        return ok(v);
    }

    /** 拟新增的服务间接口：按工号和模型取 llm-gateway 路由（真实接口需 openclaw 团队提供，见 docs/fivestar-integration.md） */
    @PostMapping("/mock/openclaw/internal/zhiye/llm/route")
    public ResponseEntity<Map<String, Object>> route(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                                     @RequestBody JsonNode body) {
        String expected = fs.internalToken();
        if (!expected.isEmpty() && !expected.equals(token)) return ResponseEntity.status(401).build();
        String a = body.path("userAccount").asText("");
        if (!a.matches("[A-Za-z0-9._-]{1,64}")) return fail(400, "userAccount 不合法");
        String pid = body.path("providerId").asText(""), mid = body.path("modelId").asText("");
        Provider p = pid.isEmpty() ? PROVIDERS.get(0) : PROVIDERS.stream().filter(x -> x.id().equals(pid)).findFirst().orElse(null);
        if (p == null) return fail(400, "供应商不存在或未启用");
        String[] m = mid.isEmpty() ? p.models().get(0) : p.models().stream().filter(x -> x[1].equals(mid)).findFirst().orElse(null);
        if (m == null) return fail(400, "模型不存在或未启用");
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("baseUrl", fs.llmGatewayBaseUrl().replace("{self}", port.selfBase()).replaceAll("/+$", "") + "/v1");
        v.put("providerModelName", "xclaw-" + a + "-" + p.name() + "/" + m[1]);
        v.put("virtualKey", "vk-" + a);
        v.put("providerId", p.id());
        v.put("providerName", p.name());
        v.put("modelId", m[1]);
        v.put("modelName", m[2]);
        return ok(v);
    }

    // ---------------- llm-gateway ----------------

    private static void gatewayError(HttpServletResponse res, int status, String type, String code, String message, String resetAt) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        ObjectNode e = Json.obj();
        ObjectNode err = e.putObject("error").put("message", message).put("type", type).put("code", code);
        if (resetAt != null) err.put("reset_at", resetAt);
        res.getOutputStream().write(Json.write(e).getBytes(StandardCharsets.UTF_8));
    }

    /** 校验虚拟密钥和 X-User-Id，返回工号；失败已写好错误响应，返回 null */
    private static String gatewayAccount(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String auth = req.getHeader("Authorization");
        String user = req.getHeader("X-User-Id");
        if (auth == null || !auth.startsWith("Bearer vk-")) { gatewayError(res, 401, "authentication_error", "invalid_virtual_key", "virtual key is invalid", null); return null; }
        String a = auth.substring("Bearer vk-".length()).trim();
        if (user == null || !user.equals(a)) { gatewayError(res, 400, "invalid_request_error", "user_mismatch", "X-User-Id is required and must match the virtual key owner", null); return null; }
        return a;
    }

    @GetMapping("/mock/llm-gateway/v1/models")
    public void gatewayModels(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String a = gatewayAccount(req, res);
        if (a == null) return;
        ObjectNode n = Json.obj().put("object", "list");
        var data = n.putArray("data");
        for (Provider p : PROVIDERS) for (String[] m : p.models()) data.addObject().put("id", "xclaw-" + a + "-" + p.name() + "/" + m[1]).put("object", "model");
        res.setContentType("application/json;charset=UTF-8");
        res.getOutputStream().write(Json.write(n).getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/mock/llm-gateway/v1/chat/completions")
    public void gatewayChat(HttpServletRequest req, HttpServletResponse res) throws Exception {
        String a = gatewayAccount(req, res);
        if (a == null) return;
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-User-Id", req.getHeader("X-User-Id"));
        h.put("X-Project-Id", String.valueOf(req.getHeader("X-Project-Id")));
        LAST_HEADERS.addFirst(h);
        while (LAST_HEADERS.size() > 50) LAST_HEADERS.pollLast();
        if (used(a) >= fs.mockLimitTokens()) {
            gatewayError(res, 429, "rate_limit_exceeded", "token_quota_exceeded", "token quota exceeded for user " + a, resetAt());
            return;
        }
        JsonNode body = Json.M.readTree(req.getInputStream().readAllBytes());
        String asked = body.path("model").asText("");
        String prefix = "xclaw-" + a + "-";
        if (!asked.startsWith(prefix)) { gatewayError(res, 400, "invalid_request_error", "model_not_found", "model " + asked + " is not routed for this key", null); return; }
        String modelId = asked.substring(asked.indexOf('/') + 1);
        var local = upstream.localRoute();
        ((ObjectNode) body).put("model", local.model());
        boolean stream = body.path("stream").asBoolean(false);
        HttpResponse<InputStream> up = upstream.send(local, "POST", "/chat/completions", Json.write(body).getBytes(StandardCharsets.UTF_8));
        res.setStatus(up.statusCode());
        res.setContentType(up.headers().firstValue("content-type").orElse(stream ? "text/event-stream" : "application/json"));
        OutputStream out = res.getOutputStream();
        JsonNode lastUsage = null;
        try (InputStream in = up.body()) {
            byte[] buf = new byte[8192];
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
                all.write(buf, 0, n);
            }
            for (String line : all.toString(StandardCharsets.UTF_8).split("\n")) {
                String l = line.trim();
                if (l.startsWith("data:")) l = l.substring(5).trim();
                if (!l.contains("usage")) continue;
                try { JsonNode u = UsageService.findUsage(Json.M.readTree(l)); if (u != null) lastUsage = u; } catch (Exception ignored) { }
            }
        }
        if (lastUsage != null && up.statusCode() < 400) {
            long t = lastUsage.path("prompt_tokens").asLong(0) + lastUsage.path("completion_tokens").asLong(0);
            used.computeIfAbsent(a, k -> new AtomicLong()).addAndGet(t);
            byModel.computeIfAbsent(a, k -> new ConcurrentHashMap<>()).computeIfAbsent(modelId, k -> new AtomicLong()).addAndGet(t);
        }
    }
}
