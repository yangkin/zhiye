import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk, useMeta } from '../api/queries';
import type { AdminCustomQuota, DashboardActor, DashboardData, DashboardRange } from '../api/types';
import { Icon } from '../lib/icons';
import { fmtTime, handleTabListKeyDown } from '../lib/format';
import { toast } from '../lib/store';
import { confirmDialog } from '../components/ConfirmDialog';
import { Select } from '../components/Select';
import { Pagination, useClientPagination } from '../components/Pagination';
import { AdminGovernancePanel, AdminQuotaModal, type QuotaModalTarget } from '../components/AdminGovernancePanel';
import {
  CalendarHeatmap,
  CohortRetentionMatrix,
  MicroSparkBar,
  PolarRoseChart,
  SankeyFlowChart,
  SectionHeading,
  StackedAreaChart,
  TrapezoidFunnel,
} from '../components/AnalyticsCharts';
import { formatOwnerIdentity } from '../components/OwnerPicker';
import { ClusterSandboxPanel } from '../components/ClusterSandboxPanel';
import { ErrorState, LoadingState } from '../components/EmptyState';
import {
  BreakdownBars,
  CHART_PALETTE,
  DashEmptyBox,
  DashPanelHead,
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

type SectionTab = 'all' | 'cluster' | 'growth' | 'apps' | 'agent' | 'infra' | 'admin';
type TrendMetric = 'tasks' | 'pv' | 'apps' | 'versions' | 'activity' | 'calls';

const RANGES: [Exclude<DashboardRange, 'custom'>, string][] = [
  ['today', '今天'],
  ['7d', '近 7 天'],
  ['30d', '近 30 天'],
  ['90d', '近 90 天'],
  ['all', '全部时间'],
];

const SECTION_TABS: [SectionTab, string][] = [
  ['all', '概览总览'],
  ['cluster', '集群与沙箱'],
  ['growth', '增长与空间排行'],
  ['apps', '应用资产与流量'],
  ['agent', 'Agent 编排与质检'],
  ['infra', '云服务、反馈与运行监控'],
  ['admin', '治理与运维'],
];

const TREND_METRICS: [TrendMetric, string][] = [
  ['tasks', 'AI 任务'],
  ['pv', '访问 PV'],
  ['apps', '应用 / 空间'],
  ['versions', '版本 / 部署'],
  ['activity', '消息互动'],
  ['calls', '云 / 数据源'],
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

const TOOL_LABELS: Record<string, string> = {
  bash: '命令执行 (bash)',
  write: '写入文件 (write)',
  edit: '精确编辑 (edit)',
  edits: '批量编辑 (edits)',
  read: '读取文件 (read)',
  screenshot: '页面自查截图 (screenshot)',
  todo_write: '待办规划 (todo_write)',
  cloud: '云服务操作 (cloud)',
  request_confirm: '人工确认卡 (confirm)',
};

const CONFIRM_KIND_LABELS: Record<string, string> = {
  cloud: '启用共享云服务',
  continue: '超步数继续确认',
};

const CONFIRM_STATE_LABELS: Record<string, string> = {
  accepted: '已同意',
  declined: '已拒绝',
  pending: '待回应',
  timeout: '已超时',
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

function downloadBlob(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

function exportTrendsCsv(data: DashboardData) {
  const header = ['日期', 'PV', '新建应用', '活跃工作空间', '总任务', '成功任务', '失败任务', '版本数', '部署数', '对话消息', '云函数调用', '数据源调用'];
  const rows = data.trends.daily.map((d) => [
    d.date,
    d.pv,
    d.appsCreated,
    d.activeWorkspaces,
    d.tasksTotal,
    d.tasksSucceeded,
    d.tasksFailed,
    d.versions,
    d.deployments,
    d.messages,
    d.fnCalls,
    d.sourceCalls,
  ]);
  const csv = '\uFEFF' + [header, ...rows].map((r) => r.join(',')).join('\n');
  downloadBlob(`zhiye-admin-trends-${data.meta.range}-${data.meta.endDate}.csv`, csv, 'text/csv;charset=utf-8');
}

export function DashboardPage() {
  const navigate = useNavigate();
  const [range, setRange] = useState<DashboardRange>('30d');
  const [customStart, setCustomStart] = useState('');
  const [customEnd, setCustomEnd] = useState('');
  const [appliedStart, setAppliedStart] = useState('');
  const [appliedEnd, setAppliedEnd] = useState('');
  const [filterWorkspaceId, setFilterWorkspaceId] = useState('');
  const [filterUserId, setFilterUserId] = useState('');
  const [section, setSection] = useState<SectionTab>('all');
  const [trendMetric, setTrendMetric] = useState<TrendMetric>('tasks');
  const [chartKind, setChartKind] = useState<'line' | 'bar' | 'area'>('bar');
  const [areaMode, setAreaMode] = useState<'value' | 'percent'>('value');
  const [calMetric, setCalMetric] = useState<'total' | 'tasks' | 'pv' | 'apps'>('total');
  const [healthTab, setHealthTab] = useState<'zombie' | 'stalled'>('zombie');
  const [appQuery, setAppQuery] = useState('');
  const [appTypeFilter, setAppTypeFilter] = useState('');
  const [appSort, setAppSort] = useState<'pv' | 'versions' | 'tasks'>('pv');
  const [wsQuery, setWsQuery] = useState('');
  const [wsSort, setWsSort] = useState<'apps' | 'tasks' | 'pv'>('apps');
  const [quotaTarget, setQuotaTarget] = useState<QuotaModalTarget | null>(null);
  const [inlineBusy, setInlineBusy] = useState<string | null>(null);

  const qc = useQueryClient();
  const metaQ = useMeta();
  const isAdmin = Boolean(metaQ.data?.auth?.admin);
  const scope: 'platform' | 'workspace' = isAdmin ? 'platform' : 'workspace';

  const adminQ = useQuery({
    queryKey: qk.adminOverview,
    queryFn: api.adminOverview,
    enabled: isAdmin,
  });

  const customQuotaByWs = useMemo(() => {
    const map = new Map<string, AdminCustomQuota>();
    for (const item of adminQ.data?.customQuotas ?? []) map.set(item.workspaceId, item);
    return map;
  }, [adminQ.data?.customQuotas]);

  const invalidateAdminAndDash = async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: ['dashboard'] }),
      qc.invalidateQueries({ queryKey: qk.adminOverview }),
    ]);
  };

  const runInlineAdmin = async (key: string, fn: () => Promise<void>) => {
    setInlineBusy(key);
    try {
      await fn();
      await invalidateAdminAndDash();
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setInlineBusy(null);
    }
  };

  const queryParams = useMemo(
    () => ({
      scope,
      range: range === 'custom' ? undefined : range,
      startDate: range === 'custom' && appliedStart ? appliedStart : undefined,
      endDate: range === 'custom' && appliedEnd ? appliedEnd : undefined,
      workspaceId: isAdmin && filterWorkspaceId ? filterWorkspaceId : undefined,
      userId: isAdmin && filterUserId ? filterUserId : undefined,
    }),
    [scope, isAdmin, range, appliedStart, appliedEnd, filterWorkspaceId, filterUserId],
  );

  const q = useQuery({
    queryKey: qk.dashboard(queryParams),
    queryFn: () => api.dashboard(queryParams),
    enabled: !metaQ.isPending,
    placeholderData: keepPreviousData,
  });

  const applyCustomDates = () => {
    if (!customStart || !customEnd) {
      toast('请同时选择开始日期和结束日期。', 'error');
      return;
    }
    if (customStart > customEnd) {
      toast('开始日期不能晚于结束日期。', 'error');
      return;
    }
    setAppliedStart(customStart);
    setAppliedEnd(customEnd);
    setRange('custom');
  };

  const selectPresetRange = (r: Exclude<DashboardRange, 'custom'>) => {
    setRange(r);
    setAppliedStart('');
    setAppliedEnd('');
  };

  const selectActor = (actor: { userId?: string | null; workspaceId?: string | null } | null) => {
    if (!actor) {
      setFilterUserId('');
      setFilterWorkspaceId('');
      return;
    }
    if (actor.userId && !actor.workspaceId) {
      setFilterUserId(actor.userId);
      setFilterWorkspaceId('');
    } else if (actor.workspaceId) {
      setFilterWorkspaceId(actor.workspaceId);
      setFilterUserId('');
    } else if (actor.userId) {
      setFilterUserId(actor.userId);
      setFilterWorkspaceId('');
    }
  };

  const data = q.data;
  const show = (tab: SectionTab) => section === tab;

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
    }
  }, [data, trendMetric]);

  const sortedWorkspaces = useMemo(() => {
    const qText = wsQuery.trim().toLowerCase();
    const list = (data?.workspaces ?? []).filter(
      (w) =>
        !qText ||
        w.workspaceId.toLowerCase().includes(qText) ||
        (w.ownerName ?? '').toLowerCase().includes(qText) ||
        (w.loginName ?? '').toLowerCase().includes(qText),
    );
    list.sort((a, b) => {
      if (wsSort === 'tasks') return b.tasks - a.tasks || b.apps - a.apps;
      if (wsSort === 'pv') return b.pv - a.pv || b.apps - a.apps;
      return b.apps - a.apps || b.tasks - a.tasks;
    });
    return list;
  }, [data, wsQuery, wsSort]);

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
      return b.totalPv - a.totalPv || b.versions - a.versions;
    });
    return filtered;
  }, [data, appQuery, appTypeFilter, appSort]);

  const daily = data?.trends.daily ?? [];
  const wsPager = useClientPagination(sortedWorkspaces, 10, `${wsQuery}|${wsSort}|${range}|${filterWorkspaceId}|${filterUserId}`);
  const appsPager = useClientPagination(filteredTopApps, 10, `${appQuery}|${appTypeFilter}|${appSort}|${range}|${filterWorkspaceId}|${filterUserId}`);
  const visibleWorkspaces = wsPager.pageItems;
  const visibleApps = appsPager.pageItems;
  const totalVerifyChecks = (data?.agentTelemetry.verifyChecks ?? []).reduce((s, c) => s + c.total, 0);
  const passedVerifyChecks = (data?.agentTelemetry.verifyChecks ?? []).reduce((s, c) => s + c.passed, 0);
  const overallVerifyRate = totalVerifyChecks ? Math.round((passedVerifyChecks / totalVerifyChecks) * 1000) / 10 : 0;

  return (
    <div className="content-page dash-page">
      {/* 顶部管理指挥舱标题与操作按钮 */}
      <header className="dash-header-top">
        <div className="dash-title-block">
          <span className="overline">ZHIYE ADMIN OBSERVABILITY &amp; OPERATIONS CONSOLE</span>
          <div className="dash-title-row">
            <h1>织页平台管理看板</h1>
            <span className={`dash-scope-badge ${scope}`}>
              <i className="dash-pulse-dot" aria-hidden="true" />
              {!isAdmin
                ? '当前工作空间统计（个人隔离视角）'
                : filterWorkspaceId || filterUserId
                  ? `已按人员/空间筛选 · ${data?.meta.filterLabel ?? ''}`
                  : '全平台实时统计'}
            </span>
          </div>
          <p>
            面向平台运营、研发与运维的全维度指挥舱：覆盖空间生产力、创作漏斗、Agent 工具遥测与质量门禁、故障根因诊断、云服务能力与集群实例健康度。
          </p>
        </div>
        <div className="dash-header-cta">
          <button
            type="button"
            className="dash-action-btn"
            onClick={() => {
              const sp = new URLSearchParams();
              if (filterWorkspaceId) sp.set('workspaceId', filterWorkspaceId);
              if (filterUserId) sp.set('userId', filterUserId);
              if (range === 'custom' && appliedStart && appliedEnd) {
                sp.set('startDate', appliedStart);
                sp.set('endDate', appliedEnd);
              } else if (range === 'today') {
                sp.set('range', '24h');
              } else if (range === '7d' || range === '30d' || range === 'all') {
                sp.set('range', range);
              } else if (range === '90d' && data?.meta.startDate && data?.meta.endDate) {
                sp.set('startDate', data.meta.startDate);
                sp.set('endDate', data.meta.endDate);
              }
              navigate('/logs' + (sp.size ? '?' + sp.toString() : ''));
            }}
          >
            <Icon name="terminal" />
            <span>日志排查</span>
          </button>
          <button type="button" className="dash-action-btn" onClick={() => void q.refetch()} disabled={q.isFetching}>
            <Icon name="refresh" className={q.isFetching ? 'spinning' : ''} />
            <span>{q.isFetching ? '刷新中…' : '刷新数据'}</span>
          </button>
          <button
            type="button"
            className="dash-action-btn"
            disabled={!data}
            onClick={() => {
              if (!data) return;
              exportTrendsCsv(data);
              toast('已导出每日趋势 CSV 报表。');
            }}
          >
            <Icon name="file" />
            <span>导出 CSV</span>
          </button>
          <button
            type="button"
            className="dash-action-btn primary"
            disabled={!data}
            onClick={() => {
              if (!data) return;
              downloadBlob(`zhiye-admin-snapshot-${data.meta.range}-${data.meta.endDate}.json`, JSON.stringify(data, null, 2), 'application/json;charset=utf-8');
              toast('已导出全量管理看板快照 JSON。');
            }}
          >
            <Icon name="download" />
            <span>导出 JSON</span>
          </button>
        </div>
      </header>

      {/* 双排精准筛选控制条：按人 / 按工作空间 + 按预设/自定义时间段 */}
      <div className="dash-command-bar">
        <div className="dash-command-left">
          {isAdmin ? (
            <>
              <ActorPicker
                actors={data?.actors ?? []}
                filterWorkspaceId={filterWorkspaceId}
                filterUserId={filterUserId}
                activeLabel={data?.meta.filterLabel ?? '全平台全部人员与空间'}
                onSelect={selectActor}
              />
              {(filterWorkspaceId || filterUserId) ? (
                <button type="button" className="dash-clear-chip" onClick={() => selectActor(null)}>
                  <span>清除人员筛选</span>
                  <Icon name="close" />
                </button>
              ) : null}
            </>
          ) : null}
          {data ? (
            <span className="dash-window-info">
              {data.meta.startDate} ~ {data.meta.endDate}（{data.meta.days} 天） · 更新于 {fmtTime(data.meta.generatedAt)}
            </span>
          ) : null}
        </div>
        <div className="dash-command-right">
          <div className="seg-control" role="radiogroup" aria-label="快捷时间段">
            {RANGES.map(([r, label]) => (
              <button key={r} type="button" role="radio" aria-checked={range === r} className={range === r ? 'active' : ''} onClick={() => selectPresetRange(r)}>
                {label}
              </button>
            ))}
          </div>
          <div className="dash-date-range" aria-label="自定义起止日期">
            <input
              type="date"
              aria-label="开始日期"
              value={customStart || data?.meta.startDate || ''}
              max={customEnd || data?.meta.endDate}
              onChange={(e) => setCustomStart(e.target.value)}
            />
            <span>至</span>
            <input
              type="date"
              aria-label="结束日期"
              value={customEnd || data?.meta.endDate || ''}
              min={customStart || undefined}
              onChange={(e) => setCustomEnd(e.target.value)}
            />
            <button type="button" className={`dash-date-apply ${range === 'custom' ? 'active' : ''}`} onClick={applyCustomDates}>
              筛选
            </button>
          </div>
        </div>
      </div>

      {/* 维度导航页签 */}
      <nav
        className="dash-nav-bar"
        role="tablist"
        aria-label="管理维度分组"
        onKeyDown={(e) => {
          const visibleIds = SECTION_TABS.filter(([id]) => isAdmin || (id !== 'admin' && id !== 'cluster')).map(([id]) => id);
          handleTabListKeyDown(e, visibleIds, section, setSection);
        }}
      >
        {SECTION_TABS.filter(([id]) => isAdmin || (id !== 'admin' && id !== 'cluster')).map(([id, label]) => (
          <button
            key={id}
            type="button"
            role="tab"
            tabIndex={section === id ? 0 : -1}
            aria-selected={section === id}
            className={section === id ? 'active' : ''}
            onClick={() => setSection(id)}
          >
            <span>{label}</span>
            {id === 'cluster' && (data?.system.cluster?.activeSandboxes ?? 0) > 0 ? (
              <em className="dash-tab-badge ok">{data?.system.cluster?.activeSandboxes}</em>
            ) : null}
            {id === 'admin' && (adminQ.data?.feedbackOpenCount ?? 0) > 0 ? (
              <em className="dash-tab-badge">{adminQ.data?.feedbackOpenCount}</em>
            ) : null}
          </button>
        ))}
      </nav>

      {q.isLoading ? (
        <LoadingState label="正在聚合全平台多维运营与遥测数据…" />
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
                {data.tasks.activeList.slice(0, 5).map((t) => (
                  <div key={t.id} className="dash-live-group">
                    <button
                      type="button"
                      className="dash-live-chip"
                      onClick={() => navigate('/logs?taskId=' + encodeURIComponent(t.id))}
                      title="点击实时查看该任务过程日志"
                    >
                      <span>{t.appName}</span>
                      <em>{labelOf(TASK_TYPE_LABELS, t.type)} · {t.progress}%</em>
                    </button>
                    {isAdmin ? (
                      <button
                        type="button"
                        className="dash-live-stop"
                        disabled={inlineBusy === `stop-${t.id}`}
                        onClick={async () => {
                          const ok = await confirmDialog({
                            title: '强行停止任务',
                            message: `确认强行停止「${t.appName}」的运行任务 ${t.id} 吗？`,
                            confirmLabel: '强行停止',
                            danger: true,
                          });
                          if (!ok) return;
                          await runInlineAdmin(`stop-${t.id}`, async () => {
                            await api.adminCancelTask(t.id);
                            toast('已发送强停指令', 'ok');
                          });
                        }}
                      >
                        强停
                      </button>
                    ) : null}
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          {/* X-APM 风格顶部章节锚点导览条（仅在总览模式下展示，大屏一键直达 5 大分析板块） */}
          {section === 'all' ? (
            <div className="xa-anchor-bar" aria-label="章节快速导航">
              <div className="xa-anchor-links">
                {[
                  ['sec-kpi', '01', '核心指标'],
                  ['sec-funnel', '02', '转化漏斗与趋势'],
                  ['sec-calendar', '03', '活跃日历与24h时段'],
                  ['sec-sankey', '04', '任务流向桑基图'],
                  ['sec-health', '05', '留存矩阵与资产健康'],
                ].map(([id, n, lbl]) => (
                  <a key={id} href={`#${id}`} className="xa-anchor-pill">
                    <i>{n}</i>
                    <span>{lbl}</span>
                  </a>
                ))}
              </div>
              <span className="xa-anchor-meta">
                共 {fmtNum(data.overview.workspacesProductive)} 有产出空间 · {fmtNum(data.overview.appsTotal)} 应用 · {fmtNum(data.overview.tasksTotal)} 任务
              </span>
            </div>
          ) : null}

          {/* 01. 核心北极星 KPI（6 卡片等高对齐 + Sparkline + 环比，仅在「全部维度」展示） */}
          {section === 'all' ? (
            <section className="dash-section" aria-label="核心管理 KPI">
              <SectionHeading
                id="sec-kpi"
                num="01"
                title="核心北极星指标"
                sub="默认对比当前窗口与上一等长周期的真实环比变化（全平台实盘统计）"
              />
              <div className="dash-kpi-grid-6">
              <KpiCard
                title="活跃空间与用户"
                value={fmtNum(data.overview.workspacesActiveRange)}
                unit={`活跃 / ${fmtNum(data.overview.workspacesProductive)} 有产出（库内 ${fmtNum(data.overview.workspacesTotal)}）`}
                accent="#2563eb"
                delta={range === 'all' ? null : calcDelta(data.overview.workspacesActiveRange, data.overview.workspacesPrevRange ?? 0)}
                spark={daily.map((d) => d.activeWorkspaces)}
                details={[
                  `产出率 ${data.overview.workspacesTotal ? Math.round((data.overview.workspacesProductive / data.overview.workspacesTotal) * 1000) / 10 : 0}%`,
                  `SSO 用户 ${fmtNum(data.overview.ssoUsersTotal)}`,
                  `对话转应用 ${fmtNum(data.overview.conversationsConverted)}`,
                ]}
              />
              <KpiCard
                title="应用资产与发布"
                value={fmtNum(data.overview.appsTotal)}
                unit={`已发布 ${fmtNum(data.overview.appsPublished)}（发布率 ${data.overview.appsTotal ? Math.round((data.overview.appsPublished / data.overview.appsTotal) * 1000) / 10 : 0}%）`}
                accent="#10b981"
                delta={range === 'all' ? null : calcDelta(data.overview.appsRange, data.overview.appsPrevRange)}
                spark={daily.map((d) => d.appsCreated)}
                details={[
                  `窗口新增 ${fmtNum(data.overview.appsRange)}`,
                  `就绪 ${fmtNum(data.overview.appsReady)} · 失败 ${fmtNum(data.overview.appsFailed)}`,
                  `回收站 ${fmtNum(data.overview.appsDeleted)}`,
                ]}
              />
              <KpiCard
                title="AI 任务吞吐与 SLA"
                value={fmtNum(data.overview.tasksTotal)}
                unit={`成功率 ${data.overview.taskSuccessRate}%`}
                accent="#4f46e5"
                delta={range === 'all' ? null : calcDelta(data.overview.tasksRange, data.overview.tasksPrevRange)}
                spark={daily.map((d) => d.tasksTotal)}
                details={[
                  `成 ${fmtNum(data.overview.tasksSucceeded)} · 败 ${fmtNum(data.overview.tasksFailed)} · 消 ${fmtNum(data.overview.tasksCancelled)}`,
                  `P50 ${data.overview.taskP50DurationSec}s / P90 ${data.overview.taskP90DurationSec}s / P95 ${data.overview.taskP95DurationSec}s`,
                ]}
              />
              <KpiCard
                title="版本构建与云调用"
                value={fmtNum(data.overview.versionsTotal)}
                unit={`部署 ${fmtNum(data.overview.deploymentsTotal)} · 构建 ${fmtNum(data.overview.buildsTotal)}`}
                accent="#f59e0b"
                delta={range === 'all' ? null : calcDelta(data.overview.versionsRange, data.overview.versionsPrevRange ?? 0)}
                spark={daily.map((d) => d.versions)}
                details={[
                  `云函数 ${fmtCompact(data.overview.fnCallsTotal)}`,
                  `数据源 ${fmtCompact(data.overview.sourceCallsTotal)}`,
                  `消息互动 ${fmtCompact(data.overview.workbenchMessagesTotal)}`,
                ]}
              />
              <KpiCard
                title="站点访问流量 (PV)"
                value={fmtCompact(data.overview.pvTotal)}
                unit={`今日 ${fmtNum(data.overview.pvToday)} · 昨日 ${fmtNum(data.overview.pvYesterday)}`}
                accent="#7c3aed"
                delta={range === 'all' ? null : calcDelta(data.overview.pvRange, data.overview.pvPrevRange)}
                spark={daily.map((d) => d.pv)}
                details={[
                  `窗口 PV ${fmtNum(data.overview.pvRange)}`,
                  `日均 ${data.overview.pvAvgDaily}`,
                  `有流量应用 ${fmtNum(data.overview.appsWithPv)}`,
                ]}
              />
              <KpiCard
                title="质检、云服务与反馈"
                value={`${overallVerifyRate}%`}
                unit={`质检通过率（${fmtNum(totalVerifyChecks)} 次门禁）`}
                accent="#ec4899"
                spark={daily.map((d) => d.versions)}
                details={[
                  `版本 ${fmtNum(data.overview.versionsTotal)} · 部署 ${fmtNum(data.overview.deploymentsTotal)}`,
                  `云服务 ${fmtNum(data.overview.cloudEnabledApps)} · 表 ${fmtNum(data.overview.cloudTablesTotal)}`,
                  `用户反馈 ${fmtNum(data.overview.helpFeedbackTotal)} · 社区 ${fmtNum(data.overview.templatesPublished)}`,
                ]}
              />
              </div>
            </section>
          ) : null}

          {/* 集群与沙箱全景（在「全部」显示高密度摘要条，在「集群与沙箱」页签展开完整节点拓扑与沙箱控制台） */}
          {isAdmin && section === 'all' ? (
            <ClusterSandboxPanel compact onOpenClusterTab={() => setSection('cluster')} />
          ) : null}
          {isAdmin && section === 'cluster' ? <ClusterSandboxPanel /> : null}

          {/* 02. 对称梯形转化漏斗 + 多维每日趋势（含堆叠面积构成） */}
          {section === 'all' || section === 'growth' ? (
            <section className="dash-section" aria-label="转化漏斗与每日趋势">
              <SectionHeading
                id="sec-funnel"
                num="02"
                title="转化漏斗与多维趋势构成"
                sub="同口径对称梯形漏斗 + 流失定位洞察，右侧支持折线 / 柱状 / 多维堆叠面积切换"
              />
              <div className="dash-hero-split xa-split-wide">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h2>全链路创作转化漏斗</h2>
                      <p>对话构想 → 新建应用 → 首版就绪 → 发布上线 → 真实访问 → 云服务</p>
                    </div>
                  </header>
                  <TrapezoidFunnel steps={data.funnel} />
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h2>多维度每日趋势与构成</h2>
                      <p>按 Asia/Shanghai 时区补零对齐，支持 6 类指标与全链路堆叠面积占比视图</p>
                    </div>
                    <div className="dash-toolbar-group">
                      {chartKind === 'area' ? (
                        <div className="seg-control small" role="radiogroup" aria-label="堆叠口径">
                          <button type="button" role="radio" aria-checked={areaMode === 'value'} className={areaMode === 'value' ? 'active' : ''} onClick={() => setAreaMode('value')}>
                            总量
                          </button>
                          <button type="button" role="radio" aria-checked={areaMode === 'percent'} className={areaMode === 'percent' ? 'active' : ''} onClick={() => setAreaMode('percent')}>
                            占比
                          </button>
                        </div>
                      ) : null}
                      <div className="seg-control small" role="radiogroup" aria-label="图表形式">
                        <button type="button" role="radio" aria-checked={chartKind === 'bar'} className={chartKind === 'bar' ? 'active' : ''} onClick={() => setChartKind('bar')}>
                          柱状图
                        </button>
                        <button type="button" role="radio" aria-checked={chartKind === 'line'} className={chartKind === 'line' ? 'active' : ''} onClick={() => setChartKind('line')}>
                          折线图
                        </button>
                        <button type="button" role="radio" aria-checked={chartKind === 'area'} className={chartKind === 'area' ? 'active' : ''} onClick={() => setChartKind('area')}>
                          堆叠面积
                        </button>
                      </div>
                    </div>
                  </header>
                  {chartKind === 'area' ? (
                    <StackedAreaChart
                      mode={areaMode}
                      data={daily.map((d) => ({
                        date: d.date,
                        tasksSucceeded: d.tasksSucceeded,
                        tasksFailed: d.tasksFailed,
                        appsCreated: d.appsCreated,
                        deployments: d.deployments,
                      }))}
                      series={[
                        { key: 'tasksSucceeded', label: '成功任务', color: '#2563eb' },
                        { key: 'appsCreated', label: '新建应用', color: '#10b981' },
                        { key: 'deployments', label: '发布部署', color: '#8b5cf6' },
                        { key: 'tasksFailed', label: '失败任务', color: '#ef4444' },
                      ]}
                    />
                  ) : (
                    <>
                      <div
                        className="dash-metric-pills"
                        role="tablist"
                        aria-label="趋势指标"
                        onKeyDown={(e) =>
                          handleTabListKeyDown(
                            e,
                            TREND_METRICS.map(([m]) => m),
                            trendMetric,
                            setTrendMetric,
                          )
                        }
                      >
                        {TREND_METRICS.map(([m, label]) => (
                          <button
                            key={m}
                            type="button"
                            role="tab"
                            tabIndex={trendMetric === m ? 0 : -1}
                            aria-selected={trendMetric === m}
                            className={trendMetric === m ? 'active' : ''}
                            onClick={() => setTrendMetric(m)}
                          >
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
                    </>
                  )}
                </div>
              </div>
            </section>
          ) : null}

          {/* 03. 近 140 天活跃日历热力图 + 24 小时极坐标玫瑰图 */}
          {section === 'all' || section === 'growth' ? (
            <section className="dash-section" aria-label="活跃日历热力图与时段玫瑰图">
              <SectionHeading
                id="sec-calendar"
                num="03"
                title="平台活跃日历与 24 小时时段分布"
                sub="左图回溯近半年（26 周）每日活动强度，右图按早/中/夜班表盘呈现 24h 任务触发高峰"
              />
              <div className="xa-grid-cal-polar">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>近 180 天每日活跃日历热力图</h3>
                      <p>一眼识别工作周波峰、节假日低谷与版本推广节点，悬停查看当日明细</p>
                    </div>
                    <div className="seg-control small" role="radiogroup" aria-label="日历指标">
                      {(
                        [
                          ['total', '综合活跃'],
                          ['tasks', 'AI 任务'],
                          ['pv', '访问 PV'],
                          ['apps', '新建应用'],
                        ] as const
                      ).map(([k, lbl]) => (
                        <button
                          key={k}
                          type="button"
                          role="radio"
                          aria-checked={calMetric === k}
                          className={calMetric === k ? 'active' : ''}
                          onClick={() => setCalMetric(k)}
                        >
                          {lbl}
                        </button>
                      ))}
                    </div>
                  </header>
                  <CalendarHeatmap days={data.calendarHeatmap ?? []} metric={calMetric} />
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>24 小时任务触发极坐标玫瑰图</h3>
                      <p>按 Asia/Shanghai 小时分布，色系区分早/中/夜班</p>
                    </div>
                  </header>
                  <PolarRoseChart hours={data.hourly} />
                </div>
              </div>
            </section>
          ) : null}

          {/* 04. 任务全链路三段桑基流向图 */}
          {section === 'all' || section === 'agent' ? (
            <section className="dash-section" aria-label="任务全链路桑基流向图">
              <SectionHeading
                id="sec-sankey"
                num="04"
                title="创作任务流向与失败归因（桑基图）"
                sub="从创作入口 → 应用形态 → 最终执行结果（成功 / 构建失败 / 模型异常 / 取消）透明追踪流量去向"
              />
              <div className="dash-panel">
                <SankeyFlowChart flow={data.sankeyFlow} />
              </div>
            </section>
          ) : null}

          {/* 05. 员工/空间留存 Cohort 矩阵 + 资产健康度与僵尸治理 */}
          {section === 'all' || section === 'growth' || section === 'apps' ? (
            <section className="dash-section" aria-label="留存分析与资产健康度治理">
              <SectionHeading
                id="sec-health"
                num="05"
                title="空间复访留存矩阵与资产健康治理"
                sub="左图追踪新增空间的次日～第 7 日真实回访留存；右图自动识别僵尸站点（7 日 0 访问）与异常停滞应用"
              />
              <div className="dash-grid-2">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>留存 Cohort 热力矩阵（第 1 日 ～ 第 7 日）</h3>
                      <p>行 = 首次活跃批次日，列 = 第 N 日仍持续创作或运行任务的空间留存率</p>
                    </div>
                  </header>
                  <CohortRetentionMatrix cohort={data.cohortRetention} />
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>应用资产健康度与僵尸清单</h3>
                      <p>在用 {fmtNum(data.assetHealth?.totalActive)} 个 · 就绪 {fmtNum(data.assetHealth?.readyCount)} · 已发布 {fmtNum(data.assetHealth?.publishedCount)} · 近 7 日有访问 {fmtNum(data.assetHealth?.active7dCount)}</p>
                    </div>
                    <div className="seg-control small" role="radiogroup" aria-label="健康治理清单">
                      <button
                        type="button"
                        role="radio"
                        aria-checked={healthTab === 'zombie'}
                        className={healthTab === 'zombie' ? 'active' : ''}
                        onClick={() => setHealthTab('zombie')}
                      >
                        僵尸站点 ({fmtNum(data.assetHealth?.zombie7dCount)})
                      </button>
                      <button
                        type="button"
                        role="radio"
                        aria-checked={healthTab === 'stalled'}
                        className={healthTab === 'stalled' ? 'active' : ''}
                        onClick={() => setHealthTab('stalled')}
                      >
                        异常停滞 ({fmtNum(data.assetHealth?.stalledCount)})
                      </button>
                    </div>
                  </header>

                  <div className="xa-health-strip">
                    <div className="xa-health-kpi">
                      <span>就绪率</span>
                      <b>
                        {data.assetHealth && data.assetHealth.totalActive > 0
                          ? `${((data.assetHealth.readyCount / data.assetHealth.totalActive) * 100).toFixed(1)}%`
                          : '0%'}
                      </b>
                    </div>
                    <div className="xa-health-kpi">
                      <span>发布率</span>
                      <b>
                        {data.assetHealth && data.assetHealth.totalActive > 0
                          ? `${((data.assetHealth.publishedCount / data.assetHealth.totalActive) * 100).toFixed(1)}%`
                          : '0%'}
                      </b>
                    </div>
                    <div className="xa-health-kpi ok">
                      <span>7 日活跃站</span>
                      <b>{fmtNum(data.assetHealth?.active7dCount)}</b>
                    </div>
                    <div className="xa-health-kpi warn">
                      <span>7 日 0 访问僵尸</span>
                      <b>{fmtNum(data.assetHealth?.zombie7dCount)}</b>
                    </div>
                  </div>

                  {healthTab === 'zombie' ? (
                    (data.assetHealth?.zombieSites.length ?? 0) === 0 ? (
                      <DashEmptyBox icon="check" title="全部已发布站点近 7 日均有真实访问" />
                    ) : (
                      <div className="dash-table-wrap">
                        <table className="dash-table xa-compact-table">
                          <thead>
                            <tr>
                              <th scope="col">僵尸站点 / Slug</th>
                              <th scope="col">归属人</th>
                              <th scope="col">累计 PV / 最后访问</th>
                              {isAdmin ? <th scope="col">处置</th> : null}
                            </tr>
                          </thead>
                          <tbody>
                            {(data.assetHealth?.zombieSites ?? []).slice(0, 7).map((z) => {
                              const owner = formatOwnerIdentity({
                                ownerName: z.ownerName,
                                ownerLogin: z.ownerLogin,
                                workspaceId: z.workspaceId,
                              });
                              return (
                                <tr key={z.id} className="xa-row-zombie">
                                  <td>
                                    <b>{z.name}</b> <span className="xa-zombie-badge">僵尸</span>
                                    <div><code>{z.slug}</code></div>
                                  </td>
                                  <td>
                                    {owner.bound ? (
                                      <span>{owner.primary} {owner.account ? <code className="owner-cell-acct">{owner.account}</code> : null}</span>
                                    ) : (
                                      <code className="dash-muted">{z.workspaceId}</code>
                                    )}
                                  </td>
                                  <td className="dash-nowrap">
                                    <b>{fmtNum(z.totalPv)}</b> · <span className="dash-muted">{z.lastPvDay ?? '从未访问'}</span>
                                  </td>
                                  {isAdmin ? (
                                    <td className="dash-nowrap">
                                      <button
                                        type="button"
                                        className="dash-link"
                                        onClick={() => navigate('/admin?tab=sites')}
                                      >
                                        去风控
                                      </button>
                                    </td>
                                  ) : null}
                                </tr>
                              );
                            })}
                          </tbody>
                        </table>
                      </div>
                    )
                  ) : (data.assetHealth?.stalledApps.length ?? 0) === 0 ? (
                    <DashEmptyBox icon="check" title="当前无失败或停滞态应用" />
                  ) : (
                    <div className="dash-table-wrap">
                      <table className="dash-table xa-compact-table">
                        <thead>
                          <tr>
                            <th scope="col">异常应用</th>
                            <th scope="col">归属人</th>
                            <th scope="col">状态 / 更新</th>
                            {isAdmin ? <th scope="col">排查</th> : null}
                          </tr>
                        </thead>
                        <tbody>
                          {(data.assetHealth?.stalledApps ?? []).slice(0, 7).map((s) => {
                            const owner = formatOwnerIdentity({
                              ownerName: s.ownerName,
                              ownerLogin: s.ownerLogin,
                              workspaceId: s.workspaceId,
                            });
                            return (
                              <tr key={s.id} className="xa-row-zombie">
                                <td>
                                  <b>{s.name}</b>
                                  <div><code>{s.slug}</code></div>
                                </td>
                                <td>
                                  {owner.bound ? (
                                    <span>{owner.primary} {owner.account ? <code className="owner-cell-acct">{owner.account}</code> : null}</span>
                                  ) : (
                                    <code className="dash-muted">{s.workspaceId}</code>
                                  )}
                                </td>
                                <td className="dash-nowrap">
                                  <span className="dash-pill err">{labelOf(PHASE_LABELS, s.phase)}</span>{' '}
                                  <span className="dash-muted">{fmtTime(s.updatedAt)}</span>
                                </td>
                                {isAdmin ? (
                                  <td className="dash-nowrap">
                                    <button
                                      type="button"
                                      className="dash-link"
                                      onClick={() => navigate(`/logs?workspaceId=${encodeURIComponent(s.workspaceId)}&q=${encodeURIComponent(s.name)}`)}
                                    >
                                      看日志
                                    </button>
                                  </td>
                                ) : null}
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              </div>
            </section>
          ) : null}

          {/* 总览专享：4 大业务域深度分析直达卡片（替代 8000px 纵向大长页） */}
          {section === 'all' ? (
            <section className="dash-section" aria-label="分域深度分析入口">
              <div className="dash-exec-grid">
                <button type="button" className="dash-exec-card" onClick={() => setSection('growth')}>
                  <div className="dash-exec-head">
                    <span className="dash-head-icon violet sm"><Icon name="chart" /></span>
                    <b>增长与空间排行</b>
                    <span className="dash-exec-arrow">深入分析 →</span>
                  </div>
                  <div className="dash-exec-metrics">
                    <div><span>有产出空间</span><strong>{fmtNum(data.overview.workspacesProductive)}</strong></div>
                    <div><span>活跃空间</span><strong>{fmtNum(data.overview.workspacesActiveRange)}</strong></div>
                    <div><span>对话转应用</span><strong>{fmtNum(data.overview.conversationsConverted)}</strong></div>
                  </div>
                  <p>查看 Top 工作空间生产力榜单、24h 活跃分布与配额快捷调整</p>
                </button>

                <button type="button" className="dash-exec-card" onClick={() => setSection('apps')}>
                  <div className="dash-exec-head">
                    <span className="dash-head-icon teal sm"><Icon name="grid" /></span>
                    <b>应用资产与流量</b>
                    <span className="dash-exec-arrow">深入分析 →</span>
                  </div>
                  <div className="dash-exec-metrics">
                    <div><span>应用总量</span><strong>{fmtNum(data.overview.appsTotal)}</strong></div>
                    <div><span>已发布站点</span><strong>{fmtNum(data.overview.appsPublished)}</strong></div>
                    <div><span>累计 PV</span><strong>{fmtCompact(data.overview.pvTotal)}</strong></div>
                  </div>
                  <p>查看应用类型 / 技术栈分布、状态健康度与热门应用分页榜</p>
                </button>

                <button type="button" className="dash-exec-card" onClick={() => setSection('agent')}>
                  <div className="dash-exec-head">
                    <span className="dash-head-icon indigo sm"><Icon name="agent" /></span>
                    <b>Agent 编排与质检</b>
                    <span className="dash-exec-arrow">深入分析 →</span>
                  </div>
                  <div className="dash-exec-metrics">
                    <div><span>任务总数</span><strong>{fmtNum(data.overview.tasksTotal)}</strong></div>
                    <div><span>成功率</span><strong>{data.overview.taskSuccessRate}%</strong></div>
                    <div><span>门禁通过率</span><strong>{overallVerifyRate}%</strong></div>
                  </div>
                  <p>查看任务耗时分位、工具调用遥测、模型路由与失败根因排行</p>
                </button>

                <button type="button" className="dash-exec-card" onClick={() => setSection('infra')}>
                  <div className="dash-exec-head">
                    <span className="dash-head-icon amber sm"><Icon name="cloud" /></span>
                    <b>云服务、反馈与监控</b>
                    <span className="dash-exec-arrow">深入分析 →</span>
                  </div>
                  <div className="dash-exec-metrics">
                    <div><span>云服务应用</span><strong>{fmtNum(data.overview.cloudEnabledApps)}</strong></div>
                    <div><span>云数据表</span><strong>{fmtNum(data.overview.cloudTablesTotal)}</strong></div>
                    <div><span>用户反馈</span><strong>{fmtNum(data.overview.helpFeedbackTotal)}</strong></div>
                  </div>
                  <p>查看云函数与数据源调用、对话协作、素材导入与社区互动明细</p>
                </button>
              </div>
            </section>
          ) : null}

          {show('growth') ? (
            <section className="dash-section" aria-label="空间生产力排行与时段分布">
              <div className="dash-panel">
                <header className="dash-panel-head">
                  <div>
                    <h3>活跃工作空间 / 用户生产力排行（Top {sortedWorkspaces.length}）</h3>
                    <p>全库共 {fmtNum(data.overview.workspacesTotal)} 个工作空间，其中 {fmtNum(data.overview.workspacesProductive)} 个有真实产出（已过滤空壳空间）。</p>
                  </div>
                  <div className="dash-toolbar-group">
                    <label className="dash-search">
                      <Icon name="search" />
                      <input
                        aria-label="搜索工作空间"
                        placeholder="按空间 ID 或用户名筛选"
                        value={wsQuery}
                        onChange={(e) => setWsQuery(e.target.value)}
                      />
                    </label>
                    <div className="seg-control unified" role="radiogroup" aria-label="工作空间排序">
                      {(
                        [
                          ['apps', '按应用'],
                          ['tasks', '按任务'],
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
                  <DashEmptyBox icon="folder" title="暂无符合条件的工作空间" />
                ) : (
                  <>
                    <div className="dash-table-wrap">
                      <table className="dash-table">
                        <thead>
                          <tr>
                            <th scope="col">工作空间 / 归属人</th>
                            <th scope="col">应用 (已发布)</th>
                            <th scope="col">任务 (窗口)</th>
                            <th scope="col">成功率</th>
                            <th scope="col">PV (今日)</th>
                            <th scope="col">近 7 日活跃</th>
                            <th scope="col">最近活跃</th>
                            <th scope="col">操作</th>
                          </tr>
                        </thead>
                        <tbody>
                          {visibleWorkspaces.map((w) => {
                            const isFiltered = filterWorkspaceId === w.workspaceId;
                            const customQ = customQuotaByWs.get(w.workspaceId) ?? null;
                            const ownerInfo = formatOwnerIdentity({
                              ownerName: w.ownerName,
                              ownerLogin: w.loginName,
                              workspaceId: w.workspaceId,
                            });
                            return (
                              <tr key={w.workspaceId} className={isFiltered ? 'dash-row-highlight' : ''}>
                                <td>
                                  <div className="owner-cell">
                                    <div className="owner-cell-main">
                                      {ownerInfo.bound ? (
                                        <>
                                          <span className="owner-cell-name">{ownerInfo.primary}</span>
                                          {ownerInfo.account ? <code className="owner-cell-acct">{ownerInfo.account}</code> : null}
                                        </>
                                      ) : (
                                        <span className="owner-cell-anon">未绑定账号</span>
                                      )}
                                      {customQ ? <span className="dash-pill cloud">自定义配额</span> : null}
                                    </div>
                                    <div className="owner-cell-ws">
                                      <code>{w.workspaceId}</code>
                                    </div>
                                  </div>
                                </td>
                                <td><b>{fmtNum(w.apps)}</b> <span className="dash-muted">({fmtNum(w.published)})</span></td>
                                <td>{fmtNum(w.tasks)} <span className="dash-muted">({fmtNum(w.rangeTasks)})</span></td>
                                <td><span className={w.successRate >= 80 ? 'dash-ok' : w.successRate < 50 && w.tasks > 0 ? 'dash-err' : ''}>{w.successRate}%</span></td>
                                <td><b>{fmtNum(w.pv)}</b> <span className="dash-muted">({fmtNum(w.todayPv)})</span></td>
                                <td><MicroSparkBar values={w.trend7d} color="#2563eb" /></td>
                                <td className="dash-nowrap">{w.lastActiveAt ? fmtTime(w.lastActiveAt) : '-'}</td>
                                <td>
                                  <div className="dash-action-row">
                                    <button
                                      type="button"
                                      className="dash-link"
                                      onClick={() => selectActor(isFiltered ? null : { workspaceId: w.workspaceId })}
                                    >
                                      {isFiltered ? '取消筛选' : '下钻'}
                                    </button>
                                    {isAdmin ? (
                                      <>
                                        <button
                                          type="button"
                                          className="dash-link"
                                          onClick={() =>
                                            navigate('/admin?tab=handover&fromWs=' + encodeURIComponent(w.workspaceId))
                                          }
                                        >
                                          交接
                                        </button>
                                        <button
                                          type="button"
                                          className="dash-link"
                                          onClick={() =>
                                            setQuotaTarget({
                                              workspaceId: w.workspaceId,
                                              ownerName: w.ownerName,
                                              custom: customQ,
                                            })
                                          }
                                        >
                                          配额
                                        </button>
                                      </>
                                    ) : null}
                                  </div>
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                    <Pagination
                      page={wsPager.page}
                      pageSize={wsPager.pageSize}
                      total={wsPager.total}
                      onPageChange={wsPager.setPage}
                      onPageSizeChange={wsPager.setPageSize}
                      pageSizeOptions={[10, 20, 50]}
                      alwaysShowSummary
                    />
                  </>
                )}
              </div>
            </section>
          ) : null}

          {/* 3. 应用资产画像与排行 */}
          {show('apps') ? (
            <section className="dash-section" aria-label="应用资产画像与排行">
              <SectionTitle title="全平台应用资产画像与流量排行" sub="按创造类型、项目形态、生命周期、创建模式与来源、设计风格深度拆解全平台 384+ 应用资产。" />

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
                    <p>按 PV、版本迭代次数与任务数排行，支持关键词检索与类型过滤。</p>
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
                    <Select
                      ariaLabel="按类型筛选"
                      value={appTypeFilter}
                      options={[
                        { value: '', label: '全部类型' },
                        ...data.apps.byType.map((t) => ({ value: t.key, label: t.key })),
                      ]}
                      onChange={setAppTypeFilter}
                    />
                    <div className="seg-control unified" role="radiogroup" aria-label="排序维度">
                      {(
                        [
                          ['pv', '按 PV'],
                          ['versions', '按版本'],
                          ['tasks', '按任务'],
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
                  <DashEmptyBox icon="grid" title="暂无符合条件的应用数据" />
                ) : (
                  <>
                    <div className="dash-table-wrap">
                      <table className="dash-table">
                        <thead>
                          <tr>
                            <th scope="col">应用名称</th>
                            <th scope="col">所属空间</th>
                            <th scope="col">类型 / 形态</th>
                            <th scope="col">状态</th>
                            <th scope="col">云服务 / 访问</th>
                            <th scope="col">版本</th>
                            <th scope="col">任务</th>
                            <th scope="col">今日 / 累计 PV</th>
                            <th scope="col">近 7 日 PV</th>
                            {isAdmin ? <th scope="col">治理操作</th> : null}
                          </tr>
                        </thead>
                        <tbody>
                          {visibleApps.map((a) => (
                            <tr key={a.id}>
                              <td>
                                {a.workspaceId === data.meta.workspaceId ? (
                                  <button type="button" className="dash-link" onClick={() => navigate('/apps/' + a.id)}>
                                    {a.name}
                                  </button>
                                ) : (
                                  <b>{a.name}</b>
                                )}
                              </td>
                              <td><code>{a.workspaceId}</code></td>
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
                              <td>{fmtNum(a.todayPv)} / <b>{fmtNum(a.totalPv)}</b></td>
                              <td><MicroSparkBar values={a.trend7d} color="#10b981" /></td>
                              {isAdmin ? (
                                <td>
                                  <div className="dash-action-row">
                                    {a.published ? (
                                      <button
                                        type="button"
                                        className="dash-link danger"
                                        disabled={inlineBusy === `unpub-${a.id}`}
                                        onClick={async () => {
                                          const ok = await confirmDialog({
                                            title: '强行下线站点',
                                            message: `确认下线「${a.name}」的已发布站点吗？应用本身不会被删除。`,
                                            confirmLabel: '强行下线',
                                            danger: true,
                                          });
                                          if (!ok) return;
                                          await runInlineAdmin(`unpub-${a.id}`, async () => {
                                            await api.adminUnpublishApp(a.id);
                                            toast(`已下线站点「${a.name}」`, 'ok');
                                          });
                                        }}
                                      >
                                        下线站点
                                      </button>
                                    ) : null}
                                    <button
                                      type="button"
                                      className="dash-link danger"
                                      disabled={inlineBusy === `del-${a.id}`}
                                      onClick={async () => {
                                        const ok = await confirmDialog({
                                          title: '管理员删除应用',
                                          message: `确认将「${a.name}」移入回收站并下线其站点吗？后续可在「治理与运维 -> 回收站」恢复。`,
                                          confirmLabel: '确认删除',
                                          danger: true,
                                        });
                                        if (!ok) return;
                                        await runInlineAdmin(`del-${a.id}`, async () => {
                                          await api.adminDeleteApp(a.id);
                                          toast(`已删除「${a.name}」，可随时在回收站恢复`, 'ok');
                                        });
                                      }}
                                    >
                                      删除
                                    </button>
                                  </div>
                                </td>
                              ) : null}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <Pagination
                      page={appsPager.page}
                      pageSize={appsPager.pageSize}
                      total={appsPager.total}
                      onPageChange={appsPager.setPage}
                      onPageSizeChange={appsPager.setPageSize}
                      pageSizeOptions={[10, 20, 50]}
                      alwaysShowSummary
                    />
                  </>
                )}
              </div>
            </section>
          ) : null}

          {/* 4. Agent 编排、工具遥测、质检门禁与故障诊断 */}
          {show('agent') ? (
            <section className="dash-section" aria-label="Agent 编排与质检遥测">
              <SectionTitle title="Agent 编排效能、工具遥测与质量门禁" sub="深度剖析 2.9 万+任务事件流：Agent 工具调用成功率、构建/冒烟/视觉质检通过率、人工确认卡、分时段高峰与失败根因。" />

              <div className="dash-grid-2">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>任务 SLA 健康度与编排事件</h3>
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
                    <div><span>P90 耗时</span><b>{data.overview.taskP90DurationSec}s</b></div>
                    <div><span>P95 耗时</span><b>{data.overview.taskP95DurationSec}s</b></div>
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

              {/* Agent 工具调用 + 质量门禁检查通过率 + 人工确认卡 */}
              <div className="dash-grid-2">
                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>Agent 工具调用遥测（Tool Telemetry）</h3>
                      <p>统计 bash / write / edit / read / screenshot / cloud 等工具调用量与成功率</p>
                    </div>
                  </header>
                  {data.agentTelemetry.tools.length === 0 ? (
                    <DashEmptyBox icon="terminal" title="暂无工具调用遥测数据" />
                  ) : (
                    <div className="dash-table-wrap">
                      <table className="dash-table">
                        <thead>
                          <tr>
                            <th scope="col">工具名称</th>
                            <th scope="col">调用次数</th>
                            <th scope="col">失败次数</th>
                            <th scope="col">成功率</th>
                          </tr>
                        </thead>
                        <tbody>
                          {data.agentTelemetry.tools.map((tl) => (
                            <tr key={tl.tool}>
                              <td><b>{labelOf(TOOL_LABELS, tl.tool)}</b></td>
                              <td>{fmtNum(tl.calls)}</td>
                              <td className={tl.failed > 0 ? 'dash-err' : ''}>{fmtNum(tl.failed)}</td>
                              <td>
                                <span className={`dash-rate-pill ${tl.passRate >= 95 ? 'ok' : tl.passRate >= 80 ? 'warn' : 'err'}`}>
                                  {tl.passRate}%
                                </span>
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                  <div className="dash-sub-block">
                    <h4>推理与人机协同事件统计</h4>
                    <div className="dash-chip-grid">
                      <span className="dash-kv-chip"><span>深度思考 (thinking)</span><b>{fmtNum(data.agentTelemetry.eventBreakdown.thinking)}</b></span>
                      <span className="dash-kv-chip"><span>待办规划 (todo)</span><b>{fmtNum(data.agentTelemetry.eventBreakdown.todo)}</b></span>
                      <span className="dash-kv-chip"><span>中途补充 (steer)</span><b>{fmtNum(data.agentTelemetry.eventBreakdown.steer)}</b></span>
                      <span className="dash-kv-chip"><span>队列触发云服务</span><b>{fmtNum(data.tasks.queue.enableCloudCount)}</b></span>
                    </div>
                  </div>
                </div>

                <div className="dash-panel">
                  <header className="dash-panel-head compact">
                    <div>
                      <h3>自动化质检门禁通过率（Quality Gates）</h3>
                      <p>覆盖类型检查、构建预览/发布版、Chromium 冒烟测试、视觉自查与技能格式校验</p>
                    </div>
                  </header>
                  {data.agentTelemetry.verifyChecks.length === 0 ? (
                    <DashEmptyBox icon="check" title="暂无质检门禁记录" />
                  ) : (
                    <div className="dash-scroll-list" style={{ maxHeight: 310 }}>
                      <div className="dash-table-wrap">
                        <table className="dash-table">
                          <thead>
                            <tr>
                              <th scope="col">门禁检查项</th>
                              <th scope="col">总次数</th>
                              <th scope="col">通过 / 未通过</th>
                              <th scope="col">通过率</th>
                            </tr>
                          </thead>
                          <tbody>
                            {data.agentTelemetry.verifyChecks.map((vc) => (
                              <tr key={vc.name}>
                                <td><b>{vc.name}</b></td>
                                <td>{fmtNum(vc.total)}</td>
                                <td>
                                  <span className="dash-ok">{fmtNum(vc.passed)}</span> / <span className={vc.failed > 0 ? 'dash-err' : 'dash-muted'}>{fmtNum(vc.failed)}</span>
                                </td>
                                <td>
                                  <span className={`dash-rate-pill ${vc.passRate >= 95 ? 'ok' : vc.passRate >= 75 ? 'warn' : 'err'}`}>
                                    {vc.passRate}%
                                  </span>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </div>
                  )}
                  {data.agentTelemetry.confirms.length > 0 ? (
                    <div className="dash-sub-block">
                      <h4>人机确认卡决策分布（Confirm Cards）</h4>
                      <div className="dash-chip-grid">
                        {data.agentTelemetry.confirms.map((c, idx) => (
                          <span key={idx} className="dash-kv-chip">
                            <span>{labelOf(CONFIRM_KIND_LABELS, c.kind)} · {labelOf(CONFIRM_STATE_LABELS, c.state)}</span>
                            <b>{fmtNum(c.count)}</b>
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
                      <h3>各类型任务效能矩阵</h3>
                      <p>不同任务类型的数量、成功率与平均/最大耗时</p>
                    </div>
                  </header>
                  {data.tasks.byType.length === 0 ? (
                    <DashEmptyBox icon="play" title="暂无任务记录" />
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
                      <h3>最近失败任务诊断（最新 10 条）</h3>
                      <p>展示失败应用、错误码、报错详情与耗时</p>
                    </div>
                  </header>
                  {data.tasks.recentFailures.length === 0 ? (
                    <DashEmptyBox icon="check" title="当前范围暂无失败任务" />
                  ) : (
                    <div className="dash-scroll-list">
                      <ul className="dash-error-list">
                        {data.tasks.recentFailures.map((f) => (
                          <li key={f.id}>
                            <div className="dash-err-head">
                              <b>{f.appName} · {labelOf(TASK_TYPE_LABELS, f.type)}</b>
                              <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                                <span className="dash-pill err">{f.errorCode || 'UNKNOWN'}</span>
                                <button type="button" className="dash-link" onClick={() => navigate('/logs?taskId=' + encodeURIComponent(f.id))}>
                                  查看日志
                                </button>
                                {isAdmin && f.type !== 'import' && f.type !== 'clone' ? (
                                  <button
                                    type="button"
                                    className="dash-link"
                                    disabled={inlineBusy === `retry-${f.id}`}
                                    onClick={async () => {
                                      const ok = await confirmDialog({
                                        title: '运维重跑失败任务',
                                        message: `确认替用户重跑「${f.appName}」的失败任务 ${f.id} 吗？`,
                                        confirmLabel: '立即重跑',
                                      });
                                      if (!ok) return;
                                      await runInlineAdmin(`retry-${f.id}`, async () => {
                                        const nt = await api.adminRetryTask(f.id);
                                        toast(`已提交重跑任务 ${nt.id}`, 'ok');
                                      });
                                    }}
                                  >
                                    运维重跑
                                  </button>
                                ) : null}
                              </div>
                            </div>
                            <span>{f.errorMessage || f.phaseLabel || '无详细错误信息'}</span>
                            <small>{fmtTime(f.createdAt)} · 任务 {f.id} · 空间 {f.workspaceId} · 耗时 {fmtDurationMs(f.durationMs)}</small>
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
                      <p>实时记录每次规划、生成、修改、代码保存的耗时与状态</p>
                    </div>
                  </header>
                  {data.tasks.recentTasks.length === 0 ? (
                    <DashEmptyBox icon="history" title="暂无最近任务" />
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
                              <th scope="col">耗时</th>
                              <th scope="col">排查</th>
                            </tr>
                          </thead>
                          <tbody>
                            {data.tasks.recentTasks.map((t) => (
                              <tr key={t.id}>
                                <td className="dash-nowrap">{fmtTime(t.createdAt)}</td>
                                <td>
                                  {t.workspaceId === data.meta.workspaceId ? (
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
                                <td>{fmtDurationMs(t.durationMs)}</td>
                                <td>
                                  <button type="button" className="dash-link" onClick={() => navigate('/logs?taskId=' + encodeURIComponent(t.id))}>
                                    日志
                                  </button>
                                </td>
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

          {/* 5. 云服务、数据源、对话、反馈、社区与运行监控 */}
          {show('infra') ? (
            <section className="dash-section" aria-label="云服务、数据源、用户反馈与运行监控">
              <SectionTitle title="版本部署、云服务合规、数据源、用户反馈与运行监控" sub="覆盖全平台 BaaS 数据表与站内安全权限、Deno 云函数、AGW 数据源、会话转化、用户上报反馈与实例延迟。" />

              <div className="dash-grid-3">
                <div className="dash-panel">
                  <DashPanelHead
                    icon="history"
                    tone="indigo"
                    title="版本来源与构建部署"
                    sub={`共 ${fmtNum(data.overview.versionsTotal)} 个版本 · ${fmtNum(data.versions.buildsTotal)} 次构建`}
                  />
                  <BreakdownBars
                    emptyIcon="history"
                    emptyText="暂无版本记录"
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
                  <DashPanelHead
                    icon="database"
                    tone="teal"
                    title="云服务、数据表与访问控制"
                    sub={`已启用 ${fmtNum(data.cloud.enabledApps)} 个 · 云表 ${fmtNum(data.cloud.tablesCount)} 张 · 站内会话 ${fmtNum(data.cloud.activeSiteSessions)}`}
                  />
                  <DonutChart
                    centerLabel="访问级别"
                    emptyIcon="database"
                    emptyText="暂无云服务与访问级别分布"
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
                  <DashPanelHead
                    icon="globe"
                    tone="violet"
                    title="数据源与 API 网关 (AGW)"
                    sub="已登记外部查询接口与绑定覆盖统计"
                  />
                  <div className="dash-mini-stats compact-2" style={{ marginBottom: 14 }}>
                    <div><span>空间自建源</span><b>{fmtNum(data.dataSources.workspaceCount)}</b></div>
                    <div><span>平台公共源</span><b>{fmtNum(data.dataSources.platformCount)}</b></div>
                    <div><span>已绑定应用</span><b>{fmtNum(data.dataSources.boundAppsCount)}</b></div>
                    <div><span>绑定关系数</span><b>{fmtNum(data.dataSources.totalBindings)}</b></div>
                  </div>
                  <BreakdownBars
                    emptyIcon="globe"
                    emptyText="当前无活跃登记的数据源"
                    emptyHint="配置空间或公共数据源后将在此展示类型占比"
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
                    ]}
                  />
                </div>
              </div>

              <div className="dash-grid-2">
                <div className="dash-panel">
                  <DashPanelHead
                    icon="code"
                    tone="violet"
                    title="云函数运行监控（Deno 宿主）"
                    sub={`总调用 ${fmtNum(data.cloud.functions.totalCalls)} · 成功率 ${data.cloud.functions.successRate}% · 平均 ${data.cloud.functions.avgDurationMs} ms · P95 ${data.cloud.functions.p95DurationMs} ms`}
                  />
                  {data.cloud.functions.byName.length === 0 ? (
                    <DashEmptyBox icon="code" title="暂无云函数调用记录" hint="应用启用 Deno 云函数并触发调用后，将实时统计耗时与报错" />
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
                  <DashPanelHead
                    icon="share"
                    tone="indigo"
                    title="数据源代理调用监控"
                    sub={`总调用 ${fmtNum(data.dataSources.calls.totalCalls)} · 成功率 ${data.dataSources.calls.successRate}% · 平均耗时 ${data.dataSources.calls.avgDurationMs} ms`}
                  />
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
                    <DashEmptyBox icon="share" title="暂无数据源调用记录" hint="应用预览或线上站点发起数据源代理请求后展示调用排行" />
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

              <div className="dash-grid-3">
                <div className="dash-panel">
                  <DashPanelHead
                    icon="spark"
                    tone="violet"
                    title="创造页会话 & 文档产出"
                    sub={`会话转应用率 ${data.interactions.conversations.conversionRate}% · 均 ${
                      data.interactions.conversations.total > 0
                        ? (
                            (data.interactions.conversations.userMessages + data.interactions.conversations.assistantMessages) /
                            data.interactions.conversations.total
                          ).toFixed(1)
                        : '0.0'
                    } 条消息/会话`}
                  />
                  <div className="dash-mini-stats compact-2" style={{ marginBottom: 12 }}>
                    <div><span>创造会话总数</span><b>{fmtNum(data.interactions.conversations.total)}</b></div>
                    <div><span>已转为应用</span><b>{fmtNum(data.interactions.conversations.convertedToApp)}</b></div>
                    <div><span>含深度思考回复</span><b>{fmtNum(data.interactions.conversations.thinkingMessages)}</b></div>
                    <div><span>产出文件 / 版本</span><b>{fmtNum(data.interactions.conversations.filesTotal)} / {fmtNum(data.interactions.conversations.fileVersionsTotal)}</b></div>
                  </div>
                  <div className="dash-sub-block" style={{ marginTop: 0, paddingTop: 0, borderTop: 'none' }}>
                    <h4>会话消息构成</h4>
                    <SegmentedStrip
                      segments={[
                        { key: 'c-user', label: '用户提问', value: data.interactions.conversations.userMessages, color: '#7953dc' },
                        { key: 'c-ai', label: 'AI 回复', value: data.interactions.conversations.assistantMessages, color: '#2f9e8f' },
                      ]}
                    />
                  </div>
                  <div className="dash-sub-block">
                    <h4>关键转化与思考渗透</h4>
                    <div className="dash-rate-bars">
                      <RateMetricBar
                        label="会话 → 应用转化"
                        valueText={`${data.interactions.conversations.convertedToApp} / ${data.interactions.conversations.total}`}
                        pct={data.interactions.conversations.conversionRate}
                        color="#7953dc"
                      />
                      <RateMetricBar
                        label="AI 深度思考占比"
                        valueText={`${data.interactions.conversations.thinkingMessages} / ${data.interactions.conversations.assistantMessages}`}
                        pct={
                          data.interactions.conversations.assistantMessages > 0
                            ? Math.round((data.interactions.conversations.thinkingMessages / data.interactions.conversations.assistantMessages) * 1000) / 10
                            : 0
                        }
                        color="#2f9e8f"
                      />
                    </div>
                  </div>
                </div>

                <div className="dash-panel">
                  <DashPanelHead
                    icon="agent"
                    tone="indigo"
                    title="工作台协作 & 小织帮助"
                    sub={`共 ${fmtNum(data.interactions.workbench.totalMessages)} 条工作台消息 · 小织咨询 ${fmtNum(data.interactions.help.conversations)} 次`}
                  />
                  <div className="dash-mini-stats compact-2" style={{ marginBottom: 12 }}>
                    <div><span>工作台消息总量</span><b>{fmtNum(data.interactions.workbench.totalMessages)}</b></div>
                    <div><span>定点元素修改</span><b>{fmtNum(data.interactions.workbench.elementContextMessages)}</b></div>
                    <div><span>小织会话 / 提问</span><b>{fmtNum(data.interactions.help.conversations)} / {fmtNum(data.interactions.help.userQuestions)}</b></div>
                    <div><span>问题上报反馈</span><b>{fmtNum(data.interactions.help.feedbackCount)}</b></div>
                  </div>
                  <div className="dash-sub-block" style={{ marginTop: 0, paddingTop: 0, borderTop: 'none' }}>
                    <h4>工作台消息角色分布</h4>
                    <SegmentedStrip
                      segments={[
                        { key: 'w-user', label: '用户指令', value: data.interactions.workbench.userMessages, color: '#4c7be8' },
                        { key: 'w-ai', label: 'AI 响应', value: data.interactions.workbench.assistantMessages, color: '#7953dc' },
                        { key: 'w-sys', label: '系统提示', value: data.interactions.workbench.systemMessages, color: '#9c94b3' },
                      ]}
                    />
                  </div>
                  <div className="dash-sub-block">
                    <h4>高阶交互与帮助触达</h4>
                    <BreakdownBars
                      emptyIcon="agent"
                      emptyText="暂无高阶协作交互记录"
                      items={[
                        { key: 'wb-el', label: '定点元素修改', value: data.interactions.workbench.elementContextMessages, color: '#4c7be8' },
                        { key: 'wb-help', label: '小织智能问答提问', value: data.interactions.help.userQuestions, color: '#2f9e8f' },
                        { key: 'wb-skill', label: '消息挂载技能', value: data.interactions.workbench.skillAttachedMessages, color: '#7953dc' },
                        { key: 'wb-asset', label: '消息挂载素材附件', value: data.interactions.workbench.assetAttachedMessages, color: '#e28743' },
                      ].filter((x) => x.value > 0)}
                    />
                  </div>
                </div>

                <div className="dash-panel">
                  <DashPanelHead
                    icon="image"
                    tone="teal"
                    title="素材附件与代码导入"
                    sub={`素材 ${fmtNum(data.interactions.assets.total)} 个（${fmtBytes(data.interactions.assets.totalBytes)}）· 上传 ${fmtNum(data.interactions.assets.uploadsTotal)} 次`}
                  />
                  <div className="dash-mini-stats compact-2" style={{ marginBottom: 12 }}>
                    <div><span>上传素材总数</span><b>{fmtNum(data.interactions.assets.total)}</b></div>
                    <div><span>总占用空间</span><b>{fmtBytes(data.interactions.assets.totalBytes)}</b></div>
                    <div><span>代码导入次数</span><b>{fmtNum(data.interactions.assets.uploadsTotal)}</b></div>
                    <div><span>文件分类数</span><b>{fmtNum(data.interactions.assets.byCategory.length)}</b></div>
                  </div>
                  <BreakdownBars
                    emptyIcon="image"
                    emptyText="暂无上传素材"
                    emptyHint="支持图片、文档等附件用于对话建站"
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

              <div className="dash-grid-2">
                <div className="dash-panel">
                  <DashPanelHead
                    icon="help"
                    tone="amber"
                    title="用户问题上报反馈（最新 10 条）"
                    sub={`小织咨询 ${fmtNum(data.interactions.help.conversations)} 次 · 提问 ${fmtNum(data.interactions.help.userQuestions)} 条 · 上报 ${fmtNum(data.interactions.help.feedbackCount)} 条`}
                    right={
                      isAdmin ? (
                        <button type="button" className="dash-link" onClick={() => setSection('admin')}>
                          进入反馈分诊台 →
                        </button>
                      ) : undefined
                    }
                  />
                  <div className="dash-mini-stats compact-3" style={{ marginBottom: 12 }}>
                    <div><span>小织咨询会话</span><b>{fmtNum(data.interactions.help.conversations)}</b></div>
                    <div><span>员工有效提问</span><b>{fmtNum(data.interactions.help.userQuestions)}</b></div>
                    <div>
                      <span>待跟进 / 累计上报</span>
                      <b className={(adminQ.data?.feedbackOpenCount ?? 0) > 0 ? 'dash-warn-num' : ''}>
                        {isAdmin && adminQ.data ? `${fmtNum(adminQ.data.feedbackOpenCount)} / ` : ''}
                        {fmtNum(data.interactions.help.feedbackCount)}
                      </b>
                    </div>
                  </div>
                  {data.interactions.help.recentFeedbacks.length === 0 ? (
                    <DashEmptyBox icon="help" title="暂无问题上报反馈" hint="来自小织助手「问题上报」的员工反馈会实时汇总于此" />
                  ) : (
                    <ul className="dash-feedback-list">
                      {data.interactions.help.recentFeedbacks.map((fb) => {
                        const matchedAdminFb = adminQ.data?.feedbacks.find((x) => x.id === fb.id);
                        const st = matchedAdminFb?.status;
                        return (
                          <li key={fb.id}>
                            <div className="dash-fb-row-head">
                              {st ? (
                                <span className={`dash-pill ${st === 'resolved' ? 'ok' : st === 'open' ? 'err' : 'cloud'}`}>
                                  {st === 'open' ? '待处理' : st === 'in_progress' ? '跟进中' : st === 'resolved' ? '已解决' : '已忽略'}
                                </span>
                              ) : (
                                <span className="dash-pill">用户反馈</span>
                              )}
                              <small>{fmtTime(fb.createdAt)} · <code>{fb.workspaceId}</code></small>
                            </div>
                            <p>{fb.content}</p>
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>

                <div className="dash-panel">
                  <DashPanelHead
                    icon="community"
                    tone="rose"
                    title="灵感社区贡献与热门作品"
                    sub={`全社区 ${fmtNum(data.ecosystem.platformCommunity.totalWorks)} 个作品（浏览 ${fmtNum(data.ecosystem.platformCommunity.totalViews)} · 复刻 ${fmtNum(data.ecosystem.platformCommunity.totalClones)} · 赞 ${fmtNum(data.ecosystem.platformCommunity.totalLikes)}）`}
                    right={
                      isAdmin ? (
                        <button type="button" className="dash-link" onClick={() => setSection('admin')}>
                          内容精选与治理 →
                        </button>
                      ) : undefined
                    }
                  />
                  <div className="dash-mini-stats compact-4" style={{ marginBottom: 12 }}>
                    <div><span>公开作品</span><b>{fmtNum(data.ecosystem.platformCommunity.totalWorks)}</b></div>
                    <div><span>累计浏览</span><b>{fmtNum(data.ecosystem.platformCommunity.totalViews)}</b></div>
                    <div><span>模板复刻</span><b>{fmtNum(data.ecosystem.platformCommunity.totalClones)}</b></div>
                    <div><span>收获点赞</span><b>{fmtNum(data.ecosystem.platformCommunity.totalLikes)}</b></div>
                  </div>
                  <div className="dash-rate-bars" style={{ marginBottom: 12 }}>
                    <RateMetricBar
                      label="浏览 → 模板复刻率"
                      valueText={`${data.ecosystem.platformCommunity.totalClones} / ${data.ecosystem.platformCommunity.totalViews}`}
                      pct={
                        data.ecosystem.platformCommunity.totalViews > 0
                          ? Math.round((data.ecosystem.platformCommunity.totalClones / data.ecosystem.platformCommunity.totalViews) * 1000) / 10
                          : 0
                      }
                      color="#d0465c"
                    />
                    <RateMetricBar
                      label="浏览 → 点赞互动率"
                      valueText={`${data.ecosystem.platformCommunity.totalLikes} / ${data.ecosystem.platformCommunity.totalViews}`}
                      pct={
                        data.ecosystem.platformCommunity.totalViews > 0
                          ? Math.round((data.ecosystem.platformCommunity.totalLikes / data.ecosystem.platformCommunity.totalViews) * 1000) / 10
                          : 0
                      }
                      color="#7953dc"
                    />
                  </div>
                  {data.ecosystem.platformCommunity.topWorks.length === 0 ? (
                    <DashEmptyBox icon="community" title="灵感社区暂无公开作品" hint="将优质应用分享到灵感社区后可实时查看浏览与复刻榜单" />
                  ) : (
                    <div className="dash-table-wrap">
                      <table className="dash-table">
                        <thead>
                          <tr>
                            <th scope="col">作品</th>
                            <th scope="col">类型</th>
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
                              <td><span className="dash-tag">{w.appType}</span></td>
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

              <div className="dash-panel">
                <DashPanelHead
                  icon="terminal"
                  tone="slate"
                  title="集群运行期持久化监控与跨实例控制"
                  sub={`固定窗口（7 天遥测 / 10 分钟滑窗 · 不受顶部空间与日期筛选影响）· 汇总 ${Math.max(1, data.system.runtime.instances?.length ?? 1)} 个实例`}
                  right={
                    isAdmin ? (
                      <button type="button" className="dash-link" onClick={() => setSection('cluster')}>
                        进入集群节点拓扑与沙箱控制 →
                      </button>
                    ) : undefined
                  }
                />
                <div className="dash-mini-stats">
                  <div>
                    <span className="dash-mini-label-icon"><Icon name="terminal" /><span>沙箱冷启动</span></span>
                    <b>{fmtNum(data.system.runtime.sandbox?.count ?? 0)} 次</b>
                    <small>
                      均 {data.system.runtime.sandbox?.avgMs ?? 0}ms / P95 {data.system.runtime.sandbox?.p95Ms ?? 0}ms
                    </small>
                  </div>
                  <div>
                    <span className="dash-mini-label-icon"><Icon name="spark" /><span>上游 LLM 调用</span></span>
                    <b>{fmtNum(data.system.runtime.llm?.count ?? 0)} 次</b>
                    <small>
                      均 {data.system.runtime.llm?.avgMs ?? 0}ms / P95 {data.system.runtime.llm?.p95Ms ?? 0}ms · 重试{' '}
                      {data.system.runtime.llm?.retries ?? 0}
                    </small>
                  </div>
                  <div>
                    <span className="dash-mini-label-icon"><Icon name="alert" /><span>额度错误率</span></span>
                    <b>{((data.system.runtime.llm?.quotaErrorRate ?? 0) * 100).toFixed(2)}%</b>
                    <small>额度错 {data.system.runtime.llm?.quotaErrors ?? 0} 次</small>
                  </div>
                  <div>
                    <span className="dash-mini-label-icon"><Icon name="lock" /><span>近 10 分钟限流拦截</span></span>
                    <b className={(data.system.rateLimits.rejectedLast10Min ?? data.system.rateLimits.hitsLast10Min) > 0 ? 'dash-warn-num' : ''}>
                      {fmtNum(data.system.rateLimits.rejectedLast10Min ?? data.system.rateLimits.hitsLast10Min)}
                    </b>
                    <small>
                      滑窗 {fmtNum(data.system.rateLimits.activeWindowHits ?? 0)} · 放行 {fmtNum(data.system.rateLimits.allowedLast10Min ?? 0)} · 24h 拦截{' '}
                      {fmtNum(data.system.rateLimits.rejected24h ?? 0)}
                    </small>
                  </div>
                  <div>
                    <span className="dash-mini-label-icon"><Icon name="share" /><span>跨实例任务信号</span></span>
                    <b>{fmtNum(data.system.taskSignals.consumed)} 已消费</b>
                    <small>待消费 {fmtNum(data.system.taskSignals.pending)}</small>
                  </div>
                  <div>
                    <span className="dash-mini-label-icon"><Icon name="chart" /><span>集群累计任务 (7d)</span></span>
                    <b>{fmtNum(data.system.runtime.tasks?.total ?? 0)}</b>
                    <small>
                      成 {data.system.runtime.tasks?.succeeded ?? 0} / 败 {data.system.runtime.tasks?.failed ?? 0} · P95{' '}
                      {fmtDurationMs(data.system.runtime.tasks?.p95Ms)}
                    </small>
                  </div>
                </div>
              </div>
            </section>
          ) : null}

          {/* 治理与运维：在「全部维度」展示高密度治理摘要入口，在「治理与运维」专页展开完整控制台 */}
          {isAdmin && section === 'all' && adminQ.data ? (
            <section className="dash-section" aria-label="平台治理快捷入口">
              <div className="dash-panel">
                <DashPanelHead
                  icon="lock"
                  tone="violet"
                  title="平台治理、配额运维与操作审计"
                  sub={`当前操作人 ${adminQ.data.currentActor} · 默认配额 ${adminQ.data.defaultQuota.maxApps} 应用 / ${adminQ.data.defaultQuota.dailyTaskLimit} 次日任务 / ${adminQ.data.defaultQuota.maxConcurrentTasks} 并发`}
                  right={
                    <button type="button" className="primary-button dash-compact-btn" onClick={() => navigate('/admin')}>
                      进入管理工作台 →
                    </button>
                  }
                />
                <div className="dash-mini-stats compact-4">
                  <div>
                    <span className="dash-mini-label-icon"><Icon name="settings" /><span>自定义配额空间</span></span>
                    <b>{fmtNum(adminQ.data.customQuotas.length)}</b>
                    <small>覆盖全局默认上限</small>
                  </div>
                  <div>
                    <span className="dash-mini-label-icon"><Icon name="help" /><span>待跟进用户反馈</span></span>
                    <b className={adminQ.data.feedbackOpenCount > 0 ? 'dash-warn-num' : ''}>{fmtNum(adminQ.data.feedbackOpenCount)}</b>
                    <small>累计 {fmtNum(adminQ.data.feedbacks.length)} 条员工上报</small>
                  </div>
                  <div>
                    <span className="dash-mini-label-icon"><Icon name="globe" /><span>线上站点 / 回收站</span></span>
                    <b>{fmtNum(adminQ.data.publishedApps?.length ?? 0)} / {fmtNum(adminQ.data.deletedApps.length)}</b>
                    <small>支持风控权限调整与一键恢复</small>
                  </div>
                  <div>
                    <span className="dash-mini-label-icon"><Icon name="history" /><span>审计留痕记录</span></span>
                    <b>{fmtNum(adminQ.data.auditLogs.length)}</b>
                    <small>支持按动作筛选与 CSV 导出</small>
                  </div>
                </div>
              </div>
            </section>
          ) : null}

          {isAdmin && section === 'admin' ? (
            <AdminGovernancePanel
              overview={adminQ.data}
              loading={adminQ.isLoading}
              error={adminQ.error}
              onRetry={() => void adminQ.refetch()}
              dashboard={data}
              onOpenQuotaModal={(target) => setQuotaTarget(target)}
            />
          ) : null}

          {quotaTarget && adminQ.data ? (
            <AdminQuotaModal
              target={quotaTarget}
              defaultQuota={adminQ.data.defaultQuota}
              onClose={() => setQuotaTarget(null)}
              onSaved={invalidateAdminAndDash}
            />
          ) : null}
        </div>
      )}
    </div>
  );
}

function ActorPicker({
  actors,
  filterWorkspaceId,
  filterUserId,
  activeLabel,
  onSelect,
}: {
  actors: DashboardActor[];
  filterWorkspaceId: string;
  filterUserId: string;
  activeLabel: string;
  onSelect: (actor: { userId?: string | null; workspaceId?: string | null } | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState('');
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const filtered = useMemo(() => {
    const t = q.trim().toLowerCase();
    if (!t) return actors;
    return actors.filter(
      (a) =>
        a.label.toLowerCase().includes(t) ||
        (a.workspaceId ?? '').toLowerCase().includes(t) ||
        a.loginName.toLowerCase().includes(t) ||
        a.realName.toLowerCase().includes(t),
    );
  }, [actors, q]);

  const users = filtered.filter((a) => a.kind === 'user');
  const spaces = filtered.filter((a) => a.kind === 'workspace');
  const isFiltered = Boolean(filterWorkspaceId || filterUserId);

  return (
    <div className="dash-actor-wrap" ref={wrapRef}>
      <button
        type="button"
        className={`dash-actor-btn ${isFiltered ? 'filtered' : ''}`}
        aria-expanded={open}
        aria-haspopup="listbox"
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name="agent" />
        <span className="dash-actor-text">{isFiltered ? activeLabel : '全部人员 / 工作空间'}</span>
        <Icon name="down" />
      </button>
      {open ? (
        <div className="dash-actor-pop" role="dialog" aria-label="按人员或工作空间筛选">
          <label className="dash-actor-search">
            <Icon name="search" />
            <input
              autoFocus
              placeholder="搜索姓名、工号或 ws_xxx"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
          </label>
          <div className="dash-actor-list" role="listbox">
            <button
              type="button"
              role="option"
              aria-selected={!isFiltered}
              className={!isFiltered ? 'active' : ''}
              onClick={() => {
                onSelect(null);
                setOpen(false);
              }}
            >
              <span>全部人员与工作空间（全平台汇总）</span>
            </button>
            {users.length > 0 ? <div className="dash-actor-group">实名员工（SSO）</div> : null}
            {users.map((a) => {
              const sel = filterUserId === a.userId && !filterWorkspaceId;
              return (
                <button
                  key={a.value}
                  type="button"
                  role="option"
                  aria-selected={sel}
                  className={sel ? 'active' : ''}
                  onClick={() => {
                    onSelect({ userId: a.userId, workspaceId: null });
                    setOpen(false);
                  }}
                >
                  <span>{a.realName} ({a.loginName})</span>
                  <small>{a.apps}应用 · {a.tasks}任务</small>
                </button>
              );
            })}
            {spaces.length > 0 ? <div className="dash-actor-group">活跃工作空间</div> : null}
            {spaces.map((a) => {
              const sel = filterWorkspaceId === a.workspaceId;
              return (
                <button
                  key={a.value}
                  type="button"
                  role="option"
                  aria-selected={sel}
                  className={sel ? 'active' : ''}
                  onClick={() => {
                    onSelect({ workspaceId: a.workspaceId });
                    setOpen(false);
                  }}
                >
                  <span>{a.realName ? `${a.realName} · ${a.workspaceId}` : a.workspaceId}</span>
                  <small>{a.apps}应用 · {a.tasks}任务</small>
                </button>
              );
            })}
            {filtered.length === 0 ? <p className="dash-actor-empty">未匹配到人员或空间</p> : null}
          </div>
        </div>
      ) : null}
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

function RateMetricBar({
  label,
  valueText,
  pct,
  color,
}: {
  label: string;
  valueText: string;
  pct: number;
  color: string;
}) {
  const clamped = Math.max(0, Math.min(100, pct));
  return (
    <div className="dash-rate-row">
      <div className="dash-rate-top">
        <span>{label}</span>
        <div className="dash-rate-val">
          <small>{valueText}</small>
          <b>{pct}%</b>
        </div>
      </div>
      <div className="dash-rate-track" role="progressbar" aria-valuenow={clamped} aria-valuemin={0} aria-valuemax={100} aria-label={label}>
        <i style={{ width: `${Math.max(clamped, pct > 0 ? 3 : 0)}%`, background: color }} />
      </div>
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
