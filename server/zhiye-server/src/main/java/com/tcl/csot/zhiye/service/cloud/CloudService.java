package com.tcl.csot.zhiye.service.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.storage.ObjectStore;
import com.tcl.csot.zhiye.repository.VersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 云服务：每个应用一份独立的 Postgres schema 和一份对象存储前缀，应用令牌只代表这一个应用。
 * v1 只支持「预览里用」——令牌注入到预览页面；发布的站点暂不注入（见 docs/cloud-services.md）。
 */
@Service
public class CloudService {
    private static final Logger log = LoggerFactory.getLogger(CloudService.class);
    private static final String DEV_SECRET = "zhiye-cloud-dev-secret";
    static final int MAX_TABLES = com.tcl.csot.zhiye.constants.Capabilities.MAX_TABLES;
    static final int MAX_COLUMNS = 40;
    static final int MAX_ROWS = 200;
    private static final Set<String> TYPES = Set.of("text", "int", "integer", "bigint", "real", "double precision", "numeric", "boolean", "date", "timestamp", "timestamptz", "jsonb");
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final JdbcClient jdbc;
    private final ObjectStore store;
    private final AppRepository apps;
    private final VersionRepository versions;
    private final GitService git;
    /** 令牌签名密钥，启动时确定；生产环境不允许回落到开发密钥 */
    private final String secretKey;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.ops.PlatformLogService platformLogs;

    public CloudService(JdbcClient jdbc, ObjectStore store, AppRepository apps, VersionRepository versions, GitService git) {
        this.jdbc = jdbc;
        this.store = store;
        this.apps = apps;
        this.versions = versions;
        this.git = git;
        this.secretKey = resolveSecret();
    }

    /** ZHIYE_CLOUD_SECRET 为空时：prod/k8s 直接启动失败，其他环境用开发密钥并告警 */
    private static String resolveSecret() {
        String env = System.getenv("ZHIYE_CLOUD_SECRET");
        if (env != null && !env.isBlank()) return env;
        String profiles = System.getenv().getOrDefault("SPRING_PROFILES_ACTIVE", "");
        if (profiles.contains("prod") || profiles.contains("k8s"))
            throw new IllegalStateException("生产环境必须设置 ZHIYE_CLOUD_SECRET（云服务应用令牌的签名密钥）");
        log.warn("ZHIYE_CLOUD_SECRET 未设置，使用内置开发密钥；生产环境必须配置");
        return DEV_SECRET;
    }

    public record Cloud(String appId, String status, String schema, String storagePrefix, Instant0 enabledAt) {}
    /** 时间用 String 避免引入类型 */
    public record Instant0(String at) {}

    // ---------- 控制面 ----------

