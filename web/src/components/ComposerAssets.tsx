import { useCallback, useEffect, useRef, useState, type ClipboardEvent, type DragEvent } from 'react';
import { createPortal } from 'react-dom';
import { api, errorMessage } from '../api/client';
import type { Asset } from '../api/types';
import {
  LONG_PASTE_FILE_THRESHOLD,
  MAX_ATTACHMENTS,
  MAX_PROMPT_CHARS,
  makeLongPasteMarkdownFile,
  parseClipboardData,
  spliceAtCursor,
} from '../lib/clipboardPaste';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';

const MAX_ASSET_MB = 10;
const UPLOAD_CONCURRENCY = 3;

export interface PendingUpload {
  tempId: string;
  name: string;
  mime: string;
  size: number;
  previewUrl?: string;
}

export interface PasteNotice {
  blockedLocalImages: number;
  pastedImagesAfterNotice: number;
}

let pendingSeq = 0;

function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(bytes < 10240 ? 1 : 0)} KB`;
  return `${(bytes / 1048576).toFixed(1)} MB`;
}

function fileExtBadge(name: string, mime?: string): string {
  const m = /\.([a-z0-9]{1,6})$/i.exec(name.trim());
  if (m) return m[1].toUpperCase();
  if (mime?.includes('markdown')) return 'MD';
  if (mime?.includes('pdf')) return 'PDF';
  if (mime?.startsWith('text/')) return 'TXT';
  return 'FILE';
}

function isPreviewableTextAsset(asset: Asset): boolean {
  const lower = asset.name.toLowerCase();
  return asset.mime.startsWith('text/')
    || /\.(md|markdown|txt|csv|json|ya?ml|log)$/i.test(lower);
}

/**
 * 统一的输入框上传、粘贴（支持 Word/WPS 富文本 + 图片 + 超长自动转附件）与拖拽 Hook。
 * 被创造页、会话页、工作台对话（含计划阶段）共用，确保体验完全一致。
 */
export function useComposerAssets(options: {
  getAttachments: () => Asset[];
  addAttachment: (asset: Asset) => void;
  removeAttachment: (id: string) => void;
  getText: () => string;
  setText: (next: string) => void;
  textareaRef: React.RefObject<HTMLTextAreaElement | null>;
  maxAttachments?: number;
  maxChars?: number;
  disabled?: boolean;
}) {
  const maxAttachments = options.maxAttachments ?? MAX_ATTACHMENTS;
  const maxChars = options.maxChars ?? MAX_PROMPT_CHARS;
  const optsRef = useRef(options);
  optsRef.current = options;
  const [pending, setPending] = useState<PendingUpload[]>([]);
  const [pasteNotice, setPasteNotice] = useState<PasteNotice | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const dragDepth = useRef(0);
  const pendingCountRef = useRef(0);
  const objectUrlsRef = useRef<Set<string>>(new Set());

  // 卸载时释放未回收的本地预览 ObjectURL
  useEffect(() => {
    const urls = objectUrlsRef.current;
    return () => {
      for (const u of urls) URL.revokeObjectURL(u);
      urls.clear();
    };
  }, []);

  const uploadBatch = useCallback(
    async (
      files: Iterable<File>,
      opts?: { silentSuccessToast?: boolean; onUploaded?: (assets: Asset[]) => void },
    ): Promise<Asset[]> => {
      if (optsRef.current.disabled) return [];
      const currentTotal = optsRef.current.getAttachments().length + pendingCountRef.current;
      const room = Math.max(0, maxAttachments - currentTotal);
      const incoming = Array.from(files);
      if (!incoming.length) return [];
      if (room <= 0) {
        toast(`最多添加 ${maxAttachments} 份参考素材。`, 'error');
        return [];
      }
      const list = incoming.slice(0, room);
      const skippedCount = incoming.length - list.length;

      const valid: { file: File; item: PendingUpload }[] = [];
      for (const file of list) {
        if (file.size > MAX_ASSET_MB * 1048576) {
          toast(`${file.name} 超过 ${MAX_ASSET_MB} MB，请压缩后再上传。`, 'error');
          continue;
        }
        const previewUrl = file.type.startsWith('image/') && file.type !== 'image/svg+xml'
          ? URL.createObjectURL(file)
          : undefined;
        if (previewUrl) objectUrlsRef.current.add(previewUrl);
        valid.push({
          file,
          item: {
            tempId: `up-${++pendingSeq}`,
            name: file.name,
            mime: file.type || 'application/octet-stream',
            size: file.size,
            previewUrl,
          },
        });
      }
      if (!valid.length) return [];

      pendingCountRef.current += valid.length;
      setPending((prev) => [...prev, ...valid.map((v) => v.item)]);

      const uploaded: Asset[] = [];
      let cursor = 0;

      const worker = async () => {
        while (cursor < valid.length) {
          const entry = valid[cursor++];
          try {
            const asset = await api.uploadAsset(entry.file);
            uploaded.push(asset);
            optsRef.current.addAttachment(asset);
          } catch (err) {
            toast(`${entry.file.name}：${errorMessage(err)}`, 'error');
          } finally {
            pendingCountRef.current = Math.max(0, pendingCountRef.current - 1);
            setPending((prev) => prev.filter((p) => p.tempId !== entry.item.tempId));
            if (entry.item.previewUrl) {
              URL.revokeObjectURL(entry.item.previewUrl);
              objectUrlsRef.current.delete(entry.item.previewUrl);
            }
          }
        }
      };

      await Promise.all(Array.from({ length: Math.min(UPLOAD_CONCURRENCY, valid.length) }, () => worker()));

      if (uploaded.length) {
        const imgCount = uploaded.filter((a) => a.mime.startsWith('image/')).length;
        if (imgCount > 0) {
          setPasteNotice((cur) => (cur ? { ...cur, pastedImagesAfterNotice: cur.pastedImagesAfterNotice + imgCount } : null));
        }
        if (!opts?.silentSuccessToast) {
          const skipHint = skippedCount > 0 ? `（已达 ${maxAttachments} 份上限，其余 ${skippedCount} 份已忽略）` : '';
          toast(`已上传 ${uploaded.length} 份参考素材${skipHint}`);
        }
        opts?.onUploaded?.(uploaded);
      }
      return uploaded;
    },
    [maxAttachments],
  );

  const insertText = useCallback((textToInsert: string) => {
    const el = optsRef.current.textareaRef.current;
    const cur = optsRef.current.getText();
    const { nextValue, nextCursor } = spliceAtCursor(cur, textToInsert, el?.selectionStart, el?.selectionEnd);
    optsRef.current.setText(nextValue);
    requestAnimationFrame(() => {
      const target = optsRef.current.textareaRef.current;
      if (!target) return;
      target.focus();
      target.setSelectionRange(nextCursor, nextCursor);
    });
  }, []);

  const handlePaste = useCallback(
    (e: ClipboardEvent<HTMLTextAreaElement>) => {
      if (optsRef.current.disabled) return;
      const parsed = parseClipboardData(e.clipboardData);
      const el = optsRef.current.textareaRef.current;
      const current = optsRef.current.getText();
      const selStart = el?.selectionStart ?? current.length;
      const selEnd = el?.selectionEnd ?? selStart;
      const plainCandidate = parsed.text ?? (e.clipboardData?.getData('text/plain') || '').replace(/\r\n?/g, '\n');
      const mergedLen = current.length - (selEnd - selStart) + plainCandidate.length;
      const shouldConvertLongTextToFile =
        plainCandidate.trim().length > 0
        && (plainCandidate.length > LONG_PASTE_FILE_THRESHOLD || mergedLen > maxChars);

      // 纯普通短文本粘贴：不拦截，保留浏览器原生撤销栈与光标体验
      if (!parsed.handled && !shouldConvertLongTextToFile) return;

      e.preventDefault();

      const filesToUpload: File[] = [...parsed.files];
      let convertedLongFile: File | null = null;

      if (plainCandidate && plainCandidate.trim().length > 0) {
        if (shouldConvertLongTextToFile) {
          const room = maxAttachments - (optsRef.current.getAttachments().length + pendingCountRef.current + filesToUpload.length);
          if (room > 0) {
            convertedLongFile = makeLongPasteMarkdownFile(plainCandidate);
            filesToUpload.unshift(convertedLongFile);
          } else {
            // 附件已满时回退为截断插入并提示，绝不静默丢内容
            const avail = Math.max(0, maxChars - (current.length - (selEnd - selStart)));
            if (avail > 0) insertText(plainCandidate.slice(0, avail));
            toast(`附件数量已达上限（${maxAttachments} 份），超长文本仅保留了前 ${avail} 字。`, 'error');
          }
        } else {
          insertText(plainCandidate);
        }
      }

      if (parsed.blockedLocalImages > 0) {
        setPasteNotice({ blockedLocalImages: parsed.blockedLocalImages, pastedImagesAfterNotice: 0 });
      }

      if (filesToUpload.length > 0) {
        const longFileRef = convertedLongFile;
        const canInlineSwitch = Boolean(longFileRef && mergedLen <= maxChars);
        void uploadBatch(filesToUpload, {
          silentSuccessToast: Boolean(longFileRef) || parsed.recoveredRtfImages > 0 || parsed.blockedLocalImages > 0,
          onUploaded: (uploaded) => {
            if (longFileRef) {
              const docAsset = uploaded.find((a) => a.name === longFileRef.name) ?? uploaded[0];
              const imgCount = uploaded.filter((a) => a.mime.startsWith('image/')).length;
              const imgSuffix = imgCount > 0 ? ` 及 ${imgCount} 张图片` : '';
              toast(
                `粘贴内容共 ${plainCandidate.length.toLocaleString()} 字，已完整存为文档附件「${docAsset.name}」${imgSuffix}，避免截断。`,
                'ok',
                canInlineSwitch
                  ? {
                      label: '改为正文粘贴',
                      run: () => {
                        optsRef.current.removeAttachment(docAsset.id);
                        insertText(plainCandidate);
                      },
                    }
                  : undefined,
              );
            } else if (parsed.recoveredRtfImages > 0) {
              toast(`已粘贴文字与表格，并自动提取了 ${parsed.recoveredRtfImages} 张 Word 插图。`);
            } else if (parsed.blockedLocalImages > 0) {
              toast(`已粘贴文字与表格；有 ${parsed.blockedLocalImages} 张 Word 本地图片需单独复制粘贴。`);
            }
          },
        });
      } else if (parsed.fromRichHtml && parsed.blockedLocalImages === 0) {
        toast('已按 Markdown 表格与排版格式粘贴。');
      }
    },
    [insertText, maxAttachments, maxChars, uploadBatch],
  );

  const dropHandlers = {
    onDragEnter: (e: DragEvent) => {
      if (optsRef.current.disabled) return;
      if (!Array.from(e.dataTransfer.types).includes('Files')) return;
      e.preventDefault();
      dragDepth.current += 1;
      setDragOver(true);
    },
    onDragOver: (e: DragEvent) => {
      if (optsRef.current.disabled) return;
      if (!Array.from(e.dataTransfer.types).includes('Files')) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = 'copy';
    },
    onDragLeave: (e: DragEvent) => {
      if (optsRef.current.disabled) return;
      if (!Array.from(e.dataTransfer.types).includes('Files')) return;
      e.preventDefault();
      dragDepth.current = Math.max(0, dragDepth.current - 1);
      if (dragDepth.current === 0) setDragOver(false);
    },
    onDrop: (e: DragEvent) => {
      if (optsRef.current.disabled) return;
      if (!e.dataTransfer.files || e.dataTransfer.files.length === 0) return;
      e.preventDefault();
      dragDepth.current = 0;
      setDragOver(false);
      void uploadBatch(e.dataTransfer.files);
    },
  };

  return {
    pending,
    uploading: pending.length > 0,
    pasteNotice,
    dismissPasteNotice: () => setPasteNotice(null),
    dragOver,
    uploadBatch,
    handlePaste,
    dropHandlers,
  };
}

/** 输入框内的图片缩略图 + 上传进度态 + 文档预览卡组件 */
export function ComposerAttachmentList({
  assets,
  pending,
  onRemove,
}: {
  assets: Asset[];
  pending: PendingUpload[];
  onRemove: (id: string) => void;
}) {
  const [viewingImg, setViewingImg] = useState<{ src: string; name: string } | null>(null);
  const [viewingDoc, setViewingDoc] = useState<{ name: string; content: string; loading: boolean } | null>(null);

  useEffect(() => {
    if (!viewingImg && !viewingDoc) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setViewingImg(null);
        setViewingDoc(null);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [viewingImg, viewingDoc]);

  const openDocPreview = async (asset: Asset) => {
    if (!isPreviewableTextAsset(asset)) {
      window.open(api.assetContentUrl(asset.id), '_blank', 'noopener,noreferrer');
      return;
    }
    setViewingDoc({ name: asset.name, content: '', loading: true });
    try {
      const res = await fetch(api.assetContentUrl(asset.id), { credentials: 'same-origin' });
      const text = res.ok ? await res.text() : '无法加载文档内容。';
      setViewingDoc({ name: asset.name, content: text, loading: false });
    } catch {
      setViewingDoc({ name: asset.name, content: '读取预览失败，请重试。', loading: false });
    }
  };

  if (!assets.length && !pending.length) return null;

  return (
    <>
      {assets.map((a) =>
        a.mime?.startsWith('image/') && a.mime !== 'image/svg+xml' ? (
          <div key={a.id} className="ca-thumb" title={`${a.name}（点击查看大图）`}>
            <button
              type="button"
              className="ca-thumb-open"
              aria-label={`预览图片 ${a.name}`}
              onClick={() => setViewingImg({ src: api.assetContentUrl(a.id), name: a.name })}
            >
              <img src={api.assetContentUrl(a.id)} alt={a.name} />
            </button>
            <button
              type="button"
              className="ca-thumb-remove"
              aria-label={`移除图片 ${a.name}`}
              title="移除"
              onClick={() => onRemove(a.id)}
            >
              <Icon name="close" />
            </button>
          </div>
        ) : (
          <div key={a.id} className="ca-file">
            <button
              type="button"
              className="ca-file-open"
              title={`${a.name}${a.size ? ` · ${formatBytes(a.size)}` : ''}（点击预览）`}
              onClick={() => void openDocPreview(a)}
            >
              <span className="ca-file-ext">{fileExtBadge(a.name, a.mime)}</span>
              <span className="ca-file-meta">
                <b>{a.name}</b>
                {a.size ? <small>{formatBytes(a.size)}</small> : null}
              </span>
            </button>
            <button
              type="button"
              className="ca-file-remove"
              aria-label={`移除附件 ${a.name}`}
              title="移除"
              onClick={() => onRemove(a.id)}
            >
              <Icon name="close" />
            </button>
          </div>
        ),
      )}

      {pending.map((p) =>
        p.previewUrl ? (
          <div key={p.tempId} className="ca-thumb is-uploading" title={`正在上传 ${p.name}…`}>
            <img src={p.previewUrl} alt={p.name} />
            <span className="ca-thumb-spinner" aria-label="上传中">
              <Icon name="refresh" className="spinning" />
            </span>
          </div>
        ) : (
          <div key={p.tempId} className="ca-file is-uploading" title={`正在上传 ${p.name}…`}>
            <span className="ca-file-ext">
              <Icon name="refresh" className="spinning" />
            </span>
            <span className="ca-file-meta">
              <b>{p.name}</b>
              <small>上传中…</small>
            </span>
          </div>
        ),
      )}

      {viewingImg
        ? createPortal(
            <div className="asset-lightbox" role="dialog" aria-label={`预览 ${viewingImg.name}`} onClick={() => setViewingImg(null)}>
              <img src={viewingImg.src} alt={viewingImg.name} onClick={(e) => e.stopPropagation()} />
            </div>,
            document.body,
          )
        : null}

      {viewingDoc
        ? createPortal(
            <div className="ca-doc-modal-mask" role="dialog" aria-label={`预览 ${viewingDoc.name}`} onClick={() => setViewingDoc(null)}>
              <div className="ca-doc-modal" onClick={(e) => e.stopPropagation()}>
                <header className="ca-doc-modal-head">
                  <div>
                    <b>{viewingDoc.name}</b>
                    {!viewingDoc.loading ? <small>{viewingDoc.content.length.toLocaleString()} 字</small> : null}
                  </div>
                  <button type="button" className="icon-button" aria-label="关闭预览" onClick={() => setViewingDoc(null)}>
                    <Icon name="close" />
                  </button>
                </header>
                <div className="ca-doc-modal-body">
                  {viewingDoc.loading ? <p className="ca-doc-loading">正在读取…</p> : <pre>{viewingDoc.content}</pre>}
                </div>
              </div>
            </div>,
            document.body,
          )
        : null}
    </>
  );
}

/** Word 全选粘贴遇浏览器 file:/// 限制时的内联友好提示条 */
export function PasteNoticeBanner({
  notice,
  onDismiss,
}: {
  notice: PasteNotice | null;
  onDismiss: () => void;
}) {
  if (!notice) return null;
  const remaining = Math.max(0, notice.blockedLocalImages - notice.pastedImagesAfterNotice);
  return (
    <div className="paste-notice-banner" role="status">
      <Icon name="alert" />
      <span>
        {remaining > 0 ? (
          <>
            文字与表格已保留；Word 中的 <b>{notice.blockedLocalImages} 张本地图片</b>受浏览器安全限制无法随全选直接读取
            {notice.pastedImagesAfterNotice > 0 ? `（已补贴 ${notice.pastedImagesAfterNotice} 张）` : ''}。
            请在 Word 里<b>单选图片 Ctrl+C</b>，回到这里直接 <b>Ctrl+V</b> 粘贴即可。
          </>
        ) : (
          <>已补全粘贴 {notice.pastedImagesAfterNotice} 张图片，现在可以直接发送了。</>
        )}
      </span>
      <button type="button" aria-label="关闭提示" onClick={onDismiss}>
        <Icon name="close" />
      </button>
    </div>
  );
}

/** 输入框拖放提示层 */
export function ComposerDropOverlay({ visible }: { visible: boolean }) {
  if (!visible) return null;
  return (
    <div className="composer-drop-overlay" aria-hidden="true">
      <Icon name="clip" />
      <span>松开即可添加图片或文档（最多 {MAX_ATTACHMENTS} 份）</span>
    </div>
  );
}
