# 能力边界、已知限制与可优化 / 扩展项

生成规模能力的**唯一来源**是 `server/.../constants/Capabilities.java`：计划、创造页会话、生成、修改、小织的提示词都引用它；数据源绑定上限在 `DataSourceService.MAX_PER_APP`。调整后同步本文和离线 FAQ `web/src/lib/helpFaq.ts`。本文是给人看的汇总，数字以代码为准。

## 一、一次能做多大

| 项目 | 上限 | 位置 |
|---|---|---|
| 页面数量 | 不限 | `Capabilities.LIMITS` |
| 云数据库表 | 每个应用 ≤ 50 张（表结构校验硬限制） | `Capabilities.MAX_TABLES`、`CloudService` |
| 角色 / 每条流程的状态 | 不限，按业务需要设计 | `Capabilities.LIMITS` |
| 应用绑定的数据源 | 每个应用 ≤ 100 个 | `DataSourceService.MAX_PER_APP` |
| 执行步数 | 不限；每 60 步弹「要继续吗？」 | `zhiye.task.max-agent-turns` |
| 任务 token | 不设上限；只受五星AI岛个人额度约束 | `LlmRouter`、`QuotaText` |
| 一段运行时长 | 60 分钟（防卡死，继续后重新计时） | `zhiye.task.max-duration-minutes` |
| 等待「继续」 | 30 分钟，超时暂停（可重试接着做） | `zhiye.task.continue-wait-minutes` |
| 沙箱存活 | 8 小时 | `zhiye.task.sandbox-max-hours` |
| 自检修复 | 校验失败自动交回 AI 修 3 轮 | `zhiye.task.verify-fix-rounds` |

超过规模时：计划只写「第一期」并附「## 分期计划」；有做不到的部分附「## 做不到的部分」，计划卡上会显示说明。

## 二、平台做不到的事（以及替代做法）

| 做不到的需求 | 替代做法 |
|---|---|
| 原生 App、小程序、桌面客户端、扫码从外网访问 | 网页应用，公司内网电脑或手机浏览器打开 |
| 直连生产数据库，或往 MES / ERP / SAP 写数据 | 管理员登记只读查询接口作为「数据源」；没有接口先用 Excel / CSV 导入 |
| 自建后端、消息队列、定时任务、长时间后台程序 | 页面 + 云数据库 + 云函数（单次 10 秒、128 MB、返回 1 MB）；批处理做成「命令行脚本」项目 |
| 支付、短信、邮件群发、开放给外部用户 | 不做；内部用状态提醒、待办列表 |
| 实时多人协同、音视频、直播、3D、语音识别 | 不做；协作改成「提交 → 审批」 |
| 训练模型、自建 AI 服务 | 应用内 AI（对话、抽取、总结、识图） |
| 统一登录以外的账号体系 | 平台的「员工登录」 |

## 三、使用配额

| 项目 | 默认值 |
|---|---|
| 每个工作空间的应用数 | 10（删除的不算，导入中的算）`zhiye.workspace.max-apps` |
| 同时进行的写任务 | 每人 1 个（含计划任务）；其余消息排队 |
| 每天创建任务数 | 本地 200 / 生产 50 `zhiye.workspace.daily-task-limit` |
| 每条消息可选技能 | 5 个 |
| 导入代码 | 压缩包 50 MB、5000 个文件；跳过 `node_modules`、`.git` |
| 应用内 AI | 每应用每分钟 60 次（`RateLimitService` 数据库全局滑动窗口计数），单次 40 条消息 / 6 万字 / 4 张图 |

## 四、已知限制（技术）

