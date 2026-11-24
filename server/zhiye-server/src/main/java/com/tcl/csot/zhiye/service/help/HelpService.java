package com.tcl.csot.zhiye.service.help;

import com.tcl.csot.zhiye.repository.HelpRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.service.dev.FaultState;
import com.tcl.csot.zhiye.client.UpstreamClient;
import com.tcl.csot.zhiye.service.llm.UsageService;
import com.tcl.csot.zhiye.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 小织帮助助手：对话 CRUD、调用模型流式回答、问题上报。 */
@Service
public class HelpService {
    private static final Logger log = LoggerFactory.getLogger(HelpService.class);
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static final int DAILY_LIMIT = 60;
    public static final int MAX_CHARS = 2000;
    public static final int MAX_IMAGES = 3;
    public static final int CONTEXT_MESSAGES = 20;
    public static final int MAX_TOKENS = 1500;
    public static final int TITLE_CHARS = 20;
    /** 最近几条消息里的图片会带给模型（更早的只保留文字说明） */
    static final int IMAGE_WINDOW = 4;
    public static final String UPSTREAM_ERROR = "小织暂时没连上，请稍后再试";

    private final HelpRepository repo;
    private final JdbcClient jdbc;
    private final ObjectStore store;
    private final UpstreamClient upstream;
    private final UsageService usage;
    private final FaultState faults;
    private final ZhiyeProperties props;
    /** 正在回答的对话 → 流状态（用于「停止回答」） */
    private final Map<String, Stream> active = new ConcurrentHashMap<>();
    /** 仅测试用：把小织的模型请求发到另一个 OpenAI 兼容地址（不经过 UpstreamClient），避免为测试单独起一个 Spring 上下文 */
    volatile String testBaseUrl;
    private final java.net.http.HttpClient testHttp = java.net.http.HttpClient.newHttpClient();

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.llm.LlmRouter router;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private HelpKnowledgeService knowledge;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.workspace.WorkspaceMemoryService memories;

