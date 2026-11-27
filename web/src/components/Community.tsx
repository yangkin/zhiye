import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import type { AppSummary, TemplateItem } from '../api/types';
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
