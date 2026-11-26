import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, api, errorMessage, type AccessLevel } from '../api/client';
import { qk, useInvalidateApp, useMeta } from '../api/queries';
import { waitForTask } from '../api/sse';
import {
  isServiceApp,
  isSkillApp,
  type AppDetail,
  type PublishRegistrationForm,
  type PublishRequestItem,
  type Task,
} from '../api/types';
import { Icon } from '../lib/icons';
import { copyText, fmtTime } from '../lib/format';
import { encodeQrMatrix } from '../lib/qr';
import { toast } from '../lib/store';
import { Modal } from '../components/Modal';
import { ACCESS_OPTIONS, AccessForm, type AccessFormState } from './CloudConsole';

function QrPreview({ value, size = 104 }: { value: string; size?: number }) {
  const matrix = useMemo(() => encodeQrMatrix(value), [value]);
  if (!matrix) return null;
  const n = matrix.length;
  const quiet = 2;
  const total = n + quiet * 2;
  const cells: string[] = [];
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (matrix[r][c]) cells.push(`M${c + quiet},${r + quiet}h1v1h-1z`);
    }
  }
  return (
    <svg
      className="publish-qr-svg"
      width={size}
      height={size}
      viewBox={`0 0 ${total} ${total}`}
      role="img"
      aria-label="站点访问二维码"
    >
      <rect width={total} height={total} fill="#ffffff" rx="1.5" />
      <path d={cells.join('')} fill="#1f1a2d" />
    </svg>
  );
}

export function formatChineseDate(iso: string | null | undefined): string {
  if (!iso) return '— 年 — 月 — 日';
  const m = String(iso).trim().match(/^(\d{4})-(\d{2})-(\d{2})/);
  if (!m) return iso;
  return `${m[1]} 年 ${Number(m[2])} 月 ${Number(m[3])} 日`;
}

