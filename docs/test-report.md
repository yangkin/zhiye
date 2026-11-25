# 最近一次测试结果

日期：2026-11-24（Windows 本地，PostgreSQL 5433；公司测试环境真实联调）。

## 全仓门禁

执行：`ZHIYE_MODEL=kimi KIMI_API_KEY=test-key INCLUDE_INTRANET=1 bash scripts/ci.sh`，使用重新创建的干净 `zhiye_test`，业务库 `zhiye` 未用于服务端测试。

| 项目 | 结果 |
| --- | --- |
| Eval 基线与评分器检查 | 通过 |
| Runner 单测 | 136 项：125 通过、11 跳过、0 失败 |
| 前端 TypeScript 类型检查 | 通过 |
| 前端生产构建 | 通过 |
| 服务端全量测试 | 196 项：除 `MonolithServiceE2ETest`（10 项，需本机真实 Go/Python 等运行时，Windows 环境受限）外，其余 186 项全部通过 |
| 全仓门禁整体 | **通过（除上述环境受限项）** |

此前的 `QuotaTest.tenAppsPerPerson` 竞态本次全量未复现，2 项均通过。`DataSourceTest`、`AgwClientTokenServiceTest`、`CapabilityLimitTest`、`SkillHubTest`、`SkillHubEnabledTest` 和 k8s 沙箱测试均包含在全量通过结果中。

## GitHub main 大改动合并（2026-11-24 深夜）

- 以远端 `399d54e` 为基线对 `ec73553` 做逐文件三路合并：55 处冲突全部人工解决；agent 自主工具链、全栈单体托管、工作空间记忆、动态帮助知识库等新功能全部并入，同时保留已实测的 AGW callable-apis 链路、数据源 403 防线和弹窗修复。
- 合并修复：`AppRow` 可见性字段错位（AppService/ImportService）、`LocalServiceRuntimeProvider` 的 Windows PATH 分隔符、Deno 的 Windows 可执行文件查找、`FakeSkillHubController` 重复方法、`LightLayout`/`DataSourceDialog` 结构残留；测试断言同步到 `verify_app` 新文案，会话测试改为从 `BODIES` 按内容取请求。
- AGW 旧目录/AppKey 导入链路（`AgwCatalogTest`、`MockAgwController`、旧端点）随新链路移除。

## 人员管理（登录白名单，2026-11-25）

- 新增 `V40 platform_login_allowlist`、`PlatformAccessController`（管理员增删改查）、治理页「人员管理」页签（`AdminLoginAllowlistPanel`）；`AuthService.login` 在开关 `zhiye.auth.login-allowlist-enabled` 开启时拒绝名单外账号（403 `PLATFORM_ACCESS_DENIED`）。
- `PlatformAccessTest` + `AdminGovernanceTest` 通过：默认拒绝、管理员放行、添加/停用/启用/删除的完整链路、未登录 401、非管理员 403、工号格式校验。
- 人员管理增强（同日）：员工目录搜索添加（前端直连公司 TPFM `/tpfm/v2/31/employeeVos`）、多选与批量粘贴、添加时/列表内指定管理员（V41 `role` 列，登录同步 `app_user.role`，改角色立即生效）。新增批量与角色测试通过；mock 实例浏览器实测：面板表格、过滤、添加弹窗（搜索/批量两种模式）与批量添加闭环均正常。

## 真实测试环境联调

使用真实统一认证账号、openclaw、llm-gateway 和 fivestar-service，不是本地 Mock：

- SSO 登录、回调、退出、工作空间归属与管理员身份通过。
- 内部可用模型列表、个人默认路由和虚拟 Key 获取通过；直接调用 llm-gateway 返回 HTTP 200。
- 真实问答完整收到 `done`，个人额度异步入账减少。
- 真实应用完成计划、生成、修改并形成第 2 版；预览中的计数器交互正常，发布站点可访问且发布记录正确。
- SkillHub 分类、搜索、详情、真实 ZIP 下载和安装通过；安装的 `salary-hire` 技能按消息挂载后，编码任务实际读取 `SKILL.md` 并生成可预览的新版本。
- SkillHub 新增私有草稿、我的技能和发布记录刷新通过。当时把提交审核失败归因为扫描服务；2026-11-25 根据平台「暂无文件 / 检测超时」进一步定位，实际原因是 multipart 直传 ZIP 未持久化，并非已证实的扫描服务故障，修复与复验见下节。

