import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { Icon } from '../lib/icons';
import { toast, useUi } from '../lib/store';
import { Modal } from './Modal';
import { useSources } from './DataSourceDialog';
import type { DataSourceInfo } from '../api/types';

export interface EcosystemItem {
  id: string;
  category: 'feishu_bot' | 'feishu_bitable' | 'mcp';
  name: string;
  subtitle?: string;
}

/** 将用户勾选的生态能力自动组装成结构化要求附加到 prompt，用户无需手动复制粘贴指令或记 connectorId。 */
export function appendEcosystemContext(prompt: string, items: EcosystemItem[]): string {
  if (!items.length) return prompt;
  const lines = items.map((it) => {
    if (it.category === 'feishu_bitable') {
      return `- 飞书多维表格「${it.name}」（connectorId: \`${it.id}\`）：实现读取与写入同步（云函数用 ctx.bitable，全栈后端用 ZHIYE_CONNECTOR_API/bitable）`;
    }
    if (it.category === 'mcp') {
      return `- 企业知识与工具 MCP「${it.name}」（serverKey: \`${it.id}\`）：开发时按需调用 \`mcp_call\` 获取真实规范或数据`;
    }
    return `- 飞书机器人「${it.name}」（connectorId: \`${it.id}\`）：在关键提交/告警/审批节点推送飞书交互卡片（云函数用 ctx.feishu.sendCard，全栈后端用 ZHIYE_CONNECTOR_API/feishu/send-card）`;
  });
  return `${prompt.trim()}\n\n【已选企业生态能力（免密直连）】\n${lines.join('\n')}`;
}

export function EcosystemChips({
  items,
  onRemove,
}: {
  items: EcosystemItem[];
  onRemove: (id: string) => void;
}) {
  if (!items.length) return null;
  return (
    <>
      {items.map((it) => (
        <span
          key={`${it.category}:${it.id}`}
          style={{
            background: it.category === 'mcp' ? '#eef2ff' : '#ecfdf5',
            borderColor: it.category === 'mcp' ? '#c7d2fe' : '#a7f3d0',
            color: it.category === 'mcp' ? '#4338ca' : '#047857',
          }}
        >
          <Icon name={it.category === 'feishu_bitable' ? 'database' : it.category === 'mcp' ? 'spark' : 'globe'} />
          {it.name}
          <button
            type="button"
            aria-label={`移除生态能力 ${it.name}`}
            onClick={() => onRemove(it.id)}
          >
            <Icon name="close" />
          </button>
        </span>
      ))}
    </>
  );
}

/**
 * 统一「生态」选择器（首页创造页与工作台对话栏平级入口）：
 * - 飞书群机器人 & 多维表格：一键勾选即绑定 + 普通用户自助添加自己的 Webhook/多维表格链接 + 在线测试连通
 * - 企业知识与工具 (MCP)：可视化浏览已授权企业 MCP 及工具列表，一键勾选接入
 * - AGW 企业数据源：一键勾选并快捷跳转 AGW 接口目录
 */
