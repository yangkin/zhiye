import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk, useActiveTask, useApp, useInvalidateApp, useVersions } from '../api/queries';
import { useTaskStream, waitForTask } from '../api/sse';
import { isScriptApp, isSkillApp, isTerminal, type Message, type Task, type Version } from '../api/types';
import { Icon, kindIcon } from '../lib/icons';
import { downloadUrl } from '../lib/format';
import { toast, useChat } from '../lib/store';
import { EmptyState, ErrorState, LoadingState } from '../components/EmptyState';
import { Modal } from '../components/Modal';
import { Select } from '../components/Select';
import { RenameModal } from '../components/RenameModal';
import { RestoreModal, VersionSheet } from '../components/Versions';
import { ChatEditBar, ElementTagPill, ManualBottomBar, SavedCard, type BusyInfo } from '../editor/ModeOverlays';
import { CreatingStage } from './CreatingStage';
import { AppRail } from '../components/LightLayout';
import { HelpPanel } from '../components/HelpPanel';
import { ManualPanel } from '../editor/ManualPanel';
import { submitCodeEdits, useCode } from '../lib/codeStore';
import { useVisualEditor } from '../editor/useVisualEditor';
import { ChatPanel } from './ChatPanel';
import { CloudPane, FilesPane } from './Panes';
import { PublishModal } from './PublishModal';
import { SkillhubModal } from './SkillhubModal';
import { Pet } from '../components/Pet';
import { ErrorBoundary, retryableLazy } from '../components/ErrorBoundary';
import { useMediaQuery } from '../hooks/useMediaQuery';

const CodePane = retryableLazy(() => import('./CodePane').then((m) => ({ default: m.CodePane })));

type Tab = 'code' | 'preview' | 'files' | 'cloud';

/** 预览地址能不能打开：同源时看状态码；跨域时只能确认网络可达 */
async function probePreview(src: string): Promise<boolean> {
  try {
    const url = new URL(src, window.location.href);
    const sameOrigin = url.origin === window.location.origin;
    const res = await fetch(url, { cache: 'no-store', credentials: 'include', ...(sameOrigin ? {} : { mode: 'no-cors' as const }) });
    return !sameOrigin || res.ok;
  } catch {
    return false;
  }
}
type ModalKind = null | 'skillhub' | 'rename' | 'versions' | 'publish' | 'download' | 'more' | 'routes';

const finishToast: Record<string, string> = {
  plan: '创造计划已就绪，确认后就可以开始。',
  generate: '应用已生成，好想法正在发生。',
  modify: '修改已完成，并保存为新版本。',
  visual_save: '编辑已保存为新版本。',
  import: '导入部署完成。',
  code_save: '代码修改已保存为新版本。',
};

export function WorkbenchRoute() {
  const { id } = useParams();
  return <Workbench key={id} id={id!} />;
}

