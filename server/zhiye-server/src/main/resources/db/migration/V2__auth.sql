-- 统一认证（SSO）用户与织页会话。默认免登录模式下这两张表不使用。
create table app_user (
  id            text primary key,
  sso_user_id   text not null unique,
  login_name    text not null,
  real_name     text,
  email         text,
  tenant_num    text,
  created_at    timestamptz not null default now(),
  last_login_at timestamptz not null default now()
);

create table user_session (
  id          text primary key,
  user_id     text not null references app_user(id) on delete cascade,
  token_hash  text not null unique,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null
);
create index user_session_user_idx on user_session(user_id);

create index workspace_owner_idx on workspace(owner_user_id) where owner_user_id is not null;
