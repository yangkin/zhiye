import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { copyText, fmtTime } from '../lib/format';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';

/** 应用自动化触发器面板（嵌入 CloudConsole：支持 Cron 定时 / DB 写入钩子 / 签名入站 Webhook → 飞书卡片或云函数）。 */
export function CloudTriggersTab({ appId, tableNames }: { appId: string; tableNames: string[] }) {
  const qc = useQueryClient();
  const listQ = useQuery({ queryKey: ['app-triggers', appId], queryFn: () => api.appTriggersList(appId) });
  const runsQ = useQuery({ queryKey: ['app-trigger-runs', appId], queryFn: () => api.appTriggerRuns(appId, undefined, 20) });
  const connQ = useQuery({ queryKey: ['workspace-connectors'], queryFn: api.connectorsList });

  const [name, setName] = useState('');
  const [kind, setKind] = useState<'cron' | 'db_insert' | 'db_update' | 'webhook'>('db_insert');
  const [cronExpr, setCronExpr] = useState('*/5 * * * *');
  const [tableName, setTableName] = useState(tableNames[0] || '');
  const [actionType, setActionType] = useState<'feishu_card' | 'cloud_function'>('feishu_card');
  const [connectorId, setConnectorId] = useState('conn_feishu_bot_sandbox');
  const [cardTitle, setCardTitle] = useState('织页自动化提醒：{{table}} 有新记录');
  const [cardContent, setCardContent] = useState('触发事件：`{{event}}`\n数据摘要：`{{payload}}`');
  const [functionName, setFunctionName] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [newSecret, setNewSecret] = useState<string | null>(null);

  const triggers = listQ.data ?? [];
  const runs = runsQ.data ?? [];
  const connectors = (connQ.data ?? []).filter((c) => c.kind === 'feishu_bot' || c.kind === 'feishu_app');

  const refresh = () =>
    Promise.allSettled([
      qc.invalidateQueries({ queryKey: ['app-triggers', appId] }),
      qc.invalidateQueries({ queryKey: ['app-trigger-runs', appId] }),
    ]);

  const handleAdd = async () => {
    if (!name.trim()) return;
    setBusy('add');
    try {
      const res = await api.appTriggerSave(appId, {
        name: name.trim(),
        kind,
        enabled: true,
        cronExpr: kind === 'cron' ? cronExpr.trim() : '',
        tableName: kind === 'db_insert' || kind === 'db_update' ? (tableName.trim() || tableNames[0] || '') : '',
        actionType,
        actionConfig:
          actionType === 'feishu_card'
            ? { connectorId, title: cardTitle, content: cardContent, templateColor: 'blue' }
            : { functionName: functionName.trim() },
      });
      if (res.generatedWebhookSecret) setNewSecret(String(res.generatedWebhookSecret));
      setName('');
      toast('自动化触发器已创建并启用。');
      await refresh();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };

  const handleRun = async (id: string) => {
    setBusy(`run:${id}`);
    try {
      await api.appTriggerRun(appId, id, { source: 'manual_console_test', ts: new Date().toISOString() });
      toast('触发器已立即测试执行成功！');
      await refresh();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="cc-section" style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div>
        <h3 style={{ margin: '0 0 4px' }}>自动化触发器（Cron / 数据库事件 / Webhook）</h3>
        <p className="cc-hint" style={{ margin: 0 }}>
          在数据库新增/更新、定时计划到达或外部系统推送 Webhook 时，自动执行 Deno 云函数或通过企业连接器发送公域飞书交互卡片。
        </p>
      </div>

      {/* 新建触发器表单 */}
      <div style={{ padding: 12, borderRadius: 10, border: '1px solid var(--line, #e2e8f0)', background: 'var(--bg-subtle, #f8fafc)', display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div style={{ display: 'grid', gridTemplateColumns: '1.4fr 1fr 1fr 1fr', gap: 8 }}>
          <input
            type="text"
            placeholder="触发器名称（如：异常工单飞书告警）"
            value={name}
            onChange={(e) => setName(e.target.value)}
            style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', fontSize: 12.5 }}
          />
          <select value={kind} onChange={(e) => setKind(e.target.value as 'cron' | 'db_insert' | 'db_update' | 'webhook')} style={{ height: 34, borderRadius: 8, padding: '0 8px', border: '1px solid var(--line, #e2e8f0)', fontSize: 12.5 }}>
            <option value="db_insert">数据库新增 (db_insert)</option>
            <option value="db_update">数据库更新 (db_update)</option>
            <option value="cron">定时计划 (cron)</option>
            <option value="webhook">签名入站 Webhook</option>
          </select>
          {kind === 'cron' ? (
            <input type="text" value={cronExpr} onChange={(e) => setCronExpr(e.target.value)} placeholder="*/5 * * * *" style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', fontFamily: 'var(--font-mono, monospace)', fontSize: 12 }} />
          ) : kind === 'db_insert' || kind === 'db_update' ? (
            <input type="text" value={tableName} onChange={(e) => setTableName(e.target.value)} placeholder={tableNames[0] || '监听表名'} style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', fontSize: 12.5 }} />
          ) : (
            <span style={{ fontSize: 12, color: 'var(--muted)', alignSelf: 'center' }}>自动签发 HMAC 密钥</span>
          )}
          <select value={actionType} onChange={(e) => setActionType(e.target.value as 'feishu_card' | 'cloud_function')} style={{ height: 34, borderRadius: 8, padding: '0 8px', border: '1px solid var(--line, #e2e8f0)', fontSize: 12.5 }}>
            <option value="feishu_card">发送飞书交互卡片</option>
            <option value="cloud_function">调用 Deno 云函数</option>
          </select>
        </div>

        {actionType === 'feishu_card' ? (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.2fr 1.5fr auto', gap: 8, alignItems: 'center' }}>
            <select value={connectorId} onChange={(e) => setConnectorId(e.target.value)} style={{ height: 34, borderRadius: 8, padding: '0 8px', border: '1px solid var(--line, #e2e8f0)', fontSize: 12 }}>
              {connectors.length === 0 ? <option value="conn_feishu_bot_sandbox">公域飞书沙箱机器人</option> : null}
              {connectors.map((c) => (
                <option key={String(c.id)} value={String(c.id)}>{String(c.name)} ({String(c.mode)})</option>
              ))}
            </select>
            <input type="text" value={cardTitle} onChange={(e) => setCardTitle(e.target.value)} placeholder="飞书卡片标题" style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', fontSize: 12.5 }} />
            <input type="text" value={cardContent} onChange={(e) => setCardContent(e.target.value)} placeholder="支持 {{event}} / {{table}} / {{row.xxx}}" style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', fontSize: 12.5 }} />
            <button type="button" className="primary-button" disabled={!!busy || !name.trim()} onClick={() => void handleAdd()}>
              <Icon name="plus" /> 添加
            </button>
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 8 }}>
            <input type="text" value={functionName} onChange={(e) => setFunctionName(e.target.value)} placeholder="云函数名（不含 .ts，如 notify_feishu）" style={{ flex: 1, height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', fontSize: 12.5 }} />
            <button type="button" className="primary-button" disabled={!!busy || !name.trim() || !functionName.trim()} onClick={() => void handleAdd()}>
              <Icon name="plus" /> 添加
            </button>
          </div>
        )}

        {newSecret ? (
          <div style={{ fontSize: 12, padding: '6px 10px', borderRadius: 6, background: '#ecfdf5', color: '#065f46' }}>
            Webhook 签名密钥（仅展示一次）：<code>{newSecret}</code>
          </div>
        ) : null}
      </div>

      {/* 已配置触发器列表 */}
      {triggers.length === 0 ? (
        <p className="cc-hint">暂无触发器，可在上方创建定时播报、数据写入飞书告警或外部 Webhook 自动化。</p>
      ) : (
        <div className="cc-files">
          {triggers.map((t) => {
            const id = String(t.id ?? '');
            const kindVal = String(t.kind ?? '');
            const whUrl = typeof window !== 'undefined' ? `${window.location.origin}${String(t.webhookPath ?? '')}` : String(t.webhookPath ?? '');
            return (
              <div key={id} className="cc-file-row" style={{ gap: 10 }}>
                <Icon name="spark" />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <b>{String(t.name ?? '')}</b>
                  <div style={{ fontSize: 11.5, color: 'var(--muted)' }}>
                    {kindVal === 'cron' ? `Cron: ${String(t.cronExpr ?? '')}` : kindVal === 'webhook' ? `Webhook: ${whUrl}` : `表 ${String(t.tableName ?? '')} (${kindVal})`}
                    {' · '}动作：{String(t.actionType ?? '')} · 已运行 {String(t.runCount ?? 0)} 次
                  </div>
                </div>
                {kindVal === 'webhook' ? (
                  <button type="button" className="subtle-button" style={{ fontSize: 12, padding: '4px 8px' }} onClick={async () => toast((await copyText(whUrl)) ? '已复制 Webhook 地址。' : '请手动复制。')}>
                    复制 URL
                  </button>
                ) : null}
                <button type="button" className="subtle-button" style={{ fontSize: 12, padding: '4px 8px' }} disabled={busy === `run:${id}`} onClick={() => void handleRun(id)}>
                  {busy === `run:${id}` ? '执行中…' : '立即测试'}
                </button>
                <button
                  type="button"
                  className="icon-button"
                  aria-label="删除触发器"
                  onClick={async () => {
                    await api.appTriggerDelete(appId, id);
                    toast('触发器已删除。');
                    await refresh();
                  }}
                >
                  <Icon name="trash" />
                </button>
              </div>
            );
          })}
        </div>
      )}

      {runs.length > 0 ? (
        <div>
          <h4 style={{ margin: '4px 0 6px', fontSize: 13 }}>最近执行记录</h4>
          <div className="cc-files">
            {runs.slice(0, 8).map((r) => (
              <div key={String(r.id ?? '')} className="cc-file-row" style={{ fontSize: 12 }}>
                <span className={`cc-dot ${r.status === 'ok' ? 'ok' : 'bad'}`} />
                <b>{String(r.eventType ?? '')}</b>
                <small className="cc-fn-stat">{String(r.durationMs ?? 0)} ms</small>
                <small className="cc-fn-stat">{r.createdAt ? fmtTime(String(r.createdAt)) : ''}</small>
                <span style={{ opacity: 0.75, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {String(r.error || r.resultSummary || 'ok')}
                </span>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}
