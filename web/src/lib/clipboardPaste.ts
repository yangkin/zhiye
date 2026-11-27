/**
 * 富文本剪贴板解析与智能粘贴引擎（支持 Word / WPS / Excel / 飞书 / 网页 / 截图混合粘贴）：
 * 1. 从 clipboardData.files/items、text/html 内联 base64 图片、text/rtf 的 \pngblip/\jpegblip 十六进制块三路提取图片；
 * 2. 识别 Word/Excel/WPS/飞书 富文本 HTML，转成保留标题、表格、列表和图片位置标记的整洁 Markdown，避免纯文本表格错乱；
 * 3. 图文混合粘贴时同时保留文字与图片（不再因上传图片而吞掉文字）；
 * 4. 超长粘贴自动存为 .md 文档附件（避免静默截断，同时提供一键「改为正文粘贴」）；
 * 5. 检测 Windows Word 全选时被浏览器沙箱拦截的 file:/// 本地图片，返回明确数量以便界面给出直观引导。
 */

export const MAX_PROMPT_CHARS = 12000;
export const LONG_PASTE_FILE_THRESHOLD = 4000;
export const MAX_ATTACHMENTS = 10;

export interface ParsedPaste {
  /** 是否应由自定义逻辑接管本次粘贴（false 时交还浏览器原生粘贴，保留原生 Ctrl+Z 体验） */
  handled: boolean;
  /** 要插入到输入框的文本（null 表示不改文本，例如纯图片粘贴） */
  text: string | null;
  /** 从剪贴板提取出的图片或文件列表 */
  files: File[];
  /** Word/本地文档中因浏览器安全限制无法读取的 file:/// 图片数量 */
  blockedLocalImages: number;
  /** 是否来自富文本 HTML 转换（表格/Word/WPS 等） */
  fromRichHtml: boolean;
  /** 从 RTF（Word/WPS）成功自动提取的图片数 */
  recoveredRtfImages: number;
}

const IMAGE_EXT_BY_MIME: Record<string, string> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/jpg': 'jpg',
  'image/gif': 'gif',
  'image/webp': 'webp',
};

const GENERIC_CLIPBOARD_NAME = /^(image|blob|picture|clipboard|paste|pasted|unnamed|screenshot)?(\.(png|jpe?g|gif|webp|bmp))?$/i;

let pasteFileSeq = 0;
function nextImageName(prefix: string, mime: string): string {
  pasteFileSeq += 1;
  const ext = IMAGE_EXT_BY_MIME[mime.toLowerCase()] ?? 'png';
  return `${prefix}-${pasteFileSeq}.${ext}`;
}

function normalizePastedFile(file: File): File {
  const name = (file.name || '').trim();
  if (file.type.startsWith('image/') && (!name || GENERIC_CLIPBOARD_NAME.test(name))) {
    return new File([file], nextImageName('粘贴图片', file.type), {
      type: file.type,
      lastModified: file.lastModified || Date.now(),
    });
  }
  return file;
}

function bytesSignature(bytes: Uint8Array): string {
  const headLen = Math.min(16, bytes.length);
  const tailStart = Math.max(0, bytes.length - 16);
  let head = '';
  let tail = '';
  for (let i = 0; i < headLen; i++) head += bytes[i].toString(16).padStart(2, '0');
  for (let i = tailStart; i < bytes.length; i++) tail += bytes[i].toString(16).padStart(2, '0');
  return `${bytes.length}:${head}:${tail}`;
}

function decodeBase64Image(dataUrl: string, prefix: string): { file: File; sig: string } | null {
  const m = /^data:(image\/(?:png|jpe?g|gif|webp));base64,([A-Za-z0-9+/=\s]+)$/i.exec(dataUrl.trim());
  if (!m) return null;
  const mime = m[1].toLowerCase() === 'image/jpg' ? 'image/jpeg' : m[1].toLowerCase();
  try {
    const clean = m[2].replace(/\s+/g, '');
    const bin = atob(clean);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    if (bytes.length < 16) return null;
    const file = new File([bytes], nextImageName(prefix, mime), { type: mime });
    return { file, sig: bytesSignature(bytes) };
  } catch {
    return null;
  }
}

