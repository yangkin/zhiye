package com.tcl.csot.zhiye.repository;

import com.tcl.csot.zhiye.service.help.HelpService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 小织帮助助手的数据访问：help_conversation / help_message / help_feedback。 */
@Repository
public class HelpRepository {
    public static final String DEFAULT_TITLE = "新对话";

    public record Conversation(String id, String workspaceId, String title, Instant createdAt, Instant updatedAt) {}

    public record Message(String id, String conversationId, String role, String content, List<String> assetIds,
                          String status, Instant createdAt) {}

    private final JdbcClient jdbc;

    public HelpRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static Conversation conv(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Conversation(rs.getString("id"), rs.getString("workspace_id"), rs.getString("title"),
                Db.ts(rs, "created_at"), Db.ts(rs, "updated_at"));
    }

    private static Message msg(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<String> ids = Json.read(rs.getString("asset_ids"), new TypeReference<List<String>>() {});
        return new Message(rs.getString("id"), rs.getString("conversation_id"), rs.getString("role"), rs.getString("content"),
                ids == null ? List.of() : ids, rs.getString("status"), Db.ts(rs, "created_at"));
    }

    public List<Conversation> list(String ws) {
        return jdbc.sql("select * from help_conversation where workspace_id = ? and deleted_at is null order by updated_at desc, created_at desc limit 200")
                .param(ws).query((rs, i) -> conv(rs)).list();
    }

    public Conversation create(String ws) {
        String id = Ids.newId("hc");
        jdbc.sql("insert into help_conversation (id, workspace_id) values (?, ?)").params(id, ws).update();
        return find(ws, id).orElseThrow();
    }

    public Optional<Conversation> find(String ws, String id) {
        return jdbc.sql("select * from help_conversation where id = ? and workspace_id = ? and deleted_at is null")
                .params(id, ws).query((rs, i) -> conv(rs)).optional();
    }

    public boolean softDelete(String ws, String id) {
        return jdbc.sql("update help_conversation set deleted_at = now() where id = ? and workspace_id = ? and deleted_at is null")
                .params(id, ws).update() > 0;
    }

    /** 第一条用户消息作为标题（只在标题还是默认值时） */
    public void titleIfDefault(String id, String title) {
        jdbc.sql("update help_conversation set title = ? where id = ? and title = ?").params(title, id, DEFAULT_TITLE).update();
    }

    public void touch(String id) {
        jdbc.sql("update help_conversation set updated_at = now() where id = ?").param(id).update();
    }

    public List<Message> messages(String conversationId) {
        return jdbc.sql("select * from help_message where conversation_id = ? order by created_at, id").param(conversationId)
                .query((rs, i) -> msg(rs)).list();
    }

    /** 最近 n 条可作为上下文的消息（时间正序） */
    public List<Message> recent(String conversationId, int n) {
        List<Message> l = new java.util.ArrayList<>(jdbc.sql("""
                        select * from help_message where conversation_id = ? and status in ('done','stopped','feedback')
                          and (content <> '' or asset_ids <> '[]'::jsonb)
                        order by created_at desc, id desc limit ?""")
                .params(conversationId, n).query((rs, i) -> msg(rs)).list());
        java.util.Collections.reverse(l);
        return l;
    }

    public Message addMessage(String conversationId, String role, String content, List<String> assetIds, String status) {
        String id = Ids.newId("hm");
        jdbc.sql("insert into help_message (id, conversation_id, role, content, asset_ids, status) values (?,?,?,?,?::jsonb,?)")
                .params(id, conversationId, role, content, Json.write(assetIds == null ? List.of() : assetIds), status).update();
        return jdbc.sql("select * from help_message where id = ?").param(id).query((rs, i) -> msg(rs)).single();
    }

    /** 当前工作空间自 since 起发送的用户消息数（含已删除对话里的） */
    public int userMessagesSince(String ws, Instant since) {
        return jdbc.sql("""
                        select count(*) from help_message m join help_conversation c on c.id = m.conversation_id
                        where c.workspace_id = ? and m.role = 'user' and m.status <> 'feedback' and m.created_at >= ?""")
                .params(ws, Db.odt(since)).query(Integer.class).single();
    }

    public String addFeedback(String ws, String conversationId, String content, List<String> assetIds) {
        String id = "FB" + java.time.LocalDate.now(HelpService.ZONE).format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))
                + "-" + Ids.random(5).toUpperCase();
        jdbc.sql("insert into help_feedback (id, workspace_id, conversation_id, content, asset_ids) values (?,?,?,?,?::jsonb)")
                .params(id, ws, conversationId, content, Json.write(assetIds == null ? List.of() : assetIds)).update();
        return id;
    }
}
