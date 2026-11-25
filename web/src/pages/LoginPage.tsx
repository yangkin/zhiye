import { useEffect, useMemo } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { useMeta } from '../api/queries';
import { Icon } from '../lib/icons';
import { setPlatformToken } from '../lib/platformToken';

const ERROR_TEXT: Record<string, string> = {
  access_denied: '你取消了授权，或当前账号没有访问权限。',
  invalid_token: '登录凭证无效或已过期，请重新登录。',
  sso_unreachable: '暂时连不上公司统一认证服务。统一认证只能在公司内网使用，请确认已连接内网或 VPN。',
  sso_disabled: '当前环境没有开启统一登录。',
};

/**
 * 登录页：使用公司统一认证（SSO）。
 * 只有服务端配置 zhiye.auth.mode=sso 时才会被路由守卫引导到这里；
 * 匿名模式下直接访问会展示免登录入口。
 */
export function LoginPage() {
  const meta = useMeta();
  const [params] = useSearchParams();
  const redirect = safeRedirect(params.get('redirect'));
  const error = params.get('error');
  const auth = meta.data?.auth;

  const loginHref = useMemo(() => `/api/auth/sso/login?redirect=${encodeURIComponent(redirect)}`, [redirect]);

  const forSite = params.get('site') === '1' || redirect.startsWith('/api/auth/site/');
  useEffect(() => {
    // 发布站点的登录入口在服务端（/api/auth/site/start），要整页跳转
    if (auth?.mode === 'sso' && auth.user && redirect.startsWith('/api/')) window.location.replace(redirect);
  }, [auth, redirect]);
  if (auth?.mode === 'sso' && auth.user) return redirect.startsWith('/api/') ? null : <Navigate to={redirect} replace />;

  const ssoEnabled = auth?.mode === 'sso';

  return (
    <div className="login-page">
      <section className="login-art-wrap" aria-label="织页产品概览">
        <div className="login-art-frame">
          <img className="login-art-img" src="/login-glass.jpg" alt="" aria-hidden="true" />
          <div className="login-art-veil" aria-hidden="true" />

          <header className="login-art-top">
            <div className="login-brand-pill">
              <img src="/brand.svg" alt="织页 AI" />
              <span>织页 AI</span>
            </div>
            <span className="login-art-tag">TCL CSOT</span>
          </header>

          <div className="login-art-bottom">
            <h1>
              说清想法，
              <br />
              织出你的专属应用。
            </h1>
            <p>不用写代码，把日常报表、看板和小工具交给织页，几分钟就能亲手做出顺手好用的应用。</p>
          </div>
        </div>
      </section>

      <section className="login-panel" aria-label="账号登录">
        <div className="login-panel-top">
          <span>{ssoEnabled ? '华星统一身份认证' : '本地开发环境 · 免登录'}</span>
        </div>

        <div className="login-form-wrap">
          <img className="login-auth-logo" src="/brand.svg" alt="织页 AI" />

          <div className="login-head">
            <h2>{forSite ? '验证员工身份' : '登录织页 AI'}</h2>
            <p className="login-sub">
              {forSite
                ? '该发布应用需要员工登录，完成验证后将自动返回应用页面。'
                : ssoEnabled
                  ? '使用华星统一账号登录，管理并继续创作你的个人应用，无需单独注册密码。'
                  : '当前为本地免登录模式，可直接进入工作空间开始创作。'}
            </p>
          </div>

          {error === 'platform_access_denied' ? (
            <div className="login-denied" role="alert">
              <h3>暂时还没有平台使用权限</h3>
              <p>{params.get('msg') ?? '你的公司账号已通过统一认证，但还没有开通织页使用权限。按下面步骤开通后就能登录：'}</p>
              <ol className="login-denied-steps">
                <li>
                  <b>公司统一账号认证</b>
                  <span>已完成，无需单独注册密码</span>
                </li>
                <li>
                  <b>联系平台管理员开通权限</b>
                  <span>把你的工号报给管理员，由他在「管理 → 人员管理」中添加并启用</span>
                </li>
                <li>
                  <b>重新登录</b>
                  <span>开通后回到本页，点击「使用华星统一账号登录」即可进入</span>
                </li>
              </ol>
            </div>
          ) : error && error !== 'expired' ? (
            <div className="login-alert" role="alert">
              <Icon name="alert" />
              <span>{ERROR_TEXT[error] ?? `登录没有成功（${error}），请稍后重试。`}</span>
            </div>
          ) : null}

          <div className="login-actions">
            {meta.isLoading ? (
              <button className="login-primary-btn" disabled>
                <Icon name="refresh" className="spinning" />
                <span>正在检查登录方式…</span>
              </button>
            ) : ssoEnabled ? (
              <a className="login-primary-btn" href={loginHref} onClick={() => sessionStorage.setItem('zhiye-login-redirect', redirect)}>
                <span>使用华星统一账号登录</span>
                <svg className="login-btn-arrow-svg" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path d="M3.5 8H12.5M12.5 8L8.5 4M12.5 8L8.5 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </a>
            ) : (
              <>
                <a className="login-primary-btn" href="/create">
                  <span>进入织页工作台</span>
                  <svg className="login-btn-arrow-svg" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path d="M3.5 8H12.5M12.5 8L8.5 4M12.5 8L8.5 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                </a>
                <button className="login-secondary-btn" disabled>
                  <Icon name="lock" />
                  <span>统一认证登录（当前环境未开启）</span>
                </button>
              </>
            )}
          </div>

          <div className="login-meta-note">
            <svg viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M8 1.75L3 4v3.75c0 3.1 2.13 5.83 5 6.5 2.87-.67 5-3.4 5-6.5V4L8 1.75Z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
            </svg>
            <span>内网安全会话保护 · 已创建应用自动归属个人账号</span>
          </div>
        </div>

        <footer className="login-panel-foot">
          <span>织页 AI · 智能应用创作平台</span>
          <span>TCL 华星光电</span>
        </footer>
      </section>
    </div>
  );
}

