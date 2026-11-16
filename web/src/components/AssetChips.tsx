import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { Icon } from '../lib/icons';

export interface ChatAsset {
  id: string;
  name: string;
  mime?: string;
}

/** 消息上附带的素材：图片显示缩略图、点开大图预览；其他文件在新标签页打开（Office 文档会下载）。 */
export function AssetChips({ assets }: { assets: ChatAsset[] }) {
  const [viewing, setViewing] = useState<ChatAsset | null>(null);

  useEffect(() => {
    if (!viewing) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setViewing(null);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [viewing]);

  if (!assets.length) return null;
  return (
    <>
      <span className="msg-skills msg-assets">
        {assets.map((a) =>
          a.mime?.startsWith('image/') ? (
            <button key={a.id} type="button" className="msg-asset-img" title={a.name} onClick={() => setViewing(a)}>
              <img src={api.assetContentUrl(a.id)} alt={a.name} loading="lazy" />
            </button>
          ) : (
            <a key={a.id} className="skill-chip" href={api.assetContentUrl(a.id)} target="_blank" rel="noreferrer" title={a.name}>
              <Icon name="clip" />
              {a.name}
            </a>
          ),
        )}
      </span>
      {viewing ? (
        <div className="asset-lightbox" role="dialog" aria-label={`预览 ${viewing.name}`} onClick={() => setViewing(null)}>
          <img src={api.assetContentUrl(viewing.id)} alt={viewing.name} />
        </div>
      ) : null}
    </>
  );
}
