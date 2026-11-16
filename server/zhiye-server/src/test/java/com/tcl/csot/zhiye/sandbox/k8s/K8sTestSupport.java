package com.tcl.csot.zhiye.sandbox.k8s;

import com.tcl.csot.zhiye.config.ZhiyeProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

/** 用 Spring Binder 生成带默认值的配置记录（与线上绑定方式一致）。 */
final class K8sTestSupport {
    private K8sTestSupport() {}

    static K8sSandboxProperties k8s(Map<String, String> overrides) {
        Map<String, String> m = new HashMap<>(overrides);
        return new Binder(new MapConfigurationPropertySource(m))
                .bindOrCreate("zhiye.sandbox.k8s", Bindable.of(K8sSandboxProperties.class));
    }

    static ZhiyeProperties zhiye(String runtimeDir) {
        Map<String, String> m = new HashMap<>();
        m.put("zhiye.runtime-dir", runtimeDir);
        m.put("zhiye.task.max-duration-minutes", "15");
        return new Binder(new MapConfigurationPropertySource(m)).bindOrCreate("zhiye", Bindable.of(ZhiyeProperties.class));
    }
}
