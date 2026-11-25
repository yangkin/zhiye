package com.tcl.csot.zhiye.service.runtime;

import com.tcl.csot.zhiye.util.LogRedactor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 部署失败结构化根因分类器（Deploy Engine V2）：
 * - 对构建日志、运行崩溃日志和错误信息进行精准归因
 * - 输出面向工程师与 AI 编码自愈的诊断报告（分类、根因、修复动作、缺失变量/包线索）
 */
@Service
public class DeployDiagnosisService {

    public record Diagnosis(
            String category,
            String severity,
            String title,
            String summary,
            String rootCause,
            List<String> suggestedFixes,
            List<String> missingModules,
            List<String> envHints,
            boolean autoHealable,
            String excerpt
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", category);
            m.put("severity", severity);
            m.put("title", title);
            m.put("summary", summary);
            m.put("rootCause", rootCause);
            m.put("suggestedFixes", suggestedFixes);
            m.put("missingModules", missingModules);
            m.put("envHints", envHints);
            m.put("autoHealable", autoHealable);
            m.put("excerpt", excerpt);
            return m;
        }
    }

    private static final Pattern NODE_MOD_RE = Pattern.compile("Cannot find (?:module|package) ['\"]([^'\"]+)['\"]");
    private static final Pattern PY_MOD_RE = Pattern.compile("No module named ['\"]([^'\"]+)['\"]");
    private static final Pattern ENV_KEY_HINT_RE = Pattern.compile("\\b([A-Z][A-Z0-9_]{2,32}_(?:URL|KEY|TOKEN|SECRET|HOST|PORT|DSN|PASSWORD))\\b");

    public Diagnosis diagnose(String error, String buildLog, String runtimeLogs, String runtime, int port, String healthPath) {
        String combined = LogRedactor.redact(
                String.join("\n",
                        error == null ? "" : error,
                        buildLog == null ? "" : buildLog,
                        runtimeLogs == null ? "" : runtimeLogs)).trim();
        String lower = combined.toLowerCase(Locale.ROOT);
        String excerpt = tailLines(combined, 18);
        List<String> missingMods = extractModules(combined);
        List<String> envHints = extractEnvHints(combined);

        if (combined.isBlank()) {
            return new Diagnosis(
                    "HEALTHY", "info", "当前无部署异常",
                    "最近一次构建与运行状态正常，未检测到失败特征。",
                    "", List.of(), List.of(), List.of(), false, "");
        }

        // 1. OOM 内存溢出
        if (lower.contains("oomkilled") || lower.contains("javascript heap out of memory")
                || lower.contains("outofmemoryerror") || lower.contains("exit=137") || lower.contains("killed")) {
            return new Diagnosis(
                    "OOM_KILLED", "critical", "内存超限被系统终止（OOM / Exit 137）",
                    "构建或运行阶段内存占用超过容器配额上限。",
                    "进程触发内存保护（OOMKilled / Heap OOM），常见于构建打包或全量数据常驻内存。",
                    List.of(
                            "在「运行与存储配置」适当提高内存配额（上限 1024 MB）",
                            "Node/Java 启动参数增加内存上限限制（如 NODE_OPTIONS=--max-old-space-size=512 或 -Xmx512m）",
                            "避免启动阶段一次性全量加载大文件到内存"
                    ),
                    missingMods, envHints, true, excerpt);
        }

        // 2. 缺失依赖包
        if (!missingMods.isEmpty() || lower.contains("modulenotfounderror") || lower.contains("cannot find module")
                || lower.contains("could not resolve dependencies") || lower.contains("is not in goroot")) {
            return new Diagnosis(
                    "MISSING_DEPENDENCY", "high", "依赖包缺失或未声明",
                    "运行时或编译时找不到依赖：" + (missingMods.isEmpty() ? "请查看依赖清单" : String.join(", ", missingMods)),
                    "代码引用了未写入 package.json / requirements.txt / pom.xml / go.mod 的第三方依赖，或构建阶段未正确安装。",
                    List.of(
                            missingMods.isEmpty()
                                    ? "检查依赖声明文件（package.json / requirements.txt / go.mod）并补齐依赖"
                                    : "补齐缺失依赖：" + String.join(", ", missingMods),
                            "点击「AI 一键自愈修复」让编码智能体自动安装依赖并重新验证",
                            "若使用内网私有依赖，确认内网镜像源配置正确"
                    ),
                    missingMods, envHints, true, excerpt);
        }

        // 3. 权限不足（UID 10001 非 root 写入受限目录）
        if (lower.contains("eacces") || lower.contains("permission denied") || lower.contains("read-only file system")) {
            return new Diagnosis(
                    "PERMISSION_DENIED_NONROOT", "high", "非 Root 运行态目录权限受限",
                    "进程在非 root（UID 10001）安全上下文中尝试写入只读或受限路径。",
                    "生产容器禁止以 root 运行，所有可写文件（SQLite、上传文件、缓存）必须写入环境变量 DATA_DIR（默认 /data）或 /tmp。",
                    List.of(
                            "将数据文件与 SQLite 路径改为 process.env.DATA_DIR || '/data'",
                            "点击「AI 一键自愈修复」自动改造代码中的写路径"
                    ),
                    missingMods, envHints, true, excerpt);
        }

        // 4. 环境变量 / 数据库连接缺失
        if (lower.contains("database_url") || lower.contains("econnrefused 127.0.0.1:5432")
                || lower.contains("missing required env") || lower.contains("keyerror:")
                || (lower.contains("connection refused") && lower.contains("5432"))) {
            return new Diagnosis(
                    "ENV_MISSING", "high", "关键环境变量或托管数据库未就绪",
                    "服务启动依赖的环境变量或数据库连接串缺失。",
                    "应用尝试读取未配置的环境变量，或在未开启「托管 PostgreSQL Schema」时连接了本地 5432 端口。",
                    List.of(
                            "若应用使用 PostgreSQL，请在「运行与存储配置」开启「托管 PostgreSQL Schema」自动注入 DATABASE_URL",
                            envHints.isEmpty() ? "在「环境变量」标签补齐必需配置项" : "检查并在「环境变量」补齐：" + String.join(", ", envHints),
                            "或点击「AI 一键自愈修复」为本地存储提供优雅降级与初始化逻辑"
                    ),
                    missingMods, envHints, true, excerpt);
        }

        // 5. 编译 / 语法错误
        if (lower.contains("syntaxerror") || lower.contains("build failure") || lower.contains("error ts")
                || lower.contains("compilation error") || lower.contains("failed to compile") || lower.contains("构建命令执行失败")) {
            return new Diagnosis(
                    "BUILD_SYNTAX_ERROR", "high", "代码编译或构建命令失败",
                    "源码存在语法/类型错误，或构建脚本返回非零退出码。",
                    "在执行构建阶段（" + (runtime == null ? "service" : runtime) + "）时编译器报错终止。",
                    List.of(
                            "点击「AI 一键自愈修复」，自动携带完整构建报错定位并修复源码",
                            "核对「构建命令」是否与当前项目结构（package.json scripts / pom.xml）匹配"
                    ),
                    missingMods, envHints, true, excerpt);
        }

        // 6. 端口绑定 / 启动监听超时
        if (lower.contains("eaddrinuse") || lower.contains("address already in use")
                || lower.contains("监听端口超时") || lower.contains("启动就绪超时") || lower.contains("readiness probe failed")) {
            return new Diagnosis(
                    "PORT_BIND_ERROR", "high", "端口监听异常或健康检查超时",
                    "服务未能在限定时间内监听平台注入的 PORT（或健康检查路径 " + (healthPath == null ? "/" : healthPath) + " 未返回 2xx）。",
                    "常见原因：代码硬编码了固定端口而非读取 process.env.PORT / os.environ['PORT']，或绑定到了错误网卡/进程提前退出。",
                    List.of(
                            "确保服务监听 process.env.PORT（或 --server.port=${PORT}，当前默认 " + port + "）并绑定 0.0.0.0 / 127.0.0.1",
                            "确认健康检查路径 " + (healthPath == null ? "/" : healthPath) + " 无需登录鉴权即可返回 200",
                            "点击「AI 一键自愈修复」让编码智能体自动修复监听端口与健康检查端点"
                    ),
                    missingMods, envHints, true, excerpt);
        }

        // 7. 兜底分类
        return new Diagnosis(
                "UNKNOWN_DEPLOY_ERROR", "medium", "构建或启动异常",
                "部署流水线执行失败，建议结合日志摘要定位根因或交由 AI 自愈。",
                tailLines(combined, 4),
                List.of(
                        "切换到「运行与构建日志」查看完整上下文",
                        "点击「AI 一键自愈修复」自动分析日志并修复代码"
                ),
                missingMods, envHints, true, excerpt);
    }

    private static List<String> extractModules(String text) {
        Set<String> mods = new LinkedHashSet<>();
        Matcher m1 = NODE_MOD_RE.matcher(text);
        while (m1.find() && mods.size() < 6) mods.add(m1.group(1));
        Matcher m2 = PY_MOD_RE.matcher(text);
        while (m2.find() && mods.size() < 6) mods.add(m2.group(1));
        return new ArrayList<>(mods);
    }

    private static List<String> extractEnvHints(String text) {
        Set<String> hints = new LinkedHashSet<>();
        Matcher m = ENV_KEY_HINT_RE.matcher(text);
        while (m.find() && hints.size() < 6) hints.add(m.group(1));
        return new ArrayList<>(hints);
    }

    private static String tailLines(String text, int maxLines) {
        if (text == null || text.isBlank()) return "";
        String[] lines = text.replace("\r\n", "\n").split("\n");
        int start = Math.max(0, lines.length - maxLines);
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length));
    }
}
