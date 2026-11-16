-- 一个对话修改任务可以产生两个版本：先保存手动修改（stage=visual），再保存 AI 修改（stage=main）
alter table version drop constraint if exists version_task_id_key;
alter table version add column task_stage text not null default 'main';
create unique index version_task_stage_uq on version(task_id, task_stage) where task_id is not null;
