import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import type {
  OpsLogActor,
  OpsLogFileView,
  OpsLogRange,
  OpsTaskDetailResponse,
  OpsTaskEventRow,
  OpsTaskSummary,
} from '../api/types';
import { Icon } from '../lib/icons';
import { copyText, downloadUrl } from '../lib/format';
import { useDebounced } from '../lib/useDebounced';
import { toast } from '../lib/store';
import { fmtBytes, fmtCompact, fmtDurationMs, fmtNum } from '../components/DashboardCharts';
import { EmptyState, ErrorState, LoadingState } from '../components/EmptyState';

type MainTab = 'tasks' | 'functions' | 'sources' | 'runtime';
type DetailTab = 'events' | 'runner' | 'resources' | 'app';
type EventFilter = 'all' | 'tool' | 'log' | 'verify' | 'thinking' | 'status' | 'collab';

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

export function LogsPage() {
  const [params, setParams] = useSearchParams();
  const rawTab = params.get('tab');
  const mainTab: MainTab =
    rawTab === 'functions' || rawTab === 'sources' || rawTab === 'runtime' ? rawTab : 'tasks';

  const setMainTab = (tab: MainTab) => {
    const next = new URLSearchParams(params);
    if (tab === 'tasks') next.delete('tab');
    else next.set('tab', tab);
    setParams(next, { replace: true });
  };

  return (
    <div className="content-page logs-page">
      <header className="dash-header-top">
        <div className="dash-title-block">
          <span className="overline">OBSERVABILITY · DEVELOPMENT &amp; RUNTIME TROUBLESHOOTING</span>
          <div className="dash-title-row">
            <h1>开发与运行日志排查中心</h1>
            <span className="dash-scope-badge platform">
              <i className="dash-pulse-dot" aria-hidden="true" />
              全链路开发过程诊断
            </span>
          </div>
          <p>
            跨工作空间检索每次 AI 开发任务的思考步骤、工具入参/报错、质检门禁命令行输出与沙箱 Runner 底层日志，联动云函数、数据源代理与服务端指标，快速定位用户开发阻塞。
          </p>
        </div>
      </header>

      <nav className="dash-nav-bar logs-tabs" role="tablist" aria-label="日志排查模块">
        {(
          [
            ['tasks', 'AI 开发过程日志'],
            ['functions', '云函数执行日志'],
            ['sources', '数据源调用日志'],
            ['runtime', '服务端日志与 Prometheus 指标'],
          ] as [MainTab, string][]
        ).map(([id, label]) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={mainTab === id}
            className={mainTab === id ? 'active' : ''}
            onClick={() => setMainTab(id)}
          >
            {label}
          </button>
        ))}
      </nav>

      {mainTab === 'tasks' ? <TaskLogsWorkbench /> : null}
      {mainTab === 'functions' ? <FunctionLogsView /> : null}
      {mainTab === 'sources' ? <SourceCallsView /> : null}
      {mainTab === 'runtime' ? <RuntimeLogsView /> : null}
    </div>
  );
}

// ==========================================================================
// 1. AI 开发过程日志分栏工作台
// ==========================================================================

