create table if not exists runtime_metric_event (
    id          bigserial primary key,
    instance_id text not null,
    category    text not null,
    name        text not null,
    ok          boolean not null,
    error_code  text,
    duration_ms bigint not null,
    retries     int not null default 0,
    quota_error boolean not null default false,
    created_at  timestamptz not null default now()
);

create index if not exists runtime_metric_event_cat_ts_idx
    on runtime_metric_event (category, created_at desc);