export function EcosystemPickerModal({
  open,
  onClose,
  selected,
  onToggle,
  pickedSourceIds,
  onPickSource,
}: {
  open: boolean;
  onClose: () => void;
  selected: EcosystemItem[];
  onToggle: (item: EcosystemItem) => void;
  pickedSourceIds?: string[];
  onPickSource?: (source: DataSourceInfo) => void;
}) {
  const qc = useQueryClient();
  const setSourcesOpen = useUi((s) => s.setSourcesOpen);
  const [tab, setTab] = useState<'feishu' | 'mcp' | 'agw'>('feishu');
  const [showAdd, setShowAdd] = useState(false);

  // 普通用户自助添加飞书连接器表单
  const [newName, setNewName] = useState('');
  const [newKind, setNewKind] = useState<'feishu_bot' | 'feishu_bitable'>('feishu_bot');
  const [webhookUrl, setWebhookUrl] = useState('');
  const [webhookSecret, setWebhookSecret] = useState('');
  const [bitableUrl, setBitableUrl] = useState('');
  const [saving, setSaving] = useState(false);

  const [testingId, setTestingId] = useState<string | null>(null);
  const [testResults, setTestResults] = useState<Record<string, { ok: boolean; text: string }>>({});

  const connQ = useQuery({
    queryKey: ['workspace-connectors'],
    queryFn: api.connectorsList,
    enabled: open,
    staleTime: 15_000,
  });
  const mcpQ = useQuery({
    queryKey: ['workspace-mcp-servers'],
    queryFn: api.mcpServersList,
    enabled: open,
    staleTime: 30_000,
  });
  const sourcesQ = useSources(open);

  if (!open) return null;

  const connectors = connQ.data ?? [];
  const mcpServers = mcpQ.data ?? [];
  const sources = sourcesQ.data ?? [];
  const selectedIds = new Set(selected.map((x) => x.id));
  const pickedSrcSet = new Set(pickedSourceIds ?? []);

  const handleTest = async (id: string) => {
    setTestingId(id);
    try {
      const r = await api.connectorTest(id);
      const text = `自检通过（mode=${String(r.mode ?? 'ok')}）`;
      setTestResults((prev) => ({ ...prev, [id]: { ok: true, text } }));
      toast(`连接器${text}。`);
    } catch (e) {
      const msg = errorMessage(e);
      setTestResults((prev) => ({ ...prev, [id]: { ok: false, text: msg } }));
      toast(msg, 'error');
    } finally {
      setTestingId(null);
    }
  };

  const handleCreateOwn = async () => {
    if (!newName.trim()) return toast('请填写连接器名称（如：产线告警群机器人）。', 'error');
    setSaving(true);
    try {
      const isLive = newKind === 'feishu_bot' ? !!webhookUrl.trim() : !!bitableUrl.trim();
      const saved = await api.connectorSave({
        name: newName.trim(),
        kind: newKind,
        mode: isLive ? 'live' : 'sandbox',
        enabled: true,
        ...(newKind === 'feishu_bitable' && bitableUrl.trim() ? { bitableUrl: bitableUrl.trim() } : {}),
        secrets:
          newKind === 'feishu_bot' && webhookUrl.trim()
            ? { webhookUrl: webhookUrl.trim(), ...(webhookSecret.trim() ? { webhookSecret: webhookSecret.trim() } : {}) }
            : {},
      });
      await qc.invalidateQueries({ queryKey: ['workspace-connectors'] });
      const savedId = String(saved.id ?? '');
      if (savedId && !selectedIds.has(savedId)) {
        onToggle({ id: savedId, category: newKind, name: String(saved.name ?? newName.trim()) });
      }
      setNewName('');
      setWebhookUrl('');
      setWebhookSecret('');
      setBitableUrl('');
      setShowAdd(false);
      toast('已保存并为你自动勾选，可直接点「测试连通」验证！');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteOwn = async (id: string, name: string) => {
    try {
      await api.connectorDelete(id);
      if (selectedIds.has(id)) {
        const cur = selected.find((x) => x.id === id);
        if (cur) onToggle(cur);
      }
      await qc.invalidateQueries({ queryKey: ['workspace-connectors'] });
      toast(`已移除「${name}」。`);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <Modal title="接入企业生态能力（勾选即用，免配密钥）" onClose={onClose} className="source-modal">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, marginBottom: 12, flexWrap: 'wrap' }}>
        <div className="svc-seg" role="tablist">
          <button type="button" role="tab" aria-selected={tab === 'feishu'} className={tab === 'feishu' ? 'active' : ''} onClick={() => setTab('feishu')}>
            飞书与多维表格 ({connectors.length})
          </button>
          <button type="button" role="tab" aria-selected={tab === 'mcp'} className={tab === 'mcp' ? 'active' : ''} onClick={() => setTab('mcp')}>
            企业知识 / MCP ({mcpServers.length})
          </button>
          <button type="button" role="tab" aria-selected={tab === 'agw'} className={tab === 'agw' ? 'active' : ''} onClick={() => setTab('agw')}>
            AGW 接口 ({sources.length})
          </button>
        </div>
        {tab === 'feishu' ? (
          <button type="button" className="subtle-button" style={{ height: 32, fontSize: 12.5 }} onClick={() => setShowAdd((v) => !v)}>
            <Icon name={showAdd ? 'close' : 'plus'} /> {showAdd ? '收起添加' : '添加我的飞书'}
          </button>
        ) : null}
      </div>

      {tab === 'feishu' ? (
        <div className="source-list">
          {showAdd ? (
            <div style={{ padding: 14, borderRadius: 12, border: '1px solid #ddd6fe', background: '#faf8ff', marginBottom: 12, display: 'flex', flexDirection: 'column', gap: 10 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <b style={{ fontSize: 13 }}>自助接入我的飞书群机器人 / 多维表格（无需找管理员）</b>
                <small style={{ color: '#6b7280', fontSize: 11.5 }}>密钥 AES-256 加密保存在当前工作空间</small>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.2fr', gap: 8 }}>
                <select
                  value={newKind}
                  onChange={(e) => setNewKind(e.target.value as 'feishu_bot' | 'feishu_bitable')}
                  style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid #d8d4e8', background: '#fff', fontSize: 12.5 }}
                >
                  <option value="feishu_bot">飞书群自定义机器人（发卡片/告警）</option>
                  <option value="feishu_bitable">飞书多维表格（同步读写数据）</option>
                </select>
                <input
                  type="text"
                  placeholder="起个好记的名字（如：研发告警群机器人）"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid #d8d4e8', background: '#fff', fontSize: 12.5 }}
                />
              </div>
              {newKind === 'feishu_bot' ? (
                <div style={{ display: 'grid', gridTemplateColumns: '1.7fr 1fr', gap: 8 }}>
                  <input
                    type="text"
                    placeholder="粘贴飞书群机器人 Webhook（https://open.feishu.cn/open-apis/bot/v2/hook/...，留空则先建沙箱演示）"
                    value={webhookUrl}
                    onChange={(e) => setWebhookUrl(e.target.value)}
                    style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid #d8d4e8', background: '#fff', fontSize: 12 }}
                  />
                  <input
                    type="password"
                    placeholder="签名校验 Secret（可选）"
                    value={webhookSecret}
                    onChange={(e) => setWebhookSecret(e.target.value)}
                    style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid #d8d4e8', background: '#fff', fontSize: 12 }}
                  />
                </div>
              ) : (
                <input
                  type="text"
                  placeholder="直接粘贴飞书多维表格浏览器链接（https://xxx.feishu.cn/base/... ?table=...，留空则建沙箱演示表）"
                  value={bitableUrl}
                  onChange={(e) => setBitableUrl(e.target.value)}
                  style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid #d8d4e8', background: '#fff', fontSize: 12 }}
                />
              )}
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <small style={{ color: '#6b7280', fontSize: 11.5 }}>
                  {newKind === 'feishu_bot'
                    ? '获取方式：飞书群右上角「设置 → 群机器人 → 添加自定义机器人」复制 Webhook 即可。'
                    : '自动从链接提取 appToken 与 tableId，无需手动拆参数。'}
                </small>
                <button type="button" className="primary-button" style={{ height: 32, padding: '0 14px', fontSize: 12.5 }} disabled={saving || !newName.trim()} onClick={() => void handleCreateOwn()}>
                  {saving ? '保存中…' : '保存并勾选'}
                </button>
              </div>
            </div>
          ) : null}

          <p className="source-intro">
            勾选后直接挂在输入框上方，发送需求时 <b>AI 会自动把对应飞书卡片推送或多维表格读写接入应用</b>，无需复制指令或手写密钥。
          </p>

          {connQ.isLoading ? <p className="muted-note">正在读取飞书连接器…</p> : null}
          <ul>
            {connectors.map((c) => {
              const id = String(c.id ?? '');
              const name = String(c.name ?? id);
              const kind = String(c.kind ?? 'feishu_bot') === 'feishu_bitable' ? 'feishu_bitable' : 'feishu_bot';
              const mode = String(c.mode ?? 'sandbox');
              const owned = Boolean(c.owned);
              const picked = selectedIds.has(id);
              const tr = testResults[id];
              return (
                <li key={id} className="source-row" style={picked ? { borderColor: '#7657d9', background: '#f8f5ff' } : undefined}>
                  <span className="source-row-icon">
                    <Icon name={kind === 'feishu_bitable' ? 'database' : 'globe'} />
                  </span>
                  <span className="source-row-main">
                    <b>
                      {name}
                      <em className="source-badge">{kind === 'feishu_bitable' ? '多维表格' : '飞书机器人'}</em>
                      <em className="source-badge">{mode === 'live' ? '公域直连' : '沙箱可测'}</em>
                      {owned ? <em className="source-badge" style={{ background: '#ede9fe', color: '#5b21b6' }}>我添加的</em> : null}
                    </b>
                    <small>
                      <code>{id}</code> · {c.hasSecrets ? '密钥已加密托管' : '沙箱免密即测即用'}
                    </small>
                    {tr ? (
                      <small style={{ display: 'block', marginTop: 3, color: tr.ok ? '#059669' : '#dc2626', fontWeight: 500 }}>
                        {tr.ok ? '✓ ' : '✗ '}{tr.text}
                      </small>
                    ) : null}
                  </span>
                  <span className="source-row-actions">
                    <button type="button" className="subtle-button" disabled={testingId === id} onClick={() => void handleTest(id)}>
                      {testingId === id ? '测试中…' : '测试连通'}
                    </button>
                    {owned ? (
                      <button type="button" className="subtle-button" style={{ color: '#dc2626' }} onClick={() => void handleDeleteOwn(id, name)}>
                        删除
                      </button>
                    ) : null}
                    <button
                      type="button"
                      className={picked ? 'subtle-button' : 'primary-button'}
                      style={{ height: 30, padding: '0 12px', fontSize: 12 }}
                      onClick={() => onToggle({ id, category: kind, name })}
                    >
                      {picked ? '✓ 已勾选' : '勾选使用'}
                    </button>
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      ) : tab === 'mcp' ? (
        <div className="source-list">
          <p className="source-intro">
            勾选企业 MCP 后，AI 编码智能体会在开发时按需调用对应的内部知识库与工具（凭据由平台代理，绝不泄露到代码里）。
          </p>
          {mcpQ.isLoading ? <p className="muted-note">正在读取已授权企业 MCP…</p> : null}
          {!mcpQ.isLoading && mcpServers.length === 0 ? (
            <div className="source-empty">
              <Icon name="spark" />
              <b>暂未启用企业 MCP 服务</b>
              <p>管理员可在「管理 → 配额与运行 → 企业 MCP 注册中心」登记公司内部知识库、文档或接口 MCP。</p>
            </div>
          ) : null}
          <ul>
            {mcpServers.map((s) => {
              const key = String(s.serverKey ?? s.id ?? '');
              const name = String(s.name ?? key);
              const desc = String(s.description ?? '');
              const tools = Array.isArray(s.tools) ? (s.tools as Array<Record<string, unknown>>) : [];
              const picked = selectedIds.has(key);
              return (
                <li key={key} className="source-row" style={picked ? { borderColor: '#7657d9', background: '#f8f5ff' } : undefined}>
                  <span className="source-row-icon">
                    <Icon name="spark" />
                  </span>
                  <span className="source-row-main">
                    <b>
                      {name}
                      <em className="source-badge">{key}</em>
                      {tools.length > 0 ? <em className="source-badge">{tools.length} 个工具</em> : null}
                    </b>
                    <small>{desc || '企业内部受控 MCP 工具服务'}</small>
                  </span>
                  <span className="source-row-actions">
                    <button
                      type="button"
                      className={picked ? 'subtle-button' : 'primary-button'}
                      style={{ height: 30, padding: '0 12px', fontSize: 12 }}
                      onClick={() => onToggle({ id: key, category: 'mcp', name, subtitle: desc })}
                    >
                      {picked ? '✓ 已勾选' : '勾选使用'}
                    </button>
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      ) : (
        <div className="source-list">
          <p className="source-intro">
            选择当前账号有权调用的 AGW 企业业务接口（MES / 设备 / 经营数据），应用生成后会自动通过平台代理安全调用。
          </p>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
            <button
              type="button"
              className="subtle-button"
              style={{ height: 30, fontSize: 12 }}
              onClick={() => {
                onClose();
                setSourcesOpen(true);
              }}
            >
              <Icon name="plus" /> 去 AGW 目录添加 / 管理接口
            </button>
          </div>
          {sourcesQ.isLoading ? <p className="muted-note">正在读取 AGW 数据源…</p> : null}
          {!sourcesQ.isLoading && sources.length === 0 ? (
            <div className="source-empty">
              <Icon name="database" />
              <b>还没有添加 AGW 数据源</b>
              <p>点右上角「去 AGW 目录添加 / 管理接口」挑选当前账号有权限的接口。</p>
            </div>
          ) : null}
          <ul>
            {sources.map((s) => {
              const picked = pickedSrcSet.has(s.id);
              return (
                <li key={s.id} className="source-row" style={picked ? { borderColor: '#7657d9', background: '#f8f5ff' } : undefined}>
                  <span className="source-row-icon">
                    <Icon name="database" />
                  </span>
                  <span className="source-row-main">
                    <b>
                      {s.name}
                      <em className="source-badge">{s.provider || 'API'}</em>
                    </b>
                    <small>{s.description || s.url || '企业受控数据源'}</small>
                  </span>
                  <span className="source-row-actions">
                    {onPickSource ? (
                      <button
                        type="button"
                        className={picked ? 'subtle-button' : 'primary-button'}
                        style={{ height: 30, padding: '0 12px', fontSize: 12 }}
                        disabled={picked}
                        onClick={() => onPickSource(s)}
                      >
                        {picked ? '✓ 已绑定' : '绑定使用'}
                      </button>
                    ) : null}
                  </span>
                </li>
              );
            })}
          </ul>
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 14, paddingTop: 10, borderTop: '1px solid #eeeaf6' }}>
        <small style={{ color: '#6b7280', fontSize: 12 }}>
          已勾选 <b>{selected.length}</b> 项生态能力{pickedSrcSet.size ? ` · 已绑定 ${pickedSrcSet.size} 个 AGW 数据源` : ''}
        </small>
        <button type="button" className="primary-button" style={{ height: 32, padding: '0 16px', fontSize: 12.5 }} onClick={onClose}>
          完成
        </button>
      </div>
    </Modal>
  );
}