    public HelpService(HelpRepository repo, JdbcClient jdbc, ObjectStore store, UpstreamClient upstream, UsageService usage,
                       FaultState faults, ZhiyeProperties props) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.store = store;
        this.upstream = upstream;
        this.usage = usage;
        this.faults = faults;
        this.props = props;
    }

    // ---------- 视图 ----------
    public Map<String, Object> view(HelpRepository.Conversation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("title", c.title());
        m.put("createdAt", Db.iso(c.createdAt()));
        m.put("updatedAt", Db.iso(c.updatedAt()));
        return m;
    }

    public Map<String, Object> view(String ws, HelpRepository.Message msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", msg.id());
        m.put("role", msg.role());
        m.put("content", msg.content());
        m.put("status", msg.status());
        m.put("createdAt", Db.iso(msg.createdAt()));
        List<Map<String, Object>> assets = new ArrayList<>();
        for (String id : msg.assetIds()) {
            jdbc.sql("select id, name, mime, size from asset where id = ? and workspace_id = ?").params(id, ws).query((rs, i) -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", rs.getString("id"));
                a.put("name", rs.getString("name"));
                a.put("mime", rs.getString("mime"));
                a.put("size", rs.getLong("size"));
                a.put("url", "/api/assets/" + rs.getString("id") + "/content");
                return a;
            }).optional().ifPresent(assets::add);
        }
        m.put("assets", assets);
        return m;
    }

    // ---------- 对话 ----------
    public List<Map<String, Object>> list(String ws) { return repo.list(ws).stream().map(this::view).toList(); }

    public Map<String, Object> create(String ws) { return view(repo.create(ws)); }

    public HelpRepository.Conversation get(String ws, String id) {
        return repo.find(ws, id).orElseThrow(() -> ApiException.notFound("对话"));
    }

    public List<Map<String, Object>> messages(String ws, String id) {
        get(ws, id);
        return repo.messages(id).stream().map(m -> view(ws, m)).toList();
    }

    public void delete(String ws, String id) {
        if (!repo.softDelete(ws, id)) throw ApiException.notFound("对话");
        stop(ws, id, true);
    }

    /** 停止正在进行的回答；不存在时什么也不做 */
    public boolean stop(String ws, String id, boolean skipCheck) {
        if (!skipCheck) get(ws, id);
        Stream s = active.get(id);
        if (s == null) return false;
        s.cancel();
        return true;
    }

    public static String title(String content) {
        String t = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return "图片提问";
        int n = t.codePointCount(0, t.length());
        return n <= TITLE_CHARS ? t : t.substring(0, t.offsetByCodePoints(0, TITLE_CHARS));
    }

    // ---------- 校验 ----------
    private record Image(String id, String dataUrl) {}

    private String content(String content, boolean allowEmpty) {
        String c = content == null ? "" : content.strip();
        if (c.codePointCount(0, c.length()) > MAX_CHARS) throw ApiException.validation("消息最多 " + MAX_CHARS + " 个字");
        if (c.isEmpty() && !allowEmpty) throw ApiException.validation("请输入内容");
        return c;
    }

    private List<String> assetIds(String ws, List<String> ids) {
        if (ids == null) return List.of();
        List<String> out = new ArrayList<>(new LinkedHashSet<>(ids));
        if (out.size() > MAX_IMAGES) throw ApiException.validation("最多附 " + MAX_IMAGES + " 张图片");
        for (String id : out) {
            String mime = jdbc.sql("select mime from asset where id = ? and workspace_id = ?").params(id, ws).query(String.class).optional()
                    .orElseThrow(() -> ApiException.validation("图片不存在或已失效，请重新上传"));
            if (!mime.startsWith("image/")) throw ApiException.validation("只能附图片");
        }
        return out;
    }

    private String dataUrl(String ws, String assetId) {
        try {
            var row = jdbc.sql("select mime, object_key from asset where id = ? and workspace_id = ?").params(assetId, ws)
                    .query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).optional();
            if (row.isEmpty()) return null;
            byte[] data = store.getBytes(row.get()[1]).orElse(null);
            return HelpImages.dataUrl(row.get()[0], data);
        } catch (Exception e) {
            log.warn("读取帮助图片 {} 失败：{}", assetId, e.getMessage());
            return null;
        }
    }

    private void checkRate(String ws) {
        Instant dayStart = LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
        if (repo.userMessagesSince(ws, dayStart) >= DAILY_LIMIT)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "今天已经问了 " + DAILY_LIMIT + " 个问题啦，小织要休息一下，明天再来吧");
    }

    // ---------- 发送消息 ----------
    /** 发送前的准备（校验、保存用户消息、组装模型请求）；在请求线程里执行，出错直接返回 JSON 错误 */
    public Prepared prepare(String ws, String conversationId, String rawContent, List<String> rawAssetIds) {
        var conv = get(ws, conversationId);
        String c = content(rawContent, true);
        List<String> ids = assetIds(ws, rawAssetIds);
        if (c.isEmpty() && ids.isEmpty()) throw ApiException.validation("请输入内容");
        if (!upstream.hasKey()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "HELP_UNAVAILABLE", "小织暂时离线");
        checkRate(ws);
        // 五星模式下用这个人的模型路由（用量计入个人额度）；取不到（未登录等）在请求线程里直接报错
        com.tcl.csot.zhiye.service.llm.LlmRoute route = router.forWorkspace(ws, com.tcl.csot.zhiye.service.llm.LlmRouter.AUTO);
        Stream old = active.get(conversationId);
        if (old != null) old.cancel();

        var userMsg = repo.addMessage(conversationId, "user", c, ids, "done");
        repo.titleIfDefault(conversationId, title(c));
        repo.touch(conversationId);
        String title = repo.find(ws, conversationId).map(HelpRepository.Conversation::title).orElse(conv.title());

        List<HelpRepository.Message> ctx = repo.recent(conversationId, CONTEXT_MESSAGES);
        ObjectNode body = Json.obj();
        body.put("model", route.model());
        body.put("stream", true);
        body.put("max_tokens", MAX_TOKENS);
        body.put("reasoning_effort", "low");
        body.putObject("stream_options").put("include_usage", true);
        ArrayNode msgs = body.putArray("messages");
        if (memories != null) memories.learnFromPrompt(ws, c);
        String sysPrompt = (knowledge != null ? knowledge.buildBotSystemPrompt() : HelpPrompt.SYSTEM)
                + (memories != null ? memories.promptSection(ws) : "");
        msgs.addObject().put("role", "system").put("content", sysPrompt);
        // 上游要求对话以 user 开头
        int start = 0;
        while (start < ctx.size() && !"user".equals(ctx.get(start).role())) start++;
        for (int i = start; i < ctx.size(); i++) {
            var m = ctx.get(i);
            boolean withImages = "user".equals(m.role()) && !m.assetIds().isEmpty() && i >= ctx.size() - IMAGE_WINDOW;
            if ("assistant".equals(m.role())) {
                String text = m.content() + ("stopped".equals(m.status()) ? "\n（回答被用户中途停止）" : "");
                msgs.addObject().put("role", "assistant").put("content", text);
            } else if (withImages) {
                ObjectNode u = msgs.addObject().put("role", "user");
                ArrayNode parts = u.putArray("content");
                int attached = 0;
                for (String id : m.assetIds()) {
                    String url = dataUrl(ws, id);
                    if (url == null) continue;
                    parts.addObject().put("type", "image_url").putObject("image_url").put("url", url);
                    attached++;
                }
                String text = m.content().isEmpty() ? "请看看这张截图。" : m.content();
                if (attached < m.assetIds().size()) text += "\n（有 " + (m.assetIds().size() - attached) + " 张图片无法读取）";
                parts.addObject().put("type", "text").put("text", text);
            } else {
                String text = m.content();
                if (!m.assetIds().isEmpty()) text = (text.isEmpty() ? "" : text + "\n") + "（附了 " + m.assetIds().size() + " 张截图）";
                msgs.addObject().put("role", "user").put("content", text);
            }
        }
        Stream s = new Stream(ws, conversationId);
        active.put(conversationId, s);
        return new Prepared(s, view(ws, userMsg), title, Json.write(body).getBytes(StandardCharsets.UTF_8), route);
    }

    public record Prepared(Stream stream, Map<String, Object> userMessage, String title, byte[] body,
                           com.tcl.csot.zhiye.service.llm.LlmRoute route) {}

    /** SSE 输出 */
    public interface Sink {
        void event(String name, Object data) throws Exception;
        /** 保持连接（顺便发现客户端已断开） */
        void ping() throws Exception;
    }

    /** 一次回答的状态 */
    public static final class Stream {
        final String ws;
        final String conversationId;
        private volatile boolean cancelled;
        private volatile InputStream in;

        Stream(String ws, String conversationId) {
            this.ws = ws;
            this.conversationId = conversationId;
        }

        public void cancel() {
            cancelled = true;
            InputStream s = in;
            if (s != null) try { s.close(); } catch (Exception ignored) { }
        }

        public boolean cancelled() { return cancelled; }
    }

    /** 在后台线程里调用上游并把增量写给客户端；最后保存助手消息（done / stopped / error） */
    public void run(Prepared p, Sink sink) {
        Stream s = p.stream();
        StringBuilder text = new StringBuilder();
        String error = null;
        JsonNode lastUsage = null;
        try {
            if (faults.llmError()) throw new java.io.IOException("注入的模型故障");
            if (s.cancelled()) throw new java.io.IOException("已停止");
            HttpResponse<InputStream> res = callUpstream(p.route(), p.body());
            try (InputStream in = res.body()) {
                s.in = in;
                if (s.cancelled()) throw new java.io.IOException("已停止");
                if (res.statusCode() >= 400) {
                    byte[] all = in.readAllBytes();
                    String raw = new String(all, StandardCharsets.UTF_8);
                    log.warn("小织：上游模型返回 {}：{}", res.statusCode(), raw.length() > 300 ? raw.substring(0, 300) : raw);
                    error = "PROVIDER_QUOTA".equals(com.tcl.csot.zhiye.client.KimiClient.codeFor(res.statusCode(), raw))
                            ? com.tcl.csot.zhiye.service.llm.QuotaText.exhausted(router.fivestar(), raw) : UPSTREAM_ERROR;
                } else {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    long lastPing = System.currentTimeMillis();
                    while (!s.cancelled() && (line = r.readLine()) != null) {
                        line = line.trim();
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).trim();
                        if (data.equals("[DONE]")) break;
                        if (data.isEmpty()) continue;
                        JsonNode n;
                        try { n = Json.M.readTree(data); } catch (Exception e) { continue; }
                        JsonNode u = UsageService.findUsage(n);
                        if (u != null) lastUsage = u;
                        JsonNode delta = n.path("choices").path(0).path("delta");
                        JsonNode c = delta.get("content");
                        if (c != null && c.isTextual() && !c.asText().isEmpty()) {
                            text.append(c.asText());
                            try { sink.event("delta", Map.of("text", c.asText())); } catch (Exception e) { s.cancel(); break; }
                            lastPing = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - lastPing > 1000) {
                            // 推理阶段（reasoning_content）没有正文：定期发注释，及时发现客户端断开
                            try { sink.ping(); } catch (Exception e) { s.cancel(); break; }
                            lastPing = System.currentTimeMillis();
                        }
                    }
                    if (!s.cancelled() && text.isEmpty()) error = UPSTREAM_ERROR;
                }
            }
        } catch (Exception e) {
            if (!s.cancelled()) {
                log.warn("小织：调用模型失败：{}", e.toString());
                error = UPSTREAM_ERROR;
            }
        } finally {
            active.remove(s.conversationId, s);
            if (lastUsage != null) {
                try { usage.record(null, p.route().provider(), p.route().model(), lastUsage); } catch (Exception ignored) { }
            }
        }
        String status = s.cancelled() ? "stopped" : error != null ? "error" : "done";
        HelpRepository.Message saved = null;
        try {
            if (repo.find(s.ws, s.conversationId).isPresent() && (!"error".equals(status) || !text.isEmpty())) {
                saved = repo.addMessage(s.conversationId, "assistant", text.toString(), List.of(), status);
                repo.touch(s.conversationId);
            }
        } catch (Exception e) {
            log.warn("小织：保存回答失败：{}", e.toString());
        }
        try {
            if ("error".equals(status)) sink.event("error", Map.of("message", error));
            else {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("messageId", saved == null ? null : saved.id());
                d.put("status", status);
                d.put("title", p.title());
                sink.event("done", d);
            }
        } catch (Exception ignored) { /* 客户端已断开 */ }
    }

    private HttpResponse<InputStream> callUpstream(com.tcl.csot.zhiye.service.llm.LlmRoute route, byte[] body) throws Exception {
        String base = testBaseUrl;
        if (base == null) return upstream.send(route, "POST", "/chat/completions", body);
        return testHttp.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/chat/completions"))
                .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    // ---------- 问题上报 ----------
    public Map<String, Object> feedback(String ws, String rawContent, List<String> rawAssetIds, String conversationId) {
        String c = content(rawContent, true);
        List<String> ids = assetIds(ws, rawAssetIds);
        if (c.isEmpty() && ids.isEmpty()) throw ApiException.validation("请描述遇到的问题");
        if (conversationId != null && !conversationId.isBlank()) get(ws, conversationId);
        else conversationId = null;
        String id = repo.addFeedback(ws, conversationId, c, ids);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        String reply = "已记录你的反馈，编号 **" + id + "**。谢谢你帮织页变得更好～";
        out.put("reply", reply);
        if (conversationId != null) {
            var u = repo.addMessage(conversationId, "user", "【问题上报】" + c, ids, "feedback");
            var a = repo.addMessage(conversationId, "assistant", reply, List.of(), "feedback");
            repo.titleIfDefault(conversationId, title("问题上报：" + c));
            repo.touch(conversationId);
            out.put("messages", List.of(view(ws, u), view(ws, a)));
        }
        return out;
    }
}
