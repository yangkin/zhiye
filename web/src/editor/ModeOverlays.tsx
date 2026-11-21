import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';
import type { Selection } from '../api/types';
import { Icon } from '../lib/icons';

/** 选中元素的标签，如 `div.hero-actions`：tag + outerHTML 里的第一个 class。 */
export function elementLabel(s: Selection): string {
  const tag = s.tag.toLowerCase();
  const m = /^<[^>]*?\sclass\s*=\s*["']([^"']*)["']/i.exec(s.outerHTML ?? '');
  const cls = m?.[1].trim().split(/\s+/)[0];
  return cls ? `${tag}.${cls}` : tag;
}

/** iframe 在 preview-stage 内的视觉盒子与缩放比（覆盖层都以 preview-stage 为定位容器）。 */
function useFrameBox(frameRef: RefObject<HTMLIFrameElement | null>, deps: unknown[]) {
  const [box, setBox] = useState({ left: 0, top: 0, width: 0, height: 0, scaleX: 1, scaleY: 1 });
  useLayoutEffect(() => {
    const f = frameRef.current;
    const stage = (f?.closest('.preview-stage') as HTMLElement | null) ?? f?.parentElement ?? null;
    if (!f || !stage) return;
    const measure = () => {
      const fr = f.getBoundingClientRect();
      const sr = stage.getBoundingClientRect();
      const scaleX = f.clientWidth > 0 ? fr.width / f.clientWidth : 1;
      const scaleY = f.clientHeight > 0 ? fr.height / f.clientHeight : 1;
      setBox({
        left: fr.left - sr.left + f.clientLeft * scaleX,
        top: fr.top - sr.top + f.clientTop * scaleY,
        width: fr.width,
        height: fr.height,
        scaleX,
        scaleY,
      });
    };
    measure();
    window.addEventListener('resize', measure);
    const ro = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measure) : null;
    ro?.observe(stage);
    ro?.observe(f);
    return () => {
      window.removeEventListener('resize', measure);
      ro?.disconnect();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return box;
}

export function ElementTagPill({ selection, frameRef }: { selection: Selection; frameRef: RefObject<HTMLIFrameElement | null> }) {
  const box = useFrameBox(frameRef, [selection.loc]);
  const r = selection.rect;
  const rTop = r.top * box.scaleY;
  const rLeft = r.left * box.scaleX;
  const rHeight = r.height * box.scaleY;
  if (rTop + rHeight < 0 || rTop > box.height) return null;
  const top = Math.max(box.top + 2, box.top + rTop - 22);
  const left = Math.min(Math.max(box.left + 2, box.left + rLeft), box.left + box.width - 120);
  return (
    <span className="ze-element-tag" style={{ top, left }} aria-hidden="true">
      {elementLabel(selection)}
    </span>
  );
}

interface ChatBarProps {
  selection: Selection;
  frameRef: RefObject<HTMLIFrameElement | null>;
  onSend: (text: string) => Promise<boolean>;
  onParent: () => void;
  onViewSource: () => void;
  onClose: () => void;
  blocked: BusyInfo;
  onGo: (appId: string) => void;
  /** 未保存的手动修改数量：发送时会先保存 */
  pendingManual?: number;
}

/** 对话修改：锚定在选中元素下方的浮动输入条，发送后直接创建修改任务。 */
export function ChatEditBar({ selection, frameRef, onSend, onParent, onViewSource, onClose, blocked, onGo, pendingManual = 0 }: ChatBarProps) {
  const box = useFrameBox(frameRef, [selection.loc]);
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  const [sending, setSending] = useState(false);
  const [drag, setDrag] = useState<{ x: number; y: number } | null>(null);
  const barRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [size, setSize] = useState({ w: 460, h: 44 });

  useEffect(() => {
    setDrag(null);
    setError('');
    inputRef.current?.focus();
  }, [selection.loc]);

  useLayoutEffect(() => {
    const el = barRef.current;
    if (el) setSize({ w: el.offsetWidth, h: el.offsetHeight });
  }, [error, blocked.busy]);

  const r = selection.rect;
  const rTop = r.top * box.scaleY;
  const rLeft = r.left * box.scaleX;
  const rHeight = r.height * box.scaleY;
  const maxLeft = Math.max(box.left + 8, box.left + box.width - size.w - 8);
  const maxTop = Math.max(box.top + 8, box.top + box.height - size.h - 8);
  let top = box.top + rTop + rHeight + 8;
  if (top > maxTop) top = Math.max(box.top + 8, box.top + rTop - size.h - 30);
  let left = box.left + rLeft;
  if (drag) {
    left = drag.x;
    top = drag.y;
  }
  left = Math.min(Math.max(box.left + 8, left), maxLeft);
  top = Math.min(Math.max(box.top + 8, top), maxTop);

  const startDrag = (e: React.PointerEvent) => {
    e.preventDefault();
    const sx = e.clientX - left;
    const sy = e.clientY - top;
    const move = (ev: PointerEvent) => setDrag({ x: ev.clientX - sx, y: ev.clientY - sy });
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  };

  const send = async () => {
    const t = text.trim();
    if (!t || sending || blocked.busy) return;
    setSending(true);
    setError('');
    try {
      const ok = await onSend(t);
      if (ok) setText('');
    } catch (err) {
      setError(err instanceof Error ? err.message : '发送没有成功，请重试。');
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="ze-chat-bar" ref={barRef} style={{ left, top }} role="group" aria-label="对元素对话修改">
      <div className="ze-chat-bar-row">
        <span className="ze-drag" aria-label="拖动" title="拖动" onPointerDown={startDrag}>
          ⋮⋮
        </span>
        <button type="button" className="ze-icon-btn" aria-label="选择父元素" title="选择父元素" disabled={!selection.parentLoc} onClick={onParent}>
          <Icon name="expand" />
        </button>
        <input
          ref={inputRef}
          aria-label="对元素的修改描述"
          placeholder="描述你想要的修改…"
          value={text}
          maxLength={2000}
          onChange={(e) => {
            setText(e.target.value);
            if (error) setError('');
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              void send();
            } else if (e.key === 'Escape') {
              e.preventDefault();
              onClose();
            }
          }}
        />
        <button type="button" className="ze-send" aria-label="发送修改" disabled={!text.trim() || sending || blocked.busy} onClick={() => void send()}>
          {sending ? '发送中' : '发送'}
        </button>
        <button type="button" className="ze-icon-btn ze-source" aria-label="查看源码" title="查看源码" onClick={onViewSource}>
          {'</>'}
        </button>
      </div>
      {error ? (
        <p className="ze-chat-error" role="alert">
          {error}
        </p>
      ) : null}
      <BusyHint info={blocked} onGo={onGo} />
      {pendingManual > 0 && !blocked.busy ? <p className="draft-note">发送时会先保存你的手动修改（{pendingManual} 处）</p> : null}
    </div>
  );
}

/** 「当前有修改正在进行」提示；任务在其他应用时给出「去查看」。 */
export interface BusyInfo {
  /** 有进行中的写任务（任一应用） */
  busy: boolean;
  /** 任务所在应用（不是当前应用时提供） */
  otherApp?: { id: string; name: string } | null;
}

export function BusyHint({ info, onGo }: { info: BusyInfo; onGo?: (appId: string) => void }) {
  if (!info.busy) return null;
  return (
    <p className="ze-busy-hint" role="status">
      当前有修改正在进行，完成后再发送
      {info.otherApp && onGo ? (
        <button type="button" className="limit-link" onClick={() => onGo(info.otherApp!.id)}>
          去查看
        </button>
      ) : null}
    </p>
  );
}

interface ManualBarProps {
  canApply: boolean;
  busy: boolean;
  blocked: BusyInfo;
  onGo: (appId: string) => void;
  onReset: () => Promise<void>;
  /** 提交草稿；失败时抛出服务端说明 */
  onApply: () => Promise<void>;
  /** 失败时提供「改用对话修改」 */
  onSwitchToChat: () => void;
}

/** 手动微调：预览底部居中的浮动条（重置 / 应用），纯手动，不经过 AI。 */
export function ManualBottomBar({ canApply, busy, blocked, onGo, onReset, onApply, onSwitchToChat }: ManualBarProps) {
  const [error, setError] = useState('');
  const [working, setWorking] = useState(false);
  const run = async (fn: () => Promise<unknown>) => {
    setWorking(true);
    setError('');
    try {
      await fn();
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作没有完成，请重试。');
    } finally {
      setWorking(false);
    }
  };
  const disabled = working || busy;
  return (
    <div className="ze-manual-bar" role="group" aria-label="手动微调操作">
      {error ? (
        <p className="ze-chat-error" role="alert">
          {error}
          <button type="button" className="limit-link" onClick={onSwitchToChat}>
            改用对话修改
          </button>
        </p>
      ) : null}
      <BusyHint info={blocked} onGo={onGo} />
      <div className="ze-manual-bar-row">
        <span className="ze-manual-bar-label">{canApply ? '有未保存的手动修改' : '选中元素，在左侧调整样式'}</span>
        <button type="button" className="ze-reset" aria-label="重置" disabled={disabled || !canApply} onClick={() => void run(onReset)}>
          重置
        </button>
        <button type="button" className="ze-send" aria-label="应用" disabled={disabled || !canApply || blocked.busy} onClick={() => void run(onApply)}>
          {working || busy ? '保存中' : '应用'}
        </button>
      </div>
    </div>
  );
}

/** 保存成功后右下角的卡片（类似 meoo）。 */
export function SavedCard({ onClose }: { onClose: () => void }) {
  useEffect(() => {
    const t = setTimeout(onClose, 4000);
    return () => clearTimeout(t);
  }, [onClose]);
  return (
    <div className="ze-saved-card" role="status">
      <span className="ze-saved-check" aria-hidden="true">
        ✓
      </span>
      <span>
        <b>修改已保存</b>
        <small>已生成新版本</small>
      </span>
    </div>
  );
}
