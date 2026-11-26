-- 清理历史内置本地仿真 MCP、飞书沙箱演示连接器与本地模拟 Git 仓库，确保生态全为真实配置
update enterprise_mcp_server
   set transport = 'knowledge', updated_at = now()
 where transport = 'mock' and endpoint_url like 'kb:%';

delete from enterprise_mcp_server
 where id in ('mcp_builtin_docs', 'mcp_builtin_api_catalog')
    or transport = 'mock';

delete from enterprise_connector
 where id in ('conn_feishu_bot_sandbox', 'conn_feishu_bitable_sandbox')
    or mode = 'sandbox';

delete from app_git_repo
 where provider = 'mock';
