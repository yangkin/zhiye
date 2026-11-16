package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.cloud.CloudService;

import com.tcl.csot.zhiye.util.CurrentPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/** 云服务控制面：按工作空间鉴权（应用的主人才能启用/关闭/取状态）。 */
@RestController
@RequestMapping("/api/apps/{appId}/cloud")
public class CloudController {
    private final CloudService cloud;
    private final CurrentPrincipal principal;

    public CloudController(CloudService cloud, CurrentPrincipal principal) {
        this.cloud = cloud;
        this.principal = principal;
    }

    @GetMapping
    public Map<String, Object> status(@PathVariable String appId) {
        return cloud.status(principal.workspaceId(), appId);
    }

    @PostMapping("/enable")
    public Map<String, Object> enable(@PathVariable String appId) {
        return cloud.enable(principal.workspaceId(), appId);
    }

    @PostMapping("/disable")
    public Map<String, Object> disable(@PathVariable String appId) {
        cloud.disable(principal.workspaceId(), appId);
        return Map.of("status", "off");
    }

    /** 重新取一个可用令牌（启用时那个已经过期了也能取） */
    @PostMapping("/token")
    public Map<String, Object> token(@PathVariable String appId) {
        cloud.status(principal.workspaceId(), appId);
        return Map.of("token", cloud.mint(appId, 24 * 3600), "expiresIn", 24 * 3600);
    }
}