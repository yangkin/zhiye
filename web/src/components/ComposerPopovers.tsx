import { useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { useQuery } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk, useMeta } from '../api/queries';
import type { Meta, Mode } from '../api/types';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { SkillHubMarket } from './SkillHubMarket';

export interface SkillRef {
  id: string;
  name: string;
}
export const MAX_SKILLS = 5;

/** 自己的技能包和当前工作空间已安装的 SkillHub 技能。 */
export function useMySkills(enabled = true) {
  const hub = useMeta().data?.hubSkills ?? [];
  return useQuery({
    queryKey: qk.apps({ type: '开发skill', sort: 'updated' }),
    queryFn: () => api.listApps({ type: '开发skill', sort: 'updated' }),
    enabled,
    staleTime: 30_000,
    select: (list) => [
      ...list.filter((a) => a.currentVersion).map((a) => ({ id: a.id, name: a.name, desc: a.description })),
      ...hub.map((s) => ({ id: s.id, name: s.title || s.name, desc: s.description })),
    ],
  });
}

/** 输入框光标前的「@查询」：在词边界输入 @ 时触发。 */
export function mentionAt(text: string, caret: number): { start: number; query: string } | null {
  const before = text.slice(0, caret);
  const m = /(^|\s)@([^\s@]{0,20})$/.exec(before);
  if (!m) return null;
  return { start: before.length - m[2].length - 1, query: m[2] };
}

export function useAnchoredPosition(anchor: RefObject<HTMLElement | null>, open: boolean, width: number, height: number) {
  const [pos, setPos] = useState<{ left: number; top: number }>({ left: 0, top: 0 });
  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const r = anchor.current?.getBoundingClientRect();
      if (!r) return;
      const below = r.bottom + 8 + height <= window.innerHeight - 8;
      const rawTop = below ? r.bottom + 8 : r.top - height - 8;
      setPos({
        left: Math.max(8, Math.min(r.left, window.innerWidth - width - 8)),
        top: Math.max(8, Math.min(rawTop, window.innerHeight - height - 8)),
      });
    };
    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    const ro = new ResizeObserver(place);
    if (anchor.current) ro.observe(anchor.current);
    return () => {
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
      ro.disconnect();
    };
  }, [open, anchor, width, height]);
  return pos;
}

