import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '../lib/icons';

export interface OwnerPickerOption {
  value: string;
  workspaceId?: string | null;
  ownerRealName?: string | null;
  ownerLogin?: string | null;
  ownerName?: string | null;
  ownerBound: boolean;
  appCount: number;
  publishedCount: number;
  sampleApps?: string[];
  unprovisioned?: boolean;
}

/** 统一按「姓名 (账号)」格式化人员归属，例如：杨凯 (yangkai37) */
export function formatOwnerIdentity(opts: {
  ownerRealName?: string | null;
  ownerLogin?: string | null;
  ownerName?: string | null;
  workspaceId?: string | null;
}): {
  bound: boolean;
  primary: string;
  account: string | null;
  displayLabel: string;
} {
  const wsId = (opts.workspaceId ?? '').trim();
  const real = (opts.ownerRealName ?? '').trim();
  const login = (opts.ownerLogin ?? '').trim();
  const fallbackName = (opts.ownerName ?? '').trim();
  const effectiveName = real || (fallbackName && fallbackName !== wsId ? fallbackName : '');

  if (effectiveName && login && effectiveName.toLowerCase() !== login.toLowerCase()) {
    return {
      bound: true,
      primary: effectiveName,
      account: login,
      displayLabel: `${effectiveName} (${login})`,
    };
  }
  if (effectiveName) {
    return {
      bound: true,
      primary: effectiveName,
      account: login || null,
      displayLabel: effectiveName,
    };
  }
  if (login) {
    return {
      bound: true,
      primary: login,
      account: null,
      displayLabel: login,
    };
  }
  return {
    bound: false,
    primary: '未绑定账号',
    account: null,
    displayLabel: wsId ? `未绑定账号 (${wsId})` : '未绑定账号',
  };
}

interface OwnerPickerProps {
  value: string;
  options: OwnerPickerOption[];
  onChange: (value: string) => void;
  ariaLabel: string;
  placeholder?: string;
  emptyOptionLabel?: string;
  allowEmpty?: boolean;
  disabled?: boolean;
  compact?: boolean;
  className?: string;
}

