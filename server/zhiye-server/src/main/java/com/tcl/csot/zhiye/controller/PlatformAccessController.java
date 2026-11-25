package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.admin.AdminService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 人员管理（平台登录白名单）：管理员维护哪些 SSO 账号可以登录平台，以及各人的角色（user / admin）。
 * 开关 zhiye.auth.login-allowlist-enabled；开启后仅名单内启用人员与管理员可登录。
 */
@RestController
@Profile("api")
@RequestMapping("/api/admin/login-allowlist")
public class PlatformAccessController {
    private final JdbcClient jdbc;
    private final AdminService admin;
    private final CurrentPrincipal principal;

    public PlatformAccessController(JdbcClient jdbc, AdminService admin, CurrentPrincipal principal) {
        this.jdbc = jdbc;
        this.admin = admin;
        this.principal = principal;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        return jdbc.sql("""
                select login_name, real_name, note, role, enabled, created_by, created_at, updated_at
                from platform_login_allowlist order by created_at
                """).query((rs, i) -> Map.<String, Object>ofEntries(
                Map.entry("loginName", rs.getString("login_name")),
                Map.entry("realName", rs.getString("real_name") == null ? "" : rs.getString("real_name")),
                Map.entry("note", rs.getString("note") == null ? "" : rs.getString("note")),
                Map.entry("role", rs.getString("role")),
                Map.entry("enabled", rs.getBoolean("enabled")),
                Map.entry("createdBy", rs.getString("created_by") == null ? "" : rs.getString("created_by")),
                Map.entry("createdAt", rs.getTimestamp("created_at").toInstant().toString()),
                Map.entry("updatedAt", rs.getTimestamp("updated_at").toInstant().toString())
        )).list();
    }

    /** 添加人员：单个 loginName 或批量 loginNames 数组；role 可选 user / admin（默认 user）。重复添加工号会更新并重新启用。 */
    @PostMapping
    public Map<String, Object> add(@RequestBody JsonNode body, HttpServletRequest req) {
        String actor = admin.requireActor(req, principal.workspaceId());
        String role = body.path("role").asText("user").trim();
        if (!Set.of("user", "admin").contains(role)) throw ApiException.validation("角色只能是 user 或 admin");

        Set<String> names = new LinkedHashSet<>();
        if (!body.path("loginName").asText("").isBlank()) names.add(body.path("loginName").asText().trim());
        if (body.path("loginNames").isArray()) body.path("loginNames").forEach(n -> names.add(n.asText("").trim()));
        if (names.isEmpty()) throw ApiException.validation("请填写要添加的工号");

        String realName = body.path("realName").asText("").trim();
        String note = body.path("note").asText("").trim();
        List<String> failed = new ArrayList<>();
        int added = 0;
        for (String loginName : names) {
            if (!loginName.matches("[A-Za-z0-9_@.\\-]{2,64}")) {
                failed.add(loginName);
                continue;
            }
            jdbc.sql("""
                    insert into platform_login_allowlist (login_name, real_name, note, role, enabled, created_by)
                    values (?, ?, ?, ?, true, ?)
                    on conflict (login_name) do update set real_name = excluded.real_name, note = excluded.note,
                      role = excluded.role, enabled = true, updated_at = now()
                    """).params(loginName, realName, note, role, actor).update();
            added++;
        }
        return Map.of("ok", true, "added", added, "failed", failed);
    }

    @PatchMapping("/{loginName}")
    public Map<String, Object> update(@PathVariable String loginName, @RequestBody JsonNode body, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        String role = body.has("role") ? body.get("role").asText("") : null;
        if (role != null && !Set.of("user", "admin").contains(role)) throw ApiException.validation("角色只能是 user 或 admin");
        int n = jdbc.sql("""
                update platform_login_allowlist set enabled = coalesce(?, enabled),
                    note = coalesce(?, note), real_name = coalesce(?, real_name),
                    role = coalesce(?, role), updated_at = now()
                where login_name = ?
                """).params(body.has("enabled") ? body.get("enabled").asBoolean() : null,
                body.has("note") ? body.get("note").asText() : null,
                body.has("realName") ? body.get("realName").asText() : null, role, loginName).update();
        if (n == 0) throw ApiException.notFound("人员");
        // 角色改动对已登录用户立即生效（app_user.role 是管理员的判定来源之一）
        if (role != null) jdbc.sql("update app_user set role = ? where login_name = ?").params(role, loginName).update();
        return Map.of("ok", true);
    }

    @DeleteMapping("/{loginName}")
    public Map<String, Object> delete(@PathVariable String loginName, HttpServletRequest req) {
        admin.requireActor(req, principal.workspaceId());
        int n = jdbc.sql("delete from platform_login_allowlist where login_name = ?").param(loginName).update();
        if (n == 0) throw ApiException.notFound("人员");
        return Map.of("ok", true);
    }
}
