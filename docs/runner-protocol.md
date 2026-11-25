# agent-runner 协议（Java 主服务 ↔ 沙箱中的 runner）

runner 是 `zhiye/runner/` 下的 Node 程序，每个任务启动一个进程。Java 是唯一的裁判：runner 只执行命令、上报事件和结果；任务成败、版本创建、重试轮数都由 Java 决定。

## 1. 启动

runner 是 HTTP 服务（`runner/src/control-server.mjs`），每个任务一个进程。三种启动方式，协议完全相同：

- **生产（`zhiye.sandbox.provider=opensandbox`）**：runner 镜像以 `/opt/zhiye/bin/start-runner.sh` 作为 OpenSandbox 沙箱的 entrypoint 启动，监听 `0.0.0.0:8790`；工作进程通过平台 Ingress Gateway 访问沙箱入口（入口地址由 OpenSandbox 返回）。
- **K8s 回滚（`zhiye.sandbox.provider=k8s`）**：沙箱 Pod 容器 sleep 占住，工作进程 exec 启动 runner（监听 `0.0.0.0:8790`，`zhiye.sandbox.k8s.control-port`），再通过 Pod IP 直连控制端口；NetworkPolicy `sandbox-ingress-api` 只放行 `zhiye-api` 访问该端口。
- **本地开发（`zhiye.sandbox.provider=local`）**：Java 在任务工作区里启动 `node runner/src/main.mjs`，只监听 `127.0.0.1:<随机端口>`。可选 `sandbox-exec` 包一层（配置 `zhiye.sandbox.seatbelt=false` 可关掉，便于调试）：

```bash
sandbox-exec -f zhiye/deploy/sandbox/runner.sb \
  -D WORKSPACE=<工作区> -D AGENT_DIR=<agent 目录> -D ZHIYE_ROOT=<zhiye 仓库根> -D NODE_DIR=<node 安装目录> -D ALLOW_NET=0|1 \
  <node> zhiye/runner/src/main.mjs
```

- 当前目录 = 工作区。
- 监听成功后 runner 往 stderr 打一行 `ZHIYE_RUNNER_READY port=<port>`。本地模式下 Java 解析这一行拿到端口，再轮询 `/v1/healthz` 等就绪；沙箱里端口固定（8790），入口地址由 OpenSandbox 返回。HTTP 服务启动前的错误（如缺少 `ZHIYE_RUNNER_TOKEN`）只写 stderr。
- 环境变量（Java 设置，**不含任何真实模型密钥**）：

| 变量 | 说明 |
|---|---|
| `ZHIYE_RUNNER_HOST` | 监听地址：沙箱里 `0.0.0.0`，本地 `127.0.0.1` |
| `ZHIYE_RUNNER_PORT` | 监听端口：沙箱里 `8790`（`zhiye.sandbox.opensandbox.control-port`），本地 `0` = 随机端口 |
| `ZHIYE_RUNNER_TOKEN` | 本任务的 runner 控制令牌（32 字节随机数的十六进制串），所有 `/v1/*` 请求的 `Authorization: Bearer`；缺失时 runner 拒绝启动（退出码 2） |
| `ZHIYE_TASK_ID` | 任务编号（日志和排查用） |
| `ZHIYE_GATEWAY_URL` | 模型网关的 OpenAI 兼容地址，如 `http://127.0.0.1:8080/llm/v1` |
| `ZHIYE_TASK_TOKEN` | 本任务的网关临时令牌 |
| `ZHIYE_LOC_PLUGIN` | `zhiye/packages/vite-plugin-loc/index.mjs` 的绝对路径 |
| `ZHIYE_BRIDGE_PATH` | `zhiye/packages/editor-bridge/bridge.js` 的绝对路径 |
| `ZHIYE_CHROME_PATH` | Chrome 可执行文件，冒烟测试用（本机 `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`） |
| `HOME` | 设为 agent 目录，避免读写用户主目录 |
| `PATH` | 只含 node 所在目录和 `/usr/bin:/bin`（沙箱镜像里为 `/usr/local/bin:/usr/bin:/bin`） |

