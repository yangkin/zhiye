package com.tcl.csot.zhiye.controller;

import com.tcl.csot.zhiye.entity.VersionRow;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.service.version.PageExporter;
import com.tcl.csot.zhiye.service.version.VersionService;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.CurrentPrincipal;
import com.tcl.csot.zhiye.service.repo.GitService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@Profile("api")
@RequestMapping("/api")
public class VersionController {
    private final VersionService versions;
    private final VersionRepository repo;
    private final GitService git;
    private final PageExporter pages;
    private final CurrentPrincipal principal;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcl.csot.zhiye.service.ops.PlatformLogService platformLogs;

    public VersionController(VersionService versions, VersionRepository repo, GitService git, PageExporter pages, CurrentPrincipal principal) {
        this.versions = versions;
        this.repo = repo;
        this.git = git;
        this.pages = pages;
        this.principal = principal;
    }

    private String ws() { return principal.workspaceId(); }

    @GetMapping("/apps/{id}/versions")
    public List<Map<String, Object>> list(@PathVariable String id) { return versions.list(ws(), id); }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(required = false) String q, @RequestParam(required = false) String appId,
                                             @RequestParam(required = false, defaultValue = "all") String filter,
                                             @RequestParam(required = false) String startDate,
                                             @RequestParam(required = false) String endDate) {
        return versions.history(ws(), q, appId, filter, startDate, endDate);
    }

    @PatchMapping("/versions/{id}")
    public Map<String, Object> patch(@PathVariable String id, @RequestBody JsonNode body) {
        if (body.has("note")) return versions.setNote(ws(), id, body.path("note").isNull() ? null : body.path("note").asText());
        if (body.has("favorite")) return versions.setFavorite(ws(), id, body.path("favorite").asBoolean());
        throw ApiException.validation("缺少 favorite 或 note");
    }

    @PostMapping("/versions/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id) throws Exception {
        String workspaceId = ws();
        Map<String, Object> res = versions.restore(workspaceId, id);
        if (platformLogs != null) {
            String appId = res.get("appId") instanceof String s ? s : null;
            platformLogs.info("lifecycle", "VERSION_RESTORE", workspaceId, null, appId, null,
                    "从历史版本 " + id + " 恢复为新版本 V" + res.getOrDefault("number", ""), null,
                    Map.of("restoredFromId", id, "newVersionId", String.valueOf(res.getOrDefault("id", ""))));
        }
        return res;
    }

    /** 查看改动：与上一个版本（parent）比较；第一个版本与空树比较（base 为 null） */
    @GetMapping("/apps/{appId}/versions/{versionId}/changes")
    public Map<String, Object> changes(@PathVariable String appId, @PathVariable String versionId) throws Exception {
        VersionRow v = appVersion(appId, versionId);
        VersionRow base = v.parentVersionId() == null ? null : repo.find(v.parentVersionId()).orElse(null);
        var files = git.changes(v.appId(), base == null ? null : base.gitCommit(), v.gitCommit());
        int add = 0, rem = 0;
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (var f : files) {
            add += f.added();
            rem += f.removed();
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("path", f.path());
            m.put("status", f.status());
            if (f.oldPath() != null) m.put("oldPath", f.oldPath());
            m.put("added", f.added());
            m.put("removed", f.removed());
            m.put("binary", f.binary());
            list.add(m);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("base", base == null ? null : base.id());
        out.put("files", list);
        out.put("totalAdded", add);
        out.put("totalRemoved", rem);
        return out;
    }

    static final int DIFF_MAX_BYTES = 300 * 1024;

    @GetMapping("/apps/{appId}/versions/{versionId}/changes/file")
    public Map<String, Object> changeFile(@PathVariable String appId, @PathVariable String versionId, @RequestParam String path) throws Exception {
        VersionRow v = appVersion(appId, versionId);
        VersionRow base = v.parentVersionId() == null ? null : repo.find(v.parentVersionId()).orElse(null);
        var d = git.fileDiff(v.appId(), base == null ? null : base.gitCommit(), v.gitCommit(), path, DIFF_MAX_BYTES)
                .orElseThrow(() -> ApiException.notFound("改动"));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("path", d.path());
        out.put("status", d.status());
        out.put("diff", d.diff());
        out.put("truncated", d.truncated());
        return out;
    }

    private VersionRow appVersion(String appId, String versionId) {
        VersionRow v = versions.version(ws(), versionId);
        if (!v.appId().equals(appId)) throw ApiException.notFound("版本");
        return v;
    }

    @GetMapping("/versions/{id}/files")
    public List<Map<String, Object>> files(@PathVariable String id) throws Exception { return versions.files(ws(), id); }

    @GetMapping("/versions/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable String id,
                                       @RequestParam String path,
                                       @RequestParam(defaultValue = "false") boolean download) throws Exception {
        byte[] data = versions.file(ws(), id, path);
        if (download) {
            String normalized = path == null ? "file" : path.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
            if (fileName.isBlank()) fileName = "file";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(com.tcl.csot.zhiye.util.ContentTypes.of(fileName)))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(data);
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).body(data);
    }

    @GetMapping("/versions/{id}/export")
    public void export(@PathVariable String id, HttpServletResponse res) throws Exception {
        VersionRow v = versions.version(ws(), id);
        AppRow app = versions.app(ws(), v.appId());
        String base = app.isSkill() ? skillFolder(app, v) : safe(app.name()) + "-v" + v.number();
        res.setContentType("application/zip");
        res.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(base + ".zip", StandardCharsets.UTF_8).build().toString());
        git.exportZip(app.id(), v.gitCommit(), base, res.getOutputStream());
    }

    @GetMapping("/versions/{id}/page")
    public ResponseEntity<byte[]> page(@PathVariable String id) throws Exception {
        VersionRow v = versions.version(ws(), id);
        AppRow app = versions.app(ws(), v.appId());
        if (app.isSkill()) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "SKILL_NOT_PUBLISHABLE", "技能包没有页面，请使用「导出源码」下载技能包");
        if (app.isScript()) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "SCRIPT_NOT_PUBLISHABLE", "脚本项目没有页面，请使用「导出源码」下载");
        var build = repo.build(v.buildId()).orElseThrow(() -> ApiException.conflict("BUILD_MISSING", "这个版本没有可下载的页面"));
        String html = pages.inline(build.publishPrefix());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(safe(app.name()) + "-v" + v.number() + ".html", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(html.getBytes(StandardCharsets.UTF_8));
    }

    /** skill 包导出的顶层目录 = SKILL.md 头部的 name（不合法时转写，再不行用应用名） */
    String skillFolder(AppRow app, VersionRow v) throws Exception {
        String md = git.readFile(app.id(), v.gitCommit(), "SKILL.md").map(b -> new String(b, StandardCharsets.UTF_8)).orElse("");
        return com.tcl.csot.zhiye.service.skillhub.SkillHubService.folderName(app, md);
    }

    static String safe(String n) {
        String s = n.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        return s.isBlank() ? "app" : s;
    }
}
