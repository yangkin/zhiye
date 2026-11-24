package com.tcl.csot.zhiye.service.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.client.UpstreamClient;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.llm.LlmRoute;
import com.tcl.csot.zhiye.service.llm.LlmRouter;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人工作空间长期记忆（Workspace Memory）：
 * - 按 workspace_id 严格隔离，用户可随时查看、新增、修改、开关或一键清空
 * - 用户可自由开关「自动从聊天中学习并记住」（workspace.auto_memory_enabled）
 * - 开启时：正则快路径即时捕获 + 后台异步 LLM 智能提炼聊天中的长期背景/偏好/规范（绝不阻塞对话、绝不记录密钥或一次性细节）
 * - 自动注入创造澄清、生成计划、应用生成/修改以及小织帮助问答上下文
 */
@Service
public class WorkspaceMemoryService {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceMemoryService.class);
    public static final int MAX_ITEMS_PER_WORKSPACE = 30;
    public static final int MAX_ITEM_CHARS = 240;
    public static final int MAX_TOTAL_PROMPT_CHARS = 3000;
    public static final Set<String> VALID_CATEGORIES = Set.of("profile", "preference", "stack", "domain", "rule");

    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "profile", "岗位与厂区背景",
            "preference", "设计与交互偏好",
            "stack", "技术栈偏好",
            "domain", "业务领域与术语",
            "rule", "常用规范与要求"
    );

    private static final String EXTRACT_SYSTEM = """
            你是用户工作空间的长期记忆提炼器。给你用户的「最新聊天消息」和「已有记忆列表」。
            请判断这条消息里是否包含**值得跨应用长期记住**的个人信息或稳定偏好：
            - profile（岗位与厂区背景）：所在厂区/产线/部门、岗位角色、负责业务领域（如「T11厂Array制程良率工程师」「供应链计划岗」）
            - preference（设计与交互偏好）：偏好的视觉风格、色彩、布局、语言、常用导出格式（如「偏好浅色工业风」「表格默认带Excel导出」「不喜欢暗黑背景」）
            - stack（技术栈偏好）：偏好的前后端语言/框架/图表库（如「偏好Vue 3 + ECharts」「后端习惯Python FastAPI」）
            - domain（业务领域与术语）：常用专有名词、指标口径、业务流程习惯（如「良率看板默认含8D闭环和SPC预警」）
            - rule（常用规范与要求）：用户明确要求「以后都…」「记住…」「默认必须…」「不要…」的长期规则

            严格禁止提取以下内容：
            1. 一次性任务、临时提问、寒暄、代码报错片段或闲聊（如「帮我修这个bug」「这个怎么用」「你好」）
            2. 任何密码、API Key、Token、手机号、身份证号、工号凭证等敏感信息
            3. 与「已有记忆列表」意思重复或高度相似的条目

            输出要求：
            - 只输出一个合法 JSON 数组，不要任何 Markdown 代码块或多余说明。
            - 如果没有值得长期记住的新信息，直接输出 []。
            - 最多提取 2 条，每条 content 简明扼要（10～60 字中文）。
            格式示例：[{"category":"profile","content":"在 T11 厂负责 Array 制程良率分析"}]
            """;

    private static final Pattern SECRET_LIKE = Pattern.compile(
            "(?i)(sk-[a-z0-9]{12,}|ghp_[a-z0-9]{12,}|bearer\\s+[a-z0-9._-]{12,}|password\\s*[:=]|密码\\s*[:：]|token\\s*[:=])");

    public record MemoryRow(
            String id, String workspaceId, String category, String content,
            String source, boolean enabled, Instant createdAt, Instant updatedAt
    ) {
        static MemoryRow map(ResultSet rs, int i) throws SQLException {
            return new MemoryRow(
                    rs.getString("id"), rs.getString("workspace_id"), rs.getString("category"),
                    rs.getString("content"), rs.getString("source"), rs.getBoolean("enabled"),
                    Db.ts(rs, "created_at"), Db.ts(rs, "updated_at")
            );
        }

        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("category", category);
            m.put("categoryLabel", CATEGORY_LABELS.getOrDefault(category, category));
            m.put("content", content);
            m.put("source", source);
            m.put("enabled", enabled);
            m.put("createdAt", Db.iso(createdAt));
            m.put("updatedAt", Db.iso(updatedAt));
            return m;
        }
    }

    private final JdbcClient jdbc;
    private final UpstreamClient upstream;
    private final LlmRouter router;
    private final ConcurrentHashMap<String, Long> lastExtractAtMs = new ConcurrentHashMap<>();

    @Autowired
    public WorkspaceMemoryService(JdbcClient jdbc,
                                  @Autowired(required = false) UpstreamClient upstream,
                                  @Autowired(required = false) LlmRouter router) {
        this.jdbc = jdbc;
        this.upstream = upstream;
        this.router = router;
    }

    public WorkspaceMemoryService(JdbcClient jdbc) {
        this(jdbc, null, null);
    }

    public boolean isAutoLearnEnabled(String ws) {
        if (ws == null || ws.isBlank()) return false;
        return jdbc.sql("select auto_memory_enabled from workspace where id = ?")
                .param(ws).query(Boolean.class).optional().orElse(true);
    }

    public boolean setAutoLearnEnabled(String ws, boolean enabled) {
        jdbc.sql("update workspace set auto_memory_enabled = ? where id = ?").params(enabled, ws).update();
        return enabled;
    }

    public List<Map<String, Object>> list(String ws) {
        return jdbc.sql("select * from workspace_memory where workspace_id = ? order by enabled desc, updated_at desc, id desc")
                .param(ws).query(MemoryRow::map).list().stream().map(MemoryRow::view).toList();
    }

    public Map<String, Object> add(String ws, JsonNode body) {
        if (body == null || !body.isObject()) throw ApiException.validation("缺少记忆内容");
        String content = body.path("content").asText("").trim();
        if (content.isEmpty()) throw ApiException.validation("记忆内容不能为空");
        if (content.length() > MAX_ITEM_CHARS) throw ApiException.validation("单条记忆最多 " + MAX_ITEM_CHARS + " 字");
        String category = body.path("category").asText("preference").trim();
        if (!VALID_CATEGORIES.contains(category)) category = "preference";
        boolean enabled = !body.has("enabled") || body.path("enabled").asBoolean(true);
        return insertInternal(ws, category, content, "manual", enabled).view();
    }

    public Map<String, Object> update(String ws, String id, JsonNode body) {
        MemoryRow cur = find(ws, id).orElseThrow(() -> ApiException.notFound("工作空间记忆"));
        if (body == null || !body.isObject()) throw ApiException.validation("缺少更新内容");
        String content = body.has("content") ? body.path("content").asText("").trim() : cur.content();
        if (content.isEmpty()) throw ApiException.validation("记忆内容不能为空");
        if (content.length() > MAX_ITEM_CHARS) throw ApiException.validation("单条记忆最多 " + MAX_ITEM_CHARS + " 字");
        String category = body.has("category") ? body.path("category").asText(cur.category()).trim() : cur.category();
        if (!VALID_CATEGORIES.contains(category)) category = cur.category();
        boolean enabled = body.has("enabled") ? body.path("enabled").asBoolean(cur.enabled()) : cur.enabled();

        jdbc.sql("update workspace_memory set category = ?, content = ?, enabled = ?, updated_at = now() where id = ? and workspace_id = ?")
                .params(category, content, enabled, id, ws).update();
        return find(ws, id).orElseThrow().view();
    }

    public void delete(String ws, String id) {
        int n = jdbc.sql("delete from workspace_memory where id = ? and workspace_id = ?").params(id, ws).update();
        if (n == 0) throw ApiException.notFound("工作空间记忆");
    }

    public int clearAll(String ws) {
        return jdbc.sql("delete from workspace_memory where workspace_id = ?").param(ws).update();
    }

    public Optional<MemoryRow> find(String ws, String id) {
        return jdbc.sql("select * from workspace_memory where id = ? and workspace_id = ?")
                .params(id, ws).query(MemoryRow::map).optional();
    }

    /** 拼装给 AI 的个人记忆上下文块（无启用记忆时返回空串，保证零干扰） */
    public String promptSection(String ws) {
        if (ws == null || ws.isBlank()) return "";
        List<MemoryRow> rows = jdbc.sql("select * from workspace_memory where workspace_id = ? and enabled = true order by updated_at desc, id desc")
                .param(ws).query(MemoryRow::map).list();
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n# 用户个人工作空间记忆（以下为该用户长期偏好与背景，除非本次需求明确覆盖，否则默认遵循）\n");
        int used = 0;
        for (MemoryRow r : rows) {
            String line = "- [" + CATEGORY_LABELS.getOrDefault(r.category(), r.category()) + "] " + r.content() + "\n";
            if (used + line.length() > MAX_TOTAL_PROMPT_CHARS) break;
            sb.append(line);
            used += line.length();
        }
        return sb.toString();
    }

    private static final Pattern EXPLICIT_REMEMBER = Pattern.compile("(?:记住|以后默认|以后都|我平时都|我的偏好是|请记住)[：:，,\\s]*([^。！!\\n]{4,120})");
    private static final Pattern FAB_PROFILE = Pattern.compile("(?:我在|我是|负责|属于)\\s*(T\\d{1,2}[^，,。！!\\n]{0,20}(?:厂|产线|制程|品质|设备|PIE|MES|CIM|研发|PMC|工艺)[^，,。！!\\n]{0,16})");
    private static final Pattern STACK_PREF = Pattern.compile("(?:以后都用|默认用|偏好用|习惯用|喜欢用)\\s*(Vue\\s*3?|React|FastAPI|Python|Node(?:\\.js)?|Express|Java|Spring Boot|Go|ECharts|Recharts|深色大屏|浅色工业风|极简风)", Pattern.CASE_INSENSITIVE);

    /**
     * 从用户聊天消息中自动学习长期记忆（仅当工作空间开启 auto_memory_enabled 时生效）：
     * 1. 正则快路径同步捕获明确表述；
     * 2. 异步后台调用轻量 LLM 语义提炼自然聊天中的背景与偏好（不阻塞用户对话，自动过滤密钥与重复项）。
     */
    public void learnFromPrompt(String ws, String prompt) {
        if (ws == null || ws.isBlank() || prompt == null || prompt.isBlank()) return;
        String text = prompt.trim();
        if (text.length() < 6 || SECRET_LIKE.matcher(text).find()) return;
        try {
            if (!isAutoLearnEnabled(ws)) return;
            long count = jdbc.sql("select count(*) from workspace_memory where workspace_id = ?").param(ws).query(Long.class).single();
            if (count >= MAX_ITEMS_PER_WORKSPACE) return;

            boolean matchedFast = false;
            Matcher m1 = EXPLICIT_REMEMBER.matcher(text);
            if (m1.find()) {
                matchedFast = saveAutoIfNovel(ws, "rule", m1.group(1).trim()) || matchedFast;
            }
            Matcher m2 = FAB_PROFILE.matcher(text);
            if (m2.find()) {
                matchedFast = saveAutoIfNovel(ws, "profile", "业务背景：" + m2.group(1).trim()) || matchedFast;
            }
            Matcher m3 = STACK_PREF.matcher(text);
            if (m3.find()) {
                matchedFast = saveAutoIfNovel(ws, "stack", "常用技术/风格偏好：" + m3.group(1).trim()) || matchedFast;
            }

            // 后台异步 LLM 智能提炼自然聊天里的长期偏好/背景（仅在配置了上游模型且非纯短问候时触发）
            if (!matchedFast && text.length() >= 10 && text.length() <= 1500
                    && upstream != null && upstream.hasKey() && router != null) {
                long now = System.currentTimeMillis();
                Long prev = lastExtractAtMs.put(ws, now);
                if (prev == null || now - prev > 3_000L) {
                    Threads.start(() -> extractWithLlmQuietly(ws, text));
                }
            }
        } catch (Exception e) {
            log.debug("自动提炼工作空间记忆跳过 ws={}: {}", ws, e.getMessage());
        }
    }

    private void extractWithLlmQuietly(String ws, String userText) {
        try {
            if (!isAutoLearnEnabled(ws)) return;
            List<MemoryRow> existing = jdbc.sql("select * from workspace_memory where workspace_id = ? order by updated_at desc")
                    .param(ws).query(MemoryRow::map).list();
            if (existing.size() >= MAX_ITEMS_PER_WORKSPACE) return;

            LlmRoute route = router.forWorkspace(ws, LlmRouter.AUTO);
            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("【已有记忆列表】\n");
            if (existing.isEmpty()) {
                userPrompt.append("（暂无）\n");
            } else {
                for (MemoryRow r : existing) {
                    userPrompt.append("- [").append(r.category()).append("] ").append(r.content()).append("\n");
                }
            }
            userPrompt.append("\n【用户最新聊天消息】\n").append(userText);

            ObjectNode body = Json.obj();
            body.put("model", route.model());
            body.put("max_tokens", 300);
            body.put("reasoning_effort", "low");
            ArrayNode msgs = body.putArray("messages");
            msgs.addObject().put("role", "system").put("content", EXTRACT_SYSTEM);
            msgs.addObject().put("role", "user").put("content", userPrompt.toString());

            HttpResponse<InputStream> res = upstream.send(route, "POST", "/chat/completions",
                    Json.write(body).getBytes(StandardCharsets.UTF_8));
            byte[] raw;
            try (InputStream in = res.body()) { raw = in.readAllBytes(); }
            if (res.statusCode() >= 400) return;

            String out = Json.M.readTree(new String(raw, StandardCharsets.UTF_8))
                    .path("choices").path(0).path("message").path("content").asText("").trim();
            if (out.isEmpty()) return;
            // 兼容模型偶尔包裹的 ```json ... ```
            int start = out.indexOf('[');
            int end = out.lastIndexOf(']');
            if (start < 0 || end <= start) return;
            JsonNode arr = Json.M.readTree(out.substring(start, end + 1));
            if (!arr.isArray() || arr.isEmpty()) return;

            int added = 0;
            for (JsonNode item : arr) {
                if (added >= 2) break;
                String cat = item.path("category").asText("preference").trim();
                if (!VALID_CATEGORIES.contains(cat)) cat = "preference";
                String content = item.path("content").asText("").trim();
                if (content.length() < 4 || content.length() > MAX_ITEM_CHARS) continue;
                if (SECRET_LIKE.matcher(content).find()) continue;
                if (saveAutoIfNovel(ws, cat, content)) {
                    added++;
                }
            }
            if (added > 0) {
                log.info("已从聊天中自动提炼 {} 条工作空间长期记忆 ws={}", added, ws);
            }
        } catch (Exception e) {
            log.debug("后台 LLM 自动记忆提炼跳过 ws={}: {}", ws, e.getMessage());
        }
    }

    private boolean saveAutoIfNovel(String ws, String category, String content) {
        if (content.length() < 4 || content.length() > MAX_ITEM_CHARS) return false;
        if (SECRET_LIKE.matcher(content).find()) return false;
        long count = jdbc.sql("select count(*) from workspace_memory where workspace_id = ?").param(ws).query(Long.class).single();
        if (count >= MAX_ITEMS_PER_WORKSPACE) return false;
        long dup = jdbc.sql("select count(*) from workspace_memory where workspace_id = ? and lower(content) = lower(?)")
                .params(ws, content).query(Long.class).single();
        if (dup > 0) return false;
        insertInternal(ws, category, content, "auto", true);
        return true;
    }

    private MemoryRow insertInternal(String ws, String category, String content, String source, boolean enabled) {
        long count = jdbc.sql("select count(*) from workspace_memory where workspace_id = ?").param(ws).query(Long.class).single();
        if (count >= MAX_ITEMS_PER_WORKSPACE) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_LIMIT",
                    "个人工作空间最多保存 " + MAX_ITEMS_PER_WORKSPACE + " 条记忆，请先删除旧记忆后再新增");
        }
        String id = Ids.newId("mem");
        jdbc.sql("insert into workspace_memory (id, workspace_id, category, content, source, enabled) values (?,?,?,?,?,?)")
                .params(id, ws, category, content, source, enabled).update();
        return find(ws, id).orElseThrow();
    }
}
