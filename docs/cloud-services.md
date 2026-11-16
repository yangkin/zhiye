# 云服务（数据库、文件存储、访问控制、云函数、应用内 AI）

生成出来的应用只有前端代码，加上一份数据、存储和权限声明。后端能力由平台统一托管，**不给每个应用起服务端进程**。
预览和发布后的站点都能用；真实的数据库凭据、模型密钥永远不下发到浏览器。

## 1. 能力范围

| 能力 | 前端用法（`window.zhiye`） | 说明 |
| --- | --- | --- |
| 数据库 | `cloud.db.list/insert/update/remove/increment/aggregate` | 每个应用一个 schema `app_{appId}`；表在 `cloud/schema.sql` 声明 |
| 文件存储 | `cloud.storage.upload/download` | 按应用隔离的对象存储前缀 |
| 当前用户 | `user`（`{id, loginName, name, email, admin}`，未登录为 null）、`auth.login()/logout()` | 发布后按访问设置走公司统一登录 |
| 数据源 | `source.query(name, params)` | 公司内部只读接口，见 `docs/data-sources.md` |
| 云函数 | `fn.call(name, args)` | `functions/{name}.ts`，Deno 沙箱执行 |
| 应用内 AI | `ai.chat({ messages, system?, images?, json? })` | 平台代理模型调用 |

明确不支持：自建服务端（Express / Spring Boot 等）、自建 MySQL / Redis / 消息队列、定时任务。

## 2. 两种调用方式

| 场景 | 地址 | 身份 |
| --- | --- | --- |
| 预览（`/p/{buildId}/`） | `/cloud/{appId}/...`、`/cloud/{appId}/fn/...`、`/cloud/{appId}/ai/chat` | 页面注入的短期应用令牌；预览路由栏可切换模拟身份：管理员（作者本人）/ 普通员工 / 未登录 |
| 发布站点（应用子域名） | 同源 `/__zhiye/db/...`、`/__zhiye/storage...`、`/__zhiye/fn/...`、`/__zhiye/ai/chat`、`/__zhiye/source/...`、`/__zhiye/me` | 站点会话 cookie（host-only，只在这个应用的子域名上有效）；**HTML 里不放任何令牌** |

SDK 是 `web/public/cloud/sdk.js`，由 `SiteFilter` 按需注入（启用了云服务、绑定了数据源、或构建产物里用到 `zhiye.ai` / `zhiye.fn` 时）。

## 3. 发布访问设置与登录（`app_site_access`，V16 迁移）

- 访问级别：`public` 内网公开（默认）/ `login` 员工登录 / `members` 指定人员（登录名名单，最多 500 人）。
- 管理员 = 作者 + 管理员名单；权限预设里的「管理员」指这些人。
- 非 `public` 级别需要平台接入了统一登录（`zhiye.auth.mode=sso`，见 `docs/sso.md`），否则接口返回 409 `SSO_UNAVAILABLE`。
- 登录流程：站点 `/__zhiye/auth/login` → 平台 `/api/auth/site/start?app=` → 公司 SSO → 平台发 60 秒一次性码（`site_sso_code`）→ 站点 `/__zhiye/auth/finish` 换成站点会话（`site_session`，7 天，库里只存哈希）。退出：`/__zhiye/auth/logout`。
- 发布前检查：表的预设需要识别身份（`own`、`own_admin_read`、`admin_write`），而访问级别是「内网公开」时，拒绝发布并提示改访问设置。

## 4. 表结构与权限规则

- `cloud/schema.sql`：只允许 `create table` / `alter table add column` / `create index`；类型 `text/int/bigint/real/numeric/boolean/date/timestamp/timestamptz/jsonb`（varchar 按 text 处理）。
  任务或代码保存成功后自动补建新表新列，已有的列不删也不改类型。每张表自动带 `id`、`created_by`、`created_by_name`、`created_at`、`updated_at`、`updated_by`。
