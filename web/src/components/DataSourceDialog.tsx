import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type { DataSourceInfo, DataSourceInput, DataSourceParam } from '../api/types';
import { Icon } from '../lib/icons';
import { toast, useUi } from '../lib/store';
import { Modal } from './Modal';
import { Select } from './Select';
import { confirmDialog } from './ConfirmDialog';
import { AgwCatalog } from './AgwCatalog';

const TYPES: DataSourceParam['type'][] = ['string', 'number', 'integer', 'boolean', 'date', 'json'];
const TYPE_LABEL: Record<DataSourceParam['type'], string> = { string: '文本', number: '数字', integer: '整数', boolean: '是/否', date: '日期', json: 'JSON' };
const AUTH_LABEL: Record<DataSourceInfo['authType'], string> = { none: '无需鉴权', bearer: 'Bearer 令牌', header: '自定义请求头', basic: 'Basic（用户名:密码）', agw: 'API 网关签名', agw_key: 'API 网关项目 Key', agw_client: 'API 网关客户端模式', agw_user: 'API 网关用户模式' };

type View = { kind: 'list' } | { kind: 'catalog' } | { kind: 'edit'; source: DataSourceInfo | null } | { kind: 'test'; source: DataSourceInfo };

export function useSources(enabled = true) {
  return useQuery({ queryKey: qk.sources, queryFn: api.sources, enabled, staleTime: 30_000 });
}

/** 数据源管理弹窗：专注管理 AGW / API 企业数据源（飞书与 MCP 已统一收口至「生态」快捷浮层）。 */
export function DataSourceDialog() {
  const open = useUi((s) => s.sourcesOpen);
  const setOpen = useUi((s) => s.setSourcesOpen);
  const [view, setView] = useState<View>({ kind: 'list' });
  if (!open) return null;
  const close = () => {
    setOpen(false);
    setView({ kind: 'list' });
  };
  const title =
    view.kind === 'catalog'
      ? '可调用 API'
      : view.kind === 'edit'
        ? '编辑数据源'
        : view.kind === 'test'
          ? `试调用 · ${view.source.name}`
          : '企业数据源';
  return (
    <Modal title={title} onClose={close} className="source-modal">
      {view.kind === 'list' ? (
        <SourceList onAddApi={() => setView({ kind: 'catalog' })} onEdit={(source) => setView({ kind: 'edit', source })} onTest={(source) => setView({ kind: 'test', source })} />
      ) : view.kind === 'catalog' ? (
        <AgwCatalog onDone={() => setView({ kind: 'list' })} />
      ) : view.kind === 'edit' ? (
        <SourceForm source={view.source} onDone={(s) => setView(s ? { kind: 'test', source: s } : { kind: 'list' })} />
      ) : (
        <SourceTest source={view.source} onBack={() => setView({ kind: 'list' })} />
      )}
    </Modal>
  );
}

