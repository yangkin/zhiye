-- 全链路平台事件与异常诊断日志：覆盖云数据库 Schema/DDL、模型网关请求与报错、API 异常堆栈、沙箱故障与生命周期事件
create table if not exists platform_event_log (
  id           bigserial primary key,
  category     text not null,
  level        text not null default 'INFO',
  action       text not null,
  workspace_id text,
  user_id      text,
  app_id       text,
  task_id      text,
  ok           boolean not null default true,
  status_code  int,
  duration_ms  int,
  summary      text not null default '',
  detail       text,
  meta         jsonb not null default '{}'::jsonb,
  created_at   timestamptz not null default now()
);

create index if not exists platform_event_log_created_idx on platform_event_log (created_at desc);
create index if not exists platform_event_log_cat_idx on platform_event_log (category, created_at desc);
create index if not exists platform_event_log_app_idx on platform_event_log (app_id, created_at desc) where app_id is not null;
create index if not exists platform_event_log_task_idx on platform_event_log (task_id, created_at desc) where task_id is not null;
