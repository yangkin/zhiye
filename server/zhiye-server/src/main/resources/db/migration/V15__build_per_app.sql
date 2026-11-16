-- 构建按应用隔离：预览地址 /p/{buildId}/ 要能唯一对应到一个应用（注入云服务令牌、数据源时据此判断归属）。
-- 以前按文件树全局复用，两个应用产物相同时会共用一个 buildId。
alter table build add column app_id text;
update build b set app_id = v.app_id
  from (select build_id, min(app_id) as app_id, count(distinct app_id) as n from version where build_id is not null group by build_id) v
  where v.build_id = b.id and v.n = 1;
alter table build drop constraint build_git_tree_key;
create unique index build_app_tree_uq on build (app_id, git_tree);
