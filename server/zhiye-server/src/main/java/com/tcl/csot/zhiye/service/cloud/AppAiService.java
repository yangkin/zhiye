package com.tcl.csot.zhiye.service.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.client.UpstreamClient;
import com.tcl.csot.zhiye.service.llm.UsageHook;
import com.tcl.csot.zhiye.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 应用内 AI（技术方案 20.5.1）：生成的应用调用 window.zhiye.ai.chat，平台代为调用模型，模型密钥只在服务端。
 * - 预览带应用令牌；发布站点走同源 /__zhiye/ai/chat，且只有构建产物里确实用到 zhiye.ai 的应用才开放
 * - 限制：消息条数与长度、图片数量与大小、单次输出 token 上限、90 秒超时、每个应用每分钟次数
 */
@Service
public class AppAiService {
    private static final Logger log = LoggerFactory.getLogger(AppAiService.class);
    static final int MAX_MESSAGES = 40, MAX_CHARS = 60_000, MAX_IMAGES = 4, MAX_IMAGE_BYTES = 4 * 1024 * 1024, MAX_TOKENS = 4096;
    static final int PER_MINUTE = 60;
    private static final Pattern DATA_URL = Pattern.compile("^data:image/(png|jpe?g|webp|gif);base64,[A-Za-z0-9+/=\\s]+$");

    private final UpstreamClient upstream;
    private final ZhiyeProperties props;
    private final UsageHook usage;
    private final ObjectStore store;
    private final Map<String, Boolean> usesCache = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.llm.LlmRouter router;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.common.RateLimitService rateLimit;
    private final Map<String, Deque<Long>> calls = new ConcurrentHashMap<>();

    public AppAiService(UpstreamClient upstream, ZhiyeProperties props, UsageHook usage, ObjectStore store) {
        this.upstream = upstream;
        this.props = props;
        this.usage = usage;
        this.store = store;
    }

    /** 构建产物（某个前缀下的 html/js）里有没有调用 zhiye.ai；按前缀缓存（构建产物不可变） */
    public boolean usesAi(String prefix) { return buildUses(prefix, "zhiye.ai"); }

    /** 构建产物里有没有出现某段代码（例如 zhiye.fn），用来决定是否注入 SDK */
    public boolean buildUses(String prefix, String needle) {
        if (prefix == null) return false;
        return usesCache.computeIfAbsent(prefix + "|" + needle, k -> {
            String p = prefix;
            try {
                for (String key : store.list(p + "/")) {
                    if (!(key.endsWith(".js") || key.endsWith(".html") || key.endsWith(".mjs"))) continue;
                    var bytes = store.getBytes(key);
                    if (bytes.isPresent() && new String(bytes.get(), StandardCharsets.UTF_8).contains(needle)) return true;
                }
            } catch (Exception e) {
                log.warn("检查构建产物是否用到应用内 AI 失败：{}", e.toString());
            }
            return false;
        });
    }

