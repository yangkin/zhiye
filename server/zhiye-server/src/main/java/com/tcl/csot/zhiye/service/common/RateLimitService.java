package com.tcl.csot.zhiye.service.common;

import com.tcl.csot.zhiye.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 PostgreSQL 的跨实例滑动窗口限流器，无需引入外部缓存中间件即可在多副本间共享计数。
 */
@Service
public class RateLimitService {
    private final JdbcClient jdbc;
    private final AtomicInteger ops = new AtomicInteger();

    public RateLimitService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void check(String bucket, int maxRequests, int windowSeconds, String code, String message) {
        if (bucket == null || bucket.isBlank() || maxRequests <= 0) return;
        jdbc.sql("delete from rate_limit_hit where bucket = ? and created_at < now() - make_interval(secs => ?)")
                .params(bucket, windowSeconds).update();
        long count = jdbc.sql("select count(*) from rate_limit_hit where bucket = ? and created_at >= now() - make_interval(secs => ?)")
                .params(bucket, windowSeconds).query(Long.class).single();
        if (count >= maxRequests) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message);
        }
        jdbc.sql("insert into rate_limit_hit (bucket, created_at) values (?, clock_timestamp())")
                .param(bucket).update();
        if ((ops.incrementAndGet() & 127) == 0) {
            jdbc.sql("delete from rate_limit_hit where created_at < now() - interval '10 minutes'").update();
        }
    }
}
