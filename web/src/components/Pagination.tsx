import { useEffect, useMemo, useState } from 'react';
import { Select } from './Select';

export interface PaginationProps {
  /** 当前页（从 1 开始） */
  page: number;
  /** 每页条数 */
  pageSize: number;
  /** 总记录数 */
  total: number;
  onPageChange: (page: number) => void;
  onPageSizeChange?: (pageSize: number) => void;
  pageSizeOptions?: number[];
  /** 窄列（如日志左侧列表）使用紧凑排布 */
  compact?: boolean;
  /** 仅 1 页且无条数切换需求时是否仍显示总数条 */
  alwaysShowSummary?: boolean;
}

/** 生成带省略号的页码序列，如 [1, '...', 4, 5, 6, '...', 16] */
function buildPages(page: number, totalPages: number, compact: boolean): (number | 'ellipsis-l' | 'ellipsis-r')[] {
  if (totalPages <= (compact ? 5 : 7)) {
    return Array.from({ length: totalPages }, (_, i) => i + 1);
  }
  const sibling = compact ? 0 : 1;
  const left = Math.max(2, page - sibling);
  const right = Math.min(totalPages - 1, page + sibling);
  const out: (number | 'ellipsis-l' | 'ellipsis-r')[] = [1];
  if (left > 2) out.push('ellipsis-l');
  for (let i = left; i <= right; i++) out.push(i);
  if (right < totalPages - 1) out.push('ellipsis-r');
  out.push(totalPages);
  return out;
}

export function Pagination({
  page,
  pageSize,
  total,
  onPageChange,
  onPageSizeChange,
  pageSizeOptions = [10, 20, 50],
  compact = false,
  alwaysShowSummary = false,
}: PaginationProps) {
  const totalPages = Math.max(1, Math.ceil(total / Math.max(1, pageSize)));
  const safePage = Math.min(Math.max(1, page), totalPages);
  const [jumpText, setJumpText] = useState('');

  useEffect(() => {
    setJumpText('');
  }, [safePage]);

  if (total <= 0) return null;
  const minOpt = pageSizeOptions.length ? Math.min(...pageSizeOptions) : pageSize;
  if (totalPages <= 1 && !alwaysShowSummary && (total <= minOpt || !onPageSizeChange)) return null;

  const start = (safePage - 1) * pageSize + 1;
  const end = Math.min(total, safePage * pageSize);
  const pages = buildPages(safePage, totalPages, compact);

  const handleJump = () => {
    const n = Number.parseInt(jumpText.trim(), 10);
    if (!Number.isFinite(n)) return;
    onPageChange(Math.min(Math.max(1, n), totalPages));
    setJumpText('');
  };

  return (
    <nav className={`ui-pager ${compact ? 'is-compact' : ''}`} aria-label="分页导航">
      <div className="ui-pager-info">
        <span>
          显示 <b>{start}–{end}</b> / 共 <b>{total.toLocaleString('zh-CN')}</b> 条
        </span>
      </div>

      <div className="ui-pager-controls">
        {totalPages > 1 ? (
          <div className="ui-pager-pages">
            <button
              type="button"
              className="ui-pager-btn"
              disabled={safePage <= 1}
              aria-label="上一页"
              onClick={() => onPageChange(safePage - 1)}
            >
              ‹
            </button>
            {pages.map((p) =>
              typeof p === 'string' ? (
                <span key={p} className="ui-pager-ellipsis" aria-hidden="true">
                  …
                </span>
              ) : (
                <button
                  key={p}
                  type="button"
                  className={`ui-pager-btn ${p === safePage ? 'active' : ''}`}
                  aria-current={p === safePage ? 'page' : undefined}
                  onClick={() => onPageChange(p)}
                >
                  {p}
                </button>
              ),
            )}
            <button
              type="button"
              className="ui-pager-btn"
              disabled={safePage >= totalPages}
              aria-label="下一页"
              onClick={() => onPageChange(safePage + 1)}
            >
              ›
            </button>
          </div>
        ) : null}

        {!compact && onPageSizeChange && total > Math.min(...pageSizeOptions) ? (
          <div className="ui-pager-size">
            <Select
              ariaLabel="每页显示条数"
              value={String(pageSize)}
              options={pageSizeOptions.map((n) => ({ value: String(n), label: `${n} 条/页` }))}
              onChange={(v) => {
                const next = Number(v);
                if (Number.isFinite(next) && next > 0) {
                  onPageSizeChange(next);
                  onPageChange(1);
                }
              }}
            />
          </div>
        ) : null}

        {!compact && totalPages > 5 ? (
          <form
            className="ui-pager-jump"
            onSubmit={(e) => {
              e.preventDefault();
              handleJump();
            }}
          >
            <span>跳至</span>
            <input
              type="text"
              inputMode="numeric"
              aria-label="跳转页码"
              placeholder={String(safePage)}
              value={jumpText}
              onChange={(e) => setJumpText(e.target.value.replace(/[^\d]/g, ''))}
            />
            <span>页</span>
          </form>
        ) : null}
      </div>
    </nav>
  );
}

/** 客户端列表通用分页 Hook：源数据或筛选条件变化时自动修正越界页码 */
export function useClientPagination<T>(items: readonly T[], initialPageSize = 10, resetKey?: unknown) {
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(initialPageSize);

  useEffect(() => {
    setPage(1);
  }, [resetKey]);

  const total = items.length;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(page, totalPages);

  useEffect(() => {
    if (page !== safePage) setPage(safePage);
  }, [page, safePage]);

  const pageItems = useMemo(() => {
    const start = (safePage - 1) * pageSize;
    return items.slice(start, start + pageSize);
  }, [items, safePage, pageSize]);

  return {
    page: safePage,
    pageSize,
    total,
    totalPages,
    pageItems,
    setPage,
    setPageSize,
  };
}
