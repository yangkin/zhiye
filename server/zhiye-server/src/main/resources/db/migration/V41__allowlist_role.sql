-- 人员管理：白名单条目带角色（user / admin），登录时同步到 app_user.role，支持登录前预授管理员
alter table platform_login_allowlist add column if not exists role text not null default 'user';