/** 统一认证回调：/access_token?access_token=...（或 ?error=...） */
export function SsoCallbackPage() {
  const location = useLocation();

  useEffect(() => {
    const params = new URLSearchParams(location.search);
    const token = params.get('access_token');
    const error = params.get('error');
    const redirect = safeRedirect(sessionStorage.getItem('zhiye-login-redirect'));
    sessionStorage.removeItem('zhiye-login-redirect');
    // 立即从地址栏移除令牌
    window.history.replaceState(null, '', '/access_token');
    if (error || !token) {
      window.location.replace(`/login?error=${encodeURIComponent(error || 'invalid_token')}&redirect=${encodeURIComponent(redirect)}`);
      return;
    }
    fetch('/api/auth/sso/callback', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ accessToken: token, expiresIn: Number(params.get('expires_in')) || null }),
    })
      .then(async (res) => {
        if (res.ok) {
          setPlatformToken(token);
          window.location.replace(redirect);
          return;
        }
        const body = await res.json().catch(() => null);
        const code = body?.code === 'SSO_UNREACHABLE' ? 'sso_unreachable'
          : body?.code === 'AUTH_DISABLED' ? 'sso_disabled'
            : body?.code === 'PLATFORM_ACCESS_DENIED' ? 'platform_access_denied' : 'invalid_token';
        const msg = code === 'platform_access_denied' && body?.message ? `&msg=${encodeURIComponent(body.message)}` : '';
        window.location.replace(`/login?error=${code}${msg}&redirect=${encodeURIComponent(redirect)}`);
      })
      .catch(() => window.location.replace(`/login?error=sso_unreachable&redirect=${encodeURIComponent(redirect)}`));
  }, [location.search]);

  return (
    <div className="recover-page">
      <img className="brand-logo" src="/brand.svg" alt="" />
      <h1>正在登录…</h1>
      <p>正在验证你的公司账号，请稍候。</p>
    </div>
  );
}

/** 路由守卫：只在 sso 模式且未登录时把用户带到登录页 */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const meta = useMeta();
  const location = useLocation();
  const auth = meta.data?.auth;
  const open = location.pathname === '/login' || location.pathname === '/access_token';
  if (!open && auth?.mode === 'sso' && !auth.user) {
    const redirect = location.pathname + location.search;
    sessionStorage.setItem('zhiye-login-redirect', redirect);
    return <Navigate to={`/login?redirect=${encodeURIComponent(redirect)}`} replace />;
  }
  return <>{children}</>;
}

/** 只允许站内相对路径，防止开放重定向 */
export function safeRedirect(value: string | null | undefined) {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/login') || value.startsWith('/access_token')) return '/create';
  return value;
}
