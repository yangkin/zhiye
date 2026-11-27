import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { api as convApi } from '../api/conversation';
import { toast } from '../lib/store';
import { qk } from '../api/queries';
import { useChat } from '../lib/store';
import { Icon } from '../lib/icons';
import { downloadUrl, sizeLabel } from '../lib/format';
import { Markdown } from '../lib/markdown';
import { Modal } from '../components/Modal';
import { DownloadButton, downloadFile } from '../pages/ChatFiles';
import { ErrorState, LoadingState, EmptyState } from '../components/EmptyState';
import { CloudConsole } from './CloudConsole';
import { confirmDialog } from '../components/ConfirmDialog';

/** 仅匹配真正面向用户的文档/数据文件（严禁把源码、脚本、SQL 或内部缓存笔记混入「文件」页签） */
const DOC_EXT = /^(md|markdown|txt|pdf|csv|xlsx?|docx?|pptx?)$/i;
const TEXT_PREVIEW_EXT = /^(md|markdown|txt|csv|json)$/i;
const isUserDoc = (path: string) => {
  const norm = path.replace(/\\/g, '/');
  if (
    norm.startsWith('src/') ||
    norm.startsWith('server/') ||
    norm.startsWith('backend/') ||
    norm.startsWith('frontend/') ||
    norm.startsWith('public/') ||
    norm.startsWith('assets/') ||
    norm.startsWith('cloud/') ||
    norm.startsWith('.zhiye/') ||
    norm.startsWith('node_modules/')
  ) {
    return false;
  }
  if (norm === 'docs/PROJECT_NOTES.md' || /-parsed\.md$/i.test(norm)) return false;
  return DOC_EXT.test(norm.split('.').pop() ?? '');
};

const extOf = (name: string) => (name.split('.').pop() ?? '').toLowerCase();
const baseName = (path: string) => path.split('/').pop() ?? path;

type PreviewState =
  | null
  | { kind: 'image'; name: string; src: string; downloadHref: string }
  | { kind: 'text'; name: string; content: string; loading?: boolean; error?: string; downloadHref?: string };

