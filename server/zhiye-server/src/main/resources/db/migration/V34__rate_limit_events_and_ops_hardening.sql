create table if not exists rate_limit_event (
    id bigserial primary key,
    bucket varchar(128) not null,
    outcome varchar(32) not null,
    code varchar(64),
    window_seconds int not null default 60,
    max_requests int not null default 0,
    current_count bigint not null default 0,
    created_at timestamptz not null default now()
);

create index if not exists rate_limit_event_ts_idx
    on rate_limit_event (created_at desc);

create index if not exists rate_limit_event_bucket_outcome_idx
    on rate_limit_event (bucket, outcome, created_at desc);

create index if not exists platform_event_log_task_idx
    on platform_event_log (task_id, created_at desc)
    where task_id is not null;
