import { useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { Icon } from '../lib/icons';
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
 * 聊天/创造栏极简「生态」选择器（只管选用，不塞任何配置表单；所有维护统一收口在左侧主菜单「生态」页面 /ecosystem）。
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
  const navigate = useNavigate();
  const box = useRef<HTMLDivElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const [q, setQ] = useState('');

  const pos = useAnchoredPosition(anchor, open, 336, 340);
  useOutside(open, [box, anchor], onClose);

  useEffect(() => {
    if (!open) {
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

  const groupHeadStyle = {
    fontSize: 11,
    fontWeight: 600,
    color: 'var(--st-faint, #8b86a0)',
    padding: '6px 8px 3px',
    cursor: 'default',
    background: 'transparent',
  } as const;

  const hasAny = connectors.length > 0 || mcpServers.length > 0 || sources.length > 0;

  return createPortal(
    <div
      className="skill-picker"
      role="dialog"
      aria-label="生态"
      ref={box}
      style={{ ...pos, width: 336, maxHeight: 356, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
    >
      <label className="skill-picker-search" style={{ flex: 'none' }}>
        <Icon name="search" />
        <input
          ref={searchRef}
          placeholder="搜索飞书、MCP 或 AGW 接口"
          aria-label="搜索生态能力"
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
      </label>

      <ul className="skill-picker-list" role="listbox" aria-label="选用企业生态" style={{ flex: 1, minHeight: 0, maxHeight: 260 }}>
        {!hasAny && !connQ.isLoading && !mcpQ.isLoading && !sourcesQ.isLoading ? (
          <li className="skill-picker-empty">没有匹配的生态能力，点击下方前往「生态」页面添加。</li>
        ) : null}

        {connectors.length > 0 ? (
          <>
            <li style={groupHeadStyle} role="presentation">飞书与多维表格</li>
            {connectors.map((c) => {
              const id = String(c.id ?? '');
              const name = String(c.name ?? id);
              const kind = String(c.kind ?? 'feishu_bot') === 'feishu_bitable' ? 'feishu_bitable' : 'feishu_bot';
              const mode = String(c.mode ?? 'sandbox');
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
                    <small>{kind === 'feishu_bitable' ? '多维表格' : '飞书机器人'} · {mode === 'live' ? '公域直连' : '沙箱'}</small>
                  </span>
                  {on ? <Icon name="check" /> : null}
                </li>
              );
            })}
          </>
        ) : null}

        {mcpServers.length > 0 ? (
          <>
            <li style={groupHeadStyle} role="presentation">企业知识与工具 (MCP)</li>
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

        {onPickSource && sources.length > 0 ? (
          <>
            <li style={groupHeadStyle} role="presentation">AGW 企业接口</li>
            {sources.map((s) => {
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
            })}
          </>
        ) : null}
      </ul>

      <button
        type="button"
        className="skill-picker-more"
        style={{ flex: 'none' }}
        onClick={() => {
          onClose();
          navigate('/ecosystem');
        }}
      >
        前往「生态」管理与添加 →
      </button>
    </div>,
    document.body,
  );
}
