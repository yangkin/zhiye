# 五星AI岛对接（统一登录、模型与额度）

织页独立部署，不注册到平台服务、不依赖 metis，也不需要注册到 Nacos；它只作为调用方通过平台网关发起出站请求。登录用平台统一认证，
模型列表、默认模型和额度以五星AI岛（openclaw-service）为准，模型调用走 llm-gateway 上员工自己的路由，SkillHub 走 fivestar-service。
外网开发全部用内置模拟接口，内网联调时只改配置。对方接口采用 metis Result 信封：新版成功 `{code:"ok",data,success:true}`，测试环境旧版成功 `{code:200,message:"success",data}`；业务异常也可能是 HTTP 200 + `{code,message,type}`。

## 一、整体链路

```
浏览器 ──(织页会话 cookie + Authorization: Bearer 平台令牌)──▶ 织页 /api
  ├─ /api/llm/models ──(服务间令牌 + 工号)──▶ openclaw  POST /internal/llm/models（实际可用模型）
  ├─ /api/llm/quota ──(平台令牌)──▶ openclaw  /{org}/tokenCostDetail/usage
  └─ 计划 / 生成 / 修改 / 问答 / 小织 / 应用内 AI
        └─ LlmRouter：工作空间所有者的工号 + 选的模型
              ├─(服务间令牌)──▶ openclaw  POST /internal/llm/route
              └─(虚拟密钥 + X-User-Id: 工号 + X-Project-Id: zhiye)──▶ llm-gateway /v1/chat/completions
```

- 平台令牌：统一认证回调里的 `access_token`，前端存在 localStorage（与平台前端一致），只用于调五星AI岛的用户接口。服务端不保存、不打印。
  生成的应用在预览 / 发布域名上（`zhiye.site.preview-base` 等），与织页主站不同源，读不到这个令牌——**部署时不能把预览域名配成和主站同源**。
- **独立集群的身份传递**：SSO 回调时织页从平台用户信息里保存工号 `loginName`，并把它关联到工作空间所有者。浏览器在线查询模型 / 额度时，织页转发平台令牌；后台任务不依赖浏览器在线，也不保存平台令牌，而是以 `X-Internal-Token` 调 openclaw 路由接口，请求体只传 `userAccount`（工号）和可选模型。随后织页用返回的个人虚拟密钥调 llm-gateway，并带 `X-User-Id` 与 `X-Project-Id`。姓名、邮箱、密码和平台 accessToken 都不会交给沙箱或 llm-gateway。
- **跨集群网络**：`zhiye-api` 和负责应用内 AI 的 `zhiye-site` 都要能访问 openclaw / llm-gateway 的平台网关或 Ingress；独立集群之间不能直接使用对方集群的 `*.svc` DNS 地址。织页只作为出站 HTTP 调用方，不需要注册到对方 Nacos。
- 模型路由（含虚拟密钥）只缓存在内存里（默认 10 分钟），不落库、不给浏览器、不进生成的应用。网关返回 401 时清缓存重取。
- 额度按人：用量计入这个员工在五星AI岛的周额度，和星Claw、星域工作台共用；`X-Project-Id: zhiye` 只用于区分来源。
- **模型选择与免费/付费**：模型菜单来自 `/api/llm/models`（个人模型 + 平台免费模型）。下拉框只显示模型名，`billingType=PAID` 的加「付费」标识，选中时提示会扣个人额度；新会话优先选免费模型（`defaultId`）。用户选中后前端把 `providerId`、`modelId`（合一成 `供应商ID:模型ID` 的 `model`）和 `billingType` 随消息传给织页后端（问答 / 开始做 / 修改都带）；后端按 `billingType` 决定用公共 Key 还是个人 `virtualKey`。模型列表和路由在内存里缓存 10 分钟，网关 401 时清缓存重取。
- 应用内 AI（发布后的应用里调用 `zhiye.ai`）记在**应用作者**的额度上（访问者可能未登录）。
- 不设任务级 token 上限。额度用完时（llm-gateway 429 且错误体是额度类）：任务失败为 `PROVIDER_QUOTA`，提示「你在五星AI岛的本周模型额度已用完，X月X日 HH:mm 重置。已做的部分都保留着，额度恢复后点「重试」会接着做。」；问答、润色、小织直接提示。
- 五星模式要求统一登录（`zhiye.auth.mode=sso`）；匿名工作空间调用模型会返回 `LOGIN_REQUIRED`。

## 二、配置

