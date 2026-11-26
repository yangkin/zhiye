package com.tcl.csot.zhiye.service.source;

import com.tcl.csot.zhiye.config.SourceProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.service.cloud.CloudService;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据源：从 AGW 添加当前用户可调用的查询接口（凭证只存在服务端），应用绑定后由平台代理调用。
 * 手工登记已关闭（create 一律 403）；浏览器只拿到 key 和参数定义；地址、凭证、请求头都在这里拼装。
 */
@Service
public class DataSourceService {
    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);
    static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{1,47}");
    static final Pattern PARAM = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,47}");
    static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]{0,47})}");
    static final Pattern HEADER = Pattern.compile("[A-Za-z0-9-]{1,64}");
    static final Pattern ENV = Pattern.compile("[A-Z_][A-Z0-9_]{0,63}");
    static final Pattern APP_KEY = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([T ][0-9:.+\\-Z]{0,20})?");
    static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "date", "json");
    static final Set<String> AUTH = Set.of("none", "bearer", "header", "basic", "agw", "agw_key", "agw_client", "agw_user");
    static final Set<String> RESERVED_HEADERS = Set.of("host", "content-length", "connection", "content-type", "transfer-encoding", "upgrade", "expect");
    static final int MAX_PER_APP = 100;
    static final int MAX_PER_WORKSPACE = 50;

    public record Param(String name, String type, boolean required, String description, String example, String position) {
        public Param(String name, String type, boolean required, String description, String example) {
            this(name, type, required, description, example, null);
        }
    }

    public record Source(String id, String workspaceId, String key, String name, String description, String method, String url,
                         List<Param> params, String sample, String authType, String authHeader, String secretEnc, String secretEnv,
                         String createdAt, String provider, String externalId) {
        public Source(String id, String workspaceId, String key, String name, String description, String method, String url,
                      List<Param> params, String sample, String authType, String authHeader, String secretEnc, String secretEnv,
                      String createdAt) {
            this(id, workspaceId, key, name, description, method, url, params, sample, authType, authHeader, secretEnc, secretEnv,
                    createdAt, "manual", null);
        }
        boolean platform() { return workspaceId == null; }
    }

    private final JdbcClient jdbc;
    private final AppRepository apps;
    private final CloudService cloud;
    private final SourceProperties props;
    private final AgwClientTokenService agwClient;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    public DataSourceService(JdbcClient jdbc, AppRepository apps, CloudService cloud, SourceProperties props, AgwClientTokenService agwClient) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.cloud = cloud;
        this.props = props;
        this.agwClient = agwClient;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(Math.min(5, props.timeoutSeconds())))
                .proxy(java.net.ProxySelector.of(null))
                .build();
    }

    // ---------------- 登记 ----------------

    public List<Map<String, Object>> list(String ws) {
        return visible(ws).stream().map(s -> view(s, ws)).toList();
    }

    public Map<String, Object> get(String ws, String id) {
        return view(requireVisible(ws, id), ws);
    }

    /** 手工登记已关闭：数据源只能在 AGW 侧登记，织页通过「选择可调用 API」导入（importExternal）。 */
    public synchronized Map<String, Object> create(String ws, JsonNode body) {
        throw new ApiException(HttpStatus.FORBIDDEN, "SOURCE_MANUAL_DISABLED", "数据源请在 API 网关（AGW）侧登记；这里用「选择可调用 API」添加你已有权限的接口");
    }

    public synchronized Map<String, Object> update(String ws, String id, JsonNode body) {
        Source old = requireOwned(ws, id);
        Source s = parse(body, old, ws);
        if (keyTaken(ws, s.key(), id)) throw ApiException.conflict("SOURCE_KEY_TAKEN", "调用名「" + s.key() + "」已经被使用，请换一个");
        jdbc.sql("""
                update data_source set key = ?, name = ?, description = ?, method = ?, url = ?, params = ?::jsonb, sample = ?,
                    auth_type = ?, auth_header = ?, secret_enc = ?, updated_at = now() where id = ?
                """).params(s.key(), s.name(), s.description(), s.method(), s.url(), Json.write(s.params()), s.sample(),
                s.authType(), s.authHeader(), s.secretEnc(), id).update();
        return get(ws, id);
    }

    public void delete(String ws, String id) {
        requireOwned(ws, id);
        jdbc.sql("update data_source set deleted_at = now() where id = ?").param(id).update();
        jdbc.sql("delete from app_data_source where source_id = ?").param(id).update();
    }

    /** 登记人试调用：不需要绑定应用 */
    public Map<String, Object> test(String ws, String id, JsonNode params) {
        return call(requireVisible(ws, id), params, null, "test");
    }

    public List<Map<String, Object>> recentCalls(String ws, String id) {
        requireVisible(ws, id);
        return jdbc.sql("select app_id, origin, status, ok, duration_ms, error, created_at from data_source_call where source_id = ? order by id desc limit 20")
                .param(id).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("appId", rs.getString(1));
                    m.put("origin", rs.getString(2));
                    m.put("status", rs.getObject(3));
                    m.put("ok", rs.getBoolean(4));
                    m.put("durationMs", rs.getInt(5));
                    m.put("error", rs.getString(6));
                    m.put("at", Db.iso(Db.ts(rs, "created_at")));
                    return m;
                }).list();
    }

    // ---------------- 绑定 ----------------

    public List<Map<String, Object>> bound(String ws, String appId) {
        requireApp(ws, appId);
        return boundSources(appId).stream().map(s -> view(s, ws)).toList();
    }

    public List<Map<String, Object>> bind(String ws, String appId, String sourceId) {
        requireApp(ws, appId);
        Source s = requireVisible(ws, sourceId);
        List<Source> cur = boundSources(appId);
        if (cur.stream().noneMatch(x -> x.id().equals(s.id()))) {
            if (cur.size() >= MAX_PER_APP) throw ApiException.validation("一个应用最多绑定 " + MAX_PER_APP + " 个数据源");
            if (cur.stream().anyMatch(x -> x.key().equals(s.key())))
                throw ApiException.conflict("SOURCE_KEY_TAKEN", "应用里已经有调用名为「" + s.key() + "」的数据源");
            jdbc.sql("insert into app_data_source (app_id, source_id) values (?, ?) on conflict do nothing").params(appId, s.id()).update();
        }
        return bound(ws, appId);
    }

    public List<Map<String, Object>> unbind(String ws, String appId, String sourceId) {
        requireApp(ws, appId);
        jdbc.sql("delete from app_data_source where app_id = ? and source_id = ?").params(appId, sourceId).update();
        return bound(ws, appId);
    }

    public List<Source> boundSources(String appId) {
        return jdbc.sql("""
                select s.* from data_source s join app_data_source b on b.source_id = s.id
                where b.app_id = ? and s.deleted_at is null order by b.created_at, s.key
                """).param(appId).query((rs, i) -> row(rs)).list();
    }

    /** 创建应用时带的数据源：都要对当前工作空间可见 */
    public List<String> validateIds(String ws, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>(ids);
        if (out.size() > MAX_PER_APP) throw ApiException.validation("一个应用最多绑定 " + MAX_PER_APP + " 个数据源");
        Set<String> keys = new HashSet<>();
        for (String id : out) {
            Source s = jdbc.sql("select * from data_source where id = ? and deleted_at is null and (workspace_id = ? or workspace_id is null)")
                    .params(id, ws).query((rs, i) -> row(rs)).optional()
                    .orElseThrow(() -> ApiException.validation("选择的数据源不存在或没有权限"));
            if (!keys.add(s.key())) throw ApiException.validation("数据源的调用名重复了：" + s.key());
        }
        return List.copyOf(out);
    }

    public void bindAll(String appId, List<String> ids) {
        for (String id : ids)
            jdbc.sql("insert into app_data_source (app_id, source_id) values (?, ?) on conflict do nothing").params(appId, id).update();
    }

    public boolean hasBindings(String appId) {
        return jdbc.sql("select count(*) from app_data_source b join data_source s on s.id = b.source_id where b.app_id = ? and s.deleted_at is null")
                .param(appId).query(Long.class).single() > 0;
    }

    // ---------------- 调用（应用运行时） ----------------

    /** 应用调用：只能调用这个应用绑定的数据源 */
    public Map<String, Object> query(String appId, String key, JsonNode params, String origin) {
        Source s = boundSources(appId).stream().filter(x -> x.key().equals(key)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SOURCE_NOT_BOUND", "这个应用没有绑定数据源「" + key + "」"));
        return call(s, params, appId, origin);
    }

    Map<String, Object> call(Source s, JsonNode params, String appId, String origin) {
        long start = System.nanoTime();
        Integer status = null;
        try {
            HttpRequest req = buildRequest(s, params, appId);
            HttpResponse<InputStream> res;
            try {
                res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "SOURCE_TIMEOUT", "数据源 " + props.timeoutSeconds() + " 秒内没有响应");
            } catch (java.io.IOException e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_UNREACHABLE", "连不上数据源：" + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()));
            }
            status = res.statusCode();
            byte[] body;
            try (InputStream in = res.body()) {
                body = in.readNBytes(props.maxResponseBytes() + 1);
            }
            if (body.length > props.maxResponseBytes())
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_TOO_LARGE", "数据源返回的内容超过 " + (props.maxResponseBytes() / 1024 / 1024) + " MB，请加上分页或筛选参数");
            String text = new String(body, StandardCharsets.UTF_8);
            if (status >= 400)
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_UPSTREAM", "数据源返回 " + status + "：" + snippet(text));
            Object data;
            try { data = text.isBlank() ? null : Json.M.readTree(text); } catch (Exception e) { data = text; }
            if (status >= 300 && status < 400) {
                String location = res.headers().firstValue("Location").orElse("");
                ObjectNode redirect = Json.obj();
                redirect.put("location", location);
                redirect.put("message", "数据源返回跳转；平台没有自动跟随，请由应用按业务需要处理");
                data = redirect;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("data", data);
            out.put("status", status);
            record(s.id(), appId, origin, status, true, start, null);
            return out;
        } catch (ApiException e) {
            record(s.id(), appId, origin, status, false, start, e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_INTERRUPTED", "请求被中断");
        } catch (Exception e) {
            record(s.id(), appId, origin, status, false, start, e.toString());
            log.warn("数据源 {} 调用失败：{}", s.key(), e.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_FAILED", "数据源调用失败");
        }
    }

    HttpRequest buildRequest(Source s, JsonNode params, String appId) throws Exception {
        Map<String, String> values = checkParams(s, params);
        Map<String, Param> defs = new HashMap<>();
        for (Param p : s.params()) defs.put(p.name(), p);
        Set<String> inPath = new HashSet<>();
        Matcher m = PLACEHOLDER.matcher(s.url());
        StringBuilder url = new StringBuilder();
        while (m.find()) {
            inPath.add(m.group(1));
            m.appendReplacement(url, Matcher.quoteReplacement(encode(values.getOrDefault(m.group(1), ""))));
        }
        m.appendTail(url);
        boolean post = "POST".equals(s.method());
        StringBuilder qs = new StringBuilder();
        for (var e : values.entrySet()) {
            if (inPath.contains(e.getKey())) continue;
            Param p = defs.get(e.getKey());
            String position = p.position() == null ? (post ? "body" : "query") : p.position();
            if ("query".equals(position))
                qs.append(qs.isEmpty() ? "" : "&").append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        if (!qs.isEmpty()) url.append(url.indexOf("?") >= 0 ? "&" : "?").append(qs);
        URI uri = URI.create(url.toString());
        checkHost(uri);
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("Accept", "application/json, text/plain;q=0.8, */*;q=0.5")
                .header("User-Agent", "zhiye-source/1");
        if (appId != null) b.header("X-Zhiye-App", appId);
        for (var e : values.entrySet()) {
            Param p = defs.get(e.getKey());
            if (!"header".equals(p.position())) continue;
            if (!HEADER.matcher(p.name()).matches() || RESERVED_HEADERS.contains(p.name().toLowerCase(Locale.ROOT)))
                throw ApiException.validation("数据源 Header 参数 " + p.name() + " 不允许使用");
            b.header(p.name(), e.getValue());
        }
        String secret = "none".equals(s.authType()) || "agw_client".equals(s.authType()) || "agw_user".equals(s.authType()) ? "" : secret(s);
        switch (s.authType()) {
            case "bearer" -> b.header("Authorization", "Bearer " + secret);
            case "agw_client" -> b.header("Authorization", agwClient.authorization());
            case "agw_user" -> {
                String loginName = workspaceLoginName(s, appId);
                if (s.authHeader() != null && !s.authHeader().isBlank()) {
                    b.header("Authentication", agwClient.metisAccessTokenForUser(loginName));
                    b.header("X-Project-Id", s.authHeader());
                } else {
                    b.header("Authorization", agwClient.authorizationForUser(loginName));
                }
            }
            case "header" -> b.header(s.authHeader(), secret);
            case "basic" -> b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
            case "agw_key" -> {
                b.header("Authorization", "Bearer " + secret);
                b.header("x-agw-request-id", UUID.randomUUID().toString());
            }
            case "agw" -> {
                String timestamp = Long.toString(System.currentTimeMillis());
                String raw = String.join(";", s.authHeader(), secret, timestamp);
                String signature = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(raw.getBytes(StandardCharsets.UTF_8)));
                b.header("x-agw-app-key", s.authHeader());
                b.header("x-agw-time-stamp", timestamp);
                b.header("x-agw-signature", signature);
                b.header("x-agw-request-id", UUID.randomUUID().toString());
            }
            default -> { }
        }
        if (post) {
            if (values.containsKey("_body")) {
                b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(values.get("_body")));
            } else {
                ObjectNode json = Json.obj();
                for (var e : values.entrySet()) {
                    if (inPath.contains(e.getKey())) continue;
                    Param p = defs.get(e.getKey());
                    String position = p.position() == null ? "body" : p.position();
                    if (!"body".equals(position)) continue;
                    switch (p.type()) {
                        case "number" -> json.put(e.getKey(), Double.parseDouble(e.getValue()));
                        case "integer" -> json.put(e.getKey(), Long.parseLong(e.getValue()));
                        case "boolean" -> json.put(e.getKey(), Boolean.parseBoolean(e.getValue()));
                        case "json" -> json.set(e.getKey(), Json.read(e.getValue()));
                        default -> json.put(e.getKey(), e.getValue());
                    }
                }
                b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(json)));
            }
        } else {
            b.GET();
        }
        return b.build();
    }

    /** 按参数定义校验：未知参数、缺少必填、类型不对都直接拒绝（中文原因） */
    static Map<String, String> checkParams(Source s, JsonNode params) {
        if (params != null && !params.isNull() && !params.isObject()) throw ApiException.validation("参数必须是对象，例如 { date: '2026-01-01' }");
        Map<String, Param> defs = new LinkedHashMap<>();
        for (Param p : s.params()) defs.put(p.name(), p);
        Map<String, String> out = new LinkedHashMap<>();
        if (params != null && params.isObject()) {
            for (var it = params.fields(); it.hasNext(); ) {
                var e = it.next();
                Param p = defs.get(e.getKey());
                if (p == null) throw ApiException.validation("数据源「" + s.key() + "」没有参数 " + e.getKey() + (defs.isEmpty() ? "（它不需要参数）" : "，可用参数：" + String.join("、", defs.keySet())));
                JsonNode v = e.getValue();
                if (v == null || v.isNull() || (v.isTextual() && v.asText().isEmpty())) continue;
                out.put(p.name(), coerce(p, v));
            }
        }
        for (Param p : s.params())
            if (p.required() && !out.containsKey(p.name())) throw ApiException.validation("缺少参数 " + p.name() + (p.description().isBlank() ? "" : "（" + p.description() + "）"));
        return out;
    }

    private static String coerce(Param p, JsonNode v) {
        if ("json".equals(p.type())) {
            try {
                JsonNode parsed = v.isTextual() ? Json.read(v.asText()) : v;
                if (parsed == null || parsed.isNull()) throw ApiException.validation("参数 " + p.name() + " 必须是 JSON");
                String json = Json.write(parsed);
                if (json.length() > 20000) throw ApiException.validation("参数 " + p.name() + " 太长（最多 20000 字）");
                return json;
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw ApiException.validation("参数 " + p.name() + " 必须是合法 JSON");
            }
        }
        if (v.isContainerNode()) throw ApiException.validation("参数 " + p.name() + " 不能是对象或数组");
        String t = v.asText();
        if (t.length() > 1000) throw ApiException.validation("参数 " + p.name() + " 太长（最多 1000 字）");
        String bad = "参数 " + p.name() + " 应该是";
        switch (p.type()) {
            case "number" -> {
                try { double d = Double.parseDouble(t); if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException(); }
                catch (NumberFormatException e) { throw ApiException.validation(bad + "数字"); }
            }
            case "integer" -> {
                try { Long.parseLong(t); } catch (NumberFormatException e) { throw ApiException.validation(bad + "整数"); }
            }
            case "boolean" -> {
                if (!t.equals("true") && !t.equals("false")) throw ApiException.validation(bad + " true 或 false");
            }
            case "date" -> {
                if (!DATE.matcher(t).matches()) throw ApiException.validation(bad + "日期，例如 2026-01-31");
            }
            default -> { }
        }
        return t;
    }

    /** 拒绝链路本地 / 元数据 / 组播地址；生产环境拒绝本机；配置了白名单时只允许白名单主机 */
    void checkHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw ApiException.validation("数据源地址无效");
        String h = host.toLowerCase(Locale.ROOT);
        if (!props.hosts().isEmpty() && props.hosts().stream().map(x -> x.trim().toLowerCase(Locale.ROOT)).filter(x -> !x.isEmpty())
                .noneMatch(x -> x.startsWith("*.") ? h.endsWith(x.substring(1)) : h.equals(x)))
            throw new ApiException(HttpStatus.FORBIDDEN, "SOURCE_HOST_BLOCKED", "数据源地址 " + host + " 不在平台允许的范围内");
        InetAddress[] addrs;
        try { addrs = InetAddress.getAllByName(host); }
        catch (Exception e) { throw new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_UNREACHABLE", "解析不了数据源地址 " + host); }
        for (InetAddress a : addrs) {
            if (a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress() || isMetadata(a))
                throw new ApiException(HttpStatus.FORBIDDEN, "SOURCE_HOST_BLOCKED", "数据源地址 " + host + " 不允许访问");
            if (a.isLoopbackAddress() && !props.allowLoopback())
                throw new ApiException(HttpStatus.FORBIDDEN, "SOURCE_HOST_BLOCKED", "数据源地址不能指向平台本机");
        }
    }

    private static boolean isMetadata(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xff) == 100 && (b[2] & 0xff) == 100 && (b[3] & 0xff) == 200;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String snippet(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? "（无内容）" : t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private void record(String sourceId, String appId, String origin, Integer status, boolean ok, long start, String error) {
        try {
            jdbc.sql("insert into data_source_call (source_id, app_id, origin, status, ok, duration_ms, error) values (?,?,?,?,?,?,?)")
                    .params(sourceId, appId, origin, status, ok, (int) ((System.nanoTime() - start) / 1_000_000),
                            error == null ? null : error.length() > 300 ? error.substring(0, 300) : error).update();
        } catch (Exception e) {
            log.debug("记录数据源调用失败：{}", e.toString());
        }
    }

    // ---------------- 给 agent / 计划 / 问答的说明 ----------------

    /** 编码指令：已绑定数据源的调用名、参数、返回示例 */
    public String instruction(String appId) {
        return instruction(appId, false);
    }

    public String instruction(String appId, boolean isService) {
        List<Source> list = boundSources(appId);
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## 已绑定的数据源（只读，真实数据）\n");
        if (isService) {
            sb.append("本应用为全栈后端服务：**服务端**直接用已注入的环境变量调用 `POST ${ZHIYE_CONNECTOR_API}/source/query`（Header `Authorization: Bearer ${ZHIYE_CONNECTOR_TOKEN}`，Body `{ \"key\": \"调用名\", \"params\": { ... } }`，返回 `{ \"data\": <上游原始JSON>, \"status\": 200 }`），由平台代发请求并注入凭证与审计；")
              .append("严禁在代码里写死上游地址或凭证，也不要绕过代理直连。后端封装成自己的 `/api/...` 接口给前端消费，并处理加载中、错误（返回可读 message）和空数据状态。\n");
        } else {
            sb.append("用 `await window.zhiye.source.query('调用名', { 参数 })` 取数，返回接口原始 JSON；平台代理请求并注入凭证，")
              .append("**不要在代码里写接口地址或凭证，也不要用 fetch 直连**。要处理加载中、失败（显示 error.message）和空数据三种状态；`window.zhiye?.source` 不存在时（本地构建自测）显示「数据源只在预览和发布页面可用」，不要报错。有内置技能 data-sources 时先读它。\n");
        }
        int budget = 12000;
        for (Source s : list) {
            sb.append("\n### ").append(s.name()).append("（调用名 `").append(s.key()).append("`）\n");
            if (!s.description().isBlank()) sb.append(s.description()).append('\n');
            if (s.params().isEmpty()) sb.append("参数：无\n");
            else {
                sb.append("参数：\n");
                for (Param p : s.params()) {
                    sb.append("- `").append(p.name()).append("`（").append(p.type()).append(p.required() ? "，必填" : "，可选")
                            .append("，位置 ").append(p.position() == null ? ("POST".equals(s.method()) ? "body" : "query") : p.position()).append("）")
                            .append(p.description().isBlank() ? "" : "：" + p.description())
                            .append(p.example().isBlank() ? "" : "，例如 " + p.example());
                    if (sensitiveName(p.name()))
                        sb.append("；敏感凭证必须由用户在页面密码框当次填写，禁止写死、持久化到 localStorage 或打印日志");
                    sb.append('\n');
                }
            }
            if (s.sample() != null && !s.sample().isBlank() && budget > 0) {
                String sample = s.sample().length() > Math.min(3000, budget) ? s.sample().substring(0, Math.min(3000, budget)) + "\n…（已截断）" : s.sample();
                budget -= sample.length();
                sb.append("返回示例：\n```json\n").append(sample).append("\n```\n");
            }
        }
        return sb.toString();
    }

    /** 计划 / 工作台问答：只列名称、调用名和一句说明 */
    public String summary(String appId) {
        return summary(boundSources(appId), "应用已绑定的数据源（只读接口，可以直接用真实数据）");
    }

    /** 会话里已选择的数据源；已删除或无权访问的旧选择自动忽略。 */
    public List<Source> visibleSelection(String ws, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Map<String, Source> visibleById = new HashMap<>();
        for (Source s : visible(ws)) visibleById.put(s.id(), s);
        LinkedHashSet<String> unique = new LinkedHashSet<>(ids);
        List<Source> out = new ArrayList<>();
        for (String id : unique) {
            Source s = visibleById.get(id);
            if (s != null) out.add(s);
        }
        return List.copyOf(out);
    }

    public List<Map<String, Object>> selection(String ws, List<String> ids) {
        return visibleSelection(ws, ids).stream().map(s -> view(s, ws)).toList();
    }

    /** 创造页会话：模型可以据此规划真实数据接入，但不能把说明当成已经执行过的查询结果。 */
    public String conversationSummary(String ws, List<String> ids) {
        List<Source> list = visibleSelection(ws, ids);
        if (list.isEmpty()) return "";
        return summary(list, "本会话已连接的数据源（创建应用时会自动绑定）")
                + "用户提到这些接口或真实数据时，按上述能力设计和回答；当前问答没有执行接口调用，不要声称已经查到实时结果。生成应用时通过 window.zhiye.source.query 调用。\n";
    }

    private static String summary(List<Source> list, String heading) {
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## ").append(heading).append('\n');
        for (Source s : list) {
            sb.append("- ").append(s.name()).append("（").append(s.key()).append("）");
            if (!s.description().isBlank()) sb.append("：").append(s.description().length() > 120 ? s.description().substring(0, 120) + "…" : s.description());
            if (!s.params().isEmpty()) sb.append("；参数 ").append(String.join("、", s.params().stream().map(Param::name).toList()));
            sb.append('\n');
        }
        return sb.toString();
    }

    // ---------------- 内部 ----------------

    private List<Source> visible(String ws) {
        return jdbc.sql("select * from data_source where deleted_at is null and (workspace_id = ? or workspace_id is null) order by workspace_id nulls first, created_at")
                .param(ws).query((rs, i) -> row(rs)).list();
    }

    private Source requireVisible(String ws, String id) {
        return jdbc.sql("select * from data_source where id = ? and deleted_at is null and (workspace_id = ? or workspace_id is null)")
                .params(id, ws).query((rs, i) -> row(rs)).optional().orElseThrow(() -> ApiException.notFound("数据源"));
    }

    private Source requireOwned(String ws, String id) {
        Source s = requireVisible(ws, id);
        if (s.platform()) throw new ApiException(HttpStatus.FORBIDDEN, "SOURCE_READONLY", "平台登记的数据源只能由管理员修改");
        return s;
    }

    private void requireApp(String ws, String appId) {
        apps.findById(appId).filter(a -> a.workspaceId().equals(ws) && a.deletedAt() == null).orElseThrow(() -> ApiException.notFound("应用"));
    }

    private boolean keyTaken(String ws, String key, String exceptId) {
        return jdbc.sql("select count(*) from data_source where key = ? and deleted_at is null and (workspace_id = ? or workspace_id is null) and id <> ?")
                .params(key, ws, exceptId == null ? "" : exceptId).query(Long.class).single() > 0;
    }

    void insert(String id, String ws, Source s) {
        jdbc.sql("""
                insert into data_source (id, workspace_id, key, name, description, method, url, params, sample, auth_type, auth_header, secret_enc, secret_env)
                values (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)
                """).params(id, ws, s.key(), s.name(), s.description(), s.method(), s.url(), Json.write(s.params()), s.sample(),
                s.authType(), s.authHeader(), s.secretEnc(), s.secretEnv()).update();
    }

    private Source row(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<Param> params = new ArrayList<>();
        for (JsonNode p : Json.read(rs.getString("params"))) {
            String name = p.path("name").asText();
            params.add(new Param(name, p.path("type").asText("string"), p.path("required").asBoolean(false),
                    p.path("description").asText(""), sensitiveName(name) ? "" : p.path("example").asText(""), p.path("position").asText(null)));
        }
        // 旧版 AGW 导入把完整请求体误存成 root(string)。读取时兼容为 _body，历史数据源无需重新添加。
        if ("agw".equals(rs.getString("provider")) && params.size() == 1) {
            Param p = params.get(0);
            if ("root".equals(p.name()) && "string".equals(p.type()) && "body".equals(p.position())
                    && !p.required() && p.description().isBlank()) {
                String example = p.example();
                try { Json.read(example); } catch (Exception e) { example = ""; }
                params.set(0, new Param("_body", "json", false, "完整 JSON 请求体（填写后覆盖其他参数）", example, "body"));
            }
        }
        return new Source(rs.getString("id"), rs.getString("workspace_id"), rs.getString("key"), rs.getString("name"),
                rs.getString("description"), rs.getString("method"), rs.getString("url"), params, rs.getString("sample"),
                rs.getString("auth_type"), rs.getString("auth_header"), rs.getString("secret_enc"), rs.getString("secret_env"),
                Db.iso(Db.ts(rs, "created_at")), rs.getString("provider"), rs.getString("external_id"));
    }

    static boolean sensitiveName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*(?:secret|password|credential|token|app[-_]?key|api[-_]?key|authorization|webhook).*");
    }

    private Map<String, Object> view(Source s, String ws) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("key", s.key());
        m.put("name", s.name());
        m.put("description", s.description());
        m.put("method", s.method());
        // 平台登记的数据源只显示主机名，不展示完整地址
        m.put("url", s.platform() ? URI.create(s.url().replace("{", "%7B").replace("}", "%7D")).getHost() : s.url());
        m.put("params", s.params());
        m.put("sample", s.sample());
        m.put("authType", s.authType());
        m.put("authHeader", s.authHeader());
        m.put("hasSecret", s.secretEnc() != null || s.secretEnv() != null);
        m.put("platform", s.platform());
        m.put("editable", !s.platform() && Objects.equals(s.workspaceId(), ws));
        m.put("provider", s.provider());
        m.put("externalId", s.externalId());
        m.put("createdAt", s.createdAt());
        return m;
    }

    /** 解析并校验登记内容；old 不为空时是修改（没给 secret 就保留原凭证） */
    Source parse(JsonNode b, Source old, String ws) {
        if (b == null || !b.isObject()) throw ApiException.validation("请求体必须是对象");
        String key = text(b, "key", old == null ? null : old.key()).trim();
        if (!KEY.matcher(key).matches()) throw ApiException.validation("调用名只能用小写字母、数字、下划线，以字母开头，2～48 位，例如 mes_daily_output");
        String name = text(b, "name", old == null ? null : old.name()).trim();
        if (name.isEmpty() || name.length() > 40) throw ApiException.validation("名称需要 1～40 个字");
        String desc = text(b, "description", old == null ? "" : old.description()).trim();
        if (desc.length() > 500) throw ApiException.validation("说明最多 500 字");
        String method = text(b, "method", old == null ? "GET" : old.method()).trim().toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) throw ApiException.validation("只支持 GET 或 POST（查询类）接口");
        String url = text(b, "url", old == null ? null : old.url()).trim();
        checkUrl(url);
        List<Param> params = old == null || b.has("params") ? params(b.get("params"), url) : old.params();
        String sample = b.has("sample") ? (b.get("sample").isTextual() ? b.get("sample").asText() : b.get("sample").isNull() ? null : Json.write(b.get("sample")))
                : old == null ? null : old.sample();
        if (sample != null && sample.length() > 20000) throw ApiException.validation("返回示例最多 20000 字");
        String auth = text(b, "authType", old == null ? "none" : old.authType()).trim();
        if (!AUTH.contains(auth)) throw ApiException.validation("鉴权方式不受支持");
        String header = switch (auth) {
            case "header", "agw" -> text(b, "authHeader", old == null ? null : old.authHeader()).trim();
            case "agw_user" -> b.has("authHeader") ? b.get("authHeader").asText("").trim() : old == null ? null : old.authHeader();
            default -> null;
        };
        if ("agw".equals(auth) && (header == null || !APP_KEY.matcher(header).matches()))
            throw ApiException.validation("API 网关调用项目的 AppKey 不合法");
        if ("header".equals(auth) && header != null && (!HEADER.matcher(header).matches() || RESERVED_HEADERS.contains(header.toLowerCase(Locale.ROOT))))
            throw ApiException.validation("请求头名称不合法");
        String enc = old == null ? null : old.secretEnc();
        if (b.hasNonNull("secret") && !b.get("secret").asText().isEmpty()) {
            String secret = b.get("secret").asText();
            if (secret.length() > 4000) throw ApiException.validation("凭证太长");
            if (secret.contains("\n") || secret.contains("\r")) throw ApiException.validation("凭证不能包含换行");
            if ("basic".equals(auth) && !secret.contains(":")) throw ApiException.validation("Basic 鉴权的凭证格式为 用户名:密码");
            enc = encrypt(secret);
        }
        boolean platformCredentials = "none".equals(auth) || "agw_client".equals(auth) || "agw_user".equals(auth);
        if (platformCredentials) enc = null;
        String env = old == null ? null : old.secretEnv();
        if (ws == null) {
            env = b.hasNonNull("secretEnv") ? b.get("secretEnv").asText().trim() : null;
            if (env != null && !ENV.matcher(env).matches()) throw ApiException.validation("secretEnv 必须是环境变量名");
            enc = null;
            if (!platformCredentials && env == null) throw ApiException.validation("平台数据源需要用 secretEnv 指定凭证所在的环境变量");
        } else if (!platformCredentials && enc == null) {
            throw ApiException.validation("请填写凭证");
        }
        return new Source(old == null ? null : old.id(), ws, key, name, desc, method, url, params, sample, auth, header, enc, env, null);
    }

    private static String text(JsonNode b, String field, String fallback) {
        JsonNode v = b.get(field);
        if (v == null || v.isNull()) {
            if (fallback == null) throw ApiException.validation("缺少 " + field);
            return fallback;
        }
        return v.asText();
    }

    static void checkUrl(String url) {
        if (url.isEmpty() || url.length() > 1000) throw ApiException.validation("接口地址需要 1～1000 个字符");
        URI uri;
        try { uri = URI.create(url.replace("{", "%7B").replace("}", "%7D")); }
        catch (Exception e) { throw ApiException.validation("接口地址格式不对"); }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw ApiException.validation("接口地址必须以 http:// 或 https:// 开头");
        if (uri.getHost() == null || uri.getHost().isBlank()) throw ApiException.validation("接口地址缺少主机名");
        if (uri.getRawUserInfo() != null) throw ApiException.validation("不要把账号密码写在地址里，请用鉴权方式填写凭证");
        if (uri.getHost().contains("{")) throw ApiException.validation("主机名不能使用参数占位");
    }

    static List<Param> params(JsonNode arr, String url) {
        List<Param> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        if (arr != null && !arr.isNull()) {
            if (!arr.isArray()) throw ApiException.validation("params 必须是数组");
            if (arr.size() > 30) throw ApiException.validation("参数最多 30 个");
            for (JsonNode p : arr) {
                String name = p.path("name").asText("").trim();
                String position = p.path("position").asText(null);
                boolean header = "header".equals(position);
                if (header ? !HEADER.matcher(name).matches() : !PARAM.matcher(name).matches())
                    throw ApiException.validation("参数名「" + name + "」不合法" + (header ? "：Header 只能使用标准请求头字符" : "：只能用字母、数字、下划线"));
                if (!names.add(name)) throw ApiException.validation("参数 " + name + " 重复了");
                String type = p.path("type").asText("string").trim();
                if (!TYPES.contains(type)) throw ApiException.validation("参数 " + name + " 的类型只能是 string / number / integer / boolean / date");
                String d = p.path("description").asText("").trim();
                String ex = sensitiveName(name) ? "" : p.path("example").asText("").trim();
                if (d.length() > 200 || ex.length() > 200) throw ApiException.validation("参数说明和示例最多 200 字");
                if (position != null && !Set.of("query", "path", "header", "body").contains(position))
                    throw ApiException.validation("参数 " + name + " 的位置不合法");
                if (header && RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT)))
                    throw ApiException.validation("参数 " + name + " 不能作为 Header 传递");
                out.add(new Param(name, type, p.path("required").asBoolean(false), d, ex, position));
            }
        }
        Matcher m = PLACEHOLDER.matcher(url);
        while (m.find()) {
            String n = m.group(1);
            int i = -1;
            for (int k = 0; k < out.size(); k++) if (out.get(k).name().equals(n)) i = k;
            if (i < 0) throw ApiException.validation("地址里的 {" + n + "} 没有在参数里定义");
            Param p = out.get(i);
            if (!p.required()) out.set(i, new Param(p.name(), p.type(), true, p.description(), p.example()));
        }
        return out;
    }

    // ---------------- 凭证加解密 ----------------

    private String workspaceLoginName(Source s, String appId) {
        String ws = s.workspaceId();
        if (ws == null && appId != null)
            ws = jdbc.sql("select workspace_id from app where id = ? and deleted_at is null").param(appId).query(String.class).optional().orElse(null);
        if (ws == null) throw new ApiException(HttpStatus.BAD_REQUEST, "LOGIN_REQUIRED", "按用户调用 API 网关需要统一认证身份");
        return jdbc.sql("select u.login_name from workspace w join app_user u on u.id = w.owner_user_id where w.id = ?")
                .param(ws).query(String.class).optional()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "LOGIN_REQUIRED", "按用户调用 API 网关需要统一认证身份"));
    }

    private String secret(Source s) {
        if ("none".equals(s.authType())) return "";
        if (s.secretEnv() != null) {
            String v = System.getenv(s.secretEnv());
            if (v == null || v.isBlank()) v = System.getProperty(s.secretEnv());
            if (v == null || v.isBlank()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_SECRET_MISSING", "数据源凭证没有配置，请联系平台管理员");
            return v.trim();
        }
        if (s.secretEnc() == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_SECRET_MISSING", "数据源缺少凭证，请重新填写");
        return decrypt(s.secretEnc());
    }

    String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cloud.deriveKey("data-source-secret"), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return "v1:" + Base64.getEncoder().encodeToString(all);
        } catch (Exception e) {
            throw new IllegalStateException("加密凭证失败", e);
        }
    }

    String decrypt(String enc) {
        try {
            if (!enc.startsWith("v1:")) throw new IllegalArgumentException("未知格式");
            byte[] all = Base64.getDecoder().decode(enc.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cloud.deriveKey("data-source-secret"), "AES"), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_SECRET_INVALID", "数据源凭证无法解密（平台密钥可能已更换），请重新填写凭证");
        }
    }

    // ---------------- 外部目录导入（API 网关） ----------------

    public String importedId(String ws, String provider, String externalId) {
        return jdbc.sql("select id from data_source where workspace_id = ? and provider = ? and external_id = ? and deleted_at is null")
                .params(ws, provider, externalId).query(String.class).optional().orElse(null);
    }

    /** 以目录 ID 幂等导入；再次导入更新公开契约，AppSecret 留空时保留原值。 */
    public synchronized Map<String, Object> importExternal(String ws, String provider, String externalId, JsonNode body) {
        Source old = jdbc.sql("select * from data_source where workspace_id = ? and provider = ? and external_id = ? and deleted_at is null")
                .params(ws, provider, externalId).query((rs, i) -> row(rs)).optional().orElse(null);
        Source s = parse(body, old, ws);
        if (keyTaken(ws, s.key(), old == null ? null : old.id()))
            throw ApiException.conflict("SOURCE_KEY_TAKEN", "调用名「" + s.key() + "」已经被使用，请换一个");
        if (old == null) {
            String id = Ids.newId("src");
            jdbc.sql("""
                    insert into data_source (id, workspace_id, key, name, description, method, url, params, sample,
                        auth_type, auth_header, secret_enc, provider, external_id)
                    values (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?)
                    """).params(id, ws, s.key(), s.name(), s.description(), s.method(), s.url(), Json.write(s.params()), s.sample(),
                    s.authType(), s.authHeader(), s.secretEnc(), provider, externalId).update();
        } else {
            jdbc.sql("""
                    update data_source set key = ?, name = ?, description = ?, method = ?, url = ?, params = ?::jsonb, sample = ?,
                        auth_type = ?, auth_header = ?, secret_enc = ?, updated_at = now() where id = ?
                    """).params(s.key(), s.name(), s.description(), s.method(), s.url(), Json.write(s.params()), s.sample(),
                    s.authType(), s.authHeader(), s.secretEnc(), old.id()).update();
        }
        return get(ws, importedId(ws, provider, externalId));
    }

    // ---------------- 平台登记（配置文件同步） ----------------

    /** 平台数据源：按 key 同步配置文件里的条目，文件里没有的标记删除 */
    public synchronized int syncPlatform(ArrayNode entries) {
        Set<String> keys = new HashSet<>();
        for (JsonNode e : entries) {
            Source s = parse(e, null, null);
            keys.add(s.key());
            String id = jdbc.sql("select id from data_source where workspace_id is null and key = ? and deleted_at is null").param(s.key())
                    .query(String.class).optional().orElse(null);
            if (id == null) insert(Ids.newId("src"), null, s);
            else jdbc.sql("""
                    update data_source set name = ?, description = ?, method = ?, url = ?, params = ?::jsonb, sample = ?,
                        auth_type = ?, auth_header = ?, secret_env = ?, updated_at = now() where id = ?
                    """).params(s.name(), s.description(), s.method(), s.url(), Json.write(s.params()), s.sample(),
                    s.authType(), s.authHeader(), s.secretEnv(), id).update();
        }
        for (String id : jdbc.sql("select id from data_source where workspace_id is null and deleted_at is null").query(String.class).list()) {
            String key = jdbc.sql("select key from data_source where id = ?").param(id).query(String.class).single();
            if (!keys.contains(key)) jdbc.sql("update data_source set deleted_at = now() where id = ?").param(id).update();
        }
        return keys.size();
    }
}
