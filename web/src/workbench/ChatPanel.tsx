import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api, errorMessage, newIdempotencyKey } from '../api/client';
import { api as convApi } from '../api/conversation';
import { qk, useMessages, useMeta } from '../api/queries';
import type { TaskStreamState } from '../api/sse';
import type { AppDetail, ElementContext, Message, QueuedMessage, Task, Version } from '../api/types';
import { Icon } from '../lib/icons';
import { copyText, fmtDate, fmtTime, shortDay } from '../lib/format';
import { toast, useChat } from '../lib/store';
import { AddMenuButton, SourceChips, handleAssetPaste } from '../components/AddMenu';
import { AssetChips, type ChatAsset } from '../components/AssetChips';
import { MAX_SKILLS, ModelModeButton, SkillChips, SkillPicker, mentionAt, useMySkills } from '../components/ComposerPopovers';
import { styleFor } from '../components/Thumbnail';
import { isQuestion, quickRoute } from '../pages/CreatePage';
import { PlanCard } from './PlanCard';
import { TaskCard } from './TaskCard';
import { Timeline } from './Timeline';
import { Markdown, NextSteps, splitNext } from '../lib/markdown';

import { TodoPanel } from './TodoPanel';
import { CLOUD_CONFIRM_ITEMS, ConfirmCard, ConfirmView } from './ConfirmCard';

interface Props {
  app: AppDetail;
  stream: TaskStreamState;
  currentVersion: Version | undefined;
  versions: Version[];
  onTaskStarted: (t: Task) => void;
  onEnableCloud?: () => void;
  onRestoreVersion: (version: Version) => void;
  /** 切到「云服务」页 */
  onOpenCloud?: () => void;
  onPublish: () => void;
  onFocusPreview: () => void;
  skill?: boolean;
  /** 本应用进行中的写任务 id（发送按钮变成停止） */
  runningTaskId?: string | null;
  /** 其他应用有进行中的任务 */
  otherBusy?: { id: string; name: string } | null;
  /** 发送前的检查（代码未保存 → 让用户选择；随后等待手动修改草稿写入）。返回 false 取消发送 */
  beforeSend?: () => Promise<boolean>;
  /** 当前未保存的手动修改数量（发送时会先保存） */
  pendingManual?: number;
  /** 右侧预览是否可见（聊天全屏时不可见，「打开预览」可以点它展开） */
  previewVisible?: boolean;
}

/** 可能是「另做一个应用」的说法；命中后由模型判断（加页面、加模块仍算修改） */
const NEW_APP_HINT = /(再|另外|另|重新|新)(帮我)?(做|写|建|开发|搭|搞)(一个|一套|个)|(做|写|开发|搭建?|建)(一个|一套|个)[^，。,.]{0,12}(网站|系统|应用|平台|小程序|app|APP|工具)/;

/** 启用云服务后自动发给 AI 的那句话 */
const CLOUD_DESIGN_PROMPT = '云服务已启用。请按这个应用现在的功能设计表结构和数据权限，把数据改成云端读写。';

/** 任务有「继续 / 中途补充（steer）」消息时，把该任务的助手回复与版本卡放到最后一条补充消息之后 */
export function orderMessagesWithSteers(list: Message[]): Message[] {
  const lastSteerIdx = new Map<string, number>();
  list.forEach((m, i) => {
    if (m.role === 'user' && m.steer && m.taskId) lastSteerIdx.set(m.taskId, i);
  });
  if (!lastSteerIdx.size) return list;
  const moveAfter = new Map<number, Message[]>();
  const skip = new Set<number>();
  list.forEach((m, i) => {
    if (m.role !== 'assistant' || !m.taskId) return;
    const target = lastSteerIdx.get(m.taskId);
    if (target !== undefined && target > i) {
      skip.add(i);
      const bucket = moveAfter.get(target) ?? [];
      bucket.push(m);
      moveAfter.set(target, bucket);
    }
  });
  if (!skip.size) return list;
  const out: Message[] = [];
  list.forEach((m, i) => {
    if (!skip.has(i)) out.push(m);
    const trailing = moveAfter.get(i);
    if (trailing) out.push(...trailing);
  });
  return out;
}

