package com.tcl.csot.zhiye.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Files;
import java.nio.file.Path;

/** 织页配置（前缀 zhiye.*）。 */
@ConfigurationProperties("zhiye")
public record ZhiyeProperties(
        /** zhiye 仓库根目录；为空时自动探测（当前目录或其上级中含 packages/app-template 的目录）。 */
        String root,
        /** 运行时目录（仓库、工作区、对象存储、日志）；为空时为 {root}/runtime。 */
        String runtimeDir,
        @DefaultValue("http://127.0.0.1:8080") String publicBaseUrl,
        @DefaultValue Site site,
        /** 前端构建产物目录；为空时为 {root}/web/dist。 */
        String webDist,
        @DefaultValue Task task,
        @DefaultValue Workspace workspace,
        @DefaultValue Sandbox sandbox,
        @DefaultValue Runner runner,
        @DefaultValue Llm llm,
        @DefaultValue Storage storage,
        @DefaultValue Importer importer,
        @DefaultValue Publish publish,
        @DefaultValue DatabaseQuota databaseQuota,
        @DefaultValue Dev dev) {

    public record Site(@DefaultValue("http://127.0.0.1:8083") String previewBase,
                       @DefaultValue("http") String siteScheme,
                       @DefaultValue("localhost:8083") String siteHostSuffix,
                       String templatePreviewBase,
                       String buildPreviewBase) {}

    public record Task(@DefaultValue("4") int concurrency,
                       /** 优雅停机：停止领新任务后，等在跑任务完成的最长时间（秒）；超时仍退出并标记 worker_lost */
                       @DefaultValue("600") int shutdownWaitSeconds,
                       /** 一段运行的时限（只为防止执行环境卡死）；每次「继续」后重新计时 */
                       @DefaultValue("60") int maxDurationMinutes,
                       /** 步数检查点：每做这么多步暂停一下，问用户要不要继续（不是上限） */
                       @DefaultValue("60") int maxAgentTurns,
                       /** 等用户回应「继续」的时间，超时先暂停任务（可重试接着做） */
                       @DefaultValue("30") int continueWaitMinutes,
                       /** 沙箱（k8s Pod / OpenSandbox）的最长存活时间：一个任务可以多次「继续」，这里只兜底防止资源泄漏 */
                       @DefaultValue("8") int sandboxMaxHours,
                       @DefaultValue("3") int verifyFixRounds,
                       @DefaultValue("true") boolean qaGateEnabled,
                       @DefaultValue("2") int qaMaxRounds,
                       @DefaultValue("12") int qaMaxTurns,
                       @DefaultValue("30") int leaseSeconds,
                       // 不设任务级 token 上限：单次任务由时长和步数上限兜底，额度由五星AI岛按人控制
                       @DefaultValue("500") long pollMillis) {}

    public record Workspace(@DefaultValue("50") int dailyTaskLimit,
                            @DefaultValue("1") int maxConcurrentTasks,
                            /** 每人最多应用数（已删除的不算） */
                            @DefaultValue("10") int maxApps,
                            @DefaultValue("zhiye_ws") String cookieName,
                            @DefaultValue("false") boolean secureCookie) {}

    public record Sandbox(@DefaultValue("local") String provider,
                          @DefaultValue("true") boolean seatbelt,
                          /** 相对 root 的 Seatbelt 配置文件 */
                          @DefaultValue("deploy/sandbox/runner.sb") String seatbeltProfile,
                          String nodeBinary,
                          @DefaultValue("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome") String chromePath) {}

    public record Runner(/** runner 入口脚本，相对 root 或绝对路径 */
                         @DefaultValue("runner/src/main.mjs") String script,
                         @DefaultValue("kimi-coding") String modelProvider,
                         @DefaultValue("kimi-for-coding") String modelId,
                         @DefaultValue("600") int commandTimeoutSeconds) {}

    public record Llm(@DefaultValue("https://api.kimi.com/coding/v1") String upstreamBaseUrl,
                      @DefaultValue("KIMI_API_KEY") String apiKeyEnv,
                      @DefaultValue("kimi-for-coding") String model,
                      @DefaultValue("kimi-coding") String provider,
                      @DefaultValue("3") int maxRetries,
                      @DefaultValue("1000") long retryBackoffMillis,
                      /** runner 访问网关的地址；为空时为 http://127.0.0.1:{实际端口}/llm/v1 */
                      String gatewayUrl) {}

    public record Storage(@DefaultValue("local") String type,
                          @DefaultValue S3 s3) {}

    public record S3(String endpoint, @DefaultValue("us-east-1") String region, @DefaultValue("zhiye") String bucket,
                     String accessKey, String secretKey, @DefaultValue("true") boolean pathStyle) {}

    public record Importer(@DefaultValue("52428800") long maxUploadBytes,
                           @DefaultValue("5000") int maxFiles,
                           @DefaultValue("209715200") long maxUnpackedBytes,
                           @DefaultValue("20971520") long maxFileBytes,
                           @DefaultValue("10485760") long assetMaxBytes) {}

    public record Publish(@DefaultValue("true") boolean reviewRequired) {}

    /** 三层数据库防刷与容量配额配置（共享云服务硬拦截 + 全栈托管角色防呆/自动只读熔断 + 管理员治理） */
    public record DatabaseQuota(
            /** 共享云服务单行 JSON 体积上限（字节，默认 256 KB） */
            @DefaultValue("262144") int cloudMaxRowBytes,
            /** 共享云服务单个字段值体积上限（字节，默认 128 KB） */
            @DefaultValue("131072") int cloudMaxFieldBytes,
            /** 共享云服务单张数据表最大行数（默认 50,000 行） */
            @DefaultValue("50000") long cloudMaxRowsPerTable,
            /** 共享云服务单应用 Schema 默认磁盘占用上限（MB，默认 200 MB） */
            @DefaultValue("200") int cloudDefaultQuotaMb,
            /** 共享云服务单应用写入速率上限：每秒最大写次数（默认 30） */
            @DefaultValue("30") int cloudWritesPerSecond,
            /** 共享云服务单应用写入速率上限：每分钟最大写次数（默认 600） */
            @DefaultValue("600") int cloudWritesPerMinute,
            /** 单体全栈托管 PostgreSQL 单应用 Schema 默认磁盘占用上限（MB，默认 500 MB） */
            @DefaultValue("500") int serviceDefaultQuotaMb,
            /** 单体全栈托管数据库独立角色最大并发连接数（默认 10） */
            @DefaultValue("10") int serviceRoleConnectionLimit,
            /** 单体全栈托管数据库独立角色语句超时（毫秒，默认 15 秒） */
            @DefaultValue("15000") int serviceStatementTimeoutMs,
            /** 单体全栈托管数据库独立角色空闲事务超时（毫秒，默认 30 秒） */
            @DefaultValue("30000") int serviceIdleTxTimeoutMs,
            /** 单体全栈托管数据库独立角色临时文件上限（MB，默认 64 MB；需 PG superuser，非 superuser 自动跳过） */
            @DefaultValue("64") int serviceTempFileLimitMb,
            /** 容量预警水位线百分比（默认 80%） */
            @DefaultValue("80") int warnThresholdPercent,
            /** 熔断后自动恢复水位线百分比（默认 90%，避免边界抖动） */
            @DefaultValue("90") int recoverThresholdPercent,
            /** 共享云服务实时写入检查时 Schema 磁盘体积缓存秒数（默认 20 秒） */
            @DefaultValue("20") int sizeCacheSeconds) {}

    public record Dev(@DefaultValue("false") boolean faultsEnabled) {}

    // ---------- 派生路径 ----------
    public Path rootPath() {
        if (root != null && !root.isBlank()) return Path.of(root).toAbsolutePath().normalize();
        Path cur = Path.of("").toAbsolutePath();
        for (Path p = cur; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("packages/app-template"))) return p;
        }
        return cur.getParent() != null ? cur.getParent() : cur;
    }

    public Path runtimePath() {
        if (runtimeDir != null && !runtimeDir.isBlank()) return Path.of(runtimeDir).toAbsolutePath().normalize();
        return rootPath().resolve("runtime");
    }

    public Path webDistPath() {
        if (webDist != null && !webDist.isBlank()) return Path.of(webDist).toAbsolutePath().normalize();
        return rootPath().resolve("web/dist");
    }

    public String nodeBinary() {
        String n = sandbox.nodeBinary();
        if (n != null && !n.isBlank()) return n;
        String env = System.getenv("ZHIYE_NODE_BINARY");
        if (env != null && !env.isBlank()) return env;
        if (java.io.File.pathSeparatorChar == ';') {
            Path winNode = Path.of(System.getProperty("user.home"), ".local", "nodejs", "node-v22.20.0-win-x64", "node.exe");
            return Files.isRegularFile(winNode) ? winNode.toString() : "node";
        }
        Path nvm = Path.of(System.getProperty("user.home"), ".nvm", "versions", "node", "v24.14.1", "bin", "node");
        return Files.isRegularFile(nvm) ? nvm.toString() : "node";
    }

    public Path resolveFromRoot(String p) {
        Path path = Path.of(p);
        return path.isAbsolute() ? path : rootPath().resolve(p).normalize();
    }
}
