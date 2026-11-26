package com.tcl.csot.zhiye.service.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcl.csot.zhiye.config.ServiceRuntimeProperties;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.util.LogRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 单体后端应用托管核心编排服务（覆盖一期 + 二期全能力）：
 * - Python / Node.js / Java / Go / Dockerfile / 前后端一体自动探测与标准 Dockerfile 生成
 * - 部署、启停、重启、休眠（Scale-to-Zero）、按需透明唤醒
 * - 密文环境变量管理（AES-256-GCM）
 * - 独立 PostgreSQL Schema 自动分配（DATABASE_URL / JDBC_DATABASE_URL）与 /data 持久化卷配置
 * - 闲置自动休眠定时回收与发布流水日志
 */
@Service
public class MonolithServiceManager {
    private static final Logger log = LoggerFactory.getLogger(MonolithServiceManager.class);
    private static final Pattern ENV_KEY_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    private static final Set<String> RESERVED_ENV = Set.of("PORT", "SERVER_PORT", "HOST", "DATA_DIR", "STATIC_DIR");

    public record ServiceRow(
            String appId, String runtime, String framework, String frontendDir, String backendDir,
            String buildCmd, String startCmd, int port, String healthPath, int memoryMb, int cpuMillicores,
            int idleSleepMin, boolean pgEnabled, String pgSchema, String pgRole, String pgPasswordEnc,
            boolean pvcEnabled, int pvcSizeGi, String status, String desiredVersionId, String runningVersionId,
            String imageRef, String endpointUrl, Instant lastAccessAt, Instant lastStartedAt,
            String lastError, String buildLog, Instant createdAt, Instant updatedAt,
            String previewVersionId, String previewEndpointUrl, String previewStatus
    ) {
        static ServiceRow map(ResultSet rs, int i) throws SQLException {
            return new ServiceRow(
                    rs.getString("app_id"), rs.getString("runtime"), rs.getString("framework"),
                    rs.getString("frontend_dir"), rs.getString("backend_dir"), rs.getString("build_cmd"),
                    rs.getString("start_cmd"), rs.getInt("port"), rs.getString("health_path"),
                    rs.getInt("memory_mb"), rs.getInt("cpu_millicores"), rs.getInt("idle_sleep_min"),
                    rs.getBoolean("pg_enabled"), rs.getString("pg_schema"), rs.getString("pg_role"),
                    rs.getString("pg_password_enc"), rs.getBoolean("pvc_enabled"), rs.getInt("pvc_size_gi"),
                    rs.getString("status"), rs.getString("desired_version_id"), rs.getString("running_version_id"),
                    rs.getString("image_ref"), rs.getString("endpoint_url"), Db.ts(rs, "last_access_at"),
                    Db.ts(rs, "last_started_at"), rs.getString("last_error"), rs.getString("build_log"),
                    Db.ts(rs, "created_at"), Db.ts(rs, "updated_at"),
                    rs.getString("preview_version_id"), rs.getString("preview_endpoint_url"), rs.getString("preview_status"));
        }
    }

    private final JdbcClient jdbc;
    private final AppRepository apps;
    private final VersionRepository versions;
    private final GitService git;
    private final ServiceRuntimeProvider provider;
    private final ServiceDatabaseService dbService;
    private final ServiceCrypto crypto;
    private final ServiceRuntimeProperties props;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.ops.PlatformLogService platformLogs;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeployDiagnosisService diagnosisService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DeployLogBroadcaster deployLogBroadcaster;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.connector.EnterpriseConnectorService enterpriseConnectors;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.util.ServerPort serverPort;
    private final ConcurrentHashMap<String, Object> appLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastTouchMs = new ConcurrentHashMap<>();

    public MonolithServiceManager(JdbcClient jdbc, AppRepository apps, VersionRepository versions, GitService git,
                                  ServiceRuntimeProvider provider, ServiceDatabaseService dbService,
                                  ServiceCrypto crypto, ServiceRuntimeProperties props) {
        this.jdbc = jdbc;
        this.apps = apps;
        this.versions = versions;
        this.git = git;
        this.provider = provider;
        this.dbService = dbService;
        this.crypto = crypto;
        this.props = props;
    }

    public Optional<ServiceRow> find(String appId) {
        return jdbc.sql("select * from app_service where app_id = ?").param(appId).query(ServiceRow::map).optional();
    }

