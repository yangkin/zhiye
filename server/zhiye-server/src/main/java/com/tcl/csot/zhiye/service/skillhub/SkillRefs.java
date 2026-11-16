package com.tcl.csot.zhiye.service.skillhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.repository.AppRepository;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.exception.ApiException;
import com.tcl.csot.zhiye.util.Db;
import com.tcl.csot.zhiye.util.Ids;
import com.tcl.csot.zhiye.service.repo.GitService;
import com.tcl.csot.zhiye.repository.VersionRepository;
import com.tcl.csot.zhiye.entity.VersionRow;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * 创建应用 / 对话时引用技能：自己的「开发skill」应用 + 从 SkillHub 安装的技能 + 平台内置技能。
 * 技能的 SKILL.md、references/、scripts/ 文本文件放进 agent 目录（不是用户项目）的 skills/{name}/ 下，
 * 并在指令里要求遵循这些技能。
 */
@Service
public class SkillRefs {
    public static final int MAX_SKILLS = 5;
    public static final int MAX_TOTAL_BYTES = 60 * 1024;

    public record Placed(String name, Path dir) {}

    public record Builtin(String name, String description, Path dir) {}

    /** 一个被引用的技能（不论来自应用、SkillHub 安装还是内置），以及它的 SKILL.md 原文 */
    public record Resolved(String name, String description, String markdown, Path dir, String appId) {}

    private final AppRepository apps;
    private final VersionRepository versions;
    private final GitService git;
    private final com.tcl.csot.zhiye.config.ZhiyeProperties props;
    private SkillHubInstallService installs;