/** 可搜索的「人员（姓名 / 账号）与工作空间」选择器 */
export function OwnerPicker({
  value,
  options,
  onChange,
  ariaLabel,
  placeholder = '选择人员（姓名 / 账号）或工作空间…',
  emptyOptionLabel = '全部人员 / 空间',
  allowEmpty = false,
  disabled = false,
  compact = false,
  className = '',
}: OwnerPickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const [pos, setPos] = useState<{ left: number; top: number; width: number }>({ left: 0, top: 0, width: 320 });

  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const listId = useId();

  const current = useMemo(() => options.find((o) => o.value === value) ?? null, [options, value]);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return options;
    return options.filter((o) => {
      const hay = [
        o.ownerRealName,
        o.ownerLogin,
        o.ownerName,
        o.workspaceId,
        o.value,
        ...(o.sampleApps ?? []),
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return hay.includes(q);
    });
  }, [options, query]);

  const visibleItems = useMemo(() => filtered.slice(0, 80), [filtered]);

  const place = () => {
    const r = triggerRef.current?.getBoundingClientRect();
    if (!r) return;
    const panelWidth = Math.min(window.innerWidth - 16, Math.max(r.width, compact ? 320 : 360));
    const estHeight = 360;
    const up = r.bottom + estHeight + 8 > window.innerHeight && r.top > estHeight + 8;
    const left = Math.max(8, Math.min(r.left, window.innerWidth - panelWidth - 8));
    const top = up ? Math.max(8, r.top - estHeight - 6) : r.bottom + 6;
    setPos({ left, top, width: panelWidth });
  };

  const openMenu = () => {
    if (disabled) return;
    place();
    setQuery('');
    const idx = options.findIndex((o) => o.value === value);
    setActive(idx >= 0 ? idx : 0);
    setOpen(true);
  };

  const closeMenu = (focusTrigger = true) => {
    setOpen(false);
    if (focusTrigger) triggerRef.current?.focus();
  };

  const selectValue = (next: string) => {
    onChange(next);
    closeMenu(true);
  };

  useEffect(() => {
    if (!open) return;
    const timer = window.setTimeout(() => inputRef.current?.focus(), 0);
    const onPointerDown = (e: PointerEvent) => {
      const target = e.target as Node;
      if (!triggerRef.current?.contains(target) && !panelRef.current?.contains(target)) {
        closeMenu(false);
      }
    };
    const onReflow = () => place();
    document.addEventListener('pointerdown', onPointerDown, true);
    window.addEventListener('resize', onReflow);
    window.addEventListener('scroll', onReflow, true);
    return () => {
      window.clearTimeout(timer);
      document.removeEventListener('pointerdown', onPointerDown, true);
      window.removeEventListener('resize', onReflow);
      window.removeEventListener('scroll', onReflow, true);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  useEffect(() => {
    setActive(0);
  }, [query]);

  useLayoutEffect(() => {
    if (!open) return;
    listRef.current?.querySelector<HTMLElement>(`[data-picker-idx="${active}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [open, active]);

  const onInputKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((a) => Math.min(Math.max(0, visibleItems.length - 1), a + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(0, a - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const hit = visibleItems[active];
      if (hit) selectValue(hit.value);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      closeMenu(true);
    }
  };

  const currentId = current ? formatOwnerIdentity(current) : null;

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        role="combobox"
        aria-label={ariaLabel}
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        disabled={disabled}
        className={`owner-picker-trigger ${compact ? 'compact' : ''} ${open ? 'open' : ''} ${!current ? 'placeholder' : ''} ${className}`}
        onClick={() => (open ? closeMenu(false) : openMenu())}
      >
        <span className="owner-picker-trigger-body">
          {current && currentId ? (
            currentId.bound ? (
              <>
                <span className="owner-picker-name">{currentId.primary}</span>
                {currentId.account ? <code className="owner-picker-acct">{currentId.account}</code> : null}
              </>
            ) : (
              <>
                <span className="owner-picker-anon">未绑定</span>
                <code className="owner-picker-ws">{current.workspaceId || current.value}</code>
              </>
            )
          ) : (
            <span className="owner-picker-placeholder">{allowEmpty ? emptyOptionLabel : placeholder}</span>
          )}
        </span>
        {allowEmpty && value ? (
          <span
            role="button"
            tabIndex={-1}
            className="owner-picker-clear"
            aria-label="清空筛选"
            onClick={(e) => {
              e.stopPropagation();
              onChange('');
            }}
          >
            ×
          </span>
        ) : null}
        <span className="owner-picker-caret" aria-hidden="true">▾</span>
      </button>

      {open
        ? createPortal(
            <div
              ref={panelRef}
              className="owner-picker-pop"
              style={{ left: pos.left, top: pos.top, width: pos.width }}
            >
              <div className="owner-picker-search">
                <Icon name="search" />
                <input
                  ref={inputRef}
                  type="text"
                  value={query}
                  placeholder="搜姓名、账号（如 yangkai37）、空间或应用名…"
                  onChange={(e) => setQuery(e.target.value)}
                  onKeyDown={onInputKeyDown}
                />
                {query ? (
                  <button
                    type="button"
                    className="owner-picker-search-clear"
                    aria-label="清除搜索词"
                    onClick={() => {
                      setQuery('');
                      inputRef.current?.focus();
                    }}
                  >
                    ×
                  </button>
                ) : null}
              </div>

              <div ref={listRef} id={listId} role="listbox" className="owner-picker-list">
                {allowEmpty && !query.trim() ? (
                  <button
                    type="button"
                    role="option"
                    aria-selected={value === ''}
                    className={`owner-picker-item empty-opt ${value === '' ? 'selected' : ''}`}
                    onClick={() => selectValue('')}
                  >
                    <span>{emptyOptionLabel}</span>
                    <span className="owner-picker-meta">{options.length} 个空间</span>
                  </button>
                ) : null}

                {visibleItems.length === 0 ? (
                  <div className="owner-picker-empty">
                    未找到匹配「{query}」的人员或空间，请尝试输入姓名、域账号或应用名
                  </div>
                ) : (
                  visibleItems.map((opt, idx) => {
                    const idInfo = formatOwnerIdentity(opt);
                    const selected = opt.value === value;
                    const sampleText =
                      opt.sampleApps && opt.sampleApps.length > 0
                        ? `含「${opt.sampleApps.slice(0, 2).join('」「')}」${opt.appCount > 2 ? ` 等 ${opt.appCount} 个` : ''}`
                        : opt.unprovisioned
                          ? '尚未初始化空间，交接时自动创建'
                          : '暂无在用应用';
                    return (
                      <button
                        key={opt.value}
                        type="button"
                        role="option"
                        data-picker-idx={idx}
                        aria-selected={selected}
                        className={`owner-picker-item ${selected ? 'selected' : ''} ${idx === active ? 'active' : ''}`}
                        onMouseEnter={() => setActive(idx)}
                        onClick={() => selectValue(opt.value)}
                      >
                        <div className="owner-picker-item-top">
                          <span className="owner-picker-item-id">
                            {idInfo.bound ? (
                              <>
                                <b>{idInfo.primary}</b>
                                {idInfo.account ? <code className="owner-picker-acct">{idInfo.account}</code> : null}
                              </>
                            ) : (
                              <>
                                <span className="owner-picker-anon">未绑定账号</span>
                                <code className="owner-picker-ws">{opt.workspaceId || opt.value}</code>
                              </>
                            )}
                          </span>
                          <span className="owner-picker-meta">
                            {opt.unprovisioned
                              ? '待初始化'
                              : `${opt.appCount} 应用 / ${opt.publishedCount} 线上`}
                          </span>
                        </div>
                        <div className="owner-picker-item-sub">
                          {idInfo.bound && opt.workspaceId ? <code>{opt.workspaceId}</code> : null}
                          <span>{sampleText}</span>
                        </div>
                      </button>
                    );
                  })
                )}
              </div>

              {filtered.length > visibleItems.length ? (
                <div className="owner-picker-foot">
                  已匹配 {filtered.length} 项（展示前 {visibleItems.length} 项），继续输入姓名或账号可精确定位
                </div>
              ) : null}
            </div>,
            document.body,
          )
        : null}
    </>
  );
}
