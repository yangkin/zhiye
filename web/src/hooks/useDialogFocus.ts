import { useEffect, type RefObject } from 'react';

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"]),[contenteditable="true"]';

/**
 * 模态弹窗的键盘焦点：打开时焦点进弹窗，Tab / Shift+Tab 在弹窗内循环，背景暂不可操作，关闭后回到打开它的按钮。
 * 叠了多层时只管最上面一层。
 */
export function useDialogFocus(ref: RefObject<HTMLElement | null>, active = true) {
  useEffect(() => {
    const box = ref.current;
    if (!active || !box) return;
    const opener = document.activeElement as HTMLElement | null;
    const root = document.getElementById('app');
    const wasInert = root?.inert ?? false;
    if (root && !box.closest('#app')) root.inert = true;
    const items = () => [...box.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.offsetParent !== null || el === document.activeElement);
    if (!box.contains(document.activeElement)) {
      // 优先输入框，其次第一个非「关闭」按钮，都没有就聚焦弹窗本身
      const first = box.querySelector<HTMLElement>('[autofocus],input:not([disabled]),textarea:not([disabled]),select:not([disabled])')
        ?? items().find((el) => el.getAttribute('aria-label') !== '关闭') ?? box;
      if (first === box && !box.hasAttribute('tabindex')) box.tabIndex = -1;
      first.focus({ preventScroll: true });
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return;
      const dialogs = document.querySelectorAll('[aria-modal="true"]');
      if (dialogs[dialogs.length - 1] !== box) return;
      const list = items();
      if (!list.length) return e.preventDefault();
      const first = list[0];
      const last = list[list.length - 1];
      if (e.shiftKey && (document.activeElement === first || !box.contains(document.activeElement))) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (document.activeElement === last || !box.contains(document.activeElement))) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey, true);
    return () => {
      document.removeEventListener('keydown', onKey, true);
      if (root) root.inert = wasInert;
      if (opener && document.contains(opener)) opener.focus({ preventScroll: true });
    };
  }, [ref, active]);
}
