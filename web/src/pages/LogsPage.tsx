import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { useMeta } from '../api/queries';
import type {
  OpsLogActor,
  OpsLogFileView,
  OpsLogRange,
  OpsTaskDetailResponse,
  OpsTaskEventRow,
  OpsTaskSummary,
  SandboxItem,
} from '../api/types';
import { Icon } from '../lib/icons';
import { copyText, downloadUrl, handleTabListKeyDown } from '../lib/format';
import { useDebounced } from '../lib/useDebounced';
import { toast } from '../lib/store';
import { fmtBytes, fmtCompact, fmtDurationMs, fmtNum } from '../components/DashboardCharts';
import { EmptyState, ErrorState, LoadingState } from '../components/EmptyState';

type MainTab = 'tasks' | 'sandboxes' | 'schemas' | 'events' | 'functions' | 'sources' | 'runtime';
type DetailTab = 'events' | 'runner' | 'resources' | 'app';
type EventFilter = 'all' | 'tool' | 'log' | 'verify' | 'thinking' | 'status' | 'collab';
type LogLevelFilter = 'ALL' | 'ERROR' | 'WARN' | 'INFO';

const RANGES: [OpsLogRange, string][] = [
  ['1h', '近 1 小时'],
  ['24h', '近 24 小时'],
  ['7d', '近 7 天'],
  ['30d', '近 30 天'],
  ['all', '全部时间'],
];

const TASK_TYPE_LABELS: Record<string, string> = {
  plan: '需求规划',
  generate: '首次生成',
  modify: '对话修改',
  visual_save: '手动微调保存',
  code_save: '代码编辑保存',
  import: '代码导入部署',
  clone: '社区模板复刻',
};

const STATUS_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  PREPARING: '准备中',
  RUNNING: '执行中',
  VERIFYING: '校验中',
  COMMITTING: '提交中',
  CANCELLING: '停止中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
};

const EVENT_TYPE_LABELS: Record<string, string> = {
  status: '阶段状态',
  tool: '工具调用',
  log: '命令输出',
  verify: '质检门禁',
  thinking: '推理思考',
  thinking_end: '思考完成',
  agent_text: '回复正文',
  steer: '中途补充',
  confirm: '人工确认',
  todo: '待办规划',
  finished: '任务结束',
};

function statusTone(status: string): 'ok' | 'err' | 'run' | 'muted' {
  if (status === 'SUCCEEDED') return 'ok';
  if (status === 'FAILED') return 'err';
  if (status === 'CANCELLED') return 'muted';
  return 'run';
}

