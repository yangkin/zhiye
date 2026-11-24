package com.tcl.csot.zhiye.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.entity.SsoUser;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.service.auth.AuthService;
import com.tcl.csot.zhiye.service.runtime.MonolithServiceManager;
import com.tcl.csot.zhiye.service.runtime.ServiceProxyHandler;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 单体后端应用托管控制台 API（配置、环境变量、部署、启停、休眠、实时日志、Dockerfile 预览与工作台实时代理）。 */
@RestController
@Profile("api")
@RequestMapping("/api/apps/{appId}/service")
public class MonolithServiceController {
    private final MonolithServiceManager manager;
    private final ServiceProxyHandler proxy;
    private final AppRepository apps;
    private final CurrentPrincipal principal;
    private final AuthService ssoAuth;

    public MonolithServiceController(MonolithServiceManager manager, ServiceProxyHandler proxy, AppRepository apps,
                                     CurrentPrincipal principal,
                                     @org.springframework.beans.factory.annotation.Autowired(required = false) AuthService ssoAuth) {
        this.manager = manager;
        this.proxy = proxy;
        this.apps = apps;
        this.principal = principal;
        this.ssoAuth = ssoAuth;
    }

    private String ws() { return principal.workspaceId(); }

    @GetMapping
    public Map<String, Object> status(@PathVariable String appId) {
        return manager.statusView(ws(), appId);
    }

    @PatchMapping
    public Map<String, Object> updateConfig(@PathVariable String appId, @RequestBody JsonNode body) {
        return manager.updateConfig(ws(), appId, body);
    }

    @PutMapping("/env")
    public Map<String, Object> updateEnv(@PathVariable String appId, @RequestBody JsonNode body) {
        return manager.replaceEnvVars(ws(), appId, body);
    }

    @PostMapping("/deploy")
    public Map<String, Object> deploy(@PathVariable String appId, @RequestBody(required = false) JsonNode body) throws Exception {
        String vid = body != null && body.hasNonNull("versionId") ? body.path("versionId").asText() : null;
        try {
            return manager.redeploy(ws(), appId, vid);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "DEPLOY_FAILED",
                    "重新部署失败（请查看构建/运行日志并检查环境变量或启动命令）：" + e.getMessage());
        }
    }

    @PostMapping("/start")
    public Map<String, Object> start(@PathVariable String appId) throws Exception {
        return manager.start(ws(), appId);
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@PathVariable String appId) throws Exception {
        return manager.stop(ws(), appId);
    }

    @PostMapping("/sleep")
    public Map<String, Object> sleep(@PathVariable String appId) throws Exception {
        return manager.sleep(ws(), appId);
    }

    @PostMapping("/restart")
    public Map<String, Object> restart(@PathVariable String appId) throws Exception {
        return manager.restart(ws(), appId);
    }

    @GetMapping("/logs")
    public Map<String, Object> logs(@PathVariable String appId, @RequestParam(defaultValue = "200") int tail) {
        return manager.logs(ws(), appId, tail);
    }

    @GetMapping("/dockerfile")
    public Map<String, Object> dockerfile(@PathVariable String appId) throws Exception {
        return manager.dockerfilePreview(ws(), appId);
    }

    /** 工作台内嵌预览代理：将 /api/apps/{appId}/service/preview/** 转发至该单体应用（用于预览窗无子域名直连测试）。 */
    @RequestMapping({"/preview", "/preview/**"})
    public void previewProxy(@PathVariable String appId, HttpServletRequest req, HttpServletResponse res) throws Exception {
        AppRow app = apps.find(ws(), appId).orElseThrow(() -> ApiException.notFound("应用"));
        if (!app.isService()) throw ApiException.validation("该应用不是单体后端托管应用");
        String prefix = "/api/apps/" + appId + "/service/preview";
        String uri = req.getRequestURI();
        String sub = uri.startsWith(prefix) ? uri.substring(prefix.length()) : "/";
        if (sub.isEmpty()) sub = "/";
        SsoUser user = ssoAuth == null ? null : ssoAuth.currentUser(req).orElse(null);
        proxy.proxy(app, sub, req, res, user, false, true);
    }
}