| 配置 | 说明 | 内网示例 |
|---|---|---|
| `zhiye.auth.mode` | `sso` | `sso` |
| `zhiye.auth.sso.gateway-url` | 平台网关 | `http://testmetis.csot.tcl.com` |
| `zhiye.auth.sso.redirect-url` | 回调（需在网关登记） | `https://织页域名/access_token` |
| `zhiye.fivestar.enabled` | 打开五星对接 | `true` |
| `zhiye.fivestar.mock` | 内置模拟 openclaw / llm-gateway | `false` |
| `zhiye.fivestar.organization-id` | 平台租户 ID（测试、正式均为 31） | `31` |
| `zhiye.fivestar.openclaw-base-url` | openclaw 根地址（含 context-path） | `http://网关/openclaw-service` |
| `zhiye.skillhub.enabled` | 打开公司 SkillHub 浏览、安装和发布 | `true` |
| `zhiye.skillhub.base-url` / `file-base-url` | fivestar-service 网关根地址 | `http://网关/fivestar-new` |
| `zhiye.skillhub.upload-base-url` | TFLE 文件服务根地址；发布时先上传 ZIP，再把 `fileId/fileUrl` 交给 `/add` 或 `/edit` | `http://网关/tfle/v1` |
| `zhiye.skillhub.organization-id` | SkillHub 租户 ID | `31` |
| `zhiye.skillhub.portal-url` | 技能详情页，`{id}` 替换为技能 ID | `http://网关/fivestar-front/skillsHub?type=detail&id={id}` |
| `ZHIYE_OPENCLAW_INTERNAL_TOKEN`（环境变量） | 服务间令牌 | 由 openclaw 团队提供，放 Secret |
| `ZHIYE_FIVESTAR_PUBLIC_KEY`（环境变量 / `zhiye.fivestar.public-key-env`） | 公共免费虚拟 Key：`billingType=FREE` 的模型用它调网关 | 平台提供，放 Secret |
| `zhiye.fivestar.project-id` | `X-Project-Id` | `zhiye` |

外网模拟：`ZHIYE_MOCK_INTRANET=1 bash scripts/dev-up.sh`（profile `mock`，见 `application-mock.yml`）。
登录页会跳到模拟统一认证，输入任意工号登录；模拟 openclaw 给每人两个个人供应商三个模型 + 一个平台免费模型（`billingType=FREE`，走公共 Key、不扣额度），模拟网关每人每周 500 万 tokens（`ZHIYE_MOCK_QUOTA`），请求转给本机配置的 DeepSeek / Kimi。`MockSkillHubController` 同时提供可搜索、下载、安装和发布的本地技能市场。

## 三、openclaw 服务间路由接口

织页一次生成在后台跑十几分钟、调用几十次模型，不能依赖浏览器，所以通过服务间接口按工号取路由
（openclaw 已有把虚拟密钥下发给 CM 的先例：`CmDefaultModelSyncHelper`）。该接口已在 openclaw-service `dev` 实现，模拟实现见 `MockFivestarController.route`。

```
POST /internal/llm/models
X-Internal-Token: <服务间令牌>
{"userAccount": "工号", "includePlatformFreeModels": true}

200 [
  {"providerId":"11","providerName":"deepseek","modelId":"deepseek-v4-flash","modelName":"DeepSeek V4 Flash",
   "multimodal":true,"preferred":true,"billingType":"PAID"},
  {"providerId":"1","providerName":"platform-free","modelId":"glm-4.5-flash-free","modelName":"GLM-4.5 Flash（免费）",
   "multimodal":false,"preferred":false,"billingType":"FREE"}
]
```

`includePlatformFreeModels: true` 时把平台免费模型一并返回；`billingType` 缺失按 `PAID` 处理（用个人 Key，和旧行为一致）。

```
POST /internal/llm/route
X-Internal-Token: <服务间令牌>
{"userAccount": "工号", "includePlatformFreeModels": true,
 "providerId": "11", "modelId": "deepseek-v4-flash", "billingType": "PAID"}   // 都不传 = 用该员工的默认模型

200 {
  "baseUrl": "http://llm-gateway/v1",
  "providerModelName": "xclaw-{工号}-{供应商名}/{模型ID}",
  "virtualKey": "<该员工的虚拟密钥>",
  "providerId": "11", "providerName": "deepseek", "modelId": "deepseek-v4-flash", "modelName": "DeepSeek V4 Flash",
  "multimodal": true}
```

免费模型（`billingType=FREE`）的调用方式：织页仍调 `/internal/llm/route` 拿 `baseUrl` / `providerModelName`，
但 `Authorization: Bearer` 用公共免费虚拟 Key（`ZHIYE_FIVESTAR_PUBLIC_KEY`），不用路由返回的个人密钥；
付费模型用路由返回的个人 `virtualKey`。两者都带 `X-User-Id: <工号>` 和 `X-Project-Id: zhiye`。

此内部接口成功返回**裸 JSON**，不套 metis Result；业务错误为 400/422/503 + `{code,message}`。

具体要求：

