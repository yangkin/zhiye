import { strToU8, zipSync } from 'fflate';
import { marked, type Token, type Tokens } from 'marked';

/**
 * 在浏览器里把 Markdown 转成 Word（.docx）：标题（带大纲级别，Word 导航窗格可用）、段落、
 * 加粗/斜体/删除线/行内代码/链接、有序和无序列表（含嵌套）、表格、代码块、引用、分隔线。
 * 图片和原始 HTML 不导出。
 */
export function markdownToDocx(md: string): Blob {
  const links: string[] = [];
  const body = blocks(marked.lexer(md, { gfm: true }), { links, level: 0 });
  const rels = links
    .map((url, i) => `<Relationship Id="rLink${i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${esc(url)}" TargetMode="External"/>`)
    .join('');
  const files = {
    '[Content_Types].xml': `${XML}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>`,
    '_rels/.rels': `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>`,
    'word/_rels/document.xml.rels': `${XML}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>${rels}</Relationships>`,
    'word/styles.xml': STYLES,
    'word/document.xml': `${XML}<w:document xmlns:w="${W}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>${body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="851" w:footer="992" w:gutter="0"/></w:sectPr></w:body></w:document>`,
  };
  const zipped = zipSync(Object.fromEntries(Object.entries(files).map(([k, v]) => [k, strToU8(v)])));
  return new Blob([zipped as BlobPart], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' });
}

const XML = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
/** A4 减去左右页边距后的正文宽度（twip） */
const TEXT_WIDTH = 9026;

interface Ctx {
  links: string[];
  /** 列表嵌套层级 */
  level: number;
}

interface Fmt {
  b?: boolean;
  i?: boolean;
  strike?: boolean;
  code?: boolean;
  link?: boolean;
}

// XML 1.0 不允许的控制字符直接去掉，否则 Word 打不开
const esc = (s: string) =>
  s.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

const decode = (s: string) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, '&');

function run(text: string, f: Fmt) {
  const pr = [
    f.b ? '<w:b/>' : '',
    f.i ? '<w:i/>' : '',
    f.strike ? '<w:strike/>' : '',
    f.code ? '<w:rStyle w:val="CodeChar"/>' : '',
    f.link ? '<w:rStyle w:val="Hyperlink"/>' : '',
  ].join('');
  return text
    .split('\n')
    .map((line, i) => `<w:r>${pr ? `<w:rPr>${pr}</w:rPr>` : ''}${i ? '<w:br/>' : ''}<w:t xml:space="preserve">${esc(line)}</w:t></w:r>`)
    .join('');
}

function inline(tokens: Token[] | undefined, ctx: Ctx, f: Fmt = {}): string {
  if (!tokens) return '';
  return tokens
    .map((t) => {
      switch (t.type) {
        case 'strong':
          return inline((t as Tokens.Strong).tokens, ctx, { ...f, b: true });
        case 'em':
          return inline((t as Tokens.Em).tokens, ctx, { ...f, i: true });
        case 'del':
          return inline((t as Tokens.Del).tokens, ctx, { ...f, strike: true });
        case 'codespan':
          return run(decode((t as Tokens.Codespan).text), { ...f, code: true });
        case 'br':
          return '<w:r><w:br/></w:r>';
        case 'link': {
          const l = t as Tokens.Link;
          if (!/^(https?:|mailto:)/i.test(l.href)) return inline(l.tokens, ctx, f);
          ctx.links.push(l.href);
          return `<w:hyperlink r:id="rLink${ctx.links.length - 1}">${inline(l.tokens, ctx, { ...f, link: true })}</w:hyperlink>`;
        }
        case 'image':
          return run(`[图片：${(t as Tokens.Image).text || (t as Tokens.Image).href}]`, { ...f, i: true });
        case 'html':
          return '';
        case 'text': {
          const x = t as Tokens.Text;
          return x.tokens?.length ? inline(x.tokens, ctx, f) : run(decode(x.text), f);
        }
        default:
          return 'text' in t && typeof t.text === 'string' ? run(decode(t.text), f) : '';
      }
    })
    .join('');
}

const para = (content: string, pPr = '') => `<w:p>${pPr ? `<w:pPr>${pPr}</w:pPr>` : ''}${content}</w:p>`;

function blocks(tokens: Token[], ctx: Ctx, quote = false): string {
  const q = quote ? '<w:pStyle w:val="Quote"/>' : '';
  return tokens
    .map((t) => {
      switch (t.type) {
        case 'heading': {
          const h = t as Tokens.Heading;
          return para(inline(h.tokens, ctx), `<w:pStyle w:val="Heading${Math.min(h.depth, 4)}"/>`);
        }
        case 'paragraph':
          return para(inline((t as Tokens.Paragraph).tokens, ctx), q);
        case 'text': {
          const x = t as Tokens.Text;
          return para(x.tokens?.length ? inline(x.tokens, ctx) : run(decode(x.text), {}), q);
        }
        case 'code':
          return para(run((t as Tokens.Code).text, {}), '<w:pStyle w:val="Code"/>');
        case 'blockquote':
          return blocks((t as Tokens.Blockquote).tokens, ctx, true);
        case 'hr':
          return para('', '<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="D9D9D9"/></w:pBdr>');
        case 'list':
          return list(t as Tokens.List, ctx);
        case 'table':
          return table(t as Tokens.Table, ctx);
        default:
          return '';
      }
    })
    .join('');
}

