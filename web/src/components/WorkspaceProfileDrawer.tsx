import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { CHART_PALETTE, DonutChart, fmtDurationMs, fmtNum } from './DashboardCharts';
import { DetailDrawer } from './DetailDrawer';
import { formatOwnerIdentity } from './OwnerPicker';
import { fmtTime } from '../lib/format';

const TASK_TYPE_CN: Record<string, string> = {
  plan: '方案规划',
  generate: '首次生成',
  create: '首次生成',
  modify: '对话迭代',
  chat: '对话迭代',
  import: '代码导入',
  import_code: '代码导入',
  visual_save: '可视化编辑',
  code_save: '代码直编',
  fix: '一键修复',
  clone: '社区复刻',
  template_clone: '社区复刻',
};

/** 员工 / 工作空间 360° 细查右侧滑出抽屉（对照 X-APM 用户细查 + 右侧配置抽屉） */
export function WorkspaceProfileDrawer({
  workspaceId,
  onClose,
  onStartHandover,
}: {
  workspaceId: string | null;
  onClose: () => void;
  onStartHandover: (wsId: string) => void;
}) {
  const navigate = useNavigate();
  const [timelineFilter, setTimelineFilter] = useState<'all' | 'failed'>('all');

  const q = useQuery({
    queryKey: ['admin-workspace-profile', workspaceId],
    queryFn: () => api.adminWorkspaceProfile(workspaceId!),
    enabled: Boolean(workspaceId),
    staleTime: 15_000,
  });

  const data = q.data;
  const ownerId = data
    ? formatOwnerIdentity({
        ownerRealName: data.owner.ownerRealName,
        ownerLogin: data.owner.ownerLogin,
        ownerName: data.owner.ownerName,
        workspaceId: data.owner.workspaceId,
      })
    : null;

  const filteredTimeline = useMemo(() => {
    const list = data?.timeline ?? [];
    if (timelineFilter === 'failed') return list.filter((t) => t.status === 'FAILED');
    return list;
  }, [data?.timeline, timelineFilter]);

  const daily = data?.daily30d ?? [];
  const maxDaily = Math.max(1, ...daily.map((d) => d.tasks + d.pv));

  return (
    <DetailDrawer
      open={Boolean(workspaceId)}
      width={680}
      onClose={onClose}
      title={
        ownerId ? (
          ownerId.bound ? (
            <>
              <span>{ownerId.primary}</span>
              {ownerId.account ? <code className="owner-cell-acct">{ownerId.account}</code> : null}
            </>
          ) : (
            <span>未绑定账号（匿名空间）</span>
          )
        ) : (
          '员工与空间 360° 档案'
        )
      }
      badge={
        data?.owner.ownerBound ? (
          <span className="dash-pill ok">SSO 已绑定</span>
        ) : data ? (
          <span className="dash-tag">匿名空间</span>
        ) : null
      }
      sub={
        data ? (
          <>
            空间编码 <code>{data.owner.workspaceId}</code>
            {data.owner.ownerEmail ? ` · ${data.owner.ownerEmail}` : ''}
            {data.owner.lastLoginAt ? ` · 最近登录 ${fmtTime(data.owner.lastLoginAt)}` : ''}
          </>
        ) : (
          workspaceId
        )
      }
      footerHint="所有转移与离职交接操作均实时写入不可篡改审计日志，线上站点与云表数据 0 中断。"
      footer={
        <>
          <button type="button" className="subtle-button" onClick={onClose}>
            关闭
          </button>
          {workspaceId ? (
            <button
              type="button"
              className="primary-button"
              onClick={() => {
                onClose();
                onStartHandover(workspaceId);
              }}
            >
              发起离职 / 批量交接
            </button>
          ) : null}
        </>
      }
    >
      {q.isLoading ? (
        <div className="xa-drawer-loading">正在加载员工近 30 天资产画像与活动时间线…</div>
      ) : q.error || !data ? (
        <div className="xa-drawer-error">{errorMessage(q.error)}</div>
      ) : (
        <div className="xa-profile-stack">
          {/* 1. 顶部近 30 天活跃柱图 + 4 项核心指标（对照 X-APM 右侧抽屉顶部趋势栏） */}
          <div className="xa-drawer-hero">
            <div className="xa-drawer-kpis">
              <div>
                <span>近 30 天活跃</span>
                <b>{data.owner.activeDays30d} 天</b>
              </div>
              <div>
                <span>在用 / 已发布</span>
                <b>
                  {fmtNum(data.owner.appCount)} / {fmtNum(data.owner.publishedCount)}
                </b>
              </div>
              <div>
                <span>近 30 天任务 (成功率)</span>
                <b>
                  {fmtNum(data.owner.totalTasks30d)} ({data.owner.taskSuccessRate30d}%)
                </b>
              </div>
              <div>
                <span>近 30 天累计 PV</span>
                <b>{fmtNum(data.owner.totalPv30d)}</b>
              </div>
            </div>
            <div className="xa-drawer-bars-head">
              <span>近 30 天每日活跃（AI 任务 + 访问 PV）</span>
              <small>
                {daily[0]?.date.slice(5)} ~ {daily[daily.length - 1]?.date.slice(5)}
              </small>
            </div>
            <div className="xa-drawer-bars" role="img" aria-label="近 30 天每日活跃柱图">
              {daily.map((d) => {
                const v = d.tasks + d.pv;
                const pct = v > 0 ? Math.max(10, Math.round((v / maxDaily) * 100)) : 4;
                return (
                  <div key={d.date} className="xa-drawer-bar-col" title={`${d.date}：任务 ${d.tasks} · PV ${d.pv}`}>
                    <i className={v > 0 ? 'on' : ''} style={{ height: `${pct}%` }} />
                  </div>
                );
              })}
            </div>
          </div>

          {/* 2. 名下应用资产 + 类型占比环图 */}
          <div className="xa-profile-split">
            <div className="xa-profile-card">
              <div className="xa-profile-card-head">
                <b>名下在用应用（{data.apps.length} 个）</b>
                <small>会话 {fmtNum(data.owner.conversationCount)} · 数据源 {fmtNum(data.owner.dataSourceCount)}</small>
              </div>
              {data.apps.length === 0 ? (
                <div className="xa-empty-inline">当前名下暂无在用应用</div>
              ) : (
                <ul className="xa-profile-app-list">
                  {data.apps.slice(0, 8).map((a) => (
                    <li key={a.id}>
                      <div className="xa-profile-app-main">
                        <b>{a.name}</b>
                        <code>{a.slug}</code>
                        <span className="dash-tag">{a.type}</span>
                      </div>
                      <div className="xa-profile-app-meta">
                        {a.published ? <span className="dash-pill ok">已发布</span> : <span className="dash-tag">{a.phase}</span>}
                        <span>PV {fmtNum(a.totalPv)}</span>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="xa-profile-card">
              <div className="xa-profile-card-head">
                <b>应用类型构成</b>
              </div>
              <DonutChart
                centerLabel="在用应用"
                emptyText="暂无应用"
                slices={data.typeBreakdown.map((x, i) => ({
                  key: x.key,
                  label: x.key,
                  value: x.count,
                  color: CHART_PALETTE[i % CHART_PALETTE.length],
                }))}
              />
            </div>
          </div>

          {/* 3. 活动时间线（高亮失败行，支持一键直达全链路 Trace） */}
          <div className="xa-profile-card">
            <div className="xa-profile-card-head">
              <b>最近创作与构建时间线（{filteredTimeline.length} 条）</b>
              <div className="seg-control small" role="radiogroup" aria-label="时间线筛选">
                <button
                  type="button"
                  role="radio"
                  aria-checked={timelineFilter === 'all'}
                  className={timelineFilter === 'all' ? 'active' : ''}
                  onClick={() => setTimelineFilter('all')}
                >
                  全部 ({data.timeline.length})
                </button>
                <button
                  type="button"
                  role="radio"
                  aria-checked={timelineFilter === 'failed'}
                  className={timelineFilter === 'failed' ? 'active' : ''}
                  onClick={() => setTimelineFilter('failed')}
                >
                  仅失败 ({data.timeline.filter((t) => t.status === 'FAILED').length})
                </button>
              </div>
            </div>
            {filteredTimeline.length === 0 ? (
              <div className="xa-empty-inline">暂无匹配的活动记录</div>
            ) : (
              <ul className="xa-timeline">
                {filteredTimeline.map((ev) => {
                  const isErr = ev.status === 'FAILED';
                  const isOk = ev.status === 'SUCCEEDED';
                  return (
                    <li key={ev.id} className={isErr ? 'is-err' : ''}>
                      <span className={`xa-tl-dot ${isErr ? 'err' : isOk ? 'ok' : ''}`} />
                      <span className="xa-tl-time">{fmtTime(ev.createdAt)}</span>
                      <span className={`xa-tl-tag ${isErr ? 'err' : isOk ? 'ok' : ''}`}>
                        {TASK_TYPE_CN[ev.taskType] || ev.taskType}
                      </span>
                      <div className="xa-tl-desc">
                        <b>{ev.appName || ev.appId || '未命名应用'}</b>
                        {ev.errorCode ? <code className="xa-tl-errcode">{ev.errorCode}</code> : null}
                        {ev.errorMessage ? <small>{ev.errorMessage}</small> : null}
                      </div>
                      <span className="xa-tl-dur">{fmtDurationMs(ev.durationMs)}</span>
                      <button
                        type="button"
                        className="dash-link"
                        onClick={() => {
                          onClose();
                          navigate(`/logs?taskId=${encodeURIComponent(ev.id)}`);
                        }}
                      >
                        Trace
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
          </div>
        </div>
      )}
    </DetailDrawer>
  );
}
