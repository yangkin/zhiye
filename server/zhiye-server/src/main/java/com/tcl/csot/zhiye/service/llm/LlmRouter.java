package com.tcl.csot.zhiye.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.client.OpenclawClient;
import com.tcl.csot.zhiye.client.UpstreamClient;
import com.tcl.csot.zhiye.config.FivestarProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 决定一次模型调用走哪里。
 * 本地模式：zhiye.llm 的统一上游。五星模式：工作空间所有者（员工）在 llm-gateway 上的路由，
 * 用量计入这个人在五星AI岛的额度；模型选择 "auto" 表示五星AI岛里设置的默认模型。
 * billingType=FREE 的模型（个人免费 / 平台免费）用公共免费虚拟 Key，PAID 用路由返回的个人虚拟密钥。
 * 路由（含虚拟密钥）只缓存在内存里，不落库、不给浏览器、不进生成的应用。
 */
@Service
public class LlmRouter {
    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    /** 模型标识：auto 或 "供应商ID:模型ID" */
    public static final String AUTO = "auto";
    /** 免费模型：用公共 Key；付费模型计入个人额度 */
    public static final String FREE = "FREE";
    public static final String PAID = "PAID";

    private record Cached(LlmRoute route, long until) {}

    private record Models(JsonNode list, long until) {}

    /** availableModels 里的一条模型（只取路由和展示用得到的字段） */
    record Slot(String providerId, String modelId, boolean multimodal, boolean preferred, String billingType) {}

    private final FivestarProperties fs;
    private final UpstreamClient upstream;
    private final OpenclawClient openclaw;
    private final JdbcClient jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Models> models = new ConcurrentHashMap<>();

    public LlmRouter(FivestarProperties fs, UpstreamClient upstream, OpenclawClient openclaw, JdbcClient jdbc) {
        this.fs = fs;
        this.upstream = upstream;
        this.openclaw = openclaw;
        this.jdbc = jdbc;
    }

    public boolean fivestar() { return fs.enabled(); }

    /** 工作空间所有者的路由；匿名工作空间在五星模式下不能调用模型（额度按个人算） */
    public LlmRoute forWorkspace(String ws, String modelRef) {
        return forWorkspace(ws, modelRef, null);
    }