## 2. 工作区（Java 准备好后再启动 runner）

```text
<workspace>/
├── 项目文件（基线版本的 git 检出，含 .git）
├── node_modules -> runtime/template-cache/node_modules   （template 项目的符号链接；vue 项目指向 runtime/vue-template-cache/node_modules；已在 .gitignore 中）
└── .zhiye/            构建产物和临时文件（已在 .gitignore 中）
    ├── dist-preview/  预览版（带 data-loc 和编辑桥）
    └── dist-publish/  发布版（干净）
```
导入任务的工作区一开始是空的（只有 `.git`），由 `import` 命令解包。

## 3. 传输：HTTP 命令 + SSE 事件流

runner 只有一个客户端（Java 工作进程），每个任务一个控制令牌。stdout 不再承载协议（runner 把 stdout 全部转到 stderr）。

### 3.1 端点

| 方法 / 路径 | 说明 |
|---|---|
| `POST /v1/commands` | 提交一条命令。body 是第 4 节的命令 JSON（必须带 `id`）。立即返回 `202 {"accepted":true}`，执行结果不在这里返回。 |
| `GET /v1/events?after=N` | 事件流（`text/event-stream`）。从序号 `N` **之后**开始推送；`after=0` 或省略表示从最早已保留的消息开始回放（历史已被丢弃时返回 409，见 3.3）。 |
| `GET /v1/healthz` | 就绪检查，返回 `200 {"ok":true,"pid":...}`。**不需要令牌**。 |

除 `/v1/healthz` 外，所有请求都要 `Authorization: Bearer <ZHIYE_RUNNER_TOKEN>`，否则返回 `401`（令牌按固定时间比较）。

### 3.2 帧格式与序号

SSE 帧：

```text
id: 17
data: {"type":"event","seq":17,"event":{"type":"turn","n":1}}
```

- 每条消息都有单调递增的 `seq`（从 1 开始），`id:` 与 JSON 里的 `seq` 相同。
- `type:"reply"`：`{ "id": "c1", "ok": true, "result": {...} }` 或 `{ "id": "c1", "ok": false, "error": { "code": "...", "message": "中文说明" } }`。`id` 对应命令的 `id`。
- `type:"event"`：任务事件，`{ "event": { "type": "...", ...payload } }`（见第 5 节）。
- `type:"log"`：runner 内部日志（原 stderr 的内容，包括命令行耗时、构建输出等），`{ "line": "..." }`。Java 把它写入任务日志文件。
- 每 15 秒一行 `: keepalive` 注释帧，Java 忽略注释行。
- 回复和它之前产生的事件在同一条有序流上，因此「收到某条回复」等价于「该命令之前的事件已全部处理完」。

### 3.3 断线续传与 409

runner 保留最近的消息用于回放（最多 4096 条 / 8 MB）。Java 记录最后处理的 `seq`，断线后带 `after=<lastSeq>` 重连。

- `after` 大于 runner 当前最新序号（客户端领先）→ `409 {"error":"seq ahead","latest":...}`。
- `after` 小于回放缓冲区起点减一（消息已被丢弃）→ `409 {"error":"replay buffer overrun","oldest":...,"latest":...}`。
- Java 收到任何 409 都直接判定会话失败（「事件回放缓冲区已失效，任务事件可能丢失」），不会静默丢事件。

### 3.4 并发与生命周期

