import { useEffect, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '../lib/icons';

/** X-APM 风格右侧滑出详情 / 编辑抽屉：保留左侧台账上下文，顶部支持迷你趋势区，底部固定审计留痕栏 */
export function DetailDrawer({
  open,
  title,
  badge,
  sub,
  width = 620,
  onClose,
  footerHint,
  footer,
  children,
}: {
  open: boolean;
  title: ReactNode;
  badge?: ReactNode;
  sub?: ReactNode;
  width?: number;
  onClose: () => void;
  footerHint?: ReactNode;
  footer?: ReactNode;
  children: ReactNode;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  return createPortal(
    <div
      className="xa-drawer-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <aside
        className="xa-drawer"
        role="dialog"
        aria-modal="true"
        style={{ width: `min(${width}px, 94vw)` }}
      >
        <header className="xa-drawer-head">
          <div className="xa-drawer-head-copy">
            <div className="xa-drawer-title-row">
              <h3>{title}</h3>
              {badge}
            </div>
            {sub ? <p className="xa-drawer-sub">{sub}</p> : null}
          </div>
          <button type="button" className="icon-button" aria-label="关闭抽屉" onClick={onClose}>
            <Icon name="close" />
          </button>
        </header>
        <div className="xa-drawer-body">{children}</div>
        {footer || footerHint ? (
          <footer className="xa-drawer-foot">
            <div className="xa-drawer-foot-hint">{footerHint}</div>
            <div className="xa-drawer-foot-actions">{footer}</div>
          </footer>
        ) : null}
      </aside>
    </div>,
    document.body,
  );
}
