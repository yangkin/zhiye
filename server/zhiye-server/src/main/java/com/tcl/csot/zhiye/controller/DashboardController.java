package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.admin.AdminAuthService;
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

/** 平台管理侧全景统计看板接口：仅管理员可查全平台或跨空间下钻，普通用户强制鉴权隔离到自身工作空间（否则返回 403）。 */
@RestController
@Profile("api")
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService dashboard;
    private final CurrentPrincipal principal;
    private final AdminAuthService adminAuth;

    public DashboardController(DashboardService dashboard, CurrentPrincipal principal, AdminAuthService adminAuth) {
        this.dashboard = dashboard;
        this.principal = principal;
        this.adminAuth = adminAuth;
    }

    @GetMapping
    public Map<String, Object> get(@RequestParam(defaultValue = "30d") String range,
                                   @RequestParam(defaultValue = "platform") String scope,
                                   @RequestParam(required = false) String startDate,
                                   @RequestParam(required = false) String endDate,
                                   @RequestParam(required = false) String workspaceId,
                                   @RequestParam(required = false) String userId,
                                   HttpServletRequest req) {
        String currentWs = principal.workspaceId();
        var access = adminAuth.authorizeDashboard(req, currentWs, scope, workspaceId, userId);
        return dashboard.snapshot(currentWs, range, access.scope(), startDate, endDate,
                access.workspaceId(), access.userId(), PlatformToken.of(req), access.admin());
    }
}
