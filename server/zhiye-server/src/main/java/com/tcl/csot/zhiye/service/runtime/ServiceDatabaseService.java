package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * 为单体后端应用自动在共享 PostgreSQL 中分配隔离 Schema，并生成标准 DATABASE_URL（Python/Node/Go）
 * 与 JDBC_DATABASE_URL（Java Spring Boot）。
 */
@Service
public class ServiceDatabaseService {
    private static final Logger log = LoggerFactory.getLogger(ServiceDatabaseService.class);
    private static final Pattern SAFE_IDENT = Pattern.compile("^[a-z][a-z0-9_]{2,48}$");
    private static final SecureRandom RNG = new SecureRandom();

    public record ProvisionedDb(String schema, String role, String passwordEnc, String databaseUrl, String jdbcUrl) {}

    private final JdbcClient jdbc;
    private final ServiceCrypto crypto;
    private final String datasourceUrl;
    private final String datasourceUser;
    private final String datasourcePassword;

    public ServiceDatabaseService(JdbcClient jdbc,
                                  ServiceCrypto crypto,
                                  @Value("${spring.datasource.url:}") String datasourceUrl,
                                  @Value("${spring.datasource.username:zhiye}") String datasourceUser,
                                  @Value("${spring.datasource.password:}") String datasourcePassword) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.datasourceUrl = datasourceUrl == null ? "" : datasourceUrl;
        this.datasourceUser = datasourceUser == null ? "zhiye" : datasourceUser;
        this.datasourcePassword = datasourcePassword == null ? "" : datasourcePassword;
    }

    public static String schemaNameFor(String appId) {
        String clean = appId.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (clean.length() > 24) clean = clean.substring(clean.length() - 24);
        return "zhiye_svc_" + clean;
    }

    public ProvisionedDb ensureSchema(String appId, String existingSchema, String existingRole, String existingPasswordEnc) {
        String schema = (existingSchema != null && SAFE_IDENT.matcher(existingSchema).matches())
                ? existingSchema : schemaNameFor(appId);
        if (!SAFE_IDENT.matcher(schema).matches()) throw new IllegalArgumentException("非法 schema 名：" + schema);

        jdbc.sql("create schema if not exists " + schema).update();

        String role = existingRole;
        String passPlain;
        String passEnc = existingPasswordEnc;

        if (role == null || role.isBlank() || passEnc == null || passEnc.isBlank()) {
            String candidateRole = schema + "_u";
            byte[] rnd = new byte[16];
            RNG.nextBytes(rnd);
            String candidatePass = HexFormat.of().formatHex(rnd);
            // 严格安全隔离（Fail-Closed）：必须为每个应用签发独立最小权限 PG 角色，严禁降级回退到平台主数据库账号
            try {
                boolean exists = jdbc.sql("select exists(select 1 from pg_roles where rolname = ?)")
                        .param(candidateRole).query(Boolean.class).single();
                if (!exists) {
                    jdbc.sql("create role " + candidateRole + " login password '" + candidatePass
                            + "' nosuperuser nocreatedb nocreaterole noinherit").update();
                } else {
                    jdbc.sql("alter role " + candidateRole + " with login password '" + candidatePass
                            + "' nosuperuser nocreatedb nocreaterole noinherit").update();
                }
                // 仅授予本应用专属 Schema 读写与建表权限，并隔离 search_path，阻止跨应用与平台库越权访问
                jdbc.sql("grant usage, create on schema " + schema + " to " + candidateRole).update();
                jdbc.sql("alter default privileges in schema " + schema + " grant all on tables to " + candidateRole).update();
                jdbc.sql("alter default privileges in schema " + schema + " grant all on sequences to " + candidateRole).update();
                jdbc.sql("alter role " + candidateRole + " set search_path to " + schema).update();
                role = candidateRole;
                passPlain = candidatePass;
                passEnc = crypto.encrypt(candidatePass);
            } catch (Exception e) {
                log.error("创建独立 PG 隔离角色失败，拒绝回退平台凭据 app={} schema={}: {}", appId, schema, e.getMessage());
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DB_PROVISION_FAILED",
                        "托管数据库隔离账号创建失败，出于安全要求已拒绝启用（严禁回退平台主库凭据；请确认数据库账号具备 CREATEROLE 权限）：" + e.getMessage());
            }
        } else {
            if (role.equals(datasourceUser)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DB_PROVISION_FAILED",
                        "检测到历史非隔离数据库凭据，出于安全隔离要求已拒绝注入，请重新初始化托管数据库角色");
            }
            passPlain = crypto.decrypt(passEnc);
        }

        String[] conn = parseHostPortDb(datasourceUrl);
        String encUser = URLEncoder.encode(role, StandardCharsets.UTF_8);
        String encPass = URLEncoder.encode(passPlain, StandardCharsets.UTF_8);
        String auth = passPlain.isEmpty() ? encUser : encUser + ":" + encPass;
        String dbUrl = "postgresql://" + auth + "@" + conn[0] + ":" + conn[1] + "/" + conn[2] + "?options=-csearch_path%3D" + schema;
        String jdbcUrl = "jdbc:postgresql://" + conn[0] + ":" + conn[1] + "/" + conn[2] + "?currentSchema=" + schema;
        return new ProvisionedDb(schema, role, passEnc, dbUrl, jdbcUrl);
    }

    /** 列出应用隔离 PostgreSQL Schema 下的全部表、列定义及近似行数（只读元数据）。 */
    public java.util.List<java.util.Map<String, Object>> listTables(String schema) {
        if (schema == null || !SAFE_IDENT.matcher(schema).matches()) {
            return java.util.List.of();
        }
        java.util.Map<String, java.util.List<java.util.Map<String, Object>>> colsByTable = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                select table_name, column_name, data_type, is_nullable, column_default
                  from information_schema.columns
                 where table_schema = ?
                 order by table_name, ordinal_position
                """).param(schema).query(rs -> {
            String tbl = rs.getString("table_name");
            java.util.Map<String, Object> col = new java.util.LinkedHashMap<>();
            col.put("name", rs.getString("column_name"));
            col.put("type", rs.getString("data_type"));
            col.put("nullable", "YES".equalsIgnoreCase(rs.getString("is_nullable")));
            col.put("defaultValue", rs.getString("column_default"));
            colsByTable.computeIfAbsent(tbl, k -> new java.util.ArrayList<>()).add(col);
        });

        java.util.Map<String, Long> rowEstimates = new java.util.LinkedHashMap<>();
        try {
            jdbc.sql("""
                    select c.relname as table_name, greatest(0, c.reltuples::bigint) as est_rows
                      from pg_class c
                      join pg_namespace n on n.oid = c.relnamespace
                     where n.nspname = ? and c.relkind in ('r', 'p')
                    """).param(schema).query(rs -> {
                rowEstimates.put(rs.getString("table_name"), rs.getLong("est_rows"));
            });
        } catch (Exception ignored) {}

        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (var e : colsByTable.entrySet()) {
            java.util.Map<String, Object> t = new java.util.LinkedHashMap<>();
            t.put("name", e.getKey());
            t.put("estimatedRows", rowEstimates.getOrDefault(e.getKey(), 0L));
            t.put("columns", e.getValue());
            out.add(t);
        }
        return out;
    }

    private static final Pattern DANGEROUS_SQL_RE = Pattern.compile(
            "(?i)\\b(drop\\s+schema|create\\s+schema|alter\\s+role|create\\s+role|drop\\s+role|set\\s+role|reset\\s+role|set\\s+session|grant\\b|revoke\\b|copy\\b|pg_read_file|pg_ls_dir|lo_import|lo_export)\\b");

    /**
     * 在应用专属最小权限 PG 角色与隔离 Schema 上执行受控 SQL（内置 5s 超时 + 单语句防护 + 权限内核级隔离）。
     */
    public java.util.Map<String, Object> executeScopedSql(String schema, String role, String passwordEnc,
                                                          String rawSql, boolean allowWrite, int maxRows) {
        if (schema == null || !SAFE_IDENT.matcher(schema).matches()) {
            throw ApiException.validation("该应用尚未初始化托管 PostgreSQL Schema");
        }
        if (role == null || role.isBlank() || role.equals(datasourceUser) || passwordEnc == null || passwordEnc.isBlank()) {
            throw ApiException.validation("托管数据库隔离凭据未就绪，请先保存开启「托管 PostgreSQL Schema」");
        }
        String sql = rawSql == null ? "" : rawSql.trim();
        while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        if (sql.isEmpty()) throw ApiException.validation("SQL 语句不能为空");
        if (sql.length() > 8000) throw ApiException.validation("单次 SQL 长度不能超过 8000 字符");
        if (sql.contains(";")) throw ApiException.validation("为保障安全，每次仅允许执行单条 SQL 语句");
        if (DANGEROUS_SQL_RE.matcher(sql).find()) {
            throw ApiException.validation("安全拦截：禁止执行跨 Schema、角色提权或文件读写语句");
        }
        if (!allowWrite) {
            String upper = sql.toUpperCase(java.util.Locale.ROOT);
            if (!(upper.startsWith("SELECT") || upper.startsWith("WITH") || upper.startsWith("EXPLAIN") || upper.startsWith("SHOW"))) {
                throw ApiException.validation("当前为只读查询模式，仅允许 SELECT / WITH / EXPLAIN；如需写入请勾选「允许写入」");
            }
        }

        int limit = Math.max(1, Math.min(maxRows <= 0 ? 100 : maxRows, 500));
        String passPlain = crypto.decrypt(passwordEnc);
        String[] connInfo = parseHostPortDb(datasourceUrl);
        String jdbcUrl = "jdbc:postgresql://" + connInfo[0] + ":" + connInfo[1] + "/" + connInfo[2]
                + "?currentSchema=" + schema + "&options=-cstatement_timeout%3D5000";

        long t0 = System.currentTimeMillis();
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(jdbcUrl, role, passPlain)) {
            conn.setReadOnly(!allowWrite);
            try (java.sql.Statement st = conn.createStatement()) {
                st.setQueryTimeout(5);
                st.setMaxRows(limit);
                boolean hasRs = st.execute(sql);
                long elapsedMs = System.currentTimeMillis() - t0;
                java.util.Map<String, Object> res = new java.util.LinkedHashMap<>();
                res.put("schema", schema);
                res.put("durationMs", elapsedMs);
                res.put("readOnly", !allowWrite);
                if (hasRs) {
                    try (java.sql.ResultSet rs = st.getResultSet()) {
                        var md = rs.getMetaData();
                        int colCount = md.getColumnCount();
                        java.util.List<String> columns = new java.util.ArrayList<>(colCount);
                        for (int i = 1; i <= colCount; i++) columns.add(md.getColumnLabel(i));
                        java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
                        while (rs.next() && rows.size() < limit) {
                            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                            for (int i = 1; i <= colCount; i++) {
                                Object v = rs.getObject(i);
                                row.put(columns.get(i - 1), v == null ? null : (v instanceof Number || v instanceof Boolean ? v : String.valueOf(v)));
                            }
                            rows.add(row);
                        }
                        res.put("kind", "query");
                        res.put("columns", columns);
                        res.put("rows", rows);
                        res.put("rowCount", rows.size());
                    }
                } else {
                    res.put("kind", "update");
                    res.put("affectedRows", st.getUpdateCount());
                    res.put("columns", java.util.List.of());
                    res.put("rows", java.util.List.of());
                }
                return res;
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.validation("SQL 执行失败：" + e.getMessage());
        }
    }

    public void dropSchemaIfExists(String schema) {
        dropSchemaAndRole(schema, null);
    }

    public void dropSchemaAndRole(String schema, String role) {
        if (schema != null && SAFE_IDENT.matcher(schema).matches()) {
            try {
                jdbc.sql("drop schema if exists " + schema + " cascade").update();
            } catch (Exception e) {
                log.warn("清理单体服务 Schema 失败 schema={}: {}", schema, e.getMessage());
            }
        }
        if (role != null && !role.equals(datasourceUser) && SAFE_IDENT.matcher(role).matches()) {
            try {
                jdbc.sql("drop owned by " + role + " cascade").update();
                jdbc.sql("drop role if exists " + role).update();
            } catch (Exception e) {
                log.warn("清理单体服务独立角色失败 role={}: {}", role, e.getMessage());
            }
        }
    }

    static String[] parseHostPortDb(String jdbcUrl) {
        try {
            String stripped = jdbcUrl.replaceFirst("^jdbc:", "");
            URI u = URI.create(stripped);
            String host = u.getHost() != null ? u.getHost() : "127.0.0.1";
            String port = u.getPort() > 0 ? String.valueOf(u.getPort()) : "5432";
            String db = u.getPath() != null ? u.getPath().replaceFirst("^/+", "") : "zhiye";
            if (db.isEmpty()) db = "zhiye";
            return new String[]{host, port, db};
        } catch (Exception e) {
            return new String[]{"127.0.0.1", "5432", "zhiye"};
        }
    }
}
