package com.tcl.csot.zhiye.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public class DraftRepository {
    public record DraftRow(String appId, String baseVersionId, JsonNode patches, Instant updatedAt) {}

    private final JdbcClient jdbc;

    public DraftRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public Optional<DraftRow> find(String appId) {
        return jdbc.sql("select * from draft where app_id = ?").param(appId)
                .query((rs, i) -> new DraftRow(rs.getString("app_id"), rs.getString("base_version_id"),
                        Json.read(rs.getString("patches")), Db.ts(rs, "updated_at"))).optional();
    }

    public boolean exists(String appId) {
        return jdbc.sql("select count(*) from draft where app_id = ?").param(appId).query(Long.class).single() > 0;
    }

    public void upsert(String appId, String baseVersionId, JsonNode patches) {
        jdbc.sql("""
                insert into draft (app_id, base_version_id, patches, updated_at) values (?, ?, ?::jsonb, now())
                on conflict (app_id) do update set base_version_id = excluded.base_version_id, patches = excluded.patches, updated_at = now()
                """).params(appId, baseVersionId, Json.write(patches)).update();
    }

    public void delete(String appId) { jdbc.sql("delete from draft where app_id = ?").param(appId).update(); }
}
