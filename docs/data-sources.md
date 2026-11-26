# 数据源（只读接口代理）

让生成的应用直接使用公司内部接口的真实数据。接口地址、凭证、请求头都只在平台服务端，页面代码里只有「调用名」和参数。

## 登记与添加

接口只在 API 网关（AGW）侧登记；织页不提供手工填写地址和凭证的登记入口，`POST /api/sources` 一律返回 403 `SOURCE_MANUAL_DISABLED`。统一存在 `data_source` 表：

| 来源 | 怎么进来 | 凭证 | 可见范围 |
| --- | --- | --- | --- |
| AGW 可调用 API | 用户在「＋ → 数据源 → 管理数据源 → 选择可调用 API」里从当前账号有授权的清单添加（见下文「API 网关可调用 API」） | 数据面走当前用户 Metis 登录态 + `X-Project-Id`，不落库、不返回浏览器 | 本工作空间 |
| 平台登记 | 运维写 `zhiye.sources.file`（JSON 数组），api 进程启动时按 `key` 同步，文件里删掉的条目会下线 | `secretEnv` 指向环境变量 | 全员，只读 |
| 历史手工登记 | 老数据保留，可编辑、试调用、删除，但不能再新增 | AES-GCM 加密保存（密钥由 `ZHIYE_CLOUD_SECRET` 派生），永不返回浏览器 | 本工作空间 |

平台文件示例：

```json
[{ "key": "mes_daily_output", "name": "MES 产线日产量", "description": "按产线和日期查询每小时产量",
   "method": "GET", "url": "https://apigw.example.com/mes/daily/{line}",
   "params": [{ "name": "line", "description": "产线编号" }, { "name": "date", "type": "date", "required": true }],
   "sample": "{\"rows\":[{\"hour\":8,\"output\":120}]}", "authType": "bearer", "secretEnv": "MES_GATEWAY_TOKEN" }]
```

字段规则：调用名 `[a-z][a-z0-9_]{1,47}`；方法 GET / POST（只开放查询类）；地址可用 `{参数}` 占位（占位参数自动必填）；参数类型 string / number / integer / boolean / date；鉴权 none / bearer / header（自定义请求头）/ basic（`用户名:密码`）/ agw（API 网关调用项目签名）/ agw_key（项目 Key）/ agw_client（系统身份）/ agw_user（按工作空间所有者的用户身份）。每个应用最多绑定 100 个数据源。

## 使用

1. 在创造页、会话页或工作台输入框「＋ → 数据源」里选择。数据源是**会话/应用级连接**，不是随单条消息清空的一次性附件：创造页选择后随新会话保存，会话页通过 `PUT /api/conversations/{id}/sources` 更新并持久化，创建应用时自动绑定；工作台里选中即绑定到应用（chip 上的 × 解绑）。一个应用最多 100 个。
2. 会话问答会带上已连接数据源的名称、说明和参数（只用于规划，不能声称已经实时查询）；计划、生成、修改、工作台问答都会带上已绑定数据源的说明。编码指令里有参数和返回示例，过程里显示「读取数据源 · 名称」。内置技能 `data-sources` 规定了调用方式和加载/失败/空数据状态。
3. 应用代码：`await window.zhiye.source.query('mes_daily_output', { line: 'L1', date: '2026-01-31' })`，返回接口原始 JSON。前端页面不需要自建后端，平台代理就是安全的数据访问层。

## 调用链路

| 场景 | 路径 | 身份 |
| --- | --- | --- |
| 预览 `/p/{buildId}/` | `POST {api}/cloud/{appId}/source/{key}` | 预览页注入的应用令牌（30 分钟） |
| 发布站点 `{slug}.{suffix}` | `POST /__zhiye/source/{key}`（站点网关同源） | 站点本身，页面里没有令牌；只接受 `application/json` |
| 试调用 | `POST /api/sources/{id}/test` | 工作空间 |

平台代理：按参数定义校验（未知参数、缺必填、类型不对 → 400 中文原因）→ 拼路径参数和查询串（POST 为 JSON 体）→ 注入凭证和 `X-Zhiye-App` → 10 秒超时、返回体最大 2 MB。3xx 不自动跟随，结构化返回 `status` 与 `data.location/message`，由应用按业务处理；其他非 2xx → 502「数据源返回 500：…」。每次调用记一条 `data_source_call`（不记参数和返回内容）。敏感字段示例不写库、不返回浏览器；V43 迁移清理历史示例。