- 除 `abort`、`steer`、`confirm` 外的命令进入 FIFO 队列，一次只执行一条。
- `abort`、`steer`、`confirm` 不进队列（bypass），可以在 `prompt` 执行期间直接执行：`abort` 立即中断当前轮，`steer` 在当前轮的工具调用结束后、下一次请求模型前送达，`confirm` 把用户在确认卡上的选择交给正在等待的 `request_confirm` 工具。
- `shutdown`（第 4 节）执行后 runner 释放会话、关闭 SSE 流并退出；退出过程中新的 `POST /v1/commands` 返回 `409 {"error":"runner 正在退出"}`。
- 请求体不是合法 JSON、不是 JSON 对象、缺少 `cmd` 或 `id` → `400`；命令体上限 1 MB，超过直接断开连接。
- 未知命令返回 `ok:false, code:"UNKNOWN_COMMAND"`；处理中的异常返回大写错误码（如 `BAD_STATE`、`BAD_REQUEST`、`INTERNAL`）。未捕获异常会以 `{ "type": "reply", "id": null, "ok": false, ... }` 通知客户端。

### 3.5 本地开发

本地与生产走完全相同的 HTTP + SSE 协议，只是地址为 `http://127.0.0.1:<port>`（runner 只监听回环）。Java 从 stderr 的 `ZHIYE_RUNNER_READY port=<port>` 行取得端口，再轮询 `/v1/healthz`；沙箱里端口固定为 `8790`，入口地址由 OpenSandbox 返回。

## 4. 命令

### `init`
```json
{ "cmd": "init", "agentDir": "...", "sessionFile": null,
  "model": { "provider": "kimi-coding", "id": "kimi-for-coding" },
  "thinking": "low|medium|high", "projectKind": "template|vue|static|vite|script|skill", "maxTurns": 60 }
```
创建 Pi 会话。`sessionFile` 不为空时从该会话文件恢复（「继续」）。Pi 的 `models.json` 写在 agentDir 中，把 `kimi-coding` 的 `baseUrl` 改成 `ZHIYE_GATEWAY_URL`，并用 `ZHIYE_TASK_TOKEN` 作为 API key。
→ `{ "sessionFile": "<agentDir>/sessions/xxx.jsonl" }`

### `prompt`
```json
{ "cmd": "prompt", "text": "完整的用户指令（Java 已拼好需求、计划、风格、素材、元素上下文、校验报错等）" }
```
运行一轮 agent，直到 Pi `agent_settled`。期间持续发事件。
→ `{ "text": "agent 最后一段说明", "turns": 12, "aborted": false, "error": null }`
模型调用失败（网关拒绝、额度用完、上游错误）时 `ok:false`，`error.code` 取 `MODEL_ERROR` / `PROVIDER_QUOTA`。

本次 `prompt` 的轮数超过 `init.maxTurns`（默认 60）时 runner 中止本轮、**保留会话**，回复 `error: { code: "TURN_LIMIT" }`。这不是失败：Java 发「要继续吗？」确认卡，用户继续后在**同一个会话**里再发一条 `prompt`（`Prompts.keepGoing`，带用户补充的话），轮数重新计。所以 `maxTurns` 是「每段」的检查点，不是总步数上限。

### `abort`
立即调用 `session.abort()` 并等待空闲。正在执行的 `prompt` 以 `aborted:true` 返回。→ `{}`

### `steer`
```json
{ "cmd": "steer", "text": "再加一个深色模式切换" }
```
和 `abort` 一样可以在 `prompt` 执行期间发送（不排队）。调用 Pi `session.steer(text)`：消息在当前轮的工具调用结束后、下一次请求模型之前送达，同一个 `prompt` 继续执行。→ `{ "status": "queued" | "handled" }`。没有正在运行的 `prompt` 时 `ok:false`、`error.code = BAD_STATE`。

### `confirm`
```json
{ "cmd": "confirm", "confirmId": "cf_xxx", "accept": true, "note": "当前已有的表：notes。" }
```
回答 `confirm` 事件（agent 调用了 `request_confirm`）。`confirmId` 是事件里的 `id`（命令本身的 `id` 字段另作命令编号）；`note` 会拼进工具结果告诉模型。没有对应的等待时 `ok:false`、`error.code = BAD_STATE`。工具最多等 10 分钟，超时按「暂不启用」处理；`abort` 会让等待立即结束。→ `{}`