- `cloud/policies.json`（`CloudPolicy` 解析和校验，在接口层执行）：

  ```json
  { "tables": { "orders": {
      "preset": "own_admin_read",
      "fields": { "qty": { "required": true, "min": 0 }, "code": { "unique": true }, "status": { "enum": ["待审", "通过", "驳回"] } },
      "transitions": { "status": [ { "from": "待审", "to": ["通过", "驳回"], "by": "admin" } ] } } } }
  ```

  | 预设 | 含义 |
  | --- | --- |
  | `shared`（默认） | 所有人可看可改 |
  | `admin_write` | 所有人可看，管理员可改 |
  | `own` | 每人只看和改自己的 |
  | `own_admin_read` | 自己提交，管理员看全部 |
  | `anon_submit` | 免登录可提交，管理员查看 |

  `transitions.by`：`anyone` / `admin` / `owner` / `function`（只允许云函数改）。新记录的状态只能是流程起点。
- 错误：未知字段、类型不符、违反规则返回 400（中文说明，`code` 如 `CLOUD_BAD_VALUE`）；401 需要登录，403 没有权限。
- 数据控制台（工作台「云服务」→ 数据 / 文件 / 函数 / 访问设置）：表格查看与编辑、CSV / Excel 导入导出、清空测试数据、切换预设、文件管理、函数调用记录。接口在 `CloudConsoleController`（`/api/apps/{id}/cloud/console/...`、`/api/apps/{id}/access`）。

## 5. 云函数（`FunctionService`）

- 代码：`functions/{name}.ts`，默认导出 `async (args, ctx) => 返回值`，跟着版本走；内置技能 `cloud-functions` 给出写法。
- 执行：每次调用起一个 Deno 进程（宿主脚本 `packages/fn-runtime/host.ts`，路径配置 `zhiye.functions.deno`）。不能读写文件、读环境变量、起子进程；网络只能访问平台的 `/fnapi`（带本次调用的临时令牌）。
- `ctx`：`db`（含 `get`、`transaction` 原子事务；不受预设限制，但字段规则和状态流转照样生效）、`storage`、`source`、`ai`、`log`、`user`。
- 限制：10 秒、128 MB、返回最大 1 MB。每次调用写一条记录；对话修改时最近的报错会附进指令（「读取云函数报错日志」）。

## 6. 应用内 AI（`AppAiService`）

- 复用平台的上游模型客户端，密钥只在服务端。
- 单次限制：最多 40 条消息、6 万字、4 张图（单张 4 MB）、`max_tokens` 最大 4096；每个应用每分钟最多 60 次（超过返回 429 `AI_RATE_LIMITED`）。支持流式。
- 内置技能 `app-ai` 给出摘要、分类、字段抽取、看图识别的写法。

## 7. 涉及的代码

| 位置 | 内容 |
| --- | --- |
| `server/.../cloud/CloudService.java` | 控制面（启用、表结构应用）+ 数据面（CRUD、聚合、存储） |
| `server/.../cloud/CloudPolicy.java` | `policies.json` 解析、预设、字段规则、状态流转 |
| `server/.../cloud/AccessService.java` | 访问级别、管理员、预览身份、站点会话、一次性登录码 |
| `server/.../site/SiteFilter.java` | 预览 / 发布站点、SDK 注入、同源 `/__zhiye/*`、登录拦截 |
| `server/.../cloud/FunctionService.java`、`FnApiController.java` | 云函数执行与 `ctx` 回调接口 |
| `server/.../cloud/AppAiService.java` | 应用内 AI 代理 |
| `web/src/workbench/CloudConsole.tsx` | 数据控制台与访问设置 |

测试：`CloudSchemaTest`、`CloudAccessTest`、`CloudSiteLoginTest`（`support/FakeSsoController`）、`CloudFunctionTest`、`AppAiTest`、`ConfirmCardTest`。
