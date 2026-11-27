package com.tcl.csot.zhiye.service.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.service.runtime.ServiceRuntimeDetector;
import com.tcl.csot.zhiye.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码驱动的项目形态自洽校正器（解除创建时 project_kind 锁死）：
 * <ul>
 *   <li>当 {@code service} 应用被用户/AI 去除后端代码、仅剩静态 HTML 或纯前端工程时，自动降级为 {@code static} 或 {@code vite}，启用轻量静态构建与工作台内嵌预览；</li>
 *   <li>当 {@code static} / {@code template} / {@code vue} / {@code vite} 应用由编码 Agent 明确编写了真实后端服务时，自动升级为 {@code service} 托管。</li>
 * </ul>
 * 仅做只读文件扫描与必要的静态目录整理，不执行任何用户代码。
 */
public final class WorkspaceKindReconciler {
    private WorkspaceKindReconciler() {}

    public record Decision(
            String targetKind,
            String targetFramework,
            boolean changed,
            boolean promotePublicDir,
            String reason
    ) {
        public static Decision unchanged(String currentKind) {
            return new Decision(currentKind, null, false, false, null);
        }
    }

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", ".zhiye", ".data", "target", "dist", "build", ".next", "__pycache__", ".venv", "venv"
    );
    private static final Set<String> FRONTEND_CONFIG_FILES = Set.of(
            "vite.config.ts", "vite.config.js", "vite.config.mjs", "vite.config.cjs",
            "postcss.config.js", "postcss.config.cjs", "postcss.config.mjs",
            "tailwind.config.js", "tailwind.config.ts", "tailwind.config.cjs", "tailwind.config.mjs",
            "eslint.config.js", "eslint.config.mjs"
    );
    private static final Pattern NODE_FILE_IN_SCRIPT = Pattern.compile("\\b([A-Za-z0-9_./-]+\\.(?:mjs|cjs|js|ts))\\b");
    private static final Pattern EXTRA_NODE_SERVER_CODE = Pattern.compile(
            "(NestFactory|http\\.createServer|https\\.createServer|\\.listen\\s*\\(|express\\s*\\(|fastify\\s*\\(|new\\s+Koa\\s*\\(|new\\s+Hono\\s*\\()");
    private static final int MAX_SCAN_FILES = 5000;
    private static final int MAX_READ_BYTES = 64 * 1024;

    /** 检查工作区真实代码结构，判断是否需要演进 project_kind。 */
    public static Decision inspect(Path workspace, String currentKind) {
        if (workspace == null || !Files.isDirectory(workspace)) return Decision.unchanged(currentKind);
        String kind = (currentKind == null || currentKind.isBlank()) ? "template" : currentKind.trim();
        // 技能包与无页面脚本不参与网页/服务形态互转
        if ("skill".equals(kind) || "script".equals(kind)) {
            return Decision.unchanged(kind);
        }

        Set<String> files = scanFiles(workspace);
        if (files.isEmpty()) return Decision.unchanged(kind);
        Map<String, String> cache = new HashMap<>();
        Function<String, String> reader = rel -> cache.computeIfAbsent(rel, r -> readTextSafe(workspace.resolve(r)));

        Optional<ServiceRuntimeDetector.Spec> liveBackend = detectLiveBackend(files, reader);

        if ("service".equals(kind)) {
            if (liveBackend.isPresent()) {
                return Decision.unchanged(kind);
            }
            // 后端已不存在：检查是否为纯前端 Vite 工程或纯静态 HTML
            if (isPureFrontendVite(files, reader)) {
                return new Decision("vite", "纯前端项目（已去除后端）", true, false,
                        "检测到后端服务代码已移除，仅保留前端 Vite 工程，已自动切换为纯前端模式并启用内嵌预览");
            }
            if (files.contains("index.html")) {
                return new Decision("static", "纯静态 HTML", true, false,
                        "检测到后端服务代码已移除，已自动切换为纯静态 HTML 模式并启用内嵌预览");
            }
            if (files.contains("public/index.html")) {
                return new Decision("static", "纯静态 HTML", true, true,
                        "检测到后端服务代码已移除且页面位于 public/index.html，已自动整理为纯静态 HTML 模式并启用内嵌预览");
            }
            return Decision.unchanged(kind);
        }

        // 非 service 网页应用：仅当工作区里真的出现了可运行的后端服务源码时，自动演进为 service
        if (liveBackend.isPresent()) {
            ServiceRuntimeDetector.Spec spec = liveBackend.get();
            return new Decision("service", spec.framework(), true, false,
                    "检测到项目已新增独立后端服务（" + spec.framework() + "），已自动切换为全栈服务托管模式");
        }
        return Decision.unchanged(kind);
    }

    /**
     * 当旧全栈服务去掉 server/ 后页面留在 public/ 目录时，将 public/* 平铺提升至根目录，
     * 以便静态构建与内嵌预览直接以根目录 index.html 为入口且保持 ./assets/... 相对路径可用。
     */
    public static boolean promotePublicToRoot(Path workspace) throws IOException {
        Path pub = workspace.resolve("public");
        if (!Files.isDirectory(pub) || !Files.isRegularFile(pub.resolve("index.html"))) {
            return false;
        }
        List<Path> toMove = new ArrayList<>();
        try (var stream = Files.walk(pub)) {
            stream.filter(Files::isRegularFile).forEach(toMove::add);
        }
        for (Path src : toMove) {
            Path rel = pub.relativize(src);
            Path dst = workspace.resolve(rel).normalize();
            if (!dst.startsWith(workspace)) continue;
            Files.createDirectories(dst.getParent());
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        // 删除空目录（自底向上）
        List<Path> dirs = new ArrayList<>();
        try (var stream = Files.walk(pub)) {
            stream.filter(Files::isDirectory).forEach(dirs::add);
        }
        dirs.sort(Comparator.reverseOrder());
        for (Path d : dirs) {
            try { Files.deleteIfExists(d); } catch (DirectoryNotEmptyException ignored) {}
        }
        return Files.isRegularFile(workspace.resolve("index.html"));
    }

    /**
     * 严格判定工作区是否存在「真正可运行的后端服务代码」：
     * 过滤掉已被删掉 server.js 但 package.json 仍残留 express 依赖的空壳项目。
     */
    static Optional<ServiceRuntimeDetector.Spec> detectLiveBackend(Set<String> files, Function<String, String> reader) {
        Optional<ServiceRuntimeDetector.Spec> detected = ServiceRuntimeDetector.detect(files, reader);
        if (detected.isEmpty()) return Optional.empty();
        ServiceRuntimeDetector.Spec spec = detected.get();
        String rt = spec.runtime().toLowerCase(Locale.ROOT);
        String dir = spec.backendDir() == null ? "" : spec.backendDir().trim();
        String prefix = dir.isEmpty() ? "" : dir + "/";

        return switch (rt) {
            case "dockerfile" -> hasFile(prefix, "Dockerfile", files) ? detected : Optional.empty();
            case "go" -> hasAnySuffix(prefix, ".go", files) ? detected : Optional.empty();
            case "java" -> hasAnySuffix(prefix, ".java", files) ? detected : Optional.empty();
            case "python" -> hasLivePythonBackend(prefix, files, reader) ? detected : Optional.empty();
            case "node" -> hasLiveNodeBackend(prefix, spec, files, reader) ? detected : Optional.empty();
            default -> detected;
        };
    }

    private static boolean hasLivePythonBackend(String prefix, Set<String> files, Function<String, String> reader) {
        if (files.contains(prefix + "manage.py") || files.contains(prefix + "Dockerfile")) return true;
        for (String f : files) {
            if (!f.startsWith(prefix) || !f.endsWith(".py")) continue;
            if (ServiceRuntimeDetector.looksLikePythonWebSource(reader.apply(f))) return true;
        }
        return false;
    }

    private static boolean hasLiveNodeBackend(String prefix, ServiceRuntimeDetector.Spec spec, Set<String> files, Function<String, String> reader) {
        if ("Next.js (SSR)".equals(spec.framework())) return true;
        if (files.contains(prefix + "Dockerfile")) return true;

        // 1. 若 package.json 的 scripts.start 指向某个脚本文件，检查该文件是否存在且包含服务端监听/框架代码
        String pkgPath = prefix + "package.json";
        if (files.contains(pkgPath)) {
            try {
                JsonNode pkg = Json.M.readTree(reader.apply(pkgPath));
                String start = pkg.path("scripts").path("start").asText("").trim();
                if (!start.isEmpty()) {
                    Matcher m = NODE_FILE_IN_SCRIPT.matcher(start);
                    while (m.find()) {
                        String cand = prefix + m.group(1).replaceFirst("^\\./", "");
                        if (files.contains(cand)) {
                            String src = reader.apply(cand);
                            if (isNodeServerSource(src)) return true;
                        }
                    }
                }
            } catch (Exception ignored) { }
        }

        // 2. 扫描后端目录（排除 public/assets 与前端配置文件）查找真实的服务端监听源码
        for (String f : files) {
            if (!f.startsWith(prefix)) continue;
            String rel = f.substring(prefix.length());
            if (!(rel.endsWith(".js") || rel.endsWith(".mjs") || rel.endsWith(".cjs") || rel.endsWith(".ts"))) continue;
            if (rel.startsWith("public/") || rel.startsWith("assets/") || rel.startsWith("src/components/") || rel.startsWith("src/pages/")) continue;
            if (FRONTEND_CONFIG_FILES.contains(rel)) continue;
            String src = reader.apply(f);
            if (isNodeServerSource(src)) return true;
        }
        return false;
    }

    private static boolean isNodeServerSource(String src) {
        if (src == null || src.isBlank()) return false;
        return ServiceRuntimeDetector.looksLikeNodeWebSource(src) || EXTRA_NODE_SERVER_CODE.matcher(src).find();
    }

    private static boolean isPureFrontendVite(Set<String> files, Function<String, String> reader) {
        if (!files.contains("package.json") || !files.contains("index.html")) return false;
        boolean hasViteConfig = files.contains("vite.config.ts") || files.contains("vite.config.js") || files.contains("vite.config.mjs");
        try {
            JsonNode pkg = Json.M.readTree(reader.apply("package.json"));
            boolean hasViteDep = pkg.path("dependencies").has("vite") || pkg.path("devDependencies").has("vite");
            String build = pkg.path("scripts").path("build").asText("");
            return (hasViteConfig || hasViteDep) && (!build.isBlank() || hasViteDep);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasFile(String prefix, String name, Set<String> files) {
        return files.contains(prefix + name);
    }

    private static boolean hasAnySuffix(String prefix, String suffix, Set<String> files) {
        for (String f : files) {
            if (f.startsWith(prefix) && f.endsWith(suffix)) return true;
        }
        return false;
    }

    private static Set<String> scanFiles(Path root) {
        Set<String> out = new LinkedHashSet<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    String name = dir.getFileName().toString();
                    if (IGNORED_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (out.size() >= MAX_SCAN_FILES) return FileVisitResult.TERMINATE;
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    out.add(rel);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) { }
        return out;
    }

    private static String readTextSafe(Path file) {
        try {
            if (!Files.isRegularFile(file)) return "";
            byte[] bytes = Files.readAllBytes(file);
            int len = Math.min(bytes.length, MAX_READ_BYTES);
            return new String(bytes, 0, len, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
