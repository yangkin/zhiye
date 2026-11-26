import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type { DataSourceInfo } from '../api/types';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { Modal } from '../components/Modal';
import { confirmDialog } from '../components/ConfirmDialog';
import { AgwCatalog } from '../components/AgwCatalog';
import { SourceForm, SourceTest, useSources } from '../components/DataSourceDialog';

type EcoTab = 'feishu' | 'agw' | 'mcp';
type AgwModal = null | { kind: 'catalog' } | { kind: 'edit'; source: DataSourceInfo | null } | { kind: 'test'; source: DataSourceInfo };

/** 独立一级页面：生态管理（统一维护飞书连接器、AGW 企业接口与企业知识 MCP；聊天栏只管一键勾选使用）。 */
export function EcosystemPage() {
  const qc = useQueryClient();
  const [tab, setTab] = useState<EcoTab>('feishu');
  const [query, setQuery] = useState('');
  const [feishuModal, setFeishuModal] = useState<null | { existing: Record<string, unknown> | null }>(null);
  const [mcpModal, setMcpModal] = useState<null | { existing: Record<string, unknown> | null }>(null);
  const [agwModal, setAgwModal] = useState<AgwModal>(null);

  const [testingId, setTestingId] = useState<string | null>(null);
  const [testResults, setTestResults] = useState<Record<string, { ok: boolean; text: string }>>({});
  const [mcpTestingKey, setMcpTestingKey] = useState<string | null>(null);
  const [mcpTestResults, setMcpTestResults] = useState<Record<string, { ok: boolean; text: string }>>({});

  const connQ = useQuery({ queryKey: ['workspace-connectors'], queryFn: api.connectorsList, staleTime: 15_000 });
  const sourcesQ = useSources(true);
  const mcpQ = useQuery({ queryKey: ['workspace-mcp-servers'], queryFn: api.mcpServersList, staleTime: 30_000 });

  const kw = query.trim().toLowerCase();
  const connectors = useMemo(
    () => (connQ.data ?? []).filter((c) => !kw || `${String(c.name ?? '')} ${String(c.id ?? '')} ${String(c.kind ?? '')}`.toLowerCase().includes(kw)),
    [connQ.data, kw],
  );
  const sources = useMemo(
    () => (sourcesQ.data ?? []).filter((s) => !kw || `${s.name} ${s.description ?? ''} ${s.url ?? ''}`.toLowerCase().includes(kw)),
    [sourcesQ.data, kw],
  );
  const mcpServers = useMemo(
    () => (mcpQ.data ?? []).filter((m) => !kw || `${String(m.name ?? '')} ${String(m.serverKey ?? '')} ${String(m.description ?? '')}`.toLowerCase().includes(kw)),
    [mcpQ.data, kw],
  );

  const handleTestConnector = async (id: string) => {
    setTestingId(id);
    try {
      const r = await api.connectorTest(id);
      const text = `连通正常（mode=${String(r.mode ?? 'ok')}）`;
      setTestResults((prev) => ({ ...prev, [id]: { ok: true, text } }));
      toast(`连接器${text}`);
    } catch (e) {
      const msg = errorMessage(e);
      setTestResults((prev) => ({ ...prev, [id]: { ok: false, text: msg } }));
      toast(msg, 'error');
    } finally {
      setTestingId(null);
    }
  };

  const handleDeleteConnector = async (id: string, name: string) => {
    if (!(await confirmDialog({ title: `删除飞书连接器「${name}」？`, message: '删除后需要重新配置才能在应用中使用。', confirmLabel: '删除', danger: true }))) return;
    try {
      await api.connectorDelete(id);
      await qc.invalidateQueries({ queryKey: ['workspace-connectors'] });
      toast(`已删除「${name}」`);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const handleDeleteSource = async (s: DataSourceInfo) => {
    if (!(await confirmDialog({ title: `删除数据源「${s.name}」？`, message: '已绑定的应用将不能再调用该接口。', confirmLabel: '删除', danger: true }))) return;
    try {
      await api.deleteSource(s.id);
      await qc.invalidateQueries({ queryKey: qk.sources });
      void qc.invalidateQueries({ queryKey: ['appSources'] });
      toast(`已删除「${s.name}」`);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const handleTestMcp = async (key: string, isHttp: boolean, toolName: string) => {
    setMcpTestingKey(key);
    try {
      const r = isHttp
        ? await api.mcpServerTest(key, '__ping__')
        : await api.mcpServerTest(key, toolName, { query: '' });
      const raw = String(r.content ?? '').replace(/\s+/g, ' ').trim();
      const preview = raw.length > 130 ? `${raw.slice(0, 130)}…` : raw || '调用成功';
      setMcpTestResults((prev) => ({ ...prev, [key]: { ok: true, text: `${preview} (${String(r.durationMs ?? 0)}ms)` } }));
      if (isHttp) void qc.invalidateQueries({ queryKey: ['workspace-mcp-servers'] });
      toast('知识库 / MCP 试调用通过');
    } catch (e) {
      const msg = errorMessage(e);
      setMcpTestResults((prev) => ({ ...prev, [key]: { ok: false, text: msg } }));
      toast(msg, 'error');
    } finally {
      setMcpTestingKey(null);
    }
  };

  const handleDeleteMcp = async (id: string, name: string) => {
    if (!(await confirmDialog({ title: `删除知识库 / MCP「${name}」？`, message: '删除后无法在对话栏继续引用该知识库或 MCP 工具。', confirmLabel: '删除', danger: true }))) return;
    try {
      await api.mcpServerDelete(id);
      await qc.invalidateQueries({ queryKey: ['workspace-mcp-servers'] });
      toast(`已删除「${name}」`);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <div className="content-page eco-page">
      <div className="page-heading">
        <div>
          <h1>生态管理</h1>
          <p>统一维护飞书机器人、多维表格、AGW 企业接口与知识库 MCP；配好后在「创造」和工作台聊天栏直接勾选即用。</p>
        </div>
        {tab === 'feishu' ? (
          <button type="button" className="primary-button" onClick={() => setFeishuModal({ existing: null })}>
            <Icon name="plus" />
            添加飞书连接器
          </button>
        ) : tab === 'agw' ? (
          <button type="button" className="primary-button" onClick={() => setAgwModal({ kind: 'catalog' })}>
            <Icon name="plus" />
            从 AGW 目录添加接口
          </button>
        ) : (
          <button type="button" className="primary-button" onClick={() => setMcpModal({ existing: null })}>
            <Icon name="plus" />
            添加知识库 / MCP
          </button>
        )}
      </div>

      {/* 三大生态概览卡片（同时可点击快速切换） */}
      <div className="eco-stats">
        <button type="button" className={`eco-stat-card ${tab === 'feishu' ? 'active' : ''}`} onClick={() => setTab('feishu')}>
          <span className="eco-stat-orb is-green"><Icon name="globe" /></span>
          <span className="eco-stat-meta">
            <b>飞书与多维表格</b>
            <small>群卡片通知 · 多维表格同步读写</small>
          </span>
          <span className="eco-stat-num">{connQ.data?.length ?? 0}</span>
        </button>

        <button type="button" className={`eco-stat-card ${tab === 'agw' ? 'active' : ''}`} onClick={() => setTab('agw')}>
          <span className="eco-stat-orb"><Icon name="database" /></span>
          <span className="eco-stat-meta">
            <b>AGW 企业接口</b>
            <small>MES / 设备 / 经营数据真实查询</small>
          </span>
          <span className="eco-stat-num">{sourcesQ.data?.length ?? 0}</span>
        </button>

        <button type="button" className={`eco-stat-card ${tab === 'mcp' ? 'active' : ''}`} onClick={() => setTab('mcp')}>
          <span className="eco-stat-orb is-indigo"><Icon name="spark" /></span>
          <span className="eco-stat-meta">
            <b>企业知识与工具 (MCP)</b>
            <small>编码时实时检索公司规范与契约</small>
          </span>
          <span className="eco-stat-num">{mcpQ.data?.length ?? 0}</span>
        </button>
      </div>

      {/* 搜索与筛选栏 */}
      <div className="collection-toolbar">
        <div className="filter-tabs" role="tablist" aria-label="生态类型">
          <button type="button" role="tab" aria-selected={tab === 'feishu'} className={tab === 'feishu' ? 'active' : ''} onClick={() => setTab('feishu')}>
            飞书与多维表格 ({connQ.data?.length ?? 0})
          </button>
          <button type="button" role="tab" aria-selected={tab === 'agw'} className={tab === 'agw' ? 'active' : ''} onClick={() => setTab('agw')}>
            AGW 企业接口 ({sourcesQ.data?.length ?? 0})
          </button>
          <button type="button" role="tab" aria-selected={tab === 'mcp'} className={tab === 'mcp' ? 'active' : ''} onClick={() => setTab('mcp')}>
            企业知识 / MCP ({mcpQ.data?.length ?? 0})
          </button>
        </div>
        <div className="collection-controls">
          <label className="search-field">
            <Icon name="search" />
            <input
              placeholder={tab === 'feishu' ? '搜索飞书连接器…' : tab === 'agw' ? '搜索接口名称或地址…' : '搜索 MCP 名称或工具…'}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            {query ? (
              <button type="button" aria-label="清除搜索" onClick={() => setQuery('')}>
                <Icon name="close" />
              </button>
            ) : null}
          </label>
        </div>
      </div>

      {/* Tab 1: 飞书机器人与多维表格 */}
      {tab === 'feishu' ? (
        connectors.length === 0 && !connQ.isLoading ? (
          <div className="eco-empty-card">
            <Icon name="globe" />
            <h3>{query ? '没有匹配的飞书连接器' : '还没有飞书连接器'}</h3>
            <p>添加你的飞书群机器人 Webhook 或粘贴飞书多维表格链接，密钥加密托管在当前工作空间，随时可测试连通并在对话中勾选使用。</p>
            <button type="button" className="primary-button" onClick={() => setFeishuModal({ existing: null })}>
              <Icon name="plus" /> 添加飞书连接器
            </button>
          </div>
        ) : (
          <div className="eco-grid">
            {connectors.map((c) => {
              const id = String(c.id ?? '');
              const name = String(c.name ?? id);
              const isBitable = String(c.kind ?? '') === 'feishu_bitable';
              const owned = Boolean(c.owned);
              const tr = testResults[id];
              return (
                <article key={id} className="eco-card">
                  <div className="eco-card-head">
                    <span className={`eco-card-icon ${isBitable ? '' : 'is-green'}`}>
                      <Icon name={isBitable ? 'database' : 'globe'} />
                    </span>
                    <div className="eco-card-title">
                      <h3>{name}</h3>
                      <div className="eco-badges">
                        <span className="eco-pill">{isBitable ? '飞书多维表格' : '飞书群机器人'}</span>
                        <span className="eco-pill is-live">公域直连</span>
                        <span className={`eco-pill ${owned ? 'is-owned' : ''}`}>
                          {owned ? '我添加的' : '平台托管'}
                        </span>
                        <span className="eco-pill">限流 {String(c.rateLimitPerMin ?? 60)}/min</span>
                      </div>
                    </div>
                  </div>
                  {tr ? (
                    <div className={`eco-test-banner ${tr.ok ? 'ok' : 'err'}`}>
                      {tr.ok ? '✓ ' : '✗ '}{tr.text}
                    </div>
                  ) : null}
                  <div className="eco-card-foot">
                    <span className="eco-card-id" title={id}>{id}</span>
                    <div className="eco-card-actions">
                      <button type="button" className="subtle-button" disabled={testingId === id} onClick={() => void handleTestConnector(id)}>
                        {testingId === id ? '测试中…' : '测试连通'}
                      </button>
                      {owned ? (
                        <>
                          <button type="button" className="subtle-button" onClick={() => setFeishuModal({ existing: c })}>
                            编辑
                          </button>
                          <button type="button" className="subtle-button" style={{ color: '#dc2626' }} onClick={() => void handleDeleteConnector(id, name)}>
                            删除
                          </button>
                        </>
                      ) : null}
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        )
      ) : null}

      {/* Tab 2: AGW 企业接口数据源 */}
      {tab === 'agw' ? (
        sources.length === 0 && !sourcesQ.isLoading ? (
          <div className="eco-empty-card">
            <Icon name="database" />
            <h3>{query ? '没有匹配的 AGW 接口' : '还没有添加 AGW 企业接口'}</h3>
            <p>从企业 API 网关选择当前账号有权限调用的接口，配好后在首页「创造」和工作台聊天栏可直接一键勾选使用，凭证全程不落前端代码。</p>
            <button type="button" className="primary-button" onClick={() => setAgwModal({ kind: 'catalog' })}>
              <Icon name="plus" /> 从 AGW 目录添加接口
            </button>
          </div>
        ) : (
          <div className="eco-grid">
            {sources.map((s) => (
              <article key={s.id} className="eco-card">
                <div className="eco-card-head">
                  <span className="eco-card-icon">
                    <Icon name="database" />
                  </span>
                  <div className="eco-card-title">
                    <h3>{s.name}</h3>
                    <div className="eco-badges">
                      <span className="eco-pill is-live">{s.provider === 'agw' ? 'API 网关' : 'HTTP 接口'}</span>
                      {s.platform ? <span className="eco-pill">平台托管</span> : <span className="eco-pill is-owned">工作空间</span>}
                      <span className="eco-pill">{s.method}</span>
                    </div>
                    {s.description || s.url ? <p className="eco-card-sub">{s.description || s.url}</p> : null}
                  </div>
                </div>
                <div className="eco-card-foot">
                  <span className="eco-card-id" title={s.url}>{s.url || s.id}</span>
                  <div className="eco-card-actions">
                    <button type="button" className="subtle-button" onClick={() => setAgwModal({ kind: 'test', source: s })}>
                      试调用
                    </button>
                    {!s.platform ? (
                      <>
                        <button type="button" className="subtle-button" onClick={() => setAgwModal({ kind: 'edit', source: s })}>
                          编辑
                        </button>
                        <button type="button" className="subtle-button" style={{ color: '#dc2626' }} onClick={() => void handleDeleteSource(s)}>
                          删除
                        </button>
                      </>
                    ) : null}
                  </div>
                </div>
              </article>
            ))}
          </div>
        )
      ) : null}

      {/* Tab 3: 企业知识与工具 MCP */}
      {tab === 'mcp' ? (
        mcpServers.length === 0 && !mcpQ.isLoading ? (
          <div className="eco-empty-card">
            <Icon name="spark" />
            <h3>{query ? '没有匹配的企业知识库或 MCP 服务' : '还没有企业知识库或 MCP 服务'}</h3>
            <p>你可以直接粘贴业务规范、字段字典或 SOP 文档创建本地知识库，也可以接入公司已有的 HTTP MCP 服务，配好后在对话栏「生态」直接勾选给 AI 检索使用。</p>
            <button type="button" className="primary-button" onClick={() => setMcpModal({ existing: null })}>
              <Icon name="plus" /> 添加知识库 / MCP
            </button>
          </div>
        ) : (
          <div className="eco-grid">
            {mcpServers.map((m) => {
              const id = String(m.id ?? '');
              const key = String(m.serverKey ?? id);
              const name = String(m.name ?? key);
              const desc = String(m.description ?? '');
              const owned = Boolean(m.owned);
              const isKb = Boolean(m.isKnowledgeBase);
              const tools = Array.isArray(m.tools) ? (m.tools as Array<Record<string, unknown>>) : [];
              const firstTool = tools.length > 0 ? String(tools[0].name ?? 'search_knowledge') : 'search_knowledge';
              const tr = mcpTestResults[key];
              return (
                <article key={key} className="eco-card">
                  <div className="eco-card-head">
                    <span className="eco-card-icon">
                      <Icon name={isKb ? 'book' : 'spark'} />
                    </span>
                    <div className="eco-card-title">
                      <h3>{name}</h3>
                      <div className="eco-badges">
                        <span className="eco-pill is-live">{isKb ? '团队知识库' : 'MCP 工具服务'}</span>
                        <span className={`eco-pill ${owned ? 'is-owned' : ''}`}>{owned ? '我添加的' : '平台托管'}</span>
                        {tools.length ? <span className="eco-pill">{tools.length} 个工具</span> : null}
                      </div>
                      {desc ? <p className="eco-card-sub">{desc}</p> : null}
                    </div>
                  </div>
                  {tools.length > 0 ? (
                    <div className="eco-tool-pills">
                      {tools.map((t, idx) => (
                        <span key={idx} className="eco-tool-pill" title={String(t.description ?? '')}>
                          {String(t.name ?? 'tool')}
                        </span>
                      ))}
                    </div>
                  ) : null}
                  {tr ? (
                    <div className={`eco-test-banner ${tr.ok ? 'ok' : 'err'}`}>
                      {tr.ok ? '✓ ' : '✗ '}{tr.text}
                    </div>
                  ) : null}
                  <div className="eco-card-foot">
                    <span className="eco-card-id" title={key}>{key}</span>
                    <div className="eco-card-actions">
                      <button
                        type="button"
                        className="subtle-button"
                        disabled={mcpTestingKey === key}
                        onClick={() => void handleTestMcp(key, String(m.transport ?? '') === 'http', firstTool)}
                      >
                        {mcpTestingKey === key ? '测试中…' : '试调用'}
                      </button>
                      {owned ? (
                        <>
                          <button type="button" className="subtle-button" onClick={() => setMcpModal({ existing: m })}>
                            编辑
                          </button>
                          <button type="button" className="subtle-button" style={{ color: '#dc2626' }} onClick={() => void handleDeleteMcp(id, name)}>
                            删除
                          </button>
                        </>
                      ) : null}
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        )
      ) : null}

      {/* 添加 / 编辑飞书连接器弹窗 */}
      {feishuModal ? (
        <FeishuConnectorModal
          existing={feishuModal.existing}
          onClose={() => setFeishuModal(null)}
          onSaved={async () => {
            setFeishuModal(null);
            await qc.invalidateQueries({ queryKey: ['workspace-connectors'] });
          }}
        />
      ) : null}

      {/* 添加 / 编辑企业知识库 / MCP 服务弹窗 */}
      {mcpModal ? (
        <McpServerModal
          existing={mcpModal.existing}
          onClose={() => setMcpModal(null)}
          onSaved={async () => {
            setMcpModal(null);
            await qc.invalidateQueries({ queryKey: ['workspace-mcp-servers'] });
          }}
        />
      ) : null}

      {/* AGW 接口目录 / 编辑 / 试调用弹窗 */}
      {agwModal ? (
        <Modal
          title={
            agwModal.kind === 'catalog'
              ? '从 AGW 目录添加可调用 API'
              : agwModal.kind === 'edit'
                ? '编辑企业数据源'
                : `试调用 · ${agwModal.source.name}`
          }
          onClose={() => setAgwModal(null)}
          className="source-modal"
        >
          {agwModal.kind === 'catalog' ? (
            <AgwCatalog onDone={() => setAgwModal(null)} />
          ) : agwModal.kind === 'edit' ? (
            <SourceForm source={agwModal.source} onDone={(s) => setAgwModal(s ? { kind: 'test', source: s } : null)} />
          ) : (
            <SourceTest source={agwModal.source} onBack={() => setAgwModal(null)} />
          )}
        </Modal>
      ) : null}
    </div>
  );
}

function FeishuConnectorModal({
  existing,
  onClose,
  onSaved,
}: {
  existing: Record<string, unknown> | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const isEdit = Boolean(existing);
  const existingCfg = (existing?.config && typeof existing.config === 'object' ? existing.config : {}) as Record<string, unknown>;
  const existingBitableHint =
    existingCfg.appToken && existingCfg.tableId
      ? `https://feishu.cn/base/${String(existingCfg.appToken)}?table=${String(existingCfg.tableId)}`
      : '';
  const [kind, setKind] = useState<'feishu_bot' | 'feishu_bitable'>(
    String(existing?.kind ?? 'feishu_bot') === 'feishu_bitable' ? 'feishu_bitable' : 'feishu_bot',
  );
  const [name, setName] = useState(String(existing?.name ?? ''));
  const [webhookUrl, setWebhookUrl] = useState('');
  const [webhookSecret, setWebhookSecret] = useState('');
  const [bitableUrl, setBitableUrl] = useState(existingBitableHint);
  const [appId, setAppId] = useState(String(existingCfg.appId ?? ''));
  const [appSecret, setAppSecret] = useState('');
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    if (!name.trim()) return toast('请填写连接器名称', 'error');
    if (kind === 'feishu_bot' && !webhookUrl.trim() && !(isEdit && existing?.hasSecrets)) {
      return toast('请填写飞书群机器人 Webhook 地址', 'error');
    }
    if (kind === 'feishu_bitable') {
      if (!bitableUrl.trim()) return toast('请粘贴飞书多维表格链接', 'error');
      if (!appId.trim() && !existingCfg.appId) return toast('请填写飞书自建应用 App ID', 'error');
      if (!appSecret.trim() && !(isEdit && existing?.hasSecrets)) return toast('请填写飞书应用 App Secret', 'error');
    }
    setSaving(true);
    try {
      const payload: Record<string, unknown> = {
        name: name.trim(),
        kind,
        mode: 'live',
        enabled: true,
        ...(kind === 'feishu_bitable'
          ? {
              ...(bitableUrl.trim() ? { bitableUrl: bitableUrl.trim() } : {}),
              config: { ...(appId.trim() ? { appId: appId.trim() } : {}) },
            }
          : {}),
        secrets:
          kind === 'feishu_bot'
            ? {
                ...(webhookUrl.trim() ? { webhookUrl: webhookUrl.trim() } : {}),
                ...(webhookSecret.trim() ? { webhookSecret: webhookSecret.trim() } : {}),
              }
            : {
                ...(appId.trim() ? { appId: appId.trim() } : {}),
                ...(appSecret.trim() ? { appSecret: appSecret.trim() } : {}),
              },
      };
      if (isEdit && existing?.id) {
        await api.connectorUpdate(String(existing.id), payload);
        toast('飞书连接器已更新！');
      } else {
        await api.connectorSave(payload);
        toast('飞书连接器已保存，可在卡片上点击「测试连通」验证！');
      }
      await onSaved();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title={isEdit ? '编辑飞书连接器' : '添加飞书连接器'} onClose={onClose}>
      <div className="eco-form">
        <div className="svc-seg" role="tablist">
          <button type="button" role="tab" aria-selected={kind === 'feishu_bot'} className={kind === 'feishu_bot' ? 'active' : ''} onClick={() => setKind('feishu_bot')}>
            飞书群机器人（发卡片/告警）
          </button>
          <button type="button" role="tab" aria-selected={kind === 'feishu_bitable'} className={kind === 'feishu_bitable' ? 'active' : ''} onClick={() => setKind('feishu_bitable')}>
            飞书多维表格（同步读写）
          </button>
        </div>

        <div className="eco-field">
          <label>连接器名称</label>
          <input
            type="text"
            placeholder={kind === 'feishu_bot' ? '例如：研发产线告警群机器人' : '例如：设备点检台账多维表格'}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        {kind === 'feishu_bot' ? (
          <>
            <div className="eco-field">
              <label>飞书群机器人 Webhook 地址</label>
              <input
                type="text"
                placeholder={
                  isEdit && existing?.hasSecrets
                    ? '已加密保存，留空保持不变，粘贴新 Webhook 可替换'
                    : 'https://open.feishu.cn/open-apis/bot/v2/hook/...'
                }
                value={webhookUrl}
                onChange={(e) => setWebhookUrl(e.target.value)}
              />
              <small>在飞书群右上角「设置 → 群机器人 → 添加自定义机器人」中复制 Webhook 地址；密钥将使用 AES-256 加密托管在当前工作空间。</small>
            </div>
            <div className="eco-field">
              <label>签名校验 Secret（可选）</label>
              <input
                type="password"
                placeholder={isEdit && existing?.hasSecrets ? '留空保持原有签名密钥不变' : '若飞书机器人开启了签名校验，请在此填入密钥'}
                value={webhookSecret}
                onChange={(e) => setWebhookSecret(e.target.value)}
              />
            </div>
          </>
        ) : (
          <>
            <div className="eco-field">
              <label>飞书多维表格链接</label>
              <input
                type="text"
                placeholder="直接粘贴浏览器地址栏链接 https://xxx.feishu.cn/base/...?table=..."
                value={bitableUrl}
                onChange={(e) => setBitableUrl(e.target.value)}
              />
              <small>系统会自动从链接中解析 appToken 与 tableId，无需手动拆分参数。</small>
            </div>
            <div className="eco-grid-2">
              <div className="eco-field eco-field-pair">
                <label>飞书自建应用 App ID</label>
                <input
                  type="text"
                  placeholder="cli_xxxxxxxx"
                  value={appId}
                  onChange={(e) => setAppId(e.target.value)}
                />
              </div>
              <div className="eco-field eco-field-pair">
                <label>飞书应用 App Secret（加密托管）</label>
                <input
                  type="password"
                  placeholder={isEdit && existing?.hasSecrets ? '留空保持原有密钥不变' : '填入 App Secret'}
                  value={appSecret}
                  onChange={(e) => setAppSecret(e.target.value)}
                />
              </div>
            </div>
          </>
        )}

        <div className="eco-form-foot">
          <button type="button" className="subtle-button" onClick={onClose}>取消</button>
          <button type="button" className="primary-button" disabled={saving || !name.trim()} onClick={() => void handleSave()}>
            {saving ? '保存中…' : isEdit ? '保存修改' : '保存连接器'}
          </button>
        </div>
      </div>
    </Modal>
  );
}

function McpServerModal({
  existing,
  onClose,
  onSaved,
}: {
  existing: Record<string, unknown> | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const isEdit = Boolean(existing);
  const existingTools = Array.isArray(existing?.tools)
    ? (existing!.tools as Array<Record<string, unknown>>).map((t) => String(t.name ?? '')).filter(Boolean).join(', ')
    : '';
  const [mode, setMode] = useState<'knowledge' | 'http'>(
    existing ? (Boolean(existing.isKnowledgeBase) ? 'knowledge' : 'http') : 'knowledge',
  );
  const [name, setName] = useState(String(existing?.name ?? ''));
  const [description, setDescription] = useState(String(existing?.description ?? ''));
  const [knowledgeContent, setKnowledgeContent] = useState(String(existing?.knowledgeContent ?? ''));
  const [serverKey, setServerKey] = useState(String(existing?.serverKey ?? ''));
  const [endpointUrl, setEndpointUrl] = useState(String(existing?.endpointUrl ?? ''));
  const [secret, setSecret] = useState('');
  const [toolNames, setToolNames] = useState(existingTools);
  const [discovering, setDiscovering] = useState(false);
  const [saving, setSaving] = useState(false);

  const handleDiscover = async () => {
    if (!endpointUrl.trim()) return toast('请先填入 HTTP MCP 地址（如 https://mcp.context7.com/mcp）', 'error');
    setDiscovering(true);
    try {
      const r = await api.mcpServerDiscover({
        endpointUrl: endpointUrl.trim(),
        ...(secret.trim() ? { secret: secret.trim() } : {}),
      });
      const names = (r.tools ?? []).map((t) => String(t.name ?? '')).filter(Boolean);
      if (names.length > 0) {
        setToolNames(names.join(', '));
        toast(`自动发现 ${names.length} 个真实 MCP 工具！`);
      } else {
        toast('连接成功，但该 MCP 未返回工具列表，可手动填写工具名。');
      }
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setDiscovering(false);
    }
  };

  const handleSave = async () => {
    if (!name.trim()) return toast('请填写名称', 'error');
    if (mode === 'knowledge' && !knowledgeContent.trim()) return toast('请粘贴知识库文档或规范内容', 'error');
    if (mode === 'http' && !endpointUrl.trim()) return toast('请填写 HTTP MCP 服务地址', 'error');
    setSaving(true);
    try {
      const payload =
        mode === 'knowledge'
          ? {
              mode: 'knowledge',
              name: name.trim(),
              description: description.trim() || '团队私有知识库（支持 AI 编码时按关键词实时检索）',
              knowledgeContent: knowledgeContent.trim(),
            }
          : {
              mode: 'http',
              name: name.trim(),
              description: description.trim(),
              ...(!isEdit && serverKey.trim() ? { serverKey: serverKey.trim() } : {}),
              endpointUrl: endpointUrl.trim(),
              ...(secret.trim() ? { secret: secret.trim() } : {}),
              ...(toolNames.trim() ? { toolNames: toolNames.trim() } : {}),
            };
      if (isEdit && existing?.id) {
        await api.mcpServerUpdate(String(existing.id), payload);
        toast('已保存修改！');
      } else {
        await api.mcpServerCreate(payload);
        toast(mode === 'knowledge' ? '团队知识库已创建，现在就可以在对话栏「生态」中勾选使用！' : 'MCP 服务已添加，点击「试调用」即可验证连通性。');
      }
      await onSaved();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title={isEdit ? '编辑知识库 / MCP 服务' : '添加知识库 / MCP 服务'} onClose={onClose}>
      <div className="eco-form">
        {!isEdit ? (
          <div className="svc-seg" role="tablist">
            <button type="button" role="tab" aria-selected={mode === 'knowledge'} className={mode === 'knowledge' ? 'active' : ''} onClick={() => setMode('knowledge')}>
              团队知识库（粘贴规范 / 文档）
            </button>
            <button type="button" role="tab" aria-selected={mode === 'http'} className={mode === 'http' ? 'active' : ''} onClick={() => setMode('http')}>
              远端 HTTP MCP 服务
            </button>
          </div>
        ) : null}

        <div className="eco-field">
          <label>名称</label>
          <input
            type="text"
            placeholder={mode === 'knowledge' ? '例如：产线良率指标口径与 8D 审批规范' : '例如：Context7 官方文档 MCP / DeepWiki MCP'}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>

        <div className="eco-field">
          <label>一句话说明（可选，帮助 AI 判断何时查阅）</label>
          <input
            type="text"
            placeholder={mode === 'knowledge' ? '例如：包含良率公式、告警阈值、审批角色与字段命名规范' : '例如：实时检索最新开源库文档与 GitHub 仓库源码解析'}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        </div>

        {mode === 'knowledge' ? (
          <div className="eco-field">
            <label>知识库内容（支持 Markdown / 纯文本，按空行分段检索）</label>
            <textarea
              rows={7}
              placeholder="直接粘贴业务规则、接口字段字典、审批流转规则或设计规范，保存后会自动生成 search_knowledge 检索工具供 AI 编码时实时调用…"
              value={knowledgeContent}
              onChange={(e) => setKnowledgeContent(e.target.value)}
            />
          </div>
        ) : (
          <>
            <div className="eco-field">
              <label>MCP Endpoint 地址（支持标准 Streamable HTTP / JSON-RPC 2.0）</label>
              <div style={{ display: 'flex', gap: 8 }}>
                <input
                  type="text"
                  style={{ flex: 1 }}
                  placeholder="https://mcp.context7.com/mcp 或 https://mcp.deepwiki.com/mcp"
                  value={endpointUrl}
                  onChange={(e) => setEndpointUrl(e.target.value)}
                />
                <button type="button" className="subtle-button" disabled={discovering || !endpointUrl.trim()} onClick={() => void handleDiscover()}>
                  {discovering ? '探测中…' : '自动拉取工具'}
                </button>
              </div>
              <small>支持公开或内网 Streamable HTTP MCP（自动兼容 application/json 与 text/event-stream），点「自动拉取工具」可直连发现全部工具。</small>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
              <div className="eco-field">
                <label>服务标识（{isEdit ? '创建后不可改' : '可选，自动生成'}）</label>
                <input
                  type="text"
                  disabled={isEdit}
                  placeholder="如 context7-mcp"
                  value={serverKey}
                  onChange={(e) => setServerKey(e.target.value)}
                />
              </div>
              <div className="eco-field">
                <label>鉴权 Token（可选，加密保存）</label>
                <input
                  type="password"
                  placeholder={isEdit && existing?.hasSecret ? '留空保持原有密钥不变' : 'Bearer xxx（公开 MCP 留空）'}
                  value={secret}
                  onChange={(e) => setSecret(e.target.value)}
                />
              </div>
            </div>
            <div className="eco-field">
              <label>暴露工具名（逗号分隔；留空时保存会自动向远端拉取）</label>
              <input
                type="text"
                placeholder="例如：resolve-library-id, query-docs（留空自动探测）"
                value={toolNames}
                onChange={(e) => setToolNames(e.target.value)}
              />
            </div>
          </>
        )}

        <div className="eco-form-foot">
          <button type="button" className="subtle-button" onClick={onClose}>取消</button>
          <button
            type="button"
            className="primary-button"
            disabled={saving || !name.trim() || (mode === 'knowledge' ? !knowledgeContent.trim() : !endpointUrl.trim())}
            onClick={() => void handleSave()}
          >
            {saving ? '保存中…' : isEdit ? '保存修改' : mode === 'knowledge' ? '保存知识库' : '保存 MCP 服务'}
          </button>
        </div>
      </div>
    </Modal>
  );
}
