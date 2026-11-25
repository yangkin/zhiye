import { useEffect, useRef, useState } from 'react';
import { api } from './client';
import {
  isTerminal,
  type FinishedPayload,
  type StatusPayload,
  type Task,
  type TaskEvent,
  type StepDetail,
  type TaskStatus,
  type TodoItem,
  type ConfirmRequest,
  type ToolPayload,
  type VerifyCheck,
} from './types';

export interface ToolItem {
  id: string;
  name: string;
  summary: string;
  state: 'start' | 'end';
  ok?: boolean;
  /** 结束事件的 seq（详情按需加载用） */
  seq?: number;
  detail?: StepDetail | string;
  detailOmitted?: boolean;
}

/** 生成视图的时间线：按事件顺序交错显示文字、思考、步骤、阶段与检查 */
export type TimelineItem =
  | { kind: 'text'; key: string; text: string }
  | { kind: 'thinking'; key: string; seq: number; text: string; done: boolean; durationMs?: number; textOmitted?: boolean }
  | { kind: 'tool'; key: string; id: string }
  | { kind: 'stage'; key: string; label: string }
  | { kind: 'verify'; key: string; checks: VerifyCheck[] }
  | { kind: 'steer'; key: string; text: string };

export interface TaskStreamState {
  taskId: string | null;
  task: Task | null;
  status: TaskStatus | null;
  phaseLabel: string;
  progress: number;
  phases: string[];
  tools: ToolItem[];
  checks: VerifyCheck[];
  timeline: TimelineItem[];
  todos: TodoItem[];
  /** 收到第一条 todo 事件（用于自动展开待办） */
  todoSeen: boolean;
  agentText: string;
  logs: string[];
  finished: FinishedPayload | null;
  /** 最近一次确认请求（pending 时在输入框上方显示确认卡） */
  confirm: ConfirmRequest | null;
  connected: boolean;
  lastSeq: number;
}

const empty = (taskId: string | null): TaskStreamState => ({
  taskId,
  task: null,
  status: null,
  phaseLabel: '',
  progress: 0,
  phases: [],
  tools: [],
  checks: [],
  timeline: [],
  todos: [],
  todoSeen: false,
  agentText: '',
  logs: [],
  finished: null,
  confirm: null,
  connected: false,
  lastSeq: 0,
});

const CONFIRM_STATE: Record<ConfirmRequest['state'], string> = { pending: '等待你确认', accepted: '已启用', declined: '暂不启用', timeout: '没有回应，暂不启用' };

/** 「启用 织页云服务」这一步后面带上确认结果 */
function confirmSummary(summary: string, c: ConfirmRequest | null) {
  const base = summary.split(' · ')[0];
  return c ? `${base} · ${CONFIRM_STATE[c.state] ?? c.state}` : base;
}

