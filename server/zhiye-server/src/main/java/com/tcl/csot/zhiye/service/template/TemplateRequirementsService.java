package com.tcl.csot.zhiye.service.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.connector.EnterpriseConnectorService;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 灵感社区作品依赖清单提取与复刻后配置体检服务：
 * 1. 发布社区作品时，提取严格脱敏的外部依赖元数据（AGW 数据源调用名、飞书/企业连接器类型与引用 ID、单体服务环境变量键名），
 *    并识别会被社区打包自动剥离的私密文件（.env / 私钥文件）；绝不保存任何密钥、令牌、Webhook 地址或业务数据。
 * 2. 复刻后的应用打开工作台时，按当前工作空间实时评估各项依赖是否已就绪，并提供一键绑定/跳转配置指引。
 */
@Service
public class TemplateRequirementsService {
    private static final Logger log = LoggerFactory.getLogger(TemplateRequirementsService.class);

    private static final int MAX_SCAN_FILES = 120;
    private static final long MAX_SCAN_FILE_BYTES = 128 * 1024;
    private static final Pattern CODE_EXT = Pattern.compile("(?i).*\\.(ts|tsx|js|jsx|mjs|cjs|vue|svelte|html|py|java|go|ya?ml)$");
    private static final Pattern SOURCE_CALL = Pattern.compile(
            "(?:zhiye|ctx)\\.source\\.query\\(\\s*['\"`]([a-z][a-z0-9_]{1,47})['\"`]|sourceKey\\s*[:=]\\s*['\"`]([a-z][a-z0-9_]{1,47})['\"`]");
    private static final Pattern CONN_ID = Pattern.compile("\\b(conn_[a-zA-Z0-9_-]{4,48})\\b");
    private static final Pattern FEISHU_CARD_CALL = Pattern.compile("ctx\\.feishu\\.sendCard|/feishu/send-card");
    private static final Pattern BITABLE_CALL = Pattern.compile("ctx\\.bitable\\.|/bitable/(?:list|create|update)");
    private static final Pattern DOTENV_KEY = Pattern.compile("(?m)^\\s*(?:export\\s+)?([A-Z][A-Z0-9_]{1,63})\\s*=");

    private static final Set<String> SAFE_DOTENV_NAMES = Set.of(
            ".env.example", ".env.sample", ".env.template", ".env.dist", ".env.schema");

    private static final Set<String> PLATFORM_AUTO_ENV_KEYS = Set.of(
            "PORT", "HOST", "NODE_ENV", "PYTHONUNBUFFERED", "TZ", "LANG", "LC_ALL",
            "DATABASE_URL", "PGHOST", "PGPORT", "PGDATABASE", "PGUSER", "PGPASSWORD",
            "DATA_DIR", "ZHIYE_APP_ID", "ZHIYE_PUBLIC_URL", "ZHIYE_BASE_URL",
            "ZHIYE_CONNECTOR_API", "ZHIYE_CONNECTOR_TOKEN");

    private final JdbcClient jdbc;
    private final GitService git;
    private final AppRepository apps;
    private final VersionRepository versions;
    private final EnterpriseConnectorService connectors;

    public TemplateRequirementsService(JdbcClient jdbc, GitService git, AppRepository apps,
                                       VersionRepository versions, EnterpriseConnectorService connectors) {
        this.jdbc = jdbc;
        this.git = git;
        this.apps = apps;
        this.versions = versions;
        this.connectors = connectors;
    }

