-- 生成视图：任务的最新待办清单；对话中途补充（steer）的用户消息
alter table task add column if not exists todos jsonb;
alter table message add column if not exists steer boolean not null default false;
