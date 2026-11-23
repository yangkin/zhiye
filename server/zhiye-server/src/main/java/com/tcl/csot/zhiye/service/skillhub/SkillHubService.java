package com.tcl.csot.zhiye.service.skillhub;

import com.tcl.csot.zhiye.client.OpenclawClient;
import com.tcl.csot.zhiye.client.SkillHubClient;
import com.tcl.csot.zhiye.config.SkillHubProperties;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.entity.VersionRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 公司 SkillHub（fivestar-service 的 Skill 市场）：浏览、详情、下载、安装、发布。
 * 身份一律用前端带来的平台令牌转发给 fivestar-service，织页不保存平台令牌；可见范围和审核由平台侧判定。
 */
@Service
public class SkillHubService {
    private static final Logger log = LoggerFactory.getLogger(SkillHubService.class);

    /** 发布可见范围：private 仅自己（完整组织路径）、team 本公司（根节点）、public 全公司（NULL，全租户） */
    public static final Set<String> VISIBILITY = Set.of("private", "team", "public");
    private static final Set<String> TAGS = Set.of("周报", "会议", "文档", "表格", "数据", "可视化", "代码", "写作", "翻译", "办公");

    private final AppRepository apps;
    private final VersionRepository versions;
    private final GitService git;
    private final SkillHubClient client;
    private final SkillHubInstallService installs;
    private final SkillHubProperties props;
    private final JdbcClient jdbc;
    private OpenclawClient openclaw;

