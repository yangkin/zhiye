-- 灵感社区：页面快照（静态预览，不带数据和令牌）、作者、来源应用、浏览/使用/点赞计数，以及点赞记录
alter table template add column preview_prefix text;
alter table template add column author text;
alter table template add column source_app_id text;
alter table template add column clone_count int not null default 0;
alter table template add column view_count int not null default 0;
alter table template add column like_count int not null default 0;

create table template_like (
  template_id text not null references template (id) on delete cascade,
  workspace_id text not null,
  created_at timestamptz not null default now(),
  primary key (template_id, workspace_id)
);
create index template_like_ws_idx on template_like (workspace_id);
