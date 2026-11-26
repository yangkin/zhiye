package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.LogRedactor;
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.config.ZhiyeProperties props;

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

    /** 检查当前平台数据库账号是否具备创建应用独立隔离角色（CREATEROLE / SUPERUSER）的权限。 */
    public boolean canCreateRole() {
        try {
            Boolean ok = jdbc.sql("select coalesce(rolsuper or rolcreaterole, false) from pg_roles where rolname = current_user")
                    .query(Boolean.class).optional().orElse(false);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.debug("检测 PG CREATEROLE 权限失败: {}", sanitizeDbError(e, null));
            return false;
        }
    }

    /**
     * 剥离 Spring JdbcClient 异常里带有的原始 SQL（防止 CREATE/ALTER ROLE 中的明文密码泄露到日志与接口响应），
     * 优先返回 PostgreSQL 服务端可读错误摘要并做脱敏。
     */
    public static String sanitizeDbError(Throwable e, String secretToMask) {
        String msg = null;
        Throwable cur = e;
        int depth = 0;
        // 优先取底层 JDBC SQLException（如 PostgreSQL PSQLException），其消息仅含数据库错误原因，不含 Spring 拼接的原始 SQL
        while (cur != null && depth++ < 12) {
            if (cur instanceof java.sql.SQLException se && se.getMessage() != null && !se.getMessage().isBlank()) {
                msg = se.getMessage();
                break;
            }
            cur = cur.getCause();
        }
        if (msg == null) {
            msg = e == null || e.getMessage() == null ? "数据库操作失败" : e.getMessage();
        }
        // 剥离任何可能残留的 [create/alter role ...] 原始 SQL 片段
        msg = msg.replaceAll("(?is)\\[\\s*(?:create|alter)\\s+role\\b[^\\]]*]", "[REDACTED SQL]");
        msg = LogRedactor.redact(msg);
        if (secretToMask != null && !secretToMask.isEmpty()) {
            msg = msg.replace(secretToMask, "***");
        }
        return msg;
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
                // 仅授予本应用专属 Schema 读写与建表权限，显式回收 public Schema 权限并隔离 search_path，阻止跨应用与平台库越权访问
                try {
                    jdbc.sql("revoke all on schema public from " + candidateRole).update();
                } catch (Exception ignored) {}
                jdbc.sql("grant usage, create on schema " + schema + " to " + candidateRole).update();
                jdbc.sql("alter default privileges in schema " + schema + " grant all on tables to " + candidateRole).update();
                jdbc.sql("alter default privileges in schema " + schema + " grant all on sequences to " + candidateRole).update();
                jdbc.sql("alter role " + candidateRole + " set search_path to " + schema).update();
                hardenRole(candidateRole);
                role = candidateRole;
                passPlain = candidatePass;
                passEnc = crypto.encrypt(candidatePass);
            } catch (Exception e) {
                String safeErr = sanitizeDbError(e, candidatePass);
                log.error("创建独立 PG 隔离角色失败，拒绝回退平台凭据 app={} schema={}: {}", appId, schema, safeErr);
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DB_PROVISION_FAILED",
                        "托管数据库隔离账号创建失败，出于安全要求已拒绝启用（严禁回退平台主库凭据；请确认数据库账号具备 CREATEROLE 权限）：" + safeErr);
            }
        } else {
            if (role.equals(datasourceUser)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DB_PROVISION_FAILED",
                        "检测到历史非隔离数据库凭据，出于安全隔离要求已拒绝注入，请重新初始化托管数据库角色");
            }
            hardenRole(role);
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

    /**
     * 为单体应用独立 PG 角色施加防滥用硬参数：连接数上限、语句执行超时、空闲事务超时及临时文件上限（temp_file_limit 需 superuser，非 superuser 自动跳过）。
     */
    public void hardenRole(String role) {
        if (role == null || role.equals(datasourceUser) || !SAFE_IDENT.matcher(role).matches()) return;
        var q = props != null ? props.databaseQuota() : null;
        int connLimit = q != null && q.serviceRoleConnectionLimit() > 0 ? q.serviceRoleConnectionLimit() : 10;
        int stmtTimeoutMs = q != null && q.serviceStatementTimeoutMs() > 0 ? q.serviceStatementTimeoutMs() : 15_000;
        int idleTxMs = q != null && q.serviceIdleTxTimeoutMs() > 0 ? q.serviceIdleTxTimeoutMs() : 30_000;
        int tempFileMb = q != null && q.serviceTempFileLimitMb() > 0 ? q.serviceTempFileLimitMb() : 64;
        try {
            jdbc.sql("alter role " + role + " connection limit " + connLimit).update();
        } catch (Exception e) {
            log.debug("设置角色连接数上限跳过 role={}: {}", role, sanitizeDbError(e, null));
        }
        try {
            jdbc.sql("alter role " + role + " set statement_timeout = '" + stmtTimeoutMs + "ms'").update();
            jdbc.sql("alter role " + role + " set idle_in_transaction_session_timeout = '" + idleTxMs + "ms'").update();
        } catch (Exception e) {
            log.debug("设置角色超时参数跳过 role={}: {}", role, sanitizeDbError(e, null));
        }
        try {
            jdbc.sql("alter role " + role + " set temp_file_limit = '" + tempFileMb + "MB'").update();
        } catch (Exception e) {
            // temp_file_limit 在 PostgreSQL 中属于 SUSET 参数，仅 CREATEROLE 账号可能无权设置，静默降级不阻断启动
            log.debug("设置角色 temp_file_limit 跳过（非 superuser 属预期） role={}: {}", role, sanitizeDbError(e, null));
        }
    }

    /**
     * 单体托管数据库超限自动熔断 / 管理员一键限写与解封：
     * - block=true：回收 CREATE / INSERT / UPDATE（含默认权限，并将已有表 owner 收归平台账号以防止应用自授写权），
     *   但显式保留 USAGE + SELECT + DELETE + TRUNCATE，便于清理数据自救；
     * - block=false：完整恢复应用专属 Schema 的读写建表权限。
     */
    public void enforceWriteBlock(String schema, String role, boolean block) {
        if (schema == null || !SAFE_IDENT.matcher(schema).matches()) return;
        if (role == null || role.equals(datasourceUser) || !SAFE_IDENT.matcher(role).matches()) return;
        try {
            if (block) {
                jdbc.sql("revoke create on schema " + schema + " from " + role).update();
                jdbc.sql("grant usage on schema " + schema + " to " + role).update();
                // 收回表 owner（若权限允许），防止应用以 owner 身份重新 grant 自己写权限
                try {
                    var tbls = jdbc.sql("""
                            select c.relname from pg_class c
                              join pg_namespace n on n.oid = c.relnamespace
                             where n.nspname = ? and c.relkind in ('r', 'p')
                            """).param(schema).query(String.class).list();
                    for (String tbl : tbls) {
                        String q = "\"" + schema + "\".\"" + tbl.replace("\"", "\"\"") + "\"";
                        jdbc.sql("alter table " + q + " owner to current_user").update();
                    }
                } catch (Exception ignored) {}
                jdbc.sql("revoke all on all tables in schema " + schema + " from " + role).update();
                jdbc.sql("grant select, delete, truncate on all tables in schema " + schema + " to " + role).update();
                jdbc.sql("alter default privileges in schema " + schema + " revoke insert, update on tables from " + role).update();
            } else {
                jdbc.sql("grant usage, create on schema " + schema + " to " + role).update();
                jdbc.sql("grant all on all tables in schema " + schema + " to " + role).update();
                jdbc.sql("grant all on all sequences in schema " + schema + " to " + role).update();
                jdbc.sql("alter default privileges in schema " + schema + " grant all on tables to " + role).update();
                jdbc.sql("alter default privileges in schema " + schema + " grant all on sequences to " + role).update();
            }
        } catch (Exception e) {
            log.warn("切换托管数据库限写状态失败 schema={} role={} block={}: {}", schema, role, block, sanitizeDbError(e, null));
        }
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
            "(?i)\\b(drop\\s+schema|create\\s+schema|alter\\s+schema|alter\\s+role|create\\s+role|drop\\s+role|set\\s+role|reset\\s+role|set\\s+session|set\\s+local|set\\s+search_path|reset\\s+search_path|grant\\b|revoke\\b|copy\\b|pg_read_file|pg_read_binary_file|pg_ls_dir|pg_stat_file|pg_sleep|pg_terminate_backend|pg_cancel_backend|pg_shadow|pg_authid|pg_stat_activity|lo_import|lo_export|lo_unlink|dblink)\\b|(?i)\\bpg_catalog\\s*\\.");

    /** 剥离 SQL 行注释 (-- ...) 与块注释 (/* ... *\/)，保留单引号字符串字面量，防止注释绕过只读/危险词检查。 */
    static String stripSqlComments(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder(raw.length());
        int n = raw.length();
        boolean inSingle = false;
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (inSingle) {
                out.append(c);
                if (c == '\'') {
                    if (i + 1 < n && raw.charAt(i + 1) == '\'') {
                        out.append(raw.charAt(++i));
                    } else {
                        inSingle = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                out.append(c);
            } else if (c == '-' && i + 1 < n && raw.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && raw.charAt(i) != '\n') i++;
                out.append(' ');
            } else if (c == '/' && i + 1 < n && raw.charAt(i + 1) == '*') {
                i += 2;
                int depth = 1;
                while (i < n && depth > 0) {
                    if (raw.charAt(i) == '/' && i + 1 < n && raw.charAt(i + 1) == '*') {
                        depth++;
                        i += 2;
                    } else if (raw.charAt(i) == '*' && i + 1 < n && raw.charAt(i + 1) == '/') {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                i--;
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    /** 只读表数据安全预览（供应用创建者免写 SQL 查看表数据，校验表名归属当前 Schema，上限 50 行、5s 超时）。 */
    public java.util.Map<String, Object> previewTable(String schema, String role, String passwordEnc,
                                                      String tableName, int maxRows) {
        if (schema == null || !SAFE_IDENT.matcher(schema).matches()) {
            throw ApiException.validation("该应用尚未初始化托管 PostgreSQL Schema");
        }
        if (tableName == null || tableName.isBlank() || tableName.length() > 128) {
            throw ApiException.validation("表名不能为空");
        }
        boolean exists = jdbc.sql("""
                select exists(
                  select 1 from information_schema.tables
                   where table_schema = ? and table_name = ? and table_type in ('BASE TABLE', 'VIEW')
                )
                """).params(schema, tableName).query(Boolean.class).single();
        if (!exists) {
            throw ApiException.notFound("数据表");
        }
        int limit = Math.max(1, Math.min(maxRows <= 0 ? 50 : maxRows, 100));
        String quotedSchema = "\"" + schema.replace("\"", "\"\"") + "\"";
        String quotedTable = "\"" + tableName.replace("\"", "\"\"") + "\"";
        String sql = "SELECT * FROM " + quotedSchema + "." + quotedTable + " LIMIT " + limit;
        return executeScopedSql(schema, role, passwordEnc, sql, false, limit);
    }

    /**
     * 在应用专属最小权限 PG 角色与隔离 Schema 上执行受控 SQL（内置 5s 超时 + 单语句防护 + 注释剥离 + 权限内核级隔离）。
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
        String cleaned = stripSqlComments(sql);
        while (cleaned.endsWith(";")) cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        if (cleaned.isEmpty()) throw ApiException.validation("SQL 语句不能为空");
        if (cleaned.contains(";")) throw ApiException.validation("为保障安全，每次仅允许执行单条 SQL 语句");
        if (DANGEROUS_SQL_RE.matcher(cleaned).find()) {
            throw ApiException.validation("安全拦截：禁止执行跨 Schema、角色提权、系统目录探测或文件读写语句");
        }
        if (!allowWrite) {
            String upper = cleaned.toUpperCase(java.util.Locale.ROOT);
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
            throw ApiException.validation("SQL 执行失败：" + sanitizeDbError(e, passPlain));
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
