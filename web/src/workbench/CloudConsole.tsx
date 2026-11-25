import { useEffect, useMemo, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage, type AccessLevel, type CloudRow, type CloudTableInfo, type SiteAccess } from '../api/client';
import { Icon, type IconName } from '../lib/icons';
import { toast } from '../lib/store';
import { excelDate, parseCsv, parseXlsx, type Sheet } from '../lib/sheet';
import { Modal } from '../components/Modal';
import { Select } from '../components/Select';
import { EmptyState, ErrorState, LoadingState } from '../components/EmptyState';
import { confirmDialog } from '../components/ConfirmDialog';
import { CloudTriggersTab } from './CloudTriggersTab';

const SYSTEM = ['id', 'created_by', 'created_by_name', 'created_at', 'updated_at', 'updated_by'];
const SYSTEM_LABEL: Record<string, string> = { id: 'ID', created_by_name: '提交人', created_at: '创建时间', updated_at: '更新时间' };
const HIDDEN = new Set(['created_by', 'updated_by']);
const PAGE = 50;

export const ACCESS_OPTIONS: { value: AccessLevel; label: string; hint: string }[] = [
  { value: 'public', label: '内网公开', hint: '公司内网打开链接就能用，不需要登录。适合看板、展示页、计算器' },
  { value: 'login', label: '员工登录', hint: '先用公司账号登录，应用能知道是谁在用。适合申请、审批、个人数据' },
  { value: 'members', label: '指定人员', hint: '登录后只有名单里的人能打开。适合敏感数据' },
];

const accessKey = (appId: string) => ['access', appId];
const consoleKey = (appId: string) => ['cloud-console', appId];

const splitLogins = (s: string) =>
  s
    .split(/[\s,，;；、]+/)
    .map((x) => x.trim())
    .filter(Boolean);

export interface AccessFormState {
  loading: boolean;
  error: boolean;
  dirty: boolean;
  level: AccessLevel;
  membersCount: number;
  save: () => Promise<SiteAccess>;
}

/** 发布访问设置：发布弹窗和数据控制台共用 */
/**
 * compact：发布弹窗里用，不显示说明和不一致警告，由发布弹窗通过 onBlocked 合成一条提示。
 * onBlocked：选中的访问级别下哪些表无法使用（需要登录身份），空数组表示可以发布。
 * onStateChange：向发布弹窗同步加载态、未保存状态与保存方法，避免未落库的权限选择被发布跳过。
 */
