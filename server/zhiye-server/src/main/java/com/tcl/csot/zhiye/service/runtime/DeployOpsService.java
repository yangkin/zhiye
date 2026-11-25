package com.tcl.csot.zhiye.service.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.config.ServiceRuntimeProperties;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.TaskRow;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.MessageRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.task.TaskService;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.LogRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Deploy Engine V2 高级运维与交付编排服务：
 * 1. AI 一键自愈修复（将结构化根因诊断 + 脱敏日志投递给 Coding Agent Harness V2 自动修复并验证）
 * 2. 声明式多服务拓扑（zhiye.yaml：web / api / worker / cron / postgres / redis 解析、校验与持久化）
 * 3. GitLab / GitHub / 本地模拟 CI/CD 仓库配置、Webhook 签名鉴权与自动部署触发
 * 4. 标准生产部署包导出（Dockerfile + docker-compose.yml + .env.example + zhiye.yaml + 完整 Helm Chart ZIP）
 */
@Service
public class DeployOpsService {
    private static final Logger log = LoggerFactory.getLogger(DeployOpsService.class);
    private static final Set<String> ALLOWED_TOPOLOGY_TYPES = Set.of("web", "api", "worker", "cron", "postgres", "redis");
    private static final Pattern SVC_NAME_RE = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    private final JdbcClient jdbc;
    private final AppRepository apps;
    private final VersionRepository versions;
    private final MonolithServiceManager manager;
    private final ServiceCrypto crypto;
    private final ServiceRuntimeProperties props;
    private final TaskService tasks;
    private final MessageRepository messages;

