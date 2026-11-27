import { useEffect, type RefObject } from 'react';

const FOCUSABLE =
  'a[href],button:not([disabled]):not([hidden]),input:not([disabled]):not([hidden]):not([type="hidden"]),select:not([disabled]):not([hidden]),textarea:not([disabled]):not([hidden]),[tabindex]:not([tabindex="-1"]):not([hidden]),[contenteditable="true"]';

const INPUT_PREFERRED =
  '[autofocus]:not([disabled]):not([hidden]),input:not([disabled]):not([hidden]):not([type="hidden"]):not([type="file"]),textarea:not([disabled]):not([hidden]),select:not([disabled]):not([hidden])';

let scrollLockCount = 0;
let savedOverflow = '';
let savedPaddingRight = '';

function acquireScrollLock() {
  if (scrollLockCount === 0) {
    const scrollbar = window.innerWidth - document.documentElement.clientWidth;
    savedOverflow = document.body.style.overflow;
    savedPaddingRight = document.body.style.paddingRight;
    document.body.style.overflow = 'hidden';
    if (scrollbar > 0) {
      const currentPad = Number.parseFloat(getComputedStyle(document.body).paddingRight) || 0;
      document.body.style.paddingRight = `${currentPad + scrollbar}px`;
    }
  }
  scrollLockCount++;
}

function releaseScrollLock() {
  scrollLockCount = Math.max(0, scrollLockCount - 1);
  if (scrollLockCount === 0) {
    document.body.style.overflow = savedOverflow;
    document.body.style.paddingRight = savedPaddingRight;
  }
}

const isVisible = (el: HTMLElement) => !el.hidden && el.offsetParent !== null;

/**
 * 模态弹窗的键盘焦点与背景滚动锁定：打开时锁定页面滚动、焦点进弹窗，Tab / Shift+Tab 在弹窗内循环，背景暂不可操作，关闭后恢复滚动并回到触发按钮。
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
    acquireScrollLock();
    const items = () =>
      [...box.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => isVisible(el) || el === document.activeElement);
    if (!box.contains(document.activeElement)) {
      // 优先可见输入框，其次非关闭按钮，否则聚焦弹窗本身（避免隐藏的 file input 吞掉焦点）
      const preferred = [...box.querySelectorAll<HTMLElement>(INPUT_PREFERRED)].find(isVisible);
      const fallback = items().find((el) => !(el.getAttribute('aria-label') || '').startsWith('关闭'));
      const first = preferred ?? fallback ?? box;
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
      releaseScrollLock();
      if (root) root.inert = wasInert;
      if (opener && document.contains(opener)) opener.focus({ preventScroll: true });
    };
  }, [ref, active]);
}
