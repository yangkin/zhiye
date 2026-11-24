import { create } from 'zustand';
import type { Asset, ElementContext } from '../api/types';
import { loadDraft, saveDraft } from './drafts';

interface ToastItem {
  id: number;
  message: string;
  tone: 'ok' | 'error';
  /** 可选的一个操作，比如「撤销」 */
  action?: { label: string; run: () => void };
}

interface UiState {
  toasts: ToastItem[];
  importOpen: boolean;
  /** 数据源管理弹窗 */
  sourcesOpen: boolean;
  setSourcesOpen: (open: boolean) => void;
  helpOpen: boolean;
  helpTab: 'docs' | 'chat';
  setHelpOpen: (open: boolean) => void;
  setHelpTab: (tab: 'docs' | 'chat') => void;
  openHelp: (tab?: 'docs' | 'chat') => void;
  setImportOpen: (open: boolean) => void;
}

let toastSeq = 0;

export const useUi = create<UiState>((set) => ({
  toasts: [],
  importOpen: false,
  sourcesOpen: false,
  setSourcesOpen: (sourcesOpen) => set({ sourcesOpen }),
  helpOpen: false,
  helpTab: 'docs',
  setHelpOpen: (helpOpen) => set({ helpOpen }),
  setHelpTab: (helpTab) => set({ helpTab }),
  openHelp: (tab) => set((s) => ({ helpOpen: true, helpTab: tab ?? s.helpTab })),
  setImportOpen: (importOpen) => set({ importOpen }),
}));

export function toast(message: string, tone: 'ok' | 'error' = 'ok', action?: ToastItem['action']) {
  const id = ++toastSeq;
  useUi.setState((s) => ({ toasts: [...s.toasts.slice(-3), { id, message, tone, ...(action ? { action } : {}) }] }));
  // 带操作的多留一会儿，来得及点
  setTimeout(() => dismissToast(id), action ? 7000 : 3600);
}

export function dismissToast(id: number) {
  useUi.setState((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }));
}

/** 工作台左侧对话输入：草稿与元素上下文。上下文必须属于当前应用。 */
interface ChatState {
  appId: string | null;
  draft: string;
  elementContext: ElementContext | null;
  focusTick: number;
  /** 本次修改附带的素材与技能（随消息发送后清空） */
  assets: Asset[];
  skills: { id: string; name: string }[];
  setAssets: (assets: Asset[]) => void;
  setSkills: (skills: { id: string; name: string }[]) => void;
  /** 发送时一起保存的手动修改数量：key 为消息 id 或任务 id */
  draftNotes: Record<string, number>;
  addDraftNote: (keys: string[], n: number) => void;
  /** 输入框下方的一次性提示（如「页面已更新，请重新选择元素」） */
  hint: string;
  setHint: (hint: string) => void;
  resetFor: (appId: string | null) => void;
  setDraft: (draft: string) => void;
  setElementContext: (ctx: ElementContext | null) => void;
  requestFocus: () => void;
  /** 点了「共享云服务」：在输入框上方弹确认卡 */
  cloudAsk: boolean;
  setCloudAsk: (v: boolean) => void;
}

export const useChat = create<ChatState>((set, get) => ({
  appId: null,
  draft: '',
  elementContext: null,
  focusTick: 0,
  assets: [],
  skills: [],
  setAssets: (assets) => set({ assets }),
  setSkills: (skills) => set({ skills }),
  draftNotes: {},
  addDraftNote: (keys, n) => set((s) => ({ draftNotes: { ...s.draftNotes, ...Object.fromEntries(keys.map((k) => [k, n])) } })),
  hint: '',
  setHint: (hint) => set({ hint }),
  resetFor: (appId) => {
    if (get().appId !== appId)
      set({ appId, draft: (appId && loadDraft<string>('chat.' + appId)) || '', elementContext: null, assets: [], skills: [], hint: '', cloudAsk: false });
  },
  setDraft: (draft) => {
    set({ draft });
    const appId = get().appId;
    if (appId) saveDraft('chat.' + appId, draft.trim() ? draft : null);
  },
  setElementContext: (elementContext) => set({ elementContext }),
  requestFocus: () => set((s) => ({ focusTick: s.focusTick + 1 })),
  cloudAsk: false,
  setCloudAsk: (cloudAsk) => set({ cloudAsk }),
}));
