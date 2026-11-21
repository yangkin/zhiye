import { useEffect, useMemo, useRef, useState } from 'react';
import { Markdown, splitNext } from '../lib/markdown';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { errorMessage } from '../api/client';
import { api, type ConversationMessage } from '../api/conversation';
import { useActiveTask, useMeta } from '../api/queries';
import type { AppType, Asset, Mode } from '../api/types';
import { AddMenuButton, SourceChips, handleAssetPaste } from '../components/AddMenu';
import { AssetChips } from '../components/AssetChips';
import { ModelModeButton, SkillChips, SkillPicker, mentionAt, type SkillRef } from '../components/ComposerPopovers';
import { ErrorState, LoadingState } from '../components/EmptyState';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { copyMessage, msgTime } from '../workbench/ChatPanel';
import { FileCard, ThinkingBlock, plainAnswer, splitFiles } from './ChatParts';
import { ChatFilesPanel, filesKey, type OpenFile } from './ChatFiles';
import { confirmDialog } from '../components/ConfirmDialog';

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
  const [prompt, setPrompt] = useState('');
  const [type, setType] = useState<AppType>(initial?.type ?? '开发网站');
  const [model, setModel] = useState(initial?.model ?? 'auto');
  const [mode, setMode] = useState<Mode>(initial?.mode ?? '适中');
  const [attachments, setAttachments] = useState<Asset[]>(initial?.attachments ?? []);
  const [skills, setSkills] = useState<SkillRef[]>(initial?.skills ?? []);
  const [sources, setSources] = useState<SkillRef[]>(initial?.sources ?? []);
  const [asking, setAsking] = useState(false);
  const [building, setBuilding] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [actionError, setActionError] = useState('');
  const [filesOpen, setFilesOpen] = useState(false);
  const [openFile, setOpenFile] = useState<OpenFile | null>(null);
  const files = useQuery({ queryKey: filesKey(id), queryFn: () => api.conversationFiles(id), enabled: !!id });
  const fileCount = files.data?.length ?? 0;
  const [picker, setPicker] = useState<null | { via: 'button' } | { via: 'mention'; start: number; query: string }>(null);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const skillBtn = useRef<HTMLButtonElement>(null);
  const scroll = useRef<HTMLDivElement>(null);
  const askAbort = useRef<AbortController | null>(null);
  /** 本轮发送的时间：停止按钮就在发送按钮原来的位置，刚发出 1 秒内的点击多半是连点，不当成停止 */
  const sentAt = useRef(0);
  // 刚发送的 1 秒内停止按钮不可点（它就在发送按钮的位置，连点会误停）；用禁用态而不是悄悄忽略点击
  const [stopArmed, setStopArmed] = useState(false);
  const action = useRef<'ask' | 'build' | null>(null);
  const initialStarted = useRef(false);

  const models = meta?.models?.length ? meta.models : [{ id: 'auto', label: 'Auto' }];
  const modes: Mode[] = meta?.modes?.length ? meta.modes : ['快速', '适中', '深度'];
  const busy = asking || building;

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
  }, [data, initial]);

  useEffect(() => {
    initialStarted.current = false;
    action.current = null;
    askAbort.current?.abort();
    setMessages([]);
    setPrompt('');
    setActionError('');
    setAsking(false);
    setBuilding(false);
    setType(initial?.type ?? '开发网站');
    setModel(initial?.model ?? 'auto');
    setMode(initial?.mode ?? '适中');
    setAttachments(initial?.attachments ?? []);
    setSkills(initial?.skills ?? []);
    setSources(initial?.sources ?? []);
    setFilesOpen(false);
    setOpenFile(null);
  }, [id]);

  useEffect(() => () => askAbort.current?.abort(), []);

  useEffect(() => {
    const el = scroll.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: asking ? 'auto' : 'smooth' });
  }, [messages, asking]);

  const startBuild = async () => {
    if (!id || action.current) return;
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
        ...(attachments.length ? { assetIds: attachments.map((a) => a.id) } : {}),
        ...(skills.length ? { skillAppIds: skills.map((s) => s.id) } : {}),
        ...(sources.length ? { sourceIds: sources.map((s) => s.id) } : {}),
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
    const content = (override ?? prompt).trim();
    if (!id || !content || action.current) return;
    // 重试：去掉失败的那一问一答，用原来的文字、技能、附件重新发
    if (again) setMessages((list) => list.filter((m) => !again.replace.includes(m.id)));
    action.current = 'ask';
    setAsking(true);
    setActionError('');
    setPicker(null);
    const stamp = Date.now();
    sentAt.current = stamp;
    setStopArmed(false);
    window.setTimeout(() => { if (sentAt.current === stamp) setStopArmed(true); }, 1000);
    const assistantId = `local-a-${stamp}`;
    const selectedSkills = again ? again.skills : skills;
    const sentAssets = again ? again.assets : attachments;
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
        { content, type, ...(selectedSkills.length ? { skillAppIds: selectedSkills.map((s) => s.id) } : {}),
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
      // 只认模型的 [[build]]：需求不清楚时不要因为一句「开始做」就硬建一个空应用
      const wantsBuild = /^\s*\[\[build(?::[^\]]*)?\]\]/.test(answer);
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
      if (wantsBuild) {
        action.current = null;
        setAsking(false);
        await startBuild();
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
          {messages.map((message) =>
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
                  ) : (message.builtApp || stripBuildMarker(message.content) !== message.content) && !data?.appId ? (
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
          <section className="composer conversation-composer" aria-label="继续会话">
            <textarea
              ref={textarea}
              rows={2}
              maxLength={3000}
              aria-label="继续输入消息"
              placeholder={hasBrief ? '继续补充要求；够清楚时我会直接开始做…' : '说说你想做什么，先聊清楚再开始…'}
              value={prompt}
              onChange={(e) => {
                setPrompt(e.target.value);
                setActionError('');
                const mention = mentionAt(e.target.value, e.target.selectionStart ?? e.target.value.length);
                if (mention) setPicker({ via: 'mention', ...mention });
                else if (picker?.via === 'mention') setPicker(null);
              }}
              onPaste={(e) => handleAssetPaste(e, (asset) => setAttachments((list) => (list.length < 5 && !list.some((a) => a.id === asset.id) ? [...list, asset] : list)), attachments.length)}
              onKeyDown={(e) => {
                // 回车发送，Shift+Enter 换行；输入法组合中不触发
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  void send();
                }
              }}
            />
            {attachments.length || skills.length || sources.length ? (
              <div className="attachments">
                <SkillChips skills={skills} onRemove={(skillId) => setSkills((list) => list.filter((s) => s.id !== skillId))} />
                <SourceChips sources={sources} onRemove={(sid) => setSources((list) => list.filter((s) => s.id !== sid))} />
                {attachments.map((asset) => (
                  <span key={asset.id}>
                    <Icon name="file" /> {asset.name}
                    <button type="button" aria-label={`移除附件 ${asset.name}`} onClick={() => setAttachments((list) => list.filter((a) => a.id !== asset.id))}>
                      <Icon name="close" />
                    </button>
                  </span>
                ))}
              </div>
            ) : null}
            {actionError ? <p className="inline-error conversation-error" role="alert">{actionError}</p> : null}
            <div className="composer-bottom">
              <div className="composer-tools">
                <AddMenuButton
                  variant="workbench"
                  placement="top"
                  attachedCount={attachments.length}
                  onAttach={(asset) => setAttachments((list) => (list.length < 5 && !list.some((a) => a.id === asset.id) ? [...list, asset] : list))}
                  pickedSourceIds={sources.map((s) => s.id)}
                  onPickSource={(s) => setSources((list) => (list.some((x) => x.id === s.id) ? list : [...list, { id: s.id, name: s.name }]))}
                />
                <ModelModeButton models={models} modes={modes} model={model} mode={mode} onModel={setModel} onMode={setMode} />
                <button ref={skillBtn} type="button" className="skill-button" aria-label="选择技能" aria-expanded={picker !== null} onClick={() => setPicker(picker ? null : { via: 'button' })}>
                  <Icon name="book" /> 技能
                </button>
              </div>
              <div>
                {asking ? (
                  <button type="button" className="send-button chat-send is-stop" aria-label="停止回答" title="停止回答" disabled={!stopArmed} onClick={stopAsk}>
                    <Icon name="stop" />
                  </button>
                ) : (
                  <button type="button" className="send-button chat-send" aria-label="发送消息" disabled={!prompt.trim() || busy} onClick={() => void send()}>
                    <Icon name={building ? 'refresh' : 'send'} className={building ? 'spinning' : ''} />
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
