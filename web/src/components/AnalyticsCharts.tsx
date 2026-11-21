import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { fmtCompact, fmtNum } from './DashboardCharts';
import { niceTicks } from './TrendChart';

function fmtMedianSec(sec?: number | null): string {
  if (sec === undefined || sec === null || Number.isNaN(sec) || sec <= 0) return '—';
  if (sec < 60) return `${Math.round(sec)}秒`;
  if (sec < 3600) {
    const m = Math.floor(sec / 60);
    const s = Math.round(sec % 60);
    return s > 0 ? `${m}分${s}秒` : `${m}分钟`;
  }
  const h = Math.floor(sec / 3600);
  const m = Math.round((sec % 3600) / 60);
  return m > 0 ? `${h}时${m}分` : `${h}小时`;
}

/** 吸顶章节锚点导览条：IntersectionObserver 滚动联动高亮当前章节 + 窗口摘要 + 回到顶部 */
export function StickyAnchorBar({
  items,
  meta,
}: {
  items: readonly [string, string, string][];
  meta?: ReactNode;
}) {
  const [activeId, setActiveId] = useState(items[0]?.[0] ?? '');

  useEffect(() => {
    const els = items
      .map(([id]) => document.getElementById(id))
      .filter((el): el is HTMLElement => Boolean(el));
    if (els.length === 0 || typeof IntersectionObserver === 'undefined') return;

    const io = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((e) => e.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (visible[0]?.target.id) {
          setActiveId(visible[0].target.id);
        }
      },
      { rootMargin: '-80px 0px -65% 0px', threshold: 0.01 },
    );
    els.forEach((el) => io.observe(el));
    return () => io.disconnect();
  }, [items]);

  return (
    <div className="xa-anchor-bar sticky" aria-label="章节快速导航">
      <div className="xa-anchor-links">
        {items.map(([id, n, lbl]) => (
          <a
            key={id}
            href={`#${id}`}
            className={`xa-anchor-pill ${activeId === id ? 'active' : ''}`}
            onClick={(e) => {
              e.preventDefault();
              setActiveId(id);
              document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }}
          >
            <i>{n}</i>
            <span>{lbl}</span>
          </a>
        ))}
      </div>
      <div className="xa-anchor-right">
        {meta ? <span className="xa-anchor-meta">{meta}</span> : null}
        <button
          type="button"
          className="xa-top-btn"
          title="回到顶部"
          onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
        >
          ↑ 顶部
        </button>
      </div>
    </div>
  );
}

/* ==========================================================================
   X-APM 标杆级企业数据可视化组件库（100% 真实数据驱动，纯 SVG 高清自适应）
   ========================================================================== */

/** 环比涨跌徽标：对比当前周期与上一等长周期 */
export function DeltaBadge({
  current,
  previous,
  invertColor = false,
}: {
  current: number;
  previous?: number | null;
  invertColor?: boolean;
}) {
  if (previous === undefined || previous === null) return null;
  if (previous === 0) {
    if (current === 0) return <span className="xa-delta flat">持平</span>;
    return <span className="xa-delta up">+{fmtCompact(current)}</span>;
  }
  const pct = ((current - previous) / previous) * 100;
  if (Math.abs(pct) < 0.05) return <span className="xa-delta flat">0.0%</span>;
  const up = pct > 0;
  const tone = invertColor ? (up ? 'down' : 'up') : up ? 'up' : 'down';
  return (
    <span className={`xa-delta ${tone}`} title={`上周期 ${fmtNum(previous)}`}>
      {up ? '▲' : '▼'} {Math.abs(pct).toFixed(1)}%
    </span>
  );
}

/** 表格内嵌近 7 日迷你柱状图 */
export function MicroSparkBar({
  values,
  color = '#2563eb',
  width = 58,
  height = 18,
}: {
  values?: number[] | null;
  color?: string;
  width?: number;
  height?: number;
}) {
  const arr = values && values.length > 0 ? values : [0, 0, 0, 0, 0, 0, 0];
  const max = Math.max(1, ...arr);
  const gap = 2;
  const bw = Math.max(3, (width - gap * (arr.length - 1)) / arr.length);
  return (
    <svg
      className="xa-micro-bars"
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      aria-label={`近 7 日趋势：${arr.join(', ')}`}
    >
      {arr.map((v, i) => {
        const h = v > 0 ? Math.max(2.5, (v / max) * (height - 2)) : 1.5;
        const x = i * (bw + gap);
        const y = height - h;
        return (
          <rect
            key={i}
            x={x}
            y={y}
            width={bw}
            height={h}
            rx={1.5}
            fill={v > 0 ? color : '#e2e8f0'}
            fillOpacity={v > 0 ? (i === arr.length - 1 ? 1 : 0.72) : 1}
          >
            <title>
              第 {i + 1} 天: {fmtNum(v)}
            </title>
          </rect>
        );
      })}
    </svg>
  );
}

/** 编号章节头（如 01 核心指标 / 02 趋势与日历） */
export function SectionHeading({
  id,
  num,
  title,
  sub,
  right,
}: {
  id?: string;
  num: string;
  title: string;
  sub?: string;
  right?: ReactNode;
}) {
  return (
    <div id={id} className="xa-section-head">
      <div className="xa-section-title">
        <span className="xa-section-num">{num}</span>
        <h2>{title}</h2>
        {sub ? <span className="xa-section-sub">{sub}</span> : null}
      </div>
      {right ? <div className="xa-section-right">{right}</div> : null}
    </div>
  );
}

