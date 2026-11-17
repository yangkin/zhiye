-- 跨实例的流式对话停止信号与分布式滑动窗口限流表。
create table if not exists conversation_stop (
  conversation_id text primary key,
  requested_at    timestamptz not null default now()
);

create table if not exists rate_limit_hit (
  bucket     text not null,
  created_at timestamptz not null default now()
);

create index if not exists rate_limit_hit_bucket_ts on rate_limit_hit (bucket, created_at);
