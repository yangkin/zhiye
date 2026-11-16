import { Fragment, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { api as convApi, type ConversationSummary } from '../api/conversation';
import { toast } from '../lib/store';
import { api, errorMessage, type HistoryQuery } from '../api/client';
import { qk, useApps } from '../api/queries';
import type { HistoryRow, Version } from '../api/types';
import { Icon } from '../lib/icons';
import { dayLabel, fmtDate, fmtTime } from '../lib/format';
import { useDebounced } from '../lib/useDebounced';
import { EmptyState, ErrorState, LoadingState } from '../components/EmptyState';
import { RestoreModal, VersionPreviewModal, VersionRow } from '../components/Versions';
import { Select } from '../components/Select';
import { confirmDialog } from '../components/ConfirmDialog';

type Filter = '全部记录' | '会话记录' | '已收藏' | '线上版本' | '最近删除';

export function HistoryPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const appId = params.get('appId') ?? '';
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Filter>('全部记录');
  const [preview, setPreview] = useState<HistoryRow | null>(null);
  const [restore, setRestore] = useState<HistoryRow | null>(null);
  const q = useDebounced(query.trim());
  const { data: apps } = useApps({ sort: 'updated' });

  const hq: HistoryQuery = {
    q: q || undefined,
    appId: appId || undefined,
    filter: filter === '已收藏' ? 'favorite' : filter === '线上版本' ? 'published' : 'all',
  };
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: qk.history(hq),
    queryFn: () => api.history(hq),
    placeholderData: keepPreviousData,
  });
  const { data: total } = useQuery({ queryKey: qk.history({ filter: 'all' }), queryFn: () => api.history({ filter: 'all' }) });
  const rows = data ?? [];

  const grouped = useMemo(() => {
    const out: { day: string; date: string; rows: HistoryRow[] }[] = [];
    for (const r of rows) {
      const day = dayLabel(r.version.createdAt);
      const last = out[out.length - 1];
      if (last && last.day === day) last.rows.push(r);
      else out.push({ day, date: fmtDate(r.version.createdAt), rows: [r] });
    }
    return out;
  }, [rows]);

  const asRow = (r: HistoryRow, v: Version): HistoryRow => ({ app: r.app, version: v });

  return (
    <div className="content-page history-page">
      <div className="page-heading">
        <div>
          <span className="overline">EVERY STEP TELLS A STORY</span>
          <h1>每一步，都有迹可循。</h1>
          <p>回看创作的轨迹，找回值得保留的灵感。</p>
        </div>
      </div>
      <HistoryStats />
      <div className="collection-toolbar">
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
            <input aria-label="搜索历史" placeholder={filter === '会话记录' ? '搜索会话标题或内容' : '搜索应用或版本标题'} value={query} onChange={(e) => setQuery(e.target.value)} />
            {query ? (
              <button aria-label="清除搜索" onClick={() => setQuery('')}>
                <Icon name="close" />
              </button>
            ) : null}
          </label>
          {filter === '会话记录' ? null : <Select
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
      {filter === '最近删除' ? <TrashList /> : null}
      {filter === '会话记录' ? <ConversationList query={q} /> : null}
      <div className="history-list" hidden={filter === '最近删除' || filter === '会话记录'}>
        {isLoading ? (
          <LoadingState label="正在读取版本记录…" />
        ) : error ? (
          <ErrorState message={errorMessage(error)} onRetry={() => refetch()} />
        ) : (
          <>
            {grouped.map((g) => (
              <Fragment key={g.day + g.date}>
                <div className="history-day">
                  <span>{g.day}</span>
                  <i />
                  <small>{g.date}</small>
                </div>
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
              </Fragment>
            ))}
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
  const items: [string, number][] = [
    ['版本', data.versions],
    ['应用', data.apps],
    ['已发布', data.published],
    ['云服务', data.cloudEnabled],
    ['访问量', data.pageViews],
  ];
  return (
    <div className="history-stats">
      {items.map(([label, value]) => (
        <div key={label} className="history-stat">
          <strong>{value}</strong>
          <span>{label}</span>
        </div>
      ))}
    </div>
  );
}

function TrashList() {
  const { data, isLoading, error } = useQuery({ queryKey: ['apps-trash'], queryFn: () => api.trashApps() });
  if (isLoading) return <LoadingState label="正在读取回收站…" />;
  if (error) return <ErrorState message={errorMessage(error)} />;
  if (!data?.length) return <EmptyState title="回收站是空的" desc="删除应用后会先放到这里。" />;
  return (
    <div className="history-list">
      {data.map((a) => (
        <div key={a.id} className="version-row">
          <div>
            <b>{a.name}</b>
            <small> · {a.type}</small>
          </div>
          <small>{(a as unknown as { deletedAt?: string }).deletedAt ? fmtDate((a as unknown as { deletedAt: string }).deletedAt) : '已删除'}</small>
        </div>
      ))}
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
function ConversationList({ query }: { query: string }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data, isLoading, error, refetch } = useQuery({ queryKey: ['conversations'], queryFn: () => convApi.conversations() });
  const rows = useMemo(() => {
    const k = query.toLowerCase();
    return (data ?? []).filter((c) => !k || c.title.toLowerCase().includes(k) || (c.lastMessage ?? '').toLowerCase().includes(k));
  }, [data, query]);
  const groups = useMemo(() => {
    const out: { day: string; date: string; rows: ConversationSummary[] }[] = [];
    for (const c of rows) {
      const day = dayLabel(c.updatedAt);
      const last = out[out.length - 1];
      if (last && last.day === day) last.rows.push(c);
      else out.push({ day, date: fmtDate(c.updatedAt), rows: [c] });
    }
    return out;
  }, [rows]);
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
        <Fragment key={g.day + g.date}>
          <div className="history-day">
            <span>{g.day}</span>
            <i />
            <small>{g.date}</small>
          </div>
          {g.rows.map((c) => (
            <article key={c.id} className="version-row conversation-row">
              <span className="timeline-dot"><Icon name="agent" /></span>
              <div className="version-main">
                <div className="version-top">
                  <button className="version-number conversation-row-title" onClick={() => navigate('/chat/' + c.id)}>{c.title}</button>
                  {c.appId ? (
                    <button className="version-app" onClick={() => navigate('/apps/' + c.appId)} title="打开创建的应用">{c.appName}</button>
                  ) : null}
                  {c.fileCount ? <span className="tiny-badge">{c.fileCount} 个文件</span> : null}
                  <small>{fmtTime(c.updatedAt)}</small>
                </div>
                <h3>{snippet(c.lastMessage) || '还没有消息'}</h3>
                <div className="version-author">{c.messageCount ?? 0} 条消息</div>
              </div>
              <div className="version-actions">
                <button className="text-button" aria-label={`打开会话 ${c.title}`} onClick={() => navigate('/chat/' + c.id)}>
                  <Icon name="external" />
                  <span>打开</span>
                </button>
                <button className="text-button" aria-label={`删除会话 ${c.title}`} onClick={() => void remove(c)}>
                  <Icon name="trash" />
                  <span>删除</span>
                </button>
              </div>
            </article>
          ))}
        </Fragment>
      ))}
    </div>
  );
}
