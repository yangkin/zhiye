-- 排队消息可以带「执行前先启用云服务」：任务进行中点「共享云服务」时，启用和设计表结构的消息一起排队，撤回就不启用
alter table task_message_queue add column enable_cloud boolean not null default false;
