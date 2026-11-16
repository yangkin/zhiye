import { useEffect, useMemo } from 'react';
import { Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { useMeta } from '../api/queries';
import { Icon } from '../lib/icons';
import { setPlatformToken } from '../lib/platformToken';

const ERROR_TEXT: Record<string, string> = {
  access_denied: '你取消了授权，或当前账号没有访问权限。',
  invalid_token: '登录凭证无效或已过期，请重新登录。',
  expired: '登录已过期，请重新登录。没发送的输入和没保存的代码修改都还在，登录后会回到刚才的页面。',
  sso_unreachable: '暂时连不上公司统一认证服务。统一认证只能在公司内网使用，请确认已连接内网或 VPN。',
  sso_disabled: '当前环境没有开启统一登录。',
};

/**
 * 登录页：使用公司统一认证（SSO）。
 * 只有服务端配置 zhiye.auth.mode=sso 时才会被路由守卫引导到这里；
 * 匿名模式下直接访问会提示「当前未开启登录」。
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

  return (
    <div className="login-page">
      <section className="login-brand">
        <img className="brand-logo" src="/brand.svg" alt="" />
        <h1>织页</h1>
        <p>让想法成形</p>
        <ul>
          <li>一句话描述，生成可交互的应用</li>
          <li>对话修改与直接编辑，随时回到任意版本</li>
          <li>导入已有代码，一键部署</li>
        </ul>
      </section>
      <section className="login-card" aria-label="登录">
        <h2>欢迎使用织页</h2>
        <p className="login-sub">{forSite ? '这个应用需要员工登录，登录后自动回到应用' : '使用公司统一账号登录'}</p>
        {error && (
          <p className="inline-error" role="alert">
            {ERROR_TEXT[error] ?? `登录没有成功（${error}），请稍后重试。`}
          </p>
        )}
        {meta.isLoading ? (
          <button className="login-sso-button" disabled>
            正在检查登录方式…
          </button>
        ) : auth?.mode === 'sso' ? (
          <a className="login-sso-button" href={loginHref} onClick={() => sessionStorage.setItem('zhiye-login-redirect', redirect)}>
            <Icon name="spark" /> 统一认证登录（SSO）
          </a>
        ) : (
          <>
            <button className="login-sso-button" disabled>
              统一认证登录（SSO）
            </button>
            <p className="login-note">当前环境未开启登录，可以直接使用全部功能。</p>
            <a className="login-link" href="/create">
              直接开始创造
            </a>
          </>
        )}
        <p className="login-note">统一认证仅在公司内网可用。登录后，你之前在本设备创建的应用会归入你的账号。</p>
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
        const code = body?.code === 'SSO_UNREACHABLE' ? 'sso_unreachable' : body?.code === 'AUTH_DISABLED' ? 'sso_disabled' : 'invalid_token';
        window.location.replace(`/login?error=${code}&redirect=${encodeURIComponent(redirect)}`);
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
