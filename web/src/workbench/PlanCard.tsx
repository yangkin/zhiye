import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage, newIdempotencyKey } from '../api/client';
import { qk, useMeta } from '../api/queries';
import type { AppDetail, StyleId, Task } from '../api/types';
import { Icon } from '../lib/icons';
import { Markdown } from '../lib/markdown';
import { toast } from '../lib/store';

const STYLE_FALLBACK: { id: StyleId; label: string }[] = [
  { id: 'lavender', label: '柔雾紫' },
  { id: 'mint', label: '薄荷清爽' },
  { id: 'ink', label: '墨白极简' },
  { id: 'sand', label: '暖纸阅读' },
  { id: 'rose', label: '玫瑰来信' },
  { id: 'cyber', label: '赛博灵感' },
];
const STYLE_DESC: Record<string, string> = {
  lavender: '轻盈、有序，留一点灵感空间',
  mint: '自然的绿色，平静而专注',
  ink: '留白与秩序，回归内容本身',
  sand: '温润纸感，有呼吸的阅读体验',
  rose: '柔软细腻，保留生活的温度',
  cyber: '明亮碰撞，大胆表达自我',
};
const COUNTDOWN = 12;

/** 任务列表（- [ ]）渲染成勾选框符号：Markdown 清洗会去掉 input */
const docView = (md: string) => md.replace(/^(\s*[-*+])\s+\[ \]\s+/gm, '$1 ☐ ').replace(/^(\s*[-*+])\s+\[[xX]\]\s+/gm, '$1 ☑ ');

/** 计划按能力上限拆了期 / 写了做不到的部分（服务端 Capabilities.PLAN 约定的两个小节） */
const PHASED = /^##\s*分期计划/m;
const UNSUPPORTED = /^##\s*做不到的部分/m;

const shapeLabel = (kind?: string) =>
  kind === 'service'
    ? '全栈服务（后端托管）'
    : kind === 'static'
      ? '纯静态 HTML'
      : kind === 'script'
        ? '命令行脚本'
        : kind === 'skill'
          ? '技能包'
          : kind === 'vue'
            ? '网页应用 · Vue'
            : '网页应用';

/** 刚生成完 15 秒内的计划默认开启短倒计时顺滑开工；重新打开旧计划时不自动触发 */
function isFreshPlan(updatedAt?: string): boolean {
  if (!updatedAt) return false;
  const ts = Date.parse(updatedAt);
  return Number.isFinite(ts) && Date.now() - ts >= -5000 && Date.now() - ts < 15_000;
}

