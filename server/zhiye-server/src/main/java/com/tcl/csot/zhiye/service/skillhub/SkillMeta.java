package com.tcl.csot.zhiye.service.skillhub;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析与校验 SKILL.md 的 YAML 头部（name、description、compatibility）以及 SkillHub 安全与清理规范。 */
public record SkillMeta(String rawName, String description, String compatibility, boolean hasCompatibilityField) {
    private static final Pattern FRONT = Pattern.compile("\\A\\s*---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
    public static final Pattern VALID_NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?");
    public static final Pattern VERSION_IN_NAME = Pattern.compile("(?:^|-)v\\d+(?:-\\d+)*$|-\\d+-\\d+(?:-\\d+)*$");
    public static final int MAX_DESCRIPTION_LEN = 120;
    public static final int MAX_DISPLAY_NAME_LEN = 20;
    public static final int MAX_SKILL_MD_LINES = 500;

    private static final Pattern VAGUE_COMPATIBILITY = Pattern.compile(
            "^(?:仅?兼容飞书|兼容飞书|飞书|通用|全平台通用|无|无特殊要求|无依赖|不限|all|any|general|none|n/a)[。.!！\\s]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> GENERIC_DISPLAY_NAMES = Set.of(
            "spc", "产品助手", "助手", "ai助手", "智能助手", "工具", "新技能", "技能", "skill", "测试", "test", "demo", "示例");

    private static final Set<String> JUNK_DIRS = Set.of(
            "tmp", "temp", "logs", "cache", ".cache", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", "coverage", "output");
    private static final Pattern JUNK_FILE = Pattern.compile(
            "^(?:\\.DS_Store|Thumbs\\.db|\\.env(?:\\..+)?|(?:debug|test[_-]?output).*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern JUNK_EXT = Pattern.compile("\\.(?:log|tmp|temp|bak|swp|swo|orig|pyc|pyo)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "^(?:your[_-]|xxx+|placeholder|example|sample|dummy|test|changeme|replace|demo|\\*+|<[^>]+>|\\$\\{[^}]+}|%[a-z_]+%)",
            Pattern.CASE_INSENSITIVE);

    private record SecretRule(String label, Pattern pattern) {}
    private static final List<SecretRule> SECRET_RULES = List.of(
            new SecretRule("飞书用户 ID（ou_xxx）", Pattern.compile("\\bou_[a-zA-Z0-9]{16,}\\b")),
            new SecretRule("飞书应用 ID（cli_xxx）", Pattern.compile("\\bcli_[a-f0-9]{16,}\\b")),
            new SecretRule("私钥内容", Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
            new SecretRule("JWT 令牌", Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")),
            new SecretRule("API Key / Token", Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})\\b")),
            new SecretRule("Bearer 令牌", Pattern.compile("\\bBearer\\s+[A-Za-z0-9._-]{20,}\\b"))
    );
    private static final Pattern ASSIGN_SECRET = Pattern.compile(
            "(?:^|[\\s,{])[\"']?(password|passwd|pwd|app_?secret|api_?key|access_?token|auth_?token|secret_?key|private_?key)[\"']?\\s*[:=]\\s*[\"']([^\"'\\r\\n\\s]{8,})[\"']",
            Pattern.CASE_INSENSITIVE);

    public SkillMeta(String rawName, String description) {
        this(rawName, description, null, false);
    }

    public static SkillMeta parse(String md) {
        if (md == null) return new SkillMeta(null, null, null, false);
        Matcher m = FRONT.matcher(md.replace("\r", ""));
        if (!m.find()) return new SkillMeta(null, null, null, false);
        String[] lines = m.group(1).split("\n");
        boolean hasCompat = hasField(lines, "compatibility");
        return new SkillMeta(field(lines, "name"), field(lines, "description"), field(lines, "compatibility"), hasCompat);
    }

    private static boolean hasField(String[] lines, String key) {
        Pattern p = Pattern.compile("^" + key + "\\s*:");
        for (String line : lines) if (p.matcher(line).find()) return true;
        return false;
    }

    private static String field(String[] lines, String key) {
        for (int i = 0; i < lines.length; i++) {
            Matcher f = Pattern.compile("^" + key + "\\s*:\\s*(.*)$").matcher(lines[i]);
            if (!f.find()) continue;
            String v = f.group(1).trim();
            if (v.equals("|") || v.equals(">") || v.equals("|-") || v.equals(">-")) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j < lines.length && (lines[j].startsWith(" ") || lines[j].startsWith("\t") || lines[j].isBlank()); j++)
                    sb.append(lines[j].trim()).append(v.startsWith("|") ? "\n" : " ");
                v = sb.toString().trim();
            } else {
                v = v.replaceFirst("\\s+#.*$", "");
                if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'")))
                    v = v.substring(1, v.length() - 1);
            }
            return v.isBlank() ? null : v.trim();
        }
        return null;
    }

    public boolean hasVersionInName() {
        return rawName != null && VERSION_IN_NAME.matcher(rawName).find();
    }

    public boolean validName() {
        return rawName != null && VALID_NAME.matcher(rawName).matches() && !rawName.contains("--") && !hasVersionInName();
    }

    public boolean vagueCompatibility() {
        if (!hasCompatibilityField) return false;
        return compatibility == null || compatibility.isBlank() || VAGUE_COMPATIBILITY.matcher(compatibility.trim()).matches();
    }

    public static int codePointLength(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    public static int lineCount(String md) {
        if (md == null || md.isEmpty()) return 0;
        return md.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1).length;
    }

    public static boolean isGenericDisplayName(String displayName) {
        if (displayName == null) return true;
        String norm = displayName.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return norm.isEmpty() || GENERIC_DISPLAY_NAMES.contains(norm);
    }

    public static boolean isJunkPath(String relPath) {
        if (relPath == null || relPath.isBlank()) return false;
        String[] segs = relPath.replace('\\', '/').split("/");
        for (int i = 0; i < segs.length - 1; i++) {
            if (JUNK_DIRS.contains(segs[i].toLowerCase(Locale.ROOT))) return true;
        }
        String base = segs[segs.length - 1];
        return JUNK_DIRS.contains(base.toLowerCase(Locale.ROOT))
                || JUNK_FILE.matcher(base).matches()
                || JUNK_EXT.matcher(base).find()
                || base.endsWith("~");
    }

    public static List<String> scanSecrets(String text) {
        if (text == null || text.isEmpty()) return List.of();
        Set<String> hits = new LinkedHashSet<>();
        for (SecretRule rule : SECRET_RULES) {
            if (rule.pattern().matcher(text).find()) hits.add(rule.label());
        }
        Matcher m = ASSIGN_SECRET.matcher(text);
        while (m.find()) {
            String val = m.group(2);
            if (!PLACEHOLDER.matcher(val).find() && !val.contains("<") && !val.contains("${")) {
                hits.add("明文凭证字段 " + m.group(1));
            }
        }
        return new ArrayList<>(hits);
    }

    /** 导出用的目录名：合法 name 原样使用，否则转写；转写不出时返回 null */
    public String folderName() {
        if (rawName == null) return null;
        if (validName()) return rawName;
        String s = rawName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty()) return null;
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
