# 最近一次测试结果

日期：2026-11-17（Windows 本地，PostgreSQL 5433）。

## 本次变更

- 每个应用绑定的数据源上限从 10 调整为 100；创建应用和工作台绑定使用同一上限。
- 角色数量与每条业务流程状态数量不再限制；云数据库每应用 50 张表的限制保留。
- 补齐独立集群身份传递说明、openclaw 路由接口约定和 K8s 外部集成配置。

## 全仓门禁

执行：`ZHIYE_MODEL=kimi KIMI_API_KEY=test-key INCLUDE_INTRANET=1 bash scripts/ci.sh`，使用新建的干净 `zhiye_test`。原测试库保留为 `zhiye_test_backup_20261117_152027`，业务库 `zhiye` 未修改。

| 项目 | 结果 |
| --- | --- |
| Eval 基线与评分器检查 | 通过；12 个场景契约有效，React / Vue 两个模板基线均通过 |
| Runner 单测 | 126 项：119 通过、7 跳过、0 失败 |
| 前端 TypeScript 类型检查 | 通过 |
| 前端生产构建 | 通过 |
| 服务端全量测试 | 164 项：163 通过、0 跳过、1 失败 |
| 全仓门禁整体 | **未全绿**，唯一失败见下节 |

服务端唯一失败为 `QuotaTest.tenAppsPerPerson`：假计划任务在创建响应生成前已经完成，`Client.createApp` 假设 `activeTask` 仍存在，随后请求 `/api/tasks/` 得到 404。该用例与本次数据源 / 角色状态改动无关；单独重跑 `QuotaTest` 为 **2 个通过、0 失败**。已在 `docs/capabilities.md` 记录测试稳定性待办，本次未修改这项无关业务逻辑。

本次改动直接相关的 `DataSourceTest` 与 `CapabilityLimitTest` 定向运行结果为 **4 个通过、0 失败**；全仓运行中 `DataSourceTest` 也为 **3 个通过、0 失败**，包含绑定 100 个成功、第 101 个被拒绝的边界用例。

## 范围说明

- 上述测试使用假上游、假 runner 和本地 Mock，不消耗真实模型额度。
- 没有把真实 SSO、openclaw / llm-gateway、SkillHub 或 API 网关写成已联调通过；这些仍需公司内网真实环境验收。
- 测试日志存在 Windows 临时目录 / Git 对象清理的 `AccessDeniedException` 警告，未作为测试通过依据，后续可单独治理。
