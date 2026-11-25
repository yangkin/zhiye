import { ApiError, ASK_INTERRUPTED, newIdempotencyKey } from './client';
import type { AppDetail, AppSummary, AppType, Asset, DataSourceInfo, Mode } from './types';

export interface ConversationSummary {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  lastMessage?: string;
  messageCount?: number;
  fileCount?: number;
  /** 从这段会话创建的应用 */
  appId?: string;
  appName?: string;
  /** 应用当前阶段：generating / ready / failed 等 */
  appPhase?: string;
}

export interface ConversationFile {
  id: string;
  name: string;
  version: number;
  size: number;
  messageId?: string;
  createdAt: string;
}

export interface ConversationMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  createdAt: string;
  skillAppIds?: string[];
  /** 这条消息附带的素材（图片 / 文档） */
  assets?: { id: string; name: string; mime?: string }[];
  /** 模型的思考过程（只有助手消息有） */
  thinking?: string;
}

export interface ConversationDetail extends ConversationSummary {
  messages: ConversationMessage[];
  type?: AppType;
  model?: string;
  mode?: Mode;
  assets?: Asset[];
  /** 会话级连接，创建应用时自动绑定；不是一次性消息附件 */
  sources?: DataSourceInfo[];
}

export type TrashAppSummary = AppSummary & { deletedAt?: string | null };

export interface HistorySummary {
  versions: number;
  apps: number;
  published: number;
  cloudEnabled: number;
  pageViews: number;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
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

/** 与 client.ts 保持相同的 JSON、cookie、幂等键和错误处理约定。 */
async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const method = opts.method ?? 'GET';
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (method === 'POST') headers['Idempotency-Key'] = newIdempotencyKey();
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      credentials: 'include',
    });
  } catch {
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
      // 非 JSON 错误体使用默认说明。
    }
    throw new ApiError(message, code, res.status);
  }
  if (res.status === 204) return undefined as T;
  const raw = await res.text();
  return (raw ? JSON.parse(raw) : undefined) as T;
}

const enc = encodeURIComponent;

async function askConversation(
  id: string,
  input: { content: string; skillAppIds?: string[]; assetIds?: string[]; type?: AppType },
  onDelta: (text: string) => void,
  signal?: AbortSignal,
  onThinking?: (text: string) => void,
): Promise<void> {
  let res: Response;
  try {
    res = await fetch(`/api/conversations/${enc(id)}/ask`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(input),
      signal,
      credentials: 'include',
    });
  } catch (error) {
    if ((error as Error)?.name === 'AbortError') throw error;
    throw new ApiError(FALLBACK_MESSAGES[0], 'NETWORK', 0);
  }
  if (!res.ok || !res.body) {
    let code = 'ASK_FAILED';
    let message = FALLBACK_MESSAGES[res.status] ?? '暂时没能回答，请稍后再试。';
    try {
      const data = await res.json();
      if (typeof data?.message === 'string') message = data.message;
      if (typeof data?.code === 'string') code = data.code;
    } catch {
      // 非 JSON 错误体使用默认说明。
    }
    throw new ApiError(message, code, res.status);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = '';
  let finished = false;
  const consumeLine = (line: string) => {
    if (!line) {
      event = '';
      return;
    }
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
      return;
    }
    if (!line.startsWith('data:')) return;
    const raw = line.slice(5).trim();
    if (!raw) return;
    let data: { text?: string; message?: string; code?: string };
    try {
      data = JSON.parse(raw);
    } catch {
      return;
    }
    if (event === 'delta' && data.text) onDelta(data.text);
    if (event === 'thinking' && data.text) onThinking?.(data.text);
    if (event === 'done') finished = true;
    if (event === 'error') throw new ApiError(data.message ?? '回答中断，请稍后重试。', data.code ?? 'ASK_FAILED', 502);
  };

  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let index: number;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index).replace(/\r$/, '');
      buffer = buffer.slice(index + 1);
      consumeLine(line);
    }
  }
  buffer += decoder.decode();
  if (buffer) consumeLine(buffer.replace(/\r$/, ''));
  // 连接断开但没收到 done：已收到的只是半截回答，不能当成完整结果，也不能据此开始建应用
  if (!finished) throw new ApiError(ASK_INTERRUPTED, 'ASK_INTERRUPTED', 0);
}

export const api = {
  createConversation: (sourceIds: string[] = []) => request<ConversationSummary>('/api/conversations', { method: 'POST', body: { sourceIds } }),
  conversations: () => request<ConversationSummary[]>('/api/conversations'),
  conversation: (id: string) => request<ConversationDetail>(`/api/conversations/${enc(id)}`),
  updateConversationSources: (id: string, sourceIds: string[]) =>
    request<{ sources: DataSourceInfo[] }>(`/api/conversations/${enc(id)}/sources`, { method: 'PUT', body: { sourceIds } }),
  askConversation,
  /** 停止回答；finished=true 表示服务端其实已经答完并保存了 */
  stopAsk: (id: string) => request<{ finished: boolean }>(`/api/conversations/${enc(id)}/ask/stop`, { method: 'POST', body: {} }),
  buildConversation: (
    id: string,
    input: { type: AppType; mode: Mode; model: string; assetIds?: string[]; skillAppIds?: string[]; sourceIds?: string[] },
  ) => request<AppDetail>(`/api/conversations/${enc(id)}/build`, { method: 'POST', body: input }),
  deleteConversation: (id: string) => request<void>(`/api/conversations/${enc(id)}`, { method: 'DELETE' }),
  conversationFiles: (id: string) => request<ConversationFile[]>(`/api/conversations/${enc(id)}/files`),
  conversationFile: (id: string, fileId: string) =>
    request<ConversationFile & { content: string }>(`/api/conversations/${enc(id)}/files/${enc(fileId)}`),
  deleteConversationFile: (id: string, fileId: string) =>
    request<void>(`/api/conversations/${enc(id)}/files/${enc(fileId)}`, { method: 'DELETE' }),
  polish: (content: string) => request<{ text: string }>('/api/chat/polish', { method: 'POST', body: { content } }),
  historySummary: () => request<HistorySummary>('/api/history/summary'),
  trashApps: () => request<TrashAppSummary[]>('/api/apps/trash'),
};
