import { Suspense, useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { qk, useApps, useMeta } from '../api/queries';
import { Icon } from '../lib/icons';
import { copyText } from '../lib/format';
import { toast, useUi } from '../lib/store';
import { preloadRoute } from '../lib/routes';
import { ErrorBoundary } from './ErrorBoundary';
import { Modal } from './Modal';
import { HelpPanel } from './HelpPanel';

/** 延迟 150ms 再显字，本地/快网切换时绝不闪出「正在加载」文字 */
export function RouteFallback() {
  const [show, setShow] = useState(false);
  useEffect(() => {
    const id = setTimeout(() => setShow(true), 150);
    return () => clearTimeout(id);
  }, []);
  return show ? (
    <div style={{ padding: '48px 24px', textAlign: 'center', color: 'var(--text-muted, #888)', fontSize: 13 }}>正在加载页面…</div>
  ) : null;
}

export function LightLayout() {
  const location = useLocation();
  const page = location.pathname.split('/')[1] || 'create';
  const [modal, setModal] = useState<null | 'workspace'>(null);
  const openHelp = useUi((s) => s.openHelp);

  useEffect(() => {
    document.querySelector('.main')?.scrollTo(0, 0);
  }, [location.pathname]);

  return (
    <div className="shell">
      <Sidebar page={page} onHelp={() => openHelp('docs')} onWorkspace={() => setModal('workspace')} />
      <main className={`main ${page === 'create' ? 'main-create' : page === 'chat' ? 'main-chat' : page === 'community' && location.pathname.split('/')[2] ? 'main-work' : ''}`}>
        <ErrorBoundary area="这个页面" page key={location.pathname}>
          <Suspense fallback={<RouteFallback />}>
            <Outlet />
          </Suspense>
        </ErrorBoundary>
      </main>
      <HelpPanel />
      {modal === 'workspace' ? <WorkspaceModal onClose={() => setModal(null)} /> : null}
    </div>
  );
}

/**
 * 左侧导航栏：登录后的所有页面都用它（工作台也保留，和 meoo 一样），
 * 自带「我的工作空间」弹窗，谁用谁渲染即可。
 */
export function AppRail({ page = '' }: { page?: string }) {
  const [ws, setWs] = useState(false);
  const openHelp = useUi((s) => s.openHelp);
  return (
    <>
      <Sidebar page={page} onHelp={() => openHelp('docs')} onWorkspace={() => setWs(true)} />
      {ws ? <WorkspaceModal onClose={() => setWs(false)} /> : null}
    </>
  );
}

function Sidebar({ page, onHelp, onWorkspace }: { page: string; onHelp: () => void; onWorkspace: () => void }) {
  const meta = useMeta().data;
  const user = meta?.auth?.user ?? null;
  const isAdmin = Boolean(meta?.auth?.admin);
  const { data: apps } = useApps({ sort: 'updated' });
  const creatorItems = [
    ['create', 'plus', '创造'],
    ['community', 'community', '社区'],
    ['apps', 'grid', '应用'],
    ['history', 'history', '历史'],
  ] as const;
  const adminItems = [
    ['dashboard', 'chart', '看板'],
    ['admin', 'settings', '管理'],
    ['logs', 'terminal', '日志'],
  ] as const;
  return (
    <aside className="sidebar rail">
      <Link className="rail-brand" to="/create" aria-label="织页 · 让想法成形" title="织页 · 让想法成形">
        <img className="brand-logo" src="/brand.svg" alt="" />
        <b>织页</b>
      </Link>
      <nav className="main-nav rail-nav" aria-label="主导航">
        {creatorItems.map(([p, i, t]) => (
          <NavLink
            key={p}
            to={'/' + p}
            className={page === p ? 'active' : ''}
            title={t}
            onMouseEnter={() => preloadRoute(p)}
            onFocus={() => preloadRoute(p)}
          >
            <span className="rail-icon">
              <Icon name={i} />
              {p === 'apps' && apps?.length ? <small>{apps.length > 99 ? '99+' : apps.length}</small> : null}
            </span>
            <span>{t}</span>
          </NavLink>
        ))}
        {isAdmin ? (
          <>
            <div className="rail-divider" aria-hidden="true" />
            {adminItems.map(([p, i, t]) => (
              <NavLink
                key={p}
                to={'/' + p}
                className={page === p ? 'active' : ''}
                title={p === 'admin' ? '平台管理' : p === 'dashboard' ? '数据看板' : p === 'logs' ? '运行日志' : t}
                onMouseEnter={() => preloadRoute(p)}
                onFocus={() => preloadRoute(p)}
              >
                <span className="rail-icon">
                  <Icon name={i} />
                </span>
                <span>{t}</span>
              </NavLink>
            ))}
          </>
        ) : null}
      </nav>
      <div className="sidebar-bottom rail-bottom">
        <button className="rail-item" onClick={onHelp} aria-label="帮助与反馈" title="小织 · 使用帮助">
          <Icon name="help" />
          <span>帮助</span>
        </button>
        <button className="profile rail-profile" onClick={onWorkspace} aria-label="我的工作空间" title={user ? user.realName : '我的工作空间 · 免登录'}>
          <span className="avatar">{user ? user.realName.slice(0, 1) : '织'}</span>
          <small>{user ? user.realName : '免登录'}</small>
        </button>
      </div>
    </aside>
  );
}

const MEMORY_CATS: { value: 'profile' | 'preference' | 'stack' | 'domain' | 'rule'; label: string }[] = [
  { value: 'profile', label: '岗位与厂区背景' },
  { value: 'preference', label: '设计与交互偏好' },
  { value: 'stack', label: '技术栈偏好' },
  { value: 'domain', label: '业务领域与术语' },
  { value: 'rule', label: '常用规范与要求' },
];

function WorkspaceModal({ onClose }: { onClose: () => void }) {
  const user = useMeta().data?.auth?.user ?? null;
  const { data, isLoading, error } = useQuery({ queryKey: qk.workspace, queryFn: api.workspace });
  const {
    data: memData,
    isLoading: memLoading,
    refetch: refetchMem,
  } = useQuery({ queryKey: ['workspace-memories'], queryFn: api.workspaceMemories });
  const [memCat, setMemCat] = useState<'profile' | 'preference' | 'stack' | 'domain' | 'rule'>('profile');
  const [memInput, setMemInput] = useState('');
  const [memBusy, setMemBusy] = useState(false);

  const items = memData?.items ?? [];
  const maxItems = memData?.maxItems ?? 30;
  const autoLearnEnabled = memData?.autoLearnEnabled ?? true;

  const toggleAutoLearn = async () => {
    setMemBusy(true);
    try {
      const next = !autoLearnEnabled;
      await api.workspaceMemorySetAutoLearn(next);
      await refetchMem();
      toast(next ? '已开启自动记忆：后续聊天将自动提炼长期偏好与背景。' : '已关闭自动记忆：后续聊天不再自动写入，仅保留手动录入。');
    } catch (e) {
      toast((e as Error).message || '更新设置失败', 'error');
    } finally {
      setMemBusy(false);
    }
  };

  const addMemory = async () => {
    const content = memInput.trim();
    if (!content) return;
    setMemBusy(true);
    try {
      await api.workspaceMemoryCreate({ category: memCat, content });
      setMemInput('');
      await refetchMem();
      toast('已保存到个人记忆，后续创造与问答将自动生效。');
    } catch (e) {
      toast((e as Error).message || '保存失败', 'error');
    } finally {
      setMemBusy(false);
    }
  };

  return (
    <Modal title="我的工作空间" onClose={onClose} wide className="workspace-modal">
      {user ? (
        <div className="workspace-account-row">
          <p className="muted-note">
            已使用统一认证登录：{user.realName}（{user.loginName}）。你的应用与个人记忆保存在服务器上，换设备登录同一账号即可看到。
          </p>
          <button
            className="workspace-logout"
            onClick={async () => {
              await api.logout().catch(() => undefined);
              window.location.replace('/login');
            }}
          >
            退出登录
          </button>
        </div>
      ) : (
        <p className="muted-note">织页无需登录。工作空间与应用记忆保存在服务器上，并通过当前浏览器识别。</p>
      )}
      {/* 恢复链接只对免登录工作空间有意义；统一认证下身份跟着账号走，换浏览器登录即恢复 */}
      {!user ? (
        <details className="workspace-recovery">
          <summary>
            恢复链接
            <span>换浏览器或清理站点数据后，用它找回工作空间</span>
          </summary>
          {isLoading ? (
            <p className="muted-note">正在读取…</p>
          ) : error ? (
            <p className="inline-error">{(error as Error).message}</p>
          ) : data ? (
            <div className="copy-field">
              <span>{data.recoveryUrl}</span>
              <button
                className="icon-button"
                aria-label="复制恢复链接"
                onClick={async () => toast((await copyText(data.recoveryUrl ?? '')) ? '恢复链接已复制。' : '请手动复制链接。')}
              >
                <Icon name="copy" />
              </button>
            </div>
          ) : null}
          <p className="muted-note">在其他浏览器打开这个链接，即可回到同一个工作空间（含个人记忆）。请妥善保管，不要公开分享。</p>
        </details>
      ) : null}

      <div style={{ marginTop: 18, paddingTop: 16, borderTop: '1px solid #ece7f5' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
          <label className="field-label" style={{ margin: 0 }}>
            个人工作空间记忆（{items.length} / {maxItems}）
          </label>
          {items.length > 0 ? (
            <button
              type="button"
              className="subtle-button"
              style={{ padding: '4px 10px', fontSize: 12 }}
              disabled={memBusy}
              onClick={async () => {
                setMemBusy(true);
                try {
                  await api.workspaceMemoryClearAll();
                  await refetchMem();
                  toast('已清空全部个人记忆。');
                } finally {
                  setMemBusy(false);
                }
              }}
            >
              清空全部
            </button>
          ) : null}
        </div>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
            padding: '10px 13px',
            marginBottom: 12,
            borderRadius: 11,
            border: autoLearnEnabled ? '1px solid #dfd3fa' : '1px solid #ece7f5',
            background: autoLearnEnabled ? '#faf7ff' : '#f9f8fc',
          }}
        >
          <div style={{ minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, fontWeight: 600, color: '#241c36' }}>
              <span>自动从聊天中学习并记住</span>
              <span
                style={{
                  fontSize: 11,
                  fontWeight: 600,
                  padding: '1px 7px',
                  borderRadius: 999,
                  background: autoLearnEnabled ? '#ede5ff' : '#ece9f3',
                  color: autoLearnEnabled ? '#6d28d9' : '#7b7390',
                }}
              >
                {autoLearnEnabled ? '已开启' : '已关闭'}
              </span>
            </div>
            <p className="muted-note" style={{ margin: '3px 0 0', fontSize: 12 }}>
              开启后，日常创造对话、工作台修改或问小织时提到的岗位背景、偏好风格与常用规范会自动提炼并保存（绝不记录密码或一次性任务）。
            </p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={autoLearnEnabled}
            aria-label="自动从聊天中学习并记住"
            disabled={memBusy}
            onClick={() => void toggleAutoLearn()}
            style={{
              width: 42,
              height: 24,
              borderRadius: 999,
              border: 'none',
              padding: 2,
              flexShrink: 0,
              cursor: memBusy ? 'not-allowed' : 'pointer',
              background: autoLearnEnabled ? '#7953dc' : '#d6d0e4',
              display: 'flex',
              alignItems: 'center',
              justifyContent: autoLearnEnabled ? 'flex-end' : 'flex-start',
              transition: 'background 0.16s ease',
            }}
          >
            <span
              style={{
                width: 20,
                height: 20,
                borderRadius: '50%',
                background: '#ffffff',
                boxShadow: '0 1px 3px rgba(0,0,0,0.18)',
              }}
            />
          </button>
        </div>

        <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
          <select
            aria-label="记忆分类"
            value={memCat}
            onChange={(e) => setMemCat(e.target.value as typeof memCat)}
            style={{
              height: 36,
              borderRadius: 10,
              border: '1px solid #dfd8ee',
              padding: '0 10px',
              background: '#fff',
              fontSize: 13,
              color: '#2e2540',
            }}
          >
            {MEMORY_CATS.map((c) => (
              <option key={c.value} value={c.value}>
                {c.label}
              </option>
            ))}
          </select>
          <input
            type="text"
            value={memInput}
            maxLength={240}
            onChange={(e) => setMemInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                void addMemory();
              }
            }}
            placeholder="例如：我在 T11 厂做 Array 良率，偏好浅色工业风 + Excel 导出"
            style={{
              flex: 1,
              height: 36,
              borderRadius: 10,
              border: '1px solid #dfd8ee',
              padding: '0 12px',
              fontSize: 13,
            }}
          />
          <button
            type="button"
            className="primary-button"
            style={{ height: 36, padding: '0 14px', whiteSpace: 'nowrap' }}
            disabled={memBusy || !memInput.trim() || items.length >= maxItems}
            onClick={() => void addMemory()}
          >
            记住
          </button>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: -6, marginBottom: 10, fontSize: 11.5, color: memInput.length >= 240 ? '#b91c1c' : '#857d99' }}>
          <span>{memInput.length >= 240 ? '已达单条 240 字上限，超长内容会自动截断。' : '建议简洁录入固定偏好或厂区/业务背景，回车即可保存。'}</span>
          <span style={{ fontVariantNumeric: 'tabular-nums' }}>{memInput.length} / 240</span>
        </div>

        {memLoading ? (
          <p className="muted-note">正在加载个人记忆…</p>
        ) : items.length === 0 ? (
          <p className="muted-note" style={{ background: '#faf8fe', padding: '10px 12px', borderRadius: 10 }}>
            {autoLearnEnabled
              ? '还没有个人记忆。你可以在上方手动录入，或在正常聊天中提到你的厂区、偏好风格与常用技术栈，系统会自动提炼并展示在这里。'
              : '还没有个人记忆（自动从聊天学习已关闭）。你可以在上方手动录入，或开启上方开关让系统从聊天中自动沉淀。'}
          </p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 220, overflowY: 'auto' }}>
            {items.map((m) => (
              <div
                key={m.id}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  padding: '8px 10px',
                  borderRadius: 10,
                  border: '1px solid #ece7f5',
                  background: m.enabled ? '#fff' : '#f8f7fb',
                  opacity: m.enabled ? 1 : 0.65,
                }}
              >
                <span
                  style={{
                    fontSize: 11,
                    padding: '2px 7px',
                    borderRadius: 999,
                    background: '#f2ecff',
                    color: '#5f43b2',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {m.categoryLabel}
                </span>
                <span style={{ flex: 1, fontSize: 13, color: '#2b233b', wordBreak: 'break-word' }}>{m.content}</span>
                <span
                  style={{
                    fontSize: 11,
                    padding: '1px 7px',
                    borderRadius: 999,
                    background: m.source === 'auto' ? '#f0fdfa' : '#f4f2f8',
                    color: m.source === 'auto' ? '#0f766e' : '#7b7390',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {m.source === 'auto' ? '聊天自动记住' : '手动录入'}
                </span>
                <button
                  type="button"
                  className="subtle-button"
                  style={{ padding: '2px 8px', fontSize: 11.5 }}
                  disabled={memBusy}
                  onClick={async () => {
                    await api.workspaceMemoryUpdate(m.id, { enabled: !m.enabled });
                    await refetchMem();
                  }}
                >
                  {m.enabled ? '已启用' : '已停用'}
                </button>
                <button
                  type="button"
                  className="icon-button"
                  aria-label="删除记忆"
                  disabled={memBusy}
                  onClick={async () => {
                    await api.workspaceMemoryDelete(m.id);
                    await refetchMem();
                    toast('已删除记忆。');
                  }}
                >
                  <Icon name="close" />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </Modal>
  );
}
