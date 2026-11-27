import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { Markdown, splitNext } from '../lib/markdown';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { errorMessage } from '../api/client';
import { api, type ConversationMessage } from '../api/conversation';
import { useActiveTask, useMeta } from '../api/queries';
import type { AppType, Asset, Mode } from '../api/types';
import { AddMenuButton, SourceChips } from '../components/AddMenu';
import { AssetChips } from '../components/AssetChips';
import { ComposerAttachmentList, ComposerDropOverlay, PasteNoticeBanner, useComposerAssets } from '../components/ComposerAssets';
import { MAX_ATTACHMENTS, MAX_PROMPT_CHARS } from '../lib/clipboardPaste';
import { ModelModeButton, SkillChips, SkillPicker, billingOf, mentionAt, useModelList, type SkillRef } from '../components/ComposerPopovers';
import { ErrorState, LoadingState } from '../components/EmptyState';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { copyMessage, msgTime } from '../workbench/ChatPanel';
import { FileCard, ThinkingBlock, plainAnswer, splitFiles } from './ChatParts';
import { ChatFilesPanel, filesKey, type OpenFile } from './ChatFiles';
import { confirmDialog } from '../components/ConfirmDialog';
import { loadDraft, saveDraft } from '../lib/drafts';

export interface ChatNavigationState {
  initialMessage: string;
  type: AppType;
  model: string;
  mode: Mode;
  attachments: Asset[];
  skills: SkillRef[];
  sources?: SkillRef[];
}

interface ChatMessage extends ConversationMessage {
  pending?: boolean;
  error?: string;
  /** 本次思考用时（秒），只有当场回答的消息有 */
  thinkSeconds?: number;
  /** 这条回复触发了创建应用（[[build]] 标记），气泡下要显示应用入口卡 */
  builtApp?: boolean;
  /** 发送失败或回答中断：保留原始内容，点「重试」原样再发一次 */
  retry?: { content: string; skills: SkillRef[]; assets: Asset[]; userId: string };
  /** 用户点了「停止回答」 */
  stopped?: boolean;
}

const stripBuildMarker = (text: string) => text.replace(/^(\s*\[\[build(?::[^\]]*)?\]\]\s*)+/, '');
const START_REQUEST = /(开始|动手|直接做|直接生成|生成吧|写吧|按你的|按默认|就按这个)/;
const STARTED_CLAIM = /(系统(?:已经|正在)(?:开始)?(?:构建|创建|生成|编写)|已经(?:开始|在)(?:构建|创建|生成|编写|写)|正在(?:生成|编写|创建|写)(?:代码|文件|后端|前端|项目)|正在落盘|开始创建项目文件|开始写代码)/;

/** 模型偶尔会漏掉 [[build]] 却声称已经开工；只在这种自相矛盾时由前端兜底触发创建。 */
function missedStart(messages: ChatMessage[], index: number): boolean {
  const answer = messages[index];
  if (answer?.role !== 'assistant' || !STARTED_CLAIM.test(answer.content)) return false;
  if (messages.slice(index + 1).some((m) => m.role === 'assistant')) return false;
  for (let i = index - 1; i >= 0; i--) {
    if (messages[i].role === 'user') return START_REQUEST.test(messages[i].content);
  }
  return false;
}
function AnswerBody({ text, openName, onPreview }: { text: string; openName?: string; onPreview: (f: OpenFile) => void }) {
  const { body } = useMemo(() => splitNext(text), [text]);
  const parts = useMemo(() => splitFiles(body), [body]);
  return (
    <>
      {parts.map((p, i) =>
        p.kind === 'file' ? (
          <FileCard key={i} name={p.name} body={p.body} done={p.done} active={openName === p.name} onPreview={() => onPreview({ name: p.name, body: p.body })} />
        ) : (
          <Markdown key={i} text={p.text} className="conversation-markdown" />
        ),
      )}
    </>
  );
}

