package com.tcl.csot.zhiye.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** 创造页会话的数据访问。所有会话查询都带 workspace_id，消息访问前先由服务层校验归属。 */
@Repository
public class ConversationRepository {
    public static final String DEFAULT_TITLE = "新对话";

    public record ConversationRow(String id, String workspaceId, String title, Instant createdAt, Instant updatedAt,
                                  String lastMessage) {}

    public record MessageRow(String id, String conversationId, String role, String content, Instant createdAt, String thinking,
                             JsonNode assetIds) {}

    private final JdbcClient jdbc;

    public ConversationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private static ConversationRow conversation(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationRow(rs.getString("id"), rs.getString("workspace_id"), rs.getString("title"),
                Db.ts(rs, "created_at"), Db.ts(rs, "updated_at"), rs.getString("last_message"));
    }

    private static MessageRow message(ResultSet rs, int rowNum) throws SQLException {
        return new MessageRow(rs.getString("id"), rs.getString("conversation_id"), rs.getString("role"),
                rs.getString("content"), Db.ts(rs, "created_at"), rs.getString("thinking"), Json.read(rs.getString("asset_ids")));
    }

    /** 最近一次消息作为列表摘要；没有消息时为 null。 */
    private static final String LAST_MESSAGE = """
            (select m.content from conversation_message m
              where m.conversation_id = c.id
              order by m.created_at desc, m.id desc limit 1) as last_message
            """;

    public ConversationRow create(String ws, String title) {
        String id = Ids.newId("conv");
        jdbc.sql("insert into conversation (id, workspace_id, title) values (?, ?, ?)")
                .params(id, ws, title).update();
        return find(ws, id).orElseThrow();
    }

    public Optional<ConversationRow> find(String ws, String id) {
        return jdbc.sql("select c.*, " + LAST_MESSAGE + " from conversation c " +
                        "where c.id = ? and c.workspace_id = ? and c.deleted_at is null")
                .params(id, ws).query(ConversationRepository::conversation).optional();
    }

    public List<ConversationRow> list(String ws) {
        return jdbc.sql("select c.*, " + LAST_MESSAGE + " from conversation c " +
                        "where c.workspace_id = ? and c.deleted_at is null " +
                        "order by c.updated_at desc, c.created_at desc, c.id desc limit 200")
                .param(ws).query(ConversationRepository::conversation).list();
    }

    public boolean softDelete(String ws, String id) {
        return jdbc.sql("update conversation set deleted_at = now(), updated_at = now() " +
                        "where id = ? and workspace_id = ? and deleted_at is null")
                .params(id, ws).update() > 0;
    }

    public List<MessageRow> messages(String conversationId) {
        return jdbc.sql("select * from conversation_message where conversation_id = ? order by created_at, id")
                .param(conversationId).query(ConversationRepository::message).list();
    }

    /** 最近 n 条消息，返回时间正序；使用 list() 让 JdbcClient 在方法返回前释放结果集。 */
    public List<MessageRow> recent(String conversationId, int n) {
        if (n <= 0) return List.of();
        List<MessageRow> rows = new ArrayList<>(jdbc.sql(
                        "select * from conversation_message where conversation_id = ? " +
                                "order by created_at desc, id desc limit ?")
                .params(conversationId, n).query(ConversationRepository::message).list());
        Collections.reverse(rows);
        return rows;
    }

    public MessageRow insert(String conversationId, String role, String content) { return insert(conversationId, role, content, null, null); }

    public MessageRow insert(String conversationId, String role, String content, String thinking) {
        return insert(conversationId, role, content, thinking, null);
    }

    public MessageRow insert(String conversationId, String role, String content, String thinking, List<String> assetIds) {
        String id = Ids.newId("cmsg");
        jdbc.sql("insert into conversation_message (id, conversation_id, role, content, thinking, asset_ids) values (?, ?, ?, ?, ?, ?::jsonb)")
                .params(id, conversationId, role, content, thinking == null || thinking.isBlank() ? null : thinking,
                        assetIds == null || assetIds.isEmpty() ? "[]" : Json.write(assetIds)).update();
        return jdbc.sql("select * from conversation_message where id = ?")
                .param(id).query(ConversationRepository::message).single();
    }

    public int userMessageCount(String conversationId) {
        return jdbc.sql("select count(*) from conversation_message where conversation_id = ? and role = 'user'")
                .param(conversationId).query(Long.class).single().intValue();
    }

    /** 只有第一条用户消息且标题仍是默认值时才自动命名，用户自定义标题不会被覆盖。 */
    public void titleFromFirstUser(String conversationId, String title) {
        jdbc.sql("update conversation c set title = ? where c.id = ? and c.title = ? " +
                        "and (select count(*) from conversation_message m " +
                        "     where m.conversation_id = c.id and m.role = 'user') = 1")
                .params(title, conversationId, DEFAULT_TITLE).update();
    }

    public void touch(String conversationId) {
        jdbc.sql("update conversation set updated_at = now() where id = ?").param(conversationId).update();
    }
}
