import { useEffect, useRef, useState } from 'react';
import { useDialogFocus } from '../hooks/useDialogFocus';
import { createPortal } from 'react-dom';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError, api, errorMessage } from '../api/client';
import { useInvalidateApp } from '../api/queries';
import type { Version } from '../api/types';
import { Icon } from '../lib/icons';
import { fmtDate, fmtTime } from '../lib/format';
import { toast } from '../lib/store';
import { Modal, useEscape } from './Modal';

interface RowProps {
  appId: string;
  appName: string;
  version: Version;
  compact?: boolean;
  onOpenApp?: () => void;
  onPreview: () => void;
  onRestore: () => void;
}

export function VersionRow({ appId, appName, version: v, compact, onOpenApp, onPreview, onRestore }: RowProps) {
  const qc = useQueryClient();
  const [fav, setFav] = useState(v.favorite);
  useEffect(() => setFav(v.favorite), [v.favorite]);
  const toggle = async () => {
    const next = !fav;
    setFav(next);
    try {
      await api.patchVersion(v.id, { favorite: next });
      void qc.invalidateQueries({ queryKey: ['history'] });
      void qc.invalidateQueries({ queryKey: ['versions', appId] });
    } catch (err) {
      setFav(!next);
      toast(errorMessage(err), 'error');
    }
  };
  const [noteOpen, setNoteOpen] = useState(false);
  return (
    <article className={`version-row ${compact ? 'compact' : ''} ${v.isCurrent ? 'is-current' : ''} ${v.isPublished ? 'is-published' : ''}`}>
      {compact ? (
        <span className="timeline-dot">
          <Icon name={v.isPublished ? 'cloud' : 'history'} />
        </span>
      ) : (
        <span className={`version-rail-badge ${v.isPublished ? 'online' : v.isCurrent ? 'current' : ''}`}>
          V{v.number}
        </span>
      )}
      <div className="version-main">
        {compact ? (
          <>
            <div className="version-top">
              <span className="version-number">V{v.number}</span>
              {v.note ? <span className="version-note">{v.note}</span> : null}
              {v.isCurrent ? <span className="tiny-badge current">当前版本</span> : null}
              {v.isPublished ? <span className="tiny-badge online">线上版本</span> : null}
            </div>
            <h3>
              {v.title}
              {v.restoredFromNumber ? <small className="restored-from"> · 恢复自 V{v.restoredFromNumber}</small> : null}
            </h3>
            <div className="version-author">
              <span className="mini-avatar">我</span>创作者<span>·</span>
              <span>{fmtDate(v.createdAt)} {fmtTime(v.createdAt)}</span>
            </div>
          </>
        ) : (
          <>
            <div className="version-headline">
              <h3 title={v.title}>
                {v.title}
                {v.restoredFromNumber ? <small className="restored-from"> · 恢复自 V{v.restoredFromNumber}</small> : null}
              </h3>
              {v.isCurrent ? <span className="tiny-badge current">当前版本</span> : null}
              {v.isPublished ? <span className="tiny-badge online">线上版本</span> : null}
              {v.note ? <span className="version-note">{v.note}</span> : null}
            </div>
            <div className="version-author">
              {onOpenApp ? (
                <>
                  <button type="button" className="version-app-chip" onClick={onOpenApp}>
                    <Icon name="grid" />
                    <span>{appName}</span>
                  </button>
                  <span className="version-meta-sep" aria-hidden="true">·</span>
                </>
              ) : null}
              <span>创作者</span>
              <span className="version-meta-sep" aria-hidden="true">·</span>
              <time>{fmtDate(v.createdAt)} {fmtTime(v.createdAt)}</time>
            </div>
          </>
        )}
      </div>
      <div className="version-actions">
        <button type="button" className="text-button" aria-label={`预览 ${appName} V${v.number}`} onClick={onPreview}>
          <Icon name="eye" />
          {compact ? null : <span>预览</span>}
        </button>
        <button type="button" className="text-button" disabled={v.isCurrent} aria-label={`恢复 ${appName} V${v.number}`} onClick={onRestore}>
          <Icon name="history" />
          {compact ? null : <span>恢复</span>}
        </button>
        <button
          type="button"
          className={`icon-button bookmark ${fav ? 'is-starred' : ''}`}
          aria-label={`${fav ? '取消收藏' : '收藏'}版本 V${v.number}`}
          aria-pressed={fav}
          onClick={() => void toggle()}
        >
          <Icon name="book" />
        </button>
        <button type="button" className="icon-button" aria-label={`给 V${v.number} 写备注`} title="备注" onClick={() => setNoteOpen(true)}>
          <Icon name="edit" />
        </button>
      </div>
      {noteOpen ? <VersionNoteModal appId={appId} version={v} onClose={() => setNoteOpen(false)} /> : null}
    </article>
  );
}

