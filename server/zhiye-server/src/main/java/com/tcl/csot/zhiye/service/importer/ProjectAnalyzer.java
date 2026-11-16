package com.tcl.csot.zhiye.service.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.util.Json;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导入体检：在压缩包的文件列表里找出可部署的项目目录（候选），识别构建方式，并列出部署后可能出问题的地方。
 * 只读取小的文本文件，不执行任何代码。runner 的 importer.mjs 按同样的规则做最终识别。
 */
public final class ProjectAnalyzer {
    public record Candidate(String dir, String kind, String framework, String label, boolean prebuilt) {}

    public record Warning(String level, String code, String title, String detail) {}

    public record Report(String framework, boolean deployable, String unsupportedReason, String projectKind, String rootDir,
                         List<Candidate> candidates, List<Warning> warnings) {}

    private static final Set<String> BUILD_OUT = Set.of("dist", "build", "out");
    private static final List<String> FRONT_DIR_HINTS = List.of("frontend", "web", "ui", "client", "admin", "app", "front", "portal", "site");
    private static final Map<String, String> NATIVE = new LinkedHashMap<>();
    static {
        NATIVE.put("node-sass", "node-sass 需要编译原生模块，建议改用 sass");
        NATIVE.put("sharp", "sharp 需要下载原生二进制");
        NATIVE.put("canvas", "canvas 需要编译原生模块");
        NATIVE.put("puppeteer", "puppeteer 安装时要下载浏览器");
        NATIVE.put("electron", "electron 是桌面应用框架");
        NATIVE.put("sqlite3", "sqlite3 需要编译原生模块");
        NATIVE.put("bcrypt", "bcrypt 需要编译原生模块");
    }
    private static final Pattern SCAN_EXT = Pattern.compile("(?i).*\\.(js|jsx|ts|tsx|vue|mjs|cjs|svelte)$");
    private static final Pattern API_CALL = Pattern.compile("(axios|fetch\\s*\\(|baseURL|\\$http|request\\s*\\()");
    private static final Pattern API_PATH = Pattern.compile("['\"`](/(?:api|prod-api|dev-api|gateway|admin-api|server)[/'\"`?])");
    private static final Pattern HISTORY_ROUTER = Pattern.compile("createWebHistory|BrowserRouter|createBrowserRouter|mode\\s*:\\s*['\"]history['\"]|history\\s*:\\s*\\{\\s*type\\s*:\\s*['\"]browser");
    private static final Pattern ENV_API = Pattern.compile("(?m)^\\s*((?:VITE|VUE_APP|REACT_APP|UMI_APP)_[A-Z0-9_]*(?:API|URL|HOST|BASE)[A-Z0-9_]*)\\s*=\\s*(\\S+)");
    private static final Pattern PROXY = Pattern.compile("\\bproxy\\s*:");
    private static final Pattern VITE_BASE = Pattern.compile("\\bbase\\s*:\\s*['\"`](/[^'\"`]+)['\"`]");
    private static final Pattern PUBLIC_PATH = Pattern.compile("publicPath\\s*:\\s*['\"`](/[^'\"`]*)['\"`]");
    private static final long SCAN_FILE_MAX = 256 * 1024, SCAN_TOTAL_MAX = 6L << 20;

    private final Map<String, Long> files;
    private final Function<String, String> reader;

    private ProjectAnalyzer(Map<String, Long> files, Function<String, String> reader) {
        this.files = files;
        this.reader = reader;
    }

    /** files：去掉统一顶层目录后的相对路径 → 大小；reader：读取小文本文件，失败返回 null。 */
    public static Report analyze(Map<String, Long> files, Function<String, String> reader) {
        return new ProjectAnalyzer(files, reader).run();
    }

    private record Node(String dir, String framework, String tool, String reason, JsonNode pkg) {}

