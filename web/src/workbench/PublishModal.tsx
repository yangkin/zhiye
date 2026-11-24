import { useCallback, useRef, useState } from 'react';
import { ApiError, api, errorMessage, type AccessLevel } from '../api/client';
import { useInvalidateApp } from '../api/queries';
import { waitForTask } from '../api/sse';
import { isServiceApp, isSkillApp, type AppDetail, type Task } from '../api/types';
import { Icon } from '../lib/icons';
import { copyText } from '../lib/format';
import { toast } from '../lib/store';
import { Modal } from '../components/Modal';
import { useQuery } from '@tanstack/react-query';
import { ACCESS_OPTIONS, AccessForm, type AccessFormState } from './CloudConsole';

interface Props {
  app: AppDetail;
  hasLocalDraft: boolean;
  /** 先保存可视化编辑草稿，返回 visual_save 任务；没有草稿返回 null */
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

export function PublishModal({ app, hasLocalDraft, commitDraft, onTaskStarted, onClose }: Props) {
  const invalidate = useInvalidateApp();
  const [step, setStep] = useState<'confirm' | 'working' | 'done'>('confirm');
  const [label, setLabel] = useState('');
  const [error, setError] = useState('');
  const [siteUrl, setSiteUrl] = useState(app.siteUrl);
  const [effectiveAccess, setEffectiveAccess] = useState<{ level: AccessLevel; membersCount: number }>({ level: 'public', membersCount: 0 });
  const v = app.currentVersion;
  const needsSave = hasLocalDraft || app.hasDraft;
  const cloud = useQuery({
    queryKey: ['cloud', app.id],
    queryFn: () => api.cloudStatus(app.id),
    enabled: !isServiceApp(app) && !isSkillApp(app),
  });
  const cloudOn = cloud.data?.enabled ?? false;
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
    if (final.status !== 'SUCCEEDED') throw new Error(final.error?.message || '编辑没有保存成功，已暂停发布。');
  };

  const publish = async () => {
    const acc = accessStateRef.current;
    if (!acc || acc.loading || acc.error) {
      setError('访问设置尚未加载完成，请稍后重试。');
      return;
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
      setEffectiveAccess({ level: finalLevel, membersCount: finalMembersCount });
      invalidate(app.id);
      setStep('done');
      toast('发布完成，线上版本与访问范围已生效。');
    } catch (err) {
      invalidate(app.id);
      setError(errorMessage(err));
      setStep('confirm');
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

  if (step === 'done' && siteUrl)
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
            线上版本
            <b>{v ? `V${v.number} · ${v.title}` : '已发布'}</b>
          </span>
          <span>
            实际生效访问范围
            <b>{formatEffectiveAccess(effectiveAccess.level, effectiveAccess.membersCount)}</b>
          </span>
        </div>
        <label className="field-label">访问地址</label>
        <div className="copy-field">
          <span>{siteUrl}</span>
          <button
            className="icon-button"
            aria-label="复制站点地址"
            onClick={async () => toast((await copyText(siteUrl)) ? '站点地址已复制。' : '请手动复制地址。')}
          >
            <Icon name="copy" />
          </button>
        </div>
        <p className="muted-note">之后恢复旧版本或继续修改，都不会影响线上内容，直到你再次发布。</p>
      </Modal>
    );

  const online = !!app.publishedVersionId && app.publishedVersionId === v?.id && !needsSave;
  return (
    <Modal
      title="让好作品，被看见。"
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
            onClick={() => void publish()}
          >
            {step === 'working' ? label || '正在发布…' : online ? '重新发布' : '确认发布'} <Icon name="arrow" />
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
          即将发布的版本
          <b>{v ? `V${v.number} · ${v.title}` : '还没有版本'}</b>
        </span>
        <span>
          当前线上
          <b>{app.siteUrl ? app.siteUrl : '尚未发布'}</b>
        </span>
      </div>
      <div className="publish-access">
        <label className="field-label">谁能打开</label>
        <AccessForm appId={app.id} compact onBlocked={onBlocked} onStateChange={onAccessStateChange} />
      </div>
      {/* 只留一条最要紧的提示 */}
      {blocked.tables.length ? (
        <p className="cc-warn" role="alert">
          <Icon name="alert" />
          {blocked.sso
            ? `表 ${blocked.tables.join('、')} 需要登录身份，请改成「员工登录」后再发布。`
            : `表 ${blocked.tables.join('、')} 需要登录身份，暂时不能公开发布。可在「云服务 → 访问与权限」把它改成「所有人可看可改」。`}
        </p>
      ) : needsSave ? (
        <p className="notice-box">有未保存的编辑，发布前会先存成新版本。</p>
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
