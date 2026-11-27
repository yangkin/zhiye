import { useCallback, useEffect, useMemo, useRef, useState, type ClipboardEvent, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { api } from '../api/client';
import { qk } from '../api/queries';
import { HelpError, helpApi, streamHelp, type HelpAsset, type HelpConversation, type HelpMessage } from '../api/help';
import { Icon } from '../lib/icons';
import { HELP, HELP_CHIPS, HELP_DOC_CHAPTERS, offlineAnswer, type HelpChip, type HelpDocChapter } from '../lib/helpFaq';
import { copyText, fmtDate, fmtTime, isToday, isYesterday } from '../lib/format';
import { parseClipboardData, spliceAtCursor } from '../lib/clipboardPaste';
import { toast, useUi } from '../lib/store';
import { Pet } from './Pet';
import '../styles/help.css';

/** 界面里的一条消息（比服务端多了本地状态） */
interface UiMsg {
  key: string;
  id?: string;
  role: 'user' | 'assistant';
  content: string;
  status: 'pending' | 'streaming' | 'done' | 'stopped' | 'error' | 'feedback' | 'offline';
  assets: { id?: string; url: string; name: string }[];
}

interface Draft {
  key: string;
  file?: File;
  preview: string;
  asset?: HelpAsset;
  error?: string;
}

const MAX_IMAGES = 3;
const MAX_CHARS = 2000;
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const STORAGE = 'zhiye.help.conversation.';
const DEFAULT_TITLE = '新对话';

let keySeq = 0;
const nextKey = () => `k${++keySeq}`;

marked.setOptions({ gfm: true, breaks: true });

function renderMarkdown(md: string): string {
  const raw = marked.parse(md, { async: false }) as string;
  return DOMPurify.sanitize(raw, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button', 'iframe', 'img'],
    FORBID_ATTR: ['style'],
  });
}

function fromServer(m: HelpMessage): UiMsg {
  return {
    key: m.id,
    id: m.id,
    role: m.role,
    content: m.content,
    status: m.status,
    assets: m.assets.map((a) => ({ id: a.id, url: a.url, name: a.name })),
  };
}

function timeLabel(iso: string) {
  if (isToday(iso)) return fmtTime(iso);
  if (isYesterday(iso)) return '昨天 ' + fmtTime(iso);
  return fmtDate(iso) + ' ' + fmtTime(iso);
}

/** 消息旁的小织头像（小机器人的头，静态） */
function PetFace() {
  return (
    <span className="xz-avatar" aria-hidden="true">
      <img src="/pet/robot-face.webp" alt="" width={30} height={30} draggable={false} />
    </span>
  );
}

function AssistantBody({ msg }: { msg: UiMsg }) {
  const html = useMemo(() => (msg.content ? renderMarkdown(msg.content) : ''), [msg.content]);
  const [copied, setCopied] = useState(false);
  if (msg.status === 'pending' || (msg.status === 'streaming' && !msg.content)) {
    return (
      <div className="xz-typing" role="status">
        <span className="xz-sniff">嗅到了</span>
        <i />
        <i />
        <i />
      </div>
    );
  }
  const canCopy = Boolean(msg.content) && msg.status !== 'streaming';
  const onCopy = async () => {
    const ok = await copyText(msg.content);
    if (ok) {
      setCopied(true);
      toast('已复制到剪贴板', 'ok');
      window.setTimeout(() => setCopied(false), 1800);
    } else {
      toast('复制未成功，请直接选中文字按 Ctrl/⌘+C 复制', 'error');
    }
  };
  return (
    <>
      <div className="xz-md" dangerouslySetInnerHTML={{ __html: html }} />
      {msg.status === 'streaming' ? <span className="xz-caret" aria-hidden="true" /> : null}
      {msg.status === 'stopped' ? <small className="xz-note">已停止回答</small> : null}
      {msg.status === 'error' ? <small className="xz-note err">回答中断了</small> : null}
      {canCopy ? (
        <div className="xz-msg-actions">
          <button type="button" className={`xz-copy-btn${copied ? ' is-copied' : ''}`} onClick={() => void onCopy()} aria-label="复制这条回答">
            <Icon name={copied ? 'check' : 'copy'} />
            <span>{copied ? '已复制' : '复制'}</span>
          </button>
        </div>
      ) : null}
    </>
  );
}