    public Map<String, Object> summaryForApp(String appId) {
        return find(appId).map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runtime", s.runtime());
            m.put("framework", s.framework());
            m.put("status", effectiveStatus(s));
            m.put("port", s.port());
            m.put("provider", provider.name());
            m.put("pgEnabled", s.pgEnabled());
            m.put("pvcEnabled", s.pvcEnabled());
            m.put("idleSleepMin", s.idleSleepMin());
            m.put("lastStartedAt", Db.iso(s.lastStartedAt()));
            m.put("lastAccessAt", Db.iso(s.lastAccessAt()));
            return m;
        }).orElse(null);
    }

    /** 导入或新建单体应用版本后初始化配置并立刻构建部署到线上槽位。 */
    public ServiceRow initializeAndDeploy(AppRow app, VersionRow ver) throws Exception {
        synchronized (lockFor(app.id())) {
            ensureInitializedLocked(app, ver);
            ServiceRow row = deployLocked(app, ver);
            clearPreviewSlotLocked(app);
            return row;
        }
    }

    /** 正式发布到线上槽位（Deploy Engine V2）：若预览槽位已构建通过同一版本，自动走 0 重建晋升切流；否则正常构建切流。 */
    public ServiceRow publishDeploy(AppRow app, VersionRow ver) throws Exception {
        synchronized (lockFor(app.id())) {
            ensureInitializedLocked(app, ver);
            ServiceRow row = deployOrPromoteLocked(app, ver);
            clearPreviewSlotLocked(app);
            return row;
        }
    }

    /** 手动一键将已验证的预览槽位晋升至线上（0 重建零停机切流）。 */
    public Map<String, Object> promotePreview(String ws, String appId) throws Exception {
        AppRow app = requireServiceApp(ws, appId);
        ServiceRow cur = find(app.id()).orElseThrow(() -> ApiException.notFound("单体服务配置"));
        String prevVid = cur.previewVersionId();
        if (prevVid == null || prevVid.isBlank()) {
            throw ApiException.conflict("NO_PREVIEW_SLOT", "当前没有可晋升的预览槽位版本，请先预览或重新部署");
        }
        VersionRow ver = versions.find(prevVid).filter(v -> v.appId().equals(app.id()))
                .orElseThrow(() -> ApiException.notFound("预览版本"));
        synchronized (lockFor(app.id())) {
            deployOrPromoteLocked(app, ver);
            clearPreviewSlotLocked(app);
        }
        return statusView(ws, appId);
    }

    /** 秒级回滚到历史发布记录（命中不可变产物快照或已有镜像时 0 重建零停机切流）。 */
    public Map<String, Object> rollbackToRelease(String ws, String appId, String releaseId) throws Exception {
        AppRow app = requireServiceApp(ws, appId);
        record RelTarget(String id, String versionId, String status, String imageRef, String artifactDir) {}
        RelTarget rel = jdbc.sql("select id, version_id, status, image_ref, artifact_dir from app_service_release where id = ? and app_id = ?")
                .params(releaseId, app.id())
                .query((rs, i) -> new RelTarget(rs.getString("id"), rs.getString("version_id"),
                        rs.getString("status"), rs.getString("image_ref"), rs.getString("artifact_dir")))
                .optional().orElseThrow(() -> ApiException.notFound("发布记录"));
        if (!"succeeded".equals(rel.status())) {
            throw ApiException.validation("仅支持回滚到构建成功的发布记录（当前状态=" + rel.status() + "）");
        }
        if (rel.versionId() == null || rel.versionId().isBlank()) {
            throw ApiException.validation("该发布记录缺少版本信息，无法回滚");
        }
        VersionRow ver = versions.find(rel.versionId()).filter(v -> v.appId().equals(app.id()))
                .orElseThrow(() -> ApiException.notFound("目标版本"));
        synchronized (lockFor(app.id())) {
            deployWithPrebuiltCandidateLocked(app, ver, rel.id(), rel.imageRef(), rel.artifactDir(), "rollback");
        }
        return statusView(ws, appId);
    }

    /**
     * 预览槽位部署（FS-04 + Deploy Engine V2）：用于 AI 生成/修改、代码在线编辑、历史版本恢复。
     * - 若应用已发布 V1，绝不改动或中断线上 V1 槽位，仅独立更新预览槽位（appId-prev）；
     * - 记录不可变预览构建产物快照（artifact_dir / image_ref），点击「发布」时可直接 0 重建晋升上线。
     */
    public ServiceRow deployPreview(AppRow app, VersionRow ver) throws Exception {
        synchronized (lockFor(app.id())) {
            ensureInitializedLocked(app, ver);
            ServiceRow cur = find(app.id()).orElseThrow();
            enforceRunQuota(app, cur);
            if (cur.pgEnabled()) {
                var p = dbService.ensureSchema(app.id(), cur.pgSchema(), cur.pgRole(), cur.pgPasswordEnc());
                jdbc.sql("update app_service set pg_schema=?, pg_role=?, pg_password_enc=? where app_id=?")
                        .params(p.schema(), p.role(), p.passwordEnc(), app.id()).update();
                cur = find(app.id()).orElseThrow();
            }
            String relId = Ids.newId("srel");
            long t0 = System.currentTimeMillis();
            jdbc.sql("insert into app_service_release (id, app_id, version_id, slot, status) values (?,?,?,?,?)")
                    .params(relId, app.id(), ver.id(), "preview", "building").update();
            if (deployLogBroadcaster != null) deployLogBroadcaster.reset(app.id(), "preview", ver.id());

            String prevAppId = previewAppId(app.id());
            String prevSlug = previewSlug(app.slug());
            Path tempSrc = Files.createTempDirectory("zhiye-svc-prev-");
            try {
                git.checkout(app.id(), ver.gitCommit(), tempSrc, "service");
                ServiceRuntimeProvider.DeploySpec spec = buildDeploySpec(app, cur, ver, tempSrc, prevAppId, prevSlug);
                ServiceRuntimeProvider.DeployResult res = provider.deploy(spec);
                long durationMs = System.currentTimeMillis() - t0;
                boolean hasLive = app.publishedVersionId() != null && !app.publishedVersionId().isBlank() && cur.runningVersionId() != null;
                if (hasLive) {
                    jdbc.sql("update app_service set preview_version_id = ?, preview_endpoint_url = ?, preview_status = 'running', "
                                    + "desired_version_id = ?, build_log = ?, last_error = null, updated_at = now() where app_id = ?")
                            .params(ver.id(), res.endpointUrl(), ver.id(), truncate(res.buildLog(), 64_000), app.id()).update();
                } else {
                    jdbc.sql("update app_service set status = 'running', preview_version_id = ?, preview_endpoint_url = ?, preview_status = 'running', "
                                    + "desired_version_id = ?, build_log = ?, last_error = null, last_started_at = now(), last_access_at = now(), updated_at = now() where app_id = ?")
                            .params(ver.id(), res.endpointUrl(), ver.id(), truncate(res.buildLog(), 64_000), app.id()).update();
                }
                jdbc.sql("update app_service_release set status = 'succeeded', image_ref = ?, artifact_dir = ?, duration_ms = ?, build_log = ?, finished_at = now() where id = ?")
                        .params(res.imageRef(), res.artifactDir(), durationMs, truncate(res.buildLog(), 64_000), relId).update();
                logEvent(true, "SERVICE_PREVIEW_DEPLOY", app, "单体服务预览槽位更新成功：" + app.name() + "（V" + ver.number() + "，线上版本不受影响）",
                        truncate(res.buildLog(), 8_000), Map.of("runtime", cur.runtime(), "versionId", ver.id(), "hasLive", hasLive, "releaseId", relId, "durationMs", durationMs));
                return find(app.id()).orElseThrow();
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - t0;
                String msg = truncate(e.getMessage(), 4000);
                var diag = diagnoseFailure(msg, msg, cur, app);
                jdbc.sql("update app_service_release set status = 'failed', error = ?, build_log = ?, duration_ms = ?, error_category = ?, diagnosis_json = ?::jsonb, finished_at = now() where id = ?")
                        .params(msg, msg, durationMs, diag == null ? null : diag.category(), diag == null ? null : Json.write(diag.toMap()), relId).update();
                boolean hasLive = app.publishedVersionId() != null && !app.publishedVersionId().isBlank() && cur.runningVersionId() != null;
                if (hasLive) {
                    jdbc.sql("update app_service set preview_status = 'failed', last_error = ?, build_log = ?, updated_at = now() where app_id = ?")
                            .params(msg, msg, app.id()).update();
                } else {
                    jdbc.sql("update app_service set status = 'failed', preview_status = 'failed', last_error = ?, build_log = ?, updated_at = now() where app_id = ?")
                            .params(msg, msg, app.id()).update();
                }
                logEvent(false, "SERVICE_PREVIEW_DEPLOY", app, "单体服务预览部署失败：" + app.name() + " · " + truncate(msg, 180),
                        msg, Map.of("runtime", cur.runtime(), "versionId", ver.id(), "hasLive", hasLive, "releaseId", relId));
                throw e;
            } finally {
                GitService.deleteRecursively(tempSrc);
            }
        }
    }

    private void ensureInitializedLocked(AppRow app, VersionRow ver) throws Exception {
        ServiceRow existing = find(app.id()).orElse(null);
        if (existing != null) return;
        checkWorkspaceServiceCount(app.workspaceId(), app.id());
        ServiceRuntimeDetector.Spec detected = detectFromCommit(app.id(), ver.gitCommit())
                .orElse(new ServiceRuntimeDetector.Spec("node", app.framework(), "", "", "", "npm start", 8080, "/", 512, 500));
        int initMem = Math.min(Math.max(128, detected.memoryMb()), props.maxMemoryMb());
        int initCpu = Math.min(Math.max(100, detected.cpuMillicores()), props.maxCpuMillicores());
        int initIdle = props.allowNeverSleep()
                ? Math.max(0, props.defaultIdleSleepMinutes())
                : Math.max(props.minIdleSleepMinutes(), props.defaultIdleSleepMinutes());
        boolean autoPg = needsManagedPostgres(app.id(), ver.gitCommit());
        jdbc.sql("insert into app_service (app_id, runtime, framework, frontend_dir, backend_dir, build_cmd, start_cmd, port, health_path, memory_mb, cpu_millicores, idle_sleep_min, pg_enabled, status, desired_version_id) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
                .params(app.id(), detected.runtime(), detected.framework(), detected.frontendDir(), detected.backendDir(),
                        detected.buildCmd(), detected.startCmd(), detected.port(), detected.healthPath(),
                        initMem, initCpu, initIdle, autoPg, "building", ver.id())
                .update();
        jdbc.sql("update app set framework = ?, updated_at = now() where id = ?")
                .params(detected.framework(), app.id()).update();
    }

    /** 自动检测全栈项目是否使用 PostgreSQL（pg/psycopg/spring-postgresql/DATABASE_URL），是则自动开通托管 PG Schema。 */
    private boolean needsManagedPostgres(String appId, String commit) {
        for (String p : List.of("package.json", "backend/package.json", "requirements.txt", "backend/requirements.txt",
                "pom.xml", "backend/pom.xml", "go.mod", "server/index.mjs", "server/index.js", "server/db.mjs")) {
            try {
                Optional<byte[]> raw = git.readFile(appId, commit, p);
                if (raw.isEmpty()) continue;
                String txt = new String(raw.get(), StandardCharsets.UTF_8);
                if (p.endsWith("package.json") && txt.contains("\"pg\"")) return true;
                if (p.endsWith("requirements.txt") && (txt.contains("psycopg") || txt.contains("asyncpg"))) return true;
                if (p.endsWith("pom.xml") && txt.contains("postgresql")) return true;
                if (p.endsWith("go.mod") && (txt.contains("pgx") || txt.contains("lib/pq"))) return true;
                if (txt.contains("DATABASE_URL") || txt.contains("JDBC_DATABASE_URL")) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void clearPreviewSlotLocked(AppRow app) {
        try {
            provider.stop(previewAppId(app.id()), previewSlug(app.slug()));
        } catch (Exception ignored) {}
        jdbc.sql("update app_service set preview_version_id = null, preview_endpoint_url = null, preview_status = null where app_id = ?")
                .param(app.id()).update();
    }

    private static String previewAppId(String appId) { return appId + "-prev"; }
    private static String previewSlug(String slug) { return slug + "-prev"; }

    /** 工作台预览代理入口：优先路由到当前版本的预览槽位；若当前版本与线上已发布版本一致，直接复用线上槽位。 */
    public String ensurePreviewAwake(String appId) throws Exception {
        ServiceRow cur = find(appId).orElseThrow(() -> new IllegalStateException("该应用尚未配置单体运行托管"));
        AppRow app = apps.findById(appId).orElseThrow(() -> new IllegalStateException("应用不存在"));
        String targetVid = app.currentVersionId() != null ? app.currentVersionId() : cur.desiredVersionId();
        // 如果当前版本就是已发布且正在运行的线上版本，直接复用线上槽位
        if (targetVid != null && targetVid.equals(cur.runningVersionId()) && cur.previewEndpointUrl() == null) {
            return ensureAwake(appId, false);
        }
        String prevAppId = previewAppId(app.id());
        String prevSlug = previewSlug(app.slug());
        if ("running".equals(cur.previewStatus()) && cur.previewEndpointUrl() != null && !cur.previewEndpointUrl().isBlank()
                && Objects.equals(targetVid, cur.previewVersionId()) && provider.isRunning(prevAppId, prevSlug)) {
            return cur.previewEndpointUrl();
        }
        if ("stopped".equals(cur.status())) {
            throw new IllegalStateException("应用已手动停止，请在「服务托管」点击启动或重新部署");
        }
        VersionRow ver = targetVid == null ? null : versions.find(targetVid).orElse(null);
        if (ver == null) return ensureAwake(appId, false);
        ServiceRow updated = deployPreview(app, ver);
        return updated.previewEndpointUrl();
    }

    /** 对已有单体应用重新构建并部署指定版本（默认当前版本）。 */
    public Map<String, Object> redeploy(String ws, String appId, String versionId) throws Exception {
        AppRow app = requireServiceApp(ws, appId);
        String vid = (versionId == null || versionId.isBlank()) ? app.currentVersionId() : versionId;
        if (vid == null) throw ApiException.conflict("INVALID_PHASE", "应用还没有可部署的版本");
        VersionRow ver = versions.find(vid).filter(v -> v.appId().equals(app.id()))
                .orElseThrow(() -> ApiException.notFound("版本"));
        synchronized (lockFor(app.id())) {
            if (find(app.id()).isEmpty()) {
                initializeAndDeploy(app, ver);
            } else {
                deployLocked(app, ver);
            }
        }
        return statusView(ws, appId);
    }

    /** 网关请求进入时调用：仅当处于休眠态（sleeping）或进程意外退出时透明拉起；手动停止（stopped）绝不被访客流量唤醒。 */
    public String ensureAwake(String appId) throws Exception {
        return ensureAwake(appId, false);
    }

    /** 显式启动或网关唤醒：allowStopped=true 仅由作者「启动」或管理员「启动」调用。 */
    public String ensureAwake(String appId, boolean allowStopped) throws Exception {
        ServiceRow cur = find(appId).orElseThrow(() -> new IllegalStateException("该应用尚未配置单体运行托管"));
        if (!allowStopped && "stopped".equals(cur.status())) {
            throw new IllegalStateException("应用已手动停止，暂不对外提供服务（请由作者在工作台「服务托管」点击启动或重新部署）");
        }
        if ("running".equals(cur.status()) && cur.endpointUrl() != null && !cur.endpointUrl().isBlank()) {
            AppRow app = apps.findById(appId).orElse(null);
            if (app != null && provider.isRunning(appId, app.slug())) {
                return cur.endpointUrl();
            }
        }
        synchronized (lockFor(appId)) {
            ServiceRow s = find(appId).orElseThrow(() -> new IllegalStateException("该应用尚未配置单体运行托管"));
            AppRow app = apps.findById(appId).orElseThrow(() -> new IllegalStateException("应用不存在"));
            if (!allowStopped && "stopped".equals(s.status())) {
                throw new IllegalStateException("应用已手动停止，暂不对外提供服务（请由作者在工作台「服务托管」点击启动或重新部署）");
            }
            if ("running".equals(s.status()) && s.endpointUrl() != null && provider.isRunning(appId, app.slug())) {
                return s.endpointUrl();
            }
            String vid = s.runningVersionId() != null ? s.runningVersionId() : app.publishedVersionId();
            if (vid == null) vid = app.currentVersionId();
            VersionRow ver = vid == null ? null : versions.find(vid).orElse(null);
            if (ver == null) throw new IllegalStateException("找不到可唤醒的应用版本");
            enforceRunQuota(app, s);

            Path tempSrc = Files.createTempDirectory("zhiye-svc-wake-");
            try {
                git.checkout(app.id(), ver.gitCommit(), tempSrc, "service");
                ServiceRuntimeProvider.DeploySpec spec = buildDeploySpec(app, s, ver, tempSrc);
                String endpoint = provider.wake(spec, s.imageRef());
                jdbc.sql("update app_service set status = 'running', endpoint_url = ?, last_started_at = now(), last_access_at = now(), last_error = null, updated_at = now() where app_id = ?")
                        .params(endpoint, appId).update();
                logEvent(true, "SERVICE_WAKE", app, "单体服务已自动唤醒：" + app.name(), null,
                        Map.of("runtime", s.runtime(), "provider", provider.name(), "slug", app.slug()));
                return endpoint;
            } catch (Exception e) {
                jdbc.sql("update app_service set status = 'failed', last_error = ?, updated_at = now() where app_id = ?")
                        .params(truncate(e.getMessage(), 2000), appId).update();
                logEvent(false, "SERVICE_WAKE", app, "单体服务唤醒失败：" + app.name() + " · " + truncate(e.getMessage(), 180),
                        truncate(e.getMessage(), 4000), Map.of("runtime", s.runtime(), "provider", provider.name()));
                throw e;
            } finally {
                GitService.deleteRecursively(tempSrc);
            }
        }
    }

    /** 节流记录访问时间（用于闲置缩容到 0 判断，每应用最多 30 秒落一次库）。 */
    public void recordAccess(String appId) {
        long now = System.currentTimeMillis();
        Long prev = lastTouchMs.get(appId);
        if (prev != null && now - prev < 30_000L) return;
        lastTouchMs.put(appId, now);
        try {
            jdbc.sql("update app_service set last_access_at = now() where app_id = ?").param(appId).update();
        } catch (Exception ignored) {}
    }

    /** 定时扫描：1) 超过 idle_sleep_min 分钟无访问的线上单体服务缩容到 0；2) 超过 preview_idle_sleep_min（默认 30 分钟）的预览槽位单独缩容休眠，节省双槽位内存。 */
    @Scheduled(fixedDelayString = "${zhiye.service-runtime.idle-sweep-ms:60000}")
    public void sleepIdleServices() {
        List<ServiceRow> idle = jdbc.sql(
                "select * from app_service where status = 'running' and idle_sleep_min > 0 "
                        + "and coalesce(last_access_at, last_started_at, updated_at) < now() - (idle_sleep_min * interval '1 minute')")
                .query(ServiceRow::map).list();
        for (ServiceRow s : idle) {
            try {
                apps.findById(s.appId()).ifPresent(app -> {
                    synchronized (lockFor(app.id())) {
                        try {
                            provider.sleep(app.id(), app.slug());
                            jdbc.sql("update app_service set status = 'sleeping', updated_at = now() where app_id = ? and status = 'running'")
                                    .param(app.id()).update();
                            log.info("单体应用闲置自动休眠（缩容到 0）app={} slug={} idleMin={}", app.id(), app.slug(), s.idleSleepMin());
                            logEvent(true, "SERVICE_IDLE_SLEEP", app, "单体服务闲置自动休眠：" + app.name() + "（" + s.idleSleepMin() + " 分钟无访问）",
                                    null, Map.of("runtime", s.runtime(), "idleSleepMin", s.idleSleepMin(), "provider", provider.name()));
                        } catch (Exception e) {
                            log.warn("闲置休眠失败 app={}: {}", app.id(), e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                log.warn("闲置休眠扫描异常 app={}: {}", s.appId(), e.getMessage());
            }
        }
        // 工作台预览槽位独立短休眠（默认 30 分钟）：不影响线上正式版，避免双槽位长期占用双倍内存
        int previewIdleMin = 30;
        List<ServiceRow> idlePreviews = jdbc.sql(
                "select * from app_service where preview_status = 'running' "
                        + "and updated_at < now() - (? * interval '1 minute')")
                .param(previewIdleMin)
                .query(ServiceRow::map).list();
        for (ServiceRow s : idlePreviews) {
            try {
                apps.findById(s.appId()).ifPresent(app -> {
                    synchronized (lockFor(app.id())) {
                        try {
                            provider.sleep(previewAppId(app.id()), previewSlug(app.slug()));
                            jdbc.sql("update app_service set preview_status = 'sleeping' where app_id = ? and preview_status = 'running'")
                                    .param(app.id()).update();
                            log.info("全栈预览槽位闲置自动休眠（不影响线上版）app={} idleMin={}", app.id(), previewIdleMin);
                        } catch (Exception e) {
                            log.warn("预览槽位休眠失败 app={}: {}", app.id(), e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                log.warn("预览槽位休眠扫描异常 app={}: {}", s.appId(), e.getMessage());
            }
        }
    }

    public Map<String, Object> start(String ws, String appId) throws Exception {
        AppRow app = requireServiceApp(ws, appId);
        ensureAwake(app.id(), true);
        return statusView(ws, appId);
    }

    public Map<String, Object> stop(String ws, String appId) throws Exception {
        AppRow app = requireServiceApp(ws, appId);
        stopInternal(app);
        return statusView(ws, appId);
    }

    public Map<String, Object> sleep(String ws, String appId) throws Exception {
        AppRow app = requireServiceApp(ws, appId);
        sleepInternal(app);
        return statusView(ws, appId);
    }

    /** 管理员治理操作：按 appId 强行休眠（缩容到 0） */
    public Map<String, Object> adminSleep(String appId) throws Exception {
        AppRow app = apps.findById(appId).filter(AppRow::isService).orElseThrow(() -> ApiException.notFound("单体应用"));
        sleepInternal(app);
        return statusView(app.workspaceId(), appId);
    }

    /** 管理员治理操作：按 appId 强行停止 */
    public Map<String, Object> adminStop(String appId) throws Exception {
        AppRow app = apps.findById(appId).filter(AppRow::isService).orElseThrow(() -> ApiException.notFound("单体应用"));
        stopInternal(app);
        return statusView(app.workspaceId(), appId);
    }

    /** 管理员治理操作：按 appId 唤醒启动 */
    public Map<String, Object> adminStart(String appId) throws Exception {
        AppRow app = apps.findById(appId).filter(AppRow::isService).orElseThrow(() -> ApiException.notFound("单体应用"));
        ensureAwake(app.id(), true);
        return statusView(app.workspaceId(), appId);
    }

    public boolean isRunningNow(String appId, String slug) {
        return provider.isRunning(appId, slug);
    }

    public String providerName() {
        return provider.name();
    }

    private void stopInternal(AppRow app) {
        synchronized (lockFor(app.id())) {
            try { provider.stop(app.id(), app.slug()); } catch (Exception e) { log.warn("停止单体服务异常 app={}: {}", app.id(), e.getMessage()); }
            clearPreviewSlotLocked(app);
            jdbc.sql("update app_service set status = 'stopped', updated_at = now() where app_id = ?").param(app.id()).update();
            logEvent(true, "SERVICE_STOP", app, "单体服务已停止：" + app.name(), null, Map.of("provider", provider.name()));
        }
    }

    private void sleepInternal(AppRow app) {
        synchronized (lockFor(app.id())) {
            try { provider.sleep(app.id(), app.slug()); } catch (Exception e) { log.warn("休眠单体服务异常 app={}: {}", app.id(), e.getMessage()); }
            clearPreviewSlotLocked(app);
            jdbc.sql("update app_service set status = 'sleeping', updated_at = now() where app_id = ?").param(app.id()).update();
            logEvent(true, "SERVICE_SLEEP", app, "单体服务已休眠（缩容到 0）：" + app.name(), null, Map.of("provider", provider.name()));
        }
    }

    public Map<String, Object> restart(String ws, String appId) throws Exception {
        AppRow app = requireServiceApp(ws, appId);
        synchronized (lockFor(app.id())) {
            provider.stop(app.id(), app.slug());
            jdbc.sql("update app_service set status = 'sleeping', updated_at = now() where app_id = ?").param(app.id()).update();
        }
        ensureAwake(app.id());
        logEvent(true, "SERVICE_RESTART", app, "单体服务已重启：" + app.name(), null, Map.of("provider", provider.name()));
        return statusView(ws, appId);
    }

    /** 软删除进入「最近删除」时仅停止运行进程并释放算力，完整保留 PostgreSQL Schema 与 /data 持久化卷，供管理员恢复应用后继续读写原数据。 */
    public void stopForTrash(AppRow app) {
        if (app == null || !app.isService()) return;
        stopInternal(app);
    }

    /** 永久清理（Purge）时彻底销毁运行时容器/目录、PVC 与独立 PostgreSQL Schema/角色。 */
    public void destroyForApp(AppRow app, boolean dropSchema) {
        if (app == null || !app.isService()) return;
        synchronized (lockFor(app.id())) {
            ServiceRow s = find(app.id()).orElse(null);
            try {
                provider.destroy(previewAppId(app.id()), previewSlug(app.slug()));
            } catch (Exception ignored) {}
            try {
                provider.destroy(app.id(), app.slug());
            } catch (Exception e) {
                log.warn("销毁单体服务运行实例失败 app={}: {}", app.id(), e.getMessage());
            }
            if (dropSchema && s != null && s.pgSchema() != null) {
                dbService.dropSchemaAndRole(s.pgSchema(), s.pgRole());
            }
            jdbc.sql("update app_service set status = 'stopped', endpoint_url = null, updated_at = now() where app_id = ?").param(app.id()).update();
            logEvent(true, "SERVICE_DESTROY", app, "单体服务运行资源已彻底清理：" + app.name() + (dropSchema ? "（含数据库 Schema 与角色）" : ""),
                    null, Map.of("dropSchema", dropSchema, "provider", provider.name()));
        }
    }

    public Map<String, Object> updateConfig(String ws, String appId, JsonNode body) {
        AppRow app = requireServiceApp(ws, appId);
        ServiceRow cur = find(app.id()).orElseThrow(() -> ApiException.notFound("单体服务配置"));
        String buildCmd = body.has("buildCmd") ? body.path("buildCmd").asText("").trim() : cur.buildCmd();
        String startCmd = body.has("startCmd") ? body.path("startCmd").asText("").trim() : cur.startCmd();
        int port = body.has("port") ? body.path("port").asInt(cur.port()) : cur.port();
        String healthPath = body.has("healthPath") ? body.path("healthPath").asText("/").trim() : cur.healthPath();
        int memoryMb = body.has("memoryMb") ? body.path("memoryMb").asInt(cur.memoryMb()) : cur.memoryMb();
        int cpuMillicores = body.has("cpuMillicores") ? body.path("cpuMillicores").asInt(cur.cpuMillicores()) : cur.cpuMillicores();
        int idleSleepMin = body.has("idleSleepMin") ? body.path("idleSleepMin").asInt(cur.idleSleepMin()) : cur.idleSleepMin();
        boolean pgEnabled = body.has("pgEnabled") ? body.path("pgEnabled").asBoolean(cur.pgEnabled()) : cur.pgEnabled();
        boolean pvcEnabled = body.has("pvcEnabled") ? body.path("pvcEnabled").asBoolean(cur.pvcEnabled()) : cur.pvcEnabled();
        int pvcSizeGi = body.has("pvcSizeGi") ? body.path("pvcSizeGi").asInt(cur.pvcSizeGi()) : cur.pvcSizeGi();

        if (port < 1024 || port > 65535) throw ApiException.validation("监听端口范围应为 1024～65535");
        if (memoryMb < 128 || memoryMb > props.maxMemoryMb())
            throw ApiException.validation("单应用内存配额上限为 " + props.maxMemoryMb() + " MB（1G），允许范围 128～" + props.maxMemoryMb() + " MB，超出上限不予部署");
        if (cpuMillicores < 100 || cpuMillicores > props.maxCpuMillicores())
            throw ApiException.validation("单应用 CPU 配额上限为 " + props.maxCpuMillicores() + " mCPU（1C），允许范围 100～" + props.maxCpuMillicores() + " mCPU，超出上限不予部署");
        if (!props.allowNeverSleep() && idleSleepMin < props.minIdleSleepMinutes())
            throw ApiException.validation("为避免闲置实例长期占用服务器资源，自动休眠时间须在 " + props.minIdleSleepMinutes() + "～10080 分钟（7 天）之间，默认 2880 分钟（2 天），不支持设为 0 常驻");
        if (idleSleepMin < 0 || idleSleepMin > 10080)
            throw ApiException.validation("闲置自动休眠时间范围应为 " + (props.allowNeverSleep() ? 0 : props.minIdleSleepMinutes()) + "～10080 分钟（7 天）");
        if (pvcSizeGi < 1 || pvcSizeGi > props.maxPvcSizeGi())
            throw ApiException.validation("持久化卷容量上限为 " + props.maxPvcSizeGi() + " Gi，允许范围 1～" + props.maxPvcSizeGi() + " Gi");
        if (healthPath.isEmpty() || !healthPath.startsWith("/")) healthPath = "/" + healthPath;
        if (buildCmd.length() > 500 || startCmd.length() > 500) throw ApiException.validation("构建/启动命令最多 500 字符");

        String pgSchema = cur.pgSchema();
        String pgRole = cur.pgRole();
        String pgPassEnc = cur.pgPasswordEnc();
        if (pgEnabled) {
            var provisioned = dbService.ensureSchema(app.id(), pgSchema, pgRole, pgPassEnc);
            pgSchema = provisioned.schema();
            pgRole = provisioned.role();
            pgPassEnc = provisioned.passwordEnc();
        }

        jdbc.sql("update app_service set build_cmd=?, start_cmd=?, port=?, health_path=?, memory_mb=?, cpu_millicores=?, "
                        + "idle_sleep_min=?, pg_enabled=?, pg_schema=?, pg_role=?, pg_password_enc=?, pvc_enabled=?, pvc_size_gi=?, updated_at=now() where app_id=?")
                .params(buildCmd, startCmd, port, healthPath, memoryMb, cpuMillicores, idleSleepMin,
                        pgEnabled, pgSchema, pgRole, pgPassEnc, pvcEnabled, pvcSizeGi, app.id())
                .update();
        logEvent(true, "SERVICE_CONFIG_UPDATE", app,
                "更新单体服务配置：" + app.name() + "（PG=" + pgEnabled + ", PVC=" + pvcEnabled + ", 休眠=" + idleSleepMin + "m）",
                null, Map.of("port", port, "memoryMb", memoryMb, "cpuMillicores", cpuMillicores, "pgEnabled", pgEnabled, "pvcEnabled", pvcEnabled, "idleSleepMin", idleSleepMin));
        return statusView(ws, appId);
    }

    public Map<String, Object> replaceEnvVars(String ws, String appId, JsonNode body) {
        AppRow app = requireServiceApp(ws, appId);
        JsonNode items = body == null ? null : body.get("vars");
        if (items == null || !items.isArray()) throw ApiException.validation("缺少 vars 数组");
        if (items.size() > 64) throw ApiException.validation("环境变量最多 64 个");

        Map<String, String> existingEnc = new LinkedHashMap<>();
        jdbc.sql("select env_key, value_enc from app_service_env where app_id = ?").param(app.id())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list()
                .forEach(e -> existingEnc.put(e.getKey(), e.getValue()));

        record EnvEntry(String key, String enc, boolean secret) {}
        List<EnvEntry> next = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode n : items) {
            String k = n.path("key").asText("").trim();
            if (!ENV_KEY_RE.matcher(k).matches()) throw ApiException.validation("环境变量名不合法：" + k + "（仅允许字母、数字和下划线）");
            if (RESERVED_ENV.contains(k.toUpperCase(Locale.ROOT))) throw ApiException.validation("不能覆盖平台保留环境变量：" + k);
            if (!seen.add(k)) throw ApiException.validation("环境变量重复：" + k);
            boolean secret = n.path("secret").asBoolean(true);
            boolean keepExisting = n.path("keepExisting").asBoolean(false);
            String enc;
            if (keepExisting && existingEnc.containsKey(k)) {
                enc = existingEnc.get(k);
            } else {
                String val = n.path("value").asText("");
                if (val.length() > 8192) throw ApiException.validation("环境变量值过长：" + k);
                enc = crypto.encrypt(val);
            }
            next.add(new EnvEntry(k, enc, secret));
        }

        jdbc.sql("delete from app_service_env where app_id = ?").param(app.id()).update();
        for (EnvEntry e : next) {
            jdbc.sql("insert into app_service_env (app_id, env_key, value_enc, secret, updated_at) values (?,?,?,?,now())")
                    .params(app.id(), e.key(), e.enc(), e.secret()).update();
        }
        logEvent(true, "SERVICE_ENV_UPDATE", app, "更新单体服务环境变量：" + app.name() + "（共 " + next.size() + " 项）",
                null, Map.of("count", next.size()));
        return statusView(ws, appId);
    }

    public Map<String, Object> logs(String ws, String appId, int tail) {
        AppRow app = requireServiceApp(ws, appId);
        ServiceRow s = find(app.id()).orElse(null);
        String runtimeLogs = provider.logs(app.id(), app.slug(), tail);
        if ((runtimeLogs == null || runtimeLogs.isBlank()) && s != null && s.previewVersionId() != null) {
            runtimeLogs = provider.logs(previewAppId(app.id()), previewSlug(app.slug()), tail);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s == null ? "stopped" : effectiveStatus(s));
        m.put("provider", provider.name());
        m.put("runtimeLogs", runtimeLogs);
        m.put("buildLog", s == null || s.buildLog() == null ? "" : s.buildLog());
        m.put("lastError", s == null ? null : s.lastError());
        return m;
    }

    /** 为编码 Agent 生成只读脱敏的运行态快照（不含任何密钥值，仅含状态、环境变量键名、PG 表结构与脱敏日志）。 */
    public ObjectNode inspectSnapshotForAgent(AppRow app) {
        if (app == null || !app.isService()) return null;
        ServiceRow s = find(app.id()).orElse(null);
        if (s == null) return null;
        ObjectNode root = Json.obj();
        ObjectNode svc = root.putObject("service");
        svc.put("status", effectiveStatus(s));
        svc.put("runtime", s.runtime() == null ? "" : s.runtime());
        svc.put("framework", s.framework() == null ? "" : s.framework());
        svc.put("healthPath", s.healthPath() == null ? "/api/health" : s.healthPath());
        if (s.buildCmd() != null) svc.put("buildCommand", s.buildCmd());
        if (s.startCmd() != null) svc.put("startCommand", s.startCmd());
        if (s.lastError() != null && !s.lastError().isBlank()) {
            svc.put("lastError", LogRedactor.redact(s.lastError()));
        }

        ArrayNode envKeys = root.putArray("envKeys");
        try {
            List<String> keys = jdbc.sql("select env_key from app_service_env where app_id = ? order by env_key")
                    .param(app.id()).query(String.class).list();
            keys.forEach(envKeys::add);
        } catch (Exception ignored) { }

        ObjectNode db = root.putObject("database");
        db.put("enabled", s.pgEnabled());
        if (s.pgEnabled() && s.pgSchema() != null && !s.pgSchema().isBlank()) {
            db.put("schemaName", s.pgSchema());
            ArrayNode tables = db.putArray("tables");
            try {
                Map<String, List<String>> byTable = new LinkedHashMap<>();
                jdbc.sql("""
                        select table_name, column_name, data_type
                          from information_schema.columns
                         where table_schema = ?
                         order by table_name, ordinal_position
                        """).param(s.pgSchema()).query(rs -> {
                    String tbl = rs.getString("table_name");
                    String col = rs.getString("column_name") + ":" + rs.getString("data_type");
                    byTable.computeIfAbsent(tbl, k -> new ArrayList<>()).add(col);
                });
                byTable.forEach((name, cols) -> {
                    ObjectNode tn = tables.addObject();
                    tn.put("name", name);
                    ArrayNode cn = tn.putArray("columns");
                    cols.stream().limit(40).forEach(cn::add);
                });
            } catch (Exception ignored) { }
        }

        String runtimeLogs = "";
        try {
            runtimeLogs = provider.logs(app.id(), app.slug(), 120);
            if ((runtimeLogs == null || runtimeLogs.isBlank()) && s.previewVersionId() != null) {
                runtimeLogs = provider.logs(previewAppId(app.id()), previewSlug(app.slug()), 120);
            }
        } catch (Exception ignored) { }
        String combined = (runtimeLogs == null ? "" : runtimeLogs.trim());
        if (combined.isEmpty() && s.buildLog() != null && !s.buildLog().isBlank()) {
            combined = s.buildLog().trim();
        }
        root.put("recentLogs", LogRedactor.redact(combined));
        return root;
    }

    public Map<String, Object> dockerfilePreview(String ws, String appId) throws Exception {
        AppRow app = requireServiceApp(ws, appId);
        ServiceRow s = find(app.id()).orElseThrow(() -> ApiException.notFound("单体服务配置"));
        String vid = app.currentVersionId();
        String custom = null;
        if (vid != null) {
            VersionRow v = versions.find(vid).orElse(null);
            if (v != null) {
                custom = git.readFile(app.id(), v.gitCommit(), "Dockerfile")
                        .map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
            }
        }
        boolean isCustom = custom != null && !custom.isBlank();
        String content = isCustom ? custom : DockerfileGenerator.generate(toSpec(s), props);
        return Map.of("custom", isCustom, "content", content);
    }

    public Map<String, Object> statusView(String ws, String appId) {
        AppRow app = requireServiceApp(ws, appId);
        ServiceRow s = find(app.id()).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appId", app.id());
        m.put("slug", app.slug());
        m.put("provider", provider.name());
        m.put("k8sNamespace", props.isK8s() ? props.namespace() : null);
        if (s == null) {
            m.put("configured", false);
            m.put("status", "stopped");
            m.put("envVars", List.of());
            m.put("releases", List.of());
            return m;
        }
        m.put("configured", true);
        m.put("runtime", s.runtime());
        m.put("framework", s.framework());
        m.put("frontendDir", s.frontendDir());
        m.put("backendDir", s.backendDir());
        m.put("buildCmd", s.buildCmd());
        m.put("startCmd", s.startCmd());
        m.put("port", s.port());
        m.put("healthPath", s.healthPath());
        m.put("memoryMb", s.memoryMb());
        m.put("cpuMillicores", s.cpuMillicores());
        m.put("idleSleepMin", s.idleSleepMin());
        m.put("pgEnabled", s.pgEnabled());
        m.put("pgSchema", s.pgSchema());
        m.put("pvcEnabled", s.pvcEnabled());
        m.put("pvcSizeGi", s.pvcSizeGi());
        m.put("status", effectiveStatus(s));
        m.put("desiredVersionId", s.desiredVersionId());
        m.put("runningVersionId", s.runningVersionId());
        m.put("previewVersionId", s.previewVersionId());
        m.put("previewStatus", s.previewStatus());
        m.put("hasUnpublishedChanges", app.currentVersionId() != null && !Objects.equals(app.currentVersionId(), app.publishedVersionId()));
        m.put("imageRef", s.imageRef());
        m.put("lastAccessAt", Db.iso(s.lastAccessAt()));
        m.put("lastStartedAt", Db.iso(s.lastStartedAt()));
        m.put("lastError", s.lastError());
        m.put("updatedAt", Db.iso(s.updatedAt()));

        List<Map<String, Object>> envList = jdbc.sql("select env_key, value_enc, secret, updated_at from app_service_env where app_id = ? order by env_key")
                .param(app.id()).query((rs, i) -> {
                    boolean sec = rs.getBoolean("secret");
                    Map<String, Object> em = new LinkedHashMap<>();
                    em.put("key", rs.getString("env_key"));
                    em.put("secret", sec);
                    em.put("value", sec ? "••••••••" : crypto.decrypt(rs.getString("value_enc")));
                    em.put("hasValue", true);
                    em.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
                    return em;
                }).list();
        m.put("envVars", envList);

        List<Map<String, Object>> releases = jdbc.sql("""
                select id, version_id, slot, status, image_ref, artifact_dir, duration_ms,
                       promoted_from_release_id, error_category, error, started_at, finished_at
                  from app_service_release
                 where app_id = ?
                 order by started_at desc
                 limit 15
                """)
                .param(app.id()).query((rs, i) -> {
                    Map<String, Object> rm = new LinkedHashMap<>();
                    String st = rs.getString("status");
                    String artDir = rs.getString("artifact_dir");
                    boolean hasSnap = artDir != null && !artDir.isBlank() && Files.isDirectory(Path.of(artDir));
                    rm.put("id", rs.getString("id"));
                    rm.put("versionId", rs.getString("version_id"));
                    rm.put("slot", rs.getString("slot"));
                    rm.put("status", st);
                    rm.put("imageRef", rs.getString("image_ref"));
                    rm.put("durationMs", rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"));
                    rm.put("promotedFromReleaseId", rs.getString("promoted_from_release_id"));
                    rm.put("errorCategory", rs.getString("error_category"));
                    rm.put("error", rs.getString("error"));
                    rm.put("rollbackable", "succeeded".equals(st) && rs.getString("version_id") != null);
                    rm.put("zeroRebuildReady", "succeeded".equals(st) && (hasSnap || (props.isK8s() && rs.getString("image_ref") != null)));
                    rm.put("startedAt", Db.iso(Db.ts(rs, "started_at")));
                    rm.put("finishedAt", Db.iso(Db.ts(rs, "finished_at")));
                    return rm;
                }).list();
        m.put("releases", releases);
        m.put("canPromotePreview", s.previewVersionId() != null && !s.previewVersionId().isBlank() && "running".equals(s.previewStatus()));
        if (s.lastError() != null && !s.lastError().isBlank()) {
            var diag = diagnoseFailure(s.lastError(), s.buildLog(), s, app);
            if (diag != null) m.put("diagnosis", diag.toMap());
        }
        m.put("quota", quotaUsageView(ws));
        return m;
    }

    /** 结构化部署诊断报告（供前端告警卡片与 AI 一键自愈使用）。 */
    public Map<String, Object> diagnosisView(String ws, String appId) {
        AppRow app = requireServiceApp(ws, appId);
        ServiceRow s = find(app.id()).orElseThrow(() -> ApiException.notFound("单体服务配置"));
        String rtLogs = "";
        try {
            rtLogs = provider.logs(app.id(), app.slug(), 120);
            if ((rtLogs == null || rtLogs.isBlank()) && s.previewVersionId() != null) {
                rtLogs = provider.logs(previewAppId(app.id()), previewSlug(app.slug()), 120);
            }
        } catch (Exception ignored) {}
        DeployDiagnosisService svc = diagnosisService != null ? diagnosisService : new DeployDiagnosisService();
        var d = svc.diagnose(s.lastError(), s.buildLog(), rtLogs, s.runtime(), s.port(), s.healthPath());
        Map<String, Object> out = new LinkedHashMap<>(d.toMap());
        out.put("status", effectiveStatus(s));
        out.put("runtime", s.runtime());
        out.put("framework", s.framework());
        return out;
    }

    /** 采集应用实时运行指标与历史发布 SLA 统计。 */
    public Map<String, Object> metricsView(String ws, String appId) {
        AppRow app = requireServiceApp(ws, appId);
        ServiceRow s = find(app.id()).orElse(null);
        ServiceRuntimeProvider.RuntimeMetrics rm = provider.metrics(app.id(), app.slug());
        if (!rm.running() && s != null && s.previewVersionId() != null) {
            rm = provider.metrics(previewAppId(app.id()), previewSlug(app.slug()));
        }
        Map<String, Object> stats = jdbc.sql("""
                select count(*) as total,
                       count(*) filter (where status = 'succeeded') as ok_cnt,
                       coalesce(avg(duration_ms) filter (where status = 'succeeded' and duration_ms is not null), 0)::bigint as avg_ms,
                       coalesce(max(duration_ms) filter (where status = 'succeeded'), 0)::bigint as max_ms
                  from app_service_release
                 where app_id = ?
                """).param(app.id()).query((rs, i) -> {
            long total = rs.getLong("total");
            long ok = rs.getLong("ok_cnt");
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("totalReleases", total);
            st.put("succeededReleases", ok);
            st.put("successRate", total == 0 ? 100.0 : Math.round(ok * 1000.0 / total) / 10.0);
            st.put("avgBuildMs", rs.getLong("avg_ms"));
            st.put("maxBuildMs", rs.getLong("max_ms"));
            return st;
        }).single();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", rm.provider());
        m.put("running", rm.running());
        m.put("pid", rm.pid());
        m.put("port", rm.port());
        m.put("rssMb", rm.rssMb());
        m.put("cpuPercent", rm.cpuPercent());
        m.put("uptimeSeconds", rm.uptimeSeconds());
        m.put("memoryLimitMb", s == null ? props.maxMemoryMb() : s.memoryMb());
        m.put("cpuLimitMillicores", s == null ? props.maxCpuMillicores() : s.cpuMillicores());
        m.put("releases", stats);
        return m;
    }

    /** 在线 PostgreSQL 表浏览（严格限定本应用专属 pg_schema）。 */
    public Map<String, Object> listDbTables(String ws, String appId) {
        AppRow app = requireServiceApp(ws, appId);
        ServiceRow s = find(app.id()).orElseThrow(() -> ApiException.notFound("单体服务配置"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pgEnabled", s.pgEnabled());
        m.put("schema", s.pgSchema());
        m.put("role", s.pgRole());
        m.put("tables", s.pgEnabled() && s.pgSchema() != null ? dbService.listTables(s.pgSchema()) : List.of());
        return m;
    }

    /** 在线 PostgreSQL 受控 SQL 查询（使用该应用专属最小权限 PG 角色执行，内核级隔离）。 */
    public Map<String, Object> queryDb(String ws, String appId, String sql, boolean allowWrite, int limit) {
        AppRow app = requireServiceApp(ws, appId);
        ServiceRow s = find(app.id()).orElseThrow(() -> ApiException.notFound("单体服务配置"));
        if (!s.pgEnabled()) {
            throw ApiException.validation("请先在「运行与存储配置」开启并保存「托管 PostgreSQL Schema」");
        }
        return dbService.executeScopedSql(s.pgSchema(), s.pgRole(), s.pgPasswordEnc(), sql, allowWrite, limit);
    }

    private Map<String, Object> quotaUsageView(String ws) {
        int[] wsActive = workspaceActiveUsage(ws, "");
        long wsCount = jdbc.sql("select count(*) from app where workspace_id = ? and project_kind = 'service' and deleted_at is null")
                .param(ws).query(Long.class).single();
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("maxMemoryMb", props.maxMemoryMb());
        q.put("maxCpuMillicores", props.maxCpuMillicores());
        q.put("maxPvcSizeGi", props.maxPvcSizeGi());
        q.put("minIdleSleepMinutes", props.minIdleSleepMinutes());
        q.put("allowNeverSleep", props.allowNeverSleep());
        q.put("maxServicesPerWorkspace", props.maxServicesPerWorkspace());
        q.put("workspaceServiceCount", wsCount);
        q.put("maxWorkspaceRunningMemoryMb", props.maxWorkspaceRunningMemoryMb());
        q.put("maxWorkspaceRunningCpuMillicores", props.maxWorkspaceRunningCpuMillicores());
        q.put("workspaceRunningMemoryMb", wsActive[0]);
        q.put("workspaceRunningCpuMillicores", wsActive[1]);
        return q;
    }

    private void checkWorkspaceServiceCount(String ws, String excludingAppId) {
        long existing = jdbc.sql("select count(*) from app where workspace_id = ? and project_kind = 'service' and deleted_at is null and id <> ?")
                .params(ws, excludingAppId).query(Long.class).single();
        if (existing >= props.maxServicesPerWorkspace()) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_QUOTA_EXCEEDED",
                    "单个工作空间最多创建 " + props.maxServicesPerWorkspace() + " 个单体后端服务（当前已有 " + existing + " 个），请先删除不用的服务后重试");
        }
    }

    private int[] workspaceActiveUsage(String ws, String excludingAppId) {
        return jdbc.sql("""
                select coalesce(sum(s.memory_mb), 0), coalesce(sum(s.cpu_millicores), 0)
                from app_service s join app a on a.id = s.app_id
                where a.workspace_id = ? and a.deleted_at is null and s.status in ('running', 'building') and s.app_id <> ?
                """).params(ws, excludingAppId)
                .query((rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}).single();
    }

    private int[] platformActiveUsage(String excludingAppId) {
        return jdbc.sql("""
                select coalesce(sum(s.memory_mb), 0), coalesce(sum(s.cpu_millicores), 0)
                from app_service s join app a on a.id = s.app_id
                where a.deleted_at is null and s.status in ('running', 'building') and s.app_id <> ?
                """).param(excludingAppId)
                .query((rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}).single();
    }

    private void enforceRunQuota(AppRow app, ServiceRow target) {
        if (target.memoryMb() > props.maxMemoryMb() || target.cpuMillicores() > props.maxCpuMillicores()) {
            throw ApiException.validation("该服务配置（" + target.memoryMb() + " MB / " + target.cpuMillicores()
                    + " mCPU）超出单应用上限（" + props.maxMemoryMb() + " MB / " + props.maxCpuMillicores() + " mCPU），请下调配额后再部署");
        }
        int[] wsUsed = workspaceActiveUsage(app.workspaceId(), app.id());
        if (wsUsed[0] + target.memoryMb() > props.maxWorkspaceRunningMemoryMb()) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_QUOTA_EXCEEDED",
                    "当前工作空间运行中服务内存已用 " + wsUsed[0] + " MB，启动本服务需 " + target.memoryMb()
                            + " MB，将超出个人运行上限 " + props.maxWorkspaceRunningMemoryMb() + " MB；请先休眠或停止其他闲置服务");
        }
        if (wsUsed[1] + target.cpuMillicores() > props.maxWorkspaceRunningCpuMillicores()) {
            throw new ApiException(HttpStatus.CONFLICT, "SERVICE_QUOTA_EXCEEDED",
                    "当前工作空间运行中服务 CPU 已用 " + wsUsed[1] + " mCPU，启动本服务需 " + target.cpuMillicores()
                            + " mCPU，将超出个人运行上限 " + props.maxWorkspaceRunningCpuMillicores() + " mCPU；请先休眠或停止其他闲置服务");
        }
        int[] platUsed = platformActiveUsage(app.id());
        if (platUsed[0] + target.memoryMb() > props.maxPlatformRunningMemoryMb()
                || platUsed[1] + target.cpuMillicores() > props.maxPlatformRunningCpuMillicores()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLATFORM_CAPACITY_EXCEEDED",
                    "平台单体服务运行资源池已达上限（内存 " + platUsed[0] + "/" + props.maxPlatformRunningMemoryMb()
                            + " MB），暂不予部署；请联系管理员休眠闲置实例或扩容");
        }
    }

    private ServiceRow deployOrPromoteLocked(AppRow app, VersionRow ver) throws Exception {
        record Cand(String id, String imageRef, String artifactDir) {}
        Optional<Cand> previewCand = jdbc.sql("""
                select id, image_ref, artifact_dir
                  from app_service_release
                 where app_id = ? and version_id = ? and status = 'succeeded'
                 order by started_at desc
                 limit 1
                """).params(app.id(), ver.id())
                .query((rs, i) -> new Cand(rs.getString("id"), rs.getString("image_ref"), rs.getString("artifact_dir")))
                .optional();
        if (previewCand.isPresent()) {
            Cand c = previewCand.get();
            boolean hasLocalSnap = c.artifactDir() != null && !c.artifactDir().isBlank() && Files.isDirectory(Path.of(c.artifactDir()).resolve("work"));
            boolean hasK8sImage = props.isK8s() && c.imageRef() != null && !c.imageRef().isBlank();
            if (hasLocalSnap || hasK8sImage) {
                return deployWithPrebuiltCandidateLocked(app, ver, c.id(), c.imageRef(), c.artifactDir(), "promote");
            }
        }
        return deployLocked(app, ver);
    }

    private ServiceRow deployWithPrebuiltCandidateLocked(AppRow app, VersionRow ver, String sourceReleaseId,
                                                         String existingImageRef, String artifactDirStr, String actionKind) throws Exception {
        ServiceRow cur = find(app.id()).orElseThrow();
        enforceRunQuota(app, cur);
        String relId = Ids.newId("srel");
        long t0 = System.currentTimeMillis();
        jdbc.sql("insert into app_service_release (id, app_id, version_id, slot, status, promoted_from_release_id) values (?,?,?,?,?,?)")
                .params(relId, app.id(), ver.id(), "live", "building", sourceReleaseId).update();
        if (deployLogBroadcaster != null) deployLogBroadcaster.reset(app.id(), actionKind, ver.id());

        if (cur.pgEnabled()) {
            var p = dbService.ensureSchema(app.id(), cur.pgSchema(), cur.pgRole(), cur.pgPasswordEnc());
            jdbc.sql("update app_service set pg_schema=?, pg_role=?, pg_password_enc=? where app_id=?")
                    .params(p.schema(), p.role(), p.passwordEnc(), app.id()).update();
            cur = find(app.id()).orElseThrow();
        }

        Path tempSrc = Files.createTempDirectory("zhiye-svc-promo-");
        try {
            git.checkout(app.id(), ver.gitCommit(), tempSrc, "service");
            ServiceRuntimeProvider.DeploySpec spec = buildDeploySpec(app, cur, ver, tempSrc);
            Path snapPath = (artifactDirStr == null || artifactDirStr.isBlank()) ? null : Path.of(artifactDirStr);
            ServiceRuntimeProvider.DeployResult res = provider.deployPrebuilt(spec, existingImageRef, snapPath);
            long durationMs = System.currentTimeMillis() - t0;

            jdbc.sql("update app_service set status = 'running', running_version_id = ?, desired_version_id = ?, image_ref = ?, endpoint_url = ?, "
                            + "build_log = ?, last_error = null, last_started_at = now(), last_access_at = now(), updated_at = now() where app_id = ?")
                    .params(ver.id(), ver.id(), res.imageRef(), res.endpointUrl(), truncate(res.buildLog(), 64_000), app.id()).update();
            jdbc.sql("update app_service_release set status = 'succeeded', image_ref = ?, artifact_dir = ?, duration_ms = ?, build_log = ?, finished_at = now() where id = ?")
                    .params(res.imageRef(), res.artifactDir(), durationMs, truncate(res.buildLog(), 64_000), relId).update();
            String evtAction = "rollback".equals(actionKind) ? "SERVICE_ROLLBACK" : "SERVICE_PROMOTE";
            String evtLabel = "rollback".equals(actionKind) ? "秒级回滚成功" : "0 重建晋升上线成功";
            logEvent(true, evtAction, app, "单体服务" + evtLabel + "：" + app.name() + "（V" + ver.number() + "，" + durationMs + "ms）",
                    truncate(res.buildLog(), 12_000), Map.of("versionId", ver.id(), "sourceReleaseId", sourceReleaseId, "releaseId", relId, "durationMs", durationMs));
            return find(app.id()).orElseThrow();
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - t0;
            String msg = truncate(e.getMessage(), 4000);
            var diag = diagnoseFailure(msg, msg, cur, app);
            jdbc.sql("update app_service_release set status = 'failed', error = ?, build_log = ?, duration_ms = ?, error_category = ?, diagnosis_json = ?::jsonb, finished_at = now() where id = ?")
                    .params(msg, msg, durationMs, diag == null ? null : diag.category(), diag == null ? null : Json.write(diag.toMap()), relId).update();
            // 蓝绿切流失败时若线上旧实例仍存活，保留 running 状态仅写入 last_error，绝不误报宕机
            boolean stillAlive = provider.isRunning(app.id(), app.slug());
            jdbc.sql("update app_service set status = ?, last_error = ?, build_log = ?, updated_at = now() where app_id = ?")
                    .params(stillAlive ? "running" : "failed", msg, msg, app.id()).update();
            throw e;
        } finally {
            GitService.deleteRecursively(tempSrc);
        }
    }

    private ServiceRow deployLocked(AppRow app, VersionRow ver) throws Exception {
        ServiceRow cur = find(app.id()).orElseThrow();
        enforceRunQuota(app, cur);
        String relId = Ids.newId("srel");
        long t0 = System.currentTimeMillis();
        jdbc.sql("insert into app_service_release (id, app_id, version_id, slot, status) values (?,?,?,?,?)")
                .params(relId, app.id(), ver.id(), "live", "building").update();
        jdbc.sql("update app_service set status = 'building', desired_version_id = ?, last_error = null, updated_at = now() where app_id = ?")
                .params(ver.id(), app.id()).update();
        if (deployLogBroadcaster != null) deployLogBroadcaster.reset(app.id(), "live", ver.id());

        // 若已开启 PG 托管，确保 Schema 与凭证就绪
        if (cur.pgEnabled()) {
            var p = dbService.ensureSchema(app.id(), cur.pgSchema(), cur.pgRole(), cur.pgPasswordEnc());
            jdbc.sql("update app_service set pg_schema=?, pg_role=?, pg_password_enc=? where app_id=?")
                    .params(p.schema(), p.role(), p.passwordEnc(), app.id()).update();
            cur = find(app.id()).orElseThrow();
        }

        Path tempSrc = Files.createTempDirectory("zhiye-svc-src-");
        try {
            git.checkout(app.id(), ver.gitCommit(), tempSrc, "service");
            ServiceRuntimeProvider.DeploySpec spec = buildDeploySpec(app, cur, ver, tempSrc);
            ServiceRuntimeProvider.DeployResult res = provider.deploy(spec);
            long durationMs = System.currentTimeMillis() - t0;

            jdbc.sql("update app_service set status = 'running', running_version_id = ?, image_ref = ?, endpoint_url = ?, "
                            + "build_log = ?, last_error = null, last_started_at = now(), last_access_at = now(), updated_at = now() where app_id = ?")
                    .params(ver.id(), res.imageRef(), res.endpointUrl(), truncate(res.buildLog(), 64_000), app.id()).update();
            jdbc.sql("update app_service_release set status = 'succeeded', image_ref = ?, artifact_dir = ?, duration_ms = ?, build_log = ?, finished_at = now() where id = ?")
                    .params(res.imageRef(), res.artifactDir(), durationMs, truncate(res.buildLog(), 64_000), relId).update();
            logEvent(true, "SERVICE_DEPLOY", app, "单体服务部署成功：" + app.name() + "（" + cur.runtime() + " / " + cur.framework() + "，" + durationMs + "ms）",
                    truncate(res.buildLog(), 12_000), Map.of("runtime", cur.runtime(), "framework", cur.framework(), "imageRef", res.imageRef(), "provider", provider.name(), "releaseId", relId, "durationMs", durationMs));
            return find(app.id()).orElseThrow();
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - t0;
            String msg = truncate(e.getMessage(), 4000);
            var diag = diagnoseFailure(msg, msg, cur, app);
            boolean stillAlive = provider.isRunning(app.id(), app.slug());
            jdbc.sql("update app_service set status = ?, last_error = ?, build_log = ?, updated_at = now() where app_id = ?")
                    .params(stillAlive ? "running" : "failed", msg, msg, app.id()).update();
            jdbc.sql("update app_service_release set status = 'failed', error = ?, build_log = ?, duration_ms = ?, error_category = ?, diagnosis_json = ?::jsonb, finished_at = now() where id = ?")
                    .params(msg, msg, durationMs, diag == null ? null : diag.category(), diag == null ? null : Json.write(diag.toMap()), relId).update();
            logEvent(false, "SERVICE_DEPLOY", app, "单体服务部署失败：" + app.name() + " · " + truncate(msg, 180),
                    msg, Map.of("runtime", cur.runtime(), "framework", cur.framework(), "provider", provider.name(), "releaseId", relId));
            throw e;
        } finally {
            GitService.deleteRecursively(tempSrc);
        }
    }

    private DeployDiagnosisService.Diagnosis diagnoseFailure(String error, String buildLog, ServiceRow cur, AppRow app) {
        try {
            DeployDiagnosisService svc = diagnosisService != null ? diagnosisService : new DeployDiagnosisService();
            String rtLogs = "";
            try { rtLogs = provider.logs(app.id(), app.slug(), 80); } catch (Exception ignored) {}
            return svc.diagnose(error, buildLog, rtLogs, cur.runtime(), cur.port(), cur.healthPath());
        } catch (Exception ignored) {
            return null;
        }
    }

    private ServiceRuntimeProvider.DeploySpec buildDeploySpec(AppRow app, ServiceRow s, VersionRow ver, Path sourceDir) throws Exception {
        return buildDeploySpec(app, s, ver, sourceDir, app.id(), app.slug());
    }

    private ServiceRuntimeProvider.DeploySpec buildDeploySpec(AppRow app, ServiceRow s, VersionRow ver, Path sourceDir,
                                                              String targetAppId, String targetSlug) throws Exception {
        Map<String, String> env = new LinkedHashMap<>();
        jdbc.sql("select env_key, value_enc from app_service_env where app_id = ?").param(app.id())
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2))).list()
                .forEach(e -> env.put(e.getKey(), crypto.decrypt(e.getValue())));

        if (s.pgEnabled()) {
            var p = dbService.ensureSchema(app.id(), s.pgSchema(), s.pgRole(), s.pgPasswordEnc());
            env.put("DATABASE_URL", p.databaseUrl());
            env.put("JDBC_DATABASE_URL", p.jdbcUrl());
            env.put("PG_SCHEMA", p.schema());
        }
        // 自动注入平台受控企业连接器网关地址与本应用专属签名令牌（零明文飞书密钥进容器）
        if (enterpriseConnectors != null) {
            String apiBase = (serverPort != null ? serverPort.selfBase() : "http://127.0.0.1:8080") + "/internal/app-connectors";
            env.put("ZHIYE_CONNECTOR_API", apiBase);
            env.put("ZHIYE_CONNECTOR_TOKEN", enterpriseConnectors.issueAppConnectorToken(app.id()));
        }

        Path customDf = sourceDir.resolve("Dockerfile");
        String dockerfile = Files.isRegularFile(customDf)
                ? Files.readString(customDf, StandardCharsets.UTF_8)
                : DockerfileGenerator.generate(toSpec(s), props);

        return new ServiceRuntimeProvider.DeploySpec(
                targetAppId, targetSlug, ver.id(), s.runtime(), s.framework(), s.frontendDir(), s.backendDir(),
                s.buildCmd(), s.startCmd(), s.port(), s.healthPath(), s.memoryMb(), s.cpuMillicores(),
                s.pvcEnabled(), s.pvcSizeGi(), sourceDir, dockerfile, env);
    }

    private Optional<ServiceRuntimeDetector.Spec> detectFromCommit(String appId, String commit) throws Exception {
        var entries = git.listFiles(appId, commit);
        Set<String> paths = new LinkedHashSet<>();
        for (var e : entries) paths.add(e.path());
        return ServiceRuntimeDetector.detect(paths, p -> {
            try {
                return git.readFile(appId, commit, p).map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
            } catch (Exception ex) {
                return null;
            }
        });
    }

    private static ServiceRuntimeDetector.Spec toSpec(ServiceRow s) {
        return new ServiceRuntimeDetector.Spec(s.runtime(), s.framework(), s.frontendDir(), s.backendDir(),
                s.buildCmd(), s.startCmd(), s.port(), s.healthPath(), s.memoryMb(), s.cpuMillicores());
    }

    private String effectiveStatus(ServiceRow s) {
        if ("running".equals(s.status())) {
            AppRow app = apps.findById(s.appId()).orElse(null);
            if (app != null) {
                boolean liveUp = provider.isRunning(app.id(), app.slug());
                boolean prevUp = s.previewEndpointUrl() != null && provider.isRunning(previewAppId(app.id()), previewSlug(app.slug()));
                if (!liveUp && !prevUp) {
                    return "sleeping";
                }
            }
        }
        return s.status();
    }

    private AppRow requireServiceApp(String ws, String appId) {
        AppRow app = apps.find(ws, appId).orElseThrow(() -> ApiException.notFound("应用"));
        if (!app.isService()) throw ApiException.validation("该应用不是单体后端托管类型（projectKind=" + app.projectKind() + "）");
        return app;
    }

    private Object lockFor(String appId) {
        return appLocks.computeIfAbsent(appId, k -> new Object());
    }

    private void logEvent(boolean ok, String action, AppRow app, String summary, String detail, Map<String, Object> meta) {
        if (platformLogs == null || app == null) return;
        try {
            if (ok) {
                platformLogs.info("service_runtime", action, app.workspaceId(), null, app.id(), null, summary, detail, meta);
            } else {
                platformLogs.error("service_runtime", action, app.workspaceId(), null, app.id(), null, 500, null, summary, detail, meta);
            }
        } catch (Exception ignored) {}
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