export function useOutside(open: boolean, refs: RefObject<HTMLElement | null>[], onClose: () => void) {
  const cb = useRef(onClose);
  cb.current = onClose;
  useEffect(() => {
    if (!open) return;
    const down = (e: PointerEvent) => {
      const t = e.target instanceof Node ? e.target : null;
      if (!t || !refs.some((r) => r.current?.contains(t))) cb.current();
    };
    const onScroll = (e: Event) => {
      // 弹层内部列表滚动不关闭；外部页面滚动时自动收起，避免浮层脱离按钮漂在屏幕顶部
      const t = e.target instanceof Node ? e.target : null;
      if (!t || !refs.some((r) => r.current?.contains(t))) cb.current();
    };
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        cb.current();
      }
    };
    document.addEventListener('pointerdown', down, true);
    window.addEventListener('scroll', onScroll, true);
    document.addEventListener('keydown', key, true);
    return () => {
      document.removeEventListener('pointerdown', down, true);
      window.removeEventListener('scroll', onScroll, true);
      document.removeEventListener('keydown', key, true);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
}

interface SkillPickerProps {
  open: boolean;
  /** 弹层锚点（技能按钮或输入框） */
  anchor: RefObject<HTMLElement | null>;
  picked: SkillRef[];
  /** 由 @ 触发时的查询文字（受控）；按钮打开时为 null，使用弹层里的搜索框 */
  mentionQuery: string | null;
  onPick: (s: SkillRef) => void;
  onClose: () => void;
}

/** 技能选择弹层（role=dialog，aria-label=技能）。键盘：↑↓ 选择、Enter 添加、Esc 关闭。 */
export function SkillPicker({ open, anchor, picked, mentionQuery, onPick, onClose }: SkillPickerProps) {
  const box = useRef<HTMLDivElement>(null);
  const search = useRef<HTMLInputElement>(null);
  const [q, setQ] = useState('');
  const [tab, setTab] = useState<'mine' | 'builtin'>('mine');
  const [active, setActive] = useState(0);
  const [hubOpen, setHubOpen] = useState(false);
  const skills = useMySkills(open);
  const meta = useMeta().data;
  const builtin = meta?.builtinSkills ?? [];
  const query = (mentionQuery ?? q).trim().toLowerCase();
  const list = useMemo(() => (skills.data ?? []).filter((s) => !query || s.name.toLowerCase().includes(query)), [skills.data, query]);
  const pos = useAnchoredPosition(anchor, open, 320, 340);
  useOutside(open && !hubOpen, [box, anchor], onClose);

  useEffect(() => {
    if (!open) {
      setHubOpen(false);
      return;
    }
    setActive(0);
    if (mentionQuery === null) {
      setQ('');
      requestAnimationFrame(() => search.current?.focus());
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  useEffect(() => setActive(0), [query]);

  const choose = (i: number) => {
    const s = list[i];
    if (!s) return;
    if (picked.some((p) => p.id === s.id)) return toast('这个技能已经添加了。');
    if (picked.length >= MAX_SKILLS) return toast(`最多添加 ${MAX_SKILLS} 个技能。`);
    onPick({ id: s.id, name: s.name });
  };

  // @ 触发时键盘事件来自输入框，由这里统一处理
  useEffect(() => {
    if (!open || mentionQuery === null) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.isComposing) return;
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        setActive((a) => Math.max(0, Math.min(list.length - 1, a + (e.key === 'ArrowDown' ? 1 : -1))));
      } else if ((e.key === 'Enter' || e.key === 'Tab') && list.length) {
        e.preventDefault();
        e.stopPropagation();
        choose(active);
      }
    };
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  });

  if (!open) return null;
  return createPortal(
    <div className="skill-picker" role="dialog" aria-label="技能" ref={box} style={{ ...pos, visibility: hubOpen ? 'hidden' : 'visible' }}>
      {mentionQuery === null ? (
        <label className="skill-picker-search">
          <Icon name="search" />
          <input
            ref={search}
            placeholder="搜索技能"
            aria-label="搜索技能"
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                e.preventDefault();
                setActive((a) => Math.max(0, Math.min(list.length - 1, a + (e.key === 'ArrowDown' ? 1 : -1))));
              } else if (e.key === 'Enter') {
                e.preventDefault();
                choose(active);
              }
            }}
          />
        </label>
      ) : (
        <div className="skill-picker-mention">@{mentionQuery}</div>
      )}
      <div className="skill-picker-tabs" role="tablist">
        <button type="button" role="tab" aria-selected={tab === 'mine'} className={tab === 'mine' ? 'active' : ''} onClick={() => setTab('mine')}>
          我的技能
        </button>
        <button type="button" role="tab" aria-selected={tab === 'builtin'} className={tab === 'builtin' ? 'active' : ''} onClick={() => setTab('builtin')}>
          内置技能
        </button>
      </div>
      <div className="skill-picker-count">已添加 {picked.length} 项技能</div>
      {tab === 'builtin' ? (
        <ul className="skill-picker-list skill-picker-builtin" aria-label="内置技能">
          <li className="skill-picker-note">织页会根据需求自动加载，无需手动添加</li>
          {builtin
            .filter((b) => !query || b.title.toLowerCase().includes(query) || b.name.includes(query))
            .map((b) => (
              <li key={b.name} title={b.description}>
                <Icon name="spark" />
                <span>
                  <b>{b.title}</b>
                  <small>{b.description}</small>
                </span>
                <em className="skill-picker-auto">自动</em>
              </li>
            ))}
        </ul>
      ) : null}
      <ul className="skill-picker-list" role="listbox" aria-label="我的技能" hidden={tab !== 'mine'}>
        {skills.isLoading ? <li className="skill-picker-empty">正在读取技能…</li> : null}
        {!skills.isLoading && !list.length ? (
          <li className="skill-picker-empty">{skills.data?.length ? '没有匹配的技能' : '还没有技能包，可以在「创造」里选择「开发skill」生成一个'}</li>
        ) : null}
        {list.map((s, i) => {
          const on = picked.some((p) => p.id === s.id);
          return (
            <li
              key={s.id}
              role="option"
              aria-selected={on}
              className={`${i === active ? 'active' : ''} ${on ? 'picked' : ''}`}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => choose(i)}
            >
              <Icon name="book" />
              <span>
                <b>{s.name}</b>
                {s.desc ? <small>{s.desc}</small> : null}
              </span>
              {on ? <Icon name="check" /> : null}
            </li>
          );
        })}
      </ul>
      <button
        type="button"
        className="skill-picker-more"
        onClick={() => meta?.skillhub ? setHubOpen(true) : toast('SkillHub 还没有配置服务地址，当前可以先使用自己的技能包。')}
      >
        SkillHub 技能市场
      </button>
      {hubOpen ? <SkillHubMarket picked={picked} onPick={onPick} onClose={() => setHubOpen(false)} /> : null}
    </div>,
    document.body,
  );
}

