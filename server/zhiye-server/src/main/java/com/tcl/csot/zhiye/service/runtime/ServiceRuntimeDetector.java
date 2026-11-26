package com.tcl.csot.zhiye.service.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单体后端项目（Python / Node.js / Java / Go / Dockerfile / 前后端一体）运行时自动探测。
 * 仅读取少量配置文件与入口文件，不执行任何用户代码。
 */
public final class ServiceRuntimeDetector {
    private ServiceRuntimeDetector() {}

    public record Spec(
            String runtime,       // python | node | java | go | dockerfile
            String framework,     // 人类可读框架名称（永不为 null 或空串）
            String frontendDir,   // 前后端一体时的前端子目录（空表示无独立前端）
            String backendDir,    // 后端目录（空表示根目录）
            String buildCmd,      // 推断的构建命令
            String startCmd,      // 推断的启动命令
            int port,             // 默认端口
            String healthPath,    // 健康检查路径
            int memoryMb,         // 推荐内存上限
            int cpuMillicores     // 推荐 CPU 上限
    ) {
        public Spec {
            runtime = (runtime == null || runtime.isBlank()) ? "node" : runtime.trim();
            framework = (framework == null || framework.isBlank()) ? defaultFrameworkFor(runtime) : framework.trim();
            frontendDir = frontendDir == null ? "" : frontendDir.trim();
            backendDir = backendDir == null ? "" : backendDir.trim();
            buildCmd = buildCmd == null ? "" : buildCmd.trim();
            startCmd = startCmd == null ? "" : startCmd.trim();
            healthPath = (healthPath == null || healthPath.isBlank()) ? "/" : healthPath.trim();
        }
    }

    public static String defaultFrameworkFor(String runtime) {
        if (runtime == null) return "Node.js 服务端";
        return switch (runtime.trim().toLowerCase(Locale.ROOT)) {
            case "java" -> "Java (Spring Boot)";
            case "python" -> "Python";
            case "go" -> "Go";
            case "dockerfile" -> "Docker 自定义服务";
            default -> "Node.js 服务端";
        };
    }

    private static final List<String> BACKEND_HINTS = List.of("backend", "server", "api", "service", "app");
    private static final List<String> FRONTEND_HINTS = List.of("frontend", "web", "client", "ui", "portal", "admin");
    private static final Pattern EXPOSE_PORT = Pattern.compile("(?im)^\\s*EXPOSE\\s+(\\d{2,5})\\b");
    private static final Pattern HEALTH_ROUTE = Pattern.compile("['\"`](/healthz?|/api/healthz?|/actuator/health)['\"`]");
    private static final Pattern PY_WEB_REQS = Pattern.compile(
            "(?i)\\b(fastapi|flask|django|streamlit|uvicorn|gunicorn|aiohttp|tornado|sanic|starlette|gradio|bottle|falcon|quart)\\b");
    private static final Pattern PY_WEB_CODE = Pattern.compile(
            "(FastAPI\\s*\\(|Flask\\s*\\(|HTTPServer|ThreadingHTTPServer|BaseHTTPRequestHandler|http\\.server|wsgiref|socketserver|uvicorn\\.run|streamlit|aiohttp\\.web|tornado\\.web|app\\.run\\s*\\()");
    private static final Pattern NODE_WEB_CODE = Pattern.compile(
            "(createServer\\s*\\(|\\.listen\\s*\\(|\\bexpress\\s*\\(|\\bfastify\\s*\\(|\\bnew\\s+Koa\\s*\\(|\\bnew\\s+Hono\\s*\\()");

    public static boolean looksLikePythonWebSource(String src) {
        return src != null && PY_WEB_CODE.matcher(src).find();
    }

    public static boolean looksLikeNodeWebSource(String src) {
        return src != null && NODE_WEB_CODE.matcher(src).find();
    }

