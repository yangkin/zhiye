import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type AppListQuery, type HistoryQuery } from './client';

export const qk = {
  meta: ['meta'] as const,
  workspace: ['workspace'] as const,
  appsSummary: ['apps', 'summary'] as const,
  activeTask: ['activeTask'] as const,
  appStats: (id: string, range: string) => ['appStats', id, range] as const,
  apps: (q?: AppListQuery) => (q ? (['apps', q] as const) : (['apps'] as const)),
  app: (id: string) => ['app', id] as const,
  messages: (id: string) => ['messages', id] as const,
  versions: (id: string) => ['versions', id] as const,
  appTasks: (id: string) => ['appTasks', id] as const,
  history: (q?: HistoryQuery) => (q ? (['history', q] as const) : (['history'] as const)),
  files: (versionId: string) => ['files', versionId] as const,
  file: (versionId: string, path: string) => ['file', versionId, path] as const,
  changes: (versionId: string) => ['changes', versionId] as const,
  changeFile: (versionId: string, path: string) => ['changeFile', versionId, path] as const,
  draft: (appId: string) => ['draft', appId] as const,
  assets: ['assets'] as const,
  sources: ['sources'] as const,
  appSources: (appId: string) => ['appSources', appId] as const,
  skillhubCategories: ['skillhub', 'categories'] as const,
  skillhubMarket: (q: string, categoryId: string, page: number) => ['skillhub', 'market', q, categoryId, page] as const,
};

export const useMeta = () => useQuery({ queryKey: qk.meta, queryFn: api.meta, staleTime: 5 * 60_000 });
export const useApps = (q: AppListQuery = {}) => useQuery({ queryKey: qk.apps(q), queryFn: () => api.listApps(q) });
export const useApp = (id: string | undefined) =>
  useQuery({ queryKey: qk.app(id ?? ''), queryFn: () => api.getApp(id!), enabled: !!id });
export const useMessages = (id: string | undefined, enabled = true) =>
  useQuery({ queryKey: qk.messages(id ?? ''), queryFn: () => api.messages(id!), enabled: !!id && enabled });
export const useVersions = (id: string | undefined, enabled = true) =>
  useQuery({ queryKey: qk.versions(id ?? ''), queryFn: () => api.versions(id!), enabled: !!id && enabled });
export const useHistory = (q: HistoryQuery) => useQuery({ queryKey: qk.history(q), queryFn: () => api.history(q) });

/** 任务结束或写操作后，刷新与某个应用有关的全部服务器状态。 */
export function useInvalidateApp() {
  const qc = useQueryClient();
  return (appId: string) => {
    void qc.invalidateQueries({ queryKey: qk.app(appId) });
    void qc.invalidateQueries({ queryKey: qk.messages(appId) });
    void qc.invalidateQueries({ queryKey: qk.versions(appId) });
    void qc.invalidateQueries({ queryKey: qk.appTasks(appId) });
    void qc.invalidateQueries({ queryKey: qk.draft(appId) });
    void qc.invalidateQueries({ queryKey: ['queued', appId] });
    void qc.invalidateQueries({ queryKey: ['cloud', appId] });
    void qc.invalidateQueries({ queryKey: ['apps'] });
    void qc.invalidateQueries({ queryKey: ['history'] });
    void qc.invalidateQueries({ queryKey: qk.activeTask });
  };
}

/** 当前用户进行中的任务（任一应用）。interval 毫秒；0 表示不轮询。404 视为接口未上线 → null。 */
export const useActiveTask = (interval = 5000) =>
  useQuery({
    queryKey: qk.activeTask,
    queryFn: async () => {
      try {
        return (await api.activeTask()) ?? null;
      } catch (e) {
        if ((e as { status?: number }).status === 404) return null;
        throw e;
      }
    },
    refetchInterval: interval || false,
    refetchOnWindowFocus: true,
    staleTime: 2000,
  });
