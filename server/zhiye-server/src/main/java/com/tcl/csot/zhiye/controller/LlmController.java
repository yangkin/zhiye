package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.client.OpenclawClient;
import com.tcl.csot.zhiye.config.FivestarProperties;
import com.tcl.csot.zhiye.service.app.Catalog;
import com.tcl.csot.zhiye.service.llm.LlmRouter;
import com.tcl.csot.zhiye.util.PlatformToken;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型与额度（五星AI岛为准）：模型列表是用户在五星AI岛配置的供应商和模型，额度是五星AI岛的个人套餐。
 * 本地模式下返回平台统一模型，没有额度信息。
 */
@RestController
@Profile("api")
@RequestMapping("/api/llm")
public class LlmController {
    private final FivestarProperties fs;
    private final OpenclawClient openclaw;
    private final LlmRouter router;
    private final com.tcl.csot.zhiye.util.CurrentPrincipal principal;

    public LlmController(FivestarProperties fs, OpenclawClient openclaw, LlmRouter router,
                         com.tcl.csot.zhiye.util.CurrentPrincipal principal) {
        this.fs = fs;
        this.openclaw = openclaw;
        this.router = router;
        this.principal = principal;
    }

    @GetMapping("/models")
    public Map<String, Object> models(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        if (!fs.enabled()) {
            for (Catalog.Model m : Catalog.MODELS) list.add(model(m.id(), m.label(), "平台统一配置的模型", null, false, null));
            out.put("source", "local");
            out.put("models", list);
            out.put("defaultId", LlmRouter.AUTO);
            return out;
        }
        if (PlatformToken.of(req) == null)
            throw new com.tcl.csot.zhiye.exception.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_MISSING", "请先用公司统一账号登录");
        String account = router.accountOf(principal.workspaceId());
        if (account == null)
            throw new com.tcl.csot.zhiye.exception.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "使用模型需要先用公司统一账号登录");
        JsonNode available = router.availableModels(account);
        String defaultId = LlmRouter.AUTO, defaultLabel = null, freeId = null;
        for (JsonNode m : available) {
            String id = m.path("providerId").asText() + ":" + m.path("modelId").asText();
            String billing = billing(m);
            if (LlmRouter.FREE.equals(billing) && freeId == null) freeId = id;
            if (m.path("preferred").asBoolean(false)) {
                defaultId = id;
                defaultLabel = m.path("modelName").asText(m.path("modelId").asText());
            }
            list.add(model(id, m.path("modelName").asText(m.path("modelId").asText()),
                    m.path("providerName").asText(""), m.path("providerName").asText(null),
                    m.path("multimodal").asBoolean(false) || m.path("isMultimodal").asBoolean(false), billing));
        }
        list.add(0, model(LlmRouter.AUTO, "五星默认", defaultLabel == null ? "使用当前账号的首选可用模型" : "跟随五星AI岛默认模型：" + defaultLabel, null, false, null));
        out.put("source", "fivestar");
        out.put("models", list);
        // 新会话优先选免费模型；没有免费模型时跟随五星AI岛的默认模型
        out.put("defaultId", freeId != null ? freeId : defaultId);
        return out;
    }

    /** 当前用户在五星AI岛的套餐与额度（字段沿用 TokenCostUsageVO 的含义） */
    @GetMapping("/quota")
    public Map<String, Object> quota(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", fs.enabled());
        if (!fs.enabled()) return out;
        JsonNode u = openclaw.usage(PlatformToken.of(req));
        out.put("packageName", text(u, "packageName"));
        out.put("status", text(u, "quotaStatus"));
        out.put("usedTokens", num(u, "totalTokens"));
        out.put("limitTokens", num(u, "packageTotalTokens"));
        out.put("remainingTokens", num(u, "remainingTokens"));
        out.put("resetAt", text(u, "resetAt"));
        List<String> usable = new ArrayList<>();
        u.path("packageUsableModels").forEach(n -> usable.add(n.asText()));
        out.put("usableModels", usable);
        return out;
    }

    private static Map<String, Object> model(String id, String label, String desc, String provider, boolean multimodal, String billingType) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("desc", desc);
        if (provider != null) m.put("provider", provider);
        m.put("multimodal", multimodal);
        if (billingType != null) m.put("billingType", billingType);
        return m;
    }

    private static String billing(JsonNode m) {
        String b = m.path("billingType").asText("");
        if (b.isBlank()) b = m.path("billing").asText("");
        return LlmRouter.FREE.equalsIgnoreCase(b) ? LlmRouter.FREE : LlmRouter.PAID;
    }

    private static String text(JsonNode n, String k) { return n.hasNonNull(k) ? n.get(k).asText() : null; }

    private static Long num(JsonNode n, String k) { return n.hasNonNull(k) && n.get(k).canConvertToLong() ? n.get(k).asLong() : null; }
}
