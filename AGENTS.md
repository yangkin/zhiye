# 织页开发备忘

## 本地环境
- 一键启动：`bash scripts/dev-up.sh`（PostgreSQL 5433 → 前端构建 → api 8080 + site 8083）；停止：`bash scripts/dev-down.sh`
- 只启动数据库：`pg_ctl -D runtime/pg -o "-p 5433 -k /tmp -c listen_addresses=127.0.0.1" -l runtime/pg.log -w start`
- 仓库已納入 Git 管理（`main` 分支）并配置了 GitHub Actions（`.github/workflows/ci.yml`）；改动保持小步提交、通过全量测试门禁。
- 两个模板各有一份依赖缓存：React `packages/app-template` → `runtime/template-cache`，Vue `packages/vue-template` → `runtime/vue-template-cache`。锁文件在 `deploy/docker/{template-cache,vue-template-cache}/package-lock.json`，`dev-up.sh` 发现 package.json 或锁文件变化时用它 `npm ci`，runner 镜像也用同一份。
- 改模板的 package.json 后重新生成锁文件：空目录放入 package.json，`npm install --package-lock-only --before=<7 天前的日期>`（传递依赖也只取发布满 7 天的版本），拷回 `deploy/docker/...`。新增依赖写死版本号。vite 固定 7.3.7（7.3.6 及以前有安全公告）。
- 模型上游：`scripts/env.sh` 按 `ZHIYE_MODEL`（kimi / deepseek）设置网关上游、模型和密钥文件；本机选择写在 `runtime/local.env`（目前 `ZHIYE_MODEL=deepseek`，只放非密钥配置，命令行显式设置的变量优先）。DeepSeek 用 `deepseek-flash`（V4.1 Flash，支持图片，Pi 内置 `deepseek/deepseek-flash`），密钥在 `/tmp/claude-501/zhiye-secrets/deepseek.env`（`DEEPSEEK_API_KEY`，`/tmp` 重启电脑会被清掉）。临时切回 Kimi：`ZHIYE_MODEL=kimi bash scripts/dev-up.sh`。
- 重启服务前先确认没有进行中的任务（用户可能正在用本地环境）：`psql -h 127.0.0.1 -p 5433 -U zhiye -d zhiye -tAc "select id,type from task where status in ('QUEUED','RUNNING')"`，重启会让进行中的任务失败。

## 验证命令
- 全量 CI 门禁：`bash scripts/ci.sh`（依次跑 runner 单测、黄金评测离线基线、前端 tsc + 构建、服务端 Maven 测试）
- runner 单测：`cd runner && npm test`（模板依赖/导入构建的测试会用 `runtime/template-cache/node_modules`）
- 黄金评测基准（12 个典型场景）：离线检查 `node evals/run-eval.mjs --offline`；在线端到端生成与契约评分 `node evals/run-eval.mjs --live`（报告输出到 `runtime/eval-report.json`）
- 前端类型检查：`cd web && npx tsc --noEmit -p .`；构建：`npm run build -w web`
- 服务端是 Maven 多模块（`server/pom.xml` 父工程 = spring-boot-starter-parent，模块 `zhiye-common` / `zhiye-api` / `zhiye-server`，包名 `com.tcl.csot.zhiye`，Java 17）。织页独立部署、不注册到平台服务，**不依赖 metis**；只参照五星AI岛的模块划分、配置和国际化写法。jar 在 `server/zhiye-server/target/zhiye-server.jar`。
- 服务端测试（需要数据库）：
  `cd server && JAVA_HOME=/opt/homebrew/opt/openjdk@21 JAVA_TOOL_OPTIONS="-DsocksNonProxyHosts=127.0.0.1|localhost|*.localhost" mvn -o test`
  这台机器有系统级 SOCKS 代理，不加 `socksNonProxyHosts` 时 JDBC 连 127.0.0.1 会报 UnknownHostException。
- 端到端（需要先 dev-up）：`cd e2e && npx playwright test tests/api-import.spec.ts`

