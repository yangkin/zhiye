/**
 * 零依赖轻量级 QR Code（ISO/IEC 18004 Byte 模式，容错等级 L，支持 Version 1~10 / 最多 271 UTF-8 字节）。
 * 用于发布弹窗内联渲染标准可扫码 SVG 二维码，不引入任何外部 CDN 或第三方库。
 */

const GF_EXP = new Uint8Array(512);
const GF_LOG = new Uint8Array(256);
(() => {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x;
    GF_LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
})();

function gfMul(a: number, b: number): number {
  return a === 0 || b === 0 ? 0 : GF_EXP[GF_LOG[a] + GF_LOG[b]];
}

function rsGenerator(deg: number): Uint8Array {
  let poly = new Uint8Array([1]);
  for (let i = 0; i < deg; i++) {
    const next = new Uint8Array(poly.length + 1);
    const root = GF_EXP[i];
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= gfMul(poly[j], root);
    }
    poly = next;
  }
  return poly;
}

function rsRemainder(data: Uint8Array, ecLen: number): Uint8Array {
  const gen = rsGenerator(ecLen);
  const rem = new Uint8Array(ecLen);
  for (let i = 0; i < data.length; i++) {
    const factor = data[i] ^ rem[0];
    rem.copyWithin(0, 1);
    rem[ecLen - 1] = 0;
    for (let j = 0; j < ecLen; j++) rem[j] ^= gfMul(gen[j + 1], factor);
  }
  return rem;
}

// Level L 容错表: [version, totalCodewords, ecPerBlock, numBlocks, alignPositions]
interface VerSpec {
  ver: number;
  total: number;
  ecPerBlock: number;
  blocks: number;
  align: number[];
}

const VERSIONS: VerSpec[] = [
  { ver: 1, total: 26, ecPerBlock: 7, blocks: 1, align: [] },
  { ver: 2, total: 44, ecPerBlock: 10, blocks: 1, align: [6, 18] },
  { ver: 3, total: 70, ecPerBlock: 15, blocks: 1, align: [6, 22] },
  { ver: 4, total: 100, ecPerBlock: 20, blocks: 1, align: [6, 26] },
  { ver: 5, total: 134, ecPerBlock: 26, blocks: 1, align: [6, 30] },
  { ver: 6, total: 172, ecPerBlock: 18, blocks: 2, align: [6, 34] },
  { ver: 7, total: 196, ecPerBlock: 20, blocks: 2, align: [6, 22, 38] },
  { ver: 8, total: 242, ecPerBlock: 24, blocks: 2, align: [6, 24, 42] },
  { ver: 9, total: 292, ecPerBlock: 30, blocks: 2, align: [6, 26, 46] },
  { ver: 10, total: 346, ecPerBlock: 18, blocks: 4, align: [6, 28, 50] },
];