/**
 * 从 RTF 剪贴板数据中提取内嵌的 PNG/JPEG 图片（Word/WPS 复制富文本时常在 text/rtf 中携带 \pict 十六进制块）。
 * 自动跳过 \nonshppict 兼容旧图与 \blipuid 等子分组，防止重复或头字节损坏。
 */
export function extractRtfImages(rtf: string): File[] {
  if (!rtf || rtf.length > 30 * 1024 * 1024 || !rtf.includes('\\pict')) return [];
  const out: File[] = [];
  const seen = new Set<string>();

  const SKIP_DEST = new Set([
    'nonshppict', 'fonttbl', 'colortbl', 'stylesheet', 'info',
    'listtable', 'listoverridetable', 'datastore', 'themedata', 'colorschememapping',
  ]);

  let depth = 0;
  let skipDepth = -1;
  let pictDepth = -1;
  let pictKind: '' | 'png' | 'jpeg' | 'unsupported' = '';
  let hexChunks: string[] = [];

  const flushPict = () => {
    if (pictKind === 'png' || pictKind === 'jpeg') {
      const hex = hexChunks.join('');
      const byteLen = Math.floor(hex.length / 2);
      if (byteLen >= 16) {
        const bytes = new Uint8Array(byteLen);
        for (let j = 0; j < byteLen; j++) {
          bytes[j] = parseInt(hex.slice(j * 2, j * 2 + 2), 16);
        }
        const validPng = pictKind === 'png'
          && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
        const validJpeg = pictKind === 'jpeg'
          && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
        if (validPng || validJpeg) {
          const sig = bytesSignature(bytes);
          if (!seen.has(sig)) {
            seen.add(sig);
            const mime = pictKind === 'png' ? 'image/png' : 'image/jpeg';
            out.push(new File([bytes], nextImageName('Word插图', mime), { type: mime }));
          }
        }
      }
    }
    pictDepth = -1;
    pictKind = '';
    hexChunks = [];
  };

  let i = 0;
  const len = rtf.length;
  while (i < len) {
    const ch = rtf[i];
    if (ch === '{') {
      depth++;
      i++;
      continue;
    }
    if (ch === '}') {
      if (pictDepth === depth) flushPict();
      if (skipDepth === depth) skipDepth = -1;
      depth = Math.max(0, depth - 1);
      i++;
      continue;
    }
    if (ch === '\\') {
      i++;
      if (i >= len) break;
      const next = rtf[i];
      if (next === '*' || next === '{' || next === '}' || next === '\\' || next === '~' || next === '-' || next === '_') {
        i++;
        continue;
      }
      if (next === "'") {
        i += 3;
        continue;
      }
      if ((next >= 'a' && next <= 'z') || (next >= 'A' && next <= 'Z')) {
        const start = i;
        while (i < len && ((rtf[i] >= 'a' && rtf[i] <= 'z') || (rtf[i] >= 'A' && rtf[i] <= 'Z'))) i++;
        const word = rtf.slice(start, i);
        let numStr = '';
        if (i < len && rtf[i] === '-') {
          numStr += '-';
          i++;
        }
        while (i < len && rtf[i] >= '0' && rtf[i] <= '9') {
          numStr += rtf[i];
          i++;
        }
        if (i < len && rtf[i] === ' ') i++;

        if (word === 'bin' && numStr) {
          const n = parseInt(numStr, 10);
          if (n > 0) i = Math.min(len, i + n);
          continue;
        }
        if (skipDepth === -1) {
          if (SKIP_DEST.has(word)) {
            skipDepth = depth;
          } else if (word === 'pict' && pictDepth === -1) {
            pictDepth = depth;
            pictKind = '';
            hexChunks = [];
          } else if (pictDepth === depth) {
            if (word === 'pngblip') pictKind = 'png';
            else if (word === 'jpegblip') pictKind = 'jpeg';
            else if (word === 'wmetafile' || word === 'emfblip' || word === 'macpict' || word === 'dibitmap' || word === 'wbitmap') {
              if (!pictKind) pictKind = 'unsupported';
            }
          }
        }
        continue;
      }
      i++;
      continue;
    }

    // 仅在 \pict 顶层收集十六进制载荷（跳过内部 {\*\blipuid ...} 等子分组）
    if (skipDepth === -1 && pictDepth === depth && (pictKind === 'png' || pictKind === 'jpeg')) {
      const start = i;
      while (i < len) {
        const c = rtf[i];
        if (c === '{' || c === '}' || c === '\\') break;
        i++;
      }
      const slice = rtf.slice(start, i).replace(/[^0-9a-fA-F]/g, '');
      if (slice) hexChunks.push(slice);
      continue;
    }
    i++;
  }
  if (pictDepth !== -1) flushPict();
  return out;
}

