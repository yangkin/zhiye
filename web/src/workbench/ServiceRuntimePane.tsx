import { Fragment, useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import type { AppDetail, MonolithEnvVar } from '../api/types';
import { copyText, fmtTime } from '../lib/format';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { ServiceCicdTopologyTab, ServiceDbTab } from './DeployEngineTabs';
import { CloudTriggersTab } from './CloudTriggersTab';

type SubTab = 'config' | 'env' | 'logs' | 'db' | 'triggers' | 'cicd' | 'dockerfile';
type LogView = 'runtime' | 'build' | 'sse' | 'releases';

const STATUS_META: Record<string, { label: string; tone: string }> = {
  running: { label: '运行中', tone: 'ok' },
  sleeping: { label: '休眠中 · 访问自动唤醒', tone: 'warn' },
  building: { label: '构建部署中…', tone: 'info' },
  stopped: { label: '已停止', tone: 'muted' },
  failed: { label: '启动异常', tone: 'err' },
};

const DOCKER_KEYWORDS = new Set([
  'FROM',
  'AS',
  'WORKDIR',
  'ARG',
  'ENV',
  'RUN',
  'COPY',
  'ADD',
  'EXPOSE',
  'USER',
  'CMD',
  'ENTRYPOINT',
  'HEALTHCHECK',
  'LABEL',
  'VOLUME',
  'SHELL',
  'STOPSIGNAL',
]);

/** 将原始运行/构建日志解析为带级别、时间戳、会话分隔线的结构化行 */
function parseLogLines(raw: string) {
  if (!raw || !raw.trim()) return [];
  return raw.replace(/\r\n/g, '\n').split('\n').map((text, idx) => {
    const trimmed = text.trim();
    const isBanner = /^={3,}\s*\[.*\]\s*={3,}$/.test(trimmed);
    const isErr = /\b(ERROR|FATAL|SEVERE|Exception|Traceback|BUILD FAILURE|exit=[1-9]\d*|FAILED)\b/i.test(text);
    const isWarn = !isErr && /\b(WARN|WARNING)\b/i.test(text);
    const isSys = !isErr && !isWarn && (/^\[zhiye\]/i.test(trimmed) || /^\[k8s\]/i.test(trimmed) || /\b(Started |listening on|服务启动成功|BUILD SUCCESS)\b/i.test(text));
    const level: 'banner' | 'err' | 'warn' | 'sys' | 'info' = isBanner
      ? 'banner'
      : isErr
        ? 'err'
        : isWarn
          ? 'warn'
          : isSys
            ? 'sys'
            : 'info';
    return { num: idx + 1, text, level };
  });
}

/** 对日志正文做轻量高亮（时间戳、级别词、[zhiye] 标签、URL） */
function renderLogText(line: string) {
  const re = /(\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?Z?|\b(?:ERROR|FATAL|WARN|WARNING|INFO|DEBUG)\b|\[(?:zhiye|k8s|[a-z0-9_-]+)\]|https?:\/\/[^\s)]+)/gi;
  const parts: React.ReactNode[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  let k = 0;
  while ((m = re.exec(line)) !== null) {
    if (m.index > last) parts.push(line.slice(last, m.index));
    const tok = m[0];
    let cls = 'lg-ts';
    if (/^(ERROR|FATAL)$/i.test(tok)) cls = 'lg-err';
    else if (/^(WARN|WARNING)$/i.test(tok)) cls = 'lg-warn';
    else if (/^(INFO|DEBUG)$/i.test(tok)) cls = 'lg-info';
    else if (tok.startsWith('[')) cls = 'lg-tag';
    else if (tok.startsWith('http')) cls = 'lg-url';
    parts.push(
      <span key={k++} className={cls}>
        {tok}
      </span>,
    );
    last = m.index + tok.length;
  }
  if (last < line.length) parts.push(line.slice(last));
  return parts.length ? parts : line || ' ';
}

/** 对 Dockerfile 逐行做词法高亮（指令关键字、注释、变量、标志位、字符串） */
function renderDockerfileLine(line: string) {
  const trimmed = line.trim();
  if (!trimmed) return ' ';
  if (trimmed.startsWith('#')) {
    return <span className="df-comment">{line}</span>;
  }
  const leadSpaces = line.match(/^\s*/)?.[0] ?? '';
  const body = line.slice(leadSpaces.length);
  const firstWordMatch = body.match(/^([A-Za-z]+)(\s+|$)/);
  const nodes: React.ReactNode[] = [];
  if (leadSpaces) nodes.push(leadSpaces);
  let rest = body;
  if (firstWordMatch && DOCKER_KEYWORDS.has(firstWordMatch[1].toUpperCase())) {
    nodes.push(
      <span key="kw" className="df-kw">
        {firstWordMatch[1]}
      </span>,
    );
    nodes.push(firstWordMatch[2]);
    rest = body.slice(firstWordMatch[0].length);
  }
  const tokRe = /(\$\{[^}]+\}|\$[A-Za-z_][A-Za-z0-9_]*|"[^"]*"|'[^']*'|--[a-zA-Z0-9_-]+(?:=[^\s]+)?|\bAS\b|&&|\\$)/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let k = 0;
  while ((m = tokRe.exec(rest)) !== null) {
    if (m.index > last) nodes.push(rest.slice(last, m.index));
    const tok = m[0];
    let cls = 'df-str';
    if (tok.startsWith('$')) cls = 'df-var';
    else if (tok.startsWith('--')) cls = 'df-flag';
    else if (tok === 'AS') cls = 'df-kw';
    else if (tok === '&&' || tok === '\\') cls = 'df-op';
    nodes.push(
      <span key={`t${k++}`} className={cls}>
        {tok}
      </span>,
    );
    last = m.index + tok.length;
  }
  if (last < rest.length) nodes.push(rest.slice(last));
  return nodes;
}

