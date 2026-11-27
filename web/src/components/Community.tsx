import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import { isServiceApp, isSkillApp, type AppSummary, type TemplateItem } from '../api/types';
import { shortDay } from '../lib/format';
import { Icon, kindIcon, type IconName } from '../lib/icons';
import { toast } from '../lib/store';
import { Modal } from './Modal';

export const authorOf = (t: Pick<TemplateItem, 'author' | 'mine'>) => t.author || (t.mine ? '我' : '华星织友');

export function FeaturedBadge({ inline = false }: { inline?: boolean }) {
  return (
    <span className={inline ? 'work-featured-inline' : 'work-featured-badge'} aria-label="平台精选置顶作品" title="平台精选置顶作品">
      <Icon name="spark" />
      精选
    </span>
  );
}

export function countLabel(n: number) {
  return n >= 10000 ? (n / 10000).toFixed(1).replace(/\.0$/, '') + 'w' : n >= 1000 ? (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k' : String(n);
}

/**
 * 作品封面：页面快照缩小显示（iframe 按 4 倍尺寸渲染再缩到 1/4，不可交互）。
 * 快照是静态页面、不带令牌，用云服务的页面看到的是空数据。
 */
export function WorkCover({ t, live = true }: { t: TemplateItem; live?: boolean }) {
  return (
    <div className="work-cover">
      {t.previewUrl && live ? (
        <iframe src={t.previewUrl} title={`${t.name} 预览`} loading="lazy" sandbox="allow-scripts" tabIndex={-1} aria-hidden="true" />
      ) : (
        <div className="work-cover-empty">
          <Icon name={((kindIcon as Record<string, string>)[t.type] ?? 'spark') as IconName} />
          <span>{t.name}</span>
        </div>
      )}
      <span className="work-badge">
        <Icon name="copy" />
        模板
      </span>
      {t.featured ? <FeaturedBadge /> : null}
      {t.requirements?.hasExternalDeps ? (
        <span className="work-dep-badge" title="复刻后需自备或重新绑定外部数据源、飞书连接器或环境变量（不含作者密钥）">
          <Icon name="settings" />
          需自备配置
        </span>
      ) : null}
    </div>
  );
}

/** 点赞：乐观更新，失败回滚并提示 */
export function useLikeWork() {
  const qc = useQueryClient();
  return async (t: TemplateItem) => {
    const on = !t.liked;
    const patch = (x: TemplateItem) => (x.id === t.id ? { ...x, liked: on, likeCount: Math.max(0, x.likeCount + (on ? 1 : -1)) } : x);
    qc.setQueriesData<TemplateItem[]>({ queryKey: ['templates'] }, (old) => old?.map(patch));
    qc.setQueryData<TemplateItem>(['template', t.id], (old) => (old ? patch(old) : old));
    try {
      await api.templateLike(t.id, on);
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      void qc.invalidateQueries({ queryKey: ['templates'] });
      void qc.invalidateQueries({ queryKey: ['template', t.id] });
    }
  };
}

/** 使用模板：复刻成自己的应用，跳到工作台看部署进度 */
export function useUseWork() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [busy, setBusy] = useState<string | null>(null);
  const run = async (t: TemplateItem) => {
    if (busy) return;
    setBusy(t.id);
    try {
      const r = await api.templateClone(t.id);
      toast(`已用「${t.name}」创建应用，正在部署…`);
      void qc.invalidateQueries({ queryKey: ['apps'] });
      void qc.invalidateQueries({ queryKey: ['templates'] });
      navigate('/apps/' + r.app.id);
    } catch (err) {
      toast(errorMessage(err), 'error');
      setBusy(null);
    }
  };
  return { run, busy };
}

