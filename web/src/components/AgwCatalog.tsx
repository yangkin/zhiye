import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import type { AgwApiItem, DataSourceInfo } from '../api/types';
import { Icon } from '../lib/icons';
import { qk } from '../api/queries';

interface Props {
  onBack: () => void;
  onDone: (source: DataSourceInfo) => void;
}

export function AgwCatalog({ onBack, onDone }: Props) {
  const qc = useQueryClient();
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<AgwApiItem | null>(null);
  const [appKey, setAppKey] = useState('');
  const [appSecret, setAppSecret] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const credRef = useRef<HTMLFormElement>(null);
  const selectItem = (item: AgwApiItem | null) => {
    setSelected(item);
    setAppKey('');
    setAppSecret('');
    setError('');
  };
  useEffect(() => {
    if (selected) credRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [selected]);
  const list = useQuery({
    queryKey: ['agw-apis', query, page],
    queryFn: () => api.agwApis({ q: query, page, size: 20 }),
    retry: false,
  });

  const importApi = async () => {
    if (!selected || busy) return;
    setBusy(true);
    setError('');
    try {
      const source = await api.importAgwApi(selected.id, { appKey, appSecret });
      await qc.invalidateQueries({ queryKey: qk.sources });
      await qc.invalidateQueries({ queryKey: ['agw-apis'] });
      setAppSecret('');
      onDone(source);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="agw-catalog">
      <p className="source-intro">这里只列出当前账号可见、已启用且已上架的 API。登录令牌仅用于读取目录；应用调用时使用你填写的调用项目凭证。</p>
      <form className="agw-catalog-tools" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(keyword.trim()); }}>
        <label className="skillhub-market-search">
          <Icon name="search" />
          <input aria-label="搜索 API 网关接口" placeholder="搜索 API 名称、编码或描述" value={keyword} onChange={(e) => setKeyword(e.target.value)} />
        </label>
        <button className="primary-button" type="submit">搜索</button>
      </form>
      {list.isLoading ? <p className="muted-note">正在读取 API 网关目录…</p> : null}
      {list.error ? <p className="inline-error" role="alert">{errorMessage(list.error)}</p> : null}
      {!list.isLoading && !list.error && !list.data?.items.length ? <p className="source-empty">没有找到符合条件的 API</p> : null}
      <ul className="agw-api-list">
        {list.data?.items.map((item) => (
          <li key={item.id} className="source-row">
            <span className="source-row-icon"><Icon name="database" /></span>
            <span className="source-row-main">
              <b>{item.name}{item.importedSourceId ? <em className="source-badge">已导入</em> : null}</b>
              <small><code>{item.method} {item.path}</code></small>
              <small>{item.appName || item.appCode || '未标提供方'}{item.dataSourceName ? ` · ${item.dataSourceName}` : ''} · {item.paramCount} 个参数</small>
              {item.description ? <p>{item.description}</p> : null}
              {!item.supported ? <p className="inline-error">{item.unsupportedReason}</p> : null}
            </span>
            <span className="source-row-actions">
              <button type="button" className="subtle-button" disabled={!item.supported} onClick={() => selectItem(item)}>
                {item.importedSourceId ? '更新' : '导入'}
              </button>
            </span>
          </li>
        ))}
      </ul>
      <div className="skillhub-market-footer">
        <span>共 {list.data?.totalElements ?? 0} 个 API</span>
        <div>
          <button type="button" className="icon-button" aria-label="上一页" disabled={page <= 0} onClick={() => setPage((p) => p - 1)}>‹</button>
          <span>{page + 1} / {Math.max(1, list.data?.totalPages ?? 1)}</span>
          <button type="button" className="icon-button" aria-label="下一页" disabled={page + 1 >= (list.data?.totalPages ?? 1)} onClick={() => setPage((p) => p + 1)}>›</button>
        </div>
      </div>
      {selected ? (
        <form ref={credRef} className="agw-credential" onSubmit={(e) => { e.preventDefault(); void importApi(); }}>
          <h3>导入「{selected.name}」</h3>
          <p>填写已获得该 API 授权的<b>调用项目</b>凭证，不是提供方应用编码。</p>
          <label>
            <span className="field-label">调用项目 AppKey</span>
            <input autoFocus className="field mono" required={!selected.importedSourceId} value={appKey} placeholder={selected.importedSourceId ? '留空保留原 AppKey' : '项目 AppKey'} onChange={(e) => setAppKey(e.target.value)} />
          </label>
          <label>
            <span className="field-label">AppSecret</span>
            <input className="field mono" type="password" autoComplete="new-password" required={!selected.importedSourceId} value={appSecret} placeholder={selected.importedSourceId ? '留空保留原 AppSecret' : '项目 AppSecret'} onChange={(e) => setAppSecret(e.target.value)} />
          </label>
          <small className="muted-note">AppSecret 加密保存在服务端，不会返回浏览器或写入生成的应用。</small>
          {error ? <p className="inline-error" role="alert">{error}</p> : null}
          <div className="source-actions">
            <button type="button" className="subtle-button" onClick={() => selectItem(null)}>取消</button>
            <button type="submit" className="primary-button" disabled={busy}>{busy ? '正在导入…' : selected.importedSourceId ? '更新数据源' : '导入并试调用'}</button>
          </div>
        </form>
      ) : null}
      <div className="source-actions">
        <button type="button" className="subtle-button" onClick={onBack}>返回数据源</button>
      </div>
    </div>
  );
}
