-- 会话消息引用的素材（图片/文档），问答时带给模型
alter table conversation_message add column asset_ids jsonb not null default '[]';
