import { useEffect, useMemo, useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { TaskStreamState, TimelineItem, ToolItem } from '../api/sse';
import type { StepDetail, Task } from '../api/types';
import { useCode } from '../lib/codeStore';
import { Markdown, splitNext } from '../lib/markdown';


const TOOL_ICON: Record<string, string> = {
  write: '✦', edit: '✎', read: '☰', bash: '>_', ls: '☰', grep: '⌕', find: '⌕',
  find_files: '⌕', grep_files: '⌕', verify_app: '✓', service_runtime_inspect: '◉',
  typecheck_diagnostics: '⚡', browser_test: '▶', test_endpoint: '⇄', code_symbols: '◈',
  inspect_source: '⛁', inspect_bitable: '⛁', install_package: '⊞', mcp_list: '◇', mcp_call: '◇',
  skill: '◆', read_skill: '◆', source: '⛁', request_confirm: '☁', screenshot: '▣',
};

/** 打开代码页的某个文件（由 Workbench 监听 openFile 后切到「代码」） */
export function openInCode(path: string, line?: number) {
  useCode.getState().openFile(path, line);
  window.dispatchEvent(new CustomEvent('zhiye:open-code', { detail: { path } }));
}

/** 折叠行的耗时文案；创建/结束时间任一缺失就返回 null（不显示耗时）。 */
function taskDuration(task: Task | null): string | null {
  // 优先用开始执行时间，避免把排队时间算进「耗时」；老数据没有 startedAt 时回落到创建时间
  const from = task?.startedAt || task?.createdAt;
  if (!from || !task?.finishedAt) return null;
  const start = Date.parse(from);
  const end = Date.parse(task.finishedAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null;
  const total = Math.max(1, Math.round((end - start) / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  if (!m) return `${s} 秒`;
  return s ? `${m} 分 ${s} 秒` : `${m} 分`;
}

interface Props {
  stream: TaskStreamState;
  /** 任务已结束：隐藏思考过程，折叠步骤为一行汇总 */
  finished: boolean;
  /** 只要步骤汇总那一行（完成后的消息里用，文字由消息本身显示） */
  summaryOnly?: boolean;
}

/** 写/改文件步骤的统计：创建几个、编辑几个、增删多少行 */
function fileStats(fileSteps: ToolItem[]) {
  const created = fileSteps.filter((t) => typeof t.detail === 'object' && t.detail && 'created' in t.detail && t.detail.created).length;
  const edited = new Set(fileSteps.map((t) => (typeof t.detail === 'object' && t.detail ? (t.detail as { path: string }).path : t.summary))).size - created;
  let added = 0;
  let removed = 0;
  for (const t of fileSteps) {
    const d = t.detail as StepDetail | undefined;
    if (d && typeof d === 'object') {
      if (d.kind === 'write') added += d.lines ?? 0;
      if (d.kind === 'edit') {
        added += d.added ?? 0;
        removed += d.removed ?? 0;
      }
    } else {
      const m = /\+(\d+)(?:\s*行)?(?:\s*[−-](\d+))?/.exec(t.summary);
      if (m) {
        added += +m[1];
        removed += +(m[2] ?? 0);
      }
    }
  }
  return { created, edited: Math.max(0, edited), added, removed };
}

type Row = TimelineItem | { kind: 'group'; key: string; ids: string[] };

/** 生成中：连续两步以上已结束的工具调用收成一行（「创建 2 个文件，编辑 1 个文件 +318 行」），正在跑的那步单独显示 */
function groupRows(items: TimelineItem[], tools: Map<string, ToolItem>): Row[] {
  const out: Row[] = [];
  let run: string[] = [];
  const flush = () => {
    if (run.length >= 2) out.push({ kind: 'group', key: 'g' + run[0], ids: run });
    else for (const id of run) out.push({ kind: 'tool', key: 'tl' + id, id });
    run = [];
  };
  for (const x of items) {
    const t = x.kind === 'tool' ? tools.get(x.id) : undefined;
    // 技能加载、数据源读取单独一行（和 meoo 的「加载 xx 技能」一致），失败的步骤也单独保留
    const standalone = !t || t.name === 'skill' || t.name === 'source' || t.name === 'request_confirm' || t.name === 'screenshot' || t.summary.startsWith('加载技能');
    if (x.kind === 'tool' && t && !standalone && t.state === 'end' && t.ok !== false) run.push(x.id);
    else {
      flush();
      out.push(x);
    }
  }
  flush();
  return out;
}

const READ_TOOLS = new Set(['read', 'ls', 'grep', 'find', 'find_files', 'grep_files', 'code_symbols']);
const CHECK_TOOLS = new Set(['typecheck_diagnostics', 'browser_test', 'test_endpoint', 'verify_app']);
const PROBE_TOOLS = new Set(['inspect_source', 'inspect_bitable', 'service_runtime_inspect', 'mcp_list', 'mcp_call']);

function groupLabel(list: ToolItem[]) {
  const files = list.filter((t) => t.name === 'write' || t.name === 'edit');
  const { created, edited, added, removed } = fileStats(files);
  const readItems = list.filter((t) => READ_TOOLS.has(t.name));
  const reads = readItems.length;
  const pureFileReads = reads > 0 && readItems.every((t) => t.name === 'read');
  const cmds = list.filter((t) => t.name === 'bash').length;
  const checks = list.filter((t) => CHECK_TOOLS.has(t.name)).length;
  const deps = list.filter((t) => t.name === 'install_package').length;
  const probes = list.filter((t) => PROBE_TOOLS.has(t.name)).length;
  const other = list.length - files.length - reads - cmds - checks - deps - probes;
  const parts = [
    created ? `创建 ${created} 个文件` : '',
    edited ? `编辑 ${edited} 个文件` : '',
    reads ? (pureFileReads ? `阅读 ${reads} 个文件` : `查阅 ${reads} 项代码`) : '',
    cmds ? `执行 ${cmds} 条命令` : '',
    checks ? `校验 ${checks} 项` : '',
    deps ? `安装 ${deps} 个依赖` : '',
    probes ? `探测 ${probes} 项上下文` : '',
    other ? `${other} 个其他步骤` : '',
  ].filter(Boolean);
  return { text: parts.join('，'), added, removed, files: files.length };
}

function GroupRow({ ids, tools, taskId }: { ids: string[]; tools: Map<string, ToolItem>; taskId: string | null }) {
  const [open, setOpen] = useState(false);
  const list = ids.map((id) => tools.get(id)).filter((t): t is ToolItem => !!t);
  const g = groupLabel(list);
  return (
    <div className={`tl-step-wrap tl-group ${open ? 'open' : ''}`}>
      <button type="button" className="tl-step ok" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        <span className="tl-tool-icon" aria-hidden="true">☰</span>
        <span className="tl-step-text">
          {g.text}
          {g.files ? (
            <>
              {' '}
              <em className="diff-add">+{g.added} 行</em>
              {g.removed ? <em className="diff-del"> −{g.removed} 行</em> : null}
            </>
          ) : null}
        </span>
        <i className="tl-caret" aria-hidden="true">
          {open ? '▾' : '▸'}
        </i>
      </button>
      {open ? (
        <div className="tl-summary-list">
          {list.map((t) => (
            <StepRow key={t.id} tool={t} taskId={taskId} />
          ))}
        </div>
      ) : null}
    </div>
  );
}

/** 实时秒表：用于思考中 / 等待模型下一步时显示已耗时，让用户一眼确认仍在运行 */
function useElapsedSeconds(active: boolean, resetKey: string | number): number {
  const [sec, setSec] = useState(0);
  useEffect(() => {
    if (!active) {
      setSec(0);
      return;
    }
    const started = Date.now();
    setSec(0);
    const id = window.setInterval(() => {
      setSec(Math.max(1, Math.floor((Date.now() - started) / 1000)));
    }, 1000);
    return () => window.clearInterval(id);
  }, [active, resetKey]);
  return sec;
}

/** 进行中的底部实时脉搏条：只要任务未结束，即使处于思考静默期或等待模型下一轮，也能明确看到正在运行 */
function LiveActivityBar({ stream, items }: { stream: TaskStreamState; items: TimelineItem[] }) {
  const activeThinking = items.some((x) => x.kind === 'thinking' && !x.done);
  const runningTool = [...stream.tools].reverse().find((t) => t.state === 'start' && t.name !== 'todo_write');
  const last = items[items.length - 1];
  const resetKey = `${items.length}:${stream.lastSeq}:${runningTool?.id ?? ''}`;
  const elapsed = useElapsedSeconds(!activeThinking, resetKey);

  // 如果底部已经有一个正在脉动的「思考中…」气泡，避免重复叠加
  if (activeThinking) return null;

  let label = '正在思考下一步';
  if (stream.status === 'CANCELLING') {
    label = '正在停止当前任务';
  } else if (runningTool) {
    label = `正在执行：${runningTool.summary || runningTool.name}`;
  } else if (last?.kind === 'stage' && last.label) {
    label = last.label;
  } else if (last?.kind === 'text') {
    label = '正在持续输出';
  } else if (stream.phaseLabel) {
    label = stream.phaseLabel;
  }

  return (
    <div className="tl-live-bar" role="status" aria-live="polite">
      <span className="tl-live-orb" aria-hidden="true" />
      <span className="tl-live-label">{label}</span>
      <span className="tl-live-dots" aria-hidden="true">
        <i />
        <i />
        <i />
      </span>
      {elapsed >= 2 ? <span className="tl-live-sec">{elapsed}s</span> : null}
    </div>
  );
}

/** 生成视图：文字、思考、步骤、阶段、检查按事件顺序交错。 */
export function Timeline({ stream, finished, summaryOnly = false }: Props) {
  const tools = useMemo(() => new Map(stream.tools.map((t) => [t.id, t])), [stream.tools]);
  const items = stream.timeline.filter((x) => !(finished && x.kind === 'thinking'));
  const fileSteps = stream.tools.filter((t) => t.state === 'end' && (t.name === 'write' || t.name === 'edit'));
  const stepCount = stream.tools.filter((t) => t.name !== 'todo_write').length;
  const [showSteps, setShowSteps] = useState(false);

  if (finished) {
    const { created, edited, added, removed } = fileStats(fileSteps);
    const texts = summaryOnly ? [] : items.filter((x) => x.kind === 'text' || x.kind === 'steer' || x.kind === 'verify');
    const duration = taskDuration(stream.task);
    const changed = created > 0 || edited > 0;
    return (
      <div className="timeline is-finished">
        {stepCount ? (
          <div className="tl-summary">
            <button type="button" className="tl-summary-head" aria-expanded={showSteps} onClick={() => setShowSteps((v) => !v)}>
              <i className="tl-caret-lead" aria-hidden="true">
                {showSteps ? '▾' : '▸'}
              </i>
              <span>
                已处理 {stepCount} 个任务
                {duration ? <span className="tl-summary-time">耗时 {duration}</span> : null}
              </span>
            </button>
            {showSteps ? (
              <div className="tl-summary-list">
                {changed ? (
                  <div className="tl-summary-stats">
                    {created ? `创建 ${created} 个文件` : ''}
                    {created && edited > 0 ? ' · ' : ''}
                    {edited > 0 ? `编辑 ${edited} 个文件` : ''}
                    {fileSteps.length ? (
                      <>
                        {' · '}
                        <em className="diff-add">+{added} 行</em>
                        {removed ? <em className="diff-del"> −{removed} 行</em> : null}
                      </>
                    ) : null}
                  </div>
                ) : null}
                {stream.tools
                  .filter((t) => t.name !== 'todo_write')
                  .map((t) => (
                    <StepRow key={t.id} tool={t} taskId={stream.taskId} />
                  ))}
              </div>
            ) : null}
          </div>
        ) : null}
        {texts.map((x) => (
          <TimelineRow key={x.key} item={x} tools={tools} taskId={stream.taskId} />
        ))}
      </div>
    );
  }

  return (
    <div className="timeline" aria-live="polite">
      {groupRows(items, tools).map((x) =>
        x.kind === 'group' ? (
          <GroupRow key={x.key} ids={x.ids} tools={tools} taskId={stream.taskId} />
        ) : (
          <TimelineRow key={x.key} item={x} tools={tools} taskId={stream.taskId} />
        ),
      )}
      <LiveActivityBar stream={stream} items={items} />
    </div>
  );
}

function TimelineRow({ item, tools, taskId }: { item: TimelineItem; tools: Map<string, ToolItem>; taskId: string | null }) {
  switch (item.kind) {
    case 'text':
      return item.text.trim() ? <Markdown className="tl-text markdown-lite" text={splitNext(item.text).body} /> : null;
    case 'thinking':
      return <ThinkingRow item={item} taskId={taskId} />;
    case 'tool': {
      const t = tools.get(item.id);
      return t ? <StepRow tool={t} taskId={taskId} /> : null;
    }
    case 'stage':
      return (
        <div className="tl-stage">
          <span aria-hidden="true">·</span>
          {item.label}
        </div>
      );
    case 'verify':
      return (
        <div className="tl-verify">
          {item.checks.map((c) => (
            // 跳过项（skipped）既不是通过也不是失败，单独显示，避免技能包等场景被看成报错
            <div key={c.name} className={`tl-step ${c.skipped ? 'skip' : c.ok ? 'ok' : 'bad'}`} title={c.detail}>
              <span className={`tl-mark ${c.skipped ? 'skip' : c.ok ? 'ok' : 'bad'}`} aria-hidden="true">
                {c.skipped ? '–' : c.ok ? '✓' : '×'}
              </span>
              <span className="tl-step-text">检查 · {c.name}</span>
              {c.skipped ? (
                <small className="tl-check-detail">已跳过</small>
              ) : !c.ok && c.detail ? (
                <small className="tl-check-detail">{c.detail}</small>
              ) : null}
            </div>
          ))}
        </div>
      );
    case 'steer':
      return (
        <div className="tl-steer">
          <span className="steer-tag">补充要求</span>
          {item.text}
        </div>
      );
  }
}

function ThinkingRow({ item, taskId }: { item: Extract<TimelineItem, { kind: 'thinking' }>; taskId: string | null }) {
  const [open, setOpen] = useState<boolean | null>(null);
  const [loaded, setLoaded] = useState<string | null>(null);
  // 思考过程默认收起，点开才看
  const expanded = open ?? false;
  const text = loaded ?? item.text;
  const liveSecs = useElapsedSeconds(!item.done, item.key);
  const secs = item.durationMs ? Math.max(1, Math.round(item.durationMs / 1000)) : null;
  const toggle = async () => {
    const next = !expanded;
    setOpen(next);
    if (next && item.textOmitted && loaded === null && taskId) {
      try {
        const r = await api.taskEventDetail(taskId, item.seq);
        setLoaded(String(r.detail ?? ''));
      } catch (err) {
        setLoaded('（' + errorMessage(err, '没能读取思考过程') + '）');
      }
    }
  };
  return (
    <div className={`tl-thinking ${item.done ? 'done' : 'live'}`}>
      <button type="button" aria-expanded={expanded} onClick={() => void toggle()}>
        {!item.done ? <span className="tl-live-orb" aria-hidden="true" /> : null}
        <span>
          {item.done ? `已思考 ${secs ?? '?'}s` : `思考中${liveSecs >= 1 ? ` ${liveSecs}s` : ''}`}
        </span>
        {!item.done ? (
          <span className="tl-live-dots" aria-hidden="true">
            <i />
            <i />
            <i />
          </span>
        ) : null}
        <i aria-hidden="true">{expanded ? '▾' : '▸'}</i>
      </button>
      {expanded && text ? <p>{text}</p> : null}
    </div>
  );
}

function StepRow({ tool, taskId }: { tool: ToolItem; taskId: string | null }) {
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<StepDetail | string | null>(tool.detail ?? null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');
  const state = tool.state === 'start' ? 'run' : tool.ok === false ? 'bad' : 'ok';
  const hasDetail = !!tool.detail || !!tool.detailOmitted;
  const d = (detail ?? tool.detail ?? null) as StepDetail | string | null;

  const toggle = async () => {
    const next = !open;
    setOpen(next);
    if (next && !d && tool.detailOmitted && taskId && tool.seq) {
      setLoading(true);
      try {
        const r = await api.taskEventDetail(taskId, tool.seq);
        setDetail(r.detail as StepDetail);
      } catch (e) {
        setErr(errorMessage(e, '没能读取详情'));
      } finally {
        setLoading(false);
      }
    }
  };

  const label = (
    <>
      <span className={`tl-tool-icon ${state}`} aria-hidden="true">
        {state === 'run' ? <i className="skill-spinner" /> : state === 'bad' ? '!' : (TOOL_ICON[tool.name] ?? '·')}
      </span>
      <span className="tl-step-text">
        <StepSummary text={tool.summary || tool.name} />
      </span>
    </>
  );
  if (!hasDetail) return <div className={`tl-step ${state}`}>{label}</div>;
  return (
    <div className={`tl-step-wrap ${open ? 'open' : ''}`}>
      <button type="button" className={`tl-step ${state}`} aria-expanded={open} onClick={() => void toggle()}>
        {label}
        <i className="tl-caret" aria-hidden="true">
          {open ? '▾' : '▸'}
        </i>
      </button>
      {open ? <div className="tl-detail">{loading ? <p className="muted-note">正在读取…</p> : err ? <p className="inline-error">{err}</p> : <DetailView d={d} />}</div> : null}
    </div>
  );
}

/** 摘要中的 +n / −n 着色 */
function StepSummary({ text }: { text: string }) {
  const parts = text.split(/(\+\d+(?:\s*行)?|−\d+)/);
  return (
    <>
      {parts.map((p, i) =>
        /^\+\d/.test(p) ? (
          <em key={i} className="diff-add">
            {p}
          </em>
        ) : /^−\d/.test(p) ? (
          <em key={i} className="diff-del">
            {p}
          </em>
        ) : (
          p
        ),
      )}
    </>
  );
}

function DetailView({ d }: { d: StepDetail | string | null }) {
  const [source, setSource] = useState(false);
  if (!d) return <p className="muted-note">没有详情。</p>;
  if (typeof d === 'string') return <pre className="tl-pre">{d}</pre>;
  switch (d.kind) {
    case 'write': {
      const isMd = /\.(md|markdown|mdx)$/i.test(d.path);
      return (
        <>
          <div className="tl-detail-head">
            <code>{d.path}</code>
            {isMd ? (
              <button type="button" className="text-button" aria-pressed={source} onClick={() => setSource((v) => !v)}>
                {source ? '阅读' : '源码'}
              </button>
            ) : null}
            <button type="button" className="text-button" onClick={() => openInCode(d.path)}>
              在代码中打开
            </button>
          </div>
          {d.error ? <p className="inline-error">{d.error}</p> : null}
          {isMd && !source ? (
            <Markdown className="tl-md" text={d.preview ?? ''} />
          ) : (
            <pre className="tl-pre">{d.preview ?? ''}</pre>
          )}
          {d.truncated ? <p className="muted-note">内容较长，只显示前一部分。</p> : null}
        </>
      );
    }
    case 'edit':
      return (
        <>
          <div className="tl-detail-head">
            <code>{d.path}</code>
            <button type="button" className="text-button" onClick={() => openInCode(d.path)}>
              在代码中打开
            </button>
          </div>
          {d.error ? <p className="inline-error">{d.error}</p> : null}
          <DiffView diff={d.diff ?? ''} />
        </>
      );
    case 'bash':
      return (
        <pre className="tl-pre tl-bash">
          <b>$ {d.command}</b>
          {'\n'}
          {d.output ?? ''}
          {'\n'}
          <span className={d.exitCode === 0 ? 'diff-add' : 'diff-del'}>{d.blocked ? '（命令被拦截）' : `退出码 ${d.exitCode ?? '?'}`}</span>
        </pre>
      );
    case 'read':
      return (
        <div className="tl-detail-head">
          <code>{d.path}</code>
          {d.lines ? <small>{d.lines} 行</small> : null}
        </div>
      );
    case 'verify': {
      const checks = Array.isArray(d.checks) ? d.checks : [];
      if (!checks.length) return <p className="muted-note">{d.ok === false ? '自检未通过，无详细检查项。' : '自检已通过。'}</p>;
      return (
        <div className="tl-verify tl-verify-inline">
          {checks.map((c, idx) => (
            <div key={`${c.name}-${idx}`} className={`tl-step ${c.skipped ? 'skip' : c.ok ? 'ok' : 'bad'}`} title={c.detail}>
              <span className={`tl-mark ${c.skipped ? 'skip' : c.ok ? 'ok' : 'bad'}`} aria-hidden="true">
                {c.skipped ? '–' : c.ok ? '✓' : '×'}
              </span>
              <span className="tl-step-text">
                {c.name}
                {c.durationMs && c.durationMs >= 100 ? <small className="tl-dur"> ({(c.durationMs / 1000).toFixed(1)}s)</small> : null}
              </span>
              {c.skipped ? (
                <small className="tl-check-detail">已跳过</small>
              ) : c.detail ? (
                <small className="tl-check-detail">{c.detail}</small>
              ) : null}
            </div>
          ))}
        </div>
      );
    }
    case 'tool_output':
      return (
        <>
          <div className="tl-detail-head">
            <span className={`tl-out-badge ${d.ok === false ? 'bad' : 'ok'}`}>{d.ok === false ? '未通过' : '已通过'}</span>
            {d.durationMs && d.durationMs >= 10 ? <small>{d.durationMs < 1000 ? `${d.durationMs}ms` : `${(d.durationMs / 1000).toFixed(1)}s`}</small> : null}
          </div>
          {d.output ? <pre className="tl-pre tl-output">{d.output}</pre> : <p className="muted-note">无输出详情。</p>}
          {d.truncated ? <p className="muted-note">输出较长，只显示前一部分。</p> : null}
        </>
      );
  }
}

export function DiffView({ diff }: { diff: string }) {
  if (!diff.trim()) return <p className="muted-note">没有可显示的改动。</p>;
  return (
    <pre className="tl-pre tl-diff">
      {diff.split('\n').map((l, i) => (
        <span key={i} className={l.startsWith('+++') || l.startsWith('---') ? 'dh' : l.startsWith('@@') ? 'dhunk' : l.startsWith('+') ? 'dadd' : l.startsWith('-') ? 'ddel' : ''}>
          {l || ' '}
          {'\n'}
        </span>
      ))}
    </pre>
  );
}
