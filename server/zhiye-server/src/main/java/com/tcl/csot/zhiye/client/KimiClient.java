package com.tcl.csot.zhiye.client;

import com.tcl.csot.zhiye.exception.LlmException;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.llm.LlmRoute;
import com.tcl.csot.zhiye.service.llm.LlmRouter;
import com.tcl.csot.zhiye.service.llm.QuotaText;
import com.tcl.csot.zhiye.service.llm.UsageService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.dev.FaultState;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.http.HttpResponse;

/** Java 侧的单次模型调用（生成计划等），非流式。 */
@Component
public class KimiClient {
    private final UpstreamClient upstream;
    private final UsageService usage;
    private final FaultState faults;
    private final ZhiyeProperties props;
    private final LlmRouter router;

    public KimiClient(UpstreamClient upstream, UsageService usage, FaultState faults, ZhiyeProperties props, LlmRouter router) {
        this.upstream = upstream;
        this.usage = usage;
        this.faults = faults;
        this.props = props;
        this.router = router;
    }

    public String chat(String taskId, String system, String user, int maxTokens) throws LlmException {
        return chat(taskId, system, Json.obj().put("role", "user").put("content", user), maxTokens);
    }

    /** userMessage 是完整的 user 消息（content 可以是多模态数组，带图片） */
    public String chat(String taskId, String system, ObjectNode userMessage, int maxTokens) throws LlmException {
        if (faults.llmError()) throw new LlmException("模型服务暂不可用（注入的故障）");
        LlmRoute route;
        try {
            route = taskId == null ? upstream.localRoute() : router.forTask(taskId);
        } catch (ApiException e) {
            throw new LlmException("MODEL_ROUTE", e.getMessage());
        }
        ObjectNode body = Json.obj();
        body.put("model", route.model());
        body.put("max_tokens", maxTokens);
        body.put("reasoning_effort", "low");
        var msgs = body.putArray("messages");
        if (system != null) msgs.addObject().put("role", "system").put("content", system);
        msgs.add(userMessage);
        try {
            HttpResponse<InputStream> res = upstream.send(route, "POST", "/chat/completions", Json.write(body).getBytes());
            String text;
            try (InputStream in = res.body()) { text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
            if (res.statusCode() >= 400) {
                // 上游错误体里才有配额/预算的依据（例如 exceeded_current_quota_error / insufficient_quota），
                // 截断后带上，并按与 runner 相同的一套规则给出错误码
                String detail = text.length() > 300 ? text.substring(0, 300) + "…" : text;
                String code = codeFor(res.statusCode(), text);
                if ("PROVIDER_QUOTA".equals(code)) throw new LlmException(code, QuotaText.exhausted(router.fivestar(), text));
                throw new LlmException(code, "模型服务返回错误（HTTP " + res.statusCode() + "）：" + detail);
            }
            JsonNode n = Json.read(text);
            usage.record(taskId, route.provider(), route.model(), UsageService.findUsage(n));
            JsonNode content = n.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) throw new LlmException("模型没有返回内容");
            return content.asText();
        } catch (LlmException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("模型调用被中断", e);
        } catch (Exception e) {
            throw new LlmException("模型服务暂不可用：" + e.getMessage(), e);
        }
    }

    /** 与 runner/src/agent.mjs 的 classifyError 保持同一套判断。织页不设任务级上限，额度类错误都来自上游（五星AI岛 / 供应商）。 */
    public static String codeFor(int status, String body) {
        String t = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        boolean quotaWord = t.contains("quota") || t.contains("usage limit") || t.contains("current_quota")
                || t.contains("rate limit") || t.contains("rate_limit") || t.contains("exceed");
        if (t.contains("usage limit") || t.contains("current_quota") || t.contains("rate limit")) return "PROVIDER_QUOTA";
        if (status == 429 && quotaWord) return "PROVIDER_QUOTA";
        // DeepSeek 余额不足是 402（Insufficient Balance）
        if (status == 402 || t.contains("insufficient_quota")) return "PROVIDER_QUOTA";
        return "MODEL_ERROR";
    }

    /** 从模型输出中稳健地提取 JSON 对象（去掉代码块、前后说明文字） */
    public static JsonNode extractJson(String content) {
        if (content == null) return null;
        String s = content.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int start = s.indexOf('\n', fence);
            int end = s.indexOf("```", start + 1);
            if (start > 0 && end > start) {
                try { return Json.M.readTree(s.substring(start + 1, end)); } catch (Exception ignored) { }
            }
        }
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        while (a >= 0 && b > a) {
            try { return Json.M.readTree(s.substring(a, b + 1)); } catch (Exception e) {
                a = s.indexOf('{', a + 1);
            }
        }
        return null;
    }
}
