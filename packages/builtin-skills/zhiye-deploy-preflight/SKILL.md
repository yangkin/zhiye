---
name: zhiye-deploy-preflight
description: 检查外部编码 Agent 开发的项目是否符合织页平台导入与托管能力边界；部署前执行预检并输出通过/警告/阻断及整改理由。
compatibility: Python 3.9+（仅标准库，无需第三方依赖）；适用于 CodeBuddy、Cursor、Claude Code 等外部编码 Agent 及本地 CLI 预检
---

# 织页平台（Zhiye）部署前规范校验与能力边界审查

本 Skill 用于约束外部编码 Agent（CodeBuddy、Cursor、Claude Code 等）在开发准备导入 **织页平台** 的应用时，提前对齐平台能力边界、规避常见部署踩坑，并在交付/打包前执行自动化预检。

> **核心原则**：当项目触发 **BLOCK（阻断）** 项时，Agent **必须停止打包交付**，明确向用户说明触发原因与平台能力上限，并给出改造方案（或告知用户该架构不适合织页平台，应改用独立云主机/标准 K8s 部署）。

---

## 1. 织页平台能力矩阵与上限（红线）

### 1.1 明确支持的项目形态

| 项目形态 | 支持技术栈 | 平台托管方式 |
| :--- | :--- | :--- |
| **纯静态站点** | HTML / CSS / JS | 静态资源托管，开箱即用 |
| **前端 SPA** | Vite / React / Vue / Svelte / Astro | 自动安装依赖、构建并托管产物 |
| **单体后端 / 全栈应用** | **Python**（FastAPI / Flask / Django / Uvicorn）<br>**Node.js**（Express / Fastify / Koa / NestJS / Next.js）<br>**Java**（Spring Boot + Maven / Gradle）<br>**Go**（`go.mod` / Gin / Echo / net/http）<br>**自定义 Linux `Dockerfile`** | 单容器/单进程隔离托管，分配独立子域名，反代 `/` 与 `/api/*` 同源访问，自动透传 SSO 身份头 |
| **数据持久化** | 托管 **PostgreSQL 独立 Schema**（注入 `DATABASE_URL` / `JDBC_DATABASE_URL`）<br>持久化卷 **`/data`**（注入 `DATA_DIR=/data` / `DATABASE_PATH`） | 服务托管面板一键开启；无状态文件在缩容/重启后会重置 |
| **资源配额与归档限制** | 压缩包 ≤ **50 MB**；解压总大小 ≤ **200 MB**；单文件 ≤ **20 MB**；文件数 ≤ **5000**；单应用容器硬上限 **1C 1G**（CPU ≤ **1000 mCPU**、内存 ≤ **1024 MB**、持久化卷 ≤ **5 Gi**，默认 **2 天 / 2880 分钟**无访问自动休眠且强制开启）；单工作空间同时运行上限 **2C 2G** | 超出上限不予部署，请精简依赖/内存占用或休眠其他闲置服务 |

### 1.2 平台能力上限（明确不支持 / 必阻断）

以下场景**超出织页单体托管能力边界**，遇到时必须直接告知用户原因，不要强行导入：

1. **微服务与多进程编排架构**：Spring Cloud / Nacos / Dubbo / Eureka / Consul、多容器 `docker-compose` 强耦合集群、Sidecar 网格。织页每个应用为**单服务实例**。
2. **自带外部中间件基础设施**：平台**不提供**托管的 Redis、Kafka、RabbitMQ、RocketMQ、MySQL、MongoDB、Elasticsearch、MinIO、ClickHouse。
   - 若项目**强依赖**本地/同机 Redis/MySQL/MQ 且无降级模式，需改为使用托管 PostgreSQL / SQLite（`/data`）或由用户在环境变量中填入已有外部中间件地址（并确认网络可达）。
3. **GPU / CUDA / 本地大模型推理**：运行容器仅提供 CPU 资源，无 NVIDIA GPU，不支持本地加载 PyTorch/vLLM/Transformers 大模型权重（AI 能力应改用 HTTP API 并在环境变量配置密钥）。
4. **特权操作与平台绑定**：Docker-in-Docker（挂载 `docker.sock`）、系统内核驱动、Windows 专用组件（`win32com` / `pywin32` / `.exe`）、符号链接（导入直接拒绝）。
5. **未列出的后端运行时（无 Dockerfile）**：Rust（`Cargo.toml`）、PHP（`composer.json`）、Ruby（`Gemfile`）、.NET（`*.csproj`）等；若确需运行，必须自备可独立在 Linux 容器内构建运行的 `Dockerfile`。

---

## 2. 外部 Agent 编码必须遵守的硬性规范

1. **监听地址与端口（必选）**
   - 后端服务必须监听 **`0.0.0.0`**（严禁绑定 `127.0.0.1` 或 `localhost`，否则容器反代无法访问）。
   - 端口必须从环境变量 **`PORT`**（Java 支持 `SERVER_PORT` / `--server.port=${PORT:-8080}`）读取，不得写死。
2. **前后端同源调用（必选）**
   - 前端请求后端 API 一律使用**相对路径**（如 `fetch('/api/items')`），**严禁硬编码 `http://localhost:8000` 或 `http://127.0.0.1:3000`**。
   - 若为前后端分离目录（如 `frontend/` + `backend/`），建议由后端直接托管前端构建产物，或由构建命令先构建前端再由后端挂载静态目录。