export function PlanCard({
  app,
  onStarted,
  updatedTick = 0,
  userInteracting = false,
}: {
  app: AppDetail;
  onStarted: (t: Task) => void;
  updatedTick?: number;
  /** 用户正在计划对话框输入或讨论，自动暂停倒计时避免打断 */
  userInteracting?: boolean;
}) {
  const qc = useQueryClient();
  const { data: meta } = useMeta();
  const styles = meta?.styles?.length ? meta.styles : STYLE_FALLBACK;
  const [tab, setTab] = useState<'plan' | 'style'>('plan');
  const [doc, setDoc] = useState(app.planDoc);
  const [editing, setEditing] = useState(false);
  const [auto, setAuto] = useState(() => isFreshPlan(app.updatedAt));
  const [count, setCount] = useState(COUNTDOWN);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');
  const [justUpdated, setJustUpdated] = useState(false);

  useEffect(() => {
    if (userInteracting || editing) setAuto(false);
  }, [userInteracting, editing]);

  useEffect(() => {
    if (!updatedTick) return;
    setTab('plan');
    setAuto(false);
    setJustUpdated(true);
    const id = setTimeout(() => setJustUpdated(false), 3200);
    return () => clearTimeout(id);
  }, [updatedTick]);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const dirtyDoc = useRef(false);
  const revRef = useRef(0);
  const savedRevRef = useRef(0);
  const latestDocRef = useRef(app.planDoc);
  const inFlightSaveRef = useRef<Promise<void> | null>(null);
  const keyRef = useRef<string | null>(null);

  useEffect(() => {
    if (!dirtyDoc.current && revRef.current === savedRevRef.current) {
      setDoc(app.planDoc);
      latestDocRef.current = app.planDoc;
    }
  }, [app.planDoc]);

  const saveDoc = useCallback(async (value: string, targetRev?: number) => {
    const rev = targetRev ?? revRef.current;
    timer.current = null;
    const p = (async () => {
      try {
        const next = await api.patchApp(app.id, { planDoc: value });
        if (rev >= savedRevRef.current) {
          savedRevRef.current = rev;
        }
        if (rev === revRef.current) {
          dirtyDoc.current = false;
        }
        qc.setQueryData(qk.app(app.id), next);
      } catch (err) {
        toast(errorMessage(err, '计划没有保存成功。'), 'error');
        throw err;
      }
    })();
    inFlightSaveRef.current = p;
    try {
      await p;
    } finally {
      if (inFlightSaveRef.current === p) inFlightSaveRef.current = null;
    }
  }, [app.id, qc]);

  const onDoc = (value: string) => {
    setDoc(value);
    latestDocRef.current = value;
    dirtyDoc.current = true;
    const nextRev = ++revRef.current;
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => void saveDoc(value, nextRev).catch(() => undefined), 600);
  };

  const patch = async (p: { style?: StyleId }) => {
    try {
      const next = await api.patchApp(app.id, p);
      qc.setQueryData(qk.app(app.id), next);
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  };

  const execute = useCallback(async () => {
    if (starting) return;
    setAuto(false);
    setStarting(true);
    setError('');
    try {
      if (timer.current) {
        clearTimeout(timer.current);
        timer.current = null;
      }
      if (inFlightSaveRef.current) {
        await inFlightSaveRef.current.catch(() => undefined);
      }
      if (dirtyDoc.current || savedRevRef.current < revRef.current) {
        await saveDoc(latestDocRef.current, revRef.current);
      }
      keyRef.current ??= newIdempotencyKey();
      const task = await api.generate(app.id, keyRef.current);
      keyRef.current = null;
      onStarted(task);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setStarting(false);
    }
  }, [app.id, onStarted, saveDoc, starting]);

  // 自动执行倒计时（原型保留的交互；到 0 时调用与「立即执行」相同的接口）
  const execRef = useRef(execute);
  execRef.current = execute;
  useEffect(() => {
    if (!auto) return;
    setCount(COUNTDOWN);
    const id = setInterval(() => {
      setCount((c) => {
        if (c <= 1) {
          clearInterval(id);
          setTimeout(() => void execRef.current(), 0);
          return 0;
        }
        return c - 1;
      });
    }, 1000);
    return () => clearInterval(id);
  }, [auto]);

  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  return (
    <div className={`plan-card${justUpdated ? ' plan-just-updated' : ''}`}>
      <div className="plan-card-heading">
        <span>
          <Icon name="file" />
          创造计划
        </span>
        <span className="plan-shape" title="由模型按需求判断的产出形态，需要的话可以在下方对话里讨论调整">
          {shapeLabel(app.projectKind)}
        </span>
        {justUpdated ? <span className="tiny-badge plan-updated-badge">已更新</span> : null}
        <span className="tiny-badge current">待确认</span>
      </div>
      <div className="plan-tabs" role="tablist">
        {(
          [
            ['plan', '确认计划'],
            ['style', '设计风格'],
          ] as const
        ).map(([p, t], i) => (
          <button
            key={p}
            className={tab === p ? 'active' : ''}
            role="tab"
            aria-selected={tab === p}
            onClick={() => {
              setTab(p);
              if (p === 'style') setAuto(false);
            }}
          >
            <small>{i + 1}</small>
            {t}
          </button>
        ))}
      </div>
      {tab === 'plan' ? (
        <div className="plan-document">
          <span>
            需求文档
            <button type="button" className="plan-doc-toggle" onClick={() => setEditing((v) => !v)}>
              <Icon name={editing ? 'check' : 'edit'} />
              {editing ? '完成' : '编辑'}
            </button>
          </span>
          {PHASED.test(doc) || UNSUPPORTED.test(doc) ? (
            <p className="plan-scope-note" role="note">
              <Icon name="spark" />
              <span>
                {PHASED.test(doc) ? '需求比较大，一次做不完：这次先做第一期，其余排在文末「分期计划」里，做完后在对话里说「继续做第二期」就能接着加。' : ''}
                {UNSUPPORTED.test(doc) ? (PHASED.test(doc) ? '另外，' : '') + '有部分需求平台做不到，已改用替代做法，见文末「做不到的部分」。' : ''}
              </span>
            </p>
          ) : null}
          {editing ? (
            <textarea id="plan-document" aria-label="编辑需求文档" autoFocus value={doc} onChange={(e) => onDoc(e.target.value)} />
          ) : (
            <div className="plan-doc-view" role="document" aria-label="需求文档" title="双击编辑" onDoubleClick={() => setEditing(true)}>
              <Markdown text={docView(doc)} />
            </div>
          )}
          {editing ? <small className="plan-doc-hint">Markdown 格式，自动保存；点「完成」查看排版效果。</small> : null}
        </div>
      ) : tab === 'style' ? (
        <div className="style-grid">
          {styles.map((s) => (
            <button key={s.id} className={app.style === s.id ? 'selected' : ''} onClick={() => void patch({ style: s.id })}>
              <span className={`style-sample ${s.id}`}>
                <i>Aa</i>
                <span />
                <b />
                {app.style === s.id ? <Icon name="check" /> : null}
              </span>
              <b>{s.label}</b>
              <small>{STYLE_DESC[s.id] ?? ''}</small>
            </button>
          ))}
        </div>
      ) : null}
      {error ? (
        <p className="inline-error" role="alert">
          {error}
        </p>
      ) : null}
      <div className="plan-card-footer">
        <label>
          <input type="checkbox" checked={auto} onChange={(e) => setAuto(e.target.checked)} />
          自动执行{auto ? <small>{count}s</small> : null}
          {auto ? (
            <button type="button" className="text-button plan-cancel" onClick={() => setAuto(false)}>
              取消
            </button>
          ) : null}
        </label>
        <button className="primary-button small" disabled={starting} onClick={() => void execute()}>
          {starting ? '正在启动…' : '立即执行'} <Icon name="arrow" />
        </button>
      </div>
    </div>
  );
}
