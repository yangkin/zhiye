import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import { toast } from '../lib/store';

/** 错误码：TASK_LIMIT 时提供「查看进行中的任务」入口。 */
export const errorCode = (err: unknown) => (err instanceof ApiError ? err.code : '');

export function TaskLimitLink({ onNavigate }: { onNavigate?: () => void }) {
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  return (
    <button
      type="button"
      className="limit-link"
      disabled={busy}
      onClick={async () => {
        setBusy(true);
        try {
          const apps = await api.listApps({ sort: 'updated' });
          const running = apps.find((a) => a.activeTask);
          if (!running) return toast('进行中的任务已经结束，可以再试一次。');
          onNavigate?.();
          navigate('/apps/' + running.id);
        } catch {
          toast('没能找到进行中的任务，请到应用列表查看。', 'error');
        } finally {
          setBusy(false);
        }
      }}
    >
      查看进行中的任务
    </button>
  );
}
