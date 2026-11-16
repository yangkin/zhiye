import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { qk, useApps, useMeta } from '../api/queries';
import { Icon } from '../lib/icons';
import { copyText } from '../lib/format';
import { toast, useUi } from '../lib/store';
import { Modal } from './Modal';
import { HelpPanel } from './HelpPanel';

const titles: Record<string, string> = { create: '我的工作空间', apps: '我的应用', history: '版本与记录', community: '灵感社区' };

export function LightLayout() {
  const location = useLocation();
  const page = location.pathname.split('/')[1] || 'create';
  const mobileNav = useUi((s) => s.mobileNav);
  const setMobileNav = useUi((s) => s.setMobileNav);
  const [modal, setModal] = useState<null | 'workspace'>(null);
  const setHelpOpen = useUi((s) => s.setHelpOpen);

  useEffect(() => {
    setMobileNav(false);
    document.querySelector('.main')?.scrollTo(0, 0);
  }, [location.pathname, setMobileNav]);

  return (
    <div className="shell">
      {mobileNav ? <button className="nav-backdrop" aria-label="关闭导航" onClick={() => setMobileNav(false)} /> : null}
      <Sidebar page={page} onHelp={() => setHelpOpen(true)} onWorkspace={() => setModal('workspace')} />
      <main className={`main ${page === 'create' ? 'main-create' : page === 'chat' ? 'main-chat' : page === 'community' && location.pathname.split('/')[2] ? 'main-work' : ''}`}>
        <header className="topbar">
          <div className="topbar-left">
            <button className="icon-button mobile-menu" aria-label="打开导航" onClick={() => setMobileNav(!mobileNav)}>
              <Icon name="menu" />
            </button>
            <span className="workspace-dot" />
            <span>{titles[page] ?? '我的工作空间'}</span>
          </div>
          <div className="topbar-right" />
        </header>
        <Outlet />
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
  const setHelpOpen = useUi((s) => s.setHelpOpen);
  return (
    <>
      <Sidebar page={page} onHelp={() => setHelpOpen(true)} onWorkspace={() => setWs(true)} />
      {ws ? <WorkspaceModal onClose={() => setWs(false)} /> : null}
    </>
  );
}

function Sidebar({ page, onHelp, onWorkspace }: { page: string; onHelp: () => void; onWorkspace: () => void }) {
  const user = useMeta().data?.auth?.user ?? null;
  const mobileNav = useUi((s) => s.mobileNav);
  const { data: apps } = useApps({ sort: 'updated' });
  return (
    <aside className={`sidebar rail ${mobileNav ? 'visible' : ''}`}>
      <Link className="rail-brand" to="/create" aria-label="织页 · 让想法成形" title="织页 · 让想法成形">
        <img className="brand-logo" src="/brand.svg" alt="" />
        <b>织页</b>
      </Link>
      <nav className="main-nav rail-nav" aria-label="主导航">
        {(
          [
            ['create', 'plus', '创造'],
            ['community', 'community', '社区'],
            ['apps', 'grid', '应用'],
            ['history', 'history', '历史'],
          ] as const
        ).map(([p, i, t]) => (
          <NavLink key={p} to={'/' + p} className={page === p ? 'active' : ''} title={t}>
            <span className="rail-icon">
              <Icon name={i} />
              {p === 'apps' && apps?.length ? <small>{apps.length > 99 ? '99+' : apps.length}</small> : null}
            </span>
            <span>{t}</span>
          </NavLink>
        ))}
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
