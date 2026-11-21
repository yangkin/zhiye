import { useEffect, useRef, type ReactNode } from 'react';
import { useDialogFocus } from '../hooks/useDialogFocus';
import { createPortal } from 'react-dom';
import { Icon } from '../lib/icons';

interface ModalProps {
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  onClose: () => void;
  overline?: string;
  className?: string;
}

export function Modal({ title, children, footer, wide, onClose, overline = '', className = '' }: ModalProps) {
  useEscape(onClose);
  const box = useRef<HTMLElement>(null);
  useDialogFocus(box);
  return createPortal(
    <div className="modal-backdrop" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <section ref={box} className={`modal ${wide ? 'modal-wide' : ''} ${className}`} role="dialog" aria-modal="true" aria-label={title}>
        <header>
          <div>
            {overline ? <span className="overline">{overline}</span> : null}
            <h2>{title}</h2>
          </div>
          <button className="icon-button" aria-label="关闭" onClick={onClose}>
            <Icon name="close" />
          </button>
        </header>
        <div className="modal-body">{children}</div>
        {footer ? <footer>{footer}</footer> : null}
      </section>
    </div>,
    document.body,
  );
}

/** 叠了多层弹层时，Esc 只关最上面（最后打开）的那一层 */
const escStack: { current: () => void }[] = [];

export function useEscape(handler: () => void, active = true) {
  const ref = useRef(handler);
  useEffect(() => {
    ref.current = handler;
  }, [handler]);
  useEffect(() => {
    if (!active) return;
    const me = ref;
    escStack.push(me);
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && escStack[escStack.length - 1] === me) me.current();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      const i = escStack.indexOf(me);
      if (i >= 0) escStack.splice(i, 1);
    };
  }, [active]);
}