    public static Optional<Spec> detect(Set<String> files, Function<String, String> reader) {
        if (files == null || files.isEmpty()) return Optional.empty();
        // 1. 先检查根目录是否直接是可运行单体（或自定义 Dockerfile）
        Optional<Spec> rootSpec = detectSingleDir("", files, reader);
        String frontSub = findFrontendSubdir(files, reader);
        if (rootSpec.isPresent()) {
            Spec s = rootSpec.get();
            if (!frontSub.isEmpty() && !"dockerfile".equals(s.runtime())) {
                String frontFw = detectFrontendLabel(frontSub, files, reader);
                return Optional.of(new Spec(s.runtime(), s.framework() + " + " + frontFw, frontSub, s.backendDir(),
                        s.buildCmd(), s.startCmd(), s.port(), s.healthPath(), s.memoryMb(), s.cpuMillicores()));
            }
            return rootSpec;
        }
        // 2. 检查常见后端子目录（如 backend/ + frontend/ 前后端一体结构）
        for (String hint : BACKEND_HINTS) {
            Optional<Spec> sub = detectSingleDir(hint, files, reader);
            if (sub.isPresent()) {
                Spec s = sub.get();
                String fw = frontSub.isEmpty() ? s.framework() : s.framework() + " + " + detectFrontendLabel(frontSub, files, reader);
                return Optional.of(new Spec(s.runtime(), fw, frontSub, hint, s.buildCmd(), s.startCmd(),
                        s.port(), s.healthPath(), s.memoryMb(), s.cpuMillicores()));
            }
        }
        // 3. 兜底扫描所有一级子目录（兼容 fastapi-backend / react-frontend 等非固定命名仓库）
        Set<String> topDirs = new LinkedHashSet<>();
        for (String f : files) {
            int slash = f.indexOf('/');
            if (slash > 0) topDirs.add(f.substring(0, slash));
        }
        for (String d : topDirs) {
            if (BACKEND_HINTS.contains(d) || d.equals(frontSub)) continue;
            // 避免把仅含前端 + Dockerfile 的子目录（如 react-frontend/）误判为后端目录
            if (isFrontendDir(d, files, reader) && !hasBackendLanguageMarker(d, files, reader)) continue;
            Optional<Spec> sub = detectSingleDir(d, files, reader);
            if (sub.isPresent()) {
                Spec s = sub.get();
                String resolvedFront = frontSub.isEmpty() ? findAnyFrontendSubdir(topDirs, d, files, reader) : frontSub;
                String fw = resolvedFront.isEmpty() ? s.framework() : s.framework() + " + " + detectFrontendLabel(resolvedFront, files, reader);
                return Optional.of(new Spec(s.runtime(), fw, resolvedFront, d, s.buildCmd(), s.startCmd(),
                        s.port(), s.healthPath(), s.memoryMb(), s.cpuMillicores()));
            }
        }
        return Optional.empty();
    }