    public SkillRefs(AppRepository apps, VersionRepository versions, GitService git, com.tcl.csot.zhiye.config.ZhiyeProperties props) {
        this.apps = apps;
        this.versions = versions;
        this.git = git;
        this.props = props;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setInstalls(SkillHubInstallService installs) { this.installs = installs; }

    public List<SkillMeta> builtinCatalog() {
        List<SkillMeta> out = new ArrayList<>();
        Path root = props.rootPath().resolve("packages/builtin-skills");
        if (!Files.isDirectory(root)) return out;
        try (var dirs = Files.list(root)) {
            for (Path d : dirs.sorted().toList()) {
                Path md = d.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) continue;
                SkillMeta m = SkillMeta.parse(Files.readString(md));
                if (m.validName() && m.description() != null) out.add(m);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** 给前端展示的内置技能：name、标题（SKILL.md 第一个 # 标题）、说明 */
    public List<Map<String, String>> builtinList() {
        List<Map<String, String>> out = new ArrayList<>();
        for (SkillMeta m : builtinCatalog()) {
            String md = readBuiltin(m.rawName(), "SKILL.md");
            String title = md == null ? m.rawName() : md.lines().filter(l -> l.startsWith("# ")).map(l -> l.substring(2).trim()).findFirst().orElse(m.rawName());
            out.add(Map.of("name", m.rawName(), "title", title, "description", m.description()));
        }
        return out;
    }

    /** 已安装的 SkillHub 技能（给「我的技能」列表和技能名解析用） */
    public List<Map<String, Object>> hubList(String ws) {
        if (installs == null || ws == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillHubInstallService.Install i : installs.list(ws)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.id());
            m.put("name", i.name());
            m.put("title", i.name());
            m.put("description", i.description());
            m.put("version", i.version());
            m.put("source", "skillhub");
            m.put("skillId", i.skillId());
            m.put("updatedAt", i.updatedAt() == null ? null : Db.iso(i.updatedAt()));
            Path md = i.dir(props, ws).resolve("SKILL.md");
            try {
                if (Files.isRegularFile(md)) {
                    String t = Files.readString(md, StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("# ")).map(l -> l.substring(2).trim()).findFirst().orElse(null);
                    if (t != null) m.put("title", t);
                }
            } catch (Exception ignored) { }
            out.add(m);
        }
        return out;
    }

    /** 读取内置技能里的一个文本文件（问答的 read_skill 用）；名称或路径不合法返回 null */
    public String readBuiltin(String name, String rel) {
        if (name == null || !SkillMeta.VALID_NAME.matcher(name).matches()) return null;
        Path dir = props.rootPath().resolve("packages/builtin-skills").resolve(name).normalize();
        Path f = dir.resolve(rel == null || rel.isBlank() ? "SKILL.md" : rel).normalize();
        if (!f.startsWith(dir) || !Files.isRegularFile(f)) return null;
        try {
            if (Files.size(f) > 64 * 1024) return null;
            byte[] data = Files.readAllBytes(f);
            return isText(data) ? new String(data, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public List<Builtin> placeBuiltin(Path agentDir, Set<String> taken) throws Exception {
        List<Builtin> out = new ArrayList<>();
        Path root = props.rootPath().resolve("packages/builtin-skills");
        for (SkillMeta m : builtinCatalog()) {
            if (taken.contains(m.rawName())) continue;
            Path src = root.resolve(m.rawName());
            if (!Files.isRegularFile(src.resolve("SKILL.md"))) continue;
            Path dst = agentDir.resolve("skills").resolve(m.rawName());
            try (var walk = Files.walk(src)) {
                for (Path f : walk.toList()) {
                    Path t = dst.resolve(src.relativize(f).toString());
                    if (Files.isDirectory(f)) Files.createDirectories(t);
                    else if (Files.isRegularFile(f) && Files.size(f) <= 64 * 1024) { Files.createDirectories(t.getParent()); Files.copy(f, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                }
            }
            out.add(new Builtin(m.rawName(), m.description(), dst));
        }
        return out;
    }

    public static String catalogInstruction(List<Builtin> builtins) {
        if (builtins.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## 可用技能（按需加载）\n下面是织页内置的技能，任务涉及其中的场景时，先用 read 读取对应的 SKILL.md 再动手；不相关的不用读：\n");
        for (Builtin b : builtins) sb.append("- ").append(b.name()).append("：").append(b.description()).append("（").append(b.dir().resolve("SKILL.md")).append("）\n");
        return sb.toString();
    }

    /** 校验 skillAppIds：最多 MAX_SKILLS 个；「开发skill」应用要属于当前工作空间且已生成，SkillHub 安装（hub: 前缀）要属于当前工作空间 */
    public List<String> validate(String ws, JsonNode ids) {
        if (ids == null || ids.isNull()) return List.of();
        if (!ids.isArray()) throw ApiException.validation("skillAppIds 必须是数组");
        if (ids.size() > MAX_SKILLS) throw ApiException.validation("一次最多引用 " + MAX_SKILLS + " 个技能");
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (JsonNode n : ids) {
            String id = n.asText("");
            if (SkillHubInstallService.isHubId(id)) {
                if (installs == null || installs.find(ws, id).isEmpty()) throw ApiException.validation("引用的 SkillHub 技能不存在或已卸载");
            } else {
                AppRow app = apps.find(ws, id).filter(a -> a.visible() && a.isSkill())
                        .orElseThrow(() -> ApiException.validation("引用的技能不存在或不属于你"));
                if (app.currentVersionId() == null) throw ApiException.validation("技能「" + app.name() + "」还没有生成完成，暂时不能引用");
            }
            out.add(id);
        }
        return List.copyOf(out);
    }

    public List<String> validate(String ws, List<String> ids) {
        return validate(ws, ids == null ? null : com.tcl.csot.zhiye.util.Json.M.valueToTree(ids));
    }

    /** 解析引用的技能（应用 + SkillHub 安装），供问答 / 计划使用 */
    public List<Resolved> resolveAll(String ws, List<String> skillAppIds) {
        List<String> ids = normalizeIds(skillAppIds);
        if (ids.isEmpty()) return List.of();
        List<Resolved> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            Resolved r = SkillHubInstallService.isHubId(id) ? resolveHub(ws, id) : resolveApp(id);
            if (r == null || !seen.add(r.name())) continue;
            out.add(r);
        }
        return out;
    }

    private static List<String> normalizeIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String id : ids) if (id != null && !id.isBlank()) set.add(id.trim());
        return set.stream().limit(MAX_SKILLS).toList();
    }

    private Resolved resolveHub(String ws, String id) {
        if (installs == null) return null;
        return installs.find(ws, id).map(i -> {
            Path dir = i.dir(props, ws);
            String md = readText(dir.resolve("SKILL.md"), 128 * 1024);
            if (md == null) return null;
            SkillMeta meta = SkillMeta.parse(md);
            String name = meta.rawName() != null && meta.validName() ? meta.rawName() : i.skillSlug();
            return new Resolved(name, meta.description() != null ? meta.description() : i.description(), md, dir, null);
        }).orElse(null);
    }

    private Resolved resolveApp(String id) {
        Optional<AppRow> appOpt = apps.findById(id).filter(a -> a.deletedAt() == null && a.currentVersionId() != null);
        if (appOpt.isEmpty()) return null;
        AppRow app = appOpt.get();
        VersionRow v = versions.find(app.currentVersionId()).orElse(null);
        if (v == null) return null;
        String md;
        try {
            md = git.readFile(app.id(), v.gitCommit(), "SKILL.md").map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
        if (md == null) return null;
        SkillMeta meta = SkillMeta.parse(md);
        String name = SkillHubService.folderName(app, md);
        return new Resolved(name, meta.description(), md, null, app.id());
    }

    /** 写入 {agentDir}/skills/{name}/…（应用与 SkillHub 安装都支持）；总量不超过 60 KB（SKILL.md 优先） */
    public List<Placed> place(Path agentDir, List<String> skillAppIds) throws Exception {
        List<Placed> placed = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int[] budget = {MAX_TOTAL_BYTES};
        List<Resolved> resolved = resolveAllFromAnyWorkspace(skillAppIds);
        for (Resolved r : resolved) {
            String unique = r.name();
            for (int i = 2; !names.add(unique); i++) unique = r.name() + "-" + i;
            Path dir = agentDir.resolve("skills").resolve(unique);
            Files.createDirectories(dir);
            if (!write(dir, "SKILL.md", r.markdown().getBytes(StandardCharsets.UTF_8), budget)) continue;
            if (r.dir() != null) {
                copyTree(r.dir(), dir, budget);
            } else if (r.appId() != null) {
                VersionRow v = versions.find(apps.findById(r.appId()).orElseThrow().currentVersionId()).orElse(null);
                if (v != null) {
                    for (var f : git.listFiles(r.appId(), v.gitCommit())) {
                        if (!(f.path().startsWith("references/") || f.path().startsWith("scripts/"))) continue;
                        if (f.size() > budget[0]) continue;
                        byte[] data = git.readFile(r.appId(), v.gitCommit(), f.path()).orElse(null);
                        if (data == null || !isText(data)) continue;
                        write(dir, f.path(), data, budget);
                    }
                }
            }
            placed.add(new Placed(unique, dir));
        }
        return placed;
    }

    /** place 的解析不做工作空间校验（任务里的 id 已经在创建 / 发送时校验过）；SkillHub 安装按记录里的工作空间找目录 */
    private List<Resolved> resolveAllFromAnyWorkspace(List<String> skillAppIds) {
        List<String> ids = normalizeIds(skillAppIds);
        if (ids.isEmpty()) return List.of();
        List<Resolved> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            Resolved r;
            if (SkillHubInstallService.isHubId(id)) {
                r = installs == null ? null : installs.findAny(id).map(i -> {
                    Path dir = SkillHubInstallService.Install.dir(props, i.workspaceId(), i.id());
                    String md = readText(dir.resolve("SKILL.md"), 128 * 1024);
                    if (md == null) return null;
                    SkillMeta meta = SkillMeta.parse(md);
                    String name = meta.rawName() != null && meta.validName() ? meta.rawName() : i.skillSlug();
                    return new Resolved(name, meta.description() != null ? meta.description() : i.description(), md, dir, null);
                }).orElse(null);
            } else {
                r = resolveApp(id);
            }
            if (r != null && seen.add(r.name())) out.add(r);
        }
        return out;
    }

    private void copyTree(Path src, Path dst, int[] budget) throws Exception {
        if (!Files.isDirectory(src)) return;
        try (var walk = Files.walk(src)) {
            for (Path f : walk.toList()) {
                if (Files.isDirectory(f)) continue;
                Path rel = src.relativize(f);
                String p = rel.toString().replace('\\', '/');
                if (!(p.startsWith("references/") || p.startsWith("scripts/"))) continue;
                if (Files.size(f) > budget[0]) continue;
                byte[] data = Files.readAllBytes(f);
                if (!isText(data)) continue;
                write(dst, p, data, budget);
            }
        }
    }

    /**
     * 计划阶段用：只读每个技能的 SKILL.md，拼成一段文本（总预算 8KB）。
     * 计划阶段没有 agent 目录，不需要落文件；内容只用于让计划遵循技能的规范。
     */
    public String planContext(String ws, List<String> skillAppIds) throws Exception {
        List<Resolved> all = resolveAll(ws, skillAppIds);
        if (all.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int budget = 8000;
        for (Resolved r : all) {
            if (budget <= 0) break;
            String md = r.markdown();
            String text = md.length() > budget ? md.substring(0, budget) + "\n…（已截断）" : md;
            budget -= text.length();
            String title = r.appId() != null ? apps.findById(r.appId()).map(AppRow::name).orElse(r.name()) : r.name();
            out.append("\n【技能：").append(title).append("】\n").append(text).append('\n');
        }
        return out.toString();
    }

    /** 问答用：每个技能的 SKILL.md + references/scripts 文本，拼成一段（总预算 8KB） */
    public String chatContext(String ws, List<String> skillAppIds) {
        List<Resolved> all = resolveAll(ws, skillAppIds);
        if (all.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        int budget = 8000;
        for (Resolved r : all) {
            if (budget <= 0) break;
            StringBuilder files = new StringBuilder();
            files.append("\n--- SKILL.md ---\n").append(truncate(r.markdown(), budget));
            budget -= files.length();
            if (r.dir() != null) appendTree(r.dir(), files, budget);
            else if (r.appId() != null) appendGit(r.appId(), files, budget);
            out.append("\n【技能包：").append(r.name()).append("】").append(files);
        }
        return out.toString();
    }

    private void appendTree(Path dir, StringBuilder sb, int budget) {
        try (var walk = Files.walk(dir)) {
            for (Path f : walk.sorted().toList()) {
                if (budget <= 0 || Files.isDirectory(f)) continue;
                String p = dir.relativize(f).toString().replace('\\', '/');
                if (p.equals("SKILL.md") || !(p.startsWith("references/") || p.startsWith("scripts/") || p.endsWith(".md") || p.endsWith(".txt"))) continue;
                byte[] data = Files.readAllBytes(f);
                if (!isText(data)) continue;
                String t = truncate(new String(data, StandardCharsets.UTF_8), budget);
                sb.append("\n--- ").append(p).append(" ---\n").append(t);
                budget -= t.length();
            }
        } catch (Exception ignored) { }
    }

    private void appendGit(String appId, StringBuilder sb, int budget) {
        try {
            AppRow app = apps.findById(appId).orElse(null);
            if (app == null || app.currentVersionId() == null) return;
            VersionRow v = versions.find(app.currentVersionId()).orElse(null);
            if (v == null) return;
            for (var f : git.listFiles(appId, v.gitCommit())) {
                if (budget <= 0) break;
                String p = f.path();
                if (p.equals("SKILL.md") || !(p.startsWith("references/") || p.startsWith("scripts/") || p.endsWith(".md") || p.endsWith(".txt"))) continue;
                byte[] data = git.readFile(appId, v.gitCommit(), p).orElse(null);
                if (data == null || !isText(data)) continue;
                String t = truncate(new String(data, StandardCharsets.UTF_8), budget);
                sb.append("\n--- ").append(p).append(" ---\n").append(t);
                budget -= t.length();
            }
        } catch (Exception ignored) { }
    }

    private static String truncate(String s, int budget) {
        if (s == null) return "";
        return s.length() > budget ? s.substring(0, budget) + "\n…（已截断）" : s;
    }

    private static String readText(Path p, int max) {
        try {
            if (!Files.isRegularFile(p) || Files.size(p) > max) return null;
            byte[] data = Files.readAllBytes(p);
            return isText(data) ? new String(data, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean write(Path dir, String rel, byte[] data, int[] budget) throws Exception {
        if (data.length > budget[0]) return false;
        Path target = dir.resolve(rel).normalize();
        if (!target.startsWith(dir)) return false;
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        budget[0] -= data.length;
        return true;
    }

    static boolean isText(byte[] data) {
        for (byte b : data) if (b == 0) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 指令里的技能说明 */
    public static String instruction(List<Placed> placed) {
        if (placed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## 参考技能\n请遵循以下技能的规范完成任务：");
        for (int i = 0; i < placed.size(); i++) {
            Placed p = placed.get(i);
            if (i > 0) sb.append("；");
            sb.append(p.name()).append("（说明见 ").append(p.dir().resolve("SKILL.md")).append("）");
        }
        sb.append("\n先读取这些 SKILL.md（以及其中引用的 references/、scripts/ 文件），再开始工作。这些文件只读，不要复制进项目，除非任务要求。\n");
        return sb.toString();
    }
}
