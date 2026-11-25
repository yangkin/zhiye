import type { CSSProperties, ReactNode } from 'react';
import type { SceneTemplate } from '../lib/templates';
import { TemplateIcon } from '../lib/templateIcons';

interface Palette {
  mesh: string;
  accent: string;
  accentSoft: string;
  ink: string;
  orb: string;
}

const PALETTES: Record<SceneTemplate['style'], Palette> = {
  lavender: {
    mesh: 'radial-gradient(120% 90% at 92% 8%, rgba(167, 139, 250, 0.34), transparent 55%), radial-gradient(90% 80% at 6% 96%, rgba(196, 181, 253, 0.30), transparent 60%), linear-gradient(155deg, #f8f5ff 0%, #ede6ff 100%)',
    accent: '#6d28d9',
    accentSoft: '#ede9fe',
    ink: '#1e1635',
    orb: '#8b5cf6',
  },
  mint: {
    mesh: 'radial-gradient(120% 90% at 90% 10%, rgba(52, 211, 153, 0.28), transparent 55%), radial-gradient(90% 80% at 8% 95%, rgba(110, 231, 183, 0.26), transparent 60%), linear-gradient(155deg, #f0fdf8 0%, #dcf7ec 100%)',
    accent: '#047857',
    accentSoft: '#d1fae5',
    ink: '#0d2b22',
    orb: '#10b981',
  },
  sand: {
    mesh: 'radial-gradient(120% 90% at 92% 8%, rgba(251, 191, 36, 0.28), transparent 55%), radial-gradient(90% 80% at 8% 96%, rgba(253, 211, 145, 0.30), transparent 60%), linear-gradient(155deg, #fffaf0 0%, #feefd6 100%)',
    accent: '#b45309',
    accentSoft: '#fef3c7',
    ink: '#311e0c',
    orb: '#f59e0b',
  },
  rose: {
    mesh: 'radial-gradient(120% 90% at 90% 10%, rgba(251, 113, 133, 0.26), transparent 55%), radial-gradient(90% 80% at 10% 95%, rgba(253, 164, 175, 0.28), transparent 60%), linear-gradient(155deg, #fff3f6 0%, #fde3ea 100%)',
    accent: '#be123c',
    accentSoft: '#ffe4e6',
    ink: '#35121d',
    orb: '#f43f5e',
  },
  ink: {
    mesh: 'radial-gradient(120% 90% at 92% 8%, rgba(99, 102, 241, 0.28), transparent 55%), radial-gradient(90% 80% at 8% 96%, rgba(147, 197, 253, 0.30), transparent 60%), linear-gradient(155deg, #f2f5ff 0%, #e1e8ff 100%)',
    accent: '#3730a3',
    accentSoft: '#e0e7ff',
    ink: '#151b38',
    orb: '#4f46e5',
  },
  cyber: {
    mesh: 'radial-gradient(120% 90% at 90% 10%, rgba(129, 140, 248, 0.34), transparent 55%), radial-gradient(90% 80% at 10% 96%, rgba(56, 189, 248, 0.24), transparent 60%), linear-gradient(155deg, #f1f0ff 0%, #e1dcff 100%)',
    accent: '#4f46e5',
    accentSoft: '#e0e7ff',
    ink: '#18143b',
    orb: '#6366f1',
  },
};