1. `userAccount` 必填，是统一认证用户信息里的 `loginName` / 工号；`providerId`、`modelId` 可选，两个都不传表示使用该员工在五星AI岛设置的默认模型，只传一个或模型不存在 / 未启用时返回业务错误。
2. 返回该员工本人已有或按现有逻辑补建的虚拟密钥，不能使用织页共享密钥代替个人身份；`providerModelName` 必须与 llm-gateway 上实际配置的个人 provider 路由名一致，`baseUrl` 包含 `/v1` 且能从织页独立集群访问。
3. 这是幂等的路由查询，本身不发起模型调用、不扣额度；实际调用和扣费仍由 llm-gateway 完成。虚拟密钥只返回给织页服务端，双方都不要打印到日志。
4. 模型不存在 / 未启用等内部业务错误使用 `{code:"llm.internal.model.id.invalid",message:"..."}`，由 openclaw 按 400/422 返回。
5. 只允许织页持有的 `X-Internal-Token` 调用，建议限制来源 IP；服务令牌由 openclaw 团队生成并提供，织页配置为 `ZHIYE_OPENCLAW_INTERNAL_TOKEN`。
6. 同时请确认 llm-gateway 的身份与扣费语义：织页发送 `Authorization: Bearer <虚拟密钥>`、`X-User-Id: <工号>`、`X-Project-Id: zhiye`，要求用量进入该员工个人额度，并在日志 / 统计中标记来源项目为 `zhiye`。

已核实 openclaw 现有 `/v1` 代理按 `X-Xclaw-Key` 或 `Authorization` 中的 key 反查身份，不解析 `X-User-Id` / `X-Project-Id`。因此第 6 点需要 openclaw / llm-gateway 团队明确确认或改造，不能假设现有代理已经支持。

## 四、内网联调清单

2026-11-23 本机真实测试环境已验证 SSO 完整登录及 openclaw 用户模型/额度查询。2026-11-24 已进一步验证：`/internal/llm/models` 返回实际可用模型，路由返回个人 Key 后直接调用 llm-gateway 成功（HTTP 200，103 tokens）；织页真实问答完整收到 `done`，额度异步入账后剩余值由 59999695 降为 59996305。模型菜单已切到内部可用列表，`auto` 会选 preferred 或首个可用模型。

SkillHub 已实测分类、关键词搜索、详情、真实 ZIP 下载和安装（31 文件技能安装成功）。2026-11-25 定位此前发布后的「暂无文件 / 检测超时」：织页虽然把 ZIP 作为 multipart `file` 直传给 `/add`、`/edit`，但 `fileUrl` 为空；fivestar-service 该兼容分支只解析 ZIP、写入随机对象键和元数据，没有保存 ZIP 内容，后续文件树与扫描均无法读取。织页已改为与 SkillHub 前端相同的两步上传：读取 `/{org}/file/getFileStorageCfgInfo` → 调 `/tfle/v1/{org}/files/uuid` → 调 `/files/attachment/multipart` 保存 ZIP → 带 `fileId/fileUrl` 发布。个人使用固定 `subReview=false`；公开至 SkillHub 固定提交审核，并在首次发布请求中携带用户选择的 `visibleOrgCodePath`，不再发布后静默补设。`SkillHubEnabledTest` 覆盖文件再下载、个人/公开审核参数和组织路径。`review-state?version=` 的参数是语义版本号（如 `v1.0.0`），不是平台版本主键。

1. 统一认证：网关登记回调 `https://织页域名/access_token`；用测试账号登录，确认 `/iam/tcl/v1/users/self` 字段（`loginName` 是否就是 openclaw 的 userAccount / 工号）。
2. 前端令牌：登录后 `/api/llm/models` 能列出该账号在五星AI岛配置的模型，默认模型与五星AI岛一致；令牌过期时返回 `PLATFORM_TOKEN_INVALID`，前端引导重新登录。
3. organizationId：确认租户 ID，`/{org}/llm/models/providers` 返回 200。
4. 路由接口：`/internal/llm/route` 返回的 `providerModelName` 与 llm-gateway 上的 provider 名一致；用返回的虚拟密钥直接调一次网关。
5. 用量与额度：织页里生成一个应用，五星AI岛额度页的已用量增长；llm-gateway 日志里 `metadata.x-project-id = zhiye`、`X-User-Id` 归一化后等于工号。
6. 超额：用小额测试套餐把额度用完，确认 429 错误体里有重置时间，织页提示正确；额度恢复后点「重试」能接着做。
7. 套餐建档中（quota 404 / ENROLLING）：模型菜单显示「建档中」，不显示 0/0。
8. 应用内 AI：发布一个用 `zhiye.ai` 的应用，访问者调用后用量记在作者名下。
9. SkillHub：市场能按分类和关键词查询；安装一个技能后能创建引用它的应用；发布一个「开发skill」应用，分别在五星AI岛检查首次 /add、再次 /edit、审核状态和可见范围。
10. 预览 / 发布域名与主站不同源（令牌安全前提）。
