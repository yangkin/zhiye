-- 已发布站点的每日页面访问量（按 Asia/Shanghai 日期）
create table app_pageview_daily (
  app_id text   not null,
  day    date   not null,
  pv     bigint not null default 0,
  primary key (app_id, day)
);
