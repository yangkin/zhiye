import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type { AdminPublishRequestItem } from '../api/types';
import { fmtTime } from '../lib/format';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { DetailDrawer } from './DetailDrawer';
import { DashEmptyBox, DashPanelHead } from './DashboardCharts';
import { PublishAccessSummaryCard, VibeRegistrationReadonlyTable, formatChineseDate } from '../workbench/PublishModal';

const STATUS_META: Record<string, { label: string; tone: string }> = {
  pending: { label: '待审核', tone: 'warn' },
  approved: { label: '已通过并上线', tone: 'ok' },
  rejected: { label: '已驳回', tone: 'err' },
  cancelled: { label: '已撤回', tone: 'muted' },
};

const ACCESS_LABELS: Record<string, string> = {
  public: '内网公开',
  login: '员工登录',
  members: '指定人员',
};

function formatMemberName(m: { loginName: string; realName?: string | null }) {
  return m.realName ? `${m.realName}(${m.loginName})` : m.loginName;
}

function membersPreview(item: AdminPublishRequestItem): { summary: string; title: string } | null {
  if (item.accessLevel !== 'members') return null;
  const list = item.accessMemberDetails?.length
    ? item.accessMemberDetails.map(formatMemberName)
    : (item.accessMembers ?? []);
  if (!list.length) return { summary: '未填写名单', title: '未填写指定访问人员名单' };
  const head = list.slice(0, 3).join('、');
  return {
    summary: list.length > 3 ? `${head} 等 ${list.length} 人` : head,
    title: `指定人员（${list.length} 人）：${list.join('、')}`,
  };
}

