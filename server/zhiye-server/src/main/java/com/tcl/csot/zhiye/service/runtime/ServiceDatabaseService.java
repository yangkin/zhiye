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