function renderMiniUi(t: SceneTemplate, p: Palette): ReactNode {
  const gradId = `sg-${t.id.replace(/[^a-z0-9]/gi, '')}`;

  switch (t.id) {
    /* ============ 看板系列：每张完全不同的真实图表形态 ============ */
    case 'dash-cockpit':
      return (
        <div className="st-cockpit">
          <div className="st-kpi-3">
            <div><small>OEE</small><b>86.4%</b></div>
            <div><small>良率</small><b className="is-ok">98.4%</b></div>
            <div><small>达成</small><b>102%</b></div>
          </div>
          <div className="st-traffic-row">
            <span className="st-dot-g">T1 达标</span>
            <span className="st-dot-g">T2 达标</span>
            <span className="st-dot-a">T9 关注</span>
          </div>
        </div>
      );

    case 'dash-yield-loss':
      return (
        <div className="st-pareto">
          <div className="st-mini-head"><span>不良柏拉图 TOP5</span><b>-0.4%</b></div>
          <svg viewBox="0 0 150 38" preserveAspectRatio="none" className="st-svg-36">
            <rect x="4" y="6" width="18" height="32" rx="3" fill={p.accent} opacity="0.82" />
            <rect x="28" y="13" width="18" height="25" rx="3" fill={p.accent} opacity="0.62" />
            <rect x="52" y="20" width="18" height="18" rx="3" fill={p.accent} opacity="0.45" />
            <rect x="76" y="25" width="18" height="13" rx="3" fill={p.accent} opacity="0.32" />
            <rect x="100" y="29" width="18" height="9" rx="3" fill={p.accent} opacity="0.22" />
            <rect x="124" y="32" width="18" height="6" rx="3" fill={p.accent} opacity="0.16" />
            <polyline points="13,20 37,13 61,9 85,6 109,4 133,3" fill="none" stroke="#f59e0b" strokeWidth="1.8" strokeLinecap="round" />
            <circle cx="13" cy="20" r="2.2" fill="#f59e0b" />
            <circle cx="61" cy="9" r="2.2" fill="#f59e0b" />
            <circle cx="133" cy="3" r="2.2" fill="#f59e0b" />
          </svg>
        </div>
      );

    case 'dash-equipment-wall': {
      const states = ['run', 'run', 'pm', 'run', 'run', 'down', 'run', 'idle', 'run', 'run', 'run', 'pm'];
      return (
        <div className="st-matrix">
          <div className="st-mini-head">
            <span>12 台机台矩阵</span>
            <b className="is-ok">RUN 9 · DOWN 1</b>
          </div>
          <div className="st-eq-grid">
            {states.map((s, i) => (
              <span key={i} className={`st-eq-cell is-${s}`}>E{i + 1}</span>
            ))}
          </div>
        </div>
      );
    }

    case 'dash-delivery':
      return (
        <div className="st-gantt">
          <div className="st-mini-head"><span>交付排期与风险</span><b className="is-warn">2 单预警</b></div>
          <div className="st-gantt-rows">
            <div><small>TV-65</small><span className="st-g-track"><i style={{ width: '82%' }} className="is-ok" /></span></div>
            <div><small>NB-14</small><span className="st-g-track"><i style={{ width: '58%' }} className="is-warn" /></span></div>
            <div><small>MNT-27</small><span className="st-g-track"><i style={{ width: '36%' }} className="is-bad" /></span></div>
          </div>
        </div>
      );

    case 'dash-inventory':
      return (
        <div className="st-donut-wrap">
          <svg viewBox="0 0 44 44" className="st-donut">
            <circle cx="22" cy="22" r="15" fill="none" stroke="#f3efe6" strokeWidth="6" />
            <circle cx="22" cy="22" r="15" fill="none" stroke="#10b981" strokeWidth="6" strokeDasharray="56 94" transform="rotate(-90 22 22)" />
            <circle cx="22" cy="22" r="15" fill="none" stroke="#f59e0b" strokeWidth="6" strokeDasharray="24 94" strokeDashoffset="-56" transform="rotate(-90 22 22)" />
            <circle cx="22" cy="22" r="15" fill="none" stroke="#ef4444" strokeWidth="6" strokeDasharray="14 94" strokeDashoffset="-80" transform="rotate(-90 22 22)" />
          </svg>
          <div className="st-donut-legend">
            <div><i style={{ background: '#10b981' }} /><span>正常周转</span><b>62%</b></div>
            <div><i style={{ background: '#f59e0b' }} /><span>库龄预警</span><b>25%</b></div>
            <div><i style={{ background: '#ef4444' }} /><span>呆滞物料</span><b>13%</b></div>
          </div>
        </div>
      );

    case 'dash-energy':
      return (
        <div className="st-energy">
          <div className="st-mini-head"><span>电 / 水 / 气 单片能耗</span><b className="is-ok">↓ 4.2%</b></div>
          <div className="st-stack-bars">
            {[
              [48, 28, 18],
              [42, 30, 16],
              [52, 24, 15],
              [38, 26, 14],
              [35, 22, 13],
              [32, 20, 12],
            ].map(([a, b, c], i) => (
              <div key={i} className="st-stack-col">
                <i style={{ height: `${c}%`, background: '#38bdf8' }} />
                <i style={{ height: `${b}%`, background: '#34d399' }} />
                <i style={{ height: `${a}%`, background: '#059669' }} />
              </div>
            ))}
          </div>
        </div>
      );

    case 'dash-rd-portfolio':
      return (
        <div className="st-scatter">
          <div className="st-mini-head"><span>项目价值 × 风险象限</span><b>14 项在研</b></div>
          <svg viewBox="0 0 150 38" className="st-svg-36">
            <line x1="75" y1="2" x2="75" y2="36" stroke="#e2dcf2" strokeDasharray="2 2" />
            <line x1="4" y1="19" x2="146" y2="19" stroke="#e2dcf2" strokeDasharray="2 2" />
            <circle cx="28" cy="11" r="6" fill="#7c3aed" opacity="0.72" />
            <circle cx="52" cy="25" r="4.5" fill="#10b981" opacity="0.75" />
            <circle cx="104" cy="12" r="7" fill="#6366f1" opacity="0.8" />
            <circle cx="126" cy="16" r="4" fill="#f59e0b" opacity="0.8" />
            <circle cx="96" cy="28" r="5" fill="#ec4899" opacity="0.7" />
            <circle cx="134" cy="29" r="3.5" fill="#ef4444" opacity="0.75" />
          </svg>
        </div>
      );

    case 'dash-workforce':
      return (
        <div className="st-hbar">
          <div className="st-mini-head"><span>各车间满编与效能</span><b>97.2%</b></div>
          <div className="st-hbar-rows">
            <div><small>Array</small><span><i style={{ width: '94%', background: '#d97706' }} /></span><b>94%</b></div>
            <div><small>Cell</small><span><i style={{ width: '88%', background: '#f59e0b' }} /></span><b>88%</b></div>
            <div><small>Mod</small><span><i style={{ width: '98%', background: '#10b981' }} /></span><b>98%</b></div>
          </div>
        </div>
      );

    case 'dash-personal':
      return (
        <div className="st-upload-split">
          <div className="st-dropzone">
            <b>.xlsx / .csv</b>
            <small>拖拽上传</small>
          </div>
          <span className="st-split-arrow">→</span>
          <div className="st-mini-chart-box">
            <svg viewBox="0 0 64 30" preserveAspectRatio="none" className="st-svg-26">
              <defs>
                <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={p.accent} stopOpacity="0.32" />
                  <stop offset="100%" stopColor={p.accent} stopOpacity="0.02" />
                </linearGradient>
              </defs>
              <path d="M2 24 L16 17 L30 20 L46 9 L62 5 L62 30 L2 30 Z" fill={`url(#${gradId})`} />
              <polyline points="2,24 16,17 30,20 46,9 62,5" fill="none" stroke={p.accent} strokeWidth="1.8" strokeLinecap="round" />
            </svg>
          </div>
        </div>
      );

    /* ============ 日常开发工具：按场景定制（双线/SPC/点检/计算器/查询/日程） ============ */
    case 'yield-daily':
      return (
        <div className="st-pareto">
          <div className="st-mini-head"><span>白班 98.9% · 夜班 98.2%</span><b className="is-ok">达标</b></div>
          <svg viewBox="0 0 150 36" preserveAspectRatio="none" className="st-svg-34">
            <polyline points="4,22 30,15 58,18 86,10 116,13 146,7" fill="none" stroke={p.accent} strokeWidth="1.8" strokeLinecap="round" />
            <polyline points="4,28 30,24 58,25 86,19 116,22 146,16" fill="none" stroke="#10b981" strokeWidth="1.5" strokeDasharray="3 2" strokeLinecap="round" />
          </svg>
        </div>
      );

    case 'spc-chart':
      return (
        <div className="st-spc">
          <div className="st-mini-head"><span>UCL / CL / LCL</span><b>Cpk 1.48</b></div>
          <svg viewBox="0 0 150 36" preserveAspectRatio="none" className="st-svg-34">
            <line x1="2" y1="7" x2="148" y2="7" stroke="#ef4444" strokeWidth="1" strokeDasharray="3 2" />
            <line x1="2" y1="19" x2="148" y2="19" stroke="#10b981" strokeWidth="1" />
            <line x1="2" y1="31" x2="148" y2="31" stroke="#ef4444" strokeWidth="1" strokeDasharray="3 2" />
            <polyline points="8,18 28,22 48,14 68,20 88,5 108,17 128,21 144,16" fill="none" stroke={p.accent} strokeWidth="1.5" />
            <circle cx="88" cy="5" r="3" fill="#ef4444" />
            <circle cx="48" cy="14" r="2" fill={p.accent} />
            <circle cx="144" cy="16" r="2" fill={p.accent} />
          </svg>
        </div>
      );

    case 'equipment-check':
    case 'esd-check':
      return (
        <div className="st-checklist">
          <div className="st-check-row"><i className="is-ok">✓</i><span>{t.id === 'esd-check' ? '静电腕带接地阻值' : '气压与真空度点检'}</span><b>合格</b></div>
          <div className="st-check-row"><i className="is-ok">✓</i><span>{t.id === 'esd-check' ? '离子风机平衡电压' : '冷却水温压正常'}</span><b>合格</b></div>
          <div className="st-check-row"><i className="is-warn">!</i><span>{t.id === 'esd-check' ? '工作台面防静电垫' : '主轴润滑油位偏低'}</span><b className="is-warn">待复核</b></div>
        </div>
      );

    case 'oee-calc':
    case 'shift-hours':
    case 'expense-calc': {
      const cfg =
        t.id === 'oee-calc'
          ? { f1: '时间稼动 92%', f2: '性能×良率 91.5%', outLabel: '综合 OEE', outVal: '84.2%' }
          : t.id === 'shift-hours'
            ? { f1: '白班 14 · 夜班 8', f2: '加班 18.5h', outLabel: '月度工时', outVal: '194.5h' }
            : { f1: '出差 4 天 · 深圳', f2: '餐补 + 交通', outLabel: '预计报销', outVal: '¥1,680' };
      return (
        <div className="st-calc">
          <div className="st-calc-inputs">
            <span>{cfg.f1}</span>
            <span>{cfg.f2}</span>
          </div>
          <div className="st-calc-out">
            <small>{cfg.outLabel}</small>
            <b>{cfg.outVal}</b>
          </div>
        </div>
      );
    }

    case 'material-code':
    case 'spare-parts':
    case 'spare-inventory':
    case 'it-asset': {
      const rows =
        t.id === 'material-code'
          ? [['MAT-8801', '偏光片 65"', '在用'], ['MAT-4219', 'ACF 导电胶', '在用']]
          : t.id === 'it-asset'
            ? [['NB-2048', 'ThinkPad T14', '已领用'], ['MON-812', '27" 4K 显示器', '在库']]
            : [['SP-302', '真空泵密封圈', '低库存'], ['SP-118', '伺服电机编码器', '充足']];
      return (
        <div className="st-lookup">
          <div className="st-lookup-search">⌕ 输入编码 / 规格快速检索…</div>
          {rows.map(([code, name, tag], i) => (
            <div key={i} className="st-lookup-row">
              <code>{code}</code>
              <span>{name}</span>
              <b className={tag === '低库存' ? 'is-bad' : 'is-ok'}>{tag}</b>
            </div>
          ))}
        </div>
      );
    }

    case 'meeting-room':
      return (
        <div className="st-schedule">
          <div className="st-sch-row">
            <small>A301</small>
            <div className="st-sch-bar"><i style={{ left: '8%', width: '34%' }} /><i style={{ left: '56%', width: '28%' }} className="is-soft" /></div>
          </div>
          <div className="st-sch-row">
            <small>B205</small>
            <div className="st-sch-bar"><i style={{ left: '22%', width: '48%' }} /></div>
          </div>
          <div className="st-sch-row">
            <small>C102</small>
            <div className="st-sch-bar"><i style={{ left: '4%', width: '20%' }} className="is-soft" /><i style={{ left: '62%', width: '32%' }} /></div>
          </div>
        </div>
      );

    /* ============ 业务系统（开发网站）：工单/8D/审批/看板/考试 ============ */
    case 'repair-order':
    case 'line-downtime':
      return (
        <div className="st-ticket">
          <div className="st-tk-row"><b className="is-p0">P0</b><span>{t.id === 'repair-order' ? '曝光机真空异常报修' : 'Array 3线机械手停线'}</span><em>12m</em></div>
          <div className="st-tk-row"><b className="is-p1">P1</b><span>{t.id === 'repair-order' ? '涂布泵压力波动' : '传送带传感器误报'}</span><em>维修中</em></div>
        </div>
      );

    case 'quality-8d':
    case 'project-tracker':
      return (
        <div className="st-kanban">
          <div className="st-kb-col">
            <small>{t.id === 'quality-8d' ? 'D1-D3 围堵' : '待办 5'}</small>
            <i />
            <i />
          </div>
          <div className="st-kb-col is-mid">
            <small>{t.id === 'quality-8d' ? 'D4-D6 根因' : '进行中 3'}</small>
            <i />
          </div>
          <div className="st-kb-col is-done">
            <small>{t.id === 'quality-8d' ? 'D7-D8 闭环' : '已完成 9'}</small>
            <i />
            <i />
          </div>
        </div>
      );

    case 'ecn-approval':
    case 'ehs-hazard':
      return (
        <div className="st-flow">
          <div className="st-flow-steps">
            <span className="is-done">{t.id === 'ecn-approval' ? '发起' : '上报'}</span>
            <i />
            <span className="is-cur">{t.id === 'ecn-approval' ? '会签' : '整改'}</span>
            <i />
            <span>{t.id === 'ecn-approval' ? '生效' : '验收'}</span>
          </div>
          <div className="st-flow-meta">
            <span>{t.id === 'ecn-approval' ? 'ECN-2026-084 · 工艺参数变更' : '洁净室通道标线整改 · 限期 48h'}</span>
          </div>
        </div>
      );

    case 'iqc-inspection':
    case 'training-exam':
      return (
        <div className="st-score">
          <div className="st-score-badge">
            <b>{t.id === 'iqc-inspection' ? '96.8%' : '92分'}</b>
            <small>{t.id === 'iqc-inspection' ? '批合格率' : '通过率'}</small>
          </div>
          <div className="st-score-lines">
            <span>{t.id === 'iqc-inspection' ? 'AQL 抽检 · 24 批放行' : 'ESD 安全认证 · 38/41 通过'}</span>
            <span>{t.id === 'iqc-inspection' ? '异常批退 1 笔待处理' : '错题自动归档复训'}</span>
          </div>
        </div>
      );

    /* ============ 全栈应用（6 个旗舰模板各有独立业务图 + API 标识） ============ */
    case 'fs-yield-8d':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>POST /api/8d-cases</code><b className="is-live">LIVE · PG</b></div>
          <div className="st-kanban is-compact">
            <div className="st-kb-col"><small>围堵 2</small><i /></div>
            <div className="st-kb-col is-mid"><small>根因 3</small><i /></div>
            <div className="st-kb-col is-done"><small>闭环 14</small><i /></div>
          </div>
        </div>
      );

    case 'fs-equip-spare':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>/api/tickets · /api/spares</code><b className="is-live">事务扣减</b></div>
          <div className="st-tk-row"><b className="is-p0">维修</b><span>备件领用自动扣库存</span><em>安全</em></div>
        </div>
      );

    case 'fs-rd-tracker':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>/api/sprints · /api/issues</code><b className="is-live">燃尽图</b></div>
          <svg viewBox="0 0 150 26" preserveAspectRatio="none" className="st-svg-24">
            <line x1="4" y1="4" x2="146" y2="23" stroke="#cbd5e1" strokeWidth="1.2" strokeDasharray="3 2" />
            <polyline points="4,4 36,7 70,11 106,16 146,21" fill="none" stroke={p.accent} strokeWidth="1.8" strokeLinecap="round" />
          </svg>
        </div>
      );

    case 'fs-customer-complaint':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>/api/rma · 5Why 追溯</code><b className="is-warn-pill">SLA 24h</b></div>
          <div className="st-flow-steps">
            <span className="is-done">受理</span><i /><span className="is-cur">5Why</span><i /><span>回复</span>
          </div>
        </div>
      );

    case 'fs-doc-report-hub':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>POST /api/transform</code><b className="is-live">/data 卷</b></div>
          <div className="st-pipe">
            <span>CSV/XLSX</span><i>→</i><span className="is-hi">规则清洗</span><i>→</i><span>周报导出</span>
          </div>
        </div>
      );

    case 'fs-meeting-action':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>/api/meetings · /api/actions</code><b className="is-live">协同追踪</b></div>
          <div className="st-check-row"><i className="is-ok">✓</i><span>T11 良率改善行动项 · 责任人确认</span><b>按期</b></div>
        </div>
      );

    /* ============ 企业生态联动（AGW + 飞书 + 办公闭环） ============ */
    case 'eco-agw-feishu-alert':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>AGW 良率 → 飞书机器人</code><b className="is-warn-pill">超限推送</b></div>
          <div className="st-tk-row"><b className="is-p0">飞书卡</b><span>T11 Array 良率跌破 95.5%</span><em>已推送</em></div>
        </div>
      );

    case 'eco-feishu-bitable-inspect':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>飞书多维表格 · Bitable</code><b className="is-live">双向同步</b></div>
          <div className="st-pipe">
            <span>点检台账</span><i>⇄</i><span className="is-hi">多维表格</span><i>→</i><span>飞书催办</span>
          </div>
        </div>
      );

    case 'eco-feishu-approval-hub':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>华星 SSO · 飞书审批卡</code><b className="is-live">待批 4</b></div>
          <div className="st-flow-steps">
            <span className="is-done">发起</span><i /><span className="is-cur">会签</span><i /><span>飞书通知</span>
          </div>
        </div>
      );

    case 'eco-meeting-feishu-ai':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>AI 纪要提炼 · 飞书卡片</code><b className="is-live">一键导出</b></div>
          <div className="st-pipe">
            <span>会议速记</span><i>→</i><span className="is-hi">AI 行动项</span><i>→</i><span>飞书群卡</span>
          </div>
        </div>
      );

    case 'eco-weekly-report-bot':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>/api/reports · 飞书播报</code><b className="is-live">18/20 已交</b></div>
          <div className="st-check-row"><i className="is-ok">✓</i><span>部门周报自动聚合 · 推送高管群</span><b>就绪</b></div>
        </div>
      );

    case 'eco-spare-feishu-dispatch':
      return (
        <div className="st-fs-rich">
          <div className="st-fs-top"><code>库存扣减 → 飞书值班群</code><b className="is-warn-pill">低库预警</b></div>
          <div className="st-tk-row"><b className="is-p0">请购</b><span>真空阀芯余量 2（安全线 5）</span><em>已派单</em></div>
        </div>
      );

    /* ============ 开发 skill（深色终端卡，按技能展示专属命令/文件） ============ */
    case 'skill-8d':
    case 'skill-sql':
    case 'skill-weekly':
    case 'skill-code-review':
    case 'skill-fmea':
    case 'skill-ecn-doc':
    case 'skill-interview':
    case 'skill-meeting':
    case 'skill-log-analysis':
    case 'skill-ppt-outline': {
      const meta: Record<string, [string, string, string]> = {
        'skill-8d': ['SKILL.md', '8d_template.md', 'D1 团队 → D4 根因 → D8 预防'],
        'skill-sql': ['SKILL.md', 'mes_schema.sql', 'SELECT line, AVG(yield) FROM ...'],
        'skill-weekly': ['SKILL.md', 'weekly_fmt.md', '本周进展 · 风险阻塞 · 下周计划'],
        'skill-code-review': ['SKILL.md', 'lint_rules.md', '安全漏洞 · 性能瓶颈 · 命名规范'],
        'skill-fmea': ['SKILL.md', 'rpn_calc.py', 'S × O × D = RPN 风险优先数'],
        'skill-ecn-doc': ['SKILL.md', 'ecn_spec.md', '变更原因 · 影响评估 · 切换批次'],
        'skill-interview': ['SKILL.md', 'rubric.md', '岗位能力模型 + 追问评分表'],
        'skill-meeting': ['SKILL.md', 'minutes.md', '决议事项 · 责任人 · 截止日期'],
        'skill-log-analysis': ['SKILL.md', 'parse_log.py', 'ALARM_CODE 聚类 + 根因定位'],
        'skill-ppt-outline': ['SKILL.md', 'storyline.md', 'SCQA 叙事 · 页级图表建议'],
      };
      const [file1, file2, snippet] = meta[t.id] ?? ['SKILL.md', 'scripts/', '自动加载业务专家规范'];
      return (
        <div className="st-skill">
          <div className="st-skill-head">
            <b>{file1}</b>
            <span>{file2}</span>
          </div>
          <div className="st-skill-snippet">{snippet}</div>
        </div>
      );
    }

    /* ============ 技术文档（按文档展示专属大纲与文件格式） ============ */
    default: {
      const docMeta: Record<string, [string, string, string]> = {
        'doc-mes-api': ['API', '鉴权 · 批次查询 · 错误码', 'GET /mes/v2/lots'],
        'doc-onboarding': ['手册', '账号 · VPN · 常用系统', '新员工首周清单'],
        'doc-eap': ['规范', 'SECS/GEM · 事件订阅', '机台数据采集标准'],
        'doc-readme': ['MD', '架构 · 本地启动 · 部署', 'README & Runbook'],
        'doc-process': ['教材', 'Array → Cell → Module', '面板制造工艺图解'],
        'doc-cleanroom': ['SOP', '风淋 · 着装 · 物料管控', 'Class 10 洁净规范'],
        'doc-api-gateway': ['API', 'AppKey · 签名 · 限流', 'AGW 统一网关文档'],
        'doc-hr-handbook': ['制度', '考勤 · 假期 · 报销福利', '员工日常速查'],
        'doc-incident': ['预案', 'P0 响应 · 回滚 · 复盘', '故障应急指挥手册'],
        'doc-equipment-manual': ['保养', '日检 · 周保 · 备件清单', '设备标准操作卡'],
      };
      const [tag, outline, sub] = docMeta[t.id] ?? ['.docx', '章节目录 · 表格 · 流程图', '支持导出 Word'];
      return (
        <div className="st-doc">
          <div className="st-doc-toc">
            <em className="is-on" />
            <em />
            <em />
          </div>
          <div className="st-doc-body">
            <b>{outline}</b>
            <small>{sub}</small>
          </div>
          <span className="st-doc-ext">{tag}</span>
        </div>
      );
    }
  }
}

