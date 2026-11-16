-- 云服务：每个应用一份独立 schema + 存储前缀 + 应用令牌
create table app_cloud (
    app_id         text primary key,
    status         text not null default 'off',        -- off | on
    schema_name    text not null,
    storage_prefix text not null,
    token_hash     text not null,
    enabled_at     timestamptz,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

-- 由 cloud/schema.sql 声明的表（列与策略存在控制面，便于校验和接口文档）
create table app_cloud_table (
    app_id    text not null,
    name      text not null,
    columns   jsonb not null,
    policy    jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    primary key (app_id, name)
);

create index app_cloud_table_app_idx on app_cloud_table (app_id);