export function WorkCard({ t, onOpen }: { t: TemplateItem; onOpen: () => void }) {
  const like = useLikeWork();
  return (
    <article className="work-card">
      <button className="work-card-cover" onClick={onOpen} aria-label={`查看 ${t.name}`}>
        <WorkCover t={t} />
      </button>
      <div className="work-card-info">
        <button className="work-card-title" onClick={onOpen} title={t.name}>
          {t.name}
        </button>
        <div className="work-card-meta">
          <span className="work-author">
            <i>{authorOf(t).slice(0, 1)}</i>
            {authorOf(t)}
          </span>
          <span title="浏览">
            <Icon name="eye" />
            {countLabel(t.viewCount)}
          </span>
          <span title="使用">
            <Icon name="fork" />
            {countLabel(t.cloneCount)}
          </span>
          <button className={`work-like ${t.liked ? 'on' : ''}`} aria-pressed={t.liked} aria-label={t.liked ? '取消点赞' : '点赞'} onClick={() => void like(t)}>
            <Icon name="heart" />
            {countLabel(t.likeCount)}
          </button>
        </div>
      </div>
    </article>
  );
}

/** 发布到灵感社区：名称 + 一句话介绍 */
export function PublishWorkModal({ app, onClose }: { app: AppSummary; onClose: () => void }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [name, setName] = useState(app.name);
  const [desc, setDesc] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    if (!name.trim()) return setError('给作品起个名字吧。');
    setBusy(true);
    try {
      const t = await api.templateCreate(app.id, name.trim(), desc.trim());
      void qc.invalidateQueries({ queryKey: ['templates'] });
      toast(`「${t.name}」已发布到灵感社区。`);
      onClose();
      navigate('/community/' + t.id);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };
  return (
    <Modal
      title="发布到灵感社区"
      onClose={onClose}
      footer={
        <>
          <button className="subtle-button" onClick={onClose}>
            取消
          </button>
          <button className="primary-button" disabled={busy} onClick={() => void submit()}>
            {busy ? '正在发布…' : '发布'}
          </button>
        </>
      }
    >
      <label className="field-label" htmlFor="work-name">
        作品名称
      </label>
      <input id="work-name" className="text-input" value={name} maxLength={40} autoFocus onChange={(e) => (setName(e.target.value), setError(''))} />
      <label className="field-label" htmlFor="work-desc">
        一句话介绍
      </label>
      <textarea
        id="work-desc"
        className="text-input work-desc-input"
        value={desc}
        maxLength={200}
        rows={3}
        placeholder="它解决什么问题、适合谁用，比如：产线点检记录，扫码填写、异常自动标红。"
        onChange={(e) => setDesc(e.target.value)}
      />
      {error ? <p className="inline-error">{error}</p> : null}
      <small className="muted-note">
        发布的是当前版本的脱敏快照：同事可查看效果、点赞并「使用模板」复刻为自己的应用；不会包含你的业务数据、AGW 数据源凭证、私有飞书连接器密钥、服务环境变量值及 .env 等私密文件，外部依赖会自动生成配置清单供复用者按需对接。
      </small>
    </Modal>
  );
}

type PickScope = 'all' | 'ready' | 'published';

function phaseLabel(phase: AppSummary['phase']) {
  if (phase === 'planning') return '计划生成中';
  if (phase === 'plan') return '计划待确认';
  if (phase === 'generating') return '生成中';
  if (phase === 'failed') return '生成失败';
  if (phase === 'stopped') return '已停止';
  return '可发布';
}