    public static Optional<Spec> detectSingleDir(String dir, Set<String> files, Function<String, String> reader) {
        // 先探测具体语言（Python / Go / Java / Node），这样即使仓库自带 Dockerfile，
        // 本地隔离进程也能准确编译启动，同时 K8s Kaniko 模式仍会自动优先采用仓库的自定义 Dockerfile。

        // Python（仅当明确包含 Web/HTTP 框架或服务监听代码时按常驻服务托管；纯脚本交给 script 流程）
        String anyPy = findRootByExt(dir, List.of(".py"), files);
        if (has(dir, "requirements.txt", files) || has(dir, "pyproject.toml", files) || has(dir, "manage.py", files)
                || hasAny(dir, List.of("app.py", "main.py", "server.py"), files)
                || (anyPy != null && looksLikePythonWebSource(safeRead(dir, anyPy, reader)))) {
            if (isPythonWebService(dir, files, reader) || (anyPy != null && looksLikePythonWebSource(safeRead(dir, anyPy, reader))) || has(dir, "Dockerfile", files)) {
                return Optional.of(detectPython(dir, files, reader));
            }
        }

        // Go
        if (has(dir, "go.mod", files) || has(dir, "main.go", files)) {
            return Optional.of(detectGo(dir, files, reader));
        }

        // Java
        if (has(dir, "pom.xml", files) || has(dir, "build.gradle", files) || has(dir, "build.gradle.kts", files)
                || hasJavaEntry(dir, files)) {
            return Optional.of(detectJava(dir, files, reader));
        }

        // Node.js 服务端 / Next.js SSR
        if (has(dir, "package.json", files)) {
            Optional<Spec> node = detectNodeServer(dir, files, reader);
            if (node.isPresent()) return node;
        } else {
            // 无 package.json 的零依赖 Node HTTP 服务（例如 node:http 的 server.js / server/index.mjs）
            String rawNodeEntry = firstPresent(dir, List.of(
                    "server.js", "server.mjs", "server.cjs", "index.js", "index.mjs", "app.js", "app.mjs",
                    "server/index.mjs", "server/index.js", "server/server.mjs", "server/server.js",
                    "src/server.mjs", "src/server.js", "src/index.mjs", "src/index.js"), files);
            if (rawNodeEntry == null) rawNodeEntry = findRootByExt(dir, List.of(".mjs", ".js", ".cjs"), files);
            if (rawNodeEntry != null) {
                String src = safeRead(dir, rawNodeEntry, reader);
                if (looksLikeNodeWebSource(src)) {
                    return Optional.of(new Spec("node", "Node.js HTTP 服务", "", dir, "", "node " + rawNodeEntry,
                            8080, guessHealthPath(src), 512, 500));
                }
            }
        }

        // 无已知语言标记但包含自定义 Dockerfile 时，按自定义容器服务托管
        if (has(dir, "Dockerfile", files)) {
            String df = safeRead(dir, "Dockerfile", reader);
            int port = 8080;
            Matcher m = EXPOSE_PORT.matcher(df);
            if (m.find()) {
                int p = Integer.parseInt(m.group(1));
                if (p > 0 && p <= 65535) port = p;
            }
            return Optional.of(new Spec("dockerfile", "Docker 自定义服务", "", dir, "", "", port, "/", 512, 500));
        }

        return Optional.empty();
    }

    private static boolean isPythonWebService(String dir, Set<String> files, Function<String, String> reader) {
        if (has(dir, "manage.py", files)) return true;
        String reqs = safeRead(dir, "requirements.txt", reader) + "\n" + safeRead(dir, "pyproject.toml", reader);
        if (PY_WEB_REQS.matcher(reqs).find()) return true;
        for (String name : List.of("main.py", "app.py", "server.py", "wsgi.py", "asgi.py")) {
            if (has(dir, name, files) && looksLikePythonWebSource(safeRead(dir, name, reader))) return true;
        }
        return false;
    }

    private static Spec detectPython(String dir, Set<String> files, Function<String, String> reader) {
        String reqs = (safeRead(dir, "requirements.txt", reader) + "\n" + safeRead(dir, "pyproject.toml", reader)).toLowerCase(Locale.ROOT);
        String entry = firstPresent(dir, List.of("main.py", "app.py", "server.py", "wsgi.py", "asgi.py", "manage.py"), files);
        if (entry == null) entry = findRootByExt(dir, List.of(".py"), files);
        String entrySrc = entry == null ? "" : safeRead(dir, entry, reader);
        String health = guessHealthPath(entrySrc);
        String build = has(dir, "requirements.txt", files) ? "pip install --no-cache-dir -r requirements.txt"
                : has(dir, "pyproject.toml", files) ? "pip install --no-cache-dir ." : "";

        if (has(dir, "manage.py", files) || reqs.contains("django")) {
            return new Spec("python", "Python (Django)", "", dir, build,
                    "python3 manage.py runserver 0.0.0.0:${PORT:-8080}", 8080, health, 512, 500);
        }
        if (reqs.contains("streamlit") || entrySrc.contains("import streamlit")) {
            String f = entry != null ? entry : "app.py";
            return new Spec("python", "Python (Streamlit)", "", dir, build,
                    "streamlit run " + f + " --server.port=${PORT:-8080} --server.address=0.0.0.0 --server.headless=true",
                    8080, "/_stcore/health", 768, 500);
        }
        if (reqs.contains("fastapi") || entrySrc.contains("FastAPI(")) {
            String mod = (entry != null ? entry : "main.py").replaceFirst("\\.py$", "");
            String start = reqs.contains("uvicorn") || entrySrc.contains("uvicorn")
                    ? "python3 -m uvicorn " + mod + ":app --host 0.0.0.0 --port ${PORT:-8080}"
                    : "python3 " + (entry != null ? entry : "main.py");
            return new Spec("python", "Python (FastAPI)", "", dir, build, start, 8080, health, 512, 500);
        }
        if (reqs.contains("flask") || entrySrc.contains("Flask(")) {
            String f = entry != null ? entry : "app.py";
            String start = reqs.contains("gunicorn")
                    ? "gunicorn -b 0.0.0.0:${PORT:-8080} " + f.replaceFirst("\\.py$", "") + ":app"
                    : "python3 " + f;
            return new Spec("python", "Python (Flask)", "", dir, build, start, 8080, health, 512, 500);
        }
        String f = entry != null ? entry : "main.py";
        return new Spec("python", "Python", "", dir, build, "python3 " + f, 8080, health, 512, 500);
    }

