import { useEffect, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import type { LoginAllowlistItem } from '../api/types';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { confirmDialog } from './ConfirmDialog';
import { Modal } from './Modal';

interface Employee {
  loginName: string;
  realName: string;
}

/** 员工目录搜索由服务端代理（浏览器直连公司网关会被 CORS / 混合内容拦截），服务端按姓名 → 工号各试一次。 */
async function searchEmployees(keyword: string): Promise<Employee[]> {
  const q = keyword.trim();
  if (!q) return [];
  return api.adminEmployeeSearch(q);
}

/** 人员管理：平台登录白名单。支持按公司目录搜索添加、批量粘贴、指定管理员、启停与移除。 */
export function AdminLoginAllowlistPanel() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['adminLoginAllowlist'], queryFn: api.adminLoginAllowlist, retry: false });
  const [filter, setFilter] = useState('');
  const [addOpen, setAddOpen] = useState(false);
  const [editingNote, setEditingNote] = useState<{ loginName: string; value: string } | null>(null);
  const [savingNote, setSavingNote] = useState(false);
  const refresh = () => qc.invalidateQueries({ queryKey: ['adminLoginAllowlist'] });

  const saveNote = async (item: LoginAllowlistItem, raw: string) => {
    const next = raw.trim();
    if (next === (item.note ?? '').trim()) {
      setEditingNote(null);
      return;
    }
    setSavingNote(true);
    try {
      await api.adminLoginAllowlistUpdate(item.loginName, { note: next });
      await refresh();
      toast('已更新备注。');
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setSavingNote(false);
      setEditingNote(null);
    }
  };

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    const items = list.data ?? [];
    if (!q) return items;
    return items.filter((i) => `${i.loginName} ${i.realName} ${i.note}`.toLowerCase().includes(q));
  }, [list.data, filter]);

  const toggleEnabled = async (item: LoginAllowlistItem) => {
    try {
      await api.adminLoginAllowlistUpdate(item.loginName, { enabled: !item.enabled });
      await refresh();
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const toggleRole = async (item: LoginAllowlistItem) => {
    const next = item.role === 'admin' ? 'user' : 'admin';
    if (next === 'user') {
      if (!(await confirmDialog({
        title: `把「${item.loginName}」降为普通用户？`,
        message: '降权后该账号将不能进入平台管理。管理员白名单（ZHIYE_ADMIN_ALLOW_LOGIN_NAMES）里的账号不受此影响。',
        confirmLabel: '降为普通用户',
        danger: true,
      }))) return;
    }
    try {
      await api.adminLoginAllowlistUpdate(item.loginName, { role: next });
      await refresh();
      toast(next === 'admin' ? `已把 ${item.loginName} 设为管理员。` : `已把 ${item.loginName} 降为普通用户。`);
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  const remove = async (item: LoginAllowlistItem) => {
    if (!(await confirmDialog({
      title: `把「${item.loginName}」移出白名单？`,
      message: '移出后该账号不能再登录平台；已经建立的会话到期后失效。',
      confirmLabel: '移出',
      danger: true,
    }))) return;
    try {
      await api.adminLoginAllowlistDelete(item.loginName);
      await refresh();
      toast('已移出白名单。');
    } catch (e) {
      toast(errorMessage(e), 'error');
    }
  };

  return (
    <section className="dash-panel access-panel" aria-label="人员管理">
      <div className="dash-panel-head">
        <div>
          <h3>人员管理（登录白名单）</h3>
          <p className="muted-note">开启后只有名单内的人员和平台管理员能登录织页。管理员账号（admin 白名单 / 管理员角色）始终可登录，不受此名单影响。</p>
        </div>
        <button type="button" className="primary-button" onClick={() => setAddOpen(true)}>
          <Icon name="plus" /> 添加人员
        </button>
      </div>

      <div className="access-toolbar">
        <input
          className="field access-filter-input"
          placeholder="按工号、姓名或备注过滤名单"
          aria-label="过滤名单"
          spellCheck={false}
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <span className="quiet-label">{list.data ? `共 ${list.data.length} 人${shown.length !== list.data.length ? ` · 筛出 ${shown.length}` : ''}` : ''}</span>
      </div>

      {list.isLoading ? <p className="muted-note">正在读取…</p> : null}
      {list.error ? <p className="inline-error">{errorMessage(list.error)}</p> : null}
      {list.data && !list.data.length ? <p className="muted-note">白名单还是空的。开启登录白名单后，只有添加进来的人员和管理员能登录。</p> : null}
      {list.data && list.data.length > 0 && !shown.length ? <p className="muted-note">没有匹配「{filter}」的人员。</p> : null}

      {shown.length > 0 ? (
        <div className="dash-table-wrap">
          <table className="dash-table">
            <thead>
              <tr>
                <th scope="col">工号 / 登录名</th>
                <th scope="col">姓名</th>
                <th scope="col">备注</th>
                <th scope="col">角色</th>
                <th scope="col">状态</th>
                <th scope="col">添加人</th>
                <th scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((item) => (
                <tr key={item.loginName}>
                  <td><code>{item.loginName}</code></td>
                  <td>{item.realName || '—'}</td>
                  <td>
                    {editingNote?.loginName === item.loginName ? (
                      <input
                        className="access-note-inline"
                        autoFocus
                        maxLength={120}
                        disabled={savingNote}
                        placeholder="输入备注，回车保存"
                        value={editingNote.value}
                        onChange={(e) => setEditingNote({ loginName: item.loginName, value: e.target.value })}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') {
                            e.preventDefault();
                            void saveNote(item, editingNote.value);
                          } else if (e.key === 'Escape') {
                            e.preventDefault();
                            setEditingNote(null);
                          }
                        }}
                        onBlur={() => void saveNote(item, editingNote.value)}
                      />
                    ) : (
                      <button
                        type="button"
                        className={`access-note-btn ${item.note ? '' : 'is-empty'}`}
                        title="点击编辑备注"
                        onClick={() => setEditingNote({ loginName: item.loginName, value: item.note ?? '' })}
                      >
                        <span>{item.note || '添加备注'}</span>
                        <Icon name="edit" />
                      </button>
                    )}
                  </td>
                  <td>
                    <button
                      type="button"
                      className={`subtle-button access-role ${item.role === 'admin' ? 'is-admin' : ''}`}
                      title={item.role === 'admin' ? '点击降为普通用户' : '点击设为管理员（登录后立即生效）'}
                      onClick={() => void toggleRole(item)}
                    >
                      {item.role === 'admin' ? '管理员' : '普通用户'}
                    </button>
                  </td>
                  <td>
                    <button type="button" className="subtle-button" title={item.enabled ? '点击停用（停用后不能登录）' : '点击启用'} onClick={() => void toggleEnabled(item)}>
                      {item.enabled ? '可登录' : '已停用'}
                    </button>
                  </td>
                  <td>{item.createdBy || '—'}</td>
                  <td>
                    <button type="button" className="icon-button" aria-label={`移出 ${item.loginName}`} onClick={() => void remove(item)}>
                      <Icon name="trash" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {addOpen ? (
        <AddPeopleModal
          existing={new Set((list.data ?? []).map((i) => i.loginName))}
          onClose={() => setAddOpen(false)}
          onAdded={refresh}
        />
      ) : null}
    </section>
  );
}

/** 添加人员弹窗：搜索公司目录多选添加，或批量粘贴工号；可为这批人指定角色。 */
function AddPeopleModal({ existing, onClose, onAdded }: {
  existing: Set<string>;
  onClose: () => void;
  onAdded: () => void;
}) {
  const [keyword, setKeyword] = useState('');
  const [results, setResults] = useState<Employee[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [role, setRole] = useState<'user' | 'admin'>('user');
  const [note, setNote] = useState('');
  const [pasteMode, setPasteMode] = useState(false);
  const [pasteText, setPasteText] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const q = keyword.trim();
    if (!q || pasteMode) {
      setResults([]);
      setSearchError('');
      return;
    }
    setSearching(true);
    const timer = setTimeout(async () => {
      try {
        const list = await searchEmployees(q);
        setResults(list.filter((e) => !existing.has(e.loginName)));
        setSearchError(list.length ? '' : '公司目录里没找到，可以直接按这个工号添加。');
      } catch (e) {
        setResults([]);
        setSearchError(errorMessage(e) + '；也可以直接按工号添加。');
      } finally {
        setSearching(false);
      }
    }, 350);
    return () => clearTimeout(timer);
  }, [keyword, pasteMode, existing]);

  const submit = async () => {
    const names = pasteMode
      ? [...new Set(pasteText.split(/[\s,，;；]+/).map((s) => s.trim()).filter(Boolean))].filter((n) => !existing.has(n))
      : [...selected];
    if (!names.length || busy) return;
    setBusy(true);
    try {
      const r = await api.adminLoginAllowlistAdd({ loginNames: names, role, ...(note.trim() ? { note: note.trim() } : {}) });
      await onAdded();
      if (r.failed.length) toast(`已添加 ${r.added} 人；${r.failed.length} 个工号格式无效：${r.failed.join('、')}`, 'error');
      else toast(`已添加 ${r.added} 人${role === 'admin' ? '（管理员）' : ''}。`);
      if (!r.failed.length || r.added > 0) onClose();
    } catch (e) {
      toast(errorMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const pasteCount = pasteMode ? new Set(pasteText.split(/[\s,，;；]+/).map((s) => s.trim()).filter(Boolean)).size : 0;

  return (
    <Modal
      title="添加人员"
      onClose={onClose}
      className="access-add-modal"
      footer={
        <>
          <span className="access-add-count">{pasteMode ? `待添加 ${pasteCount} 人` : `已选 ${selected.size} 人`}</span>
          <button type="button" className="subtle-button" onClick={onClose}>取消</button>
          <button type="button" className="primary-button" disabled={busy || (pasteMode ? pasteCount === 0 : selected.size === 0)} onClick={() => void submit()}>
            {busy ? '正在添加…' : '添加'}
          </button>
        </>
      }
    >
      <div className="access-add-tabs">
        <button type="button" className={!pasteMode ? 'active' : ''} onClick={() => setPasteMode(false)}>搜索公司目录</button>
        <button type="button" className={pasteMode ? 'active' : ''} onClick={() => setPasteMode(true)}>批量粘贴工号</button>
      </div>

      {!pasteMode ? (
        <>
          <input
            className="field access-search-input"
            autoFocus
            spellCheck={false}
            placeholder="输入姓名或工号搜索公司员工"
            aria-label="搜索公司员工"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          {searching ? <p className="muted-note">正在搜索…</p> : null}
          {searchError ? <p className="muted-note">{searchError}</p> : null}
          <ul className="access-results">
            {results.map((e) => (
              <li key={e.loginName}>
                <label className={`access-result ${selected.has(e.loginName) ? 'on' : ''}`}>
                  <input
                    type="checkbox"
                    checked={selected.has(e.loginName)}
                    onChange={(ev) => {
                      const next = new Set(selected);
                      if (ev.target.checked) next.add(e.loginName);
                      else next.delete(e.loginName);
                      setSelected(next);
                    }}
                  />
                  <span className="access-result-name">{e.realName || e.loginName}</span>
                  <code>{e.loginName}</code>
                </label>
              </li>
            ))}
          </ul>
          {keyword.trim() && !searching && !results.length ? (
            <button
              type="button"
              className="subtle-button access-raw-add"
              onClick={() => {
                const raw = keyword.trim();
                if (raw && !existing.has(raw)) setSelected(new Set([...selected, raw]));
                setKeyword('');
              }}
            >
              <Icon name="plus" /> 直接按工号「{keyword.trim()}」添加
            </button>
          ) : null}
          {selected.size > 0 ? (
            <p className="access-selected">将添加：{[...selected].join('、')}</p>
          ) : null}
        </>
      ) : (
        <textarea
          className="field access-paste"
          rows={8}
          placeholder="每行一个工号（支持逗号或空格分隔）"
          aria-label="批量粘贴工号"
          value={pasteText}
          onChange={(e) => setPasteText(e.target.value)}
        />
      )}

      <div className="access-meta-group">
        <label className="access-note-field">
          <span>
            备注
            <small>可选 · 将显示在人员管理列表中</small>
          </span>
          <input
            type="text"
            maxLength={120}
            placeholder="例如：T11 厂制造部 / 权限申请单号"
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </label>
        <div className="access-role-row">
          <span>分配角色</span>
          <div className="access-role-seg" role="radiogroup" aria-label="分配角色">
            <button type="button" role="radio" aria-checked={role === 'user'} className={role === 'user' ? 'active' : ''} onClick={() => setRole('user')}>普通用户</button>
            <button type="button" role="radio" aria-checked={role === 'admin'} className={role === 'admin' ? 'active' : ''} onClick={() => setRole('admin')}>管理员</button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