    private Report run() {
        List<String> dirs = projectDirs();
        Map<String, Node> nodes = new LinkedHashMap<>();
        String backend = null, backendDir = null;
        List<Candidate> candidates = new ArrayList<>();
        for (String d : dirs) {
            if (has(d, "package.json")) {
                Node n = classifyNode(d);
                nodes.put(d, n);
                if (n.reason == null && n.tool != null) candidates.add(new Candidate(d, "vite", n.framework, labelFor(d, n.framework), false));
                else if (n.reason == null && has(d, "index.html")) candidates.add(new Candidate(d, "static", htmlLabel(d), labelFor(d, htmlLabel(d)), false));
                else if (n.reason != null && backend == null && n.framework.startsWith("Node.js")) { backend = n.framework; backendDir = d; }
                continue;
            }
            String b = backendOf(d);
            if (b != null) { if (backend == null) { backend = b; backendDir = d; } continue; }
            if (has(d, "index.html") && !insideNodeSource(d, nodes.keySet()) && !insideStatic(d, candidates)) {
                boolean pre = isBuildOutput(d);
                String fw = pre ? "已构建产物" : htmlLabel(d);
                candidates.add(new Candidate(d, "static", fw, labelFor(d, fw), pre));
            }
        }
        candidates.sort(Comparator.comparingInt(this::rank));
        if (candidates.isEmpty()) return unsupported(nodes, backend);

        Candidate primary = candidates.get(0);
        List<Warning> warnings = new ArrayList<>();
        if (backend != null)
            warnings.add(risk("backend", "后端代码不会部署",
                    "检测到" + backend + "后端" + (backendDir == null || backendDir.isEmpty() ? "" : "（" + backendDir + "/）")
                            + "。织页只部署前端" + (primary.dir().isEmpty() ? "" : " " + primary.dir() + "/")
                            + "，原来依赖后端的页面需要接入数据源，或由已经上线的后端提供接口。"));
        else if (!primary.dir().isEmpty())
            warnings.add(info("subdir", "只部署子目录", "项目在 " + primary.dir() + "/ 目录下，只会导入这个目录。"));
        if ("vite".equals(primary.kind())) nodeWarnings(nodes.get(primary.dir()), warnings);
        else staticWarnings(primary, warnings);
        for (Candidate c : candidates) {
            if (c.prebuilt() && "vite".equals(primary.kind())) {
                warnings.add(info("prebuilt", "可以直接部署已构建的产物",
                        "压缩包里有已经构建好的 " + c.dir() + "/。如果从源码构建失败，可以改选「" + c.label() + "」直接部署，不需要安装依赖。"));
                break;
            }
        }
        return new Report(primary.framework(), true, null, primary.kind(), primary.dir(), candidates, warnings);
    }

    private Report unsupported(Map<String, Node> nodes, String backend) {
        Node rootNode = nodes.get("");
        if (rootNode != null && rootNode.reason != null) return fail(rootNode.framework, rootNode.reason);
        if (backend != null) return fail(backend, "暂不支持部署 " + backend + " 项目：需要服务端运行环境。织页只能部署前端页面，如果项目里有前端目录，请单独打包前端后导入");
        if (!nodes.isEmpty()) { Node n = nodes.values().iterator().next(); return fail(n.framework, n.reason); }
        return fail("待识别", "没有找到可以部署的网页：需要根目录（或子目录）里有 index.html，或带 build 脚本的前端项目");
    }

    private static Report fail(String fw, String reason) {
        return new Report(fw, false, reason, null, "", List.of(), List.of());
    }

