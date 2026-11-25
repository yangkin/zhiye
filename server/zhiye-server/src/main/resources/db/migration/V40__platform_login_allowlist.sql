-- 平台登录白名单（人员管理）：开启 zhiye.auth.login-allowlist-enabled 后，只有此表内启用的人员和管理员能登录
create table if not exists platform_login_allowlist (
  login_name  text primary key,               -- SSO 工号/登录名
  real_name   text,                           -- 姓名（便于管理员识别）
  note        text,                           -- 备注（部门、用途等）
  enabled     boolean not null default true,  -- 停用即不能登录，但保留记录
  created_by  text,                           -- 添加人
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
