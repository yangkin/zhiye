package com.tcl.csot.zhiye.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("zhiye.agw.client")
public record AgwClientProperties(String clientId,
                                  String clientSecret,
                                  @DefaultValue("http://testmetis.csot.tcl.com/oauth/oauth/token") String tokenUrl,
                                  @DefaultValue("https://apitest.csot.tcl.com/api/agw-admin") String baseUrl,
                                  String scope,
                                  @DefaultValue("auto") String dataAuthMode) {
    @ConstructorBinding
    public AgwClientProperties {}

    public AgwClientProperties(String clientId, String clientSecret, String tokenUrl, String baseUrl, String scope) {
        this(clientId, clientSecret, tokenUrl, baseUrl, scope, "auto");
    }
}