function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  const pad = (n: number, len = 2) => String(n).padStart(len, '0');
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function fmtClockMs(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number, len = 2) => String(n).padStart(len, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

function classifyTermLine(text: string): string {
  const low = text.toLowerCase();
  if (low.includes('error') || low.includes('failed') || low.includes('exception') || low.includes('fatal') || low.includes('uncaught')) {
    return 'is-err';
  }
  if (low.includes('warn') || low.includes('timeout') || low.includes('retry')) {
    return 'is-warn';
  }
  if (text.startsWith('[java]')) return 'is-java';
  return '';
}

function eventMatchesFilter(ev: OpsTaskEventRow, filter: EventFilter, onlyErrors: boolean, query: string): boolean {
  if (filter === 'tool' && ev.type !== 'tool') return false;
  if (filter === 'log' && ev.type !== 'log') return false;
  if (filter === 'verify' && ev.type !== 'verify') return false;
  if (filter === 'thinking' && ev.type !== 'thinking' && ev.type !== 'thinking_end') return false;
  if (filter === 'status' && ev.type !== 'status' && ev.type !== 'finished') return false;
  if (filter === 'collab' && ev.type !== 'confirm' && ev.type !== 'steer' && ev.type !== 'todo') return false;

  if (onlyErrors && !isEventError(ev)) return false;

  if (query) {
    const q = query.toLowerCase();
    const raw = `${ev.type} ${JSON.stringify(ev.payload ?? {})}`.toLowerCase();
    if (!raw.includes(q)) return false;
  }
  return true;
}

function isEventError(ev: OpsTaskEventRow): boolean {
  const p = ev.payload ?? {};
  if (ev.type === 'tool' && p.ok === false) return true;
  if (ev.type === 'verify' && Array.isArray(p.checks)) {
    return p.checks.some((c: Record<string, unknown>) => !c?.ok && !c?.skipped);
  }
  if (ev.type === 'finished' && p.status === 'FAILED') return true;
  if (ev.type === 'log') {
    const line = String(p.line ?? '').toLowerCase();
    return line.includes('error') || line.includes('failed') || line.includes('exception');
  }
  return false;
}

function formatJson(val: unknown): string {
  if (typeof val === 'string') return val;
  try {
    return JSON.stringify(val, null, 2);
  } catch {
    return String(val);
  }
}

function downloadJson(filename: string, data: unknown) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

function HighlightText({ text, query }: { text: string; query: string }) {
  const q = query.trim();
  if (!q) return <>{text}</>;
  const low = text.toLowerCase();
  const qLow = q.toLowerCase();
  const parts: (string | { hit: string; k: number })[] = [];
  let idx = 0;
  let pos = low.indexOf(qLow, idx);
  let k = 0;
  while (pos !== -1) {
    if (pos > idx) parts.push(text.slice(idx, pos));
    parts.push({ hit: text.slice(pos, pos + q.length), k: k++ });
    idx = pos + q.length;
    pos = low.indexOf(qLow, idx);
  }
  if (idx < text.length) parts.push(text.slice(idx));
  return (
    <>
      {parts.map((p, i) =>
        typeof p === 'string' ? p : <mark key={p.k + '-' + i} className="logs-hl">{p.hit}</mark>,
      )}
    </>
  );
}

function OpsPager({
  offset,
  limit,
  total,
  onChange,
}: {
  offset: number;
  limit: number;
  total: number;
  onChange: (nextOffset: number) => void;
}) {
  if (total <= 0) return null;
  const start = offset + 1;
  const end = Math.min(offset + limit, total);
  return (
    <div className="logs-pager">
      <span>
        第 {start}–{end} / {fmtNum(total)}
      </span>
      <div className="logs-pager-btns">
        <button type="button" disabled={offset === 0} onClick={() => onChange(Math.max(0, offset - limit))}>
          上一页
        </button>
        <button type="button" disabled={offset + limit >= total} onClick={() => onChange(offset + limit)}>
          下一页
        </button>
      </div>
    </div>
  );
}

export function LogsPage() {
  const metaQ = useMeta();
  const isAdmin = Boolean(metaQ.data?.auth?.admin);
  const [params, setParams] = useSearchParams();
  const rawTab = params.get('tab');
  const mainTab: MainTab =
    rawTab === 'sandboxes' || rawTab === 'schemas' || rawTab === 'events' || rawTab === 'functions' || rawTab === 'sources' || (rawTab === 'runtime' && isAdmin)
      ? rawTab
      : 'tasks';

  const setMainTab = (tab: MainTab) => {
    const next = new URLSearchParams(params);
    if (tab === 'tasks') next.delete('tab');
    else next.set('tab', tab);
    setParams(next, { replace: true });
  };

  const tabs: [MainTab, string][] = [
    ['tasks', 'AI 任务全链路 Trace'],
    ['sandboxes', '沙箱生命周期日志'],
    ['events', '模型网关与异常追踪'],
    ['schemas', '云库 Schema / DDL'],
    ['functions', '云函数执行日志'],
    ['sources', '数据源调用日志'],
    ...(isAdmin ? ([['runtime', '集群服务日志与审计']] as [MainTab, string][]) : []),
  ];

  return (
    <div className="content-page logs-page">
      <header className="dash-header-top">
        <div className="dash-title-block">
          <span className="overline">OBSERVABILITY · TRACE WATERFALL, SANDBOX LIFECYCLE &amp; LOG EXPLORER</span>
          <div className="dash-title-row">
            <h1>全链路日志与 Trace 排查中心</h1>
            <span className={`dash-scope-badge ${isAdmin ? 'platform' : 'workspace'}`}>
              <i className="dash-pulse-dot" aria-hidden="true" />
              {isAdmin ? '全平台跨空间可观测视角' : '当前工作空间隔离视角'}
            </span>
          </div>
          <p>
            按全链路 Trace 视图透视任务排队、沙箱冷启动、AI 推理/工具调用、质检门禁耗时瀑布，联动沙箱生命周期台账、Runner 终端分级检索与集群多节点服务日志。
          </p>
        </div>
      </header>

      <nav
        className="dash-nav-bar logs-tabs"
        role="tablist"
        aria-label="日志排查模块"
        onKeyDown={(e) =>
          handleTabListKeyDown(
            e,
            tabs.map(([id]) => id),
            mainTab,
            setMainTab,
          )
        }
      >
        {tabs.map(([id, label]) => (
          <button
            key={id}
            type="button"
            role="tab"
            tabIndex={mainTab === id ? 0 : -1}
            aria-selected={mainTab === id}
            className={mainTab === id ? 'active' : ''}
            onClick={() => setMainTab(id)}
          >
            {label}
          </button>
        ))}
      </nav>

      {mainTab === 'tasks' ? <TaskLogsWorkbench onJumpSandboxes={() => setMainTab('sandboxes')} /> : null}
      {mainTab === 'sandboxes' ? <SandboxLogsView /> : null}
      {mainTab === 'schemas' ? <SchemasView /> : null}
      {mainTab === 'events' ? <PlatformEventsView /> : null}
      {mainTab === 'functions' ? <FunctionLogsView /> : null}
      {mainTab === 'sources' ? <SourceCallsView /> : null}
      {mainTab === 'runtime' && isAdmin ? <RuntimeLogsView /> : null}
    </div>
  );
}

// ==========================================================================
// 1. AI 开发过程日志分栏工作台
// ==========================================================================

function TaskLogsWorkbench({ onJumpSandboxes }: { onJumpSandboxes?: () => void }) {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const selectedTaskId = params.get('taskId') ?? '';
  const filterWorkspaceId = params.get('workspaceId') ?? '';
  const filterUserId = params.get('userId') ?? '';

  const [range, setRange] = useState<OpsLogRange>('7d');
  const [status, setStatus] = useState<string>('ALL');
  const [type, setType] = useState<string>('ALL');
  const [errorCode, setErrorCode] = useState<string>('');
  const [search, setSearch] = useState<string>('');
  const [offset, setOffset] = useState<number>(0);
  const [autoRefreshList, setAutoRefreshList] = useState<boolean>(false);
  const limit = 30;
  const debouncedSearch = useDebounced(search.trim(), 260);
  const filterSignature = `${range}|${status}|${type}|${filterWorkspaceId}|${filterUserId}|${errorCode}|${debouncedSearch}|${offset}`;
  const lastFilterSignatureRef = useRef<string>(filterSignature);
  const listPaneRef = useRef<HTMLElement>(null);
  const detailPaneRef = useRef<HTMLElement>(null);

  const listQ = useQuery({
    queryKey: ['ops-tasks', range, status, type, filterWorkspaceId, filterUserId, errorCode, debouncedSearch, offset],
    queryFn: () =>
      api.opsTasks({
        range,
        status,
        type: type === 'ALL' ? undefined : type,
        workspaceId: filterWorkspaceId || undefined,
        userId: filterUserId || undefined,
        errorCode: errorCode || undefined,
        q: debouncedSearch || undefined,
        limit,
        offset,
      }),
    placeholderData: keepPreviousData,
    refetchInterval: autoRefreshList ? 4000 : false,
  });

  const data = listQ.data;
  const items = data?.items ?? [];

  const selectTask = (id: string, scrollDetail = false) => {
    const next = new URLSearchParams(params);
    if (id) next.set('taskId', id);
    else next.delete('taskId');
    setParams(next, { replace: true });
    if (scrollDetail && typeof window !== 'undefined' && window.innerWidth <= 1120) {
      setTimeout(() => detailPaneRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 40);
    }
  };

  const selectActor = (actor: { userId?: string | null; workspaceId?: string | null } | null) => {
    const next = new URLSearchParams(params);
    if (actor?.userId && !actor.workspaceId) {
      next.set('userId', actor.userId);
      next.delete('workspaceId');
    } else if (actor?.workspaceId) {
      next.set('workspaceId', actor.workspaceId);
      next.delete('userId');
    } else {
      next.delete('userId');
      next.delete('workspaceId');
    }
    setOffset(0);
    setParams(next, { replace: true });
  };

  // 过滤条件变化或列表首次加载完成时，同步选中当前结果第一条或清空陈旧详情（F02）
  useEffect(() => {
    if (listQ.isLoading || listQ.isPlaceholderData || !data) return;
    const filterChanged = lastFilterSignatureRef.current !== filterSignature;
    if (filterChanged) {
      lastFilterSignatureRef.current = filterSignature;
    }
    const inItems = selectedTaskId ? items.some((it) => it.id === selectedTaskId) : false;
    if (filterChanged) {
      if (!inItems) {
        selectTask(items[0]?.id ?? '');
      }
      return;
    }
    if (!selectedTaskId && items.length > 0) {
      selectTask(items[0].id);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterSignature, listQ.isLoading, listQ.isPlaceholderData, data, selectedTaskId, items]);

  const selectionMismatchesList =
    Boolean(selectedTaskId) && !listQ.isLoading && !listQ.isPlaceholderData && Boolean(data) && !items.some((it) => it.id === selectedTaskId);

  const activeActorLabel = useMemo(() => {
    const actors = data?.actors ?? [];
    if (filterUserId) {
      const hit = actors.find((a) => a.kind === 'user' && a.userId === filterUserId);
      return hit ? `${hit.realName} (${hit.loginName})` : filterUserId;
    }
    if (filterWorkspaceId) {
      const hit = actors.find((a) => a.kind === 'workspace' && a.workspaceId === filterWorkspaceId);
      return hit ? (hit.realName ? `${hit.realName} · ${hit.workspaceId}` : hit.workspaceId!) : filterWorkspaceId;
    }
    return '全部人员 / 工作空间';
  }, [data?.actors, filterUserId, filterWorkspaceId]);

  return (
    <>
      <div className="logs-filter-bar">
        <div className="logs-filter-row">
          <div className="logs-filter-left">
            <label className="logs-search-box">
              <Icon name="search" />
              <input
                aria-label="搜索任务日志"
                placeholder="搜 taskId / 应用名 / 工号 / 错误码 / Prompt…"
                value={search}
                onChange={(e) => {
                  setSearch(e.target.value);
                  setOffset(0);
                }}
              />
              {search ? (
                <button type="button" className="logs-search-clear" aria-label="清除搜索" onClick={() => setSearch('')}>
                  <Icon name="close" />
                </button>
              ) : null}
            </label>

            {(data?.actors?.length ?? 0) > 0 ? (
              <OpsActorPicker
                actors={data?.actors ?? []}
                filterWorkspaceId={filterWorkspaceId}
                filterUserId={filterUserId}
                activeLabel={activeActorLabel}
                onSelect={selectActor}
              />
            ) : null}

            <select
              className="logs-select"
              aria-label="筛选任务类型"
              value={type}
              onChange={(e) => {
                setType(e.target.value);
                setOffset(0);
              }}
            >
              <option value="ALL">全部任务类型</option>
              {Object.entries(TASK_TYPE_LABELS).map(([k, v]) => (
                <option key={k} value={k}>
                  {v}
                </option>
              ))}
            </select>

            <select
              className="logs-select"
              aria-label="筛选时间范围"
              value={range}
              onChange={(e) => {
                setRange(e.target.value as OpsLogRange);
                setOffset(0);
              }}
            >
              {RANGES.map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
          </div>

          <div className="logs-filter-right">
            <button
              type="button"
              className={`logs-chip ${autoRefreshList ? 'active' : ''}`}
              onClick={() => setAutoRefreshList((v) => !v)}
              title="每 4 秒自动刷新左侧任务列表"
            >
              {autoRefreshList ? <span className="logs-live-dot" aria-hidden="true" /> : null}
              <span>{autoRefreshList ? '自动刷新中' : '自动刷新'}</span>
            </button>
            <button type="button" className="dash-action-btn" onClick={() => void listQ.refetch()} disabled={listQ.isFetching}>
              <Icon name="refresh" className={listQ.isFetching ? 'spinning' : ''} />
              <span>{listQ.isFetching ? '刷新中…' : '刷新'}</span>
            </button>
          </div>
        </div>

        <div className="logs-filter-row">
          <div className="logs-chip-row" role="group" aria-label="按任务状态筛选">
            {(
              [
                ['ALL', '全部', data?.summary.total ?? 0, ''],
                ['ACTIVE', '执行中', data?.summary.active ?? 0, ''],
                ['FAILED', '失败', data?.summary.failed ?? 0, 'err'],
                ['SUCCEEDED', '成功', data?.summary.succeeded ?? 0, 'ok'],
                ['CANCELLED', '已取消', data?.summary.cancelled ?? 0, ''],
              ] as [string, string, number, string][]
            ).map(([k, label, count, cls]) => (
              <button
                key={k}
                type="button"
                className={`logs-chip ${cls} ${status === k ? 'active' : ''}`}
                onClick={() => {
                  setStatus(k);
                  if (k !== 'FAILED') setErrorCode('');
                  setOffset(0);
                }}
              >
                {k === 'ACTIVE' && count > 0 ? <span className="logs-live-dot" aria-hidden="true" /> : null}
                <span>{label}</span>
                <b>{fmtNum(count)}</b>
              </button>
            ))}
          </div>

          {(data?.errorCodes?.length ?? 0) > 0 ? (
            <div className="logs-chip-row" aria-label="按错误码快筛">
              {data!.errorCodes.map((ec) => (
                <button
                  key={ec.code}
                  type="button"
                  className={`logs-err-chip ${errorCode === ec.code ? 'active' : ''}`}
                  onClick={() => {
                    const nextCode = errorCode === ec.code ? '' : ec.code;
                    setErrorCode(nextCode);
                    if (nextCode) setStatus('FAILED');
                    setOffset(0);
                  }}
                >
                  <span>{ec.code}</span>
                  <b>{ec.count}</b>
                </button>
              ))}
            </div>
          ) : null}
        </div>
      </div>

      <div className="logs-split">
        <aside className="logs-list-pane" aria-label="任务列表" ref={listPaneRef}>
          <div className="logs-list-head">
            <span>匹配 {fmtNum(data?.total ?? 0)} 条任务</span>
            {errorCode ? (
              <button type="button" className="dash-link" onClick={() => setErrorCode('')}>
                清除错误码 {errorCode}
              </button>
            ) : null}
          </div>

          <div className="logs-list-scroll">
            {listQ.isLoading ? (
              <LoadingState label="正在检索任务…" />
            ) : listQ.error ? (
              <ErrorState message={errorMessage(listQ.error)} onRetry={() => void listQ.refetch()} />
            ) : items.length === 0 ? (
              <EmptyState icon="terminal" title="没有匹配的任务" desc="试试调整时间范围、状态或搜索关键字。" />
            ) : (
              items.map((item) => (
                <TaskListCard
                  key={item.id}
                  item={item}
                  selected={item.id === selectedTaskId}
                  onSelect={() => selectTask(item.id, true)}
                />
              ))
            )}
          </div>

          <OpsPager offset={offset} limit={limit} total={data?.total ?? 0} onChange={setOffset} />
        </aside>

        <section className="logs-detail-pane" aria-label="任务全链路诊断" ref={detailPaneRef}>
          {selectionMismatchesList ? (
            <div className="logs-filter-mismatch-note" role="status">
              <span>当前筛选列表不含此任务（已按 URL 参数直达打开）</span>
              {items.length > 0 ? (
                <button type="button" className="dash-link" onClick={() => selectTask(items[0].id)}>
                  切换到匹配第一条
                </button>
              ) : (
                <button type="button" className="dash-link" onClick={() => selectTask('')}>
                  清空详情
                </button>
              )}
            </div>
          ) : null}
          {!selectedTaskId ? (
            <EmptyState icon="terminal" title="选择一条任务查看开发日志" desc="从左侧挑选任意任务，或在管理看板点击失败任务跳转至此。" />
          ) : (
            <TaskDiagnosticPane
              taskId={selectedTaskId}
              onOpenApp={(appId) => navigate('/apps/' + appId)}
              onJumpSandboxes={onJumpSandboxes}
              onBackToList={() => listPaneRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
            />
          )}
        </section>
      </div>
    </>
  );
}

function TaskListCard({ item, selected, onSelect }: { item: OpsTaskSummary; selected: boolean; onSelect: () => void }) {
  const tone = statusTone(item.status);
  return (
    <button
      type="button"
      className={`logs-task-card ${selected ? 'selected' : ''} ${item.status === 'FAILED' ? 'failed' : ''} ${item.active ? 'active-run' : ''}`}
      onClick={onSelect}
    >
      <div className="logs-card-top">
        <span className="logs-card-title">{item.appName}</span>
        <span className={`logs-status ${tone}`}>
          {item.active ? <i className="logs-live-dot" aria-hidden="true" /> : null}
          {STATUS_LABELS[item.status] ?? item.status}
        </span>
      </div>
      <div className="logs-card-meta">
        <span>{TASK_TYPE_LABELS[item.type] ?? item.type}</span>
        <span>·</span>
        <span>{item.ownerName ? `${item.ownerName}${item.ownerLoginName ? `(${item.ownerLoginName})` : ''}` : item.workspaceId}</span>
        <span>·</span>
        <span className="logs-mono">{item.id}</span>
      </div>
      {item.promptPreview ? <div className="logs-card-prompt">{item.promptPreview}</div> : null}
      {item.status === 'FAILED' && (item.errorCode || item.errorMessage) ? (
        <div className="logs-card-err">
          <b>{item.errorCode || 'FAILED'}</b>
          {item.errorMessage ? ` · ${item.errorMessage}` : ''}
        </div>
      ) : null}
      <div className="logs-card-foot">
        <span>{fmtDateTime(item.createdAt)}</span>
        <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
          {item.toolErrorCount > 0 ? <em className="logs-mini-badge err">工具错 {item.toolErrorCount}</em> : null}
          {item.hasLogFile ? <em className="logs-mini-badge">Runner {fmtBytes(item.logFileBytes)}</em> : null}
          <span>{fmtDurationMs(item.durationMs)}</span>
        </span>
      </div>
    </button>
  );
}

// ==========================================================================
// 2. 单任务全链路诊断详情面板（支持进行中任务实时追踪）
// ==========================================================================

function TaskDiagnosticPane({
  taskId,
  onOpenApp,
  onJumpSandboxes,
  onBackToList,
}: {
  taskId: string;
  onOpenApp: (appId: string) => void;
  onJumpSandboxes?: () => void;
  onBackToList?: () => void;
}) {
  const [subTab, setSubTab] = useState<DetailTab>('events');
  const [liveFollow, setLiveFollow] = useState<boolean>(true);
  const [liveEvents, setLiveEvents] = useState<OpsTaskEventRow[] | null>(null);
  const [liveTaskPatch, setLiveTaskPatch] = useState<Partial<OpsTaskSummary> | null>(null);
  const [liveRunnerLog, setLiveRunnerLog] = useState<OpsLogFileView | null>(null);

  const [evFilter, setEvFilter] = useState<EventFilter>('all');
  const [onlyEvErrors, setOnlyEvErrors] = useState<boolean>(false);
  const [evQuery, setEvQuery] = useState<string>('');
  const [expandAllEvents, setExpandAllEvents] = useState<boolean>(false);
  const [compactStream, setCompactStream] = useState<boolean>(true);

  const [runnerTail, setRunnerTail] = useState<number>(800);
  const [runnerQuery, setRunnerQuery] = useState<string>('');
  const [runnerLevel, setRunnerLevel] = useState<LogLevelFilter>('ALL');
  const [runnerWrap, setRunnerWrap] = useState<boolean>(true);
  const [autoScrollRunner, setAutoScrollRunner] = useState<boolean>(true);
  const runnerScrollRef = useRef<HTMLDivElement>(null);
  const eventListRef = useRef<HTMLDivElement>(null);

  const debouncedRunnerQuery = useDebounced(runnerQuery.trim(), 250);

  const detailQ = useQuery({
    queryKey: ['ops-task-detail', taskId],
    queryFn: () => api.opsTaskDetail(taskId, 800),
  });

  // 切换任务时重置实时叠加态
  useEffect(() => {
    setLiveEvents(null);
    setLiveTaskPatch(null);
    setLiveRunnerLog(null);
    setEvQuery('');
    setOnlyEvErrors(false);
    setEvFilter('all');
    setRunnerQuery('');
    setRunnerLevel('ALL');
  }, [taskId]);

  const baseDetail: OpsTaskDetailResponse | undefined = detailQ.data;
  const events = liveEvents ?? baseDetail?.events ?? [];
  const task = useMemo(() => {
    if (!baseDetail?.task) return undefined;
    return liveTaskPatch ? { ...baseDetail.task, ...liveTaskPatch } : baseDetail.task;
  }, [baseDetail?.task, liveTaskPatch]);

  // 单独拉取或搜索 Runner 日志（当搜索条件、级别或行数变化时）
  const runnerQ = useQuery({
    queryKey: ['ops-runner-log', taskId, runnerTail, debouncedRunnerQuery, runnerLevel],
    queryFn: () =>
      api.opsRunnerLog(taskId, {
        tail: runnerTail,
        q: debouncedRunnerQuery || undefined,
        level: runnerLevel === 'ALL' ? undefined : runnerLevel,
      }),
    enabled: subTab === 'runner' && (runnerTail !== 800 || !!debouncedRunnerQuery || runnerLevel !== 'ALL'),
  });

  const runnerLog =
    liveRunnerLog ??
    (subTab === 'runner' && (runnerTail !== 800 || !!debouncedRunnerQuery || runnerLevel !== 'ALL') ? runnerQ.data : baseDetail?.runnerLog);

  // 任务活跃时 1.5s 实时增量拉取 task_event + runner.log
  const lastSeqRef = useRef<number>(0);
  lastSeqRef.current = events.length ? events[events.length - 1].seq : 0;

  useEffect(() => {
    if (!task?.active || !liveFollow) return;
    let cancelled = false;
    const timer = window.setInterval(async () => {
      if (cancelled) return;
      try {
        const [delta, rlog] = await Promise.all([
          api.opsTaskEvents(taskId, lastSeqRef.current),
          api.opsRunnerLog(taskId, {
            tail: runnerTail,
            q: debouncedRunnerQuery || undefined,
            level: runnerLevel === 'ALL' ? undefined : runnerLevel,
          }),
        ]);
        if (cancelled) return;
        if (delta.events.length > 0) {
          setLiveEvents((prev) => {
            const base = prev ?? baseDetail?.events ?? [];
            const seen = new Set(base.map((x) => x.seq));
            const merged = [...base];
            for (const e of delta.events) {
              if (!seen.has(e.seq)) merged.push(e);
            }
            return merged;
          });
        }
        setLiveTaskPatch({
          status: delta.task.status,
          phaseLabel: delta.task.phaseLabel,
          progress: delta.task.progress,
          errorCode: delta.task.errorCode,
          errorMessage: delta.task.errorMessage,
          tokenUsed: delta.task.tokenUsed,
          startedAt: delta.task.startedAt,
          finishedAt: delta.task.finishedAt,
          active: !delta.task.terminal,
        });
        setLiveRunnerLog(rlog);
        if (delta.task.terminal) {
          void detailQ.refetch();
        }
      } catch {
        /* 轮询单次抖动忽略 */
      }
    }, 1500);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [taskId, task?.active, liveFollow, runnerTail, debouncedRunnerQuery, baseDetail?.events, detailQ]);

  useEffect(() => {
    if (subTab === 'runner' && autoScrollRunner && runnerScrollRef.current) {
      runnerScrollRef.current.scrollTop = runnerScrollRef.current.scrollHeight;
    }
  }, [subTab, autoScrollRunner, runnerLog?.lines.length]);

  const filteredEvents = useMemo<DisplayEventRow[]>(() => {
    const q = evQuery.trim();
    const matched = events.filter((e) => eventMatchesFilter(e, evFilter, onlyEvErrors, q));
    if (!compactStream || onlyEvErrors || q) return matched;
    return compactTraceEvents(matched);
  }, [events, evFilter, onlyEvErrors, evQuery, compactStream]);

  const jumpFirstErrorEvent = () => {
    const el = eventListRef.current?.querySelector('.logs-ev-card.is-err');
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    else toast('当前筛选视图下未包含错误事件');
  };

  const jumpFirstRunnerError = () => {
    const el = runnerScrollRef.current?.querySelector('.logs-term-line.is-err');
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    else toast('当前展示的日志行中未发现 ERROR');
  };

  const eventStats = useMemo(() => {
    const counts: Record<string, number> = {};
    let errors = 0;
    for (const e of events) {
      counts[e.type] = (counts[e.type] ?? 0) + 1;
      if (isEventError(e)) errors++;
    }
    return { counts, errors };
  }, [events]);

  if (detailQ.isLoading) return <LoadingState label="正在装载全链路诊断日志…" />;
  if (detailQ.error || !baseDetail || !task) {
    return <ErrorState message={errorMessage(detailQ.error)} onRetry={() => void detailQ.refetch()} />;
  }

  const tone = statusTone(task.status);
  const fullPrompt = String((task.input?.prompt as string) || (task.input?.content as string) || task.promptPreview || '');

  return (
    <>
      <div className="logs-detail-head">
        <div className="logs-detail-title-row">
          <h2>
            {onBackToList ? (
              <button type="button" className="logs-mobile-back" onClick={onBackToList}>
                ↑ 列表
              </button>
            ) : null}
            <span>{task.appName}</span>
            <span className={`logs-status ${tone}`}>
              {task.active ? <i className="logs-live-dot" aria-hidden="true" /> : null}
              {STATUS_LABELS[task.status] ?? task.status}
              {task.active ? ` · ${task.progress}%` : ''}
            </span>
            <span className="logs-mini-badge">{TASK_TYPE_LABELS[task.type] ?? task.type}</span>
          </h2>

          <div className="logs-detail-actions">
            {task.active ? (
              <button
                type="button"
                className={`logs-chip ${liveFollow ? 'active' : ''}`}
                onClick={() => setLiveFollow((v) => !v)}
              >
                {liveFollow ? <span className="logs-live-dot" aria-hidden="true" /> : null}
                <span>{liveFollow ? '实时追踪中' : '已暂停追踪'}</span>
              </button>
            ) : null}
            {task.isMine ? (
              <button type="button" className="dash-action-btn" onClick={() => onOpenApp(task.appId)}>
                <Icon name="external" />
                <span>打开应用工作台</span>
              </button>
            ) : null}
            <button
              type="button"
              className="dash-action-btn"
              onClick={() => {
                downloadJson(`zhiye-diag-${task.id}.json`, { ...baseDetail, events, runnerLog });
                toast('已导出完整任务诊断报告 JSON。');
              }}
            >
              <Icon name="download" />
              <span>导出诊断包</span>
            </button>
            <button type="button" className="dash-action-btn" onClick={() => void detailQ.refetch()} disabled={detailQ.isFetching}>
              <Icon name="refresh" className={detailQ.isFetching ? 'spinning' : ''} />
              <span>刷新</span>
            </button>
          </div>
        </div>

        <div className="logs-meta-grid">
          <div className="logs-meta-item">
            <span>任务编号 (taskId)</span>
            <button
              type="button"
              className="logs-copy-btn"
              onClick={async () => toast((await copyText(task.id)) ? `已复制 ${task.id}` : '复制失败')}
              title="点击复制 taskId"
            >
              <span>{task.id}</span>
              <Icon name="copy" />
            </button>
          </div>
          <div className="logs-meta-item">
            <span>所属用户 / 工作空间</span>
            <b title={`${task.ownerName} (${task.ownerLoginName}) · ${task.workspaceId}`}>
              {task.ownerName ? `${task.ownerName}${task.ownerLoginName ? ` (${task.ownerLoginName})` : ''}` : '匿名空间'} · {task.workspaceId}
            </b>
          </div>
          <div className="logs-meta-item">
            <span>耗时 / Token / 模型</span>
            <b>
              {fmtDurationMs(task.durationMs)} · {fmtCompact(task.tokenUsed)} · {task.model || 'auto'}
            </b>
          </div>
          <div className="logs-meta-item">
            <span>创建 / 开始 / 结束</span>
            <b title={`创建 ${fmtDateTime(task.createdAt)} / 开始 ${fmtDateTime(task.startedAt)} / 结束 ${fmtDateTime(task.finishedAt)}`}>
              {fmtDateTime(task.createdAt)} → {task.finishedAt ? fmtDateTime(task.finishedAt) : task.phaseLabel || '进行中'}
            </b>
          </div>
        </div>

        {task.errorCode || task.errorMessage ? (
          <div className="logs-error-banner" role="alert">
            <div className="logs-error-banner-head">
              <span>失败诊断：{task.errorCode || 'FAILED'}（阶段：{task.phaseLabel || '未知'}）</span>
              {task.leaseOwner ? <span className="logs-mono">Worker: {task.leaseOwner}</span> : null}
            </div>
            <pre>{task.errorMessage || '无详细错误信息'}</pre>
          </div>
        ) : null}

        {/* 执行阶段耗时瀑布图（Trace Waterfall） */}
        <TraceWaterfallBar
          task={task}
          events={events}
          sandbox={baseDetail.sandboxes?.[0] ?? null}
          onJumpSandboxes={onJumpSandboxes}
          onJumpTab={setSubTab}
        />

        {fullPrompt || baseDetail.messages.length > 0 ? (
          <details className="logs-prompt-box" open={task.status === 'FAILED'}>
            <summary>
              用户需求输入与对话补充（{baseDetail.messages.length} 条关联消息）
            </summary>
            {fullPrompt ? <p>{fullPrompt}</p> : null}
            {baseDetail.messages.filter((m) => m.steer).map((m) => (
              <p key={m.id}>
                <b>[中途补充 · {fmtClockMs(m.createdAt)}]</b> {m.content}
              </p>
            ))}
          </details>
        ) : null}
      </div>

      <nav className="logs-subtabs" role="tablist" aria-label="单任务诊断视图">
        <button type="button" role="tab" aria-selected={subTab === 'events'} className={subTab === 'events' ? 'active' : ''} onClick={() => setSubTab('events')}>
          <span>过程事件流 (task_event)</span>
          <small>{events.length}</small>
        </button>
        <button type="button" role="tab" aria-selected={subTab === 'runner'} className={subTab === 'runner' ? 'active' : ''} onClick={() => setSubTab('runner')}>
          <span>沙箱 Runner 日志 (.log)</span>
          <small>{runnerLog?.exists ? fmtBytes(runnerLog.sizeBytes) : '无文件'}</small>
        </button>
        <button type="button" role="tab" aria-selected={subTab === 'resources'} className={subTab === 'resources' ? 'active' : ''} onClick={() => setSubTab('resources')}>
          <span>模型网关与链路事件</span>
          <small>{baseDetail.modelUsages.length + (baseDetail.platformEvents?.length ?? 0)}</small>
        </button>
        <button type="button" role="tab" aria-selected={subTab === 'app'} className={subTab === 'app' ? 'active' : ''} onClick={() => setSubTab('app')}>
          <span>云库 Schema · 云函数 · 数据源</span>
          <small>{(baseDetail.cloudTables?.length ?? 0) + baseDetail.fnLogs.length + baseDetail.sourceCalls.length}</small>
        </button>
      </nav>

      <div className="logs-detail-body">
        {subTab === 'events' ? (
          <>
            <div className="logs-event-toolbar">
              <div className="logs-chip-row">
                {(
                  [
                    ['all', '全部', events.length],
                    ['tool', '工具调用', eventStats.counts.tool ?? 0],
                    ['log', '命令输出', eventStats.counts.log ?? 0],
                    ['verify', '质检门禁', eventStats.counts.verify ?? 0],
                    ['thinking', '推理思考', eventStats.counts.thinking ?? 0],
                    ['status', '阶段状态', (eventStats.counts.status ?? 0) + (eventStats.counts.finished ?? 0)],
                    ['collab', '确认/补充', (eventStats.counts.confirm ?? 0) + (eventStats.counts.steer ?? 0) + (eventStats.counts.todo ?? 0)],
                  ] as [EventFilter, string, number][]
                ).map(([k, label, cnt]) => (
                  <button
                    key={k}
                    type="button"
                    className={`logs-chip ${evFilter === k ? 'active' : ''}`}
                    onClick={() => setEvFilter(k)}
                  >
                    <span>{label}</span>
                    <b>{cnt}</b>
                  </button>
                ))}
                <button
                  type="button"
                  className={`logs-chip err ${onlyEvErrors ? 'active' : ''}`}
                  onClick={() => setOnlyEvErrors((v) => !v)}
                >
                  <span>仅看报错</span>
                  <b>{eventStats.errors}</b>
                </button>
              </div>

              <div className="logs-filter-right">
                <label className="logs-search-box" style={{ minWidth: 200, maxWidth: 260, height: 30 }}>
                  <Icon name="search" />
                  <input
                    placeholder="过滤事件内容 / 命令 / 文件路径…"
                    value={evQuery}
                    onChange={(e) => setEvQuery(e.target.value)}
                  />
                  {evQuery ? (
                    <button type="button" className="logs-search-clear" onClick={() => setEvQuery('')}>
                      <Icon name="close" />
                    </button>
                  ) : null}
                </label>
                <button type="button" className="logs-chip" onClick={() => setExpandAllEvents((v) => !v)}>
                  {expandAllEvents ? '全部收起' : '全部展开'}
                </button>
                <button
                  type="button"
                  className={`logs-chip ${compactStream ? 'active' : ''}`}
                  onClick={() => setCompactStream((v) => !v)}
                  title="合并成对的 tool start/end 与 thinking 阶段，折叠打字碎片"
                >
                  {compactStream ? `Trace 合并视图 (${filteredEvents.length})` : `原始流 (${filteredEvents.length})`}
                </button>
                {eventStats.errors > 0 ? (
                  <button type="button" className="logs-chip err" onClick={jumpFirstErrorEvent}>
                    定位首个报错
                  </button>
                ) : null}
              </div>
            </div>

            {filteredEvents.length === 0 ? (
              <EmptyState title="当前筛选下无过程事件" desc="尝试切换事件类型或取消「仅看报错」过滤。" />
            ) : (
              <div className="logs-event-list" ref={eventListRef}>
                {filteredEvents.map((ev) => (
                  <EventCard key={`${ev.seq}-${expandAllEvents ? 'o' : 'c'}`} ev={ev} defaultOpen={expandAllEvents || isEventError(ev)} />
                ))}
              </div>
            )}
          </>
        ) : null}

        {subTab === 'runner' ? (
          <>
            <div className="logs-event-toolbar">
              <div className="logs-filter-left">
                <div className="logs-chip-row" role="group" aria-label="日志级别过滤">
                  {(
                    [
                      ['ALL', '全部', runnerLog?.totalLines ?? 0, ''],
                      ['ERROR', 'ERROR', runnerLog?.errorLines ?? 0, 'err'],
                      ['WARN', 'WARN', runnerLog?.warnLines ?? 0, 'warn'],
                      ['INFO', 'INFO', undefined, ''],
                    ] as [LogLevelFilter, string, number | undefined, string][]
                  ).map(([lvl, label, cnt, cls]) => (
                    <button
                      key={lvl}
                      type="button"
                      className={`logs-chip ${cls} ${runnerLevel === lvl ? 'active' : ''}`}
                      onClick={() => setRunnerLevel(lvl)}
                    >
                      <span>{label}</span>
                      {cnt !== undefined ? <b>{fmtNum(cnt)}</b> : null}
                    </button>
                  ))}
                </div>
                <label className="logs-search-box" style={{ minWidth: 200, maxWidth: 300, height: 32 }}>
                  <Icon name="search" />
                  <input
                    placeholder="检索 Runner 日志关键字（自动高亮）…"
                    value={runnerQuery}
                    onChange={(e) => setRunnerQuery(e.target.value)}
                  />
                  {runnerQuery ? (
                    <button type="button" className="logs-search-clear" onClick={() => setRunnerQuery('')}>
                      <Icon name="close" />
                    </button>
                  ) : null}
                </label>
                <select
                  className="logs-select"
                  style={{ height: 32 }}
                  value={runnerTail}
                  onChange={(e) => setRunnerTail(Number(e.target.value))}
                >
                  <option value={200}>尾部 200 行</option>
                  <option value={800}>尾部 800 行</option>
                  <option value={2000}>尾部 2000 行</option>
                  <option value={5000}>尾部 5000 行</option>
                </select>
                {runnerLog?.exists ? (
                  <span style={{ fontSize: 12, color: '#6b647d' }}>
                    匹配 {fmtNum(runnerLog.matchedLines)} / {fmtNum(runnerLog.totalLines)} 行 · {fmtBytes(runnerLog.sizeBytes)}
                    {runnerLog.updatedAt ? ` · 更新于 ${fmtDateTime(runnerLog.updatedAt)}` : ''}
                  </span>
                ) : null}
              </div>

              <div className="logs-filter-right">
                {(runnerLog?.errorLines ?? 0) > 0 ? (
                  <button type="button" className="logs-chip err" onClick={jumpFirstRunnerError}>
                    定位首个 ERROR
                  </button>
                ) : null}
                <button
                  type="button"
                  className={`logs-chip ${runnerWrap ? 'active' : ''}`}
                  onClick={() => setRunnerWrap((v) => !v)}
                >
                  {runnerWrap ? '自动换行' : '单行滚动'}
                </button>
                <button
                  type="button"
                  className={`logs-chip ${autoScrollRunner ? 'active' : ''}`}
                  onClick={() => setAutoScrollRunner((v) => !v)}
                >
                  滚动到底部
                </button>
                {runnerLog?.exists && runnerLog.lines.length > 0 ? (
                  <button
                    type="button"
                    className="dash-action-btn"
                    style={{ height: 32 }}
                    onClick={async () => {
                      const txt = runnerLog.lines.map((l) => `${l.no}: ${l.text}`).join('\n');
                      toast((await copyText(txt)) ? '已复制当前日志内容。' : '复制失败');
                    }}
                  >
                    <Icon name="copy" />
                    <span>复制</span>
                  </button>
                ) : null}
                {runnerLog?.exists ? (
                  <button
                    type="button"
                    className="dash-action-btn primary"
                    style={{ height: 32 }}
                    onClick={() => downloadUrl(api.opsRunnerLogDownloadUrl(task.id))}
                  >
                    <Icon name="download" />
                    <span>下载 .log</span>
                  </button>
                ) : null}
              </div>
            </div>

            {!runnerLog?.exists ? (
              <EmptyState
                icon="terminal"
                title="暂无沙箱 Runner 日志文件"
                desc="该任务可能仍在排队、属于轻量任务（如需求规划/手动微调），或运行期共享日志卷尚未落盘。"
              />
            ) : runnerLog.lines.length === 0 ? (
              <EmptyState title="没有匹配的日志行" desc="请尝试切换日志级别或更换搜索关键字。" />
            ) : (
              <div className={`logs-terminal ${runnerWrap ? 'wrap' : 'nowrap'}`}>
                <div className="logs-terminal-scroll" ref={runnerScrollRef}>
                  {runnerLog.lines.map((line) => (
                    <div key={line.no} className={`logs-term-line ${classifyTermLine(line.text)}`}>
                      <span className="logs-term-no">{line.no}</span>
                      <span className="logs-term-text">
                        <HighlightText text={line.text} query={runnerQuery} />
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        ) : null}

        {subTab === 'resources' ? (
          <>
            {(baseDetail.sandboxes?.length ?? 0) > 0 ? (
              <div className="logs-json-block">
                <h4>关联沙箱生命周期台账（sandbox_instance · {baseDetail.sandboxes!.length} 条）</h4>
                <div className="dash-table-wrap">
                  <table className="dash-table">
                    <thead>
                      <tr>
                        <th scope="col">沙箱 ID</th>
                        <th scope="col">引擎 / 外部标识</th>
                        <th scope="col">状态</th>
                        <th scope="col">冷启动</th>
                        <th scope="col">资源采样 (CPU/内存)</th>
                        <th scope="col">归属节点</th>
                        <th scope="col">销毁原因 / 操作人</th>
                      </tr>
                    </thead>
                    <tbody>
                      {baseDetail.sandboxes!.map((sb) => (
                        <tr key={sb.sandboxId}>
                          <td className="logs-mono"><b>{sb.sandboxId}</b></td>
                          <td>
                            <span className="dash-tag">{sb.provider}</span>{' '}
                            <span className="logs-mono">{sb.externalRef || '—'}</span>
                          </td>
                          <td><span className={`logs-status ${sb.state === 'failed' ? 'err' : sb.state === 'active' ? 'run' : 'ok'}`}>{sb.state}</span></td>
                          <td>{sb.startupMs != null ? `${sb.startupMs} ms` : '—'}</td>
                          <td>
                            {sb.memoryUsedBytes != null ? fmtBytes(sb.memoryUsedBytes) : '—'}
                            {sb.cpuPercent != null ? ` · ${sb.cpuPercent}%` : ''}
                          </td>
                          <td className="logs-mono">{sb.ownerInstance}</td>
                          <td>{sb.destroyReason || '—'}{sb.destroyedBy ? ` (${sb.destroyedBy})` : ''}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : null}
            <div className="logs-json-block">
              <h4>模型调用记账明细（model_usage · 共 {baseDetail.modelUsages.length} 次）</h4>
              {baseDetail.modelUsages.length === 0 ? (
                <p className="dash-empty">暂无模型调用明细记录（可能未经过模型网关或尚未产生 usage）。</p>
              ) : (
                <div className="dash-table-wrap">
                  <table className="dash-table">
                    <thead>
                      <tr>
                        <th scope="col">时间</th>
                        <th scope="col">供应商</th>
                        <th scope="col">模型</th>
                        <th scope="col">输入 Token</th>
                        <th scope="col">输出 Token</th>
                        <th scope="col">估算成本</th>
                      </tr>
                    </thead>
                    <tbody>
                      {baseDetail.modelUsages.map((u) => (
                        <tr key={u.id}>
                          <td className="dash-nowrap">{fmtDateTime(u.createdAt)}</td>
                          <td><span className="dash-tag">{u.provider}</span></td>
                          <td className="logs-mono">{u.model || '—'}</td>
                          <td>{fmtNum(u.inputTokens)}</td>
                          <td>{fmtNum(u.outputTokens)}</td>
                          <td>¥{Number(u.cost).toFixed(5)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            {(baseDetail.platformEvents?.length ?? 0) > 0 ? (
              <div className="logs-json-block">
                <h4>关联平台链路事件（模型网关 HTTP / Schema DDL / 沙箱 / 异常 · {baseDetail.platformEvents!.length} 条）</h4>
                <div className="dash-table-wrap">
                  <table className="dash-table">
                    <thead>
                      <tr>
                        <th scope="col">时间</th>
                        <th scope="col">类别 / 动作</th>
                        <th scope="col">状态</th>
                        <th scope="col">耗时</th>
                        <th scope="col">摘要与详情</th>
                      </tr>
                    </thead>
                    <tbody>
                      {baseDetail.platformEvents!.map((pe) => (
                        <tr key={pe.id}>
                          <td className="dash-nowrap">{fmtDateTime(pe.createdAt)}</td>
                          <td>
                            <span className="dash-tag">{pe.category}</span>{' '}
                            <span className="logs-mono">{pe.action}</span>
                          </td>
                          <td>
                            <span className={`logs-status ${pe.ok ? 'ok' : 'err'}`}>
                              {pe.statusCode ?? (pe.ok ? 'OK' : pe.level)}
                            </span>
                          </td>
                          <td>{pe.durationMs != null ? `${pe.durationMs} ms` : '—'}</td>
                          <td style={{ maxWidth: 460 }}>
                            <div>{pe.summary}</div>
                            {pe.detail ? (
                              <pre className="logs-term-inline" style={{ margin: '6px 0 0', maxHeight: 160 }}>{pe.detail}</pre>
                            ) : null}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : null}

            {baseDetail.checkpoint ? (
              <div className="logs-json-block">
                <h4>断点检查点（checkpoint）</h4>
                <pre className="logs-pre">{formatJson(baseDetail.checkpoint)}</pre>
              </div>
            ) : null}

            <div className="logs-json-block">
              <h4>任务输入参数（task.input）</h4>
              <pre className="logs-pre">{formatJson(task.input)}</pre>
            </div>

            {task.result ? (
              <div className="logs-json-block">
                <h4>任务输出元数据（task.result）</h4>
                <pre className="logs-pre">{formatJson(task.result)}</pre>
              </div>
            ) : null}

            {task.todos && task.todos.length > 0 ? (
              <div className="logs-json-block">
                <h4>Agent 待办规划（task.todos）</h4>
                <pre className="logs-pre">{formatJson(task.todos)}</pre>
              </div>
            ) : null}
          </>
        ) : null}

        {subTab === 'app' ? (
          <>
            <div className="logs-json-block">
              <h4>
                云数据库 Schema 与表结构
                {baseDetail.cloudStatus
                  ? `（${baseDetail.cloudStatus.schemaName} · ${baseDetail.cloudStatus.status === 'on' ? '已启用' : '已关闭'}）`
                  : '（未启用云服务）'}
              </h4>
              {!baseDetail.cloudStatus ? (
                <p className="dash-empty">该应用尚未创建云数据库 Schema</p>
              ) : (baseDetail.cloudTables?.length ?? 0) === 0 ? (
                <p className="dash-empty">Schema 已创建，当前暂无注册表</p>
              ) : (
                <div className="dash-table-wrap">
                  <table className="dash-table">
                    <thead>
                      <tr>
                        <th scope="col">表名</th>
                        <th scope="col">字段定义</th>
                        <th scope="col">权限策略</th>
                      </tr>
                    </thead>
                    <tbody>
                      {baseDetail.cloudTables!.map((tbl) => (
                        <tr key={tbl.name}>
                          <td className="logs-mono"><b>{tbl.name}</b></td>
                          <td className="logs-mono" style={{ fontSize: 11 }}>{formatJson(tbl.columns)}</td>
                          <td className="logs-mono" style={{ fontSize: 11 }}>{tbl.policy ? String((tbl.policy as { preset?: string }).preset ?? 'custom') : '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="logs-json-block">
              <h4>该应用近期云函数执行记录（最新 {baseDetail.fnLogs.length} 条）</h4>
              {baseDetail.fnLogs.length === 0 ? (
                <p className="dash-empty">该应用暂无云函数执行记录</p>
              ) : (
                <div className="dash-table-wrap">
                  <table className="dash-table">
                    <thead>
                      <tr>
                        <th scope="col">时间</th>
                        <th scope="col">函数名</th>
                        <th scope="col">状态</th>
                        <th scope="col">耗时</th>
                        <th scope="col">报错 / 输出</th>
                      </tr>
                    </thead>
                    <tbody>
                      {baseDetail.fnLogs.map((f) => (
                        <tr key={f.id}>
                          <td className="dash-nowrap">{fmtDateTime(f.createdAt)}</td>
                          <td className="logs-mono">{f.name}</td>
                          <td><span className={`logs-status ${f.ok ? 'ok' : 'err'}`}>{f.ok ? '成功' : '报错'}</span></td>
                          <td>{f.durationMs} ms</td>
                          <td>
                            <pre className="logs-pre" style={{ maxHeight: 120 }}>{[f.error, f.logs].filter(Boolean).join('\n') || '—'}</pre>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            <div className="logs-json-block">
              <h4>该应用近期数据源代理调用（最新 {baseDetail.sourceCalls.length} 条）</h4>
              {baseDetail.sourceCalls.length === 0 ? (
                <p className="dash-empty">该应用暂无数据源调用记录</p>
              ) : (
                <div className="dash-table-wrap">
                  <table className="dash-table">
                    <thead>
                      <tr>
                        <th scope="col">时间</th>
                        <th scope="col">数据源</th>
                        <th scope="col">来源</th>
                        <th scope="col">状态</th>
                        <th scope="col">耗时</th>
                        <th scope="col">错误信息</th>
                      </tr>
                    </thead>
                    <tbody>
                      {baseDetail.sourceCalls.map((s) => (
                        <tr key={s.id}>
                          <td className="dash-nowrap">{fmtDateTime(s.createdAt)}</td>
                          <td><b>{s.sourceName}</b> <span className="logs-mono">{s.sourceKey}</span></td>
                          <td><span className="dash-tag">{s.origin}</span></td>
                          <td><span className={`logs-status ${s.ok ? 'ok' : 'err'}`}>{s.status ?? (s.ok ? 'OK' : 'ERR')}</span></td>
                          <td>{s.durationMs} ms</td>
                          <td className={s.error ? 'dash-err' : ''}>{s.error || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </>
        ) : null}
      </div>
    </>
  );
}

type DisplayEventRow = OpsTaskEventRow & {
  _spanLabel?: string;
  _spanDurationMs?: number;
};

function compactTraceEvents(matched: OpsTaskEventRow[]): DisplayEventRow[] {
  const merged: DisplayEventRow[] = [];
  const startsByCallId = new Map<string, number>();
  const startsByKey = new Map<string, number[]>();
  let textAcc: string[] = [];
  let firstTextEv: OpsTaskEventRow | null = null;
  let lastThinkingIdx = -1;

  const flushTextAcc = () => {
    if (!firstTextEv) return;
    merged.push({
      ...firstTextEv,
      payload: { ...firstTextEv.payload, delta: textAcc.join('') },
    });
    textAcc = [];
    firstTextEv = null;
  };

  for (const ev of matched) {
    if (ev.type === 'agent_text') {
      lastThinkingIdx = -1;
      if (!firstTextEv) firstTextEv = ev;
      textAcc.push(String(ev.payload?.delta ?? ''));
      continue;
    }
    flushTextAcc();

    const p = ev.payload ?? {};
    if (ev.type === 'thinking') {
      merged.push({ ...ev, payload: { ...p } });
      lastThinkingIdx = merged.length - 1;
      continue;
    }

    if (ev.type === 'thinking_end' && lastThinkingIdx >= 0) {
      const target = merged[lastThinkingIdx];
      const dur = typeof p.durationMs === 'number' ? p.durationMs : target.payload?.durationMs;
      merged[lastThinkingIdx] = {
        ...target,
        _spanLabel: `#${target.seq}–${ev.seq}`,
        payload: { ...target.payload, ...(dur !== undefined ? { durationMs: dur } : {}) },
      };
      lastThinkingIdx = -1;
      continue;
    }
    lastThinkingIdx = -1;

    if (ev.type === 'tool' && p.state === 'start') {
      const idx = merged.length;
      merged.push({ ...ev, payload: { ...p } });
      const callId = typeof p.toolCallId === 'string' && p.toolCallId ? p.toolCallId : '';
      if (callId) startsByCallId.set(callId, idx);
      const nameKey = `${String(p.name ?? '')}|${String(p.summary ?? '')}`;
      const listExact = startsByKey.get(nameKey) ?? [];
      listExact.push(idx);
      startsByKey.set(nameKey, listExact);
      const nameOnlyKey = String(p.name ?? '');
      const listName = startsByKey.get(nameOnlyKey) ?? [];
      listName.push(idx);
      startsByKey.set(nameOnlyKey, listName);
      continue;
    }

    if (ev.type === 'tool' && p.state === 'end') {
      const callId = typeof p.toolCallId === 'string' && p.toolCallId ? p.toolCallId : '';
      let startIdx: number | undefined;
      if (callId && startsByCallId.has(callId)) {
        startIdx = startsByCallId.get(callId);
        startsByCallId.delete(callId);
      }
      const nameKey = `${String(p.name ?? '')}|${String(p.summary ?? '')}`;
      const exactQueue = startsByKey.get(nameKey);
      if (startIdx === undefined && exactQueue?.length) {
        startIdx = exactQueue.shift();
      }
      const nameQueue = startsByKey.get(String(p.name ?? ''));
      if (startIdx === undefined && nameQueue?.length) {
        startIdx = nameQueue.shift();
      }
      if (startIdx !== undefined && merged[startIdx]) {
        const startEv = merged[startIdx];
        // 从另一个索引队列里同步移除已配对项，避免重复匹配
        if (exactQueue?.length) {
          const i = exactQueue.indexOf(startIdx);
          if (i >= 0) exactQueue.splice(i, 1);
        }
        if (nameQueue?.length) {
          const i = nameQueue.indexOf(startIdx);
          if (i >= 0) nameQueue.splice(i, 1);
        }
        const durMs =
          typeof p.durationMs === 'number'
            ? p.durationMs
            : Math.max(0, new Date(ev.createdAt).getTime() - new Date(startEv.createdAt).getTime());
        merged[startIdx] = {
          ...ev,
          _spanLabel: `#${startEv.seq}–${ev.seq}`,
          _spanDurationMs: durMs,
          payload: {
            ...startEv.payload,
            ...p,
            state: 'span',
            startDetail: startEv.payload?.detail,
          },
        };
        continue;
      }
    }

    merged.push({ ...ev });
  }
  flushTextAcc();
  return merged;
}

function EventCard({ ev, defaultOpen }: { ev: DisplayEventRow; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  const p = ev.payload ?? {};
  const err = isEventError(ev);

  const summary = useMemo(() => {
    switch (ev.type) {
      case 'status':
        return `${String(p.status ?? '')} · ${String(p.phaseLabel ?? '')} (${String(p.progress ?? 0)}%)`;
      case 'tool':
        return p.state === 'span'
          ? `${String(p.name ?? '')} · ${String(p.summary ?? '')}`
          : `${String(p.name ?? '')} [${String(p.state ?? '')}] · ${String(p.summary ?? '')}`;
      case 'log': {
        const line = String(p.line ?? '');
        const first = line.split('\n')[0];
        return first.length > 100 ? first.slice(0, 100) + '…' : first;
      }
      case 'verify': {
        const checks = Array.isArray(p.checks) ? p.checks : [];
        const bad = checks.filter((c: Record<string, unknown>) => !c?.ok && !c?.skipped).length;
        return `门禁检查 ${checks.length} 项${bad > 0 ? `（${bad} 项未通过）` : '（全部通过）'}`;
      }
      case 'thinking':
        return `推理思考（${String(p.text ?? '').length} 字${typeof p.durationMs === 'number' ? ` · ${(p.durationMs / 1000).toFixed(1)}s` : ''}）`;
      case 'thinking_end':
        return `思考阶段结束（${typeof p.durationMs === 'number' ? `${(p.durationMs / 1000).toFixed(1)}s` : ''}）`;
      case 'agent_text':
        return String(p.delta ?? '').replace(/\s+/g, ' ').slice(0, 110);
      case 'steer':
        return String(p.text ?? '');
      case 'confirm':
        return `${String(p.kind ?? '')} · ${String(p.state ?? '')} · ${String(p.title ?? p.reason ?? '')}`;
      case 'todo': {
        const items = Array.isArray(p.items) ? p.items : [];
        return `更新待办清单（${items.length} 项）`;
      }
      case 'finished': {
        const errObj = p.error as { code?: string; message?: string } | undefined;
        return errObj ? `结束：${String(p.status ?? '')} · ${errObj.code ?? ''} ${errObj.message ?? ''}` : `结束：${String(p.status ?? '')}`;
      }
      default:
        return formatJson(p).slice(0, 120);
    }
  }, [ev.type, p]);

  const typeCls = err ? 'err' : ev.type;

  // 命令输出 (log) 直接以内嵌终端块展示，排查构建/测试输出最直观
  if (ev.type === 'log') {
    return (
      <div className={`logs-ev-card is-log ${err ? 'is-err' : ''}`}>
        <div className="logs-ev-head">
          <div className="logs-ev-left">
            <span className="logs-ev-seq">#{ev.seq}</span>
            <span className={`logs-ev-type ${typeCls}`}>命令输出</span>
            <span className="logs-ev-summary logs-mono">{summary}</span>
          </div>
          <div className="logs-ev-right">
            <span>{fmtClockMs(ev.createdAt)}</span>
          </div>
        </div>
        <pre className="logs-term-inline">{String(p.line ?? '')}</pre>
      </div>
    );
  }

  return (
    <div className={`logs-ev-card ${err ? 'is-err' : ''}`}>
      <div className="logs-ev-head">
        <div className="logs-ev-left">
          <span className="logs-ev-seq">{ev._spanLabel ?? `#${ev.seq}`}</span>
          <span className={`logs-ev-type ${typeCls}`}>{EVENT_TYPE_LABELS[ev.type] ?? ev.type}</span>
          {ev.type === 'tool' && typeof p.ok === 'boolean' ? (
            <span className={`logs-mini-badge ${p.ok ? '' : 'err'}`}>{p.ok ? 'OK' : '失败'}</span>
          ) : ev.type === 'tool' && p.state === 'start' ? (
            <span className="logs-mini-badge">进行中</span>
          ) : null}
          {ev._spanDurationMs !== undefined ? (
            <span className="logs-mini-badge">{fmtDurationMs(ev._spanDurationMs)}</span>
          ) : null}
          <span className="logs-ev-summary">{summary}</span>
        </div>
        <div className="logs-ev-right">
          <span>{fmtClockMs(ev.createdAt)}</span>
          <button type="button" className="logs-ev-toggle" onClick={() => setOpen((v) => !v)}>
            {open ? '收起' : '详情'}
          </button>
        </div>
      </div>

      {open ? (
        <div className="logs-ev-body">
          {ev.type === 'verify' && Array.isArray(p.checks) ? (
            <div className="logs-verify-list">
              {(p.checks as { name?: string; ok?: boolean; skipped?: boolean; detail?: string }[]).map((c, idx) => (
                <div key={idx} className={`logs-verify-item ${!c.ok && !c.skipped ? 'bad' : ''}`}>
                  <div className="logs-verify-head">
                    <span>{c.skipped ? '–' : c.ok ? '✓' : '×'}</span>
                    <span>{c.name}</span>
                    <span className="logs-mini-badge">{c.skipped ? '已跳过' : c.ok ? '通过' : '未通过'}</span>
                  </div>
                  {c.detail ? <pre>{c.detail}</pre> : null}
                </div>
              ))}
            </div>
          ) : ev.type === 'tool' && p.detail !== undefined ? (
            <pre className="logs-pre">
              {typeof p.detail === 'string' ? p.detail : formatJson(p.detail)}
              {p.detailTruncated ? '\n\n[注：原始输出超过 16KB 已在服务端截断]' : ''}
            </pre>
          ) : ev.type === 'thinking' ? (
            <pre className="logs-pre">{String(p.text ?? '')}</pre>
          ) : ev.type === 'agent_text' ? (
            <pre className="logs-pre">{String(p.delta ?? '')}</pre>
          ) : (
            <pre className="logs-pre">{formatJson(p)}</pre>
          )}
        </div>
      ) : null}
    </div>
  );
}

// ==========================================================================
// 3. 人员 / 工作空间选择器（复用看板的检索体验）
// ==========================================================================

function OpsActorPicker({
  actors,
  filterWorkspaceId,
  filterUserId,
  activeLabel,
  onSelect,
}: {
  actors: OpsLogActor[];
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
        <span className="dash-actor-text">{activeLabel}</span>
        <Icon name="down" />
      </button>
      {open ? (
        <div className="dash-actor-pop" role="dialog" aria-label="按人员或工作空间筛选">
          <label className="dash-actor-search">
            <Icon name="search" />
            <input autoFocus placeholder="搜索姓名、工号或 ws_xxx" value={q} onChange={(e) => setQ(e.target.value)} />
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
              <span>全部人员与工作空间</span>
            </button>
            {users.length > 0 ? <div className="dash-actor-group">实名员工（SSO）</div> : null}
            {users.map((a) => (
              <button
                key={a.value}
                type="button"
                role="option"
                aria-selected={filterUserId === a.userId && !filterWorkspaceId}
                className={filterUserId === a.userId && !filterWorkspaceId ? 'active' : ''}
                onClick={() => {
                  onSelect({ userId: a.userId, workspaceId: null });
                  setOpen(false);
                }}
              >
                <span>{a.realName} ({a.loginName})</span>
                <small>{a.tasks}任务 · {a.failed}失败</small>
              </button>
            ))}
            {spaces.length > 0 ? <div className="dash-actor-group">工作空间</div> : null}
            {spaces.map((a) => (
              <button
                key={a.value}
                type="button"
                role="option"
                aria-selected={filterWorkspaceId === a.workspaceId}
                className={filterWorkspaceId === a.workspaceId ? 'active' : ''}
                onClick={() => {
                  onSelect({ workspaceId: a.workspaceId });
                  setOpen(false);
                }}
              >
                <span>{a.realName ? `${a.realName} · ${a.workspaceId}` : a.workspaceId}</span>
                <small>{a.tasks}任务 · {a.failed}失败</small>
              </button>
            ))}
            {filtered.length === 0 ? <p className="dash-actor-empty">未匹配到人员或空间</p> : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}

// ==========================================================================
// 4. 云函数执行日志视图
// ==========================================================================

function FunctionLogsView() {
  const [range, setRange] = useState<OpsLogRange>('7d');
  const [onlyErrors, setOnlyErrors] = useState<boolean>(false);
  const [q, setQ] = useState<string>('');
  const [offset, setOffset] = useState<number>(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const limit = 40;
  const debouncedQ = useDebounced(q.trim(), 260);

  const fnQ = useQuery({
    queryKey: ['ops-fn-logs', range, onlyErrors, debouncedQ, offset],
    queryFn: () =>
      api.opsFunctionLogs({
        range,
        ok: onlyErrors ? false : undefined,
        q: debouncedQ || undefined,
        limit,
        offset,
      }),
    placeholderData: keepPreviousData,
  });

  const data = fnQ.data;
  const items = data?.items ?? [];

  return (
    <>
      <div className="logs-filter-bar">
        <div className="logs-filter-row">
          <div className="logs-filter-left">
            <label className="logs-search-box">
              <Icon name="search" />
              <input
                placeholder="搜应用名 / 函数名 / 报错堆栈 / console 输出…"
                value={q}
                onChange={(e) => {
                  setQ(e.target.value);
                  setOffset(0);
                }}
              />
              {q ? (
                <button
                  type="button"
                  className="logs-search-clear"
                  onClick={() => {
                    setQ('');
                    setOffset(0);
                  }}
                >
                  <Icon name="close" />
                </button>
              ) : null}
            </label>
            <select
              className="logs-select"
              value={range}
              onChange={(e) => {
                setRange(e.target.value as OpsLogRange);
                setOffset(0);
              }}
            >
              {RANGES.map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
            <button
              type="button"
              className={`logs-chip err ${onlyErrors ? 'active' : ''}`}
              onClick={() => {
                setOnlyErrors((v) => !v);
                setOffset(0);
              }}
            >
              <span>仅看报错</span>
              <b>{fmtNum(data?.errorCount ?? 0)}</b>
            </button>
          </div>
          <div className="logs-filter-right">
            <span style={{ fontSize: 12, color: '#6b647d' }}>共 {fmtNum(data?.total ?? 0)} 条</span>
            <button type="button" className="dash-action-btn" onClick={() => void fnQ.refetch()} disabled={fnQ.isFetching}>
              <Icon name="refresh" className={fnQ.isFetching ? 'spinning' : ''} />
              <span>刷新</span>
            </button>
          </div>
        </div>
      </div>

      <div className="dash-panel">
        {fnQ.isLoading ? (
          <LoadingState label="正在读取云函数日志…" />
        ) : fnQ.error ? (
          <ErrorState message={errorMessage(fnQ.error)} onRetry={() => void fnQ.refetch()} />
        ) : items.length === 0 ? (
          <EmptyState title="暂无匹配的云函数日志" desc="尝试扩大时间范围或调整搜索关键词。" />
        ) : (
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">时间</th>
                  <th scope="col">应用 / 作者</th>
                  <th scope="col">函数名</th>
                  <th scope="col">调用者</th>
                  <th scope="col">状态</th>
                  <th scope="col">耗时</th>
                  <th scope="col">详情</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <Fragment key={item.id}>
                    <tr>
                      <td className="dash-nowrap">{fmtDateTime(item.createdAt)}</td>
                      <td>
                        <b>{item.appName}</b>
                        <div style={{ fontSize: 11, color: '#7c7491' }}>{item.ownerName || item.workspaceId || item.appId}</div>
                      </td>
                      <td className="logs-mono">{item.name}</td>
                      <td className="logs-mono">{item.caller || '—'}</td>
                      <td><span className={`logs-status ${item.ok ? 'ok' : 'err'}`}>{item.ok ? '成功' : '报错'}</span></td>
                      <td>{item.durationMs} ms</td>
                      <td>
                        {item.error || item.logs ? (
                          <button type="button" className="dash-link" onClick={() => setOpenId(openId === item.id ? null : item.id)}>
                            {openId === item.id ? '收起' : '查看堆栈/输出'}
                          </button>
                        ) : (
                          <span className="dash-muted">无</span>
                        )}
                      </td>
                    </tr>
                    {openId === item.id ? (
                      <tr>
                        <td colSpan={7}>
                          <pre className="logs-term-inline" style={{ margin: 0 }}>
                            {[item.error, item.logs].filter(Boolean).join('\n\n')}
                          </pre>
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <OpsPager offset={offset} limit={limit} total={data?.total ?? 0} onChange={setOffset} />
      </div>
    </>
  );
}

// ==========================================================================
// 5. 数据源调用日志视图
// ==========================================================================

function SourceCallsView() {
  const [range, setRange] = useState<OpsLogRange>('7d');
  const [onlyErrors, setOnlyErrors] = useState<boolean>(false);
  const [origin, setOrigin] = useState<string>('ALL');
  const [q, setQ] = useState<string>('');
  const [offset, setOffset] = useState<number>(0);
  const limit = 40;
  const debouncedQ = useDebounced(q.trim(), 260);

  const srcQ = useQuery({
    queryKey: ['ops-source-calls', range, onlyErrors, origin, debouncedQ, offset],
    queryFn: () =>
      api.opsSourceCalls({
        range,
        ok: onlyErrors ? false : undefined,
        origin: origin === 'ALL' ? undefined : origin,
        q: debouncedQ || undefined,
        limit,
        offset,
      }),
    placeholderData: keepPreviousData,
  });

  const data = srcQ.data;
  const items = data?.items ?? [];

  return (
    <>
      <div className="logs-filter-bar">
        <div className="logs-filter-row">
          <div className="logs-filter-left">
            <label className="logs-search-box">
              <Icon name="search" />
              <input
                placeholder="搜数据源 key / 名称 / 应用名 / 报错原因…"
                value={q}
                onChange={(e) => {
                  setQ(e.target.value);
                  setOffset(0);
                }}
              />
              {q ? (
                <button
                  type="button"
                  className="logs-search-clear"
                  onClick={() => {
                    setQ('');
                    setOffset(0);
                  }}
                >
                  <Icon name="close" />
                </button>
              ) : null}
            </label>
            <select
              className="logs-select"
              value={origin}
              onChange={(e) => {
                setOrigin(e.target.value);
                setOffset(0);
              }}
            >
              <option value="ALL">全部来源</option>
              <option value="preview">预览环境 (preview)</option>
              <option value="site">线上站点 (site)</option>
              <option value="test">连通性测试 (test)</option>
            </select>
            <select
              className="logs-select"
              value={range}
              onChange={(e) => {
                setRange(e.target.value as OpsLogRange);
                setOffset(0);
              }}
            >
              {RANGES.map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
            <button
              type="button"
              className={`logs-chip err ${onlyErrors ? 'active' : ''}`}
              onClick={() => {
                setOnlyErrors((v) => !v);
                setOffset(0);
              }}
            >
              <span>仅看失败</span>
              <b>{fmtNum(data?.errorCount ?? 0)}</b>
            </button>
          </div>
          <div className="logs-filter-right">
            <span style={{ fontSize: 12, color: '#6b647d' }}>共 {fmtNum(data?.total ?? 0)} 条</span>
            <button type="button" className="dash-action-btn" onClick={() => void srcQ.refetch()} disabled={srcQ.isFetching}>
              <Icon name="refresh" className={srcQ.isFetching ? 'spinning' : ''} />
              <span>刷新</span>
            </button>
          </div>
        </div>
      </div>

      <div className="dash-panel">
        {srcQ.isLoading ? (
          <LoadingState label="正在读取数据源调用记录…" />
        ) : srcQ.error ? (
          <ErrorState message={errorMessage(srcQ.error)} onRetry={() => void srcQ.refetch()} />
        ) : items.length === 0 ? (
          <EmptyState title="暂无数据源调用记录" desc="尝试调整来源或时间筛选。" />
        ) : (
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">时间</th>
                  <th scope="col">数据源</th>
                  <th scope="col">应用</th>
                  <th scope="col">来源</th>
                  <th scope="col">HTTP 状态</th>
                  <th scope="col">耗时</th>
                  <th scope="col">错误信息</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <tr key={item.id}>
                    <td className="dash-nowrap">{fmtDateTime(item.createdAt)}</td>
                    <td>
                      <b>{item.sourceName}</b>
                      <div className="logs-mono" style={{ fontSize: 11, color: '#7c7491' }}>
                        {item.method} · {item.sourceKey}
                      </div>
                    </td>
                    <td>{item.appName || item.appId || '—'}</td>
                    <td><span className="dash-tag">{item.origin}</span></td>
                    <td>
                      <span className={`logs-status ${item.ok ? 'ok' : 'err'}`}>
                        {item.status ?? (item.ok ? 'OK' : 'FAIL')}
                      </span>
                    </td>
                    <td>{item.durationMs} ms</td>
                    <td className={item.error ? 'dash-err' : ''} style={{ maxWidth: 360, wordBreak: 'break-word' }}>
                      {item.error || '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <OpsPager offset={offset} limit={limit} total={data?.total ?? 0} onChange={setOffset} />
      </div>
    </>
  );
}

// ==========================================================================
// 6. 服务端运行日志与 Prometheus 指标状态
// ==========================================================================

function RuntimeLogsView() {
  const [target, setTarget] = useState<'api' | 'site' | 'web-build'>('api');
  const [tail, setTail] = useState<number>(400);
  const [level, setLevel] = useState<LogLevelFilter>('ALL');
  const [wrapLines, setWrapLines] = useState<boolean>(true);
  const [q, setQ] = useState<string>('');
  const debouncedQ = useDebounced(q.trim(), 260);
  const termRef = useRef<HTMLDivElement>(null);

  const rtQ = useQuery({
    queryKey: ['ops-runtime-logs', target, tail, level, debouncedQ],
    queryFn: () =>
      api.opsRuntime({
        target,
        tail,
        q: debouncedQ || undefined,
        level: level === 'ALL' ? undefined : level,
      }),
    placeholderData: keepPreviousData,
  });

  const data = rtQ.data;
  const promUrl = typeof window !== 'undefined' ? window.location.origin + (data?.prometheusEndpoint ?? '/api/meta/metrics/prometheus') : '/api/meta/metrics/prometheus';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className="dash-panel">
        <header className="dash-panel-head compact">
          <div>
            <h3>集群运行期指标、节点心跳与 Prometheus 接入</h3>
            <p>
              沙箱引擎：<b className="logs-mono">{data?.sandboxProvider ?? '—'}</b> · 在线实例：
              <b className="logs-mono">
                {data?.clusterNodes?.length
                  ? data.clusterNodes
                      .map((n) => `${n.instanceId}(${n.gracefulStop ? 'stopped' : n.status})`)
                      .join(', ')
                  : (data?.metrics?.instances ?? []).join(', ') || '—'}
              </b>
            </p>
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <button
              type="button"
              className="dash-action-btn"
              onClick={async () => toast((await copyText(promUrl)) ? '已复制 Prometheus 抓取地址。' : '复制失败')}
            >
              <Icon name="copy" />
              <span>复制 Prometheus 地址</span>
            </button>
            <a className="dash-action-btn primary" href="/api/meta/metrics/prometheus" target="_blank" rel="noreferrer">
              <Icon name="external" />
              <span>查看原始指标</span>
            </a>
          </div>
        </header>

        <div className="dash-mini-stats">
          <div>
            <span>累计任务（近 7 天汇总）</span>
            <b>{fmtNum(data?.metrics?.tasks?.total ?? 0)}</b>
            <small>成 {data?.metrics?.tasks?.succeeded ?? 0} / 败 {data?.metrics?.tasks?.failed ?? 0}</small>
          </div>
          <div>
            <span>沙箱冷启动</span>
            <b>{fmtNum(data?.metrics?.sandbox?.count ?? 0)} 次</b>
            <small>均 {data?.metrics?.sandbox?.avgMs ?? 0}ms / P95 {data?.metrics?.sandbox?.p95Ms ?? 0}ms</small>
          </div>
          <div>
            <span>上游 LLM 调用</span>
            <b>{fmtNum(data?.metrics?.llm?.count ?? 0)} 次</b>
            <small>均 {data?.metrics?.llm?.avgMs ?? 0}ms / P95 {data?.metrics?.llm?.p95Ms ?? 0}ms</small>
          </div>
          <div>
            <span>额度错误率</span>
            <b>{(((data?.metrics?.llm?.quotaErrorRate ?? 0) * 100)).toFixed(2)}%</b>
            <small>额度错 {data?.metrics?.llm?.quotaErrors ?? 0} 次 · 重试 {data?.metrics?.llm?.retries ?? 0}</small>
          </div>
          <div>
            <span>活跃告警</span>
            <b>{fmtNum(data?.metrics?.alerts?.length ?? 0)}</b>
            <small>{data?.metrics?.alerts?.map((a) => a.code).join(' · ') || '运行正常'}</small>
          </div>
          <div>
            <span>任务日志目录</span>
            <b className="logs-mono" style={{ fontSize: 12 }}>logs/tasks/*.log</b>
            <small title={data?.runtimeDir}>{data?.runtimeDir ?? '—'}</small>
          </div>
        </div>
      </div>

      <div className="logs-info-banner">
        说明：Prometheus 负责抓取时序指标（如任务失败率、沙箱耗时、LLM 延迟）；Java 后端进程日志在本地启动模式下写入{' '}
        <code className="logs-mono">runtime/logs/{'{api,site}'}.log</code>，在 Kubernetes 部署下输出到容器标准输出（stdout）由集群日志系统采集，而每个用户任务的开发过程日志始终沉淀在数据库{' '}
        <code className="logs-mono">task_event</code> 与共享卷 <code className="logs-mono">logs/tasks/{'{taskId}'}.log</code> 中。
      </div>

      <div className="logs-filter-bar">
        <div className="logs-filter-row">
          <div className="logs-filter-left">
            <div className="logs-chip-row">
              {(
                [
                  ['api', 'api.log (主服务)'],
                  ['site', 'site.log (站点网关)'],
                  ['web-build', 'web-build.log (构建)'],
                ] as ['api' | 'site' | 'web-build', string][]
              ).map(([k, label]) => (
                <button key={k} type="button" className={`logs-chip ${target === k ? 'active' : ''}`} onClick={() => setTarget(k)}>
                  {label}
                </button>
              ))}
            </div>
            <div className="logs-chip-row" role="group" aria-label="日志级别过滤">
              {(
                [
                  ['ALL', '全部', data?.file.totalLines ?? 0, ''],
                  ['ERROR', 'ERROR', data?.file.errorLines ?? 0, 'err'],
                  ['WARN', 'WARN', data?.file.warnLines ?? 0, 'warn'],
                  ['INFO', 'INFO', undefined, ''],
                ] as [LogLevelFilter, string, number | undefined, string][]
              ).map(([lvl, label, cnt, cls]) => (
                <button
                  key={lvl}
                  type="button"
                  className={`logs-chip ${cls} ${level === lvl ? 'active' : ''}`}
                  onClick={() => setLevel(lvl)}
                >
                  <span>{label}</span>
                  {cnt !== undefined ? <b>{fmtNum(cnt)}</b> : null}
                </button>
              ))}
            </div>
            <label className="logs-search-box" style={{ minWidth: 200, maxWidth: 300 }}>
              <Icon name="search" />
              <input placeholder="筛选服务日志关键字（自动高亮）…" value={q} onChange={(e) => setQ(e.target.value)} />
              {q ? (
                <button type="button" className="logs-search-clear" onClick={() => setQ('')}>
                  <Icon name="close" />
                </button>
              ) : null}
            </label>
            <select className="logs-select" value={tail} onChange={(e) => setTail(Number(e.target.value))}>
              <option value={200}>尾部 200 行</option>
              <option value={400}>尾部 400 行</option>
              <option value={1000}>尾部 1000 行</option>
              <option value={3000}>尾部 3000 行</option>
            </select>
          </div>
          <div className="logs-filter-right">
            {(data?.file.errorLines ?? 0) > 0 ? (
              <button
                type="button"
                className="logs-chip err"
                onClick={() => {
                  const el = termRef.current?.querySelector('.logs-term-line.is-err');
                  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                }}
              >
                定位首个 ERROR
              </button>
            ) : null}
            <button type="button" className={`logs-chip ${wrapLines ? 'active' : ''}`} onClick={() => setWrapLines((v) => !v)}>
              {wrapLines ? '自动换行' : '单行滚动'}
            </button>
            <button type="button" className="dash-action-btn" onClick={() => void rtQ.refetch()} disabled={rtQ.isFetching}>
              <Icon name="refresh" className={rtQ.isFetching ? 'spinning' : ''} />
              <span>刷新</span>
            </button>
          </div>
        </div>
      </div>

      <div className="dash-panel">
        {rtQ.isLoading ? (
          <LoadingState label="正在读取服务日志…" />
        ) : rtQ.error ? (
          <ErrorState message={errorMessage(rtQ.error)} onRetry={() => void rtQ.refetch()} />
        ) : !data?.file.exists ? (
          <EmptyState
            icon="terminal"
            title={`当前运行目录没有 ${target}.log 文件`}
            desc="若服务运行在 Kubernetes / 容器中，后端进程日志直接输出到容器 stdout（由集群采集），请使用第一个页签排查单个任务的过程与沙箱日志。"
          />
        ) : data.file.lines.length === 0 ? (
          <EmptyState title="没有匹配的日志行" desc="请尝试切换日志级别或更换过滤关键字。" />
        ) : (
          <div className={`logs-terminal ${wrapLines ? 'wrap' : 'nowrap'}`}>
            <div className="logs-terminal-scroll" ref={termRef}>
              {data.file.lines.map((line) => (
                <div key={line.no} className={`logs-term-line ${classifyTermLine(line.text)}`}>
                  <span className="logs-term-no">{line.no}</span>
                  <span className="logs-term-text">
                    <HighlightText text={line.text} query={q} />
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      <div className="dash-grid-2">
        <div className="dash-panel">
          <header className="dash-panel-head compact">
            <div>
              <h3>限流判定流水（rate_limit_event · 最新 80 条）</h3>
              <p>分别记录 429 拦截（rejected）与滑窗放行（allowed），便于分辨真实限流事件与正常流量</p>
            </div>
          </header>
          {!data?.rateLimitHits?.length ? (
            <p className="dash-empty">近期无限流判定记录</p>
          ) : (
            <div className="dash-scroll-list">
              <div className="dash-table-wrap">
                <table className="dash-table">
                  <thead>
                    <tr>
                      <th scope="col">时间</th>
                      <th scope="col">判定结果</th>
                      <th scope="col">限流 Bucket</th>
                      <th scope="col">窗口计次 / 上限</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.rateLimitHits.map((r, i) => {
                      const isRejected = r.outcome === 'rejected';
                      return (
                        <tr key={i}>
                          <td className="dash-nowrap">{fmtDateTime(r.createdAt)}</td>
                          <td>
                            <span className={`logs-status ${isRejected ? 'err' : 'ok'}`}>
                              {isRejected ? '429 拦截' : '放行'}
                            </span>
                          </td>
                          <td className="logs-mono">{r.bucket}</td>
                          <td className="dash-nowrap">
                            {r.maxRequests != null ? `${r.currentCount ?? 0} / ${r.maxRequests} (${r.windowSeconds ?? 60}s)` : '—'}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>

        <div className="dash-panel">
          <header className="dash-panel-head compact">
            <div>
              <h3>平台管理操作审计（admin_audit_log · 最新 50 条）</h3>
              <p>强停任务、配额调整、应用下线恢复、社区置顶等操作留痕</p>
            </div>
          </header>
          {!data?.auditLogs?.length ? (
            <p className="dash-empty">暂无管理操作审计记录</p>
          ) : (
            <div className="dash-scroll-list">
              <div className="dash-table-wrap">
                <table className="dash-table">
                  <thead>
                    <tr>
                      <th scope="col">时间</th>
                      <th scope="col">操作者</th>
                      <th scope="col">动作 / 目标</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.auditLogs.map((a) => (
                      <tr key={a.id}>
                        <td className="dash-nowrap">{fmtDateTime(a.createdAt)}</td>
                        <td>{a.actor}</td>
                        <td>
                          <span className="dash-tag">{a.action}</span>{' '}
                          <span className="logs-mono">{a.targetType}:{a.targetId}</span>
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
    </div>
  );
}

// ==========================================================================
// 7. 云库 Schema / DDL 创建与变更日志视图
// ==========================================================================

function SchemasView() {
  const [range, setRange] = useState<OpsLogRange>('7d');
  const [onlyErrors, setOnlyErrors] = useState<boolean>(false);
  const [q, setQ] = useState<string>('');
  const [offset, setOffset] = useState<number>(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const limit = 40;
  const debouncedQ = useDebounced(q.trim(), 260);

  const schemaQ = useQuery({
    queryKey: ['ops-schemas', range, onlyErrors, debouncedQ, offset],
    queryFn: () =>
      api.opsSchemas({
        range,
        ok: onlyErrors ? false : undefined,
        q: debouncedQ || undefined,
        limit,
        offset,
      }),
    placeholderData: keepPreviousData,
  });

  const data = schemaQ.data;
  const items = data?.items ?? [];
  const schemas = data?.schemas ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className="logs-filter-bar">
        <div className="logs-filter-row">
          <div className="logs-filter-left">
            <label className="logs-search-box">
              <Icon name="search" />
              <input
                placeholder="搜 Schema 名 (app_xxx) / 应用名 / 表名 / DDL SQL…"
                value={q}
                onChange={(e) => {
                  setQ(e.target.value);
                  setOffset(0);
                }}
              />
              {q ? (
                <button
                  type="button"
                  className="logs-search-clear"
                  onClick={() => {
                    setQ('');
                    setOffset(0);
                  }}
                >
                  <Icon name="close" />
                </button>
              ) : null}
            </label>
            <select
              className="logs-select"
              value={range}
              onChange={(e) => {
                setRange(e.target.value as OpsLogRange);
                setOffset(0);
              }}
            >
              {RANGES.map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
            <button
              type="button"
              className={`logs-chip err ${onlyErrors ? 'active' : ''}`}
              onClick={() => {
                setOnlyErrors((v) => !v);
                setOffset(0);
              }}
            >
              <span>仅看建表/Schema 报错</span>
              <b>{fmtNum(data?.errorCount ?? 0)}</b>
            </button>
          </div>
          <div className="logs-filter-right">
            <span style={{ fontSize: 12, color: '#6b647d' }}>
              已建 Schema {fmtNum(schemas.length)} 个 · 变更日志 {fmtNum(data?.total ?? 0)} 条
            </span>
            <button type="button" className="dash-action-btn" onClick={() => void schemaQ.refetch()} disabled={schemaQ.isFetching}>
              <Icon name="refresh" className={schemaQ.isFetching ? 'spinning' : ''} />
              <span>刷新</span>
            </button>
          </div>
        </div>
      </div>

      <div className="dash-panel">
        <header className="dash-panel-head compact">
          <div>
            <h3>Schema / DDL 执行与变更日志（create schema / create table / alter table / drop）</h3>
            <p>完整记录每次启用云服务或任务提交后执行的 PostgreSQL DDL 语句与失败堆栈</p>
          </div>
        </header>
        {schemaQ.isLoading ? (
          <LoadingState label="正在读取 Schema DDL 执行日志…" />
        ) : schemaQ.error ? (
          <ErrorState message={errorMessage(schemaQ.error)} onRetry={() => void schemaQ.refetch()} />
        ) : items.length === 0 ? (
          <EmptyState title="暂无 Schema 创建/变更日志" desc="当用户启用云服务或任务同步 cloud/schema.sql 时，执行的 DDL 会实时记录于此。" />
        ) : (
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">时间</th>
                  <th scope="col">应用 / 作者</th>
                  <th scope="col">动作</th>
                  <th scope="col">状态</th>
                  <th scope="col">耗时</th>
                  <th scope="col">摘要</th>
                  <th scope="col">DDL / 堆栈</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <Fragment key={item.id}>
                    <tr>
                      <td className="dash-nowrap">{fmtDateTime(item.createdAt)}</td>
                      <td>
                        <b>{item.appName || item.appId || '—'}</b>
                        <div style={{ fontSize: 11, color: '#7c7491' }}>{item.ownerName || item.workspaceId || '—'}</div>
                      </td>
                      <td><span className="dash-tag">{item.action}</span></td>
                      <td><span className={`logs-status ${item.ok ? 'ok' : 'err'}`}>{item.ok ? '成功' : '失败'}</span></td>
                      <td>{item.durationMs != null ? `${item.durationMs} ms` : '—'}</td>
                      <td style={{ maxWidth: 360 }}>{item.summary}</td>
                      <td>
                        {item.detail ? (
                          <button type="button" className="dash-link" onClick={() => setOpenId(openId === item.id ? null : item.id)}>
                            {openId === item.id ? '收起 SQL' : '查看执行 DDL'}
                          </button>
                        ) : (
                          <span className="dash-muted">无</span>
                        )}
                      </td>
                    </tr>
                    {openId === item.id && item.detail ? (
                      <tr>
                        <td colSpan={7}>
                          <pre className="logs-term-inline" style={{ margin: 0 }}>{item.detail}</pre>
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <OpsPager offset={offset} limit={limit} total={data?.total ?? 0} onChange={setOffset} />
      </div>

      <div className="dash-panel">
        <header className="dash-panel-head compact">
          <div>
            <h3>当前库内应用 Schema 注册清单（app_cloud）</h3>
            <p>实时反映各应用独立的 PostgreSQL Schema、启用状态与当前已建表名</p>
          </div>
        </header>
        {schemas.length === 0 ? (
          <p className="dash-empty">当前筛选下暂无已注册的云数据库 Schema</p>
        ) : (
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">Schema 名称</th>
                  <th scope="col">应用 / 作者</th>
                  <th scope="col">状态</th>
                  <th scope="col">表数量</th>
                  <th scope="col">已建表清单</th>
                  <th scope="col">最近更新时间</th>
                </tr>
              </thead>
              <tbody>
                {schemas.map((s) => (
                  <tr key={s.appId}>
                    <td className="logs-mono"><b>{s.schemaName}</b></td>
                    <td>
                      <b>{s.appName}</b>
                      <div style={{ fontSize: 11, color: '#7c7491' }}>{s.ownerName || s.workspaceId || s.appId}</div>
                    </td>
                    <td><span className={`logs-status ${s.status === 'on' ? 'ok' : 'muted'}`}>{s.status === 'on' ? '已启用' : '已关闭'}</span></td>
                    <td>{fmtNum(s.tableCount)}</td>
                    <td className="logs-mono" style={{ fontSize: 11 }}>{(s.tableNames ?? []).join(', ') || '—'}</td>
                    <td className="dash-nowrap">{fmtDateTime(s.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

// ==========================================================================
// 8. 模型网关与全平台异常追踪视图
// ==========================================================================

const CATEGORY_LABELS: Record<string, string> = {
  ALL: '全部链路事件',
  llm_gateway: '模型网关与问答 (llm_gateway)',
  api_error: '接口异常与堆栈 (api_error)',
  cloud_schema: '云库 Schema (cloud_schema)',
  sandbox: '沙箱与调度异常 (sandbox)',
  lifecycle: '应用发布与生命周期 (lifecycle)',
};

function PlatformEventsView() {
  const navigate = useNavigate();
  const [range, setRange] = useState<OpsLogRange>('7d');
  const [category, setCategory] = useState<string>('ALL');
  const [onlyErrors, setOnlyErrors] = useState<boolean>(false);
  const [q, setQ] = useState<string>('');
  const [offset, setOffset] = useState<number>(0);
  const [openId, setOpenId] = useState<number | null>(null);
  const limit = 50;
  const debouncedQ = useDebounced(q.trim(), 260);

  const evQ = useQuery({
    queryKey: ['ops-platform-events', range, category, onlyErrors, debouncedQ, offset],
    queryFn: () =>
      api.opsEvents({
        range,
        category,
        ok: onlyErrors ? false : undefined,
        q: debouncedQ || undefined,
        limit,
        offset,
      }),
    placeholderData: keepPreviousData,
  });

  const data = evQ.data;
  const items = data?.items ?? [];

  return (
    <>
      <div className="logs-filter-bar">
        <div className="logs-filter-row">
          <div className="logs-filter-left">
            <label className="logs-search-box">
              <Icon name="search" />
              <input
                placeholder="搜接口路径 / 错误码 / 模型 / taskId / 异常堆栈…"
                value={q}
                onChange={(e) => {
                  setQ(e.target.value);
                  setOffset(0);
                }}
              />
              {q ? (
                <button
                  type="button"
                  className="logs-search-clear"
                  onClick={() => {
                    setQ('');
                    setOffset(0);
                  }}
                >
                  <Icon name="close" />
                </button>
              ) : null}
            </label>
            <select
              className="logs-select"
              value={range}
              onChange={(e) => {
                setRange(e.target.value as OpsLogRange);
                setOffset(0);
              }}
            >
              {RANGES.map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
            <button
              type="button"
              className={`logs-chip err ${onlyErrors ? 'active' : ''}`}
              onClick={() => {
                setOnlyErrors((v) => !v);
                setOffset(0);
              }}
            >
              <span>仅看失败与异常</span>
              <b>{fmtNum(data?.errorCount ?? 0)}</b>
            </button>
          </div>
          <div className="logs-filter-right">
            <span style={{ fontSize: 12, color: '#6b647d' }}>共 {fmtNum(data?.total ?? 0)} 条</span>
            <button type="button" className="dash-action-btn" onClick={() => void evQ.refetch()} disabled={evQ.isFetching}>
              <Icon name="refresh" className={evQ.isFetching ? 'spinning' : ''} />
              <span>刷新</span>
            </button>
          </div>
        </div>

        <div className="logs-chip-row">
          {Object.entries(CATEGORY_LABELS).map(([k, label]) => {
            const stat = data?.byCategory?.find((c) => c.category === k);
            const cnt = k === 'ALL' ? data?.total ?? 0 : stat?.count ?? 0;
            return (
              <button
                key={k}
                type="button"
                className={`logs-chip ${category === k ? 'active' : ''}`}
                onClick={() => {
                  setCategory(k);
                  setOffset(0);
                }}
              >
                <span>{label}</span>
                <b>{fmtNum(cnt)}</b>
              </button>
            );
          })}
        </div>
      </div>

      <div className="dash-panel">
        {evQ.isLoading ? (
          <LoadingState label="正在读取全链路网关与异常日志…" />
        ) : evQ.error ? (
          <ErrorState message={errorMessage(evQ.error)} onRetry={() => void evQ.refetch()} />
        ) : items.length === 0 ? (
          <EmptyState title="暂无匹配的网关或异常事件" desc="尝试切换事件类别或时间范围。" />
        ) : (
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">时间</th>
                  <th scope="col">类别 / 动作</th>
                  <th scope="col">状态</th>
                  <th scope="col">耗时</th>
                  <th scope="col">关联任务 / 应用</th>
                  <th scope="col">摘要</th>
                  <th scope="col">详情</th>
                </tr>
              </thead>
              <tbody>
                {items.map((item) => (
                  <Fragment key={item.id}>
                    <tr>
                      <td className="dash-nowrap">{fmtDateTime(item.createdAt)}</td>
                      <td>
                        <span className="dash-tag">{item.category}</span>{' '}
                        <span className="logs-mono">{item.action}</span>
                      </td>
                      <td>
                        <span className={`logs-status ${item.ok ? 'ok' : item.level === 'WARN' ? 'muted' : 'err'}`}>
                          {item.statusCode ?? (item.ok ? 'OK' : item.level)}
                        </span>
                      </td>
                      <td>{item.durationMs != null ? `${item.durationMs} ms` : '—'}</td>
                      <td>
                        {item.taskId ? (
                          <button type="button" className="dash-link logs-mono" onClick={() => navigate('/logs?taskId=' + encodeURIComponent(item.taskId!))}>
                            {item.taskId}
                          </button>
                        ) : item.appName ? (
                          <span>{item.appName}</span>
                        ) : (
                          <span className="dash-muted">{item.workspaceId || '—'}</span>
                        )}
                      </td>
                      <td style={{ maxWidth: 380, wordBreak: 'break-word' }}>{item.summary}</td>
                      <td>
                        {item.detail ? (
                          <button type="button" className="dash-link" onClick={() => setOpenId(openId === item.id ? null : item.id)}>
                            {openId === item.id ? '收起' : '堆栈/报文'}
                          </button>
                        ) : (
                          <span className="dash-muted">无</span>
                        )}
                      </td>
                    </tr>
                    {openId === item.id && item.detail ? (
                      <tr>
                        <td colSpan={7}>
                          <pre className="logs-term-inline" style={{ margin: 0 }}>{item.detail}</pre>
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <OpsPager offset={offset} limit={limit} total={data?.total ?? 0} onChange={setOffset} />
      </div>
    </>
  );
}

// ==========================================================================
// 9. 全链路执行阶段耗时瀑布图（Trace Waterfall）
// ==========================================================================

function TraceWaterfallBar({
  task,
  events,
  sandbox,
  onJumpSandboxes,
  onJumpTab,
}: {
  task: OpsTaskSummary;
  events: OpsTaskEventRow[];
  sandbox: SandboxItem | null;
  onJumpSandboxes?: () => void;
  onJumpTab: (tab: DetailTab) => void;
}) {
  const createdMs = new Date(task.createdAt).getTime();
  const startedMs = task.startedAt ? new Date(task.startedAt).getTime() : createdMs;
  const finishedMs = task.finishedAt ? new Date(task.finishedAt).getTime() : Date.now();
  const queueWaitMs = Math.max(0, startedMs - createdMs);
  const runMs = Math.max(0, finishedMs - startedMs);
  const startupMs = Math.max(0, sandbox?.startupMs ?? 0);
  const effectiveStartupMs = Math.min(runMs, startupMs);
  const workMs = Math.max(0, runMs - effectiveStartupMs);
  const totalMs = Math.max(1, queueWaitMs + runMs);

  const toolCalls = events.filter((e) => e.type === 'tool');
  const toolErrs = toolCalls.filter((e) => e.payload?.ok === false);
  const verifyEvents = events.filter((e) => e.type === 'verify');
  const verifyBad = verifyEvents.filter((e) => isEventError(e)).length;

  const queuePct = Math.round((queueWaitMs / totalMs) * 100);
  const bootPct = Math.round((effectiveStartupMs / totalMs) * 100);
  const workPct = Math.max(1, 100 - queuePct - bootPct);

  return (
    <div className="logs-waterfall-card">
      <div className="logs-wf-top">
        <div className="logs-wf-title">
          <b>全链路 Trace 阶段瀑布图</b>
          <span>
            排队 {fmtDurationMs(queueWaitMs)} → 沙箱启动 {fmtDurationMs(startupMs)} → Agent/工具执行 {fmtDurationMs(workMs)}
          </span>
        </div>
        <div className="logs-wf-badges">
          <button type="button" className="logs-wf-pill" onClick={() => onJumpTab('events')}>
            工具 <b>{toolCalls.length}</b>
            {toolErrs.length > 0 ? <em className="err">错 {toolErrs.length}</em> : null}
          </button>
          <button type="button" className="logs-wf-pill" onClick={() => onJumpTab('events')}>
            质检门禁 <b>{verifyEvents.length}</b>
            {verifyBad > 0 ? <em className="err">挂 {verifyBad}</em> : null}
          </button>
          {sandbox ? (
            <button
              type="button"
              className="logs-wf-pill"
              onClick={() => (onJumpSandboxes ? onJumpSandboxes() : onJumpTab('resources'))}
              title={`归属副本 ${sandbox.ownerInstance} · 点击查看沙箱台账`}
            >
              沙箱 <code>{sandbox.sandboxId}</code> · {sandbox.provider}
            </button>
          ) : null}
        </div>
      </div>

      <div className="logs-wf-track" role="img" aria-label={`排队 ${queuePct}%，启动 ${bootPct}%，执行 ${workPct}%`}>
        {queuePct > 0 ? (
          <div className="logs-wf-seg queue" style={{ width: `${Math.max(2, queuePct)}%` }} title={`排队等待 ${fmtDurationMs(queueWaitMs)}`} />
        ) : null}
        {bootPct > 0 ? (
          <div className="logs-wf-seg boot" style={{ width: `${Math.max(2, bootPct)}%` }} title={`沙箱冷启动 ${fmtDurationMs(startupMs)}`} />
        ) : null}
        <div className={`logs-wf-seg work ${task.status === 'FAILED' ? 'err' : ''}`} style={{ width: `${workPct}%` }} title={`编码与校验 ${fmtDurationMs(workMs)}`} />
      </div>
    </div>
  );
}

// ==========================================================================
// 10. 沙箱全生命周期日志视图
// ==========================================================================

function SandboxLogsView() {
  const navigate = useNavigate();
  const [range, setRange] = useState<OpsLogRange>('7d');
  const [state, setState] = useState<string>('ALL');
  const [onlyErrors, setOnlyErrors] = useState<boolean>(false);
  const [q, setQ] = useState<string>('');
  const [offset, setOffset] = useState<number>(0);
  const limit = 40;
  const debouncedQ = useDebounced(q.trim(), 260);

  const sbxQ = useQuery({
    queryKey: ['ops-sbx-logs', range, state, onlyErrors, debouncedQ, offset],
    queryFn: () =>
      api.opsSandboxes({
        range,
        state: state === 'ALL' ? undefined : state,
        onlyErrors: onlyErrors ? true : undefined,
        q: debouncedQ || undefined,
        limit,
        offset,
      }),
    placeholderData: keepPreviousData,
  });

  const data = sbxQ.data;
  const items = data?.items ?? [];
  const events = data?.events ?? [];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className="logs-sbx-summary">
        <div><span>沙箱总创建数</span><b>{fmtNum(data?.summary.total ?? 0)}</b></div>
        <div><span>当前活跃</span><b>{fmtNum(data?.summary.active ?? 0)}</b></div>
        <div><span>启动失败</span><b className={(data?.summary.failed ?? 0) > 0 ? 'dash-err' : ''}>{fmtNum(data?.summary.failed ?? 0)}</b></div>
        <div><span>已销毁回收</span><b>{fmtNum(data?.summary.destroyed ?? 0)}</b></div>
        <div><span>冷启动均耗时</span><b>{fmtDurationMs(data?.summary.avgStartupMs)}</b></div>
        <div><span>冷启动 P95</span><b>{fmtDurationMs(data?.summary.p95StartupMs)}</b></div>
      </div>

      <div className="logs-filter-bar">
        <div className="logs-filter-row">
          <div className="logs-filter-left">
            <label className="logs-search-box">
              <Icon name="search" />
              <input
                placeholder="搜 sandboxId / taskId / PID / Pod / 副本 / 销毁原因…"
                value={q}
                onChange={(e) => {
                  setQ(e.target.value);
                  setOffset(0);
                }}
              />
              {q ? (
                <button
                  type="button"
                  className="logs-search-clear"
                  onClick={() => {
                    setQ('');
                    setOffset(0);
                  }}
                >
                  <Icon name="close" />
                </button>
              ) : null}
            </label>
            <select
              className="logs-select"
              value={state}
              onChange={(e) => {
                setState(e.target.value);
                setOffset(0);
              }}
            >
              <option value="ALL">全部状态</option>
              <option value="active">活跃 (active)</option>
              <option value="creating">创建中 (creating)</option>
              <option value="destroyed">已销毁 (destroyed)</option>
              <option value="failed">启动失败 (failed)</option>
            </select>
            <select
              className="logs-select"
              value={range}
              onChange={(e) => {
                setRange(e.target.value as OpsLogRange);
                setOffset(0);
              }}
            >
              {RANGES.map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
            <button
              type="button"
              className={`logs-chip err ${onlyErrors ? 'active' : ''}`}
              onClick={() => {
                setOnlyErrors((v) => !v);
                setOffset(0);
              }}
            >
              <span>仅看失败与强杀</span>
            </button>
          </div>
          <div className="logs-filter-right">
            <span style={{ fontSize: 12, color: '#6b647d' }}>共 {fmtNum(data?.total ?? 0)} 条</span>
            <button type="button" className="dash-action-btn" onClick={() => void sbxQ.refetch()} disabled={sbxQ.isFetching}>
              <Icon name="refresh" className={sbxQ.isFetching ? 'spinning' : ''} />
              <span>刷新</span>
            </button>
          </div>
        </div>
      </div>

      <div className="dash-panel">
        <header className="dash-panel-head compact">
          <div>
            <h3>沙箱全生命周期台账（creating → active → destroyed/failed）</h3>
            <p>完整记录每个沙箱的冷启动耗时、归属副本、进程/Pod 引用、峰值资源采样及销毁操刀人/原因</p>
          </div>
        </header>
        {sbxQ.isLoading ? (
          <LoadingState label="正在读取沙箱生命周期日志…" />
        ) : sbxQ.error ? (
          <ErrorState message={errorMessage(sbxQ.error)} onRetry={() => void sbxQ.refetch()} />
        ) : items.length === 0 ? (
          <EmptyState title="暂无沙箱生命周期记录" desc="运行任意 AI 开发任务后，沙箱创建与销毁轨迹会自动归档于此。" />
        ) : (
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">创建时间</th>
                  <th scope="col">沙箱 ID / 引擎</th>
                  <th scope="col">关联任务 / 应用</th>
                  <th scope="col">状态</th>
                  <th scope="col">冷启动 / 存活</th>
                  <th scope="col">归属副本 / 作者</th>
                  <th scope="col">销毁原因 / 操刀人</th>
                </tr>
              </thead>
              <tbody>
                {items.map((sb) => (
                  <tr key={sb.sandboxId}>
                    <td className="dash-nowrap">{fmtDateTime(sb.createdAt)}</td>
                    <td>
                      <div className="logs-mono"><b>{sb.sandboxId}</b></div>
                      <div style={{ fontSize: 11, color: '#7c7491' }}>
                        {sb.provider} · {sb.externalRef || '—'}
                      </div>
                    </td>
                    <td>
                      {sb.taskId ? (
                        <button type="button" className="dash-link logs-mono" onClick={() => navigate('/logs?taskId=' + encodeURIComponent(sb.taskId!))}>
                          {sb.taskId}
                        </button>
                      ) : '—'}
                      <div style={{ fontSize: 11, color: '#7c7491' }}>{sb.appName || sb.appId || '—'}</div>
                    </td>
                    <td>
                      <span className={`logs-status ${sb.state === 'failed' ? 'err' : sb.state === 'active' || sb.state === 'creating' ? 'run' : 'ok'}`}>
                        {sb.state}
                      </span>
                    </td>
                    <td className="dash-nowrap">
                      <div>启 {sb.startupMs != null ? `${sb.startupMs}ms` : '—'}</div>
                      <small style={{ color: '#7c7491' }}>共 {sb.durationSeconds != null ? `${sb.durationSeconds}s` : '—'}</small>
                    </td>
                    <td>
                      <div className="logs-mono" style={{ fontSize: 11 }}>{sb.ownerInstance}</div>
                      <div style={{ fontSize: 11, color: '#7c7491' }}>{sb.ownerName || sb.workspaceId || '—'}</div>
                    </td>
                    <td>
                      <div>{sb.destroyReason || '—'}</div>
                      {sb.destroyedBy ? <small style={{ color: '#7c7491' }}>操刀人: {sb.destroyedBy}</small> : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <OpsPager offset={offset} limit={limit} total={data?.total ?? 0} onChange={setOffset} />
      </div>

      {events.length > 0 ? (
        <div className="dash-panel">
          <header className="dash-panel-head compact">
            <div>
              <h3>沙箱控制面事件流水（就绪 / 销毁 / 对账清理 / 启动异常 · {events.length} 条）</h3>
              <p>来自 platform_event_log (category = sandbox)</p>
            </div>
          </header>
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">时间</th>
                  <th scope="col">动作</th>
                  <th scope="col">级别</th>
                  <th scope="col">任务 ID</th>
                  <th scope="col">摘要</th>
                </tr>
              </thead>
              <tbody>
                {events.map((ev) => (
                  <tr key={ev.id}>
                    <td className="dash-nowrap">{fmtDateTime(ev.createdAt)}</td>
                    <td className="logs-mono">{ev.action}</td>
                    <td><span className={`logs-status ${ev.ok ? 'ok' : ev.level === 'WARN' ? 'muted' : 'err'}`}>{ev.level}</span></td>
                    <td>
                      {ev.taskId ? (
                        <button type="button" className="dash-link logs-mono" onClick={() => navigate('/logs?taskId=' + encodeURIComponent(ev.taskId!))}>
                          {ev.taskId}
                        </button>
                      ) : '—'}
                    </td>
                    <td>{ev.summary}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
    </div>
  );
}
