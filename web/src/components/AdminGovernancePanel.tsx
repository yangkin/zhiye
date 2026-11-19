import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type {
  AdminAppItem,
  AdminCustomQuota,
  AdminFeedbackItem,
  AdminFeedbackStatus,
  AdminOverview,
  DashboardData,
} from '../api/types';
import { confirmDialog } from './ConfirmDialog';
import { Modal } from './Modal';
import { Pagination, useClientPagination } from './Pagination';
import { Select } from './Select';
import { Icon, type IconName } from '../lib/icons';
import { fmtTime } from '../lib/format';
import { toast } from '../lib/store';
import { DashEmptyBox, DashPanelHead, fmtCompact, fmtNum } from './DashboardCharts';

export type GovTab = 'handover' | 'sites' | 'feedback' | 'trash' | 'ops';

function OwnerBadge({ ownerName, workspaceId }: { ownerName?: string | null; workspaceId: string }) {
  const cleanName = ownerName?.trim();
  const hasRealName = Boolean(cleanName && cleanName !== workspaceId);
  return hasRealName ? (
    <>
      <span>{cleanName}</span>
      <div><code>{workspaceId}</code></div>
    </>
  ) : (
    <code>{workspaceId}</code>
  );
}

/** 紧凑单行健康状态条：当运维/风控项为空时替代笨重的 160px 大虚线框 */
function GovHealthyBanner({
  icon,
  tone = 'ok',
  title,
  desc,
  action,
}: {
  icon: IconName;
  tone?: 'ok' | 'muted';
  title: string;
  desc?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className={`gov-healthy-banner ${tone}`}>
      <span className="gov-healthy-icon" aria-hidden="true">
        <Icon name={icon} />
      </span>
      <div className="gov-healthy-copy">
        <b>{title}</b>
        {desc ? <span>{desc}</span> : null}
      </div>
      {action ? <div className="gov-healthy-action">{action}</div> : null}
    </div>
  );
}

const FEEDBACK_STATUS_LABELS: Record<AdminFeedbackStatus, string> = {
  open: '待处理',
  in_progress: '跟进中',
  resolved: '已解决',
  ignored: '已忽略',
};

const SITE_ACCESS_LABELS: Record<string, string> = {
  public: '内网公开',
  login: '员工登录',
  members: '指定成员',
};

const AUDIT_ACTION_LABELS: Record<string, string> = {
  APP_TRANSFER: '转移应用归属',
  WORKSPACE_HANDOVER: '离职/空间批量交接',
  QUOTA_UPDATE: '调整配额',
  QUOTA_RESET: '恢复默认配额',
  ADMIN_ROLE_UPDATE: '变更管理员角色',
  SITE_ACCESS_UPDATE: '调整站点访问权限',
  APP_UNPUBLISH: '强行下线站点',
  APP_RESTORE: '恢复已删应用',
  APP_DELETE: '下架删除应用',
  APP_PURGE: '彻底清除应用',
  TASK_CANCEL: '强停任务',
  TASK_RETRY: '重跑失败任务',
  TASK_LEASE_RECLAIM: '回收超时租约',
  TEMPLATE_FEATURE: '设置精选置顶',
  TEMPLATE_DELETE: '下架社区作品',
  FEEDBACK_TRIAGE: '分诊用户反馈',
  RATE_LIMIT_CLEAR: '清理限流窗口',
  SANDBOX_DESTROY: '销毁沙箱实例',
  SANDBOX_REAP: '幽灵沙箱对账清理',
  CLUSTER_NODE_PURGE: '清理离线集群节点',
  RUNTIME_LOG_VIEW: '查看服务运行日志',
  RUNNER_LOG_DOWNLOAD: '下载 Runner 日志',
};

export interface QuotaModalTarget {
  workspaceId: string;
  ownerName?: string | null;
  custom?: AdminCustomQuota | null;
}

export interface TransferAppTarget {
  id: string;
  name: string;
  slug: string;
  workspaceId: string;
  ownerName?: string | null;
}