export function ChatPage() {
  const { id = '' } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const initial = (location.state as ChatNavigationState | null)?.initialMessage
    ? (location.state as ChatNavigationState)
    : null;
  const { data: meta } = useMeta();
  const userName = meta?.auth?.user?.realName || '我';
  const active = useActiveTask(5000).data ?? null;
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['conversation', id],
    queryFn: () => api.conversation(id),
    enabled: !!id,
  });

  const [title, setTitle] = useState('新会话');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [prompt, setPrompt] = useState(() => (id ? loadDraft<string>('chat:' + id) ?? '' : ''));
  const [type, setType] = useState<AppType>(() => initial?.type ?? (id ? loadDraft<AppType>('chat-type:' + id) : null) ?? '开发网站');
  const [model, setModel] = useState(initial?.model ?? 'auto');
  const [mode, setMode] = useState<Mode>(initial?.mode ?? '适中');
  const [attachments, setAttachments] = useState<Asset[]>(initial?.attachments ?? []);
  const [skills, setSkills] = useState<SkillRef[]>(initial?.skills ?? []);
  const [sources, setSources] = useState<SkillRef[]>(initial?.sources ?? []);
  const [asking, setAsking] = useState(false);
  const [building, setBuilding] = useState(false);
  const [sourceSaving, setSourceSaving] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [actionError, setActionError] = useState('');
  const [filesOpen, setFilesOpen] = useState(false);
  const [openFile, setOpenFile] = useState<OpenFile | null>(null);
  const files = useQuery({ queryKey: filesKey(id), queryFn: () => api.conversationFiles(id), enabled: !!id });
  const fileCount = files.data?.length ?? 0;
  const [picker, setPicker] = useState<null | { via: 'button' } | { via: 'mention'; start: number; query: string }>(null);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const promptRef = useRef(prompt);
  promptRef.current = prompt;
  const attachmentsRef = useRef(attachments);
  attachmentsRef.current = attachments;
  const skillBtn = useRef<HTMLButtonElement>(null);
  const scroll = useRef<HTMLDivElement>(null);
  const askAbort = useRef<AbortController | null>(null);
  /** 本轮发送的时间：停止按钮就在发送按钮原来的位置，刚发出 1 秒内的点击多半是连点，不当成停止 */
  const sentAt = useRef(0);
  // 刚发送的 1 秒内停止按钮不可点（它就在发送按钮的位置，连点会误停）；用禁用态而不是悄悄忽略点击
  const [stopArmed, setStopArmed] = useState(false);
  const action = useRef<'ask' | 'build' | null>(null);
  const initialStarted = useRef(false);

  const { models, defaultId } = useModelList();
  const modes: Mode[] = meta?.modes?.length ? meta.modes : ['快速', '适中', '深度'];
  const busy = asking || building || sourceSaving;
  const composer = useComposerAssets({
    getAttachments: () => attachmentsRef.current,
    addAttachment: (asset) =>
      setAttachments((list) => (list.length < MAX_ATTACHMENTS && !list.some((a) => a.id === asset.id) ? [...list, asset] : list)),
    removeAttachment: (assetId) => setAttachments((list) => list.filter((a) => a.id !== assetId)),
    getText: () => promptRef.current,
    setText: (next) => {
      setPrompt(next);
      setActionError('');
    },
    textareaRef: textarea,
    maxAttachments: MAX_ATTACHMENTS,
    maxChars: MAX_PROMPT_CHARS,
    disabled: busy,
  });

  // 输入框随内容自动伸缩（粘贴多段文字时舒展可读，最多 200px 后内部滚动）
  useLayoutEffect(() => {
    const el = textarea.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(200, Math.max(54, el.scrollHeight))}px`;
  }, [prompt, id]);
  // 新会话优先选免费模型：还没用户点选、也没有历史模型时用默认（免费）模型
  const modelTouched = useRef(!!initial?.model);
  useEffect(() => {
    if (!modelTouched.current && defaultId && defaultId !== 'auto' && (!model || model === 'auto')) setModel(defaultId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defaultId, id, model]);

  useEffect(() => {
    if (!data) return;
    setTitle(data.title || '新会话');
    setMessages(data.messages.filter((m) => m.role !== 'system').map((m) => ({
      ...m,
      builtApp: m.role === 'assistant' && /\[\[build(?::[^\]]*)?\]\]/.test(m.content),
      content: m.role === 'assistant' ? stripBuildMarker(m.content) : m.content,
    })));
    if (!initial) {
      if (data.type) setType(data.type);
      if (data.model) setModel(data.model);
      if (data.mode) setMode(data.mode);
      if (data.assets) setAttachments(data.assets);
    }
    setSources(data.sources ?? []);
  }, [data, initial]);

  useEffect(() => {
    initialStarted.current = false;
    action.current = null;
    askAbort.current?.abort();
    setMessages([]);
    setPrompt(id ? loadDraft<string>('chat:' + id) ?? '' : '');
    setActionError('');
    setAsking(false);
    setBuilding(false);
    setType(initial?.type ?? (id ? loadDraft<AppType>('chat-type:' + id) : null) ?? '开发网站');
    setModel(initial?.model ?? 'auto');
    modelTouched.current = !!initial?.model;
    setMode(initial?.mode ?? '适中');
    setAttachments(initial?.attachments ?? []);
    setSkills(initial?.skills ?? []);
    setSources(initial?.sources ?? []);
    setFilesOpen(false);
    setOpenFile(null);
    composer.dismissPasteNotice();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  useEffect(() => {
    if (!id) return;
    saveDraft('chat:' + id, prompt.trim() ? prompt : null);
  }, [id, prompt]);

  useEffect(() => {
    if (!id) return;
    saveDraft('chat-type:' + id, type === '全栈应用' || initial?.type ? type : null);
  }, [id, type, initial?.type]);

  useEffect(() => () => askAbort.current?.abort(), []);

  useEffect(() => {
    const el = scroll.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: asking ? 'auto' : 'smooth' });
  }, [messages, asking]);

  const changeSources = async (next: SkillRef[]) => {
    if (sourceSaving) return;
    setSourceSaving(true);
    try {
      const result = await api.updateConversationSources(id, next.map((s) => s.id));
      const saved = result.sources;
      setSources(saved);
      qc.setQueryData(['conversation', id], (old: typeof data) => old ? { ...old, sources: saved } : old);
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setSourceSaving(false);
    }
  };

  const startBuild = async (selection?: { skills: SkillRef[] }) => {
    if (!id || action.current) return;
    const buildSkills = selection?.skills ?? skills;
    if (active) {
      setActionError(`「${active.appName}」正在生成中，完成后再开始新的生成。`);
      return;
    }
    action.current = 'build';
    setBuilding(true);
    setActionError('');
    try {
      const app = await api.buildConversation(id, {
        type,
        mode,
        model,
        ...(billingOf(models, model) ? { billingType: billingOf(models, model)! } : {}),
        ...(attachments.length ? { assetIds: attachments.map((a) => a.id) } : {}),
        ...(buildSkills.length ? { skillAppIds: buildSkills.map((s) => s.id) } : {}),
      });
      qc.setQueryData(['app', app.id], app);
      void qc.invalidateQueries({ queryKey: ['apps'] });
      void qc.invalidateQueries({ queryKey: ['history'] });
      navigate('/apps/' + app.id);
      toast('正在为你整理创造计划。');
    } catch (err) {
      setActionError(errorMessage(err));
    } finally {
      if (action.current === 'build') action.current = null;
      setBuilding(false);
    }
  };

  // 停止回答：保留已经收到的部分，输入框立刻可用；同时通知服务端停下（否则后台会继续答完并保存完整回答）
  const stopAsk = () => {
    if (Date.now() - sentAt.current < 1000) return;
    askAbort.current?.abort();
    setMessages((list) => list.map((m) => (m.pending ? { ...m, pending: false, stopped: true } : m)));
    if (action.current === 'ask') action.current = null;
    setAsking(false);
    if (id)
      api
        .stopAsk(id)
        .then((r) => {
          // 停止来晚了：服务端已经答完，重新加载显示完整回答
          if (r.finished) void refetch();
        })
        .catch(() => undefined);
  };

  const send = async (override?: string, again?: { skills: SkillRef[]; assets: Asset[]; replace: string[] }) => {
    if (!again && composer.uploading) {
      toast('附件正在上传中，请稍候片刻再发送。');
      return;
    }
    const sentAssets = again ? again.assets : attachments;
    const rawContent = (override ?? prompt).trim();
    const content = rawContent || (sentAssets.length ? '请根据我附上的参考内容帮我处理' : '');
    if (!id || !content || action.current || sourceSaving) return;
    // 重试：去掉失败的那一问一答，用原来的文字、技能、附件重新发
    if (again) setMessages((list) => list.filter((m) => !again.replace.includes(m.id)));
    action.current = 'ask';
    setAsking(true);
    setActionError('');
    setPicker(null);
    composer.dismissPasteNotice();
    const stamp = Date.now();
    sentAt.current = stamp;
    setStopArmed(false);
    window.setTimeout(() => { if (sentAt.current === stamp) setStopArmed(true); }, 1000);
    const assistantId = `local-a-${stamp}`;
    const selectedSkills = again ? again.skills : skills;
    const userId = `local-u-${stamp}`;
    setMessages((list) => [
      ...list,
      {
        id: userId,
        role: 'user',
        content,
        createdAt: new Date(stamp).toISOString(),
        ...(selectedSkills.length ? { skillAppIds: selectedSkills.map((s) => s.id) } : {}),
        ...(sentAssets.length ? { assets: sentAssets.map((a) => ({ id: a.id, name: a.name, mime: a.mime })) } : {}),
      },
      { id: assistantId, role: 'assistant', content: '', createdAt: new Date(stamp).toISOString(), pending: true },
    ]);
    if (!again) {
      setPrompt('');
      if (id) saveDraft('chat:' + id, null);
      setAttachments([]);
    }
    const ctrl = new AbortController();
    askAbort.current?.abort();
    askAbort.current = ctrl;
    let answer = '';
    let thinking = '';
    let thinkSeconds: number | undefined;
    try {
      await api.askConversation(
        id,
        { content, type, model, ...(billingOf(models, model) ? { billingType: billingOf(models, model)! } : {}),
          ...(selectedSkills.length ? { skillAppIds: selectedSkills.map((s) => s.id) } : {}),
          ...(sentAssets.length ? { assetIds: sentAssets.map((a) => a.id) } : {}) },
        (text) => {
          if (!answer && thinking && thinkSeconds === undefined) thinkSeconds = Math.max(1, Math.round((Date.now() - stamp) / 1000));
          answer += text;
          setMessages((list) => list.map((m) => (m.id === assistantId ? { ...m, content: stripBuildMarker(answer), thinkSeconds } : m)));
        },
        ctrl.signal,
        (text) => {
          thinking += text;
          setMessages((list) => list.map((m) => (m.id === assistantId ? { ...m, thinking } : m)));
        },
      );
      // 优先认模型的 [[build]]；若用户已明确要求开始且模型声称在写却没给标记，也直接创建，避免停在假进度。
      // 模型不一定严格把它放在第一行（可能前面先写一句「好，开始动手」），回答里出现就按建应用处理。
      const wantsBuild = /\[\[build(?::[^\]]*)?\]\]/.test(answer) || (START_REQUEST.test(content) && STARTED_CLAIM.test(answer));
      setMessages((list) => list.map((m) => (m.id === assistantId ? { ...m, content: stripBuildMarker(answer), pending: false, builtApp: wantsBuild } : m)));
      // 后端在首个用户消息时自动命名；这里同步一下标题，避免停留在「新会话」
      setTitle((t) => (t === '新会话' || t === '新对话' ? content.replace(/\s+/g, ' ').slice(0, 20) : t));
      void qc.invalidateQueries({ queryKey: filesKey(id) });
      // 这一轮写出了文件：和 meoo 一样在右侧直接打开最后一个
      const written = splitFiles(splitNext(stripBuildMarker(answer)).body).filter((p) => p.kind === 'file' && p.done);
      const last = written[written.length - 1];
      if (last?.kind === 'file') {
        setOpenFile({ name: last.name, body: last.body });
        setFilesOpen(true);
      }
      if (!again) setSkills([]);
      if (wantsBuild) {
        action.current = null;
        setAsking(false);
        await startBuild({ skills: selectedSkills });
      }
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        const retry = { content, skills: selectedSkills, assets: sentAssets, userId };
        setMessages((list) => list.map((m) => (m.id === assistantId ? { ...m, pending: false, error: errorMessage(err), retry } : m)));
      }
    } finally {
      if (action.current === 'ask') action.current = null;
      setAsking(false);
      if (askAbort.current === ctrl) askAbort.current = null;
      void qc.invalidateQueries({ queryKey: ['llm-quota'] });
    }
  };

  useEffect(() => {
    if (!initial || !data || initialStarted.current) return;
    initialStarted.current = true;
    if (!data.messages.length) void send(initial.initialMessage);
  }, [data, initial]);

  const remove = async () => {
    if (!id || busy || removing) return;
    if (!(await confirmDialog({ title: '删除这段会话？', message: '此操作不可撤销。', confirmLabel: '删除', danger: true }))) return;
    setRemoving(true);
    setActionError('');
    try {
      await api.deleteConversation(id);
      qc.removeQueries({ queryKey: ['conversation', id] });
      navigate('/create');
      toast('会话已删除。');
    } catch (err) {
      setActionError(errorMessage(err));
      setRemoving(false);
    }
  };

  const hasBrief = messages.some((m) => m.role === 'user' && m.content.trim());

  return (
    <div className={`chat-page ${filesOpen ? 'with-files' : ''}`}>
      <div className="chat-column">
      <header className="conversation-head">
        <Link to="/create" className="chat-back" aria-label="返回创造" title="返回创造">
          <Icon name="back" />
        </Link>
        <div className="conversation-title" title={title}>{title}</div>
        {type === '全栈应用' ? (
          <span
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              fontSize: 12,
              fontWeight: 600,
              color: '#6d28d9',
              background: '#f3edff',
              border: '1px solid #dfd2fa',
              borderRadius: 999,
              padding: '2px 10px',
              whiteSpace: 'nowrap',
            }}
            title="已锁定全栈应用模式（支持 Vue + Java Spring Boot / React + Express / Python / Go 独立托管）"
          >
            <Icon name="cloud" />
            全栈应用模式
          </span>
        ) : null}
        {fileCount ? (
          <button type="button" className={`conversation-files ${filesOpen ? 'active' : ''}`} aria-pressed={filesOpen} onClick={() => setFilesOpen((v) => !v)}>
            <Icon name="clip" />
            文件 {fileCount}
          </button>
        ) : null}
        <button
          type="button"
          className="conversation-delete"
          aria-label="删除会话"
          title="删除会话"
          disabled={busy || removing}
          onClick={() => void remove()}
        >
          <Icon name={removing ? 'refresh' : 'trash'} className={removing ? 'spinning' : ''} />
        </button>
      </header>

      <div className="conversation-scroll" ref={scroll}>
        <div className="conversation-stream" aria-live="polite">
          {messages.length ? <p className="conversation-disclaimer">内容由 AI 生成，可能不准确，请注意核实</p> : null}
          {isLoading ? <LoadingState label="正在恢复会话…" /> : null}
          {error ? <ErrorState message={errorMessage(error)} onRetry={() => refetch()} /> : null}
          {!isLoading && !error && !messages.length ? (
            <div className="chat-empty">
              <span className="chat-avatar"><img src="/brand.svg" alt="" /></span>
              <p>说说你想做什么。不用先选类型，我会边聊边判断，聊清楚了直接开始做。</p>
            </div>
          ) : null}
          {messages.map((message, index) =>
            message.role === 'user' ? (
              <article className="chat-msg user msg-row" key={message.id}>
                <div className="chat-msg-meta">
                  <span className="chat-avatar user" aria-hidden="true">{userName.slice(0, 1)}</span>
                  <b>{userName}</b>
                </div>
                <div className="chat-bubble">
                  {message.skillAppIds?.length ? <span className="chat-skill-note">使用 {message.skillAppIds.length} 个技能</span> : null}
                  <AssetChips assets={message.assets ?? []} />
                  {message.content}
                </div>
                {message.pending ? null : (
                  <div className="msg-actions">
                    {message.createdAt ? <time dateTime={message.createdAt}>{msgTime(message.createdAt)}</time> : null}
                    <button type="button" aria-label="复制消息" title="复制" onClick={() => void copyMessage(message.content)}>
                      <Icon name="copy" />
                      复制
                    </button>
                    <button
                      type="button"
                      aria-label="编辑消息"
                      title="放回输入框，改完再发送"
                      disabled={busy}
                      onClick={() => {
                        setPrompt(message.content);
                        requestAnimationFrame(() => textarea.current?.focus());
                      }}
                    >
                      <Icon name="edit" />
                      编辑
                    </button>
                  </div>
                )}
              </article>
            ) : (
              <article className="chat-msg assistant msg-row" key={message.id}>
                <span className="chat-avatar"><img src="/brand.svg" alt="" /></span>
                <div className="chat-body">
                  <div className="chat-msg-meta"><b>织页</b><span>· Auto</span></div>
                  {message.thinking ? <ThinkingBlock text={message.thinking} live={!!message.pending && !message.content} seconds={message.thinkSeconds} /> : null}
                  {message.content ? (
                    <AnswerBody
                      text={message.content}
                      openName={filesOpen ? openFile?.name : undefined}
                      onPreview={(f) => {
                        setOpenFile(f);
                        setFilesOpen(true);
                      }}
                    />
                  ) : null}
                  {message.pending && !(message.thinking && !message.content) ? (
                    <div className="tl-live-bar" role="status" aria-live="polite" style={{ marginTop: 10 }}>
                      <span className="tl-live-orb" aria-hidden="true" />
                      <span className="tl-live-label">{message.content ? '正在输出中' : '正在思考中'}</span>
                      <span className="tl-live-dots" aria-hidden="true">
                        <i />
                        <i />
                        <i />
                      </span>
                    </div>
                  ) : null}
                  {message.stopped ? <p className="conversation-status">已停止回答{message.content ? '，上面是停止前收到的内容' : ''}。</p> : null}
                  {message.error ? (
                    <div className="inline-error msg-retry" role="alert">
                      <span>{message.error}</span>
                      {message.retry ? (
                        <button
                          type="button"
                          className="text-button"
                          disabled={busy}
                          onClick={() => {
                            const r = message.retry!;
                            void send(r.content, { skills: r.skills, assets: r.assets, replace: [r.userId, message.id] });
                          }}
                        >
                          <Icon name="refresh" /> 重试
                        </button>
                      ) : null}
                    </div>
                  ) : null}
                  {message.content && !message.pending ? (
                    <div className="msg-actions assistant-actions">
                      <button type="button" aria-label="复制消息" title="复制" onClick={() => void copyMessage(plainAnswer(splitNext(stripBuildMarker(message.content)).body))}>
                        <Icon name="copy" />
                        复制
                      </button>
                      {message.createdAt ? <time dateTime={message.createdAt}>{msgTime(message.createdAt)}</time> : null}
                    </div>
                  ) : null}
                  {message.builtApp && data?.appId ? (
                    <Link to={'/apps/' + data.appId} className="conv-app-card">
                      <span className="conv-app-icon"><Icon name="grid" /></span>
                      <span className="conv-app-info">
                        <b>{data.appName ?? '已创建应用'}</b>
                        <small>{data.appPhase === 'generating' ? '正在生成，点击查看进度' : '已创建 · 点击打开工作台'}</small>
                      </span>
                      <Icon name="arrow" />
                    </Link>
                  ) : (message.builtApp || missedStart(messages, index) || stripBuildMarker(message.content) !== message.content) && !data?.appId ? (
                    <button
                      type="button"
                      className="conv-app-card"
                      disabled={building || Boolean(active)}
                      onClick={() => void startBuild()}
                    >
                      <span className="conv-app-icon"><Icon name="refresh" className={building ? 'spinning' : ''} /></span>
                      <span className="conv-app-info">
                        <b>{building ? '正在创建应用…' : '创建应用未完成 · 点击重试'}</b>
                        <small>{active ? `等待「${active.appName}」生成完成后可重试` : '直接基于已确认的会话方案重新创建应用'}</small>
                      </span>
                      <Icon name="arrow" />
                    </button>
                  ) : null}
                </div>
              </article>
            ),
          )}
        </div>
      </div>

      <div className="conversation-composer-wrap">
        <div className="conversation-composer-inner">
          {active ? (
            <div className="conversation-buildbar">
              <span>「{active.appName}」正在生成</span>
              <Link to={'/apps/' + active.appId} className="text-button">去查看</Link>
            </div>
          ) : null}
          <section className={`composer conversation-composer${composer.dragOver ? ' is-drag-over' : ''}`} aria-label="继续会话" {...composer.dropHandlers}>
            <ComposerDropOverlay visible={composer.dragOver} />
            <PasteNoticeBanner notice={composer.pasteNotice} onDismiss={composer.dismissPasteNotice} />
            <textarea
              ref={textarea}
              rows={2}
              maxLength={MAX_PROMPT_CHARS}
              aria-label="继续输入消息"
              placeholder={hasBrief ? '继续补充要求，支持粘贴 Word 内容/图片或拖入文件…' : '说说你想做什么，支持粘贴 Word 内容/图片或拖入文件…'}
              value={prompt}
              onChange={(e) => {
                setPrompt(e.target.value);
                setActionError('');
                const mention = mentionAt(e.target.value, e.target.selectionStart ?? e.target.value.length);
                if (mention) setPicker({ via: 'mention', ...mention });
                else if (picker?.via === 'mention') setPicker(null);
              }}
              onPaste={composer.handlePaste}
              onKeyDown={(e) => {
                // 回车发送，Shift+Enter 换行；输入法组合中不触发
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  void send();
                }
              }}
            />
            {sources.length ? (
              <div className="source-context" aria-label="本会话的应用数据源">
                <span className="source-context-label"><Icon name="database" /> 应用数据源 <small>创建后持续可用</small></span>
                <SourceChips sources={sources} onRemove={(sid) => void changeSources(sources.filter((s) => s.id !== sid))} />
              </div>
            ) : null}
            {attachments.length || composer.pending.length || skills.length ? (
              <div className="attachments">
                <SkillChips skills={skills} onRemove={(skillId) => setSkills((list) => list.filter((s) => s.id !== skillId))} />
                <ComposerAttachmentList
                  assets={attachments}
                  pending={composer.pending}
                  onRemove={(assetId) => setAttachments((list) => list.filter((a) => a.id !== assetId))}
                />
              </div>
            ) : null}
            {actionError ? <p className="inline-error conversation-error" role="alert">{actionError}</p> : null}
            <div className="composer-bottom">
              <div className="composer-tools">
                <AddMenuButton
                  variant="workbench"
                  placement="top"
                  attachedCount={attachments.length + composer.pending.length}
                  maxAttachments={MAX_ATTACHMENTS}
                  onUploadFiles={composer.uploadBatch}
                  pickedSourceIds={sources.map((s) => s.id)}
                  onPickSource={(s) => void changeSources(sources.some((x) => x.id === s.id) ? sources : [...sources, { id: s.id, name: s.name }])}
                />
                <ModelModeButton models={models} modes={modes} model={model} mode={mode} onModel={(m) => { modelTouched.current = true; setModel(m); }} onMode={setMode} />
                <button ref={skillBtn} type="button" className="skill-button" aria-label="选择技能" aria-expanded={picker !== null} onClick={() => setPicker(picker ? null : { via: 'button' })}>
                  <Icon name="book" /> 技能
                </button>
              </div>
              <div className="composer-actions">
                {prompt.length > 2000 ? (
                  <span className={`composer-char-count${prompt.length > MAX_PROMPT_CHARS - 800 ? ' is-near' : ''}`}>
                    {prompt.length.toLocaleString()} / {MAX_PROMPT_CHARS.toLocaleString()}
                  </span>
                ) : null}
                {asking ? (
                  <button type="button" className="send-button chat-send is-stop" aria-label="停止回答" title="停止回答" disabled={!stopArmed} onClick={stopAsk}>
                    <Icon name="stop" />
                  </button>
                ) : (
                  <button
                    type="button"
                    className="send-button chat-send"
                    aria-label="发送消息"
                    title={composer.uploading ? '附件上传中，请稍候…' : '发送消息'}
                    disabled={(!prompt.trim() && !attachments.length) || busy || composer.uploading}
                    onClick={() => void send()}
                  >
                    <Icon name={building || composer.uploading ? 'refresh' : 'send'} className={building || composer.uploading ? 'spinning' : ''} />
                  </button>
                )}
              </div>
            </div>
          </section>
        </div>
      </div>
      </div>
      {filesOpen && id ? <ChatFilesPanel conversationId={id} open={openFile} onOpen={setOpenFile} onClose={() => setFilesOpen(false)} /> : null}

      <SkillPicker
        open={picker !== null}
        anchor={picker?.via === 'mention' ? textarea : skillBtn}
        picked={skills}
        mentionQuery={picker?.via === 'mention' ? picker.query : null}
        onClose={() => setPicker(null)}
        onPick={(skill) => {
          if (picker?.via === 'mention') {
            const end = picker.start + 1 + picker.query.length;
            setPrompt((text) => text.slice(0, picker.start) + text.slice(end));
            setTimeout(() => {
              textarea.current?.focus();
              textarea.current?.setSelectionRange(picker.start, picker.start);
            }, 0);
          }
          setSkills((list) => [...list, skill]);
          setPicker(null);
        }}
      />
    </div>
  );
}