export function reduceEvent(s: TaskStreamState, e: TaskEvent): TaskStreamState {
  if (e.seq && e.seq <= s.lastSeq) return s; // 重连回放去重
  const next: TaskStreamState = { ...s, lastSeq: e.seq || s.lastSeq };
  const p = e.payload ?? {};
  switch (e.type) {
    case 'status': {
      const st = p as StatusPayload;
      next.status = st.status ?? next.status;
      if (typeof st.progress === 'number') next.progress = Math.max(0, Math.min(100, st.progress));
      if (st.phaseLabel) {
        next.phaseLabel = st.phaseLabel;
        if (next.phases[next.phases.length - 1] !== st.phaseLabel && !next.phases.includes(st.phaseLabel)) {
          next.phases = [...next.phases, st.phaseLabel];
          next.timeline = [...next.timeline, { kind: 'stage', key: 'st' + (e.seq || next.timeline.length), label: st.phaseLabel }];
        }
      }
      break;
    }
    case 'agent_text': {
      const delta = String(p.delta ?? '');
      next.agentText = (next.agentText + delta).slice(-6000);
      if (!delta) break;
      const last = next.timeline[next.timeline.length - 1];
      next.timeline =
        last?.kind === 'text'
          ? [...next.timeline.slice(0, -1), { ...last, text: last.text + delta }]
          : [...next.timeline, { kind: 'text', key: 'tx' + (e.seq || next.timeline.length), text: delta }];
      break;
    }
    case 'tool': {
      const t = p as ToolPayload;
      const idx = next.tools.findIndex((x) => x.id === t.id);
      const item: ToolItem = {
        id: t.id,
        name: t.name,
        summary: t.summary,
        state: t.state,
        ok: t.ok,
        ...(t.state === 'end' ? { seq: e.seq } : {}),
        ...(t.detail !== undefined ? { detail: t.detail } : {}),
        ...(t.detailOmitted ? { detailOmitted: true } : {}),
      };
      if (t.name === 'request_confirm') item.summary = confirmSummary(t.summary, next.confirm);
      next.tools = idx >= 0 ? next.tools.map((x, i) => (i === idx ? { ...x, ...item } : x)) : [...next.tools, item].slice(-300);
      // 待办清单工具不单独显示一行
      if (idx < 0 && t.name !== 'todo_write') next.timeline = [...next.timeline, { kind: 'tool', key: 'tl' + t.id, id: t.id }];
      break;
    }
    case 'verify':
      next.checks = Array.isArray(p.checks) ? p.checks : next.checks;
      if (Array.isArray(p.checks)) {
        const i = next.timeline.findIndex((x) => x.kind === 'verify');
        const item: TimelineItem = { kind: 'verify', key: 'vf', checks: p.checks };
        next.timeline = i >= 0 ? next.timeline.map((x, j) => (j === i ? item : x)) : [...next.timeline, item];
      }
      break;
    case 'todo':
      if (Array.isArray(p.items)) {
        next.todos = p.items;
        next.todoSeen = true;
      }
      break;
    case 'thinking': {
      const seq = e.seq;
      const i = next.timeline.findIndex((x) => x.kind === 'thinking' && x.seq === seq);
      const item: TimelineItem = {
        kind: 'thinking',
        key: 'th' + seq,
        seq,
        text: String(p.text ?? ''),
        done: !!p.done,
        ...(typeof p.durationMs === 'number' ? { durationMs: p.durationMs } : {}),
        ...(p.textOmitted ? { textOmitted: true } : {}),
      };
      next.timeline = i >= 0 ? next.timeline.map((x, j) => (j === i ? item : x)) : [...next.timeline, item];
      break;
    }
    case 'thinking_delta': {
      const i = next.timeline.findIndex((x) => x.kind === 'thinking' && x.seq === p.blockSeq);
      if (i < 0) break;
      const cur = next.timeline[i] as Extract<TimelineItem, { kind: 'thinking' }>;
      // offset 等于已有文字长度时才追加（去重）
      if (typeof p.offset === 'number' && p.offset !== cur.text.length) break;
      next.timeline = next.timeline.map((x, j) => (j === i ? { ...cur, text: cur.text + String(p.delta ?? '') } : x));
      break;
    }
    case 'thinking_end': {
      next.timeline = next.timeline.map((x) =>
        x.kind === 'thinking' && x.seq === p.blockSeq ? { ...x, done: true, durationMs: typeof p.durationMs === 'number' ? p.durationMs : x.durationMs } : x,
      );
      break;
    }
    case 'steer':
      next.timeline = [...next.timeline, { kind: 'steer', key: 'sr' + (e.seq || next.timeline.length), text: String(p.text ?? '') }];
      break;
    case 'confirm': {
      const c = p as ConfirmRequest;
      // 结束事件不带标题和条目，沿用 pending 时的
      next.confirm = next.confirm?.id === c.id ? { ...next.confirm, ...c, title: c.title ?? next.confirm.title, items: c.items ?? next.confirm.items, options: c.options ?? next.confirm.options } : c;
      const ti = next.tools.map((x) => x.name).lastIndexOf('request_confirm');
      if (ti >= 0) next.tools = next.tools.map((x, i) => (i === ti ? { ...x, summary: confirmSummary(x.summary, next.confirm) } : x));
      break;
    }
    case 'log':
      next.logs = [...next.logs, String(p.line ?? '')].slice(-200);
      break;
    case 'finished': {
      const f = p as FinishedPayload;
      next.finished = f;
      next.status = f.status ?? next.status;
      if (f.status === 'SUCCEEDED') next.progress = 100;
      break;
    }
  }
  return next;
}

