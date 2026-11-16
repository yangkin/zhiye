import { zipSync } from 'fflate';

export interface PickedFile {
  file: File;
  path: string;
}

export interface Prechecked {
  format: 'html' | 'zip' | 'folder';
  files: PickedFile[];
  total: number;
  folderName?: string;
  name: string;
}

export interface Limits {
  maxFiles: number;
  maxBytes: number;
}

export const DEFAULT_LIMITS: Limits = { maxFiles: 5000, maxBytes: 50 * 1048576 };

const skip = (path: string) => /(^|\/)(node_modules|\.git|\.DS_Store)(\/|$)/.test(path);
const filePath = (f: File & { importPath?: string }) =>
  String(f.importPath || f.webkitRelativePath || f.name).replace(/\\/g, '/');
const mb = (n: number) => Math.round(n / 1048576);

/** 本地预检查，与原型 code-import.js 的规则一致。最终以服务端解析结果为准。 */
export async function precheck(input: File[], limits: Limits = DEFAULT_LIMITS): Promise<Prechecked> {
  const files = input.map((file) => ({ file, path: filePath(file) })).filter((f) => !skip(f.path));
  if (!files.length) throw new Error('请选择项目文件夹、ZIP 或 HTML 文件。');
  if (files.length > limits.maxFiles)
    throw new Error(`项目文件过多，请移除依赖缓存（如 node_modules）后重新选择，最多 ${limits.maxFiles} 个文件。`);
  const total = files.reduce((n, f) => n + f.file.size, 0);
  if (total > limits.maxBytes) throw new Error(`项目超过 ${mb(limits.maxBytes)} MB，请移除依赖缓存或构建产物后重新选择。`);
  const folder = files.some((f) => f.path.includes('/'));
  if (!folder && files.length > 1) throw new Error('每次导入一个项目，请选择项目文件夹或打包为 ZIP。');
  const first = files[0];
  const format = folder ? 'folder' : /\.zip$/i.test(first.file.name) ? 'zip' : /\.html?$/i.test(first.file.name) ? 'html' : null;
  if (!format) throw new Error('请选择 HTML（.html/.htm）、ZIP 文件或项目文件夹。');
  if (format === 'zip') {
    const bytes = new Uint8Array(await first.file.slice(0, 4).arrayBuffer());
    const valid = bytes[0] === 80 && bytes[1] === 75 && [[3, 4], [5, 6], [7, 8]].some((p) => bytes[2] === p[0] && bytes[3] === p[1]);
    if (!valid) throw new Error('这个文件不是有效的 ZIP，请重新打包后选择。');
  }
  const paths = files.map((f) => f.path);
  if (paths.some((p) => p.split('/').some((part) => part === '..')) || new Set(paths).size !== paths.length)
    throw new Error('项目文件路径重复或无效，请重新选择。');
  if (format === 'folder') {
    if (!paths.some((p) => /\.(html?|vue|[jt]sx?|py|go|java)$|(^|\/)(package\.json|go\.mod|pom\.xml|requirements\.txt)$/i.test(p)))
      throw new Error('请选择包含网页或项目源码的文件夹。');
  }
  if (format === 'html') {
    const text = await first.file.slice(0, 65536).text();
    if (!/<[a-z][\s\S]*>/i.test(text)) throw new Error('HTML 内容为空或无效，请重新选择。');
  }
  const folderName = folder ? paths[0].split('/')[0] : undefined;
  const name = (folderName ?? first.file.name.replace(/\.(zip|html?)$/i, '')).slice(0, 40) || '我的代码项目';
  return { format, files, total, folderName, name };
}

/** 文件夹在浏览器中打成 zip（路径相对于文件夹根目录），其他格式原样上传。 */
export async function buildUpload(p: Prechecked): Promise<{ blob: Blob; fileName: string; folderName?: string }> {
  if (p.format !== 'folder') return { blob: p.files[0].file, fileName: p.files[0].file.name };
  const entries: Record<string, Uint8Array> = {};
  const root = p.folderName ? p.folderName + '/' : '';
  for (const f of p.files) {
    const rel = root && f.path.startsWith(root) ? f.path.slice(root.length) : f.path;
    entries[rel] = new Uint8Array(await f.file.arrayBuffer());
  }
  const zipped = zipSync(entries, { level: 6 });
  return {
    blob: new Blob([zipped as BlobPart], { type: 'application/zip' }),
    fileName: (p.folderName || 'project') + '.zip',
    folderName: p.folderName,
  };
}

interface FsEntry {
  isFile: boolean;
  isDirectory: boolean;
  name: string;
  file: (ok: (f: File) => void, err: (e: unknown) => void) => void;
  createReader: () => { readEntries: (ok: (list: FsEntry[]) => void, err: (e: unknown) => void) => void };
}

/** 读取拖入的文件 / 文件夹。 */
export async function droppedFiles(transfer: DataTransfer, maxFiles: number): Promise<File[]> {
  const result: File[] = [];
  const visit = async (entry: FsEntry, path = ''): Promise<void> => {
    if (skip(path + entry.name)) return;
    if (result.length > maxFiles) throw new Error(`项目文件过多，请移除依赖缓存后重新选择，最多 ${maxFiles} 个文件。`);
    if (entry.isFile) {
      const file = await new Promise<File>((resolve, reject) => entry.file(resolve, reject));
      Object.defineProperty(file, 'importPath', { value: path + entry.name });
      result.push(file);
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      let batch: FsEntry[];
      do {
        batch = await new Promise<FsEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
        for (const child of batch) await visit(child, path + entry.name + '/');
      } while (batch.length);
    }
  };
  const entries = Array.from(transfer.items || [])
    .map((item) => (item as DataTransferItem & { webkitGetAsEntry?: () => FsEntry | null }).webkitGetAsEntry?.())
    .filter(Boolean) as unknown as FsEntry[];
  if (entries.length) {
    for (const e of entries) await visit(e);
    return result;
  }
  return Array.from(transfer.files || []);
}
