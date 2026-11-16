package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.repository.ConversationRepository;

import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工作台用：查这个应用是从哪段对话创建的，以及那段对话的消息。 */
@RestController
public class AppConversationController {
    private final JdbcClient jdbc;
    private final ConversationRepository repo;
    private final CurrentPrincipal principal;

    public AppConversationController(JdbcClient jdbc, ConversationRepository repo, CurrentPrincipal principal) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.principal = principal;
    }

    @GetMapping("/api/apps/{appId}/conversation")
    public Map<String, Object> conversation(@PathVariable String appId) {
        String ws = principal.workspaceId();
        Long owned = jdbc.sql("select count(*) from app where id = ? and workspace_id = ? and deleted_at is null")
                .params(appId, ws).query(Long.class).single();
        if (owned == null || owned == 0) return null;
        var convId = jdbc.sql("select conversation_id from app_conversation where app_id = ?")
                .param(appId).query(String.class).optional().orElse(null);
        if (convId == null) return null;
        var conv = repo.find(ws, convId);
        if (conv.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", conv.get().id());
        m.put("title", conv.get().title());
        m.put("messages", repo.messages(convId).stream().map(msg -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", msg.id());
            x.put("role", msg.role());
            x.put("content", msg.content());
            x.put("createdAt", msg.createdAt() == null ? null : msg.createdAt().toString());
            return x;
        }).toList());
        return m;
    }
}