export function SkillChips({ skills, onRemove }: { skills: SkillRef[]; onRemove: (id: string) => void }) {
  if (!skills.length) return null;
  return (
    <>
      {skills.map((k) => (
        <span key={'s' + k.id} className="skill-chip">
          <Icon name="book" />
          <span className="chip-label" title={k.name}>技能 · {k.name}</span>
          <button type="button" aria-label={`移除技能 ${k.name}`} onClick={() => onRemove(k.id)}>
            <Icon name="close" />
          </button>
        </span>
      ))}
    </>
  );
}

// ---------------- 模型与额度 ----------------

type ModelInfo = Meta['models'][number] & { desc?: string; cost?: number };
const COST = ['', '便宜', '较贵', '超贵'];

/** 网关模型名常带「供应商/」前缀和日期版本后缀（如 csot-sz-cloud/DeepSeek-V4-Flash-0731），界面只显示短名 */
export function shortModelLabel(label: string) {
  const base = label.includes('/') ? label.slice(label.lastIndexOf('/') + 1) : label;
  return base.replace(/-\d{4}$/, '');
}

/** 可选模型：五星模式下是用户在五星AI岛配置的模型（含「五星默认」和平台免费模型），否则是平台统一模型 */
export function useModelList(): { fivestar: boolean; models: ModelInfo[]; defaultId: string | null } {
  const meta = useMeta().data;
  const fivestar = !!meta?.fivestar;
  const q = useQuery({ queryKey: ['llm-models'], queryFn: api.llmModels, enabled: fivestar, staleTime: 60_000, retry: false });
  return {
    fivestar,
    models: fivestar ? (q.data?.models ?? []) : (meta?.models ?? []),
    defaultId: fivestar ? (q.data?.defaultId ?? null) : 'auto',
  };
}

/** 当前模型是否付费；五星之外一律免费 */
export function billingOf(models: { id: string; billingType?: 'FREE' | 'PAID' }[], id: string): 'FREE' | 'PAID' | undefined {
  return models.find((m) => m.id === id)?.billingType;
}

/** 1234567 → 123.5 万 */
const tokens = (n: number) => (n >= 10_000 ? `${(n / 10_000).toFixed(n >= 1_000_000 ? 0 : 1)} 万` : n.toLocaleString('zh-CN'));

function resetLabel(iso?: string | null) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return `${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')} 重置`;
}

/** 模型弹层里的个人额度（五星AI岛套餐） */
function QuotaRow() {
  const q = useQuery({ queryKey: ['llm-quota'], queryFn: api.llmQuota, staleTime: 30_000, retry: false });
  if (q.isLoading) return <div className="model-quota is-muted">正在查询额度…</div>;
  if (q.error) return <div className="model-quota is-muted">{errorMessage(q.error)}</div>;
  const d = q.data;
  if (!d) return null;
  if (d.status === 'ENROLLING' || d.limitTokens == null)
    return <div className="model-quota is-muted">{d.packageName ?? '默认套餐'}建档中，稍后再看额度</div>;
  const limit = Math.max(1, d.limitTokens);
  const remaining = Math.max(0, d.remainingTokens ?? limit - (d.usedTokens ?? 0));
  const pct = Math.min(100, Math.round(((limit - remaining) / limit) * 100));
  const level = remaining <= 0 ? 'is-out' : pct >= 85 ? 'is-low' : '';
  return (
    <div className={`model-quota ${level}`} title="额度以五星AI岛为准，和星Claw、星域工作台共用">
      <div className="model-quota-head">
        <span>{d.packageName ?? '套餐'} · 本周额度</span>
        <b>{remaining <= 0 ? '已用完' : `剩余 ${tokens(remaining)}`}</b>
      </div>
      <i className="model-quota-bar" aria-hidden="true">
        <em style={{ width: `${pct}%` }} />
      </i>
      <small>
        已用 {tokens(limit - remaining)} / {tokens(limit)}
        {resetLabel(d.resetAt) ? ` · ${resetLabel(d.resetAt)}` : ''}
      </small>
    </div>
  );
}

interface ModelPopoverProps {
  models: ModelInfo[];
  modes: Mode[];
  model: string;
  mode: Mode;
  onModel: (id: string) => void;
  onMode: (m: Mode) => void;
  /** 只读展示（工作台：应用已有的模型与模式） */
  className?: string;
}