function list(l: Tokens.List, ctx: Ctx): string {
  const start = typeof l.start === 'number' ? l.start : 1;
  const indent = 420 * (ctx.level + 1);
  return l.items
    .map((item, i) => {
      const marker = l.ordered ? `${start + i}.` : item.task ? (item.checked ? '☑' : '☐') : ctx.level % 2 ? '◦' : '•';
      const pPr = `<w:pStyle w:val="ListParagraph"/><w:tabs><w:tab w:val="left" w:pos="${indent}"/></w:tabs><w:ind w:left="${indent}" w:hanging="${l.ordered ? 360 : 280}"/>`;
      let first = true;
      return item.tokens
        .filter((c) => c.type !== 'checkbox')
        .map((c) => {
          if (c.type === 'list') return list(c as Tokens.List, { ...ctx, level: ctx.level + 1 });
          if (c.type !== 'text' && c.type !== 'paragraph') return blocks([c], { ...ctx, level: ctx.level + 1 });
          const content = inline((c as Tokens.Text).tokens ?? [{ type: 'text', raw: c.raw, text: (c as Tokens.Text).text }], ctx);
          const prefix = first ? run(marker, {}) + '<w:r><w:tab/></w:r>' : '';
          first = false;
          return para(prefix + content, pPr);
        })
        .join('');
    })
    .join('');
}

function table(t: Tokens.Table, ctx: Ctx): string {
  const cols = Math.max(t.header.length, 1);
  const w = Math.floor(TEXT_WIDTH / cols);
  const jc = (i: number) => (t.align[i] === 'center' ? 'center' : t.align[i] === 'right' ? 'right' : '');
  const cell = (c: Tokens.TableCell, i: number, head: boolean) =>
    `<w:tc><w:tcPr><w:tcW w:w="${w}" w:type="dxa"/>${head ? '<w:shd w:val="clear" w:color="auto" w:fill="F2F0F7"/>' : ''}</w:tcPr>${para(
      inline(c.tokens, ctx, head ? { b: true } : {}),
      `<w:spacing w:before="40" w:after="40"/>${jc(i) ? `<w:jc w:val="${jc(i)}"/>` : ''}`,
    )}</w:tc>`;
  const border = (side: string) => `<w:${side} w:val="single" w:sz="4" w:space="0" w:color="D9D6E3"/>`;
  return (
    `<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/><w:tblLayout w:type="fixed"/><w:tblBorders>${['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].map(border).join('')}</w:tblBorders>` +
    `<w:tblCellMar><w:left w:w="100" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar></w:tblPr>` +
    `<w:tblGrid>${Array.from({ length: cols }, () => `<w:gridCol w:w="${w}"/>`).join('')}</w:tblGrid>` +
    `<w:tr><w:trPr><w:tblHeader/></w:trPr>${t.header.map((c, i) => cell(c, i, true)).join('')}</w:tr>` +
    t.rows.map((r) => `<w:tr>${r.map((c, i) => cell(c, i, false)).join('')}</w:tr>`).join('') +
    '</w:tbl>' +
    para('', '<w:spacing w:before="0" w:after="0"/>')
  );
}

const FONT = '<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="微软雅黑" w:cs="Calibri"/>';
const MONO = '<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:eastAsia="微软雅黑" w:cs="Consolas"/>';
const heading = (n: number, size: number, before: number) =>
  `<w:style w:type="paragraph" w:styleId="Heading${n}"><w:name w:val="heading ${n}"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="${before}" w:after="120" w:line="240" w:lineRule="auto"/><w:outlineLvl w:val="${n - 1}"/></w:pPr><w:rPr><w:b/><w:color w:val="1F1B2E"/><w:sz w:val="${size}"/><w:szCs w:val="${size}"/></w:rPr></w:style>`;

const STYLES =
  `${XML}<w:styles xmlns:w="${W}">` +
  `<w:docDefaults><w:rPrDefault><w:rPr>${FONT}<w:sz w:val="21"/><w:szCs w:val="21"/><w:lang w:val="en-US" w:eastAsia="zh-CN"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="360" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>` +
  `<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/><w:rPr><w:color w:val="2B2738"/></w:rPr></w:style>` +
  heading(1, 36, 360) +
  heading(2, 30, 320) +
  heading(3, 26, 240) +
  heading(4, 22, 200) +
  `<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="60"/></w:pPr></w:style>` +
  `<w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:pPr><w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="D8D0F0"/></w:pBdr><w:ind w:left="240"/></w:pPr><w:rPr><w:color w:val="6B6478"/></w:rPr></w:style>` +
  `<w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:basedOn w:val="Normal"/><w:pPr><w:shd w:val="clear" w:color="auto" w:fill="F6F5FA"/><w:spacing w:before="60" w:after="160" w:line="276" w:lineRule="auto"/><w:ind w:left="120" w:right="120"/></w:pPr><w:rPr>${MONO}<w:sz w:val="18"/><w:szCs w:val="18"/></w:rPr></w:style>` +
  `<w:style w:type="character" w:styleId="CodeChar"><w:name w:val="Inline Code"/><w:rPr>${MONO}<w:color w:val="9A3F6B"/><w:sz w:val="19"/><w:shd w:val="clear" w:color="auto" w:fill="F3F1F8"/></w:rPr></w:style>` +
  `<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:rPr><w:color w:val="5B3FC0"/><w:u w:val="single"/></w:rPr></w:style>` +
  `</w:styles>`;
