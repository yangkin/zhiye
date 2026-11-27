import { useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { Icon } from '../lib/icons';
import { copyText, fmtDate } from '../lib/format';
import { toast } from '../lib/store';
import { authorOf, countLabel, FeaturedBadge, useLikeWork, useUseWork, WorkCover } from '../components/Community';
import { ErrorState, LoadingState } from '../components/EmptyState';
import { Modal } from '../components/Modal';
import { Select } from '../components/Select';
import { PREVIEW_RESOLUTION_OPTIONS, usePreviewViewport } from '../hooks/usePreviewViewport';

/** 作品详情：左边大预览（页面快照），右边作者、点赞、使用模板、去访问、相关推荐 */
export function WorkPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const loc = useLocation();
  // 「返回社区」回到社区列表（带进来时的筛选），不是浏览器后退：从相关推荐、创造页进来时后退会去别处
  const from = (loc.state as { from?: string } | null)?.from;
  const backTo = from?.startsWith('/community') ? from : '/community';
  const qc = useQueryClient();
  const { data: t, isLoading, error, refetch } = useQuery({ queryKey: ['template', id], queryFn: () => api.template(id), staleTime: 60_000 });
  const like = useLikeWork();
  const use = useUseWork();
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [nudge, setNudge] = useState(false);
  const vp = usePreviewViewport('auto');

  if (isLoading) return <LoadingState label="正在打开作品…" />;
  if (error || !t) return <ErrorState message={error ? errorMessage(error) : '作品不存在'} onRetry={() => refetch()} />;

  const share = async () => {
    const ok = await copyText(location.origin + '/community/' + t.id);
    toast(ok ? '作品链接已复制，发给同事就能打开。' : '复制失败，请手动复制地址栏的链接。');
  };
  const remove = async () => {
    try {
      await api.templateRemove(t.id);
      void qc.invalidateQueries({ queryKey: ['templates'] });
      toast(`已从灵感社区移除「${t.name}」。`);
      navigate('/community');
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };
  const liveSiteUrl = t.siteUrl || null;

  return (
    <div className="work-page">
      <section className="work-stage">
        <div className="work-browser">
          <header>
            <i />
            <i />
            <i />
            <span>{liveSiteUrl ? liveSiteUrl.replace(/^https?:\/\//, '') : '只读预览快照 · 复刻后可交互'}</span>
            {t.previewUrl ? <span className="work-readonly-pill">仅限预览</span> : null}
            {t.previewUrl ? (
              <div className="work-device-bar">
                {vp.isScaled ? (
                  <button
                    type="button"
                    className="work-scale-pill"
                    title={`当前按 ${vp.logicalWidth}px 等比缩放（${Math.round(vp.scale * 100)}%），点击切回 1:1 原生宽度`}
                    onClick={() => vp.setMode('native')}
                  >
                    {Math.round(vp.scale * 100)}%
                  </button>
                ) : null}
                <Select
                  className="work-res-picker"
                  ariaLabel="预览电脑分辨率"
                  value={vp.mode}
                  options={PREVIEW_RESOLUTION_OPTIONS}
                  onChange={vp.setMode}
                />
              </div>
            ) : null}
            {liveSiteUrl ? (
              <a className="icon-button" href={liveSiteUrl} target="_blank" rel="noreferrer" aria-label="新窗口打开已上线站点" title="新窗口打开已上线站点">
                <Icon name="expand" />
              </a>
            ) : null}
          </header>
          {t.previewUrl ? (
            <div
              ref={vp.stageRef}
              className={`work-browser-stage ${vp.isConstrained ? 'is-constrained-desktop' : ''} ${vp.isScaled ? 'is-scaled' : ''}`}
            >
              <div className="preview-viewport-shell work-preview-shell" style={vp.shellStyle}>
                <iframe src={t.previewUrl} style={vp.frameStyle} title={`${t.name} 只读预览`} sandbox="allow-scripts" />
                <div
                  className="work-readonly-shield"
                  role="button"
                  tabIndex={0}
                  aria-label="预览仅供查看，点击使用模板复刻到自己的应用"
                  onClick={() => setNudge(true)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      setNudge(true);
                    }
                  }}
                >
                  {nudge ? (
                    <div className="work-readonly-toast" onClick={(e) => e.stopPropagation()}>
                      <Icon name="lock" />
                      <span>社区预览仅供查看效果，请先复刻到自己的应用后再交互使用</span>
                      <button
                        type="button"
                        className="primary-button"
                        disabled={use.busy === t.id}
                        onClick={() => void use.run(t)}
                      >
                        {use.busy === t.id ? '正在创建…' : '使用模板'}
                      </button>
                      <button type="button" className="icon-button" aria-label="关闭提示" onClick={() => setNudge(false)}>
                        <Icon name="close" />
                      </button>
                    </div>
                  ) : null}
                </div>
              </div>
            </div>
          ) : (
            <div className="work-stage-empty">
              <WorkCover t={t} live={false} />
              <p>这个作品没有页面预览（技能包、脚本或文档类作品只有文件）。</p>
            </div>
          )}
        </div>
      </section>
      <aside className="work-side">
        <button className="work-back" onClick={() => navigate(backTo)}>
          <Icon name="back" />
          返回社区
        </button>
        <div className="work-who">
          <span className="work-author">
            <i>{authorOf(t).slice(0, 1)}</i>
            {authorOf(t)}
          </span>
          <button className={`work-like big ${t.liked ? 'on' : ''}`} aria-pressed={t.liked} aria-label={t.liked ? '取消点赞' : '点赞'} onClick={() => void like(t)}>
            <Icon name="heart" />
            {countLabel(t.likeCount)}
          </button>
          <button className="icon-button" aria-label="复制作品链接" title="分享" onClick={() => void share()}>
            <Icon name="share" />
          </button>
        </div>
        <p className="work-stats">
          {fmtDate(t.createdAt)} · {countLabel(t.viewCount)} 次浏览 · <Icon name="fork" /> {countLabel(t.cloneCount)} 次使用
        </p>
        <h1>
          {t.name}
          <span className="work-type">{t.type}</span>
          {t.featured ? <FeaturedBadge inline /> : null}
        </h1>
        <p className="work-desc">{t.description || '作者还没有写介绍，看看左边的效果吧。'}</p>
        <div className="work-actions">
          <button className="primary-button" disabled={use.busy === t.id} onClick={() => void use.run(t)}>
            {use.busy === t.id ? '正在创建…' : '使用模板'}
          </button>
          {liveSiteUrl ? (
            <button className="subtle-button" onClick={() => window.open(liveSiteUrl, '_blank', 'noopener')}>
              去访问线上站
            </button>
          ) : null}
        </div>
        <small className="work-note">
          左侧预览为只读展示快照，不可直接操作；点击「使用模板」会复刻一份到你的应用里（含页面与表结构，不含作者数据与凭证），之后可完整体验与修改。
        </small>
        {(() => {
          const req = t.requirements;
          const dsList = req?.dataSources ?? [];
          const connList = req?.connectors ?? [];
          const envList = req?.envVars ?? [];
          const autoItems = [
            req?.cloud ? '云数据表（自动建独立空表）' : null,
            req?.managedPg ? '托管 PostgreSQL（自动建空库）' : null,
            req?.pvc ? '持久化卷（服务托管按需开启）' : null,
          ].filter(Boolean) as string[];
          if (!dsList.length && !connList.length && !envList.length && !autoItems.length) return null;
          return (
            <section className="work-reqs-card" aria-label="复用前依赖清单">
              <div className="work-reqs-head">
                <b>
                  <Icon name="settings" />
                  {req?.hasExternalDeps ? '使用前需自备配置' : '运行环境说明'}
                </b>
                <span className="work-reqs-safe">不含作者密钥</span>
              </div>
              {dsList.length > 0 ? (
                <div className="work-reqs-group">
                  <span className="work-reqs-label">AGW / 企业数据源</span>
                  <ul>
                    {dsList.map((d) => (
                      <li key={d.key}>
                        <span>{d.name || d.key}</span>
                        <code>{d.key}</code>
                        <em>{d.platform ? '平台公共' : '需自备权限'}</em>
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
              {connList.length > 0 ? (
                <div className="work-reqs-group">
                  <span className="work-reqs-label">飞书 / 企业连接器</span>
                  <ul>
                    {connList.map((c, idx) => (
                      <li key={`${c.kind}-${c.sourceConnectorId ?? idx}`}>
                        <span>{c.kindLabel || c.name}</span>
                        <em>{c.scope === 'platform' ? '平台公共 · 可直用' : '作者私有 · 需改绑'}</em>
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
              {envList.length > 0 ? (
                <div className="work-reqs-group">
                  <span className="work-reqs-label">服务环境变量（仅键名）</span>
                  <div className="work-reqs-chips">
                    {envList.map((ev) => (
                      <code key={ev.key} title="复刻后在工作台「服务托管 → 环境变量」填写">
                        {ev.key}
                      </code>
                    ))}
                  </div>
                </div>
              ) : null}
              {autoItems.length > 0 ? (
                <div className="work-reqs-auto">
                  {autoItems.map((label) => (
                    <span key={label}>{label}</span>
                  ))}
                </div>
              ) : null}
              {req?.strippedSecretFileCount ? (
                <p className="work-reqs-strip">
                  发布时已自动剥离 {req.strippedSecretFileCount} 个本地私密文件（如 .env / 私钥），保障作者安全。
                </p>
              ) : null}
            </section>
          );
        })()}
        {t.mine ? (
          <button className="text-button danger work-remove" onClick={() => setConfirmDelete(true)}>
            <Icon name="trash" />
            从社区移除
          </button>
        ) : null}
        {t.related.length ? (
          <div className="work-related">
            <h2>相关推荐</h2>
            <div>
              {t.related.map((r) => (
                <button key={r.id} onClick={() => navigate('/community/' + r.id, { state: { from: backTo } })}>
                  <WorkCover t={r} />
                  <b title={r.name}>{r.name}</b>
                  <small>{authorOf(r)}</small>
                </button>
              ))}
            </div>
          </div>
        ) : null}
      </aside>
      {confirmDelete ? (
        <Modal
          title={`移除「${t.name}」？`}
          onClose={() => setConfirmDelete(false)}
          footer={
            <>
              <button className="subtle-button" onClick={() => setConfirmDelete(false)}>
                取消
              </button>
              <button className="danger-button" onClick={() => void remove()}>
                移除
              </button>
            </>
          }
        >
          <p className="notice-box">移除后同事在社区里就看不到这个作品了；已经用它创建的应用不受影响，你自己的原应用也不受影响。</p>
        </Modal>
      ) : null}
    </div>
  );
}
