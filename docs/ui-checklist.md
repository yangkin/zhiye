# 交互检查清单（对照 meoo，逐条状态）

状态：✅ 已实现并有端到端用例；🟡 已实现但只有手工验证；⏳ 未做（写明原因）

## 生成过程

| # | 需求 | 状态 | 落地位置 |
| --- | --- | --- | --- |
| 1 | 生成中输入框保留，发出去的内容作为「补充要求」加入当前任务 | ✅ | `ChatPanel.send` → `POST /api/tasks/{id}/steer`；`ui-timeline` |
| 2 | 待办列表贴在输入框上方，默认展开，显示当前项和 n/m | ✅ | `workbench/TodoPanel.tsx`，数据来自 runner 的 `todo` 事件（Pi 自定义工具 `todo_write`）；`ui-timeline` |
| 3 | 过程可以点开看详情（文件内容 / diff / 命令输出） | ✅ | `workbench/Timeline.tsx` + `/api/tasks/{id}/events/{seq}/detail`；`ui-timeline` |
| 4 | 思考过程可展开收起；默认收起；任务完成后不显示 | ✅ | `Timeline.ThinkingRow`；`ui-timeline` |
| 5 | 完成后保留一行文件改动汇总，可展开回看 | ✅ | `Timeline` finished 模式；`ui-timeline` |
| 6 | 生成中右侧显示小狗追骨头动画 + 小贴士轮播（不重复阶段文案） | ✅ | `workbench/CreatingStage.tsx` |

## 版本

| # | 需求 | 状态 | 落地位置 |
| --- | --- | --- | --- |
| 7 | 回复上「回到此版本 / 查看改动 / 复制 / 👍👎」 | ✅ | `ChatPanel.AssistantActions` |
| 8 | 「本次修改」视图：文件列表 A/M/D + 增删行数，点开看 diff | ✅ | `CodePane` 改动视图 + `/versions/{vid}/changes[/file]`；`ui-timeline` |
| 9 | 重试成功后不再残留「生成失败」提示 | ✅ | `TaskService.afterTerminal` → `MessageRepository.deleteFailures`；`api-tasks` |

## 对话

| # | 需求 | 状态 | 落地位置 |
| --- | --- | --- | --- |
| 10 | 创造页提问直接回答，不创建应用 | ✅ | `POST /api/chat/ask`；`ui-ask` |
| 11 | 创造页可以多轮聊，聊清楚点「开始做」才生成 | ✅ | `CreatePage` 对话面板 + localStorage；`ui-ask` |
| 12 | 工作台里也能聊天：回答落库，不建任务、不改代码 | ✅ | `/api/chat/ask` + `appId`；`ui-timeline` 第 8 步 |
| 13 | 模型自主判断「什么时候该动手」 | ✅ | 创造页：只有「第一句话 + 明确开发动词」才直接开工，其余都进对话；模型判断该动手时在回答首行输出 `[[build]]`，前端识别后自动开始做（也可以随时点「开始做」）。工作台里这个标记只用于隐藏，不会自动改代码 |

## 布局与控件

| # | 需求 | 状态 | 落地位置 |
| --- | --- | --- | --- |
| 14 | 右上角「收起工作区」：收起预览、聊天居中留白 | ✅ | `Workbench.previewCollapsed`；`ui-layout` |
| 15 | 预览自己的「全屏」：隐藏整条顶栏 | ✅ | `.preview-maximized` + `.studio-header.is-fullscreen`；`ui-layout` |
| 16 | 两个全屏互不冲突 | ✅ | 全屏时「收起工作区」禁用；进全屏会先展开预览；`ui-layout` |
| 17 | 聊天全屏时隐藏代码/预览/文件/云服务标签栏和版本号 | ✅ | `.studio-tabs.is-hidden`、`.studio-version.is-hidden`；`ui-layout` |
| 18 | 工作台保留左侧导航栏（含聊天全屏） | ✅ | `AppRail` 复用；`ui-layout` |
| 19 | 顶栏不要硬分隔线，改模糊过渡 | ✅ | `.studio-header` 半透明 + `backdrop-filter` |
| 20 | 只保留两个全屏相关按钮（头部 + 预览工具栏） | ✅ | 删掉「收起预览」「收起对话面板」；`ui-layout` |
| 21 | 完成卡片「打开预览」：已在预览时禁用，聊天全屏时可点开 | ✅ | `ChatPanel.CompletionCard` |
| 22 | 聊天输入框上方的小字去掉 | ✅ | `ChatPanel` |

