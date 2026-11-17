import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type {
  AdminCustomQuota,
  AdminFeedbackItem,
  AdminFeedbackStatus,
  AdminOverview,
  DashboardData,
} from '../api/types';
import { confirmDialog } from './ConfirmDialog';
import { Modal } from './Modal';
import { Select } from './Select';
import { fmtTime } from '../lib/format';
import { toast } from '../lib/store';
import { fmtCompact, fmtNum } from './DashboardCharts';

const FEEDBACK_STATUS_LABELS: Record<AdminFeedbackStatus, string> = {
  open: '待处理',
  in_progress: '跟进中',
  resolved: '已解决',
  ignored: '已忽略',
};

const AUDIT_ACTION_LABELS: Record<string, string> = {
  QUOTA_UPDATE: '调整配额',
  QUOTA_RESET: '恢复默认配额',
  APP_UNPUBLISH: '强行下线站点',
  APP_RESTORE: '恢复已删应用',
  APP_DELETE: '下架删除应用',
  TASK_CANCEL: '强停任务',
  TASK_RETRY: '重跑失败任务',
  TEMPLATE_FEATURE: '设置精选置顶',
  TEMPLATE_DELETE: '下架社区作品',
  FEEDBACK_TRIAGE: '分诊用户反馈',
  RATE_LIMIT_CLEAR: '清理限流窗口',
  SANDBOX_DESTROY: '销毁沙箱实例',
  SANDBOX_REAP: '幽灵沙箱对账清理',
  RUNTIME_LOG_VIEW: '查看服务运行日志',
  RUNNER_LOG_DOWNLOAD: '下载 Runner 日志',
};

export interface QuotaModalTarget {
  workspaceId: string;
  ownerName?: string | null;
  custom?: AdminCustomQuota | null;
}

