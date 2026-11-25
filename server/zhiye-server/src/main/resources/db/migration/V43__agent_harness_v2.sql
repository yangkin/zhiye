-- Phase 1: Agent Harness V2 — 检查点时间旅行（代码+Schema+QA报告快照）与企业受控 MCP 注册中心

create table if not exists app_checkpoint (
    id              varchar(64) primary key,
    app_id          varchar(64) not null references app(id) on delete cascade,
    workspace_id    varchar(64) not null,
    task_id         varchar(64),
    version_id      varchar(64),
    stage           varchar(32) not null default 'post_task',
    title           varchar(160) not null,
    git_commit      varchar(64) not null,
    tree_id         varchar(64) not null,
    schema_sql      text,
    qa_passed       boolean,
    qa_score        int,
    qa_report_json  jsonb,
    created_at      timestamptz not null default now()
);

create index if not exists idx_app_checkpoint_app on app_checkpoint (app_id, created_at desc);

create table if not exists enterprise_mcp_server (
    id              varchar(64) primary key,
    server_key      varchar(64) not null unique,
    name            varchar(80) not null,
    description     text not null default '',
    transport       varchar(24) not null default 'http',
    endpoint_url    text not null default '',
    auth_header     varchar(64) not null default 'Authorization',
    secret_enc      text not null default '',
    tools_json      jsonb not null default '[]'::jsonb,
    enabled         boolean not null default true,
    builtin         boolean not null default false,
    created_by      varchar(64),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create table if not exists mcp_call_audit (
    id              bigserial primary key,
    server_key      varchar(64) not null,
    tool_name       varchar(128) not null,
    task_id         varchar(64),
    app_id          varchar(64),
    ok              boolean not null,
    duration_ms     int not null default 0,
    error           text,
    created_at      timestamptz not null default now()
);

create index if not exists idx_mcp_call_audit_created on mcp_call_audit (created_at desc);

-- 内置两个本地仿真 MCP 服务（生产环境可在管理后台替换为真实内网 HTTP MCP 地址）
insert into enterprise_mcp_server (id, server_key, name, description, transport, endpoint_url, tools_json, enabled, builtin, created_by)
values (
    'mcp_builtin_docs',
    'csot-docs-mcp',
    '华星研发规范与文档 MCP（本地仿真）',
    '检索公司前端/后端/良率/8D/飞书接入研发规范与字段字典；可在平台管理中切换为真实内网 HTTP MCP 服务。',
    'mock',
    'mock://csot-docs',
    '[{"name":"search_specs","description":"按关键字检索公司研发规范与业务术语（如 8D、SPC、良率、SSO、AGW）"},{"name":"get_design_tokens","description":"获取华星内网企业后台标准色彩、状态徽章与排版规范"}]'::jsonb,
    true,
    true,
    'system'
) on conflict (server_key) do nothing;

insert into enterprise_mcp_server (id, server_key, name, description, transport, endpoint_url, tools_json, enabled, builtin, created_by)
values (
    'mcp_builtin_api_catalog',
    'csot-api-catalog-mcp',
    '华星内部系统接口契约 MCP（本地仿真）',
    '查询 MES/EAP/ITSM/飞书标准接口字段契约与状态码定义，帮助编码智能体准确对接真实系统字段。',
    'mock',
    'mock://csot-api-catalog',
    '[{"name":"describe_domain_api","description":"按业务域（mes/eap/itsm/feishu）获取标准接口路径与响应 JSON 字段契约"}]'::jsonb,
    true,
    true,
    'system'
) on conflict (server_key) do nothing;
