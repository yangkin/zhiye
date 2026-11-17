# 织页接口与编辑桥约定

本文件是前端（`web/`）、Java 主服务（`server/`）和预览编辑桥（`packages/editor-bridge/`）之间的唯一约定。改动接口时先改这里。

## 0. 端口与地址（本地开发）

| 服务 | 地址 | 说明 |
|---|---|---|
| 主服务 API + 前端静态资源 | `http://127.0.0.1:8080` | 前端构建产物由主服务托管；开发时 Vite 代理 `/api` 到这里 |
| 预览与站点网关 | `http://127.0.0.1:8083` | 预览：`/p/{buildId}/`；已发布站点：`http://{slug}.localhost:8083/` |

预览和站点与工作台不同源。iframe 必须使用 `sandbox="allow-scripts allow-forms"`，不加 `allow-same-origin`。

## 1. 通用

- 所有接口前缀 `/api`，JSON，UTF-8。
- 身份：浏览器 cookie `zhiye_ws`（httpOnly）。首次请求任意 `/api` 接口时服务端自动创建工作空间并下发 cookie，前端无需处理登录。
- 写接口支持请求头 `Idempotency-Key`（前端用 `crypto.randomUUID()` 生成，同一次用户操作重试时复用）。
- 时间：ISO 8601 字符串。
- 错误：HTTP 4xx/5xx，响应体 `{ "code": "STRING_CODE", "message": "给用户看的中文说明" }`。前端直接展示 `message`。

常见错误码：`TASK_LIMIT`（429，每人同时只能运行 1 个任务，计划任务也算）、`APP_LIMIT`（409，每人最多 10 个应用，已删除的不算，导入中的算）、`SKILLHUB_NOT_CONFIGURED`（503）、`NOT_A_SKILL`（400）、`SKILL_NOT_PUBLISHABLE`、`SKILL_NOT_EDITABLE`（skill 应用的草稿/可视化保存接口）、`VALIDATION`、`NOT_FOUND`、`APP_BUSY`（应用有进行中的写任务）、`DRAFT_PENDING`（存在未保存的可视化编辑）、`RATE_LIMITED`、`IMPORT_INVALID`、`IMPORT_UNSUPPORTED`、`ALREADY_CURRENT`。

## 2. 数据类型

```ts
type AppType = '日常开发' | '开发网站' | '开发skill' | '技术文档';   // 默认 '开发网站'；都生成可预览的 Web 项目，不再区分手机/桌面
type AppPhase = 'planning' | 'plan' | 'generating' | 'ready' | 'failed';
// planning：正在生成创造计划；plan：计划已生成，等待确认执行；generating：生成任务进行中；
// ready：至少有一个版本；failed：首次生成失败（可重试），还没有任何版本
type StyleId = 'lavender' | 'mint' | 'sand' | 'rose' | 'ink' | 'cyber';
type Mode = '快速' | '适中' | '深度';

interface VersionRef { id: string; number: number; title: string; createdAt: string }

interface AppSummary {
  id: string; name: string; type: AppType; description: string;
  favorite: boolean; phase: AppPhase; source: 'create' | 'import';
  framework: string | null;            // 导入应用的项目类型，如 'HTML'、'Vite + React'
  projectKind: 'template' | 'static' | 'vite' | 'skill';   // skill = 开发skill 应用：只有文件（SKILL.md、references/、scripts/），没有预览、发布和可视化编辑
  createdAt: string; updatedAt: string;
  currentVersion: VersionRef | null;
  publishedVersionId: string | null;
  previewUrl: string | null;           // 当前版本预览版地址（带编辑桥），无版本时为 null
  siteUrl: string | null;              // 已发布站点地址，未发布为 null
  hasDraft: boolean;                   // 是否有未保存的可视化编辑
  visualEditable: boolean;             // 是否支持可视化编辑（Vite/React 生成应用、静态 HTML 支持）
  activeTask: TaskRef | null;
  skillhub: { enabled: boolean; lastPublishedAt: string | null; url: string | null } | null;   // 只有 skill 应用有；enabled=false 表示未配置
}

interface AppDetail extends AppSummary {
  prompt: string;                      // 用户最初的需求
  draftBaseVersionId: string | null;   // 草稿的基线版本；与 currentVersion.id 不同时草稿已过期（前端提示）
  planDoc: string;                     // 创造计划（Markdown），可编辑
  style: StyleId;
  materials: boolean;                  // 是否「制作素材」
  mode: Mode;
  model: string;                       // 'auto'
  assets: Asset[];                     // 创建时附带的素材
}

type TaskType = 'plan' | 'generate' | 'modify' | 'visual_save' | 'code_save' | 'import';
type TaskStatus = 'QUEUED' | 'PREPARING' | 'RUNNING' | 'VERIFYING' | 'COMMITTING'
                | 'SUCCEEDED' | 'FAILED' | 'CANCELLING' | 'CANCELLED';
interface TaskRef { id: string; type: TaskType; status: TaskStatus }
interface Task extends TaskRef {
  appId: string;
  phaseLabel: string;                  // 中文阶段说明，如「正在编写代码」
  progress: number;                    // 0-100，按真实阶段推进，不是计时器
  error: { code: string; message: string } | null;
  resultVersionId: string | null;
  resumable: boolean;                  // 已停止且有检查点，可「继续」
  retryable: boolean;                  // 已失败，可「重试」
  createdAt: string; finishedAt: string | null;
}

interface TaskEvent {
  seq: number; type: TaskEventType; createdAt: string; payload: any;
}
type TaskEventType =
  | 'status'        // { status, phaseLabel, progress }
  | 'agent_text'    // { delta }：agent 说明文字增量
  | 'tool'          // { id, name, summary, state: 'start'|'end', ok?: boolean }  summary 如「修改 src/App.tsx」
  | 'verify'        // { checks: [{ name: '类型检查'|'构建'|'冒烟测试', ok, detail? }] }
  | 'log'           // { line }
  | 'finished';     // { status, versionId?, error? }  最后一条

interface Message {
  id: string; role: 'user' | 'assistant' | 'system';
  content: string;
  elementContext: ElementContext | null;
  assetIds: string[];                  // 这条消息附带的素材
  skillAppIds: string[];               // 这条消息引用的技能
  taskId: string | null;
  status: 'ok' | 'pending' | 'failed';
  createdAt: string;
}

interface ElementContext {
  appId: string; baseVersionId: string;
  loc: string;                         // 'src/App.tsx:18:9' 或 'index.html:12:5'
  tag: string; text: string; outerHTML?: string;
  styles?: Record<string, string>;
}

interface Version {
  id: string; appId: string; number: number; title: string;
  source: 'generate' | 'modify' | 'visual' | 'code' | 'restore' | 'import';
  createdAt: string; favorite: boolean;
  isCurrent: boolean; isPublished: boolean;
  restoredFromNumber: number | null;
  previewUrl: string | null;           // 该版本的预览版地址（历史版本预览不开启编辑）；skill 应用为 null
}

interface HistoryRow { app: { id: string; name: string; type: AppType }; version: Version }

interface Asset { id: string; name: string; mime: string; size: number; url: string; createdAt: string }

interface Draft { baseVersionId: string; patches: Patch[]; updatedAt: string }
interface Patch {                      // 每个 loc 一条，前端合并同一 loc 的多次修改
  loc: string;
  text?: string;
  styles?: Record<string, string>;     // 驼峰 CSS 属性：fontSize、fontWeight、fontStyle、textDecoration、
                                       // textAlign、color、backgroundColor、borderRadius、padding
  href?: string;
  removed?: boolean;
  index?: number;                      // 只作用于同 loc 的第 index 个实例（DOM 顺序，0 起；整数 ≥ 0）。保存在草稿中并原样传给 runner visual（.map() 列表项逐项写回）
  match?: string;                      // 该实例原始文字（≤200 字），列表重排后按它找回；同样原样传给 runner。不合法 → 400 VALIDATION；runner 拒绝 → visual_save 失败（runner 的中文原因），草稿保留
}

interface ImportManifest {
  uploadId: string; name: string;
  format: 'html' | 'zip';
  framework: string;                   // 'HTML' | '静态站点' | 'Vite + React' | 'Vite + Vue' | 'Next.js' | 'Nuxt.js' | 'React' | 'Vue' | 'Python' | 'Go' | 'Java' | '待识别'
  deployable: boolean;                 // false 时 unsupportedReason 说明原因，前端禁用「导入并部署」
  unsupportedReason: string | null;
  fileCount: number; totalSize: number;
  files: { path: string; size: number }[];   // 最多返回前 500 条
}

// 数据统计：只统计已发布站点（{slug}.localhost:8083）的 HTML 页面访问，不含预览、静态资源、HEAD 和爬虫；日期按 Asia/Shanghai
interface AppStats { todayPv: number; totalPv: number; rangePv: number; daily: { date: string; pv: number }[] }   // date: 'YYYY-MM-DD'，升序
interface AppsSummary { total: number; published: number; totalPv: number; appLimit: number; appsUsed: number; taskLimit: number }   // appsUsed 含导入中的应用

interface Meta {
  auth: { mode: 'anonymous' | 'sso'; user: { id: string; loginName: string; realName: string; email: string | null } | null };
  models: { id: string; label: string; desc: string; cost: number }[];   // [{ id:'auto', label:'Auto', desc:'自动选择合适的模型', cost:2 }, { id:'kimi-for-coding', label:'Kimi K2', desc:'擅长编码与长任务', cost:2 }]；目前都使用当前 Kimi 模型。`POST /api/apps` 与 `POST /messages` 的 `model` 必须是其中之一，否则 400
  modes: Mode[];
  styles: { id: StyleId; label: string; accent: string }[];
  limits: { uploadMaxBytes: number; uploadMaxFiles: number; assetMaxBytes: number };
}
```

## 3. 接口

### 工作空间与元信息
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/meta` | → `Meta` |
| GET | `/api/meta/metrics` | → `{ scope, instances, tasks, sandbox, llm, trend, alerts }`（PostgreSQL 多实例汇总：任务耗时含 p95、失败码分布、沙箱冷启动、模型延迟/额度错误率、24 小时趋势与阈值告警） |
| GET | `/api/meta/metrics/prometheus` | → Prometheus 文本格式指标（`zhiye_tasks_total`、`zhiye_sandbox_start_avg_ms`、`zhiye_llm_quota_errors_total`、`zhiye_alerts_active`） |
| GET | `/api/workspace` | → `{ id, createdAt, recoveryUrl }`，recoveryUrl 形如 `http://127.0.0.1:8080/recover#<token>` |
| POST | `/api/workspace/recover` | `{ token }` → 设置 cookie 切换到该工作空间 → `{ id }` |

### 统一登录（SSO，默认关闭，见 docs/sso.md）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/auth/sso/login` | 302 跳转到统一认证网关；未开启 → 404 `AUTH_DISABLED` |
| POST | `/api/auth/sso/callback` | `{ accessToken, expiresIn? }` → `{ user }`，设置织页会话 cookie `zhiye_session`。令牌无效 → 401 `INVALID_TOKEN`；网关不可达 → 502 `SSO_UNREACHABLE` |
| POST | `/api/auth/logout` | 退出 → 204 |
| GET | `/api/auth/me` | → `Meta.auth` |

开启 SSO 后，未登录访问 `/api/meta`、`/api/auth/**` 以外的接口返回 401 `UNAUTHORIZED`；前端守卫把用户带到 `/login`。

### 应用
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/apps?q=&type=&favorite=&published=all\|published\|unpublished&sort=updated\|created\|name` | → `AppSummary[]`；`type` 取上面五种之一，不传为全部 |
| POST | `/api/apps` | `{ type, prompt, mode, model, assetIds, skillAppIds? }`（`skillAppIds`：最多 5 个自己的、已生成的「开发skill」应用，生成时遵循这些技能，不合法 → 400） → `AppDetail`（phase=planning，同时创建 plan 任务，`activeTask` 指向它）。空白 prompt → 400。**开发skill 跳过创造计划**：不创建 plan 任务，`planDoc` = 用户需求，名称取自需求，直接 phase=generating 并创建 generate 任务（`activeTask.type='generate'`）；没有设计风格/制作素材；之后的阶段与其他类型相同（成功 ready、失败 failed 可重试、停止可继续） |
| GET | `/api/apps/{id}` | → `AppDetail` |
| PATCH | `/api/apps/{id}` | 局部更新：`{ name?, favorite?, planDoc?, style?, materials?, model?, mode?, skillAppIds? }`。`skillAppIds` 把技能挂到应用上持久保存（规则同创建，`[]` 清空），`AppDetail.skills` 返回 `[{id,name}]`（已删除的技能不返回）；`model` 为模型 id（`auto` 表示默认），`mode` 取 快速/适中/深度；只影响之后的修改任务 → `AppDetail` |
| PATCH | `/api/apps/{id}` | `{ name?, favorite?, planDoc?, style?, materials? }` → `AppDetail`。名称 1～40 字 |
| DELETE | `/api/apps/{id}` | 删除应用 → 204。同时下线（站点地址随即 404），从列表、历史、汇总中消失；有进行中的任务 → 409 `APP_BUSY`（先停止任务） |
| GET | `/api/apps/summary` | → `AppsSummary`：当前工作空间的应用数（不含未完成的导入）、已发布数、总访问量 |
| GET | `/api/apps/{id}/stats?range=all\|today\|7d\|3d` | → `AppStats`（默认 7d）。`daily` 按天补零：today=1 天；3d/7d=含今天的最近 N 天；all=从第一天有数据或首次发布那天到今天（最多 180 天）。未发布的应用返回 0 |
| POST | `/api/skillhub/apps/{id}/publish` | 发布 skill 包到公司 SkillHub：`{ versionId?, categoryId?, displayName?, summary?, slug?, tags?, version?, visibility?: 'private'\|'team'\|'public', changelog?, submitReview? }`（默认当前版本、private、提交审核）→ `{ skillId, skillVersion, url, versionId, publishedAt, status }`。非 skill 应用 → 400 `NOT_A_SKILL`；SKILL.md 头部 name/description 不合法 → 400；未开启 → 503 `SKILLHUB_NOT_CONFIGURED`。平台 /add 不接收更新说明，/edit 使用 `changeLog` |
| GET | `/api/skillhub/apps/{id}/publications` | → `{ id, versionId, versionNumber, skillId, skillVersion, url, visibility, status, reviewStatus, publishedAt }[]`（时间降序） |
| POST | `/api/apps/{id}/generate` | 按当前计划执行生成 → `Task`。phase 必须是 plan 或 failed |
| GET | `/api/apps/{id}/messages` | → `Message[]`（时间升序） |
| POST | `/api/apps/{id}/messages` | `{ content, elementContext?, assetIds?, skillAppIds? }` → `{ message: Message, task: Task }`，创建 modify 任务。存在草稿时任务会先应用草稿再修改。`elementContext.appId` 不等于路径 id → 400。`assetIds`：当前工作空间的素材（最多 20 个），复制到项目 `public/assets/`（skill 应用为 `references/assets/`）并写进指令；`skillAppIds`：同上，最多 5 个，技能文件放在 agent 目录 `skills/{name}/`（不进入项目）。不属于自己 → 400 |
| GET | `/api/apps/{id}/tasks?active=true` | → `Task[]` |
| POST | `/api/apps/{id}/code` | 代码页编辑：`{ baseVersionId, files: [{ path, content?, delete?: true }] }` → `Task`（code_save）。不经过 agent：写入/删除文件 → 校验（按 projectKind）→ 新版本（source=code，标题「代码编辑 · N 个文件」）；校验失败 → 任务 FAILED（「检查没有通过，修改未保存：…」），不产生版本。路径只能是相对路径，不能含 `..`、反斜杠，不能在 `.git/`、`node_modules/`、`.zhiye/` 下；最多 50 个文件，每个 ≤1 MB 文本。`baseVersionId` 不是当前版本 → 409 `VERSION_MISMATCH`；有草稿 → 409 `DRAFT_PENDING`；有进行中任务 → 409 `APP_BUSY`；支持 `Idempotency-Key`。所有项目类型可用 |

### SkillHub（公司技能市场）
以下浏览、安装和发布接口都需要 `Authorization: Bearer <平台令牌>`；织页只转发，不保存令牌。未配置 SkillHub 时返回 503 `SKILLHUB_NOT_CONFIGURED`，令牌失效返回 401 `PLATFORM_TOKEN_INVALID`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/skillhub/categories` | → `{ id, name, description, count }[]` |
| GET | `/api/skillhub/market?q=&categoryId=&page=1&limit=20` | → `{ items, total, pageNo, limit }`，市场技能含 `skillId/slug/name/summary/owner/version` |
| GET | `/api/skillhub/market/{skillId}` | 技能详情、版本和文件列表 |
| GET | `/api/skillhub/market/{skillId}/package?versionId=` | 下载技能 zip |
| GET | `/api/skillhub/installs` | 当前工作空间已安装技能 |
| POST | `/api/skillhub/installs` | `{ skillId, versionId? }` → 安装记录，id 以 `hub_` 开头（Windows 路径安全）；同版本同内容重复安装幂等 |
| DELETE | `/api/skillhub/installs/{id}` | 卸载 → 204 |
| GET | `/api/skillhub/my-skills` | 自己的「开发skill」应用 + 已安装 SkillHub 技能，供技能选择器使用 |
| GET | `/api/skillhub/mine?q=` / `/published?q=` | 当前平台账号发布的技能及审核状态 |
| POST | `/api/skillhub/apps/{id}/publications/refresh` | 刷新某个应用在平台上的审核状态 |
| POST | `/api/skillhub/published/{skillId}/withdraw` | `{ versionId, submissionKey? }`，撤回审核 |

### 任务
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/tasks/active` | → `{ task: Task, appId, appName } \| null`：当前工作空间进行中的任务（每人同时只能有 1 个，类型可以是 plan/generate/modify/visual_save/code_save/import），前端用来提示「「X」正在修改中」。`AppDetail.activeTask` 同样覆盖所有类型 |
| GET | `/api/tasks/{id}` | → `Task` |
| GET | `/api/tasks/{id}/events?after=<seq>` | SSE。每条 `event: task` + `data: TaskEvent`，`id:` 为 seq；支持 `Last-Event-ID`。收到 `finished` 后服务端关闭连接 |
| POST | `/api/tasks/{id}/cancel` | 停止 → `Task`（CANCELLING 或 CANCELLED） |
| POST | `/api/tasks/{id}/resume` | 从检查点继续 → 新 `Task` |
| POST | `/api/tasks/{id}/retry` | 失败后重试 → 新 `Task` |
| POST | `/api/tasks/{id}/confirm` | 回答确认卡：`{ confirmId, accept, text? }` → `{ ok, accept }`。确认卡来自 `confirm` 事件（`kind=cloud` 启用云服务；`kind=continue` 步数检查点「要继续吗？」，带 `acceptLabel/declineLabel`）。`text`（≤3000 字）只对 `continue` 有意义：用户在输入框里说「继续，顺便…」，会记成一条补充要求消息并带给 agent。确认 ID 过期、已经选过、任务已结束 → 409 `CONFIRM_EXPIRED`；别的工作空间 → 404。多实例时请求落在没执行该任务的实例上，经 `task_signal` 表约 1 秒内转给执行它的实例（同一张卡只转一次） |
| POST | `/api/tasks/{id}/steer` | 生成中途补充要求：`{ text }`（≤2000 字）→ `{ message, task }`。只有任务 RUNNING 且在 agent 阶段可用，否则 409。这条用户消息 `steer=true`，会出现在对话里；不新开任务（这是「同一时间一个修改」的唯一例外） |
| — | 事件里的字符串会去掉 NUL（`\u0000`）等控制字符：命令输出里偶尔会带，Postgres 的 jsonb 不接受，会导致整条事件写不进去 |
| GET | `/api/tasks/{id}/events/{seq}/detail` | → 该事件的完整详情（思考全文或工具详情）。初次补发为了控制体积会省略（`textOmitted` / `detailOmitted`），展开时按需加载 |
| GET | `/api/tasks/{id}/files` | 任务进行中读取工作区文件 → `{ path, size }[]`；任务结束后 409，改用版本接口 |
| GET | `/api/tasks/{id}/file?path=` | 任务进行中读取文件内容 → `text/plain` |

### 问答（不创建应用）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET/POST | `/api/sources` | 列出本工作空间可见的数据源（含平台登记）/ 登记：`{ key, name, description, method, url, params, sample, authType, authHeader?, secret? }` → `DataSource`（不返回凭证，只有 `hasSecret`）。`authType` 还支持 `agw`（此时 `authHeader` 为调用项目 AppKey，`secret` 为 AppSecret）。规则见 docs/data-sources.md |
| GET | `/api/sources/agw/apis?q=&page=0&size=20` | 需平台令牌。查询 API 网关已启用且已上架的目录 → `{items,totalElements,totalPages,number,size}`；每项含公开名称、方法、路径、提供方、参数数、可导入状态和 `importedSourceId` |
| POST | `/api/sources/agw/apis/{apiId}/import` | 需平台令牌。`{ appKey?, appSecret? }`；首次导入必填，再次导入留空保留原凭证。服务端重新读取 API 详情并幂等写入当前工作空间数据源 |
| PATCH/DELETE | `/api/sources/{id}` | 修改（不传 `secret` 保留原凭证）/ 删除（同时解绑）；平台登记的数据源 → 403 |
| POST | `/api/sources/{id}/test` | `{ params }` → `{ data, status }`；`GET /api/sources/{id}/calls` 最近 20 次调用 |
| GET/POST/DELETE | `/api/apps/{id}/sources[/{sourceId}]` | 应用绑定的数据源；POST `{ sourceId }` 绑定（最多 100 个，调用名不能重复）。创建应用 / 会话转应用时也可带 `sourceIds` |
| POST | `/cloud/{appId}/source/{key}`、站点 `/__zhiye/source/{key}` | 应用运行时调用：`{ params }` → `{ data, status }`。预览带应用令牌；发布站点同源、只接受 JSON |
| POST | `/api/chat/ask` | `{ content, skillAppIds? }` → SSE：`delta`（`{text}`）/ `done` / `error`（`{message}`）。只是回答问题，不创建应用、不落库；`skillAppIds` 是自己的工作空间里已生成的 skill 应用（最多 5 个），会把技能包里的 Markdown/脚本内容作为上下文带给模型；同时带上织页内置技能目录，模型可用只读工具 `read_skill` 读取内置技能后再回答。资源不可用 → 503 `ASK_UNAVAILABLE`。创造页在识别到「这是一句问题」时调用它 |

### 云服务（每个应用一份独立 schema + 存储前缀）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/apps/{id}/cloud` | → `{ enabled, status, schema, tables: [{ name, columns, policy }], storagePrefix }`。按工作空间鉴权 |
| POST | `/api/apps/{id}/cloud/enable` | 启用：建 schema、按当前版本的 `cloud/schema.sql` 建表（没有就建一张默认 `notes(title, content)` 表），→ 同上 + 一次性 `token` |
| POST | `/api/apps/{id}/cloud/disable` | 关闭（数据保留）→ `{ status: "off" }` |
| POST | `/api/apps/{id}/cloud/token` | 重新取一个 24 小时的令牌 → `{ token, expiresIn }` |

数据面（应用运行时调用，只认 `Authorization: Bearer zt.{appId}.{exp}.{sig}`，HMAC 签名、过期失效）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/cloud/{appId}/db/{table}?limit=&offset=&order=&<字段>=` | → `{ rows, total }`，最多 200 行；未知字段 → 400。`order` 单列 `-col` 倒序，多列逗号分隔、每列可单独加 `-`（如 `-report_date,line`） |
| POST | `/cloud/{appId}/db/{table}` | 插入一行 → 该行（含 id） |
| PATCH | `/cloud/{appId}/db/{table}/{id}` | 更新 → 该行 |
| DELETE | `/cloud/{appId}/db/{table}/{id}` | 删除 → 204 |
| POST | `/cloud/{appId}/storage?path=` | 上传原始字节（≤10 MB）→ `{ path, size }` |
| POST | `/cloud/{appId}/storage/upload` | multipart 上传，字段 `file` |
| GET | `/cloud/{appId}/storage?path=` | 下载文件字节 |
| GET | `/cloud/{appId}/storage` | 列出文件路径 |

- `cloud/schema.sql` 只允许 `create table` / `alter table add column` / `create index`；字段类型白名单：`text/int/integer/bigint/real/double precision/boolean/date/timestamp/jsonb`；表名/列名只能小写字母、数字、下划线。系统自动加 `id`、`created_at`、`updated_at`。
- 预览页（`/p/{buildId}/`）会自动注入 `window.__ZHIYE_CLOUD__` 和 `/cloud/sdk.js`（令牌 30 分钟有效）；**发布的站点不注入令牌**。
- SDK：`window.zhiye.cloud.db.{list,insert,update,remove}`、`window.zhiye.cloud.storage.{upload,download,list}`。

### 版本与历史
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/apps/{id}/versions` | → `Version[]`（编号降序） |
| GET | `/api/history?q=&appId=&filter=all\|favorite\|published` | → `HistoryRow[]`（时间降序，前端按日期分组） |
| PATCH | `/api/versions/{id}` | `{ favorite }` → `Version` |
| POST | `/api/versions/{id}/restore` | → 新 `Version`（source=restore）。是当前版本 → 409 `ALREADY_CURRENT`；有草稿 → 409 `DRAFT_PENDING` |
| GET | `/api/apps/{id}/versions/{versionId}/changes` | → `{ base: versionId\|null, files: [{ path, status: 'A'\|'M'\|'D'\|'R', oldPath?, added, removed, binary }], totalAdded, totalRemoved }`：与上一版本比较（第一个版本与空比较），用于「查看改动」 |
| GET | `/api/apps/{id}/versions/{versionId}/changes/file?path=` | → `{ path, status, diff, truncated }`：单个文件的 unified diff（最多 300 KB） |
| GET | `/api/versions/{id}/files` | → `{ path, size }[]` |
| GET | `/api/versions/{id}/file?path=` | → `text/plain` 文件内容 |
| GET | `/api/versions/{id}/export` | → 源码 zip 下载；skill 应用的 zip 只有一个顶层目录，名称取 SKILL.md 头部的 `name`（解压即可安装） |
| GET | `/api/versions/{id}/page` | → 单文件 HTML 下载（脚本和样式内联）；skill 应用 → 400 `SKILL_NOT_PUBLISHABLE` |

### 可视化编辑草稿
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/apps/{id}/draft` | → `Draft`，没有 → `null`（HTTP 200，应用不存在仍 404） |
| PUT | `/api/apps/{id}/draft` | `{ baseVersionId, patches }` → `Draft`。`baseVersionId` 不是当前版本 → 409 |
| DELETE | `/api/apps/{id}/draft` | 放弃草稿 → 204 |
| POST | `/api/apps/{id}/draft/commit` | `{ title? }` → `Task`（visual_save） |

### 发布
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/apps/{id}/publish` | `{ versionId? }`（默认当前版本）→ `{ siteUrl, versionId }`。有草稿 → 409 `DRAFT_PENDING`（前端先提交草稿、等任务成功后再发布）；skill 应用 → 400 `SKILL_NOT_PUBLISHABLE` |
| POST | `/api/apps/{id}/unpublish` | → 204 |

### 导入代码并部署
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/imports/upload` | multipart，字段 `file`（.html/.htm/.zip；文件夹由前端用 fflate 打成 zip，字段 `folderName` 传原文件夹名）→ `ImportManifest` |
| POST | `/api/imports` | `{ uploadId, name, type? }` → `{ app: AppSummary, task: Task }`。任务成功前应用不出现在列表中；取消或失败不留下应用 |

### 素材
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/assets` | multipart `file`（图片、PDF、文本，单个最多 10 MB）→ `Asset` |
| GET | `/api/assets` | → `Asset[]`（素材中心） |
| GET | `/api/assets/{id}/content` | 文件内容 |


## AI 修改与手动修改的规则

目标：AI 修改（对话）和手动修改（可视化编辑草稿、代码编辑）永远不会互相覆盖或悄悄合并。

1. **同一应用的修改串行执行。** 应用有任何进行中的任务（plan/generate/modify/visual_save/code_save/import）时，以下接口返回 409 `APP_BUSY`：`PUT/DELETE /draft`、`POST /draft/commit`、`POST /code`、`POST /messages`、`POST /versions/{id}/restore`、`POST /publish`。AI 运行期间不会收到任何手动补丁。
2. **有草稿时，对话修改先保存手动修改，作为独立版本。** 创建 modify 任务时把草稿快照进任务输入。任务分两步：
   - 第一步（手动）：状态「正在保存你的手动修改」→ runner `visual` 写回草稿 → 校验 → 提交版本「可视化编辑 · N 处修改」（source=visual）→ 清空草稿；
   - 第二步（AI）：在这个新版本上执行 AI 修改 → 提交下一个版本（source=modify）。
   用户得到两个版本，可以分别回滚。`Task.resultVersionId` 是 AI 修改的版本。
3. **失败与停止：**
   - 第一步失败（runner 拒绝补丁或校验失败）：任务 FAILED，AI 不开始，**草稿原样保留**，不产生版本；错误信息与对话里的系统消息为「你的手动修改没能保存：{原因}。AI 修改没有开始，手动修改仍保留在草稿中。」，用户消息标记为 failed。
   - 第一步已提交、第二步失败或被停止：手动修改版本 V{n} 保留；任务 FAILED / CANCELLED，信息为「你的手动修改已保存为 V{n}；AI 修改没有完成：{原因}」。重试 / 继续从 V{n} 开始，不再写回草稿。
   - 第一步进行中被停止：不产生版本，草稿保留。
4. **元素位置重映射。** 写回草稿可能让行号偏移：runner `visual` 回复 `locMap: { "旧 loc": "新 loc" | null }`，Java 在交给 agent 之前改写 `elementContext.loc`；元素被草稿删除（`null` 或 `removed` 补丁）时明确告诉 agent。指令里总是带元素文字和 outerHTML，并注明「行号可能因手动修改有偏移，请以元素内容为准」。
5. **代码编辑与草稿。** 有草稿时 `POST /code` → 409 `DRAFT_PENDING`；`PUT /draft` 的 `baseVersionId` 不是当前版本 → 409 `VERSION_MISMATCH`。`AppDetail.hasDraft` / `draftBaseVersionId` 用于显示草稿状态（基线不是当前版本时提示草稿已过期）。

## 4. 预览编辑桥协议（iframe ↔ 工作台）

编辑桥脚本只注入在**预览版构建**中。所有消息使用 `window.postMessage`，对象带 `channel: 'zhiye-editor'`。工作台只处理 `event.source === iframe.contentWindow` 的消息；编辑桥只处理 `event.source === window.parent` 的消息。

### 工作台 → iframe
| type | 字段 | 说明 |
|---|---|---|
| `toggle` | `enabled: boolean`, `loc?: string` | 开关编辑模式；`loc` 为要恢复选中的元素 |
| `apply-patches` | `patches: Patch[]` | 用完整的草稿重新渲染预览中的修改（撤销/重做后发送完整列表） |
| `select-parent` | — | 选中当前元素的父元素 |
| `deselect` | — | 取消选中 |

### iframe → 工作台
| type | 字段 | 说明 |
|---|---|---|
| `ready` | `editable: boolean` | 页面加载完成；`editable=false` 表示页面中没有 data-loc（不可可视化编辑） |
| `selected` | `selection: Selection` | 单击选中元素 |
| `selection-position` | `selection: Selection` | 滚动/尺寸变化后选框位置更新 |
| `deselect` | — | 按 Esc 或选中被删除 |
| `inline-edit` | `loc: string`, `text: string`, `index: number`, `match: string` | 双击行内改字完成（回车或失焦） |

```ts
interface Selection {
  loc: string; tag: string; text: string;
  canEditText: boolean;     // 可以修改文字（纯文字元素，或带控件的标签只改自身文字）
  canInlineEdit: boolean;   // 可以双击直接改字（纯文字元素）
  dynamic: boolean;         // 文字来自变量/表达式，不能直接改字，只能「交给 AI 修改」
  sameLocCount: number;     // 同一 loc 渲染出的元素数量（>1 表示列表项，修改会影响所有同类项）
  instanceIndex: number;    // 被点击元素在同 loc 实例中的位置（DOM 顺序，0 起）
  instanceText: string;     // 该实例未被补丁修改前的文字（≤200 字）
  href: string | null;
  styles: Record<string, string>;   // 计算样式（fontSize、fontWeight、fontStyle、textDecoration、textAlign、color、backgroundColor、borderRadius、padding、lineHeight、paddingTop、paddingRight、paddingBottom、paddingLeft、marginTop、marginRight、marginBottom、marginLeft、opacity、display）
  rect: { left: number; top: number; width: number; height: number };   // 相对 iframe 视口
  parentLoc: string | null;
  outerHTML: string;        // 截断到 2000 字符
}
```

编辑模式关闭时页面完全恢复正常交互（按钮、表单可用）。编辑桥自身在页面里绘制蓝色选框和悬停虚线框；浮动工具条和属性面板由工作台在 iframe 外层根据 `rect` 定位绘制。


## 会话与任务队列（2026-11-13 新增）

- `POST /api/conversations` → `{id,title,createdAt,updatedAt}`；`GET /api/conversations` 列表；`GET /api/conversations/{id}` 含 messages；`DELETE` 软删。
- `POST /api/conversations/{id}/ask` → SSE（delta/done/error），先落 user 消息，完成后落 assistant 消息。
- `POST /api/conversations/{id}/build` → `{type?,mode?,model?,assetIds?,skillAppIds?}`，用整段对话（含 assistant 结论）创建应用，返回应用详情。
- `POST /api/chat/polish {content}` → `{text}`。
- `GET /api/apps/{appId}/queued`、`POST /api/tasks/{taskId}/queued {content}`、`DELETE /api/queued/{id}`：生成中补充消息排队，任务结束后按顺序自动执行。
- `POST /api/apps/{id}/plan-feedback {content}`：计划阶段把补充追加到 planDoc 的「补充反馈」段。
- `GET /api/history/summary` → `{versions,apps,published,cloudEnabled,pageViews}`；`GET /api/apps/trash` 回收站列表。
- 任务被停止时，工作区有改动会生成 source=`interrupted`、标题「任务中断前自动保存」的版本（不改变 currentVersion）。

## 2026-11-14 增补：继续检查点、额度、停止回答、登录失效

### 任务失败码（`Task.error.code`）

| code | 含义 | 重试 |
|---|---|---|
| `PAUSED` | 步数检查点上选了「先到这里」，或 `continue-wait-minutes`（默认 30）内没回应 | 接着做（`resume.continueWork=true`，发 `Prompts.resume`） |
| `TIMEOUT` | 一段运行（两次「继续」之间）超过 `max-duration-minutes`（默认 60），多半是执行环境卡住 | 接着做 |
| `PROVIDER_QUOTA` | 五星AI岛个人额度用完 / 上游余额不足（402），文案带重置时间 | 额度恢复后接着做 |
| `MODEL_ERROR` | 模型服务错误（含网关 401/5xx） | 原样重发 |
| `SANDBOX_FAILED` / `worker_lost` | 沙箱起不来 / 执行它的实例退出（租约过期） | 原样重发 |