| 限制 | 影响 | 处理 / 建议 |
|---|---|---|
| 「思考过程」的逐字增量只推给同实例的连接 | 多副本时，连到别的实例的页面按 500ms 落库批次看到思考内容 | 可接受；需要时用 PostgreSQL LISTEN/NOTIFY 或 Redis 转发 |
| 没有沙箱预热池 | 每个任务冷启动沙箱，取决于 OpenSandbox 调度与镜像拉取 | 节点预拉镜像；以后可做预热池 |
| NAS 上构建 | 大量小文件的导入项目 `npm install` 慢 | 导入任务把依赖放到沙箱临时目录 |
| 五星AI岛按工号取路由的接口还没有 | 外网用模拟接口跑通；内网需要 openclaw 新增 `/internal/zhiye/llm/route` | 见 `fivestar-integration.md` 第三节 |
| 统一认证、五星AI岛、SkillHub、星claw 都只能在内网联调 | 外网全部用模拟接口；内网要按清单逐项验证 | `fivestar-integration.md` 联调清单 |
| Vue 项目只支持对话修改 | 没有可视化编辑 | 有需要再给 Vue 模板接编辑桥 |
| `zhiye-common`、`zhiye-api` 两个模块目前为空 | 只是按五星AI岛的模块划分预留 | 有对外契约或共享 DTO 时再放进去 |

## 五、可优化与扩展项（建议的优先级）

**P0：上内网前**

1. 内网联调：统一认证（真实网关、回调地址登记）、五星AI岛模型和额度、llm-gateway 个人路由、额度用完的 429 文案；SkillHub 真实市场 / 安装 / 发布审核、API 网关目录导入与授权调用也按 `fivestar-integration.md` 和 `data-sources.md` 逐项验证。上述功能代码与本地 Mock 已完成，待真实环境验收。
2. openclaw 提供 `/internal/zhiye/llm/route`（服务间令牌，按工号 + 模型返回网关地址、模型名、虚拟密钥），并确认 llm-gateway 的个人身份与项目来源请求头语义。
3. 生产配置：`ZHIYE_CLOUD_SECRET`、`ZHIYE_SSO_REDIRECT_URL`、站点域名与证书、OpenSandbox 地址和 PVC、Ingress 的 SSE 超时（≥ 10 分钟，关闭缓冲）。独立集群部署时，外部服务地址必须走可跨集群访问的平台网关 / Ingress，不能填对方集群的 `*.svc` 地址。

**P1：功能补齐**

4. 星claw 飞书多维表格只读数据源：经星claw `feishu/tool-proxy` 以员工飞书身份读取，织页侧只放行多维表格只读接口；缓存、限流，默认仅自己可见；飞书令牌刷新以星claw 为准。
5. 错误信息国际化：业务异常改成 messageKey + `i18n/messages*.properties`（对齐五星AI岛写法），前端仍展示中文。
6. 看板模板的真实生成抽测与调优（9 个模板逐个生成，检查联动筛选、下钻、导出、结论文字）。
7. 数据源访问者身份透传：当前运行时固定使用应用身份；需要按员工隔离上游数据时，再与 API 网关定义并验证员工身份传递。

**P2：体验与运维**

8. ~~「继续」检查点：在确认卡上显示这一段做了什么（待办完成情况）~~（已实现：`TaskExecutor.continueProgressItems` + `ConfirmCard`）。
9. ~~版本卡位置：一个任务里有「继续」补充消息时，版本卡显示在补充消息之后~~（已实现：`orderMessagesWithSteers`）。
10. ~~没有草稿时 `GET /draft` 返回 404 会在浏览器控制台留一条红字~~（已改为 HTTP `200 null`）。
11. 监控：任务耗时 / 失败码分布、沙箱冷启动时间、模型调用延迟与额度错误率（Micrometer + 平台监控）。
12. 沙箱预热池、构建缓存，缩短首个版本的等待时间。
13. 多人协作（同一应用多人编辑）、应用级审批流模板、更多领域模板。
14. ~~测试稳定性：`Client.createApp` 不应假设计划任务在创建响应里仍是 `activeTask`~~（已修复：`activeTask` 为空时直接回查应用状态）。