/** 场景模板封面卡片：55 个模板各具专属业务微型界面插画，拒绝千篇一律。 */
export function SceneThumb({ t }: { t: SceneTemplate }) {
  const p = PALETTES[t.style] ?? PALETTES.lavender;
  const variant = t.type === '开发skill' ? 'skill' : t.type === '全栈应用' ? 'fullstack' : 'default';

  const cssVars = {
    '--st-accent': p.accent,
    '--st-accent-soft': p.accentSoft,
    '--st-ink': p.ink,
    '--st-orb': p.orb,
    background: p.mesh,
  } as CSSProperties;

  return (
    <div className={`thumbnail scene-thumb is-${variant}`} style={cssVars} aria-hidden="true">
      <span className="scene-thumb-watermark">
        <TemplateIcon id={t.id} size={108} />
      </span>

      <div className="scene-thumb-top">
        <span className="scene-thumb-icon" style={{ background: `linear-gradient(135deg, ${p.accent}, ${p.orb})` }}>
          <TemplateIcon id={t.id} size={16} />
        </span>
        <span className="scene-thumb-type-pill">
          <i />
          {t.type}
        </span>
      </div>

      <b className="scene-thumb-title">{t.title}</b>

      <div className="scene-thumb-preview">{renderMiniUi(t, p)}</div>
    </div>
  );
}
