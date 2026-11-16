import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon, type IconName } from '../lib/icons';

export function EmptyState({
  title,
  desc,
  canCreate,
  icon = 'search',
  action,
}: {
  title: string;
  desc: string;
  canCreate?: boolean;
  icon?: IconName;
  action?: ReactNode;
}) {
  const navigate = useNavigate();
  return (
    <div className="empty-state">
      <span>
        <Icon name={icon} />
      </span>
      <h3>{title}</h3>
      <p>{desc}</p>
      {canCreate ? (
        <button className="subtle-button" onClick={() => navigate('/create')}>
          <Icon name="plus" />
          去创造
        </button>
      ) : null}
      {action}
    </div>
  );
}

export function LoadingState({ label = '正在加载…' }: { label?: string }) {
  return (
    <div className="empty-state loading-state" role="status">
      <span className="spinning">
        <Icon name="refresh" />
      </span>
      <p>{label}</p>
    </div>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="empty-state error-state" role="alert">
      <span>
        <Icon name="alert" />
      </span>
      <h3>暂时没能加载</h3>
      <p>{message}</p>
      {onRetry ? (
        <button className="subtle-button" onClick={onRetry}>
          <Icon name="refresh" />
          重新加载
        </button>
      ) : null}
    </div>
  );
}
