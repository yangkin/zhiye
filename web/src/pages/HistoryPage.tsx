import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { api as convApi, type ConversationSummary } from '../api/conversation';
import { toast } from '../lib/store';
import { api, errorMessage, type HistoryQuery } from '../api/client';
import { qk, useApps, useMeta } from '../api/queries';
import type { HistoryRow, Version } from '../api/types';
import { Icon, type IconName } from '../lib/icons';
import { dayLabel, fmtDate, fmtTime } from '../lib/format';
import { useDebounced } from '../lib/useDebounced';
import { EmptyState, ErrorState, LoadingState } from '../components/EmptyState';
import { RestoreModal, VersionPreviewModal, VersionRow } from '../components/Versions';
import { Select } from '../components/Select';
import { Pagination, useClientPagination } from '../components/Pagination';
import { confirmDialog } from '../components/ConfirmDialog';

type Filter = '全部记录' | '会话记录' | '已收藏' | '线上版本' | '最近删除';
type DatePreset = 'all' | 'today' | '7d' | '30d' | 'custom';

function localYmd(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function presetDates(preset: DatePreset): { startDate: string; endDate: string } {
  if (preset === 'all' || preset === 'custom') return { startDate: '', endDate: '' };
  const now = new Date();
  const end = localYmd(now);
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (preset === '7d') start.setDate(start.getDate() - 6);
  else if (preset === '30d') start.setDate(start.getDate() - 29);
  return { startDate: localYmd(start), endDate: end };
}

function inDateRange(iso: string | undefined | null, startDate: string, endDate: string): boolean {
  if (!startDate && !endDate) return true;
  if (!iso) return false;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return false;
  const ymd = localYmd(d);
  if (startDate && ymd < startDate) return false;
  if (endDate && ymd > endDate) return false;
  return true;
}

export function HistoryPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const appId = params.get('appId') ?? '';
  const initialStart = params.get('startDate') ?? '';
  const initialEnd = params.get('endDate') ?? '';
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('全部记录');
  const [datePreset, setDatePreset] = useState<DatePreset>(initialStart || initialEnd ? 'custom' : 'all');
  const [customStart, setCustomStart] = useState(initialStart);
  const [customEnd, setCustomEnd] = useState(initialEnd);
  const [preview, setPreview] = useState<HistoryRow | null>(null);
  const [restore, setRestore] = useState<HistoryRow | null>(null);
  const q = useDebounced(query.trim());
  const { data: apps } = useApps({ sort: 'updated' });

  const { startDate, endDate } = useMemo(() => {
    if (datePreset === 'custom') {
      // 如果用户先改了结束日期早于开始日期，自动矫正顺序避免后端报 400
      if (customStart && customEnd && customStart > customEnd) {
        return { startDate: customEnd, endDate: customStart };
      }
      return { startDate: customStart, endDate: customEnd };
    }
    return presetDates(datePreset);
  }, [datePreset, customStart, customEnd]);

  const syncDateUrl = (s: string, e: string) => {
    const next = new URLSearchParams(params);
    if (s) next.set('startDate', s);
    else next.delete('startDate');
    if (e) next.set('endDate', e);
    else next.delete('endDate');
    setParams(next, { replace: true });
  };

  const applyDatePreset = (nextPreset: DatePreset) => {
    setDatePreset(nextPreset);
    if (nextPreset === 'all') {
      setCustomStart('');
      setCustomEnd('');
      syncDateUrl('', '');
    } else if (nextPreset === 'custom') {
      const fallback = presetDates('7d');
      const s = customStart || fallback.startDate;
      const e = customEnd || fallback.endDate;
      setCustomStart(s);
      setCustomEnd(e);
      syncDateUrl(s, e);
    } else {
      const r = presetDates(nextPreset);
      syncDateUrl(r.startDate, r.endDate);
    }
  };

  const clearDateFilter = () => {
    setDatePreset('all');
    setCustomStart('');
    setCustomEnd('');
    syncDateUrl('', '');
  };

  const hasDateFilter = Boolean(startDate || endDate);

  const hq: HistoryQuery = {
    q: q || undefined,
    appId: appId || undefined,
    filter: filter === '已收藏' ? 'favorite' : filter === '线上版本' ? 'published' : 'all',
    startDate: startDate || undefined,
    endDate: endDate || undefined,
  };
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: qk.history(hq),
    queryFn: () => api.history(hq),
    placeholderData: keepPreviousData,
  });
  const { data: total } = useQuery({ queryKey: qk.history({ filter: 'all' }), queryFn: () => api.history({ filter: 'all' }) });
  const rows = data ?? [];
  const pager = useClientPagination(rows, 15, `${filter}|${appId}|${q}|${startDate}|${endDate}`);

  const grouped = useMemo(() => {
    const out: { day: string; date: string; rows: HistoryRow[] }[] = [];
    for (const r of pager.pageItems) {
      const day = dayLabel(r.version.createdAt);
      const last = out[out.length - 1];
      if (last && last.day === day) last.rows.push(r);
      else out.push({ day, date: fmtDate(r.version.createdAt), rows: [r] });
    }
    return out;
  }, [pager.pageItems]);

  const asRow = (r: HistoryRow, v: Version): HistoryRow => ({ app: r.app, version: v });

  return (
    <div className="content-page history-page">
      <header className="dash-header-top history-header-compact">
        <div className="dash-title-block">
          <div className="dash-title-row">
            <h1>历史记录</h1>
          </div>
          <p>查看应用版本、对话记录与回收站，支持预览、备注与回滚。</p>
        </div>
      </header>
      <HistoryStats />
      <div className="collection-toolbar history-toolbar-card">
        <div className="filter-tabs">
          {(['全部记录', '会话记录', '已收藏', '线上版本', '最近删除'] as Filter[]).map((f) => (
            <button key={f} className={filter === f ? 'active' : ''} onClick={() => setFilter(f)}>
              {f === '已收藏' ? <Icon name="book" /> : null}
              {f}
            </button>
          ))}
        </div>
        <div className="collection-controls">
          <label className="search-field">
            <Icon name="search" />
            <input aria-label="搜索历史" placeholder={filter === '会话记录' ? '搜索会话标题或内容' : filter === '最近删除' ? '搜索已删除应用名称' : '搜索应用或版本标题'} value={query} onChange={(e) => setQuery(e.target.value)} />
            {query ? (
              <button aria-label="清除搜索" onClick={() => setQuery('')}>
                <Icon name="close" />
              </button>
            ) : null}
          </label>
          <Select
            ariaLabel="按日期筛选"
            value={datePreset}
            onChange={(v) => applyDatePreset(v as DatePreset)}
            options={[
              { value: 'all', label: '全部日期' },
              { value: 'today', label: '今天' },
              { value: '7d', label: '近 7 天' },
              { value: '30d', label: '近 30 天' },
              { value: 'custom', label: '自定义日期' },
            ]}
          />
          {datePreset === 'custom' ? (
            <div className="history-date-range" role="group" aria-label="自定义日期区间">
              <input
                type="date"
                aria-label="开始日期"
                value={customStart}
                max={customEnd || undefined}
                onChange={(e) => {
                  const v = e.target.value;
                  setCustomStart(v);
                  syncDateUrl(v, customEnd);
                }}
              />
              <span className="history-date-sep" aria-hidden="true">至</span>
              <input
                type="date"
                aria-label="结束日期"
                value={customEnd}
                min={customStart || undefined}
                onChange={(e) => {
                  const v = e.target.value;
                  setCustomEnd(v);
                  syncDateUrl(customStart, v);
                }}
              />
              {hasDateFilter ? (
                <button type="button" className="history-date-clear" aria-label="清空日期筛选" onClick={clearDateFilter}>
                  <Icon name="close" />
                </button>
              ) : null}
            </div>
          ) : hasDateFilter ? (
            <button type="button" className="history-date-chip" title="点击清除日期筛选" onClick={clearDateFilter}>
              <span>{startDate === endDate ? startDate : `${startDate.slice(5)} ~ ${endDate.slice(5)}`}</span>
              <Icon name="close" />
            </button>
          ) : null}
          {filter === '会话记录' || filter === '最近删除' ? null : <Select
            ariaLabel="筛选应用"
            value={appId}
            onChange={(v) => {
              const next = new URLSearchParams(params);
              if (v) next.set('appId', v);
              else next.delete('appId');
              setParams(next, { replace: true });
            }}
            options={[
              { value: '', label: '全部应用' },
              ...(apps ?? []).map((a) => ({ value: a.id, label: a.name })),
              ...(appId && apps && !apps.some((a) => a.id === appId) ? [{ value: appId, label: '当前应用' }] : []),
            ]}
          />}
        </div>
      </div>
      {filter === '最近删除' ? <TrashList query={q} startDate={startDate} endDate={endDate} /> : null}
      {filter === '会话记录' ? <ConversationList query={q} startDate={startDate} endDate={endDate} /> : null}
      <div className="history-list" hidden={filter === '最近删除' || filter === '会话记录'}>
        {isLoading ? (
          <LoadingState label="正在读取版本记录…" />
        ) : error ? (
          <ErrorState message={errorMessage(error)} onRetry={() => refetch()} />
        ) : (
          <>
            {grouped.map((g) => (
              <section key={g.day + g.date} className="history-group-card" aria-label={`${g.day} ${g.date}`}>
                <header className="history-group-head">
                  <span className="history-group-day">{g.day}</span>
                  <small className="history-group-meta">{g.date} · {g.rows.length} 条记录</small>
                </header>
                <div className="history-group-rows">
                  {g.rows.map((r) => (
                    <VersionRow
                      key={r.version.id}
                      appId={r.app.id}
                      appName={r.app.name}
                      version={r.version}
                      onOpenApp={() => navigate('/apps/' + r.app.id)}
                      onPreview={() => setPreview(r)}
                      onRestore={() => setRestore(r)}
                    />
                  ))}
                </div>
              </section>
            ))}
            {rows.length > 0 ? (
              <Pagination
                page={pager.page}
                pageSize={pager.pageSize}
                total={pager.total}
                onPageChange={pager.setPage}
                onPageSizeChange={pager.setPageSize}
                pageSizeOptions={[10, 15, 30, 50]}
                alwaysShowSummary
              />
            ) : null}
            {rows.length === 0 ? (
              total && total.length === 0 ? (
                <EmptyState icon="history" title="这里还没有记录" desc="生成应用后，每一个版本都会出现在这里。" canCreate />
              ) : (
                <EmptyState title="这里还没有记录" desc="试试其他关键词，或者取消筛选条件。" />
              )
            ) : null}
          </>
        )}
      </div>
      {preview ? (
        <VersionPreviewModal
          appName={preview.app.name}
          version={preview.version}
          onClose={() => setPreview(null)}
          onRestore={() => {
            setRestore(asRow(preview, preview.version));
            setPreview(null);
          }}
        />
      ) : null}
      {restore ? (
        <RestoreModal
          appId={restore.app.id}
          appName={restore.app.name}
          version={restore.version}
          onClose={() => setRestore(null)}
        />
      ) : null}
    </div>
  );
}