/** 灵感社区入口：弹窗展示当前工作空间所有作品，支持搜索筛选、直接点选并一键发布到灵感社区 */
export function PickAndPublishWorkModal({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [scope, setScope] = useState<PickScope>('all');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [desc, setDesc] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const appsQ = useQuery({
    queryKey: qk.apps({ sort: 'updated' }),
    queryFn: () => api.listApps({ sort: 'updated' }),
  });
  const mineQ = useQuery({
    queryKey: ['templates', { tab: 'mine', sort: 'new', type: '', q: '' }],
    queryFn: () => api.templates({ tab: 'mine', sort: 'new' }),
  });

  const allApps = appsQ.data ?? [];
  const publishedMap = useMemo(() => {
    const m = new Map<string, TemplateItem>();
    for (const t of mineQ.data ?? []) {
      if (t.sourceAppId) m.set(t.sourceAppId, t);
    }
    return m;
  }, [mineQ.data]);

  const counts = useMemo(() => {
    let ready = 0;
    let published = 0;
    for (const a of allApps) {
      if (publishedMap.has(a.id)) published++;
      else if (a.phase === 'ready') ready++;
    }
    return { all: allApps.length, ready, published };
  }, [allApps, publishedMap]);

  const filteredApps = useMemo(() => {
    const kw = query.trim().toLowerCase();
    return allApps.filter((a) => {
      const isPub = publishedMap.has(a.id);
      if (scope === 'ready' && (isPub || a.phase !== 'ready')) return false;
      if (scope === 'published' && !isPub) return false;
      if (!kw) return true;
      return `${a.name} ${a.description ?? ''} ${a.type} ${a.framework ?? ''}`.toLowerCase().includes(kw);
    });
  }, [allApps, publishedMap, query, scope]);

  const selected = useMemo(() => allApps.find((a) => a.id === selectedId) ?? null, [allApps, selectedId]);

  const selectApp = (app: AppSummary) => {
    setSelectedId(app.id);
    setName(app.name.slice(0, 40));
    setDesc((app.description ?? '').slice(0, 200));
    setError('');
  };

  const submit = async () => {
    if (!selected) return setError('请先从列表中选择一个要发布的作品。');
    if (!name.trim()) return setError('给作品起个名字吧。');
    setBusy(true);
    setError('');
    try {
      const t = await api.templateCreate(selected.id, name.trim(), desc.trim());
      void qc.invalidateQueries({ queryKey: ['templates'] });
      toast(`「${t.name}」已发布到灵感社区。`);
      onClose();
      navigate('/community/' + t.id);
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  };

  return (
    <Modal
      wide
      overline="灵感社区"
      title="选择我的作品发布"
      className="pick-publish-modal"
      onClose={onClose}
      footer={
        <>
          <button type="button" className="subtle-button" onClick={onClose}>
            取消
          </button>
          <button type="button" className="primary-button" disabled={busy || !selected || !name.trim()} onClick={() => void submit()}>
            <Icon name="share" />
            {busy ? '正在发布…' : '发布到灵感社区'}
          </button>
        </>
      }
    >
      <div className="pick-publish-toolbar">
        <div className="pick-publish-tabs" role="tablist" aria-label="作品筛选">
          <button type="button" role="tab" aria-selected={scope === 'all'} className={scope === 'all' ? 'active' : ''} onClick={() => setScope('all')}>
            全部 ({counts.all})
          </button>
          <button type="button" role="tab" aria-selected={scope === 'ready'} className={scope === 'ready' ? 'active' : ''} onClick={() => setScope('ready')}>
            可发布 ({counts.ready})
          </button>
          <button type="button" role="tab" aria-selected={scope === 'published'} className={scope === 'published' ? 'active' : ''} onClick={() => setScope('published')}>
            已在社区 ({counts.published})
          </button>
        </div>
        <label className="search-field pick-publish-search">
          <Icon name="search" />
          <input placeholder="搜索应用名称或类型…" aria-label="搜索要发布的应用" value={query} onChange={(e) => setQuery(e.target.value)} />
          {query ? (
            <button type="button" aria-label="清除搜索" onClick={() => setQuery('')}>
              <Icon name="close" />
            </button>
          ) : null}
        </label>
      </div>

      {appsQ.isLoading ? (
        <div className="pick-publish-empty">正在读取你的所有应用…</div>
      ) : appsQ.error ? (
        <div className="pick-publish-empty is-error">{errorMessage(appsQ.error)}</div>
      ) : allApps.length === 0 ? (
        <div className="pick-publish-empty">
          <b>你还没有创建任何作品</b>
          <span>先用一句话创建一个应用，生成完成后就可以分享到灵感社区。</span>
          <button
            type="button"
            className="primary-button"
            onClick={() => {
              onClose();
              navigate('/create');
            }}
          >
            <Icon name="plus" />
            去创建应用
          </button>
        </div>
      ) : filteredApps.length === 0 ? (
        <div className="pick-publish-empty">
          <span>没有匹配的作品，试试切换筛选条件或清除搜索关键词。</span>
        </div>
      ) : (
        <div className="pick-publish-grid" role="listbox" aria-label="选择要发布的应用">
          {filteredApps.map((app) => {
            const existingTpl = publishedMap.get(app.id);
            const canPick = app.phase === 'ready' && !existingTpl;
            const isSel = selectedId === app.id;
            const iconName = ((kindIcon as Record<string, string>)[app.type] ?? 'spark') as IconName;
            return (
              <div
                key={app.id}
                role={canPick ? 'option' : 'group'}
                aria-selected={canPick ? isSel : undefined}
                aria-disabled={!canPick && !existingTpl ? true : undefined}
                tabIndex={canPick ? 0 : -1}
                className={`pick-app-item ${isSel ? 'is-selected' : ''} ${existingTpl ? 'is-published' : !canPick ? 'is-disabled' : ''}`}
                onClick={() => {
                  if (canPick) selectApp(app);
                }}
                onKeyDown={(e) => {
                  if (canPick && (e.key === 'Enter' || e.key === ' ')) {
                    e.preventDefault();
                    selectApp(app);
                  }
                }}
              >
                <span className="pick-app-icon">
                  <Icon name={iconName} />
                </span>
                <div className="pick-app-main">
                  <div className="pick-app-head">
                    <b title={app.name}>{app.name}</b>
                    {existingTpl ? (
                      <span className="pick-app-pill is-done">已在社区</span>
                    ) : canPick ? (
                      <span className={`pick-app-check ${isSel ? 'on' : ''}`} aria-hidden="true">
                        {isSel ? <Icon name="check" /> : null}
                      </span>
                    ) : (
                      <span className="pick-app-pill is-muted">{phaseLabel(app.phase)}</span>
                    )}
                  </div>
                  <p>{app.description || (app.source === 'import' ? `代码导入 · ${app.framework ?? ''}` : '暂无简介')}</p>
                  <div className="pick-app-meta">
                    <span>{app.type}</span>
                    {isServiceApp(app) ? <span>全栈服务</span> : isSkillApp(app) ? <span>技能包</span> : app.cloudEnabled ? <span>云服务</span> : null}
                    {app.source === 'import' ? <span>导入项目</span> : null}
                    <small>{shortDay(app.updatedAt)}</small>
                    {existingTpl ? (
                      <button
                        type="button"
                        className="pick-app-view"
                        onClick={(e) => {
                          e.stopPropagation();
                          onClose();
                          navigate('/community/' + existingTpl.id);
                        }}
                      >
                        查看作品
                      </button>
                    ) : null}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {selected ? (
        <div className="pick-publish-form">
          <div className="pick-publish-form-head">
            <span>
              已选中：<b>{selected.name}</b>
            </span>
            <small>自动脱敏快照，不含业务数据、凭证与 .env 密钥</small>
          </div>
          <div className="pick-publish-fields">
            <div>
              <label className="field-label" htmlFor="pick-work-name">
                作品名称
              </label>
              <input
                id="pick-work-name"
                className="text-input"
                value={name}
                maxLength={40}
                onChange={(e) => {
                  setName(e.target.value);
                  setError('');
                }}
              />
            </div>
            <div>
              <label className="field-label" htmlFor="pick-work-desc">
                一句话介绍（可选）
              </label>
              <input
                id="pick-work-desc"
                className="text-input"
                value={desc}
                maxLength={200}
                placeholder="例如：产线点检记录，扫码填写、异常自动标红"
                onChange={(e) => setDesc(e.target.value)}
              />
            </div>
          </div>
        </div>
      ) : allApps.length > 0 ? (
        <div className="pick-publish-hint">点击上方任意「可发布」作品即可选中，确认名称与介绍后一键发布到灵感社区。</div>
      ) : null}

      {error ? <p className="inline-error">{error}</p> : null}
    </Modal>
  );
}
