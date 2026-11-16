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

    public LlmController(FivestarProperties fs, OpenclawClient openclaw) {
        this.fs = fs;
        this.openclaw = openclaw;
    }

    @GetMapping("/models")
    public Map<String, Object> models(HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        if (!fs.enabled()) {
            for (Catalog.Model m : Catalog.MODELS) list.add(model(m.id(), m.label(), "平台统一配置的模型", null, false));
            out.put("source", "local");
            out.put("models", list);
            out.put("defaultId", LlmRouter.AUTO);
            return out;
        }
        String token = PlatformToken.of(req);
        JsonNode providers = openclaw.providers(token);
        JsonNode def = openclaw.defaultModel(token);
        String defaultId = null, defaultLabel = null;
        JsonNode dm = def == null ? null : def.path("model");
        if (dm != null && dm.path("provider").hasNonNull("id") && dm.path("model").hasNonNull("modelId")) {
            defaultId = dm.path("provider").path("id").asText() + ":" + dm.path("model").path("modelId").asText();
            defaultLabel = dm.path("model").path("modelName").asText(dm.path("model").path("modelId").asText());
        }
        list.add(model(LlmRouter.AUTO, "五星默认", defaultLabel == null ? "跟随五星AI岛里设置的默认模型" : "跟随五星AI岛默认模型：" + defaultLabel, null, false));
        for (JsonNode p : providers) {
            if (!p.path("enabled").asBoolean(true)) continue;
            for (JsonNode m : p.path("models")) {
                if (!m.path("enabled").asBoolean(true) || !m.hasNonNull("modelId")) continue;
                String id = p.path("id").asText() + ":" + m.path("modelId").asText();
                list.add(model(id, m.path("modelName").asText(m.path("modelId").asText()),
                        p.path("providerName").asText(""), p.path("providerName").asText(null), m.path("isMultimodal").asBoolean(false)));
            }
        }
        out.put("source", "fivestar");
        out.put("models", list);
        out.put("defaultId", defaultId);
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

    private static Map<String, Object> model(String id, String label, String desc, String provider, boolean multimodal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("desc", desc);
        if (provider != null) m.put("provider", provider);
        m.put("multimodal", multimodal);
        return m;
    }

    private static String text(JsonNode n, String k) { return n.hasNonNull(k) ? n.get(k).asText() : null; }

    private static Long num(JsonNode n, String k) { return n.hasNonNull(k) && n.get(k).canConvertToLong() ? n.get(k).asLong() : null; }
}