/** 「模型」按钮 + 弹层（role=dialog，aria-label=模型）。 */
export function ModelModeButton({ models, model, onModel, className = 'model-button' }: ModelPopoverProps) {
  const [open, setOpen] = useState(false);
  const [sub, setSub] = useState(false);
  const btn = useRef<HTMLButtonElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const pos = useAnchoredPosition(btn, open, 280, 170);
  useOutside(open, [box, btn], () => {
    setOpen(false);
    setSub(false);
  });
  const { fivestar, models: fivestarModels } = useModelList();
  const source = fivestar ? fivestarModels : models;
  const list: ModelInfo[] = source.length ? source : [{ id: 'auto', label: fivestar ? '五星默认' : 'Auto' }];
  const current = list.find((m) => m.id === model) ?? list[0];
  const flipSub = pos.left + 280 + 312 > window.innerWidth;
  return (
    <>
      <button
        ref={btn}
        type="button"
        className={className}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => {
          setOpen((v) => !v);
          setSub(false);
        }}
      >
        <span className="model-orb">✦</span>
        <span className="model-label" title={current.label}>{shortModelLabel(current.label)}</span>
        <Icon name="down" />
      </button>
      {open
        ? createPortal(
            <div className="model-pop" role="dialog" aria-label="模型" ref={box} style={pos}>
              {list.length > 1 ? (
                <button type="button" className="model-pop-row" aria-haspopup="menu" aria-expanded={sub} onClick={() => setSub((v) => !v)} onMouseEnter={() => setSub(true)}>
                  <span>选择模型</span>
                  {current.billingType === 'PAID' ? <span className="model-paid">付费</span> : null}
                  <span className="model-pop-value" title={current.label}>
                    {shortModelLabel(current.label)} <i aria-hidden="true">›</i>
                  </span>
                </button>
              ) : (
                // 只有一个可用模型：如实显示，不放一个点开也没得选的子菜单
                <div className="model-pop-row is-static" title={current.desc}>
                  <span>模型</span>
                  <span className="model-pop-value" title={current.label}>
                    {shortModelLabel(current.label)}
                    {current.desc ? <small>{current.desc}</small> : null}
                  </span>
                </div>
              )}
              {fivestar ? <QuotaRow /> : null}
              {sub && list.length > 1 ? (
                <div className={`model-pop-sub ${flipSub ? 'is-flip' : ''}`} role="menu" aria-label="选择模型">
                  {list.map((m) => {
                    const cost = Math.max(0, Math.min(3, m.cost ?? 0));
                    return (
                      <button
                        key={m.id}
                        type="button"
                        role="menuitemradio"
                        aria-checked={m.id === model}
                        className={m.id === model ? 'on' : ''}
                        onClick={() => {
                          if (m.billingType === 'PAID') toast('该模型会计入你在五星AI岛的个人额度，超出本周额度会暂停使用');
                          onModel(m.id);
                          setSub(false);
                        }}
                      >
                        <span className="model-pop-name">
                          <b title={m.label}>{shortModelLabel(m.label)}</b>
                          <small>{m.desc ?? (m.id === 'auto' ? '根据需求自动选择合适的模型（优先免费）' : '')}</small>
                        </span>
                        {m.billingType === 'PAID' ? (
                          <span className="model-paid" title="使用该模型会扣减你在五星AI岛的个人额度">付费</span>
                        ) : null}
                        {cost ? (
                          <span className="model-cost" aria-label={`费用：${COST[cost]}`}>
                            {'●'.repeat(cost)}
                            <i>{'●'.repeat(3 - cost)}</i> {COST[cost]}
                          </span>
                        ) : null}
                        {m.id === model ? <Icon name="check" /> : null}
                      </button>
                    );
                  })}
                  <div className="model-pop-more" aria-disabled="true">
                    {fivestar ? '模型在五星AI岛里配置和管理' : '更多模型即将接入'}
                  </div>
                </div>
              ) : null}
            </div>,
            document.body,
          )
        : null}
    </>
  );
}

/** 润色提示词：把粗略需求改写得更清楚；只在有内容时可用 */
export function PolishButton({ value, onPolished, className = 'polish-button' }: { value: string; onPolished: (text: string) => void; className?: string }) {
  const [busy, setBusy] = useState(false);
  const disabled = !value.trim() || busy;
  const label = busy ? '润色中…' : '润色';
  const run = async () => {
    if (disabled) return;
    setBusy(true);
    try {
      const r = await api.polish(value);
      onPolished(r.text);
      toast('已润色，可以再改改');
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <span className="polish-wrap">
      <button
        type="button"
        className={className}
        aria-label={label}
        disabled={disabled}
        onClick={() => void run()}
      >
        <Icon name={busy ? 'refresh' : 'spark'} className={busy ? 'spinning' : ''} />
      </button>
      <span className="polish-tip" role="tooltip" aria-hidden="true">
        {label}
      </span>
    </span>
  );
}
