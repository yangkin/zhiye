import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';

interface TermEntry {
  id: number;
  command: string;
  ok: boolean;
  exitCode: number;
  durationMs: number;
  stdout: string;
  stderr: string;
}

const QUICK_CMDS = [
  { label: '文件列表', cmd: 'ls -la' },
  { label: 'Git 状态', cmd: 'git status -s' },
  { label: '类型自查', cmd: 'npx tsc --noEmit' },
  { label: 'Node 版本', cmd: 'node -v' },
];

/** 工作台底部活体开发环境抽屉（安全 Web 终端 + 实时 SSE Dev 日志 + Snapshot-to-Deploy 状态）。 */
export function LiveWorkspaceDock({ appId, onClose }: { appId: string; onClose: () => void }) {
  const qc = useQueryClient();
  const [tab, setTab] = useState<'terminal' | 'logs'>('terminal');
  const [cmd, setCmd] = useState('');
  const [runningCmd, setRunningCmd] = useState(false);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [entries, setEntries] = useState<TermEntry[]>([]);
  const [liveLogs, setLiveLogs] = useState<string[]>([]);
  const [height, setHeight] = useState<number>(() => {
    try {
      const saved = Number(localStorage.getItem('zhiye.wb.dockHeight'));
      return Number.isFinite(saved) && saved >= 180 && saved <= 760 ? saved : 300;
    } catch {
      return 300;
    }
  });
  const [expanded, setExpanded] = useState(false);
  const [dragging, setDragging] = useState(false);
  const logBoxRef = useRef<HTMLPreElement>(null);
  const seqRef = useRef(1);

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault();
    const startY = e.clientY;
    const startH = expanded ? Math.round(window.innerHeight * 0.6) : height;
    setExpanded(false);
    setDragging(true);
    document.body.classList.add('is-resizing-v');
    const onMove = (ev: MouseEvent) => {
      const maxH = Math.max(320, Math.round(window.innerHeight * 0.72));
      const next = Math.max(180, Math.min(maxH, startH + (startY - ev.clientY)));
      setHeight(next);
    };
    const onUp = () => {
      setDragging(false);
      document.body.classList.remove('is-resizing-v');
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      setHeight((cur) => {
        try { localStorage.setItem('zhiye.wb.dockHeight', String(cur)); } catch {}
        return cur;
      });
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  };

  const statusQ = useQuery({
    queryKey: ['live-ws', appId],
    queryFn: () => api.liveWorkspaceStatus(appId),
    refetchInterval: 15_000,
  });
  const st = statusQ.data;

  useEffect(() => {
    if (st?.logs?.length && liveLogs.length === 0) setLiveLogs(st.logs);
  }, [st?.logs, liveLogs.length]);

  useEffect(() => {
    const es = new EventSource(api.liveWorkspaceLogsUrl(appId), { withCredentials: true });
    es.addEventListener('log', (ev) => {
      try {
        const d = JSON.parse((ev as MessageEvent).data) as { line?: string };
        if (!d.line) return;
        setLiveLogs((prev) => [...prev.slice(-299), d.line!]);
      } catch {}
    });
    return () => es.close();
  }, [appId]);

  useEffect(() => {
    if (tab === 'logs' && logBoxRef.current) {
      logBoxRef.current.scrollTop = logBoxRef.current.scrollHeight;
    }
  }, [liveLogs, tab]);

  const refresh = () => qc.invalidateQueries({ queryKey: ['live-ws', appId] });

  const doAction = async (kind: 'start' | 'sleep' | 'reset') => {
    setBusyAction(kind);
    try {
      if (kind === 'start') await api.liveWorkspaceStart(appId);
      else if (kind === 'sleep') await api.liveWorkspaceSleep(appId);
      else await api.liveWorkspaceReset(appId);
      await refresh();
      toast(kind === 'start' ? '活体开发环境已启动。' : kind === 'sleep' ? '已休眠（依赖缓存保留）。' : '已重置工作区。');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusyAction(null);
    }
  };

  const runCmd = async (inputCmd?: string) => {
    const c = (inputCmd ?? cmd).trim();
    if (!c || runningCmd) return;
    setRunningCmd(true);
    if (!inputCmd) setCmd('');
    try {
      const r = await api.liveWorkspaceExec(appId, c);
      setEntries((prev) => [
        ...prev.slice(-30),
        { id: seqRef.current++, command: c, ok: r.ok, exitCode: r.exitCode, durationMs: r.durationMs, stdout: r.stdout, stderr: r.stderr },
      ]);
      void refresh();
    } catch (e) {
      setEntries((prev) => [
        ...prev.slice(-30),
        { id: seqRef.current++, command: c, ok: false, exitCode: 1, durationMs: 0, stdout: '', stderr: errorMessage(e) },
      ]);
    } finally {
      setRunningCmd(false);
    }
  };

  const statusText = st?.alive
    ? `运行中${st.devPort ? ` :${st.devPort}` : ''}${st.devMode ? ` (${st.devMode})` : ''}`
    : st?.status === 'sleeping'
      ? '已休眠（缓存热留存）'
      : '未启动';

  return (
    <section
      className={`live-dock ${dragging ? 'is-dragging' : ''}`}
      style={{ height: expanded ? '62vh' : `${height}px` }}
      aria-label="活体开发环境与终端"
    >
      <div
        className="live-dock-resizer"
        role="separator"
        aria-orientation="horizontal"
        aria-label="上下拖拽调整终端高度"
        title="上下拖拽调整高度（双击重置）"
        onMouseDown={startResize}
        onDoubleClick={() => {
          setExpanded(false);
          setHeight(300);
          try { localStorage.setItem('zhiye.wb.dockHeight', '300'); } catch {}
        }}
      >
        <i />
      </div>
      <div className="live-dock-head">
        <div className="live-dock-left">
          <div className="live-dock-title">
            <Icon name="terminal" />
            <b>活体开发环境</b>
            <span className={`live-dock-badge ${st?.alive ? 'ok' : ''}`}>
              <i className={`live-dock-dot ${st?.alive ? 'on' : ''}`} />
              {statusText}
            </span>
            {st?.snapshotReady ? (
              <span className="live-dock-badge ok" title={`depsHash: ${st.depsHash || '-'}`}>
                热缓存就绪
              </span>
            ) : null}
          </div>
          <div className="live-dock-tabs" role="tablist">
            <button type="button" role="tab" aria-selected={tab === 'terminal'} className={tab === 'terminal' ? 'active' : ''} onClick={() => setTab('terminal')}>
              安全终端
            </button>
            <button type="button" role="tab" aria-selected={tab === 'logs'} className={tab === 'logs' ? 'active' : ''} onClick={() => setTab('logs')}>
              实时 Dev 日志 ({liveLogs.length})
            </button>
          </div>
        </div>
        <div className="live-dock-actions">
          {st?.alive ? (
            <button type="button" disabled={!!busyAction} onClick={() => void doAction('sleep')}>
              {busyAction === 'sleep' ? '休眠中…' : '休眠'}
            </button>
          ) : (
            <button type="button" className="primary" disabled={!!busyAction} onClick={() => void doAction('start')}>
              <Icon name="play" />
              {busyAction === 'start' ? '唤醒中…' : '启动 / 唤醒'}
            </button>
          )}
          <button type="button" disabled={!!busyAction} onClick={() => void doAction('reset')}>
            {busyAction === 'reset' ? '重置中…' : '重置'}
          </button>
          <button
            type="button"
            className="icon-only"
            aria-label={expanded ? '还原终端高度' : '放大终端高度'}
            title={expanded ? '还原高度' : '放大终端面板'}
            onClick={() => setExpanded((v) => !v)}
          >
            <Icon name={expanded ? 'contract' : 'expand'} />
          </button>
          <button type="button" className="icon-only" aria-label="关闭活体终端" title="收起活体开发环境" onClick={onClose}>
            <Icon name="close" />
          </button>
        </div>
      </div>

      {tab === 'terminal' ? (
        <div className="live-dock-term">
          <div className="live-dock-presets">
            <span className="live-dock-presets-label">快捷诊断：</span>
            {QUICK_CMDS.map((q) => (
              <button key={q.cmd} type="button" disabled={runningCmd} onClick={() => void runCmd(q.cmd)}>
                {q.label}
              </button>
            ))}
            {st?.alive && st.devPort ? (
              <button type="button" disabled={runningCmd} onClick={() => void runCmd(`curl -sS -i http://127.0.0.1:${st.devPort}/ | head -n 12`)}>
                探活 :{st.devPort}
              </button>
            ) : null}
          </div>
          <div className="live-dock-console">
            <div className="live-dock-output">
              {entries.length === 0 ? (
                <div className="live-dock-empty">
                  在隔离的应用工作区内运行安全诊断命令（自动拦截越权路径、外部联网与 <code>.env</code> 读取）。点击上方快捷按钮或在下方输入命令执行。
                </div>
              ) : (
                entries.map((it) => (
                  <div key={it.id} className="live-dock-entry">
                    <div className="live-dock-cmd">
                      <span>$ {it.command}</span>
                      <em className={it.ok ? 'ok' : 'bad'}>
                        exit {it.exitCode} · {it.durationMs}ms
                      </em>
                    </div>
                    {it.stdout ? <pre>{it.stdout}</pre> : null}
                    {it.stderr ? <pre className="err">{it.stderr}</pre> : null}
                  </div>
                ))
              )}
            </div>
            <form
              className="live-dock-input"
              onSubmit={(e) => {
                e.preventDefault();
                void runCmd();
              }}
            >
              <span>$</span>
              <input
                placeholder="输入安全诊断命令，如 git status、npx tsc --noEmit、ls -la…"
                aria-label="终端命令输入"
                value={cmd}
                disabled={runningCmd}
                onChange={(e) => setCmd(e.target.value)}
              />
              <button type="submit" disabled={runningCmd || !cmd.trim()}>
                {runningCmd ? '执行中…' : '执行'}
              </button>
            </form>
          </div>
        </div>
      ) : (
        <div className="live-dock-console is-logs">
          <pre ref={logBoxRef} className="live-dock-logs">
            {liveLogs.length ? liveLogs.join('\n') : '暂无实时日志。点击右上角「启动 / 唤醒」后将在此实时流式输出。'}
          </pre>
        </div>
      )}
    </section>
  );
}
