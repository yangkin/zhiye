---
name: feishu-ecosystem
description: 织页企业生态联动规范：优先通过平台受控企业连接器（window.zhiye.feishu / ctx.feishu / ctx.bitable / ctx.org 与 ZHIYE_CONNECTOR_API）免密对接飞书群机器人卡片、多维表格、AGW 接口与华星 SSO 身份。
---

# 织页企业生态联动开发规范（飞书群机器人 + 多维表格 + AGW + 华星办公）

## 0. 飞书群自定义机器人（`feishu_bot`）核心认知（务必遵守）
- **已直接绑定目标飞书群**：Webhook 地址本身已唯一对应到飞书里的那个群。发送消息只需要 `connectorId`（可选，不传默认取当前工作空间机器人）、`title`、`content`（可选 `templateColor` / `fields` / `buttons`）。
- **严禁索要群 ID**：**绝对不要**要求用户配置 `FEISHU_GROUP_ID`、`chat_id`、`groupId`、`receive_id` 或 Webhook 环境变量，也不要在界面上展示「未配置目标群」来拦截发送！
- **典型高价值业务场景**：表单提交自动推群（报修/工单/意见反馈）、异常巡检告警一键推红色卡片、日报/周报/会议纪要汇总一键发群、审批/进度变更自动通知群。

## 1. 优先复用平台托管「企业连接器」（零密钥配置、开箱即用）
在系统提示词的「平台已托管企业连接器」清单中查看当前工作空间已启用的真实 `connectorId`（全量直连公域 `open.feishu.cn`，凭据经 AES-256-GCM 加密托管在服务端）。

### 1.0 普通网页应用前端免后端直发飞书卡片（首选！无需开云服务、无需写后端）
只要页面代码里调用了 `window.zhiye.feishu.sendCard`，平台在预览和发布态会自动注入 SDK 并通过受控代理安全发往飞书（零密钥暴露，带限流与审计）：
```ts
declare global {
  interface Window {
    zhiye?: {
      feishu?: {
        sendCard: (opts: {
          connectorId?: string;
          title: string;
          content: string;
          templateColor?: 'blue' | 'green' | 'orange' | 'red' | 'indigo' | 'purple' | 'grey';
          fields?: Array<{ label: string; value: string }>;
          buttons?: Array<{ label: string; url?: string; type?: 'primary' | 'default' | 'danger' }>;
        }) => Promise<{ ok: boolean; connectorId: string; connectorName: string }>;
      };
    };
  }
}

export async function pushFeishuCard(payload: {
  connectorId?: string;
  title: string;
  content: string;
  templateColor?: 'blue' | 'green' | 'orange' | 'red';
  fields?: Array<{ label: string; value: string }>;
}) {
  if (!window.zhiye?.feishu) {
    throw new Error('飞书卡片推送仅在织页预览或发布站点中可用');
  }
  return window.zhiye.feishu.sendCard(payload);
}
```

### 1.1 共享云服务应用（Deno 云函数 `functions/*.ts`）
需要多表事务、服务端状态校验或组合读写飞书多维表格时，使用 Deno 云函数（宿主已原生注入 `ctx.feishu`、`ctx.bitable`、`ctx.org` 与 `ctx.source`，密钥不出服务端）：
```ts
export default async function (args, ctx) {
  // 1. 推送公域飞书交互卡片（feishu_bot 已绑定目标群，无需 groupId）
  const sent = await ctx.feishu.sendCard({
    connectorId: args.connectorId || '', // 传入工作空间真实飞书机器人 ID（留空自动用默认机器人）
    title: '良率异常告警',
    content: `产线 **${args.line}** 当前良率 ${args.yieldRate}%`,
    templateColor: 'red', // blue | green | orange | red | indigo
    fields: [{ label: '责任人', value: args.owner || '值班工程师' }],
  });
  // 2. 写入 / 查询 / 更新飞书多维表格（Bitable，返回含顶层 record_id / items / total）
  const rec = await ctx.bitable.create(args.bitableConnectorId, {
    文本: `【工单】${args.orderNo}｜状态：待处理｜产线：${args.line}`,
  });
  // 3. 检索企业员工目录
  const employees = await ctx.org.searchEmployees(args.keyword || '');
  return { sent, rec, employees };
}
```

### 1.2 单体全栈应用后端（Node / Python / Spring Boot / Go）
平台在预览槽位、活体开发环境与线上托管容器运行时均会自动注入环境变量 `ZHIYE_CONNECTOR_API`、`ZHIYE_CONNECTOR_TOKEN` 以及默认 `ZHIYE_FEISHU_BOT_ID`（严禁在前端直连 `open.feishu.cn` 或硬编码密钥，也无需用户到「环境变量」面板手动填写）：
- **推送飞书卡片**：`POST ${ZHIYE_CONNECTOR_API}/feishu/send-card`，Header `Authorization: Bearer ${ZHIYE_CONNECTOR_TOKEN}`，JSON `{ connectorId: body.connectorId || process.env.ZHIYE_FEISHU_BOT_ID || '', title, content, templateColor, fields }`（**不需要 `groupId` / `FEISHU_GROUP_ID`**）
- **多维表格 CRUD**：`POST ${ZHIYE_CONNECTOR_API}/bitable/list`（返回 `{ ok, total, items, data }`）、`.../bitable/create`（返回 `{ ok, record_id, recordId, fields, data }`）、`.../bitable/update`（返回 `{ ok, record_id, recordId, fields, data }`）
- **绑定数据源查询**：`POST ${ZHIYE_CONNECTOR_API}/source/query`，JSON `{ key, params }`，返回 `{ data, status }`
- **员工目录查询**：`POST ${ZHIYE_CONNECTOR_API}/org/employees`，JSON `{ q }`
- 注意：离线构建沙箱的 `test_endpoint` 不注入连接器地址（无外网隔离），预览部署与线上运行态都会自动注入。

## 2. AGW 真实业务接口 + 华星 SSO 身份
- 纯前端/共享云服务：在工作台「＋ → 数据源」绑定 AGW 接口后通过 `await window.zhiye.source.query('sourceKey', params)` 安全代理拉取 MES / 良率 / 设备真实数据。
- 全栈后端：直接读取网关注入的可信头 `X-Zhiye-User-Id`、`X-Zhiye-User-Login`、`X-Zhiye-User-Name`、`X-Zhiye-User-Admin` 识别当前员工身份。
