import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { copyText, fmtTime } from '../lib/format';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { Modal } from './Modal';
import { confirmDialog } from './ConfirmDialog';

/**
 * 平台级「公域飞书控制面 & 企业连接器中心」面板（嵌入 /admin?tab=ops）：
 * - 连接器中心：统一托管公域飞书群机器人、飞书自建应用、飞书多维表格（Bitable）连接器（live 直连 open.feishu.cn / sandbox 本地验证）
 * - 飞书控制面：查看事件订阅与卡片回调地址，支持本地模拟 @织页 指令并直接点击交互卡片「批准上线 / 驳回」
 */
export function AdminFeishuConnectorPanel() {
  const qc = useQueryClient();
  const [tab, setTab] = useState<'connectors' | 'control-plane'>('connectors');
  const [addOpen, setAddOpen] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  // 新建/编辑连接器表单
  const [name, setName] = useState('');
  const [kind, setKind] = useState<'feishu_bot' | 'feishu_bitable' | 'feishu_app'>('feishu_bot');
  const [appIdField, setAppIdField] = useState('');
  const [appSecret, setAppSecret] = useState('');
  const [webhookUrl, setWebhookUrl] = useState('');
  const [webhookSignSecret, setWebhookSignSecret] = useState('');
  const [bitableAppToken, setBitableAppToken] = useState('');
  const [bitableTableId, setBitableTableId] = useState('');
  const [defaultChatId, setDefaultChatId] = useState('');
  const [saving, setSaving] = useState(false);

  // 公域飞书控制面模拟器
  const [simCommand, setSimCommand] = useState('@织页 状态');
  const [simBusy, setSimBusy] = useState(false);

  const connQ = useQuery({ queryKey: ['admin-connectors'], queryFn: api.adminConnectorsList, staleTime: 15_000 });
  const auditQ = useQuery({ queryKey: ['admin-connector-audits'], queryFn: () => api.adminConnectorAudits(undefined, 15), staleTime: 15_000 });
  const sessQ = useQuery({ queryKey: ['admin-feishu-sessions'], queryFn: () => api.adminFeishuSessions(15), enabled: tab === 'control-plane', staleTime: 10_000 });

  const connectors = connQ.data ?? [];
  const audits = auditQ.data ?? [];
  const sessions = sessQ.data ?? [];

  const refreshAll = () =>
    Promise.allSettled([
      qc.invalidateQueries({ queryKey: ['admin-connectors'] }),
      qc.invalidateQueries({ queryKey: ['admin-connector-audits'] }),
      qc.invalidateQueries({ queryKey: ['admin-feishu-sessions'] }),
    ]);

  const handleCreate = async () => {
    setSaving(true);
    try {
      await api.adminConnectorSave({
        name: name.trim(),
        kind,
        mode: 'live',
        platformWide: true,
        enabled: true,
        config: {
          apiBase: 'https://open.feishu.cn',
          appId: appIdField.trim() || undefined,
          defaultChatId: defaultChatId.trim() || undefined,
          bitableAppToken: bitableAppToken.trim() || undefined,
          bitableTableId: bitableTableId.trim() || undefined,
        },
        secrets: {
          appSecret: appSecret.trim() || undefined,
          webhookUrl: webhookUrl.trim() || undefined,
          webhookSignSecret: webhookSignSecret.trim() || undefined,
        },
      });
      toast('企业连接器已加密保存。');
      setAddOpen(false);
      setName('');
      setAppIdField('');
      setAppSecret('');
      setWebhookUrl('');
      setWebhookSignSecret('');
      await refreshAll();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async (id: string) => {
    setBusyId(id);
    try {
      const res = await api.adminConnectorTest(id);
      toast(`连接器自检通过（mode=${String(res.mode ?? 'ok')}）。`);
      await refreshAll();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusyId(null);
    }
  };

  const handleSimulate = async () => {
    if (!simCommand.trim() || simBusy) return;
    setSimBusy(true);
    try {
      const res = await api.adminFeishuSimulate({ text: simCommand.trim(), userName: '管理员模拟' });
      toast(`飞书控制面已响应（意图：${String(res.intent ?? 'ok')}）。`);
      await refreshAll();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setSimBusy(false);
    }
  };

  const handleCardAction = async (sessionId: string, action: 'approve_publish' | 'reject') => {
    setBusyId(`${sessionId}:${action}`);
    try {
      await api.adminFeishuCardAction({
        operator: '管理员飞书审批',
        action: { value: { action, sessionId } },
      });
      toast(action === 'approve_publish' ? '飞书审批通过，应用已正式上线！' : '已驳回本次发布审批。');
      await refreshAll();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusyId(null);
    }
  };

  const origin = typeof window !== 'undefined' ? window.location.origin : '';
  const eventsUrl = `${origin}/api/webhooks/feishu/events`;
  const cardUrl = `${origin}/api/webhooks/feishu/card-action`;

  return (
    <div className="dash-panel" style={{ marginTop: 18 }}>
      <div className="dash-panel-head" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <div>
          <h3 style={{ margin: 0, fontSize: 15, display: 'flex', alignItems: 'center', gap: 8 }}>
            <Icon name="globe" />
            公域飞书控制面 &amp; 企业连接器中心（open.feishu.cn）
          </h3>
          <p style={{ margin: '4px 0 0', fontSize: 12.5, color: 'var(--muted)' }}>
            统一托管飞书机器人 / 多维表格凭据（AES-256-GCM 加密 + SSRF 白名单），支持云函数 <code>ctx.feishu</code> / <code>ctx.bitable</code> 与群聊 <code>@织页</code> 卡片审批上线。
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <div className="svc-seg" role="tablist">
            <button type="button" className={tab === 'connectors' ? 'active' : ''} onClick={() => setTab('connectors')}>
              企业连接器 ({connectors.length})
            </button>
            <button type="button" className={tab === 'control-plane' ? 'active' : ''} onClick={() => setTab('control-plane')}>
              公域飞书控制面 ({sessions.length})
            </button>
          </div>
          {tab === 'connectors' ? (
            <button type="button" className="primary-button dash-compact-btn" onClick={() => setAddOpen(true)}>
              <Icon name="plus" /> 新建连接器
            </button>
          ) : null}
        </div>
      </div>

      {tab === 'connectors' ? (
        <div style={{ marginTop: 12 }}>
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th>名称 / ID</th>
                  <th>类型</th>
                  <th>运行模式</th>
                  <th>凭据与限流</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {connectors.map((c) => {
                  const id = String(c.id ?? '');
                  return (
                    <tr key={id}>
                      <td>
                        <b>{String(c.name ?? '')}</b>
                        <div style={{ fontSize: 11.5, color: 'var(--muted)', fontFamily: 'var(--font-mono, monospace)' }}>{id}</div>
                      </td>
                      <td>
                        <span className="svc-chip">{String(c.kind ?? '')}</span>
                      </td>
                      <td>
                        <span className="svc-chip is-accent">Live · open.feishu.cn</span>
                      </td>
                      <td style={{ fontSize: 12, color: 'var(--muted)' }}>
                        {c.hasSecrets ? '已加密存密' : '未配密钥'} · {String(c.rateLimitPerMin ?? 60)}/min
                      </td>
                      <td>
                        <div style={{ display: 'flex', gap: 6 }}>
                          <button type="button" className="subtle-button dash-compact-btn" disabled={busyId === id} onClick={() => void handleTest(id)}>
                            {busyId === id ? '测试中…' : '测试连通'}
                          </button>
                          <button
                            type="button"
                            className="subtle-button dash-compact-btn"
                            onClick={async () => {
                              if (!(await confirmDialog({ title: `删除连接器 ${String(c.name ?? id)}？`, confirmLabel: '删除', danger: true }))) return;
                              await api.adminConnectorDelete(id);
                              toast('连接器已删除。');
                              await refreshAll();
                            }}
                          >
                            删除
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          {audits.length > 0 ? (
            <div style={{ marginTop: 12 }}>
              <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 6, color: 'var(--muted)' }}>最近连接器调用审计（{audits.length}）</div>
              <div className="dash-table-wrap">
                <table className="dash-table">
                  <thead>
                    <tr>
                      <th>时间</th>
                      <th>连接器</th>
                      <th>动作</th>
                      <th>状态</th>
                      <th>耗时 / 摘要</th>
                    </tr>
                  </thead>
                  <tbody>
                    {audits.slice(0, 6).map((a) => (
                      <tr key={String(a.id ?? '')}>
                        <td style={{ whiteSpace: 'nowrap', fontSize: 12 }}>{a.createdAt ? fmtTime(String(a.createdAt)) : '—'}</td>
                        <td style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: 11.5 }}>{String(a.connectorId ?? '')}</td>
                        <td><code>{String(a.action ?? '')}</code></td>
                        <td>
                          <span className={`svc-rel-st is-${a.status === 'ok' ? 'succeeded' : 'failed'}`}>
                            {String(a.status ?? '')}
                          </span>
                        </td>
                        <td style={{ fontSize: 12 }}>{String(a.durationMs ?? 0)}ms · {String(a.responseSummary || a.error || '—')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          ) : null}
        </div>
      ) : (
        <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
            <div style={{ minWidth: 0, padding: '10px 12px', borderRadius: 8, background: 'var(--bg-subtle, #f8fafc)', border: '1px solid var(--line, #e2e8f0)', fontSize: 12 }}>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>飞书事件订阅 Webhook（支持 url_verification 握手）</div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, minWidth: 0 }}>
                <code style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{eventsUrl}</code>
                <button type="button" className="svc-pill-toggle" style={{ whiteSpace: 'nowrap', flexShrink: 0 }} onClick={async () => toast((await copyText(eventsUrl)) ? '已复制事件订阅地址。' : '请手动复制。')}>
                  复制
                </button>
              </div>
            </div>
            <div style={{ minWidth: 0, padding: '10px 12px', borderRadius: 8, background: 'var(--bg-subtle, #f8fafc)', border: '1px solid var(--line, #e2e8f0)', fontSize: 12 }}>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>飞书交互卡片按钮回调地址（批准上线 / 驳回）</div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, minWidth: 0 }}>
                <code style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{cardUrl}</code>
                <button type="button" className="svc-pill-toggle" style={{ whiteSpace: 'nowrap', flexShrink: 0 }} onClick={async () => toast((await copyText(cardUrl)) ? '已复制卡片回调地址。' : '请手动复制。')}>
                  复制
                </button>
              </div>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input
              type="text"
              value={simCommand}
              onChange={(e) => setSimCommand(e.target.value)}
              placeholder="输入模拟飞书群指令，例如：@织页 状态 / @织页 诊断 TaskFlow / @织页 发布 TaskFlow"
              style={{ flex: 1, height: 36, borderRadius: 8, padding: '0 12px', border: '1px solid var(--line, #e2e8f0)', fontSize: 13 }}
            />
            <button type="button" className="primary-button dash-compact-btn" disabled={simBusy} onClick={() => void handleSimulate()}>
              <Icon name="send" /> {simBusy ? '执行中…' : '模拟飞书 @织页 指令'}
            </button>
          </div>

          {sessions.length === 0 ? (
            <p style={{ fontSize: 12.5, color: 'var(--muted)', margin: 0 }}>
              暂无飞书控制面会话；可在上方输入 <code>@织页 状态</code> 或 <code>@织页 发布 &lt;应用名&gt;</code> 立即体验完整交互卡片审批流。
            </p>
          ) : (
            <div className="dash-table-wrap">
              <table className="dash-table">
                <thead>
                  <tr>
                    <th>时间 / 发起人</th>
                    <th>指令内容</th>
                    <th>意图 / 应用</th>
                    <th>卡片状态</th>
                    <th>审批操作</th>
                  </tr>
                </thead>
                <tbody>
                  {sessions.map((s) => {
                    const sid = String(s.id ?? '');
                    const st = String(s.cardStatus ?? '');
                    return (
                      <tr key={sid}>
                        <td style={{ whiteSpace: 'nowrap', fontSize: 12 }}>
                          {s.createdAt ? fmtTime(String(s.createdAt)) : '—'} · {String(s.userName ?? '')}
                        </td>
                        <td style={{ fontSize: 12.5 }}>{String(s.commandText ?? '')}</td>
                        <td style={{ fontSize: 12 }}>
                          <span className="svc-chip">{String(s.intent ?? '')}</span>
                          {s.appId ? <code style={{ marginLeft: 6, fontSize: 11 }}>{String(s.appId)}</code> : null}
                        </td>
                        <td>
                          <span className={`svc-rel-st is-${st === 'approved_published' || st === 'completed' ? 'succeeded' : st === 'rejected' || st === 'failed' ? 'failed' : 'building'}`}>
                            {st}
                          </span>
                        </td>
                        <td style={{ whiteSpace: 'nowrap' }}>
                          {st === 'ready_for_approval' ? (
                            <div style={{ display: 'flex', gap: 6 }}>
                              <button
                                type="button"
                                className="primary-button dash-compact-btn"
                                disabled={!!busyId}
                                onClick={() => void handleCardAction(sid, 'approve_publish')}
                              >
                                批准上线
                              </button>
                              <button
                                type="button"
                                className="subtle-button dash-compact-btn"
                                disabled={!!busyId}
                                onClick={() => void handleCardAction(sid, 'reject')}
                              >
                                驳回
                              </button>
                            </div>
                          ) : (
                            <span style={{ fontSize: 11.5, color: 'var(--muted)' }}>{String(s.approvalResult || '已完成')}</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {addOpen ? (
      <Modal title="新建企业连接器（公域飞书）" onClose={() => setAddOpen(false)}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <label className="svc-field">
            <span>连接器名称</span>
            <input type="text" value={name} onChange={(e) => setName(e.target.value)} placeholder="如：研发告警飞书群机器人" />
          </label>
          <label className="svc-field">
            <span>连接器类型</span>
            <select value={kind} onChange={(e) => setKind(e.target.value as 'feishu_bot' | 'feishu_bitable' | 'feishu_app')} style={{ height: 36, borderRadius: 8, padding: '0 8px', border: '1px solid var(--line, #e2e8f0)' }}>
              <option value="feishu_bot">飞书群自定义机器人 (feishu_bot)</option>
              <option value="feishu_bitable">飞书多维表格 (feishu_bitable)</option>
              <option value="feishu_app">飞书自建应用 IM (feishu_app)</option>
            </select>
          </label>
          {kind === 'feishu_bot' ? (
            <>
              <label className="svc-field">
                <span>Webhook 地址（https://open.feishu.cn/open-apis/bot/v2/hook/...）</span>
                <input type="text" value={webhookUrl} onChange={(e) => setWebhookUrl(e.target.value)} placeholder="live 模式必填" />
              </label>
              <label className="svc-field">
                <span>签名校验密钥（可选，AES-256-GCM 加密）</span>
                <input type="password" value={webhookSignSecret} onChange={(e) => setWebhookSignSecret(e.target.value)} placeholder="飞书机器人安全设置中的签名密钥" />
              </label>
            </>
          ) : (
            <>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                <label className="svc-field">
                  <span>飞书 App ID</span>
                  <input type="text" value={appIdField} onChange={(e) => setAppIdField(e.target.value)} placeholder="cli_xxxxxx" />
                </label>
                <label className="svc-field">
                  <span>飞书 App Secret（加密存储）</span>
                  <input type="password" value={appSecret} onChange={(e) => setAppSecret(e.target.value)} placeholder="******" />
                </label>
              </div>
              {kind === 'feishu_bitable' ? (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                  <label className="svc-field">
                    <span>多维表格 App Token</span>
                    <input type="text" value={bitableAppToken} onChange={(e) => setBitableAppToken(e.target.value)} placeholder="bascnXXXXXX" />
                  </label>
                  <label className="svc-field">
                    <span>数据表 Table ID</span>
                    <input type="text" value={bitableTableId} onChange={(e) => setBitableTableId(e.target.value)} placeholder="tblXXXXXX" />
                  </label>
                </div>
              ) : (
                <label className="svc-field">
                  <span>默认接收群 Chat ID</span>
                  <input type="text" value={defaultChatId} onChange={(e) => setDefaultChatId(e.target.value)} placeholder="oc_xxxxxx" />
                </label>
              )}
            </>
          )}
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 8 }}>
            <button type="button" className="subtle-button" onClick={() => setAddOpen(false)}>取消</button>
            <button type="button" className="primary-button" disabled={saving || !name.trim()} onClick={() => void handleCreate()}>
              {saving ? '保存中…' : '保存连接器'}
            </button>
          </div>
        </div>
      </Modal>
      ) : null}
    </div>
  );
}