export function ChatPanel({ app, stream, currentVersion, versions, onTaskStarted, onEnableCloud, onOpenCloud, onRestoreVersion, onPublish, onFocusPreview, skill = false, runningTaskId = null, otherBusy = null, beforeSend, pendingManual = 0, previewVisible = true }: Props) {
  const qc = useQueryClient();
  const { data: messages } = useMessages(app.id);
  // 从对话创建的应用：把那段对话显示在应用消息之前，对话和应用在同一个工作台里
  const appConv = useQuery({
    queryKey: ['app-conversation', app.id],
    queryFn: () => api.appConversation(app.id),
    staleTime: 60_000,
  });
  const convMessages = appConv.data?.messages ?? [];
  const meta = useMeta().data;
  const draft = useChat((s) => s.draft);
  const setDraft = useChat((s) => s.setDraft);
  const ctx = useChat((s) => s.elementContext);
  const setCtx = useChat((s) => s.setElementContext);
  const focusTick = useChat((s) => s.focusTick);
  const [sending, setSending] = useState(false);
  const [stopping, setStopping] = useState(false);
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const input = useRef<HTMLTextAreaElement>(null);
  const scroll = useRef<HTMLDivElement>(null);
  // 悬浮在输入框上方的确认卡 / 待办 / 待发送消息：按实际高度给消息列表留白，滚到底时最后一条不被挡住
  const floatRef = useRef<HTMLDivElement>(null);
  const [floatH, setFloatH] = useState(0);
  useEffect(() => {
    const el = floatRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => {
      const box = scroll.current;
      const atBottom = !!box && box.scrollHeight - box.scrollTop - box.clientHeight < 48;
      setFloatH(el.offsetHeight);
      if (atBottom) requestAnimationFrame(() => box?.scrollTo({ top: box.scrollHeight }));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const keyRef = useRef<string | null>(null);
  const [queueOpen, setQueueOpen] = useState(true);
  const [removingQueued, setRemovingQueued] = useState<string | null>(null);
  const [planUpdatedTick, setPlanUpdatedTick] = useState(0);
  const [planInView, setPlanInView] = useState(true);
  const planCardRef = useRef<HTMLDivElement | null>(null);
  const askAbortRef = useRef<AbortController | null>(null);
  const scrollToPlan = () => {
    planCardRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  useEffect(() => {
    if (app.phase !== 'plan' || skill) {
      setPlanInView(true);
      return;
    }
    const target = planCardRef.current;
    const root = scroll.current;
    if (!target || !root || typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver(
      (entries) => setPlanInView(entries[0]?.isIntersecting ?? true),
      { root, threshold: 0.15 },
    );
    io.observe(target);
    return () => io.disconnect();
  }, [app.id, app.phase, skill]);
  const elementContext = ctx && ctx.appId === app.id ? ctx : null;
  const queuedQuery = useQuery({
    queryKey: ['queued', app.id],
    queryFn: () => api.listQueued(app.id),
    enabled: !!runningTaskId,
  });
  const cloudQuery = useQuery({
    queryKey: ['cloud', app.id],
    queryFn: () => api.cloudStatus(app.id),
    enabled: app.phase === 'ready' && !skill,
  });
  const queued = queuedQuery.data ?? [];
  const cloudEnabled = skill ? null : (cloudQuery.data?.enabled ?? null);
  // 云服务已经排队等着启用（任务进行中确认的）：按钮显示「排队中」，不再弹确认卡
  const cloudQueued = !cloudEnabled && queued.some((q) => q.enableCloud);

  useEffect(() => {
    askAbortRef.current?.abort();
    askAbortRef.current = null;
    setPlanUpdatedTick(0);
    setError('');
  }, [app.id]);

  // 任务切换或结束时同步待发送队列（避免上一条刚出队变成新任务时仍显示旧缓存）
  useEffect(() => {
    if (runningTaskId) void qc.invalidateQueries({ queryKey: ['queued', app.id] });
    else qc.setQueryData(['queued', app.id], []);
  }, [app.id, runningTaskId, qc]);

  useEffect(() => {
    if (!focusTick) return;
    const el = input.current;
    if (el) {
      el.focus();
      el.setSelectionRange(el.value.length, el.value.length);
      el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  }, [focusTick]);

  const count = messages?.length ?? 0;
  useEffect(() => {
    scroll.current?.scrollTo({ top: scroll.current.scrollHeight, behavior: 'smooth' });
  }, [count, app.phase]);

  const chatAssets = useChat((s) => s.assets);
  const chatHint = useChat((s) => s.hint);
  const [picker, setPicker] = useState<null | { via: 'button' } | { via: 'mention'; start: number; query: string }>(null);
  // 直接回答的问题 / 计划阶段讨论（不创建任务）：流式显示的那一条
  const [asking, setAsking] = useState<null | { q: string; a: string; thinking: string; planUpdating: boolean }>(null);
  useEffect(() => {
    if (!asking) return;
    const el = scroll.current;
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
  }, [asking?.q, asking?.a, asking?.planUpdating]);
  const skillBtn = useRef<HTMLButtonElement>(null);
  const draftNotes = useChat((s) => s.draftNotes);
  const mySkills = useMySkills((messages ?? []).some((m) => m.skillAppIds?.length));
  const skillNames = new Map((mySkills.data ?? []).map((k) => [k.id, k.name]));
  const chatSkills = useChat((s) => s.skills);
  // 数据源绑定在应用上：选中即绑定，之后的修改、预览和发布站点都能调用
  const appSources = useQuery({ queryKey: qk.appSources(app.id), queryFn: () => api.appSources(app.id), enabled: !skill });
  const boundSources = appSources.data ?? [];
  const changeSource = async (op: 'bind' | 'unbind', id: string, name: string) => {
    try {
      const next = op === 'bind' ? await api.bindSource(app.id, id) : await api.unbindSource(app.id, id);
      qc.setQueryData(qk.appSources(app.id), next);
      toast(op === 'bind' ? `已绑定数据源「${name}」，下一次修改时 AI 就能使用它。` : `已解绑数据源「${name}」。`);
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };
  // 和 meoo 一样，技能只对这一条消息生效：发出去后从输入框清掉，消息上会标出用了哪些技能
  const saveSkills = (list: { id: string; name: string }[]) => useChat.getState().setSkills(list);
  const cloudAsk = useChat((s) => s.cloudAsk);
  const [cloudBusy, setCloudBusy] = useState(false);
  // 点「共享云服务」弹出的确认卡：确认就启用，再让 AI 设计表结构（不往输入框里填字）。
  // 有任务在跑时不马上启用：启用和这条消息一起排队，轮到它时才启用，撤回消息就不启用——否则撤回后云服务已开、表却没人设计
  const answerCloud = async (accept: boolean) => {
    if (!accept) {
      useChat.getState().setCloudAsk(false);
      return;
    }
    setCloudBusy(true);
    try {
      if (runningTaskId) {
        const item = await api.enqueueQueued(runningTaskId, CLOUD_DESIGN_PROMPT, { enableCloud: true });
        qc.setQueryData<QueuedMessage[]>(['queued', app.id], (old) => [...(old ?? []), item]);
        void qc.invalidateQueries({ queryKey: ['queued', app.id] });
        setQueueOpen(true);
        useChat.getState().setCloudAsk(false);
        toast('当前任务完成后启用云服务，并让 AI 设计表结构。在「待发送消息」里撤回就不会启用。');
        return;
      }
      await api.cloudEnable(app.id);
      await qc.invalidateQueries({ queryKey: ['cloud', app.id] });
      void qc.invalidateQueries({ queryKey: ['apps'] });
      useChat.getState().setCloudAsk(false);
      toast('云服务已启用，AI 正在设计表结构。');
      // 这句一定是修改：不走意图判断（模型判断要多等十几秒，判成「提问」还会只回答不改代码）
      void send(CLOUD_DESIGN_PROMPT, true);
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setCloudBusy(false);
    }
  };

  // 输入框上方的悬浮层出现时，最后一条消息滚到它上面，不被挡住
  const floating = `${stream.todos.length > 0 && !!runningTaskId}|${stream.confirm?.state}|${cloudAsk}|${queued.length}`;
  useEffect(() => {
    const el = scroll.current;
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 320) requestAnimationFrame(() => el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' }));
  }, [floating]);

  // 一个对话只对应一个应用：说的是另一个无关的应用时，先问要不要新开（对照 meoo 的限制，但给出口）
  const [newApp, setNewApp] = useState<string | null>(null);
  const [newAppBusy, setNewAppBusy] = useState(false);
  const answerNewApp = async (accept: boolean) => {
    const content = newApp;
    if (!content) return;
    if (!accept) {
      setNewApp(null);
      void send(content, true);
      return;
    }
    setNewAppBusy(true);
    try {
      const conv = await convApi.createConversation();
      setNewApp(null);
      setDraft('');
      navigate('/chat/' + conv.id, { state: { initialMessage: content, model: app.model, mode: app.mode, attachments: [], skills: [] } });
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setNewAppBusy(false);
    }
  };

  /** forceModify：用户已经确认就在当前应用里做，不再判断意图 */
  const send = async (override?: string, forceModify = false) => {
    const content = (override ?? draft).trim();
    if (!content || sending) return;
    // 正在等「继续」或「方案确认」：输入框里说的话直接作为回复发给当前任务，不进队列
    const waitingPromptConfirm =
      runningTaskId && stream.taskId === runningTaskId && stream.confirm?.state === 'pending' && (stream.confirm.kind === 'continue' || stream.confirm.kind === 'choice')
        ? stream.confirm
        : null;
    if (runningTaskId && waitingPromptConfirm) {
      setSending(true);
      setError('');
      try {
        await api.confirmTask(runningTaskId, waitingPromptConfirm.id, true, content);
        setDraft('');
        toast(waitingPromptConfirm.kind === 'choice' ? '已回复，AI 正在按你的决定继续。' : '好的，AI 接着做。');
      } catch (err) {
        setError(errorMessage(err));
      } finally {
        setSending(false);
      }
      return;
    }
    // 运行中的补充要求进入队列，由后端在当前任务结束后创建后续修改任务。
    if (runningTaskId) {
      const ctxNow = useChat.getState().elementContext;
      const hasElement = Boolean(ctxNow && ctxNow.appId === app.id);
      if (chatAssets.length > 0 || chatSkills.length > 0 || hasElement) {
        setError('排队消息暂不支持同时携带附件、技能或选中页面元素；请等当前任务完成后直接发送，或先移除附件/元素后再排队。');
        return;
      }
      setSending(true);
      setError('');
      try {
        const item = await api.enqueueQueued(runningTaskId, content);
        qc.setQueryData<QueuedMessage[]>(['queued', app.id], (old) => [...(old ?? []), item]);
        void qc.invalidateQueries({ queryKey: ['queued', app.id] });
        setDraft('');
        setQueueOpen(true);
        toast('已加入队列');
      } catch (err) {
        setError(errorMessage(err));
      } finally {
        setSending(false);
      }
      return;
    }
    if (app.phase === 'plan') {
      setSending(true);
      setError('');
      setDraft('');
      setAsking({ q: content, a: '', thinking: '', planUpdating: false });
      const history = (messages ?? [])
        .filter((m, i) => !(i === 0 && m.role === 'user' && m.content.trim() === app.prompt.trim()))
        .filter((m) => (m.role === 'user' || m.role === 'assistant') && m.content.trim() && m.status !== 'failed' && m.status !== 'cancelled')
        .slice(-24)
        .map((m) => ({ role: m.role, content: m.content.slice(0, 4000) }));
      const ctrl = new AbortController();
      askAbortRef.current = ctrl;
      try {
        await api.planChat(
          app.id,
          {
            content,
            ...(history.length ? { history } : {}),
            ...(chatSkills.length ? { skillAppIds: chatSkills.map((k) => k.id) } : {}),
            ...(chatAssets.length ? { assetIds: chatAssets.map((a) => a.id) } : {}),
          },
          {
            onThinking: (t) => setAsking((r) => (r ? { ...r, thinking: r.thinking + t } : r)),
            onDelta: (t) => setAsking((r) => (r ? { ...r, a: r.a + t } : r)),
            onPlanUpdating: () => setAsking((r) => (r ? { ...r, planUpdating: true } : r)),
            onPlanUpdated: (nextApp) => {
              qc.setQueryData(qk.app(app.id), nextApp);
              setPlanUpdatedTick((v) => v + 1);
              toast('已同步更新上方「创造计划」', 'ok', { label: '查看计划', run: scrollToPlan });
            },
          },
          ctrl.signal,
        );
        useChat.getState().setSkills([]);
        useChat.getState().setAssets([]);
        await qc.invalidateQueries({ queryKey: qk.messages(app.id) });
      } catch (err) {
        if ((err as { name?: string })?.name === 'AbortError') {
          await qc.invalidateQueries({ queryKey: qk.messages(app.id) });
        } else {
          setDraft(content);
          setError(errorMessage(err));
        }
      } finally {
        if (askAbortRef.current === ctrl) askAbortRef.current = null;
        setAsking(null);
        setSending(false);
      }
      return;
    }
    // 只是一句问题：直接回答，不改代码、不建任务（工作台里也可以聊天）
    const ctxForRoute = useChat.getState().elementContext;
    const hasElement = !!ctxForRoute && ctxForRoute.appId === app.id;
    let route: ReturnType<typeof quickRoute> | 'new' = forceModify ? 'modify' : quickRoute(content, hasElement);
    // 「再做一个 / 写一个 xx 网站」这类，快速规则会当成修改；交给模型判断是不是另一个应用
    if (route === 'modify' && !forceModify && !hasElement && skill === false && NEW_APP_HINT.test(content)) route = 'model';
    if (route === 'model') {
      setSending(true);
      route = await api
        .intent(content, hasElement, skill ? undefined : { name: app.name, prompt: app.prompt })
        .then((r) => r.intent)
        .catch(() => (isQuestion(content) ? 'ask' : 'modify'));
      setSending(false);
    }
    if (route === 'new') {
      setNewApp(content);
      return;
    }
    if (route === 'ask') {
      setSending(true);
      setError('');
      setDraft('');
      setAsking({ q: content, a: '', thinking: '', planUpdating: false });
      // 带上本应用已有的对话，追问才有上下文：只留 user/assistant、非空、非失败，最近 24 条交服务端合并归一化为 12 轮
      // 首条用户消息是应用最初需求（界面上已单独展示），不再重复喂给模型
      const history = (messages ?? [])
        .filter((m, i) => !(i === 0 && m.role === 'user' && m.content.trim() === app.prompt.trim()))
        .filter((m) => (m.role === 'user' || m.role === 'assistant') && m.content.trim() && m.status !== 'failed')
        .slice(-24)
        .map((m) => ({ role: m.role, content: m.content.slice(0, m.content.includes('[[file:') ? 40000 : 2000) }));
      let handoffReq: { summary?: string; instruction: string } | null = null;
      try {
        await api.ask(
          {
            content,
            appId: app.id,
            ...(history.length ? { history } : {}),
            ...(chatSkills.length ? { skillAppIds: chatSkills.map((k) => k.id) } : {}),
            ...(chatAssets.length ? { assetIds: chatAssets.map((a) => a.id) } : {}),
          },
          (t) => setAsking((r) => (r ? { ...r, a: r.a + t } : r)),
          undefined,
          (t) => setAsking((r) => (r ? { ...r, thinking: r.thinking + t } : r)),
          (h) => { handoffReq = h; },
        );
        useChat.getState().setSkills([]);
        useChat.getState().setAssets([]);
        await qc.invalidateQueries({ queryKey: qk.messages(app.id) });
      } catch (err) {
        setDraft(content);
        setError(errorMessage(err));
      } finally {
        setAsking(null);
        setSending(false);
      }
      if (handoffReq && !otherBusy) {
        const picked = handoffReq as { summary?: string; instruction: string };
        toast(picked.summary || '已定位根因，自动转入代码修改。');
        try {
          if (!beforeSend || (await beforeSend())) {
            const res = await api.sendMessage(app.id, { content: picked.instruction });
            qc.setQueryData<Message[]>(qk.messages(app.id), (old) => [...(old ?? []), res.message]);
            onTaskStarted(res.task);
          }
        } catch (err) {
          setError(errorMessage(err));
        }
      }
      return;
    }
    if (otherBusy) return;
    setSending(true);
    setError('');
    if (beforeSend && !(await beforeSend())) {
      setSending(false);
      return;
    }
    const n = pendingManual;
    // 代码保存后版本变了：元素上下文可能已过期，由 Workbench 清掉
    const ctxNow = useChat.getState().elementContext;
    const elementContext = ctxNow && ctxNow.appId === app.id ? ctxNow : null;
    keyRef.current ??= newIdempotencyKey();
    try {
      const res = await api.sendMessage(
        app.id,
        {
          content,
          ...(elementContext ? { elementContext } : {}),
          ...(chatAssets.length ? { assetIds: chatAssets.map((a) => a.id) } : {}),
          ...(chatSkills.length ? { skillAppIds: chatSkills.map((k) => k.id) } : {}),
        },
        keyRef.current,
      );
      keyRef.current = null;
      setDraft('');
      setCtx(null);
      useChat.getState().setAssets([]);
      useChat.getState().setSkills([]);
      qc.setQueryData<Message[]>(qk.messages(app.id), (old) => [...(old ?? []), res.message]);
      if (n > 0) useChat.getState().addDraftNote([res.message.id, res.task.id], n);
      onTaskStarted(res.task);
    } catch (err) {
      // 失败时保留输入与元素上下文
      keyRef.current = null;
      setError(errorMessage(err));
    } finally {
      setSending(false);
    }
  };

  const removeQueued = async (id: string) => {
    if (removingQueued) return;
    setRemovingQueued(id);
    try {
      await api.cancelQueued(id);
      qc.setQueryData<QueuedMessage[]>(['queued', app.id], (old) => (old ?? []).filter((item) => item.id !== id));
      void qc.invalidateQueries({ queryKey: ['queued', app.id] });
      toast('已移除待发送消息');
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setRemovingQueued(null);
    }
  };

  // 工作台里改模型 / 强度：只影响之后的修改任务
  const patchModel = async (patch: { model?: string; mode?: '快速' | '适中' | '深度' }) => {
    try {
      const next = await api.patchApp(app.id, patch);
      qc.setQueryData(qk.app(app.id), next);
      toast(`已切换为 ${patch.mode ?? (patch.model === 'auto' ? 'Auto' : patch.model)}`);
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };

  // 服务端消息里若已包含最初需求，就不再重复展示；同时把含 steer 的任务助手回复挂到任务结束处
  const list = orderMessagesWithSteers(
    (messages ?? []).filter((m, i) => !(i === 0 && m.role === 'user' && m.content.trim() === app.prompt.trim())),
  );
  const phase = app.phase;
  const task = stream.task;
  const lastAssistant = list.reduce((acc, m, i) => (m.role === 'assistant' ? i : m.role === 'user' ? -1 : acc), -1);
  // 成功结束的修改任务：进度收成「已处理 N 个任务 · 耗时」一行，挂在它的回复上方（回复自带回退/查看改动/复制/时间），不再在底部重复一张卡片
  const streamSucceeded = (stream.status ?? task?.status) === 'SUCCEEDED';
  const mergedTaskId =
    streamSucceeded && stream.taskId && list.some((m) => m.role === 'assistant' && m.taskId === stream.taskId) ? stream.taskId : null;
  const showWriteTask =
    phase === 'ready' && !!stream.taskId && !!(task || stream.status) && (task?.type ?? 'modify') !== 'plan' && task?.type !== 'generate' && !mergedTaskId;
  const assistantText =
    phase === 'planning'
      ? '收到你的想法，正在为你整理一份实现计划。'
      : phase === 'plan'
        ? skill
          ? '技能包会直接开始生成，不需要确认计划。'
          : '好想法！我为你整理了一份实现计划。确认后，我们就可以开始了。'
        : phase === 'generating'
          ? skill
            ? '正在编写技能包：SKILL.md、参考资料和脚本会逐个出现在下面。'
            : '正在把你的想法，变成看得见的作品。'
          : phase === 'failed'
            ? '这次没能完成生成，已保留当前进度，可以重试。'
            : phase === 'stopped'
              ? '生成已停止，进度已保存，可以点「继续」接着做。'
            : app.source === 'import'
              ? '代码已经导入并部署，可以继续打磨。'
              : '你的想法，已经有了新的模样。';

  return (
    <section className="chat-panel">
      <div className="chat-scroll" ref={scroll} style={floatH ? { paddingBottom: floatH + 20 } : undefined}>
        <div className="chat-day">
          {shortDay(app.createdAt)} · {fmtTime(app.createdAt)}
        </div>
        {convMessages.length ? (
          convMessages.map((m) =>
            m.role === 'user' ? (
              <UserBubble key={m.id} content={m.content} createdAt={m.createdAt} canEdit={false} />
            ) : (
              <div className="assistant-message msg-row" key={m.id}>
                <span className="assistant-avatar">
                  <img src="/brand.svg" alt="" />
                </span>
                <div className="assistant-content">
                  <b className="assistant-head">
                    织页 <small>{fmtTime(m.createdAt)}</small>
                  </b>
                  <Markdown text={splitNext(m.content).body} />
                  <div className="msg-actions assistant-actions">
                    <button type="button" aria-label="复制消息" title="复制" onClick={() => void copyMessage(splitNext(m.content).body)}>
                      <Icon name="copy" />
                      复制
                    </button>
                    <time dateTime={m.createdAt}>{msgTime(m.createdAt)}</time>
                  </div>
                </div>
              </div>
            ),
          )
        ) : (
        <UserBubble
          content={app.prompt || (app.source === 'import' ? `导入「${app.name}」代码并部署。` : '')}
          createdAt={app.createdAt}
          canEdit={phase === 'ready'}
          files={
            app.assets?.length ? (
              <p className="message-files">
                {app.assets.map((f) => (
                  <span key={f.id}>
                    <Icon name="file" />
                    {f.name}
                  </span>
                ))}
              </p>
            ) : null
          }
        />
        )}
        <div className="assistant-message">
          <span className="assistant-avatar">
            <img src="/brand.svg" alt="" />
          </span>
          <div className="assistant-content">
            {convMessages.length ? null : (
              <b>
                织页 <small>与你一起创造</small>
              </b>
            )}
            {convMessages.length ? null : <p>{assistantText}</p>}
            {phase === 'planning' || phase === 'generating' || phase === 'failed' || phase === 'stopped' ? (
              stream.taskId ? (
                <TaskCard stream={stream} type={phase === 'planning' ? 'plan' : undefined} onNewTask={onTaskStarted} />
              ) : phase === 'failed' || phase === 'stopped' ? (
                <FailedNoTask app={app} onTaskStarted={onTaskStarted} />
              ) : (
                <p className="task-waiting">正在读取任务进度…</p>
              )
            ) : phase === 'plan' && skill ? (
              <SkillStart app={app} onStarted={onTaskStarted} />
            ) : phase === 'plan' ? (
              <div ref={planCardRef}>
                <PlanCard app={app} onStarted={onTaskStarted} updatedTick={planUpdatedTick} />
              </div>
            ) : (
              <>
                {/* 刚结束的任务保留一行文件改动汇总（可展开回看每一步） */}
                {(stream.timeline.length > 0 || stream.tools.length > 0) && <Timeline stream={stream} finished summaryOnly />}
                <CompletionCard
                  app={app}
                  version={currentVersion}
                  onPublish={onPublish}
                  onFocusPreview={onFocusPreview}
                  onEnableCloud={onEnableCloud}
                  cloudEnabled={cloudEnabled}
                  previewVisible={previewVisible}
                  skill={skill}
                />
              </>
            )}
          </div>
        </div>
        {list.map((m, idx) => {
          const version = m.versionId
            ? (versions.find((v) => v.id === m.versionId) ?? (currentVersion?.id === m.versionId ? currentVersion : undefined))
            : undefined;
          const versionNumber = version?.number ?? (m.versionId && app.currentVersion?.id === m.versionId ? app.currentVersion?.number : undefined);
          return (
            <MessageItem
              key={m.id}
              m={m}
              canEdit={phase === 'ready' || phase === 'plan'}
              draftNote={draftNotes[m.id]}
              skillNames={skillNames}
              version={version}
              versionNumber={versionNumber}
              currentVersionId={currentVersion?.id ?? app.currentVersion?.id ?? null}
              busy={!!runningTaskId || !!otherBusy}
              cloudEnabled={phase === 'ready' ? cloudEnabled : null}
              onEnableCloud={phase === 'ready' ? onEnableCloud : undefined}
              onRestoreVersion={onRestoreVersion}
              steps={mergedTaskId && m.role === 'assistant' && m.taskId === mergedTaskId && (stream.timeline.length > 0 || stream.tools.length > 0) ? <Timeline stream={stream} finished summaryOnly /> : null}
              next={idx === lastAssistant && !asking && !runningTaskId && (phase === 'ready' || phase === 'plan') ? (s) => void send(s) : undefined}
            />
          );
        })}
        {asking ? (
          <>
            <UserBubble content={asking.q} createdAt={new Date().toISOString()} canEdit={false} />
            <div className="assistant-message msg-row" aria-live="polite">
              <span className="assistant-avatar">
                <img src="/brand.svg" alt="" />
              </span>
              <div className="assistant-content">
                <b className="assistant-head">
                  织页
                </b>
                {!asking.a && !asking.planUpdating ? (
                  <p className="asking-thinking" role="status">
                    <span className="thinking-dots" aria-hidden="true"><i /><i /><i /></span>
                    <span>{asking.thinking ? '正在整理思路…' : '正在思考…'}</span>
                  </p>
                ) : null}
                {asking.a ? <Markdown className="asking-a" text={splitNext(asking.a).body} /> : null}
                {asking.planUpdating ? (
                  <p className="plan-updating-pill" role="status">
                    <Icon name="refresh" className="spinning" />
                    正在根据你的意见修订上方「创造计划」…
                  </p>
                ) : null}
              </div>
            </div>
          </>
        ) : null}
        {showWriteTask ? (
          <div className="assistant-message">
            <span className="assistant-avatar">
              <img src="/brand.svg" alt="" />
            </span>
            <div className="assistant-content">
              <TaskCard stream={stream} onNewTask={onTaskStarted} compact savedDraftFirst={stream.taskId ? draftNotes[stream.taskId] : undefined} />
            </div>
          </div>
        ) : null}
      </div>
      <div className="chat-composer">
        {/* 确认卡、待办、待发送消息悬浮在输入框上方，不把输入框撑高 */}
        <div className="composer-float" ref={floatRef}>
        {runningTaskId && stream.taskId === runningTaskId && stream.confirm?.state === 'pending' ? (
          <ConfirmCard taskId={runningTaskId} appId={app.id} confirm={stream.confirm} />
        ) : newApp ? (
          <ConfirmView
            title="要新开一个应用吗？"
            reason={`这段对话只对应「${app.name}」，这句话看起来是另一个应用，放进来会和当前应用混在一起。`}
            busy={newAppBusy ? 'accept' : null}
            acceptLabel="新开一个应用"
            declineLabel="就在当前应用里做"
            onAnswer={(accept) => void answerNewApp(accept)}
          />
        ) : cloudAsk && cloudEnabled === false ? (
          <ConfirmView
            title="是否要启用织页云服务？"
            reason="确认后立即启用，AI 会按这个应用现在的功能设计数据表，并把数据改成云端保存。"
            items={CLOUD_CONFIRM_ITEMS}
            busy={cloudBusy ? 'accept' : null}
            onAnswer={(accept) => void answerCloud(accept)}
          />
        ) : null}
        <TodoPanel todos={stream.todos} running={!!runningTaskId} />
        {runningTaskId && queued.length ? (
          <section className={`queued-card${queueOpen ? ' open' : ''}`} aria-label="待发送消息">
            <button type="button" className="queued-head" aria-expanded={queueOpen} onClick={() => setQueueOpen((open) => !open)}>
              <Icon name="history" />
              <span>待发送消息 {queued.length}</span>
              <small>当前任务完成后自动执行</small>
              <Icon name="down" className="queued-caret" />
            </button>
            {queueOpen ? (
              <ul className="queued-list">
                {queued.map((item) => (
                  <li key={item.id} className="queued-item">
                    {item.enableCloud ? (
                      <span className="queued-tag" title="轮到这条消息时先启用云服务；撤回就不启用">
                        <Icon name="cloud" />
                        启用云服务
                      </span>
                    ) : null}
                    <span className="queued-summary" title={item.content}>{item.content}</span>
                    <button
                      type="button"
                      className="queued-remove"
                      aria-label="删除待发送消息"
                      title="从队列删除"
                      disabled={removingQueued !== null}
                      onClick={() => void removeQueued(item.id)}
                    >
                      <Icon name={removingQueued === item.id ? 'refresh' : 'close'} className={removingQueued === item.id ? 'spinning' : ''} />
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </section>
        ) : null}
        </div>
        {phase === 'ready' || (phase === 'generating' && !!runningTaskId) ? (
          <>
            {elementContext ? (
              <div className="element-context-chip">
                <Icon name="edit" />
                <span title={elementContext.loc}>
                  {elementContext.tag} · {elementContext.text?.slice(0, 30) || '选中元素'}
                </span>
                <button aria-label="取消元素上下文" onClick={() => setCtx(null)}>
                  <Icon name="close" />
                </button>
              </div>
            ) : null}
            {boundSources.length ? (
              <div className="source-context" aria-label="当前应用的数据源">
                <span className="source-context-label"><Icon name="database" /> 应用数据源 <small>预览和发布持续可用</small></span>
                <SourceChips sources={boundSources} onRemove={(id) => void changeSource('unbind', id, boundSources.find((s) => s.id === id)?.name ?? '')} />
              </div>
            ) : null}
            {chatAssets.length || chatSkills.length ? (
              <div className="attachments chat-attachments">
                <SkillChips skills={chatSkills} onRemove={(id) => saveSkills(chatSkills.filter((x) => x.id !== id))} />
                {chatAssets.map((a) => (
                  <span key={a.id}>
                    <Icon name="file" />
                    {a.name}
                    <button aria-label="移除附件" onClick={() => useChat.getState().setAssets(chatAssets.filter((x) => x.id !== a.id))}>
                      <Icon name="close" />
                    </button>
                  </span>
                ))}
              </div>
            ) : null}
            <textarea
              ref={input}
              id="iteration-prompt"
              rows={2}
              placeholder={
                runningTaskId && stream.confirm?.state === 'pending' && stream.confirm.kind === 'continue'
                  ? '说「继续」接着做，也可以顺便补充要求…'
                  : runningTaskId
                    ? '补充要求，当前任务完成后自动执行…'
                    : '告诉织页你还想调整什么，或 @ 使用技能'
              }
              aria-label="修改需求"
              value={draft}
              onChange={(e) => {
                setDraft(e.target.value);
                if (error) setError('');
                if (chatHint) useChat.getState().setHint('');
                const m = mentionAt(e.target.value, e.target.selectionStart ?? e.target.value.length);
                if (m) setPicker({ via: 'mention', ...m });
                else if (picker?.via === 'mention') setPicker(null);
              }}
              onPaste={(e) => handleAssetPaste(e, (asset) => {
                const cur = useChat.getState().assets;
                if (cur.length < 5 && !cur.some((x) => x.id === asset.id)) useChat.getState().setAssets([...cur, asset]);
              }, chatAssets.length)}
              onKeyDown={(e) => {
                // 与创造页、会话页一致：回车发送，Shift+Enter 换行；输入法组合中、@ 技能弹层打开时不触发
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && !picker) {
                  e.preventDefault();
                  void send();
                }
              }}
            />
            {error ? (
              <p className="composer-error" role="alert">
                {error}
              </p>
            ) : null}
            {chatHint ? (
              <p className="ze-busy-hint" role="status">
                {chatHint}
              </p>
            ) : null}
            {pendingManual > 0 && !runningTaskId ? <p className="draft-note">发送时会先保存你的 {pendingManual} 处手动修改</p> : null}
            {otherBusy ? (
              <p className="ze-busy-hint" role="status">
                「{otherBusy.name}」正在进行修改，完成后再发送
                <button type="button" className="limit-link" onClick={() => navigate('/apps/' + otherBusy.id)}>
                  去查看
                </button>
              </p>
            ) : null}
            <div className="composer-tools">
              <AddMenuButton
                variant="workbench"
                buttonClassName="icon-button"
                placement="top"
                attachedCount={chatAssets.length}
                onAttach={(asset) => {
                  const cur = useChat.getState().assets;
                  if (cur.length < 5 && !cur.some((a) => a.id === asset.id)) useChat.getState().setAssets([...cur, asset]);
                }}
                pickedSourceIds={boundSources.map((s) => s.id)}
                onPickSource={skill ? undefined : (s) => void changeSource('bind', s.id, s.name)}
              />
              <ModelModeButton
                models={meta?.models ?? []}
                modes={meta?.modes ?? []}
                model={app.model ?? 'auto'}
                mode={app.mode}
                className="model-button model-button-inline"
                onModel={(m) => void patchModel({ model: m })}
                onMode={(m) => void patchModel({ mode: m })}
              />
              <button
                ref={skillBtn}
                type="button"
                className="skill-button"
                aria-label="选择技能"
                title="技能"
                aria-expanded={picker !== null}
                onClick={() => setPicker(picker ? null : { via: 'button' })}
              >
                <Icon name="book" />
                <span className="chip-label">技能</span>
              </button>
              <SkillPicker
                open={picker !== null}
                anchor={picker?.via === 'mention' ? input : skillBtn}
                picked={chatSkills}
                mentionQuery={picker?.via === 'mention' ? picker.query : null}
                onClose={() => setPicker(null)}
                onPick={(sk) => {
                  const chat = useChat.getState();
                  if (picker?.via === 'mention') {
                    const end = picker.start + 1 + picker.query.length;
                    chat.setDraft(chat.draft.slice(0, picker.start) + chat.draft.slice(end));
                    const at = picker.start;
                    setTimeout(() => {
                      input.current?.focus();
                      input.current?.setSelectionRange(at, at);
                    }, 0);
                  }
                  saveSkills([...chat.skills, sk]);
                  setPicker(null);
                }}
              />
              {skill || app.projectKind === 'service' ? null : (
                <button
                  type="button"
                  className={`skill-button cloud-chip${cloudEnabled ? ' on' : cloudQueued ? ' queued' : ''}`}
                  aria-label={cloudEnabled ? '查看云服务' : cloudQueued ? '云服务排队启用中' : '共享云服务'}
                  title={
                    cloudEnabled
                      ? '这个应用已启用云服务，点击查看数据表'
                      : cloudQueued
                        ? '当前任务完成后启用；在「待发送消息」里撤回就不会启用'
                        : '让 AI 设计表结构并启用云服务（会先请你确认）'
                  }
                  onClick={() => (cloudEnabled ? onOpenCloud?.() : cloudQueued ? setQueueOpen(true) : onEnableCloud?.())}
                >
                  <Icon name="cloud" />
                  <span className="chip-label">{cloudEnabled ? '云服务已启用' : cloudQueued ? '云服务排队中' : '共享云服务'}</span>
                </button>
              )}
              {runningTaskId && draft.trim() ? (
                <button className="send-button" aria-label="加入待发送队列" title="当前任务完成后自动执行" disabled={sending} onClick={() => void send()}>
                  <Icon name={sending ? 'refresh' : 'send'} className={sending ? 'spinning' : ''} />
                </button>
              ) : null}
              {runningTaskId ? (
                <button
                  className="send-button stop-button"
                  aria-label="停止当前任务"
                  title="停止当前任务（输入的内容会保留）"
                  disabled={stopping}
                  onClick={async () => {
                    setStopping(true);
                    try {
                      await api.cancelTask(runningTaskId);
                    } catch (err) {
                      toast(errorMessage(err), 'error');
                    } finally {
                      setStopping(false);
                    }
                  }}
                >
                  <i className="stop-glyph" aria-hidden="true" />
                </button>
              ) : otherBusy ? (
                <button className="send-button" aria-label="提交修改需求" disabled title={`「${otherBusy.name}」正在进行修改，完成后再发送`}>
                  <Icon name="send" />
                </button>
              ) : (
                <button className="send-button" aria-label="提交修改需求" disabled={!draft.trim() || sending} onClick={() => void send()}>
                  <Icon name={sending ? 'refresh' : 'send'} className={sending ? 'spinning' : ''} />
                </button>
              )}
            </div>
          </>
        ) : phase === 'plan' ? (
          <>
            <textarea
              ref={input}
              id="plan-feedback"
              rows={2}
              placeholder="继续聊聊，或告诉织页怎么调整计划…"
              aria-label="讨论或优化计划"
              value={draft}
              onChange={(e) => {
                setDraft(e.target.value);
                if (error) setError('');
              }}
              onKeyDown={(e) => {
                // 与创造页、会话页一致：回车发送，Shift+Enter 换行；输入法组合中、@ 技能弹层打开时不触发
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && !picker) {
                  e.preventDefault();
                  void send();
                }
              }}
            />
            {error ? <p className="composer-error" role="alert">{error}</p> : null}
            <div className="composer-bottom">
              <div className="composer-tools">
                <ModelModeButton
                  models={meta?.models ?? []}
                  modes={meta?.modes ?? []}
                  model={app.model ?? 'auto'}
                  mode={app.mode}
                  className="model-button model-button-inline"
                  onModel={(m) => void patchModel({ model: m })}
                  onMode={(m) => void patchModel({ mode: m })}
                />
                {!skill && !planInView ? (
                  <button type="button" className="plan-jump-btn" onClick={scrollToPlan}>
                    查看计划 ↑
                  </button>
                ) : null}
              </div>
              {sending ? (
                <button
                  type="button"
                  className="send-button stop-button"
                  aria-label="停止回复"
                  title="停止当前回复"
                  onClick={async () => {
                    askAbortRef.current?.abort();
                    await api.stopPlanChat(app.id).catch(() => undefined);
                    await qc.invalidateQueries({ queryKey: qk.messages(app.id) });
                  }}
                >
                  <i className="stop-glyph" aria-hidden="true" />
                </button>
              ) : (
                <button className="send-button" aria-label="发送消息" title="发送" disabled={!draft.trim()} onClick={() => void send()}>
                  <Icon name="send" />
                </button>
              )}
            </div>
          </>
        ) : phase === 'failed' ? (
          <span>
            <Icon name="alert" /> 可以在上方重试生成，或修改计划后再试。
          </span>
        ) : phase === 'stopped' ? (
          <span>
            <Icon name="stop" /> 已停止，可以在上方点「继续」接着做。
          </span>
        ) : (
          <span>
            <Icon name="spark" /> {stream.phaseLabel || '任务进行中，完成后可以继续修改。'}
          </span>
        )}
      </div>
    </section>
  );
}

export function msgTime(iso: string) {
  const d = new Date(iso);
  const now = new Date();
  const y = new Date();
  y.setDate(now.getDate() - 1);
  const day = d.toDateString() === now.toDateString() ? '今天' : d.toDateString() === y.toDateString() ? '昨天' : fmtDate(iso);
  return `${day} ${fmtTime(iso)}`;
}

export async function copyMessage(text: string) {
  const ok = await copyText(text);
  toast(ok ? '已复制' : '复制没有成功，请手动选择文字复制。', ok ? 'ok' : 'error');
}

/** 用户消息：名字 + 气泡 + 悬停操作（时间 / 复制 / 编辑）。编辑只把内容放回输入框，不改历史。 */
function UserBubble({
  content,
  elementContext,
  createdAt,
  failed,
  steer,
  files,
  canEdit,
  skills = [],
  assets = [],
}: {
  content: string;
  elementContext?: ElementContext | null;
  createdAt: string;
  failed?: boolean;
  steer?: boolean;
  files?: ReactNode;
  canEdit: boolean;
  skills?: { id: string; name: string }[];
  assets?: ChatAsset[];
}) {
  const user = useMeta().data?.auth?.user ?? null;
  const name = user?.realName || '我';
  return (
    <div className="user-message msg-row" tabIndex={-1}>
      <span className="mini-avatar" aria-hidden="true">
        {name.slice(0, 1)}
      </span>
      <section className="msg-col">
        <span className="msg-name">{name}</span>
        <div className="msg-bubble">
          {steer ? <span className="steer-tag bubble-tag">补充要求</span> : null}
          {elementContext ? (
            <span className="message-element">
              <Icon name="edit" />
              {elementContext.tag} · {elementContext.text?.slice(0, 30) || '选中元素'}
            </span>
          ) : null}
          {skills.length ? (
            <span className="msg-skills">
              {skills.map((k) => (
                <span key={k.id} className="skill-chip">
                  <Icon name="book" />
                  技能 · {k.name}
                </span>
              ))}
            </span>
          ) : null}
          <AssetChips assets={assets} />
          {content}
          {files}
          {failed ? <small className="message-failed">未完成</small> : null}
        </div>
        <div className="msg-actions">
          <time dateTime={createdAt}>{msgTime(createdAt)}</time>
          <button type="button" aria-label="复制消息" title="复制" onClick={() => void copyMessage(content)}>
            <Icon name="copy" />
            复制
          </button>
          {canEdit ? (
            <button
              type="button"
              aria-label="编辑消息"
              title="编辑后重新发送"
              onClick={() => {
                const chat = useChat.getState();
                chat.setDraft(content);
                chat.setElementContext(elementContext && elementContext.appId === chat.appId ? elementContext : null);
                if (skills.length) chat.setSkills(skills.filter((k) => k.name !== '已删除的技能').slice(0, MAX_SKILLS));
                chat.requestFocus();
              }}
            >
              <Icon name="edit" />
              编辑
            </button>
          ) : null}
        </div>
      </section>
    </div>
  );
}

function MessageItem({
  m,
  canEdit,
  draftNote,
  skillNames,
  currentVersionId,
  busy,
  version,
  versionNumber,
  cloudEnabled,
  onEnableCloud,
  onRestoreVersion,
  next,
  steps,
}: {
  m: Message;
  /** 刚结束的任务的折叠进度行，显示在回复正文前 */
  steps?: ReactNode;
  /** 只有最后一条回答显示「下一步」；点一下当作下一句话发出 */
  next?: (s: string) => void;
  canEdit: boolean;
  draftNote?: number;
  skillNames: Map<string, string>;
  currentVersionId?: string | null;
  busy?: boolean;
  /** 这条回答产出的版本对象（有才显示与版本相关的操作） */
  version?: Version;
  /** 这条回答产出的版本号（有才显示 Vn 徽标） */
  versionNumber?: number;
  cloudEnabled?: boolean | null;
  onEnableCloud?: () => void;
  onRestoreVersion?: (version: Version) => void;
}) {
  const { body, next: nextItems } = splitNext(m.content);
  if (m.role === 'system' && m.content.startsWith('织页的回复被用户取消'))
    return (
      <p className="stop-note">
        <Icon name="stop" />
        <span>{m.content}</span>
      </p>
    );
  if (m.role === 'system')
    return (
      <div className="chat-day system-message">
        {m.content}
      </div>
    );
  if (m.role === 'user')
    return (
      <>
        <UserBubble
          content={m.content}
          elementContext={m.elementContext}
          createdAt={m.createdAt}
          failed={m.status === 'failed'}
          steer={m.steer}
          canEdit={canEdit}
          skills={(m.skillAppIds ?? []).map((sid) => ({ id: sid, name: skillNames.get(sid) ?? '已删除的技能' }))}
          assets={m.assets ?? []}
        />
        {draftNote ? <p className="draft-note msg-draft-note">将先保存你的 {draftNote} 处手动修改，再进行 AI 修改</p> : null}
      </>
    );
  return (
    <div className="assistant-message msg-row">
      <span className="assistant-avatar">
        <img src="/brand.svg" alt="" />
      </span>
      <div className="assistant-content">
        <b className="assistant-head">
          织页 <small>{fmtTime(m.createdAt)}</small>
          {versionNumber ? (
            <span className="tiny-badge current" title={version?.title ? `V${versionNumber} · ${version.title}` : `V${versionNumber}`}>
              V{versionNumber}
            </span>
          ) : null}
        </b>
        {steps}
        {/* 与流式气泡一致：落库后的回答也按 markdown 渲染（renderMarkdown 内部已做清洗） */}
        {m.content ? (
          <Markdown className={m.status === 'failed' ? 'message-failed-text' : ''} text={body} />
        ) : (
          <p className={m.status === 'failed' ? 'message-failed-text' : ''}>
            {m.status === 'pending' ? '正在处理…' : ''}
          </p>
        )}
        {next ? <NextSteps items={nextItems} onPick={next} /> : null}
        {m.content ? (
          <AssistantActions
            content={body}
            versionId={m.versionId}
            currentVersionId={currentVersionId}
            busy={busy}
            createdAt={m.createdAt}
            messageId={m.id}
            cloudEnabled={cloudEnabled}
            onEnableCloud={onEnableCloud}
            onRestoreVersion={version && onRestoreVersion ? () => onRestoreVersion(version) : undefined}
          />
        ) : null}
      </div>
    </div>
  );
}

/**
 * 助手回复的悬停操作：回到此版本 / 查看改动 / 复制 / 反馈 / 时间。
 * 「回到此版本」在已是当前版本时有任务进行中时不可用（一次只能有一个修改）。
 */
function AssistantActions({
  content,
  versionId,
  currentVersionId,
  busy,
  createdAt,
  messageId,
  cloudEnabled,
  onEnableCloud,
  onRestoreVersion,
}: {
  content: string;
  versionId?: string | null;
  currentVersionId?: string | null;
  busy?: boolean;
  createdAt: string;
  messageId: string;
  cloudEnabled?: boolean | null;
  onEnableCloud?: () => void;
  /** 由父组件弹出确认框；没有时保持原行为（直接恢复） */
  onRestoreVersion?: () => void;
}) {
  const qc = useQueryClient();
  const [restoring, setRestoring] = useState(false);
  const [vote, setVote] = useState<null | 'up' | 'down'>(() => {
    const v = localStorage.getItem('zhiye:vote:' + messageId);
    return v === 'up' || v === 'down' ? v : null;
  });
  const isCurrent = !!versionId && versionId === currentVersionId;

  const restore = async () => {
    if (!versionId || restoring || busy) return;
    if (onRestoreVersion) { onRestoreVersion(); return; }
    setRestoring(true);
    try {
      const v = await api.restoreVersion(versionId);
      await qc.invalidateQueries();
      toast(`已回到 V${v.number}`);
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setRestoring(false);
    }
  };

  const openChanges = () => {
    if (versionId) window.dispatchEvent(new CustomEvent('zhiye:open-changes', { detail: { versionId } }));
  };

  const setV = (v: 'up' | 'down') => {
    const next = vote === v ? null : v;
    setVote(next);
    if (next) localStorage.setItem('zhiye:vote:' + messageId, next);
    else localStorage.removeItem('zhiye:vote:' + messageId);
  };

  return (
    <div className="msg-actions assistant-actions">
      {versionId ? (
        <button
          type="button"
          aria-label="回到此版本"
          title={isCurrent ? '已是当前最新预览版本' : busy ? '有修改正在进行，完成后再回到这个版本' : '以这个版本的内容新建一个版本'}
          disabled={isCurrent || restoring || busy}
          onClick={() => void restore()}
        >
          <Icon name={restoring ? 'refresh' : 'history'} className={restoring ? 'spinning' : ''} />
          {restoring ? '回退中' : '回到此版本'}
        </button>
      ) : null}
      {versionId ? (
        <button type="button" aria-label="查看改动" title="看看这次改了什么" onClick={openChanges}>
          <Icon name="diff" />
          查看改动
        </button>
      ) : null}
      {!versionId && !cloudEnabled && onEnableCloud ? (
        <button type="button" aria-label="启用云服务" title="让这个应用真正存数据" onClick={onEnableCloud}>
          <Icon name="cloud" />
          云服务
        </button>
      ) : null}
      <button type="button" aria-label="复制消息" title="复制" onClick={() => void copyMessage(content)}>
        <Icon name="copy" />
        复制
      </button>
      <button type="button" className={vote === 'up' ? 'voted' : ''} aria-label="有帮助" aria-pressed={vote === 'up'} title="有帮助" onClick={() => setV('up')}>
        <Icon name="like" />
      </button>
      <button type="button" className={vote === 'down' ? 'voted' : ''} aria-label="没帮助" aria-pressed={vote === 'down'} title="没帮助" onClick={() => setV('down')}>
        <Icon name="dislike" />
      </button>
      <time dateTime={createdAt}>{msgTime(createdAt)}</time>
    </div>
  );
}

function FailedNoTask({ app, onTaskStarted }: { app: AppDetail; onTaskStarted: (t: Task) => void }) {
  const [busy, setBusy] = useState(false);
  return (
    <div className={`progress-card task-card ${app.phase === 'stopped' ? '' : 'is-failed'}`}>
      <div className="progress-title">
        <span>
          <Icon name={app.phase === 'stopped' ? 'stop' : 'alert'} />
        </span>
        <b>{app.phase === 'stopped' ? '生成已停止' : '生成没有完成'}</b>
      </div>
      <div className="task-actions">
        <button
          className="subtle-button"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            try {
              onTaskStarted(await api.generate(app.id));
            } catch (err) {
              toast(errorMessage(err), 'error');
            } finally {
              setBusy(false);
            }
          }}
        >
          <Icon name="refresh" />
          {busy ? '正在开始…' : app.phase === 'stopped' ? '重新生成' : '重试'}
        </button>
      </div>
    </div>
  );
}

function CompletionCard({
  app,
  version: v,
  onPublish,
  onFocusPreview,
  onEnableCloud,
  cloudEnabled = false,
  previewVisible = true,
  skill = false,
}: {
  app: AppDetail;
  version: Version | undefined;
  onPublish: () => void;
  onFocusPreview: () => void;
  onEnableCloud?: () => void;
  cloudEnabled?: boolean | null;
  previewVisible?: boolean;
  skill?: boolean;
}) {
  const qc = useQueryClient();
  const ref = app.currentVersion;
  const isOnline = !!ref && app.publishedVersionId === ref.id;
  const imported = app.source === 'import';
  const toggleFav = async () => {
    if (!v) return;
    try {
      await api.patchVersion(v.id, { favorite: !v.favorite });
      void qc.invalidateQueries({ queryKey: qk.versions(app.id) });
      void qc.invalidateQueries({ queryKey: ['history'] });
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };
  return (
    <div className="completion-content">
      <div className="completed-line">
        <Icon name="check" />
        {imported ? '代码导入与部署' : skill ? '已完成技能包' : '已完成页面与交互'}
      </div>
      <div className="next-steps">
        <button type="button" className="text-button" onClick={() => useChat.getState().requestFocus()}>
          继续修改
        </button>
        {!skill && !cloudEnabled && onEnableCloud ? (
          <button type="button" className="text-button" onClick={onEnableCloud}>
            启用云服务
          </button>
        ) : null}
      </div>
      <div className="completed-points">
        {imported ? (
          <>
            <p>
              <Icon name="check" />
              {app.framework ?? '项目'} 源码已保存
            </p>
            <p>
              <Icon name="check" />
              构建与预览部署已完成
            </p>
          </>
        ) : (
          <>
            <p>
              <Icon name="check" />
              {skill ? 'SKILL.md、参考资料与脚本已生成' : `页面布局与 ${app.type} 适配`}
            </p>
            <p>
              <Icon name="check" />
              {skill ? '格式、引用与脚本检查已通过' : '构建与检查已通过'}
            </p>
          </>
        )}
        <p>
          <Icon name="check" />
          {skill ? '可在「代码」中查看，下载后即可使用' : '预览已就绪，可以开始体验'}
        </p>
      </div>
      <div className="version-card">
        <div className="version-card-top">
          <div className={`version-art ${styleFor(app.id, app.style)}`}>
            <Icon name={skill ? 'skill' : 'web'} />
          </div>
          <div>
            <strong>
              V{ref?.number ?? 0} <span>{isOnline ? '线上版本' : '最新版本'}</span>
            </strong>
            <p>{ref?.title || '应用已生成'}</p>
          </div>
          <button
            className={`icon-button bookmark ${v?.favorite ? 'is-starred' : ''}`}
            aria-label={v?.favorite ? '取消收藏当前版本' : '收藏当前版本'}
            disabled={!v}
            onClick={() => void toggleFav()}
          >
            <Icon name="book" />
          </button>
        </div>
        <div className="version-card-actions">
          <button
            className="subtle-button"
            onClick={onFocusPreview}
            disabled={!skill && previewVisible}
            title={!skill && previewVisible ? '当前已在预览' : undefined}
          >
            <Icon name={skill ? 'code' : 'eye'} />
            {skill ? '查看文件' : previewVisible ? '已打开预览' : '打开预览'}
          </button>
          {skill ? (
            <button className="dark-button" aria-label="发布技能" onClick={onPublish}>
              <Icon name="cloud" />
              发布技能
            </button>
          ) : (
            <button className="dark-button" onClick={onPublish}>
              <Icon name="cloud" />
              {isOnline ? '已发布' : '发布应用'}
            </button>
          )}
        </div>
      </div>
      <small className="completion-time">{fmtTime(app.updatedAt)} · 已保存到你的应用</small>
    </div>
  );
}

/** 旧的技能包应用停在 plan 阶段时：不显示计划卡，只给「开始生成」。 */
function SkillStart({ app, onStarted }: { app: AppDetail; onStarted: (t: Task) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return (
    <div className="progress-card task-card">
      <div className="progress-title">
        <span>
          <Icon name="spark" />
        </span>
        <b>准备生成技能包</b>
      </div>
      {error ? (
        <p className="task-error" role="alert">
          {error}
        </p>
      ) : null}
      <div className="task-actions">
        <button
          className="primary-button small"
          disabled={busy}
          onClick={async () => {
            setBusy(true);
            setError('');
            try {
              onStarted(await api.generate(app.id));
            } catch (err) {
              setError(errorMessage(err));
            } finally {
              setBusy(false);
            }
          }}
        >
          {busy ? '正在启动…' : '开始生成'}
        </button>
      </div>
    </div>
  );
}
