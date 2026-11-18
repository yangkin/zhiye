import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type { ClusterNodeInfo, ClusterOverviewResponse, SandboxItem } from '../api/types';
import { Icon } from '../lib/icons';
import { copyText, fmtTime } from '../lib/format';
import { toast } from '../lib/store';
import { confirmDialog } from './ConfirmDialog';
import { ErrorState, LoadingState } from './EmptyState';
import { Modal } from './Modal';
import { Select } from './Select';
import {
  DashEmptyBox,
  MultiTrendChart,
  fmtBytes,
  fmtDurationMs,
  fmtNum,
  type SeriesPoint,
} from './DashboardCharts';

const KIND_LABELS: Record<string, string> = {
  session: '正常会话 (session)',
  'ledger-ghost': '账面幽灵 (ghost)',
  unregistered: '未登记孤儿',
  destroyed: '已销毁回收',
  failed: '启动失败',
};

const KIND_TONES: Record<string, string> = {
  session: 'ok',
  'ledger-ghost': 'warn',
  unregistered: 'warn',
  destroyed: 'muted',
  failed: 'err',
};

const PROVIDER_LABELS: Record<string, string> = {
  local: 'Local Seatbelt/Process',
  k8s: 'Kubernetes Pod',
  opensandbox: 'OpenSandbox',
};

function fmtSecondsAgo(sec: number | undefined | null): string {
  if (sec == null || Number.isNaN(sec)) return '—';
  if (sec < 60) return `${Math.round(sec)}s`;
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${Math.round(sec % 60)}s`;
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`;
}