    private static Spec detectGo(String dir, Set<String> files, Function<String, String> reader) {
        String mainFile = has(dir, "main.go", files) ? "main.go" : findGoMain(dir, files);
        String src = mainFile == null ? "" : safeRead(dir, mainFile, reader);
        String pkgTarget = mainFile != null && mainFile.contains("/") ? "./" + mainFile.substring(0, mainFile.lastIndexOf('/')) : ".";
        String build = "go build -o app " + pkgTarget;
        String fw = src.contains("gin-gonic/gin") ? "Go (Gin)"
                : src.contains("labstack/echo") ? "Go (Echo)"
                : src.contains("gofiber/fiber") ? "Go (Fiber)" : "Go";
        return new Spec("go", fw, "", dir, build, "./app", 8080, guessHealthPath(src), 256, 500);
    }

    private static Spec detectJava(String dir, Set<String> files, Function<String, String> reader) {
        if (has(dir, "pom.xml", files)) {
            String pom = safeRead(dir, "pom.xml", reader);
            boolean boot = pom.contains("spring-boot");
            String health = boot && pom.contains("actuator") ? "/actuator/health" : "/";
            return new Spec("java", boot ? "Java (Spring Boot)" : "Java (Maven)", "", dir,
                    "mvn -B -DskipTests -Dcheckstyle.skip=true -Dspotless.check.skip=true package",
                    "java -jar app.jar --server.port=${PORT:-8080}",
                    8080, health, 1024, 1000);
        }
        if (has(dir, "build.gradle", files) || has(dir, "build.gradle.kts", files)) {
            String gr = safeRead(dir, has(dir, "build.gradle", files) ? "build.gradle" : "build.gradle.kts", reader);
            boolean boot = gr.contains("spring-boot");
            String cmd = has(dir, "gradlew", files) ? "./gradlew build -x test" : "gradle build -x test";
            return new Spec("java", boot ? "Java (Spring Boot)" : "Java (Gradle)", "", dir,
                    cmd, "java -jar app.jar --server.port=${PORT:-8080}", 8080, "/", 1024, 1000);
        }
        // 纯 JDK 单文件 / 无构建工具 Java HTTP 服务（例如 com.sun.net.httpserver）
        String mainClass = findJavaMainClass(dir, files);
        String build = "mkdir -p out && javac -d out $(find . -name '*.java')";
        String start = "java -cp out " + (mainClass != null ? mainClass : "Main");
        return new Spec("java", "Java", "", dir, build, start, 8080, "/", 512, 500);
    }