export function AccessForm({
  appId,
  compact = false,
  onBlocked,
  onStateChange,
}: {
  appId: string;
  compact?: boolean;
  onBlocked?: (tables: string[], ssoAvailable: boolean) => void;
  onStateChange?: (state: AccessFormState) => void;
}) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: accessKey(appId), queryFn: () => api.access(appId) });
  const [level, setLevel] = useState<AccessLevel>('public');
  const [members, setMembers] = useState('');
  const [admins, setAdmins] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  useEffect(() => {
    if (!q.data) return;
    setLevel(q.data.level);
    setMembers(q.data.members.join('\n'));
    setAdmins(q.data.admins.join('\n'));
  }, [q.data]);

  const a = q.data as SiteAccess | undefined;
  const dirty = Boolean(a && (level !== a.level || members !== a.members.join('\n') || admins !== a.admins.join('\n')));
  const memberList = useMemo(() => splitLogins(members), [members]);

  const save = async (): Promise<SiteAccess> => {
    setBusy(true);
    setErr('');
    try {
      const next = await api.saveAccess(appId, { level, members: splitLogins(members), admins: splitLogins(admins) });
      qc.setQueryData(accessKey(appId), next);
      void qc.invalidateQueries({ queryKey: consoleKey(appId) });
      if (!compact) toast('访问设置已保存，线上站点立即生效。');
      return next;
    } catch (e) {
      setErr(errorMessage(e));
      throw e;
    } finally {
      setBusy(false);
    }
  };
  const saveRef = useRef(save);
  saveRef.current = save;

  useEffect(() => {
    onStateChange?.({
      loading: q.isLoading,
      error: Boolean(q.error),
      dirty,
      level,
      membersCount: memberList.length,
      save: () => saveRef.current(),
    });
  }, [q.isLoading, q.error, dirty, level, memberList.length, onStateChange]);

  if (q.isLoading) return <LoadingState label="正在读取访问设置…" />;
  if (q.error || !a) return <ErrorState message={errorMessage(q.error)} onRetry={() => q.refetch()} />;
  const mismatch = level === 'public' && a.tablesNeedingLogin.length > 0;
  return (
    <>
    <BlockedReport tables={mismatch ? a.tablesNeedingLogin : []} sso={a.ssoAvailable} onBlocked={onBlocked} />
    <div className={`cc-access ${compact ? 'is-compact' : ''}`}>
      <div className="cc-access-options" role="radiogroup" aria-label="谁能打开这个应用">
        {ACCESS_OPTIONS.map((o) => {
          const locked = o.value !== 'public' && !a.ssoAvailable;
          return (
            <button
              key={o.value}
              type="button"
              role="radio"
              aria-checked={level === o.value}
              className={level === o.value ? 'active' : ''}
              disabled={locked || busy}
              title={locked ? '当前环境还没有接入公司统一登录' : o.hint}
              onClick={() => setLevel(o.value)}
            >
              <b>
                <Icon name={o.value === 'public' ? 'globe' : 'lock'} />
                {o.label}
              </b>
              {compact ? null : <small>{o.hint}</small>}
            </button>
          );
        })}
      </div>
      {!a.ssoAvailable && !compact ? <p className="cc-hint">当前环境还没有接入公司统一登录，暂时只能选「内网公开」。</p> : null}
      {level === 'members' ? (
        <label className="cc-field">
          <span>可以打开的人（公司登录名，一行一个或用逗号分隔）</span>
          <textarea rows={compact ? 2 : 3} value={members} onChange={(e) => setMembers(e.target.value)} placeholder="zhangsan&#10;lisi" />
        </label>
      ) : null}
      {mismatch && !compact ? (
        <p className="cc-warn" role="alert">
          <Icon name="alert" />
          数据表 {a.tablesNeedingLogin.join('、')} 需要知道是谁在用，「内网公开」时无法发布。请改成「员工登录」，或在数据控制台调整这些表的权限。
        </p>
      ) : null}
      {err ? (
        <p className="inline-error" role="alert">
          {err}
        </p>
      ) : null}
      {dirty && !compact ? (
        <div className="cc-access-save">
          <button className="primary-button" disabled={busy} onClick={() => void save().catch(() => undefined)}>
            {busy ? '正在保存…' : '保存访问设置'}
          </button>
        </div>
      ) : null}
      {dirty && compact ? (
        <p className="cc-hint" style={{ marginTop: 6 }}>
          点击「确认发布」时将先保存此访问范围，保存失败会自动暂停发布。
        </p>
      ) : null}
    </div>
    </>
  );
}

function BlockedReport({ tables, sso, onBlocked }: { tables: string[]; sso: boolean; onBlocked?: (tables: string[], sso: boolean) => void }) {
  const key = tables.join(',');
  useEffect(() => onBlocked?.(key ? key.split(',') : [], sso), [key, sso, onBlocked]);
  return null;
}

type Section = 'db' | 'files' | 'functions' | 'triggers' | 'access';
const NAV: [Section, string, IconName][] = [
  ['db', '数据库', 'database'],
  ['files', '文件存储', 'folder'],
  ['functions', '云函数', 'code'],
  ['triggers', '自动化触发器', 'spark'],
  ['access', '访问与登录', 'lock'],
];

