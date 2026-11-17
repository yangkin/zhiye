import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type { DashboardRange, DashboardScope } from '../api/types';
import { Icon } from '../lib/icons';
import { fmtTime } from '../lib/format';
import { toast } from '../lib/store';
import { ErrorState, LoadingState } from '../components/EmptyState';
import {
  BreakdownBars,
  CHART_PALETTE,
  ConversionFunnel,
  DonutChart,
  HourlyBarChart,
  MultiTrendChart,
  SegmentedStrip,
  Sparkline,
  fmtBytes,
  fmtCompact,
  fmtDurationMs,
  fmtNum,
  type DonutSlice,
  type SeriesPoint,
} from '../components/DashboardCharts';

type SectionTab = 'all' | 'apps' | 'tasks' | 'cloud' | 'interactions' | 'system';
type TrendMetric = 'tasks' | 'tokens' | 'pv' | 'apps' | 'versions' | 'activity' | 'calls' | 'modelCalls';

const RANGES: [DashboardRange, string][] = [
  ['today', '今天'],
  ['7d', '近 7 天'],
  ['30d', '近 30 天'],
  ['90d', '近 90 天'],
  ['all', '全部时间'],
];

const SCOPES: [DashboardScope, string][] = [
  ['platform', '全平台管理视角'],
  ['workspace', '我的工作空间'],
];

const SECTION_TABS: [SectionTab, string][] = [
  ['all', '全部维度'],
  ['apps', '空间与应用排行'],
  ['tasks', '任务诊断与模型'],
  ['cloud', '版本、云服务与数据源'],
  ['interactions', '会话、反馈与社区'],
  ['system', '实例运行监控'],
];

const TREND_METRICS: [TrendMetric, string][] = [
  ['tasks', 'AI 任务'],
  ['tokens', 'Token 消耗'],
  ['pv', '访问 PV'],
  ['apps', '应用 / 空间'],
  ['versions', '版本 / 部署'],
  ['activity', '消息互动'],
  ['calls', '云 / 数据源'],
  ['modelCalls', '模型请求'],
];

const KIND_LABELS: Record<string, string> = {
  template: 'React 模板',
  vue: 'Vue 3 模板',
  vite: '导入 Vite 项目',
  static: '静态 HTML',
  script: '命令行脚本',
  skill: 'AI 技能包',
};

const PHASE_LABELS: Record<string, string> = {
  ready: '已就绪',
  generating: '生成中',
  plan: '待确认计划',
  planning: '规划中',
  failed: '生成失败',
  stopped: '已停止',
};

const STYLE_LABELS: Record<string, string> = {
  lavender: '熏衣草紫',
  mint: '薄荷青',
  sand: '暖沙金',
  rose: '玫瑰粉',
  ink: '水墨灰',
  cyber: '赛博紫',
};

const TASK_TYPE_LABELS: Record<string, string> = {
  plan: '需求规划',
  generate: '首次生成',
  modify: '对话修改',
  visual_save: '手动微调保存',
  code_save: '代码编辑保存',
  import: '代码导入部署',
  clone: '社区模板复刻',
};

const TASK_STATUS_LABELS: Record<string, string> = {
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  RUNNING: '执行中',
  QUEUED: '排队中',
  PREPARING: '准备中',
  VERIFYING: '校验中',
  COMMITTING: '提交中',
  CANCELLING: '停止中',
};

const VERSION_SOURCE_LABELS: Record<string, string> = {
  generate: 'AI 首次生成',
  modify: 'AI 对话修改',
  visual: '可视化微调',
  code: '代码编辑器保存',
  restore: '历史版本恢复',
  import: '外部代码导入',
  clone: '社区模板复刻',
  interrupted: '中断前自动保存',
};

const ACCESS_LABELS: Record<string, string> = {
  public: '内网公开',
  login: '员工登录',
  members: '指定人员',
};

const PRESET_LABELS: Record<string, string> = {
  all_rw: '所有人可读写',
  admin_rw: '管理员可改',
  owner_rw: '仅看自己数据',
  submit_admin: '自己提交 · 管理员看全部',
  anon_submit: '免登录提交',
  fn_only: '仅云函数可写',
  custom: '自定义策略',
};

const PROVIDER_LABELS: Record<string, string> = {
  manual: '手动登记',
  agw: 'API 网关导入',
};

const ORIGIN_LABELS: Record<string, string> = {
  preview: '预览环境',
  site: '线上站点',
  test: '连通性测试',
};

const REVIEW_LABELS: Record<string, string> = {
  approved: '已通过',
  pending: '审核中',
  submitted: '已提交',
  rejected: '已退回',
  draft: '草稿',
};

const VISIBILITY_LABELS: Record<string, string> = {
  private: '仅自己可见',
  team: '团队可见',
  public: '全员公开',
};

const UPLOAD_FORMAT_LABELS: Record<string, string> = {
  html: '单文件 HTML',
  zip: 'ZIP 压缩包',
  folder: '本地文件夹',
};

function labelOf(map: Record<string, string>, key: string | null | undefined): string {
  if (!key) return '未知';
  return map[key] ?? key;
}

function calcDelta(cur: number, prev: number): { text: string; tone: 'up' | 'down' | 'flat' } | null {
  if (cur === 0 && prev === 0) return null;
  if (prev === 0) return { text: '新增 ' + fmtCompact(cur), tone: 'up' };
  const pct = Math.round(((cur - prev) / prev) * 1000) / 10;
  if (pct > 0) return { text: `↑ ${pct}%`, tone: 'up' };
  if (pct < 0) return { text: `↓ ${Math.abs(pct)}%`, tone: 'down' };
  return { text: '持平 0%', tone: 'flat' };
}

