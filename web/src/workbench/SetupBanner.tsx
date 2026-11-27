import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type { AppDetail, AppSetupCheckItem } from '../api/types';
import { Icon } from '../lib/icons';
import { toast, useChat, useUi } from '../lib/store';

interface SetupBannerProps {
  app: AppDetail;
  busy: boolean;
  onOpenService: () => void;
  onSendPrompt: (prompt: string) => Promise<void>;
}

const DISMISS_PREFIX = 'zhiye.setupBanner.dismissed.';

/**
 * 复刻自灵感社区的应用配置指引条：
 * 实时检查当前工作空间是否缺失原模板依赖的 AGW 数据源、私有飞书连接器或单体服务环境变量，
 * 提供一键绑定 / 跳转生态中心 / 跳转服务托管 / 让 AI 替换连接器的闭环入口；全部就绪后自动隐藏。
 */
export function SetupBanner({ app, busy, onOpenService, onSendPrompt }: SetupBannerProps) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const sourcesOpen = useUi((s) => s.sourcesOpen);
  const setSourcesOpen = useUi((s) => s.setSourcesOpen);

  const [dismissed, setDismissed] = useState<boolean>(() => {
    try {
      return localStorage.getItem(DISMISS_PREFIX + app.id) === '1';
    } catch {
      return false;
    }
  });
  const [expanded, setExpanded] = useState(true);
  const [bindingKey, setBindingKey] = useState<string | null>(null);
  const [rebindingKey, setRebindingKey] = useState<string | null>(null);

  useEffect(() => {
    try {
      setDismissed(localStorage.getItem(DISMISS_PREFIX + app.id) === '1');
    } catch {
      setDismissed(false);
    }
  }, [app.id]);

  // 数据源弹窗关闭后自动重新体检，方便用户导入 AGW 接口后一键绑定
  useEffect(() => {
    if (!sourcesOpen) {
      void qc.invalidateQueries({ queryKey: qk.appSetupCheck(app.id) });
    }
  }, [sourcesOpen, app.id, qc]);

  const checkQ = useQuery({
    queryKey: qk.appSetupCheck(app.id),
    queryFn: () => api.appSetupCheck(app.id),
    enabled: app.phase === 'ready' && !dismissed,
    staleTime: 15_000,
    refetchOnWindowFocus: true,
  });

  const data = checkQ.data;
  if (dismissed || !data?.needsSetup) return null;

  const pendingItems = data.items.filter((i) => i.status === 'missing');
  const readyCount = data.summary.ready;
  if (!pendingItems.length) return null;

  const dismiss = () => {
    setDismissed(true);
    try {
      localStorage.setItem(DISMISS_PREFIX + app.id, '1');
    } catch {
      /* ignore */
    }
  };

  const bindSource = async (item: AppSetupCheckItem) => {
    if (!item.matchingSourceId || bindingKey) return;
    const k = item.key || item.matchingSourceId;
    setBindingKey(k);
    try {
      await api.bindSource(app.id, item.matchingSourceId);
      toast(`已绑定数据源「${item.matchingSourceName || item.name}」。`);
      await Promise.all([
        qc.invalidateQueries({ queryKey: qk.appSetupCheck(app.id) }),
        qc.invalidateQueries({ queryKey: qk.appSources(app.id) }),
      ]);
    } catch (err) {
      toast(errorMessage(err), 'error');
    } finally {
      setBindingKey(null);
    }
  };

  const rebindConnectorWithAi = async (item: AppSetupCheckItem) => {
    const targetName = item.matchingConnectorName || item.kindLabel || '飞书连接器';
    const targetId = item.matchingConnectorId;
    const oldId = item.sourceConnectorId;
    const prompt = oldId && targetId
      ? `这个应用是从灵感社区复刻的，原代码里引用了原作者的私有连接器 ID \`${oldId}\`。请将其全部替换为我当前工作空间可用的${item.kindLabel || '飞书连接器'}「${targetName}」（连接器 ID：\`${targetId}\`），不要改动原有页面和功能，完成后自检确认。`
      : `这个应用是从灵感社区复刻的，请帮我对接我当前工作空间可用的${item.kindLabel || '飞书连接器'}「${targetName}」${targetId ? `（连接器 ID：\`${targetId}\`）` : ''}，保持原有业务逻辑不变。`;
    if (busy) {
      useChat.getState().setDraft(prompt);
      toast('当前有任务进行中，已将替换连接器的指令填入左侧输入框。');
      return;
    }
    const k = oldId || item.kind || 'conn';
    setRebindingKey(k);
    try {
      await onSendPrompt(prompt);
    } finally {
      setRebindingKey(null);
    }
  };

  const categoryTag = (item: AppSetupCheckItem) => {
    if (item.category === 'dataSource') return 'AGW 数据源';
    if (item.category === 'connector') return item.kindLabel || '飞书连接器';
    return '环境变量';
  };

  return (
    <div className="setup-check-banner" role="region" aria-label="复刻模板配置指引">
      <div className="setup-check-head">
        <span className="setup-check-title">
          <Icon name="settings" />
          <b>复刻模板配置待补齐（剩余 {pendingItems.length} 项{readyCount > 0 ? ` · 已就绪 ${readyCount} 项` : ''}）</b>
          <span className="setup-check-sub">原作者凭证、密钥与业务数据已自动隔离，请对接你自己的配置</span>
        </span>
        <div className="setup-check-head-actions">
          <button type="button" className="setup-check-link" onClick={() => setExpanded((v) => !v)}>
            {expanded ? '收起' : '展开详情'}
          </button>
          <button type="button" className="setup-check-link" onClick={dismiss} aria-label="暂不提醒">
            暂不提醒
          </button>
        </div>
      </div>
      {expanded ? (
        <ul className="setup-check-list">
          {pendingItems.map((item, idx) => {
            const rowKey = `${item.category}:${item.key || item.sourceConnectorId || item.kind || idx}`;
            return (
              <li key={rowKey} className="setup-check-row">
                <span className={`setup-check-tag is-${item.category}`}>{categoryTag(item)}</span>
                <div className="setup-check-info">
                  <div className="setup-check-name">
                    <b>{item.name}</b>
                    {item.key && item.key !== item.name ? <code>{item.key}</code> : null}
                  </div>
                  <small>{item.hint}</small>
                </div>
                <div className="setup-check-cta">
                  {item.action === 'bind_source' && item.matchingSourceId ? (
                    <button
                      type="button"
                      className="setup-check-btn primary"
                      disabled={bindingKey === (item.key || item.matchingSourceId)}
                      onClick={() => void bindSource(item)}
                    >
                      {bindingKey === (item.key || item.matchingSourceId) ? '绑定中…' : '一键绑定'}
                    </button>
                  ) : null}
                  {item.action === 'open_sources' ? (
                    <button type="button" className="setup-check-btn" onClick={() => setSourcesOpen(true)}>
                      去导入 AGW 数据源
                    </button>
                  ) : null}
                  {item.action === 'rebind_connector' ? (
                    <>
                      <button
                        type="button"
                        className="setup-check-btn primary"
                        disabled={rebindingKey === (item.sourceConnectorId || item.kind || 'conn')}
                        onClick={() => void rebindConnectorWithAi(item)}
                      >
                        {rebindingKey === (item.sourceConnectorId || item.kind || 'conn') ? '提交中…' : '让 AI 替换成我的'}
                      </button>
                      <button type="button" className="setup-check-btn" onClick={() => navigate('/ecosystem')}>
                        生态中心
                      </button>
                    </>
                  ) : null}
                  {item.action === 'open_ecosystem' ? (
                    <button type="button" className="setup-check-btn" onClick={() => navigate('/ecosystem')}>
                      去生态中心配置
                    </button>
                  ) : null}
                  {item.action === 'open_service' ? (
                    <button type="button" className="setup-check-btn primary" onClick={onOpenService}>
                      去填写环境变量
                    </button>
                  ) : null}
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}
    </div>
  );
}
