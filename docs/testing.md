# 测试

## 1. 分层

| 层 | 位置 | 依赖 | 命令 |
|---|---|---|---|
| 前端类型检查 / 构建 | `web/` | — | `cd web && npx tsc --noEmit -p .`；`npm run build -w web` |
| runner 单测 | `runner/test/*.test.mjs` | `runtime/template-cache`（`dev-up.sh` 会准备） | `cd runner && npm test` |
| 黄金评测基准（离线 / 在线） | `evals/` | 离线：模板缓存 + Chrome；在线：本地 8080 | `node evals/run-eval.mjs --offline`；`node evals/run-eval.mjs --live` |
| 服务端集成测试 | `server/zhiye-server/src/test` | PostgreSQL `zhiye_test`（5433）；假上游 / 假统一认证 / 假 runner 都在测试里 | 见下 |
| 全量 CI 门禁 | `scripts/ci.sh` | PostgreSQL + Node + JDK | `bash scripts/ci.sh` |
| 功能回归（不调模型） | `e2e/qa-func.mjs` | 本地 8080 / 8083 | `cd e2e && node qa-func.mjs` |
| 页面布局巡检 | `e2e/layout-audit.mjs` | 本地 8080 | `cd e2e && node layout-audit.mjs /tmp/zhiye-audit` |
| 端到端（真实模型） | `e2e/tests/*.spec.ts`（Playwright，api / ui 两个项目） | 本地 8080 / 8083 + 模型密钥 | `cd e2e && npx playwright test` |
| 登录过期引导、继续检查点（界面） | `e2e/qa-auth-expiry.mjs`、`e2e/qa-continue.mjs` | 模拟内网实例（见第 3 节） | `ZHIYE_BASE_URL=http://127.0.0.1:8090 node e2e/qa-…mjs` |

服务端测试：

```bash
cd server && JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  JAVA_TOOL_OPTIONS="-DsocksNonProxyHosts=127.0.0.1|localhost|*.localhost" mvn -o test
# 只跑几个类
mvn -o test -pl zhiye-server -Dtest='ContinueCheckpointTest,TaskSignalRelayTest' -Dsurefire.failIfNoSpecifiedTests=false
```

- 这台机器有系统级 SOCKS 代理，不加 `socksNonProxyHosts` 时 JDBC 连 127.0.0.1 会失败。
- 配置不同的测试类（`@TestPropertySource`：`FivestarQuotaTest`、`CloudSiteLoginTest`、`SkillHubEnabledTest`）会各起一个 Spring 上下文；它们用 `currentSchema=ctx_*` 各用一个 schema，否则各上下文的任务工作进程会领走彼此的任务（配置不同，会随机失败）。新增这类测试时照抄。
- 假上游 / 假 runner 的指令开关：需求里带 `[big]`（返回分期计划）、`[turnlimit]`（撞一次步数检查点）、`[confirm]`（申请启用云服务）、`[slowstream]`（会话慢速流）、`[doc]`（会话返回文件块）、`[vue]`（计划返回 Vue 形态）等，见 `support/FakeUpstreamController.java`、`server/dev/fake-runner.mjs`。

## 2. 端到端注意事项

- 端到端用例用的是**真实模型**，每次跑都会消耗额度，全量约 30～60 分钟；每个用例用新的工作空间，不影响已有数据。
- 跑之前确认本地没有进行中的任务（会抢同一个工作进程的并发名额），并且 8080 / 8083 是最新代码（`bash scripts/dev-up.sh` 会重新打包）。
- 多个 Playwright 进程同目录跑会互相清 `test-results`，配置已按启动时间隔离；需要固定目录时 `ZHIYE_PW_OUT=test-results/xxx`。
- `qa-func.mjs` 的 K01 会让 AI 设计表结构（真实模型），之后表的数量和顺序不固定；K06 已改成按名字切到 K02 写入数据的那张表。

## 3. 模拟内网实例

不想重启 8080 时，可以另起一个只用测试库、开了模拟统一认证 / 五星AI岛 / llm-gateway、用假 runner 的实例（不打包、不覆盖 8080 正在用的 jar）：

```bash
cd server
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 ZHIYE_ROOT=$(cd .. && pwd) LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 \
       JAVA_TOOL_OPTIONS="-DsocksNonProxyHosts=127.0.0.1|localhost|*.localhost"
set -a; . /tmp/claude-501/zhiye-secrets/deepseek.env; set +a; . ../scripts/env.sh   # 计划阶段走真实模型
mvn -o -q install -N -DskipTests && mvn -o -q install -DskipTests -pl zhiye-common,zhiye-api && mvn -o -q compile
P=http://127.0.0.1:8090
mvn -o -q spring-boot:run -pl zhiye-server -Dspring-boot.run.profiles=api,dev,mock -Dspring-boot.run.arguments="\
--server.port=8090 --spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/zhiye_test \
--zhiye.runtime-dir=/tmp/zhiye-mockcheck/runtime --zhiye.public-base-url=$P \
--zhiye.auth.sso.gateway-url=$P/mock/sso --zhiye.auth.sso.redirect-url=$P/access_token \
--zhiye.fivestar.openclaw-base-url=$P/mock/openclaw --zhiye.fivestar.llm-gateway-base-url=$P/mock/llm-gateway \
--zhiye.runner.script=server/dev/fake-runner.mjs --zhiye.sandbox.seatbelt=false"
```

这个实例没有 site 进程，预览区会显示 404，属正常。

## 4. 黄金评测与回归覆盖

- 核心回归套件：`e2e/tests/ui-flows.spec.ts`、`api-core.spec.ts`、`api-tasks.spec.ts`、`api-import.spec.ts`、`api-cloud.spec.ts`、`ui-timeline.spec.ts`。
- 12 个黄金业务场景评测集：`evals/cases.json`（覆盖 React CRUD、Recharts 看板、多页哈希路由、云数据库权限、云函数流水账本、应用内 AI、Vue 3 + ECharts、Python CLI 脚本、Skill 技能包、沙箱存储降级安全、390px 移动端适配、多轮增量修改）。在线评测完成后自动在 `runtime/eval-report.json` 输出通过率、平均耗时与 Token 消耗。
