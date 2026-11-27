import { useState } from 'react';
import { api, errorMessage } from '../api/client';
import type { TaskStreamState } from '../api/sse';
import { isTerminal, type Task, type TaskType } from '../api/types';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { Timeline } from './Timeline';

const titles: Record<TaskType, [string, string, string]> = {
  // [进行中, 成功, 失败]
  plan: ['正在生成创造计划', '创造计划已生成', '创造计划没有生成成功'],
  generate: ['正在实现你的想法', '应用已生成', '生成没有完成'],
  modify: ['正在按你的描述修改', '修改已完成', '修改没有完成'],
  visual_save: ['正在保存可视化编辑', '编辑已保存为新版本', '编辑没有保存成功'],
  import: ['正在导入并部署', '导入部署完成', '导入部署没有完成'],
  code_save: ['正在保存代码修改', '代码已保存为新版本', '代码修改没有保存成功'],
  clone: ['正在按模板复刻', '复刻完成', '复刻没有完成'],
};

interface Props {
  stream: TaskStreamState;
  type?: TaskType;
  onNewTask: (t: Task) => void;
  /** 停止/失败任务被用户主动「放弃 / 关闭」后通知外层收起卡片并刷新状态 */
  onDismiss?: (taskId: string) => void;
  /** 点击「停止」时立即通知外层进入 stopping/CANCELLING 状态，避免重复点击 */
  onCancelRequested?: (taskId: string, nextStatus?: Task['status']) => void;
  /** 首次生成被停止且有计划文档时，点「放弃」会回到计划阶段继续调整 */
  canReturnToPlan?: boolean;
  compact?: boolean;
  /** 这次修改前先保存了 N 处手动修改（后端先存为一个版本，再做 AI 修改） */
  savedDraftFirst?: number;
}