    public DeployOpsService(JdbcClient jdbc, AppRepository apps, VersionRepository versions,
                            MonolithServiceManager manager, ServiceCrypto crypto,
                            ServiceRuntimeProperties props, TaskService tasks, MessageRepository messages) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.versions = versions;
        this.manager = manager;
        this.crypto = crypto;
        this.props = props;
        this.tasks = tasks;
        this.messages = messages;
    }

    // ==================== 1. AI 一键部署失败自愈 ====================

    public Map<String, Object> triggerAutoHeal(String ws, String appId) {
        AppRow app = requireServiceApp(ws, appId);
        if (app.currentVersionId() == null || app.currentVersionId().isBlank()) {
            throw ApiException.conflict("NO_VERSION", "该应用还没有可修复的代码版本");
        }
        Map<String, Object> diag = manager.diagnosisView(ws, appId);
        Map<String, Object> logMap = manager.logs(ws, appId, 120);
        String rtLogs = LogRedactor.redact(String.valueOf(logMap.getOrDefault("runtimeLogs", "")));
        String buildLog = LogRedactor.redact(String.valueOf(logMap.getOrDefault("buildLog", "")));
        String lastErr = LogRedactor.redact(String.valueOf(logMap.getOrDefault("lastError", "")));

        StringBuilder prompt = new StringBuilder();
        prompt.append("【部署引擎 V2 自动自愈任务】请修复以下部署/启动失败，并在修改后通过类型检查、接口测试（test_endpoint）与构建验证确保服务可正常启动和通过健康检查：\n\n");
        prompt.append("- 根因分类：").append(diag.get("category")).append(" — ").append(diag.get("title")).append("\n");
        prompt.append("- 摘要：").append(diag.get("summary")).append("\n");
        if (diag.get("rootCause") != null && !String.valueOf(diag.get("rootCause")).isBlank()) {
            prompt.append("- 根因说明：").append(diag.get("rootCause")).append("\n");
        }
        Object fixes = diag.get("suggestedFixes");
        if (fixes instanceof List<?> list && !list.isEmpty()) {
            prompt.append("- 建议修复方向：\n");
            for (Object f : list) prompt.append("  * ").append(f).append("\n");
        }
        if (!lastErr.isBlank() && !"null".equals(lastErr)) {
            prompt.append("\n### 最近错误信息\n```\n").append(tail(lastErr, 2500)).append("\n```\n");
        }
        if (!buildLog.isBlank()) {
            prompt.append("\n### 构建日志尾部\n```\n").append(tail(buildLog, 3000)).append("\n```\n");
        }
        if (!rtLogs.isBlank()) {
            prompt.append("\n### 运行日志尾部\n```\n").append(tail(rtLogs, 2500)).append("\n```\n");
        }
        prompt.append("\n要求：定位根因并彻底修复源码、依赖清单或启动脚本，不要绕过健康检查；完成后输出简要修复说明。");

        String instruction = prompt.toString();
        ObjectNode input = Json.obj();
        input.put("instruction", instruction);
        input.put("source", "deploy_auto_heal");
        input.put("diagnosisCategory", String.valueOf(diag.get("category")));

        String idemKey = "autoheal-" + appId + "-" + (System.currentTimeMillis() / 5000L);
        TaskRow task = tasks.create(ws, app.id(), "modify", app.currentVersionId(), input, idemKey, 1, null);
        try {
            messages.insert(app.id(), "user",
                    "【部署自愈】自动修复部署异常（" + diag.get("category") + "：" + diag.get("title") + "）",
                    null, task.id(), "pending");
        } catch (Exception ignored) {}

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.id());
        out.put("status", task.status());
        out.put("diagnosis", diag);
        return out;
    }

    // ==================== 2. 声明式多服务拓扑 (zhiye.yaml) ====================

    public Map<String, Object> getTopology(String ws, String appId) {
        AppRow app = requireServiceApp(ws, appId);
        MonolithServiceManager.ServiceRow svc = manager.find(app.id()).orElse(null);

        record TopoRow(String yaml, String servicesJson, Instant updatedAt) {}
        Optional<TopoRow> existing = jdbc.sql("select topology_yaml, services_json::text as sj, updated_at from app_service_topology where app_id = ?")
                .param(app.id())
                .query((rs, i) -> new TopoRow(rs.getString("topology_yaml"), rs.getString("sj"), Db.ts(rs, "updated_at")))
                .optional();

        String yaml;
        List<Map<String, Object>> services;
        String updatedAt = null;
        boolean customized;

        if (existing.isPresent() && existing.get().yaml() != null && !existing.get().yaml().isBlank()) {
            yaml = existing.get().yaml();
            services = parseYamlServices(yaml);
            updatedAt = Db.iso(existing.get().updatedAt());
            customized = true;
        } else {
            yaml = generateDefaultTopologyYaml(app, svc);
            services = parseYamlServices(yaml);
            customized = false;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appId", app.id());
        out.put("customized", customized);
        out.put("yaml", yaml);
        out.put("services", services);
        out.put("updatedAt", updatedAt);
        return out;
    }

    public Map<String, Object> saveTopology(String ws, String appId, String rawYaml) {
        AppRow app = requireServiceApp(ws, appId);
        String yaml = rawYaml == null ? "" : rawYaml.replace("\r\n", "\n").trim();
        if (yaml.isEmpty()) throw ApiException.validation("zhiye.yaml 内容不能为空");
        if (yaml.length() > 32_000) throw ApiException.validation("zhiye.yaml 内容过长（上限 32KB）");

        List<Map<String, Object>> services = parseYamlServices(yaml);
        if (services.isEmpty()) {
            throw ApiException.validation("zhiye.yaml 未解析到有效的 services 定义（需包含至少一个 web/api/worker/cron/postgres/redis 服务）");
        }
        if (services.size() > 12) {
            throw ApiException.validation("单个拓扑最多声明 12 个服务组件");
        }
        for (Map<String, Object> s : services) {
            String name = String.valueOf(s.getOrDefault("name", ""));
            String type = String.valueOf(s.getOrDefault("type", ""));
            if (!SVC_NAME_RE.matcher(name).matches()) {
                throw ApiException.validation("非法服务名：" + name + "（须以小写字母开头，仅含小写字母/数字/连字符）");
            }
            if (!ALLOWED_TOPOLOGY_TYPES.contains(type)) {
                throw ApiException.validation("服务 " + name + " 的 type=" + type + " 不合法，允许值：" + ALLOWED_TOPOLOGY_TYPES);
            }
        }

        String json = Json.write(services);
        jdbc.sql("""
                insert into app_service_topology (app_id, topology_yaml, services_json, updated_at)
                values (?, ?, ?::jsonb, now())
                on conflict (app_id) do update
                   set topology_yaml = excluded.topology_yaml,
                       services_json = excluded.services_json,
                       updated_at = now()
                """).params(app.id(), yaml, json).update();
        return getTopology(ws, appId);
    }

    private String generateDefaultTopologyYaml(AppRow app, MonolithServiceManager.ServiceRow svc) {
        int port = svc != null ? svc.port() : 8080;
        String health = svc != null && svc.healthPath() != null && !svc.healthPath().isBlank() ? svc.healthPath() : "/api/health";
        String start = svc != null && svc.startCmd() != null && !svc.startCmd().isBlank() ? svc.startCmd() : "npm start";
        int mem = svc != null ? svc.memoryMb() : 512;
        int cpu = svc != null ? svc.cpuMillicores() : 500;
        StringBuilder sb = new StringBuilder();
        sb.append("# Zhiye Declarative Multi-Service Topology (zhiye.yaml)\n");
        sb.append("version: \"2\"\n");
        sb.append("app: ").append(app.slug()).append("\n");
        sb.append("services:\n");
        if (svc != null && svc.frontendDir() != null && !svc.frontendDir().isBlank()) {
            sb.append("  web:\n");
            sb.append("    type: web\n");
            sb.append("    dir: ").append(svc.frontendDir()).append("\n");
            sb.append("    port: ").append(port).append("\n");
            sb.append("    replicas: 1\n");
        }
        sb.append("  api:\n");
        sb.append("    type: api\n");
        sb.append("    command: \"").append(start.replace("\"", "\\\"")).append("\"\n");
        sb.append("    port: ").append(port).append("\n");
        sb.append("    healthPath: ").append(health).append("\n");
        sb.append("    memoryMb: ").append(mem).append("\n");
        sb.append("    cpuMillicores: ").append(cpu).append("\n");
        sb.append("    replicas: 1\n");
        if (svc != null && svc.pgEnabled()) {
            sb.append("  postgres:\n");
            sb.append("    type: postgres\n");
            sb.append("    schema: ").append(svc.pgSchema() == null ? "auto" : svc.pgSchema()).append("\n");
            sb.append("    managed: true\n");
        }
        return sb.toString();
    }

    /** 轻量确定性 YAML services 区块解析器（无需额外外部依赖，支持缩进结构、注释、引号）。 */
    static List<Map<String, Object>> parseYamlServices(String yaml) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (yaml == null || yaml.isBlank()) return list;
        String[] lines = yaml.replace("\r\n", "\n").split("\n");
        boolean inServices = false;
        Map<String, Object> current = null;
        Pattern svcHeader = Pattern.compile("^  ([a-zA-Z0-9_-]+):\\s*$");
        Pattern propLine = Pattern.compile("^    ([a-zA-Z0-9_-]+):\\s*(.*)$");

        for (String raw : lines) {
            String noComment = raw.replaceAll("\\s+#.*$", "");
            if (noComment.trim().isEmpty() || noComment.trim().startsWith("#")) continue;
            if (noComment.matches("^services:\\s*$")) {
                inServices = true;
                continue;
            }
            if (inServices && !noComment.startsWith(" ")) {
                inServices = false;
                current = null;
                continue;
            }
            if (!inServices) continue;

            Matcher sm = svcHeader.matcher(noComment);
            if (sm.matches()) {
                current = new LinkedHashMap<>();
                current.put("name", sm.group(1).trim());
                current.put("type", inferDefaultType(sm.group(1).trim()));
                list.add(current);
                continue;
            }
            Matcher pm = propLine.matcher(noComment);
            if (pm.matches() && current != null) {
                String k = pm.group(1).trim();
                String v = unquote(pm.group(2).trim());
                if ("port".equals(k) || "replicas".equals(k) || "memoryMb".equals(k) || "cpuMillicores".equals(k)) {
                    try { current.put(k, Integer.parseInt(v)); } catch (NumberFormatException ignored) { current.put(k, v); }
                } else if ("managed".equals(k)) {
                    current.put(k, Boolean.parseBoolean(v));
                } else {
                    current.put(k, v);
                }
            }
        }
        return list;
    }

    private static String inferDefaultType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("pg") || n.contains("postgres") || n.contains("db")) return "postgres";
        if (n.contains("redis") || n.contains("cache")) return "redis";
        if (n.contains("cron") || n.contains("sched")) return "cron";
        if (n.contains("worker") || n.contains("queue")) return "worker";
        if (n.contains("web") || n.contains("front")) return "web";
        return "api";
    }

    private static String unquote(String v) {
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"");
        }
        return v;
    }

    // ==================== 3. GitLab / GitHub / 本地模拟 CI/CD ====================

    public Map<String, Object> getGitCicd(String ws, String appId) {
        AppRow app = requireServiceApp(ws, appId);
        Optional<Map<String, Object>> row = jdbc.sql("""
                select provider, repo_url, branch, (webhook_secret_enc is not null and webhook_secret_enc <> '') as has_secret,
                       (access_token_enc is not null and access_token_enc <> '') as has_token,
                       auto_deploy, deploy_slot, last_commit_sha, last_commit_msg, last_sync_at, last_status, last_error, updated_at
                  from app_git_repo
                 where app_id = ?
                """).param(app.id()).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("configured", true);
            m.put("provider", rs.getString("provider"));
            m.put("repoUrl", rs.getString("repo_url"));
            m.put("branch", rs.getString("branch"));
            m.put("hasWebhookSecret", rs.getBoolean("has_secret"));
            m.put("hasAccessToken", rs.getBoolean("has_token"));
            m.put("autoDeploy", rs.getBoolean("auto_deploy"));
            m.put("deploySlot", rs.getString("deploy_slot"));
            m.put("lastCommitSha", rs.getString("last_commit_sha"));
            m.put("lastCommitMsg", rs.getString("last_commit_msg"));
            m.put("lastSyncAt", Db.iso(Db.ts(rs, "last_sync_at")));
            m.put("lastStatus", rs.getString("last_status"));
            m.put("lastError", rs.getString("last_error"));
            m.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
            m.put("webhookPath", "/api/webhooks/git/" + app.id());
            return m;
        }).optional();

        if (row.isPresent()) return row.get();
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("configured", false);
        def.put("provider", "gitlab");
        def.put("repoUrl", "");
        def.put("branch", "main");
        def.put("hasWebhookSecret", false);
        def.put("hasAccessToken", false);
        def.put("autoDeploy", true);
        def.put("deploySlot", "preview");
        def.put("webhookPath", "/api/webhooks/git/" + app.id());
        return def;
    }

    public Map<String, Object> saveGitCicd(String ws, String appId, JsonNode body) {
        AppRow app = requireServiceApp(ws, appId);
        String provider = body.path("provider").asText("gitlab").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("gitlab", "github", "mock").contains(provider)) {
            throw ApiException.validation("provider 仅支持 gitlab / github / mock");
        }
        String repoUrl = body.path("repoUrl").asText("").trim();
        if (!"mock".equals(provider) && repoUrl.isEmpty()) {
            throw ApiException.validation("请填写 Git 仓库地址（如 https://gitlab.csot.tcl.com/team/app.git），或选择「本地模拟仓库」");
        }
        if ("mock".equals(provider) && repoUrl.isEmpty()) {
            repoUrl = "mock://zhiye-git/" + app.slug() + ".git";
        }
        String branch = body.path("branch").asText("main").trim();
        if (branch.isEmpty()) branch = "main";
        boolean autoDeploy = body.path("autoDeploy").asBoolean(true);
        String deploySlot = body.path("deploySlot").asText("preview").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("preview", "live").contains(deploySlot)) deploySlot = "preview";

        // 保留既有密文，除非传入了新值
        record OldSecrets(String whEnc, String tokEnc) {}
        OldSecrets old = jdbc.sql("select webhook_secret_enc, access_token_enc from app_git_repo where app_id = ?")
                .param(app.id())
                .query((rs, i) -> new OldSecrets(rs.getString(1), rs.getString(2)))
                .optional().orElse(new OldSecrets(null, null));

        String rawSecret = body.hasNonNull("webhookSecret") ? body.path("webhookSecret").asText("").trim() : "";
        String whEnc = !rawSecret.isEmpty() ? crypto.encrypt(rawSecret)
                : (old.whEnc() != null ? old.whEnc() : crypto.encrypt("whsec_" + Ids.newId("git").replace("git_", "")));

        String rawToken = body.hasNonNull("accessToken") ? body.path("accessToken").asText("").trim() : "";
        String tokEnc = !rawToken.isEmpty() ? crypto.encrypt(rawToken) : old.tokEnc();

        jdbc.sql("""
                insert into app_git_repo
                  (app_id, workspace_id, provider, repo_url, branch, webhook_secret_enc, access_token_enc, auto_deploy, deploy_slot, updated_at)
                values (?,?,?,?,?,?,?,?,?,now())
                on conflict (app_id) do update
                   set provider = excluded.provider,
                       repo_url = excluded.repo_url,
                       branch = excluded.branch,
                       webhook_secret_enc = excluded.webhook_secret_enc,
                       access_token_enc = excluded.access_token_enc,
                       auto_deploy = excluded.auto_deploy,
                       deploy_slot = excluded.deploy_slot,
                       updated_at = now()
                """).params(app.id(), ws, provider, repoUrl, branch, whEnc, tokEnc, autoDeploy, deploySlot).update();

        Map<String, Object> view = new LinkedHashMap<>(getGitCicd(ws, appId));
        if (!rawSecret.isEmpty()) {
            view.put("webhookSecretUpdated", true);
        } else if (old.whEnc() == null) {
            view.put("generatedWebhookSecret", crypto.decrypt(whEnc));
        }
        return view;
    }

    /** 手动触发 CI/CD 流水线（或本地模拟推送触发）。 */
    public Map<String, Object> triggerGitCicd(String ws, String appId, String commitMsg, String branchOverride) throws Exception {
        AppRow app = requireServiceApp(ws, appId);
        record RepoRow(String provider, String branch, String deploySlot) {}
        RepoRow repo = jdbc.sql("select provider, branch, deploy_slot from app_git_repo where app_id = ?")
                .param(app.id())
                .query((rs, i) -> new RepoRow(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional().orElse(new RepoRow("mock", "main", "preview"));

        String branch = (branchOverride != null && !branchOverride.isBlank()) ? branchOverride.trim() : repo.branch();
        String sha = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String msg = (commitMsg != null && !commitMsg.isBlank()) ? commitMsg.trim() : "Manual CI/CD trigger (" + branch + ")";
        return executeCicdDeploy(app, repo.deploySlot(), sha, msg);
    }

    /** 公开 Webhook 入口（支持 GitLab X-Gitlab-Token 与 GitHub HMAC-SHA256 X-Hub-Signature-256 严格验签）。 */
    public Map<String, Object> handleGitWebhook(String appId, String gitlabToken, String githubSig,
                                                String rawBody, JsonNode payload) throws Exception {
        AppRow app = apps.findById(appId).filter(AppRow::isService).orElseThrow(() -> ApiException.notFound("单体应用"));
        record RepoAuth(String provider, String branch, String whEnc, boolean autoDeploy, String deploySlot) {}
        RepoAuth repo = jdbc.sql("select provider, branch, webhook_secret_enc, auto_deploy, deploy_slot from app_git_repo where app_id = ?")
                .param(app.id())
                .query((rs, i) -> new RepoAuth(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5)))
                .optional().orElseThrow(() -> ApiException.notFound("Git CI/CD 配置"));

        if (repo.whEnc() == null || repo.whEnc().isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_UNCONFIGURED", "未配置 Webhook 密钥");
        }
        String secret = crypto.decrypt(repo.whEnc());
        boolean verified = false;
        if (gitlabToken != null && !gitlabToken.isBlank()) {
            verified = MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), gitlabToken.trim().getBytes(StandardCharsets.UTF_8));
        } else if (githubSig != null && githubSig.startsWith("sha256=")) {
            String expected = "sha256=" + hmacSha256Hex(secret, rawBody == null ? "" : rawBody);
            verified = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), githubSig.trim().getBytes(StandardCharsets.UTF_8));
        }
        if (!verified) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE", "Webhook 签名或 Token 校验失败");
        }

        String ref = payload == null ? "" : payload.path("ref").asText("");
        String pushedBranch = ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
        if (!pushedBranch.isEmpty() && !pushedBranch.equals(repo.branch())) {
            return Map.of("accepted", true, "skipped", true, "reason", "分支不匹配（收到 " + pushedBranch + "，监听 " + repo.branch() + "）");
        }
        if (!repo.autoDeploy()) {
            return Map.of("accepted", true, "skipped", true, "reason", "已关闭自动部署开关");
        }
        String sha = payload == null ? "" : payload.path("after").asText(payload.path("checkout_sha").asText(""));
        if (sha.isBlank()) sha = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String msg = "Webhook push (" + repo.branch() + ")";
        if (payload != null && payload.has("commits") && payload.path("commits").isArray() && !payload.path("commits").isEmpty()) {
            msg = payload.path("commits").get(0).path("message").asText(msg);
        }
        return executeCicdDeploy(app, repo.deploySlot(), sha, msg);
    }

    private Map<String, Object> executeCicdDeploy(AppRow app, String deploySlot, String sha, String msg) throws Exception {
        String vid = app.currentVersionId();
        if (vid == null || vid.isBlank()) {
            throw ApiException.conflict("NO_VERSION", "该应用尚无可部署的代码版本");
        }
        VersionRow ver = versions.find(vid).orElseThrow(() -> ApiException.notFound("版本"));
        try {
            if ("live".equalsIgnoreCase(deploySlot)) {
                manager.redeploy(app.workspaceId(), app.id(), ver.id());
            } else {
                manager.deployPreview(app, ver);
            }
            jdbc.sql("""
                    update app_git_repo
                       set last_commit_sha = ?, last_commit_msg = ?, last_sync_at = now(),
                           last_status = 'succeeded', last_error = null, updated_at = now()
                     where app_id = ?
                    """).params(sha, truncate(msg, 300), app.id()).update();
            return Map.of(
                    "accepted", true,
                    "skipped", false,
                    "slot", deploySlot,
                    "commitSha", sha,
                    "commitMsg", truncate(msg, 200),
                    "versionId", ver.id(),
                    "status", "succeeded"
            );
        } catch (Exception e) {
            jdbc.sql("""
                    update app_git_repo
                       set last_commit_sha = ?, last_commit_msg = ?, last_sync_at = now(),
                           last_status = 'failed', last_error = ?, updated_at = now()
                     where app_id = ?
                    """).params(sha, truncate(msg, 300), truncate(e.getMessage(), 1500), app.id()).update();
            throw e;
        }
    }

    // ==================== 4. 导出标准 Docker Compose + Helm Chart 部署包 ====================

    public byte[] exportDeployBundle(String ws, String appId) throws Exception {
        AppRow app = requireServiceApp(ws, appId);
        MonolithServiceManager.ServiceRow svc = manager.find(app.id()).orElseThrow(() -> ApiException.notFound("单体服务配置"));
        Map<String, Object> dfMap = manager.dockerfilePreview(ws, appId);
        String dockerfile = String.valueOf(dfMap.getOrDefault("content", ""));
        Map<String, Object> topoMap = getTopology(ws, appId);
        String topologyYaml = String.valueOf(topoMap.getOrDefault("yaml", ""));

        List<String> envKeys = jdbc.sql("select env_key from app_service_env where app_id = ? order by env_key")
                .param(app.id()).query(String.class).list();

        String slug = app.slug();
        String chartName = K8sServiceResourceFactory.resourceName(slug);
        int port = svc.port() > 0 ? svc.port() : 8080;
        String health = (svc.healthPath() == null || svc.healthPath().isBlank()) ? "/" : svc.healthPath();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            addZipEntry(zos, "Dockerfile", dockerfile);
            addZipEntry(zos, "zhiye.yaml", topologyYaml);
            addZipEntry(zos, ".env.example", buildEnvExample(svc, envKeys));
            addZipEntry(zos, "docker-compose.yml", buildDockerCompose(slug, svc, envKeys));
            addZipEntry(zos, "helm/Chart.yaml", buildHelmChartYaml(chartName, app.name()));
            addZipEntry(zos, "helm/values.yaml", buildHelmValuesYaml(chartName, svc, port, health));
            addZipEntry(zos, "helm/templates/deployment.yaml", buildHelmDeploymentTemplate());
            addZipEntry(zos, "helm/templates/service.yaml", buildHelmServiceTemplate());
            addZipEntry(zos, "helm/templates/networkpolicy.yaml", buildHelmNetPolTemplate());
            addZipEntry(zos, "helm/templates/pvc.yaml", buildHelmPvcTemplate());
            addZipEntry(zos, "README-DEPLOY.md", buildDeployReadme(slug, chartName, port));
        }
        return baos.toByteArray();
    }

    private static void addZipEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String buildEnvExample(MonolithServiceManager.ServiceRow svc, List<String> envKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 环境变量清单（仅含键名，不含生产明文密钥）\n");
        sb.append("PORT=").append(svc.port()).append("\n");
        sb.append("DATA_DIR=/data\n");
        if (svc.pgEnabled()) {
            sb.append("DATABASE_URL=postgresql://app_user:CHANGE_ME@postgres:5432/appdb\n");
        }
        for (String k : envKeys) {
            sb.append(k).append("=CHANGE_ME\n");
        }
        return sb.toString();
    }

    private static String buildDockerCompose(String slug, MonolithServiceManager.ServiceRow svc, List<String> envKeys) {
        int port = svc.port() > 0 ? svc.port() : 8080;
        StringBuilder sb = new StringBuilder();
        sb.append("services:\n");
        sb.append("  ").append(slug).append(":\n");
        sb.append("    build:\n      context: .\n      dockerfile: Dockerfile\n");
        sb.append("    ports:\n      - \"").append(port).append(":").append(port).append("\"\n");
        sb.append("    env_file:\n      - .env\n");
        sb.append("    environment:\n");
        sb.append("      PORT: \"").append(port).append("\"\n");
        sb.append("      DATA_DIR: \"/data\"\n");
        if (svc.pgEnabled()) {
            sb.append("      DATABASE_URL: \"postgresql://app:app@postgres:5432/appdb\"\n");
            sb.append("    depends_on:\n      - postgres\n");
        }
        if (svc.pvcEnabled()) {
            sb.append("    volumes:\n      - app_data:/data\n");
        }
        sb.append("    restart: unless-stopped\n");
        if (svc.pgEnabled()) {
            sb.append("\n  postgres:\n");
            sb.append("    image: postgres:16-alpine\n");
            sb.append("    environment:\n");
            sb.append("      POSTGRES_USER: app\n");
            sb.append("      POSTGRES_PASSWORD: app\n");
            sb.append("      POSTGRES_DB: appdb\n");
            sb.append("    volumes:\n      - pg_data:/var/lib/postgresql/data\n");
        }
        if (svc.pvcEnabled() || svc.pgEnabled()) {
            sb.append("\nvolumes:\n");
            if (svc.pvcEnabled()) sb.append("  app_data:\n");
            if (svc.pgEnabled()) sb.append("  pg_data:\n");
        }
        return sb.toString();
    }

    private static String buildHelmChartYaml(String chartName, String appName) {
        return """
                apiVersion: v2
                name: %s
                description: Zhiye Deploy Engine V2 generated Helm Chart for %s
                type: application
                version: 0.1.0
                appVersion: "1.0.0"
                """.formatted(chartName, appName.replace("\"", ""));
    }

    private String buildHelmValuesYaml(String chartName, MonolithServiceManager.ServiceRow svc, int port, String health) {
        String img = (svc.imageRef() != null && !svc.imageRef().isBlank()) ? svc.imageRef() : (chartName + ":latest");
        return """
                replicaCount: 1
                image:
                  repository: %s
                  pullPolicy: IfNotPresent
                service:
                  type: ClusterIP
                  port: 80
                  targetPort: %d
                healthPath: "%s"
                resources:
                  requests:
                    cpu: %dm
                    memory: %dMi
                  limits:
                    cpu: %dm
                    memory: %dMi
                persistence:
                  enabled: %s
                  size: %dGi
                networkPolicy:
                  enabled: true
                """.formatted(
                img, port, health,
                Math.max(50, svc.cpuMillicores() / 4), Math.max(64, svc.memoryMb() / 2),
                svc.cpuMillicores(), svc.memoryMb(),
                svc.pvcEnabled(), Math.max(1, svc.pvcSizeGi()));
    }

    private static String buildHelmDeploymentTemplate() {
        return """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: {{ .Release.Name }}
                spec:
                  replicas: {{ .Values.replicaCount }}
                  selector:
                    matchLabels:
                      app: {{ .Release.Name }}
                  template:
                    metadata:
                      labels:
                        app: {{ .Release.Name }}
                    spec:
                      automountServiceAccountToken: false
                      securityContext:
                        runAsNonRoot: true
                        runAsUser: 10001
                        runAsGroup: 10001
                        fsGroup: 10001
                      containers:
                        - name: app
                          image: {{ .Values.image.repository }}
                          imagePullPolicy: {{ .Values.image.pullPolicy }}
                          ports:
                            - containerPort: {{ .Values.service.targetPort }}
                          securityContext:
                            allowPrivilegeEscalation: false
                            capabilities:
                              drop: ["ALL"]
                          readinessProbe:
                            httpGet:
                              path: {{ .Values.healthPath }}
                              port: {{ .Values.service.targetPort }}
                            initialDelaySeconds: 3
                            periodSeconds: 5
                          resources:
                            {{- toYaml .Values.resources | nindent 12 }}
                """;
    }

    private static String buildHelmServiceTemplate() {
        return """
                apiVersion: v1
                kind: Service
                metadata:
                  name: {{ .Release.Name }}
                spec:
                  type: {{ .Values.service.type }}
                  selector:
                    app: {{ .Release.Name }}
                  ports:
                    - port: {{ .Values.service.port }}
                      targetPort: {{ .Values.service.targetPort }}
                """;
    }

    private static String buildHelmNetPolTemplate() {
        return """
                {{- if .Values.networkPolicy.enabled }}
                apiVersion: networking.k8s.io/v1
                kind: NetworkPolicy
                metadata:
                  name: {{ .Release.Name }}-netpol
                spec:
                  podSelector:
                    matchLabels:
                      app: {{ .Release.Name }}
                  policyTypes:
                    - Ingress
                    - Egress
                  egress:
                    - ports:
                        - protocol: UDP
                          port: 53
                        - protocol: TCP
                          port: 53
                {{- end }}
                """;
    }

    private static String buildHelmPvcTemplate() {
        return """
                {{- if .Values.persistence.enabled }}
                apiVersion: v1
                kind: PersistentVolumeClaim
                metadata:
                  name: {{ .Release.Name }}-data
                spec:
                  accessModes: ["ReadWriteOnce"]
                  resources:
                    requests:
                      storage: {{ .Values.persistence.size }}
                {{- end }}
                """;
    }

    private static String buildDeployReadme(String slug, String chartName, int port) {
        return """
                # %s 标准生产部署包（由织页 AI Deploy Engine V2 生成）

                ## 1. 本地 / 单机 Docker Compose 一键启动
                ```bash
                cp .env.example .env
                docker compose up --build -d
                curl http://127.0.0.1:%d/
                ```

                ## 2. 企业 Kubernetes / Helm 部署
                ```bash
                helm upgrade --install %s ./helm -n zhiye-apps --create-namespace
                ```
                """.formatted(slug, port, chartName);
    }

    private AppRow requireServiceApp(String ws, String appId) {
        AppRow app = apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
        if (!app.isService()) throw ApiException.validation("该应用不是单体后端托管类型");
        return app;
    }

    private static String hmacSha256Hex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(out);
    }

    private static String tail(String s, int maxChars) {
        if (s == null) return "";
        return s.length() <= maxChars ? s : s.substring(s.length() - maxChars);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