## 测试注意
- 取消任务会把中断前的工作区自动保存成一个版本（不设为当前版本），续做再产生一个正常版本；相关断言已按这个行为写。
- `GenerationViewTest.todosSteerAndLiveFiles` 在全量并发跑时偶尔因时序拿到 409 `NOT_STEERABLE`，单独跑通过。

## 数据源 / 技能 / 构建归属（2026 后续开发）
- 数据源：`server/.../source/`（登记、绑定、代理），设计与安全规则见 `docs/data-sources.md`；SDK 在 `web/public/cloud/sdk.js`（`zhiye.source.query`）。测试 `DataSourceTest` 用 `support/FakeSourceController` 作为假接口；UI 用例 `e2e/tests/ui-sources.spec.ts`。
- 内置技能在 `packages/builtin-skills/*/SKILL.md`（Docker 镜像已复制）；问答里模型用 `read_skill` 读取，编码时按需读取。技能上限 5 个。创建应用时选的技能挂在应用上（`app.skill_app_ids`），作为之后修改任务的默认；工作台里选的技能**按消息生效**（`sendMessage` 的 `skillAppIds`），发送成功后从输入框清空，不再写回应用（TaskExecutor 对没带技能的消息回落到应用级技能，兼容老数据）。
- 构建按应用隔离（`build.app_id`，V15 迁移）：预览地址 `/p/{buildId}/` 靠它找到应用再注入令牌，不要再按 `version.build_id` 反查。
- 创造页输入框下的推荐需求固定一行 4 个、不折行（窄屏左右滑动），不要改回 5 个折行。
- 创造页场景模板在 `web/src/lib/templates.ts`（图标 `templateIcons.tsx`）；「日常开发」里 scene 以「看板 · 」开头的是数据看板模板（`thumb: 'dashboard'`，卡片封面画指标块 + 迷你图表，窄屏隐藏），共同要求写在常量 `DASH`（联动筛选、目标与预警、数据算出的结论、下钻、导出、示例数据埋异常）；「开发网站」类是多角色、带状态流转的内部业务系统，不放门户 / 招聘类展示页。
- 确认卡：runner 工具 `request_confirm`（`runner/src/steps.mjs` createConfirmExtension）→ `confirm` 事件 → 前端 `workbench/ConfirmCard.tsx` → `POST /api/tasks/{id}/confirm {confirmId, accept}` → 服务端（`TaskExecutor.answerConfirm`）启用云服务后用 bypass 命令 `confirm`（字段 `confirmId`，`id` 是命令编号）交回 agent。假 runner 里指令含 `[confirm]` 会走这条路；测试 `ConfirmCardTest`。
- 云表结构：`cloud/schema.sql` 在校验阶段检查（`withSchemaCheck`），任务/代码保存成功后自动补表补列（`applyCloudSchema`）；数据面按列类型 `?::type` 绑定，类型不符返回 400 `CLOUD_BAD_VALUE`。测试 `CloudSchemaTest`、`cloud/CloudSchemaParseTest`。
- 消息操作栏（时间/复制/编辑/回到此版本/查看改动）常显，不要改回仅悬停显示；成功结束的修改任务不再在底部重复任务卡，折叠进度行挂在对应回复上方（ChatPanel `mergedTaskId`）。
- 会话页文件：回答里的 `[[file:名称]]…[[/file]]` 存进 `conversation_file`（V19，同名记新版本）；V19 之前的会话第一次打开文件面板时从消息补记（`ConversationService.backfillFiles`）。会话页「预览」在右侧 `pages/ChatFiles.tsx` 面板里按类型预览（md 渲染 / csv 表格 / 其他纯文本），下载可选 Word（`lib/docx.ts`，浏览器里用 marked + fflate 生成，不加依赖）或原文。会话页回答下面不显示「下一步」chips。
- 会话「停止回答」：前端 `stopAsk` 中断流并调 `POST /api/conversations/{id}/ask/stop`，服务端停止推送、不落完整回答，只保存已经推给用户的部分（去掉 `[[build]]`，加「（已停止回答）」）；停止来晚了（已答完）返回 `finished:true`，前端重新加载会话。发送后 1 秒内的停止点击忽略（停止按钮就在发送按钮的位置，连点会误停）。测试 `ConversationStopTest`。
- 历史页「会话记录」：`GET /api/conversations` 带 `messageCount/fileCount/appId/appName`，没建应用的会话也能找到。
- 问答附件（图片/文档识别）：消息可带 `assetIds`（≤5，接口 `AskService.assetIds(body)`）。`chat/AskAttachments` 校验工作空间归属后按类型处理：图片（svg 除外，≤6MB）走 `image_url` 多模态；PDF 用 PDFBox（pom 依赖 pdfbox 3.0.5）；docx/xlsx/pptx 用 zip + XML 去标签的极简提取（`extract`，读不了返回 null 并在正文标注）；纯文本/md/csv/json 直读。单附件 8000 字、合计 24000 字。`AskService.userMessage` 组消息：有图时 content 是多模态数组。会话消息存 `conversation_message.asset_ids`（V20），详情/工作台消息都返回 `assets[{id,name}]` 供显示文件名；`build` 时把会话里所有消息的素材并集带进应用。前端三处输入框（创造页/会话页/工作台）都支持粘贴文件（`AddMenu.handleAssetPaste/attachFiles`，`+` 菜单同一份逻辑），会话 `askConversation` 和工作台 `api.ask` 都带 `assetIds`。消息上的附件用 `components/AssetChips.tsx` 渲染：图片显示缩略图、点开大图浮层（Esc/点击关闭），其他文件点开新标签页。上传类型扩到 docx/xlsx/pptx（旧 .doc/.xls/.ppt 二进制不支持）。测试 `chat/AskAttachmentsTest`（提取）、`AskAttachmentTest`（端到端，含图片、越权 404、消息记录）。
- 工作台输入框：确认卡、待办胶囊、待发送消息放在 `.composer-float`，悬浮在输入框上方，不撑高输入框；点「共享云服务」只弹确认卡（`useChat.cloudAsk`），确认后直接启用并自动发一条让 AI 设计表结构的消息，不往输入框填字。用例 `e2e/tests/ui-layout.spec.ts`。
- 发布弹窗：最多只显示一条提示（按优先级取最严重的），前置条件不满足时发布按钮置灰；不再堆叠多条提示。
- 工作台意图路由：`POST /api/chat/intent`（`AskService.intent`）由模型判定 `ask/modify/new`，前端 `quickRoute` 先走快路径，含糊时再调接口。判定 `new`（用户想写和当前应用无关的新应用）时弹「新开一个应用？」卡，确认后新建会话并把原话带过去——一个会话只对应一个应用。
- 云控制台（`workbench/CloudConsole.tsx`）按 WorkBuddy 布局：左侧表导航 + 表下拉、每张表一行描述（来自 `schema.sql` 里 `create table` 上方的 `--` 注释，`CloudService.descriptions` 解析，提示词已要求模型写这行注释）、空表也显示列头。启用云服务但还没有 schema 时会建占位 `notes` 表，真实 schema 落地后自动删掉（`CloudService.applySchema`，仅当 notes 为空且未在 schema 中声明）。
- 灵感社区（V22 `template` + V23 计数/快照/点赞 `template_like`，`server/.../template/`，前端 `pages/CommunityPage.tsx` 列表、`pages/WorkPage.tsx` 详情、`components/Community.tsx` 卡片/发布弹窗/点赞与使用 hooks）：应用卡片「⋯」→「发布到灵感社区」（名称 + 一句话介绍），把当前版本文件树 zip 和**页面快照**（发布产物复制到 `templates/{id}/site/`）存进 ObjectStore。社区作品跨工作空间可见：列表 `GET /api/templates?tab=all|liked|mine&sort=new|likes|uses&type=&q=`，详情 `GET /api/templates/{id}`（每次打开计一次浏览，带 `siteUrl`（来源应用已上线时）和相关推荐），点赞 `POST/DELETE /api/templates/{id}/like`（每个工作空间一次）。看效果只走快照 `SiteFilter /t/{id}/`，**不注入令牌**——不要改成原应用的 `/p/{buildId}/`（那里会注入管理员云令牌，等于把作者的数据给别人）。「使用模板」建新应用 + `clone` 任务（`TaskExecutor.runClone`）：服务端解包写入工作区，**保留原 projectKind**（不走 import，import 会重新识别形态，React 模板会变成 vite、丢掉可视化编辑），校验 → 提交 → 发布；带 `cloud/schema.sql` 时自动启用云服务建空表，不复制数据。失败/取消同 import 一样删掉应用。只有发布者能删（403）。没有「精选」（需要管理员角色，先用「最多点赞」排序代替）。测试 `TemplateTest`。
- 会话建应用的类型：模型在回答第一行输出 `[[build:类型]]`（`AskService` 提示词，四种类型之一，按用户要的成品判断，截图里的标签文字不算），`ConversationService.inferType` 优先用它；没有时才看**用户原话**关键词，再退回页面选的类型。不要再拿助手的描述文字匹配关键词——它复述截图时提到「开发skill」，曾把「照着做个工作台」做成技能包。前端 `ChatPage`/`markdown.tsx` 的正则兼容带不带类型两种写法。测试 `conversation/InferTypeTest`。
- 创造计划卡只有「确认计划」「设计风格」两步；「制作素材」步骤已去掉（它只往生成指令里加一句「用 CSS/SVG 自绘」，不产出任何文件，容易误导）。`app.materials` 列保留但不再使用。
- 创造计划卡（`workbench/PlanCard.tsx`）默认按文档排版显示需求文档（`Markdown` 组件，`- [ ]` 转成 ☐），点「编辑」或双击切到 Markdown 文本框，自动保存不变。
- 计划阶段带素材：`TaskExecutor.runPlan` 用 `AskAttachments` 读应用的 `assetIds`，经 `AskService.userMessage` 组多模态消息、`KimiClient.chat(…, ObjectNode, …)` 发出——以前只发文字，计划会写「未提供截图」。测试 `PlanAttachmentTest`（假上游 `FakeUpstreamController.BODIES` 留最近 100 个请求体，并发跑时按内容找自己那条）。
- 重试失败任务（`TaskService.retry`）一开始就删掉上一轮的「生成失败：」系统提示，否则它会挂在进行中的卡片下面，看起来像这次也失败了；重试再失败会重新写一条。
- 任务进行中点「共享云服务」并确认：不马上启用，`POST /api/tasks/{id}/queued {content, enableCloud:true}` 排一条「设计表结构」消息（V24 `task_message_queue.enable_cloud`），`TaskQueueService.drainNext` 轮到它时先 `CloudService.enable` 再建修改任务；撤回消息就不启用。按钮在排队期间显示「云服务排队中」（虚线），点它展开待发送消息。没有任务在跑时仍是立即启用并发消息。测试 `QueueCloudTest`。
- 输入框上方悬浮层（确认卡 / 待办 / 待发送消息）的留白由 ChatPanel 用 ResizeObserver 按实际高度算（`chat-scroll` 的 paddingBottom），不要再写死 64px/250px 的 `:has` 规则——几样叠在一起时会压住任务卡。
- 灵感社区列表的筛选（tab/type/sort/q）记在地址参数里；详情页「返回社区」回到带进来时的列表地址（`location.state.from`），不用浏览器后退（从相关推荐、创造页进入时后退会去别处）。
- 应用摘要带 `cloudEnabled`（`Views.summary`）：应用卡片显示「云服务」徽标，工作台「云服务」标签旁有绿点。
- 小织的产品知识两处要同步：`web/src/lib/helpFaq.ts`（离线兜底）和 `server/.../help/HelpPrompt.java`（模型提示词）。加新功能时两边都补上。
- 会话详情 `GET /api/conversations/{id}` 带 `appId/appName/appPhase`（最近一个从该会话建的应用），会话页在建应用的回答下渲染「打开应用」卡（`.conv-app-card`），重开/恢复会话后也能回到应用。
- 工作空间身份靠 `zhiye_ws` cookie（按主机区分，`localhost` 与 `127.0.0.1` 不通用）；cookie 丢了会发一个新的空工作空间，数据并没丢，用「我的工作空间 → 恢复链接」（`/recover#token`）找回。
- 吉祥物「小织」是小机器人（2026-11 起替换小金毛，旧的 `pet*.webp/png` 已不再引用）。素材在 `web/public/pet/robot-*`，原图在交接包 `动图/`：
  - 「可爱机器人打字动画帧格.gif」原图是不透明近白底，按「从边缘连通 + 色差渐变透明」抠图后拆帧（机身也偏白，不能简单按颜色抠）：`robot-type.webp`（16 帧 140ms 打字循环）、静帧 `robot-open/blink/smile.webp`（同尺寸 335×241，叠放切换不闪）、帮助头像 `robot-face.webp`。
  - 「萌趣机器人编程敲击动画.gif」→ `robot-code.webp`（生成中等待 `CreatingStage`），静帧 `robot-code-still.webp`（减少动态效果）。
  - `components/Pet.tsx` 状态：平时打字；鼠标悬停停下、往前趴到输入框边缘（`pet-peek`），每 1.6～3 秒随机眨眼（`pet-blink`）或歪头（`pet-tilt`）；点击抬头笑眼、左肩后面冒出小手挥两下（`pet-greet` + `.pet-hand`），约 0.9 秒后再执行 onClick（首页打开帮助）。睡觉状态（计划阶段、已停止、暂无预览）显示闭眼帧 + CSS zzz，悬停睁眼。样式在 `create.css` 的 `.pet*`。用例 `e2e/tests/ui-pet.spec.ts`。
  - 小织的「形象」文案在 `HelpPrompt.java`（小机器人，语气词「嗨～」），不要再写「小金毛 / 汪～」。

