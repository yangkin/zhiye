package com.tcl.csot.zhiye.service.conversation;

import com.tcl.csot.zhiye.repository.ConversationRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.app.AppService;
import com.tcl.csot.zhiye.service.app.Catalog;
import com.tcl.csot.zhiye.service.chat.AskAttachments;
import com.tcl.csot.zhiye.service.chat.AskService;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 创造页会话：持久化问答、按工作空间隔离，并把多轮结论整理成创建需求。 */
@Service
public class ConversationService {
    private static final int MAX_HISTORY = 12;
    private static final int MAX_HISTORY_CHARS = 2000;
    private static final int MAX_TITLE_CHARS = 80;
    private static final int AUTO_TITLE_CHARS = 24;
    private static final int MAX_BRIEF_CHARS = 12000;
    /** AppService.create 目前限制 prompt 为 12000 字，创建时与该上限对齐。 */
    private static final int APP_PROMPT_CHARS = 12000;

    private static final String BRIEF_HEADER = "请根据以下按时间顺序排列的多轮对话整理并创建应用。"
            + "assistant 已给出的功能结论、核心功能/二期再做/明确不做等范围约束，以及最近一次 user 的确认都必须保留；"
            + "不要只依据 user 消息，不要擅自扩大范围。对话中的假设要明确标注为假设。\n\n对话如下：";

    private final ConversationRepository repo;
    private final AskService ask;
    private final AppService apps;
    private final AskAttachments attachments;

    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final ConversationFileService files;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.service.source.DataSourceService dataSources;

    public ConversationService(ConversationRepository repo, AskService ask, AppService apps, AskAttachments attachments,
                               org.springframework.jdbc.core.simple.JdbcClient jdbc, ConversationFileService files) {
        this.repo = repo;
        this.ask = ask;
        this.apps = apps;
        this.attachments = attachments;
        this.jdbc = jdbc;
        this.files = files;
    }

    public record BuildRequest(String type, String mode, String model, List<String> assetIds, List<String> skillAppIds, List<String> sourceIds,
                               String billingType) {}

    /** 已在请求线程中完成校验并保存 user 消息，后台线程只负责调用模型。 */
    /** type：创造页选的应用类型（只作参考，形态仍由模型判断） */
    public record Prepared(String workspaceId, String conversationId, String content,
                           List<String> skillAppIds, List<JsonNode> history, String type,
                           List<AskAttachments.Attached> assets, String sourceContext) {}

    public List<Map<String, Object>> files(String ws, String id) {
        require(ws, id);
        backfillFiles(id);
        return files.list(id);
    }

    public Map<String, Object> file(String ws, String id, String fileId) { require(ws, id); return files.get(id, fileId); }

    /** V19 之前写出的文件只在消息正文里：第一次打开文件面板时按消息顺序补记一遍 */
    private void backfillFiles(String id) {
        if (files.hasAny(id)) return;
        for (ConversationRepository.MessageRow msg : repo.messages(id)) {
            if ("assistant".equals(msg.role())) files.saveFromMessage(id, msg.id(), msg.content());
        }
    }

    public void deleteFile(String ws, String id, String fileId) { require(ws, id); files.delete(id, fileId); }

    /** 最后一条消息是不是助手的回答（回答已经完整保存） */
    public boolean lastIsAssistant(String ws, String id) {
        require(ws, id);
        var last = repo.recent(id, 1);
        return !last.isEmpty() && "assistant".equals(last.get(0).role());
    }

    public ConversationRepository.ConversationRow require(String ws, String id) {
        if (id == null || id.isBlank()) throw ApiException.notFound("对话");
        return repo.find(ws, id).orElseThrow(() -> ApiException.notFound("对话"));
    }

    private static Map<String, Object> view(ConversationRepository.ConversationRow c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("title", c.title());
        m.put("createdAt", Db.iso(c.createdAt()));
        m.put("updatedAt", Db.iso(c.updatedAt()));
        return m;
    }

