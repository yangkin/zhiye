import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type { AgwCallableApi, DataSourceInfo } from '../api/types';
import { toast } from '../lib/store';

const idOf = (item: AgwCallableApi) => `${item.projectCode}:${item.apiId}`;

export function AgwCatalog({ onDone }: { onDone: () => void }) {
  const qc = useQueryClient();
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [busy, setBusy] = useState('');
  const list = useQuery({ queryKey: ['agwCallableApis'], queryFn: api.agwCallableApis });
  const sources = useQuery({ queryKey: qk.sources, queryFn: api.sources });
  const addedIds = useMemo(() => new Set((sources.data ?? []).filter((item: DataSourceInfo) => item.provider === 'agw').map((item) => item.externalId)), [sources.data]);
  const items = list.data ?? [];
  const candidates = items.filter((item) => !addedIds.has(idOf(item)));
  const selectedItems = candidates.filter((item) => selected.has(idOf(item)));
  const allSelected = candidates.length > 0 && candidates.every((item) => selected.has(idOf(item)));

  const addOne = async (item: AgwCallableApi) => {
    const id = idOf(item);
    setBusy(id);
    try {
      await api.agwAddApi(item.apiId, item.projectCode);
      await qc.invalidateQueries({ queryKey: qk.sources });
      setSelected((old) => new Set([...old].filter((x) => x !== id)));
      toast(`已添加 API「${item.apiName}」。`);
    } catch (error) {
      toast(errorMessage(error), 'error');
    } finally {
      setBusy('');
    }
  };

  const addBatch = async () => {
    if (!selectedItems.length) return;
    setBusy('batch');
    const failed: string[] = [];
    for (const item of selectedItems) {
      try {
        await api.agwAddApi(item.apiId, item.projectCode);
        setSelected((old) => new Set([...old].filter((x) => x !== idOf(item))));
      } catch (error) {
        failed.push(item.apiName);
      }
    }
    await qc.invalidateQueries({ queryKey: qk.sources });
    setBusy('');
    if (failed.length) toast(`有 ${failed.length} 个添加失败：${failed.join('、')}`, 'error');
    else toast(`已批量添加 ${selectedItems.length} 个 API。`);
  };

  const toggleAll = (checked: boolean) => {
    setSelected(checked ? new Set(candidates.map(idOf)) : new Set());
  };

  return (
    <div className="source-list">
      <p className="source-intro">这里只显示当前账号在消费项目中实际可调用的 API。勾选后可批量添加；已添加的会保留在这里并标记状态。</p>
      <div className="source-list-head agw-toolbar">
        <label className="agw-check">
          <input type="checkbox" checked={allSelected} disabled={!candidates.length || !!busy} onChange={(e) => toggleAll(e.target.checked)} />
          全选未添加（{candidates.length}）
        </label>
        <div className="source-list-buttons">
          <button type="button" className="primary-button" disabled={!selectedItems.length || !!busy} onClick={() => void addBatch()}>
            {busy === 'batch' ? '批量添加中…' : `添加所选（${selectedItems.length}）`}
          </button>
          <button type="button" className="subtle-button" onClick={onDone}>返回列表</button>
        </div>
      </div>
      {list.isLoading ? <p className="muted-note">正在读取当前用户可调用 API…</p> : null}
      {list.error ? <p className="inline-error">{errorMessage(list.error)}</p> : null}
      {sources.error ? <p className="inline-error">{errorMessage(sources.error)}</p> : null}
      {list.data && !list.data.length ? <p className="muted-note">当前账号没有有效的 API 调用授权。</p> : null}
      <ul>
        {items.map((item) => {
          const id = idOf(item);
          const added = addedIds.has(id);
          return (
            <li key={id} className={`source-row agw-row ${added ? 'is-added' : ''}`}>
              <label className="agw-check" aria-label={added ? `${item.apiName} 已添加` : `选择 ${item.apiName}`}>
                <input
                  type="checkbox"
                  checked={selected.has(id)}
                  disabled={added || !!busy}
                  onChange={(e) => {
                    const next = new Set(selected);
                    if (e.target.checked) next.add(id);
                    else next.delete(id);
                    setSelected(next);
                  }}
                />
              </label>
              <span className="source-row-main">
                <b>{item.apiName}{added ? <em className="source-badge">已添加</em> : null}</b>
                <small><code>{item.apiCode}</code> · {item.apiMethod} · {item.projectName}</small>
                <p>{item.grantTier === 'person' ? '到人授权' : '项目授权'} · {item.matchedBy}</p>
              </span>
              <span className="source-row-actions">
                <button type="button" className="subtle-button" disabled={added || !!busy} onClick={() => void addOne(item)}>
                  {busy === id ? '添加中…' : added ? '已添加' : '添加'}
                </button>
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
