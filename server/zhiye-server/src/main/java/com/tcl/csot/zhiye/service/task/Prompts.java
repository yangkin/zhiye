package com.tcl.csot.zhiye.service.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.service.app.Catalog;

import java.util.List;

/** 交给 agent 的中文指令。 */
public final class Prompts {
    private Prompts() {}

    private static void appendAssetSection(StringBuilder sb, List<String> assetPaths) {
        String section = formatAssetSection("## 用户上传的素材（已放进项目）", assetPaths, false);
        if (!section.isEmpty()) sb.append(section).append('\n');
    }

    /** 生成 / 修改共用的素材说明段：支持原始文件、自动解析的文档 Markdown（*-parsed.md）及 Word 解压原图（*-media/*）。 */
    public static String formatAssetSection(String heading, List<String> assetPaths, boolean skill) {
        if (assetPaths == null || assetPaths.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(heading).append('\n');
        boolean hasSpreadsheet = false;
        boolean hasParsedDoc = false;
        for (String p : assetPaths) {
            sb.append("- ").append(p);
            if (!skill) {
                String rel = "./" + p.replaceFirst("^public/", "");
                if (p.endsWith("-parsed.md")) {
                    sb.append("（平台已自动解析的完整文档 Markdown，请直接用 `read` 工具读取全部正文、表格与图片链接）");
                    hasParsedDoc = true;
                } else {
                    sb.append("（代码中用相对路径 `").append(rel).append("` 引用）");
                }
            } else if (p.endsWith("-parsed.md")) {
                sb.append("（平台已自动解析的完整文档 Markdown，请直接用 `read` 工具读取）");
                hasParsedDoc = true;
            }
            sb.append('\n');
            String lower = p.toLowerCase();
            if (lower.endsWith(".xlsx") || lower.endsWith(".csv")) hasSpreadsheet = true;
        }
        if (hasParsedDoc) {
            sb.append("- **Word/PDF/PPT 文档与图片专项要求**：平台已将二进制文档的正文、标题、表格和图片顺序完整解析到 `*-parsed.md`，并将 Word 内嵌图片全部解压到对应的 `*-media/` 目录。**请先用 `read` 读取 `*-parsed.md` 全文，再按文档内容和顺序生成页面，并直接用相对路径（如 `./assets/...-media/image1.png`）嵌入原图**；不要直接用 `read` 打开二进制 `.docx`/`.pdf`，绝不要声称无法读取文档。\n");
        }
        if (hasSpreadsheet) {
            sb.append("- **表格数据专项要求**：检测到用户上传了真实 `.xlsx`/`.csv` 数据文件，请先阅读内置技能 `excel-csv-to-dashboard`，全量解析 `public/assets/` 里的真实数据生成交互式看板（严禁用编造的假数据替换真实表格行），并在页面顶栏保留「导入新 Excel / CSV 刷新看板」入口。\n");
        }
        return sb.toString();
    }

    public static String generate(AppRow app, List<String> assetPaths) {
        if (app.isSkill()) return generateSkill(app, assetPaths);
        if (app.isService()) return generateService(app, assetPaths);
        if ("static".equals(app.projectKind())) return generateStatic(app, assetPaths);
        Catalog.Style st = Catalog.STYLES.getOrDefault(app.style(), Catalog.STYLES.get("lavender"));
        StringBuilder sb = new StringBuilder();
        boolean vue = app.isVue();
        sb.append("请在当前项目（").append(vue ? "Vite + Vue 3 + TypeScript + Tailwind" : "Vite + React + TypeScript + Tailwind")
                .append(" 模板）中实现下面这个应用，替换掉模板里的占位页面。\n\n");
        sb.append("## 应用类型与指引\n").append(Catalog.typeHint(app.type())).append("\n\n");
        sb.append("## 用户需求\n").append(app.prompt()).append("\n\n");
        if (app.planDoc() != null && !app.planDoc().isBlank()) sb.append("## 创造计划（用户已确认）\n").append(app.planDoc()).append("\n\n");
        sb.append("## 设计风格\n").append(st.label()).append("：").append(st.desc())
                .append("。主色 ").append(st.accent()).append("，浅色背景 ").append(st.light()).append("。\n\n");
        appendAssetSection(sb, assetPaths);
        sb.append(vue ? """
                ## 交付要求
                - 入口是 `src/App.vue`（`<script setup lang="ts">`），组件放在 `src/components/`；只修改 `src/`、`public/`、`index.html` 及需要的 `docs/`
                - 下拉选择统一使用 `src/components/CustomSelect.vue`（禁原生 `<select>`），面向桌面宽屏流式布局（`w-full`，右侧主区不套 `max-w-* mx-auto`）
                """ : """
                ## 交付要求
                - 入口是 `src/App.tsx`，组件放在 `src/components/`；只修改 `src/`、`public/`、`index.html` 及需要的 `docs/`
                - 下拉选择统一使用 `src/components/Select.tsx`（禁原生 `<select>`），面向桌面宽屏流式布局（`w-full`，右侧主区不套 `max-w-* mx-auto`）
                """);
        sb.append(com.tcl.csot.zhiye.constants.Capabilities.GENERATE);
        sb.append("- 若需求存在影响架构或业务规则的关键歧义（如审批流分支、口径取舍），可调用 `request_confirm`（`kind: \"choice\"`）给用户 2～3 个清晰选项确认后再继续；普通细节请直接合理决策，不要频繁打断\n");
        sb.append("- 写完核心代码后，简要维护 `docs/PROJECT_NOTES.md`（≤40 行，记录关键目录、数据表/状态约定、特殊业务规则），方便后续多轮迭代保持一致\n");
        sb.append("- 收尾总结之前，务必先调用 `verify_app` 工具做完整构建与冒烟自检（有错就自己修好再复检），并调用 `screenshot`（`device: \"wide\"`）确认桌面宽屏效果\n");
        sb.append(GENERATE_SUMMARY);
        return sb.toString();
    }

    static String generateStatic(AppRow app, List<String> assetPaths) {
        Catalog.Style st = Catalog.STYLES.getOrDefault(app.style(), Catalog.STYLES.get("lavender"));
        StringBuilder sb = new StringBuilder();
        sb.append("请在当前纯静态 HTML 网页模板（`index.html`、`styles.css`、`app.js`，零构建步骤，不要引入 Vite、React、package.json 或 Node.js 后端）中实现下面这个页面，替换掉模板里的占位内容。\n\n");
        sb.append("## 应用类型与指引\n").append(Catalog.typeHint(app.type())).append("\n\n");
        sb.append("## 用户需求\n").append(app.prompt()).append("\n\n");
        if (app.planDoc() != null && !app.planDoc().isBlank()) sb.append("## 创造计划（用户已确认）\n").append(app.planDoc()).append("\n\n");
        sb.append("## 设计风格\n").append(st.label()).append("：").append(st.desc())
                .append("。主色 ").append(st.accent()).append("，浅色背景 ").append(st.light()).append("。\n\n");
        appendAssetSection(sb, assetPaths);
        sb.append("""
                ## 交付要求
                - 直接编写根目录的 `index.html`、`styles.css`、`app.js`（或拆分子页 HTML / 样式表），静态资源放在 `assets/` 下用 `./assets/...` 相对路径引用
                - 不要创建 `package.json`、`server/` 或启动任何 Node.js 后端进程；面向桌面宽屏与移动端自适应排版，视觉精致克制
                - 收尾总结之前，务必先调用 `verify_app` 检查 HTML/CSS/JS 无报错，并调用 `screenshot`（`device: "wide"`）确认最终视觉效果
                """);
        sb.append(com.tcl.csot.zhiye.constants.Capabilities.GENERATE);
        sb.append(GENERATE_SUMMARY);
        return sb.toString();
    }

    static String generateService(AppRow app, List<String> assetPaths) {
        Catalog.Style st = Catalog.STYLES.getOrDefault(app.style(), Catalog.STYLES.get("lavender"));
        String userIntent = Catalog.extractUserOnlyText(app.prompt());
        boolean cleanWs = Catalog.prefersCleanWorkspace(userIntent);
        boolean vueSpring = !cleanWs && ((app.framework() != null && app.framework().toLowerCase().contains("spring"))
                || Catalog.looksLikeVueSpringBoot(userIntent));
        StringBuilder sb = new StringBuilder();
        if (cleanWs) {
            sb.append("当前是一个干净的自由全栈工作区（未预塞固定模板）。请严格按照用户指定的技术栈与架构自主规划目录并完整实现。\n\n");
            if (Catalog.looksLikeJavaBackend(app.prompt())) {
                sb.append("**技术栈强约束**：用户明确指定了 Java / Spring Boot 后端，请基于 Java + Maven（`pom.xml`）实现后端服务，严禁擅自改用 Node.js / Express / Python 替代。\n\n");
            }
        } else if (vueSpring) {
            sb.append("当前工作区已预置官方 **Vue 3 + Java Spring Boot** 离线加速骨架（`frontend/` 为 Vue 3 + Vite + TypeScript + Tailwind，`backend/` 为 Java 17 + Spring Boot 3.5 + Maven，本地 `.m2` 仓库已预热离线依赖）。请严格遵循该技术栈实现下面这个全栈应用：\n\n");
        } else {
            sb.append("当前工作区预置了前后端一体加速骨架（仅作为可选起点；如果用户指定了其他技术栈或架构，请自由调整目录、实现对应技术栈并通过 `zhiye.yaml` 声明工作流）。请实现下面这个应用：\n\n");
        }
        if (vueSpring) {
            sb.append("## 技术栈硬性要求（Vue 3 + Java Spring Boot）\n").append(Catalog.VUE_SPRINGBOOT_HINT).append("\n")
                    .append("- **严禁偷换后端技术栈**：必须在 `backend/` 下保留并使用 `pom.xml` 与 Java 源码（`backend/src/main/java/...`）实现全部后端 API，**严禁**删除 `backend/pom.xml` 或用 `backend/package.json` / Node.js / Express / Python 替代 Java 后端。\n")
                    .append("- **离线构建规范**：先阅读内置技能 `vue-springboot-enterprise`；前端在 `frontend/` 下使用已预装依赖构建，后端使用 `mvn -B -o -DskipTests package` 离线打包（仅使用 `pom.xml` 已声明并预热的 Spring Boot 3.5 Web / JDBC / Jackson / PostgreSQL 依赖，不要引入未预热的冷门三方 Maven 依赖）。\n")
                    .append("- **持久化与免 DB 启动兜底**：优先兼容 `DATA_DIR` JSON/文件持久化（保留 `DataSourceAutoConfiguration` 排除，除非用户显式要求 PostgreSQL 且配置 `JDBC_DATABASE_URL`），确保不依赖数据库 `CREATEROLE` 权限即可一键托管启动。\n\n");
        } else {
            sb.append("## 平台运行态契约（不绑定模板）\n").append(Catalog.FULLSTACK_HINT).append("\n\n");
        }
        sb.append("## 用户需求\n").append(app.prompt()).append("\n\n");
        if (app.planDoc() != null && !app.planDoc().isBlank()) sb.append("## 创造计划（用户已确认）\n").append(app.planDoc()).append("\n\n");
        sb.append("## 设计风格\n").append(st.label()).append("：").append(st.desc())
                .append("。主色 ").append(st.accent()).append("，浅色背景 ").append(st.light()).append("。\n\n");
        appendAssetSection(sb, assetPaths);
        sb.append("""
                ## 工程交付原则（自主决策架构与流程）
                - **先看工作区再决策**：先用 `find_files` 确认当前目录内容，按实际业务复杂度自主划分模块、API 契约与持久化方案（不要把业务数据只写死在前端内存里）。
                - **自定义工作流（可选 `zhiye.yaml`）**：若使用非默认技术栈或自定义目录/构建/测试命令，在根目录写一份 `zhiye.yaml` 声明 `diagnostics` / `build` / `test` / `verify` / `start`（含 `health`）与 `dev`，`verify_app`、`test_endpoint`、活体开发环境与生产部署都会按你的声明自动执行。
                - **离线构建意识**：沙箱内无外网，优先使用环境已安装的工具链与预热缓存（如本地 `.m2` 已有 Spring Boot 3.5 Web/JDBC/PG 依赖；Node 白名单依赖可用 `install_package`），避免引入离线不可用的冷门三方包。
                - **按需验证闭环**：改完核心代码后结合实际情况使用 `typecheck_diagnostics`、`test_endpoint`（实测核心接口）与 `verify_app`（编译、构建、起服探活）确保端到端可用后再交付。
                """);
        sb.append(com.tcl.csot.zhiye.constants.Capabilities.GENERATE);
        sb.append(GENERATE_SUMMARY);
        return sb.toString();
    }

    /** 完成总结指引：按实际交付内容简洁说明，不强制套用固定空模板 */
    static final String GENERATE_SUMMARY = """
            - 收尾用简洁清晰的中文 Markdown 说明结论、核心改动/架构与验证方式（不要贴大段 diff，不要写「检查结果」小节，系统会自动附上校验结果）。
            - 最后单独一行输出：[[next]] 建议一 | 建议二 | 建议三
              （2～3 个用户最可能接着做的具体下一步，每个不超过 14 个字）
            """;

    /** 修改后的总结：简洁说明改动与验证点 */
    static final String MODIFY_SUMMARY = """
            - 收尾用简洁中文说明这次改了什么、怎么验证（如有风险或影响点顺带提醒；不要写「检查结果」小节）。
            - 最后单独一行输出：[[next]] 建议一 | 建议二（2～3 个具体的下一步建议，每个不超过 14 个字）
            """;

    static String generateSkill(AppRow app, List<String> assetPaths) {
        StringBuilder sb = new StringBuilder("请在当前目录编写一个 skill 包（只有文件，不是网页），替换掉模板里的占位内容。\n\n");
        sb.append("## 类型指引\n").append(Catalog.SKILL_HINT).append("\n\n");
        sb.append("## 用户需求\n").append(app.prompt()).append("\n\n");
        if (app.planDoc() != null && !app.planDoc().isBlank() && !app.planDoc().trim().equals(app.prompt().trim()))
            sb.append("## 补充说明\n").append(app.planDoc()).append("\n\n");
        String skillAssets = formatAssetSection("## 用户上传的素材（已放进 references/assets/）", assetPaths, true);
        if (!skillAssets.isEmpty()) sb.append(skillAssets).append('\n');
        sb.append("""
                ## 工作方式
                先在第一段回复里写一个简短的待办清单（每项一行，例如「1. 编写 SKILL.md … 2. 编写 references/ … 3. 编写 scripts/ … 4. 自检」，按这个 skill 的实际文件写），然后按清单逐项完成。

                ## 要求
                - 只修改 SKILL.md、references/、scripts/；内容用中文
                - 严格遵循 SkillHub 规范：SKILL.md 放根目录且 ≤500 行；配置头 name 仅小写字母/数字/`-`、≤64 字符、不含版本号（建议 2~4 词段）；description ≤120 字；有特定运行依赖时须填具体 compatibility（不得只写「兼容飞书」等笼统描述）；正文写清功能、适用场景、使用步骤与依赖的权限或其它技能
                - 安全与清理：严禁写入凭证、密码、token、飞书用户 ID（ou_xxx）等敏感信息；脚本只用标准库，不联网、不装依赖，自测产生的缓存/日志/临时输出文件必须删除
                - 最后用两三句话总结这个 skill 包含哪些文件、怎么使用
                - 最后单独一行输出：[[next]] 建议一 | 建议二（2～3 个用户最可能的下一句话，每个不超过 14 个字）
                """);
        return sb.toString();
    }

    static String modify(String content, JsonNode ctx) { return modify(content, ctx, "template"); }

    static String modify(String content, JsonNode ctx, boolean skill) { return modify(content, ctx, skill ? "skill" : "template"); }

    static String modify(String content, JsonNode ctx, String projectKind) {
        String kind = projectKind == null ? "template" : projectKind;
        if ("skill".equals(kind)) return "请按用户的要求修改当前的 skill 包（只有文件，不是网页）。\n\n## 用户的要求\n" + content + "\n\n## 约定\n" + Catalog.SKILL_HINT
                + "\n\n## 要求\n- 严格遵循 SkillHub 规范：SKILL.md ≤500 行；配置头 name 仅小写字母/数字/`-`且不含版本号、description ≤120 字、有特定依赖时写具体 compatibility\n- 严禁包含凭证/密码/token/飞书用户 ID（ou_xxx），不得遗留缓存/调试日志/临时文件\n- 脚本只用标准库；改完调用 `verify_app` 自查后再总结\n";
        StringBuilder sb = new StringBuilder();
        sb.append("请按用户的要求修改当前项目。\n\n## 用户的要求\n").append(content).append("\n\n");
        if (ctx != null && !ctx.isNull()) {
            String loc = ctx.path("loc").asText();
            String[] parts = loc.split(":");
            sb.append("## 用户选中的元素\n");
            if (ctx.path("removedByDraft").asBoolean(false))
                sb.append("- 注意：这个元素已经被用户的手动修改删除了。如果要求仍然涉及它，请根据下面的文字和 HTML 判断该如何处理（例如在原来的位置重新加上），不要改动无关的元素。\n");
            if (parts.length >= 3)
                sb.append("- 源码位置：").append(parts[0]).append(" 第 ").append(parts[1]).append(" 行第 ").append(parts[2])
                        .append(" 列（列号从 0 开始，指向开始标签的 `<`）\n");
            else sb.append("- 源码位置：").append(loc).append('\n');
            sb.append("- 标签：").append(ctx.path("tag").asText().toLowerCase()).append('\n');
            sb.append("- 当前文字：").append(ctx.path("text").asText()).append('\n');
            String html = ctx.path("outerHTML").asText("");
            if (!html.isBlank()) sb.append("- 元素 HTML（截断）：\n```html\n").append(html.length() > 2000 ? html.substring(0, 2000) : html).append("\n```\n");
            JsonNode styles = ctx.path("styles");
            if (styles.isObject() && !styles.isEmpty()) {
                sb.append("- 当前计算样式：");
                styles.fields().forEachRemaining(e -> sb.append(e.getKey()).append("=").append(e.getValue().asText()).append("；"));
                sb.append('\n');
            }
            sb.append("- 行号可能因手动修改有偏移，请以元素内容为准\n");
            sb.append("\n只修改这个元素，除非要求明确涉及其他部分。\n\n");
        }
        String verifyHint = switch (kind) {
            case "service" -> "- 如涉及排查运行异常、环境变量或表结构，先调用 `service_runtime_inspect` 查看真实运行日志与配置；改完后调用 `verify_app` 确认前后端构建、后端起服与 `/api/health` 通过";
            case "vue" -> "- 改完后调用 `verify_app`（执行 vue-tsc、构建与冒烟自检）并用 `screenshot` 确认效果";
            case "script" -> "- 改完后调用 `verify_app` 并用小样例数据实跑脚本确认无误";
            case "static" -> "- 改完后调用 `verify_app` 并用 `screenshot` 确认无控制台报错与排版异常";
            default -> "- 改完后调用 `verify_app`（执行类型检查、构建与冒烟自检），涉及界面变动时用 `screenshot` 复核";
        };
        sb.append("## 要求\n")
                .append("- 优先使用 `find_files` / `grep_files` 精准定位目标文件，保持无关功能与交互不变；不要修改依赖，不要启动长期进程\n")
                .append("- 若改动影响了目录结构、接口契约、数据表或关键业务规则，顺手同步更新 `docs/PROJECT_NOTES.md`\n")
                .append(verifyHint).append('\n');
        sb.append(com.tcl.csot.zhiye.constants.Capabilities.MODIFY);
        sb.append(MODIFY_SUMMARY);
        return sb.toString();
    }

    /** 到了步数检查点、用户选择继续：同一个 agent 会话接着做 */
    static String keepGoing(String extra) {
        String e = extra == null ? "" : extra.trim();
        boolean onlyGo = e.isEmpty() || e.matches("^(继续|继续吧|接着做|继续做|好的?|可以|go( on)?|continue)[。.!！]*$");
        return "用户确认继续。请接着刚才的进度把任务做完：已经完成的部分不要重做，先完成待办里还没做完的项。"
                + (onlyGo ? "" : "\n\n用户同时补充：" + e);
    }

    static String resume(String original) {
        return resume(original, null);
    }

    static String resume(String original, String extraNote) {
        String extra = extraNote == null ? "" : extraNote.trim();
        String extraSection = extra.isEmpty() ? "" : "\n\n用户恢复任务时同时补充了要求：" + extra;
        return "上次的工作被中断了。请先查看当前项目的实际状态，然后继续完成下面的任务（已经完成的部分不用重做）。" + extraSection + "\n\n---\n" + original;
    }

    /** 上一轮被用户停止，这一句接着同一个工作区做：先补完上一轮，再做新要求 */
    static String continuePrevious(String previous, String instruction) {
        String prev = previous == null ? "" : previous.trim();
        if (prev.length() > 600) prev = prev.substring(0, 600) + "…";
        return "上一轮的任务被用户中途停止了，改动只做了一部分。上一轮的要求是：\n> " + prev.replace("\n", "\n> ")
                + "\n\n请先查看当前项目的实际状态：如果上一轮的要求和这次的新要求不冲突，开头用一句话说明「上一轮的 … 还没落地，我先把它补完」，"
                + "补完后再处理新要求；如果新要求明显是要换方向（例如「不要了」「改成…」），以新要求为准，不用补完上一轮。\n\n---\n" + instruction;
    }

    public static String fix(JsonNode checks, int round) { return fix(checks, round, "template"); }

    public static String fix(JsonNode checks, int round, String projectKind) {
        String checkCmd = switch (projectKind == null ? "template" : projectKind) {
            case "vue" -> "修复后调用 `verify_app`（vue-tsc + 构建 + 冒烟）自查";
            case "script" -> "修复后调用 `verify_app` 并用样例数据重新运行脚本自查";
            case "static" -> "修复后调用 `verify_app` 检查 HTML / CSS / 脚本报错";
            case "skill" -> "修复后调用 `verify_app` 对照 SKILL.md 规范与引用路径自查";
            case "vite" -> "修复后调用 `verify_app` 确认 `npm run build` 与冒烟通过";
            case "service" -> "修复后调用 `verify_app` 确认类型检查、前端构建、后端启动与 `/api/health` 均通过";
            default -> "修复后调用 `verify_app` 确认类型检查、构建与冒烟通过";
        };
        StringBuilder sb = new StringBuilder("平台校验没有通过（第 " + round + " 轮修复），请修复下面的问题，" + checkCmd + "：\n\n");
        for (JsonNode c : checks) {
            if (c.path("ok").asBoolean(true)) continue;
            sb.append("### ").append(c.path("name").asText()).append("\n```\n").append(c.path("detail").asText("")).append("\n```\n");
        }
        sb.append("\n最后用一两句话说明你修复了什么。");
        return sb.toString();
    }
}
