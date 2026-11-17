# 数据源（只读接口代理）

让生成的应用直接使用公司内部接口的真实数据。接口地址、凭证、请求头都只在平台服务端，页面代码里只有「调用名」和参数。

## 登记

两种来源，统一存在 `data_source` 表：

| 来源 | 谁登记 | 凭证 | 可见范围 |
| --- | --- | --- | --- |
| 工作空间登记 | 用户在「＋ → 数据源 → 登记与管理数据源」里填写 | AES-GCM 加密保存（密钥由 `ZHIYE_CLOUD_SECRET` 派生），永不返回浏览器 | 本工作空间 |
| 平台登记 | 运维写 `zhiye.sources.file`（JSON 数组），api 进程启动时按 `key` 同步，文件里删掉的条目会下线 | `secretEnv` 指向环境变量 | 全员，只读 |

平台文件示例：

```json
[{ "key": "mes_daily_output", "name": "MES 产线日产量", "description": "按产线和日期查询每小时产量",
   "method": "GET", "url": "https://apigw.example.com/mes/daily/{line}",
   "params": [{ "name": "line", "description": "产线编号" }, { "name": "date", "type": "date", "required": true }],
   "sample": "{\"rows\":[{\"hour\":8,\"output\":120}]}", "authType": "bearer", "secretEnv": "MES_GATEWAY_TOKEN" }]
```

字段规则：调用名 `[a-z][a-z0-9_]{1,47}`；方法 GET / POST（只开放查询类）；地址可用 `{参数}` 占位（占位参数自动必填）；参数类型 string / number / integer / boolean / date；鉴权 none / bearer / header（自定义请求头）/ basic（`用户名:密码`）/ agw（API 网关调用项目签名）。工作空间手动登记最多 50 个，这是登记数量上限，与应用最多绑定 100 个数据源是两个独立限制。

## 使用

1. 在创造页、会话页或工作台输入框「＋ → 数据源」里选择。创造页/会话页在创建应用时绑定；工作台里选中即绑定到应用（chip 上的 × 解绑）。一个应用最多 100 个。
2. 计划、生成、修改、工作台问答都会带上已绑定数据源的说明；编码指令里有参数和返回示例，过程里显示「读取数据源 · 名称」。内置技能 `data-sources` 规定了调用方式和加载/失败/空数据状态。
3. 应用代码：`await window.zhiye.source.query('mes_daily_output', { line: 'L1', date: '2026-01-31' })`，返回接口原始 JSON。

## 调用链路

| 场景 | 路径 | 身份 |
| --- | --- | --- |
| 预览 `/p/{buildId}/` | `POST {api}/cloud/{appId}/source/{key}` | 预览页注入的应用令牌（30 分钟） |
| 发布站点 `{slug}.{suffix}` | `POST /__zhiye/source/{key}`（站点网关同源） | 站点本身，页面里没有令牌；只接受 `application/json` |
| 试调用 | `POST /api/sources/{id}/test` | 工作空间 |

平台代理：按参数定义校验（未知参数、缺必填、类型不对 → 400 中文原因）→ 拼路径参数和查询串（POST 为 JSON 体）→ 注入凭证和 `X-Zhiye-App` → 10 秒超时、返回体最大 2 MB、不跟随跳转。上游非 2xx → 502「数据源返回 500：…」。每次调用记一条 `data_source_call`（不记参数和返回内容）。

## 网络安全

- 始终拒绝链路本地（169.254.0.0/16，含云厂商元数据）、`0.0.0.0`、组播地址。
- `zhiye.sources.allow-loopback`：本地开发默认允许 127.0.0.1；k8s 配置里关闭。
- `zhiye.sources.allowed-hosts`（`ZHIYE_SOURCES_ALLOWED_HOSTS`，逗号分隔，支持 `*.后缀`）：生产建议只放公司 API 网关。
- 代理请求不走系统代理，不带用户的 cookie。

## API 网关目录导入

开启 `zhiye.agw.enabled` 后，数据源管理里可以点「从 API 网关添加」。织页调用
`GET /api/agw-admin/apiInfo/list?page=0&size=…&enableStatus=1&shelvesStatus=1`，只显示当前账号可见、已启用且已上架的 API；支持 `apiName` 关键字和分页。

- **目录凭证**：浏览器带来的 SSO accessToken，只转发给 `agw-admin`（`Authorization`、`X-Metis-Authorization`、`X-Agw-Client: console`、`X-Tenant-Id`），不保存。
- **导入**：选中后服务端重新读取 `/apiInfo/{id}`，生成调用名、URL、参数和返回示例；只使用公开契约字段，不依赖非管理员会被清除的 SQL、后端路径等字段。同一个 API 重复导入更新原记录。
- **运行时凭证**：填写已获得该 API 授权的**调用项目** AppKey / AppSecret，不是提供方应用的 `appCode`。AppSecret 仍按数据源凭证加密保存。
- **签名**：代理每次生成 `x-agw-app-key`、`x-agw-time-stamp`、`x-agw-request-id`，以及 `SHA256(AppKey + ";" + AppSecret + ";" + timestamp)` 的 `x-agw-signature`，与网关公开 SDK 一致。
- 支持 REST 和数据库类公开参数的 query / path / header / JSON body 映射。非 GET/POST、字节流、动态路由和无法映射的自由 JSON 请求体会提示手动登记。

配置：`ZHIYE_AGW_ENABLED=true`、`ZHIYE_AGW_BASE_URL`（管理端根地址，测试为 `http://testmetis.csot.tcl.com`）、
`ZHIYE_AGW_PUBLIC_URL`（业务调用根地址，留空同管理端）、`ZHIYE_AGW_TENANT_ID=31`。生产仍需把业务网关主机加入 `ZHIYE_SOURCES_ALLOWED_HOSTS`。

## 已知限制

- 只读：不开放写入类接口。
- 调用身份固定为「应用身份」；透传访问者身份需要先接 SSO，后续再做。
- 构建按应用隔离（`build.app_id`）后，预览地址才能唯一对应到应用；迁移前多个应用共用的旧构建不注入令牌，重新生成一次即可。