    /**
     * 社区模板打包时应自动剥离的私密文件：避免导入应用携带的本地 .env / 私钥证书被打包公开。
     * 注意：保留 .env.example / .env.sample / .env.template 等无密示例文件。
     */
    public static boolean isSensitiveTemplateFile(String path) {
        if (path == null || path.isBlank()) return false;
        String norm = path.replace('\\', '/');
        String base = norm.contains("/") ? norm.substring(norm.lastIndexOf('/') + 1) : norm;
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.equals(".env")) return true;
        if (lower.startsWith(".env.") && !SAFE_DOTENV_NAMES.contains(lower)) return true;
        if (lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".p12")
                || lower.endsWith(".pfx") || lower.endsWith(".jks") || lower.endsWith(".keystore")) {
            return true;
        }
        return lower.equals("id_rsa") || lower.equals("id_ed25519") || lower.equals("secrets.json");
    }

    public static String kindLabel(String kind) {
        if (kind == null) return "企业连接器";
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "feishu_bot" -> "飞书群机器人";
            case "feishu_bitable" -> "飞书多维表格";
            case "feishu_app" -> "飞书应用";
            case "webhook_http" -> "Webhook 通知";
            default -> "企业连接器";
        };
    }

    /** 提取严格脱敏的作品依赖清单（绝不返回任何密钥、URL、Token 或业务数据）。 */
    public ObjectNode extract(AppRow app, String commit) {
        ObjectNode root = Json.obj();
        Map<String, ObjectNode> dataSources = new LinkedHashMap<>();
        Map<String, ObjectNode> connectorDeps = new LinkedHashMap<>();
        Map<String, ObjectNode> envVars = new LinkedHashMap<>();
        boolean hasCloudSchema = false;
        int strippedSecretFiles = 0;

        // 1. 已绑定的 AGW / 企业数据源（仅取 key/name/description/platform/provider，绝不取 URL/凭证）
        try {
            jdbc.sql("""
                    select ds.key, ds.name, coalesce(ds.description, '') as description,
                           (ds.workspace_id is null) as is_platform,
                           coalesce(ds.provider, 'agw') as provider
                      from app_data_source ads
                      join data_source ds on ds.id = ads.source_id
                     where ads.app_id = ? and ds.deleted_at is null
                     order by ads.created_at asc
                    """).param(app.id()).query(rs -> {
                String key = rs.getString("key");
                if (key == null || key.isBlank()) return;
                ObjectNode n = Json.obj();
                n.put("key", key);
                n.put("name", clip(rs.getString("name"), 60));
                n.put("description", clip(rs.getString("description"), 120));
                n.put("platform", rs.getBoolean("is_platform"));
                n.put("provider", rs.getString("provider"));
                dataSources.put(key, n);
            });
        } catch (Exception e) {
            log.warn("提取应用绑定数据源依赖失败 app={}: {}", app.id(), e.getMessage());
        }

        // 2. 单体服务自定义环境变量（仅提取键名与是否敏感标记，绝不解密或记录值）
        boolean managedPg = false;
        boolean pvc = false;
        if ("service".equals(app.projectKind())) {
            try {
                jdbc.sql("select env_key, secret from app_service_env where app_id = ? order by env_key asc")
                        .param(app.id()).query(rs -> {
                    String k = rs.getString("env_key");
                    if (k == null || k.isBlank() || PLATFORM_AUTO_ENV_KEYS.contains(k)) return;
                    ObjectNode n = Json.obj();
                    n.put("key", k);
                    n.put("secret", rs.getBoolean("secret"));
                    n.put("source", "service_env");
                    envVars.put(k, n);
                });
                var svc = jdbc.sql("select pg_enabled, pvc_enabled from app_service where app_id = ?")
                        .param(app.id()).query((rs, i) -> new boolean[]{rs.getBoolean("pg_enabled"), rs.getBoolean("pvc_enabled")})
                        .optional();
                if (svc.isPresent()) {
                    managedPg = svc.get()[0];
                    pvc = svc.get()[1];
                }
            } catch (Exception e) {
                log.warn("提取单体服务环境变量依赖失败 app={}: {}", app.id(), e.getMessage());
            }
        }

        // 3. 轻量扫描 Git 文件树：识别代码引用的数据源 key、飞书/企业连接器、.env 键名与私密文件数量
        Set<String> foundConnIds = new LinkedHashSet<>();
        boolean sawFeishuCard = false;
        boolean sawBitable = false;
        try {
            List<GitService.FileEntry> files = git.listFiles(app.id(), commit);
            int scanned = 0;
            for (GitService.FileEntry f : files) {
                String p = f.path();
                if (p == null) continue;
                if ("cloud/schema.sql".equals(p)) hasCloudSchema = true;
                boolean sensitive = isSensitiveTemplateFile(p);
                if (sensitive) strippedSecretFiles++;

                String base = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT) : p.toLowerCase(Locale.ROOT);
                boolean isDotenv = base.equals(".env") || base.startsWith(".env.");
                boolean isCode = CODE_EXT.matcher(p).matches();
                if (!isDotenv && !isCode) continue;
                if (f.size() > MAX_SCAN_FILE_BYTES || scanned >= MAX_SCAN_FILES) continue;
                scanned++;

                Optional<byte[]> raw = git.readFile(app.id(), commit, p);
                if (raw.isEmpty()) continue;
                String text = new String(raw.get(), StandardCharsets.UTF_8);

                if (isDotenv && ("service".equals(app.projectKind()) || sensitive)) {
                    Matcher dm = DOTENV_KEY.matcher(text);
                    while (dm.find()) {
                        String k = dm.group(1);
                        if (PLATFORM_AUTO_ENV_KEYS.contains(k) || envVars.containsKey(k)) continue;
                        ObjectNode n = Json.obj();
                        n.put("key", k);
                        n.put("secret", looksSecretKey(k));
                        n.put("source", sensitive ? "dotenv_stripped" : "dotenv_example");
                        envVars.put(k, n);
                    }
                    continue;
                }
                if (!isCode) continue;

                Matcher sm = SOURCE_CALL.matcher(text);
                while (sm.find()) {
                    String key = sm.group(1) != null ? sm.group(1) : sm.group(2);
                    if (key != null && !key.isBlank() && !dataSources.containsKey(key)) {
                        ObjectNode n = Json.obj();
                        n.put("key", key);
                        n.put("name", key);
                        n.put("description", "");
                        n.put("platform", false);
                        n.put("provider", "code");
                        dataSources.put(key, n);
                    }
                }
                Matcher cm = CONN_ID.matcher(text);
                while (cm.find()) foundConnIds.add(cm.group(1));
                if (FEISHU_CARD_CALL.matcher(text).find()) sawFeishuCard = true;
                if (BITABLE_CALL.matcher(text).find()) sawBitable = true;
            }
        } catch (Exception e) {
            log.warn("扫描作品源码依赖失败 app={}: {}", app.id(), e.getMessage());
        }

        // 4. 补全连接器元信息（严格脱敏：私有连接器不暴露作者群/表名称或任何 config/secret）
        boolean hasCardKind = false;
        boolean hasBitableKind = false;
        for (String cid : foundConnIds) {
            var opt = connectors.findById(cid);
            if (opt.isPresent()) {
                var c = opt.get();
                boolean platform = c.workspaceId() == null || c.workspaceId().isBlank();
                ObjectNode n = Json.obj();
                n.put("kind", c.kind());
                n.put("kindLabel", kindLabel(c.kind()));
                n.put("name", platform ? clip(c.name(), 60) : kindLabel(c.kind()));
                n.put("scope", platform ? "platform" : "workspace");
                n.put("sourceConnectorId", cid);
                connectorDeps.put("id:" + cid, n);
                if ("feishu_bot".equals(c.kind()) || "feishu_app".equals(c.kind())) hasCardKind = true;
                if ("feishu_bitable".equals(c.kind()) || "feishu_app".equals(c.kind())) hasBitableKind = true;
            } else {
                ObjectNode n = Json.obj();
                n.put("kind", "feishu_bot");
                n.put("kindLabel", "飞书 / 企业连接器");
                n.put("name", "自定义连接器");
                n.put("scope", "workspace");
                n.put("sourceConnectorId", cid);
                connectorDeps.put("id:" + cid, n);
            }
        }
        if (sawFeishuCard && !hasCardKind) {
            ObjectNode n = Json.obj();
            n.put("kind", "feishu_bot");
            n.put("kindLabel", kindLabel("feishu_bot"));
            n.put("name", "飞书消息通知");
            n.put("scope", "workspace");
            connectorDeps.put("implicit:feishu_bot", n);
        }
        if (sawBitable && !hasBitableKind) {
            ObjectNode n = Json.obj();
            n.put("kind", "feishu_bitable");
            n.put("kindLabel", kindLabel("feishu_bitable"));
            n.put("name", "飞书多维表格");
            n.put("scope", "workspace");
            connectorDeps.put("implicit:feishu_bitable", n);
        }

        ArrayNode dsArr = Json.M.createArrayNode();
        dataSources.values().forEach(dsArr::add);
        ArrayNode connArr = Json.M.createArrayNode();
        connectorDeps.values().forEach(connArr::add);
        ArrayNode envArr = Json.M.createArrayNode();
        envVars.values().forEach(envArr::add);

        boolean cloudActive = hasCloudSchema;
        if (!cloudActive) {
            try {
                cloudActive = Boolean.TRUE.equals(jdbc.sql("select exists(select 1 from app_cloud where app_id = ? and status = 'active')")
                        .param(app.id()).query(Boolean.class).single());
            } catch (Exception ignored) {}
        }

        root.set("dataSources", dsArr);
        root.set("connectors", connArr);
        root.set("envVars", envArr);
        root.put("cloud", cloudActive);
        root.put("managedPg", managedPg);
        root.put("pvc", pvc);
        root.put("strippedSecretFileCount", strippedSecretFiles);
        root.put("projectKind", app.projectKind() == null ? "static" : app.projectKind());
        root.put("sourceOrigin", app.source() == null ? "chat" : app.source());
        root.put("hasExternalDeps", !dsArr.isEmpty() || !connArr.isEmpty() || !envArr.isEmpty());
        return root;
    }

    /** 实时检查复刻应用在当前工作空间下仍需补齐的配置项。 */
    public Map<String, Object> setupCheck(String ws, String appId) {
        AppRow app = apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
        record Meta(String sourceTemplateId, String reqJson) {}
        Meta meta = jdbc.sql("select source_template_id, template_requirements::text as req_json from app where id = ? and workspace_id = ?")
                .params(app.id(), ws)
                .query((rs, i) -> new Meta(rs.getString("source_template_id"), rs.getString("req_json")))
                .optional().orElse(new Meta(null, null));

        if (meta.reqJson() == null || meta.reqJson().isBlank()) {
            return emptyCheck(meta.sourceTemplateId());
        }
        JsonNode req = Json.read(meta.reqJson());
        if (req == null || !req.isObject()) {
            return emptyCheck(meta.sourceTemplateId());
        }

        List<Map<String, Object>> items = new ArrayList<>();
        int pending = 0;
        int ready = 0;

        // 1. 检查 AGW / 企业数据源绑定情况
        JsonNode dsNodes = req.path("dataSources");
        if (dsNodes.isArray() && !dsNodes.isEmpty()) {
            Set<String> boundKeys = new HashSet<>();
            jdbc.sql("""
                    select ds.key from app_data_source ads
                      join data_source ds on ds.id = ads.source_id
                     where ads.app_id = ? and ds.deleted_at is null
                    """).param(app.id()).query(rs -> { boundKeys.add(rs.getString("key")); });

            record VisibleSource(String id, String key, String name, boolean platform) {}
            Map<String, VisibleSource> visibleByKey = new LinkedHashMap<>();
            jdbc.sql("""
                    select id, key, name, (workspace_id is null) as is_platform
                      from data_source
                     where deleted_at is null and (workspace_id is null or workspace_id = ?)
                     order by (workspace_id is null) asc, created_at desc
                    """).param(ws).query(rs -> {
                String k = rs.getString("key");
                visibleByKey.putIfAbsent(k, new VisibleSource(rs.getString("id"), k, rs.getString("name"), rs.getBoolean("is_platform")));
            });

            for (JsonNode d : dsNodes) {
                String key = d.path("key").asText("");
                if (key.isBlank()) continue;
                String name = d.path("name").asText(key);
                boolean isBound = boundKeys.contains(key);
                VisibleSource match = visibleByKey.get(key);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("category", "dataSource");
                item.put("key", key);
                item.put("name", name);
                item.put("description", d.path("description").asText(""));
                item.put("status", isBound ? "ready" : "missing");
                if (isBound) {
                    ready++;
                    item.put("hint", "已绑定到当前应用");
                } else {
                    pending++;
                    if (match != null) {
                        item.put("matchingSourceId", match.id());
                        item.put("matchingSourceName", match.name());
                        item.put("action", "bind_source");
                        item.put("hint", "你的空间已有同名数据源，可一键绑定到本应用");
                    } else {
                        item.put("action", "open_sources");
                        item.put("hint", "需先从 AGW 导入你有权限的数据源（调用名：" + key + "），再绑定或让 AI 改接");
                    }
                }
                items.add(item);
            }
        }

        // 2. 检查飞书 / 企业连接器可用性
        JsonNode connNodes = req.path("connectors");
        if (connNodes.isArray() && !connNodes.isEmpty()) {
            List<Map<String, Object>> enabledVisible = connectors.list(ws).stream()
                    .filter(c -> Boolean.TRUE.equals(c.get("enabled")))
                    .toList();
            Set<String> visibleIds = new HashSet<>();
            for (var c : enabledVisible) visibleIds.add(String.valueOf(c.get("id")));

            // 读取当前版本源码里仍实际出现的连接器 ID（若用户已让 AI 替换掉旧连接器，可自动消除提示）
            Set<String> currentCodeConnIds = scanCurrentCodeConnectorIds(app);

            for (JsonNode c : connNodes) {
                String kind = c.path("kind").asText("feishu_bot");
                String label = c.path("kindLabel").asText(kindLabel(kind));
                String name = c.path("name").asText(label);
                String srcId = c.path("sourceConnectorId").asText("");
                boolean isPlatform = "platform".equals(c.path("scope").asText(""));

                Map<String, Object> match = findMatchingConnector(enabledVisible, kind);
                boolean isReady;
                String hint;
                String action = null;

                if (!srcId.isBlank()) {
                    if (visibleIds.contains(srcId)) {
                        isReady = true;
                        hint = isPlatform ? "平台公共连接器，当前空间可直接使用" : "当前空间已具备该连接器权限";
                    } else if (!currentCodeConnIds.isEmpty() && !currentCodeConnIds.contains(srcId)) {
                        // 源码里已不再引用原作者私有连接器，且若已换成当前空间可用连接器则视为就绪
                        boolean usesOwned = currentCodeConnIds.stream().anyMatch(visibleIds::contains);
                        isReady = usesOwned || match != null;
                        hint = isReady ? "已替换为当前工作空间可用的连接器" : "请在生态中心配置自己的" + label;
                        if (!isReady) action = "open_ecosystem";
                    } else {
                        isReady = false;
                        if (match != null) {
                            action = "rebind_connector";
                            hint = "原模板引用了作者私有" + label + "，你有可用的「" + match.get("name") + "」，可一键让 AI 替换";
                        } else {
                            action = "open_ecosystem";
                            hint = "原模板引用了作者私有" + label + "（已隔离），请先在「生态中心」添加你自己的连接器";
                        }
                    }
                } else {
                    isReady = match != null;
                    if (isReady) {
                        hint = "当前空间已有可用" + label + "（" + match.get("name") + "）";
                    } else {
                        action = "open_ecosystem";
                        hint = "该模板调用了" + label + "，请先在「生态中心」配置你的飞书连接器";
                    }
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("category", "connector");
                item.put("kind", kind);
                item.put("kindLabel", label);
                item.put("name", name);
                item.put("scope", isPlatform ? "platform" : "workspace");
                if (!srcId.isBlank()) item.put("sourceConnectorId", srcId);
                if (match != null) {
                    item.put("matchingConnectorId", match.get("id"));
                    item.put("matchingConnectorName", match.get("name"));
                }
                item.put("status", isReady ? "ready" : "missing");
                if (action != null) item.put("action", action);
                item.put("hint", hint);
                if (isReady) ready++; else pending++;
                items.add(item);
            }
        }

        // 3. 检查单体服务自定义环境变量
        JsonNode envNodes = req.path("envVars");
        if (envNodes.isArray() && !envNodes.isEmpty()) {
            Set<String> configured = new HashSet<>();
            try {
                jdbc.sql("select env_key from app_service_env where app_id = ?")
                        .param(app.id()).query(rs -> { configured.add(rs.getString("env_key")); });
            } catch (Exception ignored) {}

            for (JsonNode ev : envNodes) {
                String key = ev.path("key").asText("");
                if (key.isBlank()) continue;
                boolean ok = configured.contains(key);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("category", "envVar");
                item.put("key", key);
                item.put("name", key);
                item.put("secret", ev.path("secret").asBoolean(true));
                item.put("status", ok ? "ready" : "missing");
                if (ok) {
                    ready++;
                    item.put("hint", "已在「服务托管」中配置");
                } else {
                    pending++;
                    item.put("action", "open_service");
                    item.put("hint", "原模板未携带环境变量值，请在「服务托管 → 环境变量」填写并重新部署");
                }
                items.add(item);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("needsSetup", pending > 0);
        out.put("sourceTemplateId", meta.sourceTemplateId());
        out.put("strippedSecretFileCount", req.path("strippedSecretFileCount").asInt(0));
        out.put("cloud", req.path("cloud").asBoolean(false));
        out.put("managedPg", req.path("managedPg").asBoolean(false));
        out.put("pvc", req.path("pvc").asBoolean(false));
        out.put("items", items);
        out.put("summary", Map.of("total", items.size(), "pending", pending, "ready", ready));
        return out;
    }

    private Set<String> scanCurrentCodeConnectorIds(AppRow app) {
        if (app.currentVersionId() == null) return Set.of();
        Optional<VersionRow> v = versions.find(app.currentVersionId());
        if (v.isEmpty()) return Set.of();
        Set<String> ids = new LinkedHashSet<>();
        try {
            int scanned = 0;
            for (GitService.FileEntry f : git.listFiles(app.id(), v.get().gitCommit())) {
                if (f.path() == null || !CODE_EXT.matcher(f.path()).matches()
                        || f.size() > MAX_SCAN_FILE_BYTES || scanned >= MAX_SCAN_FILES) continue;
                scanned++;
                Optional<byte[]> raw = git.readFile(app.id(), v.get().gitCommit(), f.path());
                if (raw.isEmpty()) continue;
                Matcher m = CONN_ID.matcher(new String(raw.get(), StandardCharsets.UTF_8));
                while (m.find()) ids.add(m.group(1));
            }
        } catch (Exception ignored) {}
        return ids;
    }

    private static Map<String, Object> findMatchingConnector(List<Map<String, Object>> enabledVisible, String kind) {
        for (var c : enabledVisible) {
            if (kind.equals(String.valueOf(c.get("kind")))) return c;
        }
        // feishu_app 同时兼容卡片与多维表格
        if ("feishu_bot".equals(kind) || "feishu_bitable".equals(kind)) {
            for (var c : enabledVisible) {
                if ("feishu_app".equals(String.valueOf(c.get("kind")))) return c;
            }
        }
        return null;
    }

    private static Map<String, Object> emptyCheck(String sourceTemplateId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("needsSetup", false);
        out.put("sourceTemplateId", sourceTemplateId);
        out.put("strippedSecretFileCount", 0);
        out.put("cloud", false);
        out.put("managedPg", false);
        out.put("pvc", false);
        out.put("items", List.of());
        out.put("summary", Map.of("total", 0, "pending", 0, "ready", 0));
        return out;
    }

    private static boolean looksSecretKey(String k) {
        String u = k.toUpperCase(Locale.ROOT);
        return u.contains("SECRET") || u.contains("TOKEN") || u.contains("KEY")
                || u.contains("PASS") || u.contains("PWD") || u.contains("WEBHOOK") || u.contains("AUTH");
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > n ? t.substring(0, n) : t;
    }

    public static JsonNode parseOrEmpty(String json) {
        if (json == null || json.isBlank()) return Json.obj();
        try {
            JsonNode n = Json.read(json);
            return n != null && n.isObject() ? n : Json.obj();
        } catch (Exception e) {
            return Json.obj();
        }
    }
}
