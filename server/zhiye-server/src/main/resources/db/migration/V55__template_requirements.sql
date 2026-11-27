-- 灵感社区作品脱敏依赖清单（仅存数据源调用名/连接器类型/环境变量键名等非敏感元数据，绝不存密钥或业务数据）
alter table template
    add column if not exists requirements jsonb not null default '{}'::jsonb;

-- 复刻自灵感社区模板的应用保留来源模板 ID 与脱敏依赖清单，供工作台实时检测缺失配置并引导绑定
alter table app
    add column if not exists source_template_id text;

alter table app
    add column if not exists template_requirements jsonb;
