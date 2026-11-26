import { lazy } from 'react';

const loaders = {
  apps: () => import('../pages/AppsPage').then((m) => ({ default: m.AppsPage })),
  ecosystem: () => import('../pages/EcosystemPage').then((m) => ({ default: m.EcosystemPage })),
  chat: () => import('../pages/ChatPage').then((m) => ({ default: m.ChatPage })),
  history: () => import('../pages/HistoryPage').then((m) => ({ default: m.HistoryPage })),
  community: () => import('../pages/CommunityPage').then((m) => ({ default: m.CommunityPage })),
  work: () => import('../pages/WorkPage').then((m) => ({ default: m.WorkPage })),
  dashboard: () => import('../pages/DashboardPage').then((m) => ({ default: m.DashboardPage })),
  admin: () => import('../pages/AdminPage').then((m) => ({ default: m.AdminPage })),
  logs: () => import('../pages/LogsPage').then((m) => ({ default: m.LogsPage })),
  workbench: () => import('../workbench/Workbench').then((m) => ({ default: m.WorkbenchRoute })),
} as const;

export type PreloadableRoute = keyof typeof loaders;

export const AppsPage = lazy(loaders.apps);
export const EcosystemPage = lazy(loaders.ecosystem);
export const ChatPage = lazy(loaders.chat);
export const HistoryPage = lazy(loaders.history);
export const CommunityPage = lazy(loaders.community);
export const WorkPage = lazy(loaders.work);
export const DashboardPage = lazy(loaders.dashboard);
export const AdminPage = lazy(loaders.admin);
export const LogsPage = lazy(loaders.logs);
export const WorkbenchRoute = lazy(loaders.workbench);

const preloaded = new Set<string>();

/** 悬停或聚焦导航菜单时预加载对应路由分包，切换菜单零白屏、零闪烁 */
export function preloadRoute(key: string) {
  if (preloaded.has(key)) return;
  const loader = loaders[key as PreloadableRoute];
  if (!loader) return;
  preloaded.add(key);
  void loader().catch(() => {
    preloaded.delete(key);
  });
}
