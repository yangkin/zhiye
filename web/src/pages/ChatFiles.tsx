import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { errorMessage } from '../api/client';
import { api, type ConversationFile } from '../api/conversation';
import { ErrorState, LoadingState } from '../components/EmptyState';
import { markdownToDocx } from '../lib/docx';
import { Icon } from '../lib/icons';
import { Markdown } from '../lib/markdown';
import { toast } from '../lib/store';
import { confirmDialog } from '../components/ConfirmDialog';

/** 右侧面板当前打开的文件：从对话里的文件卡片打开时直接带正文，从列表打开时按 id 读取 */
export type OpenFile = { name: string; body?: string; fileId?: string };

export const filesKey = (id: string) => ['conversation-files', id] as const;

const ext = (name: string) => (name.split('.').pop() ?? '').toLowerCase();
export const kindLabel = (name: string) => ({ md: 'Markdown', markdown: 'Markdown', txt: '文本', csv: 'CSV', json: 'JSON', html: 'HTML' })[ext(name)] ?? (ext(name).toUpperCase() || '文件');

function saveBlob(name: string, blob: Blob) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function downloadFile(name: string, body: string, as: 'source' | 'docx') {
  if (as === 'docx') saveBlob(name.replace(/\.(md|markdown|txt)$/i, '') + '.docx', markdownToDocx(body));
  else saveBlob(name, new Blob([body + '\n'], { type: 'text/markdown;charset=utf-8' }));
}

/** 「下载」按钮：Markdown 文件可以选原文或 Word */
export function DownloadButton({ name, body, label = '下载', className = '' }: { name: string; body: string; label?: string; className?: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const canWord = /\.(md|markdown|txt)$/i.test(name);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false);
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', esc);
    };
  }, [open]);
  return (
    <div className="file-download" ref={ref}>
      <button type="button" className={className} aria-haspopup={canWord ? 'menu' : undefined} aria-expanded={canWord ? open : undefined} onClick={() => (canWord ? setOpen((v) => !v) : downloadFile(name, body, 'source'))}>
        <Icon name="download" />
        {label}
        {canWord ? <Icon name="down" className="file-download-caret" /> : null}
      </button>
      {open ? (
        <div className="file-download-menu" role="menu">
          <button type="button" role="menuitem" onClick={() => { setOpen(false); downloadFile(name, body, 'docx'); }}>
            <b>Word 文档</b>
            <small>.docx</small>
          </button>
          <button type="button" role="menuitem" onClick={() => { setOpen(false); downloadFile(name, body, 'source'); }}>
            <b>Markdown 原文</b>
            <small>.{ext(name)}</small>
          </button>
        </div>
      ) : null}
    </div>
  );
}

/** 按文件类型预览 */
function FilePreview({ name, body }: { name: string; body: string }) {
  const e = ext(name);
  if (e === 'md' || e === 'markdown') return <Markdown text={body} className="file-doc" />;
  if (e === 'csv') return <CsvTable body={body} />;
  return <pre className="file-plain">{body}</pre>;
}