    // ---------- 识别 ----------
    private Node classifyNode(String d) {
        JsonNode pkg = null;
        try { String s = read(d, "package.json"); if (s != null) pkg = Json.M.readTree(s); } catch (Exception ignored) { }
        if (pkg == null || !pkg.isObject()) return new Node(d, "未知", null, "package.json 格式不正确，无法识别项目类型", null);
        Set<String> deps = deps(pkg);
        String build = pkg.path("scripts").path("build").asText("");
        if (deps.contains("next")) {
            String cfg = firstText(d, "next.config.js", "next.config.mjs", "next.config.ts", "next.config.cjs");
            boolean export = (cfg != null && cfg.matches("(?s).*output\\s*:\\s*['\"]export['\"].*")) || build.contains("next export");
            return export ? new Node(d, "Next.js（静态导出）", "next", null, pkg)
                    : new Node(d, "Next.js", null, "暂不支持部署 Next.js 项目：需要服务端运行环境。如果项目可以静态导出（next.config 里 output: 'export'），请开启后重新导入", pkg);
        }
        if (deps.contains("nuxt") || deps.contains("nuxt3"))
            return new Node(d, "Nuxt.js", null, "暂不支持部署 Nuxt.js 项目：需要服务端运行环境。如果项目可以 nuxt generate 生成静态站点，请上传生成后的 .output/public 目录", pkg);
        if (deps.contains("@tarojs/taro") || deps.contains("@dcloudio/uni-app"))
            return new Node(d, deps.contains("@tarojs/taro") ? "Taro" : "uni-app", null, "暂不支持部署小程序 / 跨端项目，请导入它构建出的 H5 产物（包含 index.html 的目录）", pkg);
        String ui = deps.contains("vue") ? "Vue" : deps.contains("react") ? "React" : deps.contains("@angular/core") ? "Angular"
                : deps.contains("svelte") ? "Svelte" : deps.contains("solid-js") ? "Solid" : deps.contains("preact") ? "Preact" : null;
        String tool = deps.contains("vite") ? "Vite" : deps.contains("@vue/cli-service") ? "Vue CLI" : deps.contains("react-scripts") ? "Create React App"
                : deps.contains("@craco/craco") ? "CRACO" : deps.contains("umi") || deps.contains("@umijs/max") ? "Umi" : deps.contains("@angular/cli") ? "Angular CLI"
                : deps.contains("@rsbuild/core") ? "Rsbuild" : deps.contains("@rspack/cli") ? "Rspack" : deps.contains("parcel") ? "Parcel"
                : deps.contains("webpack") ? "Webpack" : null;
        boolean server = deps.contains("express") || deps.contains("koa") || deps.contains("@nestjs/core") || deps.contains("fastify")
                || deps.contains("egg") || deps.contains("@midwayjs/core") || deps.contains("hapi") || deps.contains("@hapi/hapi");
        if (ui == null && tool == null) {
            if (server) return new Node(d, "Node.js 服务端", null, "暂不支持部署 Node.js 服务端项目：需要服务端运行环境", pkg);
            if (has(d, "index.html")) return new Node(d, htmlLabel(d), null, null, pkg);
            return new Node(d, "未知", null, "无法识别项目类型：package.json 里没有前端框架或构建工具，目录里也没有 index.html", pkg);
        }
        if (build.isBlank() && !"Vite".equals(tool)) {
            if (has(d, "index.html") && tool == null) return new Node(d, htmlLabel(d), null, null, pkg);
            return new Node(d, ui != null ? ui : tool, null, "无法构建这个 " + (ui != null ? ui : tool) + " 项目：package.json 里没有 build 脚本。请在 scripts 里添加 build 命令后重新导入", pkg);
        }
        String fw = tool == null ? ui : ui == null || "Angular CLI".equals(tool) || "Create React App".equals(tool) || "Umi".equals(tool) ? tool : tool + " + " + ui;
        return new Node(d, fw, tool == null ? "script" : tool, null, pkg);
    }

    private String backendOf(String d) {
        if (has(d, "pom.xml") || has(d, "build.gradle") || has(d, "build.gradle.kts")) return "Java";
        if (has(d, "requirements.txt") || has(d, "pyproject.toml") || has(d, "manage.py")) return "Python";
        if (has(d, "go.mod")) return "Go";
        if (has(d, "composer.json")) return "PHP";
        return null;
    }

    private List<String> projectDirs() {
        Set<String> out = new TreeSet<>(Comparator.comparingInt((String s) -> s.isEmpty() ? 0 : s.split("/").length).thenComparing(s -> s));
        for (String p : files.keySet()) {
            int i = p.lastIndexOf('/');
            String dir = i < 0 ? "" : p.substring(0, i), name = p.substring(i + 1);
            if (!dir.isEmpty() && dir.split("/").length > 3) continue;
            if (Set.of("package.json", "index.html", "pom.xml", "build.gradle", "build.gradle.kts", "requirements.txt", "pyproject.toml", "go.mod", "manage.py", "composer.json").contains(name))
                out.add(dir);
        }
        return new ArrayList<>(out);
    }

    /** vue-cli 的 public/index.html、src 里的 html 片段等不是可部署的站点 */
    private boolean insideNodeSource(String d, Set<String> nodeDirs) {
        for (String n : nodeDirs) {
            String prefix = n.isEmpty() ? "" : n + "/";
            if (!d.startsWith(prefix) || d.equals(n)) continue;
            String first = d.substring(prefix.length()).split("/")[0];
            if (!BUILD_OUT.contains(first)) return true;
        }
        return false;
    }

    private static boolean insideStatic(String d, List<Candidate> found) {
        return found.stream().anyMatch(c -> "static".equals(c.kind()) && !c.prebuilt() && (c.dir().isEmpty() || d.startsWith(c.dir() + "/")));
    }

    private boolean isBuildOutput(String d) {
        String last = d.contains("/") ? d.substring(d.lastIndexOf('/') + 1) : d;
        if (!BUILD_OUT.contains(last) && !d.matches(".*(^|/)dist/[^/]+(/browser)?$")) return false;
        String prefix = d + "/";
        return files.keySet().stream().anyMatch(p -> p.startsWith(prefix) && p.matches("(?i).*\\.(js|css)$"));
    }

    private int rank(Candidate c) {
        int depth = c.dir().isEmpty() ? 0 : c.dir().split("/").length;
        int score = c.prebuilt() ? 300 : "vite".equals(c.kind()) ? 0 : 100;
        String first = c.dir().isEmpty() ? "" : c.dir().split("/")[0].toLowerCase();
        if (!c.dir().isEmpty() && FRONT_DIR_HINTS.stream().noneMatch(first::contains)) score += 20;
        return score + depth * 10;
    }