function HistoryStats() {
  const { data } = useQuery({ queryKey: ['history-summary'], queryFn: () => api.historySummary() });
  if (!data) return null;
  const items: { label: string; value: number; icon: IconName; tone: 'violet' | 'indigo' | 'teal' | 'amber' | 'rose' }[] = [
    { label: '累计版本', value: data.versions, icon: 'history', tone: 'violet' },
    { label: '在用应用', value: data.apps, icon: 'grid', tone: 'indigo' },
    { label: '已发布站点', value: data.published, icon: 'globe', tone: 'teal' },
    { label: '已启云服务', value: data.cloudEnabled, icon: 'cloud', tone: 'amber' },
    { label: '累计访问 PV', value: data.pageViews, icon: 'chart', tone: 'rose' },
  ];
  return (
    <div className="history-stats-bar">
      {items.map((item) => (
        <div key={item.label} className="history-stat-cell">
          <span className={`dash-head-icon ${item.tone} sm`} aria-hidden="true">
            <Icon name={item.icon} />
          </span>
          <div className="history-stat-copy">
            <span>{item.label}</span>
            <strong>{item.value.toLocaleString('zh-CN')}</strong>
          </div>
        </div>
      ))}
    </div>
  );
}

function TrashList({ query, startDate, endDate }: { query: string; startDate: string; endDate: string }) {
  const qc = useQueryClient();
  const isAdmin = Boolean(useMeta().data?.auth?.admin);
  const [busyId, setBusyId] = useState<string | null>(null);
  const { data, isLoading, error } = useQuery({ queryKey: ['apps-trash'], queryFn: () => api.trashApps() });

  const filtered = useMemo(() => {
    const k = query.toLowerCase();
    return (data ?? []).filter((a) => {
      if (k && !a.name.toLowerCase().includes(k) && !a.type.toLowerCase().includes(k)) return false;
      const deletedAt = (a as unknown as { deletedAt?: string }).deletedAt ?? a.updatedAt;
      return inDateRange(deletedAt, startDate, endDate);
    });
  }, [data, query, startDate, endDate]);
  const pager = useClientPagination(filtered, 15, `${query}|${startDate}|${endDate}`);

  if (isLoading) return <LoadingState label="正在读取回收站…" />;
  if (error) return <ErrorState message={errorMessage(error)} />;
  if (!filtered.length)
    return data?.length ? (
      <EmptyState title="没有匹配的已删除应用" desc="试试其他关键词。" />
    ) : (
      <EmptyState icon="trash" title="回收站是空的" desc="删除应用后会先放到这里保留历史。" />
    );

  const restoreApp = async (id: string, name: string) => {
    const ok = await confirmDialog({
      title: `恢复应用「${name}」？`,
      message: '恢复后应用及历史版本将重新回到你的工作空间。',
      confirmLabel: '一键恢复',
    });
    if (!ok) return;
    setBusyId(id);
    try {
      await api.adminRestoreApp(id);
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['apps-trash'] }),
        qc.invalidateQueries({ queryKey: ['apps'] }),
        qc.invalidateQueries({ queryKey: ['history'] }),
        qc.invalidateQueries({ queryKey: ['history-summary'] }),
      ]);
      toast(`已恢复「${name}」`, 'ok');
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="history-list">
      <section className="history-group-card" aria-label="最近删除的应用">
        <header className="history-group-head">
          <span className="history-group-day">最近删除</span>
          <small className="history-group-meta">共 {filtered.length} 个应用</small>
        </header>
        <div className="history-group-rows">
          {pager.pageItems.map((a) => {
            const deletedAt = (a as unknown as { deletedAt?: string }).deletedAt;
            return (
              <article key={a.id} className="version-row">
                <span className="version-rail-badge trash">
                  <Icon name="trash" />
                </span>
                <div className="version-main">
                  <div className="version-headline">
                    <h3>{a.name}</h3>
                    <span className="tiny-badge">{a.type}</span>
                  </div>
                  <div className="version-author">
                    {deletedAt ? `删除于 ${fmtDate(deletedAt)} ${fmtTime(deletedAt)}` : '已移入回收站'}
                  </div>
                </div>
                <div className="version-actions">
                  {isAdmin ? (
                    <button
                      type="button"
                      className="text-button"
                      disabled={busyId === a.id}
                      onClick={() => void restoreApp(a.id, a.name)}
                    >
                      <Icon name="undo" />
                      <span>恢复</span>
                    </button>
                  ) : (
                    <span className="dash-muted" style={{ fontSize: 12 }}>可联系管理员恢复</span>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      </section>
      <Pagination
        page={pager.page}
        pageSize={pager.pageSize}
        total={pager.total}
        onPageChange={pager.setPage}
        onPageSizeChange={pager.setPageSize}
        pageSizeOptions={[10, 15, 30, 50]}
        alwaysShowSummary
      />
    </div>
  );
}

/** 会话摘要里去掉文件正文和界面标记，文件只留名字 */
const snippet = (text = '') =>
  text
    .replace(/\[\[file:([^\]\n]+)\]\][\s\S]*?(\[\[\/file\]\]|$)/g, '〔文件 $1〕')
    .replace(/^\s*\[\[next\]\].*$/gm, '')
    .replace(/\[\[build(?::[^\]]*)?\]\]/g, '')
    .replace(/[#*`>|]/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 120);

/** 创造页的会话：没建应用的也在这里，点开回到会话继续 */
function ConversationList({ query, startDate, endDate }: { query: string; startDate: string; endDate: string }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data, isLoading, error, refetch } = useQuery({ queryKey: ['conversations'], queryFn: () => convApi.conversations() });
  const rows = useMemo(() => {
    const k = query.toLowerCase();
    return (data ?? []).filter((c) => {
      if (k && !c.title.toLowerCase().includes(k) && !(c.lastMessage ?? '').toLowerCase().includes(k)) return false;
      return inDateRange(c.updatedAt, startDate, endDate);
    });
  }, [data, query, startDate, endDate]);
  const pager = useClientPagination(rows, 15, `${query}|${startDate}|${endDate}`);
  const groups = useMemo(() => {
    const out: { day: string; date: string; rows: ConversationSummary[] }[] = [];
    for (const c of pager.pageItems) {
      const day = dayLabel(c.updatedAt);
      const last = out[out.length - 1];
      if (last && last.day === day) last.rows.push(c);
      else out.push({ day, date: fmtDate(c.updatedAt), rows: [c] });
    }
    return out;
  }, [pager.pageItems]);
  const remove = async (c: ConversationSummary) => {
    if (!(await confirmDialog({ title: `删除会话「${c.title}」？`, message: '此操作不可撤销。', confirmLabel: '删除', danger: true }))) return;
    try {
      await convApi.deleteConversation(c.id);
      await qc.invalidateQueries({ queryKey: ['conversations'] });
      toast('会话已删除。');
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };
  if (isLoading) return <LoadingState label="正在读取会话…" />;
  if (error) return <ErrorState message={errorMessage(error)} onRetry={() => refetch()} />;
  if (!rows.length)
    return data?.length ? <EmptyState title="没有匹配的会话" desc="试试其他关键词。" /> : <EmptyState icon="history" title="还没有会话" desc="在创造页发起的每段对话都会出现在这里，包括只写了文档、没有建应用的。" canCreate />;
  return (
    <div className="history-list">
      {groups.map((g) => (
        <section key={g.day + g.date} className="history-group-card" aria-label={`${g.day} 会话`}>
          <header className="history-group-head">
            <span className="history-group-day">{g.day}</span>
            <small className="history-group-meta">{g.date} · {g.rows.length} 段会话</small>
          </header>
          <div className="history-group-rows">
            {g.rows.map((c) => (
              <article key={c.id} className="version-row conversation-row">
                <span className="version-rail-badge conv">
                  <Icon name="agent" />
                </span>
                <div className="version-main">
                  <div className="version-headline">
                    <button type="button" className="conversation-title-link" onClick={() => navigate('/chat/' + c.id)}>
                      {c.title}
                    </button>
                    {c.fileCount ? <span className="tiny-badge">{c.fileCount} 个文件</span> : null}
                  </div>
                  <p className="conversation-snippet">{snippet(c.lastMessage) || '还没有消息'}</p>
                  <div className="version-author">
                    {c.appId ? (
                      <>
                        <button type="button" className="version-app-chip" onClick={() => navigate('/apps/' + c.appId)} title="打开创建的应用">
                          <Icon name="grid" />
                          <span>{c.appName}</span>
                        </button>
                        <span className="version-meta-sep" aria-hidden="true">·</span>
                      </>
                    ) : null}
                    <span>{c.messageCount ?? 0} 条消息</span>
                    <span className="version-meta-sep" aria-hidden="true">·</span>
                    <time>{fmtTime(c.updatedAt)}</time>
                  </div>
                </div>
                <div className="version-actions">
                  <button type="button" className="text-button" aria-label={`打开会话 ${c.title}`} onClick={() => navigate('/chat/' + c.id)}>
                    <Icon name="external" />
                    <span>打开</span>
                  </button>
                  <button type="button" className="text-button" aria-label={`删除会话 ${c.title}`} onClick={() => void remove(c)}>
                    <Icon name="trash" />
                    <span>删除</span>
                  </button>
                </div>
              </article>
            ))}
          </div>
        </section>
      ))}
      <Pagination
        page={pager.page}
        pageSize={pager.pageSize}
        total={pager.total}
        onPageChange={pager.setPage}
        onPageSizeChange={pager.setPageSize}
        pageSizeOptions={[10, 15, 30, 50]}
        alwaysShowSummary
      />
    </div>
  );
}
