# 部署与配置

本文是部署总览：本地开发、外网模拟内网、容器镜像、Kubernetes、配置项和上线检查。K8s 清单与沙箱的细节在 `deploy/k8s/README.md`。

## 1. 组成

| 组件 | 说明 | 端口 |
|---|---|---|
| zhiye-api | 同一个 jar，profile `api`（生产加 `k8s`）：工作台前端、REST / SSE、模型网关 `/llm/v1`、任务工作进程 | 8080 |
| zhiye-site | 同一个 jar，profile `site`：预览 `/p/{buildId}/`、发布站点 `{slug}.站点域名`、站点同源 `/__zhiye/*` | 8083 |
| runner 沙箱 | 每个任务一个，runner 镜像（Node 22 + Chromium + 模板依赖）；生产用公司 OpenSandbox | 8790（控制端口） |
| PostgreSQL 16 | 控制面 + 每个应用一个云 schema；Flyway 自动迁移 | 5432 |
| 共享卷（NAS） | 运行时目录：`repos/ workspaces/ agents/ logs/`（本地对象存储时还有 `objects/`） | — |
| 对象存储 | 素材 `assets/`、构建产物 `builds/`、云存储 `cloud/`；本地目录或 S3 / OSS | — |

## 2. 本地开发

```bash
bash scripts/dev-up.sh        # PostgreSQL 5433 → 前端构建 → 打包 → api 8080 + site 8083
bash scripts/dev-down.sh      # 停止
```

- 模型上游在 `scripts/env.sh` 按 `ZHIYE_MODEL`（`kimi` / `deepseek`）选择，本机选择写在 `runtime/local.env`；密钥文件只加载进进程环境，不打印、不进仓库。
- **重启前先看有没有进行中的任务**（重启会让它们失败）：
  `psql -h 127.0.0.1 -p 5433 -U zhiye -d zhiye -tAc "select id,type from task where status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING')"`
- 日志：`runtime/logs/api.log`、`runtime/logs/site.log`；任务日志在 `runtime/logs/tasks/{taskId}.log`。

### 2.1 外网模拟内网

```bash
ZHIYE_MOCK_INTRANET=1 bash scripts/dev-up.sh   # api 用 api,dev,mock；site 用 site,mock
```

统一认证（任意工号登录，令牌 `tok-{工号}`）、五星AI岛 openclaw（模型列表、默认模型、额度）、llm-gateway（转发到本地统一上游并按工号计额度）都由服务内置的模拟接口提供，路径与真实服务一致；内网联调只改地址（见 `docs/fivestar-integration.md`）。

不想动 8080 时，可以另起一个只用测试库的模拟实例，见 `docs/testing.md`「模拟内网实例」。

## 3. 镜像

```bash
# runner 沙箱镜像（也是 OpenSandbox 的 sandbox 镜像）
docker build -f deploy/docker/runner.Dockerfile -t zhiye-runner:<tag> .
# 主服务镜像（api / site 共用），在镜像里用 Maven 和 npm 构建
docker build -f deploy/docker/server.Dockerfile -t zhiye-server:<tag> .
# 或复用本机构建产物（server/zhiye-server/target/zhiye-server.jar、web/dist）
(cd server && mvn -q -DskipTests package) && npm run build -w web
docker build -f deploy/docker/server.Dockerfile --build-arg JAR_SOURCE=prebuilt --build-arg WEB_SOURCE=prebuilt -t zhiye-server:<tag> .
```

内网构建时把 `--build-arg MAVEN_MIRROR=`、`NPM_REGISTRY=` 指到公司 Nexus / npm 镜像。改了模板依赖要同步更新 `deploy/docker/*template-cache/package-lock.json`（只取发布满 7 天的版本，见 `AGENTS.md`）。

公司安全网关会对 npm 等外网 HTTPS 请求重新签发 `CSOT` 证书。runner 和导入服务的 Node / Python / Java / Go 基础镜像必须信任 `deploy/docker/csot-root-ca.crt`，不能用 `strict-ssl=false` 绕过。`runner.Dockerfile` 已内置该证书；在已有基础镜像上生成内网版本：