    private static Optional<Spec> detectNodeServer(String dir, Set<String> files, Function<String, String> reader) {
        String raw = safeRead(dir, "package.json", reader);
        if (raw.isBlank()) return Optional.empty();
        JsonNode pkg;
        try { pkg = Json.M.readTree(raw); } catch (Exception e) { return Optional.empty(); }
        if (!pkg.isObject()) return Optional.empty();

        Set<String> deps = new HashSet<>();
        for (String sec : List.of("dependencies", "devDependencies", "peerDependencies")) {
            pkg.path(sec).fieldNames().forEachRemaining(deps::add);
        }
        String startScript = pkg.path("scripts").path("start").asText("").trim();
        String buildScript = pkg.path("scripts").path("build").asText("").trim();

        // Next.js SSR（非静态导出）
        if (deps.contains("next")) {
            String cfg = firstText(dir, List.of("next.config.js", "next.config.mjs", "next.config.ts", "next.config.cjs"), files, reader);
            boolean staticExport = cfg.matches("(?s).*output\\s*:\\s*['\"]export['\"].*") || buildScript.contains("next export");
            if (staticExport) return Optional.empty();
            return Optional.of(new Spec("node", "Next.js (SSR)", "", dir,
                    buildScript.isEmpty() ? "npx next build" : "npm run build",
                    "npx next start -H 0.0.0.0 -p ${PORT:-8080}", 8080, "/", 768, 500));
        }

        String serverFw = deps.contains("@nestjs/core") ? "Node.js (NestJS)"
                : deps.contains("express") ? "Node.js (Express)"
                : deps.contains("fastify") ? "Node.js (Fastify)"
                : deps.contains("koa") ? "Node.js (Koa)"
                : deps.contains("hono") ? "Node.js (Hono)"
                : deps.contains("egg") ? "Node.js (Egg.js)"
                : deps.contains("@midwayjs/core") ? "Node.js (Midway)"
                : deps.contains("hapi") || deps.contains("@hapi/hapi") ? "Node.js (Hapi)" : null;

        boolean hasFrontendTool = deps.contains("vite") || deps.contains("@vue/cli-service") || deps.contains("react-scripts")
                || deps.contains("@angular/cli") || deps.contains("umi") || deps.contains("@umijs/max");
        // 纯前端项目交给 static/vite 流程，不当作纯后端
        if (serverFw == null && hasFrontendTool) return Optional.empty();

        String mainEntry = pkg.path("main").asText("").trim();
        String entry = !mainEntry.isEmpty() && has(dir, mainEntry, files) ? mainEntry
                : firstPresent(dir, List.of(
                        "server.js", "server.mjs", "server.cjs", "index.js", "index.mjs", "app.js", "app.mjs",
                        "server/index.mjs", "server/index.js", "server/server.mjs", "server/server.js",
                        "server/app.mjs", "server/app.js",
                        "src/server.mjs", "src/server.js", "src/index.mjs", "src/index.js",
                        "src/app.mjs", "src/app.js"), files);
        if (entry == null && !startScript.isEmpty()) {
            Matcher sm = Pattern.compile("\\b([A-Za-z0-9_./-]+\\.(?:mjs|cjs|js))\\b").matcher(startScript);
            while (sm.find()) {
                String cand = sm.group(1).replaceFirst("^\\./", "");
                if (has(dir, cand, files)) { entry = cand; break; }
            }
        }

        String src = entry == null ? "" : safeRead(dir, entry, reader);
        if (serverFw == null && startScript.isEmpty() && !looksLikeNodeWebSource(src)) return Optional.empty();
        // 无主流 Web 框架且入口脚本未监听端口（纯 CLI/数据处理脚本），交由 script 流程在线运行
        if (serverFw == null && !has(dir, "Dockerfile", files) && !looksLikeNodeWebSource(src) && !src.contains("PORT")) {
            return Optional.empty();
        }
        if (serverFw == null) serverFw = "Node.js 服务端";

        String startCmd = !startScript.isEmpty() ? "npm start" : ("node " + (entry != null ? entry : "index.js"));
        String buildCmd = !buildScript.isEmpty() ? "npm run build" : "";
        return Optional.of(new Spec("node", serverFw, "", dir, buildCmd, startCmd, 8080, guessHealthPath(src), 512, 500));
    }

    private static String findFrontendSubdir(Set<String> files, Function<String, String> reader) {
        for (String hint : FRONTEND_HINTS) {
            if (isFrontendDir(hint, files, reader)) return hint;
        }
        return "";
    }

    private static String findAnyFrontendSubdir(Set<String> topDirs, String excludeBackend, Set<String> files, Function<String, String> reader) {
        for (String d : topDirs) {
            if (d.equals(excludeBackend)) continue;
            if (isFrontendDir(d, files, reader)) return d;
        }
        return "";
    }

