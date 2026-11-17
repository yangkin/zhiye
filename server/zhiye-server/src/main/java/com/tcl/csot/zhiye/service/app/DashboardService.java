package com.tcl.csot.zhiye.service.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.client.OpenclawClient;
import com.tcl.csot.zhiye.config.FivestarProperties;
import com.tcl.csot.zhiye.config.SkillHubProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.common.RuntimeMetricsService;
import com.tcl.csot.zhiye.service.site.PageViewRecorder;
import com.tcl.csot.zhiye.service.skillhub.SkillRefs;
import com.tcl.csot.zhiye.service.workspace.QuotaService;
import com.tcl.csot.zhiye.util.Db;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 系统全景统计看板服务：
 * 支持 {@code scope=platform}（全平台管理视角，统计所有工作空间/用户/资源）
 * 与 {@code scope=workspace}（当前工作空间隔离视角）。
 */
@Service
public class DashboardService {
    private static final int MAX_TREND_DAYS = 180;
    private static final ZoneId ZONE = PageViewRecorder.ZONE;

    private final JdbcClient jdbc;
    private final QuotaService quota;
    private final SkillRefs skillRefs;
    private final FivestarProperties fivestar;
    private final SkillHubProperties skillhub;

    @Autowired(required = false)
    private RuntimeMetricsService runtimeMetrics;

    @Autowired(required = false)
    private OpenclawClient openclaw;

    public DashboardService(JdbcClient jdbc, QuotaService quota, SkillRefs skillRefs,
                            FivestarProperties fivestar, SkillHubProperties skillhub) {
        this.jdbc = jdbc;
        this.quota = quota;
        this.skillRefs = skillRefs;
        this.fivestar = fivestar;
        this.skillhub = skillhub;
    }

    public Map<String, Object> snapshot(String ws, String rangeParam, String scopeParam, String platformToken) {
        String range = (rangeParam == null || rangeParam.isBlank()) ? "30d" : rangeParam.trim();
        String scope = (scopeParam == null || scopeParam.isBlank()) ? "platform" : scopeParam.trim();
        if (!"platform".equals(scope) && !"workspace".equals(scope)) {
            throw ApiException.validation("scope 只能是 platform 或 workspace");
        }
        String wsFilter = "workspace".equals(scope) ? ws : null;

        LocalDate today = LocalDate.now(ZONE);
        LocalDate start = resolveStart(wsFilter, range, today);
        long days = ChronoUnit.DAYS.between(start, today) + 1;
        OffsetDateTime startTs = start.atStartOfDay(ZONE).toOffsetDateTime();
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = start.minusDays(days);
        OffsetDateTime prevStartTs = prevStart.atStartOfDay(ZONE).toOffsetDateTime();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", metaSection(ws, scope, range, start, today, days));
        out.put("overview", overviewSection(ws, wsFilter, start, startTs, prevStart, prevEnd, prevStartTs, today, days));
        out.put("funnel", funnelSection(wsFilter));
        out.put("trends", trendsSection(wsFilter, start, startTs, today));
        out.put("hourly", hourlySection(wsFilter, startTs));
        out.put("workspaces", workspacesSection(wsFilter, startTs, today));
        out.put("apps", appsSection(wsFilter, today));
        out.put("tasks", tasksSection(wsFilter));
        out.put("agentTelemetry", agentTelemetrySection(wsFilter));
        out.put("llm", llmSection(wsFilter, platformToken));
        out.put("versions", versionsSection(wsFilter));
        out.put("cloud", cloudSection(wsFilter));
        out.put("dataSources", dataSourcesSection(wsFilter));
        out.put("interactions", interactionsSection(wsFilter));
        out.put("ecosystem", ecosystemSection(wsFilter, ws));
        out.put("system", systemSection());
        return out;
    }

    private LocalDate resolveStart(String wsFilter, String range, LocalDate today) {
        return switch (range) {
            case "today" -> today;
            case "7d" -> today.minusDays(6);
            case "30d" -> today.minusDays(29);
            case "90d" -> today.minusDays(89);
            case "all" -> {
                LocalDate first = today;
                for (LocalDate candidate : Arrays.asList(
                        minDate("select min(created_at) from app where (?::text is null or workspace_id = ?) and deleted_at is null", wsFilter),
                        minDate("select min(created_at) from task where (?::text is null or workspace_id = ?)", wsFilter),
                        minLocalDate("""
                                select min(p.day) from app_pageview_daily p
                                join app a on a.id = p.app_id
                                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                                """, wsFilter)
                )) {
                    if (candidate != null && candidate.isBefore(first)) first = candidate;
                }
                LocalDate floor = today.minusDays(MAX_TREND_DAYS - 1);
                yield first.isBefore(floor) ? floor : first;
            }
            default -> throw ApiException.validation("range 只能是 today、7d、30d、90d、all");
        };
    }

    private LocalDate minDate(String sql, String wsFilter) {
        OffsetDateTime dt = jdbc.sql(sql).params(wsFilter, wsFilter).query(OffsetDateTime.class).optional().orElse(null);
        return dt == null ? null : dt.atZoneSameInstant(ZONE).toLocalDate();
    }

    private LocalDate minLocalDate(String sql, String wsFilter) {
        return jdbc.sql(sql).params(wsFilter, wsFilter).query(LocalDate.class).optional().orElse(null);
    }

    private Map<String, Object> metaSection(String ws, String scope, String range, LocalDate start, LocalDate today, long days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", scope);
        m.put("range", range);
        m.put("startDate", start.toString());
        m.put("endDate", today.toString());
        m.put("days", days);
        m.put("generatedAt", Instant.now().toString());
        m.put("workspaceId", ws);
        m.put("fivestarEnabled", fivestar.enabled());
        m.put("skillhubEnabled", skillhub.enabled());
        return m;
    }

