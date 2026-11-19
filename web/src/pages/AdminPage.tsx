import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk, useMeta } from '../api/queries';
import { Icon } from '../lib/icons';
import { EmptyState, ErrorState, LoadingState } from '../components/EmptyState';
import {
  AdminGovernancePanel,
  AdminQuotaModal,
  type QuotaModalTarget,
} from '../components/AdminGovernancePanel';

export function AdminPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const metaQ = useMeta();
  const isAdmin = Boolean(metaQ.data?.auth?.admin);
  const [quotaTarget, setQuotaTarget] = useState<QuotaModalTarget | null>(null);

  const adminQ = useQuery({
    queryKey: qk.adminOverview,
    queryFn: api.adminOverview,
    enabled: isAdmin,
  });

  const dashQ = useQuery({
    queryKey: qk.dashboard({ scope: 'platform', range: '30d' }),
    queryFn: () => api.dashboard({ scope: 'platform', range: '30d' }),
    enabled: isAdmin,
  });

  const refreshAll = async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: qk.adminOverview }),
      qc.invalidateQueries({ queryKey: ['dashboard'] }),
    ]);
  };

  if (metaQ.isPending) {
    return (
      <div className="content-page dash-page">
        <LoadingState label="正在校验平台管理员权限…" />
      </div>
    );
  }

  if (!isAdmin) {
    return (
      <div className="content-page dash-page">
        <EmptyState
          icon="lock"
          title="仅平台管理员可访问管理工作台"
          desc="当前工作空间未配置管理员权限。如需开启管理权限，可将空间 ID 加入 ZHIYE_ADMIN_ALLOW_WORKSPACES 或通过 SSO 管理员账号登录。"
        />
      </div>
    );
  }

  const overview = adminQ.data;

  return (
    <div className="content-page dash-page">
      <header className="dash-header-top">
        <div className="dash-title-block">
          <span className="overline">ZHIYE ENTERPRISE GOVERNANCE &amp; ASSET CONSOLE</span>
          <div className="dash-title-row">
            <h1>织页平台管理工作台</h1>
            <span className="dash-scope-badge platform">
              <i className="dash-pulse-dot" aria-hidden="true" />
              平台管理员专享
            </span>
            {overview ? (
              <span className="gov-actor-chip">
                操作人 <code>{overview.currentActor}</code>
              </span>
            ) : null}
          </div>
          <p>
            集中处理人员离职与空间资产批量交接、单应用归属转移、线上站点权限风控、社区与反馈工单分诊、工作空间配额覆盖及操作审计留痕。
          </p>
        </div>
        <div className="dash-header-cta">
          <button type="button" className="dash-action-btn" onClick={() => navigate('/dashboard')}>
            <Icon name="chart" />
            <span>统计看板</span>
          </button>
          <button type="button" className="dash-action-btn" onClick={() => navigate('/logs')}>
            <Icon name="terminal" />
            <span>日志排查</span>
          </button>
          <button
            type="button"
            className="dash-action-btn primary"
            disabled={adminQ.isFetching}
            onClick={() => void refreshAll()}
          >
            <Icon name="refresh" className={adminQ.isFetching ? 'spinning' : ''} />
            <span>{adminQ.isFetching ? '刷新中…' : '刷新台账'}</span>
          </button>
        </div>
      </header>

      {adminQ.isLoading && !overview ? (
        <LoadingState label="正在加载平台资产台账与治理数据…" />
      ) : adminQ.error && !overview ? (
        <ErrorState message={errorMessage(adminQ.error)} onRetry={() => void adminQ.refetch()} />
      ) : (
        <div className="dash-body">
          <AdminGovernancePanel
            overview={overview}
            loading={adminQ.isLoading}
            error={adminQ.error}
            onRetry={() => void adminQ.refetch()}
            dashboard={dashQ.data}
            onOpenQuotaModal={(target) => setQuotaTarget(target)}
            standalone
          />
        </div>
      )}

      {quotaTarget && overview ? (
        <AdminQuotaModal
          target={quotaTarget}
          defaultQuota={overview.defaultQuota}
          onClose={() => setQuotaTarget(null)}
          onSaved={refreshAll}
        />
      ) : null}
    </div>
  );
}
