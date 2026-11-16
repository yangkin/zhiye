package com.tcl.csot.zhiye.storage;

import com.tcl.csot.zhiye.config.ZhiyeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class StorageConfig {
    @Bean
    public ObjectStore objectStore(ZhiyeProperties props) throws IOException {
        if ("s3".equalsIgnoreCase(props.storage().type())) {
            var s = props.storage().s3();
            return new S3ObjectStore(s.endpoint(), s.region(), s.bucket(), s.accessKey(), s.secretKey(), s.pathStyle());
        }
        return new LocalFsObjectStore(props.runtimePath().resolve("objects"));
    }
}
