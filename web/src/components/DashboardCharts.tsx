import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Icon, type IconName } from '../lib/icons';
import { niceTicks } from './TrendChart';

export interface SeriesPoint {
  date: string;
  primary: number;
  secondary?: number;
}

export interface DonutSlice {
  key: string;
  label: string;
  value: number;
  color: string;
}

export interface BarItem {
  key: string;
  label: string;
  value: number;
  extra?: string;
  color?: string;
}

export const CHART_PALETTE = ['#7953dc', '#2f9e8f', '#d9822b', '#c7527c', '#4a7bd0', '#7a8298', '#9b6fd6', '#56a36e'];

export function fmtNum(n: number | null | undefined): string {
  return (n ?? 0).toLocaleString('zh-CN');
}

export function fmtCompact(n: number | null | undefined): string {
  const v = n ?? 0;
  if (Math.abs(v) >= 1_000_000) return (v / 1_000_000).toFixed(2).replace(/\.00$/, '') + 'M';
  if (Math.abs(v) >= 10_000) return (v / 10_000).toFixed(1).replace(/\.0$/, '') + '万';
  return v.toLocaleString('zh-CN');
}

export function fmtBytes(bytes: number | null | undefined): string {
  const b = bytes ?? 0;
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  if (b < 1024 * 1024 * 1024) return `${(b / (1024 * 1024)).toFixed(2)} MB`;
  return `${(b / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

export function fmtDurationMs(ms: number | null | undefined): string {
  const v = ms ?? 0;
  if (v <= 0) return '0 ms';
  if (v < 1000) return `${Math.round(v)} ms`;
  if (v < 60_000) return `${(v / 1000).toFixed(1)} s`;
  const m = Math.floor(v / 60_000);
  const s = Math.round((v % 60_000) / 1000);
  return `${m}m ${s}s`;
}

const H = 240;
const PAD = { top: 24, right: 16, bottom: 28, left: 48 };
const mmdd = (d: string) => d.slice(5, 10);

/** 多维趋势图：支持主/副双序列、折线/柱状图切换、悬停十字线与可展开数据表。 */
export function MultiTrendChart({
  data,
  kind,
  primaryLabel,
  secondaryLabel,
  primaryColor = '#7953dc',
  secondaryColor = '#2f9e8f',
  unit = '',
}: {
  data: SeriesPoint[];
  kind: 'line' | 'bar';
  primaryLabel: string;
  secondaryLabel?: string;
  primaryColor?: string;
  secondaryColor?: string;
  unit?: string;
}) {
  const wrap = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(640);
  const [active, setActive] = useState<number | null>(null);
  const [showTable, setShowTable] = useState(false);
  const tableId = useId();

  useLayoutEffect(() => {
    const el = wrap.current;
    if (!el) return;
    const measure = () => setWidth(Math.max(240, Math.floor(el.clientWidth)));
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  useEffect(() => setActive(null), [kind, data, primaryLabel]);

  const n = data.length;
  const max = Math.max(0, ...data.map((d) => Math.max(d.primary, d.secondary ?? 0)));
  const ticks = useMemo(() => niceTicks(max), [max]);
  const top = ticks[ticks.length - 1];
  const plotW = Math.max(120, width - PAD.left - PAD.right);
  const plotH = H - PAD.top - PAD.bottom;
  const slot = n ? plotW / n : plotW;
  const x = (i: number) => (kind === 'bar' || n === 1 ? PAD.left + slot * (i + 0.5) : PAD.left + (n > 1 ? (plotW * i) / (n - 1) : plotW / 2));
  const y = (v: number) => PAD.top + plotH - (v / top) * plotH;
  const base = PAD.top + plotH;
  const every = Math.max(1, Math.ceil((n * 44) / plotW));
  const hasSecondary = Boolean(secondaryLabel);
  const barGroupW = Math.max(4, Math.min(26, slot * 0.68, slot - 2));
  const singleBarW = hasSecondary ? Math.max(2, (barGroupW - 2) / 2) : barGroupW;

  const linePath = (getter: (d: SeriesPoint) => number) =>
    data.map((d, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(getter(d)).toFixed(1)}`).join('');

  const pLine = linePath((d) => d.primary);
  const pArea = n > 1 ? `${pLine}L${x(n - 1).toFixed(1)},${base}L${x(0).toFixed(1)},${base}Z` : '';
  const sLine = hasSecondary ? linePath((d) => d.secondary ?? 0) : '';

  const barPath = (cx: number, bw: number, v: number) => {
    const bx = cx - bw / 2;
    const by = y(v);
    const h = base - by;
    if (h <= 0) return '';
    const r = Math.min(3, h, bw / 2);
    return `M${bx},${base}V${by + r}Q${bx},${by} ${bx + r},${by}H${bx + bw - r}Q${bx + bw},${by} ${bx + bw},${by + r}V${base}Z`;
  };

  const tip = active !== null ? data[active] : null;
  const tipTop =
    active !== null
      ? Math.min(H - 84, Math.max(6, y(Math.max(data[active].primary, data[active].secondary ?? 0)) - 22))
      : 0;
  const tipStyle =
    active !== null
      ? x(active) > width * 0.56
        ? { right: Math.max(8, width - x(active) + 12), top: tipTop, transform: 'none' }
        : { left: Math.max(8, x(active) + 12), top: tipTop, transform: 'none' }
      : undefined;

  return (
    <div className="dash-trend">
      <div className="dash-trend-legend">
        <span><i style={{ background: primaryColor }} />{primaryLabel}</span>
        {secondaryLabel ? <span><i style={{ background: secondaryColor }} />{secondaryLabel}</span> : null}
      </div>
      <div className="trend-chart-plot" ref={wrap}>
        <svg width={width} height={H} role="img" aria-label={`${primaryLabel}趋势，共 ${n} 天，峰值 ${fmtNum(max)}`} onMouseLeave={() => setActive(null)}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={PAD.left} x2={width - PAD.right} y1={y(t)} y2={y(t)} stroke="#ece9f4" strokeWidth={1} strokeDasharray={t === 0 ? undefined : '3 3'} shapeRendering="crispEdges" />
              <text x={PAD.left - 10} y={y(t)} dy="0.32em" textAnchor="end" fontSize={11} fill="#6c667d" style={{ fontVariantNumeric: 'tabular-nums' }}>
                {fmtCompact(t)}
              </text>
            </g>
          ))}
          {data.map((d, i) =>
            i % every === 0 ? (
              <text key={d.date} x={x(i)} y={H - 8} textAnchor="middle" fontSize={11} fill="#6c667d">
                {mmdd(d.date)}
              </text>
            ) : null,
          )}
          {kind === 'line' ? (
            <>
              {pArea ? <path d={pArea} fill={primaryColor} fillOpacity={0.09} /> : null}
              {n > 1 ? <path d={pLine} fill="none" stroke={primaryColor} strokeWidth={2.2} strokeLinejoin="round" strokeLinecap="round" /> : null}
              {hasSecondary && n > 1 ? (
                <path d={sLine} fill="none" stroke={secondaryColor} strokeWidth={2} strokeDasharray="4 3" strokeLinejoin="round" strokeLinecap="round" />
              ) : null}
              {n === 1 ? <circle cx={x(0)} cy={y(data[0].primary)} r={4.5} fill={primaryColor} /> : null}
              {active !== null ? (
                <>
                  <line x1={x(active)} x2={x(active)} y1={PAD.top} y2={base} stroke="#8f8c9c" strokeWidth={1} shapeRendering="crispEdges" />
                  <circle cx={x(active)} cy={y(data[active].primary)} r={4.5} fill="#fff" stroke={primaryColor} strokeWidth={2.2} />
                  {hasSecondary ? (
                    <circle cx={x(active)} cy={y(data[active].secondary ?? 0)} r={4} fill="#fff" stroke={secondaryColor} strokeWidth={2} />
                  ) : null}
                </>
              ) : null}
            </>
          ) : (
            data.map((d, i) => {
              const cx1 = hasSecondary ? x(i) - singleBarW / 2 - 1 : x(i);
              const cx2 = x(i) + singleBarW / 2 + 1;
              const op = active === null || active === i ? 1 : 0.45;
              return (
                <g key={d.date}>
                  <path d={barPath(cx1, singleBarW, d.primary)} fill={primaryColor} fillOpacity={op} />
                  {hasSecondary ? <path d={barPath(cx2, singleBarW, d.secondary ?? 0)} fill={secondaryColor} fillOpacity={op} /> : null}
                </g>
              );
            })
          )}
          {data.map((d, i) => {
            const w = kind === 'bar' || n === 1 ? slot : n > 1 ? plotW / (n - 1) : plotW;
            return (
              <rect
                key={'hit-' + d.date}
                className="trend-hit"
                x={x(i) - w / 2}
                y={PAD.top}
                width={Math.max(w, 1)}
                height={plotH}
                fill="transparent"
                tabIndex={0}
                role="graphics-symbol"
                aria-label={`${d.date} ${primaryLabel} ${fmtNum(d.primary)}${secondaryLabel ? `，${secondaryLabel} ${fmtNum(d.secondary)}` : ''}`}
                onMouseEnter={() => setActive(i)}
                onFocus={() => setActive(i)}
                onBlur={() => setActive(null)}
              />
            );
          })}
        </svg>
        {tip ? (
          <div className="trend-tooltip" style={tipStyle} role="status">
            <small>{tip.date}</small>
            <div>
              {primaryLabel}: <b>{fmtNum(tip.primary)}</b>{unit}
            </div>
            {secondaryLabel ? (
              <div>
                {secondaryLabel}: <b>{fmtNum(tip.secondary ?? 0)}</b>{unit}
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
      <div className="dash-trend-foot">
        <button type="button" className="text-button trend-table-toggle" aria-expanded={showTable} aria-controls={tableId} onClick={() => setShowTable((v) => !v)}>
          {showTable ? '收起每日明细表' : `展开每日明细表（${n} 天）`}
        </button>
      </div>
      <div className="dash-table-wrap" hidden={!showTable}>
        <table id={tableId} className="dash-table">
          <thead>
            <tr>
              <th scope="col">日期</th>
              <th scope="col">{primaryLabel}</th>
              {secondaryLabel ? <th scope="col">{secondaryLabel}</th> : null}
            </tr>
          </thead>
          <tbody>
            {data.map((d) => (
              <tr key={d.date}>
                <td>{d.date}</td>
                <td>{fmtNum(d.primary)}{unit}</td>
                {secondaryLabel ? <td>{fmtNum(d.secondary ?? 0)}{unit}</td> : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function DashPanelHead({
  icon,
  tone = 'violet',
  title,
  sub,
  right,
}: {
  icon: IconName;
  tone?: 'violet' | 'teal' | 'indigo' | 'amber' | 'rose' | 'slate';
  title: ReactNode;
  sub?: ReactNode;
  right?: ReactNode;
}) {
  return (
    <header className="dash-panel-head compact">
      <div className="dash-head-with-icon">
        <span className={`dash-head-icon ${tone}`} aria-hidden="true">
          <Icon name={icon} />
        </span>
        <div className="dash-head-copy">
          <h3>{title}</h3>
          {sub ? <p>{sub}</p> : null}
        </div>
      </div>
      {right ? <div className="dash-head-right">{right}</div> : null}
    </header>
  );
}

export function DashEmptyBox({
  icon = 'chart',
  title,
  hint,
}: {
  icon?: IconName;
  title: string;
  hint?: string;
}) {
  return (
    <div className="dash-empty-box">
      <span className="dash-empty-icon" aria-hidden="true">
        <Icon name={icon} />
      </span>
      <b>{title}</b>
      {hint ? <small>{hint}</small> : null}
    </div>
  );
}

/** 环形分布图：SVG 圆环 + 中心总数 + 可悬停图例。 */
export function DonutChart({
  slices,
  centerLabel = '总计',
  emptyText = '暂无数据',
  emptyIcon = 'chart',
}: {
  slices: DonutSlice[];
  centerLabel?: string;
  emptyText?: string;
  emptyIcon?: IconName;
}) {
  const [hover, setHover] = useState<string | null>(null);
  const total = slices.reduce((s, x) => s + x.value, 0);
  if (total <= 0) {
    return <DashEmptyBox icon={emptyIcon} title={emptyText} />;
  }
  const r = 46;
  const c = 2 * Math.PI * r;
  let acc = 0;
  const activeSlice = hover ? slices.find((s) => s.key === hover) : null;

  return (
    <div className="dash-donut-wrap">
      <svg className="dash-donut" viewBox="0 0 124 124" role="img" aria-label={`${centerLabel} ${fmtNum(total)}`}>
        <circle cx="62" cy="62" r={r} fill="none" stroke="#f1eff6" strokeWidth="16" />
        {slices
          .filter((s) => s.value > 0)
          .map((s) => {
            const len = (s.value / total) * c;
            const offset = -acc;
            acc += len;
            const dimmed = hover !== null && hover !== s.key;
            return (
              <circle
                key={s.key}
                cx="62"
                cy="62"
                r={r}
                fill="none"
                stroke={s.color}
                strokeWidth={hover === s.key ? 19 : 16}
                strokeDasharray={`${Math.max(0, len - 1.5)} ${c}`}
                strokeDashoffset={offset}
                transform="rotate(-90 62 62)"
                opacity={dimmed ? 0.35 : 1}
                style={{ transition: 'opacity .15s, stroke-width .15s', cursor: 'pointer' }}
                onMouseEnter={() => setHover(s.key)}
                onMouseLeave={() => setHover(null)}
              />
            );
          })}
        <text x="62" y="57" textAnchor="middle" fontSize="11" fill="#5e586e">
          {activeSlice ? activeSlice.label.slice(0, 6) : centerLabel}
        </text>
        <text x="62" y="74" textAnchor="middle" fontSize="15" fontWeight="600" fill="#1f1c29" style={{ fontVariantNumeric: 'tabular-nums' }}>
          {fmtCompact(activeSlice ? activeSlice.value : total)}
        </text>
      </svg>
      <ul className="dash-legend">
        {slices.map((s) => {
          const pct = total > 0 ? Math.round((s.value / total) * 1000) / 10 : 0;
          return (
            <li
              key={s.key}
              className={hover === s.key ? 'is-active' : ''}
              onMouseEnter={() => setHover(s.key)}
              onMouseLeave={() => setHover(null)}
            >
              <i style={{ background: s.color }} />
              <span className="dash-legend-label">{s.label}</span>
              <span className="dash-legend-val">
                <b>{fmtNum(s.value)}</b>
                <small>{pct}%</small>
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** 水平比例条列表：展示维度分布、数量、占比与附加说明。 */
export function BreakdownBars({
  items,
  emptyText = '暂无记录',
  emptyIcon = 'chart',
  emptyHint,
}: {
  items: BarItem[];
  emptyText?: string;
  emptyIcon?: IconName;
  emptyHint?: string;
}) {
  const total = items.reduce((s, x) => s + x.value, 0);
  if (!items.length || total <= 0) {
    return <DashEmptyBox icon={emptyIcon} title={emptyText} hint={emptyHint} />;
  }
  const max = Math.max(...items.map((i) => i.value), 1);
  return (
      <ul className="dash-bars">
      {items.map((it, idx) => {
        const share = total > 0 ? Math.round((it.value / total) * 1000) / 10 : 0;
        const w = Math.max(3, (it.value / max) * 100);
        const color = it.color ?? CHART_PALETTE[idx % CHART_PALETTE.length];
        return (
          <li key={it.key}>
            <div className="dash-bar-head">
              <span className="dash-bar-label" title={it.label}>{it.label}</span>
              <span className="dash-bar-num">
                {it.extra ? <small>{it.extra}</small> : null}
                <b>{fmtNum(it.value)}</b>
                <em>{share}%</em>
              </span>
            </div>
            <div className="dash-bar-track">
              <i style={{ width: `${w}%`, background: color }} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}

/** 分段占比条（如任务成功/失败/取消/进行中）。 */
export function SegmentedStrip({ segments }: { segments: { key: string; label: string; value: number; color: string }[] }) {
  const total = segments.reduce((s, x) => s + x.value, 0);
  if (total <= 0) return <div className="dash-strip-empty">暂无任务记录</div>;
  return (
    <div className="dash-strip-wrap">
      <div className="dash-strip" role="img" aria-label={segments.map((s) => `${s.label} ${s.value}`).join('，')}>
        {segments
          .filter((s) => s.value > 0)
          .map((s) => (
            <i key={s.key} style={{ width: `${(s.value / total) * 100}%`, background: s.color }} title={`${s.label}: ${fmtNum(s.value)}`} />
          ))}
      </div>
      <div className="dash-strip-legend">
        {segments.map((s) => (
          <span key={s.key}>
            <i style={{ background: s.color }} />
            {s.label} <b>{fmtNum(s.value)}</b> ({Math.round((s.value / total) * 1000) / 10}%)
          </span>
        ))}
      </div>
    </div>
  );
}

/** 迷你趋势线（嵌入 KPI 卡片底部，提供直观走势）。 */
export function Sparkline({ values, color = '#7953dc' }: { values: number[]; color?: string }) {
  const pts = values.length > 1 ? values : [0, values[0] ?? 0];
  const w = 120;
  const h = 28;
  const max = Math.max(...pts, 1);
  const min = Math.min(...pts, 0);
  const span = Math.max(max - min, 1);
  const coords = pts.map((v, i) => {
    const x = (i / (pts.length - 1)) * w;
    const y = h - 3 - ((v - min) / span) * (h - 6);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });
  const line = `M${coords.join('L')}`;
  const area = `${line}L${w},${h}L0,${h}Z`;
  return (
    <svg className="dash-spark" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" aria-hidden="true">
      <path d={area} fill={color} fillOpacity={0.12} />
      <path d={line} fill="none" stroke={color} strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/** 全链路转化漏斗：优先展示后端同口径转化标注，避免独立渠道相除出现 >100% 的误导比率。 */
export function ConversionFunnel({ steps }: { steps: { key: string; label: string; count: number; rateLabel?: string | null }[] }) {
  const max = Math.max(...steps.map((s) => s.count), 1);
  if (steps.every((s) => s.count === 0)) {
    return <DashEmptyBox icon="chart" title="当前范围暂无漏斗转化数据" />;
  }
  return (
    <ol className="dash-funnel">
      {steps.map((s, idx) => {
        const w = s.count === 0 ? 2 : Math.max(6, (s.count / max) * 100);
        const prev = idx > 0 ? steps[idx - 1].count : 0;
        const fallbackRate =
          s.rateLabel === undefined && idx > 0 && prev > 0 && s.count <= prev
            ? `较上步 ${Math.round((s.count / prev) * 1000) / 10}%`
            : null;
        const rateText = s.rateLabel ?? fallbackRate;
        return (
          <li key={s.key}>
            <div className="dash-funnel-meta">
              <span className="dash-funnel-step">
                <em>{idx + 1}</em>
                {s.label}
              </span>
              <span className="dash-funnel-num">
                {rateText ? <small>{rateText}</small> : null}
                <b>{fmtNum(s.count)}</b>
              </span>
            </div>
            <div className="dash-funnel-track">
              <i style={{ width: `${w}%`, background: CHART_PALETTE[idx % CHART_PALETTE.length] }} />
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/** 24 小时活跃时段分布柱状图（Asia/Shanghai）。 */
export function HourlyBarChart({ data }: { data: { hour: number; total: number; succeeded: number; failed: number }[] }) {
  const [active, setActive] = useState<number | null>(null);
  const max = Math.max(...data.map((d) => d.total), 1);
  if (data.every((d) => d.total === 0)) {
    return <DashEmptyBox icon="history" title="当前窗口暂无分时段任务分布" />;
  }
  const peak = data.reduce((best, cur) => (cur.total > best.total ? cur : best), data[0]);
  const tip = active !== null ? data[active] : null;
  return (
    <div className="dash-hourly">
      <div className="dash-hourly-head">
        <span>高峰时段：<b>{String(peak.hour).padStart(2, '0')}:00–{String((peak.hour + 1) % 24).padStart(2, '0')}:00</b>（{fmtNum(peak.total)} 次）</span>
        {tip ? (
          <span className="dash-hourly-tip">
            {String(tip.hour).padStart(2, '0')}:00 · 总计 <b>{fmtNum(tip.total)}</b>（成 {tip.succeeded} / 败 {tip.failed}）
          </span>
        ) : (
          <span className="dash-muted">悬停柱条查看分时段成败明细</span>
        )}
      </div>
      <div className="dash-hourly-bars" role="img" aria-label="24 小时任务活跃分布">
        {data.map((d) => {
          const totalPct = d.total > 0 ? Math.max(6, (d.total / max) * 100) : 2;
          const failPct = d.total > 0 ? (d.failed / d.total) * 100 : 0;
          return (
            <button
              key={d.hour}
              type="button"
              className={`dash-hr-col ${active === d.hour ? 'active' : ''}`}
              aria-label={`${d.hour} 时总任务 ${d.total} 次，失败 ${d.failed} 次`}
              onMouseEnter={() => setActive(d.hour)}
              onFocus={() => setActive(d.hour)}
              onMouseLeave={() => setActive(null)}
            >
              <span className="dash-hr-bar-wrap">
                <span className="dash-hr-bar" style={{ height: `${totalPct}%`, opacity: d.total === 0 ? 0.25 : 1 }}>
                  {failPct > 0 ? <i className="dash-hr-fail" style={{ height: `${failPct}%` }} /> : null}
                </span>
              </span>
              <small>{d.hour % 3 === 0 ? String(d.hour).padStart(2, '0') : ''}</small>
            </button>
          );
        })}
      </div>
    </div>
  );
}
