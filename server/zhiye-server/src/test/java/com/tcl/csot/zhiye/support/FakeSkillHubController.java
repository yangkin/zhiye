package com.tcl.csot.zhiye.support;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 按 fivestar-service 真实契约假扮 SkillHub：
 * 路径 /{org}/aiToolbox/skills/** 与 /{org}/skillCategory/category/queryAll，
 * 信封 metis Result{code:"ok", data, success:true}。记录最近一次调用的鉴权头、表单字段和 zip 包。
 */
@RestController
@RequestMapping("/test-skillhub")
public class FakeSkillHubController {

    public static volatile String lastAuth;
    public static volatile String lastPath;
    public static volatile Map<String, String> lastFields;
    public static volatile byte[] lastPackage;

    public static void reset() {
        lastAuth = null;
        lastPath = null;
        lastFields = null;
        lastPackage = null;
    }

    @GetMapping("/self")
    public Map<String, Object> self() {
        return ok(Map.of("loginName", "tester", "realName", "测试用户"));
    }

    @GetMapping("/{org}/skillCategory/category/queryAll")
    public Map<String, Object> categories(@PathVariable String org) {
        return ok(List.of(Map.of("id", "c1", "categoryName", "其他", "sort", 1)));
    }

    @PostMapping("/{org}/aiToolbox/skills/market/query")
    public Map<String, Object> market(@PathVariable String org, @RequestBody Map<String, Object> body) {
        return ok(Map.of("records", List.of(marketRow()), "total", 1, "pageNo", body.get("pageNo"), "limit", body.get("limit")));
    }

    @GetMapping("/{org}/aiToolbox/skills/market/101")
    public Map<String, Object> detail(@PathVariable String org) {
        Map<String, Object> d = new LinkedHashMap<>(marketRow());
        d.put("currentVersionId", 678L);
        d.put("skillMdContent", "周报技能");
        return ok(d);
    }

    @GetMapping("/{org}/aiToolbox/skills/101/versions")
    public Map<String, Object> versions(@PathVariable String org) {
        return ok(List.of(Map.of("versionId", 678L, "version", "v1.0.0", "reviewStatus", 2, "packageSize", packageBytes().length)));
    }

    @GetMapping("/{org}/aiToolbox/skills/101/versions/678/files")
    public Map<String, Object> files(@PathVariable String org) {
        return ok(List.of(Map.of("path", "SKILL.md", "directory", false, "size", 100)));
    }

    @GetMapping("/{org}/aiToolbox/skills/market/101/package/download")
    public ResponseEntity<byte[]> downloadMarket(@PathVariable String org) {
        return download();
    }

    @GetMapping("/{org}/aiToolbox/skills/101/versions/678/package/download")
    public ResponseEntity<byte[]> downloadVersion(@PathVariable String org) {
        return download();
    }

    @PostMapping("/{org}/aiToolbox/skills/add")
    public Map<String, Object> add(@PathVariable String org,
                                   @RequestParam Map<String, String> params,
                                   @RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        record("/add", params, auth, file);
        return ok(Map.of("skillId", 12345L, "versionId", 678L, "submissionKey", "sub-1", "reused", false));
    }

    @PostMapping("/{org}/aiToolbox/skills/edit")
    public Map<String, Object> edit(@PathVariable String org,
                                    @RequestParam Map<String, String> params,
                                    @RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        record("/edit", params, auth, file);
        return ok(Map.of("skillId", Long.parseLong(params.getOrDefault("skillId", "0")),
                "versionId", 679L, "submissionKey", "sub-2", "reused", false));
    }

    @GetMapping("/{org}/aiToolbox/skills/starClaw/review-state")
    public Map<String, Object> reviewState(@PathVariable String org, @RequestParam Long skillId,
                                           @RequestParam(required = false) String version) {
        return ok(Map.of("inFlight", false, "skillId", skillId, "versionId", 678L,
                "version", version == null ? "v1.0.0" : version, "reviewStatus", 1, "scanStatus", 2));
    }

    @PutMapping("/{org}/aiToolbox/skills/{skillId}/versions/{versionId}/visible-scope")
    public Map<String, Object> visibleScope(@PathVariable String org, @PathVariable Long skillId,
                                            @PathVariable Long versionId,
                                            @RequestParam String visibleOrgCodePath) {
        return ok(true);
    }

    private static Map<String, Object> marketRow() {
        return Map.of("skillId", 101L, "slug", "weekly-report", "displayName", "周报助手", "summary", "生成结构化周报",
                "owner", "测试用户", "categoryId", "c1", "currentVersion", "v1.0.0", "currentVersionId", 678L);
    }

    private static ResponseEntity<byte[]> download() {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=weekly-report.zip")
                .contentType(MediaType.parseMediaType("application/zip")).body(packageBytes());
    }

    private static byte[] packageBytes() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("weekly-report/SKILL.md"));
                zip.write("---\nname: weekly-report\ndescription: 生成结构化周报\n---\n# 周报助手\n".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void record(String path, Map<String, String> params, String auth, MultipartFile file) throws IOException {
        lastPath = path;
        lastAuth = auth;
        lastFields = new LinkedHashMap<>(params);
        lastPackage = file == null ? null : file.getBytes();
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", "ok");
        r.put("data", data);
        r.put("success", true);
        return r;
    }
}