```bash
docker build -f deploy/docker/service-base.Dockerfile \
  --build-arg BASE_IMAGE=node:22-alpine \
  -t <registry>/zhiye/base-node:22-alpine-csot .
```

该镜像同时配置系统 CA、`NODE_EXTRA_CA_CERTS`、pip/requests、curl 和 Git 的 CA 路径。测试 overlay 的 `ZHIYE_SERVICE_IMAGE_*` 使用对应的 `-csot` 镜像；自定义 Dockerfile 的基础镜像由项目作者负责，在内网也应选择已配置公司 CA 的镜像。

若集群出口把 registry 的 `.tgz` 请求拦成“URL过滤”HTML，可配置 `ZHIYE_NPM_TARBALL_CDN=https://cdn.npmmirror.com/packages`。Kaniko 只改写临时构建上下文中的公共 npm tarball URL，runner 安装结束后恢复原锁文件；私有 registry、Git 依赖和 integrity 均不变。自动 Dockerfile 对无锁项目先执行 `npm install --package-lock-only`；自定义 Dockerfile 应提交锁文件并使用 `npm ci`。不要通过关闭 TLS 或 integrity 校验绕过。

## 4. Kubernetes

```bash
kubectl apply -k deploy/k8s/overlays/test    # 测试环境；生产用 overlays/prod
```

`base/` 里是命名空间、ConfigMap（`zhiye-config`）、Secret 模板（`secret.example.yaml`）、api / site Deployment、Ingress 和 NetworkPolicy。测试 overlay 已按公司 RKE2 集群配置 Traefik（SSE 读超时 3600 秒、请求体 100 MiB）、自建 k8s 沙箱与真实域名；TLS Secret、NAS 和密钥仍需按环境准备。通用 OpenSandbox、节点池、kind 本地验证与回滚步骤见 `deploy/k8s/README.md`。

**多副本是支持的**：任务按数据库租约领取，进度事件从数据库推送，停止、确认卡、补充要求都能跨实例送达（`docs/architecture.md` 第 5 节）。

## 5. 配置项

