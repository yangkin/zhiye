import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk, useMeta } from '../api/queries';
import type { SkillHubMarketItem } from '../api/types';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { Modal } from './Modal';
import { Select } from './Select';
import type { SkillRef } from './ComposerPopovers';

const MARKET_MAX_SKILLS = 5;

interface Props {
  picked: SkillRef[];
  onPick: (skill: SkillRef) => void;
  onClose: () => void;
}

export function SkillHubMarket({ picked, onPick, onClose }: Props) {
  const qc = useQueryClient();
  const installed = useMeta().data?.hubSkills ?? [];
  const [keyword, setKeyword] = useState('');
  const [query, setQuery] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [page, setPage] = useState(1);
  const [busyId, setBusyId] = useState('');
  const [actionError, setActionError] = useState('');
  const categories = useQuery({ queryKey: qk.skillhubCategories, queryFn: api.skillhubCategories, retry: false });
  const market = useQuery({
    queryKey: qk.skillhubMarket(query, categoryId, page),
    queryFn: () => api.skillhubMarket({ q: query, categoryId, page, limit: 12 }),
    retry: false,
  });
  const total = market.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / 12));

  const install = async (skill: SkillHubMarketItem) => {
    if (busyId) return;
    setBusyId(skill.skillId);
    setActionError('');
    try {
      const item = await api.skillhubInstall(skill.skillId);
      await qc.invalidateQueries({ queryKey: qk.meta });
      toast(`已安装「${item.name}」，可以在我的技能里使用。`);
      if (!picked.some((p) => p.id === item.id) && picked.length < MARKET_MAX_SKILLS) onPick({ id: item.id, name: item.name });
    } catch (error) {
      setActionError(errorMessage(error));
    } finally {
      setBusyId('');
    }
  };

  return (
    <Modal title="SkillHub 技能市场" overline="COMPANY SKILLHUB" onClose={onClose} wide className="skillhub-market">
      <form
        className="skillhub-market-tools"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(1);
          setQuery(keyword.trim());
        }}
      >
        <label className="skillhub-market-search">
          <Icon name="search" />
          <input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="搜索技能名称或说明" aria-label="搜索 SkillHub 技能" />
        </label>
        <Select
          ariaLabel="SkillHub 分类"
          value={categoryId}
          options={[{ value: '', label: '全部分类' }, ...(categories.data ?? []).map((c) => ({ value: c.id, label: c.name }))]}
          onChange={(value) => {
            setCategoryId(value);
            setPage(1);
          }}
        />
        <button type="submit" className="primary-button">搜索</button>
      </form>
      {categories.error ? <p className="notice-box">分类读取失败：{errorMessage(categories.error)}</p> : null}
      {actionError ? <p className="inline-error" role="alert">{actionError}</p> : null}
      {market.isLoading ? <p className="skillhub-market-empty">正在读取公司技能市场…</p> : null}
      {market.error ? <p className="inline-error" role="alert">{errorMessage(market.error)}</p> : null}
      {!market.isLoading && !market.error && !market.data?.items.length ? <p className="skillhub-market-empty">没有找到匹配的技能</p> : null}
      <div className="skillhub-market-list">
        {market.data?.items.map((skill) => {
          const installedItem = installed.find((item) => item.skillId === skill.skillId);
          const isInstalled = !!installedItem;
          return (
            <article key={skill.skillId} className="skillhub-market-item">
              <div className="skillhub-market-item-icon"><Icon name="book" /></div>
              <div className="skillhub-market-item-main">
                <h3>{skill.name}</h3>
                <p>{skill.summary || '这个技能还没有填写说明。'}</p>
                <small>{skill.owner || '公司同事'}{skill.version ? ` · ${skill.version}` : ''}</small>
              </div>
              <button
                type="button"
                className="subtle-button"
                disabled={isInstalled || !!busyId}
                onClick={() => void install(skill)}
              >
                {busyId === skill.skillId ? '安装中…' : isInstalled ? '已安装' : '安装并使用'}
              </button>
            </article>
          );
        })}
      </div>
      <div className="skillhub-market-footer">
        <span>共 {total} 个技能</span>
        <div>
          <button type="button" className="icon-button" aria-label="上一页" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>‹</button>
          <span>{page} / {pages}</span>
          <button type="button" className="icon-button" aria-label="下一页" disabled={page >= pages} onClick={() => setPage((p) => p + 1)}>›</button>
        </div>
      </div>
    </Modal>
  );
}