export function AdminGovernancePanel({
  overview,
  loading,
  error,
  onRetry,
  dashboard,
  onOpenQuotaModal,
}: {
  overview: AdminOverview | undefined;
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  dashboard: DashboardData;
  onOpenQuotaModal: (target: QuotaModalTarget) => void;
}) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [fbFilter, setFbFilter] = useState<'all' | AdminFeedbackStatus>('all');
  const [triageTarget, setTriageTarget] = useState<AdminFeedbackItem | null>(null);
  const [triageStatus, setTriageStatus] = useState<AdminFeedbackStatus>('in_progress');
  const [triageNote, setTriageNote] = useState('');
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [quotaPickWs, setQuotaPickWs] = useState('');

  const invalidateAll = async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: qk.adminOverview }),
      qc.invalidateQueries({ queryKey: ['dashboard'] }),
    ]);
  };

  const runAction = async (key: string, fn: () => Promise<void>) => {
    setBusyKey(key);
    try {
      await fn();
      await invalidateAll();
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusyKey(null);
    }
  };

  const filteredFeedbacks = useMemo(() => {
    const list = overview?.feedbacks ?? [];
    if (fbFilter === 'all') return list;
    return list.filter((f) => f.status === fbFilter);
  }, [overview?.feedbacks, fbFilter]);

  const workspaceChoices = useMemo(() => {
    const map = new Map<string, string>();
    for (const w of dashboard.workspaces) {
      map.set(w.workspaceId, w.ownerName ? `${w.ownerName} · ${w.workspaceId}` : w.workspaceId);
    }
    for (const q of overview?.customQuotas ?? []) {
      if (!map.has(q.workspaceId)) {
        map.set(q.workspaceId, q.ownerName ? `${q.ownerName} · ${q.workspaceId}` : q.workspaceId);
      }
    }
    return Array.from(map.entries());
  }, [dashboard.workspaces, overview?.customQuotas]);

  const openTriage = (fb: AdminFeedbackItem) => {
    setTriageTarget(fb);
    setTriageStatus(fb.status === 'open' ? 'in_progress' : fb.status);
    setTriageNote(fb.adminNote ?? '');
  };

  const submitTriage = async () => {
    if (!triageTarget) return;
    await runAction(`fb-${triageTarget.id}`, async () => {
      await api.adminTriageFeedback(triageTarget.id, {
        status: triageStatus,
        adminNote: triageNote.trim() || null,
      });
      toast('反馈处理状态已更新', 'ok');
      setTriageTarget(null);
    });
  };

  if (loading && !overview) {
    return (
      <section className="dash-section" aria-label="治理与运维">
        <div className="dash-panel">
          <p className="dash-empty">正在加载管理治理与审计台账…</p>
        </div>
      </section>
    );
  }

  if (error && !overview) {
    return (
      <section className="dash-section" aria-label="治理与运维">
        <div className="dash-panel">
          <p className="dash-empty">治理台账加载失败：{errorMessage(error)}</p>
          <div style={{ display: 'flex', justifyContent: 'center', marginTop: 8 }}>
            <button type="button" className="subtle-button" onClick={onRetry}>
              重试加载
            </button>
          </div>
        </div>
      </section>
    );
  }

  if (!overview) return null;

  return (
    <section className="dash-section" aria-label="治理与运维">
      <div className="dash-sec-title">
        <h2>平台治理、配额运维与操作审计</h2>
        <p>
          当前操作人 <code>{overview.currentActor}</code> · 默认配额：{overview.defaultQuota.maxApps} 应用 /{' '}
          {overview.defaultQuota.dailyTaskLimit} 次/日任务 / {overview.defaultQuota.maxConcurrentTasks} 并发
        </p>
      </div>

      {/* 顶部治理摘要卡 */}
      <div className="dash-admin-summary">
        <div className="dash-admin-stat">
          <span>自定义配额空间</span>
          <b>{fmtNum(overview.customQuotas.length)}</b>
          <small>覆盖全局默认上限</small>
        </div>
        <div className="dash-admin-stat">
          <span>待跟进用户反馈</span>
          <b className={overview.feedbackOpenCount > 0 ? 'dash-warn-num' : ''}>{fmtNum(overview.feedbackOpenCount)}</b>
          <small>累计 {fmtNum(overview.feedbacks.length)} 条上报</small>
        </div>
        <div className="dash-admin-stat">
          <span>回收站可恢复应用</span>
          <b>{fmtNum(overview.deletedApps.length)}</b>
          <small>支持一键无损恢复</small>
        </div>
        <div className="dash-admin-stat">
          <span>24h 限流拦截 (429)</span>
          <b className={(overview.rateLimitStats.rejected24h ?? overview.rateLimitStats.recentHits24h) > 0 ? 'dash-warn-num' : ''}>
            {fmtNum(overview.rateLimitStats.rejected24h ?? overview.rateLimitStats.recentHits24h)}
          </b>
          <small>
            活跃滑窗 {fmtNum(overview.rateLimitStats.activeWindowHits ?? overview.rateLimitStats.totalHits)} · 24h 放行{' '}
            {fmtNum(overview.rateLimitStats.allowed24h ?? 0)}
          </small>
        </div>
      </div>

      {/* 1. 工作空间配额覆盖 + 限流清理 */}
      <div className="dash-grid-2">
        <div className="dash-panel">
          <header className="dash-panel-head">
            <div>
              <h3>工作空间配额管理（自定义覆盖）</h3>
              <p>针对重点团队或异常空间单独调整应用数量、每日任务数与并发数上限</p>
            </div>
            <div className="dash-admin-inline-form">
              <Select
                ariaLabel="选择工作空间调整配额"
                value={quotaPickWs}
                placeholder="选择工作空间…"
                options={[
                  { value: '', label: '选择工作空间…' },
                  ...workspaceChoices.map(([wsId, label]) => ({ value: wsId, label })),
                ]}
                onChange={setQuotaPickWs}
              />
              <button
                type="button"
                className="primary-button dash-compact-btn"
                disabled={!quotaPickWs}
                onClick={() => {
                  if (!quotaPickWs) return;
                  const existing = overview.customQuotas.find((q) => q.workspaceId === quotaPickWs) ?? null;
                  const wsRow = dashboard.workspaces.find((w) => w.workspaceId === quotaPickWs);
                  onOpenQuotaModal({
                    workspaceId: quotaPickWs,
                    ownerName: existing?.ownerName ?? wsRow?.ownerName,
                    custom: existing,
                  });
                }}
              >
                调整配额
              </button>
            </div>
          </header>
          {overview.customQuotas.length === 0 ? (
            <p className="dash-empty">当前所有工作空间均使用全局默认配额，可从上方下拉或空间排行行内点击“配额”设置</p>
          ) : (
            <div className="dash-table-wrap">
              <table className="dash-table">
                <thead>
                  <tr>
                    <th scope="col">空间 / 归属人</th>
                    <th scope="col">应用上限 (已用)</th>
                    <th scope="col">日任务上限 (今日)</th>
                    <th scope="col">并发 (活跃)</th>
                    <th scope="col">备注 / 操作</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.customQuotas.map((q) => (
                    <tr key={q.workspaceId}>
                      <td>
                        <code>{q.workspaceId}</code>
                        {q.ownerName ? <span className="dash-tag" style={{ marginLeft: 6 }}>{q.ownerName}</span> : null}
                      </td>
                      <td>
                        <b>{q.maxApps ?? overview.defaultQuota.maxApps}</b>{' '}
                        <span className="dash-muted">({q.appCount})</span>
                      </td>
                      <td>
                        <b>{q.dailyTaskLimit ?? overview.defaultQuota.dailyTaskLimit}</b>{' '}
                        <span className="dash-muted">({q.tasksToday})</span>
                      </td>
                      <td>
                        <b>{q.maxConcurrentTasks ?? overview.defaultQuota.maxConcurrentTasks}</b>{' '}
                        <span className="dash-muted">({q.activeTasks})</span>
                      </td>
                      <td>
                        <div className="dash-action-row">
                          {q.note ? <span className="dash-muted" title={q.note}>{q.note}</span> : null}
                          <button
                            type="button"
                            className="dash-link"
                            onClick={() =>
                              onOpenQuotaModal({
                                workspaceId: q.workspaceId,
                                ownerName: q.ownerName,
                                custom: q,
                              })
                            }
                          >
                            编辑
                          </button>
                          <button
                            type="button"
                            className="dash-link danger"
                            disabled={busyKey === `qreset-${q.workspaceId}`}
                            onClick={async () => {
                              const ok = await confirmDialog({
                                title: '恢复默认配额',
                                message: `确认取消 ${q.ownerName || q.workspaceId} 的自定义配额，恢复系统默认上限吗？`,
                                confirmLabel: '恢复默认',
                              });
                              if (!ok) return;
                              await runAction(`qreset-${q.workspaceId}`, async () => {
                                await api.adminUpdateQuota(q.workspaceId, { reset: true });
                                toast('已恢复默认配额', 'ok');
                              });
                            }}
                          >
                            恢复默认
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        <div className="dash-panel">
          <header className="dash-panel-head">
            <div>
              <h3>限流窗口监控与应急清理</h3>
              <p>触发过平台速率限制的 bucket 列表，误限流时可一键解除限制窗口</p>
            </div>
            <button
              type="button"
              className="subtle-button dash-compact-btn"
              disabled={busyKey === 'rl-all' || overview.rateLimitStats.totalHits === 0}
              onClick={async () => {
                const ok = await confirmDialog({
                  title: '清空全部限流记录',
                  message: `当前共有 ${overview.rateLimitStats.totalHits} 条限流窗口记录，确认全部清理吗？`,
                  confirmLabel: '全部清理',
                  danger: true,
                });
                if (!ok) return;
                await runAction('rl-all', async () => {
                  const res = await api.adminClearRateLimits();
                  toast(`已清理 ${res.clearedCount} 条限流记录`, 'ok');
                });
              }}
            >
              清空全部 ({fmtNum(overview.rateLimitStats.totalHits)})
            </button>
          </header>
          {overview.rateLimitStats.buckets.length === 0 ? (
            <p className="dash-empty">当前没有触发过速率限制的 bucket</p>
          ) : (
            <div className="dash-table-wrap">
              <table className="dash-table">
                <thead>
                  <tr>
                    <th scope="col">限流 Bucket</th>
                    <th scope="col">活跃滑窗</th>
                    <th scope="col">24h 拦截 (429)</th>
                    <th scope="col">24h 放行</th>
                    <th scope="col">最近触发</th>
                    <th scope="col">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.rateLimitStats.buckets.map((b) => {
                    const activeWin = b.activeHits ?? b.totalHits;
                    const rej24 = b.rejected24h ?? b.hits24h;
                    const allow24 = b.allowed24h ?? 0;
                    return (
                      <tr key={b.bucket}>
                        <td><code>{b.bucket}</code></td>
                        <td>{fmtNum(activeWin)}</td>
                        <td><b className={rej24 > 0 ? 'dash-err' : ''}>{fmtNum(rej24)}</b></td>
                        <td>{fmtNum(allow24)}</td>
                        <td className="dash-nowrap">{fmtTime(b.lastHitAt)}</td>
                        <td>
                          <button
                            type="button"
                            className="dash-link danger"
                            disabled={busyKey === `rl-${b.bucket}` || activeWin === 0}
                            onClick={async () => {
                              await runAction(`rl-${b.bucket}`, async () => {
                                const res = await api.adminClearRateLimits(b.bucket);
                                toast(`已清理 ${b.bucket} 活跃滑窗（${res.clearedCount} 条）`, 'ok');
                              });
                            }}
                          >
                            解除清理
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* 2. 运行中任务队列干预 + 已删除应用回收站 */}
      <div className="dash-grid-2">
        <div className="dash-panel">
          <header className="dash-panel-head compact">
            <div>
              <h3>运行中 / 排队任务运维干预（{dashboard.tasks.activeList.length} 个）</h3>
              <p>异常卡死或死循环任务可在此查看过程日志或直接强行停止</p>
            </div>
          </header>
          {dashboard.tasks.activeList.length === 0 ? (
            <p className="dash-empty">当前全平台没有排队或运行中的任务</p>
          ) : (
            <div className="dash-table-wrap">
              <table className="dash-table">
                <thead>
                  <tr>
                    <th scope="col">应用 / 任务 ID</th>
                    <th scope="col">状态 / 进度</th>
                    <th scope="col">空间</th>
                    <th scope="col">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {dashboard.tasks.activeList.map((t) => (
                    <tr key={t.id}>
                      <td>
                        <b>{t.appName}</b>
                        <div><code>{t.id}</code></div>
                      </td>
                      <td>
                        <span className="dash-pill cloud">{t.status} · {t.progress}%</span>
                      </td>
                      <td><code>{t.workspaceId}</code></td>
                      <td>
                        <div className="dash-action-row">
                          <button
                            type="button"
                            className="dash-link"
                            onClick={() => navigate('/logs?taskId=' + encodeURIComponent(t.id))}
                          >
                            日志
                          </button>
                          <button
                            type="button"
                            className="dash-link danger"
                            disabled={busyKey === `cancel-${t.id}`}
                            onClick={async () => {
                              const ok = await confirmDialog({
                                title: '强行停止任务',
                                message: `确认强行停止「${t.appName}」的任务 ${t.id} 吗？`,
                                confirmLabel: '强行停止',
                                danger: true,
                              });
                              if (!ok) return;
                              await runAction(`cancel-${t.id}`, async () => {
                                await api.adminCancelTask(t.id);
                                toast('已发送停止指令', 'ok');
                              });
                            }}
                          >
                            强停
                          </button>
                        </div>
                      </td>
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
              <h3>已删除应用回收站（{overview.deletedApps.length} 个）</h3>
              <p>支持跨空间一键恢复用户误删除的应用及其历史版本</p>
            </div>
          </header>
          {overview.deletedApps.length === 0 ? (
            <p className="dash-empty">回收站当前为空</p>
          ) : (
            <div className="dash-scroll-list">
              <div className="dash-table-wrap">
                <table className="dash-table">
                  <thead>
                    <tr>
                      <th scope="col">应用名称</th>
                      <th scope="col">归属人 / 空间</th>
                      <th scope="col">版本</th>
                      <th scope="col">删除时间</th>
                      <th scope="col">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {overview.deletedApps.map((a) => (
                      <tr key={a.id}>
                        <td>
                          <b>{a.name}</b>
                          <div><span className="dash-tag">{a.type}</span></div>
                        </td>
                        <td>
                          <span>{a.ownerName}</span>
                          <div><code>{a.workspaceId}</code></div>
                        </td>
                        <td>{fmtNum(a.versionCount)}</td>
                        <td className="dash-nowrap">{fmtTime(a.deletedAt)}</td>
                        <td>
                          <button
                            type="button"
                            className="dash-link"
                            disabled={busyKey === `restore-${a.id}`}
                            onClick={async () => {
                              const ok = await confirmDialog({
                                title: '从回收站恢复应用',
                                message: `确认恢复「${a.name}」回原工作空间（${a.ownerName}）吗？`,
                                confirmLabel: '确认恢复',
                              });
                              if (!ok) return;
                              await runAction(`restore-${a.id}`, async () => {
                                await api.adminRestoreApp(a.id);
                                toast(`已恢复应用「${a.name}」`, 'ok');
                              });
                            }}
                          >
                            一键恢复
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

      {/* 3. 用户反馈分诊台 + 灵感社区内容治理 */}
      <div className="dash-grid-2">
        <div className="dash-panel">
          <header className="dash-panel-head">
            <div>
              <h3>用户问题反馈分诊台（{filteredFeedbacks.length} 条）</h3>
              <p>跟踪小织问题上报处理状态、指派解决说明并自动闭环计时</p>
            </div>
            <div className="dash-metric-pills" role="group" aria-label="反馈状态筛选">
              {(
                [
                  ['all', '全部'],
                  ['open', '待处理'],
                  ['in_progress', '跟进中'],
                  ['resolved', '已解决'],
                  ['ignored', '已忽略'],
                ] as const
              ).map(([st, label]) => (
                <button
                  key={st}
                  type="button"
                  className={fbFilter === st ? 'active' : ''}
                  onClick={() => setFbFilter(st)}
                >
                  {label}
                </button>
              ))}
            </div>
          </header>
          {filteredFeedbacks.length === 0 ? (
            <p className="dash-empty">当前筛选状态下暂无用户反馈</p>
          ) : (
            <div className="dash-scroll-list">
              <ul className="dash-admin-fb-list">
                {filteredFeedbacks.map((fb) => (
                  <li key={fb.id}>
                    <div className="dash-admin-fb-head">
                      <span className={`dash-pill ${fb.status === 'resolved' ? 'ok' : fb.status === 'open' ? 'err' : 'cloud'}`}>
                        {FEEDBACK_STATUS_LABELS[fb.status]}
                      </span>
                      <span className="dash-muted">
                        {fb.ownerName} · <code>{fb.workspaceId}</code> · {fmtTime(fb.createdAt)}
                        {fb.assetCount > 0 ? ` · 含 ${fb.assetCount} 个附件` : ''}
                      </span>
                      <button type="button" className="dash-link" onClick={() => openTriage(fb)}>
                        处理 / 备注
                      </button>
                    </div>
                    <p>{fb.content}</p>
                    {fb.adminNote ? (
                      <div className="dash-admin-fb-note">
                        管理员备注：{fb.adminNote}
                        {fb.resolvedAt ? `（${fmtTime(fb.resolvedAt)}）` : ''}
                      </div>
                    ) : null}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        <div className="dash-panel">
          <header className="dash-panel-head compact">
            <div>
              <h3>灵感社区内容治理（精选置顶与违规下架）</h3>
              <p>设置精选置顶后将在灵感社区默认列表中优先排序展示</p>
            </div>
          </header>
          {overview.templates.length === 0 ? (
            <p className="dash-empty">灵感社区当前暂无作品</p>
          ) : (
            <div className="dash-scroll-list">
              <div className="dash-table-wrap">
                <table className="dash-table">
                  <thead>
                    <tr>
                      <th scope="col">作品名称 / 作者</th>
                      <th scope="col">浏览 / 点赞 / 复刻</th>
                      <th scope="col">精选权重</th>
                      <th scope="col">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {overview.templates.map((tpl) => (
                      <tr key={tpl.id}>
                        <td>
                          <b>{tpl.name}</b>
                          <div>
                            <span className="dash-tag">{tpl.type}</span>{' '}
                            <span className="dash-muted">{tpl.author || tpl.workspaceId}</span>
                          </div>
                        </td>
                        <td>
                          {fmtCompact(tpl.viewCount)} / {fmtNum(tpl.likeCount)} / <b>{fmtNum(tpl.cloneCount)}</b>
                        </td>
                        <td>
                          {tpl.featured ? (
                            <span className="dash-pill ok">精选 #{tpl.pinnedOrder}</span>
                          ) : (
                            <span className="dash-muted">普通</span>
                          )}
                        </td>
                        <td>
                          <div className="dash-action-row">
                            <button
                              type="button"
                              className="dash-link"
                              disabled={busyKey === `tpl-feat-${tpl.id}`}
                              onClick={async () => {
                                await runAction(`tpl-feat-${tpl.id}`, async () => {
                                  await api.adminFeatureTemplate(tpl.id, {
                                    featured: !tpl.featured,
                                    pinnedOrder: tpl.featured ? 0 : 10,
                                  });
                                  toast(tpl.featured ? '已取消精选置顶' : '已设为社区精选置顶', 'ok');
                                });
                              }}
                            >
                              {tpl.featured ? '取消置顶' : '精选置顶'}
                            </button>
                            <button
                              type="button"
                              className="dash-link danger"
                              disabled={busyKey === `tpl-del-${tpl.id}`}
                              onClick={async () => {
                                const ok = await confirmDialog({
                                  title: '下架社区作品',
                                  message: `确认从灵感社区永久移除作品「${tpl.name}」吗？此操作将清理作品快照和压缩包。`,
                                  confirmLabel: '确认下架',
                                  danger: true,
                                });
                                if (!ok) return;
                                await runAction(`tpl-del-${tpl.id}`, async () => {
                                  await api.adminDeleteTemplate(tpl.id);
                                  toast(`已下架社区作品「${tpl.name}」`, 'ok');
                                });
                              }}
                            >
                              下架
                            </button>
                          </div>
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

      {/* 4. 管理操作审计日志 */}
      <div className="dash-panel">
        <header className="dash-panel-head compact">
          <div>
            <h3>管理操作审计日志（最近 {overview.auditLogs.length} 条）</h3>
            <p>所有配额变更、站点下线、应用恢复/删除、任务强停/重跑、社区治理、沙箱销毁与运维下载均已操作审计落库留痕</p>
          </div>
        </header>
        {overview.auditLogs.length === 0 ? (
          <p className="dash-empty">暂无管理操作审计记录</p>
        ) : (
          <div className="dash-scroll-list">
            <div className="dash-table-wrap">
              <table className="dash-table">
                <thead>
                  <tr>
                    <th scope="col">时间</th>
                    <th scope="col">操作人</th>
                    <th scope="col">动作</th>
                    <th scope="col">目标对象</th>
                    <th scope="col">变更详情</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.auditLogs.map((log) => (
                    <tr key={log.id}>
                      <td className="dash-nowrap">{fmtTime(log.createdAt)}</td>
                      <td>{log.actor}</td>
                      <td>
                        <span className="dash-tag">{AUDIT_ACTION_LABELS[log.action] || log.action}</span>
                      </td>
                      <td>
                        <span className="dash-muted">{log.targetType}</span> <code>{log.targetId}</code>
                      </td>
                      <td>
                        <code className="dash-audit-json">{JSON.stringify(log.detail)}</code>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>

      {/* 反馈分诊弹窗 */}
      {triageTarget ? (
        <Modal
          title="处理用户反馈"
          overline="FEEDBACK TRIAGE"
          onClose={() => setTriageTarget(null)}
          footer={
            <>
              <button type="button" className="subtle-button" onClick={() => setTriageTarget(null)}>
                取消
              </button>
              <button
                type="button"
                className="primary-button"
                disabled={busyKey === `fb-${triageTarget.id}`}
                onClick={() => void submitTriage()}
              >
                保存处理结果
              </button>
            </>
          }
        >
          <div className="dash-modal-form">
            <p className="dash-modal-quote">{triageTarget.content}</p>
            <label>
              <span>处理状态</span>
              <Select
                ariaLabel="处理状态"
                className="field"
                value={triageStatus}
                options={[
                  { value: 'open', label: '待处理 (open)' },
                  { value: 'in_progress', label: '跟进中 (in_progress)' },
                  { value: 'resolved', label: '已解决 (resolved)' },
                  { value: 'ignored', label: '已忽略 (ignored)' },
                ]}
                onChange={(v) => setTriageStatus(v as AdminFeedbackStatus)}
              />
            </label>
            <label>
              <span>管理员备注 / 解决说明</span>
              <textarea
                rows={3}
                maxLength={500}
                placeholder="填写排查结论、关联版本或跟进记录…"
                value={triageNote}
                onChange={(e) => setTriageNote(e.target.value)}
              />
            </label>
          </div>
        </Modal>
      ) : null}
    </section>
  );
}

export function AdminQuotaModal({
  target,
  defaultQuota,
  onClose,
  onSaved,
}: {
  target: QuotaModalTarget;
  defaultQuota: AdminOverview['defaultQuota'];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [maxApps, setMaxApps] = useState(
    target.custom?.maxApps != null ? String(target.custom.maxApps) : String(defaultQuota.maxApps),
  );
  const [dailyTaskLimit, setDailyTaskLimit] = useState(
    target.custom?.dailyTaskLimit != null
      ? String(target.custom.dailyTaskLimit)
      : String(defaultQuota.dailyTaskLimit),
  );
  const [maxConcurrentTasks, setMaxConcurrentTasks] = useState(
    target.custom?.maxConcurrentTasks != null
      ? String(target.custom.maxConcurrentTasks)
      : String(defaultQuota.maxConcurrentTasks),
  );
  const [note, setNote] = useState(target.custom?.note ?? '');
  const [saving, setSaving] = useState(false);

  const submit = async () => {
    const ma = Number(maxApps);
    const dt = Number(dailyTaskLimit);
    const mc = Number(maxConcurrentTasks);
    if (!Number.isInteger(ma) || ma < 1 || ma > 500) {
      toast('应用数量上限需要是 1～500 的整数', 'error');
      return;
    }
    if (!Number.isInteger(dt) || dt < 1 || dt > 5000) {
      toast('每日任务上限需要是 1～5000 的整数', 'error');
      return;
    }
    if (!Number.isInteger(mc) || mc < 1 || mc > 50) {
      toast('同时运行任务上限需要是 1～50 的整数', 'error');
      return;
    }
    setSaving(true);
    try {
      await api.adminUpdateQuota(target.workspaceId, {
        maxApps: ma,
        dailyTaskLimit: dt,
        maxConcurrentTasks: mc,
        note: note.trim() || undefined,
      });
      await onSaved();
      toast('工作空间配额已更新并生效', 'ok');
      onClose();
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title="调整工作空间配额"
      overline="WORKSPACE QUOTA"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="subtle-button" onClick={onClose}>
            取消
          </button>
          <button type="button" className="primary-button" disabled={saving} onClick={() => void submit()}>
            {saving ? '保存中…' : '保存配额'}
          </button>
        </>
      }
    >
      <div className="dash-modal-form">
        <div className="dash-modal-target">
          工作空间：<code>{target.workspaceId}</code>
          {target.ownerName ? `（${target.ownerName}）` : ''}
        </div>
        <div className="dash-modal-grid3">
          <label>
            <span>应用数量上限</span>
            <input type="number" min={1} max={500} value={maxApps} onChange={(e) => setMaxApps(e.target.value)} />
          </label>
          <label>
            <span>每日任务上限</span>
            <input
              type="number"
              min={1}
              max={5000}
              value={dailyTaskLimit}
              onChange={(e) => setDailyTaskLimit(e.target.value)}
            />
          </label>
          <label>
            <span>同时并发任务</span>
            <input
              type="number"
              min={1}
              max={50}
              value={maxConcurrentTasks}
              onChange={(e) => setMaxConcurrentTasks(e.target.value)}
            />
          </label>
        </div>
        <label>
          <span>调整说明 / 工单备注</span>
          <input
            type="text"
            maxLength={200}
            placeholder="如：专项活动临时扩容至 30 个应用"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </label>
      </div>
    </Modal>
  );
}