    private Map<String, Object> overviewSection(String currentWs, String wsFilter, LocalDate start, OffsetDateTime startTs,
                                                LocalDate prevStart, LocalDate prevEnd, OffsetDateTime prevStartTs,
                                                LocalDate today, long days) {
        Map<String, Object> m = new LinkedHashMap<>();
        long[] usage = quota.appUsage(currentWs);

        // 工作空间与用户活跃维度
        long wsTotal = wsFilter == null
                ? jdbc.sql("select count(*) from workspace").query(Long.class).single()
                : 1L;
        long wsProductive = jdbc.sql("""
                select count(distinct workspace_id) from (
                  select workspace_id from app where (?::text is null or workspace_id = ?) and deleted_at is null
                  union
                  select workspace_id from task where (?::text is null or workspace_id = ?)
                  union
                  select workspace_id from conversation where (?::text is null or workspace_id = ?) and deleted_at is null
                ) t
                """).params(wsFilter, wsFilter, wsFilter, wsFilter, wsFilter, wsFilter).query(Long.class).single();
        long wsActiveRange = jdbc.sql("""
                select count(distinct workspace_id) from (
                  select workspace_id from app where (?::text is null or workspace_id = ?) and deleted_at is null and updated_at >= ?
                  union
                  select workspace_id from task where (?::text is null or workspace_id = ?) and created_at >= ?
                  union
                  select workspace_id from conversation where (?::text is null or workspace_id = ?) and deleted_at is null and updated_at >= ?
                ) t
                """).params(wsFilter, wsFilter, startTs, wsFilter, wsFilter, startTs, wsFilter, wsFilter, startTs).query(Long.class).single();
        long ssoUsers = wsFilter == null
                ? jdbc.sql("select count(*) from app_user").query(Long.class).single()
                : jdbc.sql("select count(distinct owner_user_id) from workspace where id = ? and owner_user_id is not null").param(currentWs).query(Long.class).single();

        m.put("workspacesTotal", wsTotal);
        m.put("workspacesProductive", wsProductive);
        m.put("workspacesActiveRange", wsActiveRange);
        m.put("ssoUsersTotal", ssoUsers);

        // 应用汇总
        var appCounts = jdbc.sql("""
                select
                  count(*) filter (where deleted_at is null) as total,
                  count(*) filter (where deleted_at is null and created_at >= ?) as range_created,
                  count(*) filter (where deleted_at is null and created_at >= ? and created_at < ?) as prev_created,
                  count(*) filter (where deleted_at is null and published_version_id is not null) as published,
                  count(*) filter (where deleted_at is null and favorite) as favorite,
                  count(*) filter (where deleted_at is not null) as deleted,
                  count(*) filter (where deleted_at is null and phase = 'ready') as ready,
                  count(*) filter (where deleted_at is null and phase = 'failed') as failed,
                  count(*) filter (where deleted_at is null and phase = 'stopped') as stopped,
                  count(*) filter (where deleted_at is null and phase in ('planning', 'plan', 'generating')) as in_progress
                from app where (?::text is null or workspace_id = ?)
                """).params(startTs, prevStartTs, startTs, wsFilter, wsFilter).query((rs, i) -> {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("appsTotal", rs.getLong("total"));
            am.put("appsRange", rs.getLong("range_created"));
            am.put("appsPrevRange", rs.getLong("prev_created"));
            am.put("appsLimit", usage[1]);
            am.put("appsUsedInMyWs", usage[0]);
            am.put("appsPublished", rs.getLong("published"));
            am.put("appsFavorite", rs.getLong("favorite"));
            am.put("appsDeleted", rs.getLong("deleted"));
            am.put("appsReady", rs.getLong("ready"));
            am.put("appsFailed", rs.getLong("failed"));
            am.put("appsStopped", rs.getLong("stopped"));
            am.put("appsInProgress", rs.getLong("in_progress"));
            return am;
        }).single();
        m.putAll(appCounts);

        // PV 汇总
        LocalDate yesterday = today.minusDays(1);
        var pv = jdbc.sql("""
                select
                  coalesce(sum(p.pv), 0) as total,
                  coalesce(sum(p.pv) filter (where p.day >= ? and p.day <= ?), 0) as range_pv,
                  coalesce(sum(p.pv) filter (where p.day >= ? and p.day <= ?), 0) as prev_pv,
                  coalesce(sum(p.pv) filter (where p.day = ?), 0) as today_pv,
                  coalesce(sum(p.pv) filter (where p.day = ?), 0) as yesterday_pv,
                  count(distinct p.app_id) filter (where p.pv > 0) as apps_with_pv
                from app_pageview_daily p
                join app a on a.id = p.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """).params(start, today, prevStart, prevEnd, today, yesterday, wsFilter, wsFilter).query((rs, i) -> {
            long rangePv = rs.getLong("range_pv");
            Map<String, Object> pvMap = new LinkedHashMap<>();
            pvMap.put("pvTotal", rs.getLong("total"));
            pvMap.put("pvRange", rangePv);
            pvMap.put("pvPrevRange", rs.getLong("prev_pv"));
            pvMap.put("pvToday", rs.getLong("today_pv"));
            pvMap.put("pvYesterday", rs.getLong("yesterday_pv"));
            pvMap.put("pvAvgDaily", days > 0 ? round1((double) rangePv / days) : 0.0);
            pvMap.put("appsWithPv", rs.getLong("apps_with_pv"));
            return pvMap;
        }).single();
        m.putAll(pv);

        // 任务汇总（含 P50 / P95 / 环比）
        var taskAgg = jdbc.sql("""
                select
                  count(*) as total,
                  count(*) filter (where created_at >= ?) as range_total,
                  count(*) filter (where created_at >= ? and created_at < ?) as prev_total,
                  count(*) filter (where status = 'SUCCEEDED') as succeeded,
                  count(*) filter (where status = 'FAILED') as failed,
                  count(*) filter (where status = 'CANCELLED') as cancelled,
                  count(*) filter (where status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')) as active,
                  coalesce(avg(extract(epoch from (finished_at - started_at))) filter (where started_at is not null and finished_at is not null and finished_at >= started_at), 0) as avg_sec,
                  coalesce(percentile_cont(0.50) within group (order by extract(epoch from (finished_at - started_at))) filter (where started_at is not null and finished_at is not null and finished_at >= started_at), 0) as p50_sec,
                  coalesce(percentile_cont(0.90) within group (order by extract(epoch from (finished_at - started_at))) filter (where started_at is not null and finished_at is not null and finished_at >= started_at), 0) as p90_sec,
                  coalesce(percentile_cont(0.95) within group (order by extract(epoch from (finished_at - started_at))) filter (where started_at is not null and finished_at is not null and finished_at >= started_at), 0) as p95_sec
                from task where (?::text is null or workspace_id = ?)
                """).params(startTs, prevStartTs, startTs, wsFilter, wsFilter).query((rs, i) -> {
            long ok = rs.getLong("succeeded");
            long fail = rs.getLong("failed");
            long cancel = rs.getLong("cancelled");
            long terminal = ok + fail + cancel;
            double rate = terminal == 0 ? 0.0 : round1((double) ok * 100.0 / terminal);
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("tasksTotal", rs.getLong("total"));
            tm.put("tasksRange", rs.getLong("range_total"));
            tm.put("tasksPrevRange", rs.getLong("prev_total"));
            tm.put("tasksSucceeded", ok);
            tm.put("tasksFailed", fail);
            tm.put("tasksCancelled", cancel);
            tm.put("tasksActive", rs.getLong("active"));
            tm.put("taskSuccessRate", rate);
            tm.put("taskAvgDurationSec", round1(rs.getDouble("avg_sec")));
            tm.put("taskP50DurationSec", round1(rs.getDouble("p50_sec")));
            tm.put("taskP90DurationSec", round1(rs.getDouble("p90_sec")));
            tm.put("taskP95DurationSec", round1(rs.getDouble("p95_sec")));
            return tm;
        }).single();
        m.putAll(taskAgg);

        m.put("versionsTotal", count("""
                select count(*) from version v join app a on a.id = v.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("versionsRange", jdbc.sql("""
                select count(*) from version v join app a on a.id = v.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and v.created_at >= ?
                """).params(wsFilter, wsFilter, startTs).query(Long.class).single());
        m.put("deploymentsTotal", count("""
                select count(*) from deployment d join app a on a.id = d.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("buildsTotal", count("""
                select count(*) from build b join app a on a.id = b.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));

        // 模型用量与环比
        var usageAgg = jdbc.sql("""
                select
                  count(*) as calls,
                  count(*) filter (where mu.created_at >= ?) as range_calls,
                  coalesce(sum(mu.input_tokens), 0) as in_tok,
                  coalesce(sum(mu.output_tokens), 0) as out_tok,
                  coalesce(sum(mu.input_tokens + mu.output_tokens) filter (where mu.created_at >= ?), 0) as range_tok,
                  coalesce(sum(mu.input_tokens + mu.output_tokens) filter (where mu.created_at >= ? and mu.created_at < ?), 0) as prev_tok,
                  coalesce(sum(mu.cost), 0) as cost
                from model_usage mu
                join task t on t.id = mu.task_id
                where (?::text is null or t.workspace_id = ?)
                """).params(startTs, startTs, prevStartTs, startTs, wsFilter, wsFilter).query((rs, i) -> {
            long inTok = rs.getLong("in_tok");
            long outTok = rs.getLong("out_tok");
            BigDecimal cost = rs.getBigDecimal("cost");
            Map<String, Object> um = new LinkedHashMap<>();
            um.put("modelCallsTotal", rs.getLong("calls"));
            um.put("modelCallsRange", rs.getLong("range_calls"));
            um.put("tokensInput", inTok);
            um.put("tokensOutput", outTok);
            um.put("tokensTotal", inTok + outTok);
            um.put("tokensRange", rs.getLong("range_tok"));
            um.put("tokensPrevRange", rs.getLong("prev_tok"));
            um.put("costTotal", cost == null ? 0.0 : cost.setScale(4, RoundingMode.HALF_UP).doubleValue());
            return um;
        }).single();
        m.putAll(usageAgg);

        m.put("conversationsTotal", count("""
                select count(*) from conversation where (?::text is null or workspace_id = ?) and deleted_at is null
                """, wsFilter, wsFilter));
        m.put("conversationsConverted", count("""
                select count(distinct ac.conversation_id) from app_conversation ac
                join app a on a.id = ac.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("workbenchMessagesTotal", count("""
                select count(*) from message msg join app a on a.id = msg.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("helpMessagesTotal", count("""
                select count(*) from help_message hm join help_conversation hc on hc.id = hm.conversation_id
                where (?::text is null or hc.workspace_id = ?) and hm.role = 'user'
                """, wsFilter, wsFilter));
        m.put("helpFeedbackTotal", count("""
                select count(*) from help_feedback where (?::text is null or workspace_id = ?)
                """, wsFilter, wsFilter));
        m.put("cloudEnabledApps", count("""
                select count(*) from app_cloud c join app a on a.id = c.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and c.status = 'on'
                """, wsFilter, wsFilter));
        m.put("cloudTablesTotal", count("""
                select count(*) from app_cloud_table t join app a on a.id = t.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("fnCallsTotal", count("""
                select count(*) from app_fn_log f join app a on a.id = f.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("dataSourcesTotal", count("""
                select count(*) from data_source where ((?::text is null or workspace_id = ?) or workspace_id is null) and deleted_at is null
                """, wsFilter, wsFilter));
        m.put("sourceCallsTotal", count("""
                select count(*) from data_source_call c
                left join app a on a.id = c.app_id
                left join data_source ds on ds.id = c.source_id
                where (?::text is null or a.workspace_id = ? or ds.workspace_id = ?)
                """, wsFilter, wsFilter, wsFilter));
        m.put("assetsTotal", count("select count(*) from asset where (?::text is null or workspace_id = ?)", wsFilter, wsFilter));
        m.put("assetsSizeBytes", count("select coalesce(sum(size), 0) from asset where (?::text is null or workspace_id = ?)", wsFilter, wsFilter));
        m.put("templatesPublished", count("select count(*) from template where (?::text is null or workspace_id = ?)", wsFilter, wsFilter));
        m.put("skillhubInstalled", count("select count(*) from skillhub_install where (?::text is null or workspace_id = ?) and deleted_at is null", wsFilter, wsFilter));
        return m;
    }

    /** 全链路转化漏斗：对话 -> 创建应用 -> 成功生成 -> 发布上线 -> 产生访问 -> 启用云服务 / 分享社区 */
    private List<Map<String, Object>> funnelSection(String wsFilter) {
        return jdbc.sql("""
                select
                  (select count(*) from conversation where (?::text is null or workspace_id = ?) and deleted_at is null) as convs,
                  (select count(*) from app where (?::text is null or workspace_id = ?) and deleted_at is null) as apps,
                  (select count(*) from app where (?::text is null or workspace_id = ?) and deleted_at is null and phase = 'ready') as ready,
                  (select count(*) from app where (?::text is null or workspace_id = ?) and deleted_at is null and published_version_id is not null) as published,
                  (select count(distinct p.app_id) from app_pageview_daily p join app a on a.id = p.app_id where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and p.pv > 0) as visited,
                  (select count(*) from app_cloud c join app a on a.id = c.app_id where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and c.status = 'on') as cloud
                """).params(
                wsFilter, wsFilter,
                wsFilter, wsFilter,
                wsFilter, wsFilter,
                wsFilter, wsFilter,
                wsFilter, wsFilter,
                wsFilter, wsFilter
        ).query((rs, i) -> List.<Map<String, Object>>of(
                Map.of("key", "conversations", "label", "发起创造会话", "count", rs.getLong("convs")),
                Map.of("key", "apps", "label", "创建应用项目", "count", rs.getLong("apps")),
                Map.of("key", "ready", "label", "首版生成就绪", "count", rs.getLong("ready")),
                Map.of("key", "published", "label", "发布上线部署", "count", rs.getLong("published")),
                Map.of("key", "visited", "label", "产生真实访问", "count", rs.getLong("visited")),
                Map.of("key", "cloud", "label", "启用共享云服务", "count", rs.getLong("cloud"))
        )).single();
    }

    private Map<String, Object> trendsSection(String wsFilter, LocalDate start, OffsetDateTime startTs, LocalDate today) {
        Map<LocalDate, Long> pvByDay = new HashMap<>();
        jdbc.sql("""
                select p.day as d, coalesce(sum(p.pv), 0) as c
                from app_pageview_daily p
                join app a on a.id = p.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and p.day >= ? and p.day <= ?
                group by p.day
                """).params(wsFilter, wsFilter, start, today).query((rs, i) -> {
            pvByDay.put(rs.getObject("d", LocalDate.class), rs.getLong("c"));
            return null;
        }).list();

        Map<LocalDate, Long> appsByDay = queryDailyTsCounts("""
                select (created_at at time zone 'Asia/Shanghai')::date as d, count(*) as c
                from app where (?::text is null or workspace_id = ?) and deleted_at is null and created_at >= ?
                group by 1
                """, wsFilter, startTs);

        Map<LocalDate, Long> activeWsByDay = queryDailyTsCounts("""
                select (created_at at time zone 'Asia/Shanghai')::date as d, count(distinct workspace_id) as c
                from task where (?::text is null or workspace_id = ?) and created_at >= ?
                group by 1
                """, wsFilter, startTs);

        Map<LocalDate, long[]> tasksByDay = new HashMap<>();
        jdbc.sql("""
                select (created_at at time zone 'Asia/Shanghai')::date as d,
                  count(*) as total,
                  count(*) filter (where status = 'SUCCEEDED') as ok,
                  count(*) filter (where status = 'FAILED') as fail
                from task where (?::text is null or workspace_id = ?) and created_at >= ?
                group by 1
                """).params(wsFilter, wsFilter, startTs).query((rs, i) -> {
            tasksByDay.put(rs.getObject("d", LocalDate.class), new long[]{rs.getLong("total"), rs.getLong("ok"), rs.getLong("fail")});
            return null;
        }).list();

        Map<LocalDate, Long> versionsByDay = queryDailyTsCounts("""
                select (v.created_at at time zone 'Asia/Shanghai')::date as d, count(*) as c
                from version v join app a on a.id = v.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and v.created_at >= ?
                group by 1
                """, wsFilter, startTs);

        Map<LocalDate, Long> deploymentsByDay = queryDailyTsCounts("""
                select (d.created_at at time zone 'Asia/Shanghai')::date as d, count(*) as c
                from deployment d join app a on a.id = d.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and d.created_at >= ?
                group by 1
                """, wsFilter, startTs);

        Map<LocalDate, long[]> llmByDay = new HashMap<>();
        Map<LocalDate, Double> costByDay = new HashMap<>();
        jdbc.sql("""
                select (mu.created_at at time zone 'Asia/Shanghai')::date as d,
                  coalesce(sum(mu.input_tokens + mu.output_tokens), 0) as tok,
                  count(*) as calls,
                  coalesce(sum(mu.cost), 0) as cost
                from model_usage mu join task t on t.id = mu.task_id
                where (?::text is null or t.workspace_id = ?) and mu.created_at >= ?
                group by 1
                """).params(wsFilter, wsFilter, startTs).query((rs, i) -> {
            LocalDate day = rs.getObject("d", LocalDate.class);
            llmByDay.put(day, new long[]{rs.getLong("tok"), rs.getLong("calls")});
            BigDecimal c = rs.getBigDecimal("cost");
            costByDay.put(day, c == null ? 0.0 : c.setScale(4, RoundingMode.HALF_UP).doubleValue());
            return null;
        }).list();

        Map<LocalDate, Long> wbMsgByDay = queryDailyTsCounts("""
                select (msg.created_at at time zone 'Asia/Shanghai')::date as d, count(*) as c
                from message msg join app a on a.id = msg.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and msg.created_at >= ?
                group by 1
                """, wsFilter, startTs);

        Map<LocalDate, Long> convMsgByDay = queryDailyTsCounts("""
                select (cm.created_at at time zone 'Asia/Shanghai')::date as d, count(*) as c
                from conversation_message cm join conversation c on c.id = cm.conversation_id
                where (?::text is null or c.workspace_id = ?) and c.deleted_at is null and cm.created_at >= ?
                group by 1
                """, wsFilter, startTs);

        Map<LocalDate, Long> fnByDay = queryDailyTsCounts("""
                select (f.created_at at time zone 'Asia/Shanghai')::date as d, count(*) as c
                from app_fn_log f join app a on a.id = f.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and f.created_at >= ?
                group by 1
                """, wsFilter, startTs);

        Map<LocalDate, Long> srcByDay = new HashMap<>();
        jdbc.sql("""
                select (c.created_at at time zone 'Asia/Shanghai')::date as d, count(*) as cnt
                from data_source_call c
                left join app a on a.id = c.app_id
                left join data_source ds on ds.id = c.source_id
                where (?::text is null or a.workspace_id = ? or ds.workspace_id = ?) and c.created_at >= ?
                group by 1
                """).params(wsFilter, wsFilter, wsFilter, startTs).query((rs, i) -> {
            srcByDay.put(rs.getObject("d", LocalDate.class), rs.getLong("cnt"));
            return null;
        }).list();

        List<Map<String, Object>> daily = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            long[] tk = tasksByDay.getOrDefault(d, new long[]{0, 0, 0});
            long[] lm = llmByDay.getOrDefault(d, new long[]{0, 0});
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("pv", pvByDay.getOrDefault(d, 0L));
            row.put("appsCreated", appsByDay.getOrDefault(d, 0L));
            row.put("activeWorkspaces", activeWsByDay.getOrDefault(d, 0L));
            row.put("tasksTotal", tk[0]);
            row.put("tasksSucceeded", tk[1]);
            row.put("tasksFailed", tk[2]);
            row.put("versions", versionsByDay.getOrDefault(d, 0L));
            row.put("deployments", deploymentsByDay.getOrDefault(d, 0L));
            row.put("tokens", lm[0]);
            row.put("modelCalls", lm[1]);
            row.put("cost", costByDay.getOrDefault(d, 0.0));
            row.put("messages", wbMsgByDay.getOrDefault(d, 0L) + convMsgByDay.getOrDefault(d, 0L));
            row.put("fnCalls", fnByDay.getOrDefault(d, 0L));
            row.put("sourceCalls", srcByDay.getOrDefault(d, 0L));
            daily.add(row);
        }
        return Map.of("daily", daily);
    }

    /** 24 小时活跃分布（按 Asia/Shanghai 小时统计任务数与失败数） */
    private List<Map<String, Object>> hourlySection(String wsFilter, OffsetDateTime startTs) {
        Map<Integer, long[]> byHour = new HashMap<>();
        jdbc.sql("""
                select extract(hour from (created_at at time zone 'Asia/Shanghai'))::int as hr,
                  count(*) as total,
                  count(*) filter (where status = 'SUCCEEDED') as ok,
                  count(*) filter (where status = 'FAILED') as fail
                from task
                where (?::text is null or workspace_id = ?) and created_at >= ?
                group by 1
                """).params(wsFilter, wsFilter, startTs).query((rs, i) -> {
            byHour.put(rs.getInt("hr"), new long[]{rs.getLong("total"), rs.getLong("ok"), rs.getLong("fail")});
            return null;
        }).list();
        List<Map<String, Object>> out = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            long[] v = byHour.getOrDefault(h, new long[]{0, 0, 0});
            out.add(Map.of("hour", h, "total", v[0], "succeeded", v[1], "failed", v[2]));
        }
        return out;
    }

    /** 活跃工作空间/用户生产力排行（过滤无实际产出的空空间） */
    private List<Map<String, Object>> workspacesSection(String wsFilter, OffsetDateTime startTs, LocalDate today) {
        return jdbc.sql("""
                select w.id,
                  coalesce(u.real_name, u.login_name, '') as owner_name,
                  coalesce(ac.apps, 0) as apps,
                  coalesce(ac.published, 0) as published,
                  coalesce(tc.tasks, 0) as tasks,
                  coalesce(tc.ok_tasks, 0) as ok_tasks,
                  coalesce(tc.range_tasks, 0) as range_tasks,
                  coalesce(mc.tokens, 0) as tokens,
                  coalesce(mc.cost, 0) as cost,
                  coalesce(pvc.pv, 0) as pv,
                  coalesce(pvc.today_pv, 0) as today_pv,
                  greatest(w.created_at, ac.last_app_at, tc.last_task_at) as last_active_at
                from workspace w
                left join app_user u on u.id = w.owner_user_id
                left join (
                  select workspace_id, count(*) as apps,
                    count(*) filter (where published_version_id is not null) as published,
                    max(updated_at) as last_app_at
                  from app where deleted_at is null group by workspace_id
                ) ac on ac.workspace_id = w.id
                left join (
                  select workspace_id, count(*) as tasks,
                    count(*) filter (where status = 'SUCCEEDED') as ok_tasks,
                    count(*) filter (where created_at >= ?) as range_tasks,
                    max(created_at) as last_task_at
                  from task group by workspace_id
                ) tc on tc.workspace_id = w.id
                left join (
                  select t.workspace_id,
                    coalesce(sum(mu.input_tokens + mu.output_tokens), 0) as tokens,
                    coalesce(sum(mu.cost), 0) as cost
                  from model_usage mu join task t on t.id = mu.task_id
                  group by t.workspace_id
                ) mc on mc.workspace_id = w.id
                left join (
                  select a.workspace_id,
                    coalesce(sum(p.pv), 0) as pv,
                    coalesce(sum(p.pv) filter (where p.day = ?), 0) as today_pv
                  from app_pageview_daily p join app a on a.id = p.app_id
                  where a.deleted_at is null group by a.workspace_id
                ) pvc on pvc.workspace_id = w.id
                where (?::text is null or w.id = ?)
                  and (coalesce(ac.apps, 0) > 0 or coalesce(tc.tasks, 0) > 0 or coalesce(mc.tokens, 0) > 0)
                order by apps desc, tasks desc, tokens desc
                limit 25
                """).params(startTs, today, wsFilter, wsFilter).query((rs, i) -> {
            long tasks = rs.getLong("tasks");
            long ok = rs.getLong("ok_tasks");
            BigDecimal cost = rs.getBigDecimal("cost");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("workspaceId", rs.getString("id"));
            r.put("ownerName", rs.getString("owner_name"));
            r.put("apps", rs.getLong("apps"));
            r.put("published", rs.getLong("published"));
            r.put("tasks", tasks);
            r.put("rangeTasks", rs.getLong("range_tasks"));
            r.put("successRate", tasks == 0 ? 0.0 : round1((double) ok * 100.0 / tasks));
            r.put("tokens", rs.getLong("tokens"));
            r.put("cost", cost == null ? 0.0 : cost.setScale(4, RoundingMode.HALF_UP).doubleValue());
            r.put("pv", rs.getLong("pv"));
            r.put("todayPv", rs.getLong("today_pv"));
            r.put("lastActiveAt", Db.iso(Db.ts(rs, "last_active_at")));
            return r;
        }).list();
    }

    private Map<String, Object> appsSection(String wsFilter, LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byType", jdbc.sql("""
                select a.type as k,
                  count(*) as cnt,
                  count(*) filter (where a.published_version_id is not null) as pub,
                  coalesce(sum(pv_sum.pv), 0) as pv
                from app a
                left join (select app_id, sum(pv) as pv from app_pageview_daily group by app_id) pv_sum on pv_sum.app_id = a.id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                group by a.type order by cnt desc
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", rs.getString("k"));
            r.put("count", rs.getLong("cnt"));
            r.put("published", rs.getLong("pub"));
            r.put("pv", rs.getLong("pv"));
            return r;
        }).list());
        m.put("byProjectKind", groupCounts("select project_kind as k, count(*) as c from app where (?::text is null or workspace_id = ?) and deleted_at is null group by 1 order by 2 desc", wsFilter, wsFilter));
        m.put("byPhase", groupCounts("select phase as k, count(*) as c from app where (?::text is null or workspace_id = ?) and deleted_at is null group by 1 order by 2 desc", wsFilter, wsFilter));
        m.put("bySource", groupCounts("select source as k, count(*) as c from app where (?::text is null or workspace_id = ?) and deleted_at is null group by 1 order by 2 desc", wsFilter, wsFilter));
        m.put("byMode", groupCounts("select mode as k, count(*) as c from app where (?::text is null or workspace_id = ?) and deleted_at is null group by 1 order by 2 desc", wsFilter, wsFilter));
        m.put("byStyle", groupCounts("select style as k, count(*) as c from app where (?::text is null or workspace_id = ?) and deleted_at is null group by 1 order by 2 desc", wsFilter, wsFilter));

        m.put("topApps", jdbc.sql("""
                select a.id, a.workspace_id, a.name, a.type, a.project_kind, a.phase, a.style,
                  (a.published_version_id is not null) as published,
                  coalesce(ac.status = 'on', false) as cloud_enabled,
                  coalesce(sa.level, 'public') as access_level,
                  coalesce(vc.cnt, 0) as versions,
                  coalesce(tc.cnt, 0) as tasks,
                  coalesce(tc.tok, 0) as tokens_used,
                  coalesce(pvc.total_pv, 0) as total_pv,
                  coalesce(pvc.today_pv, 0) as today_pv,
                  a.updated_at
                from app a
                left join app_cloud ac on ac.app_id = a.id
                left join app_site_access sa on sa.app_id = a.id
                left join (select app_id, count(*) as cnt from version group by app_id) vc on vc.app_id = a.id
                left join (
                  select t.app_id, count(*) as cnt,
                    coalesce(sum(coalesce(mu.tok, t.token_used)), 0) as tok
                  from task t
                  left join (select task_id, sum(input_tokens + output_tokens) as tok from model_usage group by task_id) mu on mu.task_id = t.id
                  group by t.app_id
                ) tc on tc.app_id = a.id
                left join (
                  select app_id, sum(pv) as total_pv, coalesce(sum(pv) filter (where day = ?), 0) as today_pv
                  from app_pageview_daily group by app_id
                ) pvc on pvc.app_id = a.id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                order by total_pv desc, versions desc, tasks desc, a.updated_at desc
                limit 25
                """).params(today, wsFilter, wsFilter).query((rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rs.getString("id"));
            r.put("workspaceId", rs.getString("workspace_id"));
            r.put("name", rs.getString("name"));
            r.put("type", rs.getString("type"));
            r.put("projectKind", rs.getString("project_kind"));
            r.put("phase", rs.getString("phase"));
            r.put("style", rs.getString("style"));
            r.put("published", rs.getBoolean("published"));
            r.put("cloudEnabled", rs.getBoolean("cloud_enabled"));
            r.put("accessLevel", rs.getString("access_level"));
            r.put("versions", rs.getLong("versions"));
            r.put("tasks", rs.getLong("tasks"));
            r.put("tokensUsed", rs.getLong("tokens_used"));
            r.put("totalPv", rs.getLong("total_pv"));
            r.put("todayPv", rs.getLong("today_pv"));
            r.put("updatedAt", Db.iso(Db.ts(rs, "updated_at")));
            return r;
        }).list());
        return m;
    }

    private Map<String, Object> tasksSection(String wsFilter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byType", jdbc.sql("""
                select t.type,
                  count(*) as total,
                  count(*) filter (where t.status = 'SUCCEEDED') as ok,
                  count(*) filter (where t.status = 'FAILED') as fail,
                  count(*) filter (where t.status = 'CANCELLED') as cancel,
                  count(*) filter (where t.status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')) as active,
                  coalesce(avg(extract(epoch from (t.finished_at - t.started_at)) * 1000) filter (where t.started_at is not null and t.finished_at is not null and t.finished_at >= t.started_at), 0) as avg_ms,
                  coalesce(max(extract(epoch from (t.finished_at - t.started_at)) * 1000) filter (where t.started_at is not null and t.finished_at is not null and t.finished_at >= t.started_at), 0) as max_ms,
                  coalesce(avg(coalesce(mu.tok, t.token_used)), 0) as avg_tokens
                from task t
                left join (select task_id, sum(input_tokens + output_tokens) as tok from model_usage group by task_id) mu on mu.task_id = t.id
                where (?::text is null or t.workspace_id = ?)
                group by t.type order by total desc
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            long ok = rs.getLong("ok");
            long fail = rs.getLong("fail");
            long cancel = rs.getLong("cancel");
            long terminal = ok + fail + cancel;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", rs.getString("type"));
            r.put("total", rs.getLong("total"));
            r.put("succeeded", ok);
            r.put("failed", fail);
            r.put("cancelled", cancel);
            r.put("active", rs.getLong("active"));
            r.put("successRate", terminal == 0 ? 0.0 : round1((double) ok * 100.0 / terminal));
            r.put("avgDurationMs", Math.round(rs.getDouble("avg_ms")));
            r.put("maxDurationMs", Math.round(rs.getDouble("max_ms")));
            r.put("avgTokens", Math.round(rs.getDouble("avg_tokens")));
            return r;
        }).list());

        m.put("byStatus", groupCounts("select status as k, count(*) as c from task where (?::text is null or workspace_id = ?) group by 1 order by 2 desc", wsFilter, wsFilter));
        m.put("errorCodes", jdbc.sql("""
                select coalesce(nullif(error_code, ''), 'UNKNOWN') as k,
                  count(*) as c,
                  max(left(coalesce(error_message, ''), 120)) as sample
                from task where (?::text is null or workspace_id = ?) and status = 'FAILED'
                group by 1 order by 2 desc
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", rs.getString("k"));
            r.put("count", rs.getLong("c"));
            r.put("sample", rs.getString("sample"));
            return r;
        }).list());
        m.put("retries", count("select count(*) from task where (?::text is null or workspace_id = ?) and (attempt > 1 or parent_task_id is not null)", wsFilter, wsFilter));
        m.put("steerCount", count("""
                select count(*) from message msg join app a on a.id = msg.app_id
                where (?::text is null or a.workspace_id = ?) and msg.steer = true
                """, wsFilter, wsFilter));
        m.put("checkpointsCount", count("""
                select count(*) from checkpoint cp join task t on t.id = cp.task_id
                where (?::text is null or t.workspace_id = ?)
                """, wsFilter, wsFilter));
        m.put("eventsCount", count("""
                select count(*) from task_event te join task t on t.id = te.task_id
                where (?::text is null or t.workspace_id = ?)
                """, wsFilter, wsFilter));

        m.put("queue", jdbc.sql("""
                select
                  count(*) filter (where status = 'pending') as pending,
                  count(*) filter (where status = 'sent') as sent,
                  count(*) filter (where status = 'cancelled') as cancelled,
                  count(*) filter (where enable_cloud = true) as enable_cloud
                from task_message_queue where (?::text is null or workspace_id = ?)
                """).params(wsFilter, wsFilter).query((rs, i) -> Map.of(
                "pending", rs.getLong("pending"),
                "sent", rs.getLong("sent"),
                "cancelled", rs.getLong("cancelled"),
                "enableCloudCount", rs.getLong("enable_cloud")
        )).single());

        m.put("activeList", queryTaskRows("""
                where (?::text is null or t.workspace_id = ?)
                  and t.status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')
                order by t.created_at desc limit 12
                """, wsFilter));

        m.put("recentFailures", queryTaskRows("""
                where (?::text is null or t.workspace_id = ?) and t.status = 'FAILED'
                order by t.created_at desc limit 10
                """, wsFilter));

        m.put("recentTasks", queryTaskRows("""
                where (?::text is null or t.workspace_id = ?)
                order by t.created_at desc limit 15
                """, wsFilter));
        return m;
    }

    private List<Map<String, Object>> queryTaskRows(String whereOrderLimit, String wsFilter) {
        return jdbc.sql("""
                select t.id, t.workspace_id, t.app_id, coalesce(a.name, '已删除应用') as app_name,
                  t.type, t.status, t.phase_label, t.progress, t.error_code, t.error_message,
                  coalesce(mu.tok, t.token_used) as token_used,
                  case when t.started_at is not null and t.finished_at is not null and t.finished_at >= t.started_at
                    then round(extract(epoch from (t.finished_at - t.started_at)) * 1000) else 0 end as duration_ms,
                  t.created_at
                from task t
                left join app a on a.id = t.app_id
                left join (select task_id, sum(input_tokens + output_tokens) as tok from model_usage group by task_id) mu on mu.task_id = t.id
                """ + whereOrderLimit).params(wsFilter, wsFilter).query((rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rs.getString("id"));
            r.put("workspaceId", rs.getString("workspace_id"));
            r.put("appId", rs.getString("app_id"));
            r.put("appName", rs.getString("app_name"));
            r.put("type", rs.getString("type"));
            r.put("status", rs.getString("status"));
            r.put("phaseLabel", rs.getString("phase_label"));
            r.put("progress", rs.getInt("progress"));
            r.put("errorCode", rs.getString("error_code"));
            r.put("errorMessage", rs.getString("error_message"));
            r.put("tokenUsed", rs.getLong("token_used"));
            r.put("durationMs", rs.getLong("duration_ms"));
            r.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return r;
        }).list();
    }

    /** Agent 工具调用、质检门禁（类型/构建/冒烟/视觉）与确认卡遥测统计 */
    private Map<String, Object> agentTelemetrySection(String wsFilter) {
        Map<String, Object> m = new LinkedHashMap<>();

        m.put("tools", jdbc.sql("""
                select coalesce(nullif(te.payload->>'name', ''), 'unknown') as tool,
                  count(*) as calls,
                  count(*) filter (where (te.payload->>'ok')::boolean = false) as failed
                from task_event te
                join task t on t.id = te.task_id
                where (?::text is null or t.workspace_id = ?)
                  and te.type = 'tool'
                  and te.payload->>'state' = 'end'
                group by 1 order by calls desc
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            long calls = rs.getLong("calls");
            long failed = rs.getLong("failed");
            long passed = Math.max(0, calls - failed);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tool", rs.getString("tool"));
            r.put("calls", calls);
            r.put("failed", failed);
            r.put("passRate", calls == 0 ? 0.0 : round1((double) passed * 100.0 / calls));
            return r;
        }).list());

        m.put("verifyChecks", jdbc.sql("""
                select coalesce(nullif(c->>'name', ''), '未命名检查') as name,
                  count(*) as total,
                  count(*) filter (where (c->>'ok')::boolean = true) as passed,
                  count(*) filter (where (c->>'ok')::boolean = false) as failed
                from task_event te
                join task t on t.id = te.task_id,
                jsonb_array_elements(coalesce(te.payload->'checks', '[]'::jsonb)) as c
                where (?::text is null or t.workspace_id = ?) and te.type = 'verify'
                group by 1 order by total desc
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            long total = rs.getLong("total");
            long passed = rs.getLong("passed");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", rs.getString("name"));
            r.put("total", total);
            r.put("passed", passed);
            r.put("failed", rs.getLong("failed"));
            r.put("passRate", total == 0 ? 0.0 : round1((double) passed * 100.0 / total));
            return r;
        }).list());

        m.put("confirms", jdbc.sql("""
                select coalesce(nullif(te.payload->>'kind', ''), 'other') as kind,
                  coalesce(nullif(te.payload->>'state', ''), 'unknown') as state,
                  count(*) as cnt
                from task_event te
                join task t on t.id = te.task_id
                where (?::text is null or t.workspace_id = ?) and te.type = 'confirm'
                group by 1, 2 order by cnt desc
                """).params(wsFilter, wsFilter).query((rs, i) -> Map.<String, Object>of(
                "kind", rs.getString("kind"),
                "state", rs.getString("state"),
                "count", rs.getLong("cnt")
        )).list());

        m.put("eventBreakdown", jdbc.sql("""
                select
                  count(*) filter (where te.type = 'thinking') as thinking_cnt,
                  count(*) filter (where te.type = 'todo') as todo_cnt,
                  count(*) filter (where te.type = 'steer') as steer_cnt,
                  count(*) filter (where te.type = 'verify') as verify_cnt
                from task_event te join task t on t.id = te.task_id
                where (?::text is null or t.workspace_id = ?)
                """).params(wsFilter, wsFilter).query((rs, i) -> Map.of(
                "thinking", rs.getLong("thinking_cnt"),
                "todo", rs.getLong("todo_cnt"),
                "steer", rs.getLong("steer_cnt"),
                "verify", rs.getLong("verify_cnt")
        )).single());

        return m;
    }

    private Map<String, Object> llmSection(String wsFilter, String platformToken) {
        Map<String, Object> m = new LinkedHashMap<>();
        var tot = jdbc.sql("""
                select count(*) as calls,
                  coalesce(sum(mu.input_tokens), 0) as in_tok,
                  coalesce(sum(mu.output_tokens), 0) as out_tok,
                  coalesce(sum(mu.cost), 0) as cost
                from model_usage mu join task t on t.id = mu.task_id
                where (?::text is null or t.workspace_id = ?)
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            long inTok = rs.getLong("in_tok");
            long outTok = rs.getLong("out_tok");
            BigDecimal cost = rs.getBigDecimal("cost");
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("totalCalls", rs.getLong("calls"));
            tm.put("inputTokens", inTok);
            tm.put("outputTokens", outTok);
            tm.put("totalTokens", inTok + outTok);
            tm.put("totalCost", cost == null ? 0.0 : cost.setScale(4, RoundingMode.HALF_UP).doubleValue());
            return tm;
        }).single();
        m.putAll(tot);

        m.put("byModel", jdbc.sql("""
                select mu.provider, coalesce(nullif(mu.model, ''), 'default') as model,
                  count(*) as calls,
                  coalesce(sum(mu.input_tokens), 0) as in_tok,
                  coalesce(sum(mu.output_tokens), 0) as out_tok,
                  coalesce(sum(mu.cost), 0) as cost
                from model_usage mu join task t on t.id = mu.task_id
                where (?::text is null or t.workspace_id = ?)
                group by mu.provider, 2
                order by (coalesce(sum(mu.input_tokens), 0) + coalesce(sum(mu.output_tokens), 0)) desc
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            long inTok = rs.getLong("in_tok");
            long outTok = rs.getLong("out_tok");
            long calls = rs.getLong("calls");
            BigDecimal cost = rs.getBigDecimal("cost");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("provider", rs.getString("provider"));
            r.put("model", rs.getString("model"));
            r.put("calls", calls);
            r.put("inputTokens", inTok);
            r.put("outputTokens", outTok);
            r.put("totalTokens", inTok + outTok);
            r.put("avgTokensPerCall", calls == 0 ? 0L : Math.round((double) (inTok + outTok) / calls));
            r.put("cost", cost == null ? 0.0 : cost.setScale(4, RoundingMode.HALF_UP).doubleValue());
            return r;
        }).list());

        m.put("byTaskType", jdbc.sql("""
                select t.type as task_type,
                  count(*) as calls,
                  coalesce(sum(mu.input_tokens), 0) as in_tok,
                  coalesce(sum(mu.output_tokens), 0) as out_tok,
                  coalesce(sum(mu.cost), 0) as cost
                from model_usage mu join task t on t.id = mu.task_id
                where (?::text is null or t.workspace_id = ?)
                group by t.type
                order by (coalesce(sum(mu.input_tokens), 0) + coalesce(sum(mu.output_tokens), 0)) desc
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            long inTok = rs.getLong("in_tok");
            long outTok = rs.getLong("out_tok");
            BigDecimal cost = rs.getBigDecimal("cost");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("taskType", rs.getString("task_type"));
            r.put("calls", rs.getLong("calls"));
            r.put("inputTokens", inTok);
            r.put("outputTokens", outTok);
            r.put("totalTokens", inTok + outTok);
            r.put("cost", cost == null ? 0.0 : cost.setScale(4, RoundingMode.HALF_UP).doubleValue());
            return r;
        }).list());

        m.put("byAppModelSetting", groupCounts("select model as k, count(*) as c from app where (?::text is null or workspace_id = ?) and deleted_at is null group by 1 order by 2 desc", wsFilter, wsFilter));
        m.put("quota", fetchFivestarQuota(platformToken));
        return m;
    }

