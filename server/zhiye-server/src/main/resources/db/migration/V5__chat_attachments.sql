-- 对话附带的素材、引用的技能
alter table message add column asset_ids jsonb;
alter table app add column skill_app_ids jsonb not null default '[]';
