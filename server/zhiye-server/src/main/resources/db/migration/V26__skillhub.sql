-- SkillHub（公司 fivestar-service Skill 市场）：安装记录、发布记录补充审核状态
create table if not exists skillhub_install (
  id           text primary key,
  workspace_id text not null references workspace(id),
  skill_id     text not null,
  skill_slug   text not null,
  version      text not null default '',
  name         text not null,
  description  text not null default '',
  size_bytes   bigint not null default 0,
  file_count   int not null default 0,
  sha256       text not null default '',
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz
);
create index if not exists skillhub_install_ws_idx on skillhub_install(workspace_id) where deleted_at is null;
create index if not exists skillhub_install_skill_idx on skillhub_install(skill_id);

alter table skill_publication add column if not exists slug text;
alter table skill_publication add column if not exists status text;
alter table skill_publication add column if not exists review_status text;
alter table skill_publication add column if not exists version_id_platform text;
alter table skill_publication add column if not exists submission_key text;