/**
 * 订阅任务 SSE。进度来自服务端事件和真实任务快照；SSE 被代理缓冲或断连时每 3 秒回查一次，避免卡片永久停在旧状态。
 * onFinished 在流里收到 finished（或快照即为终态）后调用一次，参数为最新的 Task。
 */
export function useTaskStream(taskId: string | null | undefined, onFinished?: (task: Task) => void): TaskStreamState {
  const [state, setState] = useState<TaskStreamState>(() => empty(taskId ?? null));
  const cb = useRef(onFinished);
  cb.current = onFinished;

  useEffect(() => {
    setState(empty(taskId ?? null));
    if (!taskId) return;
    let closed = false;
    let es: EventSource | null = null;
    let finishedFired = false;

    const finish = async () => {
      if (finishedFired) return;
      finishedFired = true;
      es?.close();
      try {
        const task = await api.getTask(taskId);
        if (closed) return;
        setState((s) => ({
          ...s,
          task,
          status: task.status,
          phaseLabel: task.phaseLabel || s.phaseLabel,
          progress: task.status === 'SUCCEEDED' ? 100 : s.progress,
          connected: false,
        }));
        cb.current?.(task);
      } catch {
        if (!closed) setState((s) => ({ ...s, connected: false }));
      }
    };

    api
      .getTask(taskId)
      .then((task) => {
        if (closed) return;
        setState((s) =>
          s.lastSeq > 0
            ? { ...s, task }
            : { ...s, task, status: task.status, phaseLabel: task.phaseLabel, progress: task.progress, todos: task.todos ?? s.todos },
        );
      })
      .catch(() => undefined);

    const open = () => {
      es = new EventSource(api.taskEventsUrl(taskId), { withCredentials: true });
      es.onopen = () => !closed && setState((s) => ({ ...s, connected: true }));
      es.addEventListener('task', (ev) => {
        if (closed) return;
        let event: TaskEvent;
        try {
          event = JSON.parse((ev as MessageEvent).data);
        } catch {
          return;
        }
        setState((s) => reduceEvent(s, event));
        if (event.type === 'finished') void finish();
      });
      es.onerror = () => {
        if (closed) return;
        setState((s) => ({ ...s, connected: false }));
        // 服务端在 finished 后关闭连接；若此时已是终态，停止自动重连。
        if (es && es.readyState === EventSource.CLOSED) {
          api
            .getTask(taskId)
            .then((task) => {
              if (closed) return;
              if (isTerminal(task.status)) void finish();
              else setTimeout(() => !closed && open(), 3000);
            })
            .catch(() => setTimeout(() => !closed && open(), 5000));
        }
      };
    };
    open();
    const reconcile = window.setInterval(() => {
      if (closed || finishedFired) return;
      api.getTask(taskId).then((task) => {
        if (closed || finishedFired) return;
        setState((s) => reduceEvent(
          { ...s, task, todos: task.todos ?? s.todos },
          { seq: 0, createdAt: new Date().toISOString(), type: 'status', payload: { status: task.status, phaseLabel: task.phaseLabel, progress: task.progress } },
        ));
        if (isTerminal(task.status)) void finish();
      }).catch(() => undefined);
    }, 3000);
    return () => {
      closed = true;
      window.clearInterval(reconcile);
      es?.close();
    };
  }, [taskId]);

  return state;
}

/** 等待任务结束（用于「先保存草稿再发布」这种串行流程）。 */
export function waitForTask(taskId: string, onEvent?: (e: TaskEvent) => void): Promise<Task> {
  return new Promise((resolve, reject) => {
    let done = false;
    const es = new EventSource(api.taskEventsUrl(taskId), { withCredentials: true });
    const settle = async () => {
      if (done) return;
      done = true;
      es.close();
      try {
        resolve(await api.getTask(taskId));
      } catch (error) {
        reject(error);
      }
    };
    es.addEventListener('task', (ev) => {
      try {
        const event: TaskEvent = JSON.parse((ev as MessageEvent).data);
        onEvent?.(event);
        if (event.type === 'finished') void settle();
      } catch {
        /* ignore */
      }
    });
    es.onerror = () => {
      if (done) return;
      api
        .getTask(taskId)
        .then((t) => {
          if (isTerminal(t.status)) void settle();
        })
        .catch(() => undefined);
    };
  });
}
