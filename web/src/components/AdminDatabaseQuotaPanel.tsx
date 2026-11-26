import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type { AdminDatabaseUsageItem } from '../api/types';
import { Icon } from '../lib/icons';
import { fmtTime } from '../lib/format';
import { toast } from '../lib/store';
import { confirmDialog } from './ConfirmDialog';
import { DetailDrawer } from './DetailDrawer';
import { Pagination, useClientPagination } from './Pagination';
import { Select } from './Select';
import { DashPanelHead } from './DashboardCharts';
import { formatOwnerIdentity } from './OwnerPicker';

function fmtBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 KB';
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(bytes >= 100 * 1024 ? 0 : 1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(bytes >= 100 * 1024 * 1024 ? 1 : 2)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

const STATUS_META: Record<AdminDatabaseUsageItem['status'], { label: string; cls: string }> = {
  ok: { label: '正常', cls: 'is-ok' },
  warning: { label: '预警 ≥80%', cls: 'is-warn' },
  exceeded: { label: '超限只读', cls: 'is-danger' },
  manual_blocked: { label: '手动限写', cls: 'is-danger' },
};

export function AdminDatabaseQuotaPanel() {
  const qc = useQueryClient();
  const [kindFilter, setKindFilter] = useState<'all' | 'cloud' | 'service'>('all');
  const [statusFilter, setStatusFilter] = useState<'all' | 'ok' | 'warning' | 'blocked'>('all');
  const [query, setQuery] = useState('');
  const [scanning, setScanning] = useState(false);
  const [busyApp, setBusyApp] = useState<string | null>(null);
  const [editItem, setEditItem] = useState<AdminDatabaseUsageItem | null>(null);
  const [quotaInput, setQuotaInput] = useState('');
  const [savingQuota, setSavingQuota] = useState(false);

  const dbQ = useQuery({
    queryKey: ['admin', 'database-usage'],
    queryFn: () => api.adminDatabaseUsage(false),
    staleTime: 30_000,
  });

  const data = dbQ.data;
  const items = data?.items ?? [];

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return items.filter((item) => {
      if (kindFilter !== 'all' && item.kind !== kindFilter) return false;
      if (statusFilter === 'blocked' && !item.writeBlocked) return false;
      if (statusFilter === 'warning' && item.status !== 'warning') return false;
      if (statusFilter === 'ok' && item.status !== 'ok') return false;
      if (!q) return true;
      return (
        item.appName.toLowerCase().includes(q) ||
        item.schema.toLowerCase().includes(q) ||
        item.ownerName.toLowerCase().includes(q) ||
        (item.ownerLogin ?? '').toLowerCase().includes(q) ||
        item.workspaceId.toLowerCase().includes(q)
      );
    });
  }, [items, kindFilter, statusFilter, query]);

  const pager = useClientPagination(filtered, 10);

  const triggerScan = async () => {
    setScanning(true);
    try {
      const fresh = await api.adminScanDatabaseUsage();
      qc.setQueryData(['admin', 'database-usage'], fresh);
      toast('已完成全平台应用数据库容量巡检', 'ok');
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setScanning(false);
    }
  };

  const toggleBlock = async (item: AdminDatabaseUsageItem) => {
    const nextBlock = !item.writeBlocked;
    const ok = await confirmDialog({
      title: nextBlock ? '限制应用数据库写入' : '恢复应用数据库写入',
      message: nextBlock
        ? `确认限制「${item.appName}」(${item.schema}) 的数据库写入吗？限制后将拒绝 INSERT / UPDATE / 建表，但仍保留查询、删除与清空能力以便清理数据。`
        : `确认解除「${item.appName}」(${item.schema}) 的写入限制并恢复读写权限吗？`,
      confirmLabel: nextBlock ? '确认限写' : '恢复写入',
      danger: nextBlock,
    });
    if (!ok) return;
    setBusyApp(item.appId);
    try {
      await api.adminUpdateAppDbQuota(item.appId, {
        writeBlocked: nextBlock,
        reason: nextBlock ? '管理员手动限制写入（防止数据库容量异常增长）' : undefined,
      });
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['admin', 'database-usage'] }),
        qc.invalidateQueries({ queryKey: qk.adminOverview }),
      ]);
      toast(nextBlock ? `已限制「${item.appName}」数据库写入` : `已恢复「${item.appName}」数据库写入`, 'ok');
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusyApp(null);
    }
  };

  const openQuotaDrawer = (item: AdminDatabaseUsageItem) => {
    setEditItem(item);
    setQuotaInput(String(item.quotaMb));
  };

  const saveAppQuota = async (resetQuota = false) => {
    if (!editItem) return;
    let quotaMb: number | undefined;
    if (!resetQuota) {
      const n = Number(quotaInput);
      if (!Number.isInteger(n) || n < 10 || n > 51200) {
        toast('容量上限需为 10～51200 MB 之间的整数', 'error');
        return;
      }
      quotaMb = n;
    }
    setSavingQuota(true);
    try {
      await api.adminUpdateAppDbQuota(editItem.appId, resetQuota ? { resetQuota: true } : { quotaMb });
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['admin', 'database-usage'] }),
        qc.invalidateQueries({ queryKey: qk.adminOverview }),
      ]);
      toast(resetQuota ? '已恢复继承工作空间/默认数据库配额' : `已将「${editItem.appName}」数据库配额调整为 ${quotaMb} MB`, 'ok');
      setEditItem(null);
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setSavingQuota(false);
    }
  };

  const summary = data?.summary;
  const defaults = data?.defaults;

  return (
    <div className="dash-panel">
      <DashPanelHead
        icon="database"
        tone="indigo"
        title={`数据库容量与防刷治理（${summary?.totalApps ?? 0}）`}
        sub={
          defaults
            ? `默认限额：共享云库 ${defaults.cloudDefaultQuotaMb} MB / 单表 ${defaults.cloudMaxRowsPerTable.toLocaleString()} 行 / 单行 ${Math.round(defaults.cloudMaxRowBytes / 1024)} KB / ${defaults.cloudWritesPerSecond}次每秒 · 托管 PG ${defaults.serviceDefaultQuotaMb} MB / ${defaults.serviceRoleConnectionLimit} 连接`
            : '监控共享云服务与全栈托管 PostgreSQL 容量占用，支持扩容、超限自动只读熔断与一键限写'
        }
        right={
          <div className="dash-toolbar-group">
            <Select
              ariaLabel="按数据库类型筛选"
              value={kindFilter}
              onChange={(v) => setKindFilter(v as typeof kindFilter)}
              options={[
                { value: 'all', label: `全部库 (${summary?.totalApps ?? 0})` },
                { value: 'cloud', label: `共享云库 (${summary?.cloudCount ?? 0})` },
                { value: 'service', label: `全栈托管 (${summary?.serviceCount ?? 0})` },
              ]}
            />
            <Select
              ariaLabel="按容量状态筛选"
              value={statusFilter}
              onChange={(v) => setStatusFilter(v as typeof statusFilter)}
              options={[
                { value: 'all', label: '全部状态' },
                { value: 'warning', label: `预警 (${summary?.warningCount ?? 0})` },
                { value: 'blocked', label: `已限写 (${summary?.blockedCount ?? 0})` },
                { value: 'ok', label: '正常' },
              ]}
            />
            <label className="dash-search">
              <Icon name="search" />
              <input
                aria-label="搜索应用名、Schema 或归属人"
                placeholder="搜应用 / Schema / 归属人…"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
            </label>
            <button
              type="button"
              className="subtle-button dash-compact-btn"
              disabled={scanning || dbQ.isFetching}
              onClick={() => void triggerScan()}
            >
              <Icon name="refresh" className={scanning ? 'spinning' : ''} />
              {scanning ? '巡检中…' : '立即巡检'}
            </button>
          </div>
        }
      />

      {summary ? (
        <div className="dbq-summary-strip">
          <div className="dbq-kpi">
            <span>全平台应用库总占用</span>
            <b>{fmtBytes(summary.totalUsedBytes)}</b>
            <small>总配额池 {fmtBytes(summary.totalQuotaBytes)}</small>
          </div>
          <div className="dbq-kpi">
            <span>纳管应用库</span>
            <b>{summary.totalApps}</b>
            <small>共享云库 {summary.cloudCount} · 托管 PG {summary.serviceCount}</small>
          </div>
          <div className={`dbq-kpi ${summary.warningCount > 0 ? 'is-warn' : ''}`}>
            <span>高水位预警（≥80%）</span>
            <b>{summary.warningCount}</b>
            <small>达 100% 自动切只读保留删表权限</small>
          </div>
          <div className={`dbq-kpi ${summary.blockedCount > 0 ? 'is-danger' : ''}`}>
            <span>已限写 / 熔断</span>
            <b>{summary.blockedCount}</b>
            <small>{summary.lastScannedAt ? `最近巡检 ${fmtTime(summary.lastScannedAt)}` : '每 2 分钟自动巡检'}</small>
          </div>
        </div>
      ) : null}

      {dbQ.isLoading && !data ? (
        <div className="gov-healthy-banner muted">
          <span className="gov-healthy-icon"><Icon name="refresh" className="spinning" /></span>
          <div className="gov-healthy-copy"><b>正在读取各应用 Schema 容量占用…</b></div>
        </div>
      ) : filtered.length === 0 ? (
        <div className="gov-healthy-banner muted">
          <span className="gov-healthy-icon"><Icon name="database" /></span>
          <div className="gov-healthy-copy">
            <b>{items.length === 0 ? '当前暂无已启用云数据库或托管 PG 的应用' : '当前筛选条件下没有匹配的应用库'}</b>
            <span>应用启用共享云服务或托管 PostgreSQL Schema 后会自动纳入容量巡检与防刷保护。</span>
          </div>
        </div>
      ) : (
        <>
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th scope="col">应用 / Schema</th>
                  <th scope="col">归属人</th>
                  <th scope="col">容量占用（Top 排行）</th>
                  <th scope="col">表 / 估算行数</th>
                  <th scope="col">状态</th>
                  <th scope="col">治理操作</th>
                </tr>
              </thead>
              <tbody>
                {pager.pageItems.map((item) => {
                  const owner = formatOwnerIdentity({
                    ownerRealName: item.ownerRealName,
                    ownerLogin: item.ownerLogin,
                    ownerName: item.ownerName,
                    workspaceId: item.workspaceId,
                  });
                  const st = STATUS_META[item.status] ?? STATUS_META.ok;
                  return (
                    <tr key={item.appId}>
                      <td>
                        <div className="dbq-app-cell">
                          <div className="dbq-app-title">
                            <b>{item.appName}</b>
                            <span className={`dbq-kind-tag is-${item.kind}`}>
                              {item.kind === 'service' ? '托管 PG' : '共享云库'}
                            </span>
                            {item.customQuotaMb != null ? (
                              <span className="dbq-custom-tag" title="单应用自定义容量">自定义</span>
                            ) : null}
                          </div>
                          <code>{item.schema}</code>
                        </div>
                      </td>
                      <td>
                        <div className="owner-cell">
                          <div className="owner-cell-main">
                            {owner.bound ? (
                              <>
                                <span className="owner-cell-name">{owner.primary}</span>
                                {owner.account ? <code className="owner-cell-acct">{owner.account}</code> : null}
                              </>
                            ) : (
                              <span className="owner-cell-anon">未绑定账号</span>
                            )}
                          </div>
                          <div className="owner-cell-ws"><code>{item.workspaceId}</code></div>
                        </div>
                      </td>
                      <td>
                        <div className="dbq-bar-cell">
                          <div className="dbq-bar-head">
                            <b>{fmtBytes(item.usedBytes)}</b>
                            <span>/ {item.quotaMb} MB ({item.usagePercent}%)</span>
                          </div>
                          <div className={`dbq-bar-track ${st.cls}`}>
                            <i style={{ width: `${Math.min(100, Math.max(item.usedBytes > 0 ? 2 : 0, item.usagePercent))}%` }} />
                          </div>
                        </div>
                      </td>
                      <td className="dash-nowrap">
                        <span>{item.tableCount} 表</span>
                        <span className="dash-muted"> · ~{item.rowEstimate.toLocaleString()} 行</span>
                      </td>
                      <td className="dash-nowrap">
                        <span className={`dbq-status-pill ${st.cls}`} title={item.blockedReason ?? undefined}>
                          {st.label}
                        </span>
                      </td>
                      <td className="dash-nowrap">
                        <div className="dash-action-row">
                          <button type="button" className="dash-link" onClick={() => openQuotaDrawer(item)}>
                            调配额
                          </button>
                          <button
                            type="button"
                            className={`dash-link ${item.writeBlocked ? '' : 'danger'}`}
                            disabled={busyApp === item.appId}
                            onClick={() => void toggleBlock(item)}
                          >
                            {busyApp === item.appId ? '处理中…' : item.writeBlocked ? '恢复写入' : '限制写入'}
                          </button>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <Pagination
            page={pager.page}
            pageSize={pager.pageSize}
            total={pager.total}
            onPageChange={pager.setPage}
            onPageSizeChange={pager.setPageSize}
            pageSizeOptions={[10, 20, 50]}
          />
        </>
      )}

      {editItem ? (
        <DetailDrawer
          open
          width={460}
          title="调整单应用数据库配额"
          badge={<code className="owner-cell-acct">{editItem.schema}</code>}
          sub={`${editItem.appName}（${editItem.kind === 'service' ? '全栈托管 PG' : '共享云服务'}）`}
          onClose={() => setEditItem(null)}
          footerHint="若因超限自动限写，扩容后将自动解除写入限制"
          footer={
            <>
              {editItem.customQuotaMb != null ? (
                <button
                  type="button"
                  className="subtle-button"
                  disabled={savingQuota}
                  onClick={() => void saveAppQuota(true)}
                >
                  恢复继承默认
                </button>
              ) : null}
              <button type="button" className="subtle-button" onClick={() => setEditItem(null)}>
                取消
              </button>
              <button type="button" className="primary-button" disabled={savingQuota} onClick={() => void saveAppQuota(false)}>
                {savingQuota ? '保存中…' : '保存容量配额'}
              </button>
            </>
          }
        >
          <div className="dash-modal-form">
            <div className="dash-modal-target xa-drawer-target-card">
              当前已用：<b>{fmtBytes(editItem.usedBytes)}</b> · 当前上限：<b>{editItem.quotaMb} MB</b>（{editItem.usagePercent}%）
            </div>
            <label>
              <span>单应用数据库容量上限（MB，范围 10～51200）</span>
              <input
                type="number"
                min={10}
                max={51200}
                value={quotaInput}
                onChange={(e) => setQuotaInput(e.target.value)}
              />
            </label>
          </div>
        </DetailDrawer>
      ) : null}
    </div>
  );
}
