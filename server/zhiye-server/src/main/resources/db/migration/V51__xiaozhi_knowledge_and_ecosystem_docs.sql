-- V51: 全面补全「小织」机器人知识库与左侧「帮助文档」（单一事实来源：驱动 HelpDocs + 小织 System Prompt）
-- 1) 调整 hk_limits 徽章为「配额」，突出第一章节「使用手册 必读」
update help_knowledge
set badge = '配额',
    updated_at = now()
where id = 'hk_limits';

-- 2) 更新 hk_monolith（补全 Vue 3 + Java Spring Boot / Python / Go 全栈、端口自动避冲突、托管 PG 只读安全预览）
update help_knowledge
set content_md = E'### 支持的创建与托管方式
- **对话直接新建全栈应用**：在「创造」页选择 **「全栈应用」**（或在需求中明确指定 Vue + Java / Spring Boot / FastAPI / Go 等前后端栈），平台可按技术栈自动初始化 **Vue 3 + Java Spring Boot**、**React + Node.js Express**、**Python FastAPI/Flask** 或 **Go** 全栈工程，一次生成前后端并自动部署到单体托管容器。
- **导入已有代码托管**：支持 **Python**（FastAPI/Flask/Django）、**Node.js**（Express/Fastify/Koa/Nest/Next）、**Java**（Spring Boot + Maven/Gradle）、**Go** 以及 **自定义 Linux Dockerfile** 单体后端与前后端一体项目；导入后同样支持继续对话迭代代码并热重部署。

### 闲置自动休眠（Scale-to-Zero）机制
1. **默认 2 天（2880 分钟）无访问自动休眠**：减少工作日期间频繁冷启动，同时在周末或项目闲置时自动回收算力。
2. **休眠时 Pod 完全销毁（`replicas = 0`）**：0 CPU、0 内存占用，不消耗个人 2C 2G 运行配额。
3. **再次访问透明唤醒**：打开线上或预览地址时，网关自动拉起容器（Go/Node/Python 约 1～3 秒，Java 约 5～10 秒）。

### 数据持久化、端口自动避冲突与安全能力
- **托管 PostgreSQL 独立 Schema**：在「服务托管」一键开启，为每个应用创建独立数据库 Schema 与最小权限角色，自动注入 `DATABASE_URL` 与 `JDBC_DATABASE_URL`；工作台仅开放**只读表结构与数据预览**（禁用任意 DDL/删除等高危 SQL 控制台），并自动对日志中的数据库凭证脱敏。
- **持久化卷 `/data`**：SQLite 数据库、上传文件、生成报表请统一写入 `DATA_DIR`（默认 `/data`），切勿写入临时目录，重启或休眠唤醒后数据不丢。
- **端口免冲突**：业务代码只需监听 `0.0.0.0` 并读取环境变量 `PORT`，平台在本地/容器调度时会自动检测占用并分配空闲端口，无需担心与其他服务撞端口。
- **华星 SSO 身份头**：网关自动剥离客户端伪造头并注入可信 `X-Zhiye-User-Id`、`X-Zhiye-User-Name`、`X-Zhiye-User-Email`。',
    keywords = '单体,后端,全栈,托管,休眠,唤醒,pod,1c,1g,pvc,data,postgres,环境变量,日志,dockerfile,python,java,springboot,vue,go,node,端口冲突,只读预览',
    updated_by = 'system',
    updated_at = now()
where id = 'hk_monolith';

-- 3) 更新 hk_cloud（补全无页面 Python/Node 脚本在线运行、Excel/CSV 直接生成看板与附件生命周期、精品内置技能）
update help_knowledge
set content_md = E'### 四种常见开发路径怎么选？
- **日常看板 / 表单审批 / 台账管理 / AI 工具**：选择「日常开发 / 开发网站」+ 开启 **「共享云服务」**，零运维、无冷启动、秒级构建。
  - **云数据库**：独立 PostgreSQL，最多 50 张表，工作台可视化增删改查、Excel/CSV 批量导入导出、6 种权限预设。
  - **云文件存储**：通过 `window.zhiye.cloud.storage` 支持 `upload / download / list / remove`（单文件 ≤ 10 MB、最多 200 文件），工作台可统一管理。
  - **Deno 云函数 & 应用内 AI**：后端校验/聚合逻辑写进云函数（10 秒内），摘要/OCR/根因分析直接调用 `window.zhiye.ai`。
