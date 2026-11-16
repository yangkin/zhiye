import { useState } from 'react';
import { create } from 'zustand';
import { Modal } from './Modal';

interface ConfirmOptions {
  title: string;
  message?: string;
  confirmLabel?: string;
  /** 删除、清空、关闭这类操作用红色按钮 */
  danger?: boolean;
}

interface PromptOptions extends Omit<ConfirmOptions, 'danger'> {
  placeholder?: string;
  defaultValue?: string;
  /** 输入框的无障碍名称，默认用标题 */
  label?: string;
}

interface ConfirmState {
  current: (ConfirmOptions & { input?: PromptOptions; resolve: (ok: boolean, value?: string) => void }) | null;
}

const useConfirm = create<ConfirmState>(() => ({ current: null }));

/**
 * 页面内的确认框，代替 window.confirm：
 * 浏览器原生弹窗在用户勾过「阻止此页面再弹出对话框」后会直接返回 false，按钮看起来就像点了没反应。
 */
export function confirmDialog(options: ConfirmOptions): Promise<boolean> {
  return new Promise((resolve) => {
    useConfirm.getState().current?.resolve(false);
    useConfirm.setState({ current: { ...options, resolve: (ok) => resolve(ok) } });
  });
}

/** 页面内的输入框，代替 window.prompt（原因同上）：取消返回 null */
export function promptDialog(options: PromptOptions): Promise<string | null> {
  return new Promise((resolve) => {
    useConfirm.getState().current?.resolve(false);
    useConfirm.setState({ current: { ...options, input: options, resolve: (ok, value) => resolve(ok ? (value ?? '') : null) } });
  });
}

export function ConfirmHost() {
  const current = useConfirm((s) => s.current);
  if (!current) return null;
  // key：换一个弹窗时重置输入框
  return <ConfirmView key={`${current.title}|${current.input?.defaultValue ?? ''}`} current={current} />;
}

function ConfirmView({ current }: { current: NonNullable<ConfirmState['current']> }) {
  const [value, setValue] = useState(current.input?.defaultValue ?? '');
  const close = (ok: boolean) => {
    current.resolve(ok, value);
    useConfirm.setState({ current: null });
  };
  return (
    <Modal
      title={current.title}
      overline={current.input ? '请输入' : '请确认'}
      onClose={() => close(false)}
      footer={
        <>
          <button className="subtle-button" onClick={() => close(false)}>
            取消
          </button>
          <button className={current.danger ? 'danger-button' : 'primary-button'} onClick={() => close(true)}>
            {current.confirmLabel ?? '确定'}
          </button>
        </>
      }
    >
      {current.message ? <p className="confirm-message">{current.message}</p> : null}
      {current.input ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            close(true);
          }}
        >
          <input
            className="confirm-input"
            autoFocus
            aria-label={current.input.label ?? current.title}
            placeholder={current.input.placeholder}
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
        </form>
      ) : null}
    </Modal>
  );
}