export function TaskCard({ stream, type, onNewTask, onDismiss, onCancelRequested, canReturnToPlan = false, compact, savedDraftFirst }: Props) {
  const [busy, setBusy] = useState<null | 'cancel' | 'resume' | 'retry' | 'discard'>(null);
  const task = stream.task;
  const t = type ?? task?.type ?? 'generate';
  const status = stream.status ?? task?.status ?? 'QUEUED';
  const done = isTerminal(status);
  const ok = status === 'SUCCEEDED';
  const failed = status === 'FAILED';
  const cancelled = status === 'CANCELLED';
  const cancelling = status === 'CANCELLING';
  const title = ok
    ? titles[t][1]
    : failed
      ? titles[t][2]
      : cancelled
        ? task?.resumable
          ? '已停止 · 已保存'
          : '任务已停止'
        : cancelling
          ? '正在停止…'
          : titles[t][0];
  const progress = Math.round(stream.progress);
  const error = stream.finished?.error ?? task?.error;
  const phases = stream.phases.length ? stream.phases : stream.phaseLabel ? [stream.phaseLabel] : [];
  // 有 runner 事件（文字/思考/步骤/检查）时用交错时间线，否则退回旧的阶段列表
  const timeline = stream.timeline.length > 0 || stream.tools.length > 0;

  const act = async (kind: 'cancel' | 'resume' | 'retry' | 'discard') => {
    if (!stream.taskId) return;
    setBusy(kind);
    try {
      if (kind === 'cancel') {
        onCancelRequested?.(stream.taskId, 'CANCELLING');
        const updated = await api.cancelTask(stream.taskId);
        onCancelRequested?.(stream.taskId, updated.status);
        if (updated.status === 'COMMITTING') {
          toast('任务已进入最终保存阶段，即将完成。');
        }
      } else if (kind === 'discard') {
        await api.discardTask(stream.taskId);
        onDismiss?.(stream.taskId);
        toast(
          t === 'clone'
            ? '已关闭本次复刻提示，如需继续可随时点击「重新复刻」。'
            : canReturnToPlan && t === 'generate'
              ? '已放弃本次未完成生成，已返回「创造计划」。'
              : '已放弃未完成的任务进度。',
        );
      } else {
        const next = kind === 'resume' ? await api.resumeTask(stream.taskId) : await api.retryTask(stream.taskId);
        onNewTask(next);
      }
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className={`progress-card task-card ${failed ? 'is-failed' : ''} ${ok ? 'is-done' : ''} ${cancelled ? 'is-cancelled' : ''} ${compact ? 'compact' : ''}`}>
      <div className="progress-title">
        <span className={!done ? 'spinning' : ''}>
          <Icon name={ok ? 'check' : failed ? 'alert' : cancelled ? 'stop' : 'refresh'} />
        </span>
        <b>{title}</b>
        <small>{progress}%</small>
        <span className="task-pills">
          {!done && !cancelling ? (
            <button type="button" className="task-pill" aria-label="停止" disabled={!!busy} onClick={() => void act('cancel')}>
              <i className="pill-stop" aria-hidden="true" />
              {busy === 'cancel' ? '停止中' : '停止'}
            </button>
          ) : null}
          {cancelling ? (
            <button type="button" className="task-pill" aria-label="正在停止" disabled>
              <Icon name="refresh" className="spinning" />
              停止中
            </button>
          ) : null}
          {done && task?.resumable ? (
            <button type="button" className="task-pill accent" aria-label="继续" disabled={!!busy} onClick={() => void act('resume')}>
              <i className="pill-play" aria-hidden="true" />
              {busy === 'resume' ? '继续中' : '继续'}
            </button>
          ) : null}
          {done && task?.retryable ? (
            <button type="button" className="task-pill accent" aria-label={t === 'clone' ? '重新复刻' : '重试'} disabled={!!busy} onClick={() => void act('retry')}>
              <Icon name="refresh" />
              {busy === 'retry' ? (t === 'clone' ? '复刻中' : '重试中') : t === 'clone' ? '重新复刻' : '重试'}
            </button>
          ) : null}
          {done && (cancelled || failed) && onDismiss ? (
            <button
              type="button"
              className="task-pill ghost"
              aria-label={canReturnToPlan && t === 'generate' ? '返回修改计划' : cancelled && task?.resumable ? '放弃任务' : '关闭卡片'}
              title={
                canReturnToPlan && t === 'generate'
                  ? '放弃未完成的进度，返回调整创造计划'
                  : cancelled && task?.resumable
                    ? '放弃未完成的进度（当前稳定版本不受影响）'
                    : '关闭此任务卡片'
              }
              disabled={!!busy}
              onClick={() => void act('discard')}
            >
              {busy === 'discard'
                ? '处理中'
                : canReturnToPlan && t === 'generate'
                  ? '返回修改计划'
                  : cancelled && task?.resumable
                    ? '放弃'
                    : '关闭'}
            </button>
          ) : null}
        </span>
      </div>
      {savedDraftFirst ? (
        <div className="task-stages" aria-label="修改阶段">
          <span className={status === 'QUEUED' ? 'pending' : 'done'}>
            {status === 'QUEUED' ? '○' : '✓'} 保存手动修改（{savedDraftFirst} 处）
          </span>
          <i aria-hidden="true">→</i>
          <span className={ok ? 'done' : done ? 'pending' : 'current'}>{ok ? '✓ ' : ''}AI 修改</span>
        </div>
      ) : null}
      <div className="build-progress">
        <span style={{ width: `${progress}%` }} />
      </div>
      {timeline ? (
        <Timeline stream={stream} finished={done} />
      ) : phases.length ? (
        <ol>
          {phases.map((p, i) => {
            const current = i === phases.length - 1 && !done;
            const isDone = i < phases.length - 1 || ok;
            return (
              <li key={p + i} className={isDone ? 'done' : current ? 'current' : ''}>
                <span>
                  <Icon name={isDone ? 'check' : current ? 'spark' : failed ? 'alert' : 'more'} />
                </span>
                {p}
                {current ? <i className="running-dot" /> : null}
              </li>
            );
          })}
        </ol>
      ) : !done ? (
        <p className="task-waiting">{stream.connected ? '任务已开始，等待第一条进度…' : '正在连接任务进度…'}</p>
      ) : null}
      {failed && error ? (
        <p className="task-error" role="alert">
          {error.message}
        </p>
      ) : null}
    </div>
  );
}