    /** {messages:[{role, content}], system?, images?:[dataURL], maxTokens?, json?} → {content, usage} */
    public Map<String, Object> chat(String appId, CloudPolicy.Who who, JsonNode req) {
        if (req == null || !req.isObject()) throw ApiException.validation("请求体要是 JSON 对象");
        JsonNode messages = req.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) throw ApiException.validation("messages 不能为空");
        if (messages.size() > MAX_MESSAGES) throw ApiException.validation("messages 最多 " + MAX_MESSAGES + " 条");
        int chars = 0;
        // 五星模式下应用内 AI 记在应用作者的个人额度上（访问者可能未登录）
        String ws = jdbc.sql("select workspace_id from app where id = ?").param(appId).query(String.class).optional().orElse(null);
        com.tcl.csot.zhiye.service.llm.LlmRoute route = router.forWorkspace(ws, com.tcl.csot.zhiye.service.llm.LlmRouter.AUTO);
        ObjectNode body = Json.obj();
        body.put("model", route.model());
        int maxTokens = req.path("maxTokens").asInt(1500);
        body.put("max_tokens", Math.max(16, Math.min(MAX_TOKENS, maxTokens)));
        ArrayNode out = body.putArray("messages");
        String system = req.path("system").asText("");
        if (!system.isBlank()) { out.addObject().put("role", "system").put("content", system); chars += system.length(); }
        for (JsonNode m : messages) {
            String role = m.path("role").asText("user");
            if (!Set.of("user", "assistant").contains(role)) throw ApiException.validation("role 只能是 user 或 assistant");
            String content = m.path("content").isTextual() ? m.get("content").asText() : m.path("content").toString();
            chars += content.length();
            out.addObject().put("role", role).put("content", content);
        }
        if (chars > MAX_CHARS) throw ApiException.validation("内容太长（最多 " + MAX_CHARS + " 字），请先截取要处理的部分");
        JsonNode images = req.get("images");
        if (images != null && images.isArray() && !images.isEmpty()) {
            if (images.size() > MAX_IMAGES) throw ApiException.validation("一次最多 " + MAX_IMAGES + " 张图片");
            ObjectNode last = (ObjectNode) out.get(out.size() - 1);
            if (!"user".equals(last.path("role").asText())) throw ApiException.validation("带图片时最后一条消息要是 user");
            ArrayNode parts = Json.M.createArrayNode();
            parts.addObject().put("type", "text").put("text", last.path("content").asText());
            for (JsonNode img : images) {
                String url = img.asText("");
                if (!DATA_URL.matcher(url).matches()) throw ApiException.validation("图片要是 data:image/...;base64, 格式（png/jpg/webp/gif）");
                if (url.length() * 3L / 4 > MAX_IMAGE_BYTES) throw ApiException.validation("单张图片最大 4 MB，请先压缩");
                parts.addObject().put("type", "image_url").putObject("image_url").put("url", url);
            }
            last.set("content", parts);
        }
        if (req.path("json").asBoolean(false)) body.putObject("response_format").put("type", "json_object");

        String uid = who == null ? null : who.userId();
        rateLimit(appId);
        usage.before(appId, uid, "ai");
        try {
            var res = upstream.send(route, "POST", "/chat/completions", Json.write(body).getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(90), 1);
            String text;
            try (InputStream in = res.body()) { text = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (res.statusCode() >= 400) {
                log.warn("应用内 AI 上游返回 {}：{}", res.statusCode(), text.length() > 300 ? text.substring(0, 300) : text);
                if ("PROVIDER_QUOTA".equals(com.tcl.csot.zhiye.client.KimiClient.codeFor(res.statusCode(), text)))
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_QUOTA_EXCEEDED", router.fivestar()
                            ? "应用作者的模型额度已用完，请稍后再试" : "模型服务配额已用完，请稍后再试");
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE", "模型服务暂时不可用（HTTP " + res.statusCode() + "），请稍后重试");
            }
            JsonNode n = Json.read(text);
            String content = n.path("choices").path(0).path("message").path("content").asText("");
            JsonNode u = n.path("usage");
            long tokens = u.path("prompt_tokens").asLong(0) + u.path("completion_tokens").asLong(0);
            usage.after(appId, uid, "ai", tokens);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("content", content);
            r.put("usage", Map.of("promptTokens", u.path("prompt_tokens").asLong(0), "completionTokens", u.path("completion_tokens").asLong(0)));
            return r;
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE", "模型调用被中断");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_UNAVAILABLE", "模型服务暂时不可用，请稍后重试");
        }
    }

    private void rateLimit(String appId) {
        if (rateLimit != null) {
            rateLimit.check("app-ai:" + appId, PER_MINUTE, 60, "AI_RATE_LIMITED",
                    "调用太频繁（每分钟最多 " + PER_MINUTE + " 次），请稍后再试");
            return;
        }
        long now = System.currentTimeMillis();
        Deque<Long> q = calls.computeIfAbsent(appId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= PER_MINUTE) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMITED", "调用太频繁（每分钟最多 " + PER_MINUTE + " 次），请稍后再试");
            q.addLast(now);
        }
    }
}