function TaskLogsWorkbench() {
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

  const selectTask = (id: string) => {
    const next = new URLSearchParams(params);
    if (id) next.set('taskId', id);
    else next.delete('taskId');
    setParams(next, { replace: true });
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

  // 若 URL 没指定 taskId，加载后默认选中第一条
  useEffect(() => {
    if (!selectedTaskId && items.length > 0) {
      selectTask(items[0].id);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedTaskId, items.length]);

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
        <aside className="logs-list-pane" aria-label="任务列表">
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
                  onSelect={() => selectTask(item.id)}
                />
              ))
            )}
          </div>

          <div className="logs-pager">
            <span>
              第 {data?.total ? offset + 1 : 0}–{Math.min(offset + limit, data?.total ?? 0)} / {fmtNum(data?.total ?? 0)}
            </span>
            <div className="logs-pager-btns">
              <button type="button" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - limit))}>
                上一页
              </button>
              <button
                type="button"
                disabled={offset + limit >= (data?.total ?? 0)}
                onClick={() => setOffset(offset + limit)}
              >
                下一页
              </button>
            </div>
          </div>
        </aside>

        <section className="logs-detail-pane" aria-label="任务全链路诊断">
          {!selectedTaskId ? (
            <EmptyState icon="terminal" title="选择一条任务查看开发日志" desc="从左侧挑选任意任务，或在管理看板点击失败任务跳转至此。" />
          ) : (
            <TaskDiagnosticPane taskId={selectedTaskId} onOpenApp={(appId) => navigate('/apps/' + appId)} />
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

function TaskDiagnosticPane({ taskId, onOpenApp }: { taskId: string; onOpenApp: (appId: string) => void }) {
  const [subTab, setSubTab] = useState<DetailTab>('events');
  const [liveFollow, setLiveFollow] = useState<boolean>(true);
  const [liveEvents, setLiveEvents] = useState<OpsTaskEventRow[] | null>(null);
  const [liveTaskPatch, setLiveTaskPatch] = useState<Partial<OpsTaskSummary> | null>(null);
  const [liveRunnerLog, setLiveRunnerLog] = useState<OpsLogFileView | null>(null);

  const [evFilter, setEvFilter] = useState<EventFilter>('all');
  const [onlyEvErrors, setOnlyEvErrors] = useState<boolean>(false);
  const [evQuery, setEvQuery] = useState<string>('');
  const [expandAllEvents, setExpandAllEvents] = useState<boolean>(false);

  const [runnerTail, setRunnerTail] = useState<number>(800);
  const [runnerQuery, setRunnerQuery] = useState<string>('');
  const [autoScrollRunner, setAutoScrollRunner] = useState<boolean>(true);
  const runnerScrollRef = useRef<HTMLDivElement>(null);

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
  }, [taskId]);

  const baseDetail: OpsTaskDetailResponse | undefined = detailQ.data;
  const events = liveEvents ?? baseDetail?.events ?? [];
  const task = useMemo(() => {
    if (!baseDetail?.task) return undefined;
    return liveTaskPatch ? { ...baseDetail.task, ...liveTaskPatch } : baseDetail.task;
  }, [baseDetail?.task, liveTaskPatch]);

  // 单独拉取或搜索 Runner 日志（当搜索条件或行数变化时）
  const runnerQ = useQuery({
    queryKey: ['ops-runner-log', taskId, runnerTail, debouncedRunnerQuery],
    queryFn: () => api.opsRunnerLog(taskId, { tail: runnerTail, q: debouncedRunnerQuery || undefined }),
    enabled: subTab === 'runner' && (runnerTail !== 800 || !!debouncedRunnerQuery),
  });

  const runnerLog = liveRunnerLog ?? (subTab === 'runner' && (runnerTail !== 800 || !!debouncedRunnerQuery) ? runnerQ.data : baseDetail?.runnerLog);

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
          api.opsRunnerLog(taskId, { tail: runnerTail, q: debouncedRunnerQuery || undefined }),
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

  const filteredEvents = useMemo(() => {
    const q = evQuery.trim();
    return events.filter((e) => eventMatchesFilter(e, evFilter, onlyEvErrors, q));
  }, [events, evFilter, onlyEvErrors, evQuery]);

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
          <span>模型调用与上下文</span>
          <small>{baseDetail.modelUsages.length}</small>
        </button>
        <button type="button" role="tab" aria-selected={subTab === 'app'} className={subTab === 'app' ? 'active' : ''} onClick={() => setSubTab('app')}>
          <span>应用云函数与数据源</span>
          <small>{baseDetail.fnLogs.length + baseDetail.sourceCalls.length}</small>
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
              </div>
            </div>

            {filteredEvents.length === 0 ? (
              <EmptyState title="当前筛选下无过程事件" desc="尝试切换事件类型或取消「仅看报错」过滤。" />
            ) : (
              <div className="logs-event-list">
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
                <label className="logs-search-box" style={{ minWidth: 220, maxWidth: 320, height: 32 }}>
                  <Icon name="search" />
                  <input
                    placeholder="检索 Runner 日志关键字（如 error / 401 / timeout）"
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
                    共 {fmtNum(runnerLog.totalLines)} 行 · 匹配 {fmtNum(runnerLog.matchedLines)} 行 · {fmtBytes(runnerLog.sizeBytes)}
                    {runnerLog.updatedAt ? ` · 更新于 ${fmtDateTime(runnerLog.updatedAt)}` : ''}
                  </span>
                ) : null}
              </div>

              <div className="logs-filter-right">
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
              <EmptyState title="没有匹配的日志行" desc="请尝试清空或更换搜索关键字。" />
            ) : (
              <div className="logs-terminal">
                <div className="logs-terminal-scroll" ref={runnerScrollRef}>
                  {runnerLog.lines.map((line) => (
                    <div key={line.no} className={`logs-term-line ${classifyTermLine(line.text)}`}>
                      <span className="logs-term-no">{line.no}</span>
                      <span className="logs-term-text">{line.text}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </>
        ) : null}

        {subTab === 'resources' ? (
          <>
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

function EventCard({ ev, defaultOpen }: { ev: OpsTaskEventRow; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  const p = ev.payload ?? {};
  const err = isEventError(ev);

  const summary = useMemo(() => {
    switch (ev.type) {
      case 'status':
        return `${String(p.status ?? '')} · ${String(p.phaseLabel ?? '')} (${String(p.progress ?? 0)}%)`;
      case 'tool':
        return `${String(p.name ?? '')} [${String(p.state ?? '')}] · ${String(p.summary ?? '')}`;
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
          <span className="logs-ev-seq">#{ev.seq}</span>
          <span className={`logs-ev-type ${typeCls}`}>{EVENT_TYPE_LABELS[ev.type] ?? ev.type}</span>
          {ev.type === 'tool' && typeof p.ok === 'boolean' ? (
            <span className={`logs-mini-badge ${p.ok ? '' : 'err'}`}>{p.ok ? 'OK' : '失败'}</span>
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
  const [openId, setOpenId] = useState<number | null>(null);
  const debouncedQ = useDebounced(q.trim(), 260);

  const fnQ = useQuery({
    queryKey: ['ops-fn-logs', range, onlyErrors, debouncedQ],
    queryFn: () =>
      api.opsFunctionLogs({
        range,
        ok: onlyErrors ? false : undefined,
        q: debouncedQ || undefined,
        limit: 50,
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
                onChange={(e) => setQ(e.target.value)}
              />
              {q ? (
                <button type="button" className="logs-search-clear" onClick={() => setQ('')}>
                  <Icon name="close" />
                </button>
              ) : null}
            </label>
            <select className="logs-select" value={range} onChange={(e) => setRange(e.target.value as OpsLogRange)}>
              {RANGES.map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
            <button
              type="button"
              className={`logs-chip err ${onlyErrors ? 'active' : ''}`}
              onClick={() => setOnlyErrors((v) => !v)}
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
  const debouncedQ = useDebounced(q.trim(), 260);

  const srcQ = useQuery({
    queryKey: ['ops-source-calls', range, onlyErrors, origin, debouncedQ],
    queryFn: () =>
      api.opsSourceCalls({
        range,
        ok: onlyErrors ? false : undefined,
        origin: origin === 'ALL' ? undefined : origin,
        q: debouncedQ || undefined,
        limit: 60,
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
                onChange={(e) => setQ(e.target.value)}
              />
              {q ? (
                <button type="button" className="logs-search-clear" onClick={() => setQ('')}>
                  <Icon name="close" />
                </button>
              ) : null}
            </label>
            <select className="logs-select" value={origin} onChange={(e) => setOrigin(e.target.value)}>
              <option value="ALL">全部来源</option>
              <option value="preview">预览环境 (preview)</option>
              <option value="site">线上站点 (site)</option>
              <option value="test">连通性测试 (test)</option>
            </select>
            <select className="logs-select" value={range} onChange={(e) => setRange(e.target.value as OpsLogRange)}>
              {RANGES.map(([k, label]) => (
                <option key={k} value={k}>
                  {label}
                </option>
              ))}
            </select>
            <button
              type="button"
              className={`logs-chip err ${onlyErrors ? 'active' : ''}`}
              onClick={() => setOnlyErrors((v) => !v)}
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
  const [q, setQ] = useState<string>('');
  const debouncedQ = useDebounced(q.trim(), 260);

  const rtQ = useQuery({
    queryKey: ['ops-runtime-logs', target, tail, debouncedQ],
    queryFn: () => api.opsRuntime({ target, tail, q: debouncedQ || undefined }),
    placeholderData: keepPreviousData,
  });

  const data = rtQ.data;
  const promUrl = typeof window !== 'undefined' ? window.location.origin + (data?.prometheusEndpoint ?? '/api/meta/metrics/prometheus') : '/api/meta/metrics/prometheus';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <div className="dash-panel">
        <header className="dash-panel-head compact">
          <div>
            <h3>运行期指标与 Prometheus 抓取接入</h3>
            <p>
              当前沙箱模式：<b className="logs-mono">{data?.sandboxProvider ?? '—'}</b> · 实例：
              <b className="logs-mono">{(data?.metrics?.instances ?? []).join(', ') || '—'}</b>
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
            <small>均 {data?.metrics?.sandbox?.avgMs ?? 0}ms / 峰值 {data?.metrics?.sandbox?.maxMs ?? 0}ms</small>
          </div>
          <div>
            <span>上游 LLM 调用</span>
            <b>{fmtNum(data?.metrics?.llm?.count ?? 0)} 次</b>
            <small>均 {data?.metrics?.llm?.avgMs ?? 0}ms / 重试 {data?.metrics?.llm?.retries ?? 0}</small>
          </div>
          <div>
            <span>额度错误率</span>
            <b>{(((data?.metrics?.llm?.quotaErrorRate ?? 0) * 100)).toFixed(2)}%</b>
            <small>额度错 {data?.metrics?.llm?.quotaErrors ?? 0} 次</small>
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
        <code className="logs-mono">task_event</code> 与共享卷 <code className="logs-mono">logs/tasks/{'{taskId}'}.log</code> 中，可在第一个页签直接排查。
      </div>

      <div className="logs-filter-bar">
        <div className="logs-filter-row">
          <div className="logs-filter-left">
            <div className="logs-chip-row">
              {(
                [
                  ['api', 'api.log (主服务与任务)'],
                  ['site', 'site.log (站点网关)'],
                  ['web-build', 'web-build.log (前端构建)'],
                ] as ['api' | 'site' | 'web-build', string][]
              ).map(([k, label]) => (
                <button key={k} type="button" className={`logs-chip ${target === k ? 'active' : ''}`} onClick={() => setTarget(k)}>
                  {label}
                </button>
              ))}
            </div>
            <label className="logs-search-box" style={{ minWidth: 200, maxWidth: 320 }}>
              <Icon name="search" />
              <input placeholder="筛选服务日志关键字…" value={q} onChange={(e) => setQ(e.target.value)} />
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
          <EmptyState title="没有匹配的日志行" desc="请更换过滤关键字。" />
        ) : (
          <div className="logs-terminal">
            <div className="logs-terminal-scroll">
              {data.file.lines.map((line) => (
                <div key={line.no} className={`logs-term-line ${classifyTermLine(line.text)}`}>
                  <span className="logs-term-no">{line.no}</span>
                  <span className="logs-term-text">{line.text}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
