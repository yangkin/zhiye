package com.tcl.csot.zhiye.service.source;

import com.tcl.csot.zhiye.config.SourceProperties;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.tcl.csot.zhiye.util.Json;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/** 启动时同步平台登记的数据源（zhiye.sources.file，JSON 数组）；凭证只引用环境变量。 */
@Component
@Profile("api")
public class SourceSync {
    private static final Logger log = LoggerFactory.getLogger(SourceSync.class);
    private final DataSourceService sources;
    private final SourceProperties props;
    private final ZhiyeProperties zhiye;

    public SourceSync(DataSourceService sources, SourceProperties props, ZhiyeProperties zhiye) {
        this.sources = sources;
        this.props = props;
        this.zhiye = zhiye;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sync() {
        if (props.file() == null || props.file().isBlank()) return;
        Path p = zhiye.resolveFromRoot(props.file());
        try {
            if (!Files.isRegularFile(p)) { log.warn("平台数据源文件不存在：{}", p); return; }
            var node = Json.M.readTree(Files.readString(p));
            if (!(node instanceof ArrayNode arr)) { log.warn("平台数据源文件必须是 JSON 数组：{}", p); return; }
            log.info("已同步平台数据源 {} 个（{}）", sources.syncPlatform(arr), p);
        } catch (Exception e) {
            log.error("同步平台数据源失败（{}）：{}", p, e.getMessage());
        }
    }
}
