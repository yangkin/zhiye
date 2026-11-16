import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import { isSkillApp, type AppSummary, type StatsRange } from '../api/types';
import { Modal } from './Modal';
import { TrendChart } from './TrendChart';
import { ErrorState, LoadingState } from './EmptyState';

const RANGES: [StatsRange, string][] = [
  ['all', '全部时间'],
  ['today', '今天'],
  ['7d', '最近7天'],
  ['3d', '最近3天'],
];
const PV_TIP = '页面每被打开一次计 1 次，同一访客重复浏览会累计';

export function StatsModal({ app, onClose }: { app: AppSummary; onClose: () => void }) {
  const [range, setRange] = useState<StatsRange>('7d');
  const [kind, setKind] = useState<'line' | 'bar'>('line');
  const skill = isSkillApp(app);
  const q = useQuery({
    queryKey: qk.appStats(app.id, range),
    queryFn: () => api.appStats(app.id, range),
    enabled: !skill,
    placeholderData: keepPreviousData,
  });
  const rangeLabel = RANGES.find(([r]) => r === range)![1];
  const data = q.data?.daily ?? [];
  const empty = !data.length || data.every((d) => !d.pv);

  return (
    <Modal title="数据分析" overline={app.name} onClose={onClose} className="stats-modal">
      <p className="stats-sub">了解应用的访问情况，查看页面浏览量与访问趋势。</p>
      <div className="seg-control" role="radiogroup" aria-label="统计时间范围">
        {RANGES.map(([r, label]) => (
          <button key={r} type="button" role="radio" aria-checked={range === r} className={range === r ? 'active' : ''} onClick={() => setRange(r)}>
            {label}
          </button>
        ))}
      </div>
      {skill ? (
        <p className="stats-empty">技能包没有线上页面，不统计访问量</p>
      ) : q.isLoading ? (
        <LoadingState label="正在读取访问数据…" />
      ) : q.error ? (
        <ErrorState message={errorMessage(q.error)} onRetry={() => q.refetch()} />
      ) : (
        <>
          <div className="stats-tiles">
            <StatTile label="今日实时 PV" value={q.data?.todayPv ?? 0} />
            <StatTile label="总访问量（PV）" value={q.data?.totalPv ?? 0} />
          </div>
          <section className="stats-card" aria-label="访问趋势">
            <header>
              <div>
                <h3>访问趋势</h3>
                <p>{rangeLabel}的每日页面浏览量，同一访客重复浏览会累计计数。</p>
              </div>
              <div className="seg-control small" role="radiogroup" aria-label="图表类型">
                {(
                  [
                    ['line', '折线图'],
                    ['bar', '柱状图'],
                  ] as const
                ).map(([k, label]) => (
                  <button key={k} type="button" role="radio" aria-checked={kind === k} className={kind === k ? 'active' : ''} onClick={() => setKind(k)}>
                    {label}
                  </button>
                ))}
              </div>
            </header>
            {empty ? (
              <p className="stats-empty">还没有访问数据，发布后分享链接，访问量会显示在这里</p>
            ) : (
              <TrendChart data={data} kind={kind} label={`${rangeLabel}每日页面浏览量`} />
            )}
          </section>
        </>
      )}
    </Modal>
  );
}

function StatTile({ label, value }: { label: string; value: number }) {
  const [tip, setTip] = useState(false);
  return (
    <div className="stat-tile">
      <span className="stat-label">
        {label}
        <button
          type="button"
          className="info-dot"
          aria-label={`${label}说明：${PV_TIP}`}
          onMouseEnter={() => setTip(true)}
          onMouseLeave={() => setTip(false)}
          onFocus={() => setTip(true)}
          onBlur={() => setTip(false)}
        >
          ⓘ
        </button>
        {tip ? (
          <span className="info-tip" role="tooltip">
            {PV_TIP}
          </span>
        ) : null}
      </span>
      <b className="stat-value">{value.toLocaleString('zh-CN')}</b>
    </div>
  );
}
