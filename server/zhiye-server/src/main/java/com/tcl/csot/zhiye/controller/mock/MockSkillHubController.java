package com.tcl.csot.zhiye.controller.mock;

import com.tcl.csot.zhiye.exception.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 本地 SkillHub：接口路径、metis 信封和 multipart 发布契约与 fivestar-service 一致。 */
@RestController
@Profile("api")
@ConditionalOnProperty(prefix = "zhiye.skillhub", name = "mock", havingValue = "true")
@RequestMapping("/mock/fivestar-service/{org}")
public class MockSkillHubController {
    private record Skill(long id, String slug, String name, String summary, String owner, String categoryId,
                         String version, long versionId, byte[] pkg, int reviewStatus) {}

    private final Map<Long, Skill> skills = new ConcurrentHashMap<>();
    private final AtomicLong skillIds = new AtomicLong(1000);
    private final AtomicLong versionIds = new AtomicLong(2000);

    public MockSkillHubController() {
        Skill sample = new Skill(101, "weekly-report", "周报助手", "把本周工作整理成结构化周报", "织页示例", "c2",
                "v1.0.0", 1001, samplePackage(), 2);
        skills.put(sample.id(), sample);
    }

    private static String account(String auth) {
        String account = MockFivestarController.account(auth);
        if (account == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_INVALID", "请先登录");
        return account;
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", "ok");
        r.put("data", data);
        r.put("success", true);
        return r;
    }

    @GetMapping("/skillCategory/category/queryAll")
    public Map<String, Object> categories(@RequestHeader(value = "Authorization", required = false) String auth) {
        account(auth);
        return ok(List.of(Map.of("id", "c1", "categoryName", "其他", "sort", 1),
                Map.of("id", "c2", "categoryName", "办公效率", "sort", 2)));
    }

    @PostMapping("/skillCategory/category/queryByCenter")
    public Map<String, Object> categoriesByCenter(@RequestHeader(value = "Authorization", required = false) String auth,
                                                  @RequestBody Map<String, Object> body) {
        return categories(auth);
    }

    @PostMapping("/aiToolbox/skills/market/query")
    public Map<String, Object> market(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody Map<String, Object> body) {
        account(auth);
        String q = String.valueOf(body.getOrDefault("keyword", "")).toLowerCase();
        String category = String.valueOf(body.getOrDefault("categoryId", ""));
        List<Map<String, Object>> rows = new ArrayList<>(skills.values()).stream().sorted((a, b) -> Long.compare(b.id(), a.id()))
                .filter(s -> q.isEmpty() || (s.name() + s.summary() + s.slug()).toLowerCase().contains(q))
                .filter(s -> category.isEmpty() || category.equals(s.categoryId())).map(MockSkillHubController::marketRow).toList();
        return ok(Map.of("records", rows, "total", rows.size(), "pageNo", body.getOrDefault("pageNo", 1), "limit", body.getOrDefault("limit", 20)));
    }

    @PostMapping("/aiToolbox/skills/my/query")
    public Map<String, Object> mine(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody Map<String, Object> body) {
        String account = account(auth);
        List<Map<String, Object>> rows = skills.values().stream().filter(s -> s.owner().equals(account) || s.id() == 101).map(s -> {
            Map<String, Object> m = marketRow(s);
            m.put("latestVersionId", s.versionId());
            m.put("latestReviewStatus", s.reviewStatus());
            m.put("status", 3);
            m.put("hasDraft", false);
            return m;
        }).toList();
        return ok(Map.of("records", rows, "total", rows.size(), "pageNo", 1, "limit", 100));
    }

    @GetMapping("/aiToolbox/skills/market/{skillId}")
    public Map<String, Object> detail(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long skillId) {
        account(auth);
        Skill s = skill(skillId);
        Map<String, Object> m = marketRow(s);
        m.put("skillMdContent", "本地模拟技能");
        m.put("installed", false);
        return ok(m);
    }

    @GetMapping("/aiToolbox/skills/{skillId}/versions")
    public Map<String, Object> versions(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long skillId) {
        account(auth);
        Skill s = skill(skillId);
        return ok(List.of(Map.of("versionId", s.versionId(), "version", s.version(), "reviewStatus", s.reviewStatus(), "packageSize", s.pkg().length)));
    }

    @GetMapping("/aiToolbox/skills/{skillId}/versions/{versionId}/files")
    public Map<String, Object> files(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @PathVariable long skillId, @PathVariable long versionId) {
        account(auth);
        skill(skillId);
        return ok(List.of(Map.of("path", "SKILL.md", "directory", false, "size", 100)));
    }

    @GetMapping("/aiToolbox/skills/market/{skillId}/package/download")
    public ResponseEntity<byte[]> downloadMarket(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long skillId) {
        account(auth);
        return download(skill(skillId));
    }

    @GetMapping("/aiToolbox/skills/{skillId}/versions/{versionId}/package/download")
    public ResponseEntity<byte[]> downloadVersion(@RequestHeader(value = "Authorization", required = false) String auth,
                                                  @PathVariable long skillId, @PathVariable long versionId) {
        account(auth);
        return download(skill(skillId));
    }

    @PostMapping("/aiToolbox/skills/add")
    public Map<String, Object> add(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestParam Map<String, String> p, @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        account(auth);
        long id = skillIds.incrementAndGet(), versionId = versionIds.incrementAndGet();
        byte[] pkg = file == null ? MockSkillHubFileController.packageBytes(p.get("fileUrl")) : file.getBytes();
        if (pkg == null) throw new IllegalArgumentException("skill package missing");
        Skill s = new Skill(id, p.get("slug"), p.get("displayName"), p.get("summary"), p.get("owner"), p.get("categoryId"),
                p.get("version"), versionId, pkg, Boolean.parseBoolean(p.get("subReview")) ? 1 : 5);
        skills.put(id, s);
        return ok(Map.of("skillId", id, "versionId", versionId, "submissionKey", "sub-" + id, "reused", false));
    }

    @PostMapping("/aiToolbox/skills/edit")
    public Map<String, Object> edit(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestParam Map<String, String> p, @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        account(auth);
        Skill old = skill(Long.parseLong(p.get("skillId")));
        long versionId = versionIds.incrementAndGet();
        byte[] pkg = file == null ? MockSkillHubFileController.packageBytes(p.get("fileUrl")) : file.getBytes();
        if (pkg == null) throw new IllegalArgumentException("skill package missing");
        Skill s = new Skill(old.id(), old.slug(), p.getOrDefault("displayName", old.name()), p.getOrDefault("summary", old.summary()),
                old.owner(), p.getOrDefault("categoryId", old.categoryId()), p.get("version"), versionId,
                pkg, Boolean.parseBoolean(p.get("subReview")) ? 1 : 5);
        skills.put(old.id(), s);
        return ok(Map.of("skillId", old.id(), "versionId", versionId, "submissionKey", "sub-" + versionId, "reused", false));
    }

    @GetMapping("/aiToolbox/skills/starClaw/review-state")
    public Map<String, Object> reviewState(@RequestHeader(value = "Authorization", required = false) String auth, @RequestParam long skillId) {
        account(auth);
        Skill s = skill(skillId);
        return ok(Map.of("inFlight", s.reviewStatus() == 1, "skillId", s.id(), "versionId", s.versionId(),
                "version", s.version(), "reviewStatus", s.reviewStatus(), "scanStatus", 2));
    }

    @PutMapping("/aiToolbox/skills/{skillId}/versions/{versionId}/visible-scope")
    public Map<String, Object> visibleScope(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @PathVariable long skillId, @PathVariable long versionId, @RequestParam String visibleOrgCodePath) {
        account(auth);
        skill(skillId);
        return ok(true);
    }

    @GetMapping("/aiToolbox/skills/visible-scope/options")
    public Map<String, Object> scopes(@RequestHeader(value = "Authorization", required = false) String auth) {
        account(auth);
        return ok(List.of(Map.of("orgCode", "31", "orgName", "TCL华星", "level", 1, "codePath", "31", "namePath", "TCL华星")));
    }

    @PostMapping("/aiToolbox/skills/{skillId}/versions/{versionId}/withdraw-review")
    public Map<String, Object> withdraw(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long skillId) {
        account(auth);
        skill(skillId);
        return ok(true);
    }

    private Skill skill(long id) {
        Skill s = skills.get(id);
        if (s == null) throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "技能不存在");
        return s;
    }

    private static Map<String, Object> marketRow(Skill s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skillId", s.id());
        m.put("slug", s.slug());
        m.put("displayName", s.name());
        m.put("summary", s.summary());
        m.put("owner", s.owner());
        m.put("categoryId", s.categoryId());
        m.put("currentVersion", s.version());
        m.put("currentVersionId", s.versionId());
        m.put("latestVersion", s.version());
        return m;
    }

    private static ResponseEntity<byte[]> download(Skill s) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=" + s.slug() + ".zip")
                .contentType(MediaType.parseMediaType("application/zip")).body(s.pkg());
    }

    private static byte[] samplePackage() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("weekly-report/SKILL.md"));
                zip.write("---\nname: weekly-report\ndescription: 把本周工作整理成结构化周报\n---\n# 周报助手\n".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("weekly-report/references/template.md"));
                zip.write("# 周报模板\n## 本周完成\n## 下周计划\n".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
