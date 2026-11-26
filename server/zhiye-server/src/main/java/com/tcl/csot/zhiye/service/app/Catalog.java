package com.tcl.csot.zhiye.service.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 应用类型、模式、设计风格等常量。 */
public final class Catalog {
    private Catalog() {}

    public static final List<String> TYPE_LIST = List.of("日常开发", "开发网站", "全栈应用", "开发skill", "技术文档");
    public static final Set<String> TYPES = Set.copyOf(TYPE_LIST);
    public static final String DEFAULT_TYPE = "开发网站";
    public static final String SKILL_TYPE = "开发skill";
    public static final String FULLSTACK_TYPE = "全栈应用";
    public static final String FULLSTACK_HINT = "全栈应用：前后端一体全栈单体项目（React + Vite + TypeScript 前端在 src/，Node.js Express 后端在 server/index.mjs）。"
            + "后端监听 0.0.0.0:${PORT:-8080}，必须提供 GET /api/health，持久化数据写入 DATA_DIR（默认 .data/）或托管 PG（DATABASE_URL），"
            + "构建产物 dist/ 由 Express 同源托管；前端请求一律使用相对路径 /api/...，禁止硬编码 localhost。";
    public static final String SKILL_HINT = "开发skill：产出一个符合 SkillHub 规范的 skill 包（不是网页）。仓库根目录就是 skill 目录：`SKILL.md` 放根目录且总行数不超过 500 行"
            + "（YAML 配置头：`name` 仅小写英文字母、数字和连字符 `-`，≤64 字符，不含版本号，建议 2~4 个词段如 `t11-stk-alarm-query`；"
            + "`description` 说明做什么与何时使用，严格不超过 120 字；有特定运行环境、工具或接口依赖时须填写 `compatibility` 并写明具体依赖，不得只写「兼容飞书」等笼统描述），"
            + "正文写清功能、适用场景、使用步骤及依赖的权限或其它技能；规范、模板、示例放 `references/`；辅助脚本放 `scripts/`（Python 或 Node.js，只用标准库，带用法注释）；"
            + "SKILL.md 里用相对路径引用这些文件。严禁包含凭证、账号密码、token、飞书用户 ID（ou_xxx）等敏感信息，严禁残留缓存、调试日志、测试输出等临时文件，不要创建网页、src/ 或 package.json。";

    public static String projectKindFor(String type) {
        if (SKILL_TYPE.equals(type)) return "skill";
        if (FULLSTACK_TYPE.equals(type)) return "service";
        return "template";
    }

