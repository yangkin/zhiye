import { strFromU8, unzipSync } from 'fflate';

/** 表格文件解析结果：第一行当表头 */
export interface Sheet {
  headers: string[];
  rows: string[][];
}

/** RFC 4180 CSV：引号包裹、"" 转义、单元格内换行；自动去掉 UTF-8 BOM */
export function parseCsv(text: string): Sheet {
  const src = text.replace(/^\uFEFF/, '');
  const out: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (quoted) {
      if (ch === '"' && src[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (ch === '"') quoted = false;
      else cell += ch;
    } else if (ch === '"' && cell === '') quoted = true;
    else if (ch === ',') {
      row.push(cell);
      cell = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && src[i + 1] === '\n') i++;
      row.push(cell);
      out.push(row);
      row = [];
      cell = '';
    } else cell += ch;
  }
  if (cell !== '' || row.length) {
    row.push(cell);
    out.push(row);
  }
  return toSheet(out);
}

/** .xlsx：只读第一个工作表；共享字符串、内联字符串、数字、布尔 */
export function parseXlsx(buf: ArrayBuffer): Sheet {
  const files = unzipSync(new Uint8Array(buf));
  const xml = (name: string) => (files[name] ? new DOMParser().parseFromString(strFromU8(files[name]), 'application/xml') : null);
  const shared: string[] = [];
  const ss = xml('xl/sharedStrings.xml');
  if (ss) for (const si of Array.from(ss.getElementsByTagName('si'))) shared.push(Array.from(si.getElementsByTagName('t')).map((t) => t.textContent ?? '').join(''));
  const sheetName = Object.keys(files).filter((n) => /^xl\/worksheets\/sheet\d+\.xml$/.test(n)).sort((a, b) => parseInt(a.replace(/\D/g, '')) - parseInt(b.replace(/\D/g, '')))[0];
  const sheet = sheetName ? xml(sheetName) : null;
  if (!sheet) throw new Error('没有在文件里找到工作表');
  const out: string[][] = [];
  for (const r of Array.from(sheet.getElementsByTagName('row'))) {
    const line: string[] = [];
    for (const c of Array.from(r.getElementsByTagName('c'))) {
      const ref = c.getAttribute('r') ?? '';
      const col = colIndex(ref.replace(/\d+/g, ''));
      const t = c.getAttribute('t');
      const v = c.getElementsByTagName('v')[0]?.textContent ?? '';
      const text =
        t === 's' ? (shared[Number(v)] ?? '') : t === 'inlineStr' ? Array.from(c.getElementsByTagName('t')).map((x) => x.textContent ?? '').join('') : t === 'b' ? (v === '1' ? 'true' : 'false') : v;
      while (line.length < col) line.push('');
      line[col] = text;
    }
    out.push(line);
  }
  return toSheet(out);
}

function colIndex(letters: string) {
  let n = 0;
  for (const ch of letters.toUpperCase()) n = n * 26 + (ch.charCodeAt(0) - 64);
  return Math.max(0, n - 1);
}

function toSheet(all: string[][]): Sheet {
  const rows = all.filter((r) => r.some((c) => c.trim() !== ''));
  const headers = (rows.shift() ?? []).map((h) => h.trim());
  return { headers, rows: rows.map((r) => headers.map((_, i) => (r[i] ?? '').trim())) };
}

/** Excel 日期序列号（1900 系统）→ YYYY-MM-DD；目标列是日期而值是 2 万～8 万之间的数字时才转换 */
export function excelDate(value: string, withTime = false): string {
  const n = Number(value);
  if (!value || Number.isNaN(n) || n < 20000 || n > 80000) return value;
  const ms = Math.round((n - 25569) * 86400 * 1000);
  const iso = new Date(ms).toISOString();
  return withTime ? iso.slice(0, 19).replace('T', ' ') : iso.slice(0, 10);
}
