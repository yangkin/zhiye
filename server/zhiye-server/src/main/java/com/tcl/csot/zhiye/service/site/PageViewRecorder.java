package com.tcl.csot.zhiye.service.site;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** 已发布站点的 PV 统计：内存中累加，每秒批量写入 app_pageview_daily（不阻塞页面响应）。 */
@Component
public class PageViewRecorder {
    private static final Logger log = LoggerFactory.getLogger(PageViewRecorder.class);
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern BOT = Pattern.compile("(?i)bot|spider|crawler");

    private record Key(String appId, LocalDate day) {}

    private final Map<Key, AtomicLong> pending = new ConcurrentHashMap<>();
    private final JdbcClient jdbc;

    public PageViewRecorder(JdbcClient jdbc) { this.jdbc = jdbc; }

    public static boolean isBot(String userAgent) { return userAgent != null && BOT.matcher(userAgent).find(); }

    public void record(String appId) {
        pending.computeIfAbsent(new Key(appId, LocalDate.now(ZONE)), k -> new AtomicLong()).incrementAndGet();
    }

    @Scheduled(fixedDelay = 1000)
    @PreDestroy
    public synchronized void flush() {
        for (Key k : pending.keySet()) {
            AtomicLong c = pending.remove(k);
            long n = c == null ? 0 : c.getAndSet(0);
            if (n <= 0) continue;
            try {
                jdbc.sql("insert into app_pageview_daily (app_id, day, pv) values (?, ?, ?) on conflict (app_id, day) do update set pv = app_pageview_daily.pv + excluded.pv")
                        .params(k.appId(), k.day(), n).update();
            } catch (Exception e) {
                log.warn("写入访问量失败：{}", e.getMessage());
                pending.computeIfAbsent(k, x -> new AtomicLong()).addAndGet(n);
            }
        }
    }
}
