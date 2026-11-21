import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, api, errorMessage, type AppListQuery } from '../api/client';
import { qk } from '../api/queries';
import { APP_TYPES, hasNoPreview, isScriptApp, isSkillApp, type AppSummary, type AppType } from '../api/types';
import { Icon, kindIcon } from '../lib/icons';
import { fmtTime, shortDay } from '../lib/format';
import { toast } from '../lib/store';
import { useDebounced } from '../lib/useDebounced';
import { EmptyState, ErrorState, LoadingState } from '../components/EmptyState';
import { PublishWorkModal } from '../components/Community';
import { Modal } from '../components/Modal';
import { RenameModal } from '../components/RenameModal';
import { Thumbnail } from '../components/Thumbnail';
import { StatsModal } from '../components/StatsModal';
import { Select } from '../components/Select';

type Filter = '全部' | AppType | '已收藏';
type Status = '全部' | '已发布' | '未发布';

export function statusLabel(a: AppSummary) {
  if (a.phase === 'planning') return '计划生成中';
  if (a.phase === 'plan') return '计划待确认';
  if (a.phase === 'generating') return '生成中';
  if (a.phase === 'failed') return '生成失败';
  if (a.phase === 'stopped') return '已停止';
  return a.publishedVersionId ? '已发布' : '未发布';
}

export function openAppPreview(a: AppSummary) {
  const url = a.siteUrl ?? a.previewUrl;
  if (!url) {
    if (isSkillApp(a)) return toast('技能包无需网页预览，请进入应用在「代码」页查看。');
    if (isScriptApp(a)) return toast('命令行脚本无需网页预览，请进入应用在「代码」页查看。');
    if (a.phase === 'plan' || a.phase === 'planning') return toast('需求计划尚未确认，确认并生成后即可预览。');
    return toast('应用生成完成后即可打开预览。');
  }
  window.open(url, '_blank', 'noopener');
}

