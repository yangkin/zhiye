-- 云函数调用记录（控制台「函数」标签、修改任务附带最近报错；保留 7 天）
create table app_fn_log (
  id          bigserial primary key,
  app_id      text not null references app(id) on delete cascade,
  name        text not null,
  version_id  text,
  ok          boolean not null,
  duration_ms int not null,
  error       text,
  logs        text,
  caller      text,
  created_at  timestamptz not null default now()
);
create index app_fn_log_app_idx on app_fn_log (app_id, created_at desc);
