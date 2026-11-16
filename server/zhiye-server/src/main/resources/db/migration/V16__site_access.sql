-- 发布访问级别（20.4.1）：内网公开 / 员工登录 / 指定人员；管理员名单（权限预设里的「管理员」，作者默认是管理员）
create table app_site_access (
  app_id     text primary key references app(id) on delete cascade,
  level      text not null default 'public' check (level in ('public', 'login', 'members')),
  members    jsonb not null default '[]'::jsonb,
  admins     jsonb not null default '[]'::jsonb,
  updated_at timestamptz not null default now()
);

-- 发布站点自己的会话（cookie 只写在应用子域名上）
create table site_session (
  token_hash text primary key,
  app_id     text not null references app(id) on delete cascade,
  user_id    text not null references app_user(id) on delete cascade,
  expires_at timestamptz not null,
  created_at timestamptz not null default now()
);
create index site_session_app_idx on site_session (app_id);

-- 统一登录回调后换会话用的一次性码（60 秒、只能用一次）
create table site_sso_code (
  code_hash  text primary key,
  app_id     text not null references app(id) on delete cascade,
  user_id    text not null references app_user(id) on delete cascade,
  next_path  text not null,
  expires_at timestamptz not null,
  used_at    timestamptz
);
