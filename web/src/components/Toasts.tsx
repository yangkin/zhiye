import { dismissToast, useUi } from '../lib/store';
import { Icon } from '../lib/icons';

export function Toasts() {
  const toasts = useUi((s) => s.toasts);
  return (
    <div id="toasts" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.tone === 'error' ? 'toast-error' : ''}`}>
          <Icon name={t.tone === 'error' ? 'alert' : 'check'} />
          <span>{t.message}</span>
          {t.action ? (
            <button
              type="button"
              className="toast-action"
              onClick={() => {
                t.action!.run();
                dismissToast(t.id);
              }}
            >
              {t.action.label}
            </button>
          ) : null}
        </div>
      ))}
    </div>
  );
}
