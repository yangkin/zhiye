package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.service.app.Catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.filter.AnonymousWorkspaceFilter;
import com.tcl.csot.zhiye.service.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@Profile("api")
@RequestMapping("/api")
public class MetaController {
    private final ZhiyeProperties props;
    private final CurrentPrincipal principal;
    private final WorkspaceService workspaces;
    private final com.tcl.csot.zhiye.service.auth.AuthService authService;
    private final com.tcl.csot.zhiye.service.skillhub.SkillRefs skillRefs;
    @org.springframework.beans.factory.annotation.Autowired
    private com.tcl.csot.zhiye.config.FivestarProperties fivestar;

    public MetaController(ZhiyeProperties props, CurrentPrincipal principal, WorkspaceService workspaces,
                          com.tcl.csot.zhiye.service.auth.AuthService authService, com.tcl.csot.zhiye.service.skillhub.SkillRefs skillRefs) {
        this.skillRefs = skillRefs;
        this.props = props;
        this.principal = principal;
        this.workspaces = workspaces;
        this.authService = authService;
    }

    @GetMapping("/meta")
    public Map<String, Object> meta(jakarta.servlet.http.HttpServletRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("models", Catalog.MODELS.stream().map(x -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", x.id()); o.put("label", x.label()); o.put("cost", x.cost());
            o.put("desc", "当前使用 " + props.runner().modelId() + "（平台统一配置）");
            return o;
        }).toList());
        m.put("modes", Catalog.MODES);
        m.put("styles", Catalog.STYLES.values().stream().map(s -> Map.of("id", s.id(), "label", s.label(), "accent", s.accent())).toList());
        m.put("limits", Map.of("uploadMaxBytes", props.importer().maxUploadBytes(), "uploadMaxFiles", props.importer().maxFiles(),
                "assetMaxBytes", props.importer().assetMaxBytes()));
        m.put("auth", authService.describe(request));
        // 五星模式：模型列表与额度改从 /api/llm/models、/api/llm/quota 取（以五星AI岛为准）
        m.put("fivestar", fivestar.enabled());
        // 内置技能：编码与问答时由模型按需加载，这里只用于在技能选择器里展示
        m.put("builtinSkills", skillRefs.builtinList());
        // 已安装的 SkillHub 技能（技能选择器的「我的技能」里和「开发skill」应用并列）
        m.put("hubSkills", skillRefs.hubList(principal.workspaceIdOrNull()));
        return m;
    }

    @GetMapping("/workspace")
    public Map<String, Object> workspace() {
        var ws = workspaces.byId(principal.workspaceId()).orElseThrow(() -> ApiException.notFound("工作空间"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ws.id());
        m.put("createdAt", ws.createdAt().toString());
        String token = principal.rawToken();
        m.put("recoveryUrl", token == null ? null : props.publicBaseUrl().replaceAll("/+$", "") + "/recover#" + token);
        return m;
    }

    @PostMapping("/workspace/recover")
    public Map<String, Object> recover(@RequestBody JsonNode body, HttpServletResponse res) {
        String token = body.path("token").asText("").trim();
        var ws = workspaces.byToken(token).orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "NOT_FOUND", "恢复链接无效或已失效"));
        AnonymousWorkspaceFilter.setCookie(res, props.workspace().cookieName(), token, props.workspace().secureCookie());
        return Map.of("id", ws.id());
    }
}
