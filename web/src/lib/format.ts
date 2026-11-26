export const fmtDate = (x: string | number | Date) =>
  new Date(x).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
export const fmtTime = (x: string | number | Date) =>
  new Date(x).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
export const isToday = (x: string) => new Date(x).toDateString() === new Date().toDateString();
export const isYesterday = (x: string) => {
  const d = new Date();
  d.setDate(d.getDate() - 1);
  return new Date(x).toDateString() === d.toDateString();
};
export const dayLabel = (x: string) => (isToday(x) ? '今天' : isYesterday(x) ? '昨天' : fmtDate(x));
export const shortDay = (x: string) => (isToday(x) ? '今天' : fmtDate(x));
export const sizeLabel = (n: number) =>
  n < 1048576 ? Math.max(1, Math.round(n / 1024)) + ' KB' : (n / 1048576).toFixed(1) + ' MB';

export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // 非 HTTPS 内网环境或剪贴板权限受限时走下方降级复制
  }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-9999px';
    ta.style.left = '-9999px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.focus();
    ta.select();
    ta.setSelectionRange(0, text.length);
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  } catch {
    return false;
  }
}

/** 触发浏览器下载（同源接口，带 cookie）。 */
export function downloadUrl(url: string) {
  const link = document.createElement('a');
  link.href = url;
  link.download = '';
  document.body.append(link);
  link.click();
  link.remove();
}

/** WAI-ARIA Tabs 键盘左右/Home/End 焦点与选中联动 */
export function handleTabListKeyDown<T extends string>(
  e: { key: string; preventDefault: () => void; currentTarget: HTMLElement },
  ids: readonly T[],
  activeId: T,
  onSelect: (id: T) => void,
) {
  const idx = ids.indexOf(activeId);
  if (idx < 0 || ids.length === 0) return;
  let nextIdx = -1;
  if (e.key === 'ArrowRight') nextIdx = (idx + 1) % ids.length;
  else if (e.key === 'ArrowLeft') nextIdx = (idx - 1 + ids.length) % ids.length;
  else if (e.key === 'Home') nextIdx = 0;
  else if (e.key === 'End') nextIdx = ids.length - 1;
  if (nextIdx < 0) return;
  e.preventDefault();
  onSelect(ids[nextIdx]);
  const tabs = e.currentTarget.querySelectorAll<HTMLElement>('[role="tab"]');
  tabs[nextIdx]?.focus();
}
