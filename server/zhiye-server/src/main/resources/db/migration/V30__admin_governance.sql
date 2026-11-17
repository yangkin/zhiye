-- 管理台治理与运维能力：工作空间配额覆盖、反馈分诊状态、社区精选置顶、管理审计日志

create table if not exists workspace_quota (
  workspace_id         text primary key,
  max_apps             int,
  daily_task_limit     int,
  max_concurrent_tasks int,
  note                 text,
  updated_by           text,
  updated_at           timestamptz not null default now()
);

alter table help_feedback add column if not exists status text not null default 'open';
alter table help_feedback add column if not exists admin_note text;
alter table help_feedback add column if not exists resolved_at timestamptz;

alter table template add column if not exists featured boolean not null default false;
alter table template add column if not exists pinned_order int not null default 0;

create table if not exists admin_audit_log (
  id          text primary key,
  actor       text not null,
  action      text not null,
  target_type text not null,
  target_id   text not null,
  detail      jsonb not null default '{}',
  created_at  timestamptz not null default now()
);

create index if not exists admin_audit_log_created_idx on admin_audit_log (created_at desc);
