import { useMemo, type MouseEvent } from 'react';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { copyText } from './format';
import { toast } from './store';

/** 清洗后的 Markdown HTML；代码块外面包一层，带「复制」按钮（按钮在清洗之后才加上，内容固定） */
export function renderMarkdown(src: string): string {
  const html = DOMPurify.sanitize(marked.parse(src, { async: false, gfm: true }) as string, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button', 'img', 'iframe'],
  });
  return html
    .replace(/<pre>/g, '<div class="md-code"><button type="button" class="md-copy" aria-label="复制代码">复制</button><pre>')
    .replace(/<\/pre>/g, '</pre></div>')
    .replace(/<table>/g, '<div class="md-table"><table>')
    .replace(/<\/table>/g, '</table></div>');
}

const NEXT_INLINE = /\[\[next\]\]\s*(.*)$/;

/** 模型在回答末尾用 `[[next]] A | B | C`（独占一行或行尾）给出可点击的下一步；这里把它从正文里拆出来 */
export function splitNext(text: string): { body: string; next: string[] } {
  const lines = text.split('\n');
  const next: string[] = [];
  const keep: string[] = [];
  let inFence = false;
  for (const l of lines) {
    if (/^\s*```/.test(l)) {
      inFence = !inFence;
      keep.push(l);
      continue;
    }
    const m = inFence ? null : NEXT_INLINE.exec(l);
    if (m) {
      const before = l.slice(0, m.index).trimEnd();
      if (before) keep.push(before);
      next.push(...m[1].split(/[|｜]/).map((s) => s.trim().replace(/^[「"]|[」"]$/g, '')).filter(Boolean));
    } else {
      keep.push(l);
    }
  }
  return { body: keep.join('\n').replace(/\s*\[\[build(?::[^\]]*)?\]\]\s*/g, '').trimEnd(), next: [...new Set(next)].filter((s) => s.length <= 40).slice(0, 3) };
}

function onCopyClick(e: MouseEvent<HTMLDivElement>) {
  const btn = (e.target as HTMLElement).closest('.md-copy');
  if (!btn) return;
  const code = btn.parentElement?.querySelector('pre')?.textContent ?? '';
  void copyText(code).then((ok) => toast(ok ? '已复制代码' : '复制失败', ok ? 'ok' : 'error'));
}

export function Markdown({ text, className = '' }: { text: string; className?: string }) {
  const html = useMemo(() => renderMarkdown(text), [text]);
  return <div className={`markdown-body ${className}`.trim()} onClick={onCopyClick} dangerouslySetInnerHTML={{ __html: html }} />;
}

/** 回答下方的「下一步」：点一下就当作用户的下一句话发出去 */
export function NextSteps({ items, onPick, disabled }: { items: string[]; onPick: (s: string) => void; disabled?: boolean }) {
  if (!items.length) return null;
  return (
    <div className="next-chips" role="group" aria-label="下一步建议">
      {items.map((s) => (
        <button key={s} type="button" disabled={disabled} onClick={() => onPick(s)}>
          {s}
          <span aria-hidden="true">→</span>
        </button>
      ))}
    </div>
  );
}
