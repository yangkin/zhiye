/**
 * 平台登录令牌（统一认证回调里的 access_token）。按平台前端的做法由前端保存，调用 /api 时放在 Authorization 头里，
 * 织页服务端用它以用户身份查询五星AI岛（模型列表、额度）。生成的应用在另一个源（预览 / 发布域名）上，读不到这里。
 */
const KEY = 'zhiye-platform-token';

export const getPlatformToken = (): string | null => {
  try {
    return localStorage.getItem(KEY);
  } catch {
    return null;
  }
};

export const setPlatformToken = (token: string) => {
  try {
    localStorage.setItem(KEY, token);
  } catch {
    /* 隐私模式等存不了：只是看不到额度，不影响使用 */
  }
};

export const clearPlatformToken = () => {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* ignore */
  }
};