    public SkillHubService(AppRepository apps, VersionRepository versions, GitService git, SkillHubClient client,
                           SkillHubInstallService installs, SkillHubProperties props, JdbcClient jdbc) {
        this.apps = apps;
        this.versions = versions;
        this.git = git;
        this.client = client;
        this.installs = installs;
        this.props = props;
        this.jdbc = jdbc;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOpenclaw(OpenclawClient openclaw) { this.openclaw = openclaw; }

    public boolean enabled() { return props.enabled(); }

    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 4096);
        for (int i = 0; i < limit; i++) if (bytes[i] == 0) return true;
        return false;
    }

    /** 导出 skill 包的顶层目录名（与旧实现一致） */
    public static String folderName(AppRow app, String skillMd) {
        String f = SkillMeta.parse(skillMd).folderName();
        if (f != null) return f;
        String slug = app.name().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "skill-" + app.slug() : slug;
    }

    private String requireToken(String token) {
        if (token == null || token.isBlank())
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PLATFORM_TOKEN_MISSING", "浏览和使用 SkillHub 需要先用公司统一账号登录");
        return token.trim();
    }

    // ---------- 分类 / 市场 / 详情 ----------

    public List<Map<String, Object>> categories(String token) {
        if (token == null || token.isBlank()) return List.of();
        return client.categories(token).stream().map(c -> {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("id", c.id());
                m.put("name", c.name());
                m.put("description", c.description());
                m.put("count", c.skillCount());
                return m;
            }).toList();
    }

    public Map<String, Object> market(String token, String keyword, String categoryId, int pageNo, int limit) {
        token = requireToken(token);
        pageNo = Math.max(1, pageNo);
        limit = Math.min(50, Math.max(1, limit));
        var p = client.market(token, keyword, categoryId, pageNo, limit);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", p.items().stream().map(SkillHubService::itemView).toList());
        m.put("total", p.total());
        m.put("pageNo", pageNo);
        m.put("limit", limit);
        return m;
    }

    /** 市场详情；市场里看不到（未上架 / 不在可见范围）时，退回「我发布的」里找 */
    public Map<String, Object> detail(String token, long skillId) {
        token = requireToken(token);
        SkillHubClient.Detail d;
        try {
            d = client.detail(token, skillId);
        } catch (ApiException e) {
            if (e.status() != HttpStatus.NOT_FOUND) throw e;
            d = mine(token, skillId).map(ms -> {
                SkillHubClient.Item it = new SkillHubClient.Item(ms.skillId(), ms.slug(), ms.name(), ms.summary(), ms.owner(), null, ms.categoryId(),
                        ms.tags(), ms.version(), null, null, null, null, ms.updatedAt());
                return new SkillHubClient.Detail(it, null, List.of(), ms.versionId(), ms.reviewStatus() == null ? null : String.valueOf(ms.reviewStatus()),
                        null, null, List.of(), ms.status() != null && ms.status() == 3, null);
            }).orElseThrow(() -> e);
        }
        Map<String, Object> m = itemView(d.item());
        if (d.categoryName() != null) m.put("categoryName", d.categoryName());
        if (d.downloads() != null) m.put("downloads", d.downloads());
        if (d.installed() != null) m.put("installed", d.installed());
        if (d.premium() != null) m.put("premium", d.premium());
        if (!d.versions().isEmpty()) m.put("versions", d.versions().stream().map(v -> {
            Map<String, Object> vm = new LinkedHashMap<String, Object>();
            vm.put("versionId", String.valueOf(v.versionId()));
            vm.put("version", v.version());
            vm.put("reviewStatus", v.reviewStatus());
            vm.put("reviewStatusText", reviewText(v.reviewStatus()));
            vm.put("changelog", v.changelog());
            vm.put("packageSize", v.packageSize());
            vm.put("fileCount", v.fileCount());
            return vm;
        }).toList());
        m.put("content", d.content());
        m.put("files", d.files().stream().map(f -> {
            Map<String, Object> f2 = new LinkedHashMap<String, Object>();
            f2.put("path", f.path());
            f2.put("size", f.size());
            return f2;
        }).toList());
        m.put("versionId", d.versionId() == null ? null : String.valueOf(d.versionId()));
        return m;
    }

    static String reviewText(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> "审核中";
            case 2 -> "已通过";
            case 3 -> "已驳回";
            case 4 -> "已下架";
            case 5 -> "草稿";
            default -> "状态 " + code;
        };
    }

    public Map<String, Object> packageDownload(String token, long skillId, Long versionId) {
        return Map.of("file", client.packageFile(requireToken(token), skillId, versionId));
    }

    // ---------- 安装 ----------

    public Map<String, Object> install(String ws, String token, long skillId, Long versionId) {
        SkillHubInstallService.Install inst = installs.install(ws, requireToken(token), skillId, versionId);
        Map<String, Object> m = installView(inst);
        m.put("installed", true);
        return m;
    }

    public List<Map<String, Object>> installs(String ws) {
        List<Map<String, Object>> out = installs.list(ws).stream().map(SkillHubService::installView).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        installs.withTitles(out, ws);
        return out;
    }

    public void uninstall(String ws, String id) { installs.uninstall(ws, id); }

    // ---------- 发布 ----------

    public record PublishRequest(String versionId, String categoryId, String displayName, String summary, String slug, List<String> tags,
                                 String version, String visibility, String changelog, Boolean submitReview) {}

    /** 可发布时的辅助信息：分类、建议版本号、当前用户信息 */
    public Map<String, Object> publishOptions(String token) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled());
        try {
            m.put("categories", categories(token));
        } catch (ApiException e) {
            m.put("categories", List.of());
            m.put("categoryError", e.getMessage());
        }
        return m;
    }

    public Map<String, Object> publish(String ws, String appId, String token, PublishRequest req) throws Exception {
        AppRow app = skillApp(ws, appId);
        String vid = req == null || req.versionId() == null || req.versionId().isBlank() ? app.currentVersionId() : req.versionId();
        if (vid == null) throw ApiException.validation("技能还没有生成完成，暂时不能发布");
        VersionRow v = versions.find(vid).filter(x -> x.appId().equals(app.id())).orElseThrow(() -> ApiException.notFound("版本"));
        String md = git.readFile(app.id(), v.gitCommit(), "SKILL.md").map(b -> new String(b, StandardCharsets.UTF_8))
                .orElseThrow(() -> ApiException.validation("这个版本根目录没有 SKILL.md，不能发布"));
        int mdLines = SkillMeta.lineCount(md);
        if (mdLines > SkillMeta.MAX_SKILL_MD_LINES)
            throw ApiException.validation("SKILL.md 超过 " + SkillMeta.MAX_SKILL_MD_LINES + " 行上限（当前 " + mdLines + " 行），请将长规范或示例拆到 references/ 后再发布");
        SkillMeta meta = SkillMeta.parse(md);
        if (meta.hasVersionInName())
            throw ApiException.validation("SKILL.md 头部的 name 不能包含版本号（如 -v1、-1-0），建议由 2~4 个英文词段组成");
        if (!meta.validName())
            throw ApiException.validation("SKILL.md 头部的 name 不合法：仅允许小写英文字母、数字和连字符 \"-\"，不能以 \"-\" 开头/结尾或连续 \"--\"，不超过 64 个字符");
        if (meta.description() == null || meta.description().isBlank())
            throw ApiException.validation("SKILL.md 头部缺少 description：写清这个 skill 做什么、何时使用");
        int descLen = SkillMeta.codePointLength(meta.description());
        if (descLen > SkillMeta.MAX_DESCRIPTION_LEN)
            throw ApiException.validation("SKILL.md 的 description 超过 " + SkillMeta.MAX_DESCRIPTION_LEN + " 字上限（当前 " + descLen + " 字）");
        if (meta.vagueCompatibility())
            throw ApiException.validation("SKILL.md 头部的 compatibility 描述过于笼统：请写明所需运行环境、工具或接口依赖，不得只写「兼容飞书」等泛称");

        // 发布前强制安全与文件清理检测（不可绕过）
        List<String> junkFiles = new ArrayList<>();
        List<String> secretHits = new ArrayList<>();
        for (var entry : git.listFiles(app.id(), v.gitCommit())) {
            String rel = entry.path();
            if (SkillMeta.isJunkPath(rel)) {
                junkFiles.add(rel);
                continue;
            }
            if (entry.size() > 512 * 1024) continue;
            byte[] bytes = git.readFile(app.id(), v.gitCommit(), rel).orElse(null);
            if (bytes == null || looksBinary(bytes)) continue;
            List<String> found = SkillMeta.scanSecrets(new String(bytes, StandardCharsets.UTF_8));
            if (!found.isEmpty()) secretHits.add(rel + "（" + String.join("、", found) + "）");
        }
        if (!secretHits.isEmpty())
            throw ApiException.validation("发布前安全检测未通过，发现敏感信息：" + String.join("；", secretHits) + "。请清理凭证、密码、token 或飞书用户 ID 后重试");
        if (!junkFiles.isEmpty())
            throw ApiException.validation("发布前文件清理检测未通过，存在无关缓存/调试日志/临时文件：" + String.join("、", junkFiles) + "。请删除后重试");

        // 显示名称校验（SkillHub 规范：≤20 字，不可仅用简称/泛称）
        String displayName = req != null && req.displayName() != null && !req.displayName().isBlank() ? req.displayName().trim() : app.name().trim();
        if (SkillMeta.isGenericDisplayName(displayName))
            throw ApiException.validation("SkillHub 显示名称过于泛化，建议按「厂区/业务域/系统 - 场景用途」清晰命名（如：t11 - 储位告警查询）");
        int displayLen = SkillMeta.codePointLength(displayName);
        if (displayLen > SkillMeta.MAX_DISPLAY_NAME_LEN)
            throw ApiException.validation("SkillHub 显示名称不能超过 " + SkillMeta.MAX_DISPLAY_NAME_LEN + " 字（当前 " + displayLen + " 字）");

        token = requireToken(token);

        // 分类：必须是 SkillHub 里的分类
        String categoryId = req == null ? null : req.categoryId();
        List<SkillHubClient.Category> cats = client.categories(token);
        String account = account(token);
        String realName = realName(token).orElse(account);
        String wantedCategory = categoryId;
        if (wantedCategory == null || wantedCategory.isBlank()) {
            categoryId = cats.stream().filter(c -> c.name() != null && c.name().contains("其他")).map(SkillHubClient.Category::id).findFirst()
                    .or(() -> cats.stream().map(SkillHubClient.Category::id).findFirst()).orElse(null);
        } else if (cats.stream().noneMatch(c -> wantedCategory.equals(c.id()))) {
            throw ApiException.validation("请选择 SkillHub 里的分类");
        }

        // slug：同一个应用沿用上次的；新应用由名称 + 应用 id 生成，保证唯一且重试时不变
        String slug = lastSlug(app.id()).orElseGet(() -> slugOf(meta.rawName(), app.id()));
        String summary = req != null && req.summary() != null && !req.summary().isBlank() ? req.summary().trim() : meta.description();
        if (SkillMeta.codePointLength(summary) > SkillMeta.MAX_DESCRIPTION_LEN) {
            summary = summary.substring(0, summary.offsetByCodePoints(0, SkillMeta.MAX_DESCRIPTION_LEN));
        }
        List<String> tags = req != null && req.tags() != null && !req.tags().isEmpty() ? req.tags().stream().limit(5).toList() : defaultTags(app.type());
        String version = req != null && req.version() != null && !req.version().isBlank() ? req.version().trim() : nextVersion(lastVersion(app.id()));
        boolean submit = req == null || req.submitReview() == null || req.submitReview();
        String changelog = req == null || req.changelog() == null ? "" : req.changelog().trim();
        if (changelog.length() > 2000) throw ApiException.validation("更新说明最多 2000 字");
        String visibility = req == null || req.visibility() == null || req.visibility().isBlank() ? "private" : req.visibility();
        if (!VISIBILITY.contains(visibility)) throw ApiException.validation("可见范围只能是 private、team 或 public");

        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        git.exportZip(app.id(), v.gitCommit(), meta.rawName(), zip);
        Long platformSkillId = lastPlatformSkillId(app.id());
        SkillHubClient.Submission sub = client.publish(token, new SkillHubClient.PublishInput(categoryId, displayName, realName, slug, summary, tags,
                version, submit, null, changelog.isEmpty() ? null : changelog, platformSkillId), zip.toByteArray(), meta.rawName() + ".zip");
        long skillId = sub.skillId();
        applyVisibility(token, skillId, sub.versionId(), visibility);

        String statusText = statusText(sub.versionId() == null ? null : review(token, skillId, sub.versionId()));
        String id = Ids.newId("sp");
        jdbc.sql("insert into skill_publication (id, app_id, version_id, skillhub_id, skill_version, url, visibility, slug, status, review_status, version_id_platform, submission_key, created_at) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,now())")
                .params(id, app.id(), v.id(), String.valueOf(skillId), version, portal(skillId), visibility, slug, submit ? "submitted" : "draft",
                        statusText, sub.versionId() == null ? null : String.valueOf(sub.versionId()), sub.submissionKey()).update();
        Instant at = jdbc.sql("select created_at from skill_publication where id = ?").param(id).query((rs, i) -> Db.ts(rs, "created_at")).single();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skillId", String.valueOf(skillId));
        m.put("skillVersion", version);
        m.put("url", portal(skillId));
        m.put("versionId", v.id());
        m.put("slug", slug);
        m.put("visibility", visibility);
        m.put("status", statusText);
        m.put("publishedAt", Db.iso(at));
        return m;
    }

    public List<Map<String, Object>> publications(String ws, String appId) {
        AppRow app = skillApp(ws, appId);
        return jdbc.sql("select p.*, v.number from skill_publication p left join version v on v.id = p.version_id where p.app_id = ? order by p.created_at desc")
                .param(app.id()).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("versionId", rs.getString("version_id"));
                    m.put("versionNumber", rs.getObject("number"));
                    m.put("skillId", rs.getString("skillhub_id"));
                    m.put("skillVersion", rs.getString("skill_version"));
                    m.put("url", rs.getString("url") != null ? rs.getString("url") : portal(rs.getString("skillhub_id")));
                    m.put("visibility", rs.getString("visibility"));
                    m.put("slug", rs.getString("slug"));
                    m.put("status", rs.getString("status"));
                    m.put("reviewStatus", rs.getString("review_status"));
                    m.put("platformVersionId", rs.getString("version_id_platform"));
                    m.put("submissionKey", rs.getString("submission_key"));
                    m.put("publishedAt", Db.iso(Db.ts(rs, "created_at")));
                    return m;
                }).list();
    }

    /** 重新拉取平台上的审核状态，更新本地记录（列表里点「刷新状态」） */
    public List<Map<String, Object>> refreshPublications(String ws, String appId, String token) {
        AppRow app = skillApp(ws, appId);
        token = requireToken(token);
        List<Map<String, Object>> rows = publications(ws, appId);
        for (Map<String, Object> r : rows) {
            String sid = (String) r.get("skillId");
            String pvid = (String) r.get("platformVersionId");
            if (sid == null || pvid == null) continue;
            try {
                SkillHubClient.ReviewState st = client.reviewState(token, Long.parseLong(sid), Long.parseLong(pvid));
                String text = statusText(st);
                if (text != null) {
                    r.put("reviewStatus", text);
                    jdbc.sql("update skill_publication set review_status = ? where id = ?").params(text, r.get("id")).update();
                }
            } catch (Exception e) {
                log.warn("刷新 SkillHub 审核状态失败 skill={} version={}", sid, pvid, e);
            }
        }
        return rows;
    }

    /** 我在 SkillHub 上发布过的技能（含审核中 / 被驳回），按应用汇总最新一次发布 */
    public List<Map<String, Object>> mine(String token, String keyword) {
        token = requireToken(token);
        var p = client.mySkills(token, keyword, 1, 100);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillHubClient.MySkill s : p.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skillId", s.skillId());
            m.put("slug", s.slug());
            m.put("name", s.name());
            m.put("summary", s.summary());
            m.put("categoryId", s.categoryId());
            m.put("owner", s.owner());
            m.put("tags", s.tags());
            m.put("version", s.version());
            m.put("versionId", s.versionId());
            m.put("latestVersion", s.latestVersion());
            m.put("status", statusText(s));
            m.put("statusCode", s.reviewStatus());
            m.put("published", s.status() != null && s.status() == 3);
            m.put("hasDraft", s.hasDraft());
            m.put("fileCount", s.fileCount());
            m.put("updatedAt", s.updatedAt());
            m.put("url", portal(s.skillId()));
            // 对应织页里的应用（同一个 slug 最近一次发布）
            var appId = jdbc.sql("select app_id from skill_publication where slug = ? order by created_at desc limit 1").param(s.slug())
                    .query(String.class).optional();
            m.put("appId", appId.orElse(null));
            out.add(m);
        }
        return out;
    }

    public Optional<SkillHubClient.MySkill> mine(String token, long skillId) {
        token = requireToken(token);
        var p = client.mySkills(token, null, 1, 100);
        return p.items().stream().filter(s -> skillId == Long.parseLong(s.skillId())).findFirst();
    }

    /** 撤回审核中的版本 */
    public boolean withdraw(String token, long skillId, long versionId, String submissionKey) {
        token = requireToken(token);
        try {
            client.withdraw(token, skillId, versionId, submissionKey);
            return true;
        } catch (ApiException e) {
            log.warn("撤回 SkillHub 审核失败 {}", e.getMessage());
            return false;
        }
    }

    /** 可见范围选项（组织节点）+ 当前用户的默认值 */
    public List<Map<String, Object>> scopeOptions(String token) {
        token = requireToken(token);
        List<SkillHubClient.ScopeNode> nodes = client.scopeOptions(token);
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of("value", "public", "label", "全公司", "kind", "public", "desc", "所有员工都能在 SkillHub 里看到和安装"));
        if (!nodes.isEmpty()) {
            out.add(Map.of("value", "team", "label", "本公司 · " + label(nodes.get(0)), "kind", "company", "desc", "公司内所有员工可见"));
            out.add(Map.of("value", "private", "label", "仅自己 · " + label(nodes.get(nodes.size() - 1)), "kind", "own", "desc", "只有你能看到和安装"));
            for (SkillHubClient.ScopeNode n : nodes) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("value", "org:" + n.codePath());
                m.put("label", label(n));
                m.put("kind", "org");
                m.put("level", n.level());
                m.put("desc", "该组织及其下级可见");
                out.add(m);
            }
        } else {
            out.add(Map.of("value", "team", "label", "本公司", "kind", "company", "desc", "公司内所有员工可见"));
            out.add(Map.of("value", "private", "label", "仅自己", "kind", "own", "desc", "只有你能看到和安装"));
        }
        return out;
    }

    /** 把前端选的可见范围翻译成 fivestar 的 visibleOrgCodePath；public → null（全租户） */
    public String resolveScope(String token, String scope) {
        if (scope == null || scope.isBlank() || "public".equals(scope)) return null;
        token = requireToken(token);
        List<SkillHubClient.ScopeNode> nodes = client.scopeOptions(token);
        if ("private".equals(scope)) return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1).codePath();
        if ("team".equals(scope)) return nodes.isEmpty() ? null : nodes.get(0).codePath();
        if (scope.startsWith("org:")) {
            String path = scope.substring(4);
            boolean ok = nodes.stream().anyMatch(n -> path.equals(n.codePath()));
            if (!ok) throw ApiException.validation("可见范围不在你的组织范围内");
            return path;
        }
        throw ApiException.validation("不支持的可见范围");
    }

    // ---------- 内部 ----------

    private AppRow skillApp(String ws, String id) {
        AppRow app = apps.find(ws, id).orElseThrow(() -> ApiException.notFound("应用"));
        if (!app.isSkill()) throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_A_SKILL", "只有「开发skill」应用可以发布到 SkillHub");
        return app;
    }

    private void applyVisibility(String token, long skillId, Long versionId, String visibility) {
        if (versionId == null || "public".equals(visibility)) return;
        String path = resolveScopeQuietly(token, visibility);
        if (path == null) {
            log.warn("SkillHub 技能 {} 拿不到组织路径，跳过可见范围设置", skillId);
            return;
        }
        try {
            client.setVisibleScope(token, skillId, versionId, path);
        } catch (Exception e) {
            // 可见范围设置失败不影响发布本身：默认按平台规则（提交者所在组织）可见，用户可以之后在 SkillHub 页面调整
            log.warn("设置 SkillHub 可见范围失败 skill={} version={}：{}", skillId, versionId, e.toString());
        }
    }

    private String resolveScopeQuietly(String token, String visibility) {
        try {
            return resolveScope(token, visibility);
        } catch (Exception e) {
            return null;
        }
    }

    private SkillHubClient.ReviewState review(String token, long skillId, Long versionId) {
        if (versionId == null) return null;
        try {
            return client.reviewState(token, skillId, versionId);
        } catch (Exception e) {
            log.warn("查询 SkillHub 审核状态失败：{}", e.toString());
            return null;
        }
    }

    static String statusText(SkillHubClient.MySkill s) {
        Integer skillStatus = s.status();
        Integer reviewStatus = s.reviewStatus();
        if (reviewStatus != null) {
            switch (reviewStatus) {
                case 1: return "审核中";
                case 2: return "已通过";
                case 3: return "已驳回";
                case 4: return "已下架";
                case 5: return "草稿";
                default: break;
            }
        }
        if (skillStatus != null) {
            if (skillStatus == 1) return "草稿";
            if (skillStatus == 3) return "已上架";
            if (skillStatus == 5) return "已下架";
        }
        return "状态未知";
    }

    static String statusText(SkillHubClient.ReviewState st) {
        if (st == null) return null;
        if (st.reviewStatus() != null) {
            switch (st.reviewStatus()) {
                case 1: return "审核中";
                case 2: return "已通过";
                case 3: return "已驳回";
                case 4: return "已下架";
                case 5: return "草稿";
                default: break;
            }
        }
        return null;
    }

    private String account(String token) {
        try {
            JsonNode u = openclaw == null ? null : openclaw.userInfo(token);
            String login = u == null ? null : u.path("loginName").asText(null);
            return login == null || login.isBlank() ? "织页用户" : login;
        } catch (Exception e) {
            log.warn("查询用户账号失败：{}", e.toString());
            return "织页用户";
        }
    }

    private Optional<String> realName(String token) {
        try {
            JsonNode u = openclaw == null ? null : openclaw.userInfo(token);
            if (u == null) return Optional.empty();
            String n = u.path("realName").asText(u.path("name").asText(null));
            return n == null || n.isBlank() ? Optional.empty() : Optional.of(n);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<String> lastSlug(String appId) {
        return jdbc.sql("select slug from skill_publication where app_id = ? and slug is not null order by created_at desc limit 1").param(appId)
                .query(String.class).optional();
    }

    private Long lastPlatformSkillId(String appId) {
        return jdbc.sql("select skillhub_id from skill_publication where app_id = ? order by created_at desc limit 1").param(appId)
                .query(String.class).optional().map(Long::parseLong).orElse(null);
    }

    private String lastVersion(String appId) {
        return jdbc.sql("select skill_version from skill_publication where app_id = ? and skill_version is not null order by created_at desc limit 1").param(appId)
                .query(String.class).optional().orElse(null);
    }

    /** 版本号：v1.0.0 起，每次发布 patch +1；用户可以在界面上改 */
    static String nextVersion(String last) {
        if (last == null) return "v1.0.0";
        String s = last.trim().replaceFirst("^[vV]", "");
        String[] p = s.split("\\.");
        try {
            int a = Integer.parseInt(p[0]), b = p.length > 1 ? Integer.parseInt(p[1]) : 0, c = p.length > 2 ? Integer.parseInt(p[2].replaceAll("[^0-9].*$", "")) : 0;
            return "v" + a + "." + b + "." + (c + 1);
        } catch (Exception e) {
            return last + ".1";
        }
    }

    static String slugOf(String name, String appId) {
        String base = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "zhiye-skill";
        if (!Character.isLetterOrDigit(base.charAt(0))) base = "s-" + base;
        if (base.length() > 40) base = base.substring(0, 40).replaceAll("-+$", "");
        String h = sha256(appId).substring(0, 6);
        String slug = base + "-" + h;
        return slug.length() > 64 ? slug.substring(0, 64).replaceAll("-+$", "") : slug;
    }

    static List<String> defaultTags(String type) {
        List<String> out = new ArrayList<>();
        if (type != null && TAGS.contains(type)) out.add(type);
        out.add("织页");
        return out;
    }

    private static String label(SkillHubClient.ScopeNode n) {
        String name = n.namePath() != null && !n.namePath().isBlank() ? n.namePath() : n.orgName();
        if (name == null || name.isBlank()) return "组织 " + n.orgCode();
        String[] parts = name.split("/");
        return parts[parts.length - 1];
    }

    String portal(Long skillId) { return portal(String.valueOf(skillId)); }

    String portal(String skillId) {
        String base = props.portalUrl();
        if (base == null || base.isBlank()) return null;
        if (base.contains("{id}")) return base.replace("{id}", skillId);
        return base.replaceAll("/+$", "") + "/" + skillId;
    }

    // ---------- 视图 ----------

    static Map<String, Object> itemView(SkillHubClient.Item it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skillId", it.skillId());
        m.put("slug", it.slug());
        m.put("name", it.name());
        m.put("summary", it.summary());
        m.put("owner", it.owner());
        m.put("ownerUserName", it.ownerUserName());
        m.put("categoryId", it.categoryId());
        m.put("tags", it.tags());
        m.put("version", it.version());
        m.put("downloads", it.downloads());
        m.put("favorites", it.favorites());
        m.put("riskLevel", it.riskLevel());
        m.put("visibleOrgNamePath", it.visibleOrgNamePath());
        m.put("updatedAt", it.updatedAt());
        return m;
    }

    public static Map<String, Object> installView(SkillHubInstallService.Install i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("skillId", i.skillId());
        m.put("slug", i.skillSlug());
        m.put("name", i.name());
        m.put("title", i.name());
        m.put("description", i.description());
        m.put("version", i.version());
        m.put("sizeBytes", i.sizeBytes());
        m.put("fileCount", i.fileCount());
        m.put("createdAt", Db.iso(i.createdAt()));
        m.put("updatedAt", Db.iso(i.updatedAt()));
        m.put("source", "skillhub");
        return m;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    // ---------- 兼容旧接口（应用维度的发布 / 列表），内部转到新的发布逻辑 ----------

    /** AppSummary.skillhub（只给 skill 应用） */
    public Map<String, Object> summary(AppRow app) {
        if (!app.isSkill()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        var last = jdbc.sql("select created_at, url, review_status from skill_publication where app_id = ? order by created_at desc limit 1").param(app.id())
                .query((rs, i) -> new Object[]{Db.iso(Db.ts(rs, "created_at")), rs.getString(2), rs.getString(3)}).optional();
        m.put("lastPublishedAt", last.map(x -> x[0]).orElse(null));
        m.put("url", last.map(x -> x[1]).orElse(null));
        m.put("status", last.map(x -> x[2]).orElse(null));
        return m;
    }
}