/** 数据控制台（云服务已启用时，对照 WorkBuddy）：左侧分区导航；数据库下分「数据 / 权限」 */
export function CloudConsole({ appId, onDisable, disabling }: { appId: string; onDisable: () => void; disabling: boolean }) {
  const [section, setSection] = useState<Section>('db');
  const [dbTab, setDbTab] = useState<'data' | 'policy'>('data');
  const ov = useQuery({ queryKey: consoleKey(appId), queryFn: () => api.consoleOverview(appId) });
  const [table, setTable] = useState<string | null>(null);
  const tables = ov.data?.tables ?? [];
  const current = tables.find((t) => t.name === table) ?? tables[0] ?? null;
  return (
    <div className="cc cc-layout">
      <nav className="cc-nav" aria-label="云服务">
        {NAV.map(([v, label, icon]) => (
          <button key={v} type="button" aria-current={section === v ? 'page' : undefined} className={section === v ? 'active' : ''} onClick={() => setSection(v)}>
            <Icon name={icon} />
            {label}
          </button>
        ))}
        <span className="cc-spacer" />
        <button type="button" className="cc-link" disabled={disabling} onClick={onDisable}>
          {disabling ? '正在关闭…' : '关闭云服务'}
        </button>
      </nav>
      <section className="cc-main">
        {ov.isLoading ? (
          <LoadingState label="正在读取数据…" />
        ) : ov.error ? (
          <ErrorState message={errorMessage(ov.error)} onRetry={() => ov.refetch()} />
        ) : section === 'db' ? (
          <>
            <div className="cc-subtabs" role="tablist" aria-label="数据库">
              {([['data', '数据'], ['policy', '权限']] as const).map(([v, label]) => (
                <button key={v} type="button" role="tab" aria-selected={dbTab === v} className={dbTab === v ? 'active' : ''} onClick={() => setDbTab(v)}>
                  {label}
                </button>
              ))}
            </div>
            {!tables.length ? (
              <EmptyState icon="database" title="还没有数据表" desc="让 AI 按应用设计表结构，任务成功后会自动建表。" />
            ) : dbTab === 'data' && current ? (
              <TableView key={current.name} appId={appId} table={current} tables={tables} counts={ov.data!.counts} onPick={setTable} presets={ov.data!.presets} />
            ) : (
              <div className="cc-section">
                <div className="cc-presets">
                  {tables.map((t) => (
                    <PresetRow key={t.name} appId={appId} table={t} presets={ov.data!.presets} />
                  ))}
                </div>
                <p className="cc-hint">权限由平台在接口层执行，页面代码绕不过去。</p>
              </div>
            )}
          </>
        ) : section === 'files' ? (
          <FilesView appId={appId} />
        ) : section === 'functions' ? (
          <FunctionsView appId={appId} />
        ) : section === 'triggers' ? (
          <CloudTriggersTab appId={appId} tableNames={tables.map((t) => t.name)} />
        ) : (
          <div className="cc-section">
            <h3>谁能打开这个应用</h3>
            <AccessForm appId={appId} />
          </div>
        )}
      </section>
    </div>
  );
}

function PresetRow({ appId, table, presets }: { appId: string; table: CloudTableInfo; presets: { id: string; label: string; needsLogin: boolean }[] }) {
  const qc = useQueryClient();
  const value = table.policy?.preset ?? 'shared';
  return (
    <div className="cc-preset-row">
      <code>{table.name}</code>
      <Select
        ariaLabel={`${table.name} 的数据权限`}
        value={value}
        options={presets.map((p) => ({ value: p.id, label: p.label, hint: p.needsLogin ? '需要员工登录' : '不需要登录' }))}
        onChange={async (preset) => {
          try {
            qc.setQueryData(consoleKey(appId), await api.consolePreset(appId, table.name, preset));
            void qc.invalidateQueries({ queryKey: accessKey(appId) });
            toast('数据权限已更新。');
          } catch (e) {
            toast(errorMessage(e));
          }
        }}
      />
    </div>
  );
}

function fmt(v: unknown) {
  if (v === null || v === undefined) return '';
  if (typeof v === 'object') return JSON.stringify(v);
  const s = String(v);
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(s) ? s.slice(0, 16).replace('T', ' ') : s;
}