## 项目类型（projectKind）
- `template`（React 模板，默认）、`vue`（Vue 3 模板，计划判定形态为 `vue` 时切换，只在用户明确要 Vue 时选）、`static`、`script`、`skill`，以及导入项目的 `vite`（历史名称，表示「安装依赖后构建」，导入的 Vue 项目也是它）。
- 计划阶段按形态换模板：`TaskExecutor.kindForShape/templateFor`，`GitService.initFromTemplate` 强制覆盖初始提交，所以只在还没有版本时换。
- Vue 项目：只支持对话修改（`visualEditable=false`，不注入编辑桥）；runner 校验见 `verify.mjs verifyVue`（vue-tsc → 构建 → 冒烟），系统提示 `prompts.mjs VUE`；`charts-dashboard`、`multi-page-app` 两个内置技能是 React 写法，不给 Vue 项目。测试 `VueTemplateTest`、`runner/test/vue-template.test.mjs`。假上游里需求带 `[vue]` 时计划返回 Vue 形态。

## 五星AI岛：统一登录、模型与额度（2026-11）
- 设计、配置、需要 openclaw 新增的接口和内网联调清单见 `docs/fivestar-integration.md`。开关 `zhiye.fivestar.enabled`（默认关，走 `zhiye.llm` 本地上游）。
- 所有模型调用都经 `service/llm/LlmRouter` 取 `LlmRoute`（本地上游，或员工在 llm-gateway 上的路由：虚拟密钥 + `X-User-Id` 工号 + `X-Project-Id: zhiye`），再用 `UpstreamClient.send(route, …)`。新增模型调用不要直接用 `upstream.send(...)` 的本地重载。任务按「消息指定的模型 → 应用上的模型」取路由，模型标识是 `auto` 或 `供应商ID:模型ID`。
- 不设任务级 token 上限（`token_budget` 列保留但不再使用）。额度用完统一成 `PROVIDER_QUOTA`，文案在 `QuotaText`；重试会接着做（`TaskService.retry` 的 continueWork 包含它）。
- 前端：平台令牌存 `lib/platformToken.ts`，`api/client.ts` 对 `/api/*` 自动带 `Authorization`；模型菜单 `ComposerPopovers.useModelList / QuotaRow`（`meta.fivestar` 为真时取 `/api/llm/models`、`/api/llm/quota`）。
- 外网模拟：`ZHIYE_MOCK_INTRANET=1 bash scripts/dev-up.sh`（profile `mock`）。模拟统一认证 `controller/mock/MockSsoController`（路径同真实网关，令牌 `tok-{工号}`），模拟 openclaw + llm-gateway `controller/mock/MockFivestarController`。测试 `FivestarQuotaTest`。

