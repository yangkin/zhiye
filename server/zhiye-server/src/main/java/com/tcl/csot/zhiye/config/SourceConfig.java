package com.tcl.csot.zhiye.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SourceProperties.class, AgwProperties.class})
public class SourceConfig {}
