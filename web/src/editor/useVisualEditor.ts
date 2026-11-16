import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import { ApiError, api, errorMessage } from '../api/client';
import { isSkillApp, type AppDetail, type BridgeInbound, type BridgeOutbound, type ElementContext, type Patch, type Selection, type Task } from '../api/types';
import { toast } from '../lib/store';

export type PatchChange = Omit<Patch, 'loc'>;

const sameStyles = (a?: Record<string, string>, b?: Record<string, string>) =>
  JSON.stringify(a ?? {}) === JSON.stringify(b ?? {});

/** 补丁目标：loc + 可选的实例位置（同 loc 多实例时「只改这一项」）。 */
export interface PatchTarget {
  loc: string;
  index?: number;
  match?: string;
  tag?: string;
}

export const isInstancePatch = (p: Patch) => typeof p.index === 'number';
const sameTarget = (p: Patch, t: PatchTarget) => p.loc === t.loc && (p.index ?? -1) === (t.index ?? -1);

/** 同一目标的多次修改合并为一条。 */
export function mergePatch(list: Patch[], target: PatchTarget, change: PatchChange): Patch[] {
  const idx = list.findIndex((p) => sameTarget(p, target));
  const prev: Patch =
    idx >= 0
      ? list[idx]
      : typeof target.index === 'number'
        ? { loc: target.loc, index: target.index, match: target.match ?? '', ...(target.tag ? { tag: target.tag } : {}) }
        : { loc: target.loc };
  const next: Patch = { ...prev };
  if (change.text !== undefined) next.text = change.text;
  if (change.href !== undefined) next.href = change.href;
  if (change.removed !== undefined) next.removed = change.removed;
  if (change.styles) next.styles = { ...(prev.styles ?? {}), ...change.styles };
  if (idx >= 0) {
    const p = list[idx];
    if (p.text === next.text && p.href === next.href && p.removed === next.removed && sameStyles(p.styles, next.styles)) return list;
    return list.map((x, i) => (i === idx ? next : x));
  }
  return [...list, next];
}

/** 发给编辑桥的顺序：全部同类项在前，单项在后（单项覆盖全部）。 */
const forBridge = (list: Patch[]) => [...list.filter((p) => !isInstancePatch(p)), ...list.filter(isInstancePatch)];
export type EditScope = 'one' | 'all';

export interface VisualEditor {
  enabled: boolean;
  ready: boolean;
  editable: boolean | null;
  selection: Selection | null;
  patches: Patch[];
  canUndo: boolean;
  canRedo: boolean;
  saving: boolean;
  dirty: boolean;
  setEnabled: (on: boolean) => void;
  /** 不传 target 时作用于当前选中元素，并按 scope 决定是否只改这一项 */
  apply: (change: PatchChange, target?: PatchTarget) => void;
  /** 同 loc 多实例时的修改范围；选中新元素时重置为「只改这一项」 */
  scope: EditScope;
  setScope: (scope: EditScope) => void;
  undo: () => void;
  redo: () => void;
  selectParent: () => void;
  deselect: () => void;
  flush: () => Promise<void>;
  commit: (title?: string) => Promise<Task | null>;
  reloadDraft: (opts?: { silent?: boolean }) => Promise<void>;
  /** 放弃全部未保存的手动修改：DELETE 草稿、预览恢复原样、清空撤销记录 */
  reset: () => Promise<void>;
  /** 过期草稿（基于旧版本）：不自动应用也不自动丢弃 */
  staleDraft: { baseVersionId: string; count: number } | null;
  discardStaleDraft: () => Promise<void>;
  locked: boolean;
  /** 被锁定时尝试选中元素的次数（用于显示提示） */
  blockedTick: number;
  /** 选中被关闭但保持模式（对话修改发送后） */
  clearSelection: () => void;
  toContext: (s: Selection) => ElementContext | null;
}

export function useVisualEditor(
  app: AppDetail | undefined,
  frameRef: RefObject<HTMLIFrameElement | null>,
  frameSrc: string | null,
  /** 手动微调模式：允许双击改字、Delete 删除与快捷键撤销 */
  manual = false,
  /** 有进行中的任务：忽略新的选中、禁止修改与撤销 */
  locked = false,
): VisualEditor {
  const lockedRef = useRef(locked);
  lockedRef.current = locked;
  const manualRef = useRef(manual);
  manualRef.current = manual;
  const [enabled, setEnabledState] = useState(false);
  const [ready, setReady] = useState(false);
  const [editable, setEditable] = useState<boolean | null>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [patches, setPatches] = useState<Patch[]>([]);
  const [scope, setScopeState] = useState<EditScope>('one');
  const scopeRef = useRef<EditScope>('one');
  const setScope = useCallback((v: EditScope) => {
    scopeRef.current = v;
    setScopeState(v);
  }, []);
  const undoRef = useRef<Patch[][]>([]);
  const redoRef = useRef<Patch[][]>([]);
  const [, bump] = useState(0);
  const resetHistory = useCallback(() => {
    undoRef.current = [];
    redoRef.current = [];
    bump((n) => n + 1);
  }, []);
  const [saving, setSaving] = useState(false);
  const [staleDraft, setStaleDraft] = useState<{ baseVersionId: string; count: number } | null>(null);
  const [blockedTick, setBlockedTick] = useState(0);

  const appRef = useRef(app);
  appRef.current = app;
  const patchesRef = useRef(patches);
  patchesRef.current = patches;
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;
  const selectionRef = useRef(selection);
  selectionRef.current = selection;
  const saveTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingSave = useRef<Promise<void> | null>(null);
  const serverHasDraft = useRef(false);

  const send = useCallback(
    (msg: BridgeOutbound) => {
      const win = frameRef.current?.contentWindow;
      // 预览与工作台不同源（sandbox 无 allow-same-origin），只能用 '*'；接收端校验 event.source。
      if (win) win.postMessage({ channel: 'zhiye-editor', ...msg }, '*');
    },
    [frameRef],
  );

  // iframe 地址变化即视为重新加载，等待新的 ready
  useEffect(() => {
    setReady(false);
    setEditable(null);
    setSelection(null);
  }, [frameSrc]);

  // ---- 草稿保存 ----
  const saveNow = useCallback(async () => {
    const a = appRef.current;
    if (!a?.currentVersion) return;
    // 单项补丁（index / match）和全部同类项一起进入服务端草稿，由 runner 确定性写回
    const list = patchesRef.current.map(({ tag: _tag, ...p }) => p);
    try {
      if (list.length) {
        await api.putDraft(a.id, { baseVersionId: a.currentVersion.id, patches: list });
        serverHasDraft.current = true;
      } else if (serverHasDraft.current || a.hasDraft) {
        await api.deleteDraft(a.id).catch((e) => {
          if (!(e instanceof ApiError && e.status === 404)) throw e;
        });
        serverHasDraft.current = false;
      }
    } catch (error) {
      toast(errorMessage(error, '编辑草稿没有保存成功，请重试。'), 'error');
      throw error;
    }
  }, []);

  const scheduleSave = useCallback(() => {
    if (saveTimer.current) clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(() => {
      saveTimer.current = null;
      pendingSave.current = saveNow().catch(() => undefined).finally(() => (pendingSave.current = null));
    }, 500);
  }, [saveNow]);

  const flush = useCallback(async () => {
    if (saveTimer.current) {
      clearTimeout(saveTimer.current);
      saveTimer.current = null;
      await saveNow();
    } else if (pendingSave.current) {
      await pendingSave.current;
    }
  }, [saveNow]);

  const setList = useCallback(
    (next: Patch[], save = true) => {
      const prev = patchesRef.current;
      patchesRef.current = next;
      setPatches(next);
      send({ type: 'apply-patches', patches: forBridge(next) });
      if (save && prev !== next) scheduleSave();
    },
    [scheduleSave, send],
  );

  const apply = useCallback(
    (change: PatchChange, explicit?: PatchTarget) => {
      if (lockedRef.current) return toast('正在修改中，完成后可继续微调');
      const sel = selectionRef.current;
      let target: PatchTarget | null = explicit ?? null;
      if (!target) {
        if (!sel) return toast('先在预览中选中一个元素。');
        target = { loc: sel.loc };
        if (sel.sameLocCount > 1 && scopeRef.current === 'one' && typeof sel.instanceIndex === 'number' && sel.instanceIndex >= 0)
          target = { loc: sel.loc, index: sel.instanceIndex, match: (sel.instanceText ?? sel.text ?? '').slice(0, 200), tag: sel.tag };
      }
      const prev = patchesRef.current;
      const next = mergePatch(prev, target, change);
      if (next === prev) return;
      undoRef.current = [...undoRef.current.slice(-49), prev];
      redoRef.current = [];
      bump((n) => n + 1);
      setList(next);
      const loc = target.loc;
      setSelection((s) => {
        if (!s || s.loc !== loc) return s;
        if (change.removed) return null;
        return {
          ...s,
          text: change.text ?? s.text,
          href: change.href ?? s.href,
          styles: change.styles ? { ...s.styles, ...change.styles } : s.styles,
        };
      });
    },
    [setList],
  );

  const undo = useCallback(() => {
    if (lockedRef.current) return;
    const u = undoRef.current;
    if (!u.length) return;
    redoRef.current = [...redoRef.current, patchesRef.current];
    undoRef.current = u.slice(0, -1);
    bump((n) => n + 1);
    // 保持选中：编辑桥重绘后会发 selection-position，带回最新的计算样式
    setList(u[u.length - 1]);
  }, [setList]);

  const redo = useCallback(() => {
    if (lockedRef.current) return;
    const r = redoRef.current;
    if (!r.length) return;
    undoRef.current = [...undoRef.current, patchesRef.current];
    redoRef.current = r.slice(0, -1);
    bump((n) => n + 1);
    setList(r[r.length - 1]);
  }, [setList]);

  // ---- 桥消息 ----
  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      const frame = frameRef.current;
      if (!frame || event.source !== frame.contentWindow) return;
      const msg = event.data as BridgeInbound;
      if (!msg || msg.channel !== 'zhiye-editor') return;
      switch (msg.type) {
        case 'ready':
          setReady(true);
          setEditable(msg.editable !== false);
          if (patchesRef.current.length) send({ type: 'apply-patches', patches: forBridge(patchesRef.current) });
          if (enabledRef.current && msg.editable !== false)
            send({ type: 'toggle', enabled: true, loc: selectionRef.current?.loc });
          else if (enabledRef.current) setEnabledState(false);
          break;
        case 'selected':
          if (enabledRef.current && lockedRef.current) {
            setBlockedTick((n) => n + 1);
            send({ type: 'deselect' });
          } else if (enabledRef.current) {
            if (selectionRef.current?.loc !== msg.selection.loc || selectionRef.current?.instanceIndex !== msg.selection.instanceIndex) setScope('one');
            setSelection(msg.selection);
          }
          break;
        case 'selection-position':
          if (enabledRef.current && selectionRef.current?.loc === msg.selection?.loc)
            // 位置和计算样式都更新（撤销 / 重做 / 补丁重放后面板读到真实值）
            setSelection((s) =>
              s && (s.instanceIndex === undefined || msg.selection.instanceIndex === undefined || s.instanceIndex === msg.selection.instanceIndex)
                ? { ...s, ...msg.selection }
                : s,
            );
          break;
        case 'deselect':
          setSelection(null);
          break;
        case 'inline-edit':
          // 双击改字在两种模式下都记为补丁（页面上已经改了，不能悄悄丢掉）
          if (enabledRef.current && typeof msg.text === 'string') {
            const sel = selectionRef.current;
            const many = sel?.loc === msg.loc ? sel.sameLocCount > 1 : false;
            const one = many && scopeRef.current === 'one' && typeof msg.index === 'number' && msg.index >= 0;
            apply(
              { text: msg.text },
              one ? { loc: msg.loc, index: msg.index, match: (msg.match ?? '').slice(0, 200), tag: sel?.tag } : { loc: msg.loc },
            );
          }
          break;
      }
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [apply, frameRef, send, setScope]);

  const setEnabled = useCallback(
    (on: boolean) => {
      const a = appRef.current;
      if (on) {
        if (!a || a.phase !== 'ready' || !a.currentVersion) return toast('应用生成完成后即可进入编辑模式。');
        if (isSkillApp(a) || !a.visualEditable || editable === false) return toast('该应用暂不支持可视化编辑，可以使用对话修改');
      }
      setEnabledState(on);
      enabledRef.current = on;
      if (!on) setSelection(null);
      if (ready || !on) send({ type: 'toggle', enabled: on });
    },
    [editable, ready, send],
  );

  // ---- 恢复草稿 ----
  const loaded = useRef<string | null>(null);
  const reloadDraft = useCallback(async (opts?: { silent?: boolean }) => {
    const a = appRef.current;
    if (!a) return;
    try {
      const draft = await api.getDraft(a.id);
      serverHasDraft.current = !!draft;
      const stale = !!draft && !!a.currentVersion && draft.baseVersionId !== a.currentVersion.id;
      const list = draft && !stale ? draft.patches : [];
      // 过期草稿既不套用也不丢弃，交给用户决定
      setStaleDraft(stale ? { baseVersionId: draft!.baseVersionId, count: draft!.patches.length } : null);
      void opts;
      resetHistory();
      setList(list ?? [], false);
    } catch (error) {
      if (!opts?.silent) toast(errorMessage(error, '没能读取编辑草稿。'), 'error');
    }
  }, [resetHistory, setList]);

  useEffect(() => {
    if (!app?.id || app.phase !== 'ready') return;
    const key = `${app.id}@${app.currentVersion?.id ?? ''}`;
    if (loaded.current === key) return;
    const first = !loaded.current?.startsWith(app.id + '@');
    loaded.current = key;
    if (!first) {
      // 新版本到达：清掉选中，重新读取草稿（撤销记录在 reloadDraft 里重置）
      setSelection(null);
      if (saveTimer.current) {
        clearTimeout(saveTimer.current);
        saveTimer.current = null;
      }
    }
    void reloadDraft();
  }, [app?.id, app?.phase, app?.currentVersion?.id, reloadDraft]);

  const discardStaleDraft = useCallback(async () => {
    const a = appRef.current;
    if (!a) return;
    try {
      await api.deleteDraft(a.id);
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 404)) {
        toast(errorMessage(e), 'error');
        return;
      }
    }
    serverHasDraft.current = false;
    setStaleDraft(null);
  }, []);

  const commit = useCallback(
    async (title?: string) => {
      const a = appRef.current;
      if (!a) return null;
      setSaving(true);
      try {
        await flush();
        if (!patchesRef.current.length && !serverHasDraft.current && !a.hasDraft) return null;
        const task = await api.commitDraft(a.id, title);
        resetHistory();
        return task;
      } finally {
        setSaving(false);
      }
    },
    [flush, resetHistory],
  );

  // ---- 快捷键 ----
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!enabledRef.current || !manualRef.current) return;
      const t = e.target as HTMLElement;
      const form = /INPUT|TEXTAREA|SELECT/.test(t?.tagName ?? '') || t?.isContentEditable;
      if (!form && (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (e.shiftKey) redo();
        else undo();
      } else if (!form && (e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'y') {
        e.preventDefault();
        redo();
      } else if (!form && (e.key === 'Delete' || e.key === 'Backspace') && selectionRef.current) {
        const s = selectionRef.current;
        if (!['HTML', 'BODY'].includes(s.tag.toUpperCase())) {
          e.preventDefault();
          apply({ removed: true });
        }
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [apply, redo, undo]);

  // 离开页面前尽量保存草稿
  useEffect(
    () => () => {
      if (saveTimer.current) {
        clearTimeout(saveTimer.current);
        void saveNow().catch(() => undefined);
      }
    },
    [saveNow],
  );

  const reset = useCallback(async () => {
    if (saveTimer.current) {
      clearTimeout(saveTimer.current);
      saveTimer.current = null;
    }
    if (pendingSave.current) await pendingSave.current.catch(() => undefined);
    const a = appRef.current;
    if (a) {
      try {
        await api.deleteDraft(a.id);
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 404)) {
          toast(errorMessage(e, '没能放弃修改，请重试。'), 'error');
          return;
        }
      }
    }
    serverHasDraft.current = false;
    resetHistory();
    setList([], false);
  }, [resetHistory, setList]);

  const toContext = useCallback((s: Selection): ElementContext | null => {
    const a = appRef.current;
    if (!a?.currentVersion) return null;
    return {
      appId: a.id,
      baseVersionId: a.currentVersion.id,
      loc: s.loc,
      tag: s.tag,
      text: s.text,
      outerHTML: s.outerHTML?.slice(0, 2000),
      styles: s.styles,
    };
  }, []);

  return {
    enabled,
    ready,
    editable,
    selection,
    patches,
    canUndo: !locked && undoRef.current.length > 0,
    canRedo: !locked && redoRef.current.length > 0,
    staleDraft,
    discardStaleDraft,
    locked,
    blockedTick,
    saving,
    dirty: patches.length > 0,
    setEnabled,
    apply,
    undo,
    redo,
    selectParent: () => send({ type: 'select-parent' }),
    deselect: () => {
      setSelection(null);
      send({ type: 'deselect' });
    },
    flush,
    commit,
    reloadDraft,
    reset,
    scope,
    setScope,
    clearSelection: () => {
      setSelection(null);
      send({ type: 'deselect' });
    },
    toContext,
  };
}