function Workbench({ id }: { id: string }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const invalidate = useInvalidateApp();
  const appQ = useApp(id);
  const app = appQ.data;
  const versionsQ = useVersions(id, !!app?.currentVersion);
  const versions = useMemo(() => versionsQ.data ?? [], [versionsQ.data]);

  const skill = app ? isSkillApp(app) : false;
  const script = app ? isScriptApp(app) : false;
  const mainTab: Tab = skill || script ? 'code' : 'preview';
  const [tab, setTab] = useState<Tab>(mainTab);
  // 技能包与脚本默认打开「代码」；没有云服务
  useEffect(() => {
    if (skill || script) setTab('code');
  }, [skill, script, app?.id]);
  useEffect(() => {
    if ((skill || script) && tab === 'cloud') setTab('code');
  }, [skill, script, tab]);
  // 时间线 / 消息里的「在代码中打开」「查看改动」：切到代码页
  useEffect(() => {
    const openCode = () => setTab('code');
    const openChanges = (e: Event) => {
      const vid = (e as CustomEvent<{ versionId?: string | null }>).detail?.versionId;
      setTab('code');
      useCode.getState().openChanges(vid ?? null);
    };
    window.addEventListener('zhiye:open-code', openCode);
    window.addEventListener('zhiye:open-changes', openChanges as EventListener);
    return () => {
      window.removeEventListener('zhiye:open-code', openCode);
      window.removeEventListener('zhiye:open-changes', openChanges as EventListener);
    };
  }, []);
  const [maximized, setMaximized] = useState(false);
  // 右侧面板可以整块收起，把宽度让给对话（对照 meoo 的预览收缩）
  const [previewCollapsed, setPreviewCollapsed] = useState(false);
  const [editMode, setEditMode] = useState<null | 'chat' | 'manual'>(null);
  const [previewVersion, setPreviewVersion] = useState<Version | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  /** 预览身份：作者（管理员）/ 普通员工 / 未登录，用来试数据权限 */
  const [previewAs, setPreviewAs] = useState<'admin' | 'member' | 'anon'>('admin');
  const [modal, setModal] = useState<ModalKind>(null);
  const [restoreTarget, setRestoreTarget] = useState<Version | null>(null);
  const [trackedId, setTrackedId] = useState<string | null>(null);
  // 手动微调「应用」产生的 visual_save 任务：成功弹出「修改已保存」，失败把 runner 的说明显示在底部条
  const [visualTaskId, setVisualTaskId] = useState<string | null>(null);
  const visualTaskRef = useRef<{ id: string; resolve: (t: Task) => void } | null>(null);
  const [savedCard, setSavedCard] = useState(false);
  const activeQ = useActiveTask(5000);
  const frameRef = useRef<HTMLIFrameElement>(null);

  // 深色工作台 + 切换应用时清掉上一个应用的元素上下文
  useEffect(() => {
    document.body.classList.add('studio-mode');
    useChat.getState().resetFor(id);
    useCode.getState().resetFor(id);
    const restored = useCode.getState().restored;
    if (restored) {
      useCode.setState({ restored: 0 });
      toast(`上次有 ${restored} 个文件没保存，已为你保留，可以在「代码」里继续保存或重置。`);
    }
    return () => document.body.classList.remove('studio-mode');
  }, [id]);

  // 任务跟踪：优先进行中的任务；否则展示最近一次失败/停止的任务（用于「继续」「重试」）
  const activeId = app?.activeTask?.id ?? null;
  useEffect(() => {
    if (activeId) setTrackedId(activeId);
  }, [activeId]);
  const tasksQ = useQuery({
    queryKey: qk.appTasks(id),
    queryFn: () => api.appTasks(id),
    enabled: !!app && !activeId && trackedId === null,
    staleTime: 30_000,
  });
  useEffect(() => {
    if (trackedId || activeId || !tasksQ.data?.length || !app) return;
    const latest = [...tasksQ.data].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
    const newerThanVersion = !app.currentVersion || latest.createdAt > app.currentVersion.createdAt;
    if ((latest.status === 'FAILED' || latest.status === 'CANCELLED') && newerThanVersion) setTrackedId(latest.id);
  }, [tasksQ.data, trackedId, activeId, app]);
  const taskId = activeId ?? trackedId;

  const cloudQ = useQuery({ queryKey: ['cloud', id], queryFn: () => api.cloudStatus(id!), enabled: !!id && !!app && !isSkillApp(app) });
  const cloudOn = cloudQ.data?.enabled ?? false;
  const baseFrameSrc = previewVersion ? previewVersion.previewUrl : (app?.previewUrl ?? null);
  const frameSrc = baseFrameSrc && cloudOn && previewAs !== 'admin' ? `${baseFrameSrc}?__as=${previewAs}` : baseFrameSrc;
  const otherActiveEarly = activeQ.data && app && activeQ.data.appId !== app.id ? activeQ.data : null;
  const lockedEarly = (!!app?.activeTask && !isTerminal(app.activeTask.status)) || !!otherActiveEarly;
  // 手机上对话和工作区上下叠放时，点页签要直接切过去，不能让内容藏在长对话下面
  const narrow = useMediaQuery('(max-width: 760px)');
  const [mobileView, setMobileView] = useState<'chat' | 'panel'>('chat');
  const openTab = (value: Tab) => {
    setTab(value);
    if (narrow) {
      setMobileView('panel');
      window.scrollTo({ top: 0 });
    }
  };
  // 页签的键盘操作：左右方向键切到相邻页签，Home / End 到首尾（焦点和选中一起走）
  const onTabKey = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(e.key)) return;
    const tabs = [...e.currentTarget.querySelectorAll<HTMLButtonElement>('[role="tab"]')];
    const i = tabs.indexOf(document.activeElement as HTMLButtonElement);
    if (i < 0) return;
    e.preventDefault();
    const next = e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : (i + (e.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    tabs[next].focus();
    tabs[next].click();
  };
  // 刷新预览：先确认预览地址能打开再换 iframe；打不开就保留当前画面并说明，不报「已刷新」
  const [refreshing, setRefreshing] = useState(false);
  const refreshPreview = async () => {
    if (!frameSrc || refreshing) return;
    setRefreshing(true);
    const ok = await probePreview(frameSrc);
    setRefreshing(false);
    if (!ok) return toast('预览暂时打不开，可能是网络或预览服务的问题。当前画面先保留，稍后再点刷新试试。', 'error');
    setRefreshKey((k) => k + 1);
    toast('预览已刷新。');
  };
  const editor = useVisualEditor(app, frameRef, frameSrc ? `${frameSrc}#${refreshKey}` : null, editMode === 'manual', lockedEarly);
  const [codeChoice, setCodeChoice] = useState<null | { resolve: (go: boolean) => void; saving: boolean; error: string }>(null);
  const editorRef = useRef(editor);
  editorRef.current = editor;

  // 新版本到达（任务成功、恢复、其他标签页）：刷新预览，清掉选中和元素上下文，草稿由编辑器重新读取
  const versionKey = app?.currentVersion?.id ?? null;
  const lastVersion = useRef<string | null>(null);
  useEffect(() => {
    if (!versionKey) return;
    if (lastVersion.current && lastVersion.current !== versionKey) {
      setRefreshKey((k) => k + 1);
      const chat = useChat.getState();
      if (chat.elementContext) {
        chat.setElementContext(null);
        if (chat.draft.trim()) chat.setHint('页面已更新，请重新选择元素');
      }
    }
    lastVersion.current = versionKey;
  }, [versionKey]);

  const onFinished = useCallback(
    async (task: Task) => {
      invalidate(id);
      void qc.invalidateQueries({ queryKey: ['files'] });
      void qc.invalidateQueries({ queryKey: qk.activeTask });
      if (visualTaskRef.current?.id === task.id) {
        visualTaskRef.current.resolve(task);
        visualTaskRef.current = null;
        setVisualTaskId(null);
      }
      if (task.type === 'code_save') {
        const code = useCode.getState();
        if (code.savingTaskId === task.id || !code.savingTaskId) {
          code.setSaving(null);
          if (task.status === 'SUCCEEDED') code.discard();
          else code.setError(task.error?.message || (task.status === 'CANCELLED' ? '代码保存已停止，修改仍保留。' : '代码修改没有保存成功，修改仍保留。'));
        }
        void qc.invalidateQueries({ queryKey: ['file'] });
      }
      if (task.status === 'SUCCEEDED') {
        if (task.type === 'visual_save') setSavedCard(true);
        else toast(finishToast[task.type] ?? '任务已完成。');
        if (task.type === 'modify' || task.type === 'visual_save') {
          await qc.refetchQueries({ queryKey: qk.app(id) });
          void editorRef.current.reloadDraft({ silent: true });
        }
        setPreviewVersion(null);
      } else if (task.status === 'FAILED') {
        toast(task.error?.message || '任务没有完成，可以重试。', 'error');
      } else if (task.status === 'CANCELLED') {
        toast(task.resumable ? '已停止，当前进度已保存，可以继续。' : '已停止。');
      }
    },
    [id, invalidate, qc],
  );
  const stream = useTaskStream(taskId, onFinished);

  // 写文件 / 改文件的工具调用结束时刷新文件树（只读已有版本的文件；没有版本时显示生成中）
  const writesDone = stream.tools.filter((t) => t.state === 'end' && /write|edit|create|patch|写|改|创建|修改|新建/i.test(t.name + ' ' + t.summary)).length;
  useEffect(() => {
    if (!writesDone) return;
    void qc.invalidateQueries({ queryKey: ['files'] });
  }, [writesDone, qc]);

  const onTaskStarted = useCallback(
    (t: Task) => {
      setTrackedId(t.id);
      qc.setQueryData(qk.app(id), (old: typeof app) =>
        old ? { ...old, activeTask: isTerminal(t.status) ? null : { id: t.id, type: t.type, status: t.status } } : old,
      );
      invalidate(id);
    },
    [id, invalidate, qc],
  );

  // 生成中但暂时拿不到 activeTask 时，低频刷新应用状态（不用于进度展示）
  const phase = app?.phase;
  useEffect(() => {
    if (!(phase === 'planning' || phase === 'generating') || activeId) return;
    const t = setInterval(() => void qc.invalidateQueries({ queryKey: qk.app(id) }), 4000);
    return () => clearInterval(t);
  }, [phase, activeId, id, qc]);

  // 离开预览页签或查看历史版本时退出选择模式（草稿保留）
  useEffect(() => {
    if ((tab !== 'preview' || previewVersion) && editorRef.current.enabled) editorRef.current.setEnabled(false);
  }, [tab, previewVersion]);
  // 编辑桥关闭（如页面不可编辑）时同步退出模式
  useEffect(() => {
    if (!editor.enabled && editMode) setEditMode(null);
  }, [editor.enabled, editMode]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || modal) return;
      if (editorRef.current.selection) editorRef.current.clearSelection();
      else setMaximized(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [modal]);

  if (appQ.isLoading)
    return (
      <Shell>
        <LoadingState label="正在打开应用…" />
      </Shell>
    );
  if (appQ.error || !app)
    return (
      <Shell>
        {appQ.error && (appQ.error as { status?: number }).status !== 404 ? (
          <ErrorState message={errorMessage(appQ.error)} onRetry={() => appQ.refetch()} />
        ) : (
          <EmptyState title="这个应用暂时找不到了" desc="从应用列表重新选择一个应用。" canCreate />
        )}
        <button className="subtle-button center-back" onClick={() => navigate('/apps')}>
          返回应用列表
        </button>
      </Shell>
    );

  const ready = app.phase === 'ready' && !!app.currentVersion;
  // 每人同时只能有 1 个进行中的修改：本应用的 activeTask，或其他应用里的任务
  const otherActive = activeQ.data && activeQ.data.appId !== app.id ? activeQ.data : null;
  const busyHere = !!app.activeTask && !isTerminal(app.activeTask.status);
  const blocked: BusyInfo = { busy: busyHere || !!otherActive, otherApp: otherActive ? { id: otherActive.appId, name: otherActive.appName } : null };
  const goApp = (appId: string) => navigate('/apps/' + appId);
  const draftCount = editor.patches.length;

  /**
   * 所有会启动任务的操作之前调用：
   * 1. 代码页有未保存修改 → 让用户选择「先保存代码再继续」或「放弃代码修改并继续」；
   * 2. 等待手动微调草稿的防抖写入完成，保证最后一次修改不丢。
   */
  const beforeTask = async (opts: { checkCode?: boolean } = {}): Promise<boolean> => {
    const checkCode = opts.checkCode ?? true;
    if (checkCode && Object.keys(useCode.getState().edits).length) {
      const go = await new Promise<boolean>((resolve) => setCodeChoice({ resolve, saving: false, error: '' }));
      if (!go) return false;
    }
    try {
      await editor.flush();
    } catch {
      return false;
    }
    return true;
  };

  const saveCodeThenContinue = async () => {
    if (!codeChoice || !app.currentVersion) return;
    setCodeChoice({ ...codeChoice, saving: true, error: '' });
    try {
      const task = await submitCodeEdits(app.id, app.currentVersion.id);
      if (!task) {
        codeChoice.resolve(true);
        setCodeChoice(null);
        return;
      }
      onTaskStarted(task);
      const final = await waitForTask(task.id);
      if (final.status !== 'SUCCEEDED') throw new Error(final.error?.message || '代码修改没有保存成功。');
      useCode.getState().setSaving(null);
      useCode.getState().discard();
      await qc.refetchQueries({ queryKey: qk.app(app.id) });
      codeChoice.resolve(true);
      setCodeChoice(null);
    } catch (err) {
      useCode.getState().setSaving(null);
      setCodeChoice({ ...codeChoice, saving: false, error: errorMessage(err) });
    }
  };
  const viewedVersionId = previewVersion?.id ?? app.currentVersion?.id ?? null;
  const currentVersion = versions.find((v) => v.isCurrent) ?? versions.find((v) => v.id === app.currentVersion?.id);
  const unsaved = app.hasDraft || editor.dirty;

  const toggleMode = (mode: 'chat' | 'manual') => {
    if (editMode === mode) {
      // 再次点击退出；手动微调的未保存修改保留为草稿
      editor.setEnabled(false);
      setEditMode(null);
      return;
    }
    if (skill || !app.visualEditable || editor.editable === false) return toast('该应用暂不支持可视化编辑，可以使用对话修改');
    if (previewVersion) return toast('历史版本仅供预览，返回当前版本后再修改。');
    if (!ready) return toast('应用生成完成后即可选择元素修改。');
    setEditMode(mode);
    if (!editor.enabled) editor.setEnabled(true);
    if (mode === 'manual') setPreviewCollapsed(false);
  };

  /** 带元素上下文直接发送修改（对话修改浮条 / 手动微调的一句话描述）。成功返回 true，失败抛出服务端说明。 */
  const sendElementMessage = async (content: string, withSelection = true): Promise<boolean> => {
    const sel = withSelection ? editor.selection : null;
    const elementContext = sel ? editor.toContext(sel) : null;
    const n = draftCount;
    if (!(await beforeTask())) return false;
    const res = await api.sendMessage(app.id, { content, ...(elementContext ? { elementContext } : {}) });
    qc.setQueryData<Message[]>(qk.messages(app.id), (old) => [...(old ?? []), res.message]);
    if (n > 0) useChat.getState().addDraftNote([res.message.id, res.task.id], n);
    onTaskStarted(res.task);
    setPreviewCollapsed(false);
    return true;
  };

  const viewSource = () => {
    const sel = editor.selection;
    if (!sel) return;
    const m = /^(.*?):(\d+)(?::\d+)?$/.exec(sel.loc);
    const path = (m ? m[1] : sel.loc).replace(/^\.?\//, '');
    const line = m ? Number(m[2]) : 1;
    setPreviewVersion(null);
    useCode.getState().openFile(path, line);
    setTab('code');
  };

  /** 手动微调「应用」：提交草稿 → 跟随 visual_save 任务；失败时抛出 runner 的说明（草稿保留）。 */
  const manualApply = async (): Promise<void> => {
    if (!(await beforeTask())) return;
    const task = await editor.commit('手动微调');
    if (!task) {
      toast('没有需要保存的修改。');
      return;
    }
    const done = new Promise<Task>((resolve) => {
      visualTaskRef.current = { id: task.id, resolve };
    });
    setVisualTaskId(task.id);
    onTaskStarted(task);
    const final = await done;
    if (final.status === 'SUCCEEDED') {
      editor.clearSelection();
      return;
    }
    await editor.reloadDraft({ silent: true });
    throw new Error(final.error?.message || (final.status === 'CANCELLED' ? '保存已停止，修改仍保留。' : '修改没有保存成功，修改仍保留。'));
  };

  const download = (kind: 'page' | 'export') => {
    if (!viewedVersionId) return toast('应用生成完成后即可下载。');
    downloadUrl(kind === 'page' ? api.pageUrl(viewedVersionId) : api.exportUrl(viewedVersionId));
    setModal(null);
    toast(kind === 'page' ? '正在下载页面。' : '正在导出源码。');
  };

  const openExternal = () => {
    if (!frameSrc) return toast('应用生成完成后即可打开。');
    window.open(frameSrc, '_blank', 'noopener');
  };

  const openRestore = async (v: Version) => {
    await editor.flush().catch(() => undefined);
    setModal(null);
    setRestoreTarget(v);
  };

  return (
    <Shell>
      <header className={`studio-header ${maximized ? 'is-fullscreen' : ''}`}>
        <div className="studio-identity">
          <button className="studio-back" aria-label="返回应用列表" onClick={() => navigate('/apps')}>
            <Icon name="chevron" />
          </button>
          <Icon name={kindIcon[app.type]} />
          <button className="studio-app-name" title={`${app.name}（点击重命名）`} onClick={() => setModal('rename')}>
            {app.name}
          </button>
          <button
            className={`studio-version ${previewCollapsed ? 'is-hidden' : ''}`}
            aria-label={`版本历史 V${app.currentVersion?.number ?? 0}`}
            disabled={!app.currentVersion}
            onClick={() => setModal('versions')}
          >
            V{previewVersion?.number ?? app.currentVersion?.number ?? 0} <Icon name="down" />
          </button>
          <span className="studio-dirty studio-dirty-visible" hidden={!unsaved}>
            未保存
          </span>
        </div>
        <div className={`studio-tabs ${previewCollapsed ? 'is-hidden' : ''}`} role="tablist" aria-label="工作台功能" onKeyDown={onTabKey}>
          {narrow ? (
            <button className={mobileView === 'chat' ? 'active' : ''} role="tab" aria-selected={mobileView === 'chat'} tabIndex={mobileView === 'chat' ? 0 : -1} onClick={() => setMobileView('chat')}>
              <Icon name="agent" />
              <span>对话</span>
            </button>
          ) : null}
          {(
            [
              ['code', '代码', 'code'],
              ['preview', '预览', 'preview'],
              ['files', '文件', 'clip'],
              ['cloud', '云服务', 'cloud'],
            ] as const
          ).filter(([value]) => !((skill || script) && (value === 'cloud' || value === 'preview'))).map(([value, label, symbol]) => (
            <button
              key={value}
              className={tab === value && !(narrow && mobileView === 'chat') ? 'active' : ''}
              role="tab"
              aria-label={label}
              title={label}
              aria-selected={tab === value && !(narrow && mobileView === 'chat')}
              tabIndex={tab === value && !(narrow && mobileView === 'chat') ? 0 : -1}
              onClick={() => openTab(value)}
            >
              <Icon name={symbol} />
              <span>{label}</span>
              {value === 'cloud' && cloudOn && <i className="studio-tab-on" title="已启用" aria-label="已启用" />}
            </button>
          ))}
          <span className="studio-nav-divider" />
          <button className="studio-more" aria-label="更多工作台功能" onClick={() => setModal('more')}>
            <Icon name="plus" />
          </button>
        </div>
        <div className="studio-header-actions">
          <button className="studio-square" aria-label="下载应用" disabled={!ready} onClick={() => setModal('download')}>
            <Icon name="download" />
          </button>
          {skill || script ? (
            <button
              className="studio-publish"
              aria-label={script ? '导出源码' : '发布技能'}
              disabled={!ready}
              onClick={() => (script ? download('export') : setModal('skillhub'))}
            >
              <Icon name="globe" />
              <span>{script ? '导出源码' : '发布技能'}</span>
            </button>
          ) : (
            <button className="studio-publish" disabled={!ready} onClick={() => setModal('publish')}>
              <Icon name="globe" />
              <span>发布</span>
            </button>
          )}
          <span className="studio-action-divider" />
          <button
            className={`studio-square ${previewCollapsed ? 'active' : ''}`}
            aria-label={previewCollapsed ? '展开工作区' : '收起工作区'}
            title={previewCollapsed ? '展开工作区（显示预览）' : '收起工作区（对话全屏）'}
            aria-pressed={previewCollapsed}
            disabled={maximized}
            onClick={() => setPreviewCollapsed((v) => !v)}
          >
            <Icon name="collapse-left" />
          </button>
        </div>
      </header>
      <div className={`workspace-body studio-body ${previewCollapsed ? 'preview-collapsed' : ''} ${maximized ? 'is-maximized' : ''} ${narrow ? 'mobile-' + mobileView : ''}`}>
        {editMode === 'manual' && editor.selection && tab === 'preview' ? (
          <ManualPanel key={`${editor.selection.loc}#${editor.selection.instanceIndex ?? ''}`} editor={editor} selection={editor.selection} onClose={editor.clearSelection} />
        ) : (
        <ChatPanel
          app={app}
          stream={stream}
          currentVersion={currentVersion}
          versions={versions}
          onTaskStarted={onTaskStarted}
          onEnableCloud={() => useChat.getState().setCloudAsk(true)}
          onOpenCloud={() => setTab('cloud')}
          onRestoreVersion={(v) => setRestoreTarget(v)}
          onPublish={() => (script ? download('export') : setModal(skill ? 'skillhub' : 'publish'))}
          skill={skill}
          runningTaskId={busyHere ? app.activeTask!.id : null}
          beforeSend={() => beforeTask()}
          pendingManual={draftCount}
          otherBusy={blocked.otherApp ?? null}
          previewVisible={!previewCollapsed}
          onFocusPreview={() => {
            setPreviewCollapsed(false);
            setMobileView('panel');
            setTab(mainTab);
            setPreviewVersion(null);
          }}
        />
        )}
        {!previewCollapsed ? (
        <section className={`preview-panel studio-panel ${maximized ? 'preview-maximized' : ''}`}>
          {tab === 'preview' && (skill || script) ? (
            <div className="no-preview">
              <Pet size={150} sleeping bubble={script ? '脚本项目只有文件，没有页面哦' : '技能包只有文件，没有页面哦'} />
              <b>暂无预览</b>
              <p>{script ? '这是命令行脚本项目，交付的是可以直接运行的代码，没有页面。' : '技能包由 SKILL.md、参考资料和脚本组成，可以在「代码」中查看，下载后放进技能目录即可使用。'}</p>
              <button className="subtle-button" onClick={() => setTab('code')}>
                <Icon name="code" /> 查看文件
              </button>
            </div>
          ) : tab === 'preview' ? (
            <>
              <div className="studio-preview-tools">
                <div className="studio-device-tools">
                  <button
                    className="studio-tool-button"
                    aria-label={maximized ? '退出全屏预览' : '全屏预览'}
                    title={maximized ? '退出全屏' : '预览全屏（不显示顶部工具栏）'}
                    onClick={() => {
                      setPreviewCollapsed(false);
                      setMaximized((v) => !v);
                    }}
                  >
                    <Icon name={maximized ? 'contract' : 'expand'} />
                  </button>
                </div>
                <div className="studio-route-bar">
                  <button
                    className="studio-tool-button"
                    aria-label="刷新预览"
                    disabled={!frameSrc || refreshing}
                    onClick={() => void refreshPreview()}
                  >
                    <Icon name="refresh" className={refreshing ? 'spinning' : ''} />
                  </button>
                  <button className="studio-route-picker" aria-label="选择预览页面" onClick={() => setModal('routes')}>
                    Home
                    <Icon name="down" />
                  </button>
                  {cloudOn ? (
                    <Select
                      className="studio-identity-picker"
                      ariaLabel="预览身份"
                      value={previewAs}
                      options={[
                        { value: 'admin', label: '作者', hint: '以管理员身份预览' },
                        { value: 'member', label: '员工', hint: '模拟一位普通员工，试「只看自己的」等权限' },
                        { value: 'anon', label: '未登录', hint: '模拟没有登录的访问者' },
                      ]}
                      onChange={setPreviewAs}
                    />
                  ) : null}
                  <button className="studio-tool-button" aria-label="在新窗口打开预览" disabled={!frameSrc} onClick={openExternal}>
                    <Icon name="external" />
                  </button>
                </div>
                <div className="studio-edit-tools">
                  <button
                    type="button"
                    className={`studio-dialog-modify ${editMode === 'chat' ? 'active' : ''}`}
                    aria-label="对话修改"
                    aria-pressed={editMode === 'chat'}
                    title="选中元素后，直接描述想怎么改"
                    disabled={editor.saving}
                    onClick={() => toggleMode('chat')}
                  >
                    <Icon name="cursor" />
                    <span>对话修改</span>
                  </button>
                  <button
                    type="button"
                    className={`studio-edit-toggle ${editMode === 'manual' ? 'active' : ''}`}
                    aria-label="手动微调"
                    aria-pressed={editMode === 'manual'}
                    title="选中元素后，在左侧调整文字与样式"
                    disabled={editor.saving}
                    onClick={() => toggleMode('manual')}
                  >
                    <Icon name="edit" />
                    <span>手动微调</span>
                  </button>
                </div>
              </div>
              {editor.staleDraft && !previewVersion ? (
                <div className="stale-draft-banner" role="alert">
                  有一份基于 V{versions.find((v) => v.id === editor.staleDraft!.baseVersionId)?.number ?? '?'} 的手动修改未保存，当前已是 V{app.currentVersion?.number}
                  <button type="button" onClick={() => void editor.discardStaleDraft().then(() => qc.invalidateQueries({ queryKey: qk.app(id) }))}>
                    放弃这份修改
                  </button>
                </div>
              ) : null}
              {previewVersion ? (
                <div className="old-version-banner">
                  正在预览历史版本 V{previewVersion.number}
                  <button onClick={() => setPreviewVersion(null)}>返回当前版本</button>
                </div>
              ) : null}
              <div className={`preview-stage ${editMode ? 'is-selecting' : ''}`}>
                {frameSrc ? (
                  <iframe
                    key={frameSrc + '#' + refreshKey}
                    ref={frameRef}
                    src={frameSrc}
                    data-preview
                    sandbox="allow-scripts allow-forms"
                    title={`${app.name}应用预览`}
                  />
                ) : null}
                {frameSrc && editMode && editor.selection ? <ElementTagPill selection={editor.selection} frameRef={frameRef} /> : null}
                {frameSrc && editMode === 'chat' && editor.selection ? (
                  <ChatEditBar
                    key={editor.selection.loc}
                    selection={editor.selection}
                    frameRef={frameRef}
                    onParent={editor.selectParent}
                    onViewSource={viewSource}
                    onClose={editor.clearSelection}
                    blocked={blocked}
                    onGo={goApp}
                    pendingManual={draftCount}
                    onSend={async (text) => {
                      const ok = await sendElementMessage(text, true);
                      if (ok) editor.clearSelection();
                      return ok;
                    }}
                  />
                ) : null}
                {frameSrc && editMode === 'chat' && !editor.selection ? (
                  <div className="ze-mode-tip" role="status">
                    {blocked.busy ? '正在修改中，完成后可继续选择元素' : draftCount ? `点击元素描述修改 · 发送时会先保存你的 ${draftCount} 处手动修改` : '点击预览中的元素，直接描述想怎么改'}
                  </div>
                ) : null}
                {frameSrc && editMode === 'manual' && blocked.busy && !visualTaskId ? (
                  <div className="ze-mode-tip" role="status">正在修改中，完成后可继续微调</div>
                ) : null}
                {frameSrc && editMode === 'manual' ? (
                  <ManualBottomBar
                    canApply={unsaved}
                    busy={editor.saving || !!visualTaskId}
                    blocked={visualTaskId ? { busy: false } : blocked}
                    onGo={goApp}
                    onSwitchToChat={() => {
                      setEditMode('chat');
                      if (!editor.enabled) editor.setEnabled(true);
                    }}
                    onReset={async () => {
                      await editor.reset();
                      editor.clearSelection();
                      void qc.invalidateQueries({ queryKey: qk.app(id) });
                      toast('已放弃未保存的手动修改。');
                    }}
                    onApply={manualApply}
                  />
                ) : null}
                {frameSrc ? null : app.phase === 'planning' || app.phase === 'plan' ? (
                  <div className="preview-empty preview-empty-pet">
                    <Pet size={150} sleeping bubble={app.phase === 'planning' ? '计划写好就叫醒我～' : '确认计划我就开工～'} />
                    <h3>下一步，让想法成真。</h3>
                    <p>{app.phase === 'planning' ? '正在生成创造计划，稍后在左侧确认。' : '确认左侧计划后，你的应用将在这里出现。'}</p>
                    <span className="preview-empty-note">一点想象，无限可能。</span>
                  </div>
                ) : app.phase === 'stopped' ? (
                  <div className="preview-empty preview-empty-pet">
                    <Pet size={150} sleeping bubble="想继续就叫醒我～" />
                    <h3>生成已停止</h3>
                    <p>进度已保存，在左侧点「继续」就能接着做。</p>
                  </div>
                ) : app.phase === 'failed' ? (
                  <div className="preview-empty">
                    <span>
                      <Icon name="alert" />
                    </span>
                    <h3>这次没能生成出来</h3>
                    <p>可以在左侧查看原因并重试。</p>
                  </div>
                ) : skill || script ? (
                  <div className="preview-empty">
                    <span>
                      <Icon name={skill ? 'agent' : 'code'} />
                    </span>
                    <h3>{skill ? '该应用为技能包' : '该应用为命令行脚本'}</h3>
                    <p>无需网页预览，可在「代码」页查看源码或执行文件。</p>
                  </div>
                ) : app.phase === 'ready' && !frameSrc ? (
                  <div className="preview-empty">
                    <span>
                      <Icon name="alert" />
                    </span>
                    <h3>暂无可用的网页预览</h3>
                    <p>当前版本未包含可预览的前端页面，可在「代码」页查看文件。</p>
                  </div>
                ) : (
                  <CreatingStage label={stream.phaseLabel} skill={skill} />
                )}
              </div>
            </>
          ) : tab === 'code' ? (
            <>
              <div className="studio-pane-title">
                <Icon name="code" />
                <span>代码</span>
                <small>
                  {viewedVersionId
                    ? `V${previewVersion?.number ?? app.currentVersion?.number} · ${previewVersion ? '历史版本，只读' : app.phase !== 'ready' ? '生成中，只读' : blocked.busy ? '修改中，暂不能编辑' : '可编辑'}${app.framework ? ' · ' + app.framework : ''}`
                    : '暂无版本'}
                </small>
              </div>
              <ErrorBoundary area="代码面板" onReset={CodePane.reload}>
              <Suspense fallback={<LoadingState label="正在打开代码…" />}>
              <CodePane
                appId={app.id}
                appName={app.name}
                versionId={viewedVersionId}
                currentVersionId={app.currentVersion?.id ?? null}
                readOnly={!!previewVersion || app.phase !== 'ready'}
                skill={skill}
                onTaskStarted={onTaskStarted}
                generating={app.phase === 'generating' || app.phase === 'planning'}
                liveTaskId={busyHere ? app.activeTask!.id : null}
                pendingDraft={
                  draftCount && !previewVersion
                    ? {
                        onApply: () => {
                          setTab('preview');
                          setEditMode('manual');
                          if (!editor.enabled) editor.setEnabled(true);
                          void manualApply().catch((err) => toast(errorMessage(err), 'error'));
                        },
                        onReset: () => {
                          void editor.reset().then(() => qc.invalidateQueries({ queryKey: qk.app(id) }));
                        },
                      }
                    : null
                }
                blocked={blocked}
                onGo={goApp}
              />
              </Suspense>
              </ErrorBoundary>
            </>
          ) : tab === 'files' ? (
            <FilesPane
              versionId={viewedVersionId}
              assets={app.assets ?? []}
              onDownloadPage={() => download('page')}
              onExport={() => download('export')}
            />
          ) : (
            <CloudPane appId={app.id} />
          )}
        </section>
        ) : null}
      </div>
      {codeChoice ? (
        <div className="code-choice" role="alertdialog" aria-label="代码页有未保存的修改">
          <b>代码页有未保存的修改</b>
          <p>先处理代码修改，再继续这次操作，避免两边的修改互相覆盖。</p>
          {codeChoice.error ? (
            <p className="inline-error" role="alert">
              {codeChoice.error}
            </p>
          ) : null}
          <div>
            <button
              type="button"
              className="subtle-button"
              disabled={codeChoice.saving}
              onClick={() => {
                codeChoice.resolve(false);
                setCodeChoice(null);
              }}
            >
              取消
            </button>
            <button
              type="button"
              className="subtle-button"
              disabled={codeChoice.saving}
              onClick={() => {
                useCode.getState().discard();
                codeChoice.resolve(true);
                setCodeChoice(null);
              }}
            >
              放弃代码修改并继续
            </button>
            <button type="button" className="primary-button" disabled={codeChoice.saving} onClick={() => void saveCodeThenContinue()}>
              {codeChoice.saving ? '正在保存代码…' : '先保存代码再继续'}
            </button>
          </div>
        </div>
      ) : null}
      {savedCard ? <SavedCard onClose={() => setSavedCard(false)} /> : null}
      {modal === 'rename' ? <RenameModal appId={app.id} name={app.name} onClose={() => setModal(null)} /> : null}
      {modal === 'versions' ? (
        <VersionSheet
          appId={app.id}
          appName={app.name}
          versions={versions}
          loading={versionsQ.isLoading}
          onClose={() => setModal(null)}
          onPreview={(v) => {
            setPreviewVersion(v.isCurrent ? null : v);
            setTab(mainTab);
            setModal(null);
          }}
          onRestore={(v) => void openRestore(v)}
        />
      ) : null}
      {restoreTarget ? (
        <RestoreModal
          appId={app.id}
          appName={app.name}
          version={restoreTarget}
          onClose={() => setRestoreTarget(null)}
          onDone={() => setPreviewVersion(null)}
        />
      ) : null}
      {modal === 'skillhub' ? (
        <SkillhubModal
          app={app}
          versions={versions}
          onClose={() => setModal(null)}
          onDownload={(vid) => {
            downloadUrl(api.exportUrl(vid));
            toast('正在下载技能包。');
          }}
        />
      ) : null}
      {modal === 'publish' ? (
        <PublishModal
          app={app}
          hasLocalDraft={editor.dirty}
          commitDraft={() => editor.commit('发布前保存编辑')}
          onTaskStarted={onTaskStarted}
          onClose={() => setModal(null)}
        />
      ) : null}
      {modal === 'download' ? (
        <Modal title="下载应用" onClose={() => setModal(null)}>
          <div className="menu-list">
            {skill ? null : (
              <button onClick={() => download('page')}>
                <Icon name="web" />
                下载页面<small className="menu-hint">单文件 HTML，可直接打开</small>
              </button>
            )}
            <button onClick={() => download('export')}>
              <Icon name="code" />
              {skill ? '下载技能包' : '导出源码'}<small className="menu-hint">{skill ? '解压即可放进技能目录使用' : '完整项目 zip'}</small>
            </button>
          </div>
          <p className="muted-note">
            下载的是 V{previewVersion?.number ?? app.currentVersion?.number}
            {unsaved ? '；未保存的可视化编辑不包含在内。' : '。'}
          </p>
        </Modal>
      ) : null}
      {modal === 'more' ? (
        <Modal title="更多功能" onClose={() => setModal(null)}>
          <div className="menu-list">
            <button disabled={!app.currentVersion} onClick={() => setModal('versions')}>
              <Icon name="history" />
              版本历史
            </button>
            <button onClick={() => setModal('rename')}>
              <Icon name="edit" />
              重命名应用
            </button>
            <button onClick={() => navigate('/history?appId=' + encodeURIComponent(app.id))}>
              <Icon name="book" />
              在历史页查看
            </button>
          </div>
        </Modal>
      ) : null}
      {modal === 'routes' ? (
        <Modal title="预览页面" onClose={() => setModal(null)}>
          <div className="menu-list">
            <button onClick={() => setModal(null)}>
              <Icon name="preview" />
              Home /
            </button>
          </div>
          <p className="muted-note">可以在预览中直接点击导航切换页面。</p>
        </Modal>
      ) : null}
    </Shell>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="shell studio-shell">
      <AppRail page="workspace" />
      <main className="main main-workspace studio-main">{children}</main>
      <HelpPanel />
    </div>
  );
}
