package com.tcl.csot.zhiye.service.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcl.csot.zhiye.entity.AppRow;
import com.tcl.csot.zhiye.service.app.Catalog;

import java.util.List;

/** 交给 agent 的中文指令。 */
public final class Prompts {
    private Prompts() {}

    static String generate(AppRow app, List<String> assetPaths) {
        if (app.isSkill()) return generateSkill(app, assetPaths);
        if (app.isService()) return generateService(app, assetPaths);
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
        if (!assetPaths.isEmpty()) {
            sb.append("## 用户上传的素材（已放进项目）\n");
            for (String p : assetPaths) sb.append("- ").append(p).append("（代码中用相对路径 `./").append(p.replaceFirst("^public/", "")).append("` 引用）\n");
            sb.append('\n');
        }
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

    static String generateService(AppRow app, List<String> assetPaths) {
        Catalog.Style st = Catalog.STYLES.getOrDefault(app.style(), Catalog.STYLES.get("lavender"));
        StringBuilder sb = new StringBuilder();
        sb.append("请在当前全栈项目骨架（前端 `src/` React 19 + Vite + TypeScript + Tailwind，后端 `server/index.mjs` Express）中实现下面这个前后端一体应用，替换掉骨架占位代码。\n\n");
        sb.append("## 应用类型与指引\n").append(Catalog.FULLSTACK_HINT).append("\n\n");
        sb.append("## 用户需求\n").append(app.prompt()).append("\n\n");
        if (app.planDoc() != null && !app.planDoc().isBlank()) sb.append("## 创造计划（用户已确认）\n").append(app.planDoc()).append("\n\n");
        sb.append("## 设计风格\n").append(st.label()).append("：").append(st.desc())
                .append("。主色 ").append(st.accent()).append("，浅色背景 ").append(st.light()).append("。\n\n");
        if (!assetPaths.isEmpty()) {
            sb.append("## 用户上传的素材（已放进项目）\n");
            for (String p : assetPaths) sb.append("- ").append(p).append("（代码中用相对路径 `./").append(p.replaceFirst("^public/", "")).append("` 引用）\n");
            sb.append('\n');
        }
        sb.append("""
                ## 全栈交付要求
                - **同时实现前后端**：前端在 `src/`，后端在 `server/`（入口 `server/index.mjs`）；不要把业务数据只写死在前端内存或 localStorage 里，必须通过真实 `/api/...` 接口读写持久化数据
                - **接口与持久化规范**：前端统一请求相对路径 `/api/...`（禁止硬编码 `localhost`）；后端监听 `0.0.0.0:${PORT || 8080}`，保留 `GET /api/health` 返回 200 JSON，数据写入 `process.env.DATA_DIR || '.data'`（原子落盘 `tmp -> rename`）并在检测到 `DATABASE_URL` 时支持 PostgreSQL，静态托管 `dist/` 与 SPA 回退
                - **桌面宽屏与交互**：界面中文、面向桌面端全宽流式布局（`w-full`），表单/筛选交互真实联动后端接口并给出明确加载与错误提示；需要参考全栈最佳实践时可用 `read` 查看内置技能 `fullstack-app`
                - 若需求存在关键业务歧义，可调用 `request_confirm`（`kind: "choice"`）让用户快速拍板；普通实现细节自行合理决策
                - 简要维护 `docs/PROJECT_NOTES.md`（≤40 行，记录后端 API 清单、数据存储结构、前端核心模块与关键约定），供后续迭代直接复用
                - 收尾总结之前，务必调用 `verify_app` 自检（自动验证类型检查、前端构建、后端临时起服与 `/api/health` 探活及页面冒烟），全部通过后再总结
                """);
        sb.append(com.tcl.csot.zhiye.constants.Capabilities.GENERATE);
        sb.append(GENERATE_SUMMARY);
        return sb.toString();
    }

    /** 完成总结的骨架（对齐 meoo）：结论前置、按文件说明、能力清单、怎么验证；检查结果由系统根据校验填写 */
    static final String GENERATE_SUMMARY = """
            - 最后用 Markdown 按下面的结构收尾（不要输出 diff，不要用 emoji；没有内容的小节整段省略）：
              第一行：一句话结论，例如「设备点检记录已经写好并跑通了。」
              ### 已完成
              - `src/...` —— 这个文件做了什么（每个关键文件一行，最多 6 行）
              ### 能力清单
              - 做了什么 + 具体规则，逐条写（例如「编号不能重复，保存时校验」），最多 6 条
              ### 怎么验证
              1. 可以照着点的操作步骤，2～4 步
              ### 需要注意
              - 例如：数据只存在当前浏览器里，换电脑或清缓存就没了
              ### 当前模拟的内容
              - 例如：设备清单是示例数据，尚未接入真实系统
              ### 后续可完善
              - 1～3 条
            - 不要写「检查结果」小节，系统会根据校验结果补上
            - 最后单独一行输出：[[next]] 建议一 | 建议二 | 建议三
              （2～3 个用户最可能的下一句话，用用户的口吻，每个不超过 14 个字，例如「接上云端数据」「增加导出 Excel」；
              必须是这个应用接下来真正值得做的事，不要写「继续修改」这种空话）
            """;

    /** 修改后的总结：比生成轻，只说改了什么和怎么看 */
    static final String MODIFY_SUMMARY = """
            - 最后用 Markdown 按下面的结构收尾（不要输出 diff，不要用 emoji；没有内容的小节整段省略）：
              第一行：一句话结论，说清这次改了什么
              ### 改了什么
              - `src/...` —— 改动说明（每个文件一行）
              ### 怎么验证
              1. 操作步骤，1～3 步
              ### 需要注意
              - 有影响其他功能或数据的地方才写
            - 不要写「检查结果」小节，系统会根据校验结果补上
            - 最后单独一行输出：[[next]] 建议一 | 建议二（2～3 个用户最可能的下一句话，每个不超过 14 个字）
            """;

    static String generateSkill(AppRow app, List<String> assetPaths) {
        StringBuilder sb = new StringBuilder("请在当前目录编写一个 skill 包（只有文件，不是网页），替换掉模板里的占位内容。\n\n");
        sb.append("## 类型指引\n").append(Catalog.SKILL_HINT).append("\n\n");
        sb.append("## 用户需求\n").append(app.prompt()).append("\n\n");
        if (app.planDoc() != null && !app.planDoc().isBlank() && !app.planDoc().trim().equals(app.prompt().trim()))
            sb.append("## 补充说明\n").append(app.planDoc()).append("\n\n");
        if (!assetPaths.isEmpty()) {
            sb.append("## 用户上传的素材（已放进 references/assets/）\n");
            for (String p : assetPaths) sb.append("- ").append(p).append('\n');
            sb.append('\n');
        }
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
        return "上次的工作被中断了。请先查看当前项目的实际状态，然后继续完成下面的任务（已经完成的部分不用重做）。\n\n---\n" + original;
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