    private Map<String, Object> viewMessage(ConversationRepository.MessageRow msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", msg.id());
        m.put("role", msg.role());
        m.put("content", msg.content());
        m.put("createdAt", Db.iso(msg.createdAt()));
        if (msg.thinking() != null && !msg.thinking().isBlank()) m.put("thinking", msg.thinking());
        // 消息附带的素材：id + 文件名 + 类型，历史里显示成附件标签 / 图片预览
        if (msg.assetIds() != null && msg.assetIds().isArray() && !msg.assetIds().isEmpty()) {
            List<Map<String, Object>> assets = new ArrayList<>();
            for (JsonNode n : msg.assetIds()) {
                String aid = n.asText();
                if (aid == null || aid.isBlank()) continue;
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", aid);
                jdbc.sql("select name, mime from asset where id = ?").param(aid)
                        .query((rs, i) -> Map.of("name", rs.getString(1), "mime", rs.getString(2))).optional()
                        .ifPresentOrElse(meta -> { a.putAll(meta); assets.add(a); },
                                () -> { a.put("name", aid); assets.add(a); });
            }
            if (!assets.isEmpty()) m.put("assets", assets);
        }
        return m;
    }

    public Map<String, Object> create(String ws, JsonNode body) {
        String title = body == null ? "" : body.path("title").asText("").trim();
        if (title.isEmpty()) title = ConversationRepository.DEFAULT_TITLE;
        if (title.length() > MAX_TITLE_CHARS) throw ApiException.validation("对话标题最多 " + MAX_TITLE_CHARS + " 字");
        List<String> sourceIds = dataSources.validateIds(ws, stringIds(body, "sourceIds"));
        return view(repo.create(ws, title, sourceIds));
    }

    public List<Map<String, Object>> updateSources(String ws, String id, List<String> sourceIds) {
        require(ws, id);
        List<String> valid = dataSources.validateIds(ws, sourceIds);
        repo.updateSources(id, valid);
        return dataSources.selection(ws, valid);
    }

    private static List<String> stringIds(JsonNode body, String field) {
        if (body == null || !body.path(field).isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode id : body.get(field)) if (id.isTextual() && !id.asText().isBlank()) out.add(id.asText());
        return out;
    }

    private static List<String> sourceIds(JsonNode value) {
        return stringIds(Json.obj().set("ids", value == null ? Json.M.createArrayNode() : value), "ids");
    }