export function AdminPublishReviewPanel({ onReviewed }: { onReviewed?: () => void }) {
  const qc = useQueryClient();
  const [statusFilter, setStatusFilter] = useState<'all' | 'pending' | 'approved' | 'rejected' | 'cancelled'>('pending');
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<AdminPublishRequestItem | null>(null);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState<'approve' | 'reject' | null>(null);

  const listQ = useQuery({
    queryKey: ['admin-publish-requests', statusFilter, query],
    queryFn: () => api.adminPublishRequests({ status: statusFilter, q: query.trim() || undefined }),
  });

  const data = listQ.data;
  const items = data?.items ?? [];

  const openDetail = (item: AdminPublishRequestItem) => {
    setSelected(item);
    setComment(item.reviewComment ?? '');
  };

  const refresh = async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: ['admin-publish-requests'] }),
      qc.invalidateQueries({ queryKey: qk.adminOverview }),
    ]);
    onReviewed?.();
  };

  const approve = async () => {
    if (!selected || busy) return;
    setBusy('approve');
    try {
      const res = await api.adminApprovePublishRequest(selected.id, comment);
      toast(`已审核通过并正式发布上线：${res.siteUrl || selected.appName}`, 'ok');
      setSelected(null);
      setComment('');
      await refresh();
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusy(null);
    }
  };

  const reject = async () => {
    if (!selected || busy) return;
    if (!comment.trim()) {
      toast('请先填写驳回原因，再点击驳回申请。', 'error');
      return;
    }
    setBusy('reject');
    try {
      await api.adminRejectPublishRequest(selected.id, comment.trim());
      toast('已驳回该发布申请，申请人可在工作台查看驳回意见并修改重提。', 'ok');
      setSelected(null);
      setComment('');
      await refresh();
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="gov-stack">
      <div className="dash-panel">
        <DashPanelHead
          icon="check"
          tone="violet"
          title="应用发布审核（Vibe Coding 应用登记表）"
          sub="所有应用正式发布上线前均需提交登记表，管理员核验业务背景、数据范围与预期收益并审批通过后自动正式上线"
          right={
            <button
              type="button"
              className="subtle-button dash-compact-btn"
              disabled={listQ.isFetching}
              onClick={() => void listQ.refetch()}
            >
              <Icon name="refresh" className={listQ.isFetching ? 'spinning' : ''} /> 刷新列表
            </button>
          }
        />

        <div className="gov-filter-bar" style={{ marginBottom: 14, display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between' }}>
          <div className="review-filter-pills" role="tablist" aria-label="审核状态筛选">
            {(
              [
                ['pending', `待审核 (${data?.pendingCount ?? 0})`],
                ['approved', `已通过 (${data?.approvedCount ?? 0})`],
                ['rejected', `已驳回 (${data?.rejectedCount ?? 0})`],
                ['cancelled', `已撤回 (${data?.cancelledCount ?? 0})`],
                ['all', `全部 (${data?.total ?? 0})`],
              ] as const
            ).map(([k, label]) => (
              <button
                key={k}
                type="button"
                role="tab"
                aria-selected={statusFilter === k}
                className={`review-filter-pill ${statusFilter === k ? 'active' : ''}`}
                onClick={() => setStatusFilter(k)}
              >
                {label}
              </button>
            ))}
          </div>
          <input
            type="search"
            className="review-search-input"
            aria-label="检索发布审核单"
            placeholder="搜索应用名称 / 所属部门 / 联系人 / 指定人员 / 单号…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>

        {listQ.isLoading && !data ? (
          <div className="muted-note" style={{ padding: '20px 0', textAlign: 'center' }}>
            正在加载发布审核列表…
          </div>
        ) : items.length === 0 ? (
          <DashEmptyBox
            icon="check"
            title={statusFilter === 'pending' ? '当前没有待审核的发布申请' : '没有匹配的发布审核记录'}
            hint={statusFilter === 'pending' ? '所有待审应用均已处理完毕。' : '尝试切换状态筛选或清空搜索关键词。'}
          />
        ) : (
          <div className="dash-table-wrap">
            <table className="dash-table">
              <thead>
                <tr>
                  <th>应用名称 / 版本</th>
                  <th>应用所属部门</th>
                  <th>预期用户与范围</th>
                  <th>部署方式 / 权限</th>
                  <th>主备联系人</th>
                  <th>预计使用周期</th>
                  <th>提交时间</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((it) => {
                  const st = STATUS_META[it.status] ?? { label: it.status, tone: 'muted' };
                  return (
                    <tr key={it.id}>
                      <td>
                        <div className="review-app-cell">
                          <b>{it.appName}</b>
                          <small>
                            <code>V{it.versionNumber}</code>
                            {it.framework ? ` · ${it.framework}` : ''}
                            {it.appType ? ` · ${it.appType}` : ''}
                          </small>
                        </div>
                      </td>
                      <td>{it.department}</td>
                      <td>{it.targetUsers}</td>
                      <td>
                        {(() => {
                          const mp = membersPreview(it);
                          const cnt = it.accessMemberDetails?.length ?? it.accessMembers?.length ?? 0;
                          return (
                            <div className="review-app-cell">
                              <span>{it.deployMode}</span>
                              <small>
                                {ACCESS_LABELS[it.accessLevel] ?? it.accessLevel}
                                {it.accessLevel === 'members' ? `（${cnt} 人）` : ''}
                              </small>
                              {mp ? (
                                <span className="review-members-preview" title={mp.title}>
                                  {mp.summary}
                                </span>
                              ) : null}
                            </div>
                          );
                        })()}
                      </td>
                      <td>
                        <div className="review-app-cell">
                          <span>{it.contacts}</span>
                          {it.submitterName && it.submitterName !== it.contacts ? (
                            <small>提交人：{it.submitterName}</small>
                          ) : null}
                        </div>
                      </td>
                      <td style={{ whiteSpace: 'nowrap' }}>{formatChineseDate(it.expectedUntil)}</td>
                      <td style={{ whiteSpace: 'nowrap' }}>{fmtTime(it.createdAt)}</td>
                      <td>
                        <span className={`review-status-pill ${st.tone}`}>{st.label}</span>
                      </td>
                      <td style={{ whiteSpace: 'nowrap' }}>
                        <button
                          type="button"
                          className={it.status === 'pending' ? 'primary-button dash-compact-btn' : 'subtle-button dash-compact-btn'}
                          onClick={() => openDetail(it)}
                        >
                          {it.status === 'pending' ? '审阅与审批' : '查看登记表'}
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

      {selected ? (
        <DetailDrawer
          open
          width={740}
          title={selected.status === 'pending' ? '发布审核 · 查看登记表与审批' : '发布审核记录详情'}
          badge={
            <span className={`review-status-pill ${(STATUS_META[selected.status] ?? { tone: 'muted' }).tone}`}>
              {(STATUS_META[selected.status] ?? { label: selected.status }).label}
            </span>
          }
          sub={`申请单 ${selected.id} · 版本 V${selected.versionNumber} · 提交于 ${fmtTime(selected.createdAt)}`}
          onClose={() => !busy && setSelected(null)}
          footerHint={
            selected.status === 'pending'
              ? '审核通过将立即正式发布该版本上线，并写入平台审计日志'
              : selected.reviewedAt
                ? `审核人 ${selected.reviewerActor || '—'} · ${fmtTime(selected.reviewedAt)}`
                : undefined
          }
          footer={
            selected.status === 'pending' ? (
              <>
                <button type="button" className="subtle-button" disabled={!!busy} onClick={() => setSelected(null)}>
                  取消
                </button>
                <button
                  type="button"
                  className="danger-button"
                  disabled={!!busy}
                  onClick={() => void reject()}
                >
                  {busy === 'reject' ? '驳回中…' : '驳回申请'}
                </button>
                <button
                  type="button"
                  className="primary-button"
                  disabled={!!busy}
                  onClick={() => void approve()}
                >
                  {busy === 'approve' ? '发布上线中…' : '审核通过并正式发布'}
                </button>
              </>
            ) : (
              <>
                {selected.publishedSiteUrl ? (
                  <button
                    type="button"
                    className="subtle-button"
                    onClick={() => window.open(selected.publishedSiteUrl!, '_blank', 'noopener')}
                  >
                    打开线上站点 <Icon name="external" />
                  </button>
                ) : null}
                <button type="button" className="primary-button" onClick={() => setSelected(null)}>
                  关闭
                </button>
              </>
            )
          }
        >
          <div className="review-drawer-body">
            <div className="review-drawer-meta">
              <div>
                <span>应用 ID</span>
                <code>{selected.appId}</code>
              </div>
              <div>
                <span>形态 / 框架</span>
                <b>
                  {selected.appType || '—'} · {selected.framework || selected.projectKind || '—'}
                </b>
              </div>
              <div>
                <span>上线访问权限</span>
                <b>
                  {ACCESS_LABELS[selected.accessLevel] ?? selected.accessLevel}
                  {selected.accessLevel === 'members'
                    ? `（${selected.accessMemberDetails?.length ?? selected.accessMembers?.length ?? 0} 人）`
                    : ''}
                </b>
              </div>
              <div className="review-drawer-links">
                {selected.previewUrl ? (
                  <button
                    type="button"
                    className="subtle-button dash-compact-btn"
                    onClick={() => window.open(selected.previewUrl!, '_blank', 'noopener')}
                  >
                    <Icon name="eye" /> 预览待发版本
                  </button>
                ) : null}
                <button
                  type="button"
                  className="subtle-button dash-compact-btn"
                  onClick={() => window.open(`/apps/${selected.appId}`, '_blank', 'noopener')}
                >
                  <Icon name="external" /> 打开工作台
                </button>
              </div>
            </div>

            <PublishAccessSummaryCard item={selected} />

            <VibeRegistrationReadonlyTable item={selected} />

            {selected.status === 'pending' ? (
              <div className="review-decision-box">
                <label className="field-label" htmlFor="admin-review-comment">
                  审核意见 / 驳回原因（通过时选填，驳回时必填）
                </label>
                <textarea
                  id="admin-review-comment"
                  rows={3}
                  maxLength={1000}
                  placeholder="填写审核备注；如驳回请写清需要补充的业务背景、数据合规说明或整改项…"
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                />
              </div>
            ) : selected.reviewComment ? (
              <div className={`publish-review-banner ${selected.status === 'rejected' ? 'rejected' : 'approved'}`}>
                <Icon name={selected.status === 'rejected' ? 'alert' : 'check'} />
                <div>
                  <b>{selected.status === 'rejected' ? '驳回原因' : '审批意见'}</b>
                  <p>{selected.reviewComment}</p>
                </div>
              </div>
            ) : null}
          </div>
        </DetailDrawer>
      ) : null}
    </div>
  );
}
