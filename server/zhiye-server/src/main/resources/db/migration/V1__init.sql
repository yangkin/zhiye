create table workspace (
  id            text primary key,
  token_hash    text not null unique,
  owner_user_id text,
  created_at    timestamptz not null default now()
);

create table app (
  id                   text primary key,
  workspace_id         text not null references workspace(id),
  name                 text not null,
  name_locked          boolean not null default false,
  type                 text not null,
  description          text not null default '',
  prompt               text not null default '',
  plan_doc             text not null default '',
  style                text not null default 'lavender',
  materials            boolean not null default false,
  mode                 text not null default '适中',
  model                text not null default 'auto',
  favorite             boolean not null default false,
  phase                text not null,
  source               text not null default 'create',
  framework            text,
  project_kind         text not null default 'template',
  visible              boolean not null default true,
  slug                 text not null unique,
  asset_ids            jsonb not null default '[]',
  current_version_id   text,
  published_version_id text,
  repo_path            text,
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  deleted_at           timestamptz
);
create index app_ws_idx on app(workspace_id) where deleted_at is null;

create table build (
  id             text primary key,
  git_tree       text not null unique,
  status         text not null,
  preview_prefix text not null,
  publish_prefix text not null,
  editable       boolean not null default false,
  created_at     timestamptz not null default now()
);

create table version (
  id                text primary key,
  app_id            text not null references app(id),
  number            int  not null,
  title             text not null,
  source            text not null,
  git_commit        text not null,
  git_tree          text not null,
  parent_version_id text,
  restored_from_id  text,
  task_id           text unique,
  build_id          text references build(id),
  favorite          boolean not null default false,
  created_at        timestamptz not null default now(),
  unique (app_id, number)
);

create table task (
  id                text primary key,
  workspace_id      text not null,
  app_id            text not null,
  type              text not null,
  status            text not null,
  base_version_id   text,
  input             jsonb not null default '{}',
  result            jsonb,
  error_code        text,
  error_message     text,
  idempotency_key   text,
  attempt           int  not null default 1,
  parent_task_id    text,
  phase_label       text not null default '',
  progress          int  not null default 0,
  result_version_id text,
  lease_owner       text,
  lease_until       timestamptz,
  token_used        bigint not null default 0,
  token_budget      bigint not null default 0,
  created_at        timestamptz not null default now(),
  started_at        timestamptz,
  finished_at       timestamptz,
  unique (workspace_id, idempotency_key)
);
-- 每个应用同一时间只有一个进行中的任务（写锁）
create unique index task_one_active_per_app on task(app_id)
  where status in ('QUEUED','PREPARING','RUNNING','VERIFYING','COMMITTING','CANCELLING');
create index task_status_idx on task(status, created_at);
create index task_app_idx on task(app_id, created_at);

create table task_event (
  task_id    text not null,
  seq        int  not null,
  type       text not null,
  payload    jsonb not null,
  created_at timestamptz not null default now(),
  primary key (task_id, seq)
);

create table message (
  id              text primary key,
  app_id          text not null,
  role            text not null,
  content         text not null,
  element_context jsonb,
  task_id         text,
  status          text not null,
  created_at      timestamptz not null default clock_timestamp()
);
create index message_app_idx on message(app_id, created_at);

create table draft (
  app_id          text primary key,
  base_version_id text not null,
  patches         jsonb not null,
  updated_at      timestamptz not null default now()
);

create table checkpoint (
  task_id       text primary key,
  workspace_dir text not null,
  agent_dir     text,
  session_file  text,
  draft_applied boolean not null default false,
  created_at    timestamptz not null default now()
);

create table deployment (
  id         text primary key,
  app_id     text not null,
  version_id text not null,
  build_id   text,
  status     text not null,
  url        text,
  created_at timestamptz not null default now()
);

create table asset (
  id           text primary key,
  workspace_id text not null,
  app_id       text,
  object_key   text not null,
  mime         text not null,
  size         bigint not null,
  name         text not null,
  created_at   timestamptz not null default now()
);
create index asset_ws_idx on asset(workspace_id, created_at);

create table upload (
  id           text primary key,
  workspace_id text not null,
  object_key   text not null,
  file_name    text not null,
  format       text not null,
  folder_name  text,
  manifest     jsonb not null,
  created_at   timestamptz not null default now()
);

create table model_usage (
  id            bigserial primary key,
  task_id       text,
  provider      text not null,
  model         text,
  input_tokens  int not null default 0,
  output_tokens int not null default 0,
  cost          numeric(14,6) not null default 0,
  created_at    timestamptz not null default now()
);
create index model_usage_task_idx on model_usage(task_id);

create table task_token (
  token_hash text primary key,
  task_id    text not null,
  expires_at timestamptz not null,
  revoked    boolean not null default false,
  created_at timestamptz not null default now()
);
