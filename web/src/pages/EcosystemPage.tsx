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
  const [addFeishuOpen, setAddFeishuOpen] = useState(false);
  const [agwModal, setAgwModal] = useState<AgwModal>(null);

  const [testingId, setTestingId] = useState<string | null>(null);
  const [testResults, setTestResults] = useState<Record<string, { ok: boolean; text: string }>>({});

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

  return (
    <div className="content-page eco-page">
      <div className="page-heading">
        <div>
          <h1>生态管理</h1>
          <p>统一维护飞书机器人、多维表格、AGW 企业接口与知识库 MCP；配好后在「创造」和工作台聊天栏直接勾选即用。</p>
        </div>
        {tab === 'feishu' ? (
          <button type="button" className="primary-button" onClick={() => setAddFeishuOpen(true)}>
            <Icon name="plus" />
            添加飞书连接器
          </button>
        ) : tab === 'agw' ? (
          <button type="button" className="primary-button" onClick={() => setAgwModal({ kind: 'catalog' })}>
            <Icon name="plus" />
            从 AGW 目录添加接口
          </button>
        ) : null}
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
            <button type="button" className="primary-button" onClick={() => setAddFeishuOpen(true)}>
              <Icon name="plus" /> 添加飞书连接器
            </button>
          </div>
        ) : (
          <div className="eco-grid">
            {connectors.map((c) => {
              const id = String(c.id ?? '');
              const name = String(c.name ?? id);
              const isBitable = String(c.kind ?? '') === 'feishu_bitable';
              const mode = String(c.mode ?? 'sandbox');
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
                        <span className={`eco-pill ${mode === 'live' ? 'is-live' : 'is-sandbox'}`}>
                          {mode === 'live' ? '公域直连' : '沙箱可测'}
                        </span>
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
                        <button type="button" className="subtle-button" style={{ color: '#dc2626' }} onClick={() => void handleDeleteConnector(id, name)}>
                          删除
                        </button>
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
            <h3>{query ? '没有匹配的企业 MCP 服务' : '暂未启用企业 MCP 服务'}</h3>
            <p>平台管理员可在「管理 → 配额与运行 → 企业 MCP 注册中心」登记公司内部知识库、规范与工具 MCP，启用后所有成员可在此浏览并在对话栏勾选使用。</p>
          </div>
        ) : (
          <div className="eco-grid">
            {mcpServers.map((m) => {
              const key = String(m.serverKey ?? m.id ?? '');
              const name = String(m.name ?? key);
              const desc = String(m.description ?? '');
              const tools = Array.isArray(m.tools) ? (m.tools as Array<Record<string, unknown>>) : [];
              return (
                <article key={key} className="eco-card">
                  <div className="eco-card-head">
                    <span className="eco-card-icon">
                      <Icon name="spark" />
                    </span>
                    <div className="eco-card-title">
                      <h3>{name}</h3>
                      <div className="eco-badges">
                        <span className="eco-pill is-owned">{key}</span>
                        <span className="eco-pill is-live">已启用</span>
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
                  <div className="eco-card-foot">
                    <span className="eco-card-id">编码时可在对话栏「生态」直接勾选调用</span>
                    <span className="eco-pill">平台安全代理</span>
                  </div>
                </article>
              );
            })}
          </div>
        )
      ) : null}

      {/* 添加飞书连接器弹窗（宽敞清晰、独立维护） */}
      {addFeishuOpen ? (
        <AddFeishuConnectorModal
          onClose={() => setAddFeishuOpen(false)}
          onSaved={async () => {
            setAddFeishuOpen(false);
            await qc.invalidateQueries({ queryKey: ['workspace-connectors'] });
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

function AddFeishuConnectorModal({ onClose, onSaved }: { onClose: () => void; onSaved: () => Promise<void> }) {
  const [kind, setKind] = useState<'feishu_bot' | 'feishu_bitable'>('feishu_bot');
  const [name, setName] = useState('');
  const [webhookUrl, setWebhookUrl] = useState('');
  const [webhookSecret, setWebhookSecret] = useState('');
  const [bitableUrl, setBitableUrl] = useState('');
  const [saving, setSaving] = useState(false);

  const handleSave = async () => {
    if (!name.trim()) return toast('请填写连接器名称', 'error');
    setSaving(true);
    try {
      const isLive = kind === 'feishu_bot' ? !!webhookUrl.trim() : !!bitableUrl.trim();
      await api.connectorSave({
        name: name.trim(),
        kind,
        mode: isLive ? 'live' : 'sandbox',
        enabled: true,
        ...(kind === 'feishu_bitable' && bitableUrl.trim() ? { bitableUrl: bitableUrl.trim() } : {}),
        secrets:
          kind === 'feishu_bot' && webhookUrl.trim()
            ? { webhookUrl: webhookUrl.trim(), ...(webhookSecret.trim() ? { webhookSecret: webhookSecret.trim() } : {}) }
            : {},
      });
      toast('飞书连接器已保存，可在卡片上点击「测试连通」验证！');
      await onSaved();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal title="添加飞书连接器" onClose={onClose}>
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
                placeholder="https://open.feishu.cn/open-apis/bot/v2/hook/...（留空则先建沙箱演示）"
                value={webhookUrl}
                onChange={(e) => setWebhookUrl(e.target.value)}
              />
              <small>在飞书群右上角「设置 → 群机器人 → 添加自定义机器人」中复制 Webhook 地址；密钥将使用 AES-256 加密托管在当前工作空间。</small>
            </div>
            <div className="eco-field">
              <label>签名校验 Secret（可选）</label>
              <input
                type="password"
                placeholder="若飞书机器人开启了签名校验，请在此填入密钥"
                value={webhookSecret}
                onChange={(e) => setWebhookSecret(e.target.value)}
              />
            </div>
          </>
        ) : (
          <div className="eco-field">
            <label>飞书多维表格链接</label>
            <input
              type="text"
              placeholder="直接粘贴浏览器地址栏链接 https://xxx.feishu.cn/base/... ?table=...（留空建沙箱表）"
              value={bitableUrl}
              onChange={(e) => setBitableUrl(e.target.value)}
            />
            <small>系统会自动从链接中解析 appToken 与 tableId，无需手动拆分参数。</small>
          </div>
        )}

        <div className="eco-form-foot">
          <button type="button" className="subtle-button" onClick={onClose}>取消</button>
          <button type="button" className="primary-button" disabled={saving || !name.trim()} onClick={() => void handleSave()}>
            {saving ? '保存中…' : '保存连接器'}
          </button>
        </div>
      </div>
    </Modal>
  );
}