## 云服务、访问控制、云函数、应用内 AI
- 设计与接口见 `docs/cloud-services.md`。发布站点走同源 `/__zhiye/*`，HTML 里不放令牌；预览用令牌 + 预览身份（admin/member/anon）。
- 访问级别 `public/login/members` 在 `app_site_access`（V16），非 public 需要 `zhiye.auth.mode=sso`；表预设需要身份而访问级别是内网公开时拒绝发布。
- 云函数用 Deno（`zhiye.functions.deno`，宿主 `packages/fn-runtime/host.ts`）；应用内 AI 走 `AppAiService`（复用上游客户端）。

## 截图自查与视觉质检门禁
- runner 工具 `screenshot`（`runner/src/screenshot.mjs`，在 `steps.mjs` 注册，`agent.mjs` 的扩展和工具白名单都要有它）；需要 `ZHIYE_CHROME_PATH`，本地是系统 Chrome。模板项目优先通过内存级 Vite Dev Server 直接渲染截图，免去写盘构建产物（`.zhiye/dist-shot`）。
- 平台校验阶段（`verify.mjs` + `smoke.mjs`）：预览版（`minify: false`）与发布版并行构建；Chromium 冒烟覆盖首屏渲染、多页 `#/` 路由巡检、安全 Tab 与搜索框探活、按钮重叠与死图检查、390px 移动端视口溢出、以及无同源 `sandbox="allow-scripts allow-forms"` iframe 沙箱探活。runner 镜像设了 `ZHIYE_SMOKE_REQUIRED=1`，浏览器起不来时冒烟测试判失败。
- 校验首轮通过后，`VisualCritiqueService` 基于冒烟采集的真实桌面截图调用多模态模型做一次快速视觉把关（专查严重遮挡、排版坍塌或泄露标记，上游异常时默认 fail-open 放行）。
- 增量修改（`modify`）上下文增强：`RepoMap.build` 自动注入最近 3 个版本演进摘要与仓库文件/顶层符号索引；`TaskExecutor.recentWorkbenchQa` 自动桥接上一轮任务后的工作台问答记录；`AskService` 保证静态提示词前置以提升 KV Cache 命中率，并对多轮历史执行 `user`/`assistant` 严格交替归一化与字符/Token 双预算裁剪。