    /** 历史页的会话记录：带消息数、文件数，以及从这段会话创建的应用（应用已删除则不带） */
    public List<Map<String, Object>> list(String ws) {
        Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
        jdbc.sql("""
                select c.id,
                       (select count(*) from conversation_message m where m.conversation_id = c.id) as messages,
                       (select count(distinct f.name) from conversation_file f
                         where f.conversation_id = c.id and f.deleted_at is null) as files,
                       a.id as app_id, a.name as app_name
                  from conversation c
                  left join lateral (select a.id, a.name from app_conversation ac join app a on a.id = ac.app_id
                                      where ac.conversation_id = c.id and a.deleted_at is null
                                      order by ac.created_at desc limit 1) a on true
                 where c.workspace_id = ? and c.deleted_at is null""").param(ws).query(rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("messageCount", rs.getInt("messages"));
            m.put("fileCount", rs.getInt("files"));
            if (rs.getString("app_id") != null) {
                m.put("appId", rs.getString("app_id"));
                m.put("appName", rs.getString("app_name"));
            }
            stats.put(rs.getString("id"), m);
        });
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConversationRepository.ConversationRow c : repo.list(ws)) {
            Map<String, Object> m = view(c);
            if (c.lastMessage() != null) m.put("lastMessage", c.lastMessage());
            m.putAll(stats.getOrDefault(c.id(), Map.of()));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> detail(String ws, String id) {
        ConversationRepository.ConversationRow c = require(ws, id);
        Map<String, Object> m = view(c);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ConversationRepository.MessageRow msg : repo.messages(id)) messages.add(viewMessage(msg));
        m.put("messages", messages);
        m.put("sources", dataSources.selection(ws, sourceIds(c.sourceIds())));
        // 从这段会话创建的最新应用：重新打开会话时还能找到入口和进度
        jdbc.sql("""
                select a.id, a.name, a.phase from app_conversation ac join app a on a.id = ac.app_id
                 where ac.conversation_id = ? and a.deleted_at is null
                 order by ac.created_at desc limit 1""").param(id)
                .query((rs, i) -> Map.of("id", rs.getString(1), "name", rs.getString(2), "phase", rs.getString(3)))
                .optional().ifPresent(a -> {
                    m.put("appId", a.get("id"));
                    m.put("appName", a.get("name"));
                    m.put("appPhase", a.get("phase"));
                });
        return m;
    }

    public void delete(String ws, String id) {
        if (!repo.softDelete(ws, id)) throw ApiException.notFound("对话");
    }

    /** 首个用户问题自动生成短标题；已有自定义标题或已有用户消息时不改名。 */
    /** 去掉回答开头的 [[build:类型]] 标记 */
    public static String stripBuildMarker(String content) {
        return content == null ? "" : content.replaceFirst("^(\\s*\\[\\[build(?::[^\\]]*)?\\]\\]\\s*)+", "");
    }

    private static final java.util.regex.Pattern ECO_BLOCK = java.util.regex.Pattern.compile("\\n*【已选企业生态能力[^】]*】[\\s\\S]*$");

    /** 剥离前端自动附加的【已选企业生态能力】指令块，避免污染会话标题、应用简介与版本标题。 */
    public static String stripEcosystemBlock(String text) {
        if (text == null || text.isEmpty()) return "";
        return ECO_BLOCK.matcher(text).replaceFirst("").trim();
    }

    public static String title(String content) {
        String clean = stripEcosystemBlock(content);
        String t = (clean.isEmpty() && content != null ? content : clean).replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return ConversationRepository.DEFAULT_TITLE;
        return cap(t, AUTO_TITLE_CHARS);
    }

    /**
     * 校验并保存一条用户消息。优先使用调用方显式提供的 history；未提供时使用服务端已保存的最近 12 条，
     * 这样刷新页面后仍能继续多轮对话，且不会把本轮 user 重复传给模型。
     */
    public Prepared prepareAsk(String ws, String id, JsonNode body) {
        ConversationRepository.ConversationRow conversation = require(ws, id);
        String content = ask.validate(body == null ? null : body.path("content").asText(""));
        List<String> skillAppIds = List.copyOf(AskService.ids(body));
        List<JsonNode> history = requestedHistory(body);
        if (history == null) history = storedHistory(id);

        // 消息里附带的素材：本轮图片走视觉、文档提取文本；前序轮次上传的文档/表格保留结构化摘要持续有效
        List<String> assetIds = AskService.assetIds(body);
        Set<String> currentAssetSet = new LinkedHashSet<>(assetIds);
        List<String> earlierAssetIds = new ArrayList<>();
        List<ConversationRepository.MessageRow> prevMessages = repo.messages(id);
        for (int i = prevMessages.size() - 1; i >= 0; i--) {
            JsonNode aids = prevMessages.get(i).assetIds();
            if (aids != null && aids.isArray()) {
                for (JsonNode n : aids) {
                    if (n.isTextual()) {
                        String aid = n.asText();
                        if (!aid.isBlank() && !currentAssetSet.contains(aid) && !earlierAssetIds.contains(aid)) {
                            earlierAssetIds.add(aid);
                        }
                    }
                }
            }
        }
        String historicalAssetsCtx = attachments.historicalContext(ws, earlierAssetIds);
        List<AskAttachments.Attached> attached = attachments.load(ws, assetIds);
        boolean firstUser = repo.userMessageCount(id) == 0;
        repo.insert(id, "user", content, null, assetIds);
        if (firstUser) repo.titleFromFirstUser(id, title(content));
        repo.touch(id);
        String type = body == null ? "" : body.path("type").asText("");
        String sourceSummary = dataSources.conversationSummary(ws, sourceIds(conversation.sourceIds()));
        String combinedContext = (sourceSummary == null ? "" : sourceSummary) + historicalAssetsCtx;
        return new Prepared(ws, id, content, skillAppIds, history, Catalog.TYPES.contains(type) ? type : null, attached,
                combinedContext);
    }

    /** 只有回答完整产生后才保存 assistant；会话已被删除时不写回。 */
    public void saveAssistant(String ws, String id, String content, String thinking) {
        if (content == null || content.isBlank() || repo.find(ws, id).isEmpty()) return;
        ConversationRepository.MessageRow row = repo.insert(id, "assistant", content, thinking);
        files.saveFromMessage(id, row.id(), content);
        repo.touch(id);
    }

    private static List<JsonNode> requestedHistory(JsonNode body) {
        if (body == null || !body.has("history") || !body.get("history").isArray()) return null;
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode h : body.get("history")) {
            if (!h.isObject()) continue;
            out.add(h);
        }
        return AskService.normalizeHistory(out);
    }

    private List<JsonNode> storedHistory(String id) {
        List<JsonNode> out = new ArrayList<>();
        for (ConversationRepository.MessageRow msg : repo.recent(id, MAX_HISTORY * 2)) {
            out.add(Json.obj().put("role", msg.role()).put("content", msg.content()));
        }
        return AskService.normalizeHistory(out);
    }

    /** 带文件（[[file:…]]）的回答保留全文：用户接着说「第 3 节再展开」时模型要看到整份文档 */
    static int historyCap(String text) {
        return text != null && text.contains(AskService.FILE_OPEN) ? AskService.MAX_FILE_CHARS : MAX_HISTORY_CHARS;
    }

    /** 创建应用前整理完整对话，而不是只拼接用户消息。 */
    public Map<String, Object> build(String ws, String id, BuildRequest request, String idemKey) throws Exception {
        ConversationRepository.ConversationRow conversation = require(ws, id);
        List<ConversationRepository.MessageRow> messages = repo.messages(id);
        boolean hasUser = false;
        for (ConversationRepository.MessageRow msg : messages) {
            if ("user".equals(msg.role()) && !msg.content().isBlank()) {
                hasUser = true;
                break;
            }
        }
        if (!hasUser) throw ApiException.validation("对话还没有用户需求，请先聊几句再创建应用");

        BuildRequest req = request == null ? new BuildRequest(null, null, null, null, null, null, null) : request;
        String brief = dialogueBrief(messages, MAX_BRIEF_CHARS);
        // 复用现有创建入口时不能超过它的 prompt 限制；两次压缩都保留首条需求、最近 assistant 和最近 user。
        String prompt = brief.length() <= APP_PROMPT_CHARS ? brief : dialogueBrief(messages, APP_PROMPT_CHARS);
        String type = inferType(messages, req.type());
        // 聊天过程里附过的素材也带给应用（参考图、需求文档等），不只是点「开始做」这一刻选的
        LinkedHashSet<String> assetIds = new LinkedHashSet<>();
        for (ConversationRepository.MessageRow msg : messages) {
            if (msg.assetIds() != null) for (JsonNode n : msg.assetIds()) if (n.isTextual()) assetIds.add(n.asText());
        }
        if (req.assetIds() != null) assetIds.addAll(req.assetIds());
        LinkedHashSet<String> selectedSources = new LinkedHashSet<>();
        for (var source : dataSources.visibleSelection(ws, sourceIds(conversation.sourceIds()))) selectedSources.add(source.id());
        if (req.sourceIds() != null) selectedSources.addAll(req.sourceIds());
        // 若对话里用户或助手实时调用/明确提到了当前工作空间可见的数据源（按 key 或名称匹配），创建应用时自动一并绑定，免去用户重复手动绑定
        StringBuilder allDialogue = new StringBuilder();
        for (ConversationRepository.MessageRow msg : messages) {
            if (msg.content() != null) allDialogue.append('\n').append(msg.content());
        }
        String dialogueText = allDialogue.toString();
        if (!dialogueText.isBlank()) {
            for (var src : dataSources.listVisibleSources(ws)) {
                if (selectedSources.contains(src.id())) continue;
                boolean keyMatch = src.key() != null && src.key().length() >= 3
                        && java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(src.key()) + "(?![A-Za-z0-9_])")
                                .matcher(dialogueText).find();
                boolean nameMatch = src.name() != null && src.name().trim().length() >= 3
                        && dialogueText.contains(src.name().trim());
                if (keyMatch || nameMatch) selectedSources.add(src.id());
            }
        }
        AppService.CreateRequest create = new AppService.CreateRequest(type, prompt, req.mode(), req.model(),
                List.copyOf(assetIds), null, req.skillAppIds(), List.copyOf(selectedSources), req.billingType());
        Map<String, Object> app = apps.create(ws, create, idemKey);
        Object appId = app.get("id");
        if (appId != null) {
            // 记下来源会话：工作台据此把对话和应用显示在一起
            jdbc.sql("insert into app_conversation (app_id, conversation_id) values (?, ?) on conflict (app_id) do nothing")
                    .params(String.valueOf(appId), id).update();
        }
        return app;
    }

    /**
     * 全部内容能放下时逐条保留。超长时保留首条 user、最近 assistant、最近 user，再补最近消息，
     * 对选中的消息等比压缩；最终仍按原始时间顺序输出并带明确角色标记。
     */
    static String dialogueBrief(List<ConversationRepository.MessageRow> messages, int limit) {
        List<ConversationRepository.MessageRow> usable = new ArrayList<>();
        for (ConversationRepository.MessageRow msg : messages) {
            if (msg == null || msg.content() == null || msg.content().isBlank()) continue;
            if (!"user".equals(msg.role()) && !"assistant".equals(msg.role())) continue;
            usable.add(msg);
        }
        String full = renderBrief(usable, -1);
        if (full.length() <= limit) return full;

        int firstUser = -1;
        int lastAssistant = -1;
        int lastUser = -1;
        for (int i = 0; i < usable.size(); i++) {
            String role = usable.get(i).role();
            if (firstUser < 0 && "user".equals(role)) firstUser = i;
            if ("assistant".equals(role)) lastAssistant = i;
            if ("user".equals(role)) lastUser = i;
        }
        Set<Integer> selected = new LinkedHashSet<>();
        if (firstUser >= 0) selected.add(firstUser);
        if (lastAssistant >= 0) selected.add(lastAssistant);
        if (lastUser >= 0) selected.add(lastUser);
        for (int i = usable.size() - 1; i >= 0 && selected.size() < MAX_HISTORY; i--) selected.add(i);
        List<ConversationRepository.MessageRow> compressed = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>(selected);
        indexes.sort(Comparator.naturalOrder());
        for (int i : indexes) compressed.add(usable.get(i));
        return renderBrief(compressed, limit);
    }

    /** limit < 0 表示不压缩；否则先剥离控制标记，再用注水法按实际长度分配字符预算，避免短回复浪费配额。 */
    private static String renderBrief(List<ConversationRepository.MessageRow> messages, int limit) {
        List<String> cleaned = new ArrayList<>(messages.size());
        for (ConversationRepository.MessageRow msg : messages) cleaned.add(stripControlTags(msg.content()));
        if (limit < 0) {
            StringBuilder out = new StringBuilder(BRIEF_HEADER);
            for (int i = 0; i < messages.size(); i++) appendBlock(out, messages.get(i), cleaned.get(i));
            return out.toString();
        }
        String note = "\n\n…（内容较长，已保留首条需求、最近助手结论和最近用户确认，并对选中内容压缩）";
        int fixed = BRIEF_HEADER.length() + note.length();
        for (ConversationRepository.MessageRow msg : messages) fixed += 2 + roleLabel(msg.role()).length() + 2;
        int budget = Math.max(0, limit - fixed);
        int[] caps = allocateBudgets(cleaned, budget);
        StringBuilder out = new StringBuilder(BRIEF_HEADER);
        for (int i = 0; i < messages.size(); i++) appendBlock(out, messages.get(i), AskService.cap(cleaned.get(i), caps[i]));
        out.append(note);
        return AskService.cap(out.toString(), limit);
    }

    private static int[] allocateBudgets(List<String> texts, int budget) {
        int n = texts.size();
        int[] caps = new int[n];
        boolean[] done = new boolean[n];
        int remaining = budget;
        int left = n;
        while (left > 0) {
            int share = remaining / left;
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                if (!done[i] && texts.get(i).length() <= share) {
                    caps[i] = texts.get(i).length();
                    remaining -= caps[i];
                    done[i] = true;
                    left--;
                    changed = true;
                }
            }
            if (!changed) {
                int rem = remaining - share * left;
                for (int i = n - 1; i >= 0; i--) {
                    if (!done[i]) {
                        caps[i] = share + (rem-- > 0 ? 1 : 0);
                    }
                }
                break;
            }
        }
        return caps;
    }

    private static String stripControlTags(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?m)^\\s*\\[\\[(?:next|build(?::[^\\]]*)?)\\]\\].*$", "").trim();
    }

    private static void appendBlock(StringBuilder out, ConversationRepository.MessageRow msg, String content) {
        out.append("\n\n").append(roleLabel(msg.role())).append("：\n").append(content);
    }

    private static String roleLabel(String role) {
        return "assistant".equals(role) ? "assistant（助手）" : "user（用户）";
    }

    private static final java.util.regex.Pattern BUILD_TYPE = java.util.regex.Pattern.compile("\\[\\[build:\\s*([^\\]]+?)\\s*\\]\\]");

    /**
     * 类型优先听模型的：最近一条 [[build:类型]] 是模型按整段对话（含截图）判断的成品形态。
     * 没有时才退回用户原话里的关键词、页面上选的类型。不拿助手的描述文字匹配关键词——
     * 它复述截图时会提到「开发skill」这类界面文字，曾把「照着做一个工作台」判成技能包。
     */
    static String inferType(List<ConversationRepository.MessageRow> messages, String requested) {
        // 若用户在创造页明确选定了「全栈应用」（含点击全栈模板），绝不降级覆盖
        if (Catalog.FULLSTACK_TYPE.equals(requested)) return Catalog.FULLSTACK_TYPE;
        StringBuilder user = new StringBuilder();
        String declared = null;
        for (ConversationRepository.MessageRow msg : messages) {
            if (msg.content() == null) continue;
            if ("user".equals(msg.role())) user.append(' ').append(msg.content());
            if ("assistant".equals(msg.role())) {
                var m = BUILD_TYPE.matcher(msg.content());
                while (m.find()) {
                    if (Catalog.TYPES.contains(m.group(1))) declared = m.group(1);
                }
            }
        }
        String userText = user.toString();
        // 用户未主动选择「全栈应用」且需求本身是纯页面/不要后端时，不让模型误输出的 [[build:全栈应用]] 劫持成服务托管
        if (Catalog.FULLSTACK_TYPE.equals(declared) && Catalog.isPureHtmlOrNoBackendIntent(userText)) {
            declared = null;
        }
        if (declared != null) return declared;
        String inferred = inferTypeText(userText);
        if (inferred != null) return inferred;
        return requested == null || requested.isBlank() ? Catalog.DEFAULT_TYPE : requested;
    }

    private static String inferTypeText(String raw) {
        String text = raw.toLowerCase(Locale.ROOT);
        if (Catalog.isPureHtmlOrNoBackendIntent(raw)) {
            if (containsAny(text, "工作台", "管理系统", "看板", "小工具", "计算器", "点检", "巡检", "效率工具")) return "日常开发";
            return "开发网站";
        }
        if (Catalog.shouldForceFullstack(raw)
                || containsAny(text, "全栈应用", "前后端一体", "前后端分离", "express 后端", "node 后端", "spring boot", "springboot", "java 后端", "java后端", "fastapi", "flask", "django"))
            return Catalog.FULLSTACK_TYPE;
        if (containsAny(text, "开发skill", "skill.md", "技能包", "技能应用", "做一个 skill", "生成 skill")) return Catalog.SKILL_TYPE;
        if (containsAny(text, "技术文档", "文档站", "文档网站", "开发文档", "使用文档", "api 文档", "api文档", "markdown 文档", "知识库"))
            return "技术文档";
        if (containsAny(text, "日常开发", "工作台", "管理系统", "后台", "小工具", "计算器", "看板", "点检", "巡检", "命令行", "数据清洗", "表格清洗", "脚本", "效率工具"))
            return "日常开发";
        if (containsAny(text, "开发网站", "官网", "官方网站", "活动页", "落地页", "门户", "宣传页", "展示网站", "公司网站", "首页", "html 页面", "html页面", "html 网页", "静态页面", "静态网页"))
            return "开发网站";
        return null;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    /** 按 UTF-16 长度截断并避免切断代理对（共用 AskService.cap）。 */
    private static String cap(String text, int max) { return AskService.cap(text, max); }
}
