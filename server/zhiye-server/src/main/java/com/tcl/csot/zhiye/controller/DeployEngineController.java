package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.service.admin.AdminAuthService;
import com.tcl.csot.zhiye.service.ops.OpsLogService;
import com.tcl.csot.zhiye.service.runtime.DeployLogBroadcaster;
import com.tcl.csot.zhiye.service.runtime.DeployOpsService;
import com.tcl.csot.zhiye.service.runtime.MonolithServiceManager;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Deploy Engine V2 REST API：
 * - 实时 SSE 构建/部署日志流（/deploy-logs/stream）
 * - 预览槽位一键 0 重建晋升线上（/promote）与秒级回滚（/releases/{id}/rollback）
 * - 结构化部署失败根因诊断（/diagnosis）与 AI 一键自愈修复（/auto-heal）
 * - 实时运行指标与 SLA 统计（/metrics）
 * - 在线 PostgreSQL 表浏览与受控 SQL 执行（/db/tables, /db/query）
 * - 声明式多服务拓扑 zhiye.yaml（/topology）
 * - GitLab / GitHub / 本地模拟 CI/CD 配置、手动触发与签名校验 Webhook（/git-cicd, /api/webhooks/git/{appId}）
 * - 导出标准 Docker Compose + Helm Chart 生产部署包（/export-bundle）
 */
@RestController
@Profile("api")
public class DeployEngineController {
    private final MonolithServiceManager manager;
    private final DeployOpsService ops;
    private final DeployLogBroadcaster broadcaster;
    private final AppRepository apps;
    private final CurrentPrincipal principal;
    private final com.tcl.csot.zhiye.config.ZhiyeProperties props;
    private final AdminAuthService adminAuth;
    private final OpsLogService opsLogs;

