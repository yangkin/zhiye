import { useCallback, useEffect, useRef, useState } from 'react';
import { useDialogFocus } from '../hooks/useDialogFocus';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage, newIdempotencyKey } from '../api/client';
import { useMeta } from '../api/queries';
import { useTaskStream } from '../api/sse';
import { APP_TYPES, type AppType, type ImportCandidate, type ImportManifest, type Task } from '../api/types';
import { sizeLabel } from '../lib/format';
import { toast, useUi } from '../lib/store';
import { TaskLimitLink, errorCode } from '../components/LimitNotice';
import { DEFAULT_LIMITS, buildUpload, droppedFiles, precheck, type Limits } from './precheck';
import { Select } from '../components/Select';

type Phase = 'select' | 'reading' | 'uploading' | 'review' | 'deploying';

const hintFor = (msg: string) =>
  /过多|超过|MB|依赖/.test(msg)
    ? '提示：删除 node_modules、dist、.git 等目录，或只选择源码目录后重试。'
    : /ZIP|zip/.test(msg)
      ? '提示：可以在本地重新压缩项目文件夹，或直接选择文件夹导入。'
      : /HTML|html/.test(msg)
        ? '提示：确认文件是完整的 HTML 页面（包含 <html> 或 <body> 标签）。'
        : '';

const FolderArt = () => (
  <svg viewBox="0 0 80 65" aria-hidden="true">
    <defs>
      <linearGradient id="import-folder" x2="0" y2="1">
        <stop stopColor="#9b72ff" />
        <stop offset="1" stopColor="#ede7ff" />
      </linearGradient>
    </defs>
    <path fill="#b69aff" d="M8 9a7 7 0 0 1 7-7h19l8 8h24a7 7 0 0 1 7 7v36H8z" />
    <path fill="#ded1ff" d="M8 14h60v37H8z" />
    <path fill="url(#import-folder)" d="M7 18h65a5 5 0 0 1 5 6l-5 34H9L3 24a5 5 0 0 1 4-6" />
    <path d="m32 33-7 6 7 6m16-12 7 6-7 6m-5-15-6 19" stroke="white" fill="none" strokeWidth="2.5" strokeLinecap="round" />
  </svg>
);

const chosen = (m: ImportManifest, dir: string): ImportCandidate | undefined => m.candidates?.find((c) => c.dir === dir);
const dirLabel = (dir: string) => (dir ? dir + '/' : '根目录');

function footerText(m: ImportManifest, dir: string) {
  if (!m.deployable) return m.unsupportedReason || '这个项目暂时无法部署。';
  const c = chosen(m, dir);
  const risks = dir === (m.rootDir ?? '') ? (m.warnings ?? []).filter((w) => w.level === 'risk').length : 0;
  const what =
    c?.kind === 'service' ? '自动构建并启动单体服务托管' : c?.kind === 'vite' ? '安装依赖、构建并部署' : '保存文件并部署';
  return `确认后会${what}${c && c.dir ? ` ${dirLabel(c.dir)}` : ''}，完成后进入应用工作台。${risks ? `体检发现 ${risks} 项风险，部署后部分功能可能不可用。` : ''}`;
}