export function AppsPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('全部');
  const [status, setStatus] = useState<Status>('全部');
  const [sort, setSort] = useState<'updated' | 'created' | 'name'>('updated');
  const [menu, setMenu] = useState<string | null>(null);
  const [stats, setStats] = useState<AppSummary | null>(null);
  const [remove, setRemove] = useState<AppSummary | null>(null);
  const [publish, setPublish] = useState<AppSummary | null>(null);
  const summary = useQuery({ queryKey: qk.appsSummary, queryFn: api.appsSummary });
  const [rename, setRename] = useState<AppSummary | null>(null);
  const q = useDebounced(query.trim());

  const params: AppListQuery = {
    q: q || undefined,
    type: filter === '全部' || filter === '已收藏' ? undefined : filter,
    favorite: filter === '已收藏' ? true : undefined,
    published: status === '已发布' ? 'published' : status === '未发布' ? 'unpublished' : 'all',
    sort,
  };
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: qk.apps(params),
    queryFn: () => api.listApps(params),
    placeholderData: keepPreviousData,
  });
  const { data: all } = useQuery({ queryKey: qk.apps({ sort: 'updated' }), queryFn: () => api.listApps({ sort: 'updated' }) });
  const apps = data ?? [];
  const unfiltered = !q && filter === '全部' && status === '全部';

  const toggleFavorite = async (a: AppSummary) => {
    try {
      await api.patchApp(a.id, { favorite: !a.favorite });
      toast(!a.favorite ? '已收藏，喜欢的灵感值得保留。' : '已取消收藏。');
      void qc.invalidateQueries({ queryKey: ['apps'] });
      void qc.invalidateQueries({ queryKey: qk.app(a.id) });
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };

  return (
    <div className="content-page apps-page">
      <div className="page-heading">
        <div>
          <h1>我的应用</h1>
          <p>管理已创建的应用，查看发布状态与访问数据。</p>
        </div>
        <button className="primary-button" onClick={() => navigate('/create')}>
          <Icon name="plus" />
          新建应用
        </button>
      </div>
      <AppsKpiGrid
        used={summary.data?.appsUsed ?? summary.data?.total}
        limit={summary.data?.appLimit}
        published={summary.data?.published}
        totalPv={summary.data?.totalPv}
        loading={summary.isLoading}
        allApps={all ?? []}
        activeStatus={status}
        activeFilter={filter}
        onTogglePublished={() => setStatus((cur) => (cur === '已发布' ? '全部' : '已发布'))}
        onToggleFavorite={() => setFilter((cur) => (cur === '已收藏' ? '全部' : '已收藏'))}
      />
      <div className="collection-toolbar">
        <div className="filter-tabs">
          {(['全部', ...APP_TYPES, '已收藏'] as Filter[]).map((x) => (
            <button key={x} className={filter === x ? 'active' : ''} onClick={() => setFilter(x)}>
              {x === '已收藏' ? <Icon name="star" /> : null}
              {x}
            </button>
          ))}
        </div>
        <div className="collection-controls">
          <label className="search-field">
            <Icon name="search" />
            <input placeholder="搜索应用名称" aria-label="搜索应用" value={query} onChange={(e) => setQuery(e.target.value)} />
            {query ? (
              <button aria-label="清除搜索" onClick={() => setQuery('')}>
                <Icon name="close" />
              </button>
            ) : null}
          </label>
          <Select
            ariaLabel="发布状态"
            value={status}
            onChange={setStatus}
            options={[
              { value: '全部', label: '全部' },
              { value: '已发布', label: '已发布' },
              { value: '未发布', label: '未发布' },
            ]}
          />
        </div>
      </div>
      <div className="results-line">
        <span>
          {apps.length} 个应用{isFetching && !isLoading ? ' · 更新中…' : ''}
        </span>
        <Select
          ariaLabel="排序方式"
          className="compact"
          value={sort}
          onChange={setSort}
          options={[
            { value: 'updated', label: '最近更新' },
            { value: 'created', label: '创建时间' },
            { value: 'name', label: '名称排序' },
          ]}
        />
      </div>
      {isLoading ? (
        <LoadingState label="正在读取你的应用…" />
      ) : error ? (
        <ErrorState message={errorMessage(error)} onRetry={() => refetch()} />
      ) : (
        <>
          <div className="app-grid">
            {apps.map((a) => (
              <article key={a.id} className="app-card">
                <button className="card-cover" aria-label={`打开 ${a.name}`} onClick={() => navigate('/apps/' + a.id)}>
                  <Thumbnail app={a} />
                  <div className="cover-hover">
                    <Icon name="arrow" />
                    继续创作
                  </div>
                </button>
                <div className="app-card-info">
                  <div className="app-card-title">
                    <button onClick={() => navigate('/apps/' + a.id)}>
                      <h2 title={a.name}>{a.name}</h2>
                    </button>
                    <button
                      className={`icon-button star-button ${a.favorite ? 'is-starred' : ''}`}
                      aria-label={`${a.favorite ? '取消收藏' : '收藏'}${a.name}`}
                      aria-pressed={a.favorite}
                      onClick={() => void toggleFavorite(a)}
                    >
                      <Icon name="star" />
                    </button>
                  </div>
                  <p>{a.description || (a.source === 'import' ? `代码导入 · ${a.framework ?? ''}` : '一个正在成形的好想法')}</p>
                  <div className="app-card-meta">
                    <span>
                      <Icon name={kindIcon[a.type]} />
                      {a.type}
                    </span>
                    {isSkillApp(a) ? (
                      <span className="status-badge skill">技能包</span>
                    ) : (
                      <span className={`status-badge ${a.publishedVersionId ? 'published' : ''}`}>{statusLabel(a)}</span>
                    )}
                    {a.cloudEnabled && (
                      <span className="status-badge cloud" title="已启用云服务：数据表、员工登录、云函数">
                        <Icon name="cloud" />
                        云服务
                      </span>
                    )}
                    <small>
                      {shortDay(a.updatedAt)} {fmtTime(a.updatedAt)}
                    </small>
                    <CardMenu
                      app={a}
                      open={menu === a.id}
                      onOpenChange={(o) => setMenu(o ? a.id : null)}
                      onRename={() => setRename(a)}
                      onStats={() => setStats(a)}
                      onPreview={() => openAppPreview(a)}
                      onHistory={() => navigate('/history?appId=' + encodeURIComponent(a.id))}
                      onTpl={() => setPublish(a)}
                      onDelete={() => setRemove(a)}
                    />
                  </div>
                </div>
              </article>
            ))}
            {unfiltered && apps.length > 0 ? (
              <button className="create-card" onClick={() => navigate('/create')}>
                <span>
                  <Icon name="plus" />
                </span>
                <b>下一个好想法</b>
                <p>从一句话开始，让它发生。</p>
              </button>
            ) : null}
          </div>
          {apps.length === 0 ? (
            unfiltered ? (
              <EmptyState title="还没有应用" desc="从一个新想法开始，织页会和你一起把它做出来。" canCreate icon="spark" />
            ) : (
              <EmptyState title="还没有找到这个应用" desc="换个关键词试试，或从一个新想法开始。" canCreate />
            )
          ) : null}
        </>
      )}
      <footer className="page-footer">
        <span>想法常新，创作不止。</span>
        <span>你的工作空间 · {all?.length ?? 0} 个应用</span>
      </footer>
      {stats ? <StatsModal app={stats} onClose={() => setStats(null)} /> : null}
      {remove ? <DeleteAppModal app={remove} onClose={() => setRemove(null)} /> : null}
      {publish ? <PublishWorkModal app={publish} onClose={() => setPublish(null)} /> : null}
      {rename ? <RenameModal appId={rename.id} name={rename.name} onClose={() => setRename(null)} /> : null}
    </div>
  );
}

