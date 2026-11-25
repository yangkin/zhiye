--- 会话级数据源：创建应用时自动继承，问答和刷新都使用同一份连接配置
alter table conversation add column source_ids jsonb not null default '[]';
