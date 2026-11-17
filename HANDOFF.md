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

- **SSO**：登录、回调、会话、退出和令牌转发代码已完成；测试网关登录跳转已验证。仍需登记织页回调并用真实账号跑完整登录。
- **五星AI岛模型与额度**：代码和本地 Mock 链路已完成；真实后台调用仍需 openclaw 提供 `/internal/zhiye/llm/route`，并确认 llm-gateway 的个人身份、额度和项目来源请求头语义。
- **SkillHub**：市场、下载、安装、首次 / 再次发布、审核状态和可见范围已按 fivestar-service 接口实现并通过本地契约测试；真实环境联调未完成。
- **API 网关数据源**：目录查询、公开契约导入、应用绑定（每应用最多 100 个）和签名代理已实现；仍需真实目录权限及调用项目凭证联调。
- 飞书多维表格只读数据源（星Claw）**尚未实现**，见 `docs/capabilities.md` 待办。
- 最新构建与测试结果见 `docs/test-report.md`；真实模型端到端测试说明见 `docs/testing.md`。