function AppsKpiGrid({
  used,
  limit,
  published,
  totalPv,
  loading,
  allApps,
  activeStatus,
  activeFilter,
  onTogglePublished,
  onToggleFavorite,
}: {
  used: number | undefined;
  limit: number | undefined;
  published: number | undefined;
  totalPv: number | undefined;
  loading: boolean;
  allApps: AppSummary[];
  activeStatus: Status;
  activeFilter: Filter;
  onTogglePublished: () => void;
  onToggleFavorite: () => void;
}) {
  const u = used ?? allApps.length;
  const pub = published ?? allApps.filter((a) => !!a.publishedVersionId).length;
  const pv = totalPv ?? 0;
  const full = !!limit && u >= limit;
  const quotaPct = limit ? Math.min(100, Math.round((u / limit) * 100)) : 0;
  const pubPct = u > 0 ? Math.min(100, Math.round((pub / u) * 100)) : 0;
  const drafts = Math.max(0, u - pub);
  const cloudCount = allApps.filter((a) => a.cloudEnabled).length;
  const favCount = allApps.filter((a) => a.favorite).length;
  const latestApp = allApps[0];

  const typeBreakdown = APP_TYPES.map((t) => ({ type: t, count: allApps.filter((a) => a.type === t).length }))
    .filter((x) => x.count > 0)
    .slice(0, 3)
    .map((x) => `${x.type} ${x.count}`)
    .join(' · ');

  return (
    <section className="apps-kpi-grid" aria-label="应用概览">
      {/* 1. 应用总数与配额 */}
      <article className={`apps-kpi-card tone-purple ${full ? 'is-full' : ''}`}>
        <div className="apps-kpi-head">
          <span className="apps-kpi-icon">
            <Icon name="grid" />
          </span>
          <span className="apps-kpi-label">应用总数</span>
          {limit && !loading ? (
            <span className={`apps-kpi-badge ${full ? 'danger' : ''}`}>{full ? '已达上限' : `配额 ${quotaPct}%`}</span>
          ) : null}
        </div>
        <div className="apps-kpi-val">
          <b>{loading ? '—' : u.toLocaleString('zh-CN')}</b>
          {limit && !loading ? <small>/ {limit}</small> : null}
        </div>
        {limit ? (
          <div className="apps-kpi-bar" role="progressbar" aria-label="应用数量用量" aria-valuemin={0} aria-valuemax={limit} aria-valuenow={u}>
            <i style={{ width: `${quotaPct}%` }} />
          </div>
        ) : null}
        <p className="apps-kpi-foot">
          {full ? '删除闲置应用后可继续创建新应用' : typeBreakdown || '从一句话开始创建你的第一个应用'}
        </p>
      </article>

      {/* 2. 已发布上线 */}
      <article
        className={`apps-kpi-card tone-emerald is-clickable ${activeStatus === '已发布' ? 'is-selected' : ''}`}
        role="button"
        tabIndex={0}
        onClick={onTogglePublished}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onTogglePublished();
          }
        }}
        title="点击快速筛选已发布应用"
      >
        <div className="apps-kpi-head">
          <span className="apps-kpi-icon">
            <Icon name="globe" />
          </span>
          <span className="apps-kpi-label">已发布上线</span>
          {!loading && u > 0 ? <span className="apps-kpi-badge">上线率 {pubPct}%</span> : null}
        </div>
        <div className="apps-kpi-val">
          <b>{loading ? '—' : pub.toLocaleString('zh-CN')}</b>
          <small>个站点</small>
        </div>
        <div className="apps-kpi-bar">
          <i style={{ width: `${pubPct}%` }} />
        </div>
        <p className="apps-kpi-foot">
          {drafts > 0 ? `未发布草稿 ${drafts} 个 · 点击切换筛选` : '所有应用均已发布上线'}
        </p>
      </article>

      {/* 3. 累计访问量 */}
      <article className="apps-kpi-card tone-blue">
        <div className="apps-kpi-head">
          <span className="apps-kpi-icon">
            <Icon name="eye" />
          </span>
          <span className="apps-kpi-label">总访问量（PV）</span>
          <span className="apps-kpi-badge">{pub > 0 ? `${pub} 站点在线` : '待上线'}</span>
        </div>
        <div className="apps-kpi-val">
          <b>{loading ? '—' : pv.toLocaleString('zh-CN')}</b>
          <small>次浏览</small>
        </div>
        <div className="apps-kpi-spark" aria-hidden="true">
          <span style={{ height: '32%' }} />
          <span style={{ height: '54%' }} />
          <span style={{ height: '42%' }} />
          <span style={{ height: '76%' }} />
          <span style={{ height: '60%' }} />
          <span style={{ height: pv > 0 ? '92%' : '36%' }} />
        </div>
        <p className="apps-kpi-foot">
          {pub > 0 ? '点击应用卡片 ⋯ 可查看单站点明细统计' : '发布应用后自动统计真实访问流量'}
        </p>
      </article>

      {/* 4. 云服务与收藏 */}
      <article
        className={`apps-kpi-card tone-amber is-clickable ${activeFilter === '已收藏' ? 'is-selected' : ''}`}
        role="button"
        tabIndex={0}
        onClick={onToggleFavorite}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onToggleFavorite();
          }
        }}
        title="点击快速筛选已收藏应用"
      >
        <div className="apps-kpi-head">
          <span className="apps-kpi-icon">
            <Icon name="cloud" />
          </span>
          <span className="apps-kpi-label">云服务与收藏</span>
          <span className="apps-kpi-badge">★ 收藏 {favCount}</span>
        </div>
        <div className="apps-kpi-val">
          <b>{loading ? '—' : cloudCount.toLocaleString('zh-CN')}</b>
          <small>个云应用</small>
        </div>
        <div className="apps-kpi-meta-pills">
          <span>数据库 · 权限 · 云函数</span>
        </div>
        <p className="apps-kpi-foot" title={latestApp ? `最近更新：${latestApp.name}` : undefined}>
          {latestApp ? `最近更新：${latestApp.name}` : '启用云服务即可持久化业务数据'}
        </p>
      </article>
    </section>
  );
}