Spring 的环境变量映射：`zhiye.task.max-duration-minutes` ⇔ `ZHIYE_TASK_MAX_DURATION_MINUTES`，下面列的是 `application-k8s.yml` 里显式声明的名字。敏感值只放 Secret。

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | — | api：`api,k8s`；site：`site,k8s` |
| `ZHIYE_DB_URL` / `ZHIYE_DB_USER` / `ZHIYE_DB_PASSWORD`* | — | PostgreSQL 16 |
| `ZHIYE_RUNTIME_DIR` | `/var/lib/zhiye/runtime` | 共享卷挂载点 |
| `ZHIYE_PUBLIC_BASE_URL` | — | 工作台地址（站点登录回跳、预览注入用） |
| `ZHIYE_PREVIEW_BASE`、`ZHIYE_SITE_SCHEME`、`ZHIYE_SITE_HOST_SUFFIX` | — | 预览地址、发布站点的协议和域名后缀（`{slug}.后缀`） |
| `ZHIYE_SITE_TEMPLATE_PREVIEW_BASE` | 同 `ZHIYE_PREVIEW_BASE` | 社区页面快照地址；证书无法覆盖独立预览域名时可设为主站地址，并由 Ingress 把主站 `/t/` 转给 site。只影响静态快照，不改变工作台 `/p/` 预览隔离 |
| `ZHIYE_SITE_BUILD_PREVIEW_BASE` | 同 `ZHIYE_PREVIEW_BASE` | 工作台应用预览地址；证书只覆盖主站时可设为主站地址，并由 Ingress 把主站 `/p/` 转给 site。`/p`、`/t` 文档响应带 `CSP sandbox`，不能读取主站登录态；服务型应用的独立站点域名不受此项影响 |
| `ZHIYE_STORAGE_TYPE` + `ZHIYE_S3_*`（`ACCESS_KEY`*、`SECRET_KEY`*） | `local` | 对象存储 |
| `ZHIYE_CLOUD_SECRET`* | — | 云服务令牌签名密钥，k8s profile 下必填（≥ 32 位随机串） |
| `ZHIYE_TASK_CONCURRENCY` | 4 | 每个实例同时执行的任务数 |
| `ZHIYE_TASK_MAX_MINUTES` | 60 | 一段运行（两次「继续」之间）的防卡死时限 |
| `ZHIYE_TASK_CONTINUE_WAIT_MINUTES` | 30 | 「要继续吗？」等多久没回应就暂停 |
| `ZHIYE_TASK_SHUTDOWN_WAIT_SECONDS` | 600 | 优雅停机：收到 SIGTERM 后停止领新任务，等在跑任务做完的最长时间（期间手动续约租约，防止被误判 worker_lost）；K8s 的 `terminationGracePeriodSeconds` 必须大于它 |
| `ZHIYE_SANDBOX_MAX_HOURS` | 8 | 沙箱存活上限（覆盖多次继续） |
| `ZHIYE_SANDBOX_PROVIDER` | `opensandbox` | `opensandbox` / `k8s`（回滚）/ `local`（site 固定 local） |
| `ZHIYE_OPENSANDBOX_*`、`ZHIYE_RUNNER_IMAGE`、`ZHIYE_SANDBOX_PVC`、`ZHIYE_SANDBOX_CPU_*`、`ZHIYE_SANDBOX_MEMORY_*` | 见 ConfigMap | 沙箱 |
| `ZHIYE_GATEWAY_URL` | `http://zhiye-api.zhiye.svc:8080/llm/v1` | 沙箱访问织页模型网关的集群内地址 |
| `ZHIYE_SERVICE_IMAGE_*`、`ZHIYE_NPM_TARBALL_CDN` | 见测试 overlay | 导入服务基础镜像；可选的公共 npm tarball CDN 转换 |
| `KIMI_API_KEY`* / `DEEPSEEK_API_KEY`* + `ZHIYE_LLM_*` | — | 本地统一上游（不开五星AI岛时） |
| `ZHIYE_AUTH_MODE` | `anonymous` | `sso` 开启统一登录 |
| `ZHIYE_AUTH_LOGIN_ALLOWLIST_ENABLED` | `true`（base 配置） | 人员管理：sso 下开启登录白名单，仅「管理 → 人员管理」中启用的人员与管理员可登录，其他账号登录返回 403 `PLATFORM_ACCESS_DENIED`；本地开发/测试默认关闭 |
| `ZHIYE_SSO_GATEWAY_URL`、`ZHIYE_SSO_CLIENT_ID`、`ZHIYE_SSO_REDIRECT_URL`、`ZHIYE_SSO_SECURE_COOKIE` | 测试网关 / `SSO-METIS` / — / `true` | 统一认证；回调地址要在网关登记，形如 `https://织页域名/access_token` |
| `ZHIYE_FIVESTAR_ENABLED`、`ZHIYE_FIVESTAR_ORG`、`ZHIYE_OPENCLAW_BASE_URL`、`ZHIYE_LLM_GATEWAY_URL` | `false` / 31 / — / — | 五星AI岛模型、额度、llm-gateway 路由（需要 sso） |
| `ZHIYE_FIVESTAR_PROJECT_ID` | `zhiye` | 发往 llm-gateway 的来源项目标识 |
| `ZHIYE_OPENCLAW_INTERNAL_TOKEN`* | — | 调 openclaw 内部接口的服务令牌（api 和 site 都要） |
| `ZHIYE_FIVESTAR_PUBLIC_KEY`* | — | 公共免费虚拟 Key：`billingType=FREE` 的模型用它调网关，不占个人额度（api 和 site 都要） |
| `ZHIYE_SKILLHUB_ENABLED`、`ZHIYE_SKILLHUB_URL`、`ZHIYE_SKILLHUB_FILE_URL`、`ZHIYE_SKILLHUB_UPLOAD_URL`、`ZHIYE_SKILLHUB_PORTAL`、`ZHIYE_SKILLHUB_ORG` | `false` / — / — / — / 测试详情页 / 31 | 公司 SkillHub 浏览、安装和发布；上传地址为 `http://网关/tfle/v1`，发布前先把 ZIP 传到文件服务 |
| `ZHIYE_SOURCES_ALLOWED_HOSTS` | 空 | 数据源代理允许访问的主机（逗号分隔，支持 `*.域名`） |
| `ZHIYE_AGW_CLIENT_ID`、`ZHIYE_AGW_CLIENT_SECRET`*、`ZHIYE_AGW_CLIENT_TOKEN_URL`、`ZHIYE_AGW_CLIENT_BASE_URL` | 见 ConfigMap | 手动数据源的 `agw_client` 鉴权：客户端模式取 Metis Token，再换 AGW JWT；Secret 只注入 api 和 site |