const OFFICE_HTML_RE = /(urn:schemas-microsoft-com:office|Word\.Document|Excel\.Sheet|PowerPoint\.Slide|class=["']?Mso|mso-style-|data-lark-|data-lake-|wps-office)/i;
const RICH_STRUCTURE_RE = /<(table|h[1-6]|ul|ol|img|blockquote)\b/i;

function isOfficeHtml(html: string): boolean {
  return OFFICE_HTML_RE.test(html);
}

function shouldConvertRichHtml(html: string): boolean {
  if (!html || !html.trim()) return false;
  return isOfficeHtml(html) || RICH_STRUCTURE_RE.test(html);
}

function isLocalBlockedSrc(src: string): boolean {
  const s = src.trim().toLowerCase();
  return s.startsWith('file://') || s.startsWith('webkit-fake-url:') || s.startsWith('cid:') || s.startsWith('ms-appx:');
}

function normalizeSrcKey(src: string): string {
  return src.trim().replace(/\\/g, '/').toLowerCase();
}

function preprocessOfficeHtml(html: string): string {
  // 去掉 Word 条件注释里的元数据/VML 矢量备份块，保留正常渲染体与 <![if !vml]> 内的 <img>
  return html
    .replace(/<!--\[if\s+gte\s+mso\s+\d+\]>[\s\S]*?<!\[endif\]-->/gi, '')
    .replace(/<!--\[if\s+gte\s+vml\s+\d+\]>[\s\S]*?<!\[endif\]-->/gi, '')
    .replace(/<!\[if\s+!vml\]>/gi, '')
    .replace(/<!\[endif\]>/gi, '');
}

function normalizeInlineText(text: string): string {
  return text
    .replace(/\u00a0/g, ' ')
    .replace(/[\u200b\u200c\u200d\ufeff]/g, '')
    .replace(/\r\n?/g, '\n')
    .replace(/[ \t\f\v]+/g, ' ');
}

function tableToMarkdown(table: Element): string {
  const rows: string[][] = [];
  const trList = Array.from(table.querySelectorAll('tr')).filter((tr) => tr.closest('table') === table);
  for (const tr of trList) {
    const cells = Array.from(tr.children).filter((c) => c.tagName === 'TD' || c.tagName === 'TH');
    if (!cells.length) continue;
    const row = cells.map((cell) =>
      nodeToMarkdown(cell, 0)
        .replace(/\n+/g, ' ')
        .replace(/\s+/g, ' ')
        .replace(/\|/g, '\\|')
        .trim(),
    );
    if (row.some((c) => c.length > 0)) rows.push(row);
  }
  if (!rows.length) return '';
  const cols = Math.max(...rows.map((r) => r.length));
  const pad = (r: string[]) => {
    const copy = r.slice();
    while (copy.length < cols) copy.push('');
    return `| ${copy.join(' | ')} |`;
  };
  const header = pad(rows[0]);
  const sep = `| ${Array.from({ length: cols }, () => '---').join(' | ')} |`;
  const body = rows.slice(1).map(pad);
  return `\n\n${[header, sep, ...body].join('\n')}\n\n`;
}

function nodeToMarkdown(node: Node, listDepth: number): string {
  if (node.nodeType === Node.TEXT_NODE) {
    return normalizeInlineText(node.textContent || '');
  }
  if (node.nodeType !== Node.ELEMENT_NODE) return '';
  const el = node as Element;
  const tag = el.tagName.toLowerCase();

  if (tag === 'style' || tag === 'script' || tag === 'meta' || tag === 'link' || tag === 'title' || tag === 'head' || tag === 'xml' || tag === 'noscript') {
    return '';
  }
  if (tag === 'br') return '\n';
  if (tag === 'hr') return '\n\n---\n\n';
  if (tag === 'table') return tableToMarkdown(el);

  if (tag === 'img') {
    const matched = el.getAttribute('data-zhiye-img-name');
    if (matched) return `\n[附图：${matched}]\n`;
    const blocked = el.getAttribute('data-zhiye-blocked-idx');
    if (blocked) return `\n[原文档图片 ${blocked}]\n`;
    const src = (el.getAttribute('src') || '').trim();
    const alt = (el.getAttribute('alt') || '图片').trim();
    if (/^https?:\/\//i.test(src)) return `![${alt}](${src})`;
    return '';
  }

  if (tag === 'pre') {
    const code = (el.textContent || '').replace(/\r\n?/g, '\n').replace(/\u00a0/g, ' ').trimEnd();
    return code ? `\n\n\`\`\`\n${code}\n\`\`\`\n\n` : '';
  }

  const childrenText = () => Array.from(el.childNodes).map((c) => nodeToMarkdown(c, listDepth)).join('');

  if (tag === 'code') {
    const inner = (el.textContent || '').trim();
    return inner ? `\`${inner}\`` : '';
  }
  if (tag === 'strong' || tag === 'b') {
    const inner = childrenText().trim();
    return inner ? `**${inner}**` : '';
  }
  if (tag === 'em' || tag === 'i') {
    const inner = childrenText().trim();
    return inner ? `*${inner}*` : '';
  }
  if (tag === 'a') {
    const inner = childrenText().trim();
    const href = (el.getAttribute('href') || '').trim();
    if (!inner) return '';
    if (/^https?:\/\//i.test(href) && href !== inner) return `[${inner}](${href})`;
    return inner;
  }
  if (/^h[1-6]$/.test(tag)) {
    const level = Number(tag[1]);
    const inner = childrenText().replace(/\n+/g, ' ').trim();
    return inner ? `\n\n${'#'.repeat(level)} ${inner}\n\n` : '';
  }
  if (tag === 'blockquote') {
    const inner = childrenText().trim();
    if (!inner) return '';
    return `\n\n${inner.split('\n').map((l) => `> ${l}`).join('\n')}\n\n`;
  }
  if (tag === 'ul' || tag === 'ol') {
    const items = Array.from(el.children).filter((c) => c.tagName.toLowerCase() === 'li');
    const indent = '  '.repeat(listDepth);
    const lines = items
      .map((li, idx) => {
        const inner = Array.from(li.childNodes)
          .map((c) => nodeToMarkdown(c, listDepth + 1))
          .join('')
          .replace(/^\n+|\n+$/g, '')
          .replace(/\n{2,}/g, '\n');
        if (!inner.trim()) return '';
        const bullet = tag === 'ol' ? `${idx + 1}. ` : '- ';
        return `${indent}${bullet}${inner.replace(/\n/g, `\n${indent}  `)}`;
      })
      .filter(Boolean);
    return lines.length ? `\n\n${lines.join('\n')}\n\n` : '';
  }
  if (tag === 'p' || tag === 'div' || tag === 'section' || tag === 'article' || tag === 'header' || tag === 'footer') {
    const inner = childrenText().trim();
    return inner ? `\n\n${inner}\n\n` : '';
  }
  return childrenText();
}

function cleanMarkdownOutput(raw: string): string {
  return raw
    .replace(/\r\n?/g, '\n')
    .replace(/\u00a0/g, ' ')
    .replace(/[\u200b\u200c\u200d\ufeff]/g, '')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** 收集 clipboardData 中直接附带的文件（合并 items 与 files 并去重） */
function collectDirectFiles(data: DataTransfer): File[] {
  const out: File[] = [];
  const seen = new Set<string>();
  const add = (f: File | null) => {
    if (!f) return;
    const key = `${f.name}|${f.size}|${f.type}`;
    if (seen.has(key)) return;
    seen.add(key);
    out.push(f);
  };
  if (data.items) {
    for (let i = 0; i < data.items.length; i++) {
      const item = data.items[i];
      if (item.kind === 'file') add(item.getAsFile());
    }
  }
  if (data.files) {
    for (let i = 0; i < data.files.length; i++) add(data.files[i]);
  }
  return out;
}

/**
 * 解析剪贴板内容：同时提取图片/文件与整洁 Markdown 文本，绝不互相覆盖或静默丢弃。
 */
export function parseClipboardData(data: DataTransfer | null): ParsedPaste {
  if (!data) {
    return { handled: false, text: null, files: [], blockedLocalImages: 0, fromRichHtml: false, recoveredRtfImages: 0 };
  }

  const plain = (data.getData('text/plain') || '').replace(/\r\n?/g, '\n');
  const rawHtml = data.getData('text/html') || '';
  const rtf = data.getData('text/rtf') || '';
  const directFilesRaw = collectDirectFiles(data);
  const office = isOfficeHtml(rawHtml);
  const hasImgTagInHtml = /<(img|v:imagedata)\b/i.test(rawHtml);

  // Office（尤其是 Excel/Word）复制纯表格或纯文字时，系统常顺带塞 1 张整段文字渲染图（image.png）到 files 里。
  // 如果 HTML 里完全没有真实 <img> 且有文字/表格内容，跳过这张渲染图，直接走结构化 Markdown 表格粘贴。
  const skipOfficeTextBitmap = office
    && !hasImgTagInHtml
    && plain.trim().length > 0
    && directFilesRaw.length === 1
    && directFilesRaw[0].type.startsWith('image/');

  const extractedFiles: File[] = [];
  if (!skipOfficeTextBitmap) {
    for (const f of directFilesRaw) extractedFiles.push(normalizePastedFile(f));
  }

  let blockedLocalImages = 0;
  let recoveredRtfImages = 0;
  let markdownFromHtml: string | null = null;
  let fromRichHtml = false;

  if (rawHtml && (shouldConvertRichHtml(rawHtml) || hasImgTagInHtml)) {
    const cleanedHtml = office ? preprocessOfficeHtml(rawHtml) : rawHtml;
    const doc = new DOMParser().parseFromString(cleanedHtml, 'text/html');
    const imgs = Array.from(doc.querySelectorAll('img'));

    const dataImgSigs = new Set<string>();
    const localImgEls: Element[] = [];
    const seenLocalSrc = new Set<string>();

    for (const img of imgs) {
      const src = (img.getAttribute('src') || '').trim();
      if (!src) continue;
      if (src.startsWith('data:image/')) {
        const decoded = decodeBase64Image(src, '粘贴图片');
        if (decoded) {
          if (!dataImgSigs.has(decoded.sig)) {
            dataImgSigs.add(decoded.sig);
            extractedFiles.push(decoded.file);
          }
          img.setAttribute('data-zhiye-img-name', decoded.file.name);
        }
      } else if (isLocalBlockedSrc(src)) {
        const key = normalizeSrcKey(src);
        if (!seenLocalSrc.has(key)) {
          seenLocalSrc.add(key);
          localImgEls.push(img);
        }
      }
    }

    // 兜底：若某些 Word 版本只有 <v:imagedata src="file:///..."> 而没有 <![if !vml]> <img>
    if (office && localImgEls.length === 0) {
      const vmlRe = /v:imagedata[^>]+src=["'](file:\/\/[^"']+)["']/gi;
      let m: RegExpExecArray | null;
      while ((m = vmlRe.exec(rawHtml)) !== null) {
        const key = normalizeSrcKey(m[1]);
        if (!seenLocalSrc.has(key)) seenLocalSrc.add(key);
      }
    }

    const totalLocalImgCount = seenLocalSrc.size;

    // 尝试从 text/rtf 自动抢救 Word/WPS 内嵌 PNG/JPEG 图片
    if (totalLocalImgCount > 0 && rtf) {
      const rtfImgs = extractRtfImages(rtf);
      if (rtfImgs.length > 0) {
        recoveredRtfImages = rtfImgs.length;
        for (let i = 0; i < rtfImgs.length; i++) {
          extractedFiles.push(rtfImgs[i]);
          if (localImgEls[i]) {
            localImgEls[i].setAttribute('data-zhiye-img-name', rtfImgs[i].name);
          }
        }
      }
    }

    // 统计未能从 RTF/files 恢复的本地 file:/// 图片
    const directImgCount = extractedFiles.filter((f) => f.type.startsWith('image/')).length;
    if (totalLocalImgCount > 0 && recoveredRtfImages === 0 && directImgCount === 0) {
      blockedLocalImages = totalLocalImgCount;
      localImgEls.forEach((img, idx) => img.setAttribute('data-zhiye-blocked-idx', String(idx + 1)));
    } else if (totalLocalImgCount > recoveredRtfImages && recoveredRtfImages > 0) {
      blockedLocalImages = totalLocalImgCount - recoveredRtfImages;
      for (let i = recoveredRtfImages; i < localImgEls.length; i++) {
        localImgEls[i].setAttribute('data-zhiye-blocked-idx', String(i - recoveredRtfImages + 1));
      }
    }

    if (shouldConvertRichHtml(rawHtml)) {
      const md = cleanMarkdownOutput(nodeToMarkdown(doc.body, 0));
      if (md) {
        markdownFromHtml = md;
        fromRichHtml = true;
      }
    }
  }

  // 如果既没有文件/图片，也没有富文本 HTML 转换，也没有本地图片受阻 -> 交给调用方根据长度决定是否原生粘贴
  const finalText = markdownFromHtml ?? (plain.trim() ? plain : null);
  const handled = extractedFiles.length > 0 || fromRichHtml || blockedLocalImages > 0;

  return {
    handled,
    text: finalText,
    files: extractedFiles,
    blockedLocalImages,
    fromRichHtml,
    recoveredRtfImages,
  };
}

/** 把超长粘贴文本打包为可读的 Markdown 文档附件 */
export function makeLongPasteMarkdownFile(text: string): File {
  const firstLine = text
    .split('\n')
    .map((l) => l.replace(/^[#>*\-\s\d.]+/, '').trim())
    .find((l) => l.length >= 2) || '';
  const safeTitle = firstLine
    .replace(/[\\/:*?"<>|\r\n\t]+/g, '')
    .replace(/\s+/g, '-')
    .slice(0, 18)
    .replace(/^-+|-+$/g, '');
  const name = safeTitle ? `粘贴文档-${safeTitle}.md` : `粘贴文档-${++pasteFileSeq}.md`;
  return new File([text], name, { type: 'text/markdown;charset=utf-8' });
}

/** 在受控 textarea 的当前选区插入文本，并返回新值与新光标位置 */
export function spliceAtCursor(
  current: string,
  insert: string,
  selectionStart: number | null | undefined,
  selectionEnd: number | null | undefined,
): { nextValue: string; nextCursor: number } {
  const start = typeof selectionStart === 'number' ? Math.max(0, Math.min(current.length, selectionStart)) : current.length;
  const end = typeof selectionEnd === 'number' ? Math.max(start, Math.min(current.length, selectionEnd)) : start;
  const nextValue = current.slice(0, start) + insert + current.slice(end);
  return { nextValue, nextCursor: start + insert.length };
}