interface CardMenuProps {
  app: AppSummary;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onRename: () => void;
  onStats: () => void;
  onPreview: () => void;
  onHistory: () => void;
  onTpl: () => void;
  onDelete: () => void;
}

/** 卡片 ⋯ 弹出菜单。 */
function CardMenu({ app, open, onOpenChange, onRename, onStats, onPreview, onHistory, onTpl, onDelete }: CardMenuProps) {
  const wrap = useRef<HTMLDivElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const btn = useRef<HTMLButtonElement>(null);
  const change = useRef(onOpenChange);
  change.current = onOpenChange;
  useEffect(() => {
    if (!open) return;
    const down = (e: PointerEvent) => !wrap.current?.contains(e.target as Node) && change.current(false);
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        change.current(false);
        btn.current?.focus();
      }
    };
    document.addEventListener('pointerdown', down, true);
    document.addEventListener('keydown', key);
    requestAnimationFrame(() => menu.current?.querySelector<HTMLElement>('[role="menuitem"]')?.focus());
    return () => {
      document.removeEventListener('pointerdown', down, true);
      document.removeEventListener('keydown', key);
    };
  }, [open]);
  const pick = (fn: () => void) => () => {
    onOpenChange(false);
    fn();
  };
  const canPreview = !hasNoPreview(app) && !!(app.siteUrl || app.previewUrl);
  return (
    <div className="card-menu-wrap" ref={wrap}>
      <button ref={btn} className="icon-button" aria-label={`${app.name}更多操作`} aria-haspopup="menu" aria-expanded={open} onClick={() => onOpenChange(!open)}>
        <Icon name="more" />
      </button>
      {open ? (
        <div
          className="card-menu"
          role="menu"
          aria-label={`${app.name}操作`}
          ref={menu}
          onKeyDown={(e) => {
            if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
            e.preventDefault();
            const items = Array.from(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') ?? []);
            const i = items.indexOf(document.activeElement as HTMLElement);
            items[(i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus();
          }}
        >
          <button role="menuitem" onClick={pick(onRename)}>
            <Icon name="edit" />
            修改标题
          </button>
          <button role="menuitem" onClick={pick(onStats)}>
            <Icon name="grid" />
            数据统计
          </button>
          <button role="menuitem" disabled={!canPreview} onClick={pick(onPreview)}>
            <Icon name="eye" />
            {app.siteUrl ? '打开线上站点' : '打开预览'}
          </button>
          <button role="menuitem" onClick={pick(onHistory)}>
            <Icon name="history" />
            查看版本历史
          </button>
          <button role="menuitem" disabled={app.phase !== 'ready'} onClick={pick(onTpl)}>
            <Icon name="share" />
            发布到灵感社区
          </button>
          <div className="card-menu-divider" role="separator" />
          <button role="menuitem" className="danger" onClick={pick(onDelete)}>
            <Icon name="close" />
            删除应用
          </button>
        </div>
      ) : null}
    </div>
  );
}

function DeleteAppModal({ app, onClose }: { app: AppSummary; onClose: () => void }) {
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const confirm = async () => {
    setBusy(true);
    setError('');
    try {
      await api.deleteApp(app.id);
      toast(`已删除「${app.name}」。`);
      qc.removeQueries({ queryKey: qk.app(app.id) });
      void qc.invalidateQueries({ queryKey: ['apps'] });
      void qc.invalidateQueries({ queryKey: ['history'] });
      onClose();
    } catch (err) {
      setError(err instanceof ApiError && err.code === 'APP_BUSY' ? err.message : errorMessage(err));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={`删除「${app.name}」？`}
      onClose={onClose}
      footer={
        <>
          <button className="subtle-button" onClick={onClose}>
            取消
          </button>
          <button className="danger-button" disabled={busy} onClick={() => void confirm()}>
            {busy ? '正在删除…' : '删除'}
          </button>
        </>
      }
    >
      <p className="notice-box">删除后应用、版本记录和线上地址都会移除，且无法恢复。</p>
      {error ? (
        <p className="inline-error" role="alert">
          {error}
        </p>
      ) : null}
    </Modal>
  );
}