export function encodeQrMatrix(text: string): boolean[][] | null {
  const bytes = new TextEncoder().encode(text);
  const spec = VERSIONS.find((v) => {
    const dataCap = v.total - v.ecPerBlock * v.blocks;
    const headerBytes = v.ver <= 9 ? 2 : 3; // 4-bit mode + 8/16-bit len + 4-bit terminator
    return bytes.length + headerBytes <= dataCap;
  });
  if (!spec) return null;

  const size = spec.ver * 4 + 17;
  const dataCap = spec.total - spec.ecPerBlock * spec.blocks;

  // 1. 构造位流（Byte mode 0100）
  const bits: number[] = [];
  const pushBits = (val: number, len: number) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1);
  };
  pushBits(0b0100, 4);
  pushBits(bytes.length, spec.ver <= 9 ? 8 : 16);
  for (const b of bytes) pushBits(b, 8);
  const maxBits = dataCap * 8;
  pushBits(0, Math.min(4, maxBits - bits.length));
  while (bits.length % 8 !== 0) bits.push(0);
  const pad = [0xec, 0x11];
  for (let pi = 0; bits.length < maxBits; pi++) pushBits(pad[pi & 1], 8);

  const rawData = new Uint8Array(dataCap);
  for (let i = 0; i < dataCap; i++) {
    let v = 0;
    for (let b = 0; b < 8; b++) v = (v << 1) | bits[i * 8 + b];
    rawData[i] = v;
  }

  // 2. 分块计算 Reed-Solomon 校验码并交织
  const shortCount = spec.blocks - (dataCap % spec.blocks);
  const shortLen = Math.floor(dataCap / spec.blocks);
  const dataBlocks: Uint8Array[] = [];
  const ecBlocks: Uint8Array[] = [];
  let offset = 0;
  for (let b = 0; b < spec.blocks; b++) {
    const len = shortLen + (b >= shortCount ? 1 : 0);
    const blk = rawData.slice(offset, offset + len);
    offset += len;
    dataBlocks.push(blk);
    ecBlocks.push(rsRemainder(blk, spec.ecPerBlock));
  }
  const codewords: number[] = [];
  const maxBlockLen = shortLen + (dataCap % spec.blocks === 0 ? 0 : 1);
  for (let i = 0; i < maxBlockLen; i++) {
    for (const blk of dataBlocks) if (i < blk.length) codewords.push(blk[i]);
  }
  for (let i = 0; i < spec.ecPerBlock; i++) {
    for (const ec of ecBlocks) codewords.push(ec[i]);
  }

  // 3. 绘制功能图形
  const mod: boolean[][] = Array.from({ length: size }, () => Array(size).fill(false));
  const isFunc: boolean[][] = Array.from({ length: size }, () => Array(size).fill(false));
  const setFunc = (r: number, c: number, dark: boolean) => {
    if (r < 0 || c < 0 || r >= size || c >= size) return;
    mod[r][c] = dark;
    isFunc[r][c] = true;
  };
  const drawFinder = (tr: number, tc: number) => {
    for (let dr = -1; dr <= 7; dr++) {
      for (let dc = -1; dc <= 7; dc++) {
        const r = tr + dr;
        const c = tc + dc;
        if (r < 0 || c < 0 || r >= size || c >= size) continue;
        const inside = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6;
        const border = dr === 0 || dr === 6 || dc === 0 || dc === 6;
        const center = dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4;
        setFunc(r, c, inside && (border || center));
      }
    }
  };
  drawFinder(0, 0);
  drawFinder(0, size - 7);
  drawFinder(size - 7, 0);

  for (let i = 8; i < size - 8; i++) {
    setFunc(6, i, i % 2 === 0);
    setFunc(i, 6, i % 2 === 0);
  }
  for (const ar of spec.align) {
    for (const ac of spec.align) {
      if ((ar === 6 && ac === 6) || (ar === 6 && ac === size - 7) || (ar === size - 7 && ac === 6)) continue;
      for (let dr = -2; dr <= 2; dr++) {
        for (let dc = -2; dc <= 2; dc++) {
          setFunc(ar + dr, ac + dc, Math.max(Math.abs(dr), Math.abs(dc)) !== 1);
        }
      }
    }
  }
  // 预留格式信息与暗模块
  for (let i = 0; i < 9; i++) {
    if (i !== 6) {
      setFunc(8, i, false);
      setFunc(i, 8, false);
    }
  }
  for (let i = 0; i < 8; i++) {
    setFunc(8, size - 1 - i, false);
    setFunc(size - 1 - i, 8, false);
  }
  setFunc(size - 8, 8, true);

  // Version >= 7 版本信息
  if (spec.ver >= 7) {
    let rem = spec.ver;
    for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    const vBits = (spec.ver << 12) | rem;
    for (let i = 0; i < 18; i++) {
      const dark = ((vBits >>> i) & 1) === 1;
      const a = size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      setFunc(a, b, dark);
      setFunc(b, a, dark);
    }
  }

  // 4. 按蛇形填充数据位 + 应用 Mask 0 ((r + c) % 2 === 0)
  let bitIdx = 0;
  const totalBits = codewords.length * 8;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    const upward = ((right + 1) & 2) === 0;
    for (let vert = 0; vert < size; vert++) {
      const r = upward ? size - 1 - vert : vert;
      for (let j = 0; j < 2; j++) {
        const c = right - j;
        if (isFunc[r][c]) continue;
        let dark = false;
        if (bitIdx < totalBits) {
          dark = ((codewords[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) === 1;
          bitIdx++;
        }
        if ((r + c) % 2 === 0) dark = !dark;
        mod[r][c] = dark;
      }
    }
  }

  // 5. 写入格式信息（ECL=L (01), Mask=0 (000) -> data=01000）
  const fmtData = (0b01 << 3) | 0;
  let rem = fmtData;
  for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
  const fmtBits = ((fmtData << 10) | rem) ^ 0x5412;
  for (let i = 0; i <= 5; i++) setFunc(8, i, ((fmtBits >>> (14 - i)) & 1) === 1);
  setFunc(8, 7, ((fmtBits >>> 8) & 1) === 1);
  setFunc(8, 8, ((fmtBits >>> 7) & 1) === 1);
  setFunc(7, 8, ((fmtBits >>> 6) & 1) === 1);
  for (let i = 9; i < 15; i++) setFunc(14 - i, 8, ((fmtBits >>> (14 - i)) & 1) === 1);
  for (let i = 0; i < 8; i++) setFunc(size - 1 - i, 8, ((fmtBits >>> (14 - i)) & 1) === 1);
  for (let i = 8; i < 15; i++) setFunc(8, size - 15 + i, ((fmtBits >>> (14 - i)) & 1) === 1);
  setFunc(size - 8, 8, true);

  return mod;
}
