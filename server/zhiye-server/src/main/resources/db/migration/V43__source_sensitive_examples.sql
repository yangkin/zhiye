-- 历史数据源的参数示例可能误存凭证；只清空示例字段，不改变参数定义或真实代理凭证。
update data_source
set params = (
    select jsonb_agg(case
        when p->>'name' ~* 'secret|password|credential|token|app[-_]?key|api[-_]?key|authorization|webhook'
            then jsonb_set(p, '{example}', '""', false)
        else p end)
    from jsonb_array_elements(params) p
)
where exists (
    select 1 from jsonb_array_elements(params) p
    where p->>'name' ~* 'secret|password|credential|token|app[-_]?key|api[-_]?key|authorization|webhook'
      and coalesce(p->>'example', '') <> ''
);