function TableView({
  appId,
  table,
  tables,
  counts,
  onPick,
  presets,
}: {
  appId: string;
  table: CloudTableInfo;
  tables: CloudTableInfo[];
  counts: Record<string, number>;
  onPick: (name: string) => void;
  presets: { id: string; label: string; needsLogin: boolean }[];
}) {
  const qc = useQueryClient();
  const userCols = Object.keys(table.columns).filter((c) => !SYSTEM.includes(c));
  const shown = ['id', ...userCols, ...SYSTEM.filter((c) => c !== 'id' && !HIDDEN.has(c) && c in table.columns)];
  const [offset, setOffset] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [searchCol, setSearchCol] = useState(userCols[0] ?? 'id');
  const [editing, setEditing] = useState<CloudRow | 'new' | null>(null);
  const [importing, setImporting] = useState<Sheet | null>(null);
  const [filtering, setFiltering] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const params = useMemo(() => {
    const p: Record<string, string> = { limit: String(PAGE), offset: String(offset) };
    if (keyword.trim()) p[`${searchCol}__like`] = keyword.trim();
    return p;
  }, [offset, keyword, searchCol]);
  const rows = useQuery({ queryKey: [...consoleKey(appId), table.name, params], queryFn: () => api.consoleRows(appId, table.name, params) });
  const refresh = () => qc.invalidateQueries({ queryKey: consoleKey(appId) });
  const total = rows.data?.total ?? 0;
  const preset = presets.find((p) => p.id === (table.policy?.preset ?? 'shared'));

  const pick = async (f: File) => {
    try {
      const sheet = /\.xlsx$/i.test(f.name) ? parseXlsx(await f.arrayBuffer()) : parseCsv(await f.text());
      if (!sheet.headers.length || !sheet.rows.length) throw new Error('文件里没有数据（第一行要是表头）');
      if (sheet.rows.length > 5000) throw new Error(`一次最多导入 5000 行，这个文件有 ${sheet.rows.length} 行`);
      setImporting(sheet);
    } catch (e) {
      toast(/\.xls$/i.test(f.name) ? '暂不支持旧版 .xls，请在 Excel 里另存为 .xlsx 或 CSV。' : errorMessage(e));
    }
  };

  return (
    <div className="cc-table">
      <div className="cc-toolbar">
        <Select
          className="cc-table-select"
          ariaLabel="选择数据表"
          value={table.name}
          options={tables.map((t) => ({ value: t.name, label: t.name, hint: `${counts[t.name] ?? 0} 条` }))}
          onChange={onPick}
        />
        <span className="cc-spacer" />
        <button type="button" className="cc-icon" aria-label="刷新" title="刷新" onClick={() => void refresh()}>
          <Icon name="refresh" />
        </button>
        <button
          type="button"
          className={`cc-icon ${filtering || keyword ? 'active' : ''}`}
          aria-label="筛选"
          title="筛选"
          aria-pressed={filtering}
          onClick={() => setFiltering((v) => !v)}
        >
          <Icon name="search" />
        </button>
        <button
          type="button"
          className="cc-icon danger"
          aria-label="清空数据"
          title="清空数据"
          disabled={!total}
          onClick={async () => {
            if (!(await confirmDialog({ title: `清空 ${table.name} 的全部 ${total} 条数据？`, message: '清空后不能恢复。', confirmLabel: '清空', danger: true }))) return;
            try {
              const r = await api.consoleClear(appId, table.name);
              toast(`已清空 ${r.deleted} 条数据。`);
              setOffset(0);
              void refresh();
            } catch (e) {
              toast(errorMessage(e));
            }
          }}
        >
          <Icon name="trash" />
        </button>
        <button type="button" className="studio-outline" onClick={() => setEditing('new')}>
          <Icon name="plus" />
          添加行
        </button>
        <button type="button" className="studio-outline" onClick={() => fileRef.current?.click()}>
          <Icon name="clip" />
          导入
        </button>
        <a className={`studio-outline ${total ? '' : 'is-disabled'}`} href={api.consoleExportUrl(appId, table.name)} download aria-disabled={!total}>
          <Icon name="download" />
          导出 CSV
        </a>
        <input
          ref={fileRef}
          type="file"
          hidden
          accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          onChange={(e) => {
            const f = e.target.files?.[0];
            e.target.value = '';
            if (f) void pick(f);
          }}
        />
      </div>
      <div className="cc-meta">
        {table.description ? <span>描述：{table.description}</span> : <span>{userCols.length} 个字段</span>}
        <span className="cc-preset-tag" title="数据权限，可在「权限」里修改">
          <Icon name="lock" />
          {preset?.label ?? '所有人可看可改'}
        </span>
        <span className="cc-spacer" />
        {rows.data ? <span>共 {total} 条</span> : null}
      </div>
      {filtering ? (
        <div className="cc-search">
          <Select ariaLabel="搜索字段" value={searchCol} options={userCols.map((c) => ({ value: c, label: c }))} onChange={setSearchCol} />
          <input
            autoFocus
            value={keyword}
            placeholder="包含…"
            aria-label="搜索内容"
            onChange={(e) => {
              setKeyword(e.target.value);
              setOffset(0);
            }}
          />
        </div>
      ) : null}
      {rows.error ? (
        <ErrorState message={errorMessage(rows.error)} onRetry={() => rows.refetch()} />
      ) : (
        <div className="cc-grid-wrap">
          <table className="cc-grid">
            <thead>
              <tr>
                {shown.map((c) => (
                  <th key={c} title={table.columns[c]}>
                    {SYSTEM_LABEL[c] ?? c}
                  </th>
                ))}
                <th aria-label="操作" />
              </tr>
            </thead>
            <tbody>
              {!rows.data?.rows.length ? (
                <tr className="cc-empty-row">
                  <td colSpan={shown.length + 1}>{rows.isLoading ? '正在读取…' : keyword ? '没有匹配的数据' : '暂无数据'}</td>
                </tr>
              ) : null}
              {(rows.data?.rows ?? []).map((r) => (
                <tr key={r.id}>
                  {shown.map((c) => (
                    <td key={c} title={fmt(r[c])}>
                      {fmt(r[c])}
                    </td>
                  ))}
                  <td className="cc-row-actions">
                    <button className="icon-button" aria-label="编辑" onClick={() => setEditing(r)}>
                      <Icon name="edit" />
                    </button>
                    <button
                      className="icon-button"
                      aria-label="删除"
                      onClick={async () => {
                        if (!(await confirmDialog({ title: `删除第 ${r.id} 条数据？`, confirmLabel: '删除', danger: true }))) return;
                        try {
                          await api.consoleDelete(appId, table.name, r.id);
                          void refresh();
                        } catch (e) {
                          toast(errorMessage(e));
                        }
                      }}
                    >
                      <Icon name="trash" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {total > PAGE ? (
        <div className="cc-pager">
          <button className="subtle-button" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>
            上一页
          </button>
          <span>
            {offset + 1}–{Math.min(offset + PAGE, total)} / 共 {total} 条
          </span>
          <button className="subtle-button" disabled={offset + PAGE >= total} onClick={() => setOffset(offset + PAGE)}>
            下一页
          </button>
        </div>
      ) : null}
      {editing ? (
        <RowEditor appId={appId} table={table} row={editing === 'new' ? null : editing} cols={userCols} onClose={() => setEditing(null)} onSaved={() => void refresh()} />
      ) : null}
      {importing ? <ImportDialog appId={appId} table={table} cols={userCols} sheet={importing} onClose={() => setImporting(null)} onDone={() => void refresh()} /> : null}
    </div>
  );
}

function RowEditor({
  appId,
  table,
  row,
  cols,
  onClose,
  onSaved,
}: {
  appId: string;
  table: CloudTableInfo;
  row: CloudRow | null;
  cols: string[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [values, setValues] = useState<Record<string, string>>(() => Object.fromEntries(cols.map((c) => [c, row ? fmt(row[c]) : ''])));
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const save = async () => {
    setBusy(true);
    setErr('');
    const body: Record<string, unknown> = {};
    for (const c of cols) {
      const v = values[c];
      if (row ? v !== fmt(row[c]) : v !== '') body[c] = v === '' ? null : v;
    }
    try {
      if (row) await api.consoleUpdate(appId, table.name, row.id, body);
      else await api.consoleInsert(appId, table.name, body);
      onSaved();
      onClose();
    } catch (e) {
      setErr(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={row ? `编辑第 ${row.id} 条` : `新增到 ${table.name}`}
      overline="数据控制台"
      onClose={onClose}
      footer={
        <>
          <button className="subtle-button" onClick={onClose}>
            取消
          </button>
          <button className="primary-button" disabled={busy} onClick={() => void save()}>
            {busy ? '正在保存…' : '保存'}
          </button>
        </>
      }
    >
      <div className="cc-form">
        {cols.map((c) => (
          <label key={c} className="cc-field">
            <span>
              {c} <small>{table.columns[c]}</small>
            </span>
            {table.columns[c] === 'boolean' ? (
              <Select
                ariaLabel={c}
                value={values[c] || ''}
                options={[
                  { value: '', label: '（空）' },
                  { value: 'true', label: '是' },
                  { value: 'false', label: '否' },
                ]}
                onChange={(v) => setValues({ ...values, [c]: v })}
              />
            ) : (
              <input
                value={values[c]}
                type={table.columns[c] === 'date' ? 'date' : 'text'}
                onChange={(e) => setValues({ ...values, [c]: e.target.value })}
              />
            )}
          </label>
        ))}
        {err ? (
          <p className="inline-error" role="alert">
            {err}
          </p>
        ) : null}
        <p className="cc-hint">保存时会按 cloud/policies.json 里的字段规则和状态流转校验。</p>
      </div>
    </Modal>
  );
}

/** 导入：自动按同名匹配字段，可手动调整映射，预览前 5 行后整批提交 */
function ImportDialog({
  appId,
  table,
  cols,
  sheet,
  onClose,
  onDone,
}: {
  appId: string;
  table: CloudTableInfo;
  cols: string[];
  sheet: Sheet;
  onClose: () => void;
  onDone: () => void;
}) {
  const [map, setMap] = useState<Record<string, string>>(() => {
    const norm = (s: string) => s.toLowerCase().replace(/[\s_-]/g, '');
    return Object.fromEntries(cols.map((c) => [c, sheet.headers.find((h) => norm(h) === norm(c)) ?? '']));
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const mapped = cols.filter((c) => map[c]);
  const build = () =>
    sheet.rows.map((r) => {
      const o: Record<string, unknown> = {};
      for (const c of mapped) {
        let v = r[sheet.headers.indexOf(map[c])] ?? '';
        const t = table.columns[c];
        if (t === 'date' || t.startsWith('timestamp')) v = excelDate(v, t !== 'date');
        if (v !== '') o[c] = v;
      }
      return o;
    });
  const preview = useMemo(build, [map, sheet]); // eslint-disable-line react-hooks/exhaustive-deps
  const go = async () => {
    setBusy(true);
    setErr('');
    try {
      const r = await api.consoleImport(appId, table.name, preview);
      toast(`已导入 ${r.inserted} 条数据。`);
      onDone();
      onClose();
    } catch (e) {
      setErr(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={`导入到 ${table.name}`}
      overline="数据控制台"
      wide
      onClose={onClose}
      footer={
        <>
          <button className="subtle-button" onClick={onClose}>
            取消
          </button>
          <button className="primary-button" disabled={busy || !mapped.length} onClick={() => void go()}>
            {busy ? '正在导入…' : `导入 ${sheet.rows.length} 条`}
          </button>
        </>
      }
    >
      <p className="cc-hint">文件有 {sheet.rows.length} 行数据。把表里的字段对应到文件的列，没对应的字段留空。任何一行不符合规则时整批不导入，并告诉你是第几行。</p>
      <div className="cc-mapping">
        {cols.map((c) => (
          <label key={c} className="cc-field">
            <span>
              {c} <small>{table.columns[c]}</small>
            </span>
            <Select
              ariaLabel={`${c} 对应的列`}
              value={map[c]}
              options={[{ value: '', label: '（不导入）' }, ...sheet.headers.filter(Boolean).map((h) => ({ value: h, label: h }))]}
              onChange={(v) => setMap({ ...map, [c]: v })}
            />
          </label>
        ))}
      </div>
      {mapped.length ? (
        <div className="cc-grid-wrap">
          <table className="cc-grid">
            <thead>
              <tr>
                {mapped.map((c) => (
                  <th key={c}>{c}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {preview.slice(0, 5).map((r, i) => (
                <tr key={i}>
                  {mapped.map((c) => (
                    <td key={c}>{fmt(r[c])}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
      {err ? (
        <p className="inline-error" role="alert">
          {err}
        </p>
      ) : null}
    </Modal>
  );
}

function FilesView({ appId }: { appId: string }) {
  const q = useQuery({ queryKey: [...consoleKey(appId), 'files'], queryFn: () => api.consoleFiles(appId) });
  if (q.isLoading) return <LoadingState label="正在读取文件…" />;
  if (q.error) return <ErrorState message={errorMessage(q.error)} onRetry={() => q.refetch()} />;
  const files = q.data?.files ?? [];
  if (!files.length) return <EmptyState icon="folder" title="还没有文件" desc="应用里通过 zhiye.cloud.storage.upload 上传的文件会出现在这里。" />;
  return (
    <div className="cc-files">
      {files.map((f) => (
        <div key={f} className="cc-file-row">
          <Icon name="file" />
          <b title={f}>{f}</b>
          <a className="icon-button" aria-label="下载" href={api.consoleFileUrl(appId, f)} download>
            <Icon name="download" />
          </a>
          <button
            className="icon-button"
            aria-label="删除"
            onClick={async () => {
              if (!(await confirmDialog({ title: `删除文件 ${f}？`, confirmLabel: '删除', danger: true }))) return;
              try {
                await api.consoleDeleteFile(appId, f);
                void q.refetch();
              } catch (e) {
                toast(errorMessage(e));
              }
            }}
          >
            <Icon name="trash" />
          </button>
        </div>
      ))}
    </div>
  );
}

function FunctionsView({ appId }: { appId: string }) {
  const q = useQuery({ queryKey: [...consoleKey(appId), 'functions'], queryFn: () => api.consoleFunctions(appId), refetchInterval: 15_000 });
  const [open, setOpen] = useState<number | null>(null);
  if (q.isLoading) return <LoadingState label="正在读取函数…" />;
  if (q.error) return <ErrorState message={errorMessage(q.error)} onRetry={() => q.refetch()} />;
  const d = q.data!;
  if (!d.functions.length)
    return (
      <EmptyState
        icon="code"
        title="还没有云函数"
        desc="审批流转、多表一起写、提交前的业务校验这类逻辑，可以让 AI 写成云函数（functions/*.ts），在服务端执行。"
      />
    );
  return (
    <div className="cc-section">
      {!d.available ? <p className="cc-warn">当前环境没有安装云函数运行服务，函数暂时无法调用。</p> : null}
      <div className="cc-files">
        {d.functions.map((f) => (
          <div key={f.name} className="cc-file-row">
            <Icon name="code" />
            <b>functions/{f.name}.ts</b>
            <small className="cc-fn-stat">24 小时 {f.calls} 次</small>
            <small className={`cc-fn-stat ${f.errors ? 'bad' : ''}`}>{f.errors} 次报错</small>
          </div>
        ))}
      </div>
      <h3>最近调用</h3>
      {d.logs.length ? (
        <div className="cc-files">
          {d.logs.map((l, i) => (
            <div key={i} className="cc-fn-log">
              <button className="cc-file-row" onClick={() => setOpen(open === i ? null : i)}>
                <span className={`cc-dot ${l.ok ? 'ok' : 'bad'}`} />
                <b>{l.name}</b>
                <small className="cc-fn-stat">{l.ok ? `${l.durationMs} ms` : (l.error ?? '').split('\n')[0]}</small>
                <small className="cc-fn-stat">{l.caller ?? '未登录'} · {l.at.slice(5, 16).replace('T', ' ')}</small>
              </button>
              {open === i && (l.error || l.logs) ? <pre className="cc-fn-detail">{[l.error, l.logs].filter(Boolean).join('\n\n')}</pre> : null}
            </div>
          ))}
        </div>
      ) : (
        <p className="cc-hint">还没有调用记录。</p>
      )}
      <p className="cc-hint">记录保留 7 天。函数报错后，在对话里说一句「某某按钮报错了」，AI 会读取最近的报错来修。</p>
    </div>
  );
}
