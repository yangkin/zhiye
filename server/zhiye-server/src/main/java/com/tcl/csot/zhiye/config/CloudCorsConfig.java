package com.tcl.csot.zhiye.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 云服务数据面跨源配置：预览页在工作台的 sandbox iframe 里加载（无 allow-same-origin，Origin 为 null），
 * 而 /cloud/** 在 api 域，所以需要允许跨源调用。
 * 令牌走 Authorization 头，不用 cookie，因此不开启 allowCredentials。
 */
@Configuration
public class CloudCorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/cloud/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .maxAge(3600);
    }
}