# 织页技术设计

织页（Zhiye）是 TCL华星内部的 AI 应用创作平台：员工用对话描述想要的应用，AI 出计划、写代码、自检、发布成可访问的内部站点；之后可以继续对话修改、直接编辑、看代码、管版本、开云数据库。本文是整体设计，细节约定见：

| 文档 | 内容 |
|---|---|
| `docs/api-contract.md` | 前端 ↔ 主服务的全部接口、错误码、编辑桥协议 |
| `docs/runner-protocol.md` | 主服务 ↔ 沙箱里的 runner（AI 编码代理）协议 |
| `docs/cloud-services.md` | 云数据库、存储、云函数、应用内 AI、访问控制 |
| `docs/data-sources.md` | 外部数据源登记、代理与安全规则 |
| `docs/sso.md`、`docs/fivestar-integration.md` | 统一登录；五星AI岛模型、额度与 llm-gateway 路由 |
| `docs/capabilities.md` | 能力边界（能做什么、做不到什么）、已知限制、可优化与扩展项 |
| `docs/deployment.md`、`deploy/k8s/README.md` | 部署（本地 / 容器 / K8s）与配置项 |
| `docs/testing.md` | 测试分层、命令、用例清单 |

## 1. 定位与边界

- **独立部署**的 Spring Boot 服务，不注册到平台服务、不依赖 metis；只参照五星AI岛的模块划分、配置与命名习惯。
- 生成的应用面向**内部员工**。每个应用可以单独选择访问级别：内网公开 / 员工登录 / 指定成员（见 `cloud-services.md`）。
- 平台本身可以免登录（匿名工作空间）或接统一登录（`zhiye.auth.mode=sso`）。接五星AI岛模型和额度时必须是 sso。
- 不做的事和替代做法统一写在 `Capabilities.java`（见 `capabilities.md`）。

## 2. 运行形态

```mermaid
flowchart LR
  U[浏览器<br/>织页前端 React] -- REST / SSE --> API
  U -- iframe 预览 / 站点 --> SITE
  subgraph 织页
    API[zhiye-api ×N<br/>profile api<br/>REST、SSE、模型网关、任务工作进程]
    SITE[zhiye-site ×N<br/>profile site<br/>预览 /p/{buildId}/、发布站点 {slug}.域名]
  end
  API -- 每任务一个 --> SBX[沙箱里的 runner<br/>Node + Pi 编码代理<br/>本地进程 / K8s Pod / OpenSandbox]
  SBX -- 模型调用（任务令牌） --> API
  API -- 上游 --> LLM[模型上游<br/>本地统一上游 或 llm-gateway 员工路由]
  API & SITE --> PG[(PostgreSQL<br/>控制面 + 每应用一个云 schema)]
  API & SITE & SBX --> NAS[(运行时目录（NAS）<br/>repos/ workspaces/ agents/ logs/)]
  API & SITE --> OBJ[(对象存储<br/>素材、构建产物 builds/、云存储<br/>本地目录 或 S3/OSS)]
```

- **同一个 jar 两个角色**：`api` 处理工作台的一切并运行任务；`site` 只托管预览和发布站点（与工作台不同源，iframe 不给 `allow-same-origin`），也承载发布站点的同源 `/__zhiye/*`（云数据库、存储、云函数、应用内 AI、数据源、站点登录）。
- **runner** 是 `runner/src/main.mjs`：在沙箱里跑 Pi 编码代理，按 `runner-protocol.md` 接收 `init / prompt / steer / confirm / verify / visual / import / shutdown` 等命令，事件经 SSE 回传。沙箱里没有任何真实模型密钥，模型调用走主服务的网关 `/llm/v1`（任务令牌鉴权，任务结束即失效）。
- 沙箱实现可切换（`zhiye.sandbox.provider`）：`local`（本机进程，macOS 用 sandbox-exec 限制）、`opensandbox`（生产，公司 OpenSandbox）、`k8s`（每任务一个 Pod，回滚路径）。

## 3. 代码结构

```
server/                          Maven 多模块（父工程 spring-boot-starter-parent，Java 17）
├── zhiye-common/                预留：跨模块共享的常量 / DTO（目前为空）
├── zhiye-api/                   预留：对外接口契约（如果以后有其他服务调用织页）（目前为空）
└── zhiye-server/  com.tcl.csot.zhiye
    ├── controller/              REST / SSE 入口（mock/ 下是外网开发用的模拟统一认证、五星AI岛）
    ├── service/                 按领域分包：app task conversation chat help version repo importer
    │                            cloud source skillhub template site workspace auth llm dev
    ├── repository/              JdbcClient 手写 SQL（不用 ORM）
    ├── entity/                  表行 record
    ├── sandbox/                 SandboxProvider：local / k8s / opensandbox；RunnerSession（HTTP 命令 + SSE）
    ├── client/                  外部调用：UpstreamClient（模型）、OpenclawClient、SsoClient、SkillHub
    ├── filter/                  SsoAuthFilter（登录）、SiteFilter（预览/站点/同源 /__zhiye/*）
    ├── config/ constants/ exception/ handler/ storage/ util/
    └── resources/db/migration   Flyway V1…V25
web/        React 19 + TypeScript + Vite + TanStack Query；pages/ 页面，workbench/ 工作台，editor/ 可视化编辑
runner/     AI 编码代理（Node 22，Pi），verify（tsc / 构建 / Chromium 冒烟）、visual 写回、import
packages/   生成项目的模板（React / Vue / 静态 / 脚本 / 技能）、内置技能、编辑桥、云函数宿主
deploy/     Dockerfile、K8s（Kustomize）、macOS 沙箱配置
e2e/        Playwright 端到端用例与脚本（见 docs/testing.md）
```

## 4. 核心流程

### 4.1 从一句话到应用

1. **创造页会话**：`POST /api/conversations/{id}/ask`（SSE）。模型先判断成品是文档还是应用、是否超出能力边界；聊清楚后回答以 `[[build:类型]]` 开头，前端调 `/build` 用整段对话建应用。
2. **计划任务（plan）**：生成需求文档（Markdown，可编辑）、设计风格、形态（React / Vue / 静态…）。超出单次能力时写「分期计划」，只做第一期。
3. **生成任务（generate）**：`PREPARING`（检出 git、准备工作区和 agent 目录）→ `RUNNING`（runner 编码，事件实时推到时间线）→ `VERIFYING`（tsc、两次构建（预览版带编辑桥 / 发布版）、Chromium 冒烟；失败自动交回 AI 修，最多 `verify-fix-rounds` 轮）→ `COMMITTING`（JGit 提交、生成版本、产物写入对象存储 `builds/{buildId}/preview|publish`）→ `SUCCEEDED`。
4. 之后的**修改（modify）**、**可视化保存（visual_save）**、**代码保存（code_save）**、**导入（import）**、**复刻模板（clone）**都是同一套任务机制，只是步骤不同。

### 4.2 任务语义

- 每个工作空间同时只有 1 个写任务（`TASK_LIMIT`）；生成中发的新消息进**排队**（`task_message_queue`），任务结束后按顺序执行；也可以作为「补充要求」（steer）直接送进当前 agent 会话。
- **不限执行步数、不设 token 上限**。runner 每 60 步回 `TURN_LIMIT`，`TaskExecutor.awaitContinue` 发「要继续吗？」确认卡：点「继续」或在输入框说话（可带补充要求）→ 在同一会话里接着做、重新计时；「先到这里」或 30 分钟没回应 → `PAUSED`，已做的保留。一段运行（两次继续之间）超过 60 分钟判 `TIMEOUT`（防卡死）；沙箱存活上限 8 小时。
- **停止**：prompt 期间先 abort，15 秒没退出就杀进程；工作区有改动会存一个「任务中断前自动保存」版本（不设为当前版本）。
- **重试**：`PAUSED / TIMEOUT / PROVIDER_QUOTA`（以及旧数据的 `TOO_LARGE`）接着做，不重做已完成部分；其他失败原样重发。
- 额度用完（五星AI岛个人额度、上游 402）统一成 `PROVIDER_QUOTA`，提示重置时间。

### 4.3 版本、构建、预览、发布

- 每个应用一个 git 仓库（`repos/{appId}`，JGit），每个版本对应一个提交；恢复旧版本 = 新建一个内容相同的版本，不改历史，也不改线上版本。
- 构建产物按应用隔离（`build.app_id`）：预览 `/p/{buildId}/`（注入编辑桥和预览令牌），发布 = 把某个版本的发布版产物挂到 `{slug}.站点域名`。只有「发布」会改变线上版本。
- 可视化编辑：编辑桥在 iframe 里给元素打源码位置（`vite-plugin-loc`），修改先存草稿（`/draft`），保存时由 runner 的 `visual` 命令写回源码。

### 4.4 模型调用

- 所有调用经 `LlmRouter` 取路由（本地统一上游，或员工在 llm-gateway 上的路由：虚拟密钥 + `X-User-Id` 工号 + `X-Project-Id: zhiye`），再由 `UpstreamClient` 发送；429/402 额度错误不重试。
- runner 的调用经主服务 `GatewayController`（`/llm/v1`）：用任务令牌找到任务，再按任务的模型选择转发。密钥只在主服务进程里。

## 5. 任务调度与多实例

- **领取与租约**：`TaskWorker` 轮询 `task` 表领取（`lease_owner` + `lease_until`，每 10 秒续约）；实例挂掉 → 租约过期 → 其他实例的回收器把任务标成 `worker_lost`（可重试）。
- **事件**：`TaskEvents` 把事件写入 `task_event`，SSE 接口从数据库按 seq 推送，所以前端连到哪个实例都能看到完整进度（只有「思考过程」的逐字增量走本机内存，其他实例上按落库批次显示）。
- **跨实例信号**：
  - 停止：写 `CANCELLING` 状态，执行任务的实例心跳（1 秒）发现后停止；
  - 确认卡（启用云服务 / 继续）与补充要求：收到请求的实例不在执行该任务时，写一条 `task_signal`（确认卡按 `confirm:{id}` 去重），执行实例心跳取走后交给 agent。测试 `TaskSignalRelayTest`。
- **仍是单实例内存的**（多副本时的影响已经处理成可接受的退化）：
  - 会话回答流：停止请求落在别的实例时按数据库判断；浏览器断开后，回答所在实例停下并保存已显示部分（`ConversationStopTest`）；
  - 小织、应用内 AI 的限流计数按实例算（N 个副本 ≈ N 倍额度），需要严格限流时改成数据库或 Redis 计数；
  - 实时文件视图优先用本机路径，回落到共享卷 `workspaces/{taskId}`。

## 6. 身份与权限

- **工作空间**：匿名模式下 `zhiye_ws` cookie 就是身份；丢了可以用恢复链接（`/recover#token`）找回。sso 模式下工作空间挂在员工账号上，`zhiye_session` cookie（7 天）。
- **平台令牌**：统一认证回调拿到的 `access_token` 换织页会话，同时由前端保存，调用 `/api` 时放 `Authorization`，用于以员工身份查五星AI岛模型和额度。会话或令牌过期 → 前端整页引导重新登录，草稿保留（`api-contract.md` 末节）。
- **生成的应用**：预览用应用令牌 + 预览身份（admin / member / anon）；发布站点不放令牌，走同源 `/__zhiye/*` + 站点会话。表级权限预设（如「自己提交、管理员看全部」）在服务端执行。

## 7. 云服务、数据源、技能、模板

- **云服务**（`cloud-services.md`）：每个应用一个 PostgreSQL schema（最多 50 张表，`cloud/schema.sql` 声明，任务成功后自动建表补列）、对象存储前缀、Deno 云函数（`packages/fn-runtime`）、应用内 AI（扣作者的额度）。由 agent 用 `request_confirm` 申请，用户在确认卡上同意才启用。
- **数据源**（`data-sources.md`）：工作空间登记 HTTP 接口（凭证只存服务端），应用通过 `zhiye.source.query` 调用，服务端代理、限制主机白名单和响应大小。
- **技能**：内置技能（`packages/builtin-skills`）+ 用户自己做的技能应用（projectKind=skill）；按消息选用，放进 agent 目录供模型读取。SkillHub 对接见 `capabilities.md` 待办。
- **模板与社区**：创造页场景模板（`web/src/lib/templates.ts`，含 9 个看板模板）；社区作品可「使用模板」复刻成自己的应用（clone 任务）。
- **小织**：右下角帮助助手（`HelpService` + 离线 FAQ），产品知识两处同步（`HelpPrompt.java`、`helpFaq.ts`）。

## 8. 数据库

Flyway 迁移 `V1…V25`，控制面的表：工作空间与应用 `workspace`、`app`、`version`、`build`、`deployment`、`draft`、`checkpoint`、`asset`、`upload`；任务 `task`、`task_event`、`task_token`、`task_signal`、`task_message_queue`、`message`、`model_usage`；会话 `conversation`、`conversation_message`、`conversation_file`、`app_conversation`；登录 `app_user`、`user_session`；站点 `app_site_access`、`site_session`、`site_sso_code`、`app_pageview_daily`；云服务 `app_cloud`、`app_cloud_table`、`app_fn_log`（每个应用的数据另在独立 schema 里）；数据源 `data_source`、`app_data_source`、`data_source_call`；技能与社区 `skill_publication`、`template`、`template_like`；小织 `help_conversation`、`help_message`、`help_feedback`。约定：snake_case 列，JSON 用 jsonb，时间 timestamptz；新表只加迁移，不改老迁移。

## 9. 安全要点

- 模型密钥、虚拟密钥、数据源凭证、云服务签名密钥只在服务端；不进浏览器、不进沙箱、不进生成的应用。
- 沙箱：非 root、只挂本任务两个目录、出网默认拒绝（只放行模型网关，导入任务另放行 npm）。
- 预览与站点和工作台不同源；iframe 不给 `allow-same-origin`；站点 HTML 不放令牌。
- 数据源代理生产禁止回环地址、只放行白名单主机；云表 SQL 按列类型绑定参数。
- 不用浏览器原生 `confirm / prompt`（被用户屏蔽后会静默失败），统一用页面内确认框。
