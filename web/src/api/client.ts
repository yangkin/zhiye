import { clearPlatformToken, getPlatformToken } from '../lib/platformToken';
import type {
  AdminFeedbackStatus,
  AdminOverview,
  ClusterOverviewResponse,
  TemplateItem,
  TemplateDetail,
  TemplateQuery,
  DataSourceCall,
  DataSourceInfo,
  DataSourceInput,
  AgwApiPage,
  FileChange,
  ActiveTaskInfo,
  SkillVisibility,
  SkillhubPublication,
  SkillHubCategory,
  SkillHubInstall,
  SkillHubMarket,
  AppStats,
  AppsSummary,
  DashboardData,
  DashboardRange,
  DashboardScope,
  OpsFunctionLogItem,
  OpsLogFileView,
  OpsLogRange,
  OpsPlatformEventsResponse,
  OpsRuntimeResponse,
  OpsSandboxLogsResponse,
  OpsSchemasResponse,
  OpsSourceCallItem,
  OpsTaskDetailResponse,
  OpsTaskEventsDeltaResponse,
  OpsTasksResponse,
  SandboxInventorySummary,
  SandboxItem,
  StatsRange,
  AppDetail,
  AppSummary,
  AppType,
  Asset,
  Draft,
  ElementContext,
  FileEntry,
  HistoryRow,
  ImportManifest,
  Message,
  Meta,
  Mode,
  Patch,
  QueuedMessage,
  ConversationSummary,
  ConversationDetail,
  StyleId,
  Task,
  Version,
  Workspace,
  LlmModels,
  LlmQuota,
} from './types';

export class ApiError extends Error {
  code: string;
  status: number;
  constructor(message: string, code: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.status = status;
  }
}

export const newIdempotencyKey = (): string =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  form?: FormData;
  idempotencyKey?: string;
  signal?: AbortSignal;
  text?: boolean;
}

const FALLBACK_MESSAGES: Record<number, string> = {
  0: '网络连接中断，请检查网络后重试。',
  404: '没有找到对应的内容。',
  413: '文件太大了，请压缩后重试。',
  429: '操作太频繁，请稍后再试。',
  500: '服务暂时出了点问题，请稍后重试。',
  502: '服务暂时不可用，请稍后重试。',
  503: '服务暂时不可用，请稍后重试。',
};

/**
 * 登录失效的统一处理（只有 sso 模式会出现这两种 401）：
 * - UNAUTHORIZED：织页会话过期 → 整页跳登录页（整页才会重新读 /api/meta，缓存里的用户信息作废），带「登录已过期」提示；
 * - PLATFORM_TOKEN_INVALID：织页会话还在、平台令牌过期 → 直接重走统一认证换新令牌。
 * 草稿都在 localStorage，重新登录回来还在。同时有多个请求 401 时只跳一次；登录页本身不跳，不会循环。
 */
let authRedirecting = false;
function onAuthExpired(code: string) {
  if (typeof window === 'undefined') return;
  const { pathname, search } = window.location;
  if (pathname.startsWith('/login') || pathname.startsWith('/access_token')) return;
  if (authRedirecting) return;
  authRedirecting = true;
  try {
    sessionStorage.setItem('zhiye-login-redirect', pathname + search);
  } catch {
    /* sessionStorage 不可用时照样跳转 */
  }
  const redirect = encodeURIComponent(pathname + search);
  if (code === 'PLATFORM_TOKEN_INVALID') clearPlatformToken();
  const target = code === 'PLATFORM_TOKEN_INVALID' ? `/api/auth/sso/login?redirect=${redirect}` : `/login?error=expired&redirect=${redirect}`;
  // 页面同时在用 history.replaceState 改地址栏（筛选条件写进 URL）时，浏览器会取消还没完成的整页跳转：放到下一轮，没走成就再跳一次
  let tries = 0;
  const go = () => {
    if (window.location.pathname.startsWith('/login') || tries++ >= 3) return;
    window.location.replace(target);
    window.setTimeout(go, 1200);
  };
  window.setTimeout(go, 0);
}

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const method = opts.method ?? 'GET';
  const headers: Record<string, string> = { Accept: opts.text ? 'text/plain' : 'application/json' };
  let body: BodyInit | undefined;
  if (opts.form) {
    body = opts.form;
  } else if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }
  if (method === 'POST') headers['Idempotency-Key'] = opts.idempotencyKey ?? newIdempotencyKey();
  const platformToken = path.startsWith('/api/') ? getPlatformToken() : null;
  if (platformToken) headers.Authorization = `Bearer ${platformToken}`;
  let res: Response;
  try {
    res = await fetch(path, { method, headers, body, credentials: 'include', signal: opts.signal });
  } catch (error) {
    if ((error as Error)?.name === 'AbortError') throw error;
    throw new ApiError(FALLBACK_MESSAGES[0], 'NETWORK', 0);
  }
  if (!res.ok) {
    let code = 'HTTP_' + res.status;
    let message = FALLBACK_MESSAGES[res.status] ?? `请求未完成（${res.status}），请稍后重试。`;
    try {
      const data = await res.json();
      if (data && typeof data.message === 'string') message = data.message;
      if (data && typeof data.code === 'string') code = data.code;
    } catch {
      /* 非 JSON 错误体，使用默认说明 */
    }
    if (res.status === 401 && (code === 'UNAUTHORIZED' || code === 'PLATFORM_TOKEN_INVALID')) onAuthExpired(code);
    throw new ApiError(message, code, res.status);
  }
  if (res.status === 204) return undefined as T;
  if (opts.text) return (await res.text()) as T;
  const raw = await res.text();
  return (raw ? JSON.parse(raw) : undefined) as T;
}