### `verify`
```json
{ "cmd": "verify", "projectKind": "template|vue|static|vite|script|skill" }
```
| 项目类型 | 检查项 |
|---|---|
| template | ① 类型检查 `tsc --noEmit -p tsconfig.json` ② 构建预览版 `vite build --mode preview --outDir .zhiye/dist-preview`（环境变量 `ZHIYE_LOC_PLUGIN`）③ 构建发布版 `vite build --outDir .zhiye/dist-publish` ④ 冒烟测试 |
| static | ① 入口检查（存在 index.html）② 生成预览版：复制全部文件，对每个 .html 用 `annotateHtml` 加 data-loc 并内联编辑桥 ③ 发布版：原样复制 ④ 冒烟测试 |
| vue | ① 类型检查 `vue-tsc --noEmit -p tsconfig.json`，并拒绝 `createWebHistory`（要用 `createWebHashHistory`）和 React 依赖 ② 构建预览版 `vite build --mode preview --outDir .zhiye/dist-preview`（不注入编辑桥，`editable:false`）③ 发布版复制自预览版 ④ 冒烟测试 |
| vite | ① 构建：有 build 脚本时执行 `npm run build`（最后一步是 `vite build` 时追加 `-- --base=./`，让 `/p/{buildId}/` 子路径下的预览也能加载资源）；前置步骤（如 `vue-tsc &&`）失败时跳过它们直接 `vite build --base=./`，并在 `notes` 里说明。没有 build 脚本但有 vite 时直接 `vite build --base=./`。环境变量 `CI=false PUBLIC_URL=. GENERATE_SOURCEMAP=false NG_CLI_ANALYTICS=false`。产物目录：vite 配置的 outDir，或 `dist/ build/ out/ www/ dist/*/browser dist/*` 里本次新生成 index.html 的那个；构建前不存在的产物目录复制后删除（不进版本）② 预览版和发布版都复制自构建产物（不支持可视化编辑）③ 冒烟测试 |

冒烟测试：runner 在 `127.0.0.1` 随机端口起一个静态文件服务器，用 playwright-core + Chrome 无头打开预览版首页：没有页面错误（pageerror）、没有 console error、`body` 里有可见内容（template / vue 项目要求挂载节点 `#root` / `#app` 非空）。设置 `ZHIYE_SMOKE_REQUIRED=1`（runner 镜像默认）时，浏览器起不来判失败而不是跳过。

→ `{ "ok": true, "checks": [{ "name": "类型检查", "ok": true, "detail": "" }, ...], "previewDir": ".zhiye/dist-preview", "publishDir": ".zhiye/dist-publish", "editable": true }`
失败时 `ok:false`，失败项的 `detail` 是截断到 4000 字符的报错摘要（Java 会把它交回 agent 修复）。vite 项目可能带 `notes: string[]`（自动降级、产物用绝对路径等提示）。

### `visual`
```json
{ "cmd": "visual", "projectKind": "template|static", "patches": [ { "loc": "src/App.tsx:12:6", "index": 1, "match": "原文字", "sameLocCount": 3, "text": "...", "styles": { "color": "#4f78eb" }, "href": "...", "removed": true } ] }
```
把可视化编辑写回源码（见第 6 节）。`index`/`match`/`sameLocCount` 可选：带 `index` 时只改同一 loc 渲染出的第 index 个实例（DOM 顺序，0 起）；不带时改全部实例。`projectKind: "skill"` → `BAD_REQUEST`「技能项目不支持可视化编辑」。
→
```json
{ "applied": 3,
  "failures": [{ "loc": "...", "reason": "这段文字是动态生成的，不能直接修改" }],
  "locMap": { "src/App.tsx:21:12": "src/App.tsx:21:13", "src/App.tsx:24:6": null } }
```
- 有任何失败时不写任何文件（`locMap` 为 `{}`），Java 让任务失败，不提交部分结果。
- `locMap`：写回后，被改动文件里**位置发生变化**的元素的 `旧 loc → 新 loc`；`null` 表示该元素已被删除（包括被删除元素的子孙）。位置不变的元素、没被改动的文件不出现。被下标条件包裹的元素（`{__i === 1 ? null : <a/>}`）仍然存在，只是位置变了。Java 用它更新草稿、对话中引用的元素上下文等保存的 loc，避免手动微调和 AI 修改互相串位。
- 新 loc 与新代码经 `@zhiye/vite-plugin-loc` 标注得到的 `data-loc` 完全一致（runner 用实际执行的编辑记录映射位置，并用新代码的标注结果核对）。

