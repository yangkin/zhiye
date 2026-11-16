import { Suspense, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { api, errorMessage } from '../api/client';
import { qk } from '../api/queries';
import type { FileEntry, Task } from '../api/types';
import { submitCodeEdits, useCode } from '../lib/codeStore';
import { sizeLabel } from '../lib/format';
import { Icon } from '../lib/icons';
import { toast } from '../lib/store';
import { EmptyState, ErrorState, LoadingState } from '../components/EmptyState';
import { BusyHint, type BusyInfo } from '../editor/ModeOverlays';
import { DiffView } from './Timeline';
import { ErrorBoundary, retryableLazy } from '../components/ErrorBoundary';
import { promptDialog } from '../components/ConfirmDialog';

const CodeEditor = retryableLazy(() => import('./CodeEditor'));

interface TreeNode {
  name: string;
  path: string;
  children?: TreeNode[];
  size?: number;
}

function buildTree(files: FileEntry[]): TreeNode[] {
  const root: TreeNode = { name: '', path: '', children: [] };
  for (const f of files) {
    const parts = f.path.split('/').filter(Boolean);
    let node = root;
    parts.forEach((part, i) => {
      const leaf = i === parts.length - 1;
      node.children ??= [];
      let child = node.children.find((c) => c.name === part && !!c.children === !leaf);
      if (!child) {
        child = { name: part, path: parts.slice(0, i + 1).join('/'), ...(leaf ? { size: f.size } : { children: [] }) };
        node.children.push(child);
      }
      node = child;
    });
  }
  const sort = (list: TreeNode[]) => {
    list.sort((a, b) => (!!b.children === !!a.children ? a.name.localeCompare(b.name) : b.children ? 1 : -1));
    list.forEach((n) => n.children && sort(n.children));
  };
  sort(root.children!);
  return root.children!;
}

const extOf = (path: string) => (path.split('/').pop()?.includes('.') ? (path.split('.').pop() ?? '').toLowerCase() : '');
const isMarkdown = (path: string) => /^(md|markdown|mdx)$/.test(extOf(path));
const isBinary = (path: string) => /^(png|jpe?g|gif|webp|ico|bmp|woff2?|ttf|otf|eot|zip|gz|pdf|mp4|mp3|wav|webm)$/.test(extOf(path));

const FILE_KINDS: Record<string, [string, string]> = {
  md: ['md', 'M↓'],
  markdown: ['md', 'M↓'],
  mdx: ['md', 'M↓'],
  py: ['py', 'py'],
  js: ['js', 'JS'],
  mjs: ['js', 'JS'],
  cjs: ['js', 'JS'],
  jsx: ['js', 'JSX'],
  ts: ['ts', 'TS'],
  mts: ['ts', 'TS'],
  tsx: ['tsx', 'TSX'],
  html: ['html', '<>'],
  htm: ['html', '<>'],
  vue: ['html', 'V'],
  css: ['css', '#'],
  scss: ['css', '#'],
  json: ['json', '{}'],
};

/** 文件类型图标：folder / md / py / js / ts / tsx / html / css / json / other */
export function FileTypeIcon({ path, folder, open }: { path: string; folder?: boolean; open?: boolean }) {
  if (folder)
    return (
      <span className={`ft-icon ft-folder ${open ? 'open' : ''}`} data-kind="folder" aria-hidden="true">
        <Icon name="folder" />
      </span>
    );
  const [kind, label] = FILE_KINDS[extOf(path)] ?? ['other', ''];
  return (
    <span className={`ft-icon ft-${kind}`} data-kind={kind} aria-hidden="true">
      {label || <Icon name="file" />}
    </span>
  );
}

const KEYWORDS =
  /^(import|from|export|default|function|return|const|let|var|if|else|for|while|class|extends|new|async|await|try|catch|throw|interface|type|public|private|static|def|package|func|true|false|null|undefined|None|True|False|self)$/;
const TOKEN = /(\/\/.*$|#.*$|\/\*[\s\S]*?\*\/|<!--[\s\S]*?-->|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|`(?:[^`\\]|\\.)*`|<\/?[A-Za-z][\w.-]*|\b\d+(?:\.\d+)?\b|\b[A-Za-z_]\w*\b)/gm;

/** 只读视图的简易高亮：纯 React 节点，不注入 HTML。 */
function highlight(line: string, lang: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  const hashComment = /^(py|sh|yml|yaml|toml|rb)$/.test(lang);
  line.replace(TOKEN, (m: string, _g: string, offset: number) => {
    if (offset > last) out.push(line.slice(last, offset));
    let cls = '';
    if (m.startsWith('//') || m.startsWith('/*') || m.startsWith('<!--')) cls = 'code-comment';
    else if (m.startsWith('#')) {
      if (!hashComment) {
        // CSS 选择器 / 颜色等：只把 # 本身当普通文本，剩余部分继续由外层输出
        out.push(m);
        last = offset + m.length;
        return m;
      }
      cls = 'code-comment';
    } else if (/^["'`]/.test(m)) cls = 'code-green';
    else if (m.startsWith('<')) cls = 'code-tag';
    else if (/^\d/.test(m)) cls = 'code-number';
    else if (KEYWORDS.test(m)) cls = 'code-purple';
    out.push(cls ? <span key={offset} className={cls}>{m}</span> : m);
    last = offset + m.length;
    return m;
  });
  if (last < line.length) out.push(line.slice(last));
  return out;
}

marked.setOptions({ gfm: true, breaks: false });
function renderMarkdown(src: string): string {
  // 去掉 YAML front matter 的分隔符，以表格形式之外的代码块展示
  const fm = /^---\n([\s\S]*?)\n---\n?/.exec(src);
  const body = fm ? '```yaml\n' + fm[1] + '\n```\n\n' + src.slice(fm[0].length) : src;
  const raw = marked.parse(body, { async: false }) as string;
  return DOMPurify.sanitize(raw, { USE_PROFILES: { html: true }, FORBID_TAGS: ['style', 'form', 'input', 'button'] });
}

function pickDefault(files: FileEntry[]): string | null {
  const byName = (re: RegExp) =>
    files.filter((f) => re.test(f.path)).sort((a, b) => a.path.split('/').length - b.path.split('/').length)[0]?.path;
  return (
    byName(/(^|\/)SKILL\.md$/i) ??
    byName(/(^|\/)README\.md$/i) ??
    files.find((f) => /(^|\/)(App\.[jt]sx|index\.html|main\.[jt]sx?)$/.test(f.path))?.path ??
    files[0]?.path ??
    null
  );
}

interface Props {
  appId: string;
  appName: string;
  versionId: string | null;
  currentVersionId: string | null;
  readOnly: boolean;
  skill: boolean;
  onTaskStarted: (t: Task) => void;
  /** 首次生成进行中（还没有版本） */
  generating?: boolean;
  /** 这个应用正在跑的任务：代码页显示工作区的实时文件 */
  liveTaskId?: string | null;
  blocked?: BusyInfo;
  /** 有未应用的手动微调草稿：提示先应用或重置 */
  pendingDraft?: { onApply: () => void; onReset: () => void } | null;
  onGo?: (appId: string) => void;
}

export function CodePane({ appId, appName, versionId, currentVersionId, readOnly, skill, onTaskStarted, generating, liveTaskId = null, blocked = { busy: false }, onGo, pendingDraft = null }: Props) {
  // 有进行中的任务或未应用的手动修改时，编辑器只读
  const locked = (blocked.busy && !useCode.getState().savingTaskId) || !!pendingDraft;
  const code = useCode();
  // 有正在跑的任务时直接读任务工作区（边生成边看），否则读版本里的文件
  const live = !!liveTaskId;
  const files = useQuery({
    queryKey: live ? (['taskFiles', liveTaskId] as const) : qk.files(versionId ?? ''),
    queryFn: () => (live ? api.taskFiles(liveTaskId!) : api.versionFiles(versionId!)),
    enabled: live || !!versionId,
    refetchInterval: live ? 3000 : false,
  });
  const allFiles = useMemo(() => {
    const list = [...(files.data ?? [])];
    if (!readOnly) for (const p of code.created) if (!list.some((f) => f.path === p)) list.push({ path: p, size: code.edits[p]?.length ?? 0 });
    return list;
  }, [files.data, code.created, code.edits, readOnly]);
  const tree = useMemo(() => buildTree(allFiles), [allFiles]);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  // 「本次修改」：查看某个版本相对上一版的改动（/api/apps/:id/versions/:vid/changes）
  const changesVersion = code.changesVersion;
  const [changePath, setChangePath] = useState<string | null>(null);
  const changes = useQuery({
    queryKey: qk.changes(changesVersion ?? ''),
    queryFn: () => api.versionChanges(appId, changesVersion!),
    enabled: !!changesVersion,
  });
  const changeDiff = useQuery({
    queryKey: qk.changeFile(changesVersion ?? '', changePath ?? ''),
    queryFn: () => api.versionChangeFile(appId, changesVersion!, changePath!),
    enabled: !!changesVersion && !!changePath,
    staleTime: Infinity,
  });
  const changeFiles = changes.data?.files ?? [];
  useEffect(() => {
    if (!changesVersion) setChangePath(null);
    else if (changeFiles.length && !changeFiles.some((c) => c.path === changePath)) setChangePath(changeFiles[0].path);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [changesVersion, changes.data]);

  // 选中默认文件
  useEffect(() => {
    if (!files.data) return;
    const known = (p: string | null) => !!p && allFiles.some((f) => f.path === p);
    if (!known(code.active)) {
      const d = pickDefault(files.data);
      if (d) code.openFile(d);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [files.data, versionId]);

  const path = code.active && allFiles.some((f) => f.path === code.active) ? code.active : null;
  const entry = allFiles.find((f) => f.path === path);
  const created = !!path && code.created.includes(path);
  const tooBig = (entry?.size ?? 0) > 1048576;
  const binary = !!path && isBinary(path);
  const content = useQuery({
    queryKey: live ? (['taskFile', liveTaskId, path] as const) : qk.file(versionId ?? '', path ?? ''),
    queryFn: () => (live ? api.taskFile(liveTaskId!, path!) : api.versionFile(versionId!, path!)),
    enabled: (live || !!versionId) && !!path && !tooBig && !binary && !created,
    staleTime: live ? 0 : Infinity,
    refetchInterval: live ? 3000 : false,
  });
  const original = created ? '' : (content.data ?? null);
  const edited = !readOnly && path !== null && path in code.edits;
  const text = edited ? code.edits[path!] : (original ?? '');
  const md = !!path && isMarkdown(path);
  const mode = (path && code.modes[path]) || 'preview';
  const effectiveMode = readOnly ? 'preview' : mode;
  const jumpLine = code.jump && code.jump.path === path ? code.jump.line : undefined;
  const editCount = Object.keys(code.edits).length;

  // 只读视图跳到行号
  const viewRef = useRef<HTMLPreElement>(null);
  useEffect(() => {
    if (!jumpLine || effectiveMode !== 'preview' || md) return;
    const el = viewRef.current?.querySelector(`[data-line="${jumpLine}"]`);
    el?.scrollIntoView({ block: 'center' });
  }, [jumpLine, code.jump?.nonce, effectiveMode, md, content.data]);

  const [saving, setSaving] = useState(false);
  // 草稿是在旧版本上改的（离开期间 AI 又改过一版）：保存前讲清楚会覆盖
  const stale = editCount > 0 && !!code.base && !!currentVersionId && code.base !== currentVersionId;
  const save = async () => {
    if (!currentVersionId || !editCount || saving || code.savingTaskId || locked) return;
    setSaving(true);
    try {
      const task = await submitCodeEdits(appId, currentVersionId);
      if (task) onTaskStarted(task);
    } catch {
      /* 错误已写入 code.error */
    } finally {
      setSaving(false);
    }
  };

  const newFile = async () => {
    const input = await promptDialog({
      title: '新建文件',
      message: '输入相对于技能包根目录的路径，文件夹不存在会自动创建。',
      placeholder: 'references/notes.md',
      label: '新文件路径',
      confirmLabel: '创建',
    });
    if (input === null) return;
    const p = input.trim().replace(/\\/g, '/').replace(/^\.\/+/, '');
    if (!p || p.startsWith('/') || p.endsWith('/') || p.split('/').some((x) => x === '..' || x === '' || x === '.'))
      return toast('请输入有效的相对路径，例如 references/notes.md', 'error');
    if (p.length > 200) return toast('路径太长了。', 'error');
    if (allFiles.some((f) => f.path === p)) {
      code.openFile(p);
      return toast('这个文件已经存在，已为你打开。');
    }
    code.createFile(p);
  };

  if (!versionId && !live)
    return generating ? (
      <LoadingState label={skill ? '正在生成技能包…' : '正在生成代码…'} />
    ) : (
      <EmptyState icon="code" title="还没有代码" desc="应用生成后，这里会显示对应版本的真实代码。" />
    );
  if (files.isLoading) return <LoadingState label="正在读取项目文件…" />;
  if (files.error) return <ErrorState message={errorMessage(files.error)} onRetry={() => files.refetch()} />;

  const dirty = (p: string) => !readOnly && p in code.edits;
  const dirInDirty = (dir: string) => !readOnly && Object.keys(code.edits).some((p) => p.startsWith(dir + '/'));
  const renderNodes = (nodes: TreeNode[], depth: number): ReactNode =>
    nodes.map((n) =>
      n.children ? (
        <div key={n.path} role="group">
          <button
            className="code-tree-dir"
            style={{ paddingLeft: 8 + depth * 12 }}
            aria-expanded={!collapsed.has(n.path)}
            onClick={() =>
              setCollapsed((s) => {
                const next = new Set(s);
                if (next.has(n.path)) next.delete(n.path);
                else next.add(n.path);
                return next;
              })
            }
          >
            <span className="code-tree-caret">{collapsed.has(n.path) ? '▸' : '▾'}</span>
            <FileTypeIcon path={n.path} folder open={!collapsed.has(n.path)} />
            <span className="code-tree-name">{n.name}</span>
            {dirInDirty(n.path) ? <i className="code-dirty-dot" aria-label="有未保存修改" /> : null}
          </button>
          {collapsed.has(n.path) ? null : renderNodes(n.children, depth + 1)}
        </div>
      ) : (
        <button
          key={n.path}
          className={`code-tree-file ${n.path === path ? 'active' : ''}`}
          style={{ paddingLeft: 20 + depth * 12 }}
          title={`${n.path} · ${sizeLabel(n.size ?? 0)}`}
          aria-current={n.path === path ? 'true' : undefined}
          onClick={() => code.openFile(n.path)}
        >
          <FileTypeIcon path={n.path} />
          <span className="code-tree-name">{n.name}</span>
          {dirty(n.path) ? <i className="code-dirty-dot" aria-label="有未保存修改">•</i> : null}
        </button>
      ),
    );

  const openTabs = code.open.filter((p) => allFiles.some((f) => f.path === p));
  const crumbs = path ? path.split('/') : [];
  const lang = path ? extOf(path) : '';

  let body: ReactNode;
  if (!path) body = <p className="muted-note code-note">这个版本没有文件。</p>;
  else if (binary) body = <p className="muted-note code-note">这是二进制文件，无法以文本显示。</p>;
  else if (tooBig) body = <p className="muted-note code-note">文件超过 1 MB，请导出源码后查看。</p>;
  else if (!created && content.isLoading) body = <p className="muted-note code-note">正在读取文件…</p>;
  else if (!created && content.error) body = <p className="inline-error code-note">{errorMessage(content.error)}</p>;
  else if (effectiveMode === 'edit')
    body = (
      <ErrorBoundary area="代码编辑器" onReset={CodeEditor.reload}>
      <Suspense fallback={<p className="muted-note code-note">正在加载编辑器…</p>}>
        <CodeEditor key={path} path={path} value={text} line={jumpLine} readOnly={locked} onChange={(v) => !locked && code.setEdit(path, v, original, currentVersionId)} />
      </Suspense>
      </ErrorBoundary>
    );
  else if (md)
    body = text.trim() ? (
      <article className="markdown-body" dangerouslySetInnerHTML={{ __html: renderMarkdown(text) }} />
    ) : (
      <p className="muted-note code-note">这个文件还是空的。</p>
    );
  else
    body = (
      <pre className="code-lines" ref={viewRef}>
        {text.split('\n').map((line, i) => (
          <div key={i} className={`code-line ${jumpLine === i + 1 ? 'is-target' : ''}`} data-line={i + 1}>
            <span className="code-ln">{i + 1}</span>
            <code>{highlight(line, lang)}</code>
          </div>
        ))}
      </pre>
    );

  return (
    <div className="code-view code-view-real">
      <div className="code-files">
        <div className="code-files-head">
          <span title={appName}>▾ {appName}</span>
          <span className="code-files-switch" role="group" aria-label="代码视图">
            <button
              type="button"
              className={!changesVersion ? 'active' : ''}
              aria-pressed={!changesVersion}
              title="浏览文件"
              onClick={() => code.openChanges(null)}
            >
              文件
            </button>
            <button
              type="button"
              className={changesVersion ? 'active' : ''}
              aria-pressed={!!changesVersion}
              title={live ? '任务完成后可以查看改动' : '查看这次改动'}
              disabled={live || !versionId}
              onClick={() => code.openChanges(versionId)}
            >
              改动
            </button>
          </span>
          {skill && !readOnly && !changesVersion ? (
            <button type="button" className="code-new-file" aria-label="新建文件" title="新建文件" onClick={newFile}>
              <Icon name="plus" />
            </button>
          ) : null}
        </div>
        {changesVersion ? (
          <div className="code-changes" aria-label="本次修改">
            {changes.isLoading ? (
              <p className="muted-note code-note">正在读取改动…</p>
            ) : changes.error ? (
              <p className="inline-error code-note">{errorMessage(changes.error)}</p>
            ) : !changeFiles.length ? (
              <p className="muted-note code-note">这个版本没有文件改动。</p>
            ) : (
              <ul className="change-list">
                {changeFiles.map((c) => (
                  <li key={c.path} className={c.path === changePath ? 'active' : ''}>
                    <button type="button" className="change-row" onClick={() => setChangePath(c.path)}>
                      <FileTypeIcon path={c.path} />
                      <span className="change-name" title={c.path}>
                        {c.path.split('/').pop()}
                        <small>{c.path.includes('/') ? c.path.slice(0, c.path.lastIndexOf('/')) : ''}</small>
                      </span>
                      <i className={`change-status s-${c.status}`} aria-label={c.status === 'A' ? '新增' : c.status === 'D' ? '删除' : '修改'}>
                        {c.status}
                      </i>
                      <span className="change-counts">
                        {c.added ? <em className="diff-add">+{c.added}</em> : null}
                        {c.removed ? <em className="diff-del">−{c.removed}</em> : null}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ) : (
          <div className="code-tree" role="tree" aria-label="项目文件">
            {renderNodes(tree, 0)}
          </div>
        )}
      </div>
      {changesVersion ? (
        <div className="code-content change-content">
          <div className="code-content-head">
            <nav className="code-breadcrumb" aria-label="改动文件">
              <span>本次修改</span>
              {changePath ? (
                <span>
                  <i>/</i>
                  {changePath}
                </span>
              ) : null}
            </nav>
            <div className="code-head-right">
              <small>{changes.data ? `${changeFiles.length} 个文件` : ''}</small>
            </div>
          </div>
          <div className="code-body change-diff">
            {!changePath ? (
              <p className="muted-note code-note">选择左边的文件查看改动。</p>
            ) : changeDiff.isLoading ? (
              <p className="muted-note code-note">正在读取改动…</p>
            ) : changeDiff.error ? (
              <p className="inline-error code-note">{errorMessage(changeDiff.error)}</p>
            ) : (
              <DiffView diff={changeDiff.data?.diff ?? ''} />
            )}
          </div>
        </div>
      ) : (
      <div className="code-content">
        <div className="code-tabs" role="tablist" aria-label="打开的文件">
          {openTabs.map((p) => (
            <div key={p} className={`code-tab ${p === path ? 'active' : ''}`} role="tab" aria-selected={p === path}>
              <button type="button" className="code-tab-name" title={p} onClick={() => code.openFile(p)}>
                <FileTypeIcon path={p} />
                {p.split('/').pop()}
                {dirty(p) ? <i className="code-dirty-dot">•</i> : null}
              </button>
              <button type="button" className="code-tab-close" aria-label={`关闭 ${p}`} onClick={() => code.closeFile(p)}>
                ×
              </button>
            </div>
          ))}
        </div>
        <div className="code-content-head">
          <nav className="code-breadcrumb" aria-label="文件路径">
            {crumbs.map((c, i) => (
              <span key={i}>
                {i ? <i>/</i> : null}
                {c}
              </span>
            ))}
            {path && dirty(path) ? <i className="code-dirty-dot">•</i> : null}
          </nav>
          <div className="code-head-right">
            <small>
              {live ? '生成中 · 实时' : readOnly ? '历史版本 · 只读' : created ? '新文件' : entry ? sizeLabel(entry.size) : ''}
            </small>
            {path && !binary && !tooBig ? (
              <div className="code-mode-toggle" role="group" aria-label="查看方式">
                <button
                  type="button"
                  aria-label="预览文件"
                  aria-pressed={effectiveMode === 'preview'}
                  className={effectiveMode === 'preview' ? 'active' : ''}
                  onClick={() => code.setMode(path, 'preview')}
                >
                  预览
                </button>
                <button
                  type="button"
                  aria-label="编辑文件"
                  aria-pressed={effectiveMode === 'edit'}
                  className={effectiveMode === 'edit' ? 'active' : ''}
                  disabled={readOnly}
                  title={readOnly ? '历史版本只读，返回当前版本后可编辑' : undefined}
                  onClick={() => code.setMode(path, 'edit')}
                >
                  编辑
                </button>
              </div>
            ) : null}
          </div>
        </div>
        {!readOnly && pendingDraft ? (
          <div className="code-lock-banner" role="status">
            有未保存的手动微调，请先应用或重置
            <span>
              <button type="button" className="code-reset" onClick={pendingDraft.onReset}>
                重置
              </button>
              <button type="button" className="code-save" onClick={pendingDraft.onApply}>
                应用
              </button>
            </span>
          </div>
        ) : !readOnly && blocked.busy && !code.savingTaskId && effectiveMode === 'edit' ? (
          <div className="code-lock-banner" role="status">
            正在修改中，完成后可继续编辑
          </div>
        ) : null}
        <div className="code-body">{body}</div>
        {!readOnly && (editCount > 0 || code.error) ? (
          <div className="code-save-bar" role="group" aria-label="代码修改">
            <span>
              {code.savingTaskId ? '正在保存并检查代码…' : `${editCount} 个文件有未保存的修改`}
            </span>
            {!code.savingTaskId ? <BusyHint info={blocked} onGo={onGo} /> : null}
            {code.error ? (
              <p className="code-save-error" role="alert">
                {code.error}
              </p>
            ) : stale && !code.savingTaskId ? (
              <p className="code-save-error code-save-warn" role="status">
                这些修改是在旧版本上做的，之后应用又更新过。保存会用你的修改覆盖这些文件的最新内容。
              </p>
            ) : null}
            <div>
              <button
                type="button"
                className="code-reset"
                aria-label="重置代码修改"
                disabled={!!code.savingTaskId || saving || !editCount}
                onClick={() => code.discard()}
              >
                重置
              </button>
              <button
                type="button"
                className="code-save"
                aria-label="保存代码修改"
                disabled={!!code.savingTaskId || saving || !editCount || !currentVersionId || (blocked.busy && !code.savingTaskId) || !!pendingDraft}
                onClick={() => void save()}
              >
                {saving || code.savingTaskId ? '保存中…' : stale ? '覆盖保存' : '保存'}
              </button>
            </div>
          </div>
        ) : null}
      </div>
      )}
    </div>
  );
}
