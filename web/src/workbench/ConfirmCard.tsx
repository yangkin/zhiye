import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import type { ConfirmRequest } from '../api/types';
import { Icon, type IconName } from '../lib/icons';
import { toast } from '../lib/store';

const ITEM_ICONS: IconName[] = ['database', 'folder', 'code', 'lock'];

/**
 * agent 中途请求确认（request_confirm）时贴在输入框上方的卡片，和 meoo 的「是否要启用 Meoo Cloud 云服务？」一致：
 * 列出会开通的能力，「暂不启用」/「确认启用」。选完卡片消失，agent 按结果继续。
 */
/** 与服务端 TaskExecutor.CLOUD_CONFIRM_ITEMS 一致 */
export const CLOUD_CONFIRM_ITEMS = ['PostgreSQL 数据库（这个应用独立的一份表）', '文件存储（图片、附件、导出文件）', '预览页自动注入 SDK，不需要自己部署服务端'];

export function ConfirmCard({ taskId, appId, confirm }: { taskId: string; appId: string; confirm: ConfirmRequest }) {
  const qc = useQueryClient();
  const [busy, setBusy] = useState<'accept' | 'decline' | string | null>(null);
  const [answered, setAnswered] = useState<string | null>(null);
  if (confirm.state !== 'pending' || answered === confirm.id) return null;

  const answer = async (accept: boolean, choiceText?: string) => {
    setBusy(choiceText ?? (accept ? 'accept' : 'decline'));
    try {
      await api.confirmTask(taskId, confirm.id, accept, choiceText);
      setAnswered(confirm.id);
      if (confirm.kind === 'choice') {
        toast(accept && choiceText ? `已选择「${choiceText}」，AI 正在继续。` : '好的，AI 将按推荐方案继续。');
      } else if (accept && confirm.kind === 'continue') toast('好的，AI 接着做。');
      else if (!accept && confirm.kind === 'continue') toast('先停在这里，已做的部分都保留着。');
      else if (accept) {
        toast('云服务已启用，AI 会接着设计表结构。');
        void qc.invalidateQueries({ queryKey: ['cloud', appId] });
      }
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBusy(null);
    }
  };

  if (confirm.kind === 'choice' && confirm.options?.length) {
    return (
      <section className="confirm-card confirm-choice-card" role="dialog" aria-label={confirm.title ?? '请确认方案'}>
        <h3>{confirm.title ?? '需要你确认一个细节'}</h3>
        {confirm.reason ? <p className="confirm-reason">{confirm.reason}</p> : null}
        <div className="confirm-options">
          {confirm.options.map((opt, idx) => (
            <button
              key={opt}
              type="button"
              className={`confirm-option${idx === 0 ? ' recommended' : ''}`}
              disabled={busy !== null}
              onClick={() => void answer(true, opt)}
            >
              <span className="confirm-option-idx">{idx + 1}</span>
              <span className="confirm-option-label">{busy === opt ? '正在提交…' : opt}</span>
              {idx === 0 ? <em className="confirm-option-tag">推荐</em> : null}
            </button>
          ))}
        </div>
        <div className="confirm-choice-foot">
          <span>也可以直接在下方输入框回复自定义要求</span>
          <button type="button" className="text-button" disabled={busy !== null} onClick={() => void answer(false)}>
            按推荐继续
          </button>
        </div>
      </section>
    );
  }

  return (
    <ConfirmView
      kind={confirm.kind}
      title={confirm.title}
      reason={confirm.reason}
      items={confirm.items}
      busy={busy === 'accept' || busy === 'decline' ? busy : null}
      onAnswer={(accept) => void answer(accept)}
      {...(confirm.acceptLabel ? { acceptLabel: confirm.acceptLabel } : {})}
      {...(confirm.declineLabel ? { declineLabel: confirm.declineLabel } : {})}
    />
  );
}

/** 确认卡的外观：agent 发起的确认和点「共享云服务」弹出的确认共用 */
export function ConfirmView({
  kind,
  title,
  reason,
  items,
  busy,
  onAnswer,
  acceptLabel = '确认启用',
  declineLabel = '暂不启用',
}: {
  kind?: string;
  title?: string;
  reason?: string;
  items?: string[];
  busy: 'accept' | 'decline' | null;
  onAnswer: (accept: boolean) => void;
  acceptLabel?: string;
  declineLabel?: string;
}) {
  return (
    <section className="confirm-card" role="dialog" aria-label={title ?? '需要你确认'}>
      <h3>{title ?? '需要你确认'}</h3>
      {reason ? <p className="confirm-reason">{reason}</p> : null}
      {items?.length ? (
        <ul>
          {items.map((item, i) => (
            <li key={item}>
              <Icon name={kind === 'continue' ? (i === 0 ? 'history' : 'check') : (ITEM_ICONS[i] ?? 'check')} />
              <span>{item}</span>
            </li>
          ))}
        </ul>
      ) : null}
      <div className="confirm-actions">
        <button type="button" className="confirm-decline" disabled={busy !== null} onClick={() => onAnswer(false)}>
          {busy === 'decline' ? '正在提交…' : declineLabel}
        </button>
        <button type="button" className="confirm-accept" disabled={busy !== null} onClick={() => onAnswer(true)}>
          {busy === 'accept' ? '正在处理…' : acceptLabel}
        </button>
      </div>
    </section>
  );
}
