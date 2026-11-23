import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';

export interface DailyPoint {
  date: string;
  pv: number;
}

const SERIES = '#6f52d4';
const GRID = '#eeedf3';
const INK = '#272631';
const MUTED = '#8f8c9c';
const H = 220;
const PAD = { top: 22, right: 14, bottom: 26, left: 40 };

/** 4–5 个从 0 开始的整齐刻度。 */
export function niceTicks(max: number): number[] {
  if (max <= 0) return [0, 1];
  const raw = max / 4;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((f) => f * pow).find((s) => max / s <= 4.0001) ?? 10 * pow;
  const s = step < 1 ? 1 : step;
  const top = Math.ceil(max / s) * s;
  const ticks: number[] = [];
  for (let v = 0; v <= top + 1e-9; v += s) ticks.push(Math.round(v * 100) / 100);
  return ticks.length < 2 ? [0, s] : ticks;
}

const mmdd = (d: string) => d.slice(5, 10);
const fmt = (n: number) => n.toLocaleString('zh-CN');

/** 访问趋势图：单系列折线 / 柱状图，内联 SVG。 */
export function TrendChart({ data, kind, label }: { data: DailyPoint[]; kind: 'line' | 'bar'; label: string }) {
  const wrap = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(560);
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
  useEffect(() => setActive(null), [kind, data]);

  const n = data.length;
  const max = Math.max(0, ...data.map((d) => d.pv));
  const ticks = useMemo(() => niceTicks(max), [max]);
  const top = ticks[ticks.length - 1];
  const plotW = width - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;
  const slot = n ? plotW / n : plotW;
  const x = (i: number) => (kind === 'bar' || n === 1 ? PAD.left + slot * (i + 0.5) : PAD.left + (n > 1 ? (plotW * i) / (n - 1) : plotW / 2));
  const y = (v: number) => PAD.top + plotH - (v / top) * plotH;
  const base = PAD.top + plotH;
  const maxIdx = data.findIndex((d) => d.pv === max);
  // X 轴标签抽稀：每个标签约 40px
  const every = Math.max(1, Math.ceil((n * 40) / plotW));
  const barW = Math.max(2, Math.min(24, slot * 0.62, slot - 2));

  const linePath = data.map((d, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(d.pv).toFixed(1)}`).join('');
  const areaPath = n > 1 ? `${linePath}L${x(n - 1).toFixed(1)},${base}L${x(0).toFixed(1)},${base}Z` : '';
  const barPath = (i: number, v: number) => {
    const bx = x(i) - barW / 2;
    const by = y(v);
    const h = base - by;
    if (h <= 0) return '';
    const r = Math.min(4, h, barW / 2);
    return `M${bx},${base}V${by + r}Q${bx},${by} ${bx + r},${by}H${bx + barW - r}Q${bx + barW},${by} ${bx + barW},${by + r}V${base}Z`;
  };

  const tip = active !== null ? data[active] : null;
  const tipTop = active !== null ? Math.min(H - 64, Math.max(6, y(data[active].pv) - 20)) : 0;
  const tipStyle =
    active !== null
      ? x(active) > width * 0.56
        ? { right: Math.max(8, width - x(active) + 12), top: tipTop, transform: 'none' }
        : { left: Math.max(8, x(active) + 12), top: tipTop, transform: 'none' }
      : undefined;

  return (
    <div className="trend-chart">
      <div className="trend-chart-plot" ref={wrap}>
        <svg width={width} height={H} role="img" aria-label={`${label}，共 ${n} 天，最高 ${fmt(max)} 次`} onMouseLeave={() => setActive(null)}>
          {ticks.map((t) => (
            <g key={t}>
              <line x1={PAD.left} x2={width - PAD.right} y1={y(t)} y2={y(t)} stroke={GRID} strokeWidth={1} shapeRendering="crispEdges" />
              <text x={PAD.left - 8} y={y(t)} dy="0.32em" textAnchor="end" fontSize={11} fill={MUTED} style={{ fontVariantNumeric: 'tabular-nums' }}>
                {fmt(t)}
              </text>
            </g>
          ))}
          {data.map((d, i) =>
            i % every === 0 ? (
              <text key={d.date} x={x(i)} y={H - 8} textAnchor="middle" fontSize={11} fill={MUTED}>
                {mmdd(d.date)}
              </text>
            ) : null,
          )}
          {kind === 'line' ? (
            <>
              {areaPath ? <path d={areaPath} fill={SERIES} fillOpacity={0.1} /> : null}
              {n > 1 ? <path d={linePath} fill="none" stroke={SERIES} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" /> : null}
              {n === 1 ? <circle cx={x(0)} cy={y(data[0].pv)} r={4.5} fill={SERIES} /> : null}
              {active !== null ? (
                <>
                  <line x1={x(active)} x2={x(active)} y1={PAD.top} y2={base} stroke={MUTED} strokeWidth={1} shapeRendering="crispEdges" />
                  <circle cx={x(active)} cy={y(data[active].pv)} r={4.5} fill="#fff" stroke={SERIES} strokeWidth={2} />
                </>
              ) : null}
              {maxIdx >= 0 && max > 0 ? (
                <text x={x(maxIdx)} y={y(max) - 9} textAnchor="middle" fontSize={11} fontWeight={600} fill={INK}>
                  {fmt(max)}
                </text>
              ) : null}
            </>
          ) : (
            data.map((d, i) => (
              <g key={d.date}>
                <path d={barPath(i, d.pv)} fill={SERIES} fillOpacity={active === null || active === i ? 1 : 0.55} />
                {(n <= 7 || i === maxIdx) && d.pv > 0 ? (
                  <text x={x(i)} y={y(d.pv) - 6} textAnchor="middle" fontSize={11} fontWeight={i === maxIdx ? 600 : 400} fill={INK}>
                    {fmt(d.pv)}
                  </text>
                ) : null}
              </g>
            ))
          )}
          {data.map((d, i) => {
            const w = kind === 'bar' || n === 1 ? slot : n > 1 ? plotW / (n - 1) : plotW;
            return (
              <rect
                key={'hit' + d.date}
                className="trend-hit"
                x={x(i) - w / 2}
                y={PAD.top}
                width={Math.max(w, 1)}
                height={plotH}
                fill="transparent"
                tabIndex={0}
                role="graphics-symbol"
                aria-label={`${d.date} 页面浏览量 ${d.pv} 次`}
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
            <b>{fmt(tip.pv)}</b> 次浏览
          </div>
        ) : null}
      </div>
      <button type="button" className="text-button trend-table-toggle" aria-expanded={showTable} aria-controls={tableId} onClick={() => setShowTable((v) => !v)}>
        {showTable ? '收起数据表' : '查看数据表'}
      </button>
      <table id={tableId} className={showTable ? 'trend-table' : 'trend-table sr-only'}>
        <caption className="sr-only">{label}</caption>
        <thead>
          <tr>
            <th scope="col">日期</th>
            <th scope="col">页面浏览量（PV）</th>
          </tr>
        </thead>
        <tbody>
          {data.map((d) => (
            <tr key={d.date}>
              <td>{d.date}</td>
              <td>{fmt(d.pv)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
