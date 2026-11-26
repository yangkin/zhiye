import { useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { Icon } from '../lib/icons';
import { toast, useUi } from '../lib/store';
import { useAnchoredPosition, useOutside } from './ComposerPopovers';
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
 * 扁平轻量「生态」浮层（与「技能」弹层完全同款交互：无全屏遮罩、无多余 Tab、无弹窗套弹窗）。
 * 1) 扁平分组列表：飞书连接器 / 企业知识 MCP / AGW 接口，点一下即勾选生效；
 * 2) 底部「＋ 添加我的飞书」原地切换极简 3 行表单，保存后自动勾选并回到列表。
 */
export function EcosystemPickerModal({
  open,
  anchor,
  onClose,
  selected,
  onToggle,
  pickedSourceIds,
  onPickSource,
}: {
  open: boolean;
  anchor: RefObject<HTMLElement | null>;
  onClose: () => void;
  selected: EcosystemItem[];
  onToggle: (item: EcosystemItem) => void;
  pickedSourceIds?: string[];
  onPickSource?: (source: DataSourceInfo) => void;
}) {
  const qc = useQueryClient();
  const setSourcesOpen = useUi((s) => s.setSourcesOpen);
  const box = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const [q, setQ] = useState('');
  const [adding, setAdding] = useState(false);

  // 极简原地添加飞书表单
  const [newKind, setNewKind] = useState<'feishu_bot' | 'feishu_bitable'>('feishu_bot');
  const [newName, setNewName] = useState('');
  const [newUrl, setNewUrl] = useState('');
  const [saving, setSaving] = useState(false);
  const [testingId, setTestingId] = useState<string | null>(null);
  const [testedOk, setTestedOk] = useState<Record<string, boolean>>({});

  const pos = useAnchoredPosition(anchor, open, 356, 340);
  useOutside(open, [box, anchor], onClose);

  useEffect(() => {
    if (!open) {
      setAdding(false);
      setQ('');
      return;
    }
    requestAnimationFrame(() => searchRef.current?.focus());
  }, [open]);

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

  const kw = q.trim().toLowerCase();
  const connectors = useMemo(
    () => (connQ.data ?? []).filter((c) => !kw || `${String(c.name ?? '')} ${String(c.id ?? '')}`.toLowerCase().includes(kw)),
    [connQ.data, kw],
  );
  const mcpServers = useMemo(
    () => (mcpQ.data ?? []).filter((s) => !kw || `${String(s.name ?? '')} ${String(s.serverKey ?? '')} ${String(s.description ?? '')}`.toLowerCase().includes(kw)),
    [mcpQ.data, kw],
  );
  const sources = useMemo(
    () => (sourcesQ.data ?? []).filter((s) => !kw || `${s.name} ${s.description ?? ''}`.toLowerCase().includes(kw)),
    [sourcesQ.data, kw],
  );

  if (!open) return null;

  const selectedIds = new Set(selected.map((x) => x.id));
  const pickedSrcSet = new Set(pickedSourceIds ?? []);

  const handleTest = async (id: string) => {
    setTestingId(id);
    try {
      const r = await api.connectorTest(id);
      setTestedOk((prev) => ({ ...prev, [id]: true }));
      toast(`连通正常（${String(r.mode ?? 'ok')}）`);
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setTestingId(null);
    }
  };

  const handleSaveFeishu = async () => {
    if (!newName.trim()) return toast('请填写名称（如：产线告警群机器人）', 'error');
    setSaving(true);
    try {
      const url = newUrl.trim();
      const saved = await api.connectorSave({
        name: newName.trim(),
        kind: newKind,
        mode: url ? 'live' : 'sandbox',
        enabled: true,
        ...(newKind === 'feishu_bitable' && url ? { bitableUrl: url } : {}),
        secrets: newKind === 'feishu_bot' && url ? { webhookUrl: url } : {},
      });
      await qc.invalidateQueries({ queryKey: ['workspace-connectors'] });
      const savedId = String(saved.id ?? '');
      if (savedId && !selectedIds.has(savedId)) {
        onToggle({ id: savedId, category: newKind, name: String(saved.name ?? newName.trim()) });
      }
      setNewName('');
      setNewUrl('');
      setAdding(false);
      toast('已添加并勾选');
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
      toast(`已删除「${name}」`);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const groupHeadStyle = {
    fontSize: 11,
    fontWeight: 600,
    color: 'var(--st-faint, #8b86a0)',
    padding: '6px 8px 3px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    cursor: 'default',
    background: 'transparent',
  } as const;

  return createPortal(
    <div
      className="skill-picker"
      role="dialog"
      aria-label="生态"
      ref={box}
      style={{ ...pos, width: 356, maxHeight: 360, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
    >
      {adding ? (
        <div style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 9 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <b style={{ fontSize: 13 }}>添加我的飞书</b>
            <button
              type="button"
              onClick={() => setAdding(false)}
              style={{ border: 0, background: 'transparent', color: '#6b7280', fontSize: 12, cursor: 'pointer' }}
            >
              返回列表
            </button>
          </div>
          <select
            value={newKind}
            onChange={(e) => setNewKind(e.target.value as 'feishu_bot' | 'feishu_bitable')}
            style={{ height: 32, borderRadius: 8, padding: '0 8px', border: '1px solid #ded9ee', background: '#fff', fontSize: 12.5 }}
          >
            <option value="feishu_bot">飞书群机器人（Webhook 推卡片）</option>
            <option value="feishu_bitable">飞书多维表格（同步读写）</option>
          </select>
          <input
            type="text"
            placeholder="名称（如：研发告警群机器人）"
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            style={{ height: 32, borderRadius: 8, padding: '0 10px', border: '1px solid #ded9ee', fontSize: 12.5 }}
          />
          <input
            type="text"
            placeholder={
              newKind === 'feishu_bot'
                ? '飞书群 Webhook 地址（留空则建沙箱演示）'
                : '粘贴多维表格链接（留空则建沙箱演示）'
            }
            value={newUrl}
            onChange={(e) => setNewUrl(e.target.value)}
            style={{ height: 32, borderRadius: 8, padding: '0 10px', border: '1px solid #ded9ee', fontSize: 12 }}
          />
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 2 }}>
            <button type="button" className="subtle-button" style={{ height: 30, fontSize: 12 }} onClick={() => setAdding(false)}>
              取消
            </button>
            <button
              type="button"
              className="primary-button"
              style={{ height: 30, padding: '0 12px', fontSize: 12 }}
              disabled={saving || !newName.trim()}
              onClick={() => void handleSaveFeishu()}
            >
              {saving ? '保存中…' : '保存并勾选'}
            </button>
          </div>
        </div>
      ) : (
        <>
          <label className="skill-picker-search" style={{ flex: 'none' }}>
            <Icon name="search" />
            <input
              ref={searchRef}
              placeholder="搜索飞书、知识库 MCP 或数据源"
              aria-label="搜索生态能力"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
          </label>

          <ul className="skill-picker-list" role="listbox" aria-label="企业生态能力" style={{ flex: 1, minHeight: 0, maxHeight: 260 }}>
            {/* 分组 1：飞书机器人与多维表格 */}
            <li style={groupHeadStyle} role="presentation">
              <span>飞书机器人 &amp; 多维表格</span>
              <span>点即勾选</span>
            </li>
            {connQ.isLoading ? <li className="skill-picker-empty">读取中…</li> : null}
            {!connQ.isLoading && connectors.length === 0 ? (
              <li className="skill-picker-empty">暂无匹配的飞书连接器</li>
            ) : null}
            {connectors.map((c) => {
              const id = String(c.id ?? '');
              const name = String(c.name ?? id);
              const kind = String(c.kind ?? 'feishu_bot') === 'feishu_bitable' ? 'feishu_bitable' : 'feishu_bot';
              const mode = String(c.mode ?? 'sandbox');
              const owned = Boolean(c.owned);
              const on = selectedIds.has(id);
              return (
                <li
                  key={id}
                  role="option"
                  aria-selected={on}
                  className={on ? 'picked' : ''}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => onToggle({ id, category: kind, name })}
                >
                  <Icon name={kind === 'feishu_bitable' ? 'database' : 'globe'} />
                  <span>
                    <b>{name}</b>
                    <small>
                      {kind === 'feishu_bitable' ? '多维表格' : '飞书机器人'} · {mode === 'live' ? '公域直连' : '沙箱'}
                      {testedOk[id] ? ' · ✓ 连通' : ''}
                    </small>
                  </span>
                  <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
                    <button
                      type="button"
                      title="测试连通"
                      disabled={testingId === id}
                      onClick={(e) => {
                        e.stopPropagation();
                        void handleTest(id);
                      }}
                      style={{
                        border: '1px solid #e2ddf1',
                        background: testedOk[id] ? '#ecfdf5' : '#fff',
                        color: testedOk[id] ? '#059669' : '#6b6580',
                        borderRadius: 6,
                        fontSize: 11,
                        padding: '2px 6px',
                        cursor: 'pointer',
                      }}
                    >
                      {testingId === id ? '…' : testedOk[id] ? '✓' : '测'}
                    </button>
                    {owned ? (
                      <button
                        type="button"
                        title="删除我添加的连接器"
                        aria-label={`删除 ${name}`}
                        onClick={(e) => {
                          e.stopPropagation();
                          void handleDeleteOwn(id, name);
                        }}
                        style={{
                          border: 0,
                          background: 'transparent',
                          color: '#dc2626',
                          fontSize: 12,
                          padding: '2px 4px',
                          cursor: 'pointer',
                        }}
                      >
                        ×
                      </button>
                    ) : null}
                    {on ? <Icon name="check" /> : null}
                  </div>
                </li>
              );
            })}

            {/* 分组 2：企业知识与工具 MCP */}
            {mcpServers.length > 0 ? (
              <>
                <li style={groupHeadStyle} role="presentation">
                  <span>企业知识与工具 (MCP)</span>
                </li>
                {mcpServers.map((s) => {
                  const key = String(s.serverKey ?? s.id ?? '');
                  const name = String(s.name ?? key);
                  const desc = String(s.description ?? '');
                  const on = selectedIds.has(key);
                  return (
                    <li
                      key={key}
                      role="option"
                      aria-selected={on}
                      className={on ? 'picked' : ''}
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={() => onToggle({ id: key, category: 'mcp', name, subtitle: desc })}
                    >
                      <Icon name="spark" />
                      <span>
                        <b>{name}</b>
                        {desc ? <small>{desc}</small> : null}
                      </span>
                      {on ? <Icon name="check" /> : null}
                    </li>
                  );
                })}
              </>
            ) : null}

            {/* 分组 3：AGW 企业接口 */}
            {onPickSource ? (
              <>
                <li style={groupHeadStyle} role="presentation">
                  <span>企业接口 (AGW)</span>
                  <button
                    type="button"
                    onClick={() => {
                      onClose();
                      setSourcesOpen(true);
                    }}
                    style={{ border: 0, background: 'transparent', color: '#7657d9', fontSize: 11, cursor: 'pointer', padding: 0 }}
                  >
                    管理接口
                  </button>
                </li>
                {sources.length === 0 ? (
                  <li
                    role="option"
                    aria-selected={false}
                    onClick={() => {
                      onClose();
                      setSourcesOpen(true);
                    }}
                  >
                    <Icon name="plus" />
                    <span>
                      <b>从 AGW 目录添加接口</b>
                      <small>暂未添加，点此挑选有权限的业务 API</small>
                    </span>
                  </li>
                ) : (
                  sources.map((s) => {
                    const on = pickedSrcSet.has(s.id);
                    return (
                      <li
                        key={s.id}
                        role="option"
                        aria-selected={on}
                        className={on ? 'picked' : ''}
                        onMouseDown={(e) => e.preventDefault()}
                        onClick={() => {
                          if (!on) onPickSource(s);
                        }}
                      >
                        <Icon name="database" />
                        <span>
                          <b>{s.name}</b>
                          {s.description ? <small>{s.description}</small> : null}
                        </span>
                        {on ? <Icon name="check" /> : null}
                      </li>
                    );
                  })
                )}
              </>
            ) : null}
          </ul>

          <button
            type="button"
            className="skill-picker-more"
            style={{ flex: 'none' }}
            onClick={() => setAdding(true)}
          >
            ＋ 添加我的飞书机器人 / 多维表格
          </button>
        </>
      )}
    </div>,
    document.body,
  );
}
