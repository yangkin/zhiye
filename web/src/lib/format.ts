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
    await navigator.clipboard.writeText(text);
    return true;
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
