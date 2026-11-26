---
name: feishu-ecosystem
description: 织页企业生态联动规范：优先通过平台受控企业连接器（ctx.feishu / ctx.bitable / ctx.org 与 ZHIYE_CONNECTOR_API）免密对接飞书卡片、多维表格、AGW 接口与华星 SSO 身份。
---

# 织页企业生态联动开发规范（飞书 + 多维表格 + AGW + 华星办公）

## 1. 优先复用平台托管「企业连接器」（零密钥配置、开箱即用）
查看当前工作区 `.zhiye/connectors.json` 获取平台已启用的连接器 ID（默认已内置 `conn_feishu_bot_sandbox` 群机器人与 `conn_feishu_bitable_sandbox` 多维表格，管理员切换为 `live` 模式即直连公域 `open.feishu.cn`，应用代码完全无需改动）。

### 1.1 共享云服务应用（Deno 云函数 `functions/*.ts`）
Deno 宿主已原生注入 `ctx.feishu`、`ctx.bitable`、`ctx.org`（密钥不出服务端，前端不可见）：
```ts
export default async function (args, ctx) {
  // 1. 推送公域飞书交互卡片
  const sent = await ctx.feishu.sendCard({
    connectorId: 'conn_feishu_bot_sandbox', // 可省略自动选默认飞书连接器
    title: '良率异常告警',
    content: `产线 **${args.line}** 当前良率 ${args.yieldRate}%`,
    templateColor: 'red', // blue | green | orange | red | indigo
    fields: [{ label: '责任人', value: args.owner || '值班工程师' }],
  });
  // 2. 写入 / 查询 / 更新飞书多维表格（Bitable）
  const rec = await ctx.bitable.create('conn_feishu_bitable_sandbox', {
    工单号: args.orderNo, 状态: '待处理', 产线: args.line,
  });
  // 3. 检索企业员工目录
  const employees = await ctx.org.searchEmployees(args.keyword || '');
  return { sent, rec, employees };
}
```

### 1.2 单体全栈应用后端（Node / Python / Spring Boot / Go）
平台运行时已自动向后端进程注入环境变量 `ZHIYE_CONNECTOR_API` 与 `ZHIYE_CONNECTOR_TOKEN`（严禁在前端直连 `open.feishu.cn` 或硬编码密钥）：
- **推送飞书卡片**：`POST ${ZHIYE_CONNECTOR_API}/feishu/send-card`，Header `Authorization: Bearer ${ZHIYE_CONNECTOR_TOKEN}`，JSON `{ connectorId, title, content, templateColor, fields }`
- **多维表格 CRUD**：`POST ${ZHIYE_CONNECTOR_API}/bitable/list`、`.../bitable/create`、`.../bitable/update`
- **员工目录查询**：`POST ${ZHIYE_CONNECTOR_API}/org/employees`，JSON `{ q }`
- 本地无 `ZHIYE_CONNECTOR_API` 时的兜底：友好记录到本应用历史表并返回 `{ ok: true, simulated: true }`。

## 2. AGW 真实业务接口 + 华星 SSO 身份
- 纯前端/共享云服务：在工作台「＋ → 数据源」绑定 AGW 接口后通过 `await window.zhiye.source.query('sourceKey', params)` 安全代理拉取 MES / 良率 / 设备真实数据。
- 全栈后端：直接读取网关注入的可信头 `X-Zhiye-User-Id`、`X-Zhiye-User-Login`、`X-Zhiye-User-Name`、`X-Zhiye-User-Admin` 识别当前员工身份。