/** 只读渲染《Vibe Coding 应用登记表》（用于待审核展示与管理员审批详情） */
export function VibeRegistrationReadonlyTable({ item }: { item: PublishRegistrationForm }) {
  const rows: { label: string; sub?: string; required?: boolean; value: string }[] = [
    { label: '应用名称', required: true, value: item.appName },
    { label: '应用所属部门', required: true, value: item.department },
    { label: '业务背景', sub: '描述现状与由来', required: true, value: item.businessBg },
    { label: '痛点', sub: '描述当前困难与影响', required: false, value: item.painPoints || '（未填）' },
    { label: '解决方案', sub: '做什么、不做什么、拟用工具、与系统交互的具体数据范围', required: true, value: item.solution },
    { label: '预期收益', sub: '节省工时 / 减少差错 / 有形无形收益等', required: true, value: item.expectedBenefit },
    { label: '预期用户与范围', sub: '本人 / 科室 / 部门或中心', required: true, value: item.targetUsers },
    { label: '部署方式', required: true, value: item.deployMode },
    { label: '主备联系人', required: true, value: item.contacts },
    { label: '预计使用周期', required: true, value: formatChineseDate(item.expectedUntil) },
    { label: '技术方案与数据说明', required: false, value: item.techAndData || '（未填）' },
  ];
  return (
    <div className="vibe-reg-sheet">
      <div className="vibe-reg-sheet-title">Vibe Coding 应用登记表</div>
      <table className="vibe-reg-table readonly">
        <tbody>
          {rows.map((r) => (
            <tr key={r.label}>
              <th>
                <div className="vibe-th-main">
                  {r.required ? <span className="vibe-req">*</span> : null}
                  <span>{r.label}</span>
                </div>
                {r.sub ? <div className="vibe-th-sub">{r.sub}</div> : null}
              </th>
              <td>
                <div className="vibe-td-text">{r.value}</div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

interface Props {
  app: AppDetail;
  hasLocalDraft: boolean;
  commitDraft: () => Promise<Task | null>;
  onTaskStarted: (t: Task) => void;
  onClose: () => void;
}

function formatEffectiveAccess(level: AccessLevel, membersCount: number): string {
  const opt = ACCESS_OPTIONS.find((o) => o.value === level);
  const label = opt?.label ?? level;
  if (level === 'members') return `${label}（白名单 ${membersCount} 人 + 创建者）`;
  return label;
}

const TARGET_USER_CHIPS = ['个人使用', '科室使用', '部门或中心'];
const DEPLOY_MODE_CHIPS = ['托管部署', '本地部署', '私有化部署'];

const EMPTY_FORM: PublishRegistrationForm = {
  appName: '',
  department: '',
  businessBg: '',
  painPoints: '',
  solution: '',
  expectedBenefit: '',
  targetUsers: '个人使用',
  deployMode: '托管部署',
  contacts: '',
  expectedUntil: '',
  techAndData: '',
};

export function PublishModal({ app, hasLocalDraft, commitDraft, onTaskStarted, onClose }: Props) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const invalidate = useInvalidateApp();
  const { data: meta } = useMeta();
  const isAdmin = Boolean(meta?.auth?.admin);

  const [step, setStep] = useState<'form' | 'working' | 'submitted' | 'done'>('form');
  const [label, setLabel] = useState('');
  const [error, setError] = useState('');
  const [siteUrl, setSiteUrl] = useState(app.siteUrl);
  const [submittedReq, setSubmittedReq] = useState<PublishRequestItem | null>(null);
  const [effectiveAccess, setEffectiveAccess] = useState<{ level: AccessLevel; membersCount: number }>({ level: 'public', membersCount: 0 });
  const [cancelling, setCancelling] = useState(false);

  const v = app.currentVersion;
  const needsSave = hasLocalDraft || app.hasDraft;

  const cloud = useQuery({
    queryKey: ['cloud', app.id],
    queryFn: () => api.cloudStatus(app.id),
    enabled: !isServiceApp(app) && !isSkillApp(app),
  });
  const cloudOn = cloud.data?.enabled ?? false;

  const reviewQ = useQuery({
    queryKey: ['publish-requests', app.id],
    queryFn: () => api.publishRequests(app.id),
  });
  const reviewRequired = reviewQ.data?.reviewRequired ?? meta?.publishReviewRequired ?? true;
  const currentPending = reviewQ.data?.currentPending ?? null;
  const latestRequest = reviewQ.data?.latestRequest ?? null;
  const suggestedTech = reviewQ.data?.defaults?.suggestedTechAndData ?? '';

  const [form, setForm] = useState<PublishRegistrationForm>({
    ...EMPTY_FORM,
    appName: app.name,
  });
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    if (!reviewQ.data || hydrated) return;
    const d = reviewQ.data.defaults;
    setForm({
      appName: d.appName || app.name,
      department: d.department || '',
      businessBg: d.businessBg || '',
      painPoints: d.painPoints || '',
      solution: d.solution || '',
      expectedBenefit: d.expectedBenefit || '',
      targetUsers: d.targetUsers || '个人使用',
      deployMode: d.deployMode || '托管部署',
      contacts: d.contacts || meta?.auth?.user?.realName || meta?.auth?.user?.loginName || '',
      expectedUntil: d.expectedUntil || '',
      techAndData: d.techAndData || '',
    });
    setHydrated(true);
  }, [reviewQ.data, hydrated, app.name, meta?.auth?.user]);

  const setField = <K extends keyof PublishRegistrationForm>(k: K, val: PublishRegistrationForm[K]) => {
    setForm((prev) => ({ ...prev, [k]: val }));
    setError('');
  };

  const [blocked, setBlocked] = useState<{ tables: string[]; sso: boolean }>({ tables: [], sso: false });
  const onBlocked = useCallback((tables: string[], sso: boolean) => setBlocked({ tables, sso }), []);
  const accessStateRef = useRef<AccessFormState | null>(null);
  const [accessReady, setAccessReady] = useState(false);
  const [accessErr, setAccessErr] = useState(false);
  const onAccessStateChange = useCallback((st: AccessFormState) => {
    accessStateRef.current = st;
    setAccessReady(!st.loading && !st.error);
    setAccessErr(st.error);
  }, []);

  const saveDraftFirst = async () => {
    setLabel('正在保存未提交的编辑…');
    const task = await commitDraft();
    if (!task) return;
    onTaskStarted(task);
    const final = await waitForTask(task.id, (e) => {
      if (e.type === 'status' && e.payload?.phaseLabel) setLabel(`保存编辑：${e.payload.phaseLabel}`);
    });
    if (final.status !== 'SUCCEEDED') throw new Error(final.error?.message || '编辑没有保存成功，已暂停提交。');
  };

  const validateForm = (): string | null => {
    if (!form.appName.trim()) return '请填写必填项「应用名称」。';
    if (!form.department.trim()) return '请填写必填项「应用所属部门」。';
    if (!form.businessBg.trim()) return '请填写必填项「业务背景」。';
    if (!form.solution.trim()) return '请填写必填项「解决方案」。';
    if (!form.expectedBenefit.trim()) return '请填写必填项「预期收益」。';
    if (!form.targetUsers.trim()) return '请填写必填项「预期用户与范围」。';
    if (!form.deployMode.trim()) return '请填写必填项「部署方式」。';
    if (!form.contacts.trim()) return '请填写必填项「主备联系人」。';
    if (!form.expectedUntil.trim()) return '请选择必填项「预计使用周期」。';
    return null;
  };

  const handleSubmit = async () => {
    const acc = accessStateRef.current;
    if (!acc || acc.loading || acc.error) {
      setError('访问设置尚未加载完成，请稍后重试。');
      return;
    }
    if (reviewRequired) {
      const formErr = validateForm();
      if (formErr) {
        setError(formErr);
        return;
      }
    }
    setStep('working');
    setError('');
    try {
      let finalLevel: AccessLevel = acc.level;
      let finalMembersCount = acc.membersCount;
      if (acc.dirty) {
        setLabel('正在保存访问范围…');
        const saved = await acc.save();
        finalLevel = saved.level;
        finalMembersCount = saved.members.length;
      }
      if (needsSave) await saveDraftFirst();
      setEffectiveAccess({ level: finalLevel, membersCount: finalMembersCount });

      if (reviewRequired) {
        setLabel('正在提交发布审核申请…');
        const created = await api.submitPublishRequest(app.id, { form });
        setSubmittedReq(created);
        invalidate(app.id);
        void qc.invalidateQueries({ queryKey: ['publish-requests', app.id] });
        void qc.invalidateQueries({ queryKey: qk.adminOverview });
        setStep('submitted');
        toast('发布审核申请已提交，等待管理员审批通过后正式上线。');
      } else {
        setLabel('正在发布…');
        let res;
        try {
          res = await api.publish(app.id);
        } catch (err) {
          if (err instanceof ApiError && err.code === 'DRAFT_PENDING') {
            await saveDraftFirst();
            setLabel('正在发布…');
            res = await api.publish(app.id);
          } else throw err;
        }
        setSiteUrl(res.siteUrl);
        invalidate(app.id);
        setStep('done');
        toast('发布完成，线上版本与访问范围已生效。');
      }
    } catch (err) {
      invalidate(app.id);
      setError(errorMessage(err));
      setStep('form');
    }
  };

  const withdrawPending = async (reqId: string) => {
    setCancelling(true);
    setError('');
    try {
      await api.cancelPublishRequest(app.id, reqId);
      invalidate(app.id);
      await qc.invalidateQueries({ queryKey: ['publish-requests', app.id] });
      void qc.invalidateQueries({ queryKey: qk.adminOverview });
      setSubmittedReq(null);
      setStep('form');
      toast('已撤回发布审核申请，可修改登记表后重新提交。');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setCancelling(false);
    }
  };

  const unpublish = async () => {
    try {
      await api.unpublish(app.id);
      invalidate(app.id);
      toast('站点已下线。');
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  // 1. 直接发布成功视图（未开启审核时）
  if (step === 'done' && siteUrl) {
    return (
      <Modal
        title="已经上线啦"
        onClose={onClose}
        footer={
          <>
            <button className="subtle-button" onClick={onClose}>
              完成
            </button>
            <button className="primary-button" onClick={() => window.open(siteUrl, '_blank', 'noopener')}>
              打开站点 <Icon name="external" />
            </button>
          </>
        }
      >
        <div className="publish-art">
          <Icon name="cloud" />
          <i>✦</i>
        </div>
        <h3 className="center">{app.name}</h3>
        <div className="publish-detail">
          <span>
            <span className="publish-detail-label">线上版本</span>
            <b>{v ? `V${v.number} · ${v.title}` : '已发布'}</b>
          </span>
          <span>
            <span className="publish-detail-label">实际生效访问范围</span>
            <b>{formatEffectiveAccess(effectiveAccess.level, effectiveAccess.membersCount)}</b>
          </span>
        </div>
        <label className="field-label">访问地址与快捷分享</label>
        <div className="copy-field">
          <span>{siteUrl}</span>
          <button
            className="icon-button"
            aria-label="复制站点地址"
            title="复制地址"
            onClick={async () => toast((await copyText(siteUrl)) ? '站点地址已复制。' : '请手动复制地址。')}
          >
            <Icon name="copy" />
          </button>
        </div>
        <div className="publish-share-card">
          <QrPreview value={siteUrl} size={92} />
          <div className="publish-share-info">
            <b>直接发到飞书群或让同事扫码打开</b>
            <p>已附带应用名称、访问范围说明和直达链接，粘贴即用。</p>
            <button
              type="button"
              className="subtle-button"
              onClick={async () => {
                const accessLabel = formatEffectiveAccess(effectiveAccess.level, effectiveAccess.membersCount);
                const msg = `【${app.name}】已上线（${accessLabel}）\n访问地址：${siteUrl}`;
                toast((await copyText(msg)) ? '飞书分享话术已复制，直接粘贴即可发送。' : '请手动复制分享内容。');
              }}
            >
              <Icon name="copy" /> 复制飞书分享话术
            </button>
          </div>
        </div>
      </Modal>
    );
  }

  // 2. 刚提交审核成功 或 已有待审核申请视图
  const activePending = step === 'submitted' ? submittedReq : currentPending;
  if (activePending) {
    return (
      <Modal
        className="publish-review-modal"
        title="发布审核中 · 等待管理员审批"
        onClose={onClose}
        footer={
          <>
            {app.publishedVersionId ? (
              <button className="subtle-button" disabled={cancelling} onClick={() => void unpublish()}>
                下线当前线上站点
              </button>
            ) : null}
            <button
              className="subtle-button"
              disabled={cancelling}
              onClick={() => void withdrawPending(activePending.id)}
            >
              {cancelling ? '撤回中…' : '撤回申请并修改'}
            </button>
            {isAdmin ? (
              <button
                className="primary-button"
                onClick={() => {
                  onClose();
                  navigate('/admin?tab=reviews');
                }}
              >
                前往管理控制台审核 <Icon name="arrow" />
              </button>
            ) : (
              <button className="primary-button" onClick={onClose}>
                知道了
              </button>
            )}
          </>
        }
      >
        <div className="publish-review-banner pending">
          <Icon name="history" />
          <div>
            <b>
              V{activePending.versionNumber} 发布申请已提交，正在等待管理员审核
            </b>
            <p>
              申请单号 <code>{activePending.id}</code> · 提交时间 {fmtTime(activePending.createdAt)}。管理员审核通过后将自动正式发布上线。
            </p>
          </div>
        </div>
        {error ? (
          <p className="inline-error" role="alert">
            {error}
          </p>
        ) : null}
        <VibeRegistrationReadonlyTable item={activePending} />
      </Modal>
    );
  }

  // 3. 登记表填写与提交视图
  const online = !!app.publishedVersionId && app.publishedVersionId === v?.id && !needsSave;
  const rejectedNotice = latestRequest?.status === 'rejected' ? latestRequest : null;

  return (
    <Modal
      className={reviewRequired ? 'publish-review-modal' : undefined}
      title={reviewRequired ? '提交发布审核（Vibe Coding 应用登记表）' : '让好作品，被看见。'}
      onClose={() => step !== 'working' && onClose()}
      footer={
        <>
          {app.publishedVersionId ? (
            <button className="subtle-button" disabled={step === 'working'} onClick={() => void unpublish()}>
              下线站点
            </button>
          ) : (
            <button className="subtle-button" disabled={step === 'working'} onClick={onClose}>
              取消
            </button>
          )}
          <button
            className="primary-button"
            disabled={step === 'working' || !v || blocked.tables.length > 0 || !accessReady || accessErr}
            onClick={() => void handleSubmit()}
          >
            {step === 'working'
              ? label || '正在提交…'
              : reviewRequired
                ? '提交发布审核'
                : online
                  ? '重新发布'
                  : '确认发布'}{' '}
            <Icon name="arrow" />
          </button>
        </>
      }
    >
      {rejectedNotice ? (
        <div className="publish-review-banner rejected" role="alert">
          <Icon name="alert" />
          <div>
            <b>上次发布申请（V{rejectedNotice.versionNumber}）被管理员驳回</b>
            <p>
              驳回意见：{rejectedNotice.reviewComment || '无'}
              {rejectedNotice.reviewerActor ? ` · 审核人 ${rejectedNotice.reviewerActor}` : ''}
              {rejectedNotice.reviewedAt ? ` · ${fmtTime(rejectedNotice.reviewedAt)}` : ''}
            </p>
          </div>
        </div>
      ) : null}

      <div className="publish-detail">
        <span>
          <span className="publish-detail-label">申请发布的版本</span>
          <b>{v ? `V${v.number} · ${v.title}` : '还没有版本'}</b>
        </span>
        <span>
          <span className="publish-detail-label">当前线上状态</span>
          <b>{app.siteUrl ? app.siteUrl : '尚未发布（审核通过后正式上线）'}</b>
        </span>
      </div>

      <div className="publish-access">
        <label className="field-label">上线后谁能打开</label>
        <AccessForm appId={app.id} compact onBlocked={onBlocked} onStateChange={onAccessStateChange} />
      </div>

      {reviewRequired ? (
        <div className="vibe-reg-sheet">
          <div className="vibe-reg-sheet-head">
            <span className="vibe-reg-sheet-title">Vibe Coding 应用登记表</span>
            <span className="vibe-reg-sheet-tip">带 <i className="vibe-req">*</i> 为必填项，管理员审批通过后自动正式发布</span>
          </div>
          <table className="vibe-reg-table">
            <tbody>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span className="vibe-req">*</span>
                    <span>应用名称</span>
                  </div>
                </th>
                <td>
                  <input
                    aria-label="登记表-应用名称"
                    maxLength={80}
                    placeholder="填写应用名称"
                    value={form.appName}
                    onChange={(e) => setField('appName', e.target.value)}
                  />
                </td>
              </tr>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span className="vibe-req">*</span>
                    <span>应用所属部门</span>
                  </div>
                </th>
                <td>
                  <input
                    aria-label="登记表-应用所属部门"
                    maxLength={120}
                    placeholder="例如：数字化与工业智能中心 / T6厂务科"
                    value={form.department}
                    onChange={(e) => setField('department', e.target.value)}
                  />
                </td>
              </tr>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span className="vibe-req">*</span>
                    <span>业务背景</span>
                  </div>
                  <div className="vibe-th-sub">描述现状与由来</div>
                </th>
                <td>
                  <textarea
                    aria-label="登记表-业务背景"
                    rows={3}
                    maxLength={2000}
                    placeholder="描述现状与由来…"
                    value={form.businessBg}
                    onChange={(e) => setField('businessBg', e.target.value)}
                  />
                </td>
              </tr>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span>痛点</span>
                  </div>
                  <div className="vibe-th-sub">描述当前困难与影响</div>
                </th>
                <td>
                  <textarea
                    aria-label="登记表-痛点"
                    rows={2}
                    maxLength={2000}
                    placeholder="选填：描述当前困难与影响…"
                    value={form.painPoints}
                    onChange={(e) => setField('painPoints', e.target.value)}
                  />
                </td>
              </tr>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span className="vibe-req">*</span>
                    <span>解决方案</span>
                  </div>
                  <div className="vibe-th-sub">做什么、不做什么、拟用工具、与系统交互的具体数据范围</div>
                </th>
                <td>
                  <textarea
                    aria-label="登记表-解决方案"
                    rows={3}
                    maxLength={3000}
                    placeholder="写明做什么、不做什么、拟用工具及与外部/企业系统交互的数据范围…"
                    value={form.solution}
                    onChange={(e) => setField('solution', e.target.value)}
                  />
                </td>
              </tr>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span className="vibe-req">*</span>
                    <span>预期收益</span>
                  </div>
                  <div className="vibe-th-sub">节省工时 / 减少差错 / 有形无形收益等</div>
                </th>
                <td>
                  <textarea
                    aria-label="登记表-预期收益"
                    rows={2}
                    maxLength={2000}
                    placeholder="例如：每周节省 6 小时手工核对工时，降低人工抄录差错率…"
                    value={form.expectedBenefit}
                    onChange={(e) => setField('expectedBenefit', e.target.value)}
                  />
                </td>
              </tr>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span className="vibe-req">*</span>
                    <span>预期用户与范围</span>
                  </div>
                  <div className="vibe-th-sub">本人 / 科室 / 部门或中心</div>
                </th>
                <td>
                  <div className="vibe-chip-input">
                    <input
                      aria-label="登记表-预期用户与范围"
                      maxLength={200}
                      placeholder="个人使用 / 科室使用 / 部门或中心"
                      value={form.targetUsers}
                      onChange={(e) => setField('targetUsers', e.target.value)}
                    />
                    <div className="vibe-quick-chips">
                      {TARGET_USER_CHIPS.map((c) => (
                        <button
                          key={c}
                          type="button"
                          className={form.targetUsers === c ? 'active' : ''}
                          onClick={() => setField('targetUsers', c)}
                        >
                          {c}
                        </button>
                      ))}
                    </div>
                  </div>
                </td>
              </tr>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span className="vibe-req">*</span>
                    <span>部署方式</span>
                  </div>
                </th>
                <td>
                  <div className="vibe-chip-input">
                    <input
                      aria-label="登记表-部署方式"
                      maxLength={100}
                      placeholder="托管部署"
                      value={form.deployMode}
                      onChange={(e) => setField('deployMode', e.target.value)}
                    />
                    <div className="vibe-quick-chips">
                      {DEPLOY_MODE_CHIPS.map((c) => (
                        <button
                          key={c}
                          type="button"
                          className={form.deployMode === c ? 'active' : ''}
                          onClick={() => setField('deployMode', c)}
                        >
                          {c}
                        </button>
                      ))}
                    </div>
                  </div>
                </td>
              </tr>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span className="vibe-req">*</span>
                    <span>主备联系人</span>
                  </div>
                </th>
                <td>
                  <input
                    aria-label="登记表-主备联系人"
                    maxLength={200}
                    placeholder="填写主联系人（可补充备用联系人）"
                    value={form.contacts}
                    onChange={(e) => setField('contacts', e.target.value)}
                  />
                </td>
              </tr>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span className="vibe-req">*</span>
                    <span>预计使用周期</span>
                  </div>
                </th>
                <td>
                  <div className="vibe-date-cell">
                    <input
                      type="date"
                      aria-label="登记表-预计使用周期"
                      value={form.expectedUntil}
                      onChange={(e) => setField('expectedUntil', e.target.value)}
                    />
                    <span className="vibe-date-zh">{formatChineseDate(form.expectedUntil)}</span>
                  </div>
                </td>
              </tr>
              <tr>
                <th>
                  <div className="vibe-th-main">
                    <span>技术方案与数据说明</span>
                  </div>
                </th>
                <td>
                  <div className="vibe-tech-cell">
                    <textarea
                      aria-label="登记表-技术方案与数据说明"
                      rows={3}
                      maxLength={4000}
                      placeholder="选填：架构栈、数据库/表结构、接入的外部数据源与飞书连接器说明…"
                      value={form.techAndData}
                      onChange={(e) => setField('techAndData', e.target.value)}
                    />
                    {suggestedTech ? (
                      <button
                        type="button"
                        className="vibe-autofill-btn"
                        onClick={() => setField('techAndData', suggestedTech)}
                      >
                        <Icon name="spark" /> 一键填入当前应用技术概况
                      </button>
                    ) : null}
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      ) : null}

      {app.siteUrl ? (
        <div className="publish-share-card">
          <QrPreview value={app.siteUrl} size={76} />
          <div className="publish-share-info">
            <b>当前已上线版本地址（审核期间线上保持可访问）</b>
            <p>同事扫码或复制直达链接即可打开当前线上版本。</p>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <button
                type="button"
                className="subtle-button"
                onClick={async () => {
                  const acc = accessStateRef.current;
                  const accessLabel = acc ? formatEffectiveAccess(acc.level, acc.membersCount) : '线上可访问';
                  const msg = `【${app.name}】已上线（${accessLabel}）\n访问地址：${app.siteUrl}`;
                  toast((await copyText(msg)) ? '飞书分享话术已复制。' : '请手动复制分享内容。');
                }}
              >
                <Icon name="copy" /> 复制飞书分享话术
              </button>
              <button
                type="button"
                className="subtle-button"
                onClick={async () => toast((await copyText(app.siteUrl!)) ? '站点地址已复制。' : '请手动复制地址。')}
              >
                复制地址
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {blocked.tables.length ? (
        <p className="cc-warn" role="alert">
          <Icon name="alert" />
          {blocked.sso
            ? `表 ${blocked.tables.join('、')} 需要登录身份，请改成「员工登录」后再提交。`
            : `表 ${blocked.tables.join('、')} 需要登录身份，暂时不能公开发布。可在「云服务 → 访问与权限」把它改成「所有人可看可改」。`}
        </p>
      ) : needsSave ? (
        <p className="notice-box">有未保存的编辑，提交审核前会先存成新版本。</p>
      ) : cloudOn && !app.publishedVersionId ? (
        <p className="notice-box">预览和线上共用一份数据，测试数据可先在「云服务」里清空。</p>
      ) : null}

      {error && !blocked.tables.length ? (
        <p className="inline-error" role="alert">
          {error}
        </p>
      ) : null}
    </Modal>
  );
}