    private Map<String, Object> fetchFivestarQuota(String platformToken) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("enabled", fivestar.enabled());
        if (!fivestar.enabled() || openclaw == null || platformToken == null || platformToken.isBlank()) {
            q.put("available", false);
            return q;
        }
        try {
            JsonNode u = openclaw.usage(platformToken);
            q.put("available", true);
            q.put("packageName", u.path("packageName").asText(null));
            q.put("status", u.path("quotaStatus").asText(null));
            q.put("usedTokens", u.hasNonNull("totalTokens") ? u.get("totalTokens").asLong() : null);
            q.put("limitTokens", u.hasNonNull("packageTotalTokens") ? u.get("packageTotalTokens").asLong() : null);
            q.put("remainingTokens", u.hasNonNull("remainingTokens") ? u.get("remainingTokens").asLong() : null);
            q.put("resetAt", u.path("resetAt").asText(null));
        } catch (Exception ignored) {
            q.put("available", false);
        }
        return q;
    }

    private Map<String, Object> versionsSection(String wsFilter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bySource", groupCounts("""
                select v.source as k, count(*) as c from version v join app a on a.id = v.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null group by 1 order by 2 desc
                """, wsFilter, wsFilter));
        m.put("byTaskStage", groupCounts("""
                select v.task_stage as k, count(*) as c from version v join app a on a.id = v.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null group by 1 order by 2 desc
                """, wsFilter, wsFilter));
        m.put("favoritedCount", count("""
                select count(*) from version v join app a on a.id = v.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and v.favorite = true
                """, wsFilter, wsFilter));
        m.put("notedCount", count("""
                select count(*) from version v join app a on a.id = v.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and v.note is not null and btrim(v.note) <> ''
                """, wsFilter, wsFilter));
        m.put("restoredCount", count("""
                select count(*) from version v join app a on a.id = v.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and v.restored_from_id is not null
                """, wsFilter, wsFilter));
        m.put("buildsTotal", count("""
                select count(*) from build b join app a on a.id = b.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("buildsEditable", count("""
                select count(*) from build b join app a on a.id = b.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and b.editable = true
                """, wsFilter, wsFilter));
        m.put("deploymentsTotal", count("""
                select count(*) from deployment d join app a on a.id = d.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("deploymentsSucceeded", count("""
                select count(*) from deployment d join app a on a.id = d.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and lower(d.status) in ('ready', 'succeeded', 'active', 'published')
                """, wsFilter, wsFilter));
        m.put("deploymentsFailed", count("""
                select count(*) from deployment d join app a on a.id = d.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and lower(d.status) in ('failed', 'error')
                """, wsFilter, wsFilter));
        m.put("pendingDrafts", count("""
                select count(*) from draft d join app a on a.id = d.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        return m;
    }

    private Map<String, Object> cloudSection(String wsFilter) {
        Map<String, Object> m = new LinkedHashMap<>();
        long totalApps = count("select count(*) from app where (?::text is null or workspace_id = ?) and deleted_at is null", wsFilter, wsFilter);
        long enabledApps = count("""
                select count(*) from app_cloud c join app a on a.id = c.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and c.status = 'on'
                """, wsFilter, wsFilter);
        m.put("enabledApps", enabledApps);
        m.put("disabledApps", Math.max(0, totalApps - enabledApps));
        m.put("tablesCount", count("""
                select count(*) from app_cloud_table t join app a on a.id = t.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("tablePolicies", groupCounts("""
                select coalesce(nullif(t.policy->>'preset', ''), 'custom') as k, count(*) as c
                from app_cloud_table t join app a on a.id = t.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                group by 1 order by 2 desc
                """, wsFilter, wsFilter));
        m.put("siteAccessLevels", groupCounts("""
                select coalesce(sa.level, 'public') as k, count(*) as c
                from app a left join app_site_access sa on sa.app_id = a.id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                group by 1 order by 2 desc
                """, wsFilter, wsFilter));
        m.put("activeSiteSessions", count("""
                select count(*) from site_session s join app a on a.id = s.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and s.expires_at > now()
                """, wsFilter, wsFilter));

        var fnAgg = jdbc.sql("""
                select
                  count(*) as total,
                  count(*) filter (where f.ok = true) as ok_cnt,
                  count(*) filter (where f.ok = false) as err_cnt,
                  coalesce(avg(f.duration_ms), 0) as avg_ms,
                  coalesce(percentile_cont(0.95) within group (order by f.duration_ms), 0) as p95_ms
                from app_fn_log f join app a on a.id = f.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            long tot = rs.getLong("total");
            long ok = rs.getLong("ok_cnt");
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("totalCalls", tot);
            fm.put("okCalls", ok);
            fm.put("errorCalls", rs.getLong("err_cnt"));
            fm.put("successRate", tot == 0 ? 0.0 : round1((double) ok * 100.0 / tot));
            fm.put("avgDurationMs", Math.round(rs.getDouble("avg_ms")));
            fm.put("p95DurationMs", Math.round(rs.getDouble("p95_ms")));
            return fm;
        }).single();
        Map<String, Object> fnMap = new LinkedHashMap<>(fnAgg);
        fnMap.put("byName", jdbc.sql("""
                select f.name, count(*) as calls,
                  count(*) filter (where f.ok = false) as errors,
                  coalesce(avg(f.duration_ms), 0) as avg_ms
                from app_fn_log f join app a on a.id = f.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                group by f.name order by calls desc limit 10
                """).params(wsFilter, wsFilter).query((rs, i) -> Map.<String, Object>of(
                "name", rs.getString("name"),
                "calls", rs.getLong("calls"),
                "errors", rs.getLong("errors"),
                "avgDurationMs", Math.round(rs.getDouble("avg_ms"))
        )).list());
        fnMap.put("recentErrors", jdbc.sql("""
                select f.app_id, a.name as app_name, f.name, f.error, f.duration_ms, f.created_at
                from app_fn_log f join app a on a.id = f.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null and f.ok = false
                order by f.created_at desc limit 8
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("appId", rs.getString("app_id"));
            r.put("appName", rs.getString("app_name"));
            r.put("name", rs.getString("name"));
            r.put("error", rs.getString("error"));
            r.put("durationMs", rs.getInt("duration_ms"));
            r.put("at", Db.iso(Db.ts(rs, "created_at")));
            return r;
        }).list());
        m.put("functions", fnMap);
        return m;
    }

    private Map<String, Object> dataSourcesSection(String wsFilter) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workspaceCount", count("select count(*) from data_source where ((?::text is null and workspace_id is not null) or workspace_id = ?) and deleted_at is null", wsFilter, wsFilter));
        m.put("platformCount", jdbc.sql("select count(*) from data_source where workspace_id is null and deleted_at is null").query(Long.class).single());
        m.put("boundAppsCount", count("""
                select count(distinct ads.app_id) from app_data_source ads
                join app a on a.id = ads.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("totalBindings", count("""
                select count(*) from app_data_source ads
                join app a on a.id = ads.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        m.put("byProvider", groupCounts("select provider as k, count(*) as c from data_source where ((?::text is null or workspace_id = ?) or workspace_id is null) and deleted_at is null group by 1 order by 2 desc", wsFilter, wsFilter));
        m.put("byMethod", groupCounts("select method as k, count(*) as c from data_source where ((?::text is null or workspace_id = ?) or workspace_id is null) and deleted_at is null group by 1 order by 2 desc", wsFilter, wsFilter));
        m.put("byAuthType", groupCounts("select auth_type as k, count(*) as c from data_source where ((?::text is null or workspace_id = ?) or workspace_id is null) and deleted_at is null group by 1 order by 2 desc", wsFilter, wsFilter));

        var callAgg = jdbc.sql("""
                select
                  count(*) as total,
                  count(*) filter (where c.ok = true) as ok_cnt,
                  count(*) filter (where c.ok = false) as err_cnt,
                  coalesce(avg(c.duration_ms), 0) as avg_ms
                from data_source_call c
                left join app a on a.id = c.app_id
                left join data_source ds on ds.id = c.source_id
                where (?::text is null or a.workspace_id = ? or ds.workspace_id = ?)
                """).params(wsFilter, wsFilter, wsFilter).query((rs, i) -> {
            long tot = rs.getLong("total");
            long ok = rs.getLong("ok_cnt");
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("totalCalls", tot);
            cm.put("okCalls", ok);
            cm.put("errorCalls", rs.getLong("err_cnt"));
            cm.put("successRate", tot == 0 ? 0.0 : round1((double) ok * 100.0 / tot));
            cm.put("avgDurationMs", Math.round(rs.getDouble("avg_ms")));
            return cm;
        }).single();
        Map<String, Object> callsMap = new LinkedHashMap<>(callAgg);
        callsMap.put("byOrigin", jdbc.sql("""
                select c.origin, count(*) as calls, count(*) filter (where c.ok = false) as errors
                from data_source_call c
                left join app a on a.id = c.app_id
                left join data_source ds on ds.id = c.source_id
                where (?::text is null or a.workspace_id = ? or ds.workspace_id = ?)
                group by c.origin order by calls desc
                """).params(wsFilter, wsFilter, wsFilter).query((rs, i) -> Map.<String, Object>of(
                "origin", rs.getString("origin"),
                "calls", rs.getLong("calls"),
                "errors", rs.getLong("errors")
        )).list());
        callsMap.put("topSources", jdbc.sql("""
                select ds.id, ds.key, ds.name, ds.provider,
                  count(*) as calls,
                  count(*) filter (where c.ok = false) as errors,
                  coalesce(avg(c.duration_ms), 0) as avg_ms
                from data_source_call c
                join data_source ds on ds.id = c.source_id
                left join app a on a.id = c.app_id
                where (?::text is null or a.workspace_id = ? or ds.workspace_id = ?)
                group by ds.id, ds.key, ds.name, ds.provider
                order by calls desc limit 10
                """).params(wsFilter, wsFilter, wsFilter).query((rs, i) -> Map.<String, Object>of(
                "id", rs.getString("id"),
                "key", rs.getString("key"),
                "name", rs.getString("name"),
                "provider", rs.getString("provider"),
                "calls", rs.getLong("calls"),
                "errors", rs.getLong("errors"),
                "avgDurationMs", Math.round(rs.getDouble("avg_ms"))
        )).list());
        m.put("calls", callsMap);
        return m;
    }

    private Map<String, Object> interactionsSection(String wsFilter) {
        Map<String, Object> m = new LinkedHashMap<>();

        long convTotal = count("select count(*) from conversation where (?::text is null or workspace_id = ?) and deleted_at is null", wsFilter, wsFilter);
        long convConverted = count("""
                select count(distinct ac.conversation_id) from app_conversation ac
                join app a on a.id = ac.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter);
        var convMsgs = jdbc.sql("""
                select
                  count(*) filter (where cm.role = 'user') as user_msgs,
                  count(*) filter (where cm.role = 'assistant') as assistant_msgs,
                  count(*) filter (where cm.thinking is not null and length(cm.thinking) > 0) as thinking_msgs
                from conversation_message cm join conversation c on c.id = cm.conversation_id
                where (?::text is null or c.workspace_id = ?) and c.deleted_at is null
                """).params(wsFilter, wsFilter).query((rs, i) -> Map.of(
                "userMessages", rs.getLong("user_msgs"),
                "assistantMessages", rs.getLong("assistant_msgs"),
                "thinkingMessages", rs.getLong("thinking_msgs")
        )).single();
        var convFiles = jdbc.sql("""
                select count(distinct (cf.conversation_id || '/' || cf.name)) as unique_files, count(*) as file_versions
                from conversation_file cf join conversation c on c.id = cf.conversation_id
                where (?::text is null or c.workspace_id = ?) and c.deleted_at is null and cf.deleted_at is null
                """).params(wsFilter, wsFilter).query((rs, i) -> Map.of(
                "filesTotal", rs.getLong("unique_files"),
                "fileVersionsTotal", rs.getLong("file_versions")
        )).single();
        Map<String, Object> convMap = new LinkedHashMap<>();
        convMap.put("total", convTotal);
        convMap.put("convertedToApp", convConverted);
        convMap.put("conversionRate", convTotal == 0 ? 0.0 : round1((double) convConverted * 100.0 / convTotal));
        convMap.putAll(convMsgs);
        convMap.putAll(convFiles);
        m.put("conversations", convMap);

        m.put("workbench", jdbc.sql("""
                select
                  count(*) as total,
                  count(*) filter (where msg.role = 'user') as user_msgs,
                  count(*) filter (where msg.role = 'assistant') as assistant_msgs,
                  count(*) filter (where msg.role = 'system') as system_msgs,
                  count(*) filter (where msg.element_context is not null) as elem_msgs,
                  count(*) filter (where msg.skill_app_ids is not null and jsonb_array_length(msg.skill_app_ids) > 0) as skill_msgs,
                  count(*) filter (where msg.asset_ids is not null and jsonb_array_length(msg.asset_ids) > 0) as asset_msgs,
                  count(*) filter (where msg.steer = true) as steer_msgs
                from message msg join app a on a.id = msg.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            Map<String, Object> wb = new LinkedHashMap<>();
            wb.put("totalMessages", rs.getLong("total"));
            wb.put("userMessages", rs.getLong("user_msgs"));
            wb.put("assistantMessages", rs.getLong("assistant_msgs"));
            wb.put("systemMessages", rs.getLong("system_msgs"));
            wb.put("elementContextMessages", rs.getLong("elem_msgs"));
            wb.put("skillAttachedMessages", rs.getLong("skill_msgs"));
            wb.put("assetAttachedMessages", rs.getLong("asset_msgs"));
            wb.put("steerMessages", rs.getLong("steer_msgs"));
            return wb;
        }).single());

        Map<String, Object> assetMap = new LinkedHashMap<>();
        assetMap.put("total", count("select count(*) from asset where (?::text is null or workspace_id = ?)", wsFilter, wsFilter));
        assetMap.put("totalBytes", count("select coalesce(sum(size), 0) from asset where (?::text is null or workspace_id = ?)", wsFilter, wsFilter));
        assetMap.put("byCategory", jdbc.sql("""
                select
                  case
                    when mime like 'image/%' then '图片'
                    when mime = 'application/pdf' then 'PDF 文档'
                    when mime like '%wordprocessingml%' or mime like '%msword%' then 'Word 文档'
                    when mime like '%spreadsheetml%' or mime like '%excel%' or mime = 'text/csv' then '表格 / CSV'
                    when mime like '%presentationml%' or mime like '%powerpoint%' then 'PPT 演示'
                    else '文本 / 其他'
                  end as cat,
                  count(*) as cnt,
                  coalesce(sum(size), 0) as bytes
                from asset where (?::text is null or workspace_id = ?)
                group by 1 order by cnt desc
                """).params(wsFilter, wsFilter).query((rs, i) -> Map.<String, Object>of(
                "category", rs.getString("cat"),
                "count", rs.getLong("cnt"),
                "bytes", rs.getLong("bytes")
        )).list());
        assetMap.put("uploadsTotal", count("select count(*) from upload where (?::text is null or workspace_id = ?)", wsFilter, wsFilter));
        assetMap.put("uploadsByFormat", groupCounts("select format as k, count(*) as c from upload where (?::text is null or workspace_id = ?) group by 1 order by 2 desc", wsFilter, wsFilter));
        m.put("assets", assetMap);

        Map<String, Object> helpMap = new LinkedHashMap<>(jdbc.sql("""
                select
                  (select count(*) from help_conversation where (?::text is null or workspace_id = ?) and deleted_at is null) as convs,
                  (select count(*) from help_message hm join help_conversation hc on hc.id = hm.conversation_id where (?::text is null or hc.workspace_id = ?) and hm.role = 'user') as questions,
                  (select count(*) from help_message hm join help_conversation hc on hc.id = hm.conversation_id where (?::text is null or hc.workspace_id = ?) and hm.role = 'assistant') as replies,
                  (select count(*) from help_feedback where (?::text is null or workspace_id = ?)) as feedbacks
                """).params(wsFilter, wsFilter, wsFilter, wsFilter, wsFilter, wsFilter, wsFilter, wsFilter).query((rs, i) -> Map.of(
                "conversations", rs.getLong("convs"),
                "userQuestions", rs.getLong("questions"),
                "assistantReplies", rs.getLong("replies"),
                "feedbackCount", rs.getLong("feedbacks")
        )).single());
        helpMap.put("recentFeedbacks", jdbc.sql("""
                select id, workspace_id, content, created_at
                from help_feedback where (?::text is null or workspace_id = ?)
                order by created_at desc limit 10
                """).params(wsFilter, wsFilter).query((rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rs.getString("id"));
            r.put("workspaceId", rs.getString("workspace_id"));
            r.put("content", rs.getString("content"));
            r.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return r;
        }).list());
        m.put("help", helpMap);
        return m;
    }

    private Map<String, Object> ecosystemSection(String wsFilter, String currentWs) {
        Map<String, Object> m = new LinkedHashMap<>();

        Map<String, Object> skills = new LinkedHashMap<>();
        skills.put("selfBuiltCount", count("select count(*) from app where (?::text is null or workspace_id = ?) and deleted_at is null and project_kind = 'skill'", wsFilter, wsFilter));
        skills.put("builtinCount", skillRefs.builtinList().size());
        var hubInst = jdbc.sql("""
                select count(*) as cnt, coalesce(sum(size_bytes), 0) as bytes, coalesce(sum(file_count), 0) as files
                from skillhub_install where (?::text is null or workspace_id = ?) and deleted_at is null
                """).params(wsFilter, wsFilter).query((rs, i) -> Map.of(
                "hubInstalledCount", rs.getLong("cnt"),
                "hubInstalledBytes", rs.getLong("bytes"),
                "hubInstalledFiles", rs.getLong("files")
        )).single();
        skills.putAll(hubInst);
        skills.put("publicationsCount", count("""
                select count(*) from skill_publication sp join app a on a.id = sp.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                """, wsFilter, wsFilter));
        skills.put("publicationsByReview", groupCounts("""
                select coalesce(nullif(sp.review_status, ''), coalesce(nullif(sp.status, ''), 'submitted')) as k, count(*) as c
                from skill_publication sp join app a on a.id = sp.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                group by 1 order by 2 desc
                """, wsFilter, wsFilter));
        skills.put("publicationsByVisibility", groupCounts("""
                select sp.visibility as k, count(*) as c
                from skill_publication sp join app a on a.id = sp.app_id
                where (?::text is null or a.workspace_id = ?) and a.deleted_at is null
                group by 1 order by 2 desc
                """, wsFilter, wsFilter));
        m.put("skills", skills);

        var myComm = jdbc.sql("""
                select count(*) as works,
                  coalesce(sum(view_count), 0) as views,
                  coalesce(sum(clone_count), 0) as clones,
                  coalesce(sum(like_count), 0) as likes
                from template where workspace_id = ?
                """).param(currentWs).query((rs, i) -> {
            Map<String, Object> mc = new LinkedHashMap<>();
            mc.put("publishedWorks", rs.getLong("works"));
            mc.put("totalViews", rs.getLong("views"));
            mc.put("totalClones", rs.getLong("clones"));
            mc.put("totalLikes", rs.getLong("likes"));
            mc.put("likedByMe", count("select count(*) from template_like where workspace_id = ?", currentWs));
            return mc;
        }).single();
        m.put("myCommunity", myComm);

        Map<String, Object> platComm = jdbc.sql("""
                select count(*) as works,
                  coalesce(sum(view_count), 0) as views,
                  coalesce(sum(clone_count), 0) as clones,
                  coalesce(sum(like_count), 0) as likes
                from template
                """).query((rs, i) -> {
            Map<String, Object> pc = new LinkedHashMap<>();
            pc.put("totalWorks", rs.getLong("works"));
            pc.put("totalViews", rs.getLong("views"));
            pc.put("totalClones", rs.getLong("clones"));
            pc.put("totalLikes", rs.getLong("likes"));
            return pc;
        }).single();
        Map<String, Object> platCommFull = new LinkedHashMap<>(platComm);
        platCommFull.put("byAppType", jdbc.sql("""
                select app_type as type, count(*) as cnt,
                  coalesce(sum(like_count), 0) as likes,
                  coalesce(sum(clone_count), 0) as clones
                from template group by app_type order by cnt desc
                """).query((rs, i) -> Map.<String, Object>of(
                "type", rs.getString("type"),
                "count", rs.getLong("cnt"),
                "likes", rs.getLong("likes"),
                "clones", rs.getLong("clones")
        )).list());
        platCommFull.put("topWorks", jdbc.sql("""
                select id, name, app_type, coalesce(nullif(author, ''), '同事') as author,
                  view_count, clone_count, like_count, created_at
                from template
                order by (like_count * 3 + clone_count * 5 + view_count) desc, created_at desc
                limit 10
                """).query((rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", rs.getString("id"));
            r.put("name", rs.getString("name"));
            r.put("appType", rs.getString("app_type"));
            r.put("author", rs.getString("author"));
            r.put("views", rs.getLong("view_count"));
            r.put("clones", rs.getLong("clone_count"));
            r.put("likes", rs.getLong("like_count"));
            r.put("createdAt", Db.iso(Db.ts(rs, "created_at")));
            return r;
        }).list());
        m.put("platformCommunity", platCommFull);
        return m;
    }

    private Map<String, Object> systemSection() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runtime", runtimeMetrics == null ? Map.of() : runtimeMetrics.snapshot());

        List<Map<String, Object>> rateBuckets = jdbc.sql("""
                select split_part(bucket, ':', 1) as k, count(*) as c
                from rate_limit_hit
                where created_at >= now() - interval '10 minutes'
                group by 1 order by 2 desc
                """).query((rs, i) -> Map.<String, Object>of(
                "bucket", rs.getString("k"),
                "count", rs.getLong("c")
        )).list();
        long hits10m = rateBuckets.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
        m.put("rateLimits", Map.of("hitsLast10Min", hits10m, "byBucket", rateBuckets));

        m.put("taskSignals", jdbc.sql("""
                select
                  count(*) filter (where consumed_at is null) as pending,
                  count(*) filter (where consumed_at is not null) as consumed
                from task_signal
                """).query((rs, i) -> Map.of(
                "pending", rs.getLong("pending"),
                "consumed", rs.getLong("consumed")
        )).single());

        m.put("platformPulse", jdbc.sql("""
                select
                  (select count(*) from workspace) as workspaces,
                  (select count(*) from app_user) as sso_users,
                  (select count(*) from app where deleted_at is null) as total_apps,
                  (select count(*) from app where deleted_at is null and published_version_id is not null) as published_apps,
                  (select count(*) from task) as total_tasks,
                  (select count(*) from task where status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING')) as active_tasks,
                  (select count(*) from version v join app a on a.id = v.app_id where a.deleted_at is null) as total_versions,
                  (select coalesce(sum(p.pv), 0) from app_pageview_daily p join app a on a.id = p.app_id where a.deleted_at is null) as total_pv
                """).query((rs, i) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("workspaces", rs.getLong("workspaces"));
            p.put("ssoUsers", rs.getLong("sso_users"));
            p.put("totalApps", rs.getLong("total_apps"));
            p.put("publishedApps", rs.getLong("published_apps"));
            p.put("totalTasks", rs.getLong("total_tasks"));
            p.put("activeTasks", rs.getLong("active_tasks"));
            p.put("totalVersions", rs.getLong("total_versions"));
            p.put("totalPv", rs.getLong("total_pv"));
            return p;
        }).single());
        return m;
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private List<Map<String, Object>> groupCounts(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query((rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", rs.getString("k"));
            r.put("count", rs.getLong("c"));
            return r;
        }).list();
    }

    private Map<LocalDate, Long> queryDailyTsCounts(String sql, String wsFilter, OffsetDateTime startTs) {
        Map<LocalDate, Long> map = new HashMap<>();
        jdbc.sql(sql).params(wsFilter, wsFilter, startTs).query((rs, i) -> {
            map.put(rs.getObject("d", LocalDate.class), rs.getLong("c"));
            return null;
        }).list();
        return map;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
