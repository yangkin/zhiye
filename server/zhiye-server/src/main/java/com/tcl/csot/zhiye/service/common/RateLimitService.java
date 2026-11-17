package com.tcl.csot.zhiye.service.common;

import com.tcl.csot.zhiye.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 PostgreSQL 事务咨询锁（pg_advisory_xact_lock）的跨实例原子滑动窗口限流器，
 * 无需引入外部缓存中间件即可保证多副本并发请求下的严格计数。
 */
@Service
public class RateLimitService {
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AtomicInteger ops = new AtomicInteger();

    public RateLimitService(JdbcClient jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public void check(String bucket, int maxRequests, int windowSeconds, String code, String message) {
        if (bucket == null || bucket.isBlank() || maxRequests <= 0) return;
        boolean[] allowed = {false};
        long[] observedCount = {0};
        tx.executeWithoutResult(status -> {
            // 按桶哈希加事务级咨询锁，保证同一桶内的清理→计数→写入严格串行原子执行
            jdbc.sql("select pg_advisory_xact_lock(hashtext(?))").param(bucket).query((rs, i) -> 0).list();
            jdbc.sql("delete from rate_limit_hit where bucket = ? and created_at < now() - make_interval(secs => ?)")
                    .params(bucket, windowSeconds).update();
            long count = jdbc.sql("select count(*) from rate_limit_hit where bucket = ? and created_at >= now() - make_interval(secs => ?)")
                    .params(bucket, windowSeconds).query(Long.class).single();
            observedCount[0] = count;
            if (count < maxRequests) {
                jdbc.sql("insert into rate_limit_hit (bucket, created_at) values (?, clock_timestamp())")
                        .param(bucket).update();
                allowed[0] = true;
            }
        });
        recordEvent(bucket, allowed[0] ? "allowed" : "rejected", allowed[0] ? null : code,
                windowSeconds, maxRequests, observedCount[0]);
        if ((ops.incrementAndGet() & 127) == 0) {
            jdbc.sql("delete from rate_limit_hit where created_at < now() - interval '10 minutes'").update();
            jdbc.sql("delete from rate_limit_event where created_at < now() - interval '7 days'").update();
        }
        if (!allowed[0]) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
        }
    }

    private void recordEvent(String bucket, String outcome, String code,
                             int windowSeconds, int maxRequests, long currentCount) {
        jdbc.sql("""
                insert into rate_limit_event (bucket, outcome, code, window_seconds, max_requests, current_count, created_at)
                values (?, ?, ?, ?, ?, ?, clock_timestamp())
                """)
                .params(bucket, outcome, code, Math.max(1, windowSeconds), maxRequests, currentCount)
                .update();
    }
}
