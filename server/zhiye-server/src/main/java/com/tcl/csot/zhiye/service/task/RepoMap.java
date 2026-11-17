package com.tcl.csot.zhiye.service.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为 modify 任务生成紧凑的仓库结构索引（Repo Map）与最近版本变更摘要，
 * 避免每个新修改任务从零探索目录、重复读取无关文件。
 */
public final class RepoMap {
    private static final int MAX_FILES = 50;
    private static final int MAX_SYMBOLS_PER_FILE = 5;
    private static final int MAX_CHARS = 2600;
    private static final Set<String> IGNORED_DIRS = Set.of(".git", "node_modules", ".zhiye", "dist", "build", "target");
    private static final Set<String> TEXT_EXT = Set.of(
            "ts", "tsx", "js", "jsx", "mjs", "vue", "html", "css", "sql", "json", "py", "sh", "md"
    );
    private static final Pattern TS_SYMBOL = Pattern.compile(
            "(?m)^\\s*(?:export\\s+(?:default\\s+)?)?(?:async\\s+)?(?:function|const|class|interface|type|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    );
    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?im)^\\s*create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(?:\"([^\"]+)\"|([a-zA-Z0-9_]+))"
    );
    private static final Pattern PY_SYMBOL = Pattern.compile(
            "(?m)^(?:def|class)\\s+([A-Za-z_][A-Za-z0-9_]*)"
    );

    public record VersionNote(int number, String kind, String title) {}

    private RepoMap() {}

    public static String build(Path workspace, String projectKind, List<VersionNote> recentVersions) {
        if (workspace == null || !Files.isDirectory(workspace)) return "";
        StringBuilder out = new StringBuilder();
        if (recentVersions != null && !recentVersions.isEmpty()) {
            out.append("\n\n## 最近版本演进\n");
            for (VersionNote v : recentVersions) {
                String t = v.title() == null ? "" : v.title().replaceAll("\\s+", " ").trim();
                out.append("- V").append(v.number());
                if (v.kind() != null && !v.kind().isBlank()) out.append(" [").append(v.kind()).append(']');
                if (!t.isEmpty()) out.append("：").append(t.length() > 80 ? t.substring(0, 80) + "…" : t);
                out.append('\n');
            }
        }

        List<Path> files = collectFiles(workspace);
        if (files.isEmpty()) return out.toString();

        out.append("\n## 当前项目地图（").append(projectKind == null ? "template" : projectKind).append("）\n");
        int count = 0;
        for (Path abs : files) {
            if (count >= MAX_FILES || out.length() >= MAX_CHARS) {
                if (count < files.size()) out.append("- … 其余 ").append(files.size() - count).append(" 个文件可用文件工具查看\n");
                break;
            }
            String rel = workspace.relativize(abs).toString().replace('\\', '/');
            String line = summarizeFile(abs, rel);
            if (out.length() + line.length() > MAX_CHARS) break;
            out.append(line);
            count++;
        }
        return out.toString();
    }

    private static List<Path> collectFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root)) {
                        String name = dir.getFileName().toString();
                        if (IGNORED_DIRS.contains(name) || name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || attrs.size() > 512_000) return FileVisitResult.CONTINUE;
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (rel.equals("package-lock.json") || rel.endsWith(".lock")) return FileVisitResult.CONTINUE;
                    String ext = extOf(rel);
                    if (TEXT_EXT.contains(ext)) files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { }

        files.sort(Comparator.comparingInt((Path p) -> priority(root.relativize(p).toString().replace('\\', '/')))
                .thenComparing(p -> root.relativize(p).toString()));
        return files;
    }

    private static int priority(String rel) {
        if (rel.equals("src/App.tsx") || rel.equals("src/App.vue") || rel.equals("index.html")) return 0;
        if (rel.equals("cloud/schema.sql") || rel.equals("cloud/policies.json")) return 1;
        if (rel.startsWith("functions/")) return 2;
        if (rel.startsWith("src/pages/") || rel.startsWith("src/views/") || rel.startsWith("src/components/")) return 3;
        if (rel.startsWith("src/")) return 4;
        return 5;
    }

    private static String summarizeFile(Path file, String rel) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
            List<String> symbols = extractSymbols(rel, text);
            StringBuilder sb = new StringBuilder("- ").append(rel).append("（").append(lines).append(" 行）");
            if (!symbols.isEmpty()) sb.append("：").append(String.join(", ", symbols));
            sb.append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "- " + rel + "\n";
        }
    }

    private static List<String> extractSymbols(String rel, String text) {
        LinkedHashSet<String> syms = new LinkedHashSet<>();
        String ext = extOf(rel);
        Matcher m;
        if ("sql".equals(ext)) {
            m = SQL_TABLE.matcher(text);
            while (m.find() && syms.size() < MAX_SYMBOLS_PER_FILE) {
                String tbl = m.group(1) != null ? m.group(1) : m.group(2);
                if (tbl != null) syms.add("table " + tbl);
            }
        } else if ("py".equals(ext)) {
            m = PY_SYMBOL.matcher(text);
            while (m.find() && syms.size() < MAX_SYMBOLS_PER_FILE) syms.add(m.group(1));
        } else if (Set.of("ts", "tsx", "js", "jsx", "mjs", "vue").contains(ext)) {
            m = TS_SYMBOL.matcher(text);
            while (m.find() && syms.size() < MAX_SYMBOLS_PER_FILE) syms.add(m.group(1));
        }
        return new ArrayList<>(syms);
    }

    private static String extOf(String rel) {
        int i = rel.lastIndexOf('.');
        return i < 0 ? "" : rel.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
