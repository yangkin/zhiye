-- 多副本集群拓扑心跳与沙箱全生命周期台账（对账 / 资源采样 / 运维强杀与审计）

create table if not exists cluster_node (
  instance_id         text primary key,
  role                text not null default 'api',
  hostname            text not null default '',
  port                int not null default 8080,
  sandbox_provider    text not null default 'local',
  worker_owner        text not null default '',
  worker_concurrency  int not null default 4,
  active_tasks        int not null default 0,
  active_sandboxes    int not null default 0,
  cpu_percent         double precision,
  heap_used_bytes     bigint not null default 0,
  heap_max_bytes      bigint not null default 0,
  sys_mem_used_bytes  bigint not null default 0,
  sys_mem_total_bytes bigint not null default 0,
  status              text not null default 'online',
  started_at          timestamptz not null default now(),
  heartbeat_at        timestamptz not null default now()
);

create index if not exists cluster_node_hb_idx on cluster_node (heartbeat_at desc);

create table if not exists sandbox_instance (
  sandbox_id         text primary key,
  provider           text not null,
  kind               text not null default 'task',
  task_id            text,
  app_id             text,
  workspace_id       text,
  user_id            text,
  owner_instance     text not null,
  worker_owner       text not null default '',
  state              text not null default 'creating',
  external_ref       text not null default '',
  image_or_cmd       text not null default '',
  cpu_limit          text,
  memory_limit       text,
  allow_net          boolean not null default false,
  pid                bigint,
  startup_ms         int,
  cpu_percent        double precision,
  memory_used_bytes  bigint,
  memory_limit_bytes bigint,
  sampled_at         timestamptz,
  created_at         timestamptz not null default now(),
  ready_at           timestamptz,
  last_active_at     timestamptz,
  destroyed_at       timestamptz,
  destroyed_by       text,
  destroy_reason     text
);

create index if not exists sandbox_instance_state_idx on sandbox_instance (state, created_at desc);
create index if not exists sandbox_instance_task_idx on sandbox_instance (task_id) where task_id is not null;
create index if not exists sandbox_instance_ws_idx on sandbox_instance (workspace_id, created_at desc) where workspace_id is not null;