    public Map<String, Object> status(String ws, String appId) {
        require(ws, appId);
        var c = find(appId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", c != null && "on".equals(c.status));
        out.put("status", c == null ? "off" : c.status);
        out.put("schema", c == null ? null : c.schema);
        out.put("tables", c == null ? List.of() : tables(appId));
        out.put("storagePrefix", c == null ? null : c.storagePrefix);
        return out;
    }

    public List<Map<String, Object>> tables(String appId) {
        Map<String, String> desc = descriptions(readCurrent(appId, "cloud/schema.sql"));
        return jdbc.sql("select name, columns, policy from app_cloud_table where app_id = ? order by name")
                .params(appId).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("name", rs.getString(1));
                    if (desc.containsKey(rs.getString(1))) m.put("description", desc.get(rs.getString(1)));
                    m.put("columns", Json.read(rs.getString(2)));
                    m.put("policy", Json.read(rs.getString(3)));
                    return m;
                }).list();
    }

    /** 启用：建 schema、按 cloud/schema.sql 建表，返回一次性的应用令牌 */
    public synchronized Map<String, Object> enable(String ws, String appId) {
        require(ws, appId);
        // 即使已启用也重新应用 cloud/schema.sql：agent 新增表/列后，用户重新启用一次即可生效
        String schema = "app_" + appId.replaceAll("[^a-zA-Z0-9]", "_");
        String prefix = "cloud/" + appId + "/";
        String token = mint(appId, 24 * 3600);

        long t0 = System.currentTimeMillis();
        List<String> ddlLog = new ArrayList<>();
        try {
            String createSchemaSql = "create schema if not exists \"" + schema + "\"";
            jdbc.sql(createSchemaSql).update();
            ddlLog.add(createSchemaSql + ";");
            Schema sc = readSchema(appId);
            for (TableDef t : sc.tables()) createTable(appId, schema, t, sc.policies().get(t.name()), ddlLog);
            dropPlaceholder(appId, schema, sc, ddlLog);
            List<TableDef> defs = sc.tables();

        jdbc.sql("""
                insert into app_cloud (app_id, status, schema_name, storage_prefix, token_hash, enabled_at)
                values (?, 'on', ?, ?, ?, now())
                on conflict (app_id) do update set status='on', schema_name=excluded.schema_name,
                    storage_prefix=excluded.storage_prefix, token_hash=excluded.token_hash, enabled_at=now(), updated_at=now()
                """).params(appId, schema, prefix, "").update();
        log.info("云服务启用：app={} schema={} tables={}", appId, schema, defs.size());
        Map<String, Object> out = status(ws, appId);
        out.put("token", token); // 明文只在启用时返回一次；之后通过 /token 取（同工作空间）
        return out;
    }

    /** 任务成功后：已启用云服务的应用按新版本的 cloud/schema.sql 补建表/列；未启用返回 -1 */
    public synchronized int applySchema(String appId) {
        Cloud c = find(appId).orElse(null);
        if (c == null || !"on".equals(c.status)) return -1;
        Schema sc = readSchema(appId);
        for (TableDef t : sc.tables()) createTable(appId, c.schema, t, sc.policies().get(t.name()));
        dropPlaceholder(appId, c.schema, sc);
        return sc.tables().size();
    }

    /**
     * 先启用、后有 schema.sql 时，启用那会儿建的默认 notes 表会留在控制台里和真正的表并列。
     * 有了正式的表结构、里面没声明 notes、notes 还是默认的两列且没有数据，就把它去掉；有数据一律保留。
     */
    private void dropPlaceholder(String appId, String schema, Schema sc, List<String> ddlLog) {
        if (sc.placeholder() || sc.tables().stream().anyMatch(t -> t.name().equals("notes"))) return;
        String cols = jdbc.sql("select columns from app_cloud_table where app_id = ? and name = 'notes'").param(appId)
                .query(String.class).optional().orElse(null);
        if (cols == null) return;
        var userCols = new java.util.TreeSet<String>();
        Json.read(cols).fieldNames().forEachRemaining(userCols::add);
        userCols.removeAll(SYSTEM_COLUMNS);
        if (!userCols.equals(new java.util.TreeSet<>(List.of("content", "title")))) return;
        String q = "\"" + schema + "\".\"notes\"";
        Long rows = jdbc.sql("select count(*) from " + q).query(Long.class).single();
        if (rows > 0) return;
        String dropSql = "drop table " + q;
        jdbc.sql(dropSql).update();
        ddlLog.add(dropSql + ";");
        jdbc.sql("delete from app_cloud_table where app_id = ? and name = 'notes'").param(appId).update();
        log.info("去掉默认的空 notes 表：app={}", appId);
    }

    /** 校验 cloud/schema.sql 能不能被平台接受；返回错误说明，没问题返回 null */
    public static String checkSchema(String sql) {
        return checkSchema(sql, null);
    }

    /** 同时校验 cloud/policies.json（可以没有） */
    public static String checkSchema(String sql, String policiesJson) {
        try {
            List<TableDef> defs = parse(sql);
            if (defs.isEmpty()) return "cloud/schema.sql 里没有可用的 create table 语句";
            CloudPolicy.parse(policiesJson, columnsByTable(defs));
            return null;
        } catch (ApiException e) {
            return e.getMessage();
        }
    }

    private static Map<String, LinkedHashMap<String, String>> columnsByTable(List<TableDef> defs) {
        Map<String, LinkedHashMap<String, String>> m = new LinkedHashMap<>();
        for (TableDef d : defs) m.put(d.name(), d.columns());
        return m;
    }

    /** 控制台：应用主人取已启用的云服务 */
    public Cloud forOwner(String ws, String appId) {
        require(ws, appId);
        return requireOn(find(appId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "CLOUD_DISABLED", "这个应用的云服务没有启用")));
    }

    /** 控制台概览：每张表的行数 */
    public Map<String, Long> rowCounts(Cloud c) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (var t : tables(c.appId())) {
            String name = (String) t.get("name");
            try { out.put(name, jdbc.sql("select count(*) from " + qt(c, name)).query(Long.class).single()); }
            catch (org.springframework.dao.DataAccessException e) { out.put(name, 0L); }
        }
        return out;
    }

    /** 控制台导出：UTF-8 带 BOM 的 CSV（Excel 直接打开不乱码），最多 10 万行 */
    public byte[] exportCsv(Cloud c, String table) {
        var t = table(c.appId(), table);
        List<String> cols = new ArrayList<>(columns(t));
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(String.join(",", cols.stream().map(CloudService::csv).toList())).append("\r\n");
        jdbc.sql("select * from " + qt(c, table) + " order by id limit 100000").query((rs, i) -> rowToMap(rs)).list().forEach(r -> {
            List<String> cells = new ArrayList<>();
            for (String col : cols) {
                Object v = r.get(col);
                cells.add(csv(v == null ? "" : v instanceof JsonNode n ? (n.isTextual() ? n.asText() : n.toString()) : String.valueOf(v)));
            }
            sb.append(String.join(",", cells)).append("\r\n");
        });
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csv(String s) {
        // 以 = + - @ 开头的单元格在 Excel 里会被当成公式，前面加 ' 防注入
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    public boolean isOn(String appId) {
        return find(appId).map(c -> "on".equals(c.status)).orElse(false);
    }

    public void disable(String ws, String appId) {
        require(ws, appId);
        jdbc.sql("update app_cloud set status = 'off', updated_at = now() where app_id = ?").params(appId).update();
        if (platformLogs != null) {
            platformLogs.info("cloud_schema", "CLOUD_DISABLE", ws, null, appId, null,
                    "关闭应用云服务（保留既有 Schema 与表数据）", null, Map.of());
        }
    }

    /** 工作空间内的令牌：预览页注入用 */
    public String token(String ws, String appId) {
        require(ws, appId);
        throw new ApiException(HttpStatus.NOT_FOUND, "TOKEN_NOT_AVAILABLE",
                "应用令牌只在启用时返回一次；预览页会自动带上，不需要手动复制");
    }

    private AppRow require(String ws, String appId) {
        return apps.findById(appId).filter(a -> a.workspaceId().equals(ws) && a.deletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("应用"));
    }

    /** 是否已启用云服务（问答工具用，不校验工作空间——调用方已按会话归属校验过） */
    public boolean enabled(String appId) {
        return find(appId).map(c -> "on".equals(c.status)).orElse(false);
    }

    /**
     * 工作台问答的只读数据面（按创作者/admin 身份读）：
     * table 为空 → 每张表的名字、说明、字段、行数；指定表 → 最新 limit 行（≤50）。
     */
    public String askData(String appId, String table, int limit) {
        Cloud c = find(appId).orElse(null);
        if (c == null || !"on".equals(c.status)) return "这个应用没有启用云服务";
        if (table == null || table.isBlank()) {
            Map<String, Long> counts = rowCounts(c);
            StringBuilder sb = new StringBuilder("共 ").append(counts.size()).append(" 张表：\n");
            for (var t : tables(appId)) {
                sb.append("- ").append(t.get("name"));
                if (t.get("description") != null) sb.append("（").append(t.get("description")).append("）");
                sb.append("：").append(counts.getOrDefault(String.valueOf(t.get("name")), 0L)).append(" 行；字段 ")
                        .append(t.get("columns")).append('\n');
            }
            return sb.toString();
        }
        try {
            var res = list(c, table, Map.of("limit", String.valueOf(Math.max(1, Math.min(limit, 50)))),
                    new CloudPolicy.Who(null, null, "创作者", true));
            String rows = Json.write(res.get("rows"));
            if (rows.length() > 8000) rows = rows.substring(0, 8000) + "…（已截断）";
            return "表 " + table + "：共 " + res.get("total") + " 行，返回最新 " + Math.min(limit, 50) + " 行：\n" + rows;
        } catch (ApiException e) {
            return e.getMessage();
        }
    }

    private Optional<Cloud> find(String appId) {
        return jdbc.sql("select app_id, status, schema_name, storage_prefix, enabled_at from app_cloud where app_id = ?")
                .params(appId).query((rs, i) -> new Cloud(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        new Instant0(String.valueOf(rs.getTimestamp(5))))).optional();
    }

    // ---------- schema.sql ----------

    record TableDef(String name, LinkedHashMap<String, String> columns) {}

    /** placeholder：版本里还没有 cloud/schema.sql，用的是默认的 notes 表 */
    record Schema(List<TableDef> tables, Map<String, com.fasterxml.jackson.databind.node.ObjectNode> policies, boolean placeholder) {}

    /** 读当前版本的 cloud/schema.sql 和 cloud/policies.json；没有 schema 就只建一张通用的 notes 表，方便先试 */
    Schema readSchema(String appId) {
        String sql = readCurrent(appId, "cloud/schema.sql");
        if (sql.isBlank()) {
            LinkedHashMap<String, String> c = new LinkedHashMap<>();
            c.put("title", "text");
            c.put("content", "text");
            return new Schema(List.of(new TableDef("notes", c)), Map.of(), true);
        }
        List<TableDef> defs = parse(sql);
        return new Schema(defs, CloudPolicy.parse(readCurrent(appId, "cloud/policies.json"), columnsByTable(defs)), false);
    }

    private String readCurrent(String appId, String path) {
        var app = apps.findById(appId).orElseThrow();
        if (app.currentVersionId() == null) return "";
        var v = versions.find(app.currentVersionId()).orElse(null);
        if (v == null) return "";
        try {
            var bytes = git.readFile(appId, v.gitCommit(), path).orElse(null);
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw ApiException.validation("读取 " + path + " 失败：" + e.getMessage());
        }
    }

    private static final java.util.regex.Pattern CREATE_NAME = java.util.regex.Pattern.compile(
            "create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 每张表的说明：紧挨在 create table 上面的那行 `-- 注释`（控制台里显示成「描述」） */
    static Map<String, String> descriptions(String sql) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : sql.split(";")) {
            var m = CREATE_NAME.matcher(raw);
            if (!m.find()) continue;
            String before = raw.substring(0, m.start());
            String last = null;
            for (String line : before.split("\n")) {
                String l = line.trim();
                if (l.startsWith("--")) last = l.replaceFirst("^-+\\s*", "").trim();
                else if (!l.isEmpty()) last = null;
            }
            if (last != null && !last.isEmpty()) out.put(m.group(1).toLowerCase(), last.length() > 80 ? last.substring(0, 80) : last);
        }
        return out;
    }

    /** 只允许建表 / 加列 / 建索引；类型用白名单 */
    static List<TableDef> parse(String sql) {
        LinkedHashMap<String, TableDef> tables = new LinkedHashMap<>();
        for (String raw : sql.split(";")) {
            String stmt = raw.replaceAll("--[^\n]*", "").trim();
            if (stmt.isEmpty()) continue;
            var create = java.util.regex.Pattern.compile("create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)\\s*\\((.*)\\)", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(stmt);
            var alter = java.util.regex.Pattern.compile("alter\\s+table\\s+([a-z_][a-z0-9_]*)\\s+add\\s+column\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)\\s+(.+)", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(stmt);
            var index = java.util.regex.Pattern.compile("create\\s+(?:unique\\s+)?index\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)\\s+on\\s+([a-z_][a-z0-9_]*)\\s*\\([^)]*\\)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(stmt);
            if (create.matches()) {
                String name = create.group(1);
                checkName(name);
                if (tables.containsKey(name)) throw ApiException.validation("表重复定义：" + name);
                if (tables.size() >= MAX_TABLES) throw ApiException.validation("最多 " + MAX_TABLES + " 张表");
                LinkedHashMap<String, String> cols = new LinkedHashMap<>();
                for (String part : splitColumns(create.group(2))) {
                    String p = part.trim();
                    if (p.isEmpty()) continue;
                    if (TABLE_CONSTRAINT.matcher(p).lookingAt()) continue; // 表级约束（主键/唯一/外键/check）平台不建，跳过
                    String[] bits = p.split("\\s+", 2);
                    if (bits.length < 2) throw ApiException.validation("列定义不完整：" + p);
                    String col = bits[0].replace("\"", "").toLowerCase();
                    checkName(col);
                    if (col.equals("id") || col.equals("created_at") || col.equals("updated_at")) continue; // 系统自动加
                    String type = normalizeType(bits[1]);
                    if (type == null) throw ApiException.validation("不支持的字段类型：" + bits[1].split("\\s+")[0] + "（可用 text/int/bigint/real/numeric/boolean/date/timestamp/jsonb）");
                    cols.put(col, type);
                    if (cols.size() > MAX_COLUMNS) throw ApiException.validation("一张表最多 " + MAX_COLUMNS + " 列");
                }
                tables.put(name, new TableDef(name, cols));
            } else if (alter.matches()) {
                String name = alter.group(1);
                checkName(name);
                TableDef t = tables.get(name);
                if (t == null) throw ApiException.validation("alter table 之前要先 create table " + name);
                String type = normalizeType(alter.group(3));
                if (type == null) throw ApiException.validation("不支持的字段类型：" + alter.group(3).trim().split("\\s+")[0]);
                checkName(alter.group(2));
                t.columns().put(alter.group(2), type);
            } else if (index.matches()) {
                // 索引只校验表名，具体执行在 createTable 之后统一做
                continue;
            } else {
                throw ApiException.validation("cloud/schema.sql 只允许 create table / alter table add column / create index，发现不支持的语句：" + stmt.substring(0, Math.min(60, stmt.length())));
            }
        }
        if (tables.isEmpty()) throw ApiException.validation("cloud/schema.sql 里没有有效的建表语句");
        return new ArrayList<>(tables.values());
    }

    private static List<String> splitColumns(String body) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (char c : body.toCharArray()) {
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == ',' && depth == 0) { out.add(cur.toString()); cur.setLength(0); } else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static void checkName(String n) {
        if (!NAME.matcher(n).matches()) throw ApiException.validation("表名/列名只能是小写字母、数字、下划线，且以字母或下划线开头：" + n);
    }

    private static final java.util.regex.Pattern TABLE_CONSTRAINT = java.util.regex.Pattern.compile(
            "(?i)(primary\\s+key|unique\\s*\\(|foreign\\s+key|constraint\\s|check\\s*\\()");
    /** 模型常写的同义类型映射到白名单；列定义后面的 not null / default 等修饰平台不处理 */
    private static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
            Map.entry("varchar", "text"), Map.entry("character varying", "text"), Map.entry("char", "text"),
            Map.entry("character", "text"), Map.entry("uuid", "text"), Map.entry("smallint", "int"), Map.entry("int4", "int"),
            Map.entry("int8", "bigint"), Map.entry("serial", "int"), Map.entry("bigserial", "bigint"), Map.entry("float", "real"),
            Map.entry("float4", "real"), Map.entry("float8", "double precision"), Map.entry("decimal", "numeric"),
            Map.entry("bool", "boolean"), Map.entry("timestamptz", "timestamptz"), Map.entry("timestamp with time zone", "timestamptz"),
            Map.entry("timestamp without time zone", "timestamp"), Map.entry("json", "jsonb"));

    static String normalizeType(String t) {
        String s = t.trim().toLowerCase().replaceAll("\\([^)]*\\)", "").replaceAll("\\s+", " ");
        for (String cand : List.of("timestamp with time zone", "timestamp without time zone", "character varying", "double precision")) {
            if (s.startsWith(cand)) return TYPE_ALIASES.getOrDefault(cand, cand);
        }
        String first = s.split(" ")[0];
        if (TYPES.contains(first)) return first.equals("integer") ? "int" : first;
        return TYPE_ALIASES.get(first);
    }

    private void createTable(String appId, String schema, TableDef t, com.fasterxml.jackson.databind.node.ObjectNode filePolicy, List<String> ddlLog) {
        StringBuilder cols = new StringBuilder("id bigserial primary key, created_at timestamptz not null default now(), updated_at timestamptz not null default now()");
        for (var e : t.columns().entrySet()) cols.append(", \"").append(e.getKey()).append("\" ").append(e.getValue());
        String q = "\"" + schema + "\".\"" + t.name() + "\"";
        String createSql = "create table if not exists " + q + " (" + cols + ")";
        jdbc.sql(createSql).update();
        ddlLog.add(createSql + ";");
        // 已有的表：schema.sql 里新增的列也要真正加上（create table if not exists 不会补列）；系统字段老表也补上
        for (var e : t.columns().entrySet()) {
            String alterSql = "alter table " + q + " add column if not exists \"" + e.getKey() + "\" " + e.getValue();
            jdbc.sql(alterSql).update();
            ddlLog.add(alterSql + ";");
        }
        for (String sys : List.of("created_by", "created_by_name", "updated_by")) {
            String sysSql = "alter table " + q + " add column if not exists " + sys + " text";
            jdbc.sql(sysSql).update();
            ddlLog.add(sysSql + ";");
        }
        // 预设：policies.json 里这张表的预设变了才覆盖（控制台里手动切换的保留）；字段规则和状态流转以文件为准
        JsonNode old = jdbc.sql("select policy from app_cloud_table where app_id = ? and name = ?").params(appId, t.name())
                .query((rs, i) -> Json.read(rs.getString(1))).optional().orElse(null);
        com.fasterxml.jackson.databind.node.ObjectNode policy = Json.obj();
        String oldPreset = old == null ? null : old.path("preset").asText(null);
        String oldFile = old == null ? null : old.path("filePreset").asText(null);
        String filePreset = filePolicy == null ? null : filePolicy.path("preset").asText(null);
        String preset = filePreset != null && !filePreset.equals(oldFile) ? filePreset : oldPreset != null ? oldPreset : CloudPolicy.DEFAULT_PRESET;
        policy.put("preset", preset);
        if (filePreset != null) policy.put("filePreset", filePreset);
        if (filePolicy != null && filePolicy.has("fields")) policy.set("fields", filePolicy.get("fields"));
        if (filePolicy != null && filePolicy.has("transitions")) policy.set("transitions", filePolicy.get("transitions"));
        jdbc.sql("""
                insert into app_cloud_table (app_id, name, columns, policy) values (?, ?, ?::jsonb, ?::jsonb)
                on conflict (app_id, name) do update set columns = excluded.columns, policy = excluded.policy
                """).params(appId, t.name(), Json.write(t.columns()), Json.write(policy)).update();
    }

    // ---------- 数据面 ----------

    /** 令牌里的身份：admin = 作者本人（预览默认），member = 模拟普通员工，anon = 模拟未登录 */
    public static final Set<String> ROLES = Set.of("admin", "member", "anon");

    public record Bound(Cloud cloud, String role) {}

    /** 校验带签名的应用令牌：不在库里存明文，过期即失效 */
    public Cloud auth(String token) {
        return authBound(token).cloud();
    }

    public Bound authBound(String token) {
        String[] parsed = parseToken(token);
        Cloud c = find(parsed[0]).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "CLOUD_TOKEN_INVALID", "应用令牌无效"));
        return new Bound(requireOn(c), parsed[1]);
    }

    /** 发布站点：按域名找到应用后直接取（不需要令牌） */
    public Cloud forSite(String appId) {
        Cloud c = find(appId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "CLOUD_DISABLED", "这个应用的云服务没有启用"));
        return requireOn(c);
    }

    private static Cloud requireOn(Cloud c) {
        if (!"on".equals(c.status)) throw new ApiException(HttpStatus.FORBIDDEN, "CLOUD_DISABLED", "这个应用的云服务没有启用");
        return c;
    }

    /** 令牌里的模拟身份（admin / member / anon） */
    public String tokenRole(String token) {
        return parseToken(token)[1];
    }

    /** 只校验应用令牌的签名和时效，返回 appId（数据源调用不要求启用云服务） */
    public String verifyToken(String token) {
        return parseToken(token)[0];
    }

    /** zt.{appId}.{exp}.{role}.{sig}；旧格式 zt.{appId}.{exp}.{sig} 视为 admin */
    private String[] parseToken(String token) {
        if (token == null || token.isBlank()) throw new ApiException(HttpStatus.UNAUTHORIZED, "CLOUD_TOKEN_REQUIRED", "缺少应用令牌");
        String[] parts = token.split("\\.");
        if ((parts.length != 4 && parts.length != 5) || !"zt".equals(parts[0])) throw invalidToken();
        String appId = parts[1];
        String role = parts.length == 5 ? parts[3] : "admin";
        long exp;
        try { exp = Long.parseLong(parts[2]); } catch (NumberFormatException e) { throw invalidToken(); }
        String expect = parts.length == 5 ? sign(appId + ":" + exp + ":" + role) : sign(appId, exp);
        if (!ROLES.contains(role) || !expect.equals(parts[parts.length - 1])) throw invalidToken();
        if (exp < System.currentTimeMillis() / 1000) throw new ApiException(HttpStatus.UNAUTHORIZED, "CLOUD_TOKEN_EXPIRED", "应用令牌已过期");
        return new String[]{appId, role};
    }

    private static ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "CLOUD_TOKEN_INVALID", "应用令牌无效");
    }

    /** 由签名密钥派生的 32 字节密钥（不同用途用不同 label，例如数据源凭证加密） */
    public byte[] deriveKey(String label) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(("zhiye-derive:" + label).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 给应用发一个有时效的令牌：预览页用 30 分钟，启用时给用户的可用 24 小时 */
    public String mint(String appId, long ttlSeconds) {
        return mint(appId, ttlSeconds, "admin");
    }

    public String mint(String appId, long ttlSeconds, String role) {
        if (!ROLES.contains(role)) throw new IllegalArgumentException("role " + role);
        long exp = System.currentTimeMillis() / 1000 + ttlSeconds;
        return "zt." + appId + "." + exp + "." + role + "." + sign(appId + ":" + exp + ":" + role);
    }

    private String sign(String appId, long exp) {
        return sign(appId + ":" + exp);
    }

    /** 对任意文本签名（令牌、登录状态 cookie） */
    public String sign(String text) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String secret() {
        return secretKey;
    }

    private Map<String, Object> table(String appId, String name) {
        checkName(name);
        return jdbc.sql("select columns, policy from app_cloud_table where app_id = ? and name = ?").params(appId, name)
                .query((rs, i) -> Map.<String, Object>of("columns", Json.read(rs.getString(1)), "policy", Json.read(rs.getString(2))))
                .optional().orElseThrow(() -> ApiException.notFound("数据表 " + name));
    }

    private static JsonNode policyOf(Map<String, Object> t) {
        return t.get("policy") instanceof JsonNode n ? n : null;
    }

    private String qt(Cloud c, String table) {
        return "\"" + c.schema() + "\".\"" + table + "\"";
    }

    static final Set<String> SYSTEM_COLUMNS = Set.of("id", "created_at", "updated_at", "created_by", "created_by_name", "updated_by");
    private static final List<String> OPS = List.of("gte", "lte", "gt", "lt", "ne", "like", "in");
    private static final Set<String> PAGING = Set.of("limit", "offset", "order", "after", "col");

    /** 把查询参数翻译成 where：等值、__gte/__lte/__gt/__lt/__ne、__like（包含）、__in（逗号分隔） */
    private static void filters(Map<String, String> query, Map<String, String> types, StringBuilder where, List<Object> params) {
        for (var e : query.entrySet()) {
            String key = e.getKey();
            if (PAGING.contains(key)) continue;
            String col = key, op = "eq";
            int i = key.lastIndexOf("__");
            if (i > 0 && OPS.contains(key.substring(i + 2))) { col = key.substring(0, i); op = key.substring(i + 2); }
            if (!types.containsKey(col)) throw ApiException.validation("没有这个字段：" + col);
            String t = types.get(col), q = "\"" + col + "\"";
            switch (op) {
                case "eq" -> { where.append(" and ").append(q).append(" = ?::").append(t); params.add(e.getValue()); }
                case "ne" -> { where.append(" and ").append(q).append(" is distinct from ?::").append(t); params.add(e.getValue()); }
                case "gte", "lte", "gt", "lt" -> {
                    String sym = Map.of("gte", ">=", "lte", "<=", "gt", ">", "lt", "<").get(op);
                    where.append(" and ").append(q).append(' ').append(sym).append(" ?::").append(t); params.add(e.getValue());
                }
                case "like" -> { where.append(" and ").append(q).append("::text ilike ?"); params.add("%" + e.getValue().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"); }
                case "in" -> {
                    String[] vals = e.getValue().split(",", -1);
                    if (vals.length > 100) throw ApiException.validation("__in 最多 100 个值");
                    where.append(" and ").append(q).append(" in (");
                    for (int k = 0; k < vals.length; k++) { where.append(k == 0 ? "" : ", ").append("?::").append(t); params.add(vals[k].trim()); }
                    where.append(')');
                }
                default -> { }
            }
        }
    }

    private static void scope(String filter, CloudPolicy.Who who, StringBuilder where, List<Object> params) {
        if (filter == null) return;
        where.append(" and ").append(filter);
        params.add(who.userId());
    }

    /** 列表：{rows, total}；total 是满足条件的总数 */
    public Map<String, Object> list(Cloud c, String table, Map<String, String> query, CloudPolicy.Who who) {
        var t = table(c.appId(), table);
        Map<String, String> types = columnTypes(t);
        StringBuilder where = new StringBuilder(" where true");
        List<Object> params = new ArrayList<>();
        scope(CloudPolicy.readFilter(CloudPolicy.preset(policyOf(t)), who), who, where, params);
        filters(query, types, where, params);
        String orderRaw = query.getOrDefault("order", "-id");
        // 支持多列排序：逗号分隔，每列可单独带 '-' 前缀表示倒序（如 "-report_date,line"）；
        // AI 生成的应用常把多列写成逗号串，单列用法与之前完全一致
        List<String> orderCols = new ArrayList<>();
        List<String> orderDirs = new ArrayList<>();
        for (String token : orderRaw.split(",")) {
            String tok = token.trim();
            if (tok.isEmpty()) continue;
            boolean d = tok.startsWith("-");
            String col = d ? tok.substring(1).trim() : tok;
            if (!types.containsKey(col)) throw ApiException.validation("不能按「" + col + "」排序：没有这个字段");
            orderCols.add(col);
            orderDirs.add(d ? "desc" : "asc");
        }
        if (orderCols.isEmpty()) { orderCols.add("id"); orderDirs.add("desc"); }
        String firstCol = orderCols.get(0);
        boolean desc = orderDirs.get(0).equals("desc");
        if (query.containsKey("after")) {
            where.append(" and id ").append(desc ? "<" : ">").append(" ?::bigint");
            params.add(query.get("after"));
        }
        long total = jdbc.sql("select count(*) from " + qt(c, table) + where).params(params.toArray()).query(Long.class).single();
        int limit = Math.max(1, Math.min(MAX_ROWS, parseInt(query.get("limit"), 50)));
        int offset = Math.max(0, parseInt(query.get("offset"), 0));
        List<Object> p2 = new ArrayList<>(params);
        p2.add(limit);
        p2.add(offset);
        StringBuilder orderBy = new StringBuilder();
        for (int i = 0; i < orderCols.size(); i++)
            orderBy.append(i == 0 ? " order by " : ", ").append("\"").append(orderCols.get(i)).append("\" ").append(orderDirs.get(i));
        if (!firstCol.equals("id")) orderBy.append(", \"id\" desc");
        List<Map<String, Object>> rows = jdbc.sql("select * from " + qt(c, table) + where + orderBy + " limit ? offset ?")
                .params(p2.toArray()).query((rs, i) -> rowToMap(rs)).list();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        if (!rows.isEmpty() && firstCol.equals("id")) out.put("next", rows.get(rows.size() - 1).get("id"));
        return out;
    }

    public long count(Cloud c, String table) {
        table(c.appId(), table);
        return jdbc.sql("select count(*) from " + qt(c, table)).query(Long.class).single();
    }

    /** 单条：按读权限过滤，看不到时返回空 */
    public Optional<Map<String, Object>> get(Cloud c, String table, long id, CloudPolicy.Who who) {
        var t = table(c.appId(), table);
        String filter = CloudPolicy.readFilter(CloudPolicy.preset(policyOf(t)), who);
        return row(c, table, id).filter(r -> filter == null || Objects.equals(r.get("created_by"), who.userId()));
    }

    /** 一组写操作在一个事务里执行，任何一步失败全部回滚：[{op: insert|update|remove|increment, table, id, row, ...}] */
    @org.springframework.transaction.annotation.Transactional
    public List<Object> transaction(Cloud c, JsonNode ops, CloudPolicy.Who who) {
        if (ops == null || !ops.isArray() || ops.isEmpty()) throw ApiException.validation("事务里没有操作");
        if (ops.size() > 50) throw ApiException.validation("一个事务最多 50 个操作");
        List<Object> out = new ArrayList<>();
        int i = 0;
        for (JsonNode op : ops) {
            i++;
            String table = op.path("table").asText(null);
            long id = op.path("id").asLong(0);
            try {
                switch (op.path("op").asText("")) {
                    case "insert" -> out.add(insert(c, table, op.get("row"), who));
                    case "update" -> out.add(update(c, table, id, op.get("row"), who));
                    case "remove" -> { delete(c, table, id, who); out.add(null); }
                    case "increment" -> out.add(increment(c, table, id, op, who));
                    default -> throw ApiException.validation("op 只能是 insert / update / remove / increment");
                }
            } catch (ApiException e) {
                throw new ApiException(e.status(), e.code(), "事务第 " + i + " 步（" + table + "）：" + e.getMessage());
            }
        }
        return out;
    }

    private Optional<Map<String, Object>> row(Cloud c, String table, long id) {
        return jdbc.sql("select * from " + qt(c, table) + " where id = ?").params(id).query((rs, i) -> rowToMap(rs)).optional();
    }

    public Map<String, Object> insert(Cloud c, String table, JsonNode body, CloudPolicy.Who who) {
        var t = table(c.appId(), table);
        JsonNode policy = policyOf(t);
        CloudPolicy.checkInsert(CloudPolicy.preset(policy), who);
        return insertChecked(c, table, t, policy, body, who);
    }

    private Map<String, Object> insertChecked(Cloud c, String table, Map<String, Object> t, JsonNode policy, JsonNode body, CloudPolicy.Who who) {
        Map<String, Object> data = cleanData(t, body);
        Map<String, String> types = columnTypes(t);
        if (data.isEmpty()) throw ApiException.validation("没有要写入的字段");
        CloudPolicy.checkFields(policy, data, true);
        CloudPolicy.checkInitialState(policy, data);
        checkUnique(c, table, policy, data, types, null);
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (var e : data.entrySet()) {
            if (cols.length() > 0) { cols.append(", "); marks.append(", "); }
            cols.append("\"").append(e.getKey()).append("\"");
            marks.append("?::").append(types.get(e.getKey()));
            params.add(e.getValue());
        }
        if (who != null && !who.anonymous()) {
            cols.append(", created_by, created_by_name, updated_by");
            marks.append(", ?, ?, ?");
            params.add(who.userId());
            params.add(who.name() == null ? who.loginName() : who.name());
            params.add(who.actor());
        } else if (who != null && who.function()) {
            cols.append(", updated_by");
            marks.append(", ?");
            params.add(who.actor());
        }
        List<Map<String, Object>> rows = jdbc.sql("insert into " + qt(c, table) + " (" + cols + ") values (" + marks + ") returning *")
                .params(params.toArray()).query((rs, i) -> rowToMap(rs)).list();
        return rows.get(0);
    }

    /** 值和列类型对不上（数字列写文字、日期格式错）、非空约束等（SQLState 22 / 23）：转成中文说明；其他数据库错误返回 null */
    public static ApiException badValue(org.springframework.dao.DataAccessException e) {
        Throwable root = e.getMostSpecificCause();
        String state = root instanceof java.sql.SQLException se ? se.getSQLState() : null;
        if (state == null || !(state.startsWith("22") || state.startsWith("23"))) return null;
        String msg = root.getMessage() == null ? "" : root.getMessage().replaceFirst("(?s)^ERROR:\\s*", "").split("\n")[0];
        return new ApiException(HttpStatus.BAD_REQUEST, "CLOUD_BAD_VALUE", "数据格式不对：" + msg);
    }

    /** 控制台导入：一次最多 5000 行，任何一行不合规则整批不写 */
    @org.springframework.transaction.annotation.Transactional
    public int insertMany(Cloud c, String table, JsonNode rows, CloudPolicy.Who who) {
        if (rows == null || !rows.isArray() || rows.isEmpty()) throw ApiException.validation("没有要导入的数据");
        if (rows.size() > 5000) throw ApiException.validation("一次最多导入 5000 行");
        var t = table(c.appId(), table);
        JsonNode policy = policyOf(t);
        int n = 0;
        for (JsonNode r : rows) {
            try {
                insertChecked(c, table, t, policy, r, who);
            } catch (ApiException e) {
                throw ApiException.validation("第 " + (n + 1) + " 行：" + e.getMessage());
            } catch (org.springframework.dao.DataAccessException e) {
                ApiException bad = badValue(e);
                if (bad == null) throw e;
                throw ApiException.validation("第 " + (n + 1) + " 行：" + bad.getMessage());
            }
            n++;
        }
        return n;
    }

    public Map<String, Object> update(Cloud c, String table, long id, JsonNode body, CloudPolicy.Who who) {
        var t = table(c.appId(), table);
        JsonNode policy = policyOf(t);
        String filter = CloudPolicy.writeFilter(CloudPolicy.preset(policy), who);
        Map<String, Object> data = cleanData(t, body);
        Map<String, String> types = columnTypes(t);
        if (data.isEmpty()) throw ApiException.validation("没有要更新的字段");
        Map<String, Object> before = row(c, table, id).orElseThrow(() -> ApiException.notFound("这条记录"));
        if (filter != null && !Objects.equals(before.get("created_by"), who.userId())) throw CloudPolicy.denied("只能修改自己创建的记录");
        CloudPolicy.checkFields(policy, data, false);
        CloudPolicy.checkTransitions(policy, before, data, who);
        checkUnique(c, table, policy, data, types, id);
        StringBuilder set = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (var e : data.entrySet()) {
            if (set.length() > 0) set.append(", ");
            set.append("\"").append(e.getKey()).append("\" = ?::").append(types.get(e.getKey()));
            params.add(e.getValue());
        }
        set.append(", updated_at = now(), updated_by = ?");
        params.add(who == null ? null : who.actor());
        params.add(id);
        List<Map<String, Object>> rows = jdbc.sql("update " + qt(c, table) + " set " + set + " where id = ? returning *")
                .params(params.toArray()).query((rs, i) -> rowToMap(rs)).list();
        if (rows.isEmpty()) throw ApiException.notFound("这条记录");
        return rows.get(0);
    }

    public void delete(Cloud c, String table, long id, CloudPolicy.Who who) {
        var t = table(c.appId(), table);
        String filter = CloudPolicy.writeFilter(CloudPolicy.preset(policyOf(t)), who);
        Map<String, Object> before = row(c, table, id).orElseThrow(() -> ApiException.notFound("这条记录"));
        if (filter != null && !Objects.equals(before.get("created_by"), who.userId())) throw CloudPolicy.denied("只能删除自己创建的记录");
        jdbc.sql("delete from " + qt(c, table) + " where id = ?").params(id).update();
    }

    /** 原子加减：set col = col + delta，可选 min / max，越界时不改并报错（库存扣减不会扣成负数） */
    public Map<String, Object> increment(Cloud c, String table, long id, JsonNode body, CloudPolicy.Who who) {
        var t = table(c.appId(), table);
        String filter = CloudPolicy.writeFilter(CloudPolicy.preset(policyOf(t)), who);
        Map<String, String> types = columnTypes(t);
        String col = body == null ? null : body.path("column").asText(null);
        if (col == null || !types.containsKey(col) || SYSTEM_COLUMNS.contains(col)) throw ApiException.validation("没有这个字段：" + col);
        if (!Set.of("int", "bigint", "real", "double precision", "numeric").contains(types.get(col))) throw ApiException.validation("「" + col + "」不是数字字段");
        JsonNode d = body.get("delta");
        if (d == null || !d.isNumber()) throw ApiException.validation("delta 要是数字");
        StringBuilder where = new StringBuilder(" where id = ?");
        List<Object> params = new ArrayList<>();
        String q = "\"" + col + "\"";
        params.add(d.asText());
        params.add(id);
        if (filter != null) { where.append(" and ").append(filter); params.add(who.userId()); }
        if (body.has("min")) { where.append(" and coalesce(").append(q).append(", 0) + ?::numeric >= ?::numeric"); params.add(d.asText()); params.add(body.get("min").asText()); }
        if (body.has("max")) { where.append(" and coalesce(").append(q).append(", 0) + ?::numeric <= ?::numeric"); params.add(d.asText()); params.add(body.get("max").asText()); }
        List<Map<String, Object>> rows = jdbc.sql("update " + qt(c, table) + " set " + q + " = coalesce(" + q + ", 0) + ?::" + types.get(col)
                        + ", updated_at = now()" + where + " returning *")
                .params(params.toArray()).query((rs, i) -> rowToMap(rs)).list();
        if (!rows.isEmpty()) return rows.get(0);
        Map<String, Object> before = row(c, table, id).orElseThrow(() -> ApiException.notFound("这条记录"));
        if (filter != null && !Objects.equals(before.get("created_by"), who.userId())) throw CloudPolicy.denied("只能修改自己创建的记录");
        throw ApiException.validation("「" + col + "」现在是 " + before.get(col) + "，" + (d.asDouble() < 0 ? "再减就低于下限 " + body.path("min").asText() : "再加就超过上限 " + body.path("max").asText()));
    }

    /** 聚合：{groupBy:[列], count:true, sum:[列], avg:[列], min:[列], max:[列], where:{同 list 的过滤}}，在数据库里算 */
    public List<Map<String, Object>> aggregate(Cloud c, String table, JsonNode spec, CloudPolicy.Who who) {
        var t = table(c.appId(), table);
        Map<String, String> types = columnTypes(t);
        StringBuilder where = new StringBuilder(" where true");
        List<Object> params = new ArrayList<>();
        scope(CloudPolicy.readFilter(CloudPolicy.preset(policyOf(t)), who), who, where, params);
        Map<String, String> w = new LinkedHashMap<>();
        if (spec != null && spec.path("where").isObject()) spec.get("where").fields().forEachRemaining(e -> w.put(e.getKey(), e.getValue().asText()));
        filters(w, types, where, params);
        List<String> select = new ArrayList<>(), group = new ArrayList<>();
        if (spec != null && spec.path("groupBy").isArray()) for (JsonNode g : spec.get("groupBy")) {
            String col = g.asText();
            if (!types.containsKey(col)) throw ApiException.validation("没有这个字段：" + col);
            select.add("\"" + col + "\"");
            group.add("\"" + col + "\"");
        }
        if (spec == null || spec.path("count").asBoolean(true)) select.add("count(*) as count");
        for (String fn : List.of("sum", "avg", "min", "max")) {
            if (spec == null || !spec.path(fn).isArray()) continue;
            for (JsonNode x : spec.get(fn)) {
                String col = x.asText();
                if (!types.containsKey(col)) throw ApiException.validation("没有这个字段：" + col);
                if (!fn.equals("min") && !fn.equals("max") && !Set.of("int", "bigint", "real", "double precision", "numeric").contains(types.get(col)))
                    throw ApiException.validation("「" + col + "」不是数字字段，不能 " + fn);
                select.add(fn + "(\"" + col + "\") as \"" + fn + "_" + col + "\"");
            }
        }
        if (select.size() > 30 || group.size() > 5) throw ApiException.validation("聚合的字段太多");
        String sql = "select " + String.join(", ", select) + " from " + qt(c, table) + where
                + (group.isEmpty() ? "" : " group by " + String.join(", ", group) + " order by " + String.join(", ", group)) + " limit 1000";
        return jdbc.sql(sql).params(params.toArray()).query((rs, i) -> rowToMap(rs)).list();
    }

    private void checkUnique(Cloud c, String table, JsonNode policy, Map<String, Object> data, Map<String, String> types, Long selfId) {
        for (String col : CloudPolicy.uniqueFields(policy)) {
            if (!data.containsKey(col) || data.get(col) == null) continue;
            List<Object> p = new ArrayList<>(List.of(data.get(col)));
            String sql = "select count(*) from " + qt(c, table) + " where \"" + col + "\" = ?::" + types.get(col);
            if (selfId != null) { sql += " and id <> ?"; p.add(selfId); }
            if (jdbc.sql(sql).params(p.toArray()).query(Long.class).single() > 0)
                throw ApiException.validation("「" + col + "」为「" + data.get(col) + "」的记录已经存在");
        }
    }

    /** 控制台「清空数据」 */
    public long clear(Cloud c, String table) {
        table(c.appId(), table);
        return jdbc.sql("delete from " + qt(c, table)).update();
    }

    /** 控制台切换预设（之后 AI 改 policies.json 里同一张表的预设时才会被覆盖） */
    public void setPreset(String appId, String table, String preset) {
        if (!CloudPolicy.PRESETS.containsKey(preset)) throw ApiException.validation("没有这个权限预设：" + preset);
        var t = table(appId, table);
        com.fasterxml.jackson.databind.node.ObjectNode p = policyOf(t) instanceof com.fasterxml.jackson.databind.node.ObjectNode o ? o.deepCopy() : Json.obj();
        p.put("preset", preset);
        jdbc.sql("update app_cloud_table set policy = ?::jsonb where app_id = ? and name = ?").params(Json.write(p), appId, table).update();
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    /** 表的列名集合（含系统列）；columns 从 jsonb 读出来是 JsonNode，不是 Map */
    private static Set<String> columns(Map<String, Object> t) {
        return columnTypes(t).keySet();
    }

    /** 列名 → 类型（白名单里的类型，可以直接拼进 ?::type）；参数都按字符串绑定，靠类型转换写进 int/date 等列 */
    private static Map<String, String> columnTypes(Map<String, Object> t) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("id", "bigint");
        Object raw = t.get("columns");
        if (raw instanceof java.util.Map<?, ?> m) {
            for (var e : m.entrySet()) out.put(String.valueOf(e.getKey()), castType(String.valueOf(e.getValue())));
        } else if (raw instanceof JsonNode n && n.isObject()) {
            n.fields().forEachRemaining(e -> out.put(e.getKey(), castType(e.getValue().asText())));
        }
        out.put("created_at", "timestamptz");
        out.put("updated_at", "timestamptz");
        out.put("created_by", "text");
        out.put("created_by_name", "text");
        out.put("updated_by", "text");
        return out;
    }

    private static String castType(String type) {
        String n = normalizeType(type == null ? "" : type);
        return n == null ? "text" : n;
    }

    private static Map<String, Object> cleanData(Map<String, Object> t, JsonNode body) {
        if (body == null || !body.isObject()) throw ApiException.validation("请求体要是一个 JSON 对象");
        Set<String> cols = columns(t);
        Map<String, Object> out = new LinkedHashMap<>();
        body.fields().forEachRemaining(e -> {
            String k = e.getKey();
            if (SYSTEM_COLUMNS.contains(k)) return;
            if (!cols.contains(k)) throw ApiException.validation("没有这个字段：" + k);
            out.put(k, e.getValue().isNull() ? null : e.getValue().isObject() || e.getValue().isArray() ? Json.write(e.getValue()) : e.getValue().asText());
        });
        return out;
    }

    private static Map<String, Object> rowToMap(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        var md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String name = md.getColumnLabel(i);
            Object v = rs.getObject(i);
            if (v instanceof java.sql.Timestamp ts) v = ts.toInstant().toString();
            else if (v instanceof java.sql.Date d) v = d.toLocalDate().toString();
            if (v != null && v.getClass().getName().equals("org.postgresql.util.PGobject")) {
                String sv = String.valueOf(v);
                if (sv.startsWith("{") || sv.startsWith("[")) v = Json.read(sv);
            }
            m.put(name, v);
        }
        return m;
    }

    // ---------- 存储 ----------

    public Map<String, Object> putFile(Cloud c, String path, byte[] data) throws java.io.IOException {
        String p = safePath(path);
        if (data.length > 10 * 1024 * 1024) throw ApiException.validation("单个文件最多 10 MB");
        String key = c.storagePrefix() + p;
        store.put(key, data);
        return Map.of("path", p, "size", data.length, "url", "/cloud/" + c.appId() + "/storage/" + p);
    }

    public Optional<byte[]> readFile(Cloud c, String path) throws java.io.IOException {
        return store.getBytes(c.storagePrefix() + safePath(path));
    }

    public List<String> listFiles(Cloud c) throws java.io.IOException {
        return store.list(c.storagePrefix()).stream().map(k -> k.substring(c.storagePrefix().length())).toList();
    }

    public void deleteFile(Cloud c, String path) throws java.io.IOException {
        store.delete(c.storagePrefix() + safePath(path));
    }

    private static String safePath(String path) {
        if (path == null || path.isBlank()) throw ApiException.validation("缺少文件路径");
        String p = path.replace('\\', '/');
        if (p.startsWith("/") || p.contains("..")) throw ApiException.validation("路径只能是相对路径，不能含 ..");
        if (p.length() > 200) throw ApiException.validation("路径过长");
        return p;
    }
}