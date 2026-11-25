// 织页云函数宿主（Deno）：每次调用一个进程。
// 从 stdin 读 { codeB64, name, args, user, api, token, checkOnly }，执行 functions/{name}.ts 的默认导出，结果写到 stdout（一行 JSON）。
// 运行权限由平台给出：不能读写文件、读环境变量、起子进程，网络只允许访问织页自己的内部接口（--allow-net=127.0.0.1:端口）。
type Json = unknown;
interface Input { codeB64: string; name: string; args: Record<string, Json>; user: Json; api: string; token: string; checkOnly?: boolean }

const logs: string[] = [];
const fmt = (a: unknown[]) => a.map((x) => (typeof x === 'string' ? x : (() => { try { return JSON.stringify(x) } catch { return String(x) } })())).join(' ').slice(0, 2000);
const log = (...a: unknown[]) => { if (logs.length < 100) logs.push(fmt(a)) };
// 函数里的 console.* 也记进日志，不要污染 stdout
console.log = console.info = console.warn = console.error = console.debug = log;

const write = async (obj: unknown) => {
  await Deno.stdout.write(new TextEncoder().encode('\n__ZHIYE_FN__' + JSON.stringify(obj) + '\n'));
};

const input: Input = JSON.parse(await new Response(Deno.stdin.readable).text());

async function call(path: string, body: unknown) {
  const res = await fetch(input.api + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + input.token },
    body: JSON.stringify(body ?? {}),
  });
  const text = await res.text();
  let data: any = text;
  try { data = JSON.parse(text) } catch { /* 非 JSON */ }
  if (!res.ok) {
    const e = new Error((data && data.message) || `平台接口返回 ${res.status}`) as Error & { code?: string };
    e.code = data && data.code;
    throw e;
  }
  return data;
}

const b64 = (u8: Uint8Array) => { let s = ''; for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode(...u8.subarray(i, i + 0x8000)); return btoa(s) };

const ctx = {
  user: input.user,
  db: {
    list: (table: string, query?: Record<string, Json>) => call('/db/list', { table, query: query ?? {} }),
    get: async (table: string, id: number) => (await call('/db/get', { table, id })).row ?? null,
    insert: (table: string, row: Record<string, Json>) => call('/db/insert', { table, row }),
    update: (table: string, id: number, patch: Record<string, Json>) => call('/db/update', { table, id, row: patch }),
    remove: async (table: string, id: number) => { await call('/db/remove', { table, id }) },
    increment: (table: string, id: number, field: string, delta: number, opts?: { min?: number; max?: number }) =>
      call('/db/increment', { table, id, column: field, delta, ...(opts ?? {}) }),
    aggregate: async (table: string, spec: Json) => (await call('/db/aggregate', { table, spec })).rows,
    transaction: async (ops: Json[]) => (await call('/db/transaction', { ops })).results,
  },
  storage: {
    put: (path: string, data: Uint8Array | string) =>
      call('/storage/put', { path, dataB64: b64(typeof data === 'string' ? new TextEncoder().encode(data) : data) }),
    get: async (path: string) => {
      const r = await call('/storage/get', { path });
      return r.dataB64 == null ? null : Uint8Array.from(atob(r.dataB64), (c) => c.charCodeAt(0));
    },
  },
  source: { query: async (name: string, params?: Record<string, Json>) => (await call('/source', { name, params: params ?? {} })).data },
  ai: { chat: async (req: Json) => (await call('/ai', typeof req === 'string' ? { messages: [{ role: 'user', content: req }] } : req)).content },
  feishu: {
    sendCard: (opts: { connectorId?: string; title: string; content: string; templateColor?: string; fields?: { label: string; value: string }[]; buttons?: Json[] }) =>
      call('/feishu/send-card', opts ?? {}),
  },
  bitable: {
    list: (connectorId?: string, opts?: Record<string, Json>) =>
      call('/bitable/list', { connectorId: connectorId ?? '', ...(opts ?? {}) }),
    create: (connectorId: string, fields: Record<string, Json>, opts?: Record<string, Json>) =>
      call('/bitable/create', { connectorId, fields, ...(opts ?? {}) }),
    update: (connectorId: string, recordId: string, fields: Record<string, Json>, opts?: Record<string, Json>) =>
      call('/bitable/update', { connectorId, recordId, fields, ...(opts ?? {}) }),
  },
  org: {
    searchEmployees: async (q: string) => (await call('/org/employees', { q: q ?? '' })).employees ?? [],
  },
  log,
};

try {
  const mod = await import('data:application/typescript;base64,' + input.codeB64);
  const handler = mod.default;
  if (typeof handler !== 'function') throw new Error(`functions/${input.name}.ts 没有默认导出处理函数（export default async function (args, ctx) {...}）`);
  if (input.checkOnly) {
    await write({ ok: true, result: null, logs });
  } else {
    const result = await handler(input.args ?? {}, ctx);
    await write({ ok: true, result: result === undefined ? null : result, logs });
  }
} catch (e) {
  const err = e as Error & { code?: string };
  await write({ ok: false, error: { message: String(err?.message ?? err), code: err?.code ?? null, stack: String(err?.stack ?? '').split('\n').slice(0, 6).join('\n') }, logs });
}