function Switch({
  checked,
  onChange,
  ariaLabel,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  ariaLabel: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={ariaLabel}
      className={`svc-switch ${checked ? 'is-on' : ''}`}
      onClick={() => onChange(!checked)}
    >
      <span />
    </button>
  );
}

export function ServiceRuntimePane({ app }: { app: AppDetail }) {
  const qc = useQueryClient();
  const [sub, setSub] = useState<SubTab>('config');
  const [logView, setLogView] = useState<LogView>('runtime');
  const [logQuery, setLogQuery] = useState('');
  const [onlyErrors, setOnlyErrors] = useState(false);
  const [wrapLogs, setWrapLogs] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);
  const [revealedKeys, setRevealedKeys] = useState<Record<number, boolean>>({});

  const statusQ = useQuery({
    queryKey: ['service-status', app.id],
    queryFn: () => api.serviceStatus(app.id),
    refetchInterval: (q) => (q.state.data?.status === 'building' ? 2000 : 12000),
  });
  const logsQ = useQuery({
    queryKey: ['service-logs', app.id],
    queryFn: () => api.serviceLogs(app.id, 400),
    enabled: sub === 'logs',
    refetchInterval: sub === 'logs' ? 5000 : false,
  });
  const dfQ = useQuery({
    queryKey: ['service-dockerfile', app.id],
    queryFn: () => api.serviceDockerfile(app.id),
    enabled: sub === 'dockerfile',
  });
  const metricsQ = useQuery({
    queryKey: ['service-metrics', app.id],
    queryFn: () => api.serviceMetrics(app.id),
    refetchInterval: 10000,
  });

  // 实时 SSE 部署流水日志
  const [sseLines, setSseLines] = useState<{ type: string; stage: string; line: string; ts: string }[]>([]);
  useEffect(() => {
    if (sub !== 'logs' || logView !== 'sse') return;
    const es = new EventSource(api.serviceDeployLogStreamUrl(app.id));
    const push = (e: MessageEvent) => {
      try {
        const d = JSON.parse(e.data);
        setSseLines((prev) => [...prev.slice(-220), d]);
      } catch {
        setSseLines((prev) => [...prev.slice(-220), { type: 'log', stage: 'build', line: String(e.data), ts: '' }]);
      }
    };
    es.addEventListener('stage', push);
    es.addEventListener('log', push);
    es.addEventListener('done', push);
    es.addEventListener('error', push);
    return () => es.close();
  }, [app.id, sub, logView]);

  const svc = statusQ.data;
  const metrics = metricsQ.data;

  // 配置表单状态
  const [buildCmd, setBuildCmd] = useState('');
  const [startCmd, setStartCmd] = useState('');
  const [port, setPort] = useState(8080);
  const [healthPath, setHealthPath] = useState('/');
  const [memoryMb, setMemoryMb] = useState(512);
  const [cpuMillicores, setCpuMillicores] = useState(500);
  const [idleSleepMin, setIdleSleepMin] = useState(2880);
  const [pgEnabled, setPgEnabled] = useState(false);
  const [pvcEnabled, setPvcEnabled] = useState(false);
  const [pvcSizeGi, setPvcSizeGi] = useState(1);

  // 环境变量表单
  const [envRows, setEnvRows] = useState<{ key: string; value: string; secret: boolean; keepExisting: boolean }[]>([]);

  useEffect(() => {
    if (!svc?.configured) return;
    setBuildCmd(svc.buildCmd ?? '');
    setStartCmd(svc.startCmd ?? '');
    setPort(svc.port ?? 8080);
    setHealthPath(svc.healthPath ?? '/');
    setMemoryMb(svc.memoryMb ?? 512);
    setCpuMillicores(svc.cpuMillicores ?? 500);
    setIdleSleepMin(svc.idleSleepMin ?? 2880);
    setPgEnabled(!!svc.pgEnabled);
    setPvcEnabled(!!svc.pvcEnabled);
    setPvcSizeGi(svc.pvcSizeGi ?? 1);
    setEnvRows(
      (svc.envVars ?? []).map((v: MonolithEnvVar) => ({
        key: v.key,
        value: v.secret ? '' : v.value,
        secret: v.secret,
        keepExisting: v.secret,
      })),
    );
  }, [svc?.updatedAt, svc?.configured]);

  const runAction = async (kind: string, fn: () => Promise<unknown>, okMsg: string) => {
    if (busy) return;
    setBusy(kind);
    try {
      await fn();
      toast(okMsg);
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      await Promise.allSettled([
        qc.invalidateQueries({ queryKey: ['service-status', app.id] }),
        qc.invalidateQueries({ queryKey: ['service-logs', app.id] }),
        qc.invalidateQueries({ queryKey: ['app', app.id] }),
      ]);
      setBusy(null);
    }
  };

  const st = STATUS_META[svc?.status ?? 'stopped'] ?? STATUS_META.stopped;

  const activeLogText = logView === 'runtime' ? logsQ.data?.runtimeLogs ?? '' : logsQ.data?.buildLog ?? '';
  const parsedLogs = useMemo(() => {
    const all = parseLogLines(activeLogText);
    const q = logQuery.trim().toLowerCase();
    return all.filter((l) => {
      if (onlyErrors && l.level !== 'err' && l.level !== 'warn') return false;
      if (q && !l.text.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [activeLogText, logQuery, onlyErrors]);

  const dockerfileLines = useMemo(() => {
    const raw = dfQ.data?.content ?? '';
    return raw ? raw.replace(/\r\n/g, '\n').split('\n') : [];
  }, [dfQ.data?.content]);

  const saveConfigPayload = () => ({
    buildCmd,
    startCmd,
    port,
    healthPath,
    memoryMb,
    cpuMillicores,
    idleSleepMin,
    pgEnabled,
    pvcEnabled,
    pvcSizeGi,
  });

  const maxMem = svc?.quota?.maxMemoryMb ?? 1024;
  const maxCpu = svc?.quota?.maxCpuMillicores ?? 1000;
  const maxPvc = svc?.quota?.maxPvcSizeGi ?? 5;
  const minIdle = svc?.quota?.allowNeverSleep ? 0 : (svc?.quota?.minIdleSleepMinutes ?? 5);
  const allowNeverSleep = !!svc?.quota?.allowNeverSleep;

  // 即时表单校验：输入越界或非法变量名时立即标红提示，无需等提交报错
  const portErr = !Number.isFinite(port) || port < 1024 || port > 65535 ? '端口范围须在 1024～65535 之间' : '';
  const memErr = !Number.isFinite(memoryMb) || memoryMb < 128 || memoryMb > maxMem ? `内存配额须在 128～${maxMem} MB 之间` : '';
  const cpuErr = !Number.isFinite(cpuMillicores) || cpuMillicores < 100 || cpuMillicores > maxCpu ? `CPU 配额须在 100～${maxCpu} mCPU 之间` : '';
  const idleErr = !Number.isFinite(idleSleepMin) || idleSleepMin < minIdle || idleSleepMin > 10080 ? `休眠超时须在 ${minIdle}～10080 分钟之间` : '';
  const pvcErr = pvcEnabled && (!Number.isFinite(pvcSizeGi) || pvcSizeGi < 1 || pvcSizeGi > maxPvc) ? `卷容量须在 1～${maxPvc} Gi 之间` : '';
  const hasConfigErr = Boolean(portErr || memErr || cpuErr || idleErr || pvcErr);

  const RESERVED_ENV_KEYS = new Set(['PORT', 'SERVER_PORT', 'HOST', 'DATA_DIR', 'STATIC_DIR']);
  const envRowErrors = useMemo(() => {
    const counts = new Map<string, number>();
    for (const r of envRows) {
      const k = r.key.trim();
      if (k) counts.set(k, (counts.get(k) ?? 0) + 1);
    }
    return envRows.map((r) => {
      const k = r.key.trim();
      if (!k) return r.value.trim() ? '变量名不能为空' : '';
      if (!/^[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(k)) return '仅允许字母、数字和下划线，且不能以数字开头';
      if (RESERVED_ENV_KEYS.has(k.toUpperCase())) return `${k.toUpperCase()} 为平台保留变量，不可覆盖`;
      if ((counts.get(k) ?? 0) > 1) return `变量名 ${k} 重复`;
      return '';
    });
  }, [envRows]);
  const hasEnvErr = envRowErrors.some(Boolean);

  return (
    <div className="svc-pane">
      {/* 顶部运行状态概览与操作条 */}
      <header className="svc-head">
        <div className="svc-head-main">
          <div className="svc-title-row">
            <span className={`svc-badge is-${st.tone}`}>
              <i />
              {st.label}
            </span>
            <h3 className="svc-framework-title">{svc?.framework || app.framework || '单体应用'}</h3>
            <span className="svc-chip is-accent">{(svc?.runtime || 'service').toUpperCase()}</span>
            <span className="svc-chip">
              {svc?.provider === 'k8s' ? `K8s · ${svc.k8sNamespace}` : '本地隔离进程'}
            </span>
            <span className="svc-chip is-mono">
              {memoryMb} MB · {cpuMillicores} mCPU
            </span>
            {metrics?.running && metrics.rssMb != null ? (
              <span className="svc-chip is-mono" title={`PID ${metrics.pid ?? '—'} · 端口 ${metrics.port ?? '—'} · 历史成功率 ${metrics.releases.successRate}%`}>
                实占 {metrics.rssMb} MB{metrics.cpuPercent != null ? ` · CPU ${metrics.cpuPercent}%` : ''}
              </span>
            ) : null}
          </div>
          <div className="svc-meta-row">
            {app.siteUrl ? (
              <div className="svc-url-pill">
                <Icon name="globe" />
                <a href={app.siteUrl} target="_blank" rel="noopener noreferrer">
                  {app.siteUrl.replace(/^https?:\/\//, '').replace(/\/$/, '')}
                </a>
                <button
                  type="button"
                  className="svc-url-act"
                  title="复制访问地址"
                  onClick={async () => toast((await copyText(app.siteUrl!)) ? '已复制站点地址。' : '请手动复制。')}
                >
                  <Icon name="copy" />
                </button>
                <a className="svc-url-act" href={app.siteUrl} target="_blank" rel="noopener noreferrer" title="在新标签页打开">
                  <Icon name="external" />
                </a>
              </div>
            ) : (
              <span className="svc-meta-note">暂未发布公网地址，部署就绪后可点击右上角「发布」</span>
            )}
            {svc?.lastStartedAt ? <span className="svc-meta-note">最近启动 {fmtTime(svc.lastStartedAt)}</span> : null}
          </div>
        </div>
        <div className="svc-actions">
          {svc?.canPromotePreview ? (
            <button
              type="button"
              className="subtle-button"
              disabled={!!busy}
              title="将已验证的预览槽位产物 0 重建零停机晋升到线上"
              onClick={() => void runAction('promote', () => api.servicePromotePreview(app.id), '预览槽位已 0 重建零停机晋升上线！')}
            >
              <Icon name="arrow" />
              {busy === 'promote' ? '晋升中…' : '0重建晋升'}
            </button>
          ) : null}
          <button
            type="button"
            className="primary-button"
            disabled={!!busy}
            onClick={() => void runAction('deploy', () => api.serviceDeploy(app.id), '已蓝绿零停机重新构建并部署单体服务。')}
          >
            <Icon name="refresh" className={busy === 'deploy' ? 'spinning' : ''} />
            {busy === 'deploy' ? '部署中…' : '重新部署'}
          </button>
          {svc?.status === 'running' ? (
            <div className="svc-btn-group">
              <button
                type="button"
                className="svc-ghost-btn"
                disabled={!!busy}
                onClick={() => void runAction('restart', () => api.serviceRestart(app.id), '服务已重启。')}
              >
                重启
              </button>
              <button
                type="button"
                className="svc-ghost-btn"
                disabled={!!busy}
                onClick={() =>
                  void runAction('sleep', () => api.serviceSleep(app.id), '服务已休眠（缩容到 0），下次访问将自动唤醒。')
                }
              >
                休眠
              </button>
              <button
                type="button"
                className="svc-ghost-btn is-danger"
                disabled={!!busy}
                onClick={() => void runAction('stop', () => api.serviceStop(app.id), '服务已停止。')}
              >
                停止
              </button>
            </div>
          ) : (
            <button
              type="button"
              className="subtle-button"
              disabled={!!busy}
              onClick={() => void runAction('start', () => api.serviceStart(app.id), '服务已启动就绪。')}
            >
              <Icon name="play" />
              启动服务
            </button>
          )}
        </div>
      </header>

      {svc?.lastError ? (
        <div className="svc-error-banner" role="alert">
          <div className="svc-error-head">
            <Icon name="alert" />
            <b>{svc.diagnosis ? `${svc.diagnosis.title} (${svc.diagnosis.category})` : '最近一次构建 / 启动异常'}</b>
            <div style={{ marginLeft: 'auto', display: 'flex', gap: 10, alignItems: 'center' }}>
              <button
                type="button"
                className="primary-button"
                style={{ padding: '4px 10px', fontSize: 12 }}
                disabled={!!busy}
                onClick={() =>
                  void runAction(
                    'auto-heal',
                    () => api.serviceAutoHeal(app.id),
                    '已创建 AI 部署自愈任务！请在左侧对话栏观察自动修复与验证进度。',
                  )
                }
              >
                <Icon name="spark" /> {busy === 'auto-heal' ? '启动自愈…' : 'AI 一键自愈修复'}
              </button>
              <button
                type="button"
                className="svc-link-btn"
                onClick={() => {
                  setSub('logs');
                  setLogView('runtime');
                }}
              >
                查看完整日志 →
              </button>
            </div>
          </div>
          {svc.diagnosis?.suggestedFixes?.length ? (
            <div style={{ fontSize: 12, marginBottom: 6, opacity: 0.9 }}>
              <b>诊断建议：</b>
              {svc.diagnosis.suggestedFixes.join('；')}
            </div>
          ) : null}
          <pre>{svc.lastError}</pre>
        </div>
      ) : null}

      {/* 分段子导航 */}
      <nav className="svc-subtabs" role="tablist" aria-label="服务托管配置视图">
        {(
          [
            ['config', '运行与存储配置', 'settings'],
            ['env', `环境变量 (${svc?.envVars?.length ?? 0})`, 'lock'],
            ['logs', '日志与发布回滚', 'terminal'],
            ['db', 'PostgreSQL 控制台', 'database'],
            ['triggers', '自动化与飞书通知', 'spark'],
            ['cicd', 'CI/CD 与多服务拓扑', 'globe'],
            ['dockerfile', 'Dockerfile', 'code'],
          ] as const
        ).map(([k, label, icon]) => (
          <button
            key={k}
            type="button"
            role="tab"
            aria-selected={sub === k}
            className={sub === k ? 'active' : ''}
            onClick={() => setSub(k)}
          >
            <Icon name={icon} />
            <span>{label}</span>
          </button>
        ))}
      </nav>

      {/* 主内容区 */}
      <div className="svc-body">
        {sub === 'config' ? (
          <div className="svc-config-stack">
            {/* 区块 1：运行参数与计算配额 */}
            <section className="svc-section-card">
              <div className="svc-section-head">
                <div>
                  <h4>运行参数与计算配额</h4>
                  <p>平台通过环境变量 <code>PORT</code> / <code>SERVER_PORT</code> 自动注入监听端口，并按配额限制容器资源。</p>
                </div>
              </div>
              <div className="svc-grid-4">
                <label className="svc-field">
                  <span>监听端口</span>
                  <div className="svc-input-unit" style={portErr ? { borderColor: '#dc2626' } : undefined}>
                    <input
                      type="number"
                      min={1024}
                      max={65535}
                      value={port}
                      onChange={(e) => setPort(Number(e.target.value))}
                    />
                    <em>TCP</em>
                  </div>
                  {portErr ? <small style={{ color: '#dc2626', fontSize: 11.5 }}>{portErr}</small> : null}
                </label>
                <label className="svc-field">
                  <span>健康检查路径</span>
                  <input
                    type="text"
                    className="is-mono"
                    value={healthPath}
                    onChange={(e) => setHealthPath(e.target.value)}
                    placeholder="/health"
                  />
                </label>
                <label className="svc-field">
                  <span>内存配额（上限 {maxMem} MB）</span>
                  <div className="svc-input-unit" style={memErr ? { borderColor: '#dc2626' } : undefined}>
                    <input
                      type="number"
                      min={128}
                      max={maxMem}
                      step={128}
                      value={memoryMb}
                      onChange={(e) => setMemoryMb(Number(e.target.value))}
                    />
                    <em>MB</em>
                  </div>
                  {memErr ? <small style={{ color: '#dc2626', fontSize: 11.5 }}>{memErr}</small> : null}
                </label>
                <label className="svc-field">
                  <span>CPU 配额（上限 {maxCpu} mCPU）</span>
                  <div className="svc-input-unit" style={cpuErr ? { borderColor: '#dc2626' } : undefined}>
                    <input
                      type="number"
                      min={100}
                      max={maxCpu}
                      step={100}
                      value={cpuMillicores}
                      onChange={(e) => setCpuMillicores(Number(e.target.value))}
                    />
                    <em>mCPU</em>
                  </div>
                  {cpuErr ? <small style={{ color: '#dc2626', fontSize: 11.5 }}>{cpuErr}</small> : null}
                </label>
              </div>
              {svc?.quota ? (
                <p className="svc-footer-hint" style={{ marginTop: 10 }}>
                  单应用资源上限 <b>1C 1G</b>（{maxCpu} mCPU / {maxMem} MB），超出上限不予部署；当前工作空间运行中已用{' '}
                  <code>{svc.quota.workspaceRunningMemoryMb}/{svc.quota.maxWorkspaceRunningMemoryMb} MB</code> ·{' '}
                  <code>{svc.quota.workspaceRunningCpuMillicores}/{svc.quota.maxWorkspaceRunningCpuMillicores} mCPU</code>
                </p>
              ) : null}
            </section>

            {/* 区块 2：构建与启动命令 */}
            <section className="svc-section-card">
              <div className="svc-section-head">
                <div>
                  <h4>构建与启动命令</h4>
                  <p>支持 <code>{'${PORT}'}</code> 占位变量；留空构建命令时按语言规范自动安装依赖并编译。</p>
                </div>
              </div>
              <div className="svc-cmd-stack">
                <label className="svc-field">
                  <span>构建命令</span>
                  <div className="svc-cmd-input">
                    <i>$</i>
                    <input
                      type="text"
                      value={buildCmd}
                      onChange={(e) => setBuildCmd(e.target.value)}
                      placeholder="pip install -r requirements.txt / mvn -B -DskipTests package / go build -o app ."
                    />
                  </div>
                </label>
                <label className="svc-field">
                  <span>启动命令</span>
                  <div className="svc-cmd-input">
                    <i>$</i>
                    <input
                      type="text"
                      value={startCmd}
                      onChange={(e) => setStartCmd(e.target.value)}
                      placeholder="java -jar app.jar --server.port=${PORT:-8080}"
                    />
                  </div>
                </label>
              </div>
            </section>

            {/* 区块 3：弹性休眠与持久化存储 */}
            <section className="svc-section-card">
              <div className="svc-section-head">
                <div>
                  <h4>弹性休眠与持久化存储</h4>
                  <p>按需开启闲置缩容到 0、每应用独立 PostgreSQL Schema 或 <code>/data</code> 持久化卷。</p>
                </div>
              </div>
              <div className="svc-feature-grid">
                {/* 卡片 1：闲置自动休眠 */}
                <div className={`svc-feature-card ${idleSleepMin > 0 ? 'is-active' : ''}`}>
                  <div className="svc-feature-top">
                    <div className="svc-feature-title">
                      <span className="svc-feature-ico is-violet">
                        <Icon name="cloud" />
                      </span>
                      <div>
                        <b>闲置自动休眠</b>
                        <small>Scale-to-Zero</small>
                      </div>
                    </div>
                    {allowNeverSleep ? (
                      <Switch
                        checked={idleSleepMin > 0}
                        ariaLabel="开启闲置自动休眠"
                        onChange={(on) => setIdleSleepMin(on ? 20 : 0)}
                      />
                    ) : (
                      <span className="svc-runtime-chip">强制开启</span>
                    )}
                  </div>
                  <p className="svc-feature-desc">
                    连续无访问超过设定时长后 Pod 自动缩容为 0（释放 CPU/内存，数据保留），再次访问自动唤醒（默认 2880 分钟即 2 天，可设 {minIdle}～10080 分钟）。
                  </p>
                  <div className="svc-feature-control">
                    <span>无访问超时</span>
                    <div className="svc-inline-stepper">
                      <input
                        type="number"
                        min={minIdle}
                        max={10080}
                        step={60}
                        value={idleSleepMin}
                        onChange={(e) => setIdleSleepMin(Math.max(minIdle, Math.min(10080, Number(e.target.value))))}
                      />
                      <em>
                        分钟{idleSleepMin >= 1440 ? `（${(idleSleepMin / 1440).toFixed(idleSleepMin % 1440 === 0 ? 0 : 1)}天）` : idleSleepMin >= 60 ? `（${(idleSleepMin / 60).toFixed(idleSleepMin % 60 === 0 ? 0 : 1)}小时）` : ''}
                      </em>
                    </div>
                  </div>
                </div>

                {/* 卡片 2：独立 PostgreSQL Schema */}
                <div className={`svc-feature-card ${pgEnabled ? 'is-active' : ''}`}>
                  <div className="svc-feature-top">
                    <div className="svc-feature-title">
                      <span className="svc-feature-ico is-teal">
                        <Icon name="database" />
                      </span>
                      <div>
                        <b>托管 PostgreSQL Schema</b>
                        <small>隔离数据库实例</small>
                      </div>
                    </div>
                    <Switch checked={pgEnabled} ariaLabel="开启托管 PostgreSQL 独立 Schema" onChange={setPgEnabled} />
                  </div>
                  <p className="svc-feature-desc">
                    自动分配每应用隔离 Schema 与独立凭据，运行时直接注入连接串环境变量：
                  </p>
                  <div className="svc-code-pills">
                    <code>DATABASE_URL</code>
                    <code>JDBC_DATABASE_URL</code>
                    {svc?.pgSchema ? <code className="is-ok">Schema: {svc.pgSchema}</code> : null}
                  </div>
                </div>

                {/* 卡片 3：/data 持久化卷 */}
                <div className={`svc-feature-card ${pvcEnabled ? 'is-active' : ''}`}>
                  <div className="svc-feature-top">
                    <div className="svc-feature-title">
                      <span className="svc-feature-ico is-amber">
                        <Icon name="folder" />
                      </span>
                      <div>
                        <b>挂载 /data 持久化卷</b>
                        <small>SQLite 与本地文件</small>
                      </div>
                    </div>
                    <Switch checked={pvcEnabled} ariaLabel="开启 /data 持久化卷" onChange={setPvcEnabled} />
                  </div>
                  <p className="svc-feature-desc">
                    自动注入环境变量 <code className="svc-inline-code">DATA_DIR=/data</code>，重启或休眠唤醒后数据持久保留。
                  </p>
                  <div className="svc-feature-control">
                    <span>持久化容量</span>
                    <div className="svc-inline-stepper">
                      <input
                        type="number"
                        min={1}
                        max={maxPvc}
                        disabled={!pvcEnabled}
                        value={pvcSizeGi}
                        onChange={(e) => setPvcSizeGi(Math.max(1, Math.min(maxPvc, Number(e.target.value))))}
                      />
                      <em>Gi</em>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            {/* 底部提交栏 */}
            <footer className="svc-footer-bar">
              <span className="svc-footer-hint">
                网关自动剥离客户端伪造头并注入可信华星 SSO 身份头（<code>X-Zhiye-User-*</code>）。
              </span>
              <div className="svc-actions">
                <button
                  type="button"
                  className="subtle-button"
                  disabled={!!busy || hasConfigErr}
                  onClick={() =>
                    void runAction('save-cfg', () => api.serviceUpdateConfig(app.id, saveConfigPayload()), '服务配置已保存。')
                  }
                >
                  {busy === 'save-cfg' ? '保存中…' : '仅保存'}
                </button>
                <button
                  type="button"
                  className="primary-button"
                  disabled={!!busy || hasConfigErr}
                  onClick={() =>
                    void runAction(
                      'save-cfg-deploy',
                      async () => {
                        await api.serviceUpdateConfig(app.id, saveConfigPayload());
                        return api.serviceDeploy(app.id);
                      },
                      '配置已保存并重新部署生效。',
                    )
                  }
                >
                  {busy === 'save-cfg-deploy' ? '保存并部署中…' : '保存并重新部署'}
                </button>
              </div>
            </footer>
          </div>
        ) : sub === 'env' ? (
          <div className="svc-config-stack">
            <section className="svc-section-card">
              <div className="svc-section-head">
                <div>
                  <h4>密文环境变量</h4>
                  <p>
                    全部键值经 <code>AES-256-GCM</code> 加密落库，运行态通过 K8s Secret / 隔离进程注入（保留变量{' '}
                    <code>PORT</code> / <code>DATA_DIR</code> 不可覆盖）。
                  </p>
                </div>
                <button
                  type="button"
                  className="subtle-button"
                  onClick={() =>
                    setEnvRows((prev) => [...prev, { key: '', value: '', secret: true, keepExisting: false }])
                  }
                >
                  <Icon name="plus" />
                  新增变量
                </button>
              </div>

              {envRows.length === 0 ? (
                <div className="svc-empty-box">
                  <Icon name="lock" />
                  <b>暂无自定义环境变量</b>
                  <p>可点击右上角「新增变量」添加 API 密钥、数据库开关（如 <code>STORAGE=sqlite</code>）或业务配置。</p>
                </div>
              ) : (
                <div className="svc-env-table">
                  <div className="svc-env-header">
                    <span>变量名 (KEY)</span>
                    <span>变量值 (VALUE)</span>
                    <span>可见性</span>
                    <span />
                  </div>
                  {envRows.map((row, idx) => {
                    const revealed = !!revealedKeys[idx];
                    const rowErr = envRowErrors[idx];
                    return (
                      <div key={idx} style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                        <div className="svc-env-row">
                          <input
                            type="text"
                            className="is-mono"
                            placeholder="KEY_NAME"
                            style={rowErr ? { borderColor: '#dc2626' } : undefined}
                            value={row.key}
                            onChange={(e) =>
                              setEnvRows((prev) => prev.map((r, i) => (i === idx ? { ...r, key: e.target.value } : r)))
                            }
                          />
                          <input
                            type={row.secret && !revealed ? 'password' : 'text'}
                            className="is-mono"
                            placeholder={row.keepExisting ? '已加密保存（留空保持不变，输入新值可覆盖）' : '输入变量值'}
                            value={row.value}
                            onChange={(e) =>
                              setEnvRows((prev) =>
                                prev.map((r, i) =>
                                  i === idx ? { ...r, value: e.target.value, keepExisting: false } : r,
                                ),
                              )
                            }
                          />
                          <div className="svc-env-controls">
                            <label className="svc-env-sec" title="勾选后密文脱敏展示">
                              <input
                                type="checkbox"
                                checked={row.secret}
                                onChange={(e) =>
                                  setEnvRows((prev) =>
                                    prev.map((r, i) => (i === idx ? { ...r, secret: e.target.checked } : r)),
                                  )
                                }
                              />
                              机密
                            </label>
                            {row.secret && !row.keepExisting ? (
                              <button
                                type="button"
                                className="svc-icon-btn"
                                aria-label={revealed ? '隐藏明文' : '显示明文'}
                                title={revealed ? '隐藏明文' : '显示明文'}
                                onClick={() => setRevealedKeys((prev) => ({ ...prev, [idx]: !prev[idx] }))}
                              >
                                <Icon name="eye" />
                              </button>
                            ) : null}
                          </div>
                          <button
                            type="button"
                            className="svc-icon-btn is-danger"
                            aria-label="删除变量"
                            title="删除"
                            onClick={() => setEnvRows((prev) => prev.filter((_, i) => i !== idx))}
                          >
                            <Icon name="trash" />
                          </button>
                        </div>
                        {rowErr ? <small style={{ color: '#dc2626', fontSize: 11.5, paddingLeft: 2 }}>{rowErr}</small> : null}
                      </div>
                    );
                  })}
                </div>
              )}
            </section>

            <footer className="svc-footer-bar">
              <span className="svc-footer-hint">修改环境变量后建议点击「保存并重启生效」，新进程将立即加载最新配置。</span>
              <div className="svc-actions">
                <button
                  type="button"
                  className="subtle-button"
                  disabled={!!busy || hasEnvErr}
                  onClick={() =>
                    void runAction(
                      'save-env',
                      () => api.serviceUpdateEnv(app.id, envRows.filter((r) => r.key.trim())),
                      '环境变量已加密保存。',
                    )
                  }
                >
                  {busy === 'save-env' ? '保存中…' : '仅保存'}
                </button>
                <button
                  type="button"
                  className="primary-button"
                  disabled={!!busy || hasEnvErr}
                  onClick={() =>
                    void runAction(
                      'save-env-apply',
                      async () => {
                        await api.serviceUpdateEnv(app.id, envRows.filter((r) => r.key.trim()));
                        return api.serviceRestart(app.id);
                      },
                      '环境变量已保存并重启服务生效。',
                    )
                  }
                >
                  {busy === 'save-env-apply' ? '保存并重启中…' : '保存并重启生效'}
                </button>
              </div>
            </footer>
          </div>
        ) : sub === 'logs' ? (
          <div className="svc-logs-shell">
            <div className="svc-logs-toolbar">
              <div className="svc-seg" role="tablist" aria-label="日志视图切换">
                <button
                  type="button"
                  role="tab"
                  aria-selected={logView === 'runtime'}
                  className={logView === 'runtime' ? 'active' : ''}
                  onClick={() => setLogView('runtime')}
                >
                  实时运行日志
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={logView === 'build'}
                  className={logView === 'build' ? 'active' : ''}
                  onClick={() => setLogView('build')}
                >
                  最近构建日志
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={logView === 'sse'}
                  className={logView === 'sse' ? 'active' : ''}
                  onClick={() => setLogView('sse')}
                >
                  实时部署流 (SSE)
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={logView === 'releases'}
                  className={logView === 'releases' ? 'active' : ''}
                  onClick={() => setLogView('releases')}
                >
                  发布历史与回滚 ({svc?.releases?.length ?? 0})
                </button>
              </div>

              {logView !== 'releases' && logView !== 'sse' ? (
                <div className="svc-logs-tools">
                  <label className="svc-log-search">
                    <Icon name="search" />
                    <input
                      type="text"
                      placeholder="过滤关键词…"
                      value={logQuery}
                      onChange={(e) => setLogQuery(e.target.value)}
                    />
                  </label>
                  <button
                    type="button"
                    className={`svc-pill-toggle ${onlyErrors ? 'is-on' : ''}`}
                    onClick={() => setOnlyErrors((v) => !v)}
                  >
                    仅错误/告警
                  </button>
                  <button
                    type="button"
                    className={`svc-pill-toggle ${wrapLogs ? 'is-on' : ''}`}
                    onClick={() => setWrapLogs((v) => !v)}
                  >
                    自动换行
                  </button>
                  <button
                    type="button"
                    className="svc-pill-toggle"
                    disabled={!activeLogText}
                    onClick={async () => toast((await copyText(activeLogText)) ? '已复制完整日志。' : '请手动复制。')}
                  >
                    <Icon name="copy" /> 复制
                  </button>
                  <button type="button" className="svc-pill-toggle" onClick={() => void logsQ.refetch()}>
                    <Icon name="refresh" className={logsQ.isFetching ? 'spinning' : ''} /> 刷新
                  </button>
                </div>
              ) : null}
            </div>

            {logView === 'sse' ? (
              <div className="svc-code-window">
                <div className="svc-code-win-bar">
                  <div className="svc-win-dots" aria-hidden="true">
                    <i />
                    <i />
                    <i />
                  </div>
                  <span className="svc-win-title">deploy-stream · 实时构建与蓝绿切流事件流 (SSE)</span>
                  <span className="svc-win-meta">{sseLines.length} 条事件</span>
                </div>
                <div className={`svc-terminal-body ${wrapLogs ? 'is-wrap' : 'is-nowrap'}`}>
                  {sseLines.length === 0 ? (
                    <div className="svc-terminal-empty">已连接实时部署事件流，点击「重新部署」或「0重建晋升」即可实时观看蓝绿切流过程。</div>
                  ) : (
                    sseLines.map((ev, idx) => (
                      <div key={idx} className={`svc-log-line is-${ev.type === 'error' ? 'err' : ev.type === 'done' || ev.type === 'stage' ? 'sys' : 'info'}`}>
                        <span className="svc-line-no">{idx + 1}</span>
                        <span className="svc-line-code">
                          [{ev.stage}] {ev.line}
                        </span>
                      </div>
                    ))
                  )}
                </div>
              </div>
            ) : logView === 'releases' ? (
              <section className="svc-section-card">
                {(svc?.releases ?? []).length === 0 ? (
                  <div className="svc-empty-box">
                    <Icon name="history" />
                    <b>暂无发布记录</b>
                    <p>每次导入、保存代码或点击「重新部署」都会自动沉淀一条不可变发布快照。</p>
                  </div>
                ) : (
                  <div className="svc-release-table-wrap">
                    <table className="svc-release-table">
                      <thead>
                        <tr>
                          <th>状态 / 槽位</th>
                          <th>镜像 / 构建产物</th>
                          <th>耗时</th>
                          <th>开始时间</th>
                          <th>操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(svc?.releases ?? []).map((r) => (
                          <tr key={r.id}>
                            <td>
                              <span className={`svc-rel-st is-${r.status}`}>
                                {r.status === 'succeeded' ? '成功' : r.status === 'failed' ? '失败' : '构建中'}
                              </span>
                              <span className="svc-chip" style={{ marginLeft: 6, fontSize: 11 }}>
                                {r.slot === 'preview' ? '预览' : '线上'}
                              </span>
                              {r.promotedFromReleaseId ? (
                                <span className="svc-chip is-accent" style={{ marginLeft: 4, fontSize: 10.5 }}>
                                  0重建
                                </span>
                              ) : null}
                            </td>
                            <td>
                              <code>{r.imageRef || '—'}</code>
                              {r.error ? <div className="is-err-cell" style={{ fontSize: 11.5 }}>{r.errorCategory ? `[${r.errorCategory}] ` : ''}{r.error}</div> : null}
                            </td>
                            <td className="is-nowrap">{r.durationMs != null ? `${(r.durationMs / 1000).toFixed(2)}s` : '—'}</td>
                            <td className="is-nowrap">{r.startedAt ? fmtTime(r.startedAt) : '—'}</td>
                            <td className="is-nowrap">
                              {r.rollbackable ? (
                                <button
                                  type="button"
                                  className="svc-pill-toggle"
                                  disabled={!!busy}
                                  title={r.zeroRebuildReady ? '命中不可变产物快照，0 重建秒级回滚' : '从版本仓库回滚'}
                                  onClick={() =>
                                    void runAction(
                                      `rollback-${r.id}`,
                                      () => api.serviceRollbackRelease(app.id, r.id),
                                      r.zeroRebuildReady ? '已 0 重建零停机秒级回滚到该发布快照！' : '已回滚到该历史版本。',
                                    )
                                  }
                                >
                                  <Icon name="history" /> {busy === `rollback-${r.id}` ? '回滚中…' : '秒级回滚'}
                                </button>
                              ) : (
                                '—'
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            ) : (
              <div className="svc-code-window">
                <div className="svc-code-win-bar">
                  <div className="svc-win-dots" aria-hidden="true">
                    <i />
                    <i />
                    <i />
                  </div>
                  <span className="svc-win-title">
                    {logView === 'runtime' ? 'runtime.log · 实时容器标准输出' : 'build.log · 最近一次构建流水'}
                  </span>
                  <span className="svc-win-meta">
                    {parsedLogs.length} 行{logQuery || onlyErrors ? '（已筛选）' : ''}
                  </span>
                </div>
                <div className={`svc-terminal-body ${wrapLogs ? 'is-wrap' : 'is-nowrap'}`}>
                  {parsedLogs.length === 0 ? (
                    <div className="svc-terminal-empty">
                      {logsQ.isLoading
                        ? '正在拉取日志…'
                        : logQuery || onlyErrors
                          ? '当前筛选条件下没有匹配的日志行。'
                          : logView === 'runtime'
                            ? '暂无运行日志（服务启动或收到请求后自动刷新）。'
                            : '暂无构建日志。'}
                    </div>
                  ) : (
                    parsedLogs.map((row) => (
                      <div key={row.num} className={`svc-log-line is-${row.level}`}>
                        <span className="svc-line-no">{row.num}</span>
                        <span className="svc-line-code">
                          {row.level === 'banner' ? row.text : renderLogText(row.text)}
                        </span>
                      </div>
                    ))
                  )}
                </div>
              </div>
            )}
          </div>
        ) : sub === 'db' ? (
          <ServiceDbTab appId={app.id} pgEnabled={!!svc?.pgEnabled} />
        ) : sub === 'triggers' ? (
          <CloudTriggersTab appId={app.id} tableNames={[]} mode="service" />
        ) : sub === 'cicd' ? (
          <ServiceCicdTopologyTab appId={app.id} />
        ) : (
          <div className="svc-logs-shell">
            <div className="svc-code-window is-tall">
              <div className="svc-code-win-bar">
                <div className="svc-win-dots" aria-hidden="true">
                  <i />
                  <i />
                  <i />
                </div>
                <span className="svc-win-title">Dockerfile</span>
                <span className="svc-win-badge">
                  {dfQ.data?.custom ? '仓库自定义 Dockerfile' : '平台自动生成 · 分层缓存 + 非 root 多阶段构建'}
                </span>
                <div className="svc-win-actions">
                  <span className="svc-win-meta">{dockerfileLines.length} 行</span>
                  <a className="svc-win-copy" href={api.serviceExportBundleUrl(app.id)} download style={{ textDecoration: 'none' }}>
                    <Icon name="download" /> 导出 Docker/Helm 包
                  </a>
                  <button
                    type="button"
                    className="svc-win-copy"
                    disabled={!dfQ.data?.content}
                    onClick={async () =>
                      toast((await copyText(dfQ.data?.content ?? '')) ? 'Dockerfile 已复制。' : '请手动复制。')
                    }
                  >
                    <Icon name="copy" /> 复制
                  </button>
                </div>
              </div>
              <div className="svc-terminal-body is-dockerfile">
                {dfQ.isLoading ? (
                  <div className="svc-terminal-empty">正在生成 Dockerfile 预览…</div>
                ) : dockerfileLines.length === 0 ? (
                  <div className="svc-terminal-empty">暂无 Dockerfile 内容。</div>
                ) : (
                  dockerfileLines.map((line, i) => {
                    const isStage = /^\s*FROM\s+/i.test(line);
                    return (
                      <Fragment key={i}>
                        <div className={`svc-log-line is-df ${isStage && i > 0 ? 'is-stage-sep' : ''}`}>
                          <span className="svc-line-no">{i + 1}</span>
                          <span className="svc-line-code">{renderDockerfileLine(line)}</span>
                        </div>
                      </Fragment>
                    );
                  })
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