## 会话里的成品判断（文档 vs 应用）
- 创造页会话（`AskService.CONVERSATION_RULES`，只在没有 appId 时追加）先判断成品：文字材料（技术设计文档、方案、手册、报告等）不建应用，直接输出 `[[file:名称.md]] … [[/file]]` 文件块；明确要「文档网站」才走 `[[build]]`。
- 前端 `pages/ChatParts.tsx`：`splitFiles` 拆文件块 → `FileCard`（写入中进度、预览、复制、下载 .md）；`ThinkingBlock` 显示模型的 `reasoning_content`（SSE `thinking` 事件，存在 `conversation_message.thinking`，V18）。
- 会话回答 `max_tokens` 16000、`reasoning_effort` medium；带文件的历史消息保留 4 万字（`historyCap`），才能「接着改第 3 节」。测试 `ConversationDocTest`（假上游里内容带 `[doc]` 时返回思考 + 文件块）。
- 创造页「技术文档」模板写的是「直接写成一份可下载的 Markdown 文档」，不要再写成「左侧目录、搜索」这种网页需求。

## 前端体验约定（2026-11 外部评审整改）
- **字号底线**：可见文字不小于 11px（徽标、角标），正文/按钮/说明 12px 起；手机断点不再继续缩小字号。只有缩略图里的示意内容（`.mini-*`、`.thumbnail`、头像字、步骤圆点）可以更小。新增样式别再写 6～10px。
- **配色**：弹窗、说明文字、toast 用中性墨色（`#1f1c29` / `#5e586e` / `#6c667d`），不要再用早期原型的浅粉紫（`#9574a9`、`#c9b4d7` 等）。工作台用 `--st-*` 变量。
- **草稿不丢**（`lib/drafts.ts`，localStorage 前缀 `zhiye.draft.`）：创造页表单（含是否选了类型）、工作台聊天草稿（按应用）、代码未保存修改（按应用，记 `base` 版本；版本变了保存按钮显示「覆盖保存」并说明）。发送/保存成功后清掉。
- **回答流必须收到 `event: done`** 才算完成；断开就提示「回答中断」，会话页给「重试」（原文、技能、附件原样重发），也不会据此自动建应用。会话页回答中可以「停止回答」。
- **错误边界**：`components/ErrorBoundary.tsx`。整页一层（按路由重置），代码面板、代码编辑器各一层；按需加载用 `retryableLazy`，重试会重新下载模块。
- **应用阶段 `stopped`**：生成时被主动停止且还没有任何版本 → `stopped`（「已停止」，可继续/重新生成），和 `failed` 分开。任务成功或重试开始时，旧的「生成失败」和「被用户取消」系统提示一起清掉（`MessageRepository.deleteFailures`）。
- **弹窗**：`Modal`、版本抽屉、代码导入用 `hooks/useDialogFocus`（焦点进弹窗、Tab 循环、背景 inert、关闭回焦）；`useEscape` 是栈，Esc 只关最上层。
- **工作台**：手机（≤760px）对话和工作区二选一，顶部多一个「对话」页签；≤1100px 时代码页文件树在上、编辑区占满宽；页签支持左右方向键 / Home / End；预览刷新先探测地址，打不开不报「已刷新」；代码标题按实际显示「可编辑 / 历史版本只读 / 生成中只读 / 修改中」。
- **输入框底部工具**统一 `.composer-tools`：30px 高、8px 圆角、12px 字。
- **推荐需求**填入时如果已有草稿，toast 给「撤销」（`toast(msg, tone, { label, run })`）。
- **不用 `window.confirm`**：用户在 Chrome 里勾过「阻止此页面再弹出对话框」后它会静默返回 false，按钮像点了没反应（「关闭云服务」就踩过）。统一用 `components/ConfirmDialog.tsx` 的 `await confirmDialog({ title, message, confirmLabel, danger })`。