独立集群部署时，上述外部服务地址必须使用织页集群可访问的平台网关 / Ingress，不能填对方集群的 `*.svc` 地址。后台模型调用只向 openclaw 传工号并换取个人虚拟密钥，平台 accessToken 不进沙箱或 llm-gateway；完整身份链路和待确认请求头见 `docs/fivestar-integration.md`。

`deploy/k8s/overlays/{test,prod}` 中的 `<...>` 是部署前必须替换的环境参数，不是未完成的功能代码；`application-mock.yml` 和 `controller/mock/` 中的地址、模型、凭证只用于本地模拟，不应配置成真实生产值。业务常量（如分页大小、校验正则、能力上限）不需要全部配置化，只有环境地址、密钥、资源和确需按环境调整的限额才放到 ConfigMap / Secret。

\* 放 Secret `zhiye-secrets`。

## 6. 上线检查

1. 数据库：RDS PostgreSQL 16 可达，账号有建 schema 权限（每个应用的云数据库是一个 schema）；首次启动 Flyway 自动迁移到最新版本（日志里 `now at version vNN`）。
2. 密钥：`ZHIYE_CLOUD_SECRET`、数据库密码、对象存储、OpenSandbox、（开五星时）`ZHIYE_OPENCLAW_INTERNAL_TOKEN` 和 `ZHIYE_FIVESTAR_PUBLIC_KEY` 都在 Secret 里；模型密钥只给 api。
3. 域名：工作台、预览、发布站点三个域名（站点用通配符证书）；`ZHIYE_PUBLIC_BASE_URL`、`ZHIYE_PREVIEW_BASE`、`ZHIYE_SITE_HOST_SUFFIX` 一致。证书 SAN 必须覆盖工作台、预览及 `*.zhiye.csot.tcl.com` 这类发布站点泛域名；只有 `*.csot.tcl.com` 时不能覆盖多级子域名，浏览器会报 `ERR_CERT_COMMON_NAME_INVALID`。
4. Ingress：SSE 不缓冲、读超时 ≥ 10 分钟、请求体 ≥ 61 MB（导入）。
5. 沙箱：OpenSandbox 能创建沙箱、PVC 能挂进去、沙箱能访问 `ZHIYE_GATEWAY_URL`、出网默认拒绝（`deploy/k8s/README.md` 第 9 节逐项验证）。
6. 登录：开 sso 时回调地址已登记；登录 → 回到原页面；会话过期后被引导重新登录（草稿保留）。
7. 五星AI岛：模型菜单能列出员工配置的模型和默认模型、额度行显示剩余和重置时间；额度用完时任务提示重置时间（`docs/fivestar-integration.md` 联调清单）。
8. 冒烟：创建一个点击计数应用 → 计划 → 生成 → 预览能点 → 发布后站点能打开。

## 7. 运维

- **滚动发布**：进行中的任务在旧实例被停止时会因租约过期标成 `worker_lost`（可重试）。发布前确认没有长任务，或在低峰发布。
- **扩容**：api 副本数 × `ZHIYE_TASK_CONCURRENCY` = 同时执行的任务上限；沙箱资源按每任务 2 CPU / 4 Gi 估算。
- **日志**：任务日志在共享卷 `logs/tasks/{taskId}.log`；沙箱日志里有 `[java] 创建 OpenSandbox 沙箱` 等关键行。
- **备份**：数据库（含所有应用的云 schema）+ 共享卷的 `repos/`（每个应用的 git 仓库）+ 对象存储。
