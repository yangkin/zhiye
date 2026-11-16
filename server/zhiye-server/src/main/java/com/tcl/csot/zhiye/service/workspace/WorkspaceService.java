package com.tcl.csot.zhiye.service.workspace;

import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
public class WorkspaceService {
    public record WorkspaceRow(String id, Instant createdAt) {}
    public record Created(String id, String token) {}

    private final JdbcClient jdbc;

    public WorkspaceService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<WorkspaceRow> byToken(String token) {
        if (token == null || token.isBlank() || token.length() > 200) return Optional.empty();
        return jdbc.sql("select id, created_at from workspace where token_hash = ?").param(Ids.sha256(token))
                .query((rs, i) -> new WorkspaceRow(rs.getString("id"), Db.ts(rs, "created_at"))).optional();
    }

    public Optional<WorkspaceRow> byId(String id) {
        return jdbc.sql("select id, created_at from workspace where id = ?").param(id)
                .query((rs, i) -> new WorkspaceRow(rs.getString("id"), Db.ts(rs, "created_at"))).optional();
    }

    public Created create() {
        String id = Ids.newId("ws");
        String token = Ids.token();
        jdbc.sql("insert into workspace (id, token_hash) values (?, ?)").params(id, Ids.sha256(token)).update();
        return new Created(id, token);
    }
}
