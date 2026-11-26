-- V52: 同步云服务数据表权限「只读展示 + 对话由 AI 同步调整」说明到帮助文档与小织知识库
update help_knowledge
set content_md = replace(
        content_md,
        '工作台可视化增删改查、Excel/CSV 批量导入导出、6 种权限预设。',
        '工作台可视化增删改查、Excel/CSV 批量导入导出，并以只读方式展示当前表级权限规则（由平台接口层强制执行，如需调整直接在左侧对话让织页同步更新页面与规则，避免手动错配）。'
    ),
    updated_at = now()
where id = 'hk_cloud';