## 网络安全

- 始终拒绝链路本地（169.254.0.0/16，含云厂商元数据）、`0.0.0.0`、组播地址。
- `zhiye.sources.allow-loopback`：本地开发默认允许 127.0.0.1；k8s 配置里关闭。
- `zhiye.sources.allowed-hosts`（`ZHIYE_SOURCES_ALLOWED_HOSTS`，逗号分隔，支持 `*.后缀`）：生产建议只放公司 API 网关。
- 代理请求不走系统代理，不带用户的 cookie。

## API 网关可调用 API

「数据源与 API → 选择可调用 API」使用 `GET /apiInfo/me/callable-apis`，只展示当前工作空间所有者在消费项目中有有效授权的 API。添加时服务端按 `projectCode + apiId` 重新校验清单，再读 `/apiInfo/{apiId}`，保存方法、公开地址、参数和返回示例；同一 API 在不同消费项目下分别添加，不去重错上下文。前端组件 `components/AgwCatalog.tsx`。

鉴权由 `AgwClientTokenService` 使用 `ZHIYE_AGW_CLIENT_ID` / `ZHIYE_AGW_CLIENT_SECRET` 完成：管理端用 `grant_type=username` 按 SSO 工号取得用户 Token，再经 `/api/auth/metis` 换 AGW JWT；数据面用用户 Metis UUID Token + `X-Project-Id: projectPublicId`。Token 按身份在内存缓存并提前刷新，不落库、不返回浏览器。已有项目 Key（`agw_key`）和 V1 签名（`agw`）仍兼容；`agw_client` 用于系统身份查询。

实际响应的 `apiPubUrl` 若已经包含 `/agw-api/{apiCode}` 就直接使用，不能重复拼接。POST 支持参数组 JSON，也支持 `_body` 类型的完整 JSON 数组/对象；试调用里 `_body` 使用 JSON 文本框，填写后覆盖其他参数。旧版误存为 `root(string)` 的 AGW 数据源在读取时自动转换为 `_body(json)`，无需重新添加。请求体示例压缩后超过 200 字时留空，不再截成无效 JSON。除显式参数列表外，`AgwCatalogService` 还从详情中的 `{{agw.body/header/query/path.参数}}` 引用补全契约；敏感字段（secret/password/token/credential/appKey 等）不提供示例，试调用用密码框当次输入，禁止写死、持久化或记录日志。测试环境已完成真实闭环：当前用户 9 条可调用 API、3 个消费项目，授权复核、契约添加、数据面用户登录态调用及业务返回值断言均通过。写入类动作（发送消息、同步、变更）不因出现在清单里就自动执行，仍须由用户明确选择并遵守业务权限。

## 已知限制

- 只读：不开放写入类接口。
- 调用身份固定为「应用身份」；透传访问者身份需要先接 SSO，后续再做。
- 构建按应用隔离（`build.app_id`）后，预览地址才能唯一对应到应用；迁移前多个应用共用的旧构建不注入令牌，重新生成一次即可。

## 2026-11-25 全量审计与共性修复

当时当前工作空间有 8 个数据源：4 个结构化参数、2 个只有自由 JSON 请求体、2 个无参数定义。完成以下修复并部署 `v0.3.5-test`：

- AGW 详情中的 `agw.body/header/query/path` 引用补全为参数契约，标明必填、位置和敏感字段。
- 302 不再直接报错，不自动跟随，返回 `data.location/message`；飞书授权接口真实试调用由 502 `SOURCE_REDIRECT` 变为 200。
- 4 个有必填参数的接口空参试调用均返回 400 且指出缺失字段。
- 敏感参数支持密码框当次输入；V43 清理历史敏感示例，复测中 `sensitiveExampleFields` 全部为空。
- 修复 `X-Feishu-App-Secret` 等带连字符 Header 参数的保存与代理校验。

原始审计数据：`runtime/source-audit-20261125.json`、`runtime/source-audit-fixed-20261125.json`；脚本：`e2e/audit-sources.mjs`。该审计不主动发送消息、不执行预算变更、不使用真实凭证。飞书发送消息等契约仍依赖 AGW 返回完整参数，不能由模型猜测。
