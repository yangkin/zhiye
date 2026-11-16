import { create } from 'zustand';
import { api, newIdempotencyKey } from '../api/client';
import type { Task } from '../api/types';
import { loadDraft, saveDraft } from './drafts';

export type FileViewMode = 'preview' | 'edit';

/** 代码页签的本地状态：打开的文件、预览/编辑模式、未保存的修改。未保存的修改按应用存在本地，切换应用、刷新页面都不丢。 */
interface CodeState {
  appId: string | null;
  open: string[];
  active: string | null;
  modes: Record<string, FileViewMode>;
  /** path → 修改后的内容（含新建文件） */
  edits: Record<string, string>;
  /** 新建的文件（版本里还没有） */
  created: string[];
  /** 打开某个文件并跳到行号（来自「查看源码」） */
  jump: { path: string; line: number; nonce: number } | null;
  savingTaskId: string | null;
  error: string;
  /** 未保存修改基于哪个版本：恢复时版本已变就提示 */
  base: string | null;
  /** 切回应用时从本地恢复了几处未保存的修改（提示一次后清零） */
  restored: number;
  /** 打开的「本次修改」视图对应的版本；null 表示在看文件 */
  changesVersion: string | null;
  openChanges: (versionId: string | null) => void;
  resetFor: (appId: string) => void;
  openFile: (path: string, line?: number) => void;
  closeFile: (path: string) => void;
  setMode: (path: string, mode: FileViewMode) => void;
  setEdit: (path: string, content: string, original: string | null, base?: string | null) => void;
  createFile: (path: string) => void;
  discard: () => void;
  setSaving: (taskId: string | null) => void;
  setError: (error: string) => void;
}

export const useCode = create<CodeState>((set, get) => ({
  appId: null,
  open: [],
  active: null,
  modes: {},
  edits: {},
  created: [],
  jump: null,
  savingTaskId: null,
  error: '',
  changesVersion: null,
  base: null,
  restored: 0,
  openChanges: (versionId) => set({ changesVersion: versionId }),
  resetFor: (appId) => {
    if (get().appId === appId) return;
    const saved = loadDraft<CodeDraft>('code.' + appId);
    set({
      appId, jump: null, savingTaskId: null, error: '', changesVersion: null,
      open: saved?.open ?? [], active: saved?.active ?? null, modes: saved?.modes ?? {},
      edits: saved?.edits ?? {}, created: saved?.created ?? [], base: saved?.base ?? null,
      restored: saved ? Object.keys(saved.edits).length : 0,
    });
  },
  openFile: (path, line) =>
    set((s) => ({
      open: s.open.includes(path) ? s.open : [...s.open, path].slice(-8),
      active: path,
      jump: line ? { path, line, nonce: (s.jump?.nonce ?? 0) + 1 } : s.jump,
    })),
  closeFile: (path) =>
    set((s) => {
      const open = s.open.filter((p) => p !== path);
      const idx = s.open.indexOf(path);
      return { open, active: s.active === path ? (open[Math.min(idx, open.length - 1)] ?? null) : s.active };
    }),
  setMode: (path, mode) => set((s) => ({ modes: { ...s.modes, [path]: mode } })),
  setEdit: (path, content, original, base) =>
    set((s) => {
      const edits = { ...s.edits };
      if (original !== null && content === original && !s.created.includes(path)) delete edits[path];
      else edits[path] = content;
      return { edits, error: '', base: Object.keys(s.edits).length ? s.base : (base ?? s.base) };
    }),
  createFile: (path) =>
    set((s) => ({
      created: s.created.includes(path) ? s.created : [...s.created, path],
      edits: { ...s.edits, [path]: s.edits[path] ?? '' },
      open: s.open.includes(path) ? s.open : [...s.open, path],
      active: path,
      modes: { ...s.modes, [path]: 'edit' },
    })),
  discard: () =>
    set((s) => ({
      edits: {},
      created: [],
      open: s.open.filter((p) => !s.created.includes(p)),
      active: s.active && s.created.includes(s.active) ? null : s.active,
      error: '',
      base: null,
    })),
  setSaving: (savingTaskId) => set({ savingTaskId }),
  setError: (error) => set({ error }),
}));

interface CodeDraft {
  edits: Record<string, string>;
  created: string[];
  open: string[];
  active: string | null;
  modes: Record<string, FileViewMode>;
  base: string | null;
}

// 有未保存的修改就按应用存一份，没有就删掉
useCode.subscribe((s, prev) => {
  if (!s.appId || (s.edits === prev.edits && s.created === prev.created && s.open === prev.open && s.active === prev.active)) return;
  const dirty = Object.keys(s.edits).length > 0;
  saveDraft('code.' + s.appId, dirty ? { edits: s.edits, created: s.created, open: s.open, active: s.active, modes: s.modes, base: s.base } satisfies CodeDraft : null);
});

let saveKey: { sig: string; key: string } | null = null;

/** 提交当前所有代码修改，返回 code_save 任务（没有修改时返回 null）。失败抛出服务端说明并保留修改。 */
export async function submitCodeEdits(appId: string, baseVersionId: string): Promise<Task | null> {
  const st = useCode.getState();
  const files = Object.entries(st.edits).map(([path, content]) => ({ path, content }));
  if (!files.length) return null;
  const sig = JSON.stringify(files);
  if (saveKey?.sig !== sig) saveKey = { sig, key: newIdempotencyKey() };
  st.setError('');
  try {
    const task = await api.saveCode(appId, { baseVersionId, files }, saveKey.key);
    saveKey = null;
    useCode.getState().setSaving(task.id);
    return task;
  } catch (err) {
    useCode.getState().setError(err instanceof Error ? err.message : '代码修改没有保存成功。');
    throw err;
  }
}
