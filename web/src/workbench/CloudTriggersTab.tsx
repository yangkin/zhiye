import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { copyText, fmtTime } from '../lib/format';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';

const CRON_PRESETS: Array<{ label: string; expr: string }> = [
  { label: '每天早上 09:00', expr: '0 9 * * *' },
  { label: '每个工作日 09:00', expr: '0 9 * * 1-5' },
  { label: '每天傍晚 18:00', expr: '0 18 * * *' },
  { label: '每周一 09:30', expr: '30 9 * * 1' },
  { label: '每小时整点', expr: '0 * * * *' },
  { label: '每 5 分钟', expr: '*/5 * * * *' },
  { label: '自定义 Cron…', expr: '__custom__' },
];

function cronLabel(expr: string): string {
  const hit = CRON_PRESETS.find((p) => p.expr === expr);
  return hit ? `${hit.label} (${expr})` : `Cron: ${expr}`;
}

/**
 * 通用应用自动化与飞书通知面板（同时适用于云服务应用与单体全栈应用）：
 * - 去掉手写 Cron 门槛，提供说人话的定时周期下拉 + 一键场景预设
 * - 支持定时飞书播报、数据库变更实时告警、签名入站 Webhook、Deno 云函数回调
 */
export function CloudTriggersTab({
  appId,
  tableNames,
  mode = 'cloud',
}: {
  appId: string;
  tableNames: string[];
  mode?: 'cloud' | 'service';
}) {
  const qc = useQueryClient();
  const listQ = useQuery({ queryKey: ['app-triggers', appId], queryFn: () => api.appTriggersList(appId) });
  const runsQ = useQuery({ queryKey: ['app-trigger-runs', appId], queryFn: () => api.appTriggerRuns(appId, undefined, 20) });
  const connQ = useQuery({ queryKey: ['workspace-connectors'], queryFn: api.connectorsList });

  const isService = mode === 'service';
  const [name, setName] = useState('');
  const [kind, setKind] = useState<'cron' | 'db_insert' | 'db_update' | 'webhook'>(isService ? 'cron' : 'db_insert');
  const [cronPreset, setCronPreset] = useState('0 9 * * 1-5');
  const [customCron, setCustomCron] = useState('0 9 * * 1-5');
  const [tableName, setTableName] = useState(tableNames[0] || '');
  const [actionType, setActionType] = useState<'feishu_card' | 'cloud_function'>('feishu_card');
  const [connectorId, setConnectorId] = useState('');
  const [cardTitle, setCardTitle] = useState(isService ? '应用运行日报提醒' : '织页自动化提醒：{{table}} 有新记录');
  const [cardContent, setCardContent] = useState(isService ? '触发事件：`{{event}}`\n摘要：`{{payload}}`' : '触发事件：`{{event}}`\n数据摘要：`{{payload}}`');
  const [functionName, setFunctionName] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [newSecret, setNewSecret] = useState<string | null>(null);

  const triggers = listQ.data ?? [];
  const runs = runsQ.data ?? [];
  const connectors = (connQ.data ?? []).filter((c) => c.kind === 'feishu_bot' || c.kind === 'feishu_app');
  const effectiveConnectorId = connectorId || String(connectors[0]?.id ?? '');
  const effectiveCron = cronPreset === '__custom__' ? customCron.trim() : cronPreset;

  const refresh = () =>
    Promise.allSettled([
      qc.invalidateQueries({ queryKey: ['app-triggers', appId] }),
      qc.invalidateQueries({ queryKey: ['app-trigger-runs', appId] }),
    ]);

  const applyQuickPreset = (preset: 'daily' | 'weekly' | 'event') => {
    if (preset === 'daily') {
      setName('工作日早 9 点飞书日报');
      setKind('cron');
      setCronPreset('0 9 * * 1-5');
      setActionType('feishu_card');
      setCardTitle('每日运营摘要提醒');
      setCardContent('已到达每日 09:00 定时播报节点，请查看最新数据看板。');
    } else if (preset === 'weekly') {
      setName('每周一早会汇总提醒');
      setKind('cron');
      setCronPreset('30 9 * * 1');
      setActionType('feishu_card');
      setCardTitle('本周例会数据就绪');
      setCardContent('周一 09:30 自动提醒：请核对本周重点指标与待办事项。');
    } else if (isService) {
      setName('外部系统 Webhook 飞书通知');
      setKind('webhook');
      setActionType('feishu_card');
      setCardTitle('收到外部系统事件通知');
      setCardContent('事件：`{{event}}`\n负载：`{{payload}}`');
    } else {
      setName('新记录提交实时飞书告警');
      setKind('db_insert');
      setActionType('feishu_card');
      setCardTitle('新提交提醒：{{table}}');
      setCardContent('有新的业务记录写入表 `{{table}}`，详情：`{{payload}}`');
    }
    toast('已填入预设模板，直接点「添加」即可生效。');
  };

  const handleAdd = async () => {
    if (!name.trim()) return;
    setBusy('add');
    try {
      const res = await api.appTriggerSave(appId, {
        name: name.trim(),
        kind,
        enabled: true,
        cronExpr: kind === 'cron' ? effectiveCron : '',
        tableName: kind === 'db_insert' || kind === 'db_update' ? (tableName.trim() || tableNames[0] || '') : '',
        actionType: isService ? 'feishu_card' : actionType,
        actionConfig:
          isService || actionType === 'feishu_card'
            ? { connectorId: effectiveConnectorId, title: cardTitle, content: cardContent, templateColor: 'blue' }
            : { functionName: functionName.trim() },
      });
      if (res.generatedWebhookSecret) setNewSecret(String(res.generatedWebhookSecret));
      setName('');
      toast('自动化规则已创建并启用。');
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
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12, flexWrap: 'wrap' }}>
        <div>
          <h3 style={{ margin: '0 0 4px' }}>自动化与飞书通知</h3>
          <p className="cc-hint" style={{ margin: 0 }}>
            不用手写 Cron 或脚本：选好周期或事件，自动给飞书群推卡片提醒，或接收外部系统签名 Webhook。
          </p>
        </div>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          <button type="button" className="subtle-button" style={{ fontSize: 12, height: 28, padding: '0 10px' }} onClick={() => applyQuickPreset('daily')}>
            每日 9 点飞书日报
          </button>
          <button type="button" className="subtle-button" style={{ fontSize: 12, height: 28, padding: '0 10px' }} onClick={() => applyQuickPreset('weekly')}>
            每周一早会提醒
          </button>
          <button type="button" className="subtle-button" style={{ fontSize: 12, height: 28, padding: '0 10px' }} onClick={() => applyQuickPreset('event')}>
            {isService ? 'Webhook 推飞书' : '新记录实时告警'}
          </button>
        </div>
      </div>

      {/* 新建触发器表单 */}
      <div style={{ padding: 14, borderRadius: 12, border: '1px solid var(--line, #e2e8f0)', background: 'var(--bg-subtle, #f8fafc)', display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div style={{ display: 'grid', gridTemplateColumns: isService ? '1.4fr 1fr 1.2fr' : '1.4fr 1fr 1.2fr 1fr', gap: 8 }}>
          <input
            type="text"
            placeholder="规则名称（如：异常工单飞书告警）"
            value={name}
            onChange={(e) => setName(e.target.value)}
            style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', background: '#fff', fontSize: 12.5 }}
          />
          <select value={kind} onChange={(e) => setKind(e.target.value as 'cron' | 'db_insert' | 'db_update' | 'webhook')} style={{ height: 34, borderRadius: 8, padding: '0 8px', border: '1px solid var(--line, #e2e8f0)', background: '#fff', fontSize: 12.5 }}>
            {!isService ? <option value="db_insert">有新记录提交时</option> : null}
            {!isService ? <option value="db_update">记录状态更新时</option> : null}
            <option value="cron">按定时周期触发</option>
            <option value="webhook">外部系统 Webhook 触发</option>
          </select>
          {kind === 'cron' ? (
            <div style={{ display: 'flex', gap: 6 }}>
              <select
                value={cronPreset}
                onChange={(e) => setCronPreset(e.target.value)}
                style={{ flex: 1, height: 34, borderRadius: 8, padding: '0 8px', border: '1px solid var(--line, #e2e8f0)', background: '#fff', fontSize: 12.5 }}
              >
                {CRON_PRESETS.map((p) => (
                  <option key={p.expr} value={p.expr}>{p.label}</option>
                ))}
              </select>
              {cronPreset === '__custom__' ? (
                <input
                  type="text"
                  value={customCron}
                  onChange={(e) => setCustomCron(e.target.value)}
                  placeholder="0 9 * * *"
                  style={{ width: 110, height: 34, borderRadius: 8, padding: '0 8px', border: '1px solid var(--line, #e2e8f0)', background: '#fff', fontFamily: 'var(--font-mono, monospace)', fontSize: 12 }}
                />
              ) : null}
            </div>
          ) : kind === 'db_insert' || kind === 'db_update' ? (
            <input type="text" value={tableName} onChange={(e) => setTableName(e.target.value)} placeholder={tableNames[0] || '监听表名'} style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', background: '#fff', fontSize: 12.5 }} />
          ) : (
            <span style={{ fontSize: 12, color: 'var(--muted)', alignSelf: 'center' }}>自动签发 HMAC 签名密钥</span>
          )}
          {!isService ? (
            <select value={actionType} onChange={(e) => setActionType(e.target.value as 'feishu_card' | 'cloud_function')} style={{ height: 34, borderRadius: 8, padding: '0 8px', border: '1px solid var(--line, #e2e8f0)', background: '#fff', fontSize: 12.5 }}>
              <option value="feishu_card">推送飞书交互卡片</option>
              <option value="cloud_function">执行云函数</option>
            </select>
          ) : null}
        </div>

        {isService || actionType === 'feishu_card' ? (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.2fr 1.5fr auto', gap: 8, alignItems: 'center' }}>
            <select value={effectiveConnectorId} onChange={(e) => setConnectorId(e.target.value)} style={{ height: 34, borderRadius: 8, padding: '0 8px', border: '1px solid var(--line, #e2e8f0)', background: '#fff', fontSize: 12 }}>
              {connectors.length === 0 ? <option value="">请先在「生态」页添加飞书机器人</option> : null}
              {connectors.map((c) => (
                <option key={String(c.id)} value={String(c.id)}>
                  {String(c.name)}
                </option>
              ))}
            </select>
            <input type="text" value={cardTitle} onChange={(e) => setCardTitle(e.target.value)} placeholder="飞书卡片标题" style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', background: '#fff', fontSize: 12.5 }} />
            <input type="text" value={cardContent} onChange={(e) => setCardContent(e.target.value)} placeholder="正文支持 {{event}} / {{table}} / {{payload}}" style={{ height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', background: '#fff', fontSize: 12.5 }} />
            <button type="button" className="primary-button" disabled={!!busy || !name.trim()} onClick={() => void handleAdd()}>
              <Icon name="plus" /> 添加
            </button>
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 8 }}>
            <input type="text" value={functionName} onChange={(e) => setFunctionName(e.target.value)} placeholder="云函数名（不含 .ts，如 notify_feishu）" style={{ flex: 1, height: 34, borderRadius: 8, padding: '0 10px', border: '1px solid var(--line, #e2e8f0)', background: '#fff', fontSize: 12.5 }} />
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
        <p className="cc-hint">暂无自动化规则，可点右上角预设快速创建每日飞书日报、实时告警或外部 Webhook 通知。</p>
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
                    {kindVal === 'cron' ? cronLabel(String(t.cronExpr ?? '')) : kindVal === 'webhook' ? `Webhook: ${whUrl}` : `表 ${String(t.tableName ?? '')} (${kindVal === 'db_insert' ? '新增时' : '更新时'})`}
                    {' · '}动作：{String(t.actionType ?? '') === 'feishu_card' ? '推送飞书卡片' : '执行云函数'} · 已运行 {String(t.runCount ?? 0)} 次
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
                  className="subtle-button"
                  style={{ fontSize: 12, padding: '4px 8px', color: '#dc2626' }}
                  onClick={async () => {
                    await api.appTriggerDelete(appId, id);
                    toast('自动化规则已删除。');
                    await refresh();
                  }}
                >
                  删除
                </button>
              </div>
            );
          })}
        </div>
      )}

      {/* 最近触发执行记录 */}
      {runs.length > 0 ? (
        <div>
          <h4 style={{ margin: '4px 0 8px', fontSize: 13 }}>最近执行记录 ({runs.length})</h4>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {runs.slice(0, 8).map((r) => (
              <div key={String(r.id)} style={{ fontSize: 12, padding: '6px 10px', borderRadius: 8, border: '1px solid var(--line, #e2e8f0)', display: 'flex', justifyContent: 'space-between', gap: 10 }}>
                <span>
                  <b style={{ color: r.ok ? '#059669' : '#dc2626' }}>{r.ok ? '✓ 成功' : '✗ 失败'}</b>
                  {' · '}{String(r.event ?? '')} · {String(r.durationMs ?? 0)}ms
                  {r.error ? <span style={{ color: '#dc2626' }}> · {String(r.error)}</span> : null}
                </span>
                <span style={{ color: 'var(--muted)' }}>{r.createdAt ? fmtTime(String(r.createdAt)) : ''}</span>
              </div>
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}
