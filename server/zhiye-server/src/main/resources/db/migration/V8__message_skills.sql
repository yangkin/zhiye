-- 对话消息引用的技能（与 asset_ids 同样存放）
alter table message add column if not exists skill_app_ids jsonb;
