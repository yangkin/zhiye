-- 单体后端预览槽位与线上发布槽位分离（FS-04）：编辑/AI修改/恢复旧版仅更新预览槽位，点「发布」后才切流到线上槽位
alter table app_service
    add column if not exists preview_version_id text,
    add column if not exists preview_endpoint_url text,
    add column if not exists preview_status text;
