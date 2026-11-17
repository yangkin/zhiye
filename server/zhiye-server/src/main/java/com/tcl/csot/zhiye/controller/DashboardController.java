package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.app.DashboardService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.PlatformToken;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 全景统计看板接口：支持全平台管理视角（platform）与当前工作空间视角（workspace）。 */
@RestController
@Profile("api")
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    private final CurrentPrincipal principal;

    public DashboardController(DashboardService dashboard, CurrentPrincipal principal) {
        this.dashboard = dashboard;
        this.principal = principal;
    }

    @GetMapping
    public Map<String, Object> get(@RequestParam(defaultValue = "30d") String range,
                                   @RequestParam(defaultValue = "platform") String scope,
                                   HttpServletRequest req) {
        return dashboard.snapshot(principal.workspaceId(), range, scope, PlatformToken.of(req));
    }
}