    public LlmRoute forWorkspace(String ws, String modelRef, String billingType) {
        if (!fs.enabled()) return upstream.localRoute();
        String account = accountOf(ws);
        if (account == null)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "使用模型需要先用公司统一账号登录（模型额度按个人计算）");
        return forAccount(account, modelRef, billingType);
    }

    /** 任务的路由：这条消息指定的模型优先，其次是应用上选的模型 */
    public LlmRoute forTask(String taskId) {
        if (!fs.enabled()) return upstream.localRoute();
        var row = jdbc.sql("select t.workspace_id, t.input::text, a.model from task t join app a on a.id = t.app_id where t.id = ?")
                .param(taskId).query((rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)})
                .optional().orElseThrow(() -> ApiException.notFound("任务"));
        JsonNode input = Json.read(row[1]);
        String model = input.hasNonNull("model") ? input.get("model").asText() : row[2];
        String billing = input.hasNonNull("modelBilling") ? input.get("modelBilling").asText() : null;
        return forWorkspace(row[0], model, billing);
    }

    public LlmRoute forAccount(String account, String modelRef) {
        return forAccount(account, modelRef, null);
    }

    /**
     * @param billingType 前端随消息传来的 FREE / PAID；为空时按模型列表里的 billingType 判断（查不到按付费处理）
     */
    public LlmRoute forAccount(String account, String modelRef, String billingType) {
        String ref = normalize(modelRef);
        String billing = normalizeBilling(billingType);
        Boolean multimodal = null;
        if (ref == null) {
            Slot selected = defaultSlot(account);
            if (selected == null)
                throw new ApiException(HttpStatus.BAD_GATEWAY, "FIVESTAR_NO_MODEL", "五星AI岛里还没有可用的模型，请先在五星AI岛配置模型");
            ref = selected.providerId() + ":" + selected.modelId();
            multimodal = selected.multimodal();
            if (billing == null) billing = selected.billingType();
        }
        if (billing == null) billing = billingOf(account, ref);
        String key = account + "|" + ref + "|" + billing;
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && c.until > now) return c.route;
        int i = ref.indexOf(':');
        JsonNode d = openclaw.route(account, ref.substring(0, i), ref.substring(i + 1), billing);
        String base = d.path("baseUrl").asText(""), personalKey = d.path("virtualKey").asText(""), model = d.path("providerModelName").asText("");
        if (base.isBlank() || model.isBlank())
            throw new ApiException(HttpStatus.BAD_GATEWAY, "FIVESTAR_NO_MODEL", "五星AI岛里还没有可用的模型，请先在五星AI岛配置模型");
        String apiKey = personalKey;
        if (FREE.equals(billing)) {
            String pub = fs.publicKey();
            if (!pub.isBlank()) apiKey = pub;
            else log.warn("billingType=FREE 但没有配置公共免费 Key（{}），退回路由下发的个人 Key", fs.publicKeyEnv());
        }
        if (apiKey.isBlank())
            throw new ApiException(HttpStatus.BAD_GATEWAY, "FIVESTAR_NO_KEY", "模型 Key 未下发，请稍后再试");
        if (multimodal == null) multimodal = multimodalOf(account, ref);
        LlmRoute r = new LlmRoute(base.replaceAll("/+$", ""), apiKey, model, d.path("providerName").asText("fivestar"),
                Map.of("X-User-Id", account, "X-Project-Id", fs.projectId()), account,
                d.path("modelName").asText(d.path("modelId").asText(model)), false, multimodal);
        cache.put(key, new Cached(r, now + Math.max(0, fs.routeCacheSeconds()) * 1000L));
        return r;
    }

    /** 员工可用的模型（个人模型 + 平台免费模型，带 billingType），按 routeCacheSeconds 缓存 */
    public JsonNode availableModels(String account) {
        Models m = models.get(account);
        long now = System.currentTimeMillis();
        if (m != null && m.until > now) return m.list;
        JsonNode list = openclaw.availableModels(account);
        models.put(account, new Models(list, now + Math.max(0, fs.routeCacheSeconds()) * 1000L));
        return list;
    }

    /** 模型的 billingType：找不到或没标注时按 PAID（用个人 Key，和旧行为一致） */
    String billingOf(String account, String ref) {
        Slot slot = slot(account, ref);
        return slot == null ? PAID : slot.billingType();
    }

    private Slot slot(String account, String ref) {
        try {
            for (JsonNode m : availableModels(account)) {
                String id = m.path("providerId").asText() + ":" + m.path("modelId").asText();
                if (id.equals(ref)) return slotOf(m);
            }
        } catch (Exception e) {
            log.debug("读取模型列表失败（{}）：{}", ref, e.getMessage());
        }
        return null;
    }

    /** 新会话 / auto：优先免费模型（首选且免费的优先，其次任意免费），都要不到再按个人首选、首个可用 */
    private Slot defaultSlot(String account) {
        JsonNode available = availableModels(account);
        Slot firstFree = null, preferredPaid = null, firstPaid = null;
        for (JsonNode m : available) {
            Slot s = slotOf(m);
            if (FREE.equals(s.billingType())) {
                if (s.preferred()) return s;
                if (firstFree == null) firstFree = s;
            } else {
                if (s.preferred() && preferredPaid == null) preferredPaid = s;
                if (firstPaid == null) firstPaid = s;
            }
        }
        if (firstFree != null) return firstFree;
        return preferredPaid != null ? preferredPaid : firstPaid;
    }

    private static Slot slotOf(JsonNode m) {
        boolean multimodal = m.has("multimodal") ? m.path("multimodal").asBoolean(true)
                : m.has("isMultimodal") ? m.path("isMultimodal").asBoolean(true) : true;
        return new Slot(m.path("providerId").asText(""), m.path("modelId").asText(""),
                multimodal, m.path("preferred").asBoolean(false), billingOf(m));
    }

    private static String billingOf(JsonNode m) {
        String b = m.path("billingType").asText("");
        if (b.isBlank()) b = m.path("billing").asText("");
        return FREE.equalsIgnoreCase(b) ? FREE : PAID;
    }

    /** 模型的多模态能力（availableModels 里的 multimodal 标记）；查不到或调用失败时按多模态处理，不额外降级 */
    private boolean multimodalOf(String account, String ref) {
        try {
            Slot slot = slot(account, ref);
            return slot == null || slot.multimodal();
        } catch (Exception e) {
            log.debug("读取模型多模态能力失败（{}）：{}", ref, e.getMessage());
        }
        return true;
    }

    /** 员工的多模态模型路由（带图调用自动切换用）：preferred 的多模态模型优先；没有可用多模态模型时返回 null */
    public LlmRoute visionFor(String account) {
        try {
            JsonNode available = availableModels(account);
            Slot selected = null;
            for (JsonNode m : available) {
                Slot s = slotOf(m);
                if (s.multimodal() && s.preferred()) { selected = s; break; }
            }
            if (selected == null)
                for (JsonNode m : available) {
                    Slot s = slotOf(m);
                    if (s.multimodal()) { selected = s; break; }
                }
            if (selected == null) return null;
            return forAccount(account, selected.providerId() + ":" + selected.modelId(), selected.billingType());
        } catch (Exception e) {
            log.debug("没有可用的多模态模型路由（{}）：{}", account, e.getMessage());
            return null;
        }
    }

    /** 网关拒绝了虚拟密钥（被回收或重建）时清掉缓存，下次重新取 */
    public void evict(String account) {
        if (account != null) {
            cache.keySet().removeIf(k -> k.startsWith(account + "|"));
            models.remove(account);
        }
    }

    /** auto、空值和老的平台统一模型标识都按「五星默认模型」处理 */
    static String normalize(String ref) {
        if (ref == null || ref.isBlank() || AUTO.equals(ref) || ref.indexOf(':') <= 0 || ref.endsWith(":")) return null;
        return ref;
    }

    /** 只认 FREE / PAID，其余当没传 */
    public static String normalizeBilling(String billing) {
        if (billing == null) return null;
        String b = billing.trim().toUpperCase();
        return FREE.equals(b) || PAID.equals(b) ? b : null;
    }

    public String accountOf(String ws) {
        if (ws == null || ws.isBlank()) return null;
        return jdbc.sql("select u.login_name from workspace w join app_user u on u.id = w.owner_user_id where w.id = ?")
                .param(ws).query(String.class).optional().orElse(null);
    }
}
