---
name: feishu-ecosystem
description: 织页企业生态联动规范：飞书群机器人交互卡片推送、飞书多维表格（Bitable）同步、AGW 公司真实业务 API 聚合与华星 SSO 身份联动。
---

# 织页企业生态联动开发规范（飞书 + AGW + 华星办公）

## 1. 飞书群机器人 Webhook 交互卡片（全栈应用后端）
- 机器人 Webhook 地址与签名密钥**严禁**写死在前端或源码中，一律通过工作台「服务托管 → 环境变量」注入：
  - `FEISHU_WEBHOOK_URL`（仅允许 `https://open.feishu.cn/open-apis/bot/v2/hook/...` 或 `https://open.larkoffice.com/open-apis/bot/v2/hook/...`，后端必须校验 host 白名单防 SSRF）
  - `FEISHU_WEBHOOK_SECRET`（可选：使用 HMAC-SHA256 对 `${timestamp}\n${secret}` 计算 `sign` 写入请求体）
- 推送采用飞书 `interactive` 卡片格式（带醒目色头：红 `red` 告警 / 橙 `orange` 催办 / 蓝 `blue` 日报 / 绿 `green` 结案，正文支持 `lark_md` 表格与字段，底部附带「打开应用处理」跳转按钮）。
- 未配置 `FEISHU_WEBHOOK_URL` 时，后端提供 **「演练/预览模式」**：记录卡片 Payload 到推送历史表并返回 `{ ok: true, simulated: true, card }`，并在界面清晰提示用户在「服务托管 → 环境变量」配置真实 Webhook 后即刻真推。

## 2. 飞书多维表格（Bitable）对接
- **全栈双向同步模式**：通过加密环境变量 `FEISHU_APP_ID`、`FEISHU_APP_SECRET`、`FEISHU_BITABLE_APP_TOKEN`、`FEISHU_BITABLE_TABLE_ID` 在服务端换取 `tenant_access_token`（内存缓存并提前 5 分钟刷新），实现拉取多维表格记录、增量写回状态与错误重试；未配置时平滑降级为本地 `/data` 持久化并支持模拟同步演练。
- **零配置通用互导模式**：所有台账与巡检页默认兼容飞书多维表格导出的 `.xlsx` / `.csv` 表头（支持字段映射导入与标准化 `.xlsx` 一键导出回填）。

## 3. AGW 真实业务接口 + 华星 SSO 身份
- 纯前端/共享云服务应用：在对话「＋ → 数据源」绑定 AGW 可调用接口后，通过 `await window.zhiye.source.query('sourceKey', params)` 安全代理拉取 MES / 良率 / 设备 / HR 真实数据，并与本地审批/备注表按单号或机台号关联展示。
- 全栈应用：直接读取网关透传的 `X-Zhiye-User-Id`、`X-Zhiye-User-Login`、`X-Zhiye-User-Name`、`X-Zhiye-User-Admin` 识别当前员工身份，实现“我的待办 / 我的审批 / 个人视图”。