function CsvPreviewTable({ text }: { text: string }) {
  const rows = useMemo(() => text.split(/\r?\n/).filter(Boolean).slice(0, 300).map((l) => l.split(',')), [text]);
  if (!rows.length) return <pre className="file-plain">（空文件）</pre>;
  return (
    <div className="md-table file-doc">
      <table>
        <thead>
          <tr>{rows[0].map((c, i) => <th key={i}>{c}</th>)}</tr>
        </thead>
        <tbody>
          {rows.slice(1).map((r, i) => (
            <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function FilesPane({
  appId,
  versionId,
}: {
  appId: string;
  versionId: string | null;
}) {
  const [preview, setPreview] = useState<PreviewState>(null);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);

  const appFiles = useQuery({
    queryKey: ['app-files', appId],
    queryFn: () => api.appFiles(appId),
    enabled: !!appId,
  });
  const versionFiles = useQuery({
    queryKey: qk.files(versionId ?? ''),
    queryFn: () => api.versionFiles(versionId!),
    enabled: !!versionId,
  });

  useEffect(() => {
    if (preview?.kind !== 'image') return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setPreview(null);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [preview?.kind]);

  const uploaded = appFiles.data?.uploaded ?? [];
  const chatFiles = appFiles.data?.chatFiles ?? [];
  const repoDocs = (versionFiles.data ?? []).filter((f) => isUserDoc(f.path));

  const totalCount = uploaded.length + chatFiles.length + repoDocs.length;
  const totalBytes =
    uploaded.reduce((n, a) => n + (a.size ?? 0), 0) +
    chatFiles.reduce((n, f) => n + (f.size ?? 0), 0) +
    repoDocs.reduce((n, f) => n + f.size, 0);

  const loading = appFiles.isLoading || (!!versionId && versionFiles.isLoading && !totalCount);
  const error = appFiles.error || (versionFiles.error && !uploaded.length && !chatFiles.length ? versionFiles.error : null);

  const openUploadedPreview = async (a: { id: string; name: string; mime: string; url: string; downloadUrl: string }) => {
    if (a.mime?.startsWith('image/')) {
      setPreview({ kind: 'image', name: a.name, src: a.url, downloadHref: a.downloadUrl });
      return;
    }
    if (a.mime === 'application/pdf' || extOf(a.name) === 'pdf') {
      window.open(a.url, '_blank', 'noopener');
      return;
    }
    if (a.mime?.startsWith('text/') || TEXT_PREVIEW_EXT.test(extOf(a.name))) {
      setPreview({ kind: 'text', name: a.name, content: '', loading: true, downloadHref: a.downloadUrl });
      try {
        const res = await fetch(a.url, { credentials: 'same-origin' });
        if (!res.ok) throw new Error('读取文件失败');
        const text = await res.text();
        setPreview({ kind: 'text', name: a.name, content: text, downloadHref: a.downloadUrl });
      } catch (err) {
        setPreview({ kind: 'text', name: a.name, content: '', error: errorMessage(err), downloadHref: a.downloadUrl });
      }
      return;
    }
    downloadUrl(a.downloadUrl);
    toast(`正在下载「${a.name}」。`);
  };

  const openChatFilePreview = async (f: { id: string; conversationId: string; name: string }) => {
    setPreview({ kind: 'text', name: f.name, content: '', loading: true });
    try {
      const detail = await convApi.conversationFile(f.conversationId, f.id);
      setPreview({ kind: 'text', name: f.name, content: detail.content });
    } catch (err) {
      setPreview({ kind: 'text', name: f.name, content: '', error: errorMessage(err) });
    }
  };

  const downloadChatFile = async (f: { id: string; conversationId: string; name: string }, as: 'source' | 'docx' = 'source') => {
    if (downloadingId) return;
    setDownloadingId(f.id);
    try {
      const detail = await convApi.conversationFile(f.conversationId, f.id);
      downloadFile(f.name, detail.content, as);
      toast(`正在下载「${f.name}」。`);
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setDownloadingId(null);
    }
  };

  const openRepoDocPreview = async (path: string) => {
    if (!versionId) return;
    const name = baseName(path);
    const dl = api.versionFileDownloadUrl(versionId, path);
    if (!TEXT_PREVIEW_EXT.test(extOf(name))) {
      downloadUrl(dl);
      toast(`正在下载「${name}」。`);
      return;
    }
    setPreview({ kind: 'text', name, content: '', loading: true, downloadHref: dl });
    try {
      const content = await api.versionFile(versionId, path);
      setPreview({ kind: 'text', name, content, downloadHref: dl });
    } catch (err) {
      setPreview({ kind: 'text', name, content: '', error: errorMessage(err), downloadHref: dl });
    }
  };

  return (
    <>
      <div className="studio-pane-title">
        <Icon name="clip" />
        <span>文件</span>
        <small>{totalCount ? `${totalCount} 个文件 · ${sizeLabel(totalBytes)}` : '暂无文件'}</small>
      </div>
      <div className="studio-files">
        {loading ? (
          <LoadingState label="正在读取文件列表…" />
        ) : error ? (
          <ErrorState
            message={errorMessage(error)}
            onRetry={() => {
              void appFiles.refetch();
              if (versionId) void versionFiles.refetch();
            }}
          />
        ) : !totalCount ? (
          <EmptyState
            icon="folder"
            title="还没有文件"
            desc="聊天过程中上传的附件、粘贴的图片和 AI 生成的文档会集中显示在这里，并支持随时预览和下载。"
          />
        ) : (
          <>
            <div className="studio-file-labels">
              <span>名称</span>
              <span>来源</span>
              <span>大小</span>
              <span className="studio-file-op-head">操作</span>
            </div>
            {uploaded.map((a) => {
              const isImg = a.mime?.startsWith('image/');
              const canPreview = isImg || a.mime === 'application/pdf' || a.mime?.startsWith('text/') || TEXT_PREVIEW_EXT.test(extOf(a.name));
              return (
                <div key={`up-${a.id}`} className="studio-file-row">
                  <button
                    type="button"
                    className="studio-file-name-btn"
                    title={canPreview ? `预览 ${a.name}` : `下载 ${a.name}`}
                    onClick={() => void openUploadedPreview(a)}
                  >
                    {isImg ? (
                      <img className="studio-file-thumb" src={a.url} alt="" loading="lazy" />
                    ) : (
                      <Icon name="clip" />
                    )}
                    <b>{a.name}</b>
                  </button>
                  <small>上传附件</small>
                  <small>{sizeLabel(a.size ?? 0)}</small>
                  <div className="studio-file-row-actions">
                    {canPreview ? (
                      <button type="button" className="studio-file-btn" onClick={() => void openUploadedPreview(a)}>
                        预览
                      </button>
                    ) : null}
                    <button
                      type="button"
                      className="studio-file-btn primary"
                      onClick={() => {
                        downloadUrl(a.downloadUrl);
                        toast(`正在下载「${a.name}」。`);
                      }}
                    >
                      <Icon name="download" />
                      下载
                    </button>
                  </div>
                </div>
              );
            })}
            {chatFiles.map((f) => (
              <div key={`cf-${f.id}`} className="studio-file-row">
                <button
                  type="button"
                  className="studio-file-name-btn"
                  title={`预览 ${f.name}`}
                  onClick={() => void openChatFilePreview(f)}
                >
                  <Icon name="file" />
                  <b>{f.name}{f.version > 1 ? ` (V${f.version})` : ''}</b>
                </button>
                <small>对话文档</small>
                <small>{sizeLabel(f.size)}</small>
                <div className="studio-file-row-actions">
                  <button type="button" className="studio-file-btn" onClick={() => void openChatFilePreview(f)}>
                    预览
                  </button>
                  <button
                    type="button"
                    className="studio-file-btn primary"
                    disabled={downloadingId === f.id}
                    onClick={() => void downloadChatFile(f, 'source')}
                  >
                    <Icon name="download" />
                    下载
                  </button>
                </div>
              </div>
            ))}
            {repoDocs.map((f) => {
              const name = baseName(f.path);
              const canPreview = TEXT_PREVIEW_EXT.test(extOf(name));
              return (
                <div key={`doc-${f.path}`} className="studio-file-row">
                  <button
                    type="button"
                    className="studio-file-name-btn"
                    title={f.path}
                    onClick={() => void openRepoDocPreview(f.path)}
                  >
                    <Icon name="file" />
                    <b>{f.path}</b>
                  </button>
                  <small>应用文档</small>
                  <small>{sizeLabel(f.size)}</small>
                  <div className="studio-file-row-actions">
                    {canPreview ? (
                      <button type="button" className="studio-file-btn" onClick={() => void openRepoDocPreview(f.path)}>
                        预览
                      </button>
                    ) : null}
                    <button
                      type="button"
                      className="studio-file-btn primary"
                      onClick={() => {
                        if (!versionId) return;
                        downloadUrl(api.versionFileDownloadUrl(versionId, f.path));
                        toast(`正在下载「${name}」。`);
                      }}
                    >
                      <Icon name="download" />
                      下载
                    </button>
                  </div>
                </div>
              );
            })}
            <div className="studio-file-note">
              这里汇总聊天过程中上传的附件、粘贴的图片与 AI 生成的文档；应用源码请前往「代码」，导出整站请点右上角下载按钮。
            </div>
          </>
        )}
      </div>
      {preview?.kind === 'image'
        ? createPortal(
            <div className="asset-lightbox" role="dialog" aria-label={`预览 ${preview.name}`} onClick={() => setPreview(null)}>
              <div className="asset-lightbox-bar" onClick={(e) => e.stopPropagation()}>
                <span>{preview.name}</span>
                <button
                  type="button"
                  className="studio-file-btn primary"
                  onClick={() => {
                    downloadUrl(preview.downloadHref);
                    toast(`正在下载「${preview.name}」。`);
                  }}
                >
                  <Icon name="download" />
                  下载原图
                </button>
                <button type="button" className="studio-file-btn" onClick={() => setPreview(null)}>
                  关闭
                </button>
              </div>
              <img src={preview.src} alt={preview.name} onClick={(e) => e.stopPropagation()} />
            </div>,
            document.body,
          )
        : null}
      {preview?.kind === 'text' ? (
        <Modal
          title={preview.name}
          onClose={() => setPreview(null)}
          wide
        >
          <div className="studio-file-preview-modal">
            {preview.loading ? (
              <LoadingState label="正在读取文件内容…" />
            ) : preview.error ? (
              <ErrorState message={preview.error} />
            ) : (
              <>
                <div className="studio-file-preview-bar">
                  {/\.(md|markdown|txt)$/i.test(preview.name) ? (
                    <DownloadButton name={preview.name} body={preview.content} label="下载文件" className="studio-file-btn primary" />
                  ) : (
                    <button
                      type="button"
                      className="studio-file-btn primary"
                      onClick={() => {
                        if (preview.downloadHref) downloadUrl(preview.downloadHref);
                        else downloadFile(preview.name, preview.content, 'source');
                        toast(`正在下载「${preview.name}」。`);
                      }}
                    >
                      <Icon name="download" />
                      下载文件
                    </button>
                  )}
                </div>
                <div className="studio-file-preview-body">
                  {extOf(preview.name) === 'md' || extOf(preview.name) === 'markdown' ? (
                    <Markdown text={preview.content} className="file-doc" />
                  ) : extOf(preview.name) === 'csv' ? (
                    <CsvPreviewTable text={preview.content} />
                  ) : (
                    <pre className="file-plain">{preview.content}</pre>
                  )}
                </div>
              </>
            )}
          </div>
        </Modal>
      ) : null}
    </>
  );
}

export function CloudPane({ appId }: { appId: string }) {
  const qc = useQueryClient();
  const status = useQuery({ queryKey: ['cloud', appId], queryFn: () => api.cloudStatus(appId), enabled: !!appId });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const enabled = status.data?.enabled ?? false;

  const act = async (enable: boolean) => {
    setBusy(true);
    setErr('');
    try {
      if (enable) {
        await api.cloudEnable(appId);
        toast('云服务已启用，预览页里就能读写数据了。');
        await qc.invalidateQueries({ queryKey: ['cloud', appId] });
        void qc.invalidateQueries({ queryKey: ['apps'] });
      } else {
        await api.cloudDisable(appId);
        toast('已关闭，数据仍保留，重新启用就能继续用。');
        await qc.invalidateQueries({ queryKey: ['cloud', appId] });
        void qc.invalidateQueries({ queryKey: ['apps'] });
      }
    } catch (e) {
      setErr(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  if (status.isLoading && !status.data)
    return (
      <>
        <div className="studio-pane-title">
          <Icon name="cloud" />
          <span>云服务</span>
          <small>读取状态中…</small>
        </div>
        <LoadingState label="正在读取云服务状态…" />
      </>
    );

  if (status.error && !status.data)
    return (
      <>
        <div className="studio-pane-title">
          <Icon name="cloud" />
          <span>云服务</span>
        </div>
        <ErrorState message={errorMessage(status.error)} onRetry={() => void status.refetch()} />
      </>
    );

  if (enabled)
    return (
      <>
        <div className="studio-pane-title">
          <Icon name="cloud" />
          <span>云服务</span>
          <small>已启用 · 数据控制台</small>
        </div>
        {err ? (
          <p className="inline-error" role="alert">
            {err}
          </p>
        ) : null}
        <CloudConsole
          appId={appId}
          disabling={busy}
          onDisable={async () => {
            const ok = await confirmDialog({
              title: '关闭云服务？',
              message: '关闭后，应用里读写数据的功能会报错，员工登录等云服务能力也会停用。\n数据表和数据都会保留，之后重新启用就能接着用。',
              confirmLabel: '关闭云服务',
              danger: true,
            });
            if (ok) void act(false);
          }}
        />
      </>
    );

  return (
    <>
      <div className="studio-pane-title">
        <Icon name="cloud" />
        <span>云服务</span>
        <small>未启用</small>
      </div>
      <div className="studio-cloud">
        <div className="studio-cloud-orb">
          <Icon name="cloud" />
        </div>
        <h2>让这个应用真正存数据</h2>
        <p>
          点下面的按钮，对话框上方会弹出确认卡；确认后立即启用，AI 接着按这个应用的数据设计表结构和数据权限。预览和发布后的站点都能直接读写自己独立的一份数据，不需要自己部署服务端。
        </p>
        <div className="studio-cloud-grid">
          <article>
            <Icon name="grid" />
            <h3>数据库</h3>
            <p>每个应用一份独立的表空间，支持增删改查、筛选、统计，带数据控制台和 Excel 导入导出。</p>
          </article>
          <article>
            <Icon name="folder" />
            <h3>文件存储</h3>
            <p>上传的图片、附件和导出文件集中存放，可以读取或下载。</p>
          </article>
          <article>
            <Icon name="lock" />
            <h3>员工登录与权限</h3>
            <p>可选用公司账号登录；每张表可设「只看自己的」「管理员审批」等权限，由平台执行。</p>
          </article>
        </div>
        {err ? (
          <p className="inline-error" role="alert">
            {err}
          </p>
        ) : null}
        <button
          className="primary-button"
          disabled={status.isLoading}
          onClick={() => {
            // 先在对话框上方确认，确认后启用并让 AI 按当前应用设计表结构
            useChat.getState().setCloudAsk(true);
          }}
        >
          <Icon name="cloud" />
          让 AI 设计表结构并启用
        </button>
      </div>
    </>
  );
}
