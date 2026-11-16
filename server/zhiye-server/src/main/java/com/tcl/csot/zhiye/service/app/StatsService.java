package com.tcl.csot.zhiye.service.app;

import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.repository.AppRepository;

import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.service.site.PageViewRecorder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** 应用数据统计：已发布站点的页面访问量（PV）。 */
@Service
public class StatsService {
    static final int MAX_DAYS = 180;

    private final JdbcClient jdbc;
    private final AppRepository apps;

    public StatsService(JdbcClient jdbc, AppRepository apps) {
        this.jdbc = jdbc;
        this.apps = apps;
    }

    public Map<String, Object> stats(String ws, String id, String range) {
        AppRow app = apps.find(ws, id).orElseThrow(() -> ApiException.notFound("应用"));
        LocalDate today = LocalDate.now(PageViewRecorder.ZONE);
        Map<LocalDate, Long> byDay = new HashMap<>();
        jdbc.sql("select day, pv from app_pageview_daily where app_id = ?").param(app.id())
                .query((rs, i) -> byDay.put(rs.getObject("day", LocalDate.class), rs.getLong("pv"))).list();
        long total = byDay.values().stream().mapToLong(Long::longValue).sum();
        LocalDate start = switch (range == null ? "7d" : range) {
            case "today" -> today;
            case "3d" -> today.minusDays(2);
            case "7d" -> today.minusDays(6);
            case "all" -> {
                LocalDate first = byDay.keySet().stream().min(Comparator.naturalOrder()).orElse(today);
                Optional<OffsetDateTime> pub = jdbc.sql("select min(created_at) from deployment where app_id = ?").param(app.id())
                        .query(OffsetDateTime.class).optional();
                if (pub.isPresent() && pub.get() != null) {
                    LocalDate p = pub.get().atZoneSameInstant(PageViewRecorder.ZONE).toLocalDate();
                    if (p.isBefore(first)) first = p;
                }
                if (first.isAfter(today)) first = today;
                yield first.isBefore(today.minusDays(MAX_DAYS - 1)) ? today.minusDays(MAX_DAYS - 1) : first;
            }
            default -> throw ApiException.validation("range 只能是 all、today、7d、3d");
        };
        List<Map<String, Object>> daily = new ArrayList<>();
        long rangePv = 0;
        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            long pv = byDay.getOrDefault(d, 0L);
            rangePv += pv;
            daily.add(Map.of("date", d.toString(), "pv", pv));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("todayPv", byDay.getOrDefault(today, 0L));
        m.put("totalPv", total);
        m.put("rangePv", rangePv);
        m.put("daily", daily);
        return m;
    }

    public Map<String, Object> summary(String ws) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", jdbc.sql("select count(*) from app where workspace_id = ? and deleted_at is null and visible").param(ws).query(Long.class).single());
        m.put("published", jdbc.sql("select count(*) from app where workspace_id = ? and deleted_at is null and visible and published_version_id is not null")
                .param(ws).query(Long.class).single());
        m.put("totalPv", jdbc.sql("select coalesce(sum(p.pv), 0) from app_pageview_daily p join app a on a.id = p.app_id where a.workspace_id = ? and a.deleted_at is null and a.visible")
                .param(ws).query(Long.class).single());
        return m;
    }
}
