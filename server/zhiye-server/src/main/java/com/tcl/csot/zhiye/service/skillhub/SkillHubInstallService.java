package com.tcl.csot.zhiye.service.skillhub;

import com.tcl.csot.zhiye.client.SkillHubClient;
import com.tcl.csot.zhiye.config.ZhiyeProperties;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从公司 SkillHub 安装的技能：包下载后校验（SKILL.md、路径、大小），解压到运行目录，
 * 之后和「开发skill」应用一样被创建 / 修改任务引用（只读，不进用户项目）。
 * 安装按工作空间共享（同事装过的技能大家都能用）；每个工作空间最多 {@link #MAX_INSTALLS} 个。
 */
@Service
public class SkillHubInstallService {
    private static final Logger log = LoggerFactory.getLogger(SkillHubInstallService.class);

    public static final int MAX_INSTALLS = 20;
    public static final long MAX_PACKAGE = 20L * 1024 * 1024;
    public static final int MAX_FILES = 300;
    public static final long MAX_FILE = 1024 * 1024;
    public static final long MAX_TOTAL = 5L * 1024 * 1024;

    /** 一个已安装的技能在运行目录里的位置；name 是放进 agent 目录 skills/ 下的目录名 */
    public record Install(String id, String workspaceId, String skillId, String skillSlug, String name, String description,
                          String version, long sizeBytes, int fileCount, Instant createdAt, Instant updatedAt) {
        public Path dir(ZhiyeProperties props, String ws) { return dir(props, ws, id); }

        public static Path dir(ZhiyeProperties props, String ws, String id) {
            return props.rootPath().resolve("agents/skillhub").resolve(ws).resolve(id).resolve("files");
        }
    }

    private final SkillHubClient client;
    private final ZhiyeProperties props;
    private final JdbcClient jdbc;

    public SkillHubInstallService(SkillHubClient client, ZhiyeProperties props, JdbcClient jdbc) {
        this.client = client;
        this.props = props;
        this.jdbc = jdbc;
    }

    public static final String ID_PREFIX = "hub:";

    public static boolean isHubId(String id) { return id != null && id.startsWith(ID_PREFIX); }

    public static String installId(String platformSkillId) { return ID_PREFIX + platformSkillId; }

    // ---------- 查询 ----------

    public List<Install> list(String ws) {
        return jdbc.sql("select * from skillhub_install where workspace_id = ? and deleted_at is null order by created_at desc")
                .param(ws).query(SkillHubInstallService::map).list();
    }

    /** 任务执行时用：不限定工作空间（id 已在创建 / 发送消息时校验过） */
    public Optional<Install> findAny(String id) {
        if (!isHubId(id)) return Optional.empty();
        return jdbc.sql("select * from skillhub_install where id = ? and deleted_at is null").param(id).query(SkillHubInstallService::map).optional();
    }

    public Optional<Install> find(String ws, String id) {
        if (!isHubId(id)) return Optional.empty();
        return jdbc.sql("select * from skillhub_install where workspace_id = ? and id = ? and deleted_at is null").params(ws, id)
                .query(SkillHubInstallService::map).optional();
    }

    /** 给创建 / 修改任务用：工作空间里已安装的全部技能（含目录） */
    public List<Install> resolve(String ws, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> hub = ids.stream().filter(SkillHubInstallService::isHubId).distinct().toList();
        if (hub.isEmpty()) return List.of();
        Map<String, Install> byId = new LinkedHashMap<>();
        for (Install i : list(ws)) byId.put(i.id(), i);
        List<Install> out = new ArrayList<>();
        for (String id : ids) {
            Install i = byId.get(id);
            if (i != null && Files.isRegularFile(i.dir(props, ws).resolve("SKILL.md"))) out.add(i);
        }
        return out;
    }

    // ---------- 安装 / 卸载 ----------

    /** 下载并安装；同一个技能同一个版本重复安装是幂等的（返回已有记录） */
    public Install install(String ws, String token, long skillId, Long versionId) {
        int count = jdbc.sql("select count(*) from skillhub_install where workspace_id = ? and deleted_at is null").param(ws).query(Integer.class).single();
        // 先看详情：版本号以平台为准（SKILL.md 里的 version 只是兜底）
        SkillHubClient.Detail detail = client.detail(token, skillId);
        Long vid = versionId;
        if (vid == null && detail.versions() != null && !detail.versions().isEmpty()) {
            vid = detail.versions().stream().filter(v -> v.reviewStatus() != null && v.reviewStatus() == 2).map(SkillHubClient.VersionRow::versionId).findFirst()
                    .or(() -> detail.versions().stream().map(SkillHubClient.VersionRow::versionId).findFirst()).orElse(null);
        }
        SkillHubClient.PackageFile pkg = client.packageFile(token, skillId, vid);
        if (pkg.bytes().length > MAX_PACKAGE) throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL_TOO_LARGE", "技能包超过 20MB，不能安装");
        Extracted ex = extract(pkg.bytes(), skillId);
        String version = platformVersion(detail, vid);
        if (version.isBlank()) version = ex.version != null ? ex.version : "";
        String hash = sha256(pkg.bytes());
        // 同一技能同一版本内容没变：直接返回已有记录
        var same = jdbc.sql("select * from skillhub_install where workspace_id = ? and skill_id = ? and version = ? and sha256 = ? and deleted_at is null")
                .params(ws, String.valueOf(skillId), version == null ? "" : version, hash).query(SkillHubInstallService::map).optional();
        if (same.isPresent()) return same.get();
        if (count >= MAX_INSTALLS)
            throw ApiException.validation("最多安装 " + MAX_INSTALLS + " 个 SkillHub 技能，请先卸载不用的");
        Install inst = new Install(Ids.newId("hi"), ws, String.valueOf(skillId), ex.slug == null ? "skill-" + skillId : ex.slug,
                ex.title, ex.description, version == null ? "" : version, pkg.bytes().length, ex.files, Instant.now(), Instant.now());
        Path target = Install.dir(props, ws, inst.id());
        try {
            copyTo(ex.root, target);
        } catch (Exception e) {
            deleteDir(target.getParent());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_ERROR", "技能包解压失败：" + e.getMessage());
        }
        try {
            jdbc.sql("insert into skillhub_install (id, workspace_id, skill_id, skill_slug, version, name, description, size_bytes, file_count, sha256, created_at, updated_at) "
                            + "values (?,?,?,?,?,?,?,?,?,?,now(),now())")
                    .params(inst.id(), ws, inst.skillId(), inst.skillSlug(), inst.version, inst.name(), inst.description(),
                            inst.sizeBytes(), inst.fileCount(), hash).update();
        } catch (Exception e) {
            deleteDir(target.getParent());
            throw e;
        }
        log.info("workspace {} 安装 SkillHub 技能 {}（{}，{} 个文件，{} KB）", ws, skillId, inst.version, inst.fileCount, inst.sizeBytes / 1024);
        return inst;
    }

    static String platformVersion(SkillHubClient.Detail detail, Long versionId) {
        if (detail != null && detail.versions() != null) {
            for (SkillHubClient.VersionRow v : detail.versions()) if (versionId != null && versionId.equals(v.versionId())) return v.version() == null ? "" : v.version();
            for (SkillHubClient.VersionRow v : detail.versions()) if (detail.item() != null && v.version() != null && v.version().equals(detail.item().version())) return v.version();
        }
        return detail != null && detail.item() != null && detail.item().version() != null ? detail.item().version() : "";
    }

    public void uninstall(String ws, String id) {
        Install inst = find(ws, id).orElseThrow(() -> ApiException.notFound("技能"));
        jdbc.sql("update skillhub_install set deleted_at = now() where id = ?").param(inst.id()).update();
        deleteDir(Install.dir(props, ws, inst.id()).getParent());
    }

    // ---------- 包校验与解压 ----------

    record Extracted(Path root, String slug, String name, String title, String description, String version, int files) {}

    /** 解压到临时目录并校验；任何不合法都抛 400。返回临时目录（调用方负责删除） */
    Extracted extract(byte[] zip, long skillId) {
        Path tmp;
        try {
            tmp = Files.createTempDirectory("zhiye-skill-");
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SKILLHUB_ERROR", "无法创建临时目录");
        }
        int count = 0;
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("..") || name.contains("\0")) throw ApiException.validation("技能包里有不安全的文件路径：" + name);
                Path target = tmp.resolve(name).normalize();
                if (!target.startsWith(tmp)) throw ApiException.validation("技能包里有不安全的文件路径：" + name);
                if (++count > MAX_FILES) throw ApiException.validation("技能包文件太多（超过 " + MAX_FILES + " 个）");
                long size = e.getSize() > 0 ? e.getSize() : -1;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int n; (n = in.read(chunk)) > 0; ) {
                    total += n;
                    if (total > MAX_TOTAL) throw ApiException.validation("技能包解压后超过 5MB");
                    buf.write(chunk, 0, n);
                }
                if (size > 0 && size != buf.size()) { /* zip 里 size 不准时以实际为准 */ }
                if (buf.size() > MAX_FILE) throw ApiException.validation("技能包里 " + name + " 超过 1MB");
                Files.createDirectories(target.getParent());
                Files.write(target, buf.toByteArray());
            }
        } catch (ApiException e) {
            deleteDir(tmp);
            throw e;
        } catch (Exception e) {
            deleteDir(tmp);
            throw new ApiException(HttpStatus.BAD_REQUEST, "SKILL_PACKAGE_INVALID", "技能包不是有效的 zip 文件");
        }
        // 找 SKILL.md：根目录或唯一一级目录下
        Path md = tmp.resolve("SKILL.md");
        String prefix = "";
        if (!Files.isRegularFile(md)) {
            List<Path> dirs = new ArrayList<>();
            try (var s = Files.list(tmp)) { s.filter(Files::isDirectory).forEach(dirs::add); }
            catch (java.io.IOException e) { deleteDir(tmp); throw ApiException.validation("技能包读取失败"); }
            if (dirs.size() == 1) {
                md = dirs.get(0).resolve("SKILL.md");
                prefix = dirs.get(0).getFileName() + "/";
            }
        }
        if (!Files.isRegularFile(md)) {
            deleteDir(tmp);
            throw ApiException.validation("技能包里没有 SKILL.md");
        }
        String text;
        try { text = Files.readString(md, StandardCharsets.UTF_8); } catch (Exception e) {
            deleteDir(tmp);
            throw ApiException.validation("SKILL.md 不是文本文件");
        }
        SkillMeta meta = SkillMeta.parse(text);
        if (meta.rawName() == null || !meta.validName()) {
            deleteDir(tmp);
            throw ApiException.validation("SKILL.md 的 name 不合法：只能用小写字母、数字和连字符，不超过 64 个字符");
        }
        if (meta.description() == null || meta.description().isBlank()) {
            deleteDir(tmp);
            throw ApiException.validation("SKILL.md 缺少 description");
        }
        String title = text.lines().filter(l -> l.startsWith("# ")).map(l -> l.substring(2).trim()).findFirst().orElse(meta.rawName());
        // 只保留 skill 相关文件：md / txt / 代码 / 脚本 / references / scripts / assets 里的图片和数据（≤1MB）
        Path root = tmp.resolve(prefix.isEmpty() ? "." : prefix.substring(0, prefix.length() - 1)).normalize();
        if (!root.startsWith(tmp)) { deleteDir(tmp); throw ApiException.validation("技能包结构不正确"); }
        // version 从 front matter 读不到就留空
        String version = frontField(text, "version");
        return new Extracted(root, meta.rawName(), meta.rawName(), title, meta.description(), version, count);
    }

    private static String frontField(String md, String key) {
        String[] lines = md.replace("\r", "").split("\n");
        boolean in = false;
        for (String l : lines) {
            if (l.trim().equals("---")) { if (in) break; in = true; continue; }
            if (!in) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^" + key + "\\s*:\\s*(.+?)\\s*$").matcher(l);
            if (m.find()) return m.group(1).replaceAll("^['\"]|['\"]$", "");
        }
        return null;
    }

    private void copyTo(Path src, Path dst) throws Exception {
        Files.createDirectories(dst);
        try (var walk = Files.walk(src)) {
            for (Path f : walk.toList()) {
                if (Files.isDirectory(f)) continue;
                Path rel = src.relativize(f);
                String p = rel.toString().replace('\\', '/');
                if (!keep(p)) continue;
                Path t = dst.resolve(p).normalize();
                if (!t.startsWith(dst)) continue;
                Files.createDirectories(t.getParent());
                Files.copy(f, t, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!Files.isRegularFile(dst.resolve("SKILL.md"))) throw new IllegalStateException("技能包里没有 SKILL.md");
    }

    /** 允许带进 agent 目录的文件：文本类 + references/scripts/assets 下的常见资源 */
    static boolean keep(String p) {
        String l = p.toLowerCase();
        if (l.startsWith(".git/") || l.startsWith("node_modules/")) return false;
        if (l.endsWith(".md") || l.endsWith(".txt") || l.endsWith(".py") || l.endsWith(".js") || l.endsWith(".mjs") || l.endsWith(".ts")
                || l.endsWith(".sh") || l.endsWith(".json") || l.endsWith(".yml") || l.endsWith(".yaml") || l.endsWith(".csv") || l.endsWith(".svg")) return true;
        return (l.startsWith("references/") || l.startsWith("scripts/") || l.startsWith("assets/"))
                && (l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".gif") || l.endsWith(".webp") || l.endsWith(".pdf") || l.endsWith(".xlsx"));
    }

    // ---------- 行映射 ----------

    static Install map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Install(rs.getString("id"), rs.getString("workspace_id"), rs.getString("skill_id"), rs.getString("skill_slug"), rs.getString("name"), rs.getString("description"),
                rs.getString("version"), rs.getLong("size_bytes"), rs.getInt("file_count"), Db.ts(rs, "created_at"), Db.ts(rs, "updated_at"));
    }

    /** 给「我的技能」列表用：补上 SKILL.md 里的 # 标题 */
    public void withTitles(List<Map<String, Object>> out, String ws) {
        for (Map<String, Object> m : out) {
            String id = (String) m.get("id");
            if (id == null) continue;
            find(ws, id).ifPresent(inst -> {
                Path md = inst.dir(props, ws).resolve("SKILL.md");
                try {
                    if (Files.isRegularFile(md)) {
                        String t = Files.readString(md, StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("# ")).map(l -> l.substring(2).trim()).findFirst().orElse(null);
                        if (t != null) m.put("title", t);
                    }
                } catch (Exception ignored) { }
            });
        }
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            return "";
        }
    }

    static void deleteDir(Path p) {
        if (p == null) return;
        try (var s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
