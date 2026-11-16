import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { createPortal } from 'react-dom';
import { api, errorMessage } from '../api/client';
import type { Asset, DataSourceInfo } from '../api/types';
import { Icon } from '../lib/icons';
import { toast, useUi } from '../lib/store';
import { useSources } from './DataSourceDialog';

interface AddMenuButtonProps {
  /** create：创造页（含导入代码）；workbench：修改已有应用（无导入代码） */
  variant?: 'create' | 'workbench';

  /** ＋ 按钮的 class（创造页 / 工作台样式不同） */
  buttonClassName?: string;
  /** 不提供时（工作台对话）上传文件不可用 */
  onAttach?: (asset: Asset) => void;
  attachedCount?: number;
  maxAttachments?: number;
  /** 弹层向上展开（输入框靠近页面底部时） */
  placement?: 'bottom' | 'top';
  /** 选择数据源（创造页：创建时绑定；工作台：立即绑定到应用） */
  onPickSource?: (s: DataSourceInfo) => void;
  pickedSourceIds?: string[];
}

const MAX_ASSET_MB = 10;

/** 上传一批文件为参考素材（选择文件、粘贴文件共用）。返回成功上传的份数。 */
export async function attachFiles(
  files: Iterable<File>,
  onAttach: (asset: Asset) => void,
  attachedCount: number,
  maxAttachments = 5,
): Promise<number> {
  const room = Math.max(0, maxAttachments - attachedCount);
  const list = Array.from(files).slice(0, room);
  if (!list.length) {
    toast(`最多添加 ${maxAttachments} 份参考素材。`, 'error');
    return 0;
  }
  let ok = 0;
  for (const file of list) {
    if (file.size > MAX_ASSET_MB * 1048576) {
      toast(`${file.name} 超过 ${MAX_ASSET_MB} MB，请压缩后再上传。`, 'error');
      continue;
    }
    try {
      onAttach(await api.uploadAsset(file));
      ok++;
    } catch (error) {
      toast(`${file.name}：${errorMessage(error)}`, 'error');
    }
  }
  if (ok) toast(`已上传 ${ok} 份参考素材。`);
  return ok;
}

/** 输入框 onPaste：剪贴板里有文件时上传成素材并接管这次粘贴；纯文本不拦截。 */
export function handleAssetPaste(
  e: { clipboardData: DataTransfer | null; preventDefault: () => void },
  onAttach: (asset: Asset) => void,
  attachedCount: number,
  maxAttachments = 5,
) {
  const files = e.clipboardData?.files;
  if (!files || files.length === 0) return;
  e.preventDefault();
  void attachFiles(files, onAttach, attachedCount, maxAttachments);
}