3. **健康检查接口（推荐）**
   - 提供轻量 `GET /health` 或 `GET /api/health`（或 Spring Boot `/actuator/health`），返回 `200 OK`；避免启动时因外部连接超时导致进程退出。
4. **持久化与闲置休眠（Scale-to-Zero）兼容**
   - SQLite 数据库、上传文件等必须写入 `os.environ.get("DATA_DIR", "/data")`（或 `DATABASE_PATH`），不可写入项目根目录临时文件，否则闲置休眠唤醒后数据会丢失。
   - 关系型数据库优先读取 `DATABASE_URL`（Java 读取 `JDBC_DATABASE_URL`），并在未配置时优雅降级或给出清晰日志提示。
5. **密钥安全与压缩包洁净（必选）**
   - **严禁提交 `.env`、`.env.local`、私钥（`.pem`/`.key`）、明文 API Key / Token / 数据库密码**；请只保留 `.env.example`，实际密钥通过织页「服务托管 → 环境变量」加密配置。
   - 打包 ZIP 前清理 `node_modules/`、`.venv/`、`venv/`、`__pycache__/`、`.git/`、`target/`、`dist/` 等构建缓存，确保包内为干净源码且无多余嵌套壳目录。
6. **企业身份透传（可选）**
   - 织页网关会自动透传当前登录用户的 SSO 请求头：`X-Zhiye-User-Id`、`X-Zhiye-User-Name`、`X-Zhiye-User-Email`、`X-Zhiye-App-Id`，应用可直接读取，无需自建复杂登录体系。

---

## 3. 执行预检（强制步骤）

在代码完成或准备打包上传到织页平台前，**必须**运行内置预检脚本检查项目目录或 ZIP 包：

```bash
python3 scripts/check_zhiye_ready.py <项目目录或ZIP路径>
```

如需供 Agent 或 CI 机器解析，附加 `--json` 参数：

```bash
python3 scripts/check_zhiye_ready.py <项目目录或ZIP路径> --json
```

### 判定等级与处理要求

| 退出码 | 结论 | Agent 下一步动作 |
| :---: | :---: | :--- |
| `0` | **PASS** | 项目完全符合织页平台规范，可直接打包导入部署。 |
| `0` | **WARN** | 可尝试导入，但存在已知风险（如硬编码端口、依赖外部中间件、未挂持久化卷等）。**必须逐条向用户说明风险及在「服务托管」面板的配置动作**，或征求同意后直接修复。 |
| `2` | **BLOCK** | **禁止直接交付导入**。必须按报告中的【阻断原因】逐项说明为什么超出织页能力上限或违反硬规范，先完成代码改造并重新运行脚本直到不再出现 `BLOCK`；若属平台架构红线（如微服务/GPU/多容器编排），直接建议用户改用外部标准部署方案。 |

---

## 4. 常见阻断项速查与整改指南

| 检查代码 | 典型问题 | 标准整改方法 |
| :--- | :--- | :--- |
| `BIND_LOOPBACK` | 后端写死 `127.0.0.1` / `localhost` | 改为 `0.0.0.0`（如 `uvicorn app:app --host 0.0.0.0 --port $PORT`） |
| `HARDCODED_LOCALHOST_API` | 前端写死 `http://localhost:8000/api` | 改为相对路径 `/api/...`，或通过 `import.meta.env.VITE_API_BASE \|\| ''` 默认同源 |
| `SECRET_FILE_COMMITTED` | 仓库或 ZIP 内含 `.env` / `.pem` / `.key` | 删除敏感文件，改留 `.env.example`，在织页「环境变量」页加密录入 |
| `PLAINTEXT_SECRET` | 源码中出现真实 API Key / Token / 私钥 | 改为读取环境变量（如 `os.environ["API_KEY"]`），切勿明文入库 |
| `MICROSERVICE_STACK` | 依赖 Spring Cloud / Nacos / Dubbo / 多服务 compose | 拆解为单体服务并移除注册中心依赖，或告知用户改用外部 K8s 集群 |
| `GPU_WORKLOAD` | 引入 CUDA / vLLM / 本地大模型推理 | 改为调用外部模型 HTTP API（密钥放环境变量），避免本地 GPU 依赖 |
| `UNSUPPORTED_RUNTIME` | Rust / PHP / Ruby / .NET 且无 Dockerfile | 补充可在 Linux 容器独立构建运行的 `Dockerfile`，或切换至 Python/Node/Java/Go |
| `PORT_NOT_CONFIGURABLE` | 后端未读取 `PORT` / `SERVER_PORT` | 从 `PORT` 环境变量读取监听端口并保留默认值 |
| `EXTERNAL_MIDDLEWARE` | 依赖 Redis / MySQL / MongoDB / Kafka / MQ | 优先迁移至托管 PostgreSQL（`DATABASE_URL`）或进程内缓存；确需外连时，改由环境变量注入连接串并确认内网可达 |
| `ARCHIVE_LIMIT_EXCEEDED` | 包体超 50MB、解压超 200MB、单文件超 20MB 或含符号链接 | 删除 `node_modules`/`.venv`/大媒体文件与符号链接后重新打包 |
