-- skill 应用发布到公司 SkillHub 的记录（接口预留）
create table skill_publication (
  id            text primary key,
  app_id        text not null,
  version_id    text not null,
  skillhub_id   text not null,
  skill_version text,
  url           text,
  visibility    text not null,
  created_at    timestamptz not null default now()
);
create index skill_publication_app_idx on skill_publication(app_id, created_at desc);
