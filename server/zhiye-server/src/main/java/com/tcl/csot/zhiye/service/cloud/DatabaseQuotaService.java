package com.tcl.csot.zhiye.service.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.service.ops.PlatformLogService;
import com.tcl.csot.zhiye.service.runtime.ServiceDatabaseService;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 三层数据库容量治理与防刷保护服务：
 * 1) 共享云服务实时硬拦截：单行/单字段体积、单表行数上限、单应用 Schema 容量上限、按应用写入速率限流；
 * 2) 单体全栈托管数据库定时巡检与自动熔断：超限自动撤销 INSERT/UPDATE/CREATE（保留 SELECT/DELETE/TRUNCATE 以便自救清理），降至恢复水位后自动解封；
 * 3) 管理端可观测与治理：全平台 Schema 占用排行、工作空间默认容量覆写、单应用扩容与一键限写/解封。
 */
@Service
public class DatabaseQuotaService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseQuotaService.class);
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final long STATE_CACHE_TTL_MS = 5_000L;
    private static final long ROW_CACHE_TTL_MS = 5_000L;

    private final JdbcClient jdbc;
    private final ZhiyeProperties props;
    private final AppRepository apps;

    @Autowired(required = false)
    private PlatformLogService platformLogs;

    @Autowired(required = false)
    private ServiceDatabaseService serviceDb;

    public DatabaseQuotaService(JdbcClient jdbc, ZhiyeProperties props, AppRepository apps) {
        this.jdbc = jdbc;
        this.props = props;
        this.apps = apps;
    }

    private record CachedLong(long value, long atMs) {}
    private record AppDbState(Integer customQuotaMb, boolean writeBlocked, boolean blockedByAdmin,
                              String blockedReason, long usedBytes, long rowEstimate, int tableCount,
                              Instant lastScannedAt, long fetchedAtMs) {}
    private record SchemaMetrics(long totalBytes, long estRows, int tableCount) {}

    private static final class WriteRateWindow {
        private final ArrayDeque<Long> minuteTimestamps = new ArrayDeque<>();

        synchronized String tryAcquire(long nowMs, int perSec, int perMin) {
            long minCutoff = nowMs - 60_000L;
            while (!minuteTimestamps.isEmpty() && minuteTimestamps.peekFirst() < minCutoff) {
                minuteTimestamps.pollFirst();
            }
            if (perMin > 0 && minuteTimestamps.size() >= perMin) {
                return "该应用数据库写入过于频繁（每分钟上限 " + perMin + " 次），请稍后再试";
            }
            if (perSec > 0) {
                long secCutoff = nowMs - 1_000L;
                int inSec = 0;
                var it = minuteTimestamps.descendingIterator();
                while (it.hasNext()) {
                    if (it.next() >= secCutoff) inSec++;
                    else break;
                }
                if (inSec >= perSec) {
                    return "该应用数据库写入过于频繁（每秒上限 " + perSec + " 次），请稍后再试";
                }
            }
            minuteTimestamps.addLast(nowMs);
            return null;
        }
    }

    private final ConcurrentHashMap<String, WriteRateWindow> writeRateWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedLong> schemaBytesCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedLong> tableRowsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AppDbState> appStateCache = new ConcurrentHashMap<>();
    private volatile Instant lastGlobalScanAt;

    public ZhiyeProperties.DatabaseQuota config() {
        return props.databaseQuota();
    }

    public Map<String, Object> defaultsMap() {
        var c = config();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cloudMaxRowBytes", c.cloudMaxRowBytes());
        m.put("cloudMaxFieldBytes", c.cloudMaxFieldBytes());
        m.put("cloudMaxRowsPerTable", c.cloudMaxRowsPerTable());
        m.put("cloudDefaultQuotaMb", c.cloudDefaultQuotaMb());
        m.put("cloudWritesPerSecond", c.cloudWritesPerSecond());
        m.put("cloudWritesPerMinute", c.cloudWritesPerMinute());
        m.put("serviceDefaultQuotaMb", c.serviceDefaultQuotaMb());
        m.put("serviceRoleConnectionLimit", c.serviceRoleConnectionLimit());
        m.put("serviceStatementTimeoutMs", c.serviceStatementTimeoutMs());
        m.put("serviceIdleTxTimeoutMs", c.serviceIdleTxTimeoutMs());
        m.put("serviceTempFileLimitMb", c.serviceTempFileLimitMb());
        m.put("warnThresholdPercent", c.warnThresholdPercent());
        m.put("recoverThresholdPercent", c.recoverThresholdPercent());
        return m;
    }

    // ---------- 配额解析 ----------

    public int effectiveQuotaMb(String appId, String workspaceId, boolean isService) {
        AppDbState st = readAppState(appId);
        if (st.customQuotaMb() != null && st.customQuotaMb() > 0) {
            return st.customQuotaMb();
        }
        return workspaceDefaultQuotaMb(workspaceId, isService);
    }

    public int workspaceDefaultQuotaMb(String workspaceId, boolean isService) {
        var c = config();
        int sysDefault = isService ? c.serviceDefaultQuotaMb() : c.cloudDefaultQuotaMb();
        if (workspaceId == null || workspaceId.isBlank()) return sysDefault;
        String col = isService ? "service_db_quota_mb" : "cloud_db_quota_mb";
        Integer override = jdbc.sql("select " + col + " from workspace_quota where workspace_id = ?")
                .param(workspaceId).query((rs, i) -> rs.getObject(1, Integer.class)).optional().orElse(null);
        return (override != null && override > 0) ? override : sysDefault;
    }

    // ---------- 第一层：共享云服务实时写入防护 ----------

    /** 按应用维度的写入速率限流（每秒 / 每分钟双窗口），防止脚本死循环刷库 */
    public void checkCloudWriteRateLimit(String appId) {
        var c = config();
        if (c.cloudWritesPerSecond() <= 0 && c.cloudWritesPerMinute() <= 0) return;
        WriteRateWindow win = writeRateWindows.computeIfAbsent(appId, k -> new WriteRateWindow());
        String err = win.tryAcquire(System.currentTimeMillis(), c.cloudWritesPerSecond(), c.cloudWritesPerMinute());
        if (err != null) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CLOUD_WRITE_RATE_LIMITED", err);
        }
    }

    public void clearWriteRateLimits(String appId) {
        if (appId == null || appId.isBlank()) {
            writeRateWindows.clear();
        } else {
            writeRateWindows.remove(appId);
        }
    }

    /** 校验单行 JSON 体积与单字段文本体积，拦截直接向数据库写入大图片 Base64 或超大文本 */
    public long checkRowPayloadSize(JsonNode rawBody, Map<String, Object> cleanedData) {
        var c = config();
        int maxRow = c.cloudMaxRowBytes();
        int maxField = c.cloudMaxFieldBytes();
        long rowBytes = 0L;
        if (rawBody != null) {
            rowBytes = Json.write(rawBody).getBytes(StandardCharsets.UTF_8).length;
            if (maxRow > 0 && rowBytes > maxRow) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "CLOUD_ROW_TOO_LARGE",
                        "单条记录内容过大（" + fmtKb(rowBytes) + "，上限 " + fmtKb(maxRow)
                                + "）。图片、PDF 等大文件请上传到云文件存储（/storage），数据库仅保存文件路径");
            }
        }
        if (cleanedData != null && maxField > 0) {
            long sum = 0L;
            for (var e : cleanedData.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                int len = String.valueOf(v).getBytes(StandardCharsets.UTF_8).length;
                sum += len;
                if (len > maxField) {
                    throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "CLOUD_ROW_TOO_LARGE",
                            "字段「" + e.getKey() + "」内容过大（" + fmtKb(len) + "，单字段上限 " + fmtKb(maxField)
                                    + "）。图片或附件请使用云文件存储（/storage）");
                }
            }
            if (rowBytes == 0L) rowBytes = sum;
        }
        return rowBytes;
    }

    /**
     * 共享云服务写入前强检查：
     * 1) 管理员手动限写 / 超限自动限写（若用户已清理数据降至恢复水位则自动解封）；
     * 2) 单表总行数上限（新增行时检查）；
     * 3) 单应用 Schema 总磁盘占用上限。
     */
    public void checkCloudWriteAllowed(String appId, String schema, String table, long pendingNewRows, long estimatedDeltaBytes) {
        var c = config();
        AppRow app = apps.findById(appId).orElse(null);
        String wsId = app == null ? null : app.workspaceId();
        int quotaMb = effectiveQuotaMb(appId, wsId, false);
        long limitBytes = quotaMb * BYTES_PER_MB;

        // 1. 限写状态检查
        AppDbState st = readAppState(appId);
        if (st.writeBlocked()) {
            if (st.blockedByAdmin()) {
                String msg = (st.blockedReason() != null && !st.blockedReason().isBlank())
                        ? st.blockedReason()
                        : "该应用已被管理员限制数据库写入，请联系平台管理员";
                throw new ApiException(HttpStatus.FORBIDDEN, "CLOUD_WRITE_BLOCKED", msg);
            }
            // 自动超限熔断：实时重测一次磁盘占用，若用户已删除/清空降至恢复水位以下，自动解除拦截
            long liveBytes = measureSchemaBytesFresh(schema);
            long recoverBytes = limitBytes * Math.min(99, Math.max(10, c.recoverThresholdPercent())) / 100L;
            if (liveBytes < recoverBytes && liveBytes + Math.max(0L, estimatedDeltaBytes) <= limitBytes) {
                clearAutoBlock(appId, wsId, schema, null, liveBytes);
            } else {
                throw new ApiException(HttpStatus.CONFLICT, "CLOUD_DB_QUOTA_EXCEEDED",
                        "应用数据库占用已达上限（" + fmtMb(liveBytes) + " / " + quotaMb
                                + " MB），已暂停写入。请先在云服务控制台删除旧记录或清空测试数据，或联系管理员扩容");
            }
        }

        // 2. 单表总行数上限（仅新增行时检查）
        if (pendingNewRows > 0 && c.cloudMaxRowsPerTable() > 0) {
            long maxRows = c.cloudMaxRowsPerTable();
            long currentRows = estimateOrCountTableRows(schema, table, pendingNewRows, maxRows);
            if (currentRows + pendingNewRows > maxRows) {
                throw new ApiException(HttpStatus.CONFLICT, "CLOUD_TABLE_ROW_LIMIT",
                        "数据表「" + table + "」行数已达上限（最多 " + maxRows + " 行，当前 " + currentRows
                                + " 行），请先清理旧记录后再写入");
            }
            // 更新估算缓存
            tableRowsCache.put(schema + "." + table, new CachedLong(currentRows + pendingNewRows, System.currentTimeMillis()));
        }

        // 3. 单应用 Schema 磁盘总占用检查
        if (limitBytes > 0) {
            long usedBytes = getOrMeasureSchemaBytes(schema, pendingNewRows > 1, estimatedDeltaBytes, limitBytes);
            if (usedBytes + Math.max(0L, estimatedDeltaBytes) > limitBytes) {
                // 二次精确复核，避免缓存偏大误杀
                long liveBytes = measureSchemaBytesFresh(schema);
                if (liveBytes + Math.max(0L, estimatedDeltaBytes) > limitBytes) {
                    String reason = "云数据库占用已达上限（" + fmtMb(liveBytes) + " / " + quotaMb + " MB），已自动暂停写入";
                    markAutoBlocked(appId, wsId, schema, null, liveBytes, reason);
                    throw new ApiException(HttpStatus.CONFLICT, "CLOUD_DB_QUOTA_EXCEEDED",
                            reason + "。请先删除无用记录或清空测试数据，或联系管理员扩容");
                }
                usedBytes = liveBytes;
            }
            if (estimatedDeltaBytes > 0) {
                schemaBytesCache.put(schema, new CachedLong(usedBytes + estimatedDeltaBytes, System.currentTimeMillis()));
            }
        }
    }

    /** 共享云服务删除记录或清空表后调用：立刻失效缓存，若原因为自动超限且现已回落恢复水位以下，自动解除只读熔断 */
    public void onCloudDataDeleted(String appId, String schema, String table) {
        if (schema != null) {
            schemaBytesCache.remove(schema);
            if (table != null) tableRowsCache.remove(schema + "." + table);
        }
        appStateCache.remove(appId);
        AppDbState st = readAppState(appId);
        if (st.writeBlocked() && !st.blockedByAdmin() && schema != null) {
            AppRow app = apps.findById(appId).orElse(null);
            String wsId = app == null ? null : app.workspaceId();
            int quotaMb = effectiveQuotaMb(appId, wsId, false);
            long limitBytes = quotaMb * BYTES_PER_MB;
            long liveBytes = measureSchemaBytesFresh(schema);
            long recoverBytes = limitBytes * Math.min(99, Math.max(10, config().recoverThresholdPercent())) / 100L;
            if (liveBytes < recoverBytes || liveBytes <= 16_384L) {
                clearAutoBlock(appId, wsId, schema, null, liveBytes);
            }
        }
    }

    /** 返回给工作台「云服务 → 数据库」顶部的单应用容量与配额视图 */
    public Map<String, Object> cloudAppQuotaView(String appId, String schema) {
        AppRow app = apps.findById(appId).orElse(null);
        String wsId = app == null ? null : app.workspaceId();
        int quotaMb = effectiveQuotaMb(appId, wsId, false);
        long quotaBytes = quotaMb * BYTES_PER_MB;
        long usedBytes = measureSchemaBytesFresh(schema);
        AppDbState st = readAppState(appId);
        double pct = quotaBytes <= 0 ? 0.0 : Math.min(999.9, Math.round((usedBytes * 1000.0) / quotaBytes) / 10.0);
        String status = computeStatus(st.writeBlocked(), st.blockedByAdmin(), pct);
        var c = config();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("usedBytes", usedBytes);
        out.put("quotaMb", quotaMb);
        out.put("quotaBytes", quotaBytes);
        out.put("usagePercent", pct);
        out.put("maxRowsPerTable", c.cloudMaxRowsPerTable());
        out.put("maxRowBytes", c.cloudMaxRowBytes());
        out.put("maxFieldBytes", c.cloudMaxFieldBytes());
        out.put("writeBlocked", st.writeBlocked());
        out.put("blockedByAdmin", st.blockedByAdmin());
        out.put("blockedReason", st.blockedReason());
        out.put("status", status);
        return out;
    }

    // ---------- 第二层：定时巡检 + 全栈托管 PG 自动只读熔断与恢复 ----------

    @Scheduled(
            initialDelayString = "${zhiye.database-quota.scan-initial-delay-ms:30000}",
            fixedDelayString = "${zhiye.database-quota.scan-interval-ms:120000}"
    )
    public void scheduledScan() {
        try {
            scanAllSchemas();
        } catch (Exception e) {
            log.warn("定时扫描数据库容量配额失败: {}", e.getMessage());
        }
    }

    /** 立即扫描全部应用 Schema（共享云服务 app_* 与全栈托管 zhiye_svc_*），落库最新统计并执行自动超限熔断 / 恢复 */
    public synchronized Map<String, Object> scanAllSchemas() {
        Map<String, SchemaMetrics> metricsBySchema = queryAllSchemaMetrics();
        var c = config();
        int warnPct = c.warnThresholdPercent();
        int recoverPct = Math.min(99, Math.max(10, c.recoverThresholdPercent()));

        int scanned = 0;
        int warnings = 0;
        int exceeded = 0;
        int recovered = 0;

        List<ManagedSchemaRow> targets = listManagedSchemas();
        for (ManagedSchemaRow row : targets) {
            scanned++;
            SchemaMetrics sm = metricsBySchema.getOrDefault(row.schema(), new SchemaMetrics(0L, 0L, 0));
            int quotaMb = resolveQuotaMb(row.customQuotaMb(), row.wsQuotaMb(), row.service());
            long limitBytes = quotaMb * BYTES_PER_MB;
            long recoverBytes = limitBytes * recoverPct / 100L;

            boolean writeBlocked = row.writeBlocked();
            boolean blockedByAdmin = row.blockedByAdmin();
            String blockedReason = row.blockedReason();

            if (limitBytes > 0 && sm.totalBytes() >= limitBytes) {
                exceeded++;
                String newReason = (row.service() ? "托管数据库" : "云数据库") + "占用已达上限（"
                        + fmtMb(sm.totalBytes()) + " / " + quotaMb + " MB），已自动限制写入，请清理数据或联系管理员扩容";
                if (!writeBlocked) {
                    writeBlocked = true;
                    blockedReason = newReason;
                    if (row.service() && serviceDb != null && row.dbRole() != null && !row.dbRole().isBlank()) {
                        serviceDb.enforceWriteBlock(row.schema(), row.dbRole(), true);
                    }
                    logQuotaEvent("DB_QUOTA_EXCEEDED", true, row.workspaceId(), row.appId(), row.schema(),
                            newReason, Map.of("usedBytes", sm.totalBytes(), "quotaMb", quotaMb, "kind", row.service() ? "service" : "cloud"));
                } else if (!blockedByAdmin) {
                    blockedReason = newReason;
                    if (row.service() && serviceDb != null && row.dbRole() != null && !row.dbRole().isBlank()) {
                        // 兜底幂等收回（防止新建表或权限漂移）
                        serviceDb.enforceWriteBlock(row.schema(), row.dbRole(), true);
                    }
                }
            } else {
                if (limitBytes > 0 && sm.totalBytes() * 100L >= limitBytes * warnPct) {
                    warnings++;
                }
                if (writeBlocked && !blockedByAdmin && (limitBytes <= 0 || sm.totalBytes() < recoverBytes)) {
                    writeBlocked = false;
                    blockedReason = null;
                    recovered++;
                    if (row.service() && serviceDb != null && row.dbRole() != null && !row.dbRole().isBlank()) {
                        serviceDb.enforceWriteBlock(row.schema(), row.dbRole(), false);
                    }
                    logQuotaEvent("DB_QUOTA_RECOVERED", false, row.workspaceId(), row.appId(), row.schema(),
                            "数据库占用已回落至安全水位（" + fmtMb(sm.totalBytes()) + " / " + quotaMb + " MB），已自动恢复写入权限",
                            Map.of("usedBytes", sm.totalBytes(), "quotaMb", quotaMb, "kind", row.service() ? "service" : "cloud"));
                }
            }

            jdbc.sql("""
                    insert into app_db_quota (app_id, quota_mb, write_blocked, blocked_by_admin, blocked_reason,
                                              used_bytes, row_estimate, table_count, last_scanned_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                    on conflict (app_id) do update set
                      write_blocked = excluded.write_blocked,
                      blocked_reason = excluded.blocked_reason,
                      used_bytes = excluded.used_bytes,
                      row_estimate = excluded.row_estimate,
                      table_count = excluded.table_count,
                      last_scanned_at = now(),
                      updated_at = now()
                    """).params(row.appId(), row.customQuotaMb(), writeBlocked, blockedByAdmin, blockedReason,
                    sm.totalBytes(), sm.estRows(), sm.tableCount()).update();

            schemaBytesCache.put(row.schema(), new CachedLong(sm.totalBytes(), System.currentTimeMillis()));
            appStateCache.remove(row.appId());
        }

        lastGlobalScanAt = Instant.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scannedApps", scanned);
        out.put("warningCount", warnings);
        out.put("exceededCount", exceeded);
        out.put("recoveredCount", recovered);
        out.put("scannedAt", lastGlobalScanAt.toString());
        return out;
    }

    // ---------- 第三层：管理员可观测视图与治理操作 ----------

    /** 返回全平台应用数据库容量占用 Top 列表与汇总指标 */
    public Map<String, Object> adminDatabaseUsage(boolean forceScan) {
        if (forceScan || lastGlobalScanAt == null || lastGlobalScanAt.isBefore(Instant.now().minusSeconds(120))) {
            scanAllSchemas();
        }
        List<ManagedSchemaRow> rows = listManagedSchemas();
        Map<String, SchemaMetrics> liveMetrics = queryAllSchemaMetrics();

        long totalUsed = 0L;
        long totalQuota = 0L;
        int warningCount = 0;
        int blockedCount = 0;
        int cloudCount = 0;
        int serviceCount = 0;

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (ManagedSchemaRow r : rows) {
            SchemaMetrics sm = liveMetrics.getOrDefault(r.schema(), new SchemaMetrics(r.usedBytes(), r.rowEstimate(), r.tableCount()));
            long usedBytes = sm.totalBytes();
            int quotaMb = resolveQuotaMb(r.customQuotaMb(), r.wsQuotaMb(), r.service());
            long quotaBytes = quotaMb * BYTES_PER_MB;
            double pct = quotaBytes <= 0 ? 0.0 : Math.min(999.9, Math.round((usedBytes * 1000.0) / quotaBytes) / 10.0);
            String status = computeStatus(r.writeBlocked(), r.blockedByAdmin(), pct);

            totalUsed += usedBytes;
            totalQuota += quotaBytes;
            if (r.service()) serviceCount++; else cloudCount++;
            if (r.writeBlocked()) blockedCount++;
            else if ("warning".equals(status)) warningCount++;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("appId", r.appId());
            item.put("appName", r.appName());
            item.put("kind", r.service() ? "service" : "cloud");
            item.put("schema", r.schema());
            item.put("workspaceId", r.workspaceId());
            item.put("ownerLogin", r.ownerLogin());
            item.put("ownerRealName", r.ownerRealName());
            item.put("ownerName", displayName(r.ownerRealName(), r.ownerLogin(), r.workspaceId()));
            item.put("usedBytes", usedBytes);
            item.put("quotaMb", quotaMb);
            item.put("customQuotaMb", r.customQuotaMb());
            item.put("usagePercent", pct);
            item.put("rowEstimate", sm.estRows());
            item.put("tableCount", sm.tableCount());
            item.put("status", status);
            item.put("writeBlocked", r.writeBlocked());
            item.put("blockedByAdmin", r.blockedByAdmin());
            item.put("blockedReason", r.blockedReason());
            item.put("lastScannedAt", r.lastScannedAt() == null ? null : r.lastScannedAt().toString());
            items.add(item);
        }

        items.sort(Comparator.comparingLong((Map<String, Object> m) -> ((Number) m.get("usedBytes")).longValue()).reversed()
                .thenComparing(m -> String.valueOf(m.get("appName"))));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalApps", items.size());
        summary.put("cloudCount", cloudCount);
        summary.put("serviceCount", serviceCount);
        summary.put("totalUsedBytes", totalUsed);
        summary.put("totalQuotaBytes", totalQuota);
        summary.put("warningCount", warningCount);
        summary.put("blockedCount", blockedCount);
        summary.put("lastScannedAt", lastGlobalScanAt == null ? null : lastGlobalScanAt.toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defaults", defaultsMap());
        out.put("summary", summary);
        out.put("items", items);
        return out;
    }

    /** 管理员单应用数据库配额扩容 / 重置 / 一键限写或恢复写入 */
    public Map<String, Object> adminUpdateAppDbQuota(String appId, Integer quotaMb, boolean resetQuota,
                                                     Boolean writeBlocked, String reason, String actor) {
        AppRow app = apps.findById(appId).filter(a -> a.deletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("应用"));
        if (quotaMb != null && (quotaMb < 10 || quotaMb > 51_200)) {
            throw ApiException.validation("单应用数据库容量上限需在 10～51200 MB 之间");
        }

        ManagedSchemaRow target = listManagedSchemas().stream()
                .filter(r -> r.appId().equals(appId)).findFirst().orElse(null);

        AppDbState cur = readAppState(appId);
        Integer nextQuotaMb = resetQuota ? null : (quotaMb != null ? quotaMb : cur.customQuotaMb());
        boolean nextBlocked = cur.writeBlocked();
        boolean nextBlockedByAdmin = cur.blockedByAdmin();
        String nextReason = cur.blockedReason();

        if (writeBlocked != null) {
            if (writeBlocked) {
                nextBlocked = true;
                nextBlockedByAdmin = true;
                nextReason = (reason != null && !reason.isBlank())
                        ? reason.trim()
                        : "管理员已手动限制该应用数据库写入";
            } else {
                nextBlocked = false;
                nextBlockedByAdmin = false;
                nextReason = null;
            }
        } else if ((quotaMb != null || resetQuota) && nextBlocked && !nextBlockedByAdmin && target != null) {
            // 管理员扩容后，如果当前占用已低于新容量恢复水位，立即自动解封
            int effMb = resolveQuotaMb(nextQuotaMb, target.wsQuotaMb(), target.service());
            long liveBytes = measureSchemaBytesFresh(target.schema());
            long recoverBytes = effMb * BYTES_PER_MB * Math.min(99, Math.max(10, config().recoverThresholdPercent())) / 100L;
            if (liveBytes < recoverBytes) {
                nextBlocked = false;
                nextReason = null;
            }
        }

        long liveBytes = target == null ? cur.usedBytes() : measureSchemaBytesFresh(target.schema());
        jdbc.sql("""
                insert into app_db_quota (app_id, quota_mb, write_blocked, blocked_by_admin, blocked_reason,
                                          used_bytes, updated_by, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, now())
                on conflict (app_id) do update set
                  quota_mb = excluded.quota_mb,
                  write_blocked = excluded.write_blocked,
                  blocked_by_admin = excluded.blocked_by_admin,
                  blocked_reason = excluded.blocked_reason,
                  used_bytes = excluded.used_bytes,
                  updated_by = excluded.updated_by,
                  updated_at = now()
                """).params(appId, nextQuotaMb, nextBlocked, nextBlockedByAdmin, nextReason, liveBytes, actor).update();

        appStateCache.remove(appId);
        if (target != null) {
            schemaBytesCache.remove(target.schema());
            if (target.service() && serviceDb != null && target.dbRole() != null && !target.dbRole().isBlank()) {
                serviceDb.enforceWriteBlock(target.schema(), target.dbRole(), nextBlocked);
            }
        }

        int effMb = effectiveQuotaMb(appId, app.workspaceId(), app.isService());
        long quotaBytes = effMb * BYTES_PER_MB;
        double pct = quotaBytes <= 0 ? 0.0 : Math.min(999.9, Math.round((liveBytes * 1000.0) / quotaBytes) / 10.0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appId", appId);
        out.put("appName", app.name());
        out.put("quotaMb", effMb);
        out.put("customQuotaMb", nextQuotaMb);
        out.put("usedBytes", liveBytes);
        out.put("usagePercent", pct);
        out.put("writeBlocked", nextBlocked);
        out.put("blockedByAdmin", nextBlockedByAdmin);
        out.put("blockedReason", nextReason);
        out.put("status", computeStatus(nextBlocked, nextBlockedByAdmin, pct));
        return out;
    }

    // ---------- 内部辅助 ----------

    private long getOrMeasureSchemaBytes(String schema, boolean forceFresh, long deltaBytes, long limitBytes) {
        long now = System.currentTimeMillis();
        long ttlMs = Math.max(1L, config().sizeCacheSeconds()) * 1000L;
        CachedLong cached = schemaBytesCache.get(schema);
        if (!forceFresh && cached != null && (now - cached.atMs()) < ttlMs
                && (cached.value() + Math.max(0L, deltaBytes)) < (limitBytes * 85L / 100L)) {
            return cached.value();
        }
        return measureSchemaBytesFresh(schema);
    }

    public long measureSchemaBytesFresh(String schema) {
        if (schema == null || schema.isBlank()) return 0L;
        Long bytes = jdbc.sql("""
                select coalesce(sum(pg_total_relation_size(c.oid)), 0)::bigint
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = ? and c.relkind in ('r', 'p', 'm')
                """).param(schema).query(Long.class).optional().orElse(0L);
        long v = bytes == null ? 0L : Math.max(0L, bytes);
        schemaBytesCache.put(schema, new CachedLong(v, System.currentTimeMillis()));
        return v;
    }

    private long estimateOrCountTableRows(String schema, String table, long pendingNewRows, long maxRows) {
        String key = schema + "." + table;
        long now = System.currentTimeMillis();
        CachedLong cached = tableRowsCache.get(key);
        if (pendingNewRows == 1 && cached != null && (now - cached.atMs()) < ROW_CACHE_TTL_MS
                && (cached.value() + pendingNewRows) < (maxRows * 85L / 100L)) {
            return cached.value();
        }
        String qt = "\"" + schema.replace("\"", "\"\"") + "\".\"" + table.replace("\"", "\"\"") + "\"";
        Long exact = jdbc.sql("select count(*) from " + qt).query(Long.class).single();
        long v = exact == null ? 0L : exact;
        tableRowsCache.put(key, new CachedLong(v, now));
        return v;
    }

    private AppDbState readAppState(String appId) {
        long now = System.currentTimeMillis();
        AppDbState cached = appStateCache.get(appId);
        if (cached != null && (now - cached.fetchedAtMs()) < STATE_CACHE_TTL_MS) {
            return cached;
        }
        AppDbState loaded = jdbc.sql("""
                select quota_mb, write_blocked, blocked_by_admin, blocked_reason,
                       used_bytes, row_estimate, table_count, last_scanned_at
                  from app_db_quota where app_id = ?
                """).param(appId).query((rs, i) -> new AppDbState(
                rs.getObject("quota_mb", Integer.class),
                rs.getBoolean("write_blocked"),
                rs.getBoolean("blocked_by_admin"),
                rs.getString("blocked_reason"),
                rs.getLong("used_bytes"),
                rs.getLong("row_estimate"),
                rs.getInt("table_count"),
                Db.ts(rs, "last_scanned_at"),
                now
        )).optional().orElse(new AppDbState(null, false, false, null, 0L, 0L, 0, null, now));
        appStateCache.put(appId, loaded);
        return loaded;
    }

    private void markAutoBlocked(String appId, String wsId, String schema, String dbRole, long usedBytes, String reason) {
        jdbc.sql("""
                insert into app_db_quota (app_id, write_blocked, blocked_by_admin, blocked_reason, used_bytes, last_scanned_at, updated_at)
                values (?, true, false, ?, ?, now(), now())
                on conflict (app_id) do update set
                  write_blocked = true,
                  blocked_reason = case when app_db_quota.blocked_by_admin then app_db_quota.blocked_reason else excluded.blocked_reason end,
                  used_bytes = excluded.used_bytes,
                  last_scanned_at = now(),
                  updated_at = now()
                """).params(appId, reason, usedBytes).update();
        appStateCache.remove(appId);
        if (dbRole != null && !dbRole.isBlank() && serviceDb != null) {
            serviceDb.enforceWriteBlock(schema, dbRole, true);
        }
        logQuotaEvent("DB_QUOTA_EXCEEDED", true, wsId, appId, schema, reason, Map.of("usedBytes", usedBytes));
    }

    private void clearAutoBlock(String appId, String wsId, String schema, String dbRole, long usedBytes) {
        jdbc.sql("""
                update app_db_quota
                   set write_blocked = false, blocked_reason = null, used_bytes = ?, last_scanned_at = now(), updated_at = now()
                 where app_id = ? and blocked_by_admin = false
                """).params(usedBytes, appId).update();
        appStateCache.remove(appId);
        if (dbRole != null && !dbRole.isBlank() && serviceDb != null) {
            serviceDb.enforceWriteBlock(schema, dbRole, false);
        }
        logQuotaEvent("DB_QUOTA_RECOVERED", false, wsId, appId, schema,
                "数据库占用回落至安全水位，已自动恢复写入", Map.of("usedBytes", usedBytes));
    }

    private Map<String, SchemaMetrics> queryAllSchemaMetrics() {
        Map<String, SchemaMetrics> out = new LinkedHashMap<>();
        jdbc.sql("""
                select n.nspname as schema_name,
                       coalesce(sum(pg_total_relation_size(c.oid)), 0)::bigint as total_bytes,
                       coalesce(sum(greatest(0, c.reltuples::bigint)), 0)::bigint as est_rows,
                       count(c.oid)::int as table_count
                  from pg_namespace n
                  left join pg_class c on c.relnamespace = n.oid and c.relkind in ('r', 'p', 'm')
                 where n.nspname like 'app\\_%' escape '\\' or n.nspname like 'zhiye\\_svc\\_%' escape '\\'
                 group by n.nspname
                """).query(rs -> {
            out.put(rs.getString("schema_name"), new SchemaMetrics(
                    rs.getLong("total_bytes"),
                    rs.getLong("est_rows"),
                    rs.getInt("table_count")
            ));
        });
        return out;
    }

    private record ManagedSchemaRow(String appId, String appName, String workspaceId, boolean service,
                                    String schema, String dbRole, Integer customQuotaMb, Integer wsQuotaMb,
                                    boolean writeBlocked, boolean blockedByAdmin, String blockedReason,
                                    long usedBytes, long rowEstimate, int tableCount, Instant lastScannedAt,
                                    String ownerLogin, String ownerRealName) {}

    private List<ManagedSchemaRow> listManagedSchemas() {
        List<ManagedSchemaRow> out = new ArrayList<>();
        // 1. 共享云服务应用（app_cloud）
        jdbc.sql("""
                select a.id as app_id, a.name as app_name, a.workspace_id,
                       c.schema_name,
                       q.quota_mb as custom_quota_mb, wq.cloud_db_quota_mb as ws_quota_mb,
                       coalesce(q.write_blocked, false) as write_blocked,
                       coalesce(q.blocked_by_admin, false) as blocked_by_admin,
                       q.blocked_reason,
                       coalesce(q.used_bytes, 0) as used_bytes,
                       coalesce(q.row_estimate, 0) as row_estimate,
                       coalesce(q.table_count, 0) as table_count,
                       q.last_scanned_at,
                       u.login_name, u.real_name
                  from app_cloud c
                  join app a on a.id = c.app_id and a.deleted_at is null
                  left join workspace w on w.id = a.workspace_id
                  left join app_user u on u.id = w.owner_user_id
                  left join workspace_quota wq on wq.workspace_id = a.workspace_id
                  left join app_db_quota q on q.app_id = a.id
                 where c.schema_name is not null and c.schema_name <> ''
                """).query(rs -> {
            out.add(new ManagedSchemaRow(
                    rs.getString("app_id"), rs.getString("app_name"), rs.getString("workspace_id"), false,
                    rs.getString("schema_name"), null,
                    rs.getObject("custom_quota_mb", Integer.class), rs.getObject("ws_quota_mb", Integer.class),
                    rs.getBoolean("write_blocked"), rs.getBoolean("blocked_by_admin"), rs.getString("blocked_reason"),
                    rs.getLong("used_bytes"), rs.getLong("row_estimate"), rs.getInt("table_count"),
                    Db.ts(rs, "last_scanned_at"), rs.getString("login_name"), rs.getString("real_name")
            ));
        });

        // 2. 单体全栈托管数据库应用（app_service）
        jdbc.sql("""
                select a.id as app_id, a.name as app_name, a.workspace_id,
                       s.pg_schema, s.pg_role,
                       q.quota_mb as custom_quota_mb, wq.service_db_quota_mb as ws_quota_mb,
                       coalesce(q.write_blocked, false) as write_blocked,
                       coalesce(q.blocked_by_admin, false) as blocked_by_admin,
                       q.blocked_reason,
                       coalesce(q.used_bytes, 0) as used_bytes,
                       coalesce(q.row_estimate, 0) as row_estimate,
                       coalesce(q.table_count, 0) as table_count,
                       q.last_scanned_at,
                       u.login_name, u.real_name
                  from app_service s
                  join app a on a.id = s.app_id and a.deleted_at is null
                  left join workspace w on w.id = a.workspace_id
                  left join app_user u on u.id = w.owner_user_id
                  left join workspace_quota wq on wq.workspace_id = a.workspace_id
                  left join app_db_quota q on q.app_id = a.id
                 where s.pg_schema is not null and s.pg_schema <> ''
                """).query(rs -> {
            out.add(new ManagedSchemaRow(
                    rs.getString("app_id"), rs.getString("app_name"), rs.getString("workspace_id"), true,
                    rs.getString("pg_schema"), rs.getString("pg_role"),
                    rs.getObject("custom_quota_mb", Integer.class), rs.getObject("ws_quota_mb", Integer.class),
                    rs.getBoolean("write_blocked"), rs.getBoolean("blocked_by_admin"), rs.getString("blocked_reason"),
                    rs.getLong("used_bytes"), rs.getLong("row_estimate"), rs.getInt("table_count"),
                    Db.ts(rs, "last_scanned_at"), rs.getString("login_name"), rs.getString("real_name")
            ));
        });

        return out;
    }

    private int resolveQuotaMb(Integer customQuotaMb, Integer wsQuotaMb, boolean isService) {
        if (customQuotaMb != null && customQuotaMb > 0) return customQuotaMb;
        if (wsQuotaMb != null && wsQuotaMb > 0) return wsQuotaMb;
        return isService ? config().serviceDefaultQuotaMb() : config().cloudDefaultQuotaMb();
    }

    private String computeStatus(boolean writeBlocked, boolean blockedByAdmin, double usagePercent) {
        if (writeBlocked) return blockedByAdmin ? "manual_blocked" : "exceeded";
        if (usagePercent >= config().warnThresholdPercent()) return "warning";
        return "ok";
    }

    private void logQuotaEvent(String action, boolean warnOrErr, String wsId, String appId,
                               String schema, String summary, Map<String, Object> extra) {
        if (platformLogs == null) return;
        Map<String, Object> meta = new LinkedHashMap<>(extra);
        meta.put("schema", schema);
        platformLogs.record("database_quota", warnOrErr ? "WARN" : "INFO", action,
                wsId, null, appId, null, !warnOrErr, warnOrErr ? 409 : 200, 0,
                summary, null, meta);
    }

    private static String displayName(String real, String login, String fallbackWs) {
        if (real != null && !real.isBlank()) return real;
        if (login != null && !login.isBlank()) return login;
        return fallbackWs;
    }

    private static String fmtKb(long bytes) {
        if (bytes < 1024) return bytes + " B";
        return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }

    private static String fmtMb(long bytes) {
        if (bytes < BYTES_PER_MB) return String.format(Locale.ROOT, "%.2f MB", bytes / (double) BYTES_PER_MB);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (double) BYTES_PER_MB);
    }
}
