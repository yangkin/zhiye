-- Phase 4: Public Feishu Direct Control Plane + Enterprise Connector Center + Automation Triggers

-- 1. 企业连接器中心（公域飞书 Bot / 多维表格 / 飞书自建应用 / HTTP Webhook）
create table if not exists enterprise_connector (
  id                 text primary key,
  workspace_id       text,                         -- null 表示平台级共享连接器
  name               text not null,
  kind               text not null,                -- feishu_bot | feishu_bitable | feishu_app | webhook_http
  mode               text not null default 'live', -- live (open.feishu.cn 真实调用) | sandbox (本地闭环模拟验证)
  enabled            boolean not null default true,
  config_json        jsonb not null default '{}'::jsonb,
  secrets_enc        text,                         -- AES-256-GCM 加密的密钥 JSON（appSecret / webhookUrl / signSecret / verificationToken）
  rate_limit_per_min int not null default 60,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

create index if not exists idx_enterprise_connector_ws on enterprise_connector(workspace_id, kind);

-- 预置 2 个沙箱验证连接器（开箱即可本地端到端验证飞书卡片、多维表格 CRUD 与审批上线，改为 live 并填入真实 AppId/Secret 即可直连公域 open.feishu.cn）
insert into enterprise_connector (id, workspace_id, name, kind, mode, enabled, config_json, rate_limit_per_min)
values
  (
    'conn_feishu_bot_sandbox',
    null,
    '公域飞书研发协同机器人（沙箱验证）',
    'feishu_bot',
    'sandbox',
    true,
    '{"apiBase":"https://open.feishu.cn","defaultChatId":"oc_zhiye_demo_group","description":"内置公域飞书卡片与群机器人沙箱；切换为 live 并填入 open.feishu.cn 凭据即可直连生产飞书"}'::jsonb,
    120
  ),
  (
    'conn_feishu_bitable_sandbox',
    null,
    '公域飞书多维表格同步器（沙箱验证）',
    'feishu_bitable',
    'sandbox',
    true,
    '{"apiBase":"https://open.feishu.cn","bitableAppToken":"bascn_demo_zhiye","bitableTableId":"tbl_demo_orders","description":"内置飞书多维表格 Bitable 读写沙箱；切换为 live 并填入 appId/appSecret/appToken/tableId 即可读写线上多维表格"}'::jsonb,
    120
  )
on conflict (id) do nothing;

-- 2. 连接器调用审计日志
create table if not exists connector_call_audit (
  id               text primary key,
  connector_id     text not null references enterprise_connector(id) on delete cascade,
  app_id           text,
  action           text not null,
  status           text not null, -- ok | error | blocked
  duration_ms      int not null default 0,
  request_summary  text,
  response_summary text,
  error            text,
  created_at       timestamptz not null default now()
);

create index if not exists idx_connector_call_audit_conn on connector_call_audit(connector_id, created_at desc);

-- 3. 应用自动化触发器（Cron 定时 / Cloud DB 写入钩子 / 签名入站 Webhook）
create table if not exists app_trigger (
  id                 text primary key,
  app_id             text not null references app(id) on delete cascade,
  workspace_id       text not null,
  name               text not null,
  kind               text not null,                -- cron | db_insert | db_update | webhook
  enabled            boolean not null default true,
  cron_expr          text,                         -- 5 段 cron 表达式，如 */5 * * * * 或 0 9 * * *
  table_name         text,                         -- db_insert / db_update 监听的表名
  webhook_secret_enc text,                         -- webhook 签名密钥（AES-256-GCM）
  action_type        text not null,                -- cloud_function | feishu_card | service_http
  action_config      jsonb not null default '{}'::jsonb,
  last_run_at        timestamptz,
  last_status        text,
  last_error         text,
  run_count          bigint not null default 0,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

create index if not exists idx_app_trigger_app on app_trigger(app_id, kind, enabled);

-- 4. 自动化触发器运行记录
create table if not exists app_trigger_run (
  id              text primary key,
  trigger_id      text not null references app_trigger(id) on delete cascade,
  app_id          text not null,
  event_type      text not null,
  status          text not null, -- ok | error
  duration_ms     int not null default 0,
  payload_summary text,
  result_summary  text,
  error           text,
  created_at      timestamptz not null default now()
);

create index if not exists idx_app_trigger_run_trigger on app_trigger_run(trigger_id, created_at desc);

-- 5. 公域飞书控制面会话与交互卡片审批记录（@织页 指令 → 异步任务 → 卡片审批一键上线）
create table if not exists feishu_control_session (
  id              text primary key,
  connector_id    text,
  workspace_id    text,
  chat_id         text,
  open_id         text,
  user_name       text,
  command_text    text not null,
  intent          text not null default 'unknown', -- status | diagnose | modify | generate
  app_id          text,
  task_id         text,
  version_id      text,
  card_status     text not null default 'pending', -- pending | ready_for_approval | approved_published | rejected | completed | failed
  card_json       jsonb,
  approval_result text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

create index if not exists idx_feishu_control_session_created on feishu_control_session(created_at desc);
