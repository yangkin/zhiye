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
      <section className="login-showcase" aria-label="织页产品概览">
        <div className="login-showcase-inner">
          <header className="login-topbar">
            <img className="login-logo" src="/brand.svg" alt="织页" />
            <span className="login-wordmark">织页</span>
            <span className="login-sep" aria-hidden="true" />
            <span className="login-org">TCL 华星 · 内部应用工作台</span>
          </header>

          <div className="login-hero">
            <h1>
              把业务想法，
              <br />
              直接织成<span className="login-accent">可用系统</span>。
            </h1>
            <p>
              面向产线、研发与职能团队的企业级 AI 应用搭建平台——从对话规划、沙箱构建到云端数据与一键发布，全流程闭环交付。
            </p>
          </div>

          <div className="login-stage" aria-hidden="true">
            <div className="login-stage-chrome">
              <span className="login-stage-dots">
                <i />
                <i />
                <i />
              </span>
              <span className="login-stage-url">zhiye.csot.internal / 华星良率攻坚驾驶舱</span>
              <span className="login-stage-badge">V3 · 已发布</span>
            </div>
            <div className="login-stage-body">
              <div className="login-stage-chat">
                <div className="login-stage-prompt">
                  <small>业务需求</small>
                  <p>做一份模组厂良率损失分析看板，支持工段下钻与不良 Top5 柏拉图</p>
                </div>
                <ul className="login-stage-steps">
                  <li>
                    <span className="step-ok">
                      <Icon name="check" />
                    </span>
                    <div>
                      <b>规划与数据建模</b>
                      <small>Array / Cell / Module 口径确认</small>
                    </div>
                  </li>
                  <li>
                    <span className="step-ok">
                      <Icon name="check" />
                    </span>
                    <div>
                      <b>看板与联动交互生成</b>
                      <small>KPI 卡片、柏拉图与下钻表格</small>
                    </div>
                  </li>
                  <li>
                    <span className="step-ok">
                      <Icon name="check" />
                    </span>
                    <div>
                      <b>沙箱构建与冒烟通过</b>
                      <small>类型检查 0 错 · 已发布内网访问</small>
                    </div>
                  </li>
                </ul>
              </div>

              <div className="login-stage-preview">
                <div className="login-mock-kpis">
                  <div className="login-mock-kpi">
                    <span>综合良率</span>
                    <strong>
                      96.4<em>%</em>
                    </strong>
                    <i className="is-up">+0.8%</i>
                  </div>
                  <div className="login-mock-kpi">
                    <span>当日产出</span>
                    <strong>14,820</strong>
                    <i>较昨日 +4.2%</i>
                  </div>
                  <div className="login-mock-kpi">
                    <span>关注工段</span>
                    <strong className="is-warn">Cell-03</strong>
                    <i>线缺陷偏高</i>
                  </div>
                </div>

                <div className="login-mock-chart">
                  <div className="login-mock-chart-head">
                    <span>近 7 日各工段良率趋势与目标线</span>
                    <small>目标 ≥ 95.5%</small>
                  </div>
                  <svg viewBox="0 0 320 96" preserveAspectRatio="none" role="presentation">
                    <line x1="0" y1="24" x2="320" y2="24" stroke="rgba(167,139,250,0.35)" strokeDasharray="4 4" strokeWidth="1" />
                    <line x1="0" y1="54" x2="320" y2="54" stroke="rgba(255,255,255,0.06)" strokeWidth="1" />
                    <line x1="0" y1="84" x2="320" y2="84" stroke="rgba(255,255,255,0.08)" strokeWidth="1" />
                    {[
                      { x: 18, h: 46 },
                      { x: 62, h: 54 },
                      { x: 106, h: 49 },
                      { x: 150, h: 62 },
                      { x: 194, h: 58 },
                      { x: 238, h: 68 },
                      { x: 282, h: 74 },
                    ].map((b, idx) => (
                      <rect
                        key={idx}
                        x={b.x}
                        y={88 - b.h}
                        width="20"
                        height={b.h}
                        rx="4"
                        fill={idx === 6 ? 'rgba(139, 92, 246, 0.85)' : 'rgba(139, 92, 246, 0.28)'}
                      />
                    ))}
                    <polyline
                      points="28,44 72,36 116,40 160,28 204,31 248,22 292,16"
                      fill="none"
                      stroke="#c4b5fd"
                      strokeWidth="2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                    {[
                      [28, 44],
                      [72, 36],
                      [116, 40],
                      [160, 28],
                      [204, 31],
                      [248, 22],
                      [292, 16],
                    ].map(([cx, cy], idx) => (
                      <circle key={idx} cx={cx} cy={cy} r={idx === 6 ? 3.5 : 2.5} fill={idx === 6 ? '#ffffff' : '#a78bfa'} />
                    ))}
                  </svg>
                </div>

                <div className="login-mock-bars">
                  <div>
                    <span>Mura 线缺陷</span>
                    <i>
                      <b style={{ width: '68%' }} />
                    </i>
                    <em>38%</em>
                  </div>
                  <div>
                    <span>划伤与异物</span>
                    <i>
                      <b style={{ width: '44%' }} />
                    </i>
                    <em>24%</em>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <footer className="login-pillars">
            <div>
              <strong>49+ 华星场景模板</strong>
              <span>看板、8D、ECN、Skill 与文档开箱即用</span>
            </div>
            <div>
              <strong>独立沙箱与云服务</strong>
              <span>内网隔离构建，一键启用云表与权限</span>
            </div>
            <div>
              <strong>全版本可回溯发布</strong>
              <span>每次迭代留档，支持内网与指定人员访问</span>
            </div>
          </footer>
        </div>
      </section>

      <section className="login-panel" aria-label="账号登录">
        <div className="login-form-wrap">
          <div className="login-env-badge">
            <i className={ssoEnabled ? 'is-live' : ''} />
            <span>{ssoEnabled ? 'TCL 华星统一身份认证' : '本地开发环境 · 免登录'}</span>
          </div>

          <div className="login-head">
            <h2>{forSite ? '验证员工身份' : '登录织页'}</h2>
            <p className="login-sub">
              {forSite ? '该发布应用需要员工登录，完成验证后将自动返回应用页面。' : '使用公司统一账号（SSO）进入你的应用工作空间。'}
            </p>
          </div>

          {error && (
            <div className="login-alert" role="alert">
              <Icon name="alert" />
              <span>{ERROR_TEXT[error] ?? `登录没有成功（${error}），请稍后重试。`}</span>
            </div>
          )}

          <div className="login-actions">
            {meta.isLoading ? (
              <button className="login-primary-btn" disabled>
                <Icon name="refresh" className="spinning" />
                <span>正在检查登录方式…</span>
              </button>
            ) : ssoEnabled ? (
              <a className="login-primary-btn" href={loginHref} onClick={() => sessionStorage.setItem('zhiye-login-redirect', redirect)}>
                <Icon name="lock" />
                <span>统一认证登录（SSO）</span>
                <Icon name="arrow" className="login-btn-arrow" />
              </a>
            ) : (
              <>
                <a className="login-primary-btn is-dark" href="/create">
                  <span>进入织页工作台</span>
                  <Icon name="arrow" className="login-btn-arrow" />
                </a>
                <button className="login-secondary-btn" disabled>
                  <Icon name="lock" />
                  <span>统一认证登录（当前环境未开启）</span>
                </button>
              </>
            )}
          </div>

          <div className="login-notes">
            <div className="login-note-item">
              <Icon name="globe" />
              <div>
                <b>仅限公司内网访问</b>
                <p>统一认证服务仅在公司内网或 VPN 环境下可用。</p>
              </div>
            </div>
            <div className="login-note-item">
              <Icon name="cloud" />
              <div>
                <b>历史资产自动归属</b>
                <p>登录后，本设备已创建的应用将自动归入你的账号。</p>
              </div>
            </div>
          </div>
        </div>

        <footer className="login-panel-foot">
          <span>TCL CSOT</span>
          <i aria-hidden="true">·</i>
          <span>织页 AI 应用平台</span>
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