- **上传 Excel / CSV 直接做数据看板**：直接把 `.xlsx` / `.csv` 文件拖入或粘贴到输入框，告诉织页要分析的维度与指标即可生成交互看板；**同一会话内上传的图片和文件在后续多轮追问中全程有效**，新建会话时自动隔离。
- **无页面数据处理脚本**：明确描述写 Python / Node.js 命令行脚本或数据清洗算子时，平台会创建脚本工程并提供 **「脚本运行控制台」**，支持在线填参数试跑、查看实时 `stdout/stderr` 输出与历史运行记录。
- **复杂前后端业务服务**：选择 **「全栈应用」** 对话直接生成前后端代码（支持 Vue 3 + Java Spring Boot / Node / Python / Go），或通过 **「导入代码并部署」** 托管外部工程。

### 内置精品技能与个人工作空间记忆
- **精品内置技能**：在输入框点 **「选择技能」** 或输入 `@`，可直接挂载平台预置的专项高水准技能（数据看板、飞书集成、企业数据源、代码预检、前端设计规范等）以及你自己的技能包，让 AI 输出更贴合华星业务标准。
- **个人工作空间记忆（Workspace Memory）**：在侧边栏底部「我的工作空间 → 个人记忆」中录入你的厂区/岗位背景、常用技术栈和审美偏好（每人最多 30 条 / 总计 ≤ 3000 字），平台也会在日常创造中自动提炼，跨应用自动生效，随时可编辑、开关或清空。

### 明确不支持的能力红线（替代方案）
| 超出边界的场景 | 原因与替代建议 |
| :--- | :--- |
| 微服务架构（Spring Cloud / Nacos / Dubbo / 多容器 Compose） | 平台为单实例托管，请拆为单体服务或改用独立 K8s 集群 |
| 内置 Redis / MySQL / MongoDB / Kafka / RabbitMQ / ES | 平台内置托管 PostgreSQL 与 `/data`；确需中间件请改外连内网实例并在环境变量配置地址 |
| GPU / CUDA / 本地大模型推理（PyTorch / vLLM） | 容器纯 CPU 且上限 1C 1G，请改用平台应用内 AI 或 HTTP 模型接口 |
| 原生 App / 小程序 / Windows 专用组件（pywin32） | 改为内网响应式网页或跨平台 Linux 库 |
| 直写 MES / ERP / SAP 生产数据库 | 通过「数据源」配置只读查询接口，或导出 Excel/CSV 流转 |',
    keywords = '云服务,数据库,云存储,云函数,应用内AI,Excel,CSV,看板,附件,生命周期,脚本运行,python,node,内置技能,记忆,workspace,memory,能力边界,做不了,微服务,GPU,数据源',
    updated_by = 'system',
    updated_at = now()
where id = 'hk_cloud';