function ImportReport({ manifest, rootDir, onRootDir }: { manifest: ImportManifest; rootDir: string; onRootDir: (d: string) => void }) {
  if (!manifest.deployable) return null;
  const candidates = manifest.candidates ?? [];
  const primary = rootDir === (manifest.rootDir ?? '');
  const c = chosen(manifest, rootDir);
  const warnings = primary ? [...(manifest.warnings ?? [])].sort((a, b) => (a.level === b.level ? 0 : a.level === 'risk' ? -1 : 1)) : [];
  return (
    <section className="code-import-report" aria-label="导入体检">
      {candidates.length > 1 ? (
        <fieldset className="code-root-choice">
          <legend>部署内容</legend>
          {candidates.map((x) => (
            <label key={x.dir} className={x.dir === rootDir ? 'selected' : ''}>
              <input type="radio" name="import-root" checked={x.dir === rootDir} onChange={() => onRootDir(x.dir)} />
              <span>
                <b>{dirLabel(x.dir)}</b>
                <small>
                  {x.prebuilt
                    ? '已构建产物，直接部署，不安装依赖'
                    : x.kind === 'service'
                      ? `${x.framework} · 单体后端/全栈托管`
                      : x.kind === 'vite'
                        ? `${x.framework} · 从源码构建`
                        : x.framework}
                </small>
              </span>
            </label>
          ))}
        </fieldset>
      ) : null}
      <h4>
        体检结果
        <span>{primary ? (warnings.some((w) => w.level === 'risk') ? `${warnings.filter((w) => w.level === 'risk').length} 项风险` : '未发现风险') : ''}</span>
      </h4>
      {c?.prebuilt ? (
        <ul>
          <li className="info">
            <strong>
              <i aria-hidden="true">i</i>
              直接部署构建产物
            </strong>
            <p>
              {dirLabel(c.dir)} 里的文件会原样部署，不安装依赖也不构建。适合只需要上线的情况：之后 AI 只能修改构建后的文件，改源码请在本地构建后重新导入。
            </p>
          </li>
        </ul>
      ) : warnings.length ? (
        <ul>
          {warnings.map((w) => (
            <li key={w.code} className={w.level}>
              <strong>
                <i aria-hidden="true">{w.level === 'risk' ? '!' : 'i'}</i>
                {w.title}
              </strong>
              <p>{w.detail}</p>
            </li>
          ))}
        </ul>
      ) : (
        <p className="code-report-ok">{primary ? '没有发现会影响部署的问题。' : `将部署 ${dirLabel(rootDir)}。`}</p>
      )}
    </section>
  );
}

export function CodeImportDialog() {
  const open = useUi((s) => s.importOpen);
  if (!open) return null;
  return <Dialog />;
}

