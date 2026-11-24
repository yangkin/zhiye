-- 单体后端应用运行托管（Python / Node.js / Java / Go / Dockerfile）
create table if not exists app_service (
  app_id             text primary key references app(id) on delete cascade,
  runtime            text not null,                  -- python | node | java | go | dockerfile
  framework          text not null default '',       -- 如 Python (FastAPI) / Node.js (Express) / Java (Spring Boot) / Go HTTP
  frontend_dir       text not null default '',       -- 前后端一体仓库中的前端子目录
  backend_dir        text not null default '',       -- 后端子目录（空字符串表示根目录）
  build_cmd          text not null default '',       -- 构建命令（可覆写）
  start_cmd          text not null default '',       -- 启动命令（可覆写）
  port               int  not null default 8080,     -- 应用监听端口（注入 PORT 环境变量）
  health_path        text not null default '/',      -- 健康检查路径
  memory_mb          int  not null default 512,      -- 内存上限 MB（Java 默认 1024）
  cpu_millicores     int  not null default 500,      -- CPU 上限 mCPU（Java 默认 1000）
  idle_sleep_min     int  not null default 20,       -- 闲置自动休眠分钟数（0 表示关闭休眠）
  pg_enabled         boolean not null default false, -- 自动分配独立 PostgreSQL Schema（注入 DATABASE_URL）
  pg_schema          text,
  pg_role            text,
  pg_password_enc    text,
  pvc_enabled        boolean not null default false, -- 挂载持久化 /data 目录（适配 SQLite / 上传文件）
  pvc_size_gi        int  not null default 1,
  status             text not null default 'stopped', -- building | running | sleeping | stopped | failed
  desired_version_id text,
  running_version_id text,
  image_ref          text,
  endpoint_url       text,
  last_access_at     timestamptz,
  last_started_at    timestamptz,
  last_error         text,
  build_log          text,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now()
);

create index if not exists idx_app_service_status on app_service(status, last_access_at);

-- 应用运行态密文环境变量（AES-256-GCM 加密存储，运行时注入进程或 K8s Secret）
create table if not exists app_service_env (
  app_id     text not null references app(id) on delete cascade,
  env_key    text not null,
  value_enc  text not null,
  secret     boolean not null default true,
  updated_at timestamptz not null default now(),
  primary key (app_id, env_key)
);

-- 单体应用构建与发布流水记录
create table if not exists app_service_release (
  id          text primary key,
  app_id      text not null references app(id) on delete cascade,
  version_id  text,
  status      text not null, -- building | succeeded | failed
  image_ref   text,
  build_log   text,
  error       text,
  started_at  timestamptz not null default now(),
  finished_at timestamptz
);

create index if not exists idx_app_service_release_app on app_service_release(app_id, started_at desc);