/** 小织的使用帮助面板（右侧抽屉）：模型对话 + 历史 + 截图 + 问题上报；服务不可用时退回内置常见问题。 */
export function HelpPanel() {
  const panelRef = useRef<HTMLElement | null>(null);
  const open = useUi((s) => s.helpOpen);
  const setOpen = useUi((s) => s.setHelpOpen);
  const helpTab = useUi((s) => s.helpTab);
  const setHelpTab = useUi((s) => s.setHelpTab);
  const { data: workspace } = useQuery({ queryKey: qk.workspace, queryFn: api.workspace, enabled: open, staleTime: 5 * 60_000 });
  const { data: remoteDocs } = useQuery({
    queryKey: ['help-docs'],
    queryFn: helpApi.docs,
    enabled: open,
    staleTime: 60_000,
    retry: false,
  });
  const wsId = workspace?.id ?? null;

  const [view, setView] = useState<'chat' | 'history'>('chat');
  const [docChapterId, setDocChapterId] = useState<string>('workflow');
  const [docQuery, setDocQuery] = useState('');
  const [openFaqId, setOpenFaqId] = useState<string | null>('start');

  const docChapters = useMemo<HelpDocChapter[]>(() => {
    const items = remoteDocs?.items;
    if (!items || items.length === 0) return HELP_DOC_CHAPTERS;
    const dynamic: HelpDocChapter[] = items.map((a) => ({
      id: a.slug as HelpDocChapter['id'],
      title: a.title,
      shortTitle: a.shortTitle || a.title.slice(0, 8),
      badge: a.badge || undefined,
      summary: a.summary || '',
      markdown: a.contentMd || '',
      askPrompt: a.askPrompt || `帮我详细说明一下「${a.title}」`,
    }));
    if (!dynamic.some((c) => c.id === 'faq')) {
      const faqCh = HELP_DOC_CHAPTERS.find((c) => c.id === 'faq');
      if (faqCh) dynamic.push(faqCh);
    }
    return dynamic;
  }, [remoteDocs]);
  const [convId, setConvId] = useState<string | null>(null);
  const [title, setTitle] = useState(DEFAULT_TITLE);
  const [msgs, setMsgs] = useState<UiMsg[]>([]);
  const [loadingConv, setLoadingConv] = useState(false);
  const [history, setHistory] = useState<HelpConversation[] | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [images, setImages] = useState<Draft[]>([]);
  const [mode, setMode] = useState<'ask' | 'report'>('ask');
  const [busy, setBusy] = useState(false); // 回答中或提交中
  const [notice, setNotice] = useState<string | null>(null);

  const listRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const streamConvRef = useRef<string | null>(null);
  const restoredFor = useRef<string | null>(null);
  const stickToBottom = useRef(true);

  const streaming = busy && mode === 'ask';
  // 刚开始回答的 1 秒内不响应「停止」：停止按钮就在发送按钮的位置，连按回车或连点会误停
  const [stopArmed, setStopArmed] = useState(false);
  useEffect(() => {
    setStopArmed(false);
    if (!streaming) return;
    const t = window.setTimeout(() => setStopArmed(true), 1000);
    return () => window.clearTimeout(t);
  }, [streaming]);

  // ---------- 记住上次打开的对话（按工作空间） ----------
  const remember = useCallback(
    (id: string | null) => {
      if (!wsId) return;
      try {
        if (id) localStorage.setItem(STORAGE + wsId, id);
        else localStorage.removeItem(STORAGE + wsId);
      } catch {
        /* 隐私模式下忽略 */
      }
    },
    [wsId],
  );

  const openConversation = useCallback(
    async (id: string, knownTitle?: string) => {
      setView('chat');
      setLoadingConv(true);
      setNotice(null);
      try {
        const list = await helpApi.messages(id);
        setConvId(id);
        setMsgs(list.map(fromServer));
        if (knownTitle) setTitle(knownTitle);
        else {
          const all = await helpApi.conversations().catch(() => [] as HelpConversation[]);
          setTitle(all.find((c) => c.id === id)?.title ?? DEFAULT_TITLE);
        }
        remember(id);
        stickToBottom.current = true;
      } catch (e) {
        if (e instanceof HelpError && e.status === 404) {
          remember(null);
          setConvId(null);
          setMsgs([]);
          setTitle(DEFAULT_TITLE);
        } else setNotice(e instanceof Error ? e.message : '加载对话失败');
      } finally {
        setLoadingConv(false);
      }
    },
    [remember],
  );

  useEffect(() => {
    if (!open || !wsId || restoredFor.current === wsId) return;
    restoredFor.current = wsId;
    let saved: string | null = null;
    try {
      saved = localStorage.getItem(STORAGE + wsId);
    } catch {
      saved = null;
    }
    if (saved && saved !== convId) void openConversation(saved);
  }, [open, wsId, convId, openConversation]);

  // ---------- 键盘、焦点归还与滚动 ----------
  const prevFocusRef = useRef<HTMLElement | null>(null);
  const wasOpenRef = useRef(false);
  useEffect(() => {
    if (open && !wasOpenRef.current) {
      prevFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      requestAnimationFrame(() => {
        if (helpTab === 'chat' && view === 'chat' && inputRef.current) {
          inputRef.current.focus();
        } else {
          panelRef.current?.querySelector<HTMLElement>('button, input, textarea, [tabindex]')?.focus();
        }
      });
    } else if (!open && wasOpenRef.current) {
      const target = prevFocusRef.current;
      prevFocusRef.current = null;
      if (target && document.contains(target)) {
        requestAnimationFrame(() => target.focus());
      }
    }
    wasOpenRef.current = open;
  }, [open, helpTab, view]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        setOpen(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, setOpen]);

  // 点面板以外的地方关闭（点到小织本身不算关闭，由它自己切换）
  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      const t = e.target as HTMLElement | null;
      if (!t) return;
      if (panelRef.current?.contains(t)) return;
      if (t.closest('.pet')) return;
      setOpen(false);
    };
    document.addEventListener('pointerdown', onDown, true);
    return () => document.removeEventListener('pointerdown', onDown, true);
  }, [open, setOpen]);

  useEffect(() => {
    const el = listRef.current;
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight;
  }, [msgs, view, open]);

  useEffect(() => {
    if (open && helpTab === 'chat' && view === 'chat') inputRef.current?.focus();
  }, [open, helpTab, view, mode]);

  const onScroll = () => {
    const el = listRef.current;
    if (el) stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
  };

  // ---------- 历史 ----------
  const loadHistory = useCallback(async () => {
    setHistoryError(null);
    try {
      setHistory(await helpApi.conversations());
    } catch (e) {
      setHistory([]);
      setHistoryError(e instanceof HelpError && e.status === 503 ? '小织暂时离线，历史对话稍后再看' : e instanceof Error ? e.message : '加载失败');
    }
  }, []);

  const toggleHistory = () => {
    if (view === 'history') setView('chat');
    else {
      setView('history');
      setHistory(null);
      void loadHistory();
    }
  };

  const removeConversation = async (c: HelpConversation) => {
    try {
      await helpApi.remove(c.id);
      setHistory((h) => (h ? h.filter((x) => x.id !== c.id) : h));
      if (c.id === convId) startNew();
    } catch (e) {
      setHistoryError(e instanceof Error ? e.message : '删除失败');
    }
  };

  // ---------- 新对话 ----------
  const stopStreaming = useCallback(() => {
    const id = streamConvRef.current;
    if (!id) return;
    void helpApi.stop(id).catch(() => undefined);
    // 服务端会以 done(stopped) 结束流；保险起见几秒后直接断开
    const ctrl = abortRef.current;
    setTimeout(() => ctrl?.abort(), 4000);
  }, []);

  const startNew = () => {
    if (streaming) stopStreaming();
    abortRef.current?.abort();
    abortRef.current = null;
    streamConvRef.current = null;
    setBusy(false);
    setConvId(null);
    setMsgs([]);
    setTitle(DEFAULT_TITLE);
    setMode('ask');
    setNotice(null);
    setView('chat');
    remember(null);
  };

  // ---------- 图片 ----------
  const addFiles = (files: File[]) => {
    const imgs = files.filter((f) => f.type.startsWith('image/') && f.type !== 'image/svg+xml');
    if (!imgs.length) {
      if (files.length) setNotice('只能上传 PNG、JPG、GIF、WebP 图片');
      return;
    }
    const room = MAX_IMAGES - images.length;
    if (room <= 0) {
      setNotice(`最多附 ${MAX_IMAGES} 张图片`);
      return;
    }
    if (imgs.length > room) setNotice(`最多附 ${MAX_IMAGES} 张图片，多出的已忽略`);
    for (const file of imgs.slice(0, room)) {
      const key = nextKey();
      const preview = URL.createObjectURL(file);
      if (file.size > MAX_IMAGE_BYTES) {
        setImages((l) => [...l, { key, file, preview, error: '图片超过 10 MB' }]);
        continue;
      }
      setImages((l) => [...l, { key, file, preview }]);
      helpApi
        .uploadImage(file)
        .then((asset) => setImages((l) => l.map((d) => (d.key === key ? { ...d, asset } : d))))
        .catch((e) => setImages((l) => l.map((d) => (d.key === key ? { ...d, error: e instanceof Error ? e.message : '上传失败' } : d))));
    }
  };

  const removeImage = (key: string) =>
    setImages((l) => {
      const d = l.find((x) => x.key === key);
      if (d?.file) URL.revokeObjectURL(d.preview);
      return l.filter((x) => x.key !== key);
    });

  const onPaste = (e: ClipboardEvent<HTMLTextAreaElement>) => {
    const parsed = parseClipboardData(e.clipboardData);
    const imgFiles = parsed.files.filter((f) => f.type.startsWith('image/') && f.type !== 'image/svg+xml');
    if (!imgFiles.length && !parsed.blockedLocalImages && !parsed.fromRichHtml) return;
    e.preventDefault();
    if (parsed.text && parsed.text.trim()) {
      const el = inputRef.current;
      const { nextValue, nextCursor } = spliceAtCursor(draft, parsed.text, el?.selectionStart, el?.selectionEnd);
      setDraft(nextValue);
      requestAnimationFrame(() => {
        inputRef.current?.focus();
        inputRef.current?.setSelectionRange(nextCursor, nextCursor);
      });
    }
    if (imgFiles.length) addFiles(imgFiles);
    if (parsed.blockedLocalImages > 0) {
      setNotice(`文字已粘贴；Word 中的 ${parsed.blockedLocalImages} 张本地图片受浏览器限制无法随全选读取，请在 Word 中单选图片 Ctrl+C 后粘贴。`);
    }
  };

  // ---------- 发送 ----------
  const uploading = images.some((d) => !d.asset && !d.error);
  const readyImages = images.filter((d) => d.asset);
  const canSend = !busy && !uploading && (draft.trim().length > 0 || readyImages.length > 0) && draft.length <= MAX_CHARS;

  const ensureConversation = async (): Promise<string> => {
    if (convId) return convId;
    const c = await helpApi.create();
    setConvId(c.id);
    setTitle(c.title);
    remember(c.id);
    return c.id;
  };

  const patchMsg = (key: string, f: (m: UiMsg) => UiMsg) => setMsgs((l) => l.map((m) => (m.key === key ? f(m) : m)));

  const send = async (text?: string) => {
    const content = (text ?? draft).trim();
    const attach = text === undefined ? readyImages : [];
    if (busy || (!content && !attach.length)) return;
    if (content.length > MAX_CHARS) {
      setNotice(`消息最多 ${MAX_CHARS} 个字`);
      return;
    }
    setNotice(null);
    setView('chat');
    stickToBottom.current = true;
    const assetIds = attach.map((d) => d.asset!.id);
    const userKey = nextKey();
    const botKey = nextKey();
    const userMsg: UiMsg = {
      key: userKey,
      role: 'user',
      content: mode === 'report' ? '【问题上报】' + content : content,
      status: 'done',
      assets: attach.map((d) => ({ id: d.asset!.id, url: d.asset!.url, name: d.asset!.name })),
    };
    setMsgs((l) => [...l, userMsg, { key: botKey, role: 'assistant', content: '', status: 'pending', assets: [] }]);
    if (text === undefined) {
      setDraft('');
      setImages([]);
    }
    setBusy(true);

    if (mode === 'report') {
      try {
        const id = await ensureConversation().catch(() => null);
        const r = await helpApi.feedback({ content, assetIds, conversationId: id });
        patchMsg(botKey, (m) => ({ ...m, content: r.reply, status: 'feedback' }));
        if (id && r.messages?.length === 2) {
          const [u, a] = r.messages;
          patchMsg(userKey, (m) => ({ ...m, id: u.id }));
          patchMsg(botKey, (m) => ({ ...m, id: a.id }));
          if (title === DEFAULT_TITLE) setTitle(('问题上报：' + content).slice(0, 20));
        }
        setMode('ask');
      } catch (e) {
        patchMsg(botKey, (m) => ({ ...m, content: `提交没有成功：${e instanceof Error ? e.message : '请稍后再试'}`, status: 'error' }));
      } finally {
        setBusy(false);
      }
      return;
    }

    let id: string;
    try {
      id = await ensureConversation();
    } catch (e) {
      const offline = e instanceof HelpError && (e.status === 503 || e.status === 0 || e.status >= 500);
      patchMsg(botKey, (m) => ({ ...m, content: offline ? offlineAnswer(content) : String((e as Error).message), status: offline ? 'offline' : 'error' }));
      setBusy(false);
      return;
    }
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    streamConvRef.current = id;
    const finish = () => {
      if (abortRef.current === ctrl) {
        abortRef.current = null;
        streamConvRef.current = null;
        setBusy(false);
      }
    };
    try {
      await streamHelp(
        id,
        { content, assetIds },
        {
          onUser: (m, t) => {
            patchMsg(userKey, (x) => ({ ...x, id: m.id }));
            if (t) setTitle(t);
            patchMsg(botKey, (x) => (x.status === 'pending' ? { ...x, status: 'streaming' } : x));
          },
          onDelta: (t) => patchMsg(botKey, (x) => ({ ...x, content: x.content + t, status: x.status === 'stopped' ? 'stopped' : 'streaming' })),
          onDone: (d) => {
            patchMsg(botKey, (x) => ({ ...x, id: d.messageId ?? undefined, status: d.status === 'stopped' ? 'stopped' : 'done' }));
            if (d.title) setTitle(d.title);
          },
          onError: () => patchMsg(botKey, (x) => ({ ...x, content: (x.content ? x.content + '\n\n---\n\n' : '') + offlineAnswer(content), status: 'offline' })),
        },
        ctrl.signal,
      );
      if (ctrl.signal.aborted) patchMsg(botKey, (x) => (x.status === 'pending' || x.status === 'streaming' ? { ...x, status: 'stopped' } : x));
    } catch (e) {
      const he = e instanceof HelpError ? e : null;
      if (he && (he.status === 503 || he.status === 0 || he.status >= 500)) {
        patchMsg(botKey, (x) => ({ ...x, content: offlineAnswer(content), status: 'offline' }));
      } else {
        patchMsg(botKey, (x) => ({ ...x, content: he?.message ?? '发送失败，请稍后再试', status: 'error' }));
        if (he?.status === 400) setDraft((d) => d || content);
      }
    } finally {
      finish();
    }
  };

  const onStop = () => {
    stopStreaming();
    setMsgs((l) => l.map((m) => (m.status === 'pending' || m.status === 'streaming' ? { ...m, status: m.content ? 'stopped' : m.status } : m)));
  };

  const onChip = (chip: HelpChip) => {
    if (chip.id === 'report') {
      setMode('report');
      setNotice(null);
      inputRef.current?.focus();
      return;
    }
    void send(chip.prompt);
  };

  const onKeyDown = (e: ReactKeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      if (canSend) void send();
    }
  };

  // 输入框高度随内容变化
  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 160) + 'px';
  }, [draft, open]);

  if (!open) return null;

  const empty = msgs.length === 0 && !loadingConv;
  const placeholder = mode === 'report' ? '描述遇到的问题，可以附上截图' : '描述你的问题，或者跟我聊聊…';
  const activeChapter = docChapters.find((c) => c.id === docChapterId) ?? docChapters[0];
  const kw = docQuery.trim().toLowerCase();
  const filteredDocs = kw
    ? docChapters.filter(
        (ch) =>
          ch.id !== 'faq' &&
          (ch.title.toLowerCase().includes(kw) ||
            ch.summary.toLowerCase().includes(kw) ||
            ch.markdown.toLowerCase().includes(kw)),
      )
    : [];
  const filteredFaq = kw
    ? HELP.filter(
        (it) =>
          it.q.toLowerCase().includes(kw) ||
          it.keywords.some((k) => k.toLowerCase().includes(kw)) ||
          it.a.some((line) => line.toLowerCase().includes(kw)),
      )
    : HELP;

  const askBotFromDoc = (promptText: string) => {
    setHelpTab('chat');
    setView('chat');
    setMode('ask');
    void send(promptText);
  };

  return (
    <aside
      ref={panelRef}
      className={`xz-panel ${helpTab === 'docs' ? 'is-docs' : ''}`}
      role="dialog"
      aria-label={helpTab === 'docs' ? '织页帮助文档与资源限制' : '小织 · 使用帮助'}
      onClick={(e) => e.stopPropagation()}
    >
      <header className="xz-head">
        <div className="xz-tabs" role="tablist" aria-label="帮助模式切换">
          <button
            type="button"
            role="tab"
            aria-selected={helpTab === 'docs'}
            className={`xz-tab ${helpTab === 'docs' ? 'on' : ''}`}
            onClick={() => setHelpTab('docs')}
          >
            <Icon name="book" />
            <span>帮助文档</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={helpTab === 'chat'}
            className={`xz-tab ${helpTab === 'chat' ? 'on' : ''}`}
            onClick={() => setHelpTab('chat')}
          >
            {streaming ? <i className="xz-live" title="小织正在回答" /> : <Icon name="spark" />}
            <span>问小织</span>
          </button>
        </div>
        <div className="xz-actions">
          {helpTab === 'chat' ? (
            <>
              <button className="xz-icon" aria-label="新对话" title="新对话" onClick={startNew}>
                <Icon name="plus" />
              </button>
              <button className={`xz-icon ${view === 'history' ? 'on' : ''}`} aria-label="对话历史" title="历史" aria-pressed={view === 'history'} onClick={toggleHistory}>
                <Icon name="history" />
              </button>
            </>
          ) : null}
          <button className="xz-icon" aria-label="关闭帮助" title="关闭" onClick={() => setOpen(false)}>
            <Icon name="close" />
          </button>
        </div>
      </header>

      {helpTab === 'docs' ? (
        <div className="xz-docs">
          <div className="xz-docs-top">
            <div className="xz-docs-search">
              <Icon name="search" />
              <input
                type="text"
                value={docQuery}
                onChange={(e) => setDocQuery(e.target.value)}
                placeholder="搜索使用手册、Excel 看板、飞书、数据源、1C1G、休眠、预检…"
                aria-label="搜索帮助文档"
              />
              {docQuery ? (
                <button type="button" className="xz-icon small" aria-label="清空搜索" onClick={() => setDocQuery('')}>
                  <Icon name="close" />
                </button>
              ) : null}
            </div>
            <div className="xz-docs-pills" role="tablist" aria-label="文档章节">
              {docChapters.map((ch) => (
                <button
                  key={ch.id}
                  type="button"
                  role="tab"
                  aria-selected={!kw && docChapterId === ch.id}
                  className={`xz-doc-pill ${!kw && docChapterId === ch.id ? 'on' : ''}`}
                  onClick={() => {
                    setDocQuery('');
                    setDocChapterId(ch.id);
                  }}
                >
                  <span>{ch.shortTitle}</span>
                  {ch.badge ? <em>{ch.badge}</em> : null}
                </button>
              ))}
            </div>
          </div>

          <div className="xz-docs-body">
            {kw ? (
              <div className="xz-docs-section">
                <div className="xz-docs-banner">
                  <div>
                    <h3>搜索结果（{filteredDocs.length + filteredFaq.length} 条）</h3>
                    <p>关键词「{docQuery.trim()}」匹配到的知识库文档与常见问题</p>
                  </div>
                  <button type="button" className="xz-ask-btn" onClick={() => askBotFromDoc(docQuery.trim())}>
                    <Icon name="spark" /> 直接问小织
                  </button>
                </div>
                {filteredDocs.length === 0 && filteredFaq.length === 0 ? (
                  <p className="xz-muted">文档里没找到完全匹配的条目，点右上角「直接问小织」让我帮你解答～</p>
                ) : (
                  <div className="xz-faq-list">
                    {filteredDocs.map((ch) => (
                      <div key={ch.id} className="xz-faq-card is-open">
                        <button
                          type="button"
                          className="xz-faq-toggle"
                          onClick={() => {
                            setDocQuery('');
                            setDocChapterId(ch.id);
                          }}
                        >
                          <span className="xz-faq-q">
                            <Icon name="book" />
                            <b>{ch.title}</b>
                          </span>
                          <span className="xz-faq-arrow">→</span>
                        </button>
                        {ch.summary ? (
                          <div className="xz-faq-a">
                            <p>{ch.summary}</p>
                          </div>
                        ) : null}
                      </div>
                    ))}
                    {filteredFaq.map((it) => (
                      <div key={it.id} className="xz-faq-card is-open">
                        <div className="xz-faq-q">
                          <Icon name={it.icon} />
                          <b>{it.q}</b>
                        </div>
                        <div className="xz-faq-a">
                          {it.a.map((line, idx) => (
                            <p key={idx}>{line}</p>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ) : activeChapter.id === 'faq' ? (
              <div className="xz-docs-section">
                <div className="xz-docs-banner">
                  <div>
                    <h3>{activeChapter.title}</h3>
                    <p>{activeChapter.summary}</p>
                  </div>
                  <button type="button" className="xz-ask-btn" onClick={() => setHelpTab('chat')}>
                    <Icon name="spark" /> 问小织
                  </button>
                </div>
                <div className="xz-faq-list">
                  {HELP.map((it) => {
                    const isOpen = openFaqId === it.id;
                    return (
                      <div key={it.id} className={`xz-faq-card ${isOpen ? 'is-open' : ''}`}>
                        <button
                          type="button"
                          className="xz-faq-toggle"
                          aria-expanded={isOpen}
                          onClick={() => setOpenFaqId(isOpen ? null : it.id)}
                        >
                          <span className="xz-faq-q">
                            <Icon name={it.icon} />
                            <b>{it.q}</b>
                          </span>
                          <span className="xz-faq-arrow">{isOpen ? '−' : '+'}</span>
                        </button>
                        {isOpen ? (
                          <div className="xz-faq-a">
                            {it.a.map((line, idx) => (
                              <p key={idx}>{line}</p>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              </div>
            ) : (
              <div className="xz-docs-section">
                <div className="xz-docs-banner">
                  <div>
                    <h3>{activeChapter.title}</h3>
                    <p>{activeChapter.summary}</p>
                  </div>
                  <button type="button" className="xz-ask-btn" onClick={() => askBotFromDoc(activeChapter.askPrompt)}>
                    <Icon name="spark" /> 问小织细节
                  </button>
                </div>
                <div
                  className="xz-md xz-doc-md"
                  dangerouslySetInnerHTML={{ __html: renderMarkdown(activeChapter.markdown) }}
                />
              </div>
            )}
          </div>
        </div>
      ) : view === 'history' ? (
        <div className="xz-history">
          {history === null ? <p className="xz-muted">加载中…</p> : null}
          {historyError ? <p className="xz-muted">{historyError}</p> : null}
          {history && history.length === 0 && !historyError ? <p className="xz-muted">还没有对话记录</p> : null}
          {history?.map((c) => (
            <div key={c.id} className={`xz-hitem ${c.id === convId ? 'current' : ''}`}>
              <button className="xz-hopen" onClick={() => void openConversation(c.id, c.title)}>
                <span>{c.title}</span>
                <small>{timeLabel(c.updatedAt)}</small>
              </button>
              <button className="xz-icon small" aria-label={`删除对话 ${c.title}`} title="删除" onClick={() => void removeConversation(c)}>
                <Icon name="close" />
              </button>
            </div>
          ))}
        </div>
      ) : (
        <div className="xz-list" ref={listRef} onScroll={onScroll}>
          {loadingConv ? <p className="xz-muted">加载中…</p> : null}
          {empty ? (
            <div className="xz-hello">
              <Pet size={104} bubble="嗨～" />
              <p>
                Hi～我是<span>小织</span>，有什么可以帮你？
              </p>
              <div className="xz-chips">
                {HELP_CHIPS.map((chip) => (
                  <button key={chip.id} className={chip.id === 'report' && mode === 'report' ? 'on' : ''} onClick={() => onChip(chip)} disabled={busy}>
                    <Icon name={chip.icon} /> {chip.label}
                  </button>
                ))}
              </div>
            </div>
          ) : null}
          {msgs.map((m) =>
            m.role === 'user' ? (
              <div key={m.key} className="xz-row user">
                <div className="xz-bubble user">
                  {m.assets.length ? (
                    <div className="xz-shots">
                      {m.assets.map((a, i) => (
                        <a key={a.id ?? i} href={a.url} target="_blank" rel="noreferrer">
                          <img src={a.url} alt={a.name || '截图'} />
                        </a>
                      ))}
                    </div>
                  ) : null}
                  {m.content ? <div className="xz-text">{m.content}</div> : null}
                </div>
              </div>
            ) : (
              <div key={m.key} className={`xz-row bot ${m.status}`}>
                <PetFace />
                {/* help-msg pet：兼容 e2e/tests/ui-flows.spec.ts 的旧选择器 */}
                <div className="xz-bubble bot help-msg pet">
                  <AssistantBody msg={m} />
                </div>
              </div>
            ),
          )}
        </div>
      )}

      {helpTab === 'chat' ? (
        <form
          className={`xz-input ${mode === 'report' ? 'report' : ''}`}
          onSubmit={(e) => {
            e.preventDefault();
            if (streaming) {
              if (stopArmed) onStop();
            } else if (canSend) void send();
          }}
        >
        {mode === 'report' ? (
          <div className="xz-mode">
            <Icon name="alert" /> 问题上报
            <button type="button" aria-label="退出问题上报" onClick={() => setMode('ask')}>
              <Icon name="close" />
            </button>
          </div>
        ) : null}
        {images.length ? (
          <div className="xz-thumbs">
            {images.map((d) => (
              <span key={d.key} className={`xz-thumb ${d.error ? 'bad' : ''} ${!d.asset && !d.error ? 'loading' : ''}`} title={d.error ?? d.file?.name}>
                <img src={d.preview} alt={d.file?.name ?? '图片'} />
                <button type="button" aria-label="移除图片" onClick={() => removeImage(d.key)}>
                  <Icon name="close" />
                </button>
              </span>
            ))}
          </div>
        ) : null}
        {notice ? <p className="xz-notice">{notice}</p> : null}
        <textarea
          ref={inputRef}
          aria-label="向小织提问"
          placeholder={placeholder}
          rows={1}
          value={draft}
          maxLength={MAX_CHARS + 200}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
        />
        <div className="xz-bar">
          <button type="button" className="xz-icon" aria-label="上传图片" title="上传图片（也可以直接粘贴截图）" disabled={images.length >= MAX_IMAGES} onClick={() => fileRef.current?.click()}>
            <Icon name="image" />
          </button>
          <input
            ref={fileRef}
            type="file"
            accept="image/png,image/jpeg,image/gif,image/webp"
            multiple
            hidden
            onChange={(e) => {
              addFiles(Array.from(e.target.files ?? []));
              e.target.value = '';
            }}
          />
          {draft.length > MAX_CHARS * 0.8 ? <small className={`xz-count ${draft.length > MAX_CHARS ? 'over' : ''}`}>{draft.length}/{MAX_CHARS}</small> : null}
          {streaming ? (
            <button type="submit" className="xz-send stop" aria-label="停止回答" title="停止回答" disabled={!stopArmed}>
              <Icon name="stop" />
            </button>
          ) : (
            <button type="submit" className="xz-send" aria-label={mode === 'report' ? '提交问题' : '发送问题'} disabled={!canSend}>
              <Icon name="send" />
            </button>
          )}
        </div>
      </form>
      ) : null}
    </aside>
  );
}