    private static boolean hasBackendLanguageMarker(String dir, Set<String> files, Function<String, String> reader) {
        return has(dir, "requirements.txt", files) || has(dir, "pyproject.toml", files) || has(dir, "manage.py", files)
                || has(dir, "go.mod", files) || has(dir, "main.go", files)
                || has(dir, "pom.xml", files) || has(dir, "build.gradle", files) || has(dir, "build.gradle.kts", files)
                || (has(dir, "package.json", files) && detectNodeServer(dir, files, reader).isPresent());
    }

    private static boolean isFrontendDir(String dir, Set<String> files, Function<String, String> reader) {
        if (has(dir, "package.json", files)) {
            String raw = safeRead(dir, "package.json", reader);
            if (raw.contains("vite") || raw.contains("react") || raw.contains("vue") || raw.contains("next")) return true;
        }
        return has(dir, "index.html", files);
    }

    private static String detectFrontendLabel(String dir, Set<String> files, Function<String, String> reader) {
        if (has(dir, "package.json", files)) {
            String raw = safeRead(dir, "package.json", reader);
            if (raw.contains("vue")) return "Vue";
            if (raw.contains("react")) return "React";
            if (raw.contains("vite")) return "Vite";
        }
        return "静态前端";
    }

    private static boolean hasJavaEntry(String dir, Set<String> files) {
        String prefix = dir.isEmpty() ? "" : dir + "/";
        for (String f : files) {
            if (!f.startsWith(prefix) || !f.endsWith(".java")) continue;
            String rel = f.substring(prefix.length());
            // 仅匹配当前目录直属的 *.java 或 src/ 下的 *.java，避免根目录误命中 backend/src/... 导致跳过 backend/pom.xml
            if (!rel.contains("/") || rel.startsWith("src/")) return true;
        }
        return false;
    }

    private static String findJavaMainClass(String dir, Set<String> files) {
        String prefix = dir.isEmpty() ? "" : dir + "/";
        for (String f : files) {
            if (!f.startsWith(prefix) || !f.endsWith(".java")) continue;
            String rel = f.substring(prefix.length());
            if (!rel.contains("/")) return rel.replaceFirst("\\.java$", "");
        }
        return null;
    }

    private static String findGoMain(String dir, Set<String> files) {
        String prefix = dir.isEmpty() ? "" : dir + "/";
        for (String f : files) {
            if (f.startsWith(prefix) && (f.endsWith("/main.go") || f.equals(prefix + "main.go"))) {
                return f.substring(prefix.length());
            }
        }
        return null;
    }

    private static String guessHealthPath(String src) {
        if (src == null || src.isBlank()) return "/";
        Matcher m = HEALTH_ROUTE.matcher(src);
        return m.find() ? m.group(1) : "/";
    }

    private static boolean has(String dir, String name, Set<String> files) {
        return files.contains(dir.isEmpty() ? name : dir + "/" + name);
    }

    private static boolean hasAny(String dir, List<String> names, Set<String> files) {
        return firstPresent(dir, names, files) != null;
    }

    private static String findRootByExt(String dir, List<String> exts, Set<String> files) {
        String prefix = dir.isEmpty() ? "" : dir + "/";
        for (String f : files) {
            if (!f.startsWith(prefix)) continue;
            String rel = f.substring(prefix.length());
            if (rel.contains("/")) continue;
            String lower = rel.toLowerCase(Locale.ROOT);
            for (String ext : exts) if (lower.endsWith(ext)) return rel;
        }
        return null;
    }

    private static String firstPresent(String dir, List<String> names, Set<String> files) {
        for (String n : names) if (has(dir, n, files)) return n;
        return null;
    }

    private static String firstText(String dir, List<String> names, Set<String> files, Function<String, String> reader) {
        for (String n : names) if (has(dir, n, files)) return safeRead(dir, n, reader);
        return "";
    }

    private static String safeRead(String dir, String name, Function<String, String> reader) {
        String path = dir.isEmpty() ? name : dir + "/" + name;
        String s = reader.apply(path);
        return s == null ? "" : s;
    }
}