function SourceList({ onAddApi, onEdit, onTest }: { onAddApi: () => void; onEdit: (s: DataSourceInfo | null) => void; onTest: (s: DataSourceInfo) => void }) {
  const qc = useQueryClient();
  const list = useSources();
  const remove = async (s: DataSourceInfo) => {
    if (!(await confirmDialog({ title: `删除数据源「${s.name}」？`, message: '已绑定的应用将不能再调用它。', confirmLabel: '删除', danger: true }))) return;
    try {
      await api.deleteSource(s.id);
      await qc.invalidateQueries({ queryKey: qk.sources });
      void qc.invalidateQueries({ queryKey: ['appSources'] });
      toast('数据源已删除。');
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };
  return (
    <div className="source-list">
      <p className="source-intro">
        从 API 网关添加当前账号<b>可调用的查询接口</b>后，在对话框「＋ → 数据源」里选择，生成的应用就能用真实数据。接口地址和凭证只保存在平台，不会出现在页面代码里。
      </p>
      <div className="source-list-head">
        <span>{list.data ? `共 ${list.data.length} 个` : ''}</span>
        <div className="source-list-buttons">
          <button type="button" className="primary-button" onClick={onAddApi}>
            <Icon name="web" /> 选择可调用 API
          </button>
        </div>
      </div>
      {list.isLoading ? <p className="muted-note">正在读取…</p> : null}
      {list.error ? <p className="inline-error">{errorMessage(list.error)}</p> : null}
      {list.data && !list.data.length ? (
        <div className="source-empty">
          <Icon name="database" />
          <b>还没有数据源</b>
          <p>点上方「选择可调用 API」，从 API 网关添加你有权调用的接口；新接口请先在 AGW 侧登记。</p>
        </div>
      ) : null}
      <ul>
        {list.data?.map((s) => (
          <li key={s.id} className="source-row">
            <span className="source-row-icon">
              <Icon name="database" />
            </span>
            <span className="source-row-main">
              <b>
                {s.name}
                {s.platform ? <em className="source-badge">平台</em> : null}
                {s.provider === 'agw' ? <em className="source-badge">API网关</em> : null}
              </b>
              <small>
                <code>{s.key}</code> · {s.method} · {s.params.length ? `${s.params.length} 个参数` : '无参数'} · {AUTH_LABEL[s.authType]}
              </small>
              {s.description ? <p>{s.description}</p> : null}
            </span>
            <span className="source-row-actions">
              <button type="button" className="subtle-button" onClick={() => onTest(s)}>
                试调用
              </button>
              {s.editable ? (
                <>
                  <button type="button" className="icon-button" aria-label={`编辑 ${s.name}`} onClick={() => onEdit(s)}>
                    <Icon name="edit" />
                  </button>
                  <button type="button" className="icon-button" aria-label={`删除 ${s.name}`} onClick={() => void remove(s)}>
                    <Icon name="trash" />
                  </button>
                </>
              ) : null}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

const emptyParam = (): DataSourceParam => ({ name: '', type: 'string', required: false, description: '', example: '' });

export function SourceForm({ source, onDone }: { source: DataSourceInfo | null; onDone: (s: DataSourceInfo | null) => void }) {
  const qc = useQueryClient();
  const [f, setF] = useState<DataSourceInput>(() => ({
    key: source?.key ?? '',
    name: source?.name ?? '',
    description: source?.description ?? '',
    method: source?.method ?? 'GET',
    url: source?.url ?? '',
    params: source?.params ?? [],
    sample: source?.sample ?? '',
    authType: source?.authType ?? 'none',
    authHeader: source?.authHeader ?? '',
    secret: '',
  }));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const set = (p: Partial<DataSourceInput>) => setF((x) => ({ ...x, ...p }));
  const params = f.params as DataSourceParam[];
  const setParam = (i: number, p: Partial<DataSourceParam>) => set({ params: params.map((x, k) => (k === i ? { ...x, ...p } : x)) });
  const placeholders = Array.from(f.url.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}/g)).map((m) => m[1]);
  const missing = placeholders.filter((n) => !params.some((p) => p.name === n));

  const save = async () => {
    setBusy(true);
    setError('');
    const body: DataSourceInput = { ...f, params: params.filter((p) => p.name.trim()), secret: f.secret || undefined };
    if (body.authType !== 'header' && body.authType !== 'agw') delete body.authHeader;
    try {
      const saved = source ? await api.updateSource(source.id, body) : await api.createSource(body);
      await qc.invalidateQueries({ queryKey: qk.sources });
      void qc.invalidateQueries({ queryKey: ['appSources'] });
      toast(source ? '数据源已更新。' : '数据源已登记，试调用一下看看。');
      onDone(saved);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form
      className="source-form"
      onSubmit={(e) => {
        e.preventDefault();
        void save();
      }}
    >
      <div className="source-grid">
        <label>
          <span className="field-label">名称</span>
          <input className="field" required maxLength={40} value={f.name} placeholder="MES 产线日产量" onChange={(e) => set({ name: e.target.value })} />
        </label>
        <label>
          <span className="field-label">调用名</span>
          <input
            className="field mono"
            required
            value={f.key}
            placeholder="mes_daily_output"
            pattern="[a-z][a-z0-9_]{1,47}"
            title="小写字母开头，只用小写字母、数字、下划线"
            onChange={(e) => set({ key: e.target.value.toLowerCase() })}
          />
        </label>
      </div>
      <label>
        <span className="field-label">说明（给 AI 看：数据是什么、什么时候用）</span>
        <textarea className="field" rows={2} maxLength={500} value={f.description} placeholder="按产线和日期查询每小时的投入、产出和良率" onChange={(e) => set({ description: e.target.value })} />
      </label>
      <div className="source-grid source-grid-url">
        <label>
          <span className="field-label">方法</span>
          <Select
            ariaLabel="请求方法"
            className="field"
            value={f.method}
            options={[
              { value: 'GET', label: 'GET' },
              { value: 'POST', label: 'POST（查询）' },
            ]}
            onChange={(v) => set({ method: v as 'GET' | 'POST' })}
          />
        </label>
        <label>
          <span className="field-label">接口地址（可用 {'{参数}'} 占位）</span>
          <input className="field mono" required value={f.url} placeholder="https://apigw.example.com/mes/daily/{line}" onChange={(e) => set({ url: e.target.value })} />
        </label>
      </div>

      <div className="source-params">
        <div className="source-params-head">
          <span className="field-label">参数</span>
          <button type="button" className="text-button" onClick={() => set({ params: [...params, emptyParam()] })}>
            <Icon name="plus" /> 添加参数
          </button>
        </div>
        {missing.length ? (
          <button type="button" className="source-hint" onClick={() => set({ params: [...params, ...missing.map((name) => ({ ...emptyParam(), name, required: true }))] })}>
            地址里的 {missing.map((m) => `{${m}}`).join('、')} 还没定义，点击添加
          </button>
        ) : null}
        {params.map((p, i) => (
          <div key={i} className="source-param">
            <input className="field mono" aria-label="参数名" placeholder="参数名" value={p.name} onChange={(e) => setParam(i, { name: e.target.value })} />
            <Select
              ariaLabel="类型"
              className="field"
              value={p.type}
              options={TYPES.map((t) => ({ value: t, label: TYPE_LABEL[t] }))}
              onChange={(v) => setParam(i, { type: v as DataSourceParam['type'] })}
            />
            <input className="field" aria-label="参数说明" placeholder="说明" value={p.description} onChange={(e) => setParam(i, { description: e.target.value })} />
            <input className="field" aria-label="示例值" placeholder="示例" value={p.example} onChange={(e) => setParam(i, { example: e.target.value })} />
            <label className="source-required">
              <input type="checkbox" checked={p.required || placeholders.includes(p.name)} disabled={placeholders.includes(p.name)} onChange={(e) => setParam(i, { required: e.target.checked })} />
              必填
            </label>
            <button type="button" className="icon-button" aria-label="删除参数" onClick={() => set({ params: params.filter((_, k) => k !== i) })}>
              <Icon name="close" />
            </button>
          </div>
        ))}
      </div>

      <div className="source-grid">
        <label>
          <span className="field-label">鉴权方式</span>
          <Select
            ariaLabel="鉴权方式"
            className="field"
            value={f.authType}
            options={(Object.keys(AUTH_LABEL) as DataSourceInfo['authType'][]).map((a) => ({ value: a, label: AUTH_LABEL[a] }))}
            onChange={(v) => set({ authType: v as DataSourceInfo['authType'] })}
          />
        </label>
        {f.authType === 'header' ? (
          <label>
            <span className="field-label">请求头名称</span>
            <input className="field mono" required value={f.authHeader ?? ''} placeholder="X-Api-Key" onChange={(e) => set({ authHeader: e.target.value })} />
          </label>
        ) : null}
        {f.authType === 'agw' ? (
          <label>
            <span className="field-label">调用项目 AppKey</span>
            <input className="field mono" required value={f.authHeader ?? ''} placeholder="项目 AppKey" onChange={(e) => set({ authHeader: e.target.value })} />
          </label>
        ) : null}
      </div>
      {!['none', 'agw_client', 'agw_user'].includes(f.authType) ? (
        <label>
          <span className="field-label">{f.authType === 'agw' ? 'AppSecret' : '凭证'}{source?.hasSecret ? '（已保存，留空表示不修改）' : ''}</span>
          <input
            className="field mono"
            type="password"
            autoComplete="new-password"
            required={!source?.hasSecret}
            value={f.secret ?? ''}
            placeholder={f.authType === 'basic' ? '用户名:密码' : f.authType === 'agw' ? '项目 AppSecret' : '令牌'}
            onChange={(e) => set({ secret: e.target.value })}
          />
          <small className="muted-note">凭证加密保存在平台，只在代理请求时使用，不会返回给浏览器。</small>
        </label>
      ) : null}
      {f.authType === 'agw_client' ? <small className="muted-note">系统身份调用，权限由 AGW 客户端授权决定，无需填写密钥。</small> : null}
      {f.authType === 'agw_user' ? <small className="muted-note">按工作空间所有者的统一认证工号换取用户令牌，由 AGW 校验该用户的权限。</small> : null}
      <label>
        <span className="field-label">返回示例（JSON，AI 据此写解析代码）</span>
        <textarea className="field mono" rows={5} value={f.sample} placeholder='{"rows":[{"hour":8,"input":130,"output":120,"yield":0.92}]}' onChange={(e) => set({ sample: e.target.value })} />
      </label>
      {error ? (
        <p className="inline-error" role="alert">
          {error}
        </p>
      ) : null}
      <div className="source-actions">
        <button type="button" className="subtle-button" onClick={() => onDone(null)}>
          返回
        </button>
        <button type="submit" className="primary-button" disabled={busy}>
          {busy ? '正在保存…' : source ? '保存' : '登记'}
        </button>
      </div>
    </form>
  );
}

export function SourceTest({ source, onBack }: { source: DataSourceInfo; onBack: () => void }) {
  const qc = useQueryClient();
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(source.params.map((p) => [p.name, p.example ?? ''])));
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string; ms: number } | null>(null);
  const run = async () => {
    setBusy(true);
    const started = performance.now();
    const params = Object.fromEntries(Object.entries(values).filter(([, v]) => v !== ''));
    try {
      const r = await api.testSource(source.id, params);
      setResult({ ok: true, text: typeof r.data === 'string' ? r.data : JSON.stringify(r.data, null, 2), ms: performance.now() - started });
    } catch (err) {
      setResult({ ok: false, text: errorMessage(err), ms: performance.now() - started });
    } finally {
      setBusy(false);
      void qc.invalidateQueries({ queryKey: ['sourceCalls', source.id] });
    }
  };
  const exampleValue = (p: DataSourceParam) => p.type === 'json' ? (p.example || '{ … }') : (p.example ? JSON.stringify(p.example) : '…');
  const usage = `await window.zhiye.source.query('${source.key}'${source.params.length ? `, { ${source.params.map((p) => `${p.name}: ${exampleValue(p)}`).join(', ')} }` : ''})`;
  return (
    <div className="source-test">
      <p className="source-usage">
        <span>应用里这样调用</span>
        <code>{usage}</code>
      </p>
      {source.params.length ? (
        <div className="source-test-params">
          {source.params.map((p) => {
            const sensitive = /secret|password|credential|token|app[-_]?key|api[-_]?key|authorization|webhook/i.test(p.name);
            return (
              <label key={p.name}>
                <span className="field-label">
                  {p.name === '_body' ? '完整 JSON 请求体' : p.name}
                  {p.required ? <i>*</i> : null}
                  <small>{TYPE_LABEL[p.type]}{p.position ? ` · ${p.position}` : ''}{sensitive ? ' · 敏感信息，不保存' : p.name === '_body' ? ' · 填写后覆盖其他参数' : p.description ? ` · ${p.description}` : ''}</small>
                </span>
                {p.type === 'json' ? (
                  <textarea
                    className="field mono source-json-input"
                    aria-label={p.name === '_body' ? '完整 JSON 请求体' : p.name}
                    rows={p.name === '_body' ? 7 : 4}
                    maxLength={20000}
                    placeholder={'{"appId":"填写接口要求的 appId","其他字段":"按接口契约填写"}'}
                    value={values[p.name] ?? ''}
                    onChange={(e) => setValues((v) => ({ ...v, [p.name]: e.target.value }))}
                  />
                ) : (
                  <input className="field mono" type={sensitive ? 'password' : 'text'} autoComplete={sensitive ? 'off' : undefined} aria-label={p.name} value={values[p.name] ?? ''} onChange={(e) => setValues((v) => ({ ...v, [p.name]: e.target.value }))} />
                )}
              </label>
            );
          })}
        </div>
      ) : (
        <p className="muted-note">这个数据源不需要参数。</p>
      )}
      <div className="source-actions">
        <button type="button" className="subtle-button" onClick={onBack}>
          返回列表
        </button>
        <button type="button" className="primary-button" disabled={busy} onClick={() => void run()}>
          <Icon name="play" /> {busy ? '正在调用…' : '试调用'}
        </button>
      </div>
      {result ? (
        <div className={`source-result ${result.ok ? 'ok' : 'failed'}`} role="status">
          <header>
            <Icon name={result.ok ? 'check' : 'alert'} />
            {result.ok ? '调用成功' : '调用失败'}
            <small>{Math.round(result.ms)} ms</small>
          </header>
          <pre>{result.text.length > 6000 ? result.text.slice(0, 6000) + '\n…（只显示前 6000 字）' : result.text}</pre>
        </div>
      ) : null}
    </div>
  );
}
