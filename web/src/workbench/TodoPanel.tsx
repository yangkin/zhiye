import { useState } from 'react';
import type { TodoItem } from '../api/types';

interface Props {
  todos: TodoItem[];
  /** 任务是否还在进行（结束后折叠成一行汇总） */
  running: boolean;
}

/**
 * 生成中的「待办」面板：贴在输入框上方，显示当前第几项、总进度，可折叠。
 * 数据来自 runner 的 todo 事件（tool: todo_write），不是从 agent 文字里猜的。
 */
export function TodoPanel({ todos, running }: Props) {
  // 和 meoo 一样默认收成输入框上方的一颗胶囊（当前项 + n/m），点开看完整清单；工作台左栏窄，摊开会挡住消息
  const [open, setOpen] = useState(false);
  const done = todos.filter((t) => t.status === 'completed').length;
  const total = todos.length;
  const current = todos.find((t) => t.status === 'in_progress') ?? todos.find((t) => t.status === 'pending');

  // 只在进行中显示：结束后任务卡片里已有文件改动汇总，面板不再占位
  if (total === 0 || !running) return null;

  const header = open ? `待办 · ${current?.content ?? '正在收尾'}` : `待办 · ${current ? truncate(current.content, 18) : '正在收尾'}`;

  return (
    <div className={'todo-panel' + (open ? ' open' : '')} aria-label="待办">
      <button type="button" className="todo-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        <span className="todo-check" aria-hidden="true">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6">
            <rect x="2.5" y="2.5" width="11" height="11" rx="3" />
            <path d="M5.5 8l1.8 1.8L10.6 6.3" />
          </svg>
        </span>
        <span className="todo-title" title={current?.content}>{header}</span>
        <span className="todo-count">{done}/{total}</span>
        <span className={'todo-caret' + (open ? ' up' : '')} aria-hidden="true">
          <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" strokeWidth="1.8">
            <path d="M4 10l4-4 4 4" />
          </svg>
        </span>
      </button>
      {open ? (
        <ul className="todo-list">
          {todos.map((t, i) => (
            <li key={i} className={'todo-item ' + t.status}>
              <span className="todo-mark" aria-hidden="true">
                {t.status === 'completed' ? (
                  <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.9">
                    <path d="M3.5 8.4l3 3 6-6.4" />
                  </svg>
                ) : t.status === 'in_progress' ? (
                  <i className="todo-spin" />
                ) : (
                  <i className="todo-dot" />
                )}
              </span>
              <span className="todo-text">{t.content}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

function truncate(s: string, n: number) {
  return s.length > n ? s.slice(0, n) + '…' : s;
}