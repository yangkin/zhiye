package com.tcl.csot.zhiye.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.client.OpenclawClient;
import com.tcl.csot.zhiye.client.UpstreamClient;
import com.tcl.csot.zhiye.config.FivestarProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 决定一次模型调用走哪里。
 * 本地模式：zhiye.llm 的统一上游。五星模式：工作空间所有者（员工）在 llm-gateway 上的路由，
 * 用量计入这个人在五星AI岛的额度；模型选择 "auto" 表示五星AI岛里设置的默认模型。
 * 路由（含虚拟密钥）只缓存在内存里，不落库、不给浏览器、不进生成的应用。
 */
@Service
public class LlmRouter {
    /** 模型标识：auto 或 "供应商ID:模型ID" */
    public static final String AUTO = "auto";

    private record Cached(LlmRoute route, long until) {}

    private final FivestarProperties fs;
    private final UpstreamClient upstream;
    private final OpenclawClient openclaw;
    private final JdbcClient jdbc;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public LlmRouter(FivestarProperties fs, UpstreamClient upstream, OpenclawClient openclaw, JdbcClient jdbc) {
        this.fs = fs;
        this.upstream = upstream;
        this.openclaw = openclaw;
        this.jdbc = jdbc;
    }

    public boolean fivestar() { return fs.enabled(); }

    /** 工作空间所有者的路由；匿名工作空间在五星模式下不能调用模型（额度按个人算） */
    public LlmRoute forWorkspace(String ws, String modelRef) {
        if (!fs.enabled()) return upstream.localRoute();
        String account = accountOf(ws);
        if (account == null)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "使用模型需要先用公司统一账号登录（模型额度按个人计算）");
        return forAccount(account, modelRef);
    }

    /** 任务的路由：这条消息指定的模型优先，其次是应用上选的模型 */
    public LlmRoute forTask(String taskId) {
        if (!fs.enabled()) return upstream.localRoute();
        var row = jdbc.sql("select t.workspace_id, t.input::text, a.model from task t join app a on a.id = t.app_id where t.id = ?")
                .param(taskId).query((rs, i) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)})
                .optional().orElseThrow(() -> ApiException.notFound("任务"));
        JsonNode input = Json.read(row[1]);
        String model = input.hasNonNull("model") ? input.get("model").asText() : row[2];
        return forWorkspace(row[0], model);
    }

    public LlmRoute forAccount(String account, String modelRef) {
        String ref = normalize(modelRef);
        String key = account + "|" + (ref == null ? AUTO : ref);
        Cached c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && c.until > now) return c.route;
        JsonNode d = openclaw.route(account, ref);
        String base = d.path("baseUrl").asText(""), key2 = d.path("virtualKey").asText(""), model = d.path("providerModelName").asText("");
        if (base.isBlank() || key2.isBlank() || model.isBlank())
            throw new ApiException(HttpStatus.BAD_GATEWAY, "FIVESTAR_NO_MODEL", "五星AI岛里还没有可用的模型，请先在五星AI岛配置模型");
        LlmRoute r = new LlmRoute(base.replaceAll("/+$", ""), key2, model, d.path("providerName").asText("fivestar"),
                Map.of("X-User-Id", account, "X-Project-Id", fs.projectId()), account,
                d.path("modelName").asText(d.path("modelId").asText(model)), false);
        cache.put(key, new Cached(r, now + Math.max(0, fs.routeCacheSeconds()) * 1000L));
        return r;
    }

    /** 网关拒绝了虚拟密钥（被回收或重建）时清掉缓存，下次重新取 */
    public void evict(String account) {
        if (account != null) cache.keySet().removeIf(k -> k.startsWith(account + "|"));
    }

    /** auto、空值和老的平台统一模型标识都按「五星默认模型」处理 */
    static String normalize(String ref) {
        if (ref == null || ref.isBlank() || AUTO.equals(ref) || ref.indexOf(':') <= 0 || ref.endsWith(":")) return null;
        return ref;
    }

    public String accountOf(String ws) {
        if (ws == null || ws.isBlank()) return null;
        return jdbc.sql("select u.login_name from workspace w join app_user u on u.id = w.owner_user_id where w.id = ?")
                .param(ws).query(String.class).optional().orElse(null);
    }
}