function CsvTable({ body }: { body: string }) {
  const rows = useMemo(() => body.split(/\r?\n/).filter(Boolean).slice(0, 500).map((l) => l.split(',')), [body]);
  return (
    <div className="md-table file-doc">
      <table>
        <thead><tr>{rows[0]?.map((c, i) => <th key={i}>{c}</th>)}</tr></thead>
        <tbody>{rows.slice(1).map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}

/** 会话页右侧「文件」面板：左边是这段会话里 AI 生成的文件，右边按类型预览当前文件 */
export function ChatFilesPanel({ conversationId, open, onOpen, onClose }: { conversationId: string; open: OpenFile | null; onOpen: (f: OpenFile | null) => void; onClose: () => void }) {
  const qc = useQueryClient();
  const [query, setQuery] = useState('');
  const [groupOpen, setGroupOpen] = useState(true);
  const list = useQuery({ queryKey: filesKey(conversationId), queryFn: () => api.conversationFiles(conversationId) });
  const files = list.data ?? [];
  const shown = files.filter((f) => f.name.toLowerCase().includes(query.trim().toLowerCase()));
  const current = open?.fileId ? files.find((f) => f.id === open.fileId) : files.find((f) => f.name === open?.name);
  const detail = useQuery({
    queryKey: ['conversation-file', conversationId, open?.fileId],
    queryFn: () => api.conversationFile(conversationId, open!.fileId!),
    enabled: !!open?.fileId && open.body === undefined,
  });
  const body = open?.body ?? detail.data?.content;

  // 没指定文件时默认打开最新的一个
  useEffect(() => {
    if (!open && files.length) onOpen({ name: files[0].name, fileId: files[0].id });
  }, [open, files, onOpen]);

  const refresh = () => {
    void qc.invalidateQueries({ queryKey: filesKey(conversationId) });
    void qc.invalidateQueries({ queryKey: ['conversation-file', conversationId] });
    if (current) onOpen({ name: current.name, fileId: current.id });
  };

  const remove = async (f: ConversationFile) => {
    if (!(await confirmDialog({ title: `删除「${f.name}」？`, message: '对话里的回答会保留。', confirmLabel: '删除', danger: true }))) return;
    try {
      await api.deleteConversationFile(conversationId, f.id);
      if (current?.name === f.name) onOpen(null);
      await qc.invalidateQueries({ queryKey: filesKey(conversationId) });
      toast('文件已删除。');
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };

  return (
    <aside className="chat-files" aria-label="文件">
      <div className="chat-files-tabs">
        <span className="active"><Icon name="clip" /> 文件</span>
        <button type="button" className="chat-files-close" aria-label="关闭文件面板" title="关闭" onClick={onClose}>
          <Icon name="close" />
        </button>
      </div>
      <div className="chat-files-body">
        <nav className="chat-files-tree" aria-label="文件列表">
          <label className="chat-files-search">
            <Icon name="search" />
            <input placeholder="搜索文件..." value={query} onChange={(e) => setQuery(e.target.value)} aria-label="搜索文件" />
          </label>
          {list.isLoading ? <LoadingState label="正在读取…" /> : list.error ? <ErrorState message={errorMessage(list.error)} onRetry={() => list.refetch()} /> : (
            <>
              <button type="button" className="chat-files-group" aria-expanded={groupOpen} onClick={() => setGroupOpen((v) => !v)}>
                <Icon name="chevron" className={groupOpen ? 'open' : ''} />
                AI 生成（{files.length}）
              </button>
              {groupOpen ? shown.map((f) => (
                <div key={f.id} className={`chat-files-item ${current?.name === f.name ? 'active' : ''}`}>
                  <button type="button" title={f.name} onClick={() => onOpen({ name: f.name, fileId: f.id })}>
                    <Icon name="file" />
                    <span>{f.name}</span>
                    {f.version > 1 ? <small>V{f.version}</small> : null}
                  </button>
                  <button type="button" className="chat-files-del" aria-label={`删除 ${f.name}`} title="删除" onClick={() => void remove(f)}>
                    <Icon name="trash" />
                  </button>
                </div>
              )) : null}
              {groupOpen && files.length && !shown.length ? <p className="chat-files-empty">没有匹配的文件</p> : null}
            </>
          )}
        </nav>
        <section className="chat-files-view">
          {open ? (
            <>
              <div className="chat-files-viewhead">
                <span className="chat-files-tab" title={open.name}>
                  <span>{open.name}</span>
                  <button type="button" aria-label="关闭预览" onClick={() => onOpen(null)}><Icon name="close" /></button>
                </span>
                <div className="chat-files-tools">
                  <button type="button" className="chat-files-icon" aria-label="刷新" title="刷新" onClick={refresh}><Icon name="refresh" /></button>
                  {body !== undefined ? <DownloadButton name={open.name} body={body} label="下载文件" className="chat-files-btn" /> : null}
                  {current ? (
                    <button type="button" className="chat-files-icon" aria-label="删除文件" title="删除" onClick={() => void remove(current)}><Icon name="trash" /></button>
                  ) : null}
                </div>
              </div>
              <div className="chat-files-content">
                {body !== undefined ? <FilePreview name={open.name} body={body} /> : detail.error ? <ErrorState message={errorMessage(detail.error)} onRetry={() => detail.refetch()} /> : <LoadingState label="正在读取文件…" />}
              </div>
            </>
          ) : (
            <div className="chat-files-blank">{files.length ? '从左侧选择一个文件预览' : '这段会话还没有生成文件'}</div>
          )}
        </section>
      </div>
    </aside>
  );
}

