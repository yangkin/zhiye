package com.tcl.csot.zhiye.service.conversation;

import com.tcl.csot.zhiye.service.chat.AskService;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 会话文件：从助手回答里拆出文件块保存成版本；列表、读取、删除。 */
@Service
public class ConversationFileService {
    private static final Pattern BLOCK = Pattern.compile("\\[\\[file:([^\\]\\n]{1,120})\\]\\]\\s*\\n?(.*?)\\n?\\s*\\[\\[/file\\]\\]", Pattern.DOTALL);

    public record FileBlock(String name, String content) {}

    private final JdbcClient jdbc;

    public ConversationFileService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 与前端 splitFiles 相同的规则：名称去掉非法字符，没有扩展名时补 .md */
    static String safeName(String raw) {
        String n = raw.trim().replaceAll("[\\\\/:*?\"<>|]+", "-");
        if (n.isEmpty()) n = "文档";
        return n.matches("(?i).*\\.(md|markdown|txt)$") ? n : n + ".md";
    }

    public static List<FileBlock> blocks(String content) {
        List<FileBlock> out = new ArrayList<>();
        if (content == null || !content.contains(AskService.FILE_OPEN)) return out;
        Matcher m = BLOCK.matcher(content);
        while (m.find()) out.add(new FileBlock(safeName(m.group(1)), m.group(2).strip()));
        return out;
    }

    /** 助手回答保存后调用：每个文件块记一个新版本 */
    public void saveFromMessage(String conversationId, String messageId, String content) {
        for (FileBlock b : blocks(content)) {
            if (b.content().isBlank()) continue;
            int v = jdbc.sql("select coalesce(max(version), 0) from conversation_file where conversation_id = ? and name = ?")
                    .params(conversationId, b.name()).query(Integer.class).single() + 1;
            jdbc.sql("insert into conversation_file (id, conversation_id, message_id, name, version, content) values (?, ?, ?, ?, ?, ?)")
                    .params(Ids.newId("cfile"), conversationId, messageId, b.name(), v, b.content()).update();
        }
    }

    /** 含已删除的：有过记录就说明不需要再从消息补记 */
    public boolean hasAny(String conversationId) {
        return jdbc.sql("select exists (select 1 from conversation_file where conversation_id = ?)")
                .param(conversationId).query(Boolean.class).single();
    }

    /** 每个名称的最新版本（已删除的不显示），新的在前 */
    public List<Map<String, Object>> list(String conversationId) {
        return jdbc.sql("""
                select distinct on (name) id, name, version, length(content) as size, message_id, created_at
                  from conversation_file where conversation_id = ? and deleted_at is null
                 order by name, version desc""").param(conversationId)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("name", rs.getString("name"));
                    m.put("version", rs.getInt("version"));
                    m.put("size", rs.getInt("size"));
                    m.put("messageId", rs.getString("message_id"));
                    m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
                    return m;
                }).list().stream()
                .sorted(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("createdAt"))).reversed())
                .toList();
    }

    public Map<String, Object> get(String conversationId, String fileId) {
        return jdbc.sql("select id, name, version, content, created_at from conversation_file where conversation_id = ? and id = ? and deleted_at is null")
                .params(conversationId, fileId).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("name", rs.getString("name"));
                    m.put("version", rs.getInt("version"));
                    m.put("content", rs.getString("content"));
                    m.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
                    return m;
                }).optional().orElseThrow(() -> ApiException.notFound("文件"));
    }

    /** 删除一个文件：同名的所有版本都不再显示（对话里的回答保留） */
    public void delete(String conversationId, String fileId) {
        String name = (String) get(conversationId, fileId).get("name");
        jdbc.sql("update conversation_file set deleted_at = now() where conversation_id = ? and name = ? and deleted_at is null")
                .params(conversationId, name).update();
    }
}