/** 版本备注：给某个版本起个别名（如「可上线版」），V 号不变、备注重复也没关系 */
function VersionNoteModal({ appId, version: v, onClose }: { appId: string; version: Version; onClose: () => void }) {
  const qc = useQueryClient();
  const [text, setText] = useState(v.note ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const save = async () => {
    setBusy(true);
    setError('');
    try {
      await api.patchVersion(v.id, { note: text.trim() || null });
      void qc.invalidateQueries({ queryKey: ['history'] });
      void qc.invalidateQueries({ queryKey: ['versions', appId] });
      toast(text.trim() ? `已给 V${v.number} 加上备注。` : `已清除 V${v.number} 的备注。`);
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={`V${v.number} 备注`}
      onClose={onClose}
      footer={
        <>
          <button className="subtle-button" onClick={onClose}>
            取消
          </button>
          <button className="primary-button" disabled={busy} onClick={() => void save()}>
            {busy ? '正在保存…' : '保存备注'}
          </button>
        </>
      }
    >
      <label className="field-label">备注</label>
      <input
        className="text-input"
        autoFocus
        maxLength={60}
        placeholder="比如「可上线版」「初稿」"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && void save()}
      />
      <p className="muted-note">备注只是别名，方便在历史里认出版本；留空则清除备注。</p>
      {error ? (
        <p className="inline-error" role="alert">
          {error}
        </p>
      ) : null}
    </Modal>
  );
}

export function VersionPreviewModal({
  appName,
  version: v,
  onClose,
  onRestore,
}: {
  appName: string;
  version: Version;
  onClose: () => void;
  onRestore: () => void;
}) {
  return (
    <Modal
      title={`${appName} · V${v.number}`}
      wide
      onClose={onClose}
      footer={
        <>
          <button className="subtle-button" onClick={onClose}>
            关闭预览
          </button>
          <button className="primary-button" disabled={v.isCurrent} onClick={onRestore}>
            <Icon name="history" />
            恢复此版本
          </button>
        </>
      }
    >
      <div className="version-modal-meta">
        <span>{v.title}</span>
        <small>
          {fmtDate(v.createdAt)} {fmtTime(v.createdAt)}
        </small>
      </div>
      {v.previewUrl ? (
        <iframe className="version-modal-frame" src={v.previewUrl} sandbox="allow-scripts allow-forms" title="历史版本预览" />
      ) : (
        <p className="muted-note">技能包没有页面预览，可以在工作台的「代码」中查看这个版本的文件。</p>
      )}
    </Modal>
  );
}

export function RestoreModal({
  appId,
  appName,
  version: v,
  onClose,
  onDone,
}: {
  appId: string;
  appName: string;
  version: Version;
  onClose: () => void;
  onDone?: (v: Version) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const invalidate = useInvalidateApp();
  const confirm = async () => {
    setBusy(true);
    setError('');
    try {
      const nv = await api.restoreVersion(v.id);
      invalidate(appId);
      toast(`已恢复为新版本 V${nv.number}，线上版本保持原样。`);
      onDone?.(nv);
      onClose();
    } catch (err) {
      if (err instanceof ApiError && err.code === 'DRAFT_PENDING')
        setError(`${err.message}（请先在工作台保存或完成可视化编辑，再恢复版本。）`);
      else setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={`恢复到 V${v.number}？`}
      onClose={onClose}
      footer={
        <>
          <button className="subtle-button" onClick={onClose}>
            再想想
          </button>
          <button className="primary-button" disabled={busy} onClick={() => void confirm()}>
            {busy ? '正在恢复…' : '确认恢复'}
          </button>
        </>
      }
    >
      <div className="restore-preview">
        <span className="restore-icon">
          <Icon name="history" />
        </span>
        <h3>{v.title}</h3>
        <p>
          {appName} · {fmtDate(v.createdAt)} {fmtTime(v.createdAt)}
        </p>
      </div>
      <div className="notice-box">
        恢复会创建一个新版本，已有记录会保留。
        <br />
        线上版本不会自动改变，确认效果后可以再发布。
      </div>
      {error ? (
        <p className="inline-error" role="alert">
          {error}
        </p>
      ) : null}
    </Modal>
  );
}

/** 工作台里的版本历史抽屉（原型 history-sheet）。 */
export function VersionSheet({
  appName,
  versions,
  loading,
  onClose,
  onPreview,
  onRestore,
  appId,
}: {
  appId: string;
  appName: string;
  versions: Version[];
  loading: boolean;
  onClose: () => void;
  onPreview: (v: Version) => void;
  onRestore: (v: Version) => void;
}) {
  const [q, setQ] = useState('');
  useEscape(onClose);
  const sheet = useRef<HTMLElement>(null);
  useDialogFocus(sheet);
  const rows = versions.filter((v) => !q.trim() || v.title.toLowerCase().includes(q.trim().toLowerCase()) || `v${v.number}` === q.trim().toLowerCase() || (v.note ?? '').toLowerCase().includes(q.trim().toLowerCase()));
  return createPortal(
    <div className="sheet-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <section ref={sheet} className="history-sheet" role="dialog" aria-modal="true" aria-label="版本历史">
        <header>
          <div>
            <h2>版本历史</h2>
            <p>
              {appName} · {versions.length} 个版本
            </p>
          </div>
          <button className="icon-button" aria-label="关闭版本历史" onClick={onClose}>
            <Icon name="close" />
          </button>
        </header>
        <label className="search-field">
          <Icon name="search" />
          <input placeholder="输入标题搜索" aria-label="搜索当前应用版本" value={q} onChange={(e) => setQ(e.target.value)} />
        </label>
        <div className="sheet-versions">
          {loading ? <p className="muted-note">正在读取版本…</p> : null}
          {rows.map((v) => (
            <VersionRow
              key={v.id}
              appId={appId}
              appName={appName}
              version={v}
              compact
              onPreview={() => onPreview(v)}
              onRestore={() => onRestore(v)}
            />
          ))}
          {!loading && !rows.length ? (
            <div className="empty-state">
              <h3>没有匹配的版本</h3>
              <p>{versions.length ? '换一个关键词试试。' : '应用生成后，每一次修改都会留下版本。'}</p>
            </div>
          ) : null}
        </div>
        <div className="sheet-note">
          <Icon name="history" />
          每一个版本，都是创作的一部分。
        </div>
      </section>
    </div>,
    document.body,
  );
}
