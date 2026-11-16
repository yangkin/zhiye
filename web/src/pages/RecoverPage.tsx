import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';

export function RecoverPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [error, setError] = useState('');
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const token = decodeURIComponent(window.location.hash.replace(/^#/, '')).trim();
    if (!token) {
      setError('恢复链接不完整，请复制完整的链接后重新打开。');
      return;
    }
    // 恢复后再确认一次：同时发出的其他请求可能带回新的匿名 cookie 盖掉恢复结果，不对就再恢复一次（这时浏览器已有 cookie，不会再被盖）
    const recover = async () => {
      const { id } = await api.recover(token);
      if ((await api.workspace()).id !== id) await api.recover(token);
    };
    recover()
      .then(() => {
        // 清掉地址栏里的令牌，避免被再次分享
        window.history.replaceState(null, '', '/recover');
        qc.clear();
        toast('已回到你的工作空间。');
        navigate('/apps', { replace: true });
      })
      .catch((err) => setError(errorMessage(err, '恢复链接无效或已过期。')));
  }, [navigate, qc]);

  return (
    <div className="recover-page">
      <img className="brand-logo" src="/brand.svg" alt="" />
      {error ? (
        <>
          <h1>没能恢复工作空间</h1>
          <p className="inline-error" role="alert">
            {error}
          </p>
          <button className="primary-button" onClick={() => navigate('/create')}>
            回到织页 <Icon name="arrow" />
          </button>
        </>
      ) : (
        <>
          <h1>正在恢复你的工作空间…</h1>
          <p>马上就好。</p>
        </>
      )}
    </div>
  );
}
