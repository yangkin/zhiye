import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import type { AppDetail, ScriptRunResult } from '../api/types';
import { Select } from '../components/Select';
import { copyText, fmtTime, sizeLabel } from '../lib/format';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';

export function ScriptRunnerPane({
  app,
  versionId,
  onOpenCode,
}: {
  app: AppDetail;
  versionId?: string | null;
  onOpenCode: () => void;
}) {
  const qc = useQueryClient();
  const [entryFile, setEntryFile] = useState('');
  const [args, setArgs] = useState('');
  const [stdin, setStdin] = useState('');
  const [timeoutSeconds, setTimeoutSeconds] = useState(15);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<ScriptRunResult | null>(null);
  const [error, setError] = useState('');
  const [activeOutFile, setActiveOutFile] = useState<string | null>(null);

  const inspectQ = useQuery({
    queryKey: ['script-files', app.id, versionId ?? app.currentVersion?.id ?? ''],
    queryFn: () => api.scriptFiles(app.id, versionId ?? undefined),
  });

  const entries = inspectQ.data?.entries ?? [];
  const sampleFiles = inspectQ.data?.sampleFiles ?? [];
  const history = inspectQ.data?.history ?? [];

  useEffect(() => {
    if (!entries.length) return;
    if (!entryFile || !entries.some((e) => e.path === entryFile)) {
      setEntryFile(inspectQ.data?.defaultEntry ?? entries[0].path);
    }
  }, [entries, entryFile, inspectQ.data?.defaultEntry]);

  const activeEntry = entries.find((e) => e.path === entryFile) ?? entries[0] ?? null;

  const runNow = async () => {
    if (!entryFile || running) return;
    setRunning(true);
    setError('');
    setActiveOutFile(null);
    try {
      const res = await api.scriptRun(app.id, {
        versionId: versionId ?? undefined,
        entryFile,
        args,
        stdin,
        timeoutSeconds,
      });
      setResult(res);
      if (res.outputFiles?.length) {
        setActiveOutFile(res.outputFiles[0].path);
      }
      void qc.invalidateQueries({ queryKey: ['script-files', app.id] });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setRunning(false);
    }
  };

  const selectedOutputFile = result?.outputFiles?.find((f) => f.path === activeOutFile) ?? null;

  return (
    <div className="script-runner-pane">
      <div className="script-runner-header">
        <div className="script-runner-title">
          <span className={`script-rt-badge ${activeEntry?.runtime === 'python' ? 'py' : 'js'}`}>
            {activeEntry?.runtime === 'python' ? 'Python 3' : activeEntry?.runtime === 'node' ? 'Node.js' : '无页面脚本'}
          </span>
          <div>
            <h3>在线运行台</h3>
            <p>无需网页界面，直接在隔离沙箱运行 Python / Node.js 脚本并查看标准输出与生成文件</p>
          </div>
        </div>
        <div className="script-runner-actions">
          <button type="button" className="subtle-button" onClick={onOpenCode}>
            <Icon name="code" /> 查看/编辑源码
          </button>
          <button
            type="button"
            className="primary-button"
            disabled={!entryFile || running}
            onClick={() => void runNow()}
          >
            <Icon name="play" /> {running ? '正在执行…' : '运行脚本'}
          </button>
        </div>
      </div>

      <div className="script-runner-controls">
        <div className="script-control-row">
          <label className="script-field script-field-entry">
            <span>入口脚本</span>
            {entries.length ? (
              <Select
                ariaLabel="选择入口脚本"
                value={entryFile}
                onChange={setEntryFile}
                options={entries.map((e) => ({
                  value: e.path,
                  label: `${e.path} (${e.runtime === 'python' ? 'Python 3' : 'Node.js'})`,
                }))}
              />
            ) : (
              <input value={inspectQ.isLoading ? '正在扫描脚本文件…' : '未找到 .py / .js / .mjs 脚本文件'} readOnly />
            )}
          </label>
          <label className="script-field script-field-args">
            <span>命令行参数 (CLI Args)</span>
            <input
              aria-label="命令行参数"
              placeholder={
                sampleFiles.length
                  ? `例如：--input ${sampleFiles[0]} 或 --help`
                  : '例如：--help 或 --mode summary --top 5'
              }
              value={args}
              onChange={(e) => setArgs(e.target.value)}
              onKeyDown={(e) => {
                if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') void runNow();
              }}
            />
          </label>
          <label className="script-field script-field-timeout">
            <span>超时上限</span>
            <Select
              ariaLabel="运行超时上限"
              value={String(timeoutSeconds)}
              onChange={(v) => setTimeoutSeconds(Number(v) || 15)}
              options={[
                { value: '5', label: '5 秒' },
                { value: '15', label: '15 秒' },
                { value: '30', label: '30 秒' },
              ]}
            />
          </label>
        </div>

        <label className="script-field">
          <span>标准输入 (stdin，可选 — 支持粘贴 CSV / JSON / 文本供脚本读取)</span>
          <textarea
            aria-label="标准输入"
            rows={3}
            placeholder="可选：留空则直接传空 EOF；若脚本读取 sys.stdin 或 process.stdin，可在此粘贴输入数据"
            value={stdin}
            onChange={(e) => setStdin(e.target.value)}
          />
        </label>

        {sampleFiles.length ? (
          <div className="script-samples-bar">
            <span>样例文件快捷填入：</span>
            {sampleFiles.slice(0, 5).map((sf) => (
              <button
                key={sf}
                type="button"
                className="script-sample-chip"
                onClick={() => setArgs((prev) => (prev.trim() ? `${prev.trim()} ${sf}` : sf))}
              >
                + {sf}
              </button>
            ))}
          </div>
        ) : null}
      </div>

      {error ? (
        <p className="inline-error" role="alert" style={{ margin: '0 20px' }}>
          {error}
        </p>
      ) : null}

      <section className="script-terminal-card" aria-label="脚本运行输出">
        <div className="script-terminal-head">
          <div className="script-terminal-status">
            {result ? (
              <>
                <span
                  className={`script-exit-pill ${
                    result.timedOut ? 'warn' : result.exitCode === 0 ? 'ok' : 'err'
                  }`}
                >
                  {result.timedOut
                    ? '运行超时'
                    : result.exitCode === 0
                      ? '退出码 0 · 执行成功'
                      : `退出码 ${result.exitCode ?? '?'} · 执行异常`}
                </span>
                <span className="script-meta-chip">{result.runtime === 'python' ? 'python3' : 'node'} {result.entryFile}</span>
                <span className="script-meta-chip">{result.durationMs} ms</span>
              </>
            ) : (
              <span className="script-meta-chip">点击「运行脚本」（或 Ctrl/⌘ + Enter）开始执行</span>
            )}
          </div>
          {result ? (
            <div style={{ display: 'flex', gap: 6 }}>
              <button
                type="button"
                className="subtle-button"
                onClick={async () =>
                  toast((await copyText([result.stdout, result.stderr].filter(Boolean).join('\n'))) ? '已复制输出。' : '复制失败。')
                }
              >
                <Icon name="copy" /> 复制输出
              </button>
              <button type="button" className="subtle-button" onClick={() => setResult(null)}>
                清空
              </button>
            </div>
          ) : null}
        </div>

        <div className="script-terminal-body">
          {running ? (
            <div className="script-terminal-empty">正在隔离沙箱中执行 {entryFile} …</div>
          ) : !result ? (
            <div className="script-terminal-empty">
              暂无运行结果。支持直接运行 Python 3 与 Node.js（.js / .mjs / .cjs），可传 CLI 参数与 stdin。
            </div>
          ) : (
            <>
              <div className="script-stream-block">
                <div className="script-stream-label">STDOUT</div>
                <pre className="script-stream-pre">{result.stdout || '（无标准输出）'}</pre>
              </div>
              {result.stderr ? (
                <div className="script-stream-block is-err">
                  <div className="script-stream-label">STDERR</div>
                  <pre className="script-stream-pre">{result.stderr}</pre>
                </div>
              ) : null}
            </>
          )}
        </div>
      </section>

      {result?.outputFiles?.length ? (
        <section className="script-outputs-card">
          <div className="script-outputs-head">
            <b>本次运行生成的文件 ({result.outputFiles.length})</b>
            <div className="script-output-tabs">
              {result.outputFiles.map((f) => (
                <button
                  key={f.path}
                  type="button"
                  className={activeOutFile === f.path ? 'active' : ''}
                  onClick={() => setActiveOutFile(f.path)}
                >
                  {f.path} <small>{sizeLabel(f.size)}</small>
                </button>
              ))}
            </div>
          </div>
          {selectedOutputFile ? (
            <pre className="script-output-preview">
              {selectedOutputFile.preview ?? '（二进制或超过 64 KB 的文件，请导出源码或在脚本内打印摘要）'}
            </pre>
          ) : null}
        </section>
      ) : null}

      {history.length ? (
        <section className="script-history-card">
          <h4>最近运行记录 ({history.length})</h4>
          <div className="script-history-list">
            {history.slice(0, 8).map((h) => (
              <button
                key={h.id}
                type="button"
                className="script-history-item"
                onClick={() =>
                  setResult({
                    ...h,
                    outputFiles: [],
                  })
                }
              >
                <span className={`script-exit-dot ${h.timedOut ? 'warn' : h.exitCode === 0 ? 'ok' : 'err'}`} />
                <code className="script-hist-file">{h.entryFile}</code>
                <span className="script-hist-args">{h.args || '无参数'}</span>
                <span className="script-hist-meta">
                  {h.timedOut ? '超时' : `exit ${h.exitCode ?? '?'}`} · {h.durationMs}ms · {fmtTime(h.createdAt)}
                </span>
              </button>
            ))}
          </div>
        </section>
      ) : null}
    </div>
  );
}
