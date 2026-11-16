package com.tcl.csot.zhiye.service.skillhub;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 SKILL.md 的 YAML 头部（name、description）。只支持常见写法：单行值、引号、| / > 多行块。 */
public record SkillMeta(String rawName, String description) {
    private static final Pattern FRONT = Pattern.compile("\\A\\s*---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
    public static final Pattern VALID_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    public static SkillMeta parse(String md) {
        if (md == null) return new SkillMeta(null, null);
        Matcher m = FRONT.matcher(md.replace("\r", ""));
        if (!m.find()) return new SkillMeta(null, null);
        String[] lines = m.group(1).split("\n");
        return new SkillMeta(field(lines, "name"), field(lines, "description"));
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

    public boolean validName() { return rawName != null && VALID_NAME.matcher(rawName).matches(); }

    /** 导出用的目录名：合法 name 原样使用，否则转写；转写不出时返回 null */
    public String folderName() {
        if (rawName == null) return null;
        if (validName()) return rawName;
        String s = rawName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty()) return null;
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
