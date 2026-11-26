import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { useMeta } from '../api/queries';
import type { ServiceDbQueryResult } from '../api/types';
import { copyText, fmtTime } from '../lib/format';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { Select } from '../components/Select';

/** 在线 PostgreSQL 表浏览、安全只读预览与管理员受控 SQL 控制台（严格隔离至本应用专属 Schema 与最小权限角色）。 */
export function ServiceDbTab({ appId, pgEnabled }: { appId: string; pgEnabled: boolean }) {
  const { data: meta } = useMeta();
  const isAdmin = Boolean(meta?.auth?.admin);
  const [selectedTable, setSelectedTable] = useState<string>('');
  const [previewBusy, setPreviewBusy] = useState(false);
  const [previewErr, setPreviewErr] = useState('');
  const [previewRes, setPreviewRes] = useState<ServiceDbQueryResult | null>(null);

  const [sql, setSql] = useState('SELECT table_name, column_name, data_type FROM information_schema.columns WHERE table_schema = current_schema() ORDER BY table_name, ordinal_position LIMIT 50');
  const [allowWrite, setAllowWrite] = useState(false);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<ServiceDbQueryResult | null>(null);
  const [err, setErr] = useState('');

  const tablesQ = useQuery({
    queryKey: ['service-db-tables', appId],
    queryFn: () => api.serviceDbTables(appId),
  });

  const loadPreview = async (tableName: string) => {
    setSelectedTable(tableName);
    setPreviewBusy(true);
    setPreviewErr('');
    if (isAdmin) setSql(`SELECT * FROM "${tableName}" LIMIT 50`);
    try {
      const r = await api.serviceDbPreview(appId, tableName, 50);
      setPreviewRes(r);
    } catch (e) {
      setPreviewErr(errorMessage(e));
      setPreviewRes(null);
    } finally {
      setPreviewBusy(false);
    }
  };

  const runSql = async () => {
    if (!sql.trim() || running) return;
    setRunning(true);
    setErr('');
    try {
      const r = await api.serviceDbQuery(appId, sql, allowWrite, 100);
      setResult(r);
      if (allowWrite) {
        void tablesQ.refetch();
        if (selectedTable) void loadPreview(selectedTable);
      }
    } catch (e) {
      setErr(errorMessage(e));
      setResult(null);
    } finally {
      setRunning(false);
    }
  };

  if (!pgEnabled && !tablesQ.data?.pgEnabled) {
    return (
      <div className="svc-config-stack">
        <section className="svc-section-card">
          <div className="svc-empty-box">
            <Icon name="database" />
            <b>尚未开启托管 PostgreSQL Schema</b>
            <p>请先在「运行与存储配置」开启并保存「托管 PostgreSQL Schema」，系统将自动分配隔离 Schema 与最小权限账号。</p>
          </div>
        </section>
      </div>
    );
  }

  const tables = tablesQ.data?.tables ?? [];
  const activeTableMeta = tables.find((t) => t.name === selectedTable) ?? null;

  return (
    <div className="svc-config-stack">
      <section className="svc-section-card">
        <div className="svc-section-head">
          <div>
            <h4>隔离 PostgreSQL Schema 表浏览与只读预览</h4>
            <p>
              当前 Schema：<code>{tablesQ.data?.schema || '初始化中'}</code> · 隔离角色：<code>{tablesQ.data?.role || '—'}</code>（内核级限制，无法访问平台其他库表与 public Schema）
            </p>
          </div>
          <button type="button" className="subtle-button" onClick={() => void tablesQ.refetch()}>
            <Icon name="refresh" className={tablesQ.isFetching ? 'spinning' : ''} /> 刷新表结构
          </button>
        </div>

        {tables.length === 0 ? (
          <p className="svc-footer-hint">当前 Schema 下暂无数据表；应用首次启动后会自动建表并展示在此处。</p>
        ) : (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {tables.map((t) => {
              const active = selectedTable === t.name;
              return (
                <button
                  key={t.name}
                  type="button"
                  className={active ? 'primary-button' : 'subtle-button'}
                  style={{ fontSize: 12, padding: '6px 12px', height: 32, borderRadius: 9 }}
                  onClick={() => void loadPreview(t.name)}
                  title={`点击只读预览数据 · 列：${t.columns.map((c) => `${c.name}:${c.type}`).join(', ')}`}
                >
                  <Icon name="database" />
                  <span>{t.name}</span>
                  <span style={{ opacity: 0.72, fontSize: 11 }}>({t.columns.length}列 · ~{t.estimatedRows}行)</span>
                </button>
              );
            })}
          </div>
        )}

        {activeTableMeta ? (
          <div style={{ marginTop: 14, paddingTop: 14, borderTop: '1px solid #efecf7' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8, gap: 10 }}>
              <div style={{ fontSize: 12.5, color: '#3c3550' }}>
                <b>{activeTableMeta.name}</b> 表结构（{activeTableMeta.columns.length} 列）与只读数据预览（前 50 行）
              </div>
              <button type="button" className="subtle-button" style={{ height: 28, fontSize: 12, padding: '0 10px' }} disabled={previewBusy} onClick={() => void loadPreview(activeTableMeta.name)}>
                <Icon name="refresh" className={previewBusy ? 'spinning' : ''} /> 刷新预览
              </button>
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 10 }}>
              {activeTableMeta.columns.map((col) => (
                <span key={col.name} style={{ fontSize: 11.5, padding: '2px 8px', borderRadius: 6, background: '#f5f2fc', color: '#4c3a8a', border: '1px solid #e6dff7', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' }}>
                  {col.name} <em style={{ fontStyle: 'normal', opacity: 0.7 }}>{col.type}{col.nullable ? '' : ' NOT NULL'}</em>
                </span>
              ))}
            </div>
            {previewErr ? (
              <div className="svc-error-banner" role="alert"><pre>{previewErr}</pre></div>
            ) : previewBusy ? (
              <p className="svc-footer-hint">正在读取表数据预览…</p>
            ) : previewRes ? (
              previewRes.rows.length === 0 ? (
                <p className="svc-footer-hint">该表暂无数据记录（耗时 {previewRes.durationMs} ms）。</p>
              ) : (
                <div className="svc-release-table-wrap" style={{ maxHeight: 280, overflow: 'auto' }}>
                  <table className="svc-release-table">
                    <thead>
                      <tr>{previewRes.columns.map((c) => <th key={c}>{c}</th>)}</tr>
                    </thead>
                    <tbody>
                      {previewRes.rows.map((row, ri) => (
                        <tr key={ri}>
                          {previewRes.columns.map((c) => (
                            <td key={c} style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: 12 }}>
                              {row[c] === null || row[c] === undefined ? <span style={{ opacity: 0.45 }}>NULL</span> : String(row[c])}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )
            ) : null}
          </div>
        ) : null}
      </section>

      {isAdmin ? (
        <section className="svc-section-card">
          <div className="svc-section-head">
            <div>
              <h4>受控 SQL 控制台 <span className="eco-label-badge" style={{ marginLeft: 6 }}>仅平台管理员可见 · 全程审计留痕</span></h4>
              <p>默认只读模式（5 秒查询超时，单次最多返回 100 行）；所有执行均记录平台安全审计日志，危险提权 / 跨 Schema / 注释绕过语句将被强拦截。</p>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, cursor: 'pointer' }}>
                <input type="checkbox" checked={allowWrite} onChange={(e) => setAllowWrite(e.target.checked)} />
                允许写入 (DDL/DML)
              </label>
              <button type="button" className="primary-button" disabled={running || !sql.trim()} onClick={() => void runSql()}>
                <Icon name="play" /> {running ? '执行中…' : '执行 SQL'}
              </button>
            </div>
          </div>

          <textarea
            value={sql}
            onChange={(e) => setSql(e.target.value)}
            rows={4}
            spellCheck={false}
            style={{
              width: '100%',
              fontFamily: 'var(--font-mono, monospace)',
              fontSize: 12.5,
              padding: 12,
              borderRadius: 10,
              border: '1px solid #dfd9ef',
              background: '#ffffff',
              color: '#1f1b2e',
              resize: 'vertical',
              outline: 'none',
            }}
          />

          {err ? (
            <div className="svc-error-banner" role="alert" style={{ marginTop: 10 }}>
              <pre>{err}</pre>
            </div>
          ) : null}

          {result ? (
            <div style={{ marginTop: 12 }}>
              <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 6 }}>
                {result.kind === 'query'
                  ? `返回 ${result.rowCount ?? result.rows.length} 行 · 耗时 ${result.durationMs} ms`
                  : `执行成功 · 影响 ${result.affectedRows ?? 0} 行 · 耗时 ${result.durationMs} ms`}
              </div>
              {result.columns.length > 0 ? (
                <div className="svc-release-table-wrap" style={{ maxHeight: 280, overflow: 'auto' }}>
                  <table className="svc-release-table">
                    <thead>
                      <tr>
                        {result.columns.map((c) => (
                          <th key={c}>{c}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {result.rows.map((row, ri) => (
                        <tr key={ri}>
                          {result.columns.map((c) => (
                            <td key={c} style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: 12 }}>
                              {row[c] === null || row[c] === undefined ? <span style={{ opacity: 0.45 }}>NULL</span> : String(row[c])}
                            </td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : null}
            </div>
          ) : null}
        </section>
      ) : (
        <section className="svc-section-card">
          <div className="svc-section-head">
            <div>
              <h4>安全管控说明</h4>
              <p>为保障共享数据库实例稳定性与数据安全，自由 SQL 执行（含写入模式）仅向平台管理员开放并全程记录审计日志。你可以点击上方任意数据表查看完整字段定义与只读数据预览。</p>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}

/** GitLab / GitHub CI/CD + 声明式多服务拓扑（zhiye.yaml）+ 标准 Docker/Helm 部署包导出。 */
export function ServiceCicdTopologyTab({ appId }: { appId: string }) {
  const qc = useQueryClient();
  const cicdQ = useQuery({ queryKey: ['service-git-cicd', appId], queryFn: () => api.serviceGitCicd(appId) });
  const topoQ = useQuery({ queryKey: ['service-topology', appId], queryFn: () => api.serviceTopology(appId) });

  const [provider, setProvider] = useState('gitlab');
  const [repoUrl, setRepoUrl] = useState('');
  const [branch, setBranch] = useState('main');
  const [deploySlot, setDeploySlot] = useState('preview');
  const [autoDeploy, setAutoDeploy] = useState(true);
  const [webhookSecret, setWebhookSecret] = useState('');
  const [generatedSecret, setGeneratedSecret] = useState<string | null>(null);
  const [yamlText, setYamlText] = useState('');
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    if (!cicdQ.data) return;
    setProvider(cicdQ.data.provider || 'gitlab');
    setRepoUrl(cicdQ.data.repoUrl || '');
    setBranch(cicdQ.data.branch || 'main');
    setDeploySlot(cicdQ.data.deploySlot || 'preview');
    setAutoDeploy(cicdQ.data.autoDeploy ?? true);
  }, [cicdQ.data?.updatedAt, cicdQ.data?.configured]);

  useEffect(() => {
    if (topoQ.data?.yaml) setYamlText(topoQ.data.yaml);
  }, [topoQ.data?.yaml]);

  const saveCicd = async () => {
    setBusy('save-cicd');
    try {
      const res = await api.serviceSaveGitCicd(appId, {
        provider,
        repoUrl,
        branch,
        deploySlot,
        autoDeploy,
        webhookSecret: webhookSecret.trim() || undefined,
      });
      if (res.generatedWebhookSecret) setGeneratedSecret(res.generatedWebhookSecret);
      setWebhookSecret('');
      toast('Git CI/CD 配置已保存。');
      await qc.invalidateQueries({ queryKey: ['service-git-cicd', appId] });
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };

  const triggerCicd = async () => {
    setBusy('trigger-cicd');
    try {
      await api.serviceTriggerGitCicd(appId, `CI/CD 手动触发部署 (${branch})`, branch);
      toast('CI/CD 流水线构建部署成功！');
      await Promise.allSettled([
        qc.invalidateQueries({ queryKey: ['service-git-cicd', appId] }),
        qc.invalidateQueries({ queryKey: ['service-status', appId] }),
      ]);
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };

  const saveTopo = async () => {
    setBusy('save-topo');
    try {
      await api.serviceSaveTopology(appId, yamlText);
      toast('zhiye.yaml 多服务拓扑已校验并保存。');
      await qc.invalidateQueries({ queryKey: ['service-topology', appId] });
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(null);
    }
  };

  const webhookFullUrl = typeof window !== 'undefined'
    ? `${window.location.origin}${cicdQ.data?.webhookPath || `/api/webhooks/git/${appId}`}`
    : (cicdQ.data?.webhookPath || '');

  return (
    <div className="svc-config-stack">
      {/* 区块 1：GitLab / GitHub CI/CD */}
      <section className="svc-section-card">
        <div className="svc-section-head">
          <div>
            <h4>GitLab / GitHub CI/CD 自动部署</h4>
            <p>绑定企业内网 GitLab 或 GitHub 仓库，通过签名校验 Webhook（<code>X-Gitlab-Token</code> / <code>X-Hub-Signature-256</code>）自动触发蓝绿部署。</p>
          </div>
          <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
            <button type="button" className="subtle-button" disabled={!!busy} onClick={() => void triggerCicd()}>
              <Icon name="play" /> {busy === 'trigger-cicd' ? '触发中…' : '立即触发流水线'}
            </button>
            <button type="button" className="primary-button" disabled={!!busy} onClick={() => void saveCicd()}>
              {busy === 'save-cicd' ? '保存中…' : '保存 CI/CD 配置'}
            </button>
          </div>
        </div>

        <div className="svc-grid-4">
          <div className="svc-field">
            <span>代码托管平台</span>
            <Select
              ariaLabel="代码托管平台"
              className="field"
              value={provider}
              onChange={(v) => setProvider(v)}
              options={[
                { value: 'gitlab', label: '企业 GitLab' },
                { value: 'github', label: 'GitHub' },
              ]}
            />
          </div>
          <label className="svc-field">
            <span>监听分支</span>
            <input type="text" className="is-mono" value={branch} onChange={(e) => setBranch(e.target.value)} placeholder="main" />
          </label>
          <div className="svc-field">
            <span>目标部署槽位</span>
            <Select
              ariaLabel="目标部署槽位"
              className="field"
              value={deploySlot}
              onChange={(v) => setDeploySlot(v)}
              options={[
                { value: 'preview', label: '预览槽位', hint: '零影响线上' },
                { value: 'live', label: '线上槽位', hint: '蓝绿零停机切流' },
              ]}
            />
          </div>
          <label className="svc-field">
            <span>Webhook 密钥（留空自动生成）</span>
            <input
              type="password"
              className="is-mono"
              value={webhookSecret}
              onChange={(e) => setWebhookSecret(e.target.value)}
              placeholder={cicdQ.data?.hasWebhookSecret ? '已加密保存，输入新值可轮换' : '自动签发高强度密钥'}
            />
          </label>
        </div>

        <div style={{ marginTop: 10, display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
          <label className="svc-field" style={{ flex: 1, minWidth: 260 }}>
            <span>Git 仓库地址</span>
            <input
              type="text"
              className="is-mono"
              value={repoUrl}
              onChange={(e) => setRepoUrl(e.target.value)}
              placeholder="https://gitlab.csot.tcl.com/team/project.git"
            />
          </label>
          <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, paddingTop: 18, cursor: 'pointer' }}>
            <input type="checkbox" checked={autoDeploy} onChange={(e) => setAutoDeploy(e.target.checked)} />
            Push 事件自动部署
          </label>
        </div>

        <div style={{ marginTop: 10, padding: '8px 10px', borderRadius: 8, background: 'var(--bg-subtle, #f8fafc)', border: '1px solid var(--line, #e2e8f0)', fontSize: 12, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
          <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            Webhook 地址：<code>{webhookFullUrl}</code>
            {cicdQ.data?.lastSyncAt ? ` · 最近触发 ${fmtTime(cicdQ.data.lastSyncAt)} (${cicdQ.data.lastStatus || 'ok'} · ${cicdQ.data.lastCommitSha || ''})` : ''}
          </span>
          <button type="button" className="svc-pill-toggle" onClick={async () => toast((await copyText(webhookFullUrl)) ? '已复制 Webhook 地址。' : '请手动复制。')}>
            <Icon name="copy" /> 复制
          </button>
        </div>

        {generatedSecret ? (
          <div style={{ marginTop: 8, padding: '8px 10px', borderRadius: 8, background: '#ecfdf5', border: '1px solid #a7f3d0', fontSize: 12, color: '#065f46' }}>
            新签发 Webhook Secret（仅展示一次，请填入 GitLab Secret Token）：<code>{generatedSecret}</code>
          </div>
        ) : null}
      </section>

      {/* 区块 2：声明式多服务拓扑 zhiye.yaml & 标准部署包导出 */}
      <section className="svc-section-card">
        <div className="svc-section-head">
          <div>
            <h4>声明式多服务拓扑（<code>zhiye.yaml</code>）与标准 K8s/Docker 部署包</h4>
            <p>支持声明 <code>web</code> / <code>api</code> / <code>worker</code> / <code>cron</code> / <code>postgres</code> / <code>redis</code> 组件，并一键导出生产级 Docker Compose + Helm Chart。</p>
          </div>
          <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
            <a className="subtle-button" href={api.serviceExportBundleUrl(appId)} download style={{ textDecoration: 'none' }}>
              <Icon name="download" /> 导出 Docker / Helm 部署包 (.zip)
            </a>
            <button type="button" className="primary-button" disabled={!!busy} onClick={() => void saveTopo()}>
              {busy === 'save-topo' ? '保存中…' : '保存拓扑配置'}
            </button>
          </div>
        </div>

        {(topoQ.data?.services ?? []).length > 0 ? (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
            {(topoQ.data?.services ?? []).map((s, idx) => (
              <span key={idx} className="svc-chip" style={{ padding: '4px 10px' }}>
                <b>{String(s.name || '')}</b> · {String(s.type || 'api').toUpperCase()}
                {s.port ? ` :${String(s.port)}` : ''}
                {s.replicas ? ` ×${String(s.replicas)}` : ''}
              </span>
            ))}
          </div>
        ) : null}

        <textarea
          value={yamlText}
          onChange={(e) => setYamlText(e.target.value)}
          rows={11}
          spellCheck={false}
          style={{
            width: '100%',
            fontFamily: 'var(--font-mono, monospace)',
            fontSize: 12.5,
            lineHeight: 1.5,
            padding: 10,
            borderRadius: 8,
            border: '1px solid var(--line, #e2e8f0)',
            background: '#0f172a',
            color: '#e2e8f0',
            resize: 'vertical',
          }}
        />
      </section>
    </div>
  );
}
