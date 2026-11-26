-- V53: 三层数据库容量治理与防刷配额
-- 1) 工作空间级默认容量覆写（共享云服务 / 单体托管 PostgreSQL，单位 MB；NULL 表示沿用系统默认）
alter table workspace_quota
    add column if not exists cloud_db_quota_mb integer,
    add column if not exists service_db_quota_mb integer;

-- 2) 单应用级数据库容量配额、限写状态与巡检快照
create table if not exists app_db_quota (
    app_id text primary key references app(id) on delete cascade,
    quota_mb integer,
    write_blocked boolean not null default false,
    blocked_by_admin boolean not null default false,
    blocked_reason text,
    used_bytes bigint not null default 0,
    row_estimate bigint not null default 0,
    table_count integer not null default 0,
    last_scanned_at timestamptz,
    updated_by text,
    updated_at timestamptz not null default now()
);

create index if not exists idx_app_db_quota_used_bytes on app_db_quota (used_bytes desc);
create index if not exists idx_app_db_quota_blocked on app_db_quota (write_blocked) where write_blocked = true;
