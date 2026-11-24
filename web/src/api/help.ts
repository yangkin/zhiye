/** 小织帮助助手的接口（/api/help/**）。流式回答用 fetch 读取 SSE。 */

export interface HelpAsset {
  id: string;
  name: string;
  mime: string;
  size: number;
  url: string;
}

export interface HelpConversation {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export type HelpMessageStatus = 'done' | 'stopped' | 'error' | 'feedback';

export interface HelpMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  status: HelpMessageStatus;
  createdAt: string;
  assets: HelpAsset[];
}

export interface HelpFeedbackResult {
  id: string;
  reply: string;
  messages?: HelpMessage[];
}

export class HelpError extends Error {
  code: string;
  status: number;
  constructor(message: string, code: string, status: number) {
    super(message);
    this.name = 'HelpError';
    this.code = code;
    this.status = status;
  }
}

async function fail(res: Response): Promise<never> {
  let code = 'HTTP_' + res.status;
  let message = res.status === 429 ? '操作太频繁，请稍后再试' : `请求未完成（${res.status}）`;
  try {
    const data = await res.json();
    if (typeof data?.message === 'string') message = data.message;
    if (typeof data?.code === 'string') code = data.code;
  } catch {
    /* 非 JSON 错误体 */
  }
  throw new HelpError(message, code, res.status);
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, { credentials: 'include', ...init, headers: { Accept: 'application/json', ...(init.headers ?? {}) } });
  } catch (e) {
    if ((e as Error)?.name === 'AbortError') throw e;
    throw new HelpError('网络连接中断，请检查网络后重试', 'NETWORK', 0);
  }
  if (!res.ok) await fail(res);
  const raw = await res.text();
  return (raw ? JSON.parse(raw) : undefined) as T;
}

const json = (body: unknown): RequestInit => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

const enc = encodeURIComponent;

export const helpApi = {
  docs: () => call<{ items: import('./types').HelpKnowledgeArticle[] }>('/api/help/docs'),
  conversations: () => call<HelpConversation[]>('/api/help/conversations'),
  create: () => call<HelpConversation>('/api/help/conversations', { method: 'POST' }),
  messages: (id: string) => call<HelpMessage[]>(`/api/help/conversations/${enc(id)}/messages`),
  remove: (id: string) => call<{ ok: boolean }>(`/api/help/conversations/${enc(id)}`, { method: 'DELETE' }),
  stop: (id: string) => call<{ stopped: boolean }>(`/api/help/conversations/${enc(id)}/stop`, { method: 'POST' }),
  feedback: (input: { content: string; assetIds?: string[]; conversationId?: string | null }) =>
    call<HelpFeedbackResult>('/api/help/feedback', json(input)),
  uploadImage: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return call<HelpAsset>('/api/assets', { method: 'POST', body: form });
  },
};

export interface StreamHandlers {
  onUser?: (message: HelpMessage, title: string) => void;
  onDelta: (text: string) => void;
  onDone: (data: { messageId: string | null; status: 'done' | 'stopped'; title?: string }) => void;
  onError: (message: string) => void;
}

/**
 * 发送消息并读取 SSE 回答。请求本身失败（校验、429、503 等）时抛出 HelpError；
 * 流中途断开时调用 onError（除非是主动 abort）。
 */
export async function streamHelp(
  conversationId: string,
  input: { content: string; assetIds?: string[] },
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let res: Response;
  try {
    res = await fetch(`/api/help/conversations/${enc(conversationId)}/messages`, {
      ...json(input),
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' },
      credentials: 'include',
      signal,
    });
  } catch (e) {
    if ((e as Error)?.name === 'AbortError') return;
    throw new HelpError('网络连接中断，请检查网络后重试', 'NETWORK', 0);
  }
  if (!res.ok) await fail(res);
  if (!res.body) throw new HelpError('浏览器不支持流式读取', 'NO_STREAM', 0);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  let finished = false;
  const dispatch = (block: string) => {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
    }
    if (!data.length) return;
    let payload: Record<string, unknown>;
    try {
      payload = JSON.parse(data.join('\n'));
    } catch {
      return;
    }
    if (event === 'user') handlers.onUser?.(payload.message as HelpMessage, String(payload.title ?? ''));
    else if (event === 'delta') handlers.onDelta(String(payload.text ?? ''));
    else if (event === 'done') {
      finished = true;
      handlers.onDone(payload as { messageId: string | null; status: 'done' | 'stopped'; title?: string });
    } else if (event === 'error') {
      finished = true;
      handlers.onError(String(payload.message ?? '小织暂时没连上，请稍后再试'));
    }
  };
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true }).replace(/\r/g, '');
      let i: number;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        dispatch(buf.slice(0, i));
        buf = buf.slice(i + 2);
      }
    }
    if (buf.trim()) dispatch(buf);
  } catch (e) {
    if ((e as Error)?.name === 'AbortError' || signal?.aborted) return;
    if (!finished) handlers.onError('小织暂时没连上，请稍后再试');
    return;
  }
  if (!finished && !signal?.aborted) handlers.onError('小织暂时没连上，请稍后再试');
}
