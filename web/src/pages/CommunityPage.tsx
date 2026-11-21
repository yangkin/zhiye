import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { APP_TYPES, type TemplateSort, type TemplateTab } from '../api/types';
import { Icon } from '../lib/icons';
import { useDebounced } from '../lib/useDebounced';
import { WorkCard } from '../components/Community';
import { EmptyState, ErrorState, LoadingState } from '../components/EmptyState';
import { Select } from '../components/Select';

const TABS: [TemplateTab, string][] = [
  ['all', '全部'],
  ['liked', '我点赞的'],
  ['mine', '我发布的'],
];

/** 灵感社区：同事发布的作品，看效果、点赞、一键使用模板（对照 meoo 创意社区） */
export function CommunityPage() {
  const navigate = useNavigate();
  // 筛选记在地址里：从作品详情「返回社区」时还是原来的筛选
  const [search, setSearch] = useSearchParams();
  const tab = (TABS.some(([v]) => v === search.get('tab')) ? search.get('tab') : 'all') as TemplateTab;
  const type = search.get('type') ?? '';
  const sort = (['new', 'likes', 'uses'].includes(search.get('sort') ?? '') ? search.get('sort') : 'new') as TemplateSort;
  const setParam = (key: string, value: string, empty: string) =>
    setSearch((prev) => {
      const next = new URLSearchParams(prev);
      if (value && value !== empty) next.set(key, value);
      else next.delete(key);
      return next;
    }, { replace: true });
  const setTab = (v: TemplateTab) => setParam('tab', v, 'all');
  const setType = (v: string) => setParam('type', v, '');
  const setSort = (v: TemplateSort) => setParam('sort', v, 'new');
  const [query, setQueryText] = useState(search.get('q') ?? '');
  const setQuery = (v: string) => {
    setQueryText(v);
    setParam('q', v.trim(), '');
  };
  const q = useDebounced(query.trim());
  const params = { tab, sort, type, q };
  const { data, isLoading, error, refetch, isFetching } = useQuery({
    queryKey: ['templates', params],
    queryFn: () => api.templates(params),
    placeholderData: keepPreviousData,
  });
  const works = data ?? [];
  const filtered = !!(q || type || tab !== 'all');

  return (
    <div className="content-page community-page">
      <section className="community-hero">
        <div>
          <h1>灵感社区</h1>
          <p>浏览同事分享的应用作品，支持预览效果、点赞收藏或直接复刻为自己的模板。</p>
        </div>
        <button className="primary-button" onClick={() => navigate('/apps')}>
          <Icon name="share" />
          发布我的作品
        </button>
      </section>
      <div className="collection-toolbar">
        <div className="filter-tabs" role="tablist" aria-label="社区分类">
          {TABS.map(([k, label]) => (
            <button key={k} role="tab" aria-selected={tab === k} className={tab === k ? 'active' : ''} onClick={() => setTab(k)}>
              {k === 'liked' ? <Icon name="heart" /> : null}
              {label}
            </button>
          ))}
        </div>
        <div className="collection-controls">
          <label className="search-field">
            <Icon name="search" />
            <input placeholder="搜索作品、作者" aria-label="搜索作品" value={query} onChange={(e) => setQuery(e.target.value)} />
            {query ? (
              <button aria-label="清除搜索" onClick={() => setQuery('')}>
                <Icon name="close" />
              </button>
            ) : null}
          </label>
          <Select
            ariaLabel="作品类型"
            value={type}
            onChange={setType}
            options={[{ value: '', label: '全部类型' }, ...APP_TYPES.map((t) => ({ value: t, label: t }))]}
          />
          <Select
            ariaLabel="排序方式"
            value={sort}
            onChange={setSort}
            options={[
              { value: 'new', label: '最新发布' },
              { value: 'likes', label: '最多点赞' },
              { value: 'uses', label: '最多使用' },
            ]}
          />
        </div>
      </div>
      <div className="results-line">
        <span>
          {works.length} 个作品{isFetching && !isLoading ? ' · 更新中…' : ''}
        </span>
      </div>
      {isLoading ? (
        <LoadingState label="正在打开灵感社区…" />
      ) : error ? (
        <ErrorState message={errorMessage(error)} onRetry={() => refetch()} />
      ) : works.length ? (
        <div className="work-grid">
          {works.map((t) => (
            <WorkCard key={t.id} t={t} onOpen={() => navigate('/community/' + t.id, { state: { from: '/community' + (search.toString() ? '?' + search.toString() : '') } })} />
          ))}
        </div>
      ) : (
        <EmptyState
          icon="community"
          title={tab === 'liked' && !q && !type ? '还没有点赞的作品' : tab === 'mine' && !q && !type ? '你还没有发布作品' : filtered ? '没有找到相关作品' : '社区里还没有作品'}
          desc={
            tab === 'mine' || !filtered ? '在「应用」页打开应用卡片的「⋯」菜单，选「发布到灵感社区」，同事就能看到并使用。' : '换个关键词或类型试试。'
          }
          action={
            tab === 'mine' || !filtered ? (
              <button className="primary-button" onClick={() => navigate('/apps')}>
                去发布作品
              </button>
            ) : undefined
          }
        />
      )}
    </div>
  );
}
