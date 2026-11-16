/** 没发送、没保存的内容存在浏览器本地：切换应用、刷新页面都能找回。发送或保存成功后清掉。 */
const PREFIX = 'zhiye.draft.';

export function loadDraft<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

/** value 为空（null / 空串）时删除 */
export function saveDraft(key: string, value: unknown) {
  try {
    if (value === null || value === '' || value === undefined) localStorage.removeItem(PREFIX + key);
    else localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch {
    // 存储已满或被禁用：只是少了草稿恢复，不影响编辑
  }
}
