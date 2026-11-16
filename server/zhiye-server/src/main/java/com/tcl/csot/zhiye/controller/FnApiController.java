package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.cloud.AppAiService;
import com.tcl.csot.zhiye.service.cloud.CloudService;
import com.tcl.csot.zhiye.service.cloud.FunctionService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.source.DataSourceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 云函数里的 ctx.* 回调到这里（只接受本机、带本次调用的临时令牌）：
 * ctx.db 不受权限预设限制（身份带 via=fn:名称），字段规则与状态流转照样执行；ctx.source / ctx.ai 复用平台能力。
 */
@RestController
@RequestMapping("/fnapi")
public class FnApiController {
    private final FunctionService fns;
    private final CloudService cloud;
    private final DataSourceService sources;
    private final AppAiService ai;

    public FnApiController(FunctionService fns, CloudService cloud, DataSourceService sources, AppAiService ai) {
        this.fns = fns;
        this.cloud = cloud;
        this.sources = sources;
        this.ai = ai;
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, Object>> badData(org.springframework.dao.DataAccessException e) {
        ApiException bad = CloudService.badValue(e);
        if (bad == null) throw e;
        return ResponseEntity.badRequest().body(Map.of("code", bad.code(), "message", bad.getMessage()));
    }

    private FunctionService.FnToken token(HttpServletRequest req) {
        String addr = req.getRemoteAddr();
        if (!("127.0.0.1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr) || "::1".equals(addr)))
            throw new ApiException(HttpStatus.FORBIDDEN, "FN_ONLY", "只供云函数调用");
        String h = req.getHeader("Authorization");
        return fns.verify(h != null && h.startsWith("Bearer ") ? h.substring(7) : h);
    }

    private static String table(JsonNode b) { return b.path("table").asText(null); }

    @PostMapping("/db/{op}")
    public Object db(@PathVariable String op, @RequestBody JsonNode b, HttpServletRequest req) {
        var t = token(req);
        var c = cloud.forSite(t.appId());
        return switch (op) {
            case "list" -> {
                Map<String, String> q = new LinkedHashMap<>();
                b.path("query").fields().forEachRemaining(e -> q.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString()));
                yield cloud.list(c, table(b), q, t.who());
            }
            case "get" -> Collections.singletonMap("row", cloud.get(c, table(b), b.path("id").asLong(), t.who()).orElse(null));
            case "insert" -> cloud.insert(c, table(b), b.get("row"), t.who());
            case "update" -> cloud.update(c, table(b), b.path("id").asLong(), b.get("row"), t.who());
            case "remove" -> { cloud.delete(c, table(b), b.path("id").asLong(), t.who()); yield Map.of(); }
            case "increment" -> cloud.increment(c, table(b), b.path("id").asLong(), b, t.who());
            case "aggregate" -> Map.of("rows", cloud.aggregate(c, table(b), b.get("spec"), t.who()));
            case "transaction" -> Collections.singletonMap("results", cloud.transaction(c, b.get("ops"), t.who()));
            default -> throw ApiException.notFound("接口");
        };
    }

    @PostMapping("/storage/put")
    public Map<String, Object> put(@RequestBody JsonNode b, HttpServletRequest req) throws Exception {
        var t = token(req);
        byte[] data = Base64.getDecoder().decode(b.path("dataB64").asText(""));
        return cloud.putFile(cloud.forSite(t.appId()), b.path("path").asText(null), data);
    }

    @PostMapping("/storage/get")
    public Map<String, Object> get(@RequestBody JsonNode b, HttpServletRequest req) throws Exception {
        var t = token(req);
        var bytes = cloud.readFile(cloud.forSite(t.appId()), b.path("path").asText(null));
        return Collections.singletonMap("dataB64", bytes.map(x -> Base64.getEncoder().encodeToString(x)).orElse(null));
    }

    @PostMapping("/source")
    public Map<String, Object> source(@RequestBody JsonNode b, HttpServletRequest req) {
        var t = token(req);
        return sources.query(t.appId(), b.path("name").asText(""), b.get("params"), "fn");
    }

    @PostMapping("/ai")
    public Map<String, Object> ai(@RequestBody JsonNode b, HttpServletRequest req) {
        var t = token(req);
        return ai.chat(t.appId(), t.who(), b);
    }
}