任务**不限执行步数、不设 token 上限**：runner 每做 `max-agent-turns`（60）步返回 `TURN_LIMIT`，服务端不判失败，而是发 `kind=continue` 的确认卡，任务保持 `RUNNING`，`phaseLabel` 为「等你确认是否继续」。旧数据里可能还有 `TOO_LARGE`（已不再产生），重试同样接着做。

### 模型与额度（`meta.fivestar=true` 时，见 docs/fivestar-integration.md）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/llm/models` | → `{ source: "fivestar" \| "local", defaultId, models: [{ id, label, desc, provider?, multimodal }] }`。五星模式下是员工在五星AI岛配置的模型（`id` = `供应商ID:模型ID`，另有 `auto`=五星默认）；关闭时只有本地统一上游的 `auto`。五星模式未登录 401 `PLATFORM_TOKEN_MISSING` |
| GET | `/api/llm/quota` | 个人额度 → `{ enabled, packageName, status, usedTokens, limitTokens, remainingTokens, resetAt, usableModels }`（取自五星AI岛 `tokenCostDetail/usage`）；`enabled=false` 表示没开五星对接，前端不显示额度行 |

### 会话停止回答

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/conversations/{id}/ask/stop` | → `{ finished }`。`false`：已停下，只保存用户看到的部分（加「（已停止回答）」，去掉 `[[build]]` 标记）；`true`：回答其实已经完整保存（停止来晚了），前端应重新加载会话。回答不在本实例上时按数据库最后一条判断；浏览器断开流时回答所在实例也会停下并保存已显示的部分 |

前端：发送后 1 秒内「停止回答」按钮是禁用态（它在发送按钮的位置，连点会误停），小织的回车同理。

### 登录失效（只在 `zhiye.auth.mode=sso`）

| HTTP | code | 前端处理（`api/client.ts onAuthExpired`） |
|---|---|---|
| 401 | `UNAUTHORIZED` | 织页会话过期 → 整页跳 `/login?error=expired&redirect=当前页`，提示「登录已过期…草稿都还在」；登录后回到原页面 |
| 401 | `PLATFORM_TOKEN_INVALID` | 织页会话还在、平台令牌过期 → 清掉本地令牌，整页走 `/api/auth/sso/login` 换新令牌 |
| 401 | `PLATFORM_TOKEN_MISSING` / `LOGIN_REQUIRED` | 只提示，不跳转（例如免登录工作空间想用五星模型） |

同一页面同时多个 401 只跳一次。草稿都在 localStorage（`zhiye.draft.*`），重新登录不丢。用例：`e2e/qa-auth-expiry.mjs`。

## 系统全景统计看板（2026-11 新增）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/dashboard?range=today\|7d\|30d\|90d\|all&startDate=YYYY-MM-DD&endDate=YYYY-MM-DD&workspaceId=&userId=` | 平台管理侧全景统计看板：支持按预设/自定义时间段与按人（`userId`）/按工作空间（`workspaceId`）下钻筛选，返回人员目录、KPI 环比、转化漏斗、逐日趋势（含成本）、24h 时段分布、工作空间/用户排行、应用资产、任务效能与失败诊断、Agent 工具/质检门禁/确认卡遥测、模型/Token 成本、版本部署、云服务与云函数、数据源与 AGW、会话/素材/小织反馈、技能生态与社区、运行期监控 |

## 平台治理与运维（2026-11 新增）

当开启 SSO 且配置 `zhiye.admin.allow-login-names` 时，仅白名单内的登录名可调用 `/api/admin/**`（否则返回 `403 FORBIDDEN`）；所有写操作均写入 `admin_audit_log` 审计表。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/overview` | 聚合返回系统默认配额、自定义配额工作空间列表、用户反馈列表、回收站已删应用、灵感社区作品、限流触发统计、最近 60 条管理审计日志 |
| PUT | `/api/admin/workspaces/{wsId}/quota` | 设置或重置（`{ "reset": true }`）指定工作空间的 `maxApps` / `dailyTaskLimit` / `maxConcurrentTasks` 与备注 |
| POST | `/api/admin/apps/{id}/unpublish` | 强行下线已发布站点（不删除应用本体，清理活跃部署状态） |
| POST | `/api/admin/apps/{id}/restore` | 从回收站恢复被软删除的应用 |
| DELETE | `/api/admin/apps/{id}` | 管理员软删除应用（联动下线站点与草稿，存在进行中任务时返回 409） |
| POST | `/api/admin/tasks/{id}/cancel` | 强行停止排队中或运行中的任务 |
| POST | `/api/admin/tasks/{id}/retry` | 运维重跑失败或已取消的任务 |
| POST | `/api/admin/templates/{id}/feature` | 设置或取消灵感社区作品的 `featured` 精选标志与 `pinnedOrder` 置顶权重（精选作品在社区列表优先展示） |
| DELETE | `/api/admin/templates/{id}` | 管理员从灵感社区下架并清理作品快照与压缩包 |
| PATCH | `/api/admin/feedbacks/{id}` | 更新用户反馈分诊状态（`open` / `in_progress` / `resolved` / `ignored`）与 `adminNote` 管理员备注 |
| POST | `/api/admin/rate-limits/clear` | 清理 `rate_limit_hit` 限流计数记录（可传 `{ "bucket": "..." }` 按桶清理或全清） |
