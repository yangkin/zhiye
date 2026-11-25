# 织页（Zhiye）内网交接包

内部 AI 应用创建工作台：对话生成应用、计划确认、预览修改、版本管理、发布部署、
云数据/权限/云函数、应用级 AI、技能（内置 / 自建 / SkillHub）、模板社区。

## 包内容

| 路径 | 说明 |
|---|---|
| `server/` | Spring Boot 3 / Java 17 多模块（zhiye-server 主服务，zhiye-common / zhiye-api 占位） |
| `web/` | React + TS 前端；`web/dist` 已构建好 |
| `runner/` | Node agent runner（任务执行、沙箱内构建） |
| `packages/` | 应用模板、内置技能、云函数宿主 |
| `scripts/` | `dev-up.sh` 本地一键启动（`ZHIYE_MOCK_INTRANET=1` 模拟内网全套） |
| `deploy/` | Dockerfile、k8s kustomize（base + overlays）、OpenSandbox 配置 |
| `docs/` | 架构、能力边界、部署、测试、API 契约、技术设计、五星AI岛对接清单 |
| `e2e/` | Playwright 端到端 + 布局审计/功能回归脚本 |
| `zhiye-server-prebuilt.jar` | 已构建的服务端 jar（57MB，源码改动后需重新 `mvn package`） |

## 快速开始

```bash
# 本地开发（默认：免登录 + 本地 DeepSeek，需要 DEEPSEEK_API_KEY）
bash scripts/dev-up.sh

# 模拟内网（SSO 登录 + 五星AI岛模型/额度 + SkillHub，工号任意填）
ZHIYE_MOCK_INTRANET=1 bash scripts/dev-up.sh
# → http://127.0.0.1:8080 ，登录页输入工号即可
```

内网部署见 `docs/deployment.md`；与五星AI岛（fivestar-service / openclaw-service）
及平台 SSO 的对接清单见 `docs/fivestar-integration.md`。

## 构建

```bash
cd server && mvn -DskipTests package        # → zhiye-server/target/zhiye-server.jar
cd web && npm ci && npm run build          # → web/dist
cd runner && npm ci                        # runner 依赖
```

测试：`cd server && mvn test`（需本地 PostgreSQL，测试库配置见
`server/zhiye-server/src/test/resources/application-test.yml`）。

## 当前集成状态

- **SSO**：测试环境真实登录、回调、会话、退出和管理员身份已验证；部署时登记正式回调并复测。
- **五星AI岛模型与额度**：`/internal/llm/models`、`/internal/llm/route`、个人虚拟 Key 调 llm-gateway、真实问答及额度扣减均已验证；应用生成、修改和发布链路也已通过。仍需对方确认网关日志中的项目来源标记。
- **SkillHub**：真实分类、搜索、详情、ZIP 下载、安装、新增私有草稿和发布记录已验证；安装技能已在真实编码任务中读取并生效。提交审核被测试环境安全扫描 `SECURITY_BLOCKED` 拦截，需五星AI岛排查后才能进入人工审核。
- **AGW API**：已接入当前用户 `/me/callable-apis` → 授权复核 → API 详情/参数契约 → 应用绑定与代理调用；Token 按身份缓存在服务端内存。真实清单、添加、用户态数据面调用和业务返回值均验证通过；项目 Key / V1 签名兼容保留。
- 飞书多维表格只读数据源（星Claw）**尚未实现**，见 `docs/capabilities.md` 待办。
- 最新构建与测试结果见 `docs/test-report.md`；真实模型端到端测试说明见 `docs/testing.md`。

## 当前状态（2026-11-24 深夜）

- 已把 GitHub 最新 main（agent 自主工具链 verify_app/find_files/grep_files、choice 确认、全栈单体托管、工作空间记忆、动态帮助知识库、场景模板新封面）三路合并到本地；合并时修复了远端新代码的 AppRow `visible` 字段错位（技能/导入应用被误置为不可见）和 Windows PATH 分隔符问题。
- 数据源只能在 AGW 侧登记：前端无手工登记入口，`POST /api/sources` 一律 403 `SOURCE_MANUAL_DISABLED`；AGW 旧目录/AppKey 导入流程已移除（保留 callable-apis 新链路）。
- 输入框弹层修复：模型/技能弹层跟随滚动与锚点尺寸变化；「生成模式」滑杆已去掉；模型显示短名（`shortModelLabel`，去供应商前缀与日期版本后缀）；技能/数据源芯片区有高度上限和省略号。
- 测试：除 `MonolithServiceE2ETest`（真实起 Python/Node/Go/Java 进程，本机 Windows 缺 Go 等运行时，环境受限）外全绿。
- 集群：server / runner `v0.1.0-test` 镜像由本机 WSL Docker 构建并推入 Harbor（集群出口对 npm/apt 有 URL 过滤，不适合在集群内构建）；overlay 已应用到 `zhiye` 命名空间，api/site 因 RDS 网络暂未就绪。
- 集群：`zhiye` 命名空间下 api/site 已 Running（合并后最新镜像），Flyway 迁移到 v34；主域名 `https://zhiye.csot.tcl.com` 200，`/api/meta` 显示 sso + fivestar + skillhub 全开，预览与泛域名 DNS 均已生效，TLS 暂用自签证书 `zhiye-test-tls`。RDS 白名单与 DNS 已于当日由网络同事开通。
- 剩余用户验收：SSO 登录 → 模型菜单列出个人模型与额度 → 创建应用跑通沙箱生成 → 发布站点打开。