function downloadSnapshot(filename: string, data: unknown) {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export function ClusterSandboxPanel({ compact = false, onOpenClusterTab }: { compact?: boolean; onOpenClusterTab?: () => void }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [withUsage, setWithUsage] = useState(true);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [kindFilter, setKindFilter] = useState<string>('ALL');
  const [search, setSearch] = useState('');
  const [hourlyMetric, setHourlyMetric] = useState<'tasks' | 'llm'>('tasks');
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [inspectRef, setInspectRef] = useState<string | null>(null);
  const [destroyTarget, setDestroyTarget] = useState<SandboxItem | null>(null);
  const [destroyReason, setDestroyReason] = useState('');

  const clusterQ = useQuery({
    queryKey: qk.adminCluster(withUsage),
    queryFn: () => api.adminCluster(withUsage),
    placeholderData: keepPreviousData,
    refetchInterval: autoRefresh ? 5000 : false,
  });

  const detailQ = useQuery({
    queryKey: ['admin', 'sandbox-detail', inspectRef],
    queryFn: () => api.adminSandboxDetail(inspectRef!),
    enabled: !!inspectRef,
  });

  const data: ClusterOverviewResponse | undefined = clusterQ.data;

  const invalidateCluster = async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: ['admin', 'cluster'] }),
      qc.invalidateQueries({ queryKey: ['dashboard'] }),
      qc.invalidateQueries({ queryKey: qk.adminOverview }),
    ]);
  };

  const openDestroyModal = (sb: SandboxItem) => {
    setDestroyTarget(sb);
    setDestroyReason(sb.kind === 'ledger-ghost' ? '清理账面幽灵' : '运维手动释放资源');
  };

  const executeDestroy = async (force: boolean) => {
    const sb = destroyTarget;
    if (!sb) return;
    const reason = destroyReason.trim() || '运维手动销毁释放';

    setBusyKey(`del-${sb.sandboxId}`);
    try {
      const res = await api.adminDestroySandbox(sb.sandboxId, { force, reason });
      const msg =
        res.status === 'reconcile_settled'
          ? `已核销账面幽灵 ${res.sandboxId}（控制面实体已退出）`
          : `已销毁沙箱 ${res.sandboxId}${res.cancelledTask ? '，并已触发关联任务停止' : ''}`;
      toast(msg, 'ok');
      setDestroyTarget(null);
      await invalidateCluster();
    } catch (err) {
      const status = (err as { status?: number }).status;
      const errMsg = errorMessage(err);
      if (status === 409 && !force && errMsg.includes('force=true')) {
        setDestroyTarget(null);
        const confirmed = await confirmDialog({
          title: '沙箱仍有任务运行中，确认强杀？',
          message: `沙箱 ${sb.sandboxId} 关联的任务 ${sb.taskId ?? ''} 当前正在执行（${sb.taskStatus ?? 'ACTIVE'}）。强行销毁将立即终止沙箱进程/Pod，并向任务发送停止信号。`,
          confirmLabel: '强行销毁并终止任务',
          danger: true,
        });
        if (confirmed) {
          setBusyKey(`del-${sb.sandboxId}`);
          try {
            const res = await api.adminDestroySandbox(sb.sandboxId, { force: true, reason });
            toast(`已强行销毁 ${res.sandboxId}${res.cancelledTask ? '，并已触发关联任务停止' : ''}`, 'ok');
            await invalidateCluster();
          } catch (err2) {
            toast(errorMessage(err2), 'error');
          } finally {
            setBusyKey(null);
          }
        }
      } else {
        toast(errMsg, 'error');
      }
    } finally {
      setBusyKey(null);
    }
  };

  const handleReap = async () => {
    const ok = await confirmDialog({
      title: '对账清理幽灵与孤儿沙箱',
      message: '将自动平账所有已失联/退出的「账面幽灵 (ledger-ghost)」记录，并回收控制面上未登记的孤儿容器/Pod。正在健康运行的沙箱不会受影响。',
      confirmLabel: '立即对账清理',
    });
    if (!ok) return;
    setBusyKey('reap');
    try {
      const res = await api.adminReapSandboxes();
      toast(`对账完成：平账幽灵 ${res.ghostsSettled} 条，清理孤儿 ${res.orphansKilled} 个`, 'ok');
      await invalidateCluster();
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusyKey(null);
    }
  };

  const handlePurgeOfflineNodes = async (instanceId?: string) => {
    const ok = await confirmDialog({
      title: instanceId ? '移除离线节点记录' : '清理全部离线节点记录',
      message: instanceId
        ? `确认从集群拓扑中移除已离线的历史节点「${instanceId}」吗？在线节点不受影响。`
        : '确认移除所有已停机或心跳超时（>120s）的离线历史节点记录吗？在线节点不受影响。',
      confirmLabel: '确认清理',
    });
    if (!ok) return;
    const key = instanceId ? `node-${instanceId}` : 'node-all';
    setBusyKey(key);
    try {
      const res = await api.adminPurgeOfflineNodes(instanceId);
      toast(`已清理 ${res.purgedCount} 个离线集群节点记录`, 'ok');
      await invalidateCluster();
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusyKey(null);
    }
  };

  const handleReclaimLeases = async () => {
    const ok = await confirmDialog({
      title: '立即回收超时任务租约',
      message: '将立即把所有租约已过期的卡死任务标记为失败（WORKER_LOST），释放对应应用的单任务写锁。确认执行吗？',
      confirmLabel: '立即回收',
      danger: true,
    });
    if (!ok) return;
    setBusyKey('reclaim-leases');
    try {
      const res = await api.adminReclaimExpiredLeases();
      toast(`已回收 ${res.reclaimedCount} 个超时任务租约并释放应用写锁`, 'ok');
      await invalidateCluster();
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusyKey(null);
    }
  };

  const filteredSandboxes = useMemo(() => {
    const items = data?.sandboxes?.items ?? [];
    const q = search.trim().toLowerCase();
    return items.filter((it) => {
      if (kindFilter === 'LIVE' && it.kind !== 'session' && it.kind !== 'unregistered' && it.kind !== 'ledger-ghost') return false;
      if (kindFilter !== 'ALL' && kindFilter !== 'LIVE' && it.kind !== kindFilter) return false;
      if (!q) return true;
      const hay = [
        it.sandboxId,
        it.externalRef,
        it.taskId,
        it.appName,
        it.workspaceId,
        it.ownerName,
        it.ownerInstance,
        it.destroyReason,
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      return hay.includes(q);
    });
  }, [data?.sandboxes?.items, kindFilter, search]);

  const hourlyPoints: SeriesPoint[] = useMemo(() => {
    const trend = data?.runtimeMetrics?.trend ?? [];
    if (hourlyMetric === 'tasks') {
      return trend.map((t) => ({
        date: t.hour,
        primary: t.tasks,
        secondary: t.taskFailures,
      }));
    }
    return trend.map((t) => ({
      date: t.hour,
      primary: t.llmCalls,
      secondary: t.quotaErrors,
    }));
  }, [data?.runtimeMetrics?.trend, hourlyMetric]);

  if (clusterQ.isLoading && !data) {
    return (
      <section className="dash-section" aria-label="集群拓扑与沙箱控制">
        <LoadingState label="正在采集集群多副本心跳与沙箱实况…" />
      </section>
    );
  }

  if (clusterQ.error && !data) {
    return (
      <section className="dash-section" aria-label="集群拓扑与沙箱控制">
        <ErrorState message={errorMessage(clusterQ.error)} onRetry={() => void clusterQ.refetch()} />
      </section>
    );
  }

  if (!data) return null;

  const { nodesSummary, nodes, queue, sandboxes, runtimeMetrics } = data;
  const sbxSum = sandboxes.summary;
  const alerts = runtimeMetrics?.alerts ?? [];
  const anomalyCount = (sbxSum.ghostCount ?? 0) + (sbxSum.unregisteredCount ?? 0);
  const visibleSandboxes = compact ? filteredSandboxes.slice(0, 6) : filteredSandboxes;

  if (compact) {
    return (
      <section className="dash-section" aria-label="集群与沙箱实时拓扑摘要">
        <div className="dash-sec-title">
          <div>
            <h2>集群多副本拓扑与沙箱控制面</h2>
            <p>
              当前沙箱引擎：<code className="dash-code">{PROVIDER_LABELS[data.sandboxProvider] ?? data.sandboxProvider}</code> ·{' '}
              {sbxSum.controlPlaneMessage || '控制面正常'}
            </p>
          </div>
          {onOpenClusterTab ? (
            <button type="button" className="dash-action-btn primary" onClick={onOpenClusterTab}>
              <Icon name="terminal" />
              <span>进入集群与沙箱指挥台 ({fmtNum(sbxSum.total)} 活跃沙箱)</span>
            </button>
          ) : null}
        </div>

        <div className="dash-cluster-strip">
          <div className="dash-cluster-metric">
            <span>在线副本节点</span>
            <b>
              {nodesSummary.online} / {nodesSummary.total}
            </b>
            <small>{nodesSummary.offline > 0 ? `${nodesSummary.offline} 节点已离线` : '全部心跳正常'}</small>
          </div>
          <div className="dash-cluster-metric">
            <span>Worker 并发槽位</span>
            <b>
              {nodesSummary.workerSlotsUsed} / {nodesSummary.workerSlotsTotal}
            </b>
            <small>
              排队 {fmtNum(queue.queued)} · 进行中 {fmtNum(queue.runningTotal)}
            </small>
          </div>
          <div className="dash-cluster-metric">
            <span>活跃沙箱 / 幽灵孤儿</span>
            <b className={anomalyCount > 0 ? 'dash-warn-num' : ''}>
              {fmtNum(sbxSum.total)} <small>/ 异常 {anomalyCount}</small>
            </b>
            <small>冷启动均 {fmtDurationMs(sbxSum.avgStartupMs)} · P95 {fmtDurationMs(runtimeMetrics?.sandbox?.p95Ms)}</small>
          </div>
          <div className="dash-cluster-metric">
            <span>沙箱实时内存总水位</span>
            <b>{fmtBytes(sbxSum.memoryUsedBytesTotal)}</b>
            <small>{sbxSum.cpuPercentAvg != null ? `平均 CPU ${sbxSum.cpuPercentAvg}%` : '按需实时采样'}</small>
          </div>
          <div className="dash-cluster-metric">
            <span>集群任务 / 模型 P95</span>
            <b>{fmtDurationMs(runtimeMetrics?.tasks?.p95Ms)}</b>
            <small>
              LLM P95 {fmtDurationMs(runtimeMetrics?.llm?.p95Ms)} · 额度错{' '}
              {(((runtimeMetrics?.llm?.quotaErrorRate ?? 0) * 100)).toFixed(2)}%
            </small>
          </div>
        </div>
      </section>
    );
  }

  return (
    <section className="dash-section" aria-label="集群拓扑与沙箱全生命周期控制">
      {/* 顶部控制面与告警条 */}
      <div className="dash-panel dash-cluster-toolbar">
        <div className="dash-cluster-toolbar-left">
          <span className={`dash-cp-pill ${sbxSum.controlPlaneAvailable ? 'ok' : 'err'}`}>
            <i className="dash-pulse-dot" aria-hidden="true" />
            {PROVIDER_LABELS[data.sandboxProvider] ?? data.sandboxProvider} · {sbxSum.controlPlaneMessage || '控制面就绪'}
          </span>
          <span className="dash-window-info">
            当前响应副本 <code className="dash-code">{data.currentInstanceId}</code> · 采样于 {fmtTime(data.generatedAt)}
          </span>
        </div>
        <div className="dash-cluster-toolbar-right">
          <button
            type="button"
            className={`logs-chip ${withUsage ? 'active' : ''}`}
            onClick={() => setWithUsage((v) => !v)}
            title="开启后会向本副本活跃沙箱进程采集实时 CPU% 与内存 RSS"
          >
            <span>实时 CPU/内存采样</span>
          </button>
          <button
            type="button"
            className={`logs-chip ${autoRefresh ? 'active' : ''}`}
            onClick={() => setAutoRefresh((v) => !v)}
          >
            {autoRefresh ? <span className="logs-live-dot" aria-hidden="true" /> : null}
            <span>{autoRefresh ? '5s 自动刷新中' : '自动刷新已暂停'}</span>
          </button>
          <button
            type="button"
            className={`dash-action-btn ${anomalyCount > 0 ? 'warn' : ''}`}
            disabled={busyKey === 'reap' || !sbxSum.controlPlaneAvailable || sbxSum.controlPlaneComplete === false}
            title={!sbxSum.controlPlaneAvailable || sbxSum.controlPlaneComplete === false ? '控制面探测不完整，已安全禁用批量对账' : undefined}
            onClick={() => void handleReap()}
          >
            <Icon name="refresh" className={busyKey === 'reap' ? 'spinning' : ''} />
            <span>对账清理幽灵/孤儿{anomalyCount > 0 ? ` (${anomalyCount})` : ''}</span>
          </button>
          <button
            type="button"
            className="dash-action-btn"
            onClick={() => {
              downloadSnapshot(`zhiye-cluster-sandboxes-${Date.now()}.json`, data);
              toast('已导出集群拓扑与沙箱实况快照。');
            }}
          >
            <Icon name="download" />
            <span>导出快照</span>
          </button>
        </div>
      </div>

      {/* 告警提示条 */}
      {(alerts.length > 0 || queue.expiredLeases > 0 || anomalyCount > 0 || !sbxSum.controlPlaneAvailable || sbxSum.controlPlaneComplete === false) ? (
        <div className="dash-alert-stack" role="alert">
          {!sbxSum.controlPlaneAvailable || sbxSum.controlPlaneComplete === false ? (
            <div className="dash-alert-item err">
              <b>{!sbxSum.controlPlaneAvailable ? 'CONTROL_PLANE_UNREACHABLE' : 'CONTROL_PLANE_INCOMPLETE'}</b>
              <span>{sbxSum.controlPlaneMessage || '沙箱控制面探测未完整，已自动阻断批量清理账本。'}</span>
            </div>
          ) : null}
          {queue.expiredLeases > 0 ? (
            <div className="dash-alert-item err">
              <b>EXPIRED_TASK_LEASE</b>
              <span>检测到 {queue.expiredLeases} 个任务租约已超时，worker 可能已重启或异常退出，应用写锁仍被占用。</span>
              <button
                type="button"
                className="dash-link danger"
                style={{ marginLeft: 'auto', flexShrink: 0 }}
                disabled={busyKey === 'reclaim-leases'}
                onClick={() => void handleReclaimLeases()}
              >
                {busyKey === 'reclaim-leases' ? '回收中…' : `立即回收租约 (${queue.expiredLeases})`}
              </button>
            </div>
          ) : null}
          {anomalyCount > 0 ? (
            <div className="dash-alert-item warn">
              <b>SANDBOX_RECONCILE_NEEDED</b>
              <span>
                发现 {sbxSum.ghostCount} 个账面幽灵 (ledger-ghost) 与 {sbxSum.unregisteredCount} 个未登记孤儿，建议点击右上角「对账清理」。
              </span>
            </div>
          ) : null}
          {alerts.map((a) => (
            <div key={a.code} className={`dash-alert-item ${a.level === 'ERROR' ? 'err' : 'warn'}`}>
              <b>{a.code}</b>
              <span>
                {a.message}（当前值 {a.value} / 阈值 {a.threshold}）
              </span>
            </div>
          ))}
        </div>
      ) : null}

      {/* 1. 集群节点拓扑卡片 */}
      <div className="dash-panel">
        <header className="dash-panel-head compact">
          <div>
            <h3>集群多副本节点拓扑（Cluster Nodes · {nodesSummary.online} 在线 / {nodes.length} 总计）</h3>
            <p>
              每个 Pod / 进程实例每 10 秒向 PostgreSQL 上报心跳、JVM 堆内存、宿主机 CPU 与 Worker 并发槽位占用。
            </p>
          </div>
          <div className="dash-queue-pills">
            <span className="dash-pill">排队 <b>{fmtNum(queue.queued)}</b></span>
            <span className="dash-pill">准备 <b>{fmtNum(queue.preparing)}</b></span>
            <span className="dash-pill ok">编写中 <b>{fmtNum(queue.running)}</b></span>
            <span className="dash-pill">质检 <b>{fmtNum(queue.verifying)}</b></span>
            <span className="dash-pill">提交 <b>{fmtNum(queue.committing)}</b></span>
            {queue.oldestQueuedSeconds > 0 ? (
              <span className="dash-pill warn">最长排队 {fmtSecondsAgo(queue.oldestQueuedSeconds)}</span>
            ) : null}
            {nodesSummary.offline > 0 ? (
              <button
                type="button"
                className="subtle-button dash-compact-btn"
                disabled={busyKey === 'node-all'}
                onClick={() => void handlePurgeOfflineNodes()}
              >
                清理离线节点 ({nodesSummary.offline})
              </button>
            ) : null}
          </div>
        </header>

        {nodes.length === 0 ? (
          <DashEmptyBox icon="cloud" title="暂无已注册的集群节点心跳记录" />
        ) : (
          <div className="dash-node-grid">
            {nodes.map((node) => (
              <NodeTopologyCard
                key={node.instanceId}
                node={node}
                busy={busyKey === `node-${node.instanceId}`}
                onPurgeOffline={(id) => void handlePurgeOfflineNodes(id)}
              />
            ))}
          </div>
        )}
      </div>

      {/* 2. 24h 运行期 SLO 时序图 & P95 延迟对比 */}
      <div className="dash-grid-2">
        <div className="dash-panel">
          <header className="dash-panel-head compact">
            <div>
              <h3>近 24 小时集群运行期每小时趋势（跨实例持久化汇总）</h3>
              <p>来源于全集群各副本写入的 PostgreSQL runtime_metric_event 原始遥测明细</p>
            </div>
            <div className="seg-control small" role="radiogroup" aria-label="运行期趋势切换">
              <button
                type="button"
                role="radio"
                aria-checked={hourlyMetric === 'tasks'}
                className={hourlyMetric === 'tasks' ? 'active' : ''}
                onClick={() => setHourlyMetric('tasks')}
              >
                任务与失败
              </button>
              <button
                type="button"
                role="radio"
                aria-checked={hourlyMetric === 'llm'}
                className={hourlyMetric === 'llm' ? 'active' : ''}
                onClick={() => setHourlyMetric('llm')}
              >
                LLM 与额度错
              </button>
            </div>
          </header>
          {hourlyPoints.length === 0 ? (
            <DashEmptyBox icon="chart" title="近 24 小时暂无运行期事件趋势数据" />
          ) : (
            <MultiTrendChart
              data={hourlyPoints}
              kind="bar"
              primaryLabel={hourlyMetric === 'tasks' ? '任务数' : 'LLM 请求数'}
              secondaryLabel={hourlyMetric === 'tasks' ? '失败数' : '额度报错'}
              primaryColor={hourlyMetric === 'tasks' ? '#7953dc' : '#4a7bd0'}
              secondaryColor="#d0465c"
              unit=" 次"
            />
          )}
        </div>

        <div className="dash-panel">
          <header className="dash-panel-head compact">
            <div>
              <h3>全链路延迟 SLA 与故障码分布（7 天滑动窗口）</h3>
              <p>精确评估任务端到端耗时、沙箱冷启动延迟与上游模型网关 P95</p>
            </div>
          </header>
          <div className="dash-sla-grid">
            <div className="dash-sla-box">
              <span>AI 任务端到端耗时</span>
              <b>P95 {fmtDurationMs(runtimeMetrics?.tasks?.p95Ms)}</b>
              <small>
                均值 {fmtDurationMs(runtimeMetrics?.tasks?.avgMs)} · 峰值 {fmtDurationMs(runtimeMetrics?.tasks?.maxMs)} · 成功{' '}
                {fmtNum(runtimeMetrics?.tasks?.succeeded)} / 败 {fmtNum(runtimeMetrics?.tasks?.failed)}
              </small>
            </div>
            <div className="dash-sla-box">
              <span>沙箱冷启动耗时</span>
              <b>P95 {fmtDurationMs(runtimeMetrics?.sandbox?.p95Ms)}</b>
              <small>
                均值 {fmtDurationMs(runtimeMetrics?.sandbox?.avgMs)} · 峰值 {fmtDurationMs(runtimeMetrics?.sandbox?.maxMs)} · 累计{' '}
                {fmtNum(runtimeMetrics?.sandbox?.count)} 次
              </small>
            </div>
            <div className="dash-sla-box">
              <span>上游模型网关延迟</span>
              <b>P95 {fmtDurationMs(runtimeMetrics?.llm?.p95Ms)}</b>
              <small>
                均值 {fmtDurationMs(runtimeMetrics?.llm?.avgMs)} · 重试 {fmtNum(runtimeMetrics?.llm?.retries)} · 额度错{' '}
                {fmtNum(runtimeMetrics?.llm?.quotaErrors)}
              </small>
            </div>
          </div>

          {runtimeMetrics?.tasks?.failureCodes && Object.keys(runtimeMetrics.tasks.failureCodes).length > 0 ? (
            <div className="dash-sub-block">
              <h4>运行期失败码分布 (7d)</h4>
              <div className="dash-chip-grid">
                {Object.entries(runtimeMetrics.tasks.failureCodes).map(([code, cnt]) => (
                  <span key={code} className="dash-kv-chip err">
                    <span>{code}</span>
                    <b>{fmtNum(cnt)}</b>
                  </span>
                ))}
              </div>
            </div>
          ) : (
            <p className="dash-muted-line" style={{ marginTop: 12 }}>近 7 天集群运行期无错误码记录。</p>
          )}
        </div>
      </div>

      {/* 3. 全量沙箱实时盘点与对账表 */}
      <div className="dash-panel">
        <header className="dash-panel-head">
          <div>
            <h3>全量沙箱容器/进程实况与账实对账（共 {fmtNum(sbxSum.total)} 个活跃 · 总计 {fmtNum(sbxSum.totalRows)} 条记录）</h3>
            <p>
              参考控制面与 PostgreSQL 沙箱账本双源核对：支持实时进程 RSS/CPU 采样、识别账面幽灵与孤儿、409 冲突保护强杀与历史审计追溯。
            </p>
          </div>
          <div className="dash-toolbar-group">
            <Select
              ariaLabel="按沙箱类别筛选"
              value={kindFilter}
              onChange={(val) => setKindFilter(val)}
              options={[
                { value: 'ALL', label: `全部记录 (${fmtNum(sbxSum.totalRows ?? sandboxes.items.length)})` },
                { value: 'LIVE', label: `仅看活跃/异常 (${fmtNum(sbxSum.total)})` },
                { value: 'session', label: `正常会话 (${fmtNum(sbxSum.byKind?.session ?? 0)})` },
                { value: 'ledger-ghost', label: `账面幽灵 (${fmtNum(sbxSum.byKind?.['ledger-ghost'] ?? 0)})` },
                { value: 'unregistered', label: `未登记孤儿 (${fmtNum(sbxSum.byKind?.unregistered ?? 0)})` },
                { value: 'destroyed', label: `已销毁历史 (${fmtNum(sbxSum.byKind?.destroyed ?? 0)})` },
                { value: 'failed', label: `启动失败 (${fmtNum(sbxSum.byKind?.failed ?? 0)})` },
              ]}
            />
            <label className="dash-search">
              <Icon name="search" />
              <input
                aria-label="筛选沙箱"
                placeholder="搜 sandboxId / taskId / PID / 空间 / 节点…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </label>
          </div>
        </header>

        {visibleSandboxes.length === 0 ? (
          <DashEmptyBox icon="terminal" title="当前筛选条件下暂无沙箱记录" />
        ) : (
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">沙箱 ID / 外部引用</th>
                  <th scope="col">对账类别 &amp; 状态</th>
                  <th scope="col">关联任务 / 应用</th>
                  <th scope="col">归属副本 / 空间人员</th>
                  <th scope="col">CPU / 内存水位</th>
                  <th scope="col">冷启动 / 存活 / 闲置</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {visibleSandboxes.map((sb) => {
                  const kind = sb.kind || sb.state;
                  const tone = KIND_TONES[kind] ?? 'muted';
                  const canDestroy = sb.state === 'creating' || sb.state === 'active' || kind === 'unregistered' || kind === 'ledger-ghost';
                  return (
                    <tr key={sb.sandboxId}>
                      <td>
                        <div className="dash-sbx-id-cell">
                          <button
                            type="button"
                            className="dash-copy-code"
                            title="点击复制 sandboxId"
                            onClick={async () => toast((await copyText(sb.sandboxId)) ? `已复制 ${sb.sandboxId}` : '复制失败')}
                          >
                            {sb.sandboxId}
                          </button>
                          <div className="dash-sbx-sub">
                            <span className="dash-tag">{sb.provider}</span>
                            <code className="dash-code">{sb.externalRef || (sb.pid ? `pid:${sb.pid}` : '—')}</code>
                            {sb.allowNet ? <span className="dash-pill cloud">allowNet</span> : null}
                          </div>
                        </div>
                      </td>
                      <td>
                        <div className="dash-sbx-id-cell">
                          <span className={`dash-pill ${tone}`}>{KIND_LABELS[kind] ?? kind}</span>
                          <small className="dash-muted">
                            {sb.state}
                            {sb.taskRunning ? ' · 执行中' : ''}
                            {sb.destroyReason ? ` · ${sb.destroyReason}` : ''}
                          </small>
                        </div>
                      </td>
                      <td>
                        {sb.taskId ? (
                          <div className="dash-sbx-id-cell">
                            <button
                              type="button"
                              className="dash-link logs-mono"
                              onClick={() => navigate('/logs?taskId=' + encodeURIComponent(sb.taskId!))}
                            >
                              {sb.taskId}
                            </button>
                            <small className="dash-muted">
                              {sb.appName || sb.appId || '—'} · {sb.taskStatus || '—'}
                            </small>
                          </div>
                        ) : (
                          <span className="dash-muted">无关联任务</span>
                        )}
                      </td>
                      <td>
                        <div className="dash-sbx-id-cell">
                          <span>
                            <code className="dash-code">{sb.ownerInstance}</code>
                            {sb.onThisReplica ? <span className="dash-pill ok" style={{ marginLeft: 4 }}>本副本</span> : null}
                          </span>
                          <small className="dash-muted">
                            {sb.ownerName || sb.workspaceId || '—'}
                          </small>
                        </div>
                      </td>
                      <td>
                        {sb.state === 'destroyed' || sb.state === 'failed' ? (
                          <div className="dash-sbx-usage">
                            <span className="dash-muted">{sb.state === 'failed' ? '启动失败' : '已释放销毁'}</span>
                            {sb.memoryUsedBytes != null ? (
                              <small className="dash-muted">定格峰值 {fmtBytes(sb.memoryUsedBytes)}</small>
                            ) : null}
                          </div>
                        ) : sb.memoryUsedBytes != null || sb.cpuPercent != null ? (
                          <div className="dash-sbx-usage">
                            <div>
                              <b>{sb.memoryUsedBytes != null ? fmtBytes(sb.memoryUsedBytes) : '—'}</b>
                              {sb.memoryLimit ? <span className="dash-muted"> / {sb.memoryLimit}</span> : null}
                            </div>
                            <small className="dash-muted">
                              CPU {sb.cpuPercent != null ? `${sb.cpuPercent}%` : '—'}
                              {sb.memoryPercent != null ? ` · 占比 ${sb.memoryPercent}%` : ''}
                            </small>
                          </div>
                        ) : (
                          <span className="dash-muted">待采样</span>
                        )}
                      </td>
                      <td className="dash-nowrap">
                        <div>启 {sb.startupMs != null ? `${sb.startupMs}ms` : '—'}</div>
                        <small className="dash-muted">
                          {sb.state === 'destroyed' || sb.state === 'failed'
                            ? `总存续 ${fmtSecondsAgo(sb.ageSeconds)}`
                            : `活 ${fmtSecondsAgo(sb.ageSeconds)} · 闲 ${fmtSecondsAgo(sb.idleSeconds)}`}
                        </small>
                      </td>
                      <td>
                        <div className="dash-action-row">
                          <button type="button" className="dash-link" onClick={() => setInspectRef(sb.sandboxId)}>
                            详情
                          </button>
                          {canDestroy ? (
                            <button
                              type="button"
                              className="dash-link danger"
                              disabled={busyKey === `del-${sb.sandboxId}`}
                              onClick={() => openDestroyModal(sb)}
                            >
                              {kind === 'ledger-ghost' ? '平账' : '销毁'}
                            </button>
                          ) : null}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* 单沙箱详情检查抽屉 Modal */}
      {inspectRef ? (
        <Modal title={`沙箱实例详情 · ${inspectRef}`} wide onClose={() => setInspectRef(null)}>
          {detailQ.isLoading ? (
            <LoadingState label="正在读取沙箱详情与审计事件…" />
          ) : detailQ.error || !detailQ.data ? (
            <ErrorState message={errorMessage(detailQ.error)} onRetry={() => void detailQ.refetch()} />
          ) : (
            <SandboxInspectorBody item={detailQ.data} onJumpTask={(tid) => navigate('/logs?taskId=' + encodeURIComponent(tid))} />
          )}
        </Modal>
      ) : null}

      {/* 沙箱销毁审计 Modal */}
      {destroyTarget ? (
        <Modal title={`销毁并释放沙箱 · ${destroyTarget.sandboxId}`} onClose={() => setDestroyTarget(null)}>
          <div className="dash-destroy-modal">
            <p className="dash-muted-line">
              销毁仅释放运行沙箱与进程/Pod 资源，应用历史版本与聊天上下文全部保留；操作人及原因将强制写入审计台账。
            </p>
            <div className="dash-destroy-presets">
              {['运维手动释放资源', '闲置超时回收', '会话卡死重置', '清理账面幽灵'].map((preset) => (
                <button
                  key={preset}
                  type="button"
                  className={`logs-chip ${destroyReason === preset ? 'active' : ''}`}
                  onClick={() => setDestroyReason(preset)}
                >
                  {preset}
                </button>
              ))}
            </div>
            <label className="dash-destroy-label">
              <span>操作原因（写入审计日志）</span>
              <input
                value={destroyReason}
                onChange={(e) => setDestroyReason(e.target.value)}
                placeholder="请输入操作原因…"
              />
            </label>
            <div className="dash-destroy-actions">
              <button type="button" className="subtle-button" onClick={() => setDestroyTarget(null)}>
                取消
              </button>
              <button
                type="button"
                className="primary-button danger"
                disabled={busyKey === `del-${destroyTarget.sandboxId}`}
                onClick={() => void executeDestroy(false)}
              >
                {busyKey === `del-${destroyTarget.sandboxId}` ? '处理中…' : '确认销毁沙箱'}
              </button>
            </div>
          </div>
        </Modal>
      ) : null}
    </section>
  );
}

function NodeTopologyCard({
  node,
  busy,
  onPurgeOffline,
}: {
  node: ClusterNodeInfo;
  busy?: boolean;
  onPurgeOffline?: (instanceId: string) => void;
}) {
  const slotPct = node.workerConcurrency > 0 ? Math.min(100, Math.round((node.activeTasks / node.workerConcurrency) * 100)) : 0;
  const heapPct =
    node.heapPercent ??
    (node.heapMaxBytes > 0 ? Math.round((node.heapUsedBytes / node.heapMaxBytes) * 1000) / 10 : 0);
  const isGracefulStop = node.status === 'offline' && node.gracefulStop;
  const statusPillClass = node.status === 'online' ? 'ok' : node.status === 'degraded' || isGracefulStop ? 'warn' : 'err';
  const statusLabel =
    node.status === 'online'
      ? '在线'
      : node.status === 'degraded'
        ? '心跳延迟'
        : isGracefulStop
          ? '已停机'
          : '已失联';
  return (
    <div className={`dash-node-card ${node.status}`}>
      <div className="dash-node-head">
        <div className="dash-node-id">
          <i className={`dash-node-dot ${node.status}`} aria-hidden="true" />
          <b title={node.instanceId}>{node.instanceId}</b>
          {node.isCurrent ? <span className="dash-pill cloud">当前节点</span> : null}
        </div>
        <span className={`dash-pill ${statusPillClass}`}>
          {statusLabel}
        </span>
      </div>

      <div className="dash-node-meta">
        <span>角色 {node.role}</span>
        <span>·</span>
        <span>{node.hostname}:{node.port}</span>
        <span>·</span>
        <span>心跳 {fmtSecondsAgo(node.heartbeatAgeSeconds)} 前</span>
      </div>

      <div className="dash-node-bars">
        <div className="dash-gauge-row">
          <div>
            <span>Worker 并发槽位</span>
            <b>
              {node.activeTasks} / {node.workerConcurrency} ({slotPct}%)
            </b>
          </div>
          <div className="dash-gauge-track">
            <div className="dash-gauge-fill" style={{ width: `${slotPct}%`, background: slotPct >= 90 ? '#d0465c' : '#7953dc' }} />
          </div>
        </div>
        <div className="dash-gauge-row">
          <div>
            <span>JVM 堆内存</span>
            <b>
              {fmtBytes(node.heapUsedBytes)} / {fmtBytes(node.heapMaxBytes)} ({heapPct}%)
            </b>
          </div>
          <div className="dash-gauge-track">
            <div className="dash-gauge-fill" style={{ width: `${Math.min(100, heapPct)}%`, background: heapPct >= 85 ? '#d9822b' : '#2f9e8f' }} />
          </div>
        </div>
      </div>

      <div className="dash-node-foot">
        <span>活跃沙箱 {node.activeSandboxes}</span>
        <span>OS CPU {node.cpuPercent != null ? `${node.cpuPercent}%` : '—'}</span>
        {node.status === 'offline' && !node.isCurrent && onPurgeOffline ? (
          <button
            type="button"
            className="dash-link danger"
            disabled={busy}
            onClick={() => onPurgeOffline(node.instanceId)}
          >
            {busy ? '移除中…' : '移除离线记录'}
          </button>
        ) : (
          <span>进程 {node.sandboxProvider}</span>
        )}
      </div>
    </div>
  );
}

function SandboxInspectorBody({ item, onJumpTask }: { item: SandboxItem; onJumpTask: (taskId: string) => void }) {
  return (
    <div className="dash-sbx-inspector">
      <div className="dash-sbx-kv-grid">
        <div><span>Sandbox ID</span><code className="dash-code">{item.sandboxId}</code></div>
        <div><span>Provider / Kind</span><b>{item.provider} · {item.kind || item.state}</b></div>
        <div><span>External Ref / PID</span><code className="dash-code">{item.externalRef || '—'} (PID {item.pid ?? '—'})</code></div>
        <div><span>Image / Command</span><code className="dash-code">{item.imageOrCmd || '—'}</code></div>
        <div><span>归属副本</span><code className="dash-code">{item.ownerInstance} ({item.ownerReplicaStatus || '—'})</code></div>
        <div><span>Worker 节点</span><code className="dash-code">{item.workerOwner || '—'}</code></div>
        <div><span>冷启动耗时</span><b>{item.startupMs != null ? `${item.startupMs} ms` : '—'}</b></div>
        <div><span>实时内存 / CPU</span><b>{item.memoryUsedBytes != null ? fmtBytes(item.memoryUsedBytes) : '—'} / {item.cpuPercent != null ? `${item.cpuPercent}%` : '—'}</b></div>
        <div><span>创建时间</span><b>{item.createdAt ? fmtTime(item.createdAt) : '—'}</b></div>
        <div><span>就绪 / 销毁时间</span><b>{item.readyAt ? fmtTime(item.readyAt) : '—'} → {item.destroyedAt ? fmtTime(item.destroyedAt) : '运行中'}</b></div>
        {item.destroyedBy || item.destroyReason ? (
          <div><span>销毁操作 / 原因</span><b>{item.destroyedBy || 'system'} · {item.destroyReason || '—'}</b></div>
        ) : null}
        {item.taskId ? (
          <div>
            <span>关联任务</span>
            <button type="button" className="dash-link logs-mono" onClick={() => onJumpTask(item.taskId!)}>
              跳转到 Trace ({item.taskId})
            </button>
          </div>
        ) : null}
      </div>

      {item.events && item.events.length > 0 ? (
        <div className="dash-sub-block">
          <h4>沙箱生命周期审计事件 ({item.events.length})</h4>
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">时间</th>
                  <th scope="col">动作</th>
                  <th scope="col">状态</th>
                  <th scope="col">摘要</th>
                </tr>
              </thead>
              <tbody>
                {item.events.map((ev) => (
                  <tr key={ev.id}>
                    <td className="dash-nowrap">{fmtTime(ev.createdAt)}</td>
                    <td className="logs-mono">{ev.action}</td>
                    <td><span className={`dash-pill ${ev.ok ? 'ok' : 'err'}`}>{ev.ok ? 'OK' : ev.level}</span></td>
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
