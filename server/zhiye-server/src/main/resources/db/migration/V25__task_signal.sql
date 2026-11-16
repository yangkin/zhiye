-- 跨实例的任务信号：确认卡的选择、中途补充要求落到收到请求的实例上，但任务可能在另一个实例里跑。
-- 收到请求的实例写一条，任务所在实例的心跳（约 1 秒）取走并交给正在执行的任务。dedupe 防止同一张确认卡被答两次。
create table task_signal (
  id          bigserial primary key,
  task_id     text not null references task(id) on delete cascade,
  kind        text not null,
  dedupe      text,
  payload     jsonb not null,
  created_at  timestamptz not null default now(),
  consumed_at timestamptz,
  unique (task_id, dedupe)
);
create index task_signal_pending on task_signal (task_id) where consumed_at is null;
