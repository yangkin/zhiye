-- 数据源：登记公司内部的查询类接口，凭证只存在服务端；应用绑定后通过平台代理调用
create table data_source (
  id           text primary key,
  workspace_id text,                                   -- 为空 = 平台登记（配置文件同步，全员可用）
  key          text not null,                          -- 调用名：zhiye.source.query('key', …)
  name         text not null,
  description  text not null default '',
  method       text not null default 'GET',            -- GET | POST（只开放查询类）
  url          text not null,                          -- 可含 {参数} 占位
  params       jsonb not null default '[]'::jsonb,     -- [{name,type,required,description,example}]
  sample       text,                                   -- 返回示例，给 agent 写解析代码
  auth_type    text not null default 'none',           -- none | bearer | header | basic
  auth_header  text,                                   -- auth_type=header 时的请求头名
  secret_enc   text,                                   -- 加密后的凭证（工作空间登记）
  secret_env   text,                                   -- 凭证所在环境变量（平台登记）
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz
);
create unique index data_source_key_uq on data_source (coalesce(workspace_id, ''), key) where deleted_at is null;
create index data_source_ws_idx on data_source (workspace_id);

create table app_data_source (
  app_id     text not null references app(id) on delete cascade,
  source_id  text not null references data_source(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (app_id, source_id)
);

-- 调用记录：控制台显示最近调用与报错；只记元数据，不记参数和返回内容
create table data_source_call (
  id          bigserial primary key,
  source_id   text not null,
  app_id      text,
  origin      text not null,                           -- preview | site | test
  status      int,
  ok          boolean not null,
  duration_ms int not null,
  error       text,
  created_at  timestamptz not null default now()
);
create index data_source_call_src_idx on data_source_call (source_id, created_at desc);