    // ---------- 风险 ----------
    private void nodeWarnings(Node n, List<Warning> out) {
        String d = n.dir;
        JsonNode pkg = n.pkg;
        Set<String> deps = deps(pkg);
        String build = pkg.path("scripts").path("build").asText("");
        String cfg = firstText(d, "vite.config.ts", "vite.config.js", "vite.config.mjs", "vite.config.mts", "vue.config.js", "vue.config.ts",
                "webpack.config.js", ".umirc.ts", "config/config.ts", "config/index.ts", "craco.config.js", "rsbuild.config.ts");
        boolean proxy = cfg != null && PROXY.matcher(cfg).find();
        Scan scan = scanSources(d);
        List<String> envApi = envApis(d);
        if (proxy || scan.apiSample != null || !envApi.isEmpty()) {
            StringBuilder s = new StringBuilder("项目会请求后端接口");
            if (scan.apiSample != null) s.append("（例如 ").append(scan.apiSample).append("）");
            else if (!envApi.isEmpty()) s.append("（").append(envApi.get(0)).append("）");
            if (proxy) s.append("，开发时通过 proxy 代理转发");
            s.append("。织页只部署前端：页面能打开，但依赖接口的数据和操作不可用，需要接入数据源，或让接口地址指向已经上线的后端（并允许跨域访问）。");
            out.add(risk("api", "依赖后端接口", s.toString()));
        }
        String npmrc = read(d, ".npmrc");
        if (npmrc == null && !d.isEmpty()) npmrc = read("", ".npmrc");
        if (npmrc != null && Pattern.compile("(?m)^\\s*(@[^:]+:)?registry\\s*=").matcher(npmrc).find())
            out.add(risk("registry", "使用了私有 npm 源", "项目的 .npmrc 配置了自己的 npm 源。沙箱只能访问公共镜像，公司内部的私有包会安装失败。"));
        List<String> local = new ArrayList<>();
        for (String sec : List.of("dependencies", "devDependencies"))
            pkg.path(sec).fields().forEachRemaining(e -> { if (e.getValue().asText("").matches("^(file:|link:|workspace:|git\\+|git:|github:|https?:).*")) local.add(e.getKey()); });
        if (!local.isEmpty())
            out.add(risk("local-deps", "依赖本地或 Git 上的包", String.join("、", local.subList(0, Math.min(3, local.size()))) + (local.size() > 3 ? " 等" : "")
                    + " 不是从 npm 安装的（file: / workspace: / git 地址），导入后找不到这些包，构建会失败。"));
        if (pkg.has("workspaces") || has("", "pnpm-workspace.yaml") || has("", "lerna.json") || has(d, "pnpm-workspace.yaml"))
            out.add(risk("monorepo", "多包仓库（monorepo）", "项目由多个子包组成，子包之间的依赖在导入后可能无法解析。建议只导入需要部署的前端子项目。"));
        List<String> natives = NATIVE.keySet().stream().filter(deps::contains).toList();
        if (!natives.isEmpty())
            out.add(risk("native", "依赖需要原生编译", natives.stream().map(NATIVE::get).reduce((a, b) -> a + "；" + b).orElse("")
                    + "。平台安装依赖时不运行安装脚本，构建可能失败。"));
        Integer nodeMajor = nodeMajor(pkg.path("engines").path("node").asText(""), read(d, ".nvmrc"));
        if (nodeMajor != null && nodeMajor < 16)
            out.add(risk("node", "Node 版本较旧", "项目要求 Node " + nodeMajor + "，平台使用 Node 22，老版本的构建工具（如 webpack 4、node-sass）可能无法运行。"));
        if (has(d, "pnpm-lock.yaml") && !has(d, "package-lock.json"))
            out.add(info("pnpm", "使用 pnpm", "会用 pnpm 按锁文件安装依赖；平台没有 pnpm 时改用 npm 安装，依赖版本可能和本地不完全一致。"));
        else if (has(d, "yarn.lock") && !has(d, "package-lock.json"))
            out.add(info("yarn", "使用 yarn", "会改用 npm 按 package.json 安装依赖（不使用 yarn.lock），依赖版本可能和本地不完全一致。"));
        else if (!has(d, "package-lock.json"))
            out.add(info("no-lock", "没有锁文件", "没有 package-lock.json，会按 package.json 安装最新的兼容版本，可能和本地开发时不一致。"));
        if (build.matches(".*\\b(vue-tsc|tsc)\\b.*") && "Vite".equals(n.tool))
            out.add(info("typecheck", "构建前有类型检查", "build 脚本会先做类型检查。如果类型检查报错，织页会跳过类型检查直接构建，并在结果里提示。"));
        if (cfg != null) {
            Matcher m = VITE_BASE.matcher(cfg);
            Matcher pp = PUBLIC_PATH.matcher(cfg);
            if ("Vite".equals(n.tool) && m.find() && !m.group(1).equals("/"))
                out.add(info("base", "改为相对路径构建", "构建配置的访问路径是 " + m.group(1) + "，织页会改用相对路径构建，保证预览和发布地址都能加载资源。"));
            else if (!"Vite".equals(n.tool) && pp.find())
                out.add(risk("base", "资源使用绝对路径", "构建配置的 publicPath 是 " + pp.group(1) + "，工作台预览里可能加载不到脚本和样式（发布后的站点在根路径下不受影响）。建议改成 './'。"));
        }
        if (scan.historyRouter)
            out.add(info("history", "使用 history 路由", "发布后的站点可以正常访问各个页面；工作台预览挂在子路径下，首屏路由可能匹配不到（显示空白或 404 页）。"));
        if (files.size() > 2000 || deps.size() > 80)
            out.add(info("large", "项目较大", "共 " + files.size() + " 个文件、" + deps.size() + " 个依赖，安装和构建可能需要几分钟（每一步最长 5 分钟）。"));
        out.add(info("editable", "导入后的修改方式", "导入后可以用对话让 AI 修改源码；不支持可视化编辑，也不能新增依赖。"));
    }