    public DeployEngineController(MonolithServiceManager manager, DeployOpsService ops,
                                  DeployLogBroadcaster broadcaster, AppRepository apps,
                                  CurrentPrincipal principal,
                                  com.tcl.csot.zhiye.config.ZhiyeProperties props,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false) AdminAuthService adminAuth,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false) OpsLogService opsLogs) {
        this.manager = manager;
        this.ops = ops;
        this.broadcaster = broadcaster;
        this.apps = apps;
        this.principal = principal;
        this.props = props;
        this.adminAuth = adminAuth;
        this.opsLogs = opsLogs;
    }

    private String ws() { return principal.workspaceId(); }

    // ---------- 实时 SSE 部署日志流 ----------

    @GetMapping(value = "/api/apps/{appId}/service/deploy-logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDeployLogs(@PathVariable String appId) {
        AppRow app = apps.find(ws(), appId).orElseThrow(() -> ApiException.notFound("应用"));
        if (!app.isService()) throw ApiException.validation("该应用不是单体后端托管应用");
        return broadcaster.subscribe(appId);
    }

    // ---------- 预览 0 重建晋升 & 秒级回滚 ----------

    @PostMapping("/api/apps/{appId}/service/promote")
    public Map<String, Object> promotePreview(@PathVariable String appId) throws Exception {
        if (props.publish().reviewRequired()) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "PUBLISH_REVIEW_REQUIRED",
                    "预览晋升上线需先点击右上角「发布」提交《Vibe Coding 应用登记表》并经管理员审核通过");
        }
        return manager.promotePreview(ws(), appId);
    }

    @PostMapping("/api/apps/{appId}/service/releases/{releaseId}/rollback")
    public Map<String, Object> rollback(@PathVariable String appId, @PathVariable String releaseId) throws Exception {
        return manager.rollbackToRelease(ws(), appId, releaseId);
    }

    // ---------- 结构化诊断 & AI 自愈 ----------

    @GetMapping("/api/apps/{appId}/service/diagnosis")
    public Map<String, Object> diagnosis(@PathVariable String appId) {
        return manager.diagnosisView(ws(), appId);
    }

    @PostMapping("/api/apps/{appId}/service/auto-heal")
    public Map<String, Object> autoHeal(@PathVariable String appId) {
        return ops.triggerAutoHeal(ws(), appId);
    }

    // ---------- 实时运行指标 ----------

    @GetMapping("/api/apps/{appId}/service/metrics")
    public Map<String, Object> metrics(@PathVariable String appId) {
        return manager.metricsView(ws(), appId);
    }

    // ---------- 在线 PostgreSQL 表浏览 / 只读预览 / 管理员受控 SQL 控制台 ----------

    @GetMapping("/api/apps/{appId}/service/db/tables")
    public Map<String, Object> dbTables(@PathVariable String appId) {
        return manager.listDbTables(ws(), appId);
    }

    @GetMapping("/api/apps/{appId}/service/db/tables/{table}/preview")
    public Map<String, Object> dbTablePreview(@PathVariable String appId,
                                              @PathVariable String table,
                                              @RequestParam(defaultValue = "50") int limit) {
        return manager.previewDbTable(ws(), appId, table, limit);
    }

    @PostMapping("/api/apps/{appId}/service/db/query")
    public Map<String, Object> dbQuery(@PathVariable String appId,
                                       @RequestBody JsonNode body,
                                       HttpServletRequest req) {
        if (adminAuth == null || !adminAuth.isAdmin(req, ws())) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "ADMIN_ONLY",
                    "为保障共享数据库安全，自由 SQL 执行仅限平台管理员使用；普通用户请通过上方数据表进行结构浏览与只读预览");
        }
        String actor = adminAuth.requireAdminActor(req, ws());
        String sql = body == null ? "" : body.path("sql").asText("");
        boolean allowWrite = body != null && body.path("allowWrite").asBoolean(false);
        int limit = body == null ? 100 : body.path("limit").asInt(100);
        String sqlExcerpt = sql.length() > 500 ? sql.substring(0, 500) + "…" : sql;
        try {
            Map<String, Object> res = manager.queryDb(ws(), appId, sql, allowWrite, limit);
            if (opsLogs != null) {
                opsLogs.recordOpsAudit(actor, "SERVICE_DB_SQL", "app", appId, Map.of(
                        "ok", true,
                        "allowWrite", allowWrite,
                        "schema", String.valueOf(res.getOrDefault("schema", "")),
                        "durationMs", res.getOrDefault("durationMs", 0),
                        "sql", sqlExcerpt
                ));
            }
            return res;
        } catch (RuntimeException e) {
            if (opsLogs != null) {
                opsLogs.recordOpsAudit(actor, "SERVICE_DB_SQL", "app", appId, Map.of(
                        "ok", false,
                        "allowWrite", allowWrite,
                        "error", String.valueOf(e.getMessage()),
                        "sql", sqlExcerpt
                ));
            }
            throw e;
        }
    }

    // ---------- 多服务声明式拓扑 zhiye.yaml ----------

    @GetMapping("/api/apps/{appId}/service/topology")
    public Map<String, Object> getTopology(@PathVariable String appId) {
        return ops.getTopology(ws(), appId);
    }

    @PutMapping("/api/apps/{appId}/service/topology")
    public Map<String, Object> saveTopology(@PathVariable String appId, @RequestBody JsonNode body) {
        String yaml = body == null ? "" : body.path("yaml").asText("");
        return ops.saveTopology(ws(), appId, yaml);
    }

    // ---------- GitLab / GitHub CI/CD ----------

    @GetMapping("/api/apps/{appId}/service/git-cicd")
    public Map<String, Object> getGitCicd(@PathVariable String appId) {
        return ops.getGitCicd(ws(), appId);
    }

    @PutMapping("/api/apps/{appId}/service/git-cicd")
    public Map<String, Object> saveGitCicd(@PathVariable String appId, @RequestBody JsonNode body) {
        return ops.saveGitCicd(ws(), appId, body == null ? Json.obj() : body);
    }

    @PostMapping("/api/apps/{appId}/service/git-cicd/trigger")
    public Map<String, Object> triggerGitCicd(@PathVariable String appId, @RequestBody(required = false) JsonNode body) throws Exception {
        String msg = body == null ? null : body.path("commitMessage").asText(null);
        String branch = body == null ? null : body.path("branch").asText(null);
        return ops.triggerGitCicd(ws(), appId, msg, branch);
    }

    /** 公开 Webhook 端点（通过 X-Gitlab-Token 或 X-Hub-Signature-256 严格验签，无需浏览器 Session Cookie）。 */
    @PostMapping("/api/webhooks/git/{appId}")
    public Map<String, Object> gitWebhook(@PathVariable String appId,
                                          @RequestHeader(value = "X-Gitlab-Token", required = false) String gitlabToken,
                                          @RequestHeader(value = "X-Hub-Signature-256", required = false) String githubSig,
                                          @RequestBody(required = false) String rawBody) throws Exception {
        JsonNode payload = (rawBody == null || rawBody.isBlank()) ? Json.obj() : Json.read(rawBody);
        return ops.handleGitWebhook(appId, gitlabToken, githubSig, rawBody, payload);
    }

    // ---------- 标准 Docker Compose + Helm Chart 部署包导出 ----------

    @GetMapping("/api/apps/{appId}/service/export-bundle")
    public ResponseEntity<byte[]> exportBundle(@PathVariable String appId) throws Exception {
        AppRow app = apps.find(ws(), appId).orElseThrow(() -> ApiException.notFound("应用"));
        byte[] zip = ops.exportDeployBundle(ws(), appId);
        String filename = app.slug().replaceAll("[^a-zA-Z0-9._-]", "-") + "-deploy-bundle.zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(zip);
    }
}