## 能力上限（一次能做多大、哪些做不到）
- 唯一来源 `server/.../common/Capabilities.java`：`LIMITS`（页面数量不限、云表 ≤50（`CloudService.MAX_TABLES` 引用同一个常量，表结构校验硬限制）、角色 ≤3、每条流程状态 ≤6；执行步数不限）和 `UNSUPPORTED`（原生 App/小程序、直连生产库或写 MES/ERP、自建后端/定时任务、支付短信、实时协同/音视频/3D、训练模型、非统一登录账号体系，每条都带替代做法）。调上限只改这里。
- 引用处：计划（`TaskExecutor.runPlan` 追加 `PLAN`：超限只写第一期，加「## 分期计划」表格，JSON `scale: phased`；有做不到的加「## 做不到的部分」）、创造页会话（`AskService` 追加 `CHAT`：先说做不到 / 一次做不完，建议第一期，用户同意再 [[build]]）、生成（`Prompts.generate` 追加 `GENERATE`：只实现第一期）、修改（`MODIFY`：超量先做核心部分并在「需要注意」里写剩余）、小织（`HelpPrompt` 追加 `HELP`；离线 FAQ `helpFaq.ts` 的 `scope` 条目要同步）。
- 计划卡（`PlanCard.tsx`）检测需求文档里的「## 分期计划」「## 做不到的部分」两个标题，显示 `.plan-scope-note` 说明；用户手改文档也按标题判断。
- 运行时兜底：runner 每段撞到步数上限返回 `error: {code: 'TURN_LIMIT'}`，服务端**不判失败**，发「要继续吗？」确认卡（见下一条）。失败重试时 `PAUSED` / `TIMEOUT` / `PROVIDER_QUOTA`（以及旧数据的 `TOO_LARGE`）`resume.continueWork=true`，发 `Prompts.resume`（接着做、不重做），其他失败原样重发。测试 `CapabilityLimitTest`、`ContinueCheckpointTest`（假上游 `[big]` 返回分期计划，假 runner `[turnlimit]` 撞一次检查点）。
- **任务不设 token 上限，也不限执行步数**（用户决定）：任务级预算代码已删除；额度以五星AI岛为准。步数只是检查点：runner 每做 `max-agent-turns`（60）步返回 TURN_LIMIT，`TaskExecutor.awaitContinue` 发 `kind=continue` 的确认卡（「继续」/「先到这里」），用户点继续、或在输入框里说话（`POST /api/tasks/{id}/confirm` 带 `text`，或 steer）就在同一个 agent 会话里接着做（`Prompts.keepGoing`），并重新计时；「先到这里」或 `continue-wait-minutes`（30）没回应 → `PAUSED`，重试接着做。`max-duration-minutes`（60）只是一段运行的防卡死时限；沙箱存活上限 `sandbox-max-hours`（8）。上游 402（DeepSeek 余额不足）归 `PROVIDER_QUOTA`。


