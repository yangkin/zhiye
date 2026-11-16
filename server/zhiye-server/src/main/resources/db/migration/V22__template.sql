create table template (
  id text primary key,
  workspace_id text not null,
  name text not null,
  description text,
  app_type text not null,
  project_kind text,
  framework text,
  source_version int,
  object_key text not null,
  file_count int not null default 0,
  created_at timestamptz not null default now()
);
create index template_ws_idx on template (workspace_id);