-- 4) 新增 hk_ecosystem（企业生态、数据隐私隔离、审核发布与社区规范，同时注入帮助文档与小织机器人）
insert into help_knowledge (
    id, slug, category, title, short_title, badge, summary, content_md,
    keywords, ask_prompt, sort_order, for_docs, for_bot, enabled, updated_by
)
values (
    'hk_ecosystem', 'ecosystem', 'guide',
    '企业生态对接、多员工隔离安全与审核发布', '生态与安全', '隔离',
    '飞书机器人与多维表格、AGW 接口数据源、团队知识库/MCP、多员工数据隔离安全架构、应用登记审核及灵感社区规范。',
    E'### 1. 企业生态能力接入（左侧「生态」页）
- **飞书群机器人（发卡片/告警）**：填入飞书群自定义机器人的 Webhook 地址与可选签名密钥，点「测试连通」即可验证；后续应用通过平台受控代理发交互卡片，密钥绝不暴露到前端源码。
- **飞书多维表格（同步读写）**：直接粘贴浏览器地址栏里的完整多维表格链接，系统自动解析 `appToken` 与 `tableId`，配合飞书自建应用 `App ID` 与 `App Secret`（AES-256-GCM 加密托管）即可直接查询、新增、更新记录。
- **企业 AGW 接口数据源**：点「选择可调用 API」从当前华星账号已获授权的 AGW 目录一键导入，支持在线试调用与多鉴权头自动协商，每个应用最多绑定 **100 个**数据源，均走平台代理取数。
- **团队知识库 & 远端 HTTP MCP**：可粘贴部门规范/指标字典/SOP 建立专属知识库（单条最多 6 万字），或接入标准 Streamable HTTP / JSON-RPC 2.0 MCP 服务并一键「自动拉取工具」。

### 2. 多员工数据隔离与平台安全保障（不会串数据）
- **账号与工作空间严格隔离**：接入华星 SSO 后每位员工绑定唯一 `workspace_id`，所有应用、版本、会话、附件、记忆、脚本运行记录与数据源查询均强制校验归属权。
- **个人生态资产严格隔离**：员工在「生态」页自助添加的飞书连接器、私有知识库与远端 HTTP MCP **仅对自己工作空间可见可用**，其他员工无法枚举、试调用或通过编码沙箱/应用代理跨空间调用。
- **数据库与运行时多层隔离**：云数据库与单体托管 PostgreSQL 均按应用分配**独立 Schema 与最小权限角色**（撤销 `public` 写权限、锁定 `search_path`）；运行容器使用独立 `/data` 目录或独立 K8s Pod。
- **密文托管、脱敏与 SSRF 防护**：所有连接器密钥、MCP Token、数据源凭证与服务环境变量均经 **AES-256-GCM 加密落库**；系统日志与错误响应自动脱敏密码/Token；外发请求内置 SSRF 主机校验，严禁探测内网元数据（`169.254.169.254` 等）及生产环境本机回环地址。

### 3. 发布审核（《Vibe Coding 应用登记表》）与权限控制
- 点击工作台右上角 **「发布」** 后，需填写 **《Vibe Coding 应用登记表》**（应用名称、所属部门、业务背景、解决方案、预期收益、目标用户、部署模式、联系人、预计使用截止日期等）并选择 **「上线后谁能打开」**：
  - **内网公开**：公司内网打开链接即用（适合展示看板、公开工具）；
  - **员工登录**：需华星账号登录后使用，应用可识别当前员工身份；
  - **指定人员**：仅授权名单内的同事可访问（适合敏感台账）。
- 提交后由管理员在「平台管理 → 发布审核」审批，通过即自动上线生效；驳回可查看审核意见并修改重提。

### 4. 灵感社区预览与复刻规则
- 在左侧 **「社区」** 查看同事分享的作品时，**预览区为安全只读展示**，不能直接在他人预览页里操作提交数据；点击 **「使用模板」** 复刻成你自己的应用后，即可在自己的工作台正常使用和二次修改。
- 复刻仅复制应用代码与空表结构，**绝不会带走原作者的任何业务数据与私密凭据**。',
    '生态,飞书,机器人,多维表格,bitable,AGW,数据源,知识库,MCP,安全,隔离,串数据,加密,SSRF,发布,审核,登记表,vibe coding,权限,社区,预览,只读,复刻',
    '不同员工之间的数据会不会串？飞书多维表格、知识库和发布审核怎么用？',
    35, true, true, true, 'system'
)
on conflict (id) do update
set title = excluded.title,
    short_title = excluded.short_title,
    badge = excluded.badge,
    summary = excluded.summary,
    content_md = excluded.content_md,
    keywords = excluded.keywords,
    ask_prompt = excluded.ask_prompt,
    sort_order = excluded.sort_order,
    for_docs = excluded.for_docs,
    for_bot = excluded.for_bot,
    enabled = excluded.enabled,
    updated_by = excluded.updated_by,
    updated_at = now();
