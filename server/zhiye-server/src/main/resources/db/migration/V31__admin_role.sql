-- 平台管理员角色字段：默认均为普通用户（user），仅明确授权管理员（admin）或配置白名单账号可访问全平台管理看板、运维日志与治理操作
alter table app_user add column if not exists role text not null default 'user';