/** 「添加素材」＋ 按钮与下拉菜单（meoo 式 popover，无遮罩）。 */
export function AddMenuButton({
  variant = 'create',
  buttonClassName = 'icon-button add-button',
  onAttach,
  attachedCount = 0,
  maxAttachments = 5,
  placement = 'bottom',
  onPickSource,
  pickedSourceIds = [],
}: AddMenuButtonProps) {
  const [open, setOpen] = useState(false);
  const [sub, setSub] = useState<false | 'data'>(false);
  // 弹层用 fixed 定位，避免被对话面板等 overflow:hidden 的容器裁掉
  const [pos, setPos] = useState<{ left: number; top?: number; bottom?: number }>({ left: 0 });
  const place = () => {
    const r = btn.current?.getBoundingClientRect();
    if (!r) return;
    setPos(placement === 'top' ? { left: r.left, bottom: window.innerHeight - r.top + 8 } : { left: r.left, top: r.bottom + 8 });
  };
  const [uploading, setUploading] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);
  const btn = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  const setImportOpen = useUi((s) => s.setImportOpen);
  const setSourcesOpen = useUi((s) => s.setSourcesOpen);

  const close = (focusButton = false) => {
    setOpen(false);
    setSub(false);
    if (focusButton) btn.current?.focus();
  };

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      if (!wrap.current?.contains(e.target as Node) && !menu.current?.contains(e.target as Node)) close();
    };
    const onScroll = (e: Event) => {
      if (!menu.current?.contains(e.target as Node)) close();
    };
    window.addEventListener('scroll', onScroll, true);
    window.addEventListener('resize', onScroll);
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        close(true);
      }
    };
    document.addEventListener('pointerdown', onDown, true);
    document.addEventListener('keydown', onKey, true);
    requestAnimationFrame(() => menu.current?.querySelector<HTMLElement>('[role="menuitem"]:not([aria-disabled="true"])')?.focus());
    return () => {
      document.removeEventListener('pointerdown', onDown, true);
      document.removeEventListener('keydown', onKey, true);
      window.removeEventListener('scroll', onScroll, true);
      window.removeEventListener('resize', onScroll);
    };
  }, [open]);

  const upload = async (files: FileList | null) => {
    if (!files || !onAttach) return;
    setUploading(true);
    try {
      await attachFiles(files, onAttach, attachedCount, maxAttachments);
    } finally {
      setUploading(false);
    }
  };

  const onMenuKey = (e: ReactKeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(menu.current?.querySelectorAll<HTMLElement>(':scope > [role="menuitem"]') ?? []);
    const idx = items.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const step = e.key === 'ArrowDown' ? 1 : -1;
      items[(idx + step + items.length) % items.length]?.focus();
    } else if (e.key === 'Home') {
      e.preventDefault();
      items[0]?.focus();
    } else if (e.key === 'End') {
      e.preventDefault();
      items[items.length - 1]?.focus();
    } else if (e.key === 'ArrowRight' && (document.activeElement as HTMLElement)?.dataset.sub) {
      e.preventDefault();
      setSub('data');
      requestAnimationFrame(() => menu.current?.querySelector<HTMLElement>('.add-submenu [role="menuitem"]')?.focus());
    } else if (e.key === 'ArrowLeft') {
      setSub(false);
    }
  };

  return (
    <div className="add-menu-wrap" ref={wrap}>
      <button
        ref={btn}
        type="button"
        className={`${buttonClassName} ${open ? 'active' : ''}`}
        aria-label="添加素材"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => {
          if (open) return close();
          place();
          setOpen(true);
        }}
      >
        <Icon name={uploading ? 'refresh' : 'plus'} className={uploading ? 'spinning' : ''} />
      </button>
      <input
        ref={picker}
        type="file"
        multiple
        hidden
        accept="image/*,application/pdf,text/*,.md,.txt,.csv,.json,.docx,.xlsx,.pptx"
        onChange={(e) => {
          void upload(e.target.files);
          e.target.value = '';
        }}
      />
      {open
        ? createPortal(
        <div className={`add-menu add-menu-${placement}`} role="menu" aria-label="添加素材" ref={menu} onKeyDown={onMenuKey} style={pos}>
          <button
            type="button"
            role="menuitem"
            aria-label="上传文件或图片"
            aria-disabled={!onAttach || uploading}
            className="add-menu-item"
            title={onAttach ? `图片、PDF、Word/Excel/PPT 与文本，单个最多 ${MAX_ASSET_MB} MB；也可以直接粘贴到输入框` : '创建应用时可以添加参考文件'}
            onMouseEnter={() => setSub(false)}
            onClick={() => {
              if (!onAttach || uploading) return toast(onAttach ? '正在上传…' : '创建应用时可以添加参考文件。');
              close();
              picker.current?.click();
            }}
          >
            <Icon name="clip" />
            <span>
              上传文件或图片
              <small>{onAttach ? `单个最多 ${MAX_ASSET_MB} MB` : '创建应用时可添加'}</small>
            </span>
          </button>
          {variant === 'create' ? (
          <button
            type="button"
            role="menuitem"
            aria-label="导入代码并部署"
            className="add-menu-item"
            onMouseEnter={() => setSub(false)}
            onClick={() => {
              close();
              setImportOpen(true);
            }}
          >
            <Icon name="code" />
            <span>导入代码并部署</span>
          </button>
          ) : null}
          <button
            type="button"
            role="menuitem"
            aria-label="数据源"
            aria-haspopup="menu"
            aria-expanded={sub === 'data'}
            data-sub="data"
            className={`add-menu-item ${sub === 'data' ? 'active' : ''}`}
            onMouseEnter={() => setSub('data')}
            onClick={() => setSub(sub === 'data' ? false : 'data')}
          >
            <Icon name="database" />
            <span>
              数据源
              <small>只读查询公司内部接口</small>
            </span>
            {pickedSourceIds.length ? <em className="add-menu-badge">{pickedSourceIds.length}</em> : <Icon name="chevron" className="add-menu-more" />}
          </button>
          {sub === 'data' ? (
            <DataSourceMenu
              picked={pickedSourceIds}
              canPick={!!onPickSource}
              onPick={(s) => {
                close();
                onPickSource?.(s);
              }}
              onManage={() => {
                close();
                setSourcesOpen(true);
              }}
              onBack={() => {
                setSub(false);
                menu.current?.querySelector<HTMLElement>('[data-sub="data"]')?.focus();
              }}
            />
          ) : null}
        </div>
,
            document.body,
          )
        : null}
    </div>
  );
}