## 元素编辑（手动微调）

| # | 需求 | 状态 | 落地位置 |
| --- | --- | --- | --- |
| 23 | 预览全屏时点「手动微调」弹出/浮出「编辑元素」面板 | ✅ | `.studio-body.is-maximized .ze-edit-panel` 浮层；`ui-layout` |
| 24 | 面板较重（文字/字体/字重/字号/颜色/间距/布局，列表项按实例写回，撤销重做，底部应用/重置） | ✅ | `editor/ManualPanel.tsx`；`ui-flows` 手动微调用例 + `ui-layout` 全屏用例 |

## 云服务

| # | 需求 | 状态 | 落地位置 |
| --- | --- | --- | --- |
| 25 | 只做数据库、文件存储、统一登录（不做云函数） | ✅ | `docs/cloud-services.md`、技术方案 §17.7、工作台云服务页 |
| 26 | 真正接上数据库与文件存储 | ✅ | 控制面 `/api/apps/{id}/cloud`（启用/关闭/令牌）、数据面 `/cloud/{appId}/db|storage`、`cloud/schema.sql` 建表、预览页注入 `window.zhiye.cloud` 与 `/cloud/sdk.js`、工作台云服务页显示真实状态；统一登录仍依赖 SSO 开关 |

## 文件

| # | 需求 | 状态 | 落地位置 |
| --- | --- | --- | --- |
| 27 | 「文件」是上传的素材和 AI 生成的文件，不是源码 | ✅ | `Panes.FilesPane`（上传素材 + 文档/脚本，源码在「代码」） |
| 28 | 纯脚本类产物（例如只写一个 python 程序）没有预览 | ✅ | 新增 `projectKind=script`：需求像命令行/数据处理程序时产出脚本项目（main.py + README + scripts/），verify 只查脚本与 README，不构建不冒烟；预览页显示「暂无预览」，不能发布、只能导出源码 |

## 其它

| # | 需求 | 状态 | 落地位置 |
| --- | --- | --- | --- |
| 29 | 小织可以拖动，拖到中间创作卡片区域松手吸附回原位 | ✅ | `components/Pet.tsx`；`ui-pet` |
| 30 | 点小织打开帮助，点面板以外关闭 | ✅ | `components/HelpPanel.tsx`；`ui-pet` |
| 31 | 模型 / 强度用非弹框浮层 | ✅ | `ComposerPopovers.ModelModeButton`（创造页 + 工作台） |

## 端到端测试记录

| 时间 | 范围 | 结果 |
| --- | --- | --- |
| 2026-11-12 晚 | 全量（api + ui，42 个用例） | 38 通过、3 失败、1 未跑 |
| 2026-11-13 凌晨 | **全量重跑** | **42 通过（15.4 分钟），0 失败** |

期间修的真问题：
- 命令输出里的 `\u0000` 无法写入 Postgres 的 jsonb，会导致事件落库失败、任务卡住 → 事件入库前统一清洗（`TaskEventRepository.clean`）
- 列表项里带「删除」按钮时，按原文匹配对不上就放弃打补丁 → 改为精确匹配 → 前缀匹配 → 索引兜底（`editor-bridge/bridge.js`）
- 「本次修改」视图空白：接口返回 `{base, files}`，前端当数组读；单文件 diff 用 `text: true` 请求导致 406

注意：两个 Playwright 实例不能同时跑，它们共用 `test-results` 目录会互相踩掉 trace。