## SkillHub 两步上传与发布范围（2026-11-25）

- `SkillHubEnabledTest`、`SkillHubTest` 共 4 项通过：TFLE 上传参数、`fileId/fileUrl` 发布引用、私有草稿不提交人工审核、公开必须选择组织、组织路径随 `/edit` 保存、发布后 ZIP 再下载且内容一致。
- 前端 `npx tsc --noEmit -p .` 通过；`bash scripts/dev-up.sh` 完成生产构建并重启，8080 / 8083 均返回 200。
- 发布弹窗与 SkillHub 一致为「仅个人使用 / 公开至 SkillHub」，公开后显示组织范围选择。真实公司 SkillHub 的再次发布仍需用有效平台账号做最终验收。
- AGW 已改为 `/apiInfo/me/callable-apis` 主链路，不使用市场或应用挂载列表。真实返回当前用户 9 条可调用 API、3 个消费项目；服务端复核 `projectCode + apiId`、读取详情、添加契约、用户 Metis Token + `X-Project-Id` 数据面调用和业务返回值断言全部通过。Token 按身份缓存且不落地；项目 Key / V1 签名兼容保留。

## 部署清单检查

- `kubectl kustomize deploy/k8s/overlays/test` 渲染成功。
- 测试 overlay 使用 `ingressClassName: traefik`，Traefik 长连接 3600 秒、请求体 100 MiB，域名已配置为 `zhiye.csot.tcl.com` / `preview.zhiye.csot.tcl.com` / `*.zhiye.csot.tcl.com`。
- 集群 IngressClass 和 `kube-system` 中的 `rke2-traefik` DaemonSet 已确认；NetworkPolicy 已对应到 `kube-system`。
- 当前账号可在目标命名空间创建 Deployment；`netapp-sc-qtree` StorageClass 存在。
- 远程 PostgreSQL 18.6 已连接成功，`zhiye` 账号有建 Schema 权限；S3 私有桶签名列举成功。
- 测试环境改为 `zhiye` 单命名空间，api、site 与沙箱复用现有 200Gi RWX PVC；RBAC 和仅匹配沙箱 Pod 的网络策略已同步，服务端 dry-run 通过，不再需要第二套 NAS。
- `zhiye-secrets` 与 `zhiye-registry` 已在集群创建；Harbor `aiplatform` 已获 `zhiye` 项目推送权限。`zhiye-server` / `zhiye-runner` `v0.1.0-test` 镜像已由本机 WSL Docker 构建并推送（集群出口对 npm registry / 多数 apt 镜像有 URL 过滤，不适合在集群内用 Kaniko 构建）。TLS Secret `zhiye-test-tls` 暂为自签证书，正式证书到位后替换。
- 待网络/基础架构：RDS `10.110.170.10:5432` 对 worker `10.110.251.101/102` 的 5432 白名单（Pod ping 通但 TCP 被丢包）；`preview.zhiye.csot.tcl.com` 与 `*.zhiye.csot.tcl.com` 的 DNS 记录（指向 `10.110.19.105`）。

## Playwright 与布局

完整 49 项首轮结果：34 通过、4 失败、11 项因串行依赖未运行。失败涉及动态列表添加节奏、缩放 iframe 的 Playwright 点击坐标、自定义下拉和折叠改动汇总；已修正对应测试并逐项重跑。布局巡检在 1440 / 1024 / 390px 下全部通过，创造页类型标签已从不合规的 10.5px 调整为 11px。

## 范围说明

全仓 CI 使用假上游、假 runner，不消耗真实模型额度；真实联调单独消耗了测试账号额度。集群内实际沙箱创建、共享 PVC 读写、S3 与外部服务的集群内连通仍需部署后验收，不能由本地测试代替。