### `import`
```json
{ "cmd": "import", "archivePath": "<绝对路径>", "format": "zip|html", "folderName": "可选", "rootDir": "可选，如 frontend 或 dist" }
```
安全解包到工作区：拒绝绝对路径、`..`、符号链接；跳过 `node_modules/`、`.git/`、`__MACOSX/`、`.DS_Store`；解压后总量不超过 200 MB、文件数不超过 5000、单文件不超过 20 MB。如果所有文件都在同一个顶层目录下，去掉这一层。带 `rootDir` 时只保留这个子目录并以它为根（前后端同仓库只导入前端、直接部署已构建的 dist）。单个 html 存为 `index.html`。

部署目录和体检报告由服务端 `ProjectAnalyzer` 在上传时生成（候选目录、风险与提示），用户在导入弹窗里确认；runner 对选中的目录按同样的规则做最终识别：
1. 有 `package.json`：`next` → 只有 `output: 'export'` 时可部署；`nuxt` / Taro / uni-app → 不支持；有前端框架（vue/react/angular/svelte/solid/preact）或构建工具（Vite、Vue CLI、Create React App、CRACO、Umi、Angular CLI、Rsbuild、Rspack、Parcel、Webpack）且有 build 脚本（Vite 可以没有）→ `projectKind: "vite"`（历史名称，表示「安装依赖后构建」）；只有 express/koa/nest 等 → Node.js 服务端（不支持）；没有框架但有 index.html → 静态
2. `requirements.txt`/`pyproject.toml`/`manage.py` → Python；`go.mod` → Go；`pom.xml`/`build.gradle` → Java；`composer.json` → PHP（都不支持）
3. 有 `index.html` → 只有一个 html 文件时为 HTML，否则为静态站点
→ `{ "framework": "Vite + Vue", "deployable": true, "unsupportedReason": null, "projectKind": "vite", "buildTool": "Vite", "fileCount": 12, "totalSize": 34567 }`

### `install`
仅用于 vite 导入项目，`NODE_ENV=development`（保证装上 devDependencies），均加 `--ignore-scripts`，每次超时 5 分钟：
- 有 `package-lock.json` → `npm ci --include=dev`；锁文件不同步时退回 `npm install`；peer 依赖冲突（ERESOLVE）时加 `--legacy-peer-deps` 重试
- 有 `pnpm-lock.yaml` 且平台有 pnpm → `pnpm install --frozen-lockfile --prod=false`，锁文件过期时 `--no-frozen-lockfile`；没有 pnpm 时用 npm
- 有 `yarn.lock` → 用 npm 按 package.json 安装（yarn.lock 里的下载地址指向 yarn 官方源，沙箱访问不到；安装期间临时移开 yarn.lock）
→ `{ "ok": true, "notes": [...], "packageManager": "npm" }`，失败返回 `ok:false`、`notes` 和带中文提示（私有包 404、网络、版本冲突、Node 版本）的日志摘要。

导入成功后，Java 把 `node_modules` 移到 `runtime/deps/{appId}/`；之后该应用的修改任务（沙箱不联网）在准备工作区时硬链接回 `node_modules`，所以不需要重新安装。

### `shutdown`
释放会话，进程退出。

## 5. 事件（runner → Java，Java 原样转成 TaskEvent）