function downloadJson(filename: string, payload: unknown) {
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export function DashboardPage() {
  const navigate = useNavigate();
  const [scope, setScope] = useState<DashboardScope>('platform');
  const [range, setRange] = useState<DashboardRange>('30d');
  const [section, setSection] = useState<SectionTab>('all');
  const [trendMetric, setTrendMetric] = useState<TrendMetric>('tasks');
  const [chartKind, setChartKind] = useState<'line' | 'bar'>('bar');
  const [appQuery, setAppQuery] = useState('');
  const [appTypeFilter, setAppTypeFilter] = useState('');
  const [appSort, setAppSort] = useState<'pv' | 'versions' | 'tasks' | 'tokens'>('pv');
  const [wsSort, setWsSort] = useState<'apps' | 'tasks' | 'tokens' | 'pv'>('apps');
  const [expandWs, setExpandWs] = useState(false);
  const [expandApps, setExpandApps] = useState(false);

  const q = useQuery({
    queryKey: qk.dashboard(range, scope),
    queryFn: () => api.dashboard(range, scope),
    placeholderData: keepPreviousData,
  });

  const data = q.data;
  const show = (tab: SectionTab) => section === 'all' || section === tab;
  const isPlatform = scope === 'platform';

  const trendConfig = useMemo(() => {
    const daily = data?.trends.daily ?? [];
    switch (trendMetric) {
      case 'tasks':
        return {
          primaryLabel: '成功任务',
          secondaryLabel: '失败任务',
          primaryColor: '#2f9e8f',
          secondaryColor: '#d0465c',
          unit: ' 次',
          points: daily.map<SeriesPoint>((d) => ({ date: d.date, primary: d.tasksSucceeded, secondary: d.tasksFailed })),
        };
      case 'tokens':
        return {
          primaryLabel: 'Token 消耗',
          secondaryLabel: undefined,
          primaryColor: '#4a7bd0',
          unit: '',
          points: daily.map<SeriesPoint>((d) => ({ date: d.date, primary: d.tokens })),
        };
      case 'pv':
        return {
          primaryLabel: '页面浏览量 (PV)',
          secondaryLabel: undefined,
          primaryColor: '#7953dc',
          unit: ' 次',
          points: daily.map<SeriesPoint>((d) => ({ date: d.date, primary: d.pv })),
        };
      case 'apps':
        return {
          primaryLabel: '新建应用数',
          secondaryLabel: '活跃工作空间',
          primaryColor: '#7953dc',
          secondaryColor: '#2f9e8f',
          unit: '',
          points: daily.map<SeriesPoint>((d) => ({ date: d.date, primary: d.appsCreated, secondary: d.activeWorkspaces })),
        };
      case 'versions':
        return {
          primaryLabel: '生成版本数',
          secondaryLabel: '发布部署数',
          primaryColor: '#7953dc',
          secondaryColor: '#d9822b',
          unit: ' 次',
          points: daily.map<SeriesPoint>((d) => ({ date: d.date, primary: d.versions, secondary: d.deployments })),
        };
      case 'activity':
        return {
          primaryLabel: '对话消息数',
          secondaryLabel: undefined,
          primaryColor: '#9b6fd6',
          unit: ' 条',
          points: daily.map<SeriesPoint>((d) => ({ date: d.date, primary: d.messages })),
        };
      case 'calls':
        return {
          primaryLabel: '云函数调用',
          secondaryLabel: '数据源调用',
          primaryColor: '#2f9e8f',
          secondaryColor: '#4a7bd0',
          unit: ' 次',
          points: daily.map<SeriesPoint>((d) => ({ date: d.date, primary: d.fnCalls, secondary: d.sourceCalls })),
        };
      case 'modelCalls':
        return {
          primaryLabel: '模型请求次数',
          secondaryLabel: undefined,
          primaryColor: '#d9822b',
          unit: ' 次',
          points: daily.map<SeriesPoint>((d) => ({ date: d.date, primary: d.modelCalls })),
        };
    }
  }, [data, trendMetric]);

  const sortedWorkspaces = useMemo(() => {
    const list = [...(data?.workspaces ?? [])];
    list.sort((a, b) => {
      if (wsSort === 'tasks') return b.tasks - a.tasks || b.apps - a.apps;
      if (wsSort === 'tokens') return b.tokens - a.tokens || b.tasks - a.tasks;
      if (wsSort === 'pv') return b.pv - a.pv || b.apps - a.apps;
      return b.apps - a.apps || b.tasks - a.tasks;
    });
    return list;
  }, [data, wsSort]);

  const filteredTopApps = useMemo(() => {
    const list = [...(data?.apps.topApps ?? [])];
    const qText = appQuery.trim().toLowerCase();
    const filtered = list.filter((a) => {
      if (appTypeFilter && a.type !== appTypeFilter) return false;
      if (!qText) return true;
      return a.name.toLowerCase().includes(qText) || a.type.toLowerCase().includes(qText) || a.projectKind.toLowerCase().includes(qText) || a.workspaceId.toLowerCase().includes(qText);
    });
    filtered.sort((a, b) => {
      if (appSort === 'versions') return b.versions - a.versions || b.totalPv - a.totalPv;
      if (appSort === 'tasks') return b.tasks - a.tasks || b.totalPv - a.totalPv;
      if (appSort === 'tokens') return b.tokensUsed - a.tokensUsed || b.totalPv - a.totalPv;
      return b.totalPv - a.totalPv || b.versions - a.versions;
    });
    return filtered;
  }, [data, appQuery, appTypeFilter, appSort]);

  const daily = data?.trends.daily ?? [];
  const visibleWorkspaces = expandWs ? sortedWorkspaces : sortedWorkspaces.slice(0, 8);
  const visibleApps = expandApps ? filteredTopApps : filteredTopApps.slice(0, 8);

  return (
    <div className="content-page dash-page">
      {/* 顶部标题区 + 主操作按钮严格左右对齐 */}
      <header className="dash-header-top">
        <div className="dash-title-block">
          <span className="overline">ZHIYE OPERATIONS &amp; ANALYTICS CONSOLE</span>
          <div className="dash-title-row">
            <h1>{isPlatform ? '系统全景管理看板' : '我的工作空间看板'}</h1>
            <span className={`dash-scope-badge ${isPlatform ? 'platform' : 'workspace'}`}>
              {isPlatform ? '全平台汇总口径' : '当前空间隔离口径'}
            </span>
          </div>
          <p>
            {isPlatform
              ? '汇聚全平台工作空间、应用资产、AI 任务编排、模型 Token 成本、云服务合规、数据源与实例运行监控。'
              : `聚焦当前工作空间（${data?.meta.workspaceId ?? '当前会话'}）的应用产出、配额用量、访问流量与任务明细。`}
          </p>
        </div>
        <div className="dash-header-cta">
          <button type="button" className="dash-action-btn" onClick={() => void q.refetch()} disabled={q.isFetching}>
            <Icon name="refresh" className={q.isFetching ? 'spinning' : ''} />
            <span>{q.isFetching ? '刷新中…' : '刷新数据'}</span>
          </button>
          <button
            type="button"
            className="dash-action-btn primary"
            disabled={!data}
            onClick={() => {
              if (!data) return;
              downloadJson(`zhiye-${data.meta.scope}-${data.meta.range}-${data.meta.endDate}.json`, data);
              toast('已导出统计看板完整报表 JSON。');
            }}
          >
            <Icon name="download" />
            <span>导出报表</span>
          </button>
        </div>
      </header>

      {/* 单行控制工具条：口径切换 + 统计窗口 + 时间范围 */}
      <div className="dash-command-bar">
        <div className="dash-command-left">
          <div className="seg-control scope-switch" role="radiogroup" aria-label="看板统计视角">
            {SCOPES.map(([s, label]) => (
              <button key={s} type="button" role="radio" aria-checked={scope === s} className={scope === s ? 'active' : ''} onClick={() => setScope(s)}>
                {label}
              </button>
            ))}
          </div>
          {data ? (
            <span className="dash-window-info">
              {data.meta.startDate} 至 {data.meta.endDate}（{data.meta.days} 天） · 更新于 {fmtTime(data.meta.generatedAt)}
            </span>
          ) : null}
        </div>
        <div className="seg-control" role="radiogroup" aria-label="统计时间范围">
          {RANGES.map(([r, label]) => (
            <button key={r} type="button" role="radio" aria-checked={range === r} className={range === r ? 'active' : ''} onClick={() => setRange(r)}>
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* 维度导航页签 */}
      <nav className="dash-nav-bar" role="tablist" aria-label="统计维度分组">
        {SECTION_TABS.map(([id, label]) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={section === id}
            className={section === id ? 'active' : ''}
            onClick={() => setSection(id)}
          >
            {label}
          </button>
        ))}
      </nav>

      {q.isLoading ? (
        <LoadingState label="正在聚合全系统多维统计数据…" />
      ) : q.error || !data ? (
        <ErrorState message={errorMessage(q.error)} onRetry={() => void q.refetch()} />
      ) : (
        <div className="dash-body">
          {/* 实时活跃任务提示条 */}
          {data.tasks.activeList.length > 0 ? (
            <div className="dash-live-banner" role="status">
              <span className="dash-live-dot" aria-hidden="true" />
              <b>实时运行中（{data.tasks.activeList.length} 个任务）</b>
              <div className="dash-live-items">
                {data.tasks.activeList.slice(0, 4).map((t) => (
                  <button key={t.id} type="button" className="dash-live-chip" onClick={() => navigate('/apps/' + t.appId)}>
                    <span>{t.appName}</span>
                    <em>{labelOf(TASK_TYPE_LABELS, t.type)} · {t.progress}%</em>
                  </button>
                ))}
              </div>
            </div>
          ) : null}

          {/* 1. 核心北极星 KPI（6 卡片等高对齐 + Sparkline + 周期环比） */}
          <section className="dash-section" aria-label="核心全景指标">
            <div className="dash-kpi-grid-6">
              <KpiCard
                title={isPlatform ? '活跃空间与用户' : '我的空间配额'}
                value={isPlatform ? fmtNum(data.overview.workspacesActiveRange) : `${fmtNum(data.overview.appsUsedInMyWs)}/${fmtNum(data.overview.appsLimit)}`}
                unit={isPlatform ? `活跃 / ${fmtNum(data.overview.workspacesProductive)} 有产出（共 ${fmtNum(data.overview.workspacesTotal)}）` : '应用配额已用'}
                accent="#7953dc"
                spark={daily.map((d) => d.activeWorkspaces)}
                details={[
                  isPlatform ? `SSO 用户 ${fmtNum(data.overview.ssoUsersTotal)}` : `空间 ${data.meta.workspaceId}`,
                  `窗口 ${data.meta.days} 天`,
                  `转化应用 ${fmtNum(data.overview.conversationsConverted)}`,
                ]}
              />
              <KpiCard
                title="应用资产规模"
                value={fmtNum(data.overview.appsTotal)}
                unit={`已发布 ${fmtNum(data.overview.appsPublished)} (${data.overview.appsTotal ? Math.round((data.overview.appsPublished / data.overview.appsTotal) * 1000) / 10 : 0}%)`}
                accent="#2f9e8f"
                delta={range === 'all' ? null : calcDelta(data.overview.appsRange, data.overview.appsPrevRange)}
                spark={daily.map((d) => d.appsCreated)}
                details={[
                  `新增 ${fmtNum(data.overview.appsRange)}`,
                  `就绪 ${fmtNum(data.overview.appsReady)}`,
                  `失败 ${fmtNum(data.overview.appsFailed)} · 回收站 ${fmtNum(data.overview.appsDeleted)}`,
                ]}
              />
              <KpiCard
                title="AI 任务吞吐"
                value={fmtNum(data.overview.tasksTotal)}
                unit={`成功率 ${data.overview.taskSuccessRate}%`}
                accent="#4a7bd0"
                delta={range === 'all' ? null : calcDelta(data.overview.tasksRange, data.overview.tasksPrevRange)}
                spark={daily.map((d) => d.tasksTotal)}
                details={[
                  `成功 ${fmtNum(data.overview.tasksSucceeded)} · 失败 ${fmtNum(data.overview.tasksFailed)}`,
                  `P50 ${data.overview.taskP50DurationSec}s / P95 ${data.overview.taskP95DurationSec}s`,
                  `活跃 ${fmtNum(data.overview.tasksActive)}`,
                ]}
              />
              <KpiCard
                title="模型 Token 消耗"
                value={fmtCompact(data.overview.tokensTotal)}
                unit={`调用 ${fmtCompact(data.overview.modelCallsTotal)} 次 · ¥${data.overview.costTotal.toFixed(2)}`}
                accent="#d9822b"
                delta={range === 'all' ? null : calcDelta(data.overview.tokensRange, data.overview.tokensPrevRange)}
                spark={daily.map((d) => d.tokens)}
                details={[
                  `输入 ${fmtCompact(data.overview.tokensInput)}`,
                  `输出 ${fmtCompact(data.overview.tokensOutput)}`,
                  `窗口 ${fmtCompact(data.overview.tokensRange)}`,
                ]}
              />
              <KpiCard
                title="站点访问流量 (PV)"
                value={fmtCompact(data.overview.pvTotal)}
                unit={`今日 ${fmtNum(data.overview.pvToday)} · 昨日 ${fmtNum(data.overview.pvYesterday)}`}
                accent="#9b6fd6"
                delta={range === 'all' ? null : calcDelta(data.overview.pvRange, data.overview.pvPrevRange)}
                spark={daily.map((d) => d.pv)}
                details={[
                  `窗口 PV ${fmtNum(data.overview.pvRange)}`,
                  `日均 ${data.overview.pvAvgDaily}`,
                  `有流量应用 ${fmtNum(data.overview.appsWithPv)}`,
                ]}
              />
              <KpiCard
                title="版本、云与社区生态"
                value={fmtNum(data.overview.versionsTotal)}
                unit={`版本 · ${fmtNum(data.overview.deploymentsTotal)} 次部署`}
                accent="#c7527c"
                spark={daily.map((d) => d.versions)}
                details={[
                  `云服务 ${fmtNum(data.overview.cloudEnabledApps)} · 表 ${fmtNum(data.overview.cloudTablesTotal)}`,
                  `云函数 ${fmtNum(data.overview.fnCallsTotal)} · 数据源 ${fmtNum(data.overview.sourceCallsTotal)}`,
                  `反馈 ${fmtNum(data.overview.helpFeedbackTotal)} · 社区 ${fmtNum(data.overview.templatesPublished)}`,
                ]}
              />
            </div>
          </section>

          {/* 2. 转化漏斗 + 多维每日趋势 */}
          {show('apps') || show('tasks') ? (
            <section className="dash-section" aria-label="转化漏斗与每日趋势">
              <div className="dash-hero-split">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h2>全链路创作转化漏斗</h2>
                      <p>对话构想 → 创建应用 → 就绪 → 发布 → 访问 → 启用云服务</p>
                    </div>
                  </header>
                  <ConversionFunnel steps={data.funnel} />
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h2>多维度每日趋势</h2>
                      <p>按 Asia/Shanghai 时区对每日核心指标补零对齐。</p>
                    </div>
                    <div className="seg-control small" role="radiogroup" aria-label="图表形式">
                      <button type="button" role="radio" aria-checked={chartKind === 'line'} className={chartKind === 'line' ? 'active' : ''} onClick={() => setChartKind('line')}>
                        折线图
                      </button>
                      <button type="button" role="radio" aria-checked={chartKind === 'bar'} className={chartKind === 'bar' ? 'active' : ''} onClick={() => setChartKind('bar')}>
                        柱状图
                      </button>
                    </div>
                  </header>
                  <div className="dash-metric-pills" role="tablist" aria-label="趋势指标">
                    {TREND_METRICS.map(([m, label]) => (
                      <button key={m} type="button" role="tab" aria-selected={trendMetric === m} className={trendMetric === m ? 'active' : ''} onClick={() => setTrendMetric(m)}>
                        {label}
                      </button>
                    ))}
                  </div>
                  <MultiTrendChart
                    data={trendConfig.points}
                    kind={chartKind}
                    primaryLabel={trendConfig.primaryLabel}
                    secondaryLabel={trendConfig.secondaryLabel}
                    primaryColor={trendConfig.primaryColor}
                    secondaryColor={trendConfig.secondaryColor}
                    unit={trendConfig.unit}
                  />
                </div>
              </div>
            </section>
          ) : null}

          {/* 3. 工作空间/用户生产力排行 + 应用资产画像 */}
          {show('apps') ? (
            <section className="dash-section" aria-label="工作空间与应用资产">
              <SectionTitle
                title={isPlatform ? '工作空间生产力排行与应用资产画像' : '我的应用资产画像与排行'}
                sub={isPlatform ? '剔除零产出空壳空间，按应用产出、任务、Token 与访问量排序。' : '查看当前工作空间的应用类型、项目形态、阶段分布与单应用流量。'}
              />

              {isPlatform ? (
                <div className="dash-panel">
                  <header className="dash-panel-head">
                    <div>
                      <h3>活跃工作空间 / 用户生产力排行（Top {sortedWorkspaces.length}）</h3>
                      <p>全库共 {fmtNum(data.overview.workspacesTotal)} 个工作空间，其中 {fmtNum(data.overview.workspacesProductive)} 个有真实产出。</p>
                    </div>
                    <div className="dash-toolbar-group">
                      <div className="seg-control unified" role="radiogroup" aria-label="工作空间排序">
                        {(
                          [
                            ['apps', '按应用数'],
                            ['tasks', '按任务数'],
                            ['tokens', '按 Token'],
                            ['pv', '按 PV'],
                          ] as const
                        ).map(([k, lbl]) => (
                          <button key={k} type="button" role="radio" aria-checked={wsSort === k} className={wsSort === k ? 'active' : ''} onClick={() => setWsSort(k)}>
                            {lbl}
                          </button>
                        ))}
                      </div>
                    </div>
                  </header>
                  {sortedWorkspaces.length === 0 ? (
                    <p className="dash-empty">暂无有产出的工作空间</p>
                  ) : (
                    <>
                      <div className="dash-table-wrap">
                        <table className="dash-table">
                          <thead>
                            <tr>
                              <th scope="col">工作空间 / 归属人</th>
                              <th scope="col">应用 (已发布)</th>
                              <th scope="col">任务 (窗口新增)</th>
                              <th scope="col">成功率</th>
                              <th scope="col">Token 消耗</th>
                              <th scope="col">估算成本</th>
                              <th scope="col">PV (今日)</th>
                              <th scope="col">最近活跃</th>
                            </tr>
                          </thead>
                          <tbody>
                            {visibleWorkspaces.map((w) => (
                              <tr key={w.workspaceId} className={w.workspaceId === data.meta.workspaceId ? 'dash-row-highlight' : ''}>
                                <td>
                                  <code>{w.workspaceId}</code>
                                  {w.ownerName ? <span className="dash-tag" style={{ marginLeft: 6 }}>{w.ownerName}</span> : null}
                                  {w.workspaceId === data.meta.workspaceId ? <span className="dash-pill ok" style={{ marginLeft: 6 }}>当前</span> : null}
                                </td>
                                <td><b>{fmtNum(w.apps)}</b> <span className="dash-muted">({fmtNum(w.published)})</span></td>
                                <td>{fmtNum(w.tasks)} <span className="dash-muted">({fmtNum(w.rangeTasks)})</span></td>
                                <td><span className={w.successRate >= 80 ? 'dash-ok' : w.successRate < 50 && w.tasks > 0 ? 'dash-err' : ''}>{w.successRate}%</span></td>
                                <td><b>{fmtCompact(w.tokens)}</b></td>
                                <td>¥{w.cost.toFixed(4)}</td>
                                <td><b>{fmtNum(w.pv)}</b> <span className="dash-muted">({fmtNum(w.todayPv)})</span></td>
                                <td className="dash-nowrap">{w.lastActiveAt ? fmtTime(w.lastActiveAt) : '-'}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                      {sortedWorkspaces.length > 8 ? (
                        <div className="dash-table-footer">
                          <button type="button" className="text-button" onClick={() => setExpandWs((v) => !v)}>
                            {expandWs ? '收起至 Top 8' : `展开全部 Top ${sortedWorkspaces.length} 工作空间`}
                          </button>
                        </div>
                      ) : null}
                    </>
                  )}
                </div>
              ) : null}

              <div className="dash-grid-2">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>按创造类型分布</h3>
                      <p>各业务类型的数量、已发布数与累计 PV</p>
                    </div>
                  </header>
                  <DonutChart
                    centerLabel="应用总数"
                    slices={data.apps.byType.map<DonutSlice>((x, i) => ({
                      key: x.key,
                      label: `${x.key} (发布 ${x.published} · PV ${fmtCompact(x.pv)})`,
                      value: x.count,
                      color: CHART_PALETTE[i % CHART_PALETTE.length],
                    }))}
                  />
                </div>
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>按项目形态分布</h3>
                      <p>React 模板 / Vue 3 / Vite 导入 / 静态页 / 脚本 / 技能包</p>
                    </div>
                  </header>
                  <DonutChart
                    centerLabel="项目形态"
                    slices={data.apps.byProjectKind.map<DonutSlice>((x, i) => ({
                      key: x.key,
                      label: labelOf(KIND_LABELS, x.key),
                      value: x.count,
                      color: CHART_PALETTE[(i + 1) % CHART_PALETTE.length],
                    }))}
                  />
                </div>
              </div>

              <div className="dash-grid-3">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>生命周期阶段</h3>
                      <p>当前所处 phase 分布</p>
                    </div>
                  </header>
                  <BreakdownBars
                    items={data.apps.byPhase.map((x, i) => ({
                      key: x.key,
                      label: labelOf(PHASE_LABELS, x.key),
                      value: x.count,
                      color: x.key === 'ready' ? '#2f9e8f' : x.key === 'failed' ? '#d0465c' : CHART_PALETTE[i % CHART_PALETTE.length],
                    }))}
                  />
                </div>
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>创建模式与来源</h3>
                      <p>生成深度与创造/导入/复刻来源</p>
                    </div>
                  </header>
                  <BreakdownBars
                    items={data.apps.byMode.map((x, i) => ({
                      key: 'mode-' + x.key,
                      label: `模式 · ${x.key}`,
                      value: x.count,
                      color: CHART_PALETTE[i % CHART_PALETTE.length],
                    }))}
                  />
                  <div className="dash-sub-block">
                    <h4>应用来源</h4>
                    <BreakdownBars
                      items={data.apps.bySource.map((x) => ({
                        key: 'src-' + x.key,
                        label: x.key === 'import' ? '代码导入' : x.key === 'clone' ? '社区复刻' : '对话创造',
                        value: x.count,
                        color: x.key === 'import' ? '#4a7bd0' : '#2f9e8f',
                      }))}
                    />
                  </div>
                </div>
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>设计风格偏好</h3>
                      <p>默认配色主题使用统计</p>
                    </div>
                  </header>
                  <BreakdownBars
                    items={data.apps.byStyle.map((x, i) => ({
                      key: x.key,
                      label: labelOf(STYLE_LABELS, x.key),
                      value: x.count,
                      color: CHART_PALETTE[(i + 2) % CHART_PALETTE.length],
                    }))}
                  />
                </div>
              </div>

              <div className="dash-panel">
                <header className="dash-panel-head">
                  <div>
                    <h3>热门应用综合明细排行（Top {filteredTopApps.length}）</h3>
                    <p>按 PV、版本迭代次数、任务数与 Token 消耗排行。</p>
                  </div>
                  <div className="dash-toolbar-group">
                    <label className="dash-search">
                      <Icon name="search" />
                      <input
                        aria-label="搜索应用排行"
                        placeholder="按名称、空间或形态筛选"
                        value={appQuery}
                        onChange={(e) => setAppQuery(e.target.value)}
                      />
                    </label>
                    <select
                      aria-label="按类型筛选"
                      className="dash-select"
                      value={appTypeFilter}
                      onChange={(e) => setAppTypeFilter(e.target.value)}
                    >
                      <option value="">全部类型</option>
                      {data.apps.byType.map((t) => (
                        <option key={t.key} value={t.key}>{t.key}</option>
                      ))}
                    </select>
                    <div className="seg-control unified" role="radiogroup" aria-label="排序维度">
                      {(
                        [
                          ['pv', '按 PV'],
                          ['versions', '按版本'],
                          ['tasks', '按任务'],
                          ['tokens', '按 Token'],
                        ] as const
                      ).map(([k, lbl]) => (
                        <button key={k} type="button" role="radio" aria-checked={appSort === k} className={appSort === k ? 'active' : ''} onClick={() => setAppSort(k)}>
                          {lbl}
                        </button>
                      ))}
                    </div>
                  </div>
                </header>
                {filteredTopApps.length === 0 ? (
                  <p className="dash-empty">暂无符合条件的应用数据</p>
                ) : (
                  <>
                    <div className="dash-table-wrap">
                      <table className="dash-table">
                        <thead>
                          <tr>
                            <th scope="col">应用名称</th>
                            {isPlatform ? <th scope="col">所属空间</th> : null}
                            <th scope="col">类型 / 形态</th>
                            <th scope="col">状态</th>
                            <th scope="col">云服务 / 访问</th>
                            <th scope="col">版本</th>
                            <th scope="col">任务</th>
                            <th scope="col">Token</th>
                            <th scope="col">今日 / 累计 PV</th>
                          </tr>
                        </thead>
                        <tbody>
                          {visibleApps.map((a) => (
                            <tr key={a.id}>
                              <td>
                                {!isPlatform || a.workspaceId === data.meta.workspaceId ? (
                                  <button type="button" className="dash-link" onClick={() => navigate('/apps/' + a.id)}>
                                    {a.name}
                                  </button>
                                ) : (
                                  <b>{a.name}</b>
                                )}
                              </td>
                              {isPlatform ? <td><code>{a.workspaceId}</code></td> : null}
                              <td>
                                <span className="dash-tag">{a.type}</span>{' '}
                                <span className="dash-muted">{labelOf(KIND_LABELS, a.projectKind)}</span>
                              </td>
                              <td>
                                <span className={`dash-pill ${a.published ? 'ok' : a.phase === 'failed' ? 'err' : ''}`}>
                                  {a.published ? '已发布' : labelOf(PHASE_LABELS, a.phase)}
                                </span>
                              </td>
                              <td>
                                {a.cloudEnabled ? <span className="dash-pill cloud">云服务</span> : <span className="dash-muted">未开启</span>}
                                {' · '}
                                <span>{labelOf(ACCESS_LABELS, a.accessLevel)}</span>
                              </td>
                              <td>{fmtNum(a.versions)}</td>
                              <td>{fmtNum(a.tasks)}</td>
                              <td>{fmtCompact(a.tokensUsed)}</td>
                              <td>{fmtNum(a.todayPv)} / <b>{fmtNum(a.totalPv)}</b></td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    {filteredTopApps.length > 8 ? (
                      <div className="dash-table-footer">
                        <button type="button" className="text-button" onClick={() => setExpandApps((v) => !v)}>
                          {expandApps ? '收起至 Top 8' : `展开全部 Top ${filteredTopApps.length} 应用`}
                        </button>
                      </div>
                    ) : null}
                  </>
                )}
              </div>
            </section>
          ) : null}

          {/* 4. AI 任务诊断、24h 时段分布与模型经济学 */}
          {show('tasks') ? (
            <section className="dash-section" aria-label="AI 任务诊断与模型分析">
              <SectionTitle title="AI 任务编排、失败诊断与模型成本" sub="涵盖 24 小时活跃峰谷、失败错误码与真实报错样本、各任务类型 P50/P95 耗时及各模型 Token 经济学。" />

              <div className="dash-grid-2">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>任务执行健康度与交互队列</h3>
                      <p>
                        总任务 {fmtNum(data.overview.tasksTotal)} · 重试 {fmtNum(data.tasks.retries)} · Steer {fmtNum(data.tasks.steerCount)} · 检查点 {fmtNum(data.tasks.checkpointsCount)} · 事件流 {fmtNum(data.tasks.eventsCount)}
                      </p>
                    </div>
                  </header>
                  <SegmentedStrip
                    segments={[
                      { key: 'ok', label: '成功', value: data.overview.tasksSucceeded, color: '#2f9e8f' },
                      { key: 'fail', label: '失败', value: data.overview.tasksFailed, color: '#d0465c' },
                      { key: 'cancel', label: '已取消', value: data.overview.tasksCancelled, color: '#8f8c9c' },
                      { key: 'active', label: '执行/排队中', value: data.overview.tasksActive, color: '#7953dc' },
                    ]}
                  />
                  <div className="dash-mini-stats compact-4">
                    <div><span>平均耗时</span><b>{data.overview.taskAvgDurationSec}s</b></div>
                    <div><span>P50 耗时</span><b>{data.overview.taskP50DurationSec}s</b></div>
                    <div><span>P95 耗时</span><b>{data.overview.taskP95DurationSec}s</b></div>
                    <div><span>排队 (待/已/云)</span><b>{data.tasks.queue.pending}/{data.tasks.queue.sent}/{data.tasks.queue.enableCloudCount}</b></div>
                  </div>
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>24 小时任务活跃时段分布</h3>
                      <p>识别全天使用高峰与夜间批处理时段（红色部分为失败任务占比）</p>
                    </div>
                  </header>
                  <HourlyBarChart data={data.hourly} />
                </div>
              </div>

              <div className="dash-grid-2">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>各类型任务效能矩阵</h3>
                      <p>不同任务类型的数量、成功率、平均与最大耗时、平均 Token</p>
                    </div>
                  </header>
                  {data.tasks.byType.length === 0 ? (
                    <p className="dash-empty">暂无任务记录</p>
                  ) : (
                    <div className="dash-table-wrap">
                      <table className="dash-table">
                        <thead>
                          <tr>
                            <th scope="col">任务类型</th>
                            <th scope="col">总数</th>
                            <th scope="col">成 / 败 / 消</th>
                            <th scope="col">成功率</th>
                            <th scope="col">平均 / 最大耗时</th>
                            <th scope="col">均 Token</th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.tasks.byType.map((t) => (
                            <tr key={t.type}>
                              <td><b>{labelOf(TASK_TYPE_LABELS, t.type)}</b></td>
                              <td>{fmtNum(t.total)}</td>
                              <td>
                                <span className="dash-ok">{t.succeeded}</span> / <span className="dash-err">{t.failed}</span> / <span className="dash-muted">{t.cancelled}</span>
                              </td>
                              <td>{t.successRate}%</td>
                              <td>{fmtDurationMs(t.avgDurationMs)} / {fmtDurationMs(t.maxDurationMs)}</td>
                              <td>{fmtCompact(t.avgTokens)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>失败原因码聚合与报错样本</h3>
                      <p>按错误码聚合失败分布，悬停可查看完整报错摘要</p>
                    </div>
                  </header>
                  <BreakdownBars
                    emptyText="暂无失败任务（运行健康）"
                    items={data.tasks.errorCodes.map((e) => ({
                      key: e.key,
                      label: e.sample ? `${e.key} · ${e.sample}` : e.key,
                      value: e.count,
                      color: '#d0465c',
                    }))}
                  />
                  {data.tasks.byStatus.length > 0 ? (
                    <div className="dash-sub-block">
                      <h4>全量状态计数</h4>
                      <div className="dash-chip-grid">
                        {data.tasks.byStatus.map((s) => (
                          <span key={s.key} className="dash-kv-chip">
                            <span>{labelOf(TASK_STATUS_LABELS, s.key)}</span>
                            <b>{fmtNum(s.count)}</b>
                          </span>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </div>
              </div>

              <div className="dash-grid-2">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>模型调用与 Token 经济学</h3>
                      <p>按 Provider 与 Model 聚合调用数、Input/Output Token、均次消耗与费用</p>
                    </div>
                  </header>
                  {data.llm.byModel.length === 0 ? (
                    <p className="dash-empty">暂无模型调用记账数据</p>
                  ) : (
                    <div className="dash-table-wrap">
                      <table className="dash-table">
                        <thead>
                          <tr>
                            <th scope="col">供应商 / 模型</th>
                            <th scope="col">调用次数</th>
                            <th scope="col">输入 / 输出</th>
                            <th scope="col">总 Token</th>
                            <th scope="col">均次 Token</th>
                            <th scope="col">估算成本</th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.llm.byModel.map((m) => (
                            <tr key={`${m.provider}:${m.model}`}>
                              <td>
                                <span className="dash-tag">{m.provider}</span> <b>{m.model}</b>
                              </td>
                              <td>{fmtNum(m.calls)}</td>
                              <td>{fmtCompact(m.inputTokens)} / {fmtCompact(m.outputTokens)}</td>
                              <td><b>{fmtCompact(m.totalTokens)}</b></td>
                              <td>{fmtCompact(m.avgTokensPerCall)}</td>
                              <td>¥{m.cost.toFixed(4)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                  <div className="dash-sub-block">
                    <h4>五星 AI 岛个人套餐额度</h4>
                    {!data.llm.quota.enabled ? (
                      <p className="dash-muted-line">当前为本地统一模型模式（未开启五星 AI 岛对接）。</p>
                    ) : !data.llm.quota.available ? (
                      <p className="dash-muted-line">已开启五星对接，当前请求未带平台令牌或额度查询暂不可用。</p>
                    ) : (
                      <div className="dash-chip-grid">
                        <span className="dash-kv-chip"><span>套餐</span><b>{data.llm.quota.packageName || '默认'}</b></span>
                        <span className="dash-kv-chip"><span>状态</span><b>{data.llm.quota.status || '正常'}</b></span>
                        <span className="dash-kv-chip"><span>已用</span><b>{fmtCompact(data.llm.quota.usedTokens)}</b></span>
                        <span className="dash-kv-chip"><span>剩余</span><b>{fmtCompact(data.llm.quota.remainingTokens)}</b></span>
                      </div>
                    )}
                  </div>
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>各任务阶段 Token 分摊与模型偏好</h3>
                      <p>规划、首次生成、修改各自占用的模型资源</p>
                    </div>
                  </header>
                  <BreakdownBars
                    emptyText="暂无 Token 分摊数据"
                    items={data.llm.byTaskType.map((t, i) => ({
                      key: t.taskType,
                      label: labelOf(TASK_TYPE_LABELS, t.taskType),
                      value: t.totalTokens,
                      extra: `${fmtNum(t.calls)} 次 · ¥${t.cost.toFixed(2)}`,
                      color: CHART_PALETTE[i % CHART_PALETTE.length],
                    }))}
                  />
                  {data.llm.byAppModelSetting.length > 0 ? (
                    <div className="dash-sub-block">
                      <h4>应用默认模型配置分布</h4>
                      <div className="dash-chip-grid">
                        {data.llm.byAppModelSetting.map((x) => (
                          <span key={x.key} className="dash-kv-chip">
                            <span>{x.key === 'auto' ? '自动 / 默认' : x.key}</span>
                            <b>{fmtNum(x.count)}</b>
                          </span>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </div>
              </div>

              <div className="dash-grid-2">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>最近失败任务诊断（最新 10 条）</h3>
                      <p>展示失败应用、错误码、报错详情与耗时（滚动查看完整列表）</p>
                    </div>
                  </header>
                  {data.tasks.recentFailures.length === 0 ? (
                    <p className="dash-empty">当前范围暂无失败任务</p>
                  ) : (
                    <div className="dash-scroll-list">
                      <ul className="dash-error-list">
                        {data.tasks.recentFailures.map((f) => (
                          <li key={f.id}>
                            <div className="dash-err-head">
                              <b>{f.appName} · {labelOf(TASK_TYPE_LABELS, f.type)}</b>
                              <span className="dash-pill err">{f.errorCode || 'UNKNOWN'}</span>
                            </div>
                            <span>{f.errorMessage || f.phaseLabel || '无详细错误信息'}</span>
                            <small>{fmtTime(f.createdAt)} · 空间 {f.workspaceId} · 耗时 {fmtDurationMs(f.durationMs)} · Token {fmtCompact(f.tokenUsed)}</small>
                          </li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>最近任务执行流水（最新 15 条）</h3>
                      <p>实时记录每次规划、生成、修改、代码保存的耗时与 Token</p>
                    </div>
                  </header>
                  {data.tasks.recentTasks.length === 0 ? (
                    <p className="dash-empty">暂无最近任务</p>
                  ) : (
                    <div className="dash-scroll-list">
                      <div className="dash-table-wrap">
                        <table className="dash-table">
                          <thead>
                            <tr>
                              <th scope="col">时间</th>
                              <th scope="col">应用</th>
                              <th scope="col">类型</th>
                              <th scope="col">状态</th>
                              <th scope="col">耗时 / Token</th>
                            </tr>
                          </thead>
                          <tbody>
                            {data.tasks.recentTasks.map((t) => (
                              <tr key={t.id}>
                                <td className="dash-nowrap">{fmtTime(t.createdAt)}</td>
                                <td>
                                  {!isPlatform || t.workspaceId === data.meta.workspaceId ? (
                                    <button type="button" className="dash-link" onClick={() => navigate('/apps/' + t.appId)}>
                                      {t.appName}
                                    </button>
                                  ) : (
                                    <b>{t.appName}</b>
                                  )}
                                </td>
                                <td>{labelOf(TASK_TYPE_LABELS, t.type)}</td>
                                <td>
                                  <span className={`dash-pill ${t.status === 'SUCCEEDED' ? 'ok' : t.status === 'FAILED' ? 'err' : ''}`}>
                                    {labelOf(TASK_STATUS_LABELS, t.status)}
                                  </span>
                                </td>
                                <td>{fmtDurationMs(t.durationMs)} / {fmtCompact(t.tokenUsed)}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </section>
          ) : null}

          {/* 5. 版本演进、云服务与外部数据源 */}
          {show('cloud') ? (
            <section className="dash-section" aria-label="版本、云服务与数据源">
              <SectionTitle title="版本演进、云服务合规与外部数据源" sub="涵盖 Git 版本来源、构建部署、云数据库权限策略、站内访问控制、Deno 云函数日志及 API 网关数据源调用。" />

              <div className="dash-grid-3">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>版本来源与构建部署</h3>
                      <p>共 {fmtNum(data.overview.versionsTotal)} 个版本 · {fmtNum(data.versions.buildsTotal)} 次构建</p>
                    </div>
                  </header>
                  <BreakdownBars
                    items={data.versions.bySource.map((v, i) => ({
                      key: v.key,
                      label: labelOf(VERSION_SOURCE_LABELS, v.key),
                      value: v.count,
                      color: CHART_PALETTE[i % CHART_PALETTE.length],
                    }))}
                  />
                  <div className="dash-mini-stats compact-3">
                    <div><span>已收藏</span><b>{fmtNum(data.versions.favoritedCount)}</b></div>
                    <div><span>带备注</span><b>{fmtNum(data.versions.notedCount)}</b></div>
                    <div><span>历史回退</span><b>{fmtNum(data.versions.restoredCount)}</b></div>
                    <div><span>可编辑构建</span><b>{fmtNum(data.versions.buildsEditable)}</b></div>
                    <div><span>成功部署</span><b>{fmtNum(data.versions.deploymentsSucceeded)}</b></div>
                    <div><span>未保存草稿</span><b>{fmtNum(data.versions.pendingDrafts)}</b></div>
                  </div>
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>云服务、数据表与访问控制</h3>
                      <p>已启用 {fmtNum(data.cloud.enabledApps)} 个 · 云表 {fmtNum(data.cloud.tablesCount)} 张 · 站内会话 {fmtNum(data.cloud.activeSiteSessions)}</p>
                    </div>
                  </header>
                  <DonutChart
                    centerLabel="访问级别"
                    slices={data.cloud.siteAccessLevels.map((x, i) => ({
                      key: x.key,
                      label: labelOf(ACCESS_LABELS, x.key),
                      value: x.count,
                      color: CHART_PALETTE[i % CHART_PALETTE.length],
                    }))}
                  />
                  {data.cloud.tablePolicies.length > 0 ? (
                    <div className="dash-sub-block">
                      <h4>云表权限预设分布</h4>
                      <BreakdownBars
                        items={data.cloud.tablePolicies.map((p, i) => ({
                          key: p.key,
                          label: labelOf(PRESET_LABELS, p.key),
                          value: p.count,
                          color: CHART_PALETTE[(i + 2) % CHART_PALETTE.length],
                        }))}
                      />
                    </div>
                  ) : null}
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>数据源与 API 网关 (AGW)</h3>
                      <p>已登记外部查询接口与绑定覆盖统计</p>
                    </div>
                  </header>
                  <div className="dash-mini-stats compact-2" style={{ marginBottom: 14 }}>
                    <div><span>空间自建源</span><b>{fmtNum(data.dataSources.workspaceCount)}</b></div>
                    <div><span>平台公共源</span><b>{fmtNum(data.dataSources.platformCount)}</b></div>
                    <div><span>已绑定应用</span><b>{fmtNum(data.dataSources.boundAppsCount)}</b></div>
                    <div><span>绑定关系数</span><b>{fmtNum(data.dataSources.totalBindings)}</b></div>
                  </div>
                  <BreakdownBars
                    emptyText="暂未登记数据源"
                    items={[
                      ...data.dataSources.byProvider.map((p, i) => ({
                        key: 'prov-' + p.key,
                        label: `来源 · ${labelOf(PROVIDER_LABELS, p.key)}`,
                        value: p.count,
                        color: CHART_PALETTE[i % CHART_PALETTE.length],
                      })),
                      ...data.dataSources.byMethod.map((m) => ({
                        key: 'm-' + m.key,
                        label: `方法 · ${m.key}`,
                        value: m.count,
                        color: '#4a7bd0',
                      })),
                      ...data.dataSources.byAuthType.map((a) => ({
                        key: 'auth-' + a.key,
                        label: `鉴权 · ${a.key}`,
                        value: a.count,
                        color: '#9b6fd6',
                      })),
                    ]}
                  />
                </div>
              </div>

              <div className="dash-grid-2">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>云函数运行监控（Deno 宿主）</h3>
                      <p>
                        总调用 {fmtNum(data.cloud.functions.totalCalls)} · 成功率 {data.cloud.functions.successRate}% · 平均 {data.cloud.functions.avgDurationMs} ms · P95 {data.cloud.functions.p95DurationMs} ms
                      </p>
                    </div>
                  </header>
                  {data.cloud.functions.byName.length === 0 ? (
                    <p className="dash-empty">暂无云函数调用记录</p>
                  ) : (
                    <div className="dash-table-wrap">
                      <table className="dash-table">
                        <thead>
                          <tr>
                            <th scope="col">函数名</th>
                            <th scope="col">调用数</th>
                            <th scope="col">异常数</th>
                            <th scope="col">平均耗时</th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.cloud.functions.byName.map((fn) => (
                            <tr key={fn.name}>
                              <td><code>{fn.name}</code></td>
                              <td>{fmtNum(fn.calls)}</td>
                              <td className={fn.errors > 0 ? 'dash-err' : ''}>{fmtNum(fn.errors)}</td>
                              <td>{fn.avgDurationMs} ms</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                  {data.cloud.functions.recentErrors.length > 0 ? (
                    <div className="dash-sub-block">
                      <h4>最近云函数报错</h4>
                      <ul className="dash-error-list">
                        {data.cloud.functions.recentErrors.map((err, i) => (
                          <li key={i}>
                            <b>{err.appName} / {err.name}</b>
                            <span>{err.error || '执行异常'}</span>
                            <small>{fmtTime(err.at)} · {err.durationMs}ms</small>
                          </li>
                        ))}
                      </ul>
                    </div>
                  ) : null}
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>数据源代理调用监控</h3>
                      <p>
                        总调用 {fmtNum(data.dataSources.calls.totalCalls)} · 成功率 {data.dataSources.calls.successRate}% · 平均耗时 {data.dataSources.calls.avgDurationMs} ms
                      </p>
                    </div>
                  </header>
                  {data.dataSources.calls.byOrigin.length > 0 ? (
                    <div className="dash-chip-grid" style={{ marginBottom: 12 }}>
                      {data.dataSources.calls.byOrigin.map((o) => (
                        <span key={o.origin} className="dash-kv-chip">
                          <span>{labelOf(ORIGIN_LABELS, o.origin)}</span>
                          <b>{fmtNum(o.calls)}（错 {o.errors}）</b>
                        </span>
                      ))}
                    </div>
                  ) : null}
                  {data.dataSources.calls.topSources.length === 0 ? (
                    <p className="dash-empty">暂无数据源调用记录</p>
                  ) : (
                    <div className="dash-table-wrap">
                      <table className="dash-table">
                        <thead>
                          <tr>
                            <th scope="col">数据源</th>
                            <th scope="col">接入方式</th>
                            <th scope="col">调用 / 报错</th>
                            <th scope="col">均耗时</th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.dataSources.calls.topSources.map((s) => (
                            <tr key={s.id}>
                              <td><b>{s.name}</b> <small className="dash-muted">({s.key})</small></td>
                              <td><span className="dash-tag">{labelOf(PROVIDER_LABELS, s.provider)}</span></td>
                              <td>{fmtNum(s.calls)} / <span className={s.errors > 0 ? 'dash-err' : ''}>{fmtNum(s.errors)}</span></td>
                              <td>{s.avgDurationMs} ms</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              </div>
            </section>
          ) : null}

          {/* 6. 会话交互、素材导入、小织反馈与生态社区 */}
          {show('interactions') ? (
            <section className="dash-section" aria-label="会话、反馈与生态社区">
              <SectionTitle title="对话交互、用户反馈与生态社区" sub="统计创造页多轮会话转化、工作台精细修改行为、附件与导入分布、小织问题上报内容、SkillHub 与灵感社区热度。" />

              <div className="dash-grid-3">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>创造页会话 &amp; 文档产出</h3>
                      <p>会话到应用转化率 {data.interactions.conversations.conversionRate}%</p>
                    </div>
                  </header>
                  <div className="dash-mini-stats compact-2">
                    <div><span>创造会话总数</span><b>{fmtNum(data.interactions.conversations.total)}</b></div>
                    <div><span>已转为应用</span><b>{fmtNum(data.interactions.conversations.convertedToApp)}</b></div>
                    <div><span>用户提问条数</span><b>{fmtNum(data.interactions.conversations.userMessages)}</b></div>
                    <div><span>AI 回复条数</span><b>{fmtNum(data.interactions.conversations.assistantMessages)}</b></div>
                    <div><span>含深度思考回复</span><b>{fmtNum(data.interactions.conversations.thinkingMessages)}</b></div>
                    <div><span>产出文件 / 版本</span><b>{fmtNum(data.interactions.conversations.filesTotal)} / {fmtNum(data.interactions.conversations.fileVersionsTotal)}</b></div>
                  </div>
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>工作台协作 &amp; 小织帮助</h3>
                      <p>共 {fmtNum(data.interactions.workbench.totalMessages)} 条工作台消息</p>
                    </div>
                  </header>
                  <div className="dash-mini-stats compact-2">
                    <div><span>用户指令 / AI 回复</span><b>{fmtNum(data.interactions.workbench.userMessages)} / {fmtNum(data.interactions.workbench.assistantMessages)}</b></div>
                    <div><span>系统提示消息</span><b>{fmtNum(data.interactions.workbench.systemMessages)}</b></div>
                    <div><span>定点元素修改</span><b>{fmtNum(data.interactions.workbench.elementContextMessages)}</b></div>
                    <div><span>挂载技能 / 附件</span><b>{fmtNum(data.interactions.workbench.skillAttachedMessages)} / {fmtNum(data.interactions.workbench.assetAttachedMessages)}</b></div>
                    <div><span>小织会话 / 提问</span><b>{fmtNum(data.interactions.help.conversations)} / {fmtNum(data.interactions.help.userQuestions)}</b></div>
                    <div><span>问题上报反馈</span><b>{fmtNum(data.interactions.help.feedbackCount)}</b></div>
                  </div>
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>素材附件与代码导入</h3>
                      <p>素材 {fmtNum(data.interactions.assets.total)} 个（{fmtBytes(data.interactions.assets.totalBytes)}）· 上传 {fmtNum(data.interactions.assets.uploadsTotal)} 次</p>
                    </div>
                  </header>
                  <BreakdownBars
                    emptyText="暂无上传素材"
                    items={data.interactions.assets.byCategory.map((c, i) => ({
                      key: 'cat-' + c.category,
                      label: c.category,
                      value: c.count,
                      extra: fmtBytes(c.bytes),
                      color: CHART_PALETTE[i % CHART_PALETTE.length],
                    }))}
                  />
                  {data.interactions.assets.uploadsByFormat.length > 0 ? (
                    <div className="dash-sub-block">
                      <h4>代码导入格式</h4>
                      <BreakdownBars
                        items={data.interactions.assets.uploadsByFormat.map((u, i) => ({
                          key: 'up-' + u.key,
                          label: labelOf(UPLOAD_FORMAT_LABELS, u.key),
                          value: u.count,
                          color: CHART_PALETTE[(i + 3) % CHART_PALETTE.length],
                        }))}
                      />
                    </div>
                  ) : null}
                </div>
              </div>

              <div className="dash-grid-3">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>用户问题上报反馈（最新 10 条）</h3>
                      <p>来自小织「问题上报」的真实员工反馈</p>
                    </div>
                  </header>
                  {data.interactions.help.recentFeedbacks.length === 0 ? (
                    <p className="dash-empty">暂无问题上报反馈</p>
                  ) : (
                    <ul className="dash-feedback-list">
                      {data.interactions.help.recentFeedbacks.map((fb) => (
                        <li key={fb.id}>
                          <p>{fb.content}</p>
                          <small>{fmtTime(fb.createdAt)} · 空间 {fb.workspaceId}</small>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>技能资产与 SkillHub 市场</h3>
                      <p>
                        内置 {fmtNum(data.ecosystem.skills.builtinCount)} · 自建 {fmtNum(data.ecosystem.skills.selfBuiltCount)} · 市场安装 {fmtNum(data.ecosystem.skills.hubInstalledCount)}（{fmtBytes(data.ecosystem.skills.hubInstalledBytes)}）
                      </p>
                    </div>
                  </header>
                  <h4 className="dash-sub-h">SkillHub 发布记录（{fmtNum(data.ecosystem.skills.publicationsCount)} 次）</h4>
                  {data.ecosystem.skills.publicationsCount === 0 ? (
                    <p className="dash-empty">暂无发布到 SkillHub 的记录</p>
                  ) : (
                    <BreakdownBars
                      items={[
                        ...data.ecosystem.skills.publicationsByReview.map((r, i) => ({
                          key: 'rev-' + r.key,
                          label: `审核 · ${labelOf(REVIEW_LABELS, r.key)}`,
                          value: r.count,
                          color: CHART_PALETTE[i % CHART_PALETTE.length],
                        })),
                        ...data.ecosystem.skills.publicationsByVisibility.map((v) => ({
                          key: 'vis-' + v.key,
                          label: `范围 · ${labelOf(VISIBILITY_LABELS, v.key)}`,
                          value: v.count,
                          color: '#2f9e8f',
                        })),
                      ]}
                    />
                  )}
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>灵感社区贡献与热门作品</h3>
                      <p>
                        我的发布 {fmtNum(data.ecosystem.myCommunity.publishedWorks)} · 全社区 {fmtNum(data.ecosystem.platformCommunity.totalWorks)} 个作品（浏览 {fmtNum(data.ecosystem.platformCommunity.totalViews)} · 复刻 {fmtNum(data.ecosystem.platformCommunity.totalClones)}）
                      </p>
                    </div>
                  </header>
                  {data.ecosystem.platformCommunity.topWorks.length === 0 ? (
                    <p className="dash-empty">灵感社区暂无公开作品</p>
                  ) : (
                    <div className="dash-table-wrap">
                      <table className="dash-table">
                        <thead>
                          <tr>
                            <th scope="col">作品</th>
                            <th scope="col">作者</th>
                            <th scope="col">浏览 / 复刻 / 赞</th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.ecosystem.platformCommunity.topWorks.map((w) => (
                            <tr key={w.id}>
                              <td>
                                <button type="button" className="dash-link" onClick={() => navigate('/community/' + w.id)}>
                                  {w.name}
                                </button>
                              </td>
                              <td>{w.author}</td>
                              <td>{fmtNum(w.views)} / <b>{fmtNum(w.clones)}</b> / {fmtNum(w.likes)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              </div>
            </section>
          ) : null}

          {/* 7. 系统运行期监控 & 平台脉冲 */}
          {show('system') ? (
            <section className="dash-section" aria-label="系统运行期监控与平台脉冲">
              <SectionTitle title="系统运行期健康度与实例监控" sub="展示当前 API 实例 JVM 运行期延迟指标、跨实例限流滑窗与任务信号，以及全平台总规模脉冲。" />
              <div className="dash-grid-2">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>全平台聚合脉冲（全库总计）</h3>
                      <p>整个织页服务实例库内的总体规模概览</p>
                    </div>
                  </header>
                  <div className="dash-mini-stats compact-4">
                    <div><span>工作空间总数</span><b>{fmtNum(data.system.platformPulse.workspaces)}</b></div>
                    <div><span>SSO 登录用户</span><b>{fmtNum(data.system.platformPulse.ssoUsers)}</b></div>
                    <div><span>全平台应用</span><b>{fmtNum(data.system.platformPulse.totalApps)}</b></div>
                    <div><span>已上线应用</span><b>{fmtNum(data.system.platformPulse.publishedApps)}</b></div>
                    <div><span>全平台任务</span><b>{fmtNum(data.system.platformPulse.totalTasks)}</b></div>
                    <div><span>当前活跃任务</span><b>{fmtNum(data.system.platformPulse.activeTasks)}</b></div>
                    <div><span>累计版本数</span><b>{fmtNum(data.system.platformPulse.totalVersions)}</b></div>
                    <div><span>全站总 PV</span><b>{fmtCompact(data.system.platformPulse.totalPv)}</b></div>
                  </div>
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>实例运行期延迟与限流控制</h3>
                      <p>来自 RuntimeMetricsService（本次进程启动以来）、限流滑窗与跨实例任务信号</p>
                    </div>
                  </header>
                  <div className="dash-mini-stats compact-3">
                    <div>
                      <span>沙箱冷启动</span>
                      <b>{fmtNum(data.system.runtime.sandbox?.count ?? 0)} 次</b>
                      <small>均 {data.system.runtime.sandbox?.avgMs ?? 0}ms / 峰值 {data.system.runtime.sandbox?.maxMs ?? 0}ms</small>
                    </div>
                    <div>
                      <span>上游 LLM 调用</span>
                      <b>{fmtNum(data.system.runtime.llm?.count ?? 0)} 次</b>
                      <small>均 {data.system.runtime.llm?.avgMs ?? 0}ms / 重试 {data.system.runtime.llm?.retries ?? 0}</small>
                    </div>
                    <div>
                      <span>额度错误率</span>
                      <b>{((data.system.runtime.llm?.quotaErrorRate ?? 0) * 100).toFixed(2)}%</b>
                      <small>额度错 {data.system.runtime.llm?.quotaErrors ?? 0} 次</small>
                    </div>
                    <div>
                      <span>近 10 分钟限流</span>
                      <b>{fmtNum(data.system.rateLimits.hitsLast10Min)}</b>
                      <small>{data.system.rateLimits.byBucket.map((b) => `${b.bucket}:${b.count}`).join(' · ') || '无触发'}</small>
                    </div>
                    <div>
                      <span>跨实例任务信号</span>
                      <b>{fmtNum(data.system.taskSignals.consumed)} 已消费</b>
                      <small>待消费 {fmtNum(data.system.taskSignals.pending)}</small>
                    </div>
                    <div>
                      <span>本实例启动后任务</span>
                      <b>{fmtNum(data.system.runtime.tasks?.total ?? 0)}</b>
                      <small>成 {data.system.runtime.tasks?.succeeded ?? 0} / 败 {data.system.runtime.tasks?.failed ?? 0}</small>
                    </div>
                  </div>
                </div>
              </div>
            </section>
          ) : null}
        </div>
      )}
    </div>
  );
}

function SectionTitle({ title, sub }: { title: string; sub: string }) {
  return (
    <div className="dash-sec-title">
      <h2>{title}</h2>
      <p>{sub}</p>
    </div>
  );
}

function KpiCard({
  title,
  value,
  unit,
  accent,
  delta,
  spark,
  details,
}: {
  title: string;
  value: string;
  unit: string;
  accent: string;
  delta?: { text: string; tone: 'up' | 'down' | 'flat' } | null;
  spark?: number[];
  details: string[];
}) {
  return (
    <article className="dash-kpi">
      <div className="dash-kpi-top">
        <span>{title}</span>
        {delta ? (
          <em className={`dash-delta ${delta.tone}`}>{delta.text}</em>
        ) : (
          <i style={{ background: accent }} aria-hidden="true" />
        )}
      </div>
      <div className="dash-kpi-val">
        <b>{value}</b>
        <small>{unit}</small>
      </div>
      {spark ? <Sparkline values={spark} color={accent} /> : null}
      <div className="dash-kpi-sub">
        {details.map((d, i) => (
          <span key={i}>{d}</span>
        ))}
      </div>
    </article>
  );
}