/** 数据源子菜单：列出工作空间可用的数据源，选中后绑定；底部进入管理。 */
export function DataSourceMenu({
  picked,
  canPick,
  onPick,
  onManage,
  onBack,
}: {
  picked: string[];
  canPick: boolean;
  onPick: (s: DataSourceInfo) => void;
  onManage: () => void;
  onBack: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const list = useSources();
  return (
    <div
      className="add-submenu"
      role="menu"
      aria-label="数据源"
      ref={ref}
      onKeyDown={(e) => {
        const items = Array.from(ref.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);
        const idx = items.indexOf(document.activeElement as HTMLElement);
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
          e.preventDefault();
          e.stopPropagation();
          items[(idx + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus();
        } else if (e.key === 'ArrowLeft') {
          e.preventDefault();
          e.stopPropagation();
          onBack();
        }
      }}
    >
      <div className="add-submenu-head">
        <b>数据源</b>
        <p>选择后，AI 会用这个接口的真实数据来做页面；凭证由平台保管。</p>
      </div>
      {list.isLoading ? <p className="add-submenu-empty">正在读取…</p> : null}
      {list.data && !list.data.length ? <p className="add-submenu-empty">还没有登记数据源。</p> : null}
      {list.data?.map((s) => {
        const on = picked.includes(s.id);
        return (
          <button
            key={s.id}
            type="button"
            role="menuitem"
            aria-disabled={!canPick || on}
            className={`add-menu-item ${on ? 'picked' : ''}`}
            title={s.description || s.key}
            onClick={() => {
              if (on) return toast('这个数据源已经添加了。');
              if (!canPick) return toast('这里暂时不能添加数据源。');
              onPick(s);
            }}
          >
            <Icon name="database" />
            <span>
              {s.name}
              <small>
                {s.key}
                {s.platform ? ' · 平台' : ''}
              </small>
            </span>
            {on ? <Icon name="check" /> : null}
          </button>
        );
      })}
      <button type="button" role="menuitem" className="add-menu-item add-menu-manage" onClick={onManage}>
        <Icon name="settings" />
        <span>登记与管理数据源</span>
      </button>
    </div>
  );
}

/** 输入框里显示已添加的数据源 */
export function SourceChips({ sources, onRemove }: { sources: { id: string; name: string }[]; onRemove: (id: string) => void }) {
  return (
    <>
      {sources.map((s) => (
        <span key={'d' + s.id} className="skill-chip source-chip">
          <Icon name="database" />
          数据源 · {s.name}
          <button type="button" aria-label={`移除数据源 ${s.name}`} onClick={() => onRemove(s.id)}>
            <Icon name="close" />
          </button>
        </span>
      ))}
    </>
  );
}