function Dialog() {
  const setOpen = useUi((s) => s.setImportOpen);
  const dialogBox = useRef<HTMLElement>(null);
  useDialogFocus(dialogBox);
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { data: meta } = useMeta();
  const limits: Limits = meta?.limits
    ? {
        maxFiles: Math.min(meta.limits.uploadMaxFiles || DEFAULT_LIMITS.maxFiles, DEFAULT_LIMITS.maxFiles),
        maxBytes: Math.min(meta.limits.uploadMaxBytes || DEFAULT_LIMITS.maxBytes, DEFAULT_LIMITS.maxBytes),
      }
    : DEFAULT_LIMITS;

  const [phase, setPhase] = useState<Phase>('select');
  const [error, setError] = useState('');
  const [errorKind, setErrorKind] = useState('');
  const [manifest, setManifest] = useState<ImportManifest | null>(null);
  const [name, setName] = useState('');
  const [type, setType] = useState<AppType>('开发网站');
  const [rootDir, setRootDir] = useState('');
  const [dragging, setDragging] = useState(false);
  const [task, setTask] = useState<{ id: string; appId: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const folderRef = useRef<HTMLInputElement>(null);
  const abort = useRef<AbortController | null>(null);
  const token = useRef(0);
  const keyRef = useRef<string | null>(null);

  useEffect(() => {
    folderRef.current?.setAttribute('webkitdirectory', '');
    folderRef.current?.setAttribute('directory', '');
  }, []);

  const onFinished = useCallback(
    (t: Task) => {
      void qc.invalidateQueries({ queryKey: ['apps'] });
      void qc.invalidateQueries({ queryKey: ['history'] });
      if (t.status === 'SUCCEEDED') {
        setOpen(false);
        const res = t.result as Record<string, unknown> | undefined;
        const deployPending = Boolean(res?.deployPending) || res?.status === 'failed';
        if (deployPending) {
          toast('源码已保存为 V1，但服务启动未就绪：请打开「服务托管」查看日志并补齐配置后重试。', 'error');
        } else {
          toast('代码已导入并部署完成。');
        }
        navigate('/apps/' + t.appId);
      } else {
        setTask(null);
        setPhase('review');
        setError(t.status === 'CANCELLED' ? '导入已取消，没有创建应用。' : t.error?.message || '导入部署没有完成，请检查项目后重试。');
      }
    },
    [navigate, qc, setOpen],
  );
  const stream = useTaskStream(task?.id, onFinished);

  const close = () => {
    token.current++;
    abort.current?.abort();
    if (task && phase === 'deploying') {
      api.cancelTask(task.id).catch(() => undefined);
      toast('已取消导入，没有创建应用。');
    }
    setOpen(false);
  };

  const reset = () => {
    token.current++;
    abort.current?.abort();
    setPhase('select');
    setManifest(null);
    setError('');
    keyRef.current = null;
  };

  const read = async (input: Promise<File[]> | File[]) => {
    if (phase === 'uploading' || phase === 'deploying') return;
    const my = ++token.current;
    setError('');
    setManifest(null);
    setPhase('reading');
    try {
      const files = await input;
      const checked = await precheck(files, limits);
      if (my !== token.current) return;
      setPhase('uploading');
      const upload = await buildUpload(checked);
      if (my !== token.current) return;
      abort.current = new AbortController();
      const m = await api.uploadImport(upload.blob, upload.fileName, upload.folderName, abort.current.signal);
      if (my !== token.current) return;
      setManifest(m);
      setRootDir(m.rootDir ?? '');
      setName((m.name || checked.name).slice(0, 40));
      keyRef.current = null;
      setPhase('review');
    } catch (err) {
      if (my !== token.current || (err as Error)?.name === 'AbortError') return;
      setError(errorMessage(err));
      setPhase('select');
    }
  };

  const deploy = async () => {
    if (!manifest || submitting) return;
    const n = name.trim();
    if (!n) return setError('请填写应用名称。');
    if (n.length > 40) return setError('应用名称最多 40 个字。');
    if (!manifest.deployable) return;
    setSubmitting(true);
    setError('');
    setErrorKind('');
    keyRef.current ??= newIdempotencyKey();
    try {
      const res = await api.createImport({ uploadId: manifest.uploadId, name: n, type, ...(manifest.candidates ? { rootDir } : {}) }, keyRef.current);
      keyRef.current = null;
      setTask({ id: res.task.id, appId: res.app.id });
      setPhase('deploying');
    } catch (err) {
      setError(errorMessage(err));
      setErrorKind(errorCode(err));
    } finally {
      setSubmitting(false);
    }
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && close();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const busy = phase === 'reading' || phase === 'uploading';
  const steps = stream.phases.length ? stream.phases : [stream.phaseLabel || '正在排队'];

  return createPortal(
    <div className="modal-backdrop code-import-backdrop" onClick={(e) => e.target === e.currentTarget && phase !== 'deploying' && close()}>
      <section ref={dialogBox} className="code-import-dialog" role="dialog" aria-modal="true" aria-label="导入代码并部署">
        <button type="button" className="code-import-close" aria-label="关闭代码导入" onClick={close}>
          ×
        </button>
        <input
          ref={fileRef}
          type="file"
          accept=".zip,.html,.htm"
          hidden
          onChange={(e) => {
            void read(Array.from(e.target.files ?? []));
            e.target.value = '';
          }}
        />
        <input
          ref={folderRef}
          type="file"
          multiple
          hidden
          onChange={(e) => {
            void read(Array.from(e.target.files ?? []));
            e.target.value = '';
          }}
        />
        {phase === 'deploying' ? (
          <section className="code-import-progress" role="status" aria-live="polite">
            <span className="code-project-badge">{manifest?.framework ?? '导入部署'}</span>
            <h2>正在准备你的应用</h2>
            <p>
              {name} · {stream.phaseLabel || '等待任务开始'}
            </p>
            <div className="code-progress-track">
              <i style={{ width: `${stream.progress}%` }} />
            </div>
            <ol>
              {steps.map((s, i) => {
                const current = i === steps.length - 1 && stream.status !== 'SUCCEEDED';
                return (
                  <li key={s + i} className={current ? 'current' : 'done'}>
                    <span>{current ? i + 1 : '✓'}</span>
                    {s}
                    {current ? <i /> : null}
                  </li>
                );
              })}
            </ol>
            {stream.logs.length ? <pre className="code-import-log">{stream.logs.slice(-6).join('\n')}</pre> : null}
            <button type="button" className="code-text-button" onClick={close}>
              取消导入
            </button>
          </section>
        ) : (
          <>
            <div
              className={`code-import-dropzone ${manifest ? 'has-project' : ''} ${dragging ? 'dragging' : ''}`}
              role="region"
              aria-label="代码导入区域"
              onDragOver={(e) => {
                e.preventDefault();
                e.dataTransfer.dropEffect = 'copy';
                setDragging(true);
              }}
              onDragLeave={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget as Node)) setDragging(false);
              }}
              onDrop={(e) => {
                e.preventDefault();
                setDragging(false);
                if (!busy) void read(droppedFiles(e.dataTransfer, limits.maxFiles));
              }}
            >
              <FolderArt />
              <h2>代码导入</h2>
              <p>支持纯前端项目（HTML、Vite / Vue / React / Umi / dist 产物）以及 Python、Node.js、Java、Go 单体后端与前后端一体项目</p>
              <p>自动识别运行环境并容器化托管（支持同域前后端免跨域、托管 PostgreSQL Schema、/data 卷与闲置自动休眠）</p>
              <p>
                拖拽项目文件夹、.zip 或 .html 到这里，或{' '}
                <button type="button" className="code-inline" disabled={busy} onClick={() => fileRef.current?.click()}>
                  选择文件
                </button>
                <span className="code-slash">/</span>
                <button type="button" className="code-inline" disabled={busy} onClick={() => folderRef.current?.click()}>
                  文件夹
                </button>
              </p>
              <p className="code-limits">
                最多 {Math.round(limits.maxBytes / 1048576)} MB、{limits.maxFiles} 个文件，自动跳过 node_modules、.git
              </p>
              {phase === 'reading' ? (
                <span className="code-reading" role="status">
                  正在读取项目文件…
                </span>
              ) : phase === 'uploading' ? (
                <span className="code-reading" role="status">
                  正在上传并解析项目…
                </span>
              ) : null}
            </div>
            {manifest && phase === 'review' ? (
              <section className="code-import-review">
                <div className="code-import-review-head">
                  <div>
                    <span className="code-project-badge">{chosen(manifest, rootDir)?.framework ?? manifest.framework}</span>
                    <h3>{name || manifest.name}</h3>
                    <p>
                      {manifest.fileCount} 个文件 · {sizeLabel(manifest.totalSize)} · {manifest.format === 'zip' ? 'ZIP / 项目文件夹' : 'HTML 页面'}
                    </p>
                  </div>
                  <button type="button" className="code-text-button" onClick={reset}>
                    重新选择
                  </button>
                </div>
                <div className="code-import-fields">
                  <label>
                    应用名称
                    <input
                      aria-label="导入应用名称"
                      maxLength={40}
                      value={name}
                      onChange={(e) => {
                        setName(e.target.value);
                        setError('');
                      }}
                    />
                  </label>
                  <label>
                    应用类型
                    <Select ariaLabel="导入应用类型" className="field" value={type} onChange={(v) => setType(v as AppType)} options={APP_TYPES.map((t) => ({ value: t, label: t }))} />
                  </label>
                </div>
                <ImportReport manifest={manifest} rootDir={rootDir} onRootDir={(d) => { setRootDir(d); keyRef.current = null; }} />
                <details className="code-file-list">
                  <summary>
                    查看项目文件 <span>{manifest.fileCount}</span>
                  </summary>
                  <ul>
                    {manifest.files.map((f) => (
                      <li key={f.path}>
                        <span>{f.path}</span>
                        <small>{sizeLabel(f.size)}</small>
                      </li>
                    ))}
                    {manifest.fileCount > manifest.files.length ? (
                      <li className="code-list-more">还有 {manifest.fileCount - manifest.files.length} 个文件</li>
                    ) : null}
                  </ul>
                </details>
                <div className="code-deploy-footer">
                  <p>{footerText(manifest, rootDir)}</p>
                  <button
                    type="button"
                    className="code-deploy-button"
                    disabled={!manifest.deployable || submitting || !name.trim()}
                    onClick={() => void deploy()}
                  >
                    {submitting ? '正在提交…' : '导入并部署'} <span>↗</span>
                  </button>
                </div>
              </section>
            ) : null}
          </>
        )}
        {error ? (
          <p className="code-import-error" role="alert">
            {error}
            {errorKind === 'TASK_LIMIT' ? <TaskLimitLink onNavigate={() => setOpen(false)} /> : null}
            {hintFor(error) ? (
              <>
                <br />
                {hintFor(error)}
              </>
            ) : null}
          </p>
        ) : null}
        <p className="code-import-footnote">无需登录 · 导入完成前不会创建应用，取消不会留下记录</p>
      </section>
    </div>,
    document.body,
  );
}
