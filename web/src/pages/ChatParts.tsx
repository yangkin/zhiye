import { useMemo, useState } from 'react';
import { DownloadButton, kindLabel } from './ChatFiles';
import { Icon } from '../lib/icons';
import { copyText } from '../lib/format';
import { toast } from '../lib/store';

/** 回答里的一段：普通文字，或模型用 [[file:名称.md]] … [[/file]] 写出的文件（done=false 表示还在写） */
export type AnswerPart = { kind: 'text'; text: string } | { kind: 'file'; name: string; body: string; done: boolean };

const FILE_OPEN = /\[\[file:([^\]\n]{1,120})\]\]\s*\n?/;
const FILE_CLOSE = '[[/file]]';

export function splitFiles(text: string): AnswerPart[] {
  const parts: AnswerPart[] = [];
  let rest = text;
  for (;;) {
    const m = FILE_OPEN.exec(rest);
    if (!m) break;
    const before = rest.slice(0, m.index);
    if (before.trim()) parts.push({ kind: 'text', text: before });
    const after = rest.slice(m.index + m[0].length);
    const end = after.indexOf(FILE_CLOSE);
    const name = safeName(m[1]);
    if (end < 0) {
      // 流式输出时结束标记还没到；结尾可能是半截的「[[/fi」，先去掉
      parts.push({ kind: 'file', name, body: after.replace(/\n?\[\[\/?f?i?l?e?\]?\]?$/, '').trimEnd(), done: false });
      return parts;
    }
    parts.push({ kind: 'file', name, body: after.slice(0, end).trimEnd(), done: true });
    rest = after.slice(end + FILE_CLOSE.length);
  }
  if (rest.trim()) parts.push({ kind: 'text', text: rest });
  return parts;
}

function safeName(raw: string) {
  const n = raw.trim().replace(/[\\/:*?"<>|]+/g, '-') || '文档';
  return /\.(md|markdown|txt)$/i.test(n) ? n : n + '.md';
}

/** 复制整条回答时去掉文件标记，只留文件正文 */
export function plainAnswer(text: string) {
  return splitFiles(text).map((p) => (p.kind === 'text' ? p.text.trim() : p.body)).filter(Boolean).join('\n\n');
}

const sizeLabel = (body: string) => {
  const n = body.replace(/\s+/g, '').length;
  return n >= 10000 ? `${(n / 10000).toFixed(1)} 万字` : `${n} 字`;
};

/** 文件卡片：写入中显示进度；写完后「预览」在右侧文件面板里打开，下载可选 Word 或原文 */
export function FileCard({ name, body, done, active, onPreview }: { name: string; body: string; done: boolean; active?: boolean; onPreview: () => void }) {
  const outline = useMemo(() => body.split('\n').filter((l) => /^##\s/.test(l)).map((l) => l.replace(/^##\s+/, '').replace(/^[\d一二三四五六七八九十]+[.、．]\s*/, '')).slice(0, 6), [body]);
  return (
    <div className={`chat-file ${done ? 'done' : 'writing'} ${active ? 'active' : ''}`}>
      <div className="chat-file-head">
        <button type="button" className="chat-file-open" disabled={!done} onClick={onPreview} title={done ? '在右侧预览' : undefined}>
          <span className="chat-file-icon" aria-hidden="true"><Icon name="file" /></span>
          <span className="chat-file-meta">
            <b title={name}>{name}</b>
            <small>
              {done ? `${kindLabel(name)} · ${sizeLabel(body)}` : (<><Icon name="refresh" className="spinning" /> 正在写入 · {sizeLabel(body)}</>)}
            </small>
          </span>
        </button>
        {done ? (
          <div className="chat-file-actions">
            <button type="button" onClick={onPreview} aria-pressed={active}>
              <Icon name="preview" />
              预览
            </button>
            <button type="button" onClick={() => void copyText(body).then((ok) => toast(ok ? '已复制文档内容' : '复制失败', ok ? 'ok' : 'error'))}>
              <Icon name="copy" />
              复制
            </button>
            <DownloadButton name={name} body={body} className="primary" />
          </div>
        ) : null}
      </div>
      {outline.length ? (
        <ol className="chat-file-outline" aria-label="文档目录">
          {outline.map((h) => <li key={h}>{h}</li>)}
        </ol>
      ) : null}
    </div>
  );
}

/** 思考过程：思考时展开并实时滚动；开始输出正文后自动收起成「已思考 N 秒」，点开可看 */
export function ThinkingBlock({ text, live, seconds }: { text: string; live: boolean; seconds?: number }) {
  const [toggled, setToggled] = useState<boolean | null>(null);
  const open = toggled ?? live;
  if (!text.trim()) return null;
  return (
    <div className={`chat-thinking ${live ? 'live' : 'done'}`}>
      <button type="button" onClick={() => setToggled(!open)} aria-expanded={open}>
        {live ? <Icon name="refresh" className="spinning" /> : <Icon name="spark" />}
        <span>{live ? '思考中…' : seconds ? `已思考 ${seconds} 秒` : '思考过程'}</span>
        <Icon name="chevron" className={open ? 'up' : ''} />
      </button>
      {open ? <p>{text.trim()}</p> : null}
    </div>
  );
}
