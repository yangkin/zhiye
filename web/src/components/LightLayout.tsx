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
        <button className="profile rail-profile" onClick={onWorkspace} aria-label="工作空间与恢复链接" title={user ? user.realName : '我的工作空间 · 免登录'}>
          <span className="avatar">{user ? user.realName.slice(0, 1) : '织'}</span>
          <small>{user ? user.realName : '免登录'}</small>
        </button>
      </div>
    </aside>
  );
}

function WorkspaceModal({ onClose }: { onClose: () => void }) {
  const user = useMeta().data?.auth?.user ?? null;
  const { data, isLoading, error } = useQuery({ queryKey: qk.workspace, queryFn: api.workspace });
  return (
    <Modal title="我的工作空间" onClose={onClose}>
      {user ? (
        <>
          <p className="muted-note">
            已使用统一认证登录：{user.realName}（{user.loginName}）。你的应用保存在服务器上，换设备登录同一账号即可看到。
          </p>
          <button
            className="subtle-button"
            onClick={async () => {
              await api.logout().catch(() => undefined);
              window.location.replace('/login');
            }}
          >
            退出登录
          </button>
        </>
      ) : (
        <p className="muted-note">织页无需登录。工作空间保存在服务器上，并通过当前浏览器识别。</p>
      )}
      <label className="field-label">恢复链接</label>
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
            onClick={async () => toast((await copyText(data.recoveryUrl)) ? '恢复链接已复制。' : '请手动复制链接。')}
          >
            <Icon name="copy" />
          </button>
        </div>
      ) : null}
      <p className="muted-note">在其他浏览器打开这个链接，即可回到同一个工作空间。请妥善保管，不要公开分享。</p>
    </Modal>
  );
}