    private void staticWarnings(Candidate c, List<Warning> out) {
        String html = read(c.dir(), "index.html");
        if (html != null && Pattern.compile("(?i)(src|href)\\s*=\\s*[\"']/(?!/)[^\"']+\\.(js|css)").matcher(html).find())
            out.add(risk("base", "资源使用绝对路径", "index.html 用 / 开头的路径引用脚本或样式，工作台预览里可能加载不到（发布后的站点在根路径下不受影响）。构建时把 base / publicPath 改成 './' 可以避免。"));
        Scan scan = scanSources(c.dir());
        if (scan.apiSample != null)
            out.add(risk("api", "依赖后端接口", "页面会请求后端接口（例如 " + scan.apiSample + "）。织页只部署静态文件，依赖接口的数据和操作不可用，需要接入数据源或指向已经上线的后端。"));
    }

    private record Scan(String apiSample, boolean historyRouter) {}

    private Scan scanSources(String d) {
        String prefix = d.isEmpty() ? "" : d + "/";
        long budget = SCAN_TOTAL_MAX;
        String api = null;
        boolean history = false;
        for (var e : files.entrySet()) {
            String p = e.getKey();
            if (!p.startsWith(prefix) || e.getValue() > SCAN_FILE_MAX || !SCAN_EXT.matcher(p).matches()) continue;
            String rel = p.substring(prefix.length());
            if (rel.matches("(?i)^(dist|build|out|public|node_modules|test|tests|__tests__|mock|mocks)/.*") || rel.matches("(?i).*\\.min\\.js$")) continue;
            budget -= e.getValue();
            if (budget < 0) break;
            String s = reader.apply(p);
            if (s == null) continue;
            if (api == null && API_CALL.matcher(s).find()) {
                Matcher m = API_PATH.matcher(s);
                if (m.find()) api = m.group(1).replaceAll("['\"`?]$", "") + "…";
            }
            if (!history && HISTORY_ROUTER.matcher(s).find()) history = true;
            if (api != null && history) break;
        }
        return new Scan(api, history);
    }

    private List<String> envApis(String d) {
        List<String> out = new ArrayList<>();
        for (String f : List.of(".env.production", ".env", ".env.prod")) {
            String s = read(d, f);
            if (s == null) continue;
            Matcher m = ENV_API.matcher(s);
            while (m.find() && out.size() < 2) {
                String v = m.group(2).replaceAll("^['\"]|['\"]$", "");
                if (v.startsWith("http") || v.startsWith("/")) out.add(f + " 里的 " + m.group(1) + "=" + v);
            }
        }
        return out;
    }

    static Integer nodeMajor(String engines, String nvmrc) {
        String s = nvmrc != null && !nvmrc.isBlank() ? nvmrc.trim() : engines == null ? "" : engines.trim();
        if (s.isEmpty() || s.startsWith(">") || s.contains("||")) return null;
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    // ---------- 工具 ----------
    private static Set<String> deps(JsonNode pkg) {
        Set<String> out = new HashSet<>();
        for (String f : List.of("dependencies", "devDependencies", "peerDependencies")) pkg.path(f).fieldNames().forEachRemaining(out::add);
        return out;
    }

    private boolean has(String dir, String name) { return files.containsKey(dir.isEmpty() ? name : dir + "/" + name); }

    private String read(String dir, String name) { return has(dir, name) ? reader.apply(dir.isEmpty() ? name : dir + "/" + name) : null; }

    private String firstText(String dir, String... names) {
        for (String n : names) { String s = read(dir, n); if (s != null) return s; }
        return null;
    }

    private String htmlLabel(String d) {
        String prefix = d.isEmpty() ? "" : d + "/";
        long n = files.keySet().stream().filter(p -> p.startsWith(prefix) && p.toLowerCase().matches(".*\\.html?$")).count();
        return n <= 1 ? "HTML" : "静态站点";
    }

    private static String labelFor(String dir, String framework) {
        return (dir.isEmpty() ? "根目录" : dir + "/") + " · " + framework;
    }

    private static Warning risk(String code, String title, String detail) { return new Warning("risk", code, title, detail); }

    private static Warning info(String code, String title, String detail) { return new Warning("info", code, title, detail); }
}