export function AdminGovernancePanel({
  overview,
  loading,
  error,
  onRetry,
  dashboard,
  onOpenQuotaModal,
  standalone = false,
}: {
  overview: AdminOverview | undefined;
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  dashboard?: DashboardData;
  onOpenQuotaModal: (target: QuotaModalTarget) => void;
  standalone?: boolean;
}) {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const qc = useQueryClient();

  const urlTab = searchParams.get('tab') as GovTab | null;
  const [govTab, setGovTabState] = useState<GovTab>(
    urlTab && ['handover', 'sites', 'feedback', 'trash', 'ops'].includes(urlTab) ? urlTab : 'handover',
  );

  const setGovTab = (next: GovTab) => {
    setGovTabState(next);
    if (standalone) {
      const sp = new URLSearchParams(searchParams);
      sp.set('tab', next);
      setSearchParams(sp, { replace: true });
    }
  };

  // 离职 / 批量交接状态
  const [handoverFromWs, setHandoverFromWs] = useState(searchParams.get('fromWs') ?? '');
  const [handoverToWs, setHandoverToWs] = useState('');
  const [handoverIncludeConv, setHandoverIncludeConv] = useState(true);
  const [handoverIncludeDs, setHandoverIncludeDs] = useState(true);
  const [handoverIncludeTrash, setHandoverIncludeTrash] = useState(false);
  const [handoverNote, setHandoverNote] = useState('');
  const [handoverPartial, setHandoverPartial] = useState(false);
  const [handoverSelectedApps, setHandoverSelectedApps] = useState<string[]>([]);

  useEffect(() => {
    const fw = searchParams.get('fromWs');
    if (fw) {
      setHandoverFromWs(fw);
      setGovTabState('handover');
    }
  }, [searchParams]);

  // 单应用转移弹窗状态
  const [transferTarget, setTransferTarget] = useState<TransferAppTarget | null>(null);
  const [transferToWs, setTransferToWs] = useState('');
  const [transferIncludeConv, setTransferIncludeConv] = useState(true);
  const [transferNote, setTransferNote] = useState('');

  // 全平台应用台账过滤
  const [appLedgerQuery, setAppLedgerQuery] = useState('');
  const [appLedgerStatus, setAppLedgerStatus] = useState<'all' | 'published' | 'draft'>('all');
  const [appLedgerWs, setAppLedgerWs] = useState('');

  const [fbFilter, setFbFilter] = useState<'all' | AdminFeedbackStatus>('all');
  const [triageTarget, setTriageTarget] = useState<AdminFeedbackItem | null>(null);
  const [triageStatus, setTriageStatus] = useState<AdminFeedbackStatus>('in_progress');
  const [triageNote, setTriageNote] = useState('');
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [quotaPickWs, setQuotaPickWs] = useState('');
  const [userQuery, setUserQuery] = useState('');
  const [userRoleFilter, setUserRoleFilter] = useState<'all' | 'admin' | 'user'>('all');
  const [siteQuery, setSiteQuery] = useState('');
  const [siteLevelFilter, setSiteLevelFilter] = useState<'all' | 'public' | 'login' | 'members'>('all');
  const [tplQuery, setTplQuery] = useState('');
  const [auditQuery, setAuditQuery] = useState('');
  const [auditActionFilter, setAuditActionFilter] = useState('all');
  const [trashQuery, setTrashQuery] = useState('');

  const invalidateAll = async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: qk.adminOverview }),
      qc.invalidateQueries({ queryKey: ['dashboard'] }),
      qc.invalidateQueries({ queryKey: ['apps'] }),
      qc.invalidateQueries({ queryKey: ['history'] }),
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

  // 合并所有可选工作空间（优先使用 overview.workspaces，兜底合并 dashboard.workspaces / allApps / customQuotas）
  const workspaceOptions = useMemo(() => {
    const map = new Map<
      string,
      {
        workspaceId: string;
        ownerName: string;
        appCount: number;
        publishedCount: number;
        deletedAppCount: number;
        conversationCount: number;
        dataSourceCount: number;
      }
    >();
    for (const w of overview?.workspaces ?? []) {
      map.set(w.workspaceId, {
        workspaceId: w.workspaceId,
        ownerName: w.ownerName || w.workspaceId,
        appCount: w.appCount,
        publishedCount: w.publishedCount,
        deletedAppCount: w.deletedAppCount,
        conversationCount: w.conversationCount,
        dataSourceCount: w.dataSourceCount,
      });
    }
    for (const w of dashboard?.workspaces ?? []) {
      if (!map.has(w.workspaceId)) {
        map.set(w.workspaceId, {
          workspaceId: w.workspaceId,
          ownerName: w.ownerName || w.workspaceId,
          appCount: w.apps,
          publishedCount: w.published,
          deletedAppCount: 0,
          conversationCount: 0,
          dataSourceCount: 0,
        });
      }
    }
    for (const a of overview?.allApps ?? []) {
      if (!map.has(a.workspaceId)) {
        map.set(a.workspaceId, {
          workspaceId: a.workspaceId,
          ownerName: a.ownerName || a.workspaceId,
          appCount: 1,
          publishedCount: a.published ? 1 : 0,
          deletedAppCount: 0,
          conversationCount: 0,
          dataSourceCount: 0,
        });
      }
    }
    return Array.from(map.values());
  }, [overview?.workspaces, overview?.allApps, dashboard?.workspaces]);

  const formatWsOptionLabel = (w: { workspaceId: string; ownerName: string; appCount: number; publishedCount: number }) => {
    const hasName = w.ownerName && w.ownerName !== w.workspaceId;
    const prefix = hasName ? `${w.ownerName} (${w.workspaceId})` : w.workspaceId;
    return `${prefix} · ${w.appCount} 应用 / ${w.publishedCount} 线上`;
  };

  // 源空间下的应用列表（用于批量交接预览与勾选）
  const sourceWorkspaceInfo = useMemo(
    () => workspaceOptions.find((w) => w.workspaceId === handoverFromWs) ?? null,
    [workspaceOptions, handoverFromWs],
  );

  const sourceWorkspaceApps = useMemo(
    () => (overview?.allApps ?? []).filter((a) => a.workspaceId === handoverFromWs),
    [overview?.allApps, handoverFromWs],
  );

  useEffect(() => {
    setHandoverSelectedApps(sourceWorkspaceApps.map((a) => a.id));
    setHandoverPartial(false);
  }, [handoverFromWs, sourceWorkspaceApps.length]);

  const filteredAllApps = useMemo(() => {
    const list = overview?.allApps ?? [];
    const q = appLedgerQuery.trim().toLowerCase();
    return list.filter((a) => {
      if (appLedgerStatus === 'published' && !a.published) return false;
      if (appLedgerStatus === 'draft' && a.published) return false;
      if (appLedgerWs && a.workspaceId !== appLedgerWs) return false;
      if (!q) return true;
      return (
        a.name.toLowerCase().includes(q) ||
        a.slug.toLowerCase().includes(q) ||
        a.workspaceId.toLowerCase().includes(q) ||
        (a.ownerName ?? '').toLowerCase().includes(q) ||
        a.type.toLowerCase().includes(q)
      );
    });
  }, [overview?.allApps, appLedgerQuery, appLedgerStatus, appLedgerWs]);

  const filteredFeedbacks = useMemo(() => {
    const list = overview?.feedbacks ?? [];
    if (fbFilter === 'all') return list;
    return list.filter((f) => f.status === fbFilter);
  }, [overview?.feedbacks, fbFilter]);

  const filteredTemplates = useMemo(() => {
    const list = overview?.templates ?? [];
    const q = tplQuery.trim().toLowerCase();
    if (!q) return list;
    return list.filter((t) => `${t.name} ${t.type} ${t.author ?? ''} ${t.workspaceId}`.toLowerCase().includes(q));
  }, [overview?.templates, tplQuery]);

  const filteredUsers = useMemo(() => {
    const list = overview?.users ?? [];
    const q = userQuery.trim().toLowerCase();
    return list.filter((u) => {
      if (userRoleFilter !== 'all' && u.role !== userRoleFilter) return false;
      if (!q) return true;
      return (
        u.loginName.toLowerCase().includes(q) ||
        u.realName.toLowerCase().includes(q) ||
        (u.email ?? '').toLowerCase().includes(q) ||
        u.id.toLowerCase().includes(q)
      );
    });
  }, [overview?.users, userQuery, userRoleFilter]);

  const filteredPublishedApps = useMemo(() => {
    const list = overview?.publishedApps ?? [];
    const q = siteQuery.trim().toLowerCase();
    return list.filter((a) => {
      if (siteLevelFilter !== 'all' && a.accessLevel !== siteLevelFilter) return false;
      if (!q) return true;
      return (
        a.name.toLowerCase().includes(q) ||
        a.slug.toLowerCase().includes(q) ||
        a.workspaceId.toLowerCase().includes(q) ||
        a.ownerName.toLowerCase().includes(q)
      );
    });
  }, [overview?.publishedApps, siteQuery, siteLevelFilter]);

  const auditActionOptions = useMemo(() => {
    const counts = new Map<string, number>();
    for (const row of overview?.auditLogs ?? []) {
      counts.set(row.action, (counts.get(row.action) ?? 0) + 1);
    }
    return [
      { value: 'all', label: `全部动作 (${overview?.auditLogs.length ?? 0})` },
      ...Array.from(counts.entries()).map(([act, cnt]) => ({
        value: act,
        label: `${AUDIT_ACTION_LABELS[act] || act} (${cnt})`,
      })),
    ];
  }, [overview?.auditLogs]);

  const filteredAuditLogs = useMemo(() => {
    const list = overview?.auditLogs ?? [];
    const q = auditQuery.trim().toLowerCase();
    return list.filter((log) => {
      if (auditActionFilter !== 'all' && log.action !== auditActionFilter) return false;
      if (!q) return true;
      const hay = `${log.actor} ${log.action} ${AUDIT_ACTION_LABELS[log.action] || ''} ${log.targetType} ${log.targetId} ${JSON.stringify(log.detail)}`.toLowerCase();
      return hay.includes(q);
    });
  }, [overview?.auditLogs, auditQuery, auditActionFilter]);

  const exportAuditCsv = () => {
    const rows = filteredAuditLogs.map((l) => [
      l.createdAt,
      `"${(l.actor || '').replace(/"/g, '""')}"`,
      AUDIT_ACTION_LABELS[l.action] || l.action,
      l.targetType,
      `"${(l.targetId || '').replace(/"/g, '""')}"`,
      `"${JSON.stringify(l.detail ?? {}).replace(/"/g, '""')}"`,
    ]);
    const header = ['时间', '操作人', '动作', '对象类型', '目标ID', '变更详情'];
    const csv = '\uFEFF' + [header, ...rows].map((r) => r.join(',')).join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `zhiye-admin-audit-${Date.now()}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const filteredDeletedApps = useMemo(() => {
    const list = overview?.deletedApps ?? [];
    const q = trashQuery.trim().toLowerCase();
    if (!q) return list;
    return list.filter((a) =>
      `${a.name} ${a.type} ${a.ownerName ?? ''} ${a.workspaceId}`.toLowerCase().includes(q),
    );
  }, [overview?.deletedApps, trashQuery]);

  const allAppsPager = useClientPagination(filteredAllApps, 10, `${appLedgerStatus}|${appLedgerWs}|${appLedgerQuery}`);
  const quotaPager = useClientPagination(overview?.customQuotas ?? [], 10);
  const userPager = useClientPagination(filteredUsers, 10, `${userRoleFilter}|${userQuery}`);
  const fbPager = useClientPagination(filteredFeedbacks, 8, fbFilter);
  const tplPager = useClientPagination(filteredTemplates, 10, tplQuery);
  const sitesPager = useClientPagination(filteredPublishedApps, 10, `${siteLevelFilter}|${siteQuery}`);
  const trashPager = useClientPagination(filteredDeletedApps, 10, trashQuery);
  const auditPager = useClientPagination(filteredAuditLogs, 10, `${auditActionFilter}|${auditQuery}`);

  const openTransferModal = (app: TransferAppTarget) => {
    setTransferTarget(app);
    setTransferToWs('');
    setTransferIncludeConv(true);
    setTransferNote('');
  };

  const submitSingleTransfer = async () => {
    if (!transferTarget) return;
    if (!transferToWs) {
      toast('请选择接收工作空间', 'error');
      return;
    }
    if (transferToWs === transferTarget.workspaceId) {
      toast('接收空间与当前归属空间相同', 'error');
      return;
    }
    await runAction(`transfer-${transferTarget.id}`, async () => {
      await api.adminTransferApp(transferTarget.id, {
        targetWorkspaceId: transferToWs,
        includeConversation: transferIncludeConv,
        note: transferNote.trim() || undefined,
      });
      toast(`已将应用「${transferTarget.name}」无损转移至 ${transferToWs}`, 'ok');
      setTransferTarget(null);
    });
  };

  const submitBatchHandover = async () => {
    if (!handoverFromWs || !handoverToWs) {
      toast('请选择交出空间与接收空间', 'error');
      return;
    }
    if (handoverFromWs === handoverToWs) {
      toast('交出空间与接收空间不能相同', 'error');
      return;
    }
    if (handoverPartial && handoverSelectedApps.length === 0) {
      toast('请至少勾选 1 个需要交接的应用', 'error');
      return;
    }
    const fromLabel = sourceWorkspaceInfo?.ownerName && sourceWorkspaceInfo.ownerName !== handoverFromWs
      ? `${sourceWorkspaceInfo.ownerName} (${handoverFromWs})`
      : handoverFromWs;
    const toInfo = workspaceOptions.find((w) => w.workspaceId === handoverToWs);
    const toLabel = toInfo?.ownerName && toInfo.ownerName !== handoverToWs
      ? `${toInfo.ownerName} (${handoverToWs})`
      : handoverToWs;
    const targetAppCount = handoverPartial ? handoverSelectedApps.length : (sourceWorkspaceInfo?.appCount ?? sourceWorkspaceApps.length);

    const ok = await confirmDialog({
      title: '确认执行应用资产交接',
      message: `即将把「${fromLabel}」名下的 ${targetAppCount} 个应用${handoverIncludeTrash && !handoverPartial ? '（含回收站应用）' : ''}${handoverIncludeConv ? '、关联创造会话' : ''}${handoverIncludeDs ? '及私有数据源' : ''} 无损划转给「${toLabel}」。线上已发布站点域名与云表数据不会中断。确认执行吗？`,
      confirmLabel: '确认交接',
    });
    if (!ok) return;

    await runAction('handover-batch', async () => {
      const res = await api.adminHandoverWorkspace({
        fromWorkspaceId: handoverFromWs,
        toWorkspaceId: handoverToWs,
        appIds: handoverPartial ? handoverSelectedApps : undefined,
        includeTrash: !handoverPartial && handoverIncludeTrash,
        includeConversations: handoverIncludeConv,
        includeDataSources: handoverIncludeDs,
        note: handoverNote.trim() || undefined,
      });
      toast(
        `交接完成：已转移 ${res.transferredAppsCount} 个应用` +
          (res.movedConversations > 0 ? `、${res.movedConversations} 段会话` : '') +
          (res.movedDataSources > 0 ? `、${res.movedDataSources} 个数据源` : ''),
        'ok',
      );
      setHandoverNote('');
    });
  };

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
          <DashEmptyBox icon="settings" title="正在加载管理工作台与资产台账…" />
        </div>
      </section>
    );
  }

  if (error && !overview) {
    return (
      <section className="dash-section" aria-label="治理与运维">
        <div className="dash-panel">
          <DashEmptyBox icon="alert" title={`治理台账加载失败：${errorMessage(error)}`} />
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

  const allAppsCount = overview.allApps?.length ?? 0;
  const publishedCount = overview.publishedApps?.length ?? 0;
  const deletedCount = overview.deletedApps.length;
  const activeTaskList = dashboard?.tasks.activeList ?? [];
  const activeTaskCount = activeTaskList.length;
  const rateLimitRejected = overview.rateLimitStats.rejected24h ?? overview.rateLimitStats.recentHits24h;
  const rateLimitBuckets = overview.rateLimitStats.buckets.length;
  const customQuotaCount = overview.customQuotas.length;
  const ssoUserCount = overview.users?.length ?? 0;
  const runtimeIssues = activeTaskCount + rateLimitBuckets;

  const pulseItems: {
    tab: GovTab;
    icon: IconName;
    label: string;
    value: string;
    sub: string;
    tone: 'violet' | 'teal' | 'amber' | 'indigo' | 'rose' | 'slate';
    alert?: boolean;
    healthy?: boolean;
  }[] = [
    {
      tab: 'handover',
      icon: 'grid',
      label: '资产交接与转移',
      value: fmtNum(allAppsCount),
      sub: `${fmtNum(workspaceOptions.length)} 个活跃空间 · 支持离职交接`,
      tone: 'violet',
    },
    {
      tab: 'sites',
      icon: 'globe',
      label: '线上站点风控',
      value: fmtNum(publishedCount),
      sub: '权限收紧、归属转移与紧急下线',
      tone: 'teal',
    },
    {
      tab: 'feedback',
      icon: 'help',
      label: '反馈与社区治理',
      value: overview.feedbackOpenCount > 0 ? `${fmtNum(overview.feedbackOpenCount)} 待办` : fmtNum(overview.templates.length),
      sub: `反馈 ${fmtNum(overview.feedbacks.length)} 条 · 社区模板 ${fmtNum(overview.templates.length)}`,
      tone: 'amber',
      alert: overview.feedbackOpenCount > 0,
      healthy: overview.feedbackOpenCount === 0,
    },
    {
      tab: 'trash',
      icon: 'trash',
      label: '回收站与审计',
      value: fmtNum(deletedCount),
      sub: `不可篡改审计 ${fmtNum(overview.auditLogs.length)} 条`,
      tone: 'indigo',
    },
    {
      tab: 'ops',
      icon: 'lock',
      label: '运行风控与配额权限',
      value: runtimeIssues === 0 ? '正常' : `${fmtNum(runtimeIssues)} 异常`,
      sub: `配额覆盖 ${customQuotaCount} · SSO 账号 ${ssoUserCount} · 429 拦截 ${fmtNum(rateLimitRejected)}`,
      tone: runtimeIssues > 0 ? 'rose' : 'slate',
      alert: rateLimitRejected > 0,
      healthy: runtimeIssues === 0 && rateLimitRejected === 0,
    },
  ];

  return (
    <section className="dash-section gov-workbench" aria-label="平台管理工作台">
      {!standalone ? (
        <div className="gov-header-bar">
          <div className="gov-header-title">
            <h2>平台治理工作台</h2>
            <span className="gov-actor-chip">
              操作人 <code>{overview.currentActor}</code>
            </span>
          </div>
          <span className="gov-quota-meta">
            全局默认配额：<b>{overview.defaultQuota.maxApps}</b> 应用 · <b>{overview.defaultQuota.dailyTaskLimit}</b> 次/日 · <b>{overview.defaultQuota.maxConcurrentTasks}</b> 并发
          </span>
        </div>
      ) : null}

      {/* 一体化交互脉冲导航卡（既是全局状态概览，也是 5 大管理模块切换器） */}
      <div className="gov-pulse-grid" role="tablist" aria-label="管理工作台模块切换">
        {pulseItems.map((item) => (
          <button
            key={item.tab}
            type="button"
            role="tab"
            aria-selected={govTab === item.tab}
            className={`gov-pulse-card ${govTab === item.tab ? 'active' : ''} ${item.alert ? 'is-alert' : ''}`}
            onClick={() => setGovTab(item.tab)}
          >
            <div className="gov-pulse-top">
              <span className="gov-pulse-label">
                <Icon name={item.icon} />
                <span>{item.label}</span>
              </span>
              {item.alert ? (
                <i className="gov-dot alert" aria-hidden="true" />
              ) : item.healthy ? (
                <i className="gov-dot ok" aria-hidden="true" />
              ) : (
                <i className={`gov-dot ${item.tone}`} aria-hidden="true" />
              )}
            </div>
            <strong className={item.alert ? 'is-alert' : item.healthy ? 'is-ok' : ''}>{item.value}</strong>
            <small>{item.sub}</small>
          </button>
        ))}
      </div>

      {/* ====================================================================
         模块 1：资产交接与转移（人员离职批量交接 + 全平台应用台账 & 单应用转移）
         ==================================================================== */}
      {govTab === 'handover' ? (
        <div className="gov-stack">
          {/* 1A. 人员离职 / 空间资产批量交接工作台 */}
          <div className="dash-panel gov-handover-panel">
            <DashPanelHead
              icon="undo"
              tone="violet"
              title="人员离职 / 空间资产无损批量交接"
              sub="支持将离职或调岗人员名下的全部应用（含已发布站点域名、版本历史、云数据库表）、创造会话与私有数据源一键划转至接手人空间，线上服务 0 中断"
            />

            <div className="gov-handover-grid">
              {/* 左侧：交出方选择与实时资产盘点 */}
              <div className="gov-handover-box">
                <div className="gov-handover-step">
                  <span className="gov-step-num">1</span>
                  <b>选择交出方（离职人员 / 源工作空间）</b>
                </div>
                <Select
                  ariaLabel="选择交出方工作空间"
                  value={handoverFromWs}
                  placeholder="选择需要交出资产的工作空间…"
                  options={[
                    { value: '', label: '选择交出工作空间…' },
                    ...workspaceOptions.map((w) => ({
                      value: w.workspaceId,
                      label: formatWsOptionLabel(w),
                    })),
                  ]}
                  onChange={setHandoverFromWs}
                />

                {sourceWorkspaceInfo ? (
                  <div className="gov-inventory-card">
                    <div className="gov-inventory-head">
                      <span>
                        源空间资产盘点：<code>{sourceWorkspaceInfo.workspaceId}</code>
                      </span>
                      <button
                        type="button"
                        className="dash-link"
                        onClick={() => setHandoverPartial((v) => !v)}
                      >
                        {handoverPartial ? '改为整空间全量交接' : `按需勾选应用 (${sourceWorkspaceApps.length})`}
                      </button>
                    </div>
                    <div className="gov-inventory-stats">
                      <div>
                        <span>在用应用</span>
                        <b>{fmtNum(sourceWorkspaceInfo.appCount)}</b>
                      </div>
                      <div>
                        <span>已发布站点</span>
                        <b>{fmtNum(sourceWorkspaceInfo.publishedCount)}</b>
                      </div>
                      <div>
                        <span>创造页会话</span>
                        <b>{fmtNum(sourceWorkspaceInfo.conversationCount)}</b>
                      </div>
                      <div>
                        <span>私有数据源</span>
                        <b>{fmtNum(sourceWorkspaceInfo.dataSourceCount)}</b>
                      </div>
                      <div>
                        <span>回收站应用</span>
                        <b>{fmtNum(sourceWorkspaceInfo.deletedAppCount)}</b>
                      </div>
                    </div>

                    {handoverPartial && sourceWorkspaceApps.length > 0 ? (
                      <div className="gov-app-checklist">
                        <div className="gov-app-checklist-bar">
                          <span>已选 {handoverSelectedApps.length} / {sourceWorkspaceApps.length} 个应用</span>
                          <div style={{ display: 'flex', gap: 10 }}>
                            <button
                              type="button"
                              className="dash-link"
                              onClick={() => setHandoverSelectedApps(sourceWorkspaceApps.map((a) => a.id))}
                            >
                              全选
                            </button>
                            <button
                              type="button"
                              className="dash-link"
                              onClick={() => setHandoverSelectedApps([])}
                            >
                              清空
                            </button>
                          </div>
                        </div>
                        <div className="gov-app-checklist-items">
                          {sourceWorkspaceApps.map((a) => {
                            const checked = handoverSelectedApps.includes(a.id);
                            return (
                              <label key={a.id} className={`gov-check-item ${checked ? 'checked' : ''}`}>
                                <input
                                  type="checkbox"
                                  checked={checked}
                                  onChange={(e) => {
                                    if (e.target.checked) {
                                      setHandoverSelectedApps((prev) => [...prev, a.id]);
                                    } else {
                                      setHandoverSelectedApps((prev) => prev.filter((id) => id !== a.id));
                                    }
                                  }}
                                />
                                <span className="gov-check-name">{a.name}</span>
                                <code>{a.slug}</code>
                                {a.published ? <span className="dash-pill ok">已发布</span> : <span className="dash-tag">草稿</span>}
                              </label>
                            );
                          })}
                        </div>
                      </div>
                    ) : null}
                  </div>
                ) : (
                  <div className="gov-handover-hint">
                    选择源空间后，将自动盘点其名下的应用、线上站点、会话文档与私有数据源。
                  </div>
                )}
              </div>

              {/* 右侧：接收方选择、迁移范围与执行 */}
              <div className="gov-handover-box">
                <div className="gov-handover-step">
                  <span className="gov-step-num">2</span>
                  <b>选择接收方（接手同事 / 目标工作空间）与交接项</b>
                </div>
                <Select
                  ariaLabel="选择接收方工作空间"
                  value={handoverToWs}
                  placeholder="选择接手人的目标工作空间…"
                  options={[
                    { value: '', label: '选择接收工作空间…' },
                    ...workspaceOptions
                      .filter((w) => w.workspaceId !== handoverFromWs)
                      .map((w) => ({
                        value: w.workspaceId,
                        label: formatWsOptionLabel(w),
                      })),
                  ]}
                  onChange={setHandoverToWs}
                />

                <div className="gov-handover-options">
                  <label className="gov-option-check">
                    <input
                      type="checkbox"
                      checked={handoverIncludeConv}
                      onChange={(e) => setHandoverIncludeConv(e.target.checked)}
                    />
                    <span>同步迁移关联的创造页会话与需求文档</span>
                  </label>
                  <label className="gov-option-check">
                    <input
                      type="checkbox"
                      checked={handoverIncludeDs}
                      onChange={(e) => setHandoverIncludeDs(e.target.checked)}
                    />
                    <span>同步迁移绑定的私有数据源配置</span>
                  </label>
                  {!handoverPartial ? (
                    <label className="gov-option-check">
                      <input
                        type="checkbox"
                        checked={handoverIncludeTrash}
                        onChange={(e) => setHandoverIncludeTrash(e.target.checked)}
                      />
                      <span>包含回收站中已删除的应用（{sourceWorkspaceInfo?.deletedAppCount ?? 0} 个）</span>
                    </label>
                  ) : null}
                </div>

                <div className="gov-handover-foot">
                  <input
                    type="text"
                    className="gov-note-input"
                    maxLength={200}
                    placeholder="交接说明 / 工单备注（如：张三离职应用交接给李四，写入审计台账）"
                    value={handoverNote}
                    onChange={(e) => setHandoverNote(e.target.value)}
                  />
                  <button
                    type="button"
                    className="primary-button"
                    disabled={!handoverFromWs || !handoverToWs || handoverFromWs === handoverToWs || busyKey === 'handover-batch'}
                    onClick={() => void submitBatchHandover()}
                  >
                    {busyKey === 'handover-batch' ? '正在交接…' : '执行无损交接'}
                  </button>
                </div>
              </div>
            </div>
          </div>

          {/* 1B. 全平台应用资产总台账 & 单应用定向转移 */}
          <div className="dash-panel">
            <DashPanelHead
              icon="grid"
              tone="indigo"
              title={`全平台应用资产台账与定向转移（${filteredAllApps.length} / ${allAppsCount} 个）`}
              sub="涵盖全平台所有在用应用（含已发布站点与草稿），支持按空间检索并一键将单个应用定向移交给任意工作空间"
              right={
                <div className="dash-toolbar-group">
                  <Select
                    ariaLabel="按发布状态筛选应用"
                    value={appLedgerStatus}
                    onChange={(v) => setAppLedgerStatus(v as typeof appLedgerStatus)}
                    options={[
                      { value: 'all', label: `全部状态 (${allAppsCount})` },
                      { value: 'published', label: `已发布站点 (${publishedCount})` },
                      { value: 'draft', label: `仅未发布草稿 (${Math.max(0, allAppsCount - publishedCount)})` },
                    ]}
                  />
                  <Select
                    ariaLabel="按工作空间筛选应用"
                    value={appLedgerWs}
                    onChange={setAppLedgerWs}
                    options={[
                      { value: '', label: '全部工作空间' },
                      ...workspaceOptions.map((w) => ({
                        value: w.workspaceId,
                        label: formatWsOptionLabel(w),
                      })),
                    ]}
                  />
                  <label className="dash-search">
                    <Icon name="search" />
                    <input
                      aria-label="搜索全平台应用"
                      placeholder="搜应用名 / slug / 空间…"
                      value={appLedgerQuery}
                      onChange={(e) => setAppLedgerQuery(e.target.value)}
                    />
                  </label>
                </div>
              }
            />
            {filteredAllApps.length === 0 ? (
              <GovHealthyBanner
                icon="grid"
                tone="muted"
                title="当前筛选条件下暂无匹配应用"
                desc="可尝试清空搜索词或切换工作空间 / 发布状态筛选条件"
              />
            ) : (
              <>
                <div className="dash-table-wrap">
                  <table className="dash-table">
                    <thead>
                      <tr>
                        <th scope="col">应用名称 / 子域 Slug</th>
                        <th scope="col">当前归属人 / 空间</th>
                        <th scope="col">发布与云服务状态</th>
                        <th scope="col">版本 / 累计 PV</th>
                        <th scope="col">最近更新</th>
                        <th scope="col">治理操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {allAppsPager.pageItems.map((app: AdminAppItem) => (
                        <tr key={app.id}>
                          <td>
                            <b>{app.name}</b>
                            <div>
                              <code>{app.slug}</code> <span className="dash-tag">{app.type}</span>
                            </div>
                          </td>
                          <td>
                            <OwnerBadge ownerName={app.ownerName} workspaceId={app.workspaceId} />
                          </td>
                          <td>
                            <div className="dash-action-row">
                              {app.published ? (
                                <span className="dash-pill ok">
                                  已发布 · {SITE_ACCESS_LABELS[app.accessLevel] || app.accessLevel}
                                </span>
                              ) : (
                                <span className="dash-tag">未发布 ({app.phase})</span>
                              )}
                              {app.cloudEnabled ? <span className="dash-pill cloud">云服务</span> : null}
                            </div>
                          </td>
                          <td>
                            V{app.versionCount} · PV <b>{fmtNum(app.totalPv)}</b>
                          </td>
                          <td className="dash-nowrap">{fmtTime(app.updatedAt)}</td>
                          <td className="dash-nowrap">
                            <div className="dash-action-row">
                              <button
                                type="button"
                                className="dash-link"
                                onClick={() =>
                                  openTransferModal({
                                    id: app.id,
                                    name: app.name,
                                    slug: app.slug,
                                    workspaceId: app.workspaceId,
                                    ownerName: app.ownerName,
                                  })
                                }
                              >
                                转移归属
                              </button>
                              <button
                                type="button"
                                className="dash-link"
                                onClick={() => navigate('/apps/' + encodeURIComponent(app.id))}
                              >
                                工作台
                              </button>
                              {app.published ? (
                                <button
                                  type="button"
                                  className="dash-link danger"
                                  disabled={busyKey === `unpub-${app.id}`}
                                  onClick={async () => {
                                    const ok = await confirmDialog({
                                      title: '强行下线站点',
                                      message: `确认下线「${app.name} (${app.slug})」的线上站点吗？应用代码与版本本身不会丢失。`,
                                      confirmLabel: '强行下线',
                                      danger: true,
                                    });
                                    if (!ok) return;
                                    await runAction(`unpub-${app.id}`, async () => {
                                      await api.adminUnpublishApp(app.id);
                                      toast(`已下线站点「${app.name}」`, 'ok');
                                    });
                                  }}
                                >
                                  下线
                                </button>
                              ) : (
                                <button
                                  type="button"
                                  className="dash-link danger"
                                  disabled={busyKey === `del-${app.id}`}
                                  onClick={async () => {
                                    const ok = await confirmDialog({
                                      title: '移入回收站',
                                      message: `确认将「${app.name}」移入回收站吗？后续可在回收站无损恢复。`,
                                      confirmLabel: '移入回收站',
                                      danger: true,
                                    });
                                    if (!ok) return;
                                    await runAction(`del-${app.id}`, async () => {
                                      await api.adminDeleteApp(app.id);
                                      toast(`已将「${app.name}」移入回收站`, 'ok');
                                    });
                                  }}
                                >
                                  删除
                                </button>
                              )}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <Pagination
                  page={allAppsPager.page}
                  pageSize={allAppsPager.pageSize}
                  total={allAppsPager.total}
                  onPageChange={allAppsPager.setPage}
                  onPageSizeChange={allAppsPager.setPageSize}
                  pageSizeOptions={[10, 20, 50]}
                  alwaysShowSummary
                />
              </>
            )}
          </div>
        </div>
      ) : null}

      {/* ====================================================================
         模块 2：已发布站点巡检与访问风控（100% 全宽 + 直接支持转移归属）
         ==================================================================== */}
      {govTab === 'sites' ? (
        <div className="dash-panel">
          <DashPanelHead
            icon="globe"
            tone="teal"
            title={`全平台已发布站点巡检与访问风控（${filteredPublishedApps.length} 个）`}
            sub="检索所有正在对外提供服务的线上站点，支持直接收紧访问权限、转移归属人或紧急下线"
            right={
              <div className="dash-toolbar-group">
                <Select
                  ariaLabel="按访问级别筛选站点"
                  value={siteLevelFilter}
                  onChange={(val) => setSiteLevelFilter(val as typeof siteLevelFilter)}
                  options={[
                    { value: 'all', label: `全部级别 (${publishedCount})` },
                    { value: 'public', label: '内网公开' },
                    { value: 'login', label: '员工登录' },
                    { value: 'members', label: '指定成员' },
                  ]}
                />
                <label className="dash-search">
                  <Icon name="search" />
                  <input
                    aria-label="搜索已发布站点"
                    placeholder="搜应用名 / slug / 归属人…"
                    value={siteQuery}
                    onChange={(e) => setSiteQuery(e.target.value)}
                  />
                </label>
              </div>
            }
          />
          {filteredPublishedApps.length === 0 ? (
            <GovHealthyBanner
              icon="globe"
              tone="muted"
              title="当前筛选条件下暂无已发布站点"
              desc={siteQuery || siteLevelFilter !== 'all' ? '可尝试清空关键词或切换访问级别筛选' : '用户发布应用后将实时同步至此处巡检'}
            />
          ) : (
            <>
              <div className="dash-table-wrap">
                <table className="dash-table">
                  <thead>
                    <tr>
                      <th scope="col">站点名称 / 子域 Slug</th>
                      <th scope="col">归属人 / 空间</th>
                      <th scope="col">今日 / 累计 PV</th>
                      <th scope="col">访问权限级别</th>
                      <th scope="col">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {sitesPager.pageItems.map((site) => (
                      <tr key={site.id}>
                        <td>
                          <b>{site.name}</b>
                          <div>
                            <code>{site.slug}</code> <span className="dash-tag">{site.type}</span>
                          </div>
                        </td>
                        <td>
                          <OwnerBadge ownerName={site.ownerName} workspaceId={site.workspaceId} />
                        </td>
                        <td>
                          {fmtNum(site.todayPv)} / <b>{fmtNum(site.totalPv)}</b>
                        </td>
                        <td>
                          <Select
                            ariaLabel={`修改 ${site.name} 访问级别`}
                            value={site.accessLevel}
                            disabled={busyKey === `acc-${site.id}`}
                            options={[
                              { value: 'public', label: '内网公开' },
                              { value: 'login', label: '员工登录' },
                              { value: 'members', label: '指定成员' },
                            ]}
                            onChange={async (lvl) => {
                              if (lvl === site.accessLevel) return;
                              await runAction(`acc-${site.id}`, async () => {
                                await api.adminUpdateSiteAccess(site.id, lvl as 'public' | 'login' | 'members');
                                toast(`已将「${site.name}」访问级别设为${SITE_ACCESS_LABELS[lvl] || lvl}`, 'ok');
                              });
                            }}
                          />
                        </td>
                        <td className="dash-nowrap">
                          <div className="dash-action-row">
                            <button
                              type="button"
                              className="dash-link"
                              onClick={() =>
                                openTransferModal({
                                  id: site.id,
                                  name: site.name,
                                  slug: site.slug,
                                  workspaceId: site.workspaceId,
                                  ownerName: site.ownerName,
                                })
                              }
                            >
                              转移归属
                            </button>
                            <button
                              type="button"
                              className="dash-link danger"
                              disabled={busyKey === `unpub-${site.id}`}
                              onClick={async () => {
                                const ok = await confirmDialog({
                                  title: '强行下线站点',
                                  message: `确认下线「${site.name} (${site.slug})」的线上站点吗？应用代码与版本本身不会丢失。`,
                                  confirmLabel: '强行下线',
                                  danger: true,
                                });
                                if (!ok) return;
                                await runAction(`unpub-${site.id}`, async () => {
                                  await api.adminUnpublishApp(site.id);
                                  toast(`已下线站点「${site.name}」`, 'ok');
                                });
                              }}
                            >
                              强行下线
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pagination
                page={sitesPager.page}
                pageSize={sitesPager.pageSize}
                total={sitesPager.total}
                onPageChange={sitesPager.setPage}
                onPageSizeChange={sitesPager.setPageSize}
                pageSizeOptions={[10, 20, 50]}
                alwaysShowSummary
              />
            </>
          )}
        </div>
      ) : null}

      {/* ====================================================================
         模块 3：用户反馈分诊台 + 灵感社区内容治理
         ==================================================================== */}
      {govTab === 'feedback' ? (
        <div className="gov-stack">
          <div className="dash-panel">
            <DashPanelHead
              icon="help"
              tone="amber"
              title={`用户问题反馈分诊台（${filteredFeedbacks.length} 条）`}
              sub="跟踪小织问题上报处理状态、指派解决说明并自动闭环计时"
              right={
                <div className="seg-control small" role="radiogroup" aria-label="反馈状态筛选">
                  {(
                    [
                      ['all', `全部 (${overview.feedbacks.length})`],
                      ['open', `待处理 (${overview.feedbackOpenCount})`],
                      ['in_progress', '跟进中'],
                      ['resolved', '已解决'],
                      ['ignored', '已忽略'],
                    ] as const
                  ).map(([val, lbl]) => (
                    <button
                      key={val}
                      type="button"
                      role="radio"
                      aria-checked={fbFilter === val}
                      className={fbFilter === val ? 'active' : ''}
                      onClick={() => setFbFilter(val)}
                    >
                      {lbl}
                    </button>
                  ))}
                </div>
              }
            />
            {filteredFeedbacks.length === 0 ? (
              <GovHealthyBanner
                icon="check"
                tone="ok"
                title="当前筛选状态下无待跟进反馈"
                desc="所有用户通过小织提交的问题工单会在此处实时同步"
              />
            ) : (
              <>
                <ul className="dash-admin-fb-list">
                  {fbPager.pageItems.map((fb) => {
                    const hasRealOwner = Boolean(fb.ownerName && fb.ownerName !== fb.workspaceId);
                    return (
                      <li key={fb.id}>
                        <div className="dash-admin-fb-head">
                          <span className={`dash-pill ${fb.status === 'resolved' ? 'ok' : fb.status === 'open' ? 'err' : 'cloud'}`}>
                            {FEEDBACK_STATUS_LABELS[fb.status]}
                          </span>
                          <span className="dash-muted">
                            {hasRealOwner ? `${fb.ownerName} · ` : ''}<code>{fb.workspaceId}</code> · {fmtTime(fb.createdAt)}
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
                    );
                  })}
                </ul>
                <Pagination
                  page={fbPager.page}
                  pageSize={fbPager.pageSize}
                  total={fbPager.total}
                  onPageChange={fbPager.setPage}
                  onPageSizeChange={fbPager.setPageSize}
                  pageSizeOptions={[8, 16, 30]}
                />
              </>
            )}
          </div>

          <div className="dash-panel">
            <DashPanelHead
              icon="community"
              tone="rose"
              title={`灵感社区内容治理（${filteredTemplates.length} / ${overview.templates.length} 个）`}
              sub="设置精选置顶后将在灵感社区默认列表中优先排序展示，违规作品可一键下架"
              right={
                <label className="dash-search">
                  <Icon name="search" />
                  <input
                    aria-label="搜索社区作品"
                    placeholder="搜作品名 / 类型 / 作者…"
                    value={tplQuery}
                    onChange={(e) => setTplQuery(e.target.value)}
                  />
                </label>
              }
            />
            {filteredTemplates.length === 0 ? (
              <GovHealthyBanner
                icon="community"
                tone="muted"
                title={tplQuery ? '没有匹配的社区作品' : '灵感社区当前暂无作品'}
              />
            ) : (
              <>
                <div className="dash-table-wrap">
                  <table className="dash-table">
                    <thead>
                      <tr>
                        <th scope="col">作品名称 / 作者</th>
                        <th scope="col">类型</th>
                        <th scope="col">浏览 / 点赞 / 复刻</th>
                        <th scope="col">精选权重</th>
                        <th scope="col">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {tplPager.pageItems.map((tpl) => (
                        <tr key={tpl.id}>
                          <td>
                            <b>{tpl.name}</b>
                            <div>
                              <span className="dash-muted">{tpl.author || tpl.workspaceId}</span>
                            </div>
                          </td>
                          <td>
                            <span className="dash-tag">{tpl.type}</span>
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
                          <td className="dash-nowrap">
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
                <Pagination
                  page={tplPager.page}
                  pageSize={tplPager.pageSize}
                  total={tplPager.total}
                  onPageChange={tplPager.setPage}
                  onPageSizeChange={tplPager.setPageSize}
                  pageSizeOptions={[10, 20, 50]}
                  alwaysShowSummary
                />
              </>
            )}
          </div>
        </div>
      ) : null}

      {/* ====================================================================
         模块 4：回收站 + 管理操作审计日志
         ==================================================================== */}
      {govTab === 'trash' ? (
        <div className="gov-stack">
          <div className="dash-panel">
            <DashPanelHead
              icon="trash"
              tone="amber"
              title={`已删除应用回收站（${filteredDeletedApps.length} / ${deletedCount} 个）`}
              sub="支持跨空间一键恢复用户误删除的应用及其历史版本，或彻底清除释放存储"
              right={
                <label className="dash-search">
                  <Icon name="search" />
                  <input
                    aria-label="搜索回收站应用"
                    placeholder="搜应用名 / 类型 / 空间…"
                    value={trashQuery}
                    onChange={(e) => setTrashQuery(e.target.value)}
                  />
                </label>
              }
            />
            {filteredDeletedApps.length === 0 ? (
              <GovHealthyBanner
                icon="check"
                tone="ok"
                title={trashQuery ? '没有匹配的回收站应用' : '回收站当前为空'}
                desc={trashQuery ? '试试清空搜索关键词' : '用户删除应用后会暂存于此以便误删恢复'}
              />
            ) : (
              <>
                <div className="dash-table-wrap">
                  <table className="dash-table">
                    <thead>
                      <tr>
                        <th scope="col">应用名称</th>
                        <th scope="col">归属人 / 空间</th>
                        <th scope="col">版本数</th>
                        <th scope="col">删除时间</th>
                        <th scope="col">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {trashPager.pageItems.map((a) => (
                        <tr key={a.id}>
                          <td>
                            <b>{a.name}</b>{' '}
                            <span className="dash-tag" style={{ marginLeft: 6 }}>{a.type}</span>
                          </td>
                          <td>
                            <OwnerBadge ownerName={a.ownerName} workspaceId={a.workspaceId} />
                          </td>
                          <td>{fmtNum(a.versionCount)}</td>
                          <td className="dash-nowrap">{fmtTime(a.deletedAt)}</td>
                          <td className="dash-nowrap">
                            <div className="dash-action-row">
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
                              <button
                                type="button"
                                className="dash-link danger"
                                disabled={busyKey === `purge-${a.id}`}
                                onClick={async () => {
                                  const ok = await confirmDialog({
                                    title: '彻底清除回收站应用',
                                    message: `确认永久清除「${a.name}」及其所有历史版本、任务记录和云存储文件吗？此操作不可恢复！`,
                                    confirmLabel: '彻底清除',
                                    danger: true,
                                  });
                                  if (!ok) return;
                                  await runAction(`purge-${a.id}`, async () => {
                                    await api.adminPurgeApp(a.id);
                                    toast(`已彻底清除应用「${a.name}」`, 'ok');
                                  });
                                }}
                              >
                                彻底清除
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <Pagination
                  page={trashPager.page}
                  pageSize={trashPager.pageSize}
                  total={trashPager.total}
                  onPageChange={trashPager.setPage}
                  onPageSizeChange={trashPager.setPageSize}
                  pageSizeOptions={[10, 20, 50]}
                  alwaysShowSummary
                />
              </>
            )}
          </div>

          <div className="dash-panel">
            <DashPanelHead
              icon="history"
              tone="slate"
              title={`管理操作审计日志（${filteredAuditLogs.length} / 最近 ${overview.auditLogs.length} 条）`}
              sub="所有应用归属转移、离职批量交接、权限变更、配额覆盖、站点风控/下线、应用恢复/清除与任务干预均已落库留痕"
              right={
                <div className="dash-toolbar-group">
                  <Select
                    ariaLabel="按审计动作筛选"
                    value={auditActionFilter}
                    options={auditActionOptions}
                    onChange={setAuditActionFilter}
                  />
                  <label className="dash-search">
                    <Icon name="search" />
                    <input
                      aria-label="搜索审计日志"
                      placeholder="搜操作人 / 目标 ID / 详情…"
                      value={auditQuery}
                      onChange={(e) => setAuditQuery(e.target.value)}
                    />
                  </label>
                  <button
                    type="button"
                    className="subtle-button dash-compact-btn"
                    disabled={filteredAuditLogs.length === 0}
                    onClick={exportAuditCsv}
                  >
                    导出 CSV
                  </button>
                </div>
              }
            />
            {filteredAuditLogs.length === 0 ? (
              <GovHealthyBanner
                icon="history"
                tone="muted"
                title="暂无匹配的管理操作审计记录"
                desc="执行管理员操作后将自动写入可追溯审计台账"
              />
            ) : (
              <>
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
                      {auditPager.pageItems.map((log) => (
                        <tr key={log.id}>
                          <td className="dash-nowrap">{fmtTime(log.createdAt)}</td>
                          <td>{log.actor}</td>
                          <td>
                            <span className={`dash-tag ${log.action === 'APP_TRANSFER' || log.action === 'WORKSPACE_HANDOVER' ? 'dash-pill cloud' : ''}`}>
                              {AUDIT_ACTION_LABELS[log.action] || log.action}
                            </span>
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
                <Pagination
                  page={auditPager.page}
                  pageSize={auditPager.pageSize}
                  total={auditPager.total}
                  onPageChange={auditPager.setPage}
                  onPageSizeChange={auditPager.setPageSize}
                  pageSizeOptions={[10, 20, 50]}
                  alwaysShowSummary
                />
              </>
            )}
          </div>
        </div>
      ) : null}

      {/* ====================================================================
         模块 5：运行干预、限流风控、配额覆盖与 SSO 权限
         ==================================================================== */}
      {govTab === 'ops' ? (
        <div className="gov-stack">
          <div className="dash-panel">
            <DashPanelHead
              icon="settings"
              tone="violet"
              title={`工作空间自定义配额覆盖（${customQuotaCount} 个）`}
              sub="针对重点团队或异常空间单独调整应用数量、每日任务数与并发数上限"
              right={
                <div className="dash-admin-inline-form">
                  <Select
                    ariaLabel="选择工作空间调整配额"
                    value={quotaPickWs}
                    placeholder="选择工作空间…"
                    options={[
                      { value: '', label: '选择工作空间…' },
                      ...workspaceOptions.map((w) => ({
                        value: w.workspaceId,
                        label: formatWsOptionLabel(w),
                      })),
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
                      const wsRow = workspaceOptions.find((w) => w.workspaceId === quotaPickWs);
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
              }
            />
            {customQuotaCount === 0 ? (
              <GovHealthyBanner
                icon="settings"
                tone="muted"
                title="所有工作空间当前均遵循全局默认配额"
                desc={`默认上限：${overview.defaultQuota.maxApps} 个应用 · ${overview.defaultQuota.dailyTaskLimit} 次/日任务 · ${overview.defaultQuota.maxConcurrentTasks} 并发任务。可从右上角下拉选择空间单独覆写。`}
              />
            ) : (
              <>
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
                      {quotaPager.pageItems.map((q) => (
                        <tr key={q.workspaceId}>
                          <td>
                            <OwnerBadge ownerName={q.ownerName} workspaceId={q.workspaceId} />
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
                          <td className="dash-nowrap">
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
                <Pagination
                  page={quotaPager.page}
                  pageSize={quotaPager.pageSize}
                  total={quotaPager.total}
                  onPageChange={quotaPager.setPage}
                  onPageSizeChange={quotaPager.setPageSize}
                  pageSizeOptions={[10, 20, 50]}
                />
              </>
            )}
          </div>

          <div className="dash-panel">
            <DashPanelHead
              icon="lock"
              tone="slate"
              title={`SSO 账号与管理员权限（${filteredUsers.length}）`}
              sub="查看统一登录员工账号，按需授予或撤销平台管理员权限（变更实时落库审计）"
              right={
                <div className="dash-toolbar-group">
                  <Select
                    ariaLabel="按角色筛选账号"
                    value={userRoleFilter}
                    onChange={(val) => setUserRoleFilter(val as typeof userRoleFilter)}
                    options={[
                      { value: 'all', label: `全部账号 (${ssoUserCount})` },
                      { value: 'admin', label: '仅管理员' },
                      { value: 'user', label: '普通员工' },
                    ]}
                  />
                  <label className="dash-search">
                    <Icon name="search" />
                    <input
                      aria-label="搜索员工账号"
                      placeholder="搜姓名 / 登录名 / 邮箱…"
                      value={userQuery}
                      onChange={(e) => setUserQuery(e.target.value)}
                    />
                  </label>
                </div>
              }
            />
            {filteredUsers.length === 0 ? (
              <GovHealthyBanner
                icon="lock"
                tone="muted"
                title={ssoUserCount === 0 ? '尚未接入企业 SSO 账号' : '没有匹配的员工账号'}
                desc={
                  ssoUserCount === 0
                    ? '当前为本地免登录模式，通过 ZHIYE_ADMIN_ALLOW_WORKSPACES 控制管理员权限；接入企业 SSO 后员工账号会自动同步至此。'
                    : '尝试清空搜索词或切换角色筛选条件。'
                }
              />
            ) : (
              <>
                <div className="dash-table-wrap">
                  <table className="dash-table">
                    <thead>
                      <tr>
                        <th scope="col">姓名 / 登录账号</th>
                        <th scope="col">当前角色</th>
                        <th scope="col">空间 / 应用</th>
                        <th scope="col">最近登录</th>
                        <th scope="col">权限操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {userPager.pageItems.map((u) => {
                        const isAdminRole = u.role === 'admin';
                        return (
                          <tr key={u.id}>
                            <td>
                              <b>{u.realName}</b>{' '}
                              {u.self ? <span className="dash-pill cloud">当前本人</span> : null}
                              <div>
                                <code>{u.loginName}</code>
                                {u.email ? <span className="dash-muted"> · {u.email}</span> : null}
                              </div>
                            </td>
                            <td>
                              {u.allowlisted ? (
                                <span className="dash-pill ok">白名单管理员</span>
                              ) : isAdminRole ? (
                                <span className="dash-pill ok">平台管理员</span>
                              ) : (
                                <span className="dash-muted">普通员工</span>
                              )}
                            </td>
                            <td>
                              {fmtNum(u.workspaceCount)} / <b>{fmtNum(u.appCount)}</b>
                            </td>
                            <td className="dash-nowrap">{u.lastLoginAt ? fmtTime(u.lastLoginAt) : '—'}</td>
                            <td className="dash-nowrap">
                              {u.allowlisted ? (
                                <span className="dash-muted" title="由配置文件白名单锁定">配置锁定</span>
                              ) : u.self ? (
                                <span className="dash-muted" title="不可降级当前本人账号">本人账号</span>
                              ) : (
                                <button
                                  type="button"
                                  className={`dash-link ${isAdminRole ? 'danger' : ''}`}
                                  disabled={busyKey === `role-${u.id}`}
                                  onClick={async () => {
                                    const nextRole = isAdminRole ? 'user' : 'admin';
                                    const ok = await confirmDialog({
                                      title: isAdminRole ? '撤销平台管理员权限' : '授予平台管理员权限',
                                      message: isAdminRole
                                        ? `确认撤销「${u.realName} (${u.loginName})」的平台管理员权限吗？`
                                        : `确认授予「${u.realName} (${u.loginName})」平台管理员权限吗？授权后可查看全平台看板与执行运维操作。`,
                                      confirmLabel: isAdminRole ? '确认撤销' : '确认授权',
                                      danger: isAdminRole,
                                    });
                                    if (!ok) return;
                                    await runAction(`role-${u.id}`, async () => {
                                      await api.adminUpdateUserRole(u.id, nextRole);
                                      toast(
                                        isAdminRole
                                          ? `已撤销 ${u.realName} 的管理员权限`
                                          : `已授予 ${u.realName} 平台管理员权限`,
                                        'ok',
                                      );
                                    });
                                  }}
                                >
                                  {isAdminRole ? '撤销管理员' : '设为管理员'}
                                </button>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
                <Pagination
                  page={userPager.page}
                  pageSize={userPager.pageSize}
                  total={userPager.total}
                  onPageChange={userPager.setPage}
                  onPageSizeChange={userPager.setPageSize}
                  pageSizeOptions={[10, 20, 50]}
                  alwaysShowSummary
                />
              </>
            )}
          </div>

          <div className="dash-panel">
            <DashPanelHead
              icon="play"
              tone="indigo"
              title={`运行中 / 排队任务运维干预（${activeTaskCount} 个）`}
              sub="实时监控正在执行或排队的 AI 任务，遇到异常卡死或死循环时可直达过程日志或立即强停"
              right={
                <button type="button" className="subtle-button dash-compact-btn" onClick={() => navigate('/logs')}>
                  前往全链路日志 →
                </button>
              }
            />
            {activeTaskCount === 0 ? (
              <GovHealthyBanner
                icon="check"
                tone="ok"
                title="队列空闲 · 当前全平台没有排队或运行中的任务"
                desc="新任务进入执行队列时会在此实时展示进度并提供强停控制"
              />
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
                    {activeTaskList.map((t) => (
                      <tr key={t.id}>
                        <td>
                          <b>{t.appName}</b>
                          <div><code>{t.id}</code></div>
                        </td>
                        <td>
                          <span className="dash-pill cloud">{t.status} · {t.progress}%</span>
                        </td>
                        <td><code>{t.workspaceId}</code></td>
                        <td className="dash-nowrap">
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
            <DashPanelHead
              icon="alert"
              tone="rose"
              title="限流窗口监控与应急清理"
              sub={`活跃滑窗 ${fmtNum(overview.rateLimitStats.activeWindowHits ?? overview.rateLimitStats.totalHits)} · 24h 放行 ${fmtNum(overview.rateLimitStats.allowed24h ?? 0)} · 24h 拦截 ${fmtNum(rateLimitRejected)}`}
              right={
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
              }
            />
            {rateLimitBuckets === 0 ? (
              <GovHealthyBanner
                icon="check"
                tone="ok"
                title="限流保护运行正常 · 当前没有触发速率限制的 bucket"
                desc={`最近 24h 放行 ${fmtNum(overview.rateLimitStats.allowed24h ?? 0)} 次请求，0 次 429 限流拦截`}
              />
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
                          <td className="dash-nowrap">
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
      ) : null}

      {/* 单应用归属转移弹窗 */}
      {transferTarget ? (
        <Modal
          title="转移应用归属"
          overline="APP OWNERSHIP TRANSFER"
          onClose={() => setTransferTarget(null)}
          footer={
            <>
              <button type="button" className="subtle-button" onClick={() => setTransferTarget(null)}>
                取消
              </button>
              <button
                type="button"
                className="primary-button"
                disabled={!transferToWs || busyKey === `transfer-${transferTarget.id}`}
                onClick={() => void submitSingleTransfer()}
              >
                {busyKey === `transfer-${transferTarget.id}` ? '转移中…' : '确认转移归属'}
              </button>
            </>
          }
        >
          <div className="dash-modal-form">
            <div className="dash-modal-target">
              <div>
                应用：<b>{transferTarget.name}</b>（<code>{transferTarget.slug}</code>）
              </div>
              <div style={{ marginTop: 4 }}>
                当前归属：{transferTarget.ownerName && transferTarget.ownerName !== transferTarget.workspaceId ? `${transferTarget.ownerName} · ` : ''}
                <code>{transferTarget.workspaceId}</code>
              </div>
            </div>
            <label>
              <span>接收方（接手同事 / 目标工作空间）</span>
              <Select
                ariaLabel="选择接收工作空间"
                className="field"
                value={transferToWs}
                placeholder="选择接收工作空间…"
                options={[
                  { value: '', label: '选择接收工作空间…' },
                  ...workspaceOptions
                    .filter((w) => w.workspaceId !== transferTarget.workspaceId)
                    .map((w) => ({
                      value: w.workspaceId,
                      label: formatWsOptionLabel(w),
                    })),
                ]}
                onChange={setTransferToWs}
              />
            </label>
            <label className="gov-option-check" style={{ marginTop: 4 }}>
              <input
                type="checkbox"
                checked={transferIncludeConv}
                onChange={(e) => setTransferIncludeConv(e.target.checked)}
              />
              <span>同步迁移该应用关联的创造页会话与需求文档</span>
            </label>
            <label>
              <span>交接备注（写入审计日志）</span>
              <input
                type="text"
                maxLength={200}
                placeholder="如：项目负责人变更，移交后续维护"
                value={transferNote}
                onChange={(e) => setTransferNote(e.target.value)}
              />
            </label>
            <p className="muted-note" style={{ margin: 0 }}>
              转移后，该应用的线上域名、所有历史版本、云数据库表及绑定的独占私有数据源将完整保留，线上站点不会中断。
            </p>
          </div>
        </Modal>
      ) : null}

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
