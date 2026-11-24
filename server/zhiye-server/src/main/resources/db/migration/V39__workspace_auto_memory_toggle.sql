-- 个人工作空间「自动从聊天中学习并记住」用户可控开关（默认开启，用户可在「我的工作空间」随时开关）
alter table workspace
    add column if not exists auto_memory_enabled boolean not null default true;
