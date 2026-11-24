package com.tcl.csot.zhiye.service.help;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.constants.Capabilities;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 动态帮助知识库服务：
 * - 单一事实来源：同时驱动左侧「帮助文档」展示与「小织」机器人 System Prompt
 * - 管理员可在管理台在线新增/修改/启停条目，保存后内存缓存立即失效，免发版秒级生效
 */
@Service
public class HelpKnowledgeService {
    public static final Set<String> VALID_CATEGORIES = Set.of("limits", "monolith", "preflight", "cloud", "guide", "faq");

    public record Article(
            String id, String slug, String category, String title, String shortTitle, String badge,
            String summary, String contentMd, String keywords, String askPrompt,
            int sortOrder, boolean forDocs, boolean forBot, boolean enabled,
            String updatedBy, Instant createdAt, Instant updatedAt
    ) {
        static Article map(ResultSet rs, int i) throws SQLException {
            return new Article(
                    rs.getString("id"), rs.getString("slug"), rs.getString("category"),
                    rs.getString("title"), rs.getString("short_title"), rs.getString("badge"),
                    rs.getString("summary"), rs.getString("content_md"), rs.getString("keywords"),
                    rs.getString("ask_prompt"), rs.getInt("sort_order"),
                    rs.getBoolean("for_docs"), rs.getBoolean("for_bot"), rs.getBoolean("enabled"),
                    rs.getString("updated_by"), Db.ts(rs, "created_at"), Db.ts(rs, "updated_at")
            );
        }

        public Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("slug", slug);
            m.put("category", category);
            m.put("title", title);
            m.put("shortTitle", shortTitle);
            m.put("badge", badge);
            m.put("summary", summary);
            m.put("contentMd", contentMd);
            m.put("keywords", keywords);
            m.put("askPrompt", askPrompt);
            m.put("sortOrder", sortOrder);
            m.put("forDocs", forDocs);
            m.put("forBot", forBot);
            m.put("enabled", enabled);
            m.put("updatedBy", updatedBy);
            m.put("updatedAt", Db.iso(updatedAt));
            return m;
        }
    }

    private final JdbcClient jdbc;
    private final AtomicReference<List<Article>> cache = new AtomicReference<>();

    public HelpKnowledgeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Article> allCached() {
        List<Article> cur = cache.get();
        if (cur != null) return cur;
        List<Article> loaded = jdbc.sql("select * from help_knowledge order by sort_order asc, id asc")
                .query(Article::map).list();
        cache.set(loaded);
        return loaded;
    }

    public void invalidate() {
        cache.set(null);
    }

    /** 公开接口：左侧「帮助文档」读取已启用的文档条目 */
    public List<Map<String, Object>> publicDocs() {
        return allCached().stream()
                .filter(a -> a.enabled() && a.forDocs())
                .map(Article::view)
                .toList();
    }

    /** 管理台：查看全部条目（含已禁用） */
    public List<Map<String, Object>> adminList() {
        return allCached().stream().map(Article::view).toList();
    }

    /** 动态拼装小织机器人 System Prompt（从数据库读取最新已启用知识，免发版生效） */
    public String buildBotSystemPrompt() {
        List<Article> botArticles = allCached().stream()
                .filter(a -> a.enabled() && a.forBot())
                .toList();
        if (botArticles.isEmpty()) {
            return HelpPrompt.SYSTEM;
        }
        StringBuilder sb = new StringBuilder(HelpPrompt.PERSONA_AND_RULES);
        sb.append("\n# 产品知识（实时同步自平台知识库）\n");
        for (Article a : botArticles) {
            sb.append("\n## ").append(a.title()).append("\n");
            if (!a.summary().isBlank()) sb.append(a.summary()).append("\n");
            sb.append(a.contentMd()).append("\n");
        }
        sb.append("\n").append(Capabilities.HELP);
        return sb.toString();
    }

    public Map<String, Object> create(JsonNode body, String actor) {
        if (body == null || !body.isObject()) throw ApiException.validation("缺少知识条目参数");
        String title = reqStr(body, "title", 120);
        String contentMd = reqStr(body, "contentMd", 24_000);
        String slug = optStr(body, "slug", 64);
        if (slug.isBlank()) slug = "doc-" + Long.toString(System.currentTimeMillis(), 36);
        slug = slug.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        if (slug.isBlank()) throw ApiException.validation("slug 不能为空");
        String category = optStr(body, "category", 32);
        if (!VALID_CATEGORIES.contains(category)) category = "guide";
        String shortTitle = optStr(body, "shortTitle", 24);
        if (shortTitle.isBlank()) shortTitle = title.length() <= 8 ? title : title.substring(0, 8);
        String badge = optStr(body, "badge", 16);
        if (badge.isBlank()) badge = null;
        String summary = optStr(body, "summary", 300);
        String keywords = optStr(body, "keywords", 500);
        String askPrompt = optStr(body, "askPrompt", 200);
        int sortOrder = body.has("sortOrder") ? body.path("sortOrder").asInt(100) : 100;
        boolean forDocs = !body.has("forDocs") || body.path("forDocs").asBoolean(true);
        boolean forBot = !body.has("forBot") || body.path("forBot").asBoolean(true);
        boolean enabled = !body.has("enabled") || body.path("enabled").asBoolean(true);

        long dup = jdbc.sql("select count(*) from help_knowledge where slug = ?").param(slug).query(Long.class).single();
        if (dup > 0) throw ApiException.conflict("DUPLICATE_SLUG", "标识 slug 已存在：" + slug);

        String id = Ids.newId("hk");
        jdbc.sql("""
                insert into help_knowledge (id, slug, category, title, short_title, badge, summary, content_md,
                    keywords, ask_prompt, sort_order, for_docs, for_bot, enabled, updated_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)
                .params(id, slug, category, title, shortTitle, badge, summary, contentMd,
                        keywords, askPrompt, sortOrder, forDocs, forBot, enabled, actor)
                .update();
        invalidate();
        return find(id).orElseThrow().view();
    }

    public Map<String, Object> update(String id, JsonNode body, String actor) {
        Article cur = find(id).orElseThrow(() -> ApiException.notFound("知识库条目"));
        if (body == null || !body.isObject()) throw ApiException.validation("缺少更新内容");
        String title = body.has("title") ? reqStr(body, "title", 120) : cur.title();
        String contentMd = body.has("contentMd") ? reqStr(body, "contentMd", 24_000) : cur.contentMd();
        String category = body.has("category") ? optStr(body, "category", 32) : cur.category();
        if (!VALID_CATEGORIES.contains(category)) category = cur.category();
        String shortTitle = body.has("shortTitle") ? optStr(body, "shortTitle", 24) : cur.shortTitle();
        if (shortTitle.isBlank()) shortTitle = title.length() <= 8 ? title : title.substring(0, 8);
        String badge = body.has("badge") ? optStr(body, "badge", 16) : (cur.badge() == null ? "" : cur.badge());
        if (badge.isBlank()) badge = null;
        String summary = body.has("summary") ? optStr(body, "summary", 300) : cur.summary();
        String keywords = body.has("keywords") ? optStr(body, "keywords", 500) : cur.keywords();
        String askPrompt = body.has("askPrompt") ? optStr(body, "askPrompt", 200) : cur.askPrompt();
        int sortOrder = body.has("sortOrder") ? body.path("sortOrder").asInt(cur.sortOrder()) : cur.sortOrder();
        boolean forDocs = body.has("forDocs") ? body.path("forDocs").asBoolean(cur.forDocs()) : cur.forDocs();
        boolean forBot = body.has("forBot") ? body.path("forBot").asBoolean(cur.forBot()) : cur.forBot();
        boolean enabled = body.has("enabled") ? body.path("enabled").asBoolean(cur.enabled()) : cur.enabled();

        jdbc.sql("""
                update help_knowledge set category=?, title=?, short_title=?, badge=?, summary=?, content_md=?,
                    keywords=?, ask_prompt=?, sort_order=?, for_docs=?, for_bot=?, enabled=?, updated_by=?, updated_at=now()
                where id=?
                """)
                .params(category, title, shortTitle, badge, summary, contentMd,
                        keywords, askPrompt, sortOrder, forDocs, forBot, enabled, actor, id)
                .update();
        invalidate();
        return find(id).orElseThrow().view();
    }

    public void delete(String id) {
        int n = jdbc.sql("delete from help_knowledge where id = ?").param(id).update();
        if (n == 0) throw ApiException.notFound("知识库条目");
        invalidate();
    }

    public Optional<Article> find(String id) {
        return jdbc.sql("select * from help_knowledge where id = ?").param(id).query(Article::map).optional();
    }

    private static String reqStr(JsonNode body, String field, int max) {
        String s = body.path(field).asText("").trim();
        if (s.isEmpty()) throw ApiException.validation("请填写 " + field);
        if (s.length() > max) throw ApiException.validation(field + " 最多 " + max + " 个字符");
        return s;
    }

    private static String optStr(JsonNode body, String field, int max) {
        String s = body.path(field).asText("").trim();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