/** 蓝底自动诊断洞察条 */
export function InsightBox({ label = '洞察', children }: { label?: string; children: ReactNode }) {
  return (
    <div className="xa-insight-box">
      <span className="xa-insight-tag">{label}</span>
      <div className="xa-insight-text">{children}</div>
    </div>
  );
}

/** 01. 对称梯形转化漏斗 + 右侧步骤明细表 + 自动诊断洞察 */
export interface TrapezoidStep {
  key: string;
  label: string;
  count: number;
  baseKey?: string | null;
  rateLabel?: string | null;
  rateText?: string | null;
  subNote?: string | null;
  medianSec?: number | null;
}

const FUNNEL_SHADES = ['#1d4ed8', '#2563eb', '#3b82f6', '#60a5fa', '#6366f1', '#8b5cf6'];
const FUNNEL_CHAIN_PARENT: Record<string, string> = {
  ready: 'apps',
  published: 'ready',
  visited: 'published',
};

export function TrapezoidFunnel({ steps }: { steps: TrapezoidStep[] }) {
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);

  const computed = useMemo(() => {
    const byKey = new Map(steps.map((s) => [s.key, s.count]));
    const maxCount = Math.max(1, ...steps.map((s) => s.count));
    const appsBase = byKey.get('apps') ?? 0;
    let worstDropIdx = -1;
    let worstDropCount = 0;

    const rows = steps.map((s, idx) => {
      const resolvedBaseKey = s.baseKey ?? FUNNEL_CHAIN_PARENT[s.key] ?? null;
      const baseCount = resolvedBaseKey ? (byKey.get(resolvedBaseKey) ?? 0) : 0;
      const stepRate = resolvedBaseKey && baseCount > 0
        ? Math.min(100, (s.count / baseCount) * 100)
        : s.key === 'apps'
          ? 100
          : null;
      const overallRate =
        s.key === 'conversations'
          ? null
          : appsBase > 0
            ? Math.min(100, (s.count / appsBase) * 100)
            : null;
      const drop = resolvedBaseKey && baseCount > 0 ? Math.max(0, baseCount - s.count) : 0;
      if (resolvedBaseKey && drop > worstDropCount && s.key !== 'cloud') {
        worstDropCount = drop;
        worstDropIdx = idx;
      }
      const note = s.subNote ?? s.rateText ?? s.rateLabel ?? null;
      return { ...s, resolvedBaseKey, baseCount, stepRate, overallRate, drop, note };
    });

    const widthRatio = (count: number, idx: number) => {
      const ratio = count / maxCount;
      const clamped = Math.max(0.32, Math.min(1, 0.28 + ratio * 0.72));
      return idx === 0 ? Math.max(clamped, 0.56) : clamped;
    };

    return { rows, worstDropIdx, worstDropCount, widthRatio };
  }, [steps]);

  if (steps.length === 0) return null;

  const svgW = 340;
  const rowH = 44;
  const gap = 4;
  const svgH = steps.length * (rowH + gap) + 4;
  const cx = svgW / 2;
  const worstRow = computed.worstDropIdx >= 0 ? computed.rows[computed.worstDropIdx] : null;
  const worstPrev = worstRow?.resolvedBaseKey
    ? computed.rows.find((r) => r.key === worstRow.resolvedBaseKey)
    : null;

  return (
    <div className="xa-funnel-layout">
      <div className="xa-funnel-svg-wrap">
        <svg viewBox={`0 0 ${svgW} ${svgH}`} className="xa-funnel-svg" role="img" aria-label="全链路梯形转化漏斗">
          {computed.rows.map((r, i) => {
            const topW = computed.widthRatio(r.count, i) * (svgW - 16);
            const next = computed.rows[i + 1];
            const botW = next
              ? computed.widthRatio(next.count, i + 1) * (svgW - 16)
              : Math.max(topW * 0.84, 108);
            const y0 = i * (rowH + gap) + 2;
            const y1 = y0 + rowH;
            const pts = [
              `${(cx - topW / 2).toFixed(1)},${y0}`,
              `${(cx + topW / 2).toFixed(1)},${y0}`,
              `${(cx + botW / 2).toFixed(1)},${y1}`,
              `${(cx - botW / 2).toFixed(1)},${y1}`,
            ].join(' ');
            const active = hoverIdx === i;
            const color = FUNNEL_SHADES[i % FUNNEL_SHADES.length];
            return (
              <g
                key={r.key}
                className="xa-funnel-step"
                onMouseEnter={() => setHoverIdx(i)}
                onMouseLeave={() => setHoverIdx(null)}
              >
                <polygon
                  points={pts}
                  fill={color}
                  fillOpacity={hoverIdx === null || active ? 0.94 : 0.45}
                  stroke="#ffffff"
                  strokeWidth={1.5}
                />
                <text x={cx} y={y0 + 17} textAnchor="middle" fill="#ffffff" fontSize={11.5} fontWeight={500}>
                  {r.label.length > 14 ? r.label.slice(0, 14) + '…' : r.label}
                </text>
                <text
                  x={cx}
                  y={y0 + 34}
                  textAnchor="middle"
                  fill="#ffffff"
                  fontSize={13.5}
                  fontWeight={700}
                  style={{ fontVariantNumeric: 'tabular-nums' }}
                >
                  {fmtNum(r.count)}
                </text>
              </g>
            );
          })}
        </svg>
      </div>

      <div className="xa-funnel-detail">
        <div className="dash-table-wrap">
          <table className="dash-table xa-compact-table">
            <thead>
              <tr>
                <th scope="col">环节</th>
                <th scope="col" className="num">数量</th>
                <th scope="col" className="num">整体转化</th>
                <th scope="col" className="num">步间转化</th>
                <th scope="col" className="num">流失</th>
                <th scope="col" className="num">中位耗时</th>
              </tr>
            </thead>
            <tbody>
              {computed.rows.map((r, i) => {
                const isWorst = i === computed.worstDropIdx && r.drop > 0;
                return (
                  <tr
                    key={r.key}
                    className={hoverIdx === i ? 'xa-row-hover' : ''}
                    onMouseEnter={() => setHoverIdx(i)}
                    onMouseLeave={() => setHoverIdx(null)}
                  >
                    <td>
                      <span className="xa-step-dot" style={{ background: FUNNEL_SHADES[i % FUNNEL_SHADES.length] }}>
                        {i + 1}
                      </span>
                      <span className="xa-step-name">{r.label}</span>
                    </td>
                    <td className="num"><b>{fmtNum(r.count)}</b></td>
                    <td className="num">
                      {r.overallRate !== null ? (
                        <span>{r.overallRate.toFixed(1)}%</span>
                      ) : (
                        <span className="dash-muted">—</span>
                      )}
                    </td>
                    <td className="num">
                      {r.stepRate !== null ? (
                        <b>{r.stepRate.toFixed(1)}%</b>
                      ) : r.note ? (
                        <span className="dash-muted">{r.note}</span>
                      ) : (
                        <span className="dash-muted">—</span>
                      )}
                    </td>
                    <td className={`num ${isWorst ? 'xa-drop-alert' : 'dash-muted'}`}>
                      {r.drop > 0 ? `-${fmtNum(r.drop)}` : '—'}
                    </td>
                    <td className="num dash-muted">{fmtMedianSec(r.medianSec)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        {worstRow && worstPrev ? (
          <InsightBox>
            <b>{worstPrev.label} → {worstRow.label}</b> 环节流失最大（流失 <b>{fmtNum(worstRow.drop)}</b> 个，步间转化{' '}
            <b>{worstRow.stepRate !== null ? `${worstRow.stepRate.toFixed(1)}%` : '—'}</b>）。建议重点核查该阶段构建/发布阻碍与未发布草稿原因。
          </InsightBox>
        ) : null}
      </div>
    </div>
  );
}

/** 02. 多序列堆叠面积图（支持「数量 / 占比」双口径切换） */
export interface StackedSeriesDef {
  key: string;
  label: string;
  color: string;
}

export function StackedAreaChart({
  data,
  series,
  mode = 'value',
}: {
  data: ({ date: string } & Record<string, number | string>)[];
  series: StackedSeriesDef[];
  mode?: 'value' | 'percent';
}) {
  const wrap = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(560);
  const [hover, setHover] = useState<number | null>(null);

  useLayoutEffect(() => {
    const el = wrap.current;
    if (!el) return;
    const measure = () => setWidth(Math.max(260, Math.floor(el.clientWidth)));
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const H = 228;
  const PAD = { top: 18, right: 14, bottom: 26, left: 42 };
  const plotW = Math.max(120, width - PAD.left - PAD.right);
  const plotH = H - PAD.top - PAD.bottom;
  const n = data.length;

  const { layers, ticks, topVal } = useMemo(() => {
    const totals = data.map((d) => series.reduce((acc, s) => acc + Number(d[s.key] ?? 0), 0));
    const maxTot = mode === 'percent' ? 100 : Math.max(1, ...totals);
    const tks = mode === 'percent' ? [0, 25, 50, 75, 100] : niceTicks(maxTot);
    const topV = tks[tks.length - 1] || 1;

    const cum = new Array(n).fill(0);
    const lyr = series.map((s) => {
      const y0Arr: number[] = [];
      const y1Arr: number[] = [];
      for (let i = 0; i < n; i++) {
        const raw = Number(data[i][s.key] ?? 0);
        const val = mode === 'percent' ? (totals[i] > 0 ? (raw / totals[i]) * 100 : 0) : raw;
        y0Arr.push(cum[i]);
        cum[i] += val;
        y1Arr.push(cum[i]);
      }
      return { ...s, y0Arr, y1Arr };
    });
    return { layers: lyr, ticks: tks, topVal: topV };
  }, [data, series, mode, n]);

  const x = (i: number) => PAD.left + (n > 1 ? (plotW * i) / (n - 1) : plotW / 2);
  const y = (v: number) => PAD.top + plotH - (v / topVal) * plotH;
  const every = Math.max(1, Math.ceil((n * 48) / plotW));

  const tip = hover !== null ? data[hover] : null;
  const tipLeft = hover !== null ? Math.min(Math.max(x(hover), 86), width - 86) : 0;

  return (
    <div className="xa-stacked-wrap">
      <div className="dash-trend-legend">
        {series.map((s) => (
          <span key={s.key}>
            <i style={{ background: s.color }} />
            {s.label}
          </span>
        ))}
      </div>
      <div className="trend-chart-plot" ref={wrap}>
        <svg width={width} height={H} onMouseLeave={() => setHover(null)} role="img" aria-label="多维堆叠面积趋势图">
          {ticks.map((t) => (
            <g key={t}>
              <line
                x1={PAD.left}
                x2={width - PAD.right}
                y1={y(t)}
                y2={y(t)}
                stroke="#e8edf5"
                strokeDasharray={t === 0 ? undefined : '3 3'}
                shapeRendering="crispEdges"
              />
              <text x={PAD.left - 8} y={y(t)} dy="0.32em" textAnchor="end" fontSize={11} fill="#64748b">
                {mode === 'percent' ? `${t}%` : fmtCompact(t)}
              </text>
            </g>
          ))}
          {data.map((d, i) =>
            i % every === 0 ? (
              <text key={d.date} x={x(i)} y={H - 7} textAnchor="middle" fontSize={11} fill="#64748b">
                {d.date.slice(5, 10)}
              </text>
            ) : null,
          )}
          {n > 1
            ? layers.map((l) => {
                const topPts = l.y1Arr.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join('');
                const botPts = l.y0Arr
                  .map((_, i) => `L${x(n - 1 - i).toFixed(1)},${y(l.y0Arr[n - 1 - i]).toFixed(1)}`)
                  .join('');
                return (
                  <g key={l.key}>
                    <path d={`${topPts}${botPts}Z`} fill={l.color} fillOpacity={0.28} />
                    <path d={topPts} fill="none" stroke={l.color} strokeWidth={1.8} strokeLinejoin="round" />
                  </g>
                );
              })
            : null}
          {hover !== null ? (
            <line
              x1={x(hover)}
              x2={x(hover)}
              y1={PAD.top}
              y2={PAD.top + plotH}
              stroke="#64748b"
              strokeDasharray="3 3"
              strokeWidth={1}
            />
          ) : null}
          {data.map((d, i) => {
            const slot = n > 1 ? plotW / (n - 1) : plotW;
            return (
              <rect
                key={'h-' + d.date}
                x={x(i) - slot / 2}
                y={PAD.top}
                width={Math.max(slot, 1)}
                height={plotH}
                fill="transparent"
                onMouseEnter={() => setHover(i)}
              />
            );
          })}
        </svg>
        {tip ? (
          <div className="trend-tooltip" style={{ left: tipLeft, top: 8 }}>
            <small>{tip.date}</small>
            {series.map((s) => (
              <div key={s.key}>
                {s.label}: <b>{fmtNum(Number(tip[s.key] ?? 0))}</b>
              </div>
            ))}
          </div>
        ) : null}
      </div>
    </div>
  );
}

/** 03. 近 140 天每日活跃日历热力图（GitHub / X-APM 风格） */
export function CalendarHeatmap({
  days,
  metric = 'total',
}: {
  days: { date: string; tasks: number; apps: number; pv: number; total: number }[];
  metric?: 'total' | 'tasks' | 'pv' | 'apps';
}) {
  const [hover, setHover] = useState<{
    date: string;
    tasks: number;
    apps: number;
    pv: number;
    val: number;
    xPct: number;
    yPct: number;
  } | null>(null);

  const { weeks, monthLabels, maxVal, activeDaysCount, peakDay } = useMemo(() => {
    if (!days.length) {
      return { weeks: [] as (typeof days[number] | null)[][], monthLabels: [] as { col: number; text: string }[], maxVal: 0, activeDaysCount: 0, peakDay: null as typeof days[number] | null };
    }
    const firstDt = new Date(days[0].date + 'T00:00:00');
    const padStart = (firstDt.getDay() + 6) % 7; // 周一 = 0
    const cells: (typeof days[number] | null)[] = [...Array(padStart).fill(null), ...days];
    while (cells.length % 7 !== 0) cells.push(null);

    const wks: (typeof days[number] | null)[][] = [];
    for (let i = 0; i < cells.length; i += 7) wks.push(cells.slice(i, i + 7));

    const mLabels: { col: number; text: string }[] = [];
    let lastMonth = '';
    wks.forEach((wk, colIdx) => {
      const firstValid = wk.find(Boolean);
      if (firstValid) {
        const ym = firstValid.date.slice(0, 7);
        if (ym !== lastMonth) {
          lastMonth = ym;
          mLabels.push({ col: colIdx, text: `${parseInt(firstValid.date.slice(5, 7), 10)}月` });
        }
      }
    });

    let mx = 0;
    let act = 0;
    let pk: typeof days[number] | null = null;
    for (const d of days) {
      const v = d[metric];
      if (v > 0) act++;
      if (v > mx) {
        mx = v;
        pk = d;
      }
    }
    return { weeks: wks, monthLabels: mLabels, maxVal: mx, activeDaysCount: act, peakDay: pk };
  }, [days, metric]);

  const levelColor = (v: number) => {
    if (v <= 0 || maxVal <= 0) return '#eef2f7';
    const r = v / maxVal;
    if (r < 0.2) return '#dbeafe';
    if (r < 0.45) return '#93c5fd';
    if (r < 0.72) return '#3b82f6';
    return '#1d4ed8';
  };

  const cell = 18;
  const gap = 5;
  const leftPad = 24;
  const topPad = 22;
  const svgW = leftPad + weeks.length * (cell + gap) + 6;
  const svgH = topPad + 7 * (cell + gap) + 4;
  const weekLabels = ['一', '', '三', '', '五', '', '日'];

  return (
    <div className="xa-cal-wrap">
      <div className="xa-cal-scroll">
        <svg className="xa-cal-svg" viewBox={`0 0 ${svgW} ${svgH}`} role="img" aria-label="近 182 天每日活跃日历热力图">
          {monthLabels.map((m) => (
            <text key={m.col + m.text} x={leftPad + m.col * (cell + gap)} y={12} fontSize={10.5} fill="#64748b">
              {m.text}
            </text>
          ))}
          {weekLabels.map((w, r) =>
            w ? (
              <text key={r} x={0} y={topPad + r * (cell + gap) + 10} fontSize={10} fill="#94a3b8">
                {w}
              </text>
            ) : null,
          )}
          {weeks.map((wk, c) =>
            wk.map((d, r) => {
              if (!d) return null;
              const v = d[metric];
              const isHover = hover?.date === d.date;
              return (
                <rect
                  key={d.date}
                  x={leftPad + c * (cell + gap)}
                  y={topPad + r * (cell + gap)}
                  width={cell}
                  height={cell}
                  rx={3}
                  fill={levelColor(v)}
                  stroke={isHover ? '#0f172a' : 'transparent'}
                  strokeWidth={1.2}
                  onMouseEnter={() =>
                    setHover({
                      date: d.date,
                      tasks: d.tasks,
                      apps: d.apps,
                      pv: d.pv,
                      val: v,
                      xPct: ((leftPad + c * (cell + gap) + cell / 2) / svgW) * 100,
                      yPct: ((topPad + r * (cell + gap)) / svgH) * 100,
                    })
                  }
                  onMouseLeave={() => setHover(null)}
                />
              );
            }),
          )}
        </svg>
        {hover ? (
          <div
            className="xa-float-tip"
            style={{
              left: `${Math.min(84, Math.max(16, hover.xPct))}%`,
              top: `${Math.max(4, hover.yPct - 8)}%`,
            }}
          >
            <small>{hover.date}</small>
            <div>AI 任务 <b>{fmtNum(hover.tasks)}</b> · 新建 <b>{fmtNum(hover.apps)}</b> · PV <b>{fmtNum(hover.pv)}</b></div>
          </div>
        ) : null}
      </div>
      <div className="xa-cal-foot">
        <div className="xa-cal-hover-info">
          {hover ? (
            <>
              <b>{hover.date}</b> · 任务 <b>{fmtNum(hover.tasks)}</b> · 新建应用 <b>{fmtNum(hover.apps)}</b> · PV{' '}
              <b>{fmtNum(hover.pv)}</b>
            </>
          ) : (
            <>
              近 {days.length} 天共 <b>{activeDaysCount}</b> 天有平台活动
              {peakDay && maxVal > 0 ? (
                <> · 峰值 <b>{peakDay.date}</b>（{fmtNum(maxVal)}）</>
              ) : null}
            </>
          )}
        </div>
        <div className="xa-cal-legend">
          <span>低</span>
          {['#eef2f7', '#dbeafe', '#93c5fd', '#3b82f6', '#1d4ed8'].map((c) => (
            <i key={c} style={{ background: c }} />
          ))}
          <span>高</span>
        </div>
      </div>
    </div>
  );
}

/** 04. 24 小时极坐标玫瑰图（Polar Rose Chart，对照 X-APM 触发时段分布） */
export function PolarRoseChart({
  hours,
}: {
  hours: { hour: number; total: number; succeeded: number; failed: number }[];
}) {
  const [hover, setHover] = useState<number | null>(null);
  const full = useMemo(() => {
    const map = new Map(hours.map((h) => [h.hour, h]));
    return Array.from({ length: 24 }, (_, h) => map.get(h) ?? { hour: h, total: 0, succeeded: 0, failed: 0 });
  }, [hours]);

  const totalAll = full.reduce((a, b) => a + b.total, 0);
  const maxVal = Math.max(1, ...full.map((h) => h.total));
  const peak = full.reduce((best, cur) => (cur.total > best.total ? cur : best), full[0]);
  const nightTotal = full.filter((h) => h.hour < 8).reduce((a, b) => a + b.total, 0);
  const nightPct = totalAll > 0 ? ((nightTotal / totalAll) * 100).toFixed(1) : '0.0';

  const size = 246;
  const cx = size / 2;
  const cy = size / 2;
  const innerR = 40;
  const maxR = 96;

  const shiftColor = (h: number) => {
    if (h >= 8 && h < 16) return '#2563eb'; // 早班 08-16
    if (h >= 16 && h < 24) return '#0ea5e9'; // 中班 16-24
    return '#7c3aed'; // 夜班 00-08
  };

  const wedgePath = (h: number, outerR: number) => {
    const a0 = ((h * 15 - 90 - 6.2) * Math.PI) / 180;
    const a1 = ((h * 15 - 90 + 6.2) * Math.PI) / 180;
    const x0 = cx + innerR * Math.cos(a0);
    const y0 = cy + innerR * Math.sin(a0);
    const x1 = cx + outerR * Math.cos(a0);
    const y1 = cy + outerR * Math.sin(a0);
    const x2 = cx + outerR * Math.cos(a1);
    const y2 = cy + outerR * Math.sin(a1);
    const x3 = cx + innerR * Math.cos(a1);
    const y3 = cy + innerR * Math.sin(a1);
    return `M${x0.toFixed(1)},${y0.toFixed(1)}L${x1.toFixed(1)},${y1.toFixed(1)}A${outerR.toFixed(1)},${outerR.toFixed(1)} 0 0 1 ${x2.toFixed(1)},${y2.toFixed(1)}L${x3.toFixed(1)},${y3.toFixed(1)}A${innerR},${innerR} 0 0 0 ${x0.toFixed(1)},${y0.toFixed(1)}Z`;
  };

  const active = hover !== null ? full[hover] : null;

  return (
    <div className="xa-polar-wrap">
      <div className="xa-polar-meta">
        <span>
          峰值 <b>{String(peak.hour).padStart(2, '0')}:00</b>（{fmtNum(peak.total)} 次）
        </span>
        <span>夜班 00–08 占比 <b>{nightPct}%</b></span>
      </div>
      <div className="xa-polar-body">
        <svg viewBox={`0 0 ${size} ${size}`} className="xa-polar-svg" role="img" aria-label="24 小时任务触发时段玫瑰图">
          {[0.33, 0.66, 1].map((f) => (
            <circle
              key={f}
              cx={cx}
              cy={cy}
              r={innerR + (maxR - innerR) * f}
              fill="none"
              stroke="#edf1f7"
              strokeDasharray="2 3"
            />
          ))}
          {full.map((h) => {
            const outerR = h.total > 0 ? innerR + 8 + (h.total / maxVal) * (maxR - innerR - 8) : innerR + 3;
            const isHover = hover === h.hour;
            return (
              <path
                key={h.hour}
                d={wedgePath(h.hour, outerR)}
                fill={h.total > 0 ? shiftColor(h.hour) : '#e2e8f0'}
                fillOpacity={hover === null || isHover ? (h.total > 0 ? 0.92 : 0.7) : 0.35}
                onMouseEnter={() => setHover(h.hour)}
                onMouseLeave={() => setHover(null)}
              />
            );
          })}
          {[0, 3, 6, 9, 12, 15, 18, 21].map((h) => {
            const rad = ((h * 15 - 90) * Math.PI) / 180;
            const lx = cx + (maxR + 14) * Math.cos(rad);
            const ly = cy + (maxR + 14) * Math.sin(rad);
            return (
              <text key={h} x={lx} y={ly} textAnchor="middle" dy="0.35em" fontSize={10} fill="#64748b">
                {h}时
              </text>
            );
          })}
          <text x={cx} y={cy - 4} textAnchor="middle" fontSize={15} fontWeight={700} fill="#0f172a">
            {active ? fmtNum(active.total) : fmtCompact(totalAll)}
          </text>
          <text x={cx} y={cy + 13} textAnchor="middle" fontSize={10.5} fill="#64748b">
            {active ? `${String(active.hour).padStart(2, '0')}:00 · 成${active.succeeded}/败${active.failed}` : '窗口总任务'}
          </text>
        </svg>
      </div>
      <div className="xa-polar-legend">
        <span><i style={{ background: '#2563eb' }} />早班 08–16</span>
        <span><i style={{ background: '#0ea5e9' }} />中班 16–24</span>
        <span><i style={{ background: '#7c3aed' }} />夜班 00–08</span>
      </div>
    </div>
  );
}

/** 05. 员工 / 工作空间留存 Cohort 矩阵 */
export function CohortRetentionMatrix({
  cohort,
}: {
  cohort?: {
    offsets: number[];
    totalNew: number;
    avgRates: (number | null)[];
    rows: { date: string; newUsers: number; rates: (number | null)[] }[];
  };
}) {
  if (!cohort || cohort.rows.length === 0) {
    return <div className="xa-empty-inline">暂无足够跨日活跃批次生成留存矩阵</div>;
  }

  const cellStyle = (rate: number | null) => {
    if (rate === null) return { background: '#f1f5f9', color: '#94a3b8' };
    if (rate === 0) return { background: '#f8fafc', color: '#94a3b8' };
    const alpha = Math.min(0.92, 0.14 + (rate / 100) * 0.78);
    return {
      background: `rgba(37, 99, 235, ${alpha.toFixed(2)})`,
      color: rate >= 45 ? '#ffffff' : '#1e3a8a',
    };
  };

  const d1 = cohort.avgRates[0];
  const d7 = cohort.avgRates[6];

  return (
    <div className="xa-cohort-wrap">
      <div className="dash-table-wrap">
        <table className="xa-cohort-table">
          <thead>
            <tr>
              <th>首次活跃日</th>
              <th className="num">新增空间</th>
              {cohort.offsets.map((d) => (
                <th key={d} className="num">第 {d} 日</th>
              ))}
            </tr>
          </thead>
          <tbody>
            <tr className="xa-cohort-avg">
              <td><b>加权平均</b></td>
              <td className="num"><b>{fmtNum(cohort.totalNew)}</b></td>
              {cohort.avgRates.map((r, i) => (
                <td key={i} className="num" style={cellStyle(r)}>
                  <b>{r === null ? '—' : `${r.toFixed(1)}%`}</b>
                </td>
              ))}
            </tr>
            {cohort.rows.map((row) => (
              <tr key={row.date}>
                <td className="dash-nowrap">{row.date.slice(5)}</td>
                <td className="num">{fmtNum(row.newUsers)}</td>
                {row.rates.map((r, i) => (
                  <td key={i} className="num" style={cellStyle(r)}>
                    {r === null ? '—' : `${r.toFixed(1)}%`}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <InsightBox>
        近 {cohort.rows.length} 个新增批次共覆盖 <b>{fmtNum(cohort.totalNew)}</b> 个活跃空间；
        次日加权留存 <b>{d1 !== null && d1 !== undefined ? `${d1.toFixed(1)}%` : '待观察'}</b>
        {d7 !== null && d7 !== undefined ? <>，第 7 日留存 <b>{d7.toFixed(1)}%</b></> : '（随上线运行天数累积自动补全第 7 日留存）'}。
      </InsightBox>
    </div>
  );
}

/** 06. 三段式桑基流向图（创作入口 → 应用形态 → 最终执行结果） */
export function SankeyFlowChart({
  flow,
  onSelectOutcome,
}: {
  flow?: {
    total: number;
    nodes: { id: string; col: number; label: string; value: number }[];
    links: { source: string; target: string; value: number }[];
    topPaths: { path: string[]; count: number }[];
  };
  onSelectOutcome?: (label: string) => void;
}) {
  const [hoverLink, setHoverLink] = useState<string | null>(null);
  const [hoverNode, setHoverNode] = useState<string | null>(null);

  const layout = useMemo(() => {
    if (!flow || flow.total === 0 || flow.nodes.length === 0) return null;
    const W = 720;
    const H = 264;
    const nodeW = 14;
    const colX = [16, 336, 560];
    const padY = 24;
    const usableH = H - padY * 2;
    const nodeGap = 8;

    const byCol: Record<number, typeof flow.nodes> = { 0: [], 1: [], 2: [] };
    for (const n of flow.nodes) {
      if (byCol[n.col]) byCol[n.col].push(n);
    }

    const nodeColor = (col: number, label: string, idx: number) => {
      if (col === 2) {
        if (label.includes('成功')) return '#10b981';
        if (label.includes('模型') || label.includes('网关')) return '#f59e0b';
        if (label.includes('失败')) return '#ef4444';
        if (label.includes('取消')) return '#94a3b8';
        return '#6366f1';
      }
      const p0 = ['#6366f1', '#2563eb', '#0ea5e9', '#8b5cf6', '#14b8a6'];
      const p1 = ['#3b82f6', '#06b6d4', '#8b5cf6', '#ec4899', '#f59e0b'];
      return (col === 0 ? p0 : p1)[idx % 5];
    };

    const rectMap = new Map<
      string,
      { id: string; col: number; label: string; value: number; x: number; y: number; h: number; color: string; outOff: number; inOff: number }
    >();

    for (const col of [0, 1, 2]) {
      const list = byCol[col].slice(0, 6);
      const colTotal = Math.max(1, list.reduce((a, b) => a + b.value, 0));
      const avail = Math.max(60, usableH - nodeGap * Math.max(0, list.length - 1));
      let curY = padY;
      list.forEach((n, idx) => {
        const h = Math.max(14, (n.value / colTotal) * avail);
        rectMap.set(n.id, {
          ...n,
          x: colX[col],
          y: curY,
          h,
          color: nodeColor(col, n.label, idx),
          outOff: 0,
          inOff: 0,
        });
        curY += h + nodeGap;
      });
    }

    const ribbons: {
      key: string;
      source: string;
      target: string;
      value: number;
      path: string;
      color: string;
      srcLabel: string;
      tgtLabel: string;
    }[] = [];

    for (const l of flow.links) {
      const s = rectMap.get(l.source);
      const t = rectMap.get(l.target);
      if (!s || !t || l.value <= 0) continue;
      const sh = Math.max(2, (l.value / Math.max(1, s.value)) * s.h);
      const th = Math.max(2, (l.value / Math.max(1, t.value)) * t.h);
      const sy0 = s.y + s.outOff;
      const sy1 = Math.min(s.y + s.h, sy0 + sh);
      const ty0 = t.y + t.inOff;
      const ty1 = Math.min(t.y + t.h, ty0 + th);
      s.outOff += sh;
      t.inOff += th;

      const x0 = s.x + nodeW;
      const x1 = t.x;
      const mx = (x0 + x1) / 2;
      const d = [
        `M${x0},${sy0.toFixed(1)}`,
        `C${mx},${sy0.toFixed(1)} ${mx},${ty0.toFixed(1)} ${x1},${ty0.toFixed(1)}`,
        `L${x1},${ty1.toFixed(1)}`,
        `C${mx},${ty1.toFixed(1)} ${mx},${sy1.toFixed(1)} ${x0},${sy1.toFixed(1)}`,
        'Z',
      ].join(' ');
      ribbons.push({
        key: `${l.source}->${l.target}`,
        source: l.source,
        target: l.target,
        value: l.value,
        path: d,
        color: t.col === 2 ? t.color : s.color,
        srcLabel: s.label,
        tgtLabel: t.label,
      });
    }

    return { W, H, nodeW, nodes: Array.from(rectMap.values()), ribbons };
  }, [flow]);

  if (!flow || !layout) {
    return <div className="xa-empty-inline">当前时间窗口内暂无任务流转记录</div>;
  }

  const okNode = flow.nodes.find((n) => n.col === 2 && n.label.includes('成功'));
  const failNodes = flow.nodes.filter((n) => n.col === 2 && (n.label.includes('失败') || n.label.includes('异常')));
  const failSum = failNodes.reduce((a, b) => a + b.value, 0);
  const topFail = failNodes.sort((a, b) => b.value - a.value)[0];

  const activeRibbon = hoverLink ? layout.ribbons.find((r) => r.key === hoverLink) ?? null : null;
  const activeNodeObj = hoverNode ? layout.nodes.find((n) => n.id === hoverNode) ?? null : null;

  return (
    <div className="xa-sankey-grid">
      <div className="xa-sankey-chart">
        <div className="xa-sankey-col-heads">
          <span>① 创作入口 / 任务类型</span>
          <span>② 应用形态</span>
          <span>③ 最终执行结果（点击异常项可下钻排查）</span>
        </div>
        <div className="xa-sankey-canvas">
          <svg viewBox={`0 0 ${layout.W} ${layout.H}`} className="xa-sankey-svg" role="img" aria-label="任务流向桑基图">
            {layout.ribbons.map((rb) => {
              const focused =
                hoverLink === rb.key || (hoverNode !== null && (rb.source === hoverNode || rb.target === hoverNode));
              const dimmed = (hoverLink !== null || hoverNode !== null) && !focused;
              return (
                <path
                  key={rb.key}
                  d={rb.path}
                  fill={rb.color}
                  fillOpacity={focused ? 0.56 : dimmed ? 0.08 : 0.28}
                  onMouseEnter={() => setHoverLink(rb.key)}
                  onMouseLeave={() => setHoverLink(null)}
                />
              );
            })}
            {layout.nodes.map((nd) => {
              const labelX = nd.x + layout.nodeW + 7;
              const clickable = nd.col === 2 && Boolean(onSelectOutcome);
              return (
                <g
                  key={nd.id}
                  onMouseEnter={() => setHoverNode(nd.id)}
                  onMouseLeave={() => setHoverNode(null)}
                  onClick={() => {
                    if (clickable) onSelectOutcome?.(nd.label);
                  }}
                  style={{ cursor: clickable ? 'pointer' : 'default' }}
                >
                  <rect x={nd.x} y={nd.y} width={layout.nodeW} height={nd.h} rx={3} fill={nd.color} />
                  <text x={labelX} y={nd.y + nd.h / 2} dy="0.35em" fontSize={11.5} fill="#0f172a" fontWeight={500}>
                    {nd.label} <tspan fill="#64748b" fontWeight={400}>{fmtNum(nd.value)}</tspan>
                  </text>
                </g>
              );
            })}
          </svg>
          {activeRibbon ? (
            <div className="xa-sankey-tip">
              <b>{activeRibbon.srcLabel}</b> → <b>{activeRibbon.tgtLabel}</b>：<b>{fmtNum(activeRibbon.value)}</b> 次（占总任务{' '}
              {((activeRibbon.value / Math.max(1, flow.total)) * 100).toFixed(1)}%）
            </div>
          ) : activeNodeObj ? (
            <div className="xa-sankey-tip">
              <b>{activeNodeObj.label}</b>：共 <b>{fmtNum(activeNodeObj.value)}</b> 次（占总任务{' '}
              {((activeNodeObj.value / Math.max(1, flow.total)) * 100).toFixed(1)}%）
              {activeNodeObj.col === 2 && onSelectOutcome ? ' · 点击前往日志中心定位明细 →' : ''}
            </div>
          ) : null}
        </div>
      </div>

      <div className="xa-sankey-side">
        <div className="xa-sankey-side-head">
          <b>高频流转路径 TOP 5</b>
          <span>共 {fmtNum(flow.total)} 次任务</span>
        </div>
        <ol className="xa-path-list">
          {flow.topPaths.map((p, idx) => {
            const pct = flow.total > 0 ? ((p.count / flow.total) * 100).toFixed(1) : '0.0';
            const isFail = p.path[2]?.includes('失败') || p.path[2]?.includes('异常');
            return (
              <li key={idx}>
                <span className={`xa-rank-dot ${idx < 3 ? 'top' : ''}`}>{idx + 1}</span>
                <div className="xa-path-copy">
                  <div className="xa-path-crumbs">
                    {p.path[0]} <i>→</i> {p.path[1]} <i>→</i>{' '}
                    <b className={isFail ? 'err' : ''}>{p.path[2]}</b>
                  </div>
                  <small>
                    {fmtNum(p.count)} 次 · 占比 {pct}%
                  </small>
                </div>
              </li>
            );
          })}
        </ol>
        <InsightBox>
          窗口内成功交付 <b>{fmtNum(okNode?.value ?? 0)}</b> 次
          {failSum > 0 && topFail ? (
            <>
              ；异常/失败共 <b>{fmtNum(failSum)}</b> 次，其中 <b>{topFail.label}</b> 占比最高（{fmtNum(topFail.value)} 次）。
            </>
          ) : (
            '，链路健康度良好。'
          )}
        </InsightBox>
      </div>
    </div>
  );
}