| type | 字段 | 说明 |
|---|---|---|
| `agent_text` | `delta` | agent 说明文字的增量（不含思考过程） |
| `tool` | `id`、`name`、`summary`、`state: "start"\|"end"`、`ok?`（end）、`detail?`（end） | `summary` 为中文；结束事件带计数，如「读取 src/App.tsx 42 行」「创建 SKILL.md +57 行」「修改 src/App.tsx +3 −1」「执行命令：npx tsc --noEmit」「更新待办清单」。`detail` 见下 |
| `confirm` | `id, kind, reason, state` | agent 调用 `request_confirm` 时 `state: "pending"`，等平台发 `confirm` 命令；结束时再发一次，`state` 为 `accepted` / `declined` / `timeout`。目前 `kind` 只有 `cloud`（启用云服务） |
| `todo` | `items: [{ content, status }]` | agent 每次调用 `todo_write` 时发出完整的待办清单；`status` 为 `pending` / `in_progress` / `completed`，最多一项 `in_progress`，1～8 项，`content` ≤ 80 字（已清洗） |
| `thinking` | `text` | 模型思考过程的增量（合并后最多约每 300 ms 一条，已脱敏）；每个任务最多 20 KB，超过后不再发文字 |
| `thinking_end` | `durationMs` | 一段思考结束（超过 20 KB 上限后也照常发送） |
| `turn` | `n` | 第几轮 |
| `log` | `line` | 构建、安装等过程输出（节流，每秒最多 5 条） |

**`tool` 结束事件的 `detail`**（路径都相对工作区；工作区绝对路径替换为 `.`，其他用户目录替换为 `~`，工作区外的文件只显示「（工作区外）/文件名」）：

| `name` | `detail` |
|---|---|
| `write` | `{ kind:"write", path, created, lines, preview, truncated? }`：`preview` 为新内容的前 200 行 / 12 KB |
| `edit` | `{ kind:"edit", path, added, removed, diff, truncated? }`：`diff` 为本次编辑的统一 diff（`--- a/路径` `+++ b/路径` `@@ … @@`，上下文 3 行），≤ 200 行 / 12 KB |
| `read` | `{ kind:"read", path, lines }` |
| `bash` | `{ kind:"bash", command, exitCode, output, truncated?, blocked? }`：`output` 为 stdout+stderr 合并后的最后 4 KB；被安全守卫拦截时 `blocked:true`、`exitCode:null` |
| `todo_write` | 无 `detail`（看 `todo` 事件） |

工具失败时 `write` / `edit` / `read` 的 `detail` 另带 `error`（≤ 1000 字，已脱敏）。

**待办清单**：runner 给 Pi 注册了 `todo_write` 工具（参数 `{ items: [{ content, status }] }`，校验不通过时把中文原因交回模型）。系统提示要求 agent 动手前先列 3～6 个步骤，开始某步前标 `in_progress`，完成后标 `completed`，结束时全部 `completed`。

**思考过程**：Kimi 的 `reasoning_content` 由 Pi 转成 thinking 增量，runner 转成 `thinking` / `thinking_end`；`thinking: "low"` 时 Kimi 通常只给很短的思考。

## 6. 可视化编辑写回规则

`loc` = `相对路径:行(1 起):列(0 起)`，与 `@zhiye/vite-plugin-loc` 生成的一致，指向元素开始标签的 `<`。

**JSX/TSX**（用 @babel/parser 定位，按源码位置做最小替换，保留原有格式）：
- 找到开始位置等于 loc 的 JSXElement；找不到 → 失败「元素已变化，请刷新后重试」。
- `text`：子节点只有文字（JSXText，或字符串字面量表达式）→ 整体替换为新文字（转义 `{`、`}`、`<`、`>`）；有文字也有子元素（如 label 内含 input）→ 只替换第一段非空文字，删掉其他文字段，保留子元素；含其他表达式 → 失败「这段文字是动态生成的，不能直接修改」。
- `styles`：合并到 `style={{...}}`：没有就新增；已有对象字面量就设置或覆盖对应属性；是其他表达式就改成 `{...原表达式, 新属性}`。
- `href`：设置 `href` 属性（只对 a 元素）。
- `removed`：从父元素的子节点中删掉该元素；它是组件返回的根元素时失败「不能删除页面根元素」。

