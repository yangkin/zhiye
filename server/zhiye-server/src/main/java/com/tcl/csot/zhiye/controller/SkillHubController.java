package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.client.SkillHubClient;
import com.tcl.csot.zhiye.service.skillhub.SkillHubInstallService;
import com.tcl.csot.zhiye.service.skillhub.SkillHubService;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.util.PlatformToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公司 SkillHub：浏览 / 详情 / 下载 / 安装 / 发布。
 * 平台令牌由前端带在 Authorization 头里，服务端只转发给 fivestar-service，不保存。
 */
@RestController
@Profile("api")
@RequestMapping("/api/skillhub")
public class SkillHubController {
    private final SkillHubService service;
    private final SkillHubInstallService installs;
    private final CurrentPrincipal principal;
    private com.tcl.csot.zhiye.service.app.AppService appService;

    public SkillHubController(SkillHubService service, SkillHubInstallService installs, CurrentPrincipal principal) {
        this.service = service;
        this.installs = installs;
        this.principal = principal;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAppService(com.tcl.csot.zhiye.service.app.AppService appService) { this.appService = appService; }

    private String ws() { return principal.workspaceId(); }

    private static String token(HttpServletRequest req) { return PlatformToken.of(req); }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories(HttpServletRequest req) { return service.categories(token(req)); }

    @GetMapping("/market")
    public Map<String, Object> market(HttpServletRequest req, @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String categoryId,
                                      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit) {
        return service.market(token(req), q, categoryId, page, limit);
    }

    @GetMapping("/mine")
    public List<Map<String, Object>> mine(HttpServletRequest req, @RequestParam(required = false) String q) {
        return service.mine(token(req), q);
    }

    @GetMapping("/publish-options")
    public Map<String, Object> publishOptions(HttpServletRequest req) { return service.publishOptions(token(req)); }

    @GetMapping("/scopes")
    public List<Map<String, Object>> scopes(HttpServletRequest req) { return service.scopeOptions(token(req)); }

    @GetMapping("/installs")
    public List<Map<String, Object>> installs() { return service.installs(ws()); }

    @PostMapping("/installs")
    public Map<String, Object> install(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        long skillId = Long.parseLong(String.valueOf(body.get("skillId")));
        Long versionId = body.get("versionId") == null || String.valueOf(body.get("versionId")).isBlank() ? null : Long.parseLong(String.valueOf(body.get("versionId")));
        return service.install(ws(), token(req), skillId, versionId);
    }

    @DeleteMapping("/installs/{id}")
    public ResponseEntity<Void> uninstall(@PathVariable String id) {
        service.uninstall(ws(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/market/{skillId}")
    public Map<String, Object> detail(HttpServletRequest req, @PathVariable String skillId) {
        return service.detail(token(req), Long.parseLong(skillId));
    }

    @GetMapping("/market/{skillId}/package")
    public ResponseEntity<byte[]> packageFile(HttpServletRequest req, @PathVariable String skillId,
                                              @RequestParam(required = false) String versionId) {
        Long vid = versionId == null || versionId.isBlank() ? null : Long.parseLong(versionId);
        SkillHubClient.PackageFile f = (SkillHubClient.PackageFile) service.packageDownload(token(req), Long.parseLong(skillId), vid).get("file");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + f.fileName().replace("\"", "") + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(f.bytes());
    }

    /** 我发布的技能 */
    @GetMapping("/published")
    public List<Map<String, Object>> published(HttpServletRequest req, @RequestParam(required = false) String q) {
        return service.mine(token(req), q);
    }

    /** 某个应用的发布记录（含审核状态） */
    @GetMapping("/apps/{appId}/publications")
    public List<Map<String, Object>> publications(HttpServletRequest req, @PathVariable String appId) {
        return service.publications(ws(), appId);
    }

    @PostMapping("/apps/{appId}/publications/refresh")
    public List<Map<String, Object>> refresh(HttpServletRequest req, @PathVariable String appId) {
        return service.refreshPublications(ws(), appId, token(req));
    }

    @PostMapping("/published/{skillId}/withdraw")
    public Map<String, Object> withdraw(HttpServletRequest req, @PathVariable String skillId, @RequestBody(required = false) Map<String, Object> body) {
        long sid = Long.parseLong(skillId);
        long vid = Long.parseLong(String.valueOf(body == null ? null : body.get("versionId")));
        String key = body == null ? null : (String) body.get("submissionKey");
        return Map.of("ok", service.withdraw(token(req), sid, vid, key));
    }

    // ---------- 旧的应用维度接口（保留，内部走新的发布逻辑） ----------

    /** 发布「开发skill」应用的新版本到 SkillHub（body 可以是 PublishRequest，也兼容旧的 {versionId, visibility, changelog}） */
    @PostMapping("/apps/{id}/publish")
    public Map<String, Object> publish(HttpServletRequest req, @PathVariable String id, @RequestBody(required = false) com.fasterxml.jackson.databind.JsonNode body) {
        SkillHubService.PublishRequest pr;
        if (body != null && body.has("categoryId")) {
            pr = com.tcl.csot.zhiye.util.Json.M.convertValue(body, SkillHubService.PublishRequest.class);
        } else {
            var b = body == null ? com.tcl.csot.zhiye.util.Json.M.createObjectNode() : body;
            List<String> tags = b.has("tags") && b.get("tags").isArray() ? com.tcl.csot.zhiye.util.Json.M.convertValue(b.get("tags"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}) : null;
            pr = new SkillHubService.PublishRequest(b.path("versionId").asText(null), b.path("categoryId").asText(null), b.path("displayName").asText(null),
                    b.path("summary").asText(null), b.path("slug").asText(null), tags, b.path("version").asText(null), b.path("visibility").asText(null),
                    b.path("scope").asText(null), b.path("changelog").asText(null), b.has("submitReview") ? b.path("submitReview").asBoolean() : null);
        }
        try {
            return service.publish(ws(), id, token(req), pr);
        } catch (Exception e) {
            if (e instanceof com.tcl.csot.zhiye.exception.ApiException ae) throw ae;
            throw new com.tcl.csot.zhiye.exception.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "SKILLHUB_ERROR", "发布到 SkillHub 失败：" + e.getMessage());
        }
    }

    /** 「我的技能」：自己的「开发skill」应用（已生成）+ 已安装的 SkillHub 技能，技能选择器用 */
    @GetMapping("/my-skills")
    public List<Map<String, Object>> mySkills() {
        List<Map<String, Object>> list = new java.util.ArrayList<>(appService == null ? java.util.List.<Map<String, Object>>of() : appService.listSkillsForPicker(principal.workspaceId()));
        for (SkillHubInstallService.Install i : installs.list(ws())) list.add(SkillHubService.installView(i));
        return list;
    }

    /** 已安装技能的 id → 名称（消息上显示用了哪些技能） */
    @GetMapping("/skill-names")
    public Map<String, String> skillNames() {
        Map<String, String> out = new LinkedHashMap<>();
        for (SkillHubInstallService.Install i : installs.list(ws())) out.put(i.id(), i.name());
        return out;
    }
}
