-- Phase 2: 持久化活体开发环境（Live Dev Sandbox）与 Snapshot-to-Deploy 缓存表

create table if not exists app_live_workspace (
    app_id          varchar(64) primary key references app(id) on delete cascade,
    workspace_id    varchar(64) not null,
    status          varchar(24) not null default 'idle',
    project_kind    varchar(32) not null default 'template',
    workspace_dir   text not null,
    dev_port        int,
    backend_port    int,
    dev_pid         bigint,
    backend_pid     bigint,
    deps_hash       varchar(64),
    snapshot_commit varchar(64),
    snapshot_ready  boolean not null default false,
    last_error      text,
    idle_sleep_min  int not null default 20,
    last_active_at  timestamptz not null default now(),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create index if not exists idx_app_live_ws_status on app_live_workspace (status, last_active_at);
