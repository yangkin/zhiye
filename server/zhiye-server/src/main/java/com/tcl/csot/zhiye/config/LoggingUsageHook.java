package com.tcl.csot.zhiye.config;

import com.tcl.csot.zhiye.service.llm.UsageHook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 默认额度实现：全部放行，只记日志 */
@Configuration
public class LoggingUsageHook {
    private static final Logger log = LoggerFactory.getLogger("zhiye.usage");

    @Bean
    @ConditionalOnMissingBean(UsageHook.class)
    public UsageHook usageHook() {
        return new UsageHook() {
            @Override public void before(String appId, String userId, String kind) { }
            @Override public void after(String appId, String userId, String kind, long amount) {
                log.info("usage app={} user={} kind={} amount={}", appId, userId, kind, amount);
            }
        };
    }
}
