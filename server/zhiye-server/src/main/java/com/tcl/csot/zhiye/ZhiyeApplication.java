package com.tcl.csot.zhiye;

import com.tcl.csot.zhiye.config.ZhiyeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ZhiyeProperties.class)
public class ZhiyeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZhiyeApplication.class, args);
    }
}
