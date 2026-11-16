import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { useInvalidateApp } from '../api/queries';
import { toast } from '../lib/store';
import { Modal } from './Modal';

export function RenameModal({ appId, name, onClose }: { appId: string; name: string; onClose: () => void }) {
  const [value, setValue] = useState(name);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const qc = useQueryClient();
  const invalidate = useInvalidateApp();
  const save = async () => {
    const n = value.trim();
    if (!n) return setError('请给应用起一个名字。');
    if (n.length > 40) return setError('名称最多 40 个字。');
    setSaving(true);
    try {
      const app = await api.patchApp(appId, { name: n });
      qc.setQueryData(['app', appId], app);
      invalidate(appId);
      toast('应用名称已更新。');
      onClose();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };
  return (
    <Modal
      title="给作品一个好名字"
      onClose={onClose}
      footer={
        <>
          <button className="subtle-button" onClick={onClose}>
            取消
          </button>
          <button className="primary-button" disabled={saving} onClick={() => void save()}>
            {saving ? '正在保存…' : '保存名称'}
          </button>
        </>
      }
    >
      <label className="field-label" htmlFor="rename-input">
        应用名称
      </label>
      <input
        id="rename-input"
        className="text-input"
        value={value}
        maxLength={40}
        autoFocus
        onChange={(e) => {
          setValue(e.target.value);
          setError('');
        }}
        onKeyDown={(e) => e.key === 'Enter' && void save()}
      />
      {error ? <p className="inline-error">{error}</p> : <small className="muted-note">1～40 个字，一个容易记住的名字，让好想法被看见。</small>}
    </Modal>
  );
}
