package com.tcl.csot.zhiye.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/** 提示文字的语言：按请求头 Accept-Language 选 zh_CN / en_US，没有或不支持时用中文。 */
@Configuration
public class I18nConfig {
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver r = new AcceptHeaderLocaleResolver();
        r.setSupportedLocales(List.of(Locale.SIMPLIFIED_CHINESE, Locale.US));
        r.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        return r;
    }
}
