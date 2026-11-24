-- 动态帮助知识库（左侧帮助文档 + 小织机器人单一事实来源，支持管理台在线维护、免发版生效）
create table if not exists help_knowledge (
    id text primary key,
    slug text not null unique,
    category text not null default 'guide',
    title text not null,
    short_title text not null,
    badge text,
    summary text not null default '',
    content_md text not null,
    keywords text not null default '',
    ask_prompt text not null default '',
    sort_order int not null default 100,
    for_docs boolean not null default true,
    for_bot boolean not null default true,
    enabled boolean not null default true,
    updated_by text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_help_knowledge_order on help_knowledge (enabled, sort_order, id);

-- 个人工作空间长期记忆（按 workspace_id 严格隔离，自动提炼 + 用户可控查看/编辑/清空）
create table if not exists workspace_memory (
    id text primary key,
    workspace_id text not null references workspace(id) on delete cascade,
    category text not null default 'preference',
    content text not null,
    source text not null default 'manual',
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_workspace_memory_ws on workspace_memory (workspace_id, enabled, updated_at desc);

-- 初始化标准帮助文档与机器人知识种子（后续管理员可在「管理 → 知识库维护」随时在线编辑）
insert into help_knowledge (id, slug, category, title, short_title, badge, summary, content_md, keywords, ask_prompt, sort_order, for_docs, for_bot, enabled, updated_by)
values
(
    'hk_limits', 'limits', 'limits',
    '平台资源与配额限制总览', '资源限制', '必读',
    '所有单应用、个人工作空间、代码导入与云服务的硬上限一览表，超出上限系统将直接拦截提示。',
    E'### 1. 单体后端容器硬配额（超出不予部署）\n| 限制项 | 生效标准 | 说明 |\n| :--- | :--- | :--- |\n| 单应用 CPU 上限 | **100～1000 mCPU（1C）** | 默认 Py/Node/Go `500m`，Java `1000m` |\n| 单应用内存上限 | **128～1024 MB（1G）** | 默认 Py/Node/Go `512 MB`，Java `1024 MB` |\n| `/data` 持久化卷 | **1～5 Gi** | 开启开关后注入 `DATA_DIR=/data`，重启/休眠不丢 |\n| 闲置自动休眠 | **默认 2 天（2880 分钟）** | 可调 `5～10080 分钟（7天）`，强制开启不可设 `0` 常驻 |\n| 环境变量 | **最多 64 个 / 单值 ≤ 8 KB** | AES-256-GCM 密文托管 |\n| 单人单体服务数 | **最多 5 个** | 计入个人 10 个总应用名额内 |\n| 单人同时运行上限 | **2C 2G（2000m / 2048 MB）** | 超限时需先休眠或停止其他闲置服务 |\n| 全平台运行池上限 | **64C 128G / 160 Pod** | 适配 100C 200G / 30 并发开发集群（K8s 硬兜底） |\n\n### 2. 个人工作空间与 AI 构建配额\n| 限制项 | 生效标准 | 说明 |\n| :--- | :--- | :--- |\n| 每人总应用数 | **最多 10 个** | 不含已删除应用；管理员可在后台按人提额 |\n| 每人 AI 任务并发 / 日限 | **同时 1 个 / 每日 50 次** | AI 开发沙箱单实例 `2C 4G`，支持 60 步暂停确认继续 |\n| 个人工作空间记忆 | **最多 30 条 / 总计 ≤ 3000 字** | 跨应用自动记住你的业务背景与技术偏好，随时可编辑清空 |\n| 小织帮助助手 | **每日 60 条对话** | 随时可查看本帮助文档（无次数限制） |\n\n### 3. 代码导入与云服务（BaaS）限制\n| 限制项 | 生效标准 | 说明 |\n| :--- | :--- | :--- |\n| 代码导入包体 | **ZIP ≤ 50 MB / 解压 ≤ 200 MB** | 单文件 ≤ **20 MB**，文件总数 ≤ **5000** |\n| 对话附件上传 | **单文件 ≤ 10 MB / 每条 ≤ 5 个** | 支持图片、PDF、Word/Excel/PPT（新版） |\n| 云数据库 | **最多 50 张表 / 每表 ≤ 40 列** | 单次查询最多 200 行，独立 PG Schema 隔离 |\n| 云文件存储 | **单文件 ≤ 10 MB / 最多 200 文件** | 支持 `upload / download / list / remove`，防 XSS 下载 |\n| Deno 云函数 | **单次超时 10 秒** | 无外网与本地文件读写，安全沙箱执行 |',
    '限制,配额,上限,1C,1G,2C,2G,64C,128G,10个,5个,50MB,200文件,10MB,休眠,2880,2天',
    '帮我解读一下织页平台的资源配额限制，如果超限了怎么办？',
    10, true, true, true, 'system'
),
(
    'hk_monolith', 'monolith', 'monolith',
    '单体后端托管与 2 天自动休眠', '单体托管', '全栈',
    '支持对话新建全栈应用及导入 Python / Node.js / Java / Go / Dockerfile 单体应用，独立子域名同源反代与热重部署。',
    E'### 支持的创建与托管方式\n- **对话直接新建全栈应用**：在「创造」页选择 **「全栈应用」**（或在需求中明确需要前后端接口/独立服务），平台初始化 React + Vite + Node.js Express 全栈工程，一次生成前后端并自动部署到单体托管容器。\n- **导入已有代码托管**：支持 **Python**（FastAPI/Flask/Django）、**Node.js**（Express/Fastify/Koa/Nest/Next）、**Java**（Spring Boot + Maven/Gradle）、**Go** 以及 **自定义 Linux Dockerfile** 单体后端与前后端一体项目；导入后同样支持继续对话迭代代码并热重部署。\n\n### 闲置自动休眠（Scale-to-Zero）机制\n1. **默认 2 天（2880 分钟）无访问自动休眠**：减少工作日期间频繁冷启动，同时在周末或项目闲置时自动回收算力。\n2. **休眠时 Pod 完全销毁（`replicas = 0`）**：0 CPU、0 内存占用，不消耗个人 2C 2G 运行配额。\n3. **再次访问透明唤醒**：打开线上或预览地址时，网关自动拉起容器（Go/Node/Python 约 1～3 秒，Java 约 5～10 秒）。\n\n### 数据持久化与企业身份透传\n- **托管 PostgreSQL Schema**：在「服务托管」一键开启，自动注入 `DATABASE_URL` 与 `JDBC_DATABASE_URL`。\n- **持久化卷 `/data`**：SQLite 数据库、上传文件、生成报表请统一写入 `DATA_DIR`（默认 `/data`），切勿写入临时目录。\n- **华星 SSO 身份头**：网关自动剥离伪造头并注入 `X-Zhiye-User-Id`、`X-Zhiye-User-Name`、`X-Zhiye-User-Email`。',
    '单体,后端,全栈,托管,休眠,唤醒,pod,1c,1g,pvc,data,postgres,环境变量,日志,dockerfile,python,java,go,node',
    '单体后端应用休眠时 Pod 会停吗？对话能不能直接建前后端全栈应用？',
    20, true, true, true, 'system'
),
(
    'hk_preflight', 'preflight', 'preflight',
    '代码导入与外部 Agent 预检规范', '预检规范', '推荐',
    '使用 CodeBuddy / Cursor / Claude Code 编码后导入织页，先运行内置预检 Skill 避免部署踩坑。',
    E'### 外部编码 Agent 强制预检 Skill（`zhiye-deploy-preflight`）\n织页内置了标准 SkillHub 规范的预检技能 **`zhiye-deploy-preflight`**，建议发给使用 **CodeBuddy / Cursor / Claude Code** 的开发者，在交付打包前执行：\n```bash\npython3 scripts/check_zhiye_ready.py <项目目录或ZIP路径>\n```\n- **PASS（退出码 0）**：完全符合规范，可直接导入。\n- **WARN（退出码 0）**：可导入，但需按提示在「服务托管」补齐环境变量或持久化开关。\n- **BLOCK（退出码 2）**：触发平台能力上限或硬性红线，**禁止直接导入**，必须先按报告整改或改用独立部署。\n\n### 平台自动容错与静态体检\n导入 ZIP 时平台会自动：\n- 跳过 `node_modules`、`.venv`、`__pycache__`、`.git`、`.idea`、`__MACOSX`；\n- 自动剥离最多 **3 层**多余外层套壳目录；\n- 扫描 **4 类高频隐患**：`.env`/私钥泄露、Redis/MySQL/微服务依赖、前端硬编码 `http://localhost:xxxx`、后端写死监听端口未读 `PORT`。\n\n### 编码 4 条铁律\n1. 后端必须监听 **`0.0.0.0`** 并读取环境变量 **`PORT`**（禁止写死 `127.0.0.1`）。\n2. 前端请求 API 一律使用**同域相对路径**（如 `/api/...`），禁止写死 `http://localhost:8000`。\n3. **禁止打包 `.env` 与明文密钥**，敏感配置统一在工作台「环境变量」加密录入。\n4. 持久化文件统一写入 **`DATA_DIR`（`/data`）** 或托管 PostgreSQL（`DATABASE_URL`）。',
    '导入,预检,codebuddy,cursor,claude,agent,skill,preflight,校验,localhost,env,redis,微服务',
    '我用 CodeBuddy 写的项目准备导入织页，有哪些必须遵守的规范？',
    30, true, true, true, 'system'
),
(
    'hk_cloud', 'cloud', 'cloud',
    '对话创造、云服务与能力边界', '云服务与边界', null,
    '轻量化对话全栈（云数据库 + 云文件存储 + Deno 云函数 + 应用内 AI）、个人工作空间记忆与平台能力红线。',
    E'### 两种全栈路径怎么选？\n- **日常看板 / 表单审批 / 台账管理 / AI 工具**：选择「日常开发 / 开发网站」+ 开启 **「共享云服务」**，零运维、无冷启动、秒级构建。\n  - **云数据库**：独立 PostgreSQL，最多 50 张表，工作台可视化增删改查、Excel/CSV 批量导入导出、6 种权限预设。\n  - **云文件存储**：通过 `window.zhiye.cloud.storage` 支持 `upload / download / list / remove`（单文件 ≤ 10 MB、最多 200 文件），工作台可统一管理。\n  - **Deno 云函数 & 应用内 AI**：后端校验/聚合逻辑写进云函数（10 秒内），摘要/OCR/根因分析直接调用 `window.zhiye.ai`。\n- **需要自定义后端 API / 文件处理 / 复杂业务服务**：选择 **「全栈应用」** 对话直接生成前后端代码，或通过 **「导入代码并部署」** 托管外部工程。\n\n### 个人工作空间记忆（Workspace Memory）\n- 在侧边栏底部「我的工作空间 → 个人记忆」里，你可以录入自己的厂区/岗位背景、常用技术栈和视觉偏好（例如「T11 厂 Array 制程」「默认带 Excel 导出」「浅色工业风」）。\n- 平台也会在日常创造中自动提炼长期偏好（每人上限 30 条），自动生效于创造会话、方案计划、代码生成和小织问答，随时可编辑、开关或清空。\n\n### 明确不支持的能力红线（替代方案）\n| 超出边界的场景 | 原因与替代建议 |\n| :--- | :--- |\n| 微服务架构（Spring Cloud / Nacos / Dubbo / 多容器 Compose） | 平台为单实例托管，请拆为单体服务或改用独立 K8s 集群 |\n| 内置 Redis / MySQL / MongoDB / Kafka / RabbitMQ / ES | 平台内置托管 PostgreSQL 与 `/data`；确需中间件请改外连内网实例并在环境变量配置地址 |\n| GPU / CUDA / 本地大模型推理（PyTorch / vLLM） | 容器纯 CPU 且上限 1C 1G，请改用平台应用内 AI 或 HTTP 模型接口 |\n| 原生 App / 小程序 / Windows 专用组件（pywin32） | 改为内网响应式网页或跨平台 Linux 库 |\n| 直写 MES / ERP / SAP 生产数据库 | 通过「数据源」配置只读查询接口，或导出 Excel/CSV 流转 |',
    '云服务,数据库,云存储,云函数,应用内AI,记忆,workspace,memory,能力边界,做不了,微服务,GPU,数据源',
    '什么需求适合用云服务，什么适合用全栈应用，哪些是平台做不了的？',
    40, true, true, true, 'system'
),
(
    'hk_workflow', 'workflow', 'guide',
    '创造、修改、版本发布与数据源速查', '使用指南', null,
    '从一句话开始创造、可视化微调、版本回滚、权限控制、灵感社区与 SkillHub 发布全流程。',
    E'### 核心使用流程\n1. **开始创造**：在「创造」页直接描述想法或点选「华星场景模板」（支持粘贴/上传最多 5 个图片或文档附件），支持类型：**日常开发 / 开发网站 / 全栈应用 / 开发skill / 技术文档**。\n2. **修改页面与代码**：支持左侧「对话修改」、预览上方「手动微调」可视化点选改字改样式，以及「代码」标签直接在线编辑并自动重构建/热重部署。\n3. **版本与发布**：每次成功改动都会保留版本，支持备注、对比和一键回滚；点「发布」生成独立可分享链接，可配置「内网公开 / 员工登录 / 指定人员」。\n4. **连接企业数据**：点输入框左下角「＋」→「数据源」，可绑定最多 100 个只读公司内部接口（支持从 API 网关导入），密钥完全由平台代理不暴露到前端。\n5. **SkillHub & 灵感社区**：技能应用可一键发布到 SkillHub 供团队复用；网页应用可发布到「灵感社区」供同事一键复刻（仅带代码与表结构，不带真实业务数据）。',
    '开始,创造,修改,手动微调,版本,恢复,发布,数据源,社区,复刻,skillhub,下载,导出,恢复链接',
    '怎么开始创造一个应用，以及怎么发布和分享给同事？',
    50, true, true, true, 'system'
)
on conflict (id) do nothing;