**HTML**（用 parse5 的源码位置，按位置替换原字符串）：同样的语义；`styles` 合并进 `style` 属性字符串（驼峰转短横线）。

**列表单项（JSX，补丁带 `index`，元素在 `.map(回调)` 里）**：
- 回调没有下标参数时补上（`item =>` → `(item, __i) =>`，名字冲突时用 `__i2` 等）；已有下标参数就用它。
- `styles`：没有 style → `style={__i === N ? { … } : undefined}`；已有 → `style={{ ...(原表达式), ...(__i === N ? { … } : {}) }}`；每个下标一个条件，再次修改同一下标时替换该项，清空后去掉。
- `text`：静态文字 → `{__i === N ? '新文字' : '原文字'}`（多个下标串成条件链，改回原文时去掉该项）；文字来自数据（如 `{item.title}`）→ 失败「这段文字来自数据，手动微调不能只改其中一项，请用对话修改」。
- `removed`：`{__i === N ? null : <El/>}`；回调根元素 → `return (__i === N ? null : <El/>)`；再删另一项时合并成 `__i === N || __i === M`。
- `href`：`href={__i === N ? '新' : '原'}`。
- 不在 `.map` 里但 `index > 0` 或 `sameLocCount > 1`（可复用组件渲染多次）→ 失败「这个元素来自可复用组件，只改其中一处需要用对话修改」；嵌套列表 →「这个元素在嵌套的列表里…」；其他写法 →「这个列表的写法不支持只改其中一项…」。
- runner 只按 `index` 定位，`match` 仅由编辑桥用于预览中找回实例。
- HTML 的 loc 每个元素唯一，`index` 被忽略。

## 7. Pi 的配置要求

- 只开放 `read`、`write`、`edit`、`bash` 四个内置工具和织页自己的 `todo_write`、`request_confirm`（技能项目没有）、`screenshot`（template / vue / static / vite 项目：构建一份不带编辑桥的产物，无头 Chrome 打开，返回截图——模型支持图片输入时返回图片，否则返回页面文字摘要和横向溢出、文字截断、控制台报错等布局问题；参数 `route`、`device: desktop|mobile`、`fullPage`。系统提示要求结束前至少截一次）；不加载工作区或用户目录里的任何扩展、技能、提示模板（用自定义 ResourceLoader，或关闭发现）。
- 安全守卫（Pi `tool_call` 事件，返回 `{ block: true, reason }`）：
  - 读写路径必须在工作区内（例外：可以**只读** `{agentDir}/skills/**`，织页把用户引用的技能放在这里）；禁止写 `node_modules/`、`.git/`、`.zhiye/`、`.pi/`、`.env*`、锁文件
  - 禁止修改 `package.json`（template / vue 项目）
  - bash 禁止：联网和安装（`npm i`/`npm install`/`pnpm`/`yarn`/`npx` 以外的下载、`curl`、`wget`、`git clone|push|pull|fetch`）、`sudo`、`rm -rf /` 或删除工作区以外的路径、启动长期运行的服务（`vite`/`npm run dev`/`npm start`）。允许 `npx tsc --noEmit`、`ls`、`cat`、`grep` 等
- 系统提示（中文）：项目结构和约定（template：只改 `src/`、`public/`、`index.html`，以及开发skill 用的 `skill/`、技术文档用的 `docs/`；入口 `src/App.tsx`，组件放 `src/components/`；用 Tailwind；不引用外部网络资源；界面文字用中文；布局自适应；完成后用 `npx tsc --noEmit` 自查）；不要启动开发服务器；不要修改依赖；最后按任务指令要求的结构总结。vue 项目用对应的 Vue 3 约定：入口 `src/App.vue`，`<script setup lang="ts">`，路由只能用 `createWebHashHistory`，图表用 echarts，图标用 `@lucide/vue`，用 `npx vue-tsc --noEmit` 自查。
- 关闭 Pi 自动压缩以外的自动行为；重试交给 Java（Pi 内置重试最多 1 次）。