## 文档索引
- 总体设计 `docs/architecture.md`、能力边界与待办 `docs/capabilities.md`、部署与配置项 `docs/deployment.md`、测试与黄金评测 `docs/testing.md`。接口改动先改 `docs/api-contract.md`（末节是继续检查点 / 额度 / 停止回答 / 登录失效）。

## 多实例（base 部署 api 2 副本）
- 不要只靠进程内存传「用户对正在执行的任务的操作」：停止走 `CANCELLING` 状态；确认卡和补充要求在本实例没有这个任务时写 `task_signal`（V25），执行实例的 `TaskWorker.heartbeat` 取走交给 agent（`TaskController.confirm/steer`、测试 `TaskSignalRelayTest`）。新增类似操作照这个做。
- 会话回答流：停止请求落到别的实例时除了按数据库判断 `finished`，还会标记 `conversation_message.stop_requested = true`（V27），流所在实例实时感知并中断流、保存**已发出去的**部分（`ConversationStopTest`）。
- 跨实例限流：`RateLimitService` 基于 PostgreSQL `rate_limit_hit` 表（V27）对问答、润色、意图识别、应用内 AI 做全局滑动窗口限流。
- 服务端测试里配置不同的上下文（`@TestPropertySource`）必须用自己的 schema：`spring.datasource.url=…/zhiye_test?currentSchema=ctx_xxx` + `spring.flyway.schemas=ctx_xxx`，否则各上下文的工作进程会领走彼此的任务。

## 前端约定补充
- 「停止回答」（会话页、小织）发送后 1 秒内是禁用态（不是悄悄忽略点击）；小织的回车同理。
- 输入框弹窗用 `promptDialog`（`components/ConfirmDialog.tsx`），不用 `window.prompt`。
- 登录失效：`api/client.ts onAuthExpired`，401 `UNAUTHORIZED` 整页跳 `/login?error=expired`，`PLATFORM_TOKEN_INVALID` 直接重走统一认证；一次页面加载只跳一次。用例 `e2e/qa-auth-expiry.mjs`（模拟内网实例，起法见 `docs/testing.md`）。
- 页面巡检 `e2e/layout-audit.mjs`：三种宽度截图 + 横向溢出 / 小于 11px 文字 / 控制台错误 / 会话页一屏布局；改样式后跑一遍。
