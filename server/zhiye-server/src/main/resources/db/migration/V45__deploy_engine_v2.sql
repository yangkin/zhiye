-- Phase 3: Enterprise PaaS Deployment Engine V2
-- 1. 扩展发布流水表：支持预览/线上槽位、不可变产物快照目录（用于 0 重建晋升与秒级回滚）、耗时与结构化根因诊断
alter table app_service_release
  add column if not exists slot text not null default 'live',
  add column if not exists artifact_dir text,
  add column if not exists duration_ms bigint,
  add column if not exists promoted_from_release_id text,
  add column if not exists error_category text,
  add column if not exists diagnosis_json jsonb;

create index if not exists idx_app_service_release_slot on app_service_release(app_id, slot, started_at desc);

-- 2. GitLab / GitHub / 本地模拟 CI/CD 仓库关联与 Webhook 自动部署配置
create table if not exists app_git_repo (
  app_id             text primary key references app(id) on delete cascade,
  workspace_id       text not null,
  provider           text not null default 'gitlab', -- gitlab | github | mock
  repo_url           text not null default '',
  branch             text not null default 'main',
  webhook_secret_enc text,
  access_token_enc   text,
  auto_deploy        boolean not null default true,
  deploy_slot        text not null default 'preview', -- preview | live
  last_commit_sha    text,
  last_commit_msg    text,
  last_sync_at       timestamptz,
  last_status        text,
  last_error         text,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

create index if not exists idx_app_git_repo_ws on app_git_repo(workspace_id);

-- 3. 声明式多服务拓扑配置（zhiye.yaml：web / api / worker / cron / postgres / redis）
create table if not exists app_service_topology (
  app_id        text primary key references app(id) on delete cascade,
  topology_yaml text not null default '',
  services_json jsonb not null default '[]'::jsonb,
  updated_at    timestamptz not null default now()
);
