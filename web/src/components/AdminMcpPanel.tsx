import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { Modal } from './Modal';
import { confirmDialog } from './ConfirmDialog';

/** 平台级企业受控 MCP 注册中心面板（嵌入管理页「配额与运行」视图）。 */
export function AdminMcpPanel() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['admin-mcp-servers'], queryFn: api.adminMcpList, staleTime: 15_000 });
  const [addOpen, setAddOpen] = useState(false);
  const [testingKey, setTestingKey] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<{ serverKey: string; tool: string; durationMs: number; content: string } | null>(null);

  const [serverKey, setServerKey] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [endpointUrl, setEndpointUrl] = useState('');
  const [authHeader, setAuthHeader] = useState('Authorization');
  const [secret, setSecret] = useState('');
  const [toolsText, setToolsText] = useState('search_docs: 检索公司文档或接口规范');
  const [saving, setSaving] = useState(false);

  const servers = q.data?.servers ?? [];
  const audits = q.data?.audits ?? [];
  const refresh = () => qc.invalidateQueries({ queryKey: ['admin-mcp-servers'] });

  const parseTools = (raw: string) =>
    raw
      .split(/\r?\n/)
      .map((l) => l.trim())
      .filter(Boolean)
      .map((l) => {
        const idx = l.indexOf(':');
        return idx > 0
          ? { name: l.slice(0, idx).trim(), description: l.slice(idx + 1).trim() }
          : { name: l, description: '' };
      });

  const handleCreate = async () => {
    setSaving(true);
    try {
      await api.adminMcpCreate({
        serverKey: serverKey.trim(),
        name: name.trim(),
        description: description.trim(),
        transport: 'http',
        endpointUrl: endpointUrl.trim(),
        authHeader: authHeader.trim() || 'Authorization',
        secret: secret.trim() || undefined,
        tools: parseTools(toolsText),
        enabled: true,
      });
      await refresh();
      setAddOpen(false);
      setServerKey('');
      setName('');
      setDescription('');
      setEndpointUrl('');
      setSecret('');
      toast('已登记企业 MCP 服务，编码智能体在下次任务即可通过 mcp_list / mcp_call 调用。');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleToggle = async (id: string, curEnabled: boolean) => {
    try {
      await api.adminMcpUpdate(id, { enabled: !curEnabled });
      await refresh();
      toast(!curEnabled ? '已启用该 MCP 服务。' : '已停用该 MCP 服务。');
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const handleDelete = async (id: string, label: string) => {
    const ok = await confirmDialog({
      title: `删除 MCP 服务「${label}」？`,
      message: '删除后沙箱中的编码智能体将无法再调用该服务。',
      confirmLabel: '确认删除',
      danger: true,
    });
    if (!ok) return;
    try {
      await api.adminMcpDelete(id);
      await refresh();
      toast('已删除 MCP 服务。');
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const handleTest = async (srvKey: string, firstTool?: string) => {
    setTestingKey(srvKey);
    try {
      const res = await api.adminMcpTest(srvKey, { tool: firstTool || 'search_specs', arguments: { q: '8D 良率' } });
      setTestResult(res);
      await refresh();
      toast(`MCP 试调用成功（${res.durationMs}ms）。`);
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setTestingKey(null);
    }
  };

  return (
    <div className="dash-panel" aria-label="企业受控 MCP 注册中心">
      <div className="dash-panel-head">
        <div>
          <h3>企业受控 MCP 注册中心（{servers.filter((s) => s.enabled).length} / {servers.length} 启用）</h3>
          <p className="muted-note">
            统一托管公司内部 MCP 服务（文档、接口契约、GitLab 等）；认证凭据 AES-256-GCM 加密落库永不进沙箱，所有调用经服务端代理并写入审计日志。
          </p>
        </div>
        <button type="button" className="primary-button dash-compact-btn" onClick={() => setAddOpen(true)}>
          <Icon name="plus" /> 登记 MCP 服务
        </button>
      </div>

      {q.isLoading ? <p className="muted-note">正在读取 MCP 服务清单…</p> : null}
      {servers.length > 0 ? (
        <div className="dash-table-wrap">
          <table className="dash-table">
            <thead>
              <tr>
                <th scope="col">服务名称 / 标识</th>
                <th scope="col">传输协议 / 地址</th>
                <th scope="col">开放工具</th>
                <th scope="col">状态</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              {servers.map((s) => (
                <tr key={s.id}>
                  <td>
                    <b>{s.name}</b>
                    <div>
                      <code>{s.serverKey}</code>
                    </div>
                  </td>
                  <td>
                    <span className="dash-pill cloud">{s.transport.toUpperCase()}</span>
                    <div className="dash-muted" style={{ maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {s.endpointUrl || '—'}
                    </div>
                  </td>
                  <td>
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                      {(s.tools ?? []).map((t) => (
                        <code key={t.name} title={t.description || t.name}>{t.name}</code>
                      ))}
                    </div>
                  </td>
                  <td>
                    <span className={`dash-pill ${s.enabled ? 'ok' : ''}`}>{s.enabled ? '已启用' : '已停用'}</span>
                  </td>
                  <td className="dash-nowrap">
                    <div className="dash-action-row">
                      <button
                        type="button"
                        className="dash-link"
                        disabled={!s.enabled || testingKey === s.serverKey}
                        onClick={() => void handleTest(s.serverKey, s.tools?.[0]?.name)}
                      >
                        {testingKey === s.serverKey ? '测试中…' : '试调用'}
                      </button>
                      <button type="button" className="dash-link" onClick={() => void handleToggle(s.id, s.enabled)}>
                        {s.enabled ? '停用' : '启用'}
                      </button>
                      <button type="button" className="dash-link danger" onClick={() => void handleDelete(s.id, s.name)}>
                        删除
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {testResult ? (
        <div className="notice-box" style={{ marginTop: 12 }}>
          <b>试调用结果 · {testResult.serverKey}/{testResult.tool}（{testResult.durationMs}ms）</b>
          <pre style={{ margin: '6px 0 0', whiteSpace: 'pre-wrap', fontSize: 12, maxHeight: 180, overflow: 'auto' }}>{testResult.content}</pre>
        </div>
      ) : null}

      {audits.length > 0 ? (
        <details style={{ marginTop: 12 }}>
          <summary style={{ cursor: 'pointer', fontSize: 12, color: '#6b6380' }}>
            最近 MCP 调用审计（{audits.length} 条）
          </summary>
          <div className="dash-table-wrap" style={{ marginTop: 8 }}>
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">时间</th>
                  <th scope="col">服务 / 工具</th>
                  <th scope="col">任务 / 应用</th>
                  <th scope="col">结果 / 耗时</th>
                </tr>
              </thead>
              <tbody>
                {audits.slice(0, 12).map((a) => (
                  <tr key={a.id}>
                    <td>{a.createdAt.replace('T', ' ').slice(0, 19)}</td>
                    <td><code>{a.serverKey}</code> / <code>{a.toolName}</code></td>
                    <td><code>{a.taskId || '—'}</code></td>
                    <td>
                      <span className={`dash-pill ${a.ok ? 'ok' : 'bad'}`}>{a.ok ? '成功' : '失败'}</span> {a.durationMs}ms
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      ) : null}

      {addOpen ? (
        <Modal
          title="登记企业 MCP 服务"
          overline="平台 MCP 治理"
          className="eco-modal"
          onClose={() => setAddOpen(false)}
          footer={
            <>
              <button type="button" className="subtle-button" onClick={() => setAddOpen(false)}>取消</button>
              <button
                type="button"
                className="primary-button"
                disabled={saving || !serverKey.trim() || !name.trim() || !endpointUrl.trim()}
                onClick={() => void handleCreate()}
              >
                {saving ? '保存中…' : '保存并启用'}
              </button>
            </>
          }
        >
          <div className="eco-form">
            <div className="eco-grid-2">
              <div className="eco-field">
                <div className="eco-label-row">
                  <label>服务标识 *</label>
                  <span className="eco-label-badge">英文小写</span>
                </div>
                <input className="mono" placeholder="如 csot-gitlab-mcp" value={serverKey} onChange={(e) => setServerKey(e.target.value)} />
              </div>
              <div className="eco-field">
                <div className="eco-label-row">
                  <label>显示名称 *</label>
                </div>
                <input placeholder="如 华星 GitLab 研发知识 MCP" value={name} onChange={(e) => setName(e.target.value)} />
              </div>
            </div>

            <div className="eco-field">
              <div className="eco-label-row">
                <label>服务地址 *</label>
                <span className="eco-label-badge">Streamable HTTP / JSON-RPC 2.0</span>
              </div>
              <input className="mono" placeholder="https://mcp.internal.example/mcp" value={endpointUrl} onChange={(e) => setEndpointUrl(e.target.value)} />
            </div>

            <div className="eco-fieldset">
              <div className="eco-fieldset-title">鉴权与工具暴露</div>
              <div className="eco-grid-2">
                <div className="eco-field">
                  <div className="eco-label-row">
                    <label>鉴权请求头</label>
                    <span className="eco-label-badge">可选</span>
                  </div>
                  <input className="mono" placeholder="Authorization" value={authHeader} onChange={(e) => setAuthHeader(e.target.value)} />
                </div>
                <div className="eco-field">
                  <div className="eco-label-row">
                    <label>鉴权密钥</label>
                    <span className="eco-label-badge">AES-256-GCM 加密</span>
                  </div>
                  <input type="password" placeholder="Bearer xxx" value={secret} onChange={(e) => setSecret(e.target.value)} />
                </div>
              </div>
              <div className="eco-field">
                <div className="eco-label-row">
                  <label>服务说明</label>
                  <span className="eco-label-badge">帮助 AI 判断调用时机</span>
                </div>
                <input placeholder="告诉编码智能体何时调用此 MCP" value={description} onChange={(e) => setDescription(e.target.value)} />
              </div>
              <div className="eco-field">
                <div className="eco-label-row">
                  <label>开放工具列表</label>
                  <span className="eco-label-badge">每行：工具名: 说明</span>
                </div>
                <textarea className="mono" rows={3} value={toolsText} onChange={(e) => setToolsText(e.target.value)} />
              </div>
            </div>
          </div>
        </Modal>
      ) : null}
    </div>
  );
}