const qs = (params: Record<string, string | number | boolean | undefined | null>) => {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    s.set(k, String(v));
  }
  const str = s.toString();
  return str ? '?' + str : '';
};

const enc = encodeURIComponent;

export type AccessLevel = 'public' | 'login' | 'members';
export interface CloudPreset { id: string; label: string; needsLogin: boolean }
export interface SiteAccess {
  level: AccessLevel;
  members: string[];
  admins: string[];
  ssoAvailable: boolean;
  author: { id: string; loginName: string; realName: string; email?: string } | null;
  tablesNeedingLogin: string[];
  presets: CloudPreset[];
}
export interface CloudTableInfo { name: string; description?: string; columns: Record<string, string>; policy: { preset?: string; fields?: Record<string, unknown> } | null }
export interface ConsoleOverview { tables: CloudTableInfo[]; counts: Record<string, number>; presets: CloudPreset[]; access: AccessLevel }
export type CloudRow = Record<string, unknown> & { id: number };

export interface AppListQuery {
  q?: string;
  type?: AppType;
  favorite?: boolean;
  published?: 'all' | 'published' | 'unpublished';
  sort?: 'updated' | 'created' | 'name';
}

export interface HistoryQuery {
  q?: string;
  appId?: string;
  filter?: 'all' | 'favorite' | 'published';
}

/** 创造页的「问一句」：流式读 SSE（delta / done / error），不创建应用。 */
/** 回答流没收到结束事件就断开了 */
export const ASK_INTERRUPTED = '回答中断了，没有收到完整内容，请重试。';

async function askStream(
  url: string,
  input: { content: string; skillAppIds?: string[]; assetIds?: string[]; appId?: string; history?: { role: string; content: string }[] },
  onDelta: (text: string) => void,
  signal?: AbortSignal,
  handlers?: {
    onThinking?: (text: string) => void;
    onPlanUpdating?: () => void;
    onPlanUpdated?: (app: AppDetail) => void;
  },
): Promise<void> {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(input),
    signal,
    credentials: 'same-origin',
  });
  if (!res.ok || !res.body) {
    let message = FALLBACK_MESSAGES[res.status] ?? '暂时没能回答，请稍后再试。';
    try {
      const j = await res.json();
      if (j?.message) message = j.message;
    } catch { /* 非 JSON 错误体 */ }
    throw new ApiError(message, 'ASK_FAILED', res.status);
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  let event = '';
  let finished = false;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buf += decoder.decode(value, { stream: true });
    let idx: number;
    while ((idx = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, idx).replace(/\r$/, '');
      buf = buf.slice(idx + 1);
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) {
        const raw = line.slice(5).trim();
        if (!raw) continue;
        let data: { text?: string; message?: string } & Partial<AppDetail> = {};
        try { data = JSON.parse(raw); } catch { continue; }
        if (event === 'delta' && data.text) onDelta(data.text);
        else if (event === 'thinking' && data.text) handlers?.onThinking?.(data.text);
        else if (event === 'plan_updating') handlers?.onPlanUpdating?.();
        else if (event === 'plan_updated' && data.id) handlers?.onPlanUpdated?.(data as AppDetail);
        else if (event === 'done') finished = true;
        else if (event === 'error' && data.message) throw new ApiError(data.message, 'ASK_FAILED', 502);
      }
    }
  }
  // 连接断开但没收到 done：已收到的只是半截回答，不能当成完整结果
  if (!finished) throw new ApiError(ASK_INTERRUPTED, 'ASK_INTERRUPTED', 0);
}