    /** 需求像「一个命令行脚本 / 数据处理程序」时，产出脚本项目：没有页面预览，只交付代码 */
    private static final java.util.regex.Pattern SCRIPT_HINT = java.util.regex.Pattern.compile(
            "(命令行|脚本|批处理|shell|bash|python|py 程序|python3|excel|excel数据|表格清洗|数据清洗|csv|爬虫|定时任务脚本|cli)",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern WEB_HINT = java.util.regex.Pattern.compile(
            "(网页|页面|网站|官网|看板|仪表盘|界面|按钮|表单|H5|前端)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 有页面需求时优先做页面；只有明显是脚本类需求才走脚本项目 */
    public static boolean looksLikeScript(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        return SCRIPT_HINT.matcher(prompt).find() && !WEB_HINT.matcher(prompt).find();
    }

    private static final java.util.regex.Pattern SPRING_BOOT_HINT = java.util.regex.Pattern.compile(
            "(spring\\s*boot|springboot|java\\s*[+＋&与和]\\s*vue|vue\\s*[+＋&与和]\\s*java|java后端|spring\\s*mvc)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 用户明确要求 Vue + Spring Boot / Java 全栈时，走官方预热的 vue-springboot-template 骨架。 */
    public static boolean looksLikeVueSpringBoot(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        return SPRING_BOOT_HINT.matcher(prompt).find();
    }

    public static final String VUE_SPRINGBOOT_HINT = "全栈应用（Vue 3 + Spring Boot）：前端在 frontend/（Vue 3 + Vite + TypeScript + Tailwind），"
            + "后端在 backend/（Spring Boot 3.5 + Java 17 + Maven，入口 Application.java）。"
            + "后端通过 server.port=${PORT:8080} 监听，保留 GET /api/health，数据优先支持托管 PG（JDBC_DATABASE_URL）并兼容 DATA_DIR 本地持久化，"
            + "企业飞书/多维表格/员工目录直接注入 ConnectorClient 免密调用；前端请求统一用相对路径 /api/...。";
    public static final String TYPE_ERROR = "应用类型只能是：日常开发、开发网站、全栈应用、开发skill、技术文档";
    public static final List<String> MODES = List.of("快速", "适中", "深度");

    /**
     * 模型选择：任务实际只走平台配置的一个编码模型（zhiye.runner.model-id），这里只列这一个，
     * 不再摆一个选了也不生效的「Kimi K2」。旧应用里存的 kimi-for-coding 仍然合法，按 Auto 处理。
     */
    public record Model(String id, String label, String desc, int cost) {}
    public static final List<Model> MODELS = List.of(new Model("auto", "Auto", "平台统一配置的编码模型", 2));
    private static final Set<String> LEGACY_MODELS = Set.of("kimi-for-coding");
    /** 五星AI岛的模型：「供应商ID:模型ID」，是否真的可用由五星AI岛下发路由时判断 */
    private static final java.util.regex.Pattern FIVESTAR_MODEL = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,40}:[A-Za-z0-9._/@-]{1,80}");
    public static boolean validModel(String id) {
        return id != null && (MODELS.stream().anyMatch(m -> m.id().equals(id)) || LEGACY_MODELS.contains(id) || FIVESTAR_MODEL.matcher(id).matches());
    }

    public record Style(String id, String label, String accent, String light, String desc) {}

    public static final Map<String, Style> STYLES = new LinkedHashMap<>();
    static {
        for (Style s : List.of(
                new Style("lavender", "柔雾紫", "#7657d9", "#f1edfc", "轻盈、有序，留一点灵感空间"),
                new Style("mint", "薄荷清爽", "#286458", "#e9f3ee", "自然的绿色，平静而专注"),
                new Style("ink", "墨白极简", "#252833", "#eeeef1", "留白与秩序，回归内容本身"),
                new Style("sand", "暖纸阅读", "#9a6b41", "#f4eee5", "温润纸感，有呼吸的阅读体验"),
                new Style("rose", "玫瑰来信", "#ad6177", "#f9edf0", "柔软细腻，保留生活的温度"),
                new Style("cyber", "赛博灵感", "#785bff", "#ece8ff", "明亮碰撞，大胆表达自我")))
            STYLES.put(s.id(), s);
    }

    public static String thinking(String mode) {
        return switch (mode == null ? "" : mode) {
            case "快速" -> "low";
            case "深度" -> "high";
            default -> "medium";
        };
    }

    /** 按应用类型给 agent 的生成指引（产物都是同一种 Vite + React + TS + Tailwind 静态 Web 项目） */
    public static String typeHint(String type) {
        return switch (type == null ? "" : type) {
            case "日常开发" -> "日常开发：实用小工具或效率页面（计算、转换、整理、看板等），重功能与交互，界面简洁。";
            case FULLSTACK_TYPE -> FULLSTACK_HINT;
            case SKILL_TYPE -> SKILL_HINT;
            case "技术文档" -> "技术文档：技术文档站点：左侧目录、正文、代码块高亮、页内锚点和搜索框；文档内容用 Markdown 放在 `docs/` 目录，由页面读取渲染"
                    + "（可用 `import.meta.glob('../docs/*.md', { query: '?raw', import: 'default', eager: true })`）。";
            default -> "开发网站：网站或落地页，重视觉层次、内容结构和响应式排版。";
        };
    }
}
