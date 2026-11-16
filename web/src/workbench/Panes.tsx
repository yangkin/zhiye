import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../api/client';
import { toast } from '../lib/store';
import { qk } from '../api/queries';
import { useChat } from '../lib/store';
import { Icon } from '../lib/icons';
import { sizeLabel } from '../lib/format';
import { ErrorState, LoadingState, EmptyState } from '../components/EmptyState';
import { CloudConsole } from './CloudConsole';
import { confirmDialog } from '../components/ConfirmDialog';

const DOC_EXT = /^(md|markdown|txt|pdf|csv|xlsx?|docx?|pptx?|py|sh|sql)$/i;
const isDoc = (path: string) => !path.startsWith('src/') && !path.startsWith('public/') && DOC_EXT.test(path.split('.').pop() ?? '');

export function FilesPane({
  versionId,
  assets = [],
  onDownloadPage,
  onExport,
}: {
  versionId: string | null;
  /** 从这个应用上传的素材（图片、附件），和 meoo 的「文件」一样指上传/产出的文件，而不是源码 */
  assets?: { id: string; name: string; size?: number }[];
  onDownloadPage: () => void;
  onExport: () => void;
}) {
  const files = useQuery({
    queryKey: qk.files(versionId ?? ''),
    queryFn: () => api.versionFiles(versionId!),
    enabled: !!versionId,
  });
  const list = (files.data ?? []).filter((f) => isDoc(f.path));
  const total = list.reduce((n, f) => n + f.size, 0) + assets.reduce((n, a) => n + (a.size ?? 0), 0);
  return (
    <>
      <div className="studio-pane-title">
        <Icon name="clip" />
        <span>文件</span>
        <small>{list.length + assets.length ? `${list.length + assets.length} 个文件 · ${sizeLabel(total)}` : '暂无文件'}</small>
      </div>
      <div className="studio-files">
        {!versionId && !assets.length ? (
          <EmptyState icon="folder" title="还没有文件" desc="上传的素材和 AI 生成的文件会出现在这里；源码在「代码」里。" />
        ) : files.isLoading ? (
          <LoadingState label="正在读取文件列表…" />
        ) : files.error ? (
          <ErrorState message={errorMessage(files.error)} onRetry={() => files.refetch()} />
        ) : (
          <>
            <div className="studio-file-labels">
              <span>名称</span>
              <span>类型</span>
              <span>大小</span>
            </div>
            {assets.map((a) => (
              <div key={a.id} className="studio-file-row">
                <span>
                  <Icon name="clip" />
                  <b title={a.name}>{a.name}</b>
                </span>
                <small>上传</small>
                <small>{sizeLabel(a.size ?? 0)}</small>
              </div>
            ))}
            {list.map((f) => (
              <div key={f.path} className="studio-file-row">
                <span>
                  <Icon name={/\.html?$/i.test(f.path) ? 'web' : 'file'} />
                  <b title={f.path}>{f.path}</b>
                </span>
                <small>生成 · {(f.path.split('.').pop() ?? '').toUpperCase() || '文件'}</small>
                <small>{sizeLabel(f.size)}</small>
              </div>
            ))}
            <div className="studio-file-note">这里是上传的素材和 AI 产生的文档、脚本；源码在「代码」里。下载页面得到一个可直接打开的单文件 HTML，导出源码得到完整项目的 zip。</div>
            <div className="studio-file-actions">
              <button className="studio-outline" onClick={onDownloadPage}>
                <Icon name="download" />
                下载页面
              </button>
              <button className="studio-outline" onClick={onExport}>
                <Icon name="code" />
                导出源码
              </button>
            </div>
          </>
        )}
      </div>
    </>
  );
}

export function CloudPane({ appId }: { appId: string }) {
  const qc = useQueryClient();
  const status = useQuery({ queryKey: ['cloud', appId], queryFn: () => api.cloudStatus(appId), enabled: !!appId });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const enabled = status.data?.enabled ?? false;

  const act = async (enable: boolean) => {
    setBusy(true);
    setErr('');
    try {
      if (enable) {
        await api.cloudEnable(appId);
        toast('云服务已启用，预览页里就能读写数据了。');
        await qc.invalidateQueries({ queryKey: ['cloud', appId] });
        void qc.invalidateQueries({ queryKey: ['apps'] });
      } else {
        await api.cloudDisable(appId);
        toast('已关闭，数据仍保留，重新启用就能继续用。');
        await qc.invalidateQueries({ queryKey: ['cloud', appId] });
        void qc.invalidateQueries({ queryKey: ['apps'] });
      }
    } catch (e) {
      setErr(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  if (enabled)
    return (
      <>
        <div className="studio-pane-title">
          <Icon name="cloud" />
          <span>云服务</span>
          <small>已启用 · 数据控制台</small>
        </div>
        {err ? (
          <p className="inline-error" role="alert">
            {err}
          </p>
        ) : null}
        <CloudConsole
          appId={appId}
          disabling={busy}
          onDisable={async () => {
            const ok = await confirmDialog({
              title: '关闭云服务？',
              message: '关闭后，应用里读写数据的功能会报错，员工登录等云服务能力也会停用。\n数据表和数据都会保留，之后重新启用就能接着用。',
              confirmLabel: '关闭云服务',
              danger: true,
            });
            if (ok) void act(false);
          }}
        />
      </>
    );

  return (
    <>
      <div className="studio-pane-title">
        <Icon name="cloud" />
        <span>云服务</span>
        <small>未启用</small>
      </div>
      <div className="studio-cloud">
        <div className="studio-cloud-orb">
          <Icon name="cloud" />
        </div>
        <h2>让这个应用真正存数据</h2>
        <p>
          点下面的按钮，对话框上方会弹出确认卡；确认后立即启用，AI 接着按这个应用的数据设计表结构和数据权限。预览和发布后的站点都能直接读写自己独立的一份数据，不需要自己部署服务端。
        </p>
        <div className="studio-cloud-grid">
          <article>
            <Icon name="grid" />
            <h3>数据库</h3>
            <p>每个应用一份独立的表空间，支持增删改查、筛选、统计，带数据控制台和 Excel 导入导出。</p>
          </article>
          <article>
            <Icon name="folder" />
            <h3>文件存储</h3>
            <p>上传的图片、附件和导出文件集中存放，可以读取或下载。</p>
          </article>
          <article>
            <Icon name="lock" />
            <h3>员工登录与权限</h3>
            <p>可选用公司账号登录；每张表可设「只看自己的」「管理员审批」等权限，由平台执行。</p>
          </article>
        </div>
        {err ? (
          <p className="inline-error" role="alert">
            {err}
          </p>
        ) : null}
        <button
          className="primary-button"
          disabled={status.isLoading}
          onClick={() => {
            // 先在对话框上方确认，确认后启用并让 AI 按当前应用设计表结构
            useChat.getState().setCloudAsk(true);
          }}
        >
          <Icon name="cloud" />
          让 AI 设计表结构并启用
        </button>
      </div>
    </>
  );
}