export const api = {
  // 工作空间与元信息
  meta: () => request<Meta>('/api/meta'),
  cloudStatus: (appId: string) => request<{ enabled: boolean; status: string; schema: string | null; tables: { name: string; columns: Record<string, string>; policy: unknown }[]; storagePrefix: string | null }>(`/api/apps/${enc(appId)}/cloud`),
  cloudEnable: (appId: string) => request<{ enabled: boolean; token: string; tables: { name: string; columns: Record<string, string> }[] }>(`/api/apps/${enc(appId)}/cloud/enable`, { method: 'POST' }),
  cloudDisable: (appId: string) => request<{ status: string }>(`/api/apps/${enc(appId)}/cloud/disable`, { method: 'POST' }),
  cloudToken: (appId: string) => request<{ token: string; expiresIn: number }>(`/api/apps/${enc(appId)}/cloud/token`, { method: 'POST' }),
  // 发布访问设置与数据控制台
  access: (appId: string) => request<SiteAccess>(`/api/apps/${enc(appId)}/access`),
  saveAccess: (appId: string, body: { level: AccessLevel; members: string[]; admins: string[] }) =>
    request<SiteAccess>(`/api/apps/${enc(appId)}/access`, { method: 'PUT', body }),
  consoleOverview: (appId: string) => request<ConsoleOverview>(`/api/apps/${enc(appId)}/cloud/console`),
  consoleRows: (appId: string, table: string, params: Record<string, string>) =>
    request<{ rows: CloudRow[]; total: number }>(`/api/apps/${enc(appId)}/cloud/console/${enc(table)}/rows${qs(params)}`),
  consoleInsert: (appId: string, table: string, row: Record<string, unknown>) =>
    request<CloudRow>(`/api/apps/${enc(appId)}/cloud/console/${enc(table)}/rows`, { method: 'POST', body: row }),
  consoleUpdate: (appId: string, table: string, id: number, row: Record<string, unknown>) =>
    request<CloudRow>(`/api/apps/${enc(appId)}/cloud/console/${enc(table)}/rows/${id}`, { method: 'PATCH', body: row }),
  consoleDelete: (appId: string, table: string, id: number) =>
    request<void>(`/api/apps/${enc(appId)}/cloud/console/${enc(table)}/rows/${id}`, { method: 'DELETE' }),
  consoleImport: (appId: string, table: string, rows: Record<string, unknown>[]) =>
    request<{ inserted: number }>(`/api/apps/${enc(appId)}/cloud/console/${enc(table)}/import`, { method: 'POST', body: { rows } }),
  consoleExportUrl: (appId: string, table: string) => `/api/apps/${enc(appId)}/cloud/console/${enc(table)}/export`,
  consoleClear: (appId: string, table: string) =>
    request<{ deleted: number }>(`/api/apps/${enc(appId)}/cloud/console/${enc(table)}/clear`, { method: 'POST' }),
  consolePreset: (appId: string, table: string, preset: string) =>
    request<ConsoleOverview>(`/api/apps/${enc(appId)}/cloud/console/${enc(table)}/preset`, { method: 'PUT', body: { preset } }),
  consoleFunctions: (appId: string) =>
    request<{
      available: boolean;
      functions: { name: string; calls: number; errors: number }[];
      logs: { name: string; ok: boolean; durationMs: number; error: string | null; logs: string | null; caller: string | null; at: string }[];
    }>(`/api/apps/${enc(appId)}/cloud/console/functions`),
  consoleFiles: (appId: string) => request<{ files: string[] }>(`/api/apps/${enc(appId)}/cloud/console/files`),
  consoleFileUrl: (appId: string, path: string) => `/api/apps/${enc(appId)}/cloud/console/files/download${qs({ path })}`,
  consoleDeleteFile: (appId: string, path: string) =>
    request<void>(`/api/apps/${enc(appId)}/cloud/console/files${qs({ path })}`, { method: 'DELETE' }),
  appConversation: (appId: string) =>
    request<{ id: string; title: string; messages: { id: string; role: 'user' | 'assistant'; content: string; createdAt: string }[] } | null>(
      `/api/apps/${enc(appId)}/conversation`,
    ),
  ask: (
    input: { content: string; skillAppIds?: string[]; assetIds?: string[]; appId?: string; history?: { role: string; content: string }[] },
    onDelta: (text: string) => void,
    signal?: AbortSignal,
    onThinking?: (text: string) => void,
  ) => askStream('/api/chat/ask', input, onDelta, signal, onThinking ? { onThinking } : undefined),
  askConversation: (
    id: string,
    input: { content: string; skillAppIds?: string[]; assetIds?: string[]; history?: { role: string; content: string }[] },
    onDelta: (text: string) => void,
    signal?: AbortSignal,
  ) => askStream(`/api/conversations/${enc(id)}/ask`, input, onDelta, signal),
  createConversation: () => request<ConversationSummary>('/api/conversations', { method: 'POST', body: {} }),
  conversations: () => request<ConversationSummary[]>('/api/conversations'),
  conversation: (id: string) => request<ConversationDetail>(`/api/conversations/${enc(id)}`),
  deleteConversation: (id: string) => request<{ ok: boolean }>(`/api/conversations/${enc(id)}`, { method: 'DELETE' }),
  buildConversation: (
    id: string,
    input: { type: AppType; mode: Mode; model: string; assetIds?: string[]; skillAppIds?: string[]; sourceIds?: string[] },
    idempotencyKey?: string,
  ) => request<AppDetail>(`/api/conversations/${enc(id)}/build`, { method: 'POST', body: input, idempotencyKey }),
  /** new：和当前应用无关的另一个应用（一个对话只对应一个应用） */
  intent: (content: string, hasElement: boolean, app?: { name: string; prompt: string }) =>
    request<{ intent: 'ask' | 'modify' | 'new' }>('/api/chat/intent', {
      method: 'POST',
      body: { content, hasElement, ...(app ? { appName: app.name, appBrief: app.prompt.slice(0, 300) } : {}) },
    }),
  polish: (content: string) => request<{ text: string }>('/api/chat/polish', { method: 'POST', body: { content } }),
  historySummary: () =>
    request<{ versions: number; apps: number; published: number; cloudEnabled: number; pageViews: number }>('/api/history/summary'),
  trashApps: () => request<AppSummary[]>('/api/apps/trash'),
  workspace: () => request<Workspace>('/api/workspace'),
  recover: (token: string) => request<{ id: string }>('/api/workspace/recover', { method: 'POST', body: { token } }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }).finally(clearPlatformToken),
  /** 五星AI岛：我能用的模型（含「五星默认」）和个人额度 */
  llmModels: () => request<LlmModels>('/api/llm/models'),
  llmQuota: () => request<LlmQuota>('/api/llm/quota'),

  // 数据源
  sources: () => request<DataSourceInfo[]>('/api/sources'),
  agwApis: (input: { q?: string; page?: number; size?: number } = {}) =>
    request<AgwApiPage>('/api/sources/agw/apis' + qs(input)),
  importAgwApi: (apiId: string, input: { appKey: string; appSecret: string }) =>
    request<DataSourceInfo>(`/api/sources/agw/apis/${enc(apiId)}/import`, { method: 'POST', body: input }),
  createSource: (input: DataSourceInput) => request<DataSourceInfo>('/api/sources', { method: 'POST', body: input }),
  updateSource: (id: string, input: Partial<DataSourceInput>) => request<DataSourceInfo>(`/api/sources/${enc(id)}`, { method: 'PATCH', body: input }),
  deleteSource: (id: string) => request<void>(`/api/sources/${enc(id)}`, { method: 'DELETE' }),
  testSource: (id: string, params: Record<string, unknown>) =>
    request<{ data: unknown; status: number }>(`/api/sources/${enc(id)}/test`, { method: 'POST', body: { params } }),
  sourceCalls: (id: string) => request<DataSourceCall[]>(`/api/sources/${enc(id)}/calls`),
  appSources: (appId: string) => request<DataSourceInfo[]>(`/api/apps/${enc(appId)}/sources`),
  bindSource: (appId: string, sourceId: string) =>
    request<DataSourceInfo[]>(`/api/apps/${enc(appId)}/sources`, { method: 'POST', body: { sourceId } }),
  unbindSource: (appId: string, sourceId: string) =>
    request<DataSourceInfo[]>(`/api/apps/${enc(appId)}/sources/${enc(sourceId)}`, { method: 'DELETE' }),

  // 应用
  listApps: (q: AppListQuery = {}) =>
    request<AppSummary[]>('/api/apps' + qs({ ...q, favorite: q.favorite ? true : undefined })),
  createApp: (
    input: { type: AppType; prompt: string; mode: Mode; model: string; assetIds: string[]; skillAppIds?: string[]; sourceIds?: string[] },
    idempotencyKey?: string,
  ) => request<AppDetail>('/api/apps', { method: 'POST', body: input, idempotencyKey }),
  appsSummary: () => request<AppsSummary>('/api/apps/summary'),
  appStats: (id: string, range: StatsRange) => request<AppStats>(`/api/apps/${enc(id)}/stats` + qs({ range })),
  dashboard: (opts: {
    range?: DashboardRange;
    scope?: DashboardScope;
    startDate?: string;
    endDate?: string;
    workspaceId?: string;
    userId?: string;
  } = {}) => request<DashboardData>('/api/dashboard' + qs(opts)),
  opsTasks: (opts: {
    range?: OpsLogRange;
    startDate?: string;
    endDate?: string;
    status?: string;
    type?: string;
    workspaceId?: string;
    userId?: string;
    errorCode?: string;
    q?: string;
    limit?: number;
    offset?: number;
  } = {}) => request<OpsTasksResponse>('/api/logs/tasks' + qs(opts)),
  opsTaskDetail: (id: string, tailLines = 800) =>
    request<OpsTaskDetailResponse>(`/api/logs/tasks/${enc(id)}` + qs({ tailLines })),
  opsTaskEvents: (id: string, after = 0) =>
    request<OpsTaskEventsDeltaResponse>(`/api/logs/tasks/${enc(id)}/events` + qs({ after })),
  opsRunnerLog: (id: string, opts: { tail?: number; offset?: number; q?: string; level?: string } = {}) =>
    request<OpsLogFileView>(`/api/logs/tasks/${enc(id)}/runner-log` + qs(opts)),
  opsRunnerLogDownloadUrl: (id: string) => `/api/logs/tasks/${enc(id)}/runner-log/download`,
  opsFunctionLogs: (opts: { q?: string; appId?: string; ok?: boolean; range?: OpsLogRange; limit?: number; offset?: number } = {}) =>
    request<{ range: OpsLogRange; total: number; errorCount: number; items: OpsFunctionLogItem[]; limit: number; offset: number }>(
      '/api/logs/functions' + qs(opts),
    ),
  opsSourceCalls: (opts: { q?: string; appId?: string; ok?: boolean; origin?: string; range?: OpsLogRange; limit?: number; offset?: number } = {}) =>
    request<{ range: OpsLogRange; total: number; errorCount: number; items: OpsSourceCallItem[]; limit: number; offset: number }>(
      '/api/logs/sources' + qs(opts),
    ),
  opsEvents: (opts: {
    category?: string;
    level?: string;
    appId?: string;
    taskId?: string;
    ok?: boolean;
    q?: string;
    range?: OpsLogRange;
    limit?: number;
    offset?: number;
  } = {}) => request<OpsPlatformEventsResponse>('/api/logs/events' + qs(opts)),
  opsSchemas: (opts: { q?: string; ok?: boolean; range?: OpsLogRange; limit?: number; offset?: number } = {}) =>
    request<OpsSchemasResponse>('/api/logs/schemas' + qs(opts)),
  opsSandboxes: (opts: {
    range?: OpsLogRange;
    state?: string;
    provider?: string;
    workspaceId?: string;
    onlyErrors?: boolean;
    q?: string;
    limit?: number;
    offset?: number;
  } = {}) => request<OpsSandboxLogsResponse>('/api/logs/sandboxes' + qs(opts)),
  opsRuntime: (opts: { target?: 'api' | 'site' | 'web-build'; tail?: number; q?: string; level?: string } = {}) =>
    request<OpsRuntimeResponse>('/api/logs/runtime' + qs(opts)),
  getApp: (id: string) => request<AppDetail>(`/api/apps/${enc(id)}`),
  patchApp: (
    id: string,
    patch: { name?: string; favorite?: boolean; planDoc?: string; style?: StyleId; model?: string; mode?: Mode; skillAppIds?: string[] },
  ) => request<AppDetail>(`/api/apps/${enc(id)}`, { method: 'PATCH', body: patch }),
  deleteApp: (id: string) => request<void>(`/api/apps/${enc(id)}`, { method: 'DELETE' }),
  templates: (q: TemplateQuery = {}) => {
    const p = new URLSearchParams();
    for (const [k, v] of Object.entries(q)) if (v) p.set(k, String(v));
    return request<TemplateItem[]>('/api/templates' + (p.size ? '?' + p : ''));
  },
  template: (id: string) => request<TemplateDetail>(`/api/templates/${enc(id)}`),
  templateCreate: (appId: string, name?: string, description?: string) =>
    request<TemplateItem>('/api/templates', { method: 'POST', body: { appId, name, description } }),
  templateClone: (id: string, name?: string) =>
    request<{ app: AppSummary }>(`/api/templates/${enc(id)}/clone`, { method: 'POST', body: name ? { name } : {} }),
  templateLike: (id: string, on: boolean) =>
    request<{ liked: boolean; likeCount: number }>(`/api/templates/${enc(id)}/like`, { method: on ? 'POST' : 'DELETE' }),
  templateRemove: (id: string) => request<void>(`/api/templates/${enc(id)}`, { method: 'DELETE' }),
  generate: (id: string, idempotencyKey?: string) =>
    request<Task>(`/api/apps/${enc(id)}/generate`, { method: 'POST', idempotencyKey }),
  messages: (id: string) => request<Message[]>(`/api/apps/${enc(id)}/messages`),
  sendMessage: (
    id: string,
    input: { content: string; elementContext?: ElementContext; assetIds?: string[]; skillAppIds?: string[] },
    idempotencyKey?: string,
  ) =>
    request<{ message: Message; task: Task }>(`/api/apps/${enc(id)}/messages`, {
      method: 'POST',
      body: input,
      idempotencyKey,
    }),
  appTasks: (id: string, active?: boolean) =>
    request<Task[]>(`/api/apps/${enc(id)}/tasks` + qs({ active: active ? true : undefined })),
  listQueued: (appId: string) => request<QueuedMessage[]>(`/api/apps/${enc(appId)}/queued`),
  enqueueQueued: (taskId: string, content: string, opts?: { enableCloud?: boolean }) =>
    request<QueuedMessage>(`/api/tasks/${enc(taskId)}/queued`, { method: 'POST', body: { content, ...(opts?.enableCloud ? { enableCloud: true } : {}) } }),
  cancelQueued: (id: string) => request<void>(`/api/queued/${enc(id)}`, { method: 'DELETE' }),
  planFeedback: (appId: string, content: string) =>
    request<AppDetail>(`/api/apps/${enc(appId)}/plan-feedback`, { method: 'POST', body: { content } }),
  planChat: (
    appId: string,
    input: { content: string; skillAppIds?: string[]; assetIds?: string[]; history?: { role: string; content: string }[] },
    handlers: {
      onDelta: (text: string) => void;
      onThinking?: (text: string) => void;
      onPlanUpdating?: () => void;
      onPlanUpdated?: (app: AppDetail) => void;
    },
    signal?: AbortSignal,
  ) =>
    askStream(`/api/apps/${enc(appId)}/plan-chat`, input, handlers.onDelta, signal, {
      onThinking: handlers.onThinking,
      onPlanUpdating: handlers.onPlanUpdating,
      onPlanUpdated: handlers.onPlanUpdated,
    }),
  stopPlanChat: (appId: string) =>
    request<{ stopped: boolean; finished: boolean }>(`/api/apps/${enc(appId)}/plan-chat/stop`, { method: 'POST' }),

  // 任务
  /** text：「继续」确认时用户顺便说的话（可选） */
  confirmTask: (id: string, confirmId: string, accept: boolean, text?: string) =>
    request<{ ok: boolean; accept: boolean }>(`/api/tasks/${enc(id)}/confirm`, { method: 'POST', body: { confirmId, accept, ...(text ? { text } : {}) } }),
  steerTask: (id: string, text: string) => request<{ message: Message; task: Task }>(`/api/tasks/${enc(id)}/steer`, { method: 'POST', body: { text } }),
  taskEventDetail: (id: string, seq: number) =>
    request<{ seq: number; type: string; detail: unknown; durationMs?: number; detailTruncated?: boolean }>(`/api/tasks/${enc(id)}/events/${seq}/detail`),
  taskFiles: (id: string) => request<FileEntry[]>(`/api/tasks/${enc(id)}/files`),
  taskFile: (id: string, path: string) => request<string>(`/api/tasks/${enc(id)}/file` + qs({ path }), { text: true }),
  versionChanges: (appId: string, versionId: string) =>
    request<{ base: string | null; files: FileChange[]; totalAdded: number; totalRemoved: number }>(
      `/api/apps/${enc(appId)}/versions/${enc(versionId)}/changes`,
    ),
  versionChangeFile: (appId: string, versionId: string, path: string) =>
    request<{ path: string; status: string; diff: string; truncated: boolean }>(
      `/api/apps/${enc(appId)}/versions/${enc(versionId)}/changes/file` + qs({ path }),
    ),
  activeTask: () => request<ActiveTaskInfo | null>('/api/tasks/active'),
  getTask: (id: string) => request<Task>(`/api/tasks/${enc(id)}`),
  taskEventsUrl: (id: string, after?: number) => `/api/tasks/${enc(id)}/events` + qs({ after: after ? String(after) : undefined }),
  cancelTask: (id: string) => request<Task>(`/api/tasks/${enc(id)}/cancel`, { method: 'POST' }),
  resumeTask: (id: string) => request<Task>(`/api/tasks/${enc(id)}/resume`, { method: 'POST' }),
  retryTask: (id: string) => request<Task>(`/api/tasks/${enc(id)}/retry`, { method: 'POST' }),

  // 版本与历史
  versions: (appId: string) => request<Version[]>(`/api/apps/${enc(appId)}/versions`),
  history: (q: HistoryQuery = {}) => request<HistoryRow[]>('/api/history' + qs({ ...q })),
  patchVersion: (id: string, patch: { favorite?: boolean; note?: string | null }) =>
    request<Version>(`/api/versions/${enc(id)}`, { method: 'PATCH', body: patch }),
  restoreVersion: (id: string) => request<Version>(`/api/versions/${enc(id)}/restore`, { method: 'POST' }),
  versionFiles: (id: string) => request<FileEntry[]>(`/api/versions/${enc(id)}/files`),
  versionFile: (id: string, path: string) =>
    request<string>(`/api/versions/${enc(id)}/file` + qs({ path }), { text: true }),
  exportUrl: (id: string) => `/api/versions/${enc(id)}/export`,
  pageUrl: (id: string) => `/api/versions/${enc(id)}/page`,

  // 草稿（没有草稿时后端返回 200 null，避免控制台 404 红字；兼容老版本 404）
  getDraft: async (appId: string): Promise<Draft | null> => {
    try {
      return (await request<Draft | null>(`/api/apps/${enc(appId)}/draft`)) ?? null;
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null;
      throw error;
    }
  },
  putDraft: (appId: string, draft: { baseVersionId: string; patches: Patch[] }) =>
    request<Draft>(`/api/apps/${enc(appId)}/draft`, { method: 'PUT', body: draft }),
  deleteDraft: (appId: string) => request<void>(`/api/apps/${enc(appId)}/draft`, { method: 'DELETE' }),
  commitDraft: (appId: string, title?: string) =>
    request<Task>(`/api/apps/${enc(appId)}/draft/commit`, { method: 'POST', body: title ? { title } : {} }),

  // 代码编辑
  saveCode: (
    appId: string,
    input: { baseVersionId: string; files: { path: string; content: string }[] },
    idempotencyKey?: string,
  ) => request<Task>(`/api/apps/${enc(appId)}/code`, { method: 'POST', body: input, idempotencyKey }),

  // SkillHub
  skillhubPublish: (
    appId: string,
    input: { versionId: string; displayName?: string; visibility: SkillVisibility; changelog: string },
    idempotencyKey?: string,
  ) => request<SkillhubPublication>(`/api/skillhub/apps/${enc(appId)}/publish`, { method: 'POST', body: input, idempotencyKey }),
  skillhubPublications: (appId: string) => request<SkillhubPublication[]>(`/api/skillhub/apps/${enc(appId)}/publications`),
  skillhubCategories: () => request<SkillHubCategory[]>('/api/skillhub/categories'),
  skillhubMarket: (input: { q?: string; categoryId?: string; page?: number; limit?: number } = {}) =>
    request<SkillHubMarket>('/api/skillhub/market' + qs(input)),
  skillhubInstall: (skillId: string, versionId?: string) =>
    request<SkillHubInstall>('/api/skillhub/installs', { method: 'POST', body: { skillId, versionId } }),

  // 发布
  publish: (appId: string, versionId?: string) =>
    request<{ siteUrl: string; versionId: string }>(`/api/apps/${enc(appId)}/publish`, {
      method: 'POST',
      body: versionId ? { versionId } : {},
    }),
  unpublish: (appId: string) => request<void>(`/api/apps/${enc(appId)}/unpublish`, { method: 'POST' }),

  // 导入
  uploadImport: (file: Blob, fileName: string, folderName?: string, signal?: AbortSignal) => {
    const form = new FormData();
    form.append('file', file, fileName);
    if (folderName) form.append('folderName', folderName);
    return request<ImportManifest>('/api/imports/upload', { method: 'POST', form, signal });
  },
  createImport: (input: { uploadId: string; name: string; type?: AppType; rootDir?: string }, idempotencyKey?: string) =>
    request<{ app: AppSummary; task: Task }>('/api/imports', { method: 'POST', body: input, idempotencyKey }),

  // 素材
  uploadAsset: (file: File) => {
    const form = new FormData();
    form.append('file', file, file.name);
    return request<Asset>('/api/assets', { method: 'POST', form });
  },
  assets: () => request<Asset[]>('/api/assets'),
  assetContentUrl: (id: string) => `/api/assets/${enc(id)}/content`,

  // 管理控制台治理与运维
  adminOverview: () => request<AdminOverview>('/api/admin/overview'),
  adminWorkspaceProfile: (wsId: string) =>
    request<import('./types').AdminWorkspaceProfile>(`/api/admin/workspaces/${enc(wsId)}/profile`),
  adminUpdateQuota: (
    wsId: string,
    body: {
      maxApps?: number | null;
      dailyTaskLimit?: number | null;
      maxConcurrentTasks?: number | null;
      note?: string;
      reset?: boolean;
    },
  ) =>
    request<{ workspaceId: string; maxApps: number; dailyTaskLimit: number; maxConcurrentTasks: number; custom: boolean; note: string | null }>(
      `/api/admin/workspaces/${enc(wsId)}/quota`,
      { method: 'PUT', body },
    ),
  adminUnpublishApp: (id: string) => request<{ id: string; name: string; published: boolean }>(`/api/admin/apps/${enc(id)}/unpublish`, { method: 'POST', body: {} }),
  adminRestoreApp: (id: string) => request<{ id: string; name: string; restored: boolean }>(`/api/admin/apps/${enc(id)}/restore`, { method: 'POST', body: {} }),
  adminDeleteApp: (id: string) => request<{ id: string; name: string; deleted: boolean }>(`/api/admin/apps/${enc(id)}`, { method: 'DELETE' }),
  adminPurgeApp: (id: string) => request<{ id: string; name: string; purged: boolean }>(`/api/admin/apps/${enc(id)}/purge`, { method: 'DELETE' }),
  adminUpdateSiteAccess: (id: string, level: 'public' | 'login' | 'members') =>
    request<{ id: string; name: string; accessLevel: string }>(`/api/admin/apps/${enc(id)}/access`, { method: 'PATCH', body: { level } }),
  adminTransferApp: (
    id: string,
    body: { targetWorkspaceId: string; includeConversation?: boolean; note?: string },
  ) =>
    request<{ id: string; name: string; fromWorkspaceId: string; toWorkspaceId: string; transferred: boolean }>(
      `/api/admin/apps/${enc(id)}/transfer`,
      { method: 'POST', body },
    ),
  adminHandoverWorkspace: (body: {
    fromWorkspaceId: string;
    toWorkspaceId: string;
    appIds?: string[];
    includeTrash?: boolean;
    includeConversations?: boolean;
    includeDataSources?: boolean;
    note?: string;
  }) =>
    request<{
      fromWorkspaceId: string;
      toWorkspaceId: string;
      transferredAppsCount: number;
      movedConversations: number;
      movedDataSources: number;
      appNames: string[];
    }>('/api/admin/workspaces/handover', { method: 'POST', body }),
  adminUpdateUserRole: (id: string, role: 'admin' | 'user') =>
    request<{ id: string; loginName: string; realName: string; role: string }>(`/api/admin/users/${enc(id)}/role`, { method: 'PATCH', body: { role } }),
  adminCancelTask: (id: string) => request<Task>(`/api/admin/tasks/${enc(id)}/cancel`, { method: 'POST', body: {} }),
  adminRetryTask: (id: string) => request<Task>(`/api/admin/tasks/${enc(id)}/retry`, { method: 'POST', body: {} }),
  adminFeatureTemplate: (id: string, body: { featured: boolean; pinnedOrder?: number }) =>
    request<{ id: string; featured: boolean; pinnedOrder: number }>(`/api/admin/templates/${enc(id)}/feature`, { method: 'POST', body }),
  adminDeleteTemplate: (id: string) => request<{ id: string; deleted: boolean }>(`/api/admin/templates/${enc(id)}`, { method: 'DELETE' }),
  adminTriageFeedback: (id: string, body: { status: AdminFeedbackStatus; adminNote?: string | null }) =>
    request<{ id: string; status: AdminFeedbackStatus; adminNote: string | null; resolvedAt: string | null }>(
      `/api/admin/feedbacks/${enc(id)}`,
      { method: 'PATCH', body },
    ),
  adminClearRateLimits: (bucket?: string) =>
    request<{ clearedCount: number; bucket: string }>('/api/admin/rate-limits/clear', { method: 'POST', body: bucket ? { bucket } : {} }),
  adminCluster: (withUsage = true) =>
    request<ClusterOverviewResponse>('/api/admin/cluster' + qs({ withUsage })),
  adminSandboxes: (opts: { kind?: string; provider?: string; workspaceId?: string; q?: string; withUsage?: boolean } = {}) =>
    request<{ ok: boolean; replicaId: string; provider: string; summary: SandboxInventorySummary; items: SandboxItem[] }>(
      '/api/admin/sandboxes' + qs(opts),
    ),
  adminSandboxDetail: (ref: string) => request<SandboxItem>(`/api/admin/sandboxes/${enc(ref)}`),
  adminDestroySandbox: (ref: string, opts: { force?: boolean; reason?: string } = {}) =>
    request<{
      ok: boolean;
      status: string;
      path: string;
      sandboxId: string;
      taskId: string | null;
      killedRuntime: boolean;
      cancelledTask: boolean;
      operator: string;
      reason: string;
    }>(`/api/admin/sandboxes/${enc(ref)}` + qs(opts), { method: 'DELETE' }),
  adminReapSandboxes: () =>
    request<{ ok: boolean; ghostsSettled: number; orphansKilled: number; operator: string }>('/api/admin/sandboxes/reap', {
      method: 'POST',
      body: {},
    }),
  adminPurgeOfflineNodes: (instanceId?: string) =>
    request<{ ok: boolean; purgedCount: number; instanceId: string }>('/api/admin/cluster/nodes' + qs(instanceId ? { instanceId } : {}), {
      method: 'DELETE',
    }),
  adminReclaimExpiredLeases: () =>
    request<{ ok: boolean; reclaimedCount: number; taskIds: string[] }>('/api/admin/cluster/reclaim-leases', {
      method: 'POST',
      body: {},
    }),
};

export const errorMessage = (error: unknown, fallback = '操作未完成，请重试。') =>
  error instanceof Error && error.message ? error.message : fallback;
