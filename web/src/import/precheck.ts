import { zipSync } from 'fflate';

export interface PickedFile {
  file: File;
  path: string;
}

export interface Prechecked {
  format: 'html' | 'zip' | 'folder' | 'script';
  files: PickedFile[];
  total: number;
  folderName?: string;
  name: string;
}

export interface Limits {
  maxFiles: number;
  maxBytes: number;
}

export interface PrecheckOptions {
  /** 来自「文件夹」选择器时为 true，兼容个别浏览器未填充 webkitRelativePath 的场景。 */
  fromFolderPicker?: boolean;
}

export const DEFAULT_LIMITS: Limits = { maxFiles: 5000, maxBytes: 50 * 1048576 };

const SKIP_DIRS = new Set([
  'node_modules',
  '.git',
  '.svn',
  '.hg',
  '__MACOSX',
  '.venv',
  'venv',
  '__pycache__',
  '.pytest_cache',
  '.mypy_cache',
  '.idea',
  '.vscode',
  '.turbo',
  '.gradle',
  '.next',
  '.nuxt',
  '.parcel-cache',
]);

const SKIP_ROOT_DIRS = new Set(['node_modules', '.git', '.svn', '.hg', '__MACOSX']);
const SKIP_FILES = new Set(['.DS_Store', 'Thumbs.db', 'desktop.ini']);

const normalizePath = (raw: string) => raw.replace(/\\/g, '/').replace(/^\/+/, '').replace(/\/+/g, '/');

/** 判断相对路径是否属于依赖缓存、版本控制或系统隐藏文件；不会跳过用户所选根目录自身（除 __MACOSX/.git 等）。 */
export function shouldSkipPath(rawPath: string): boolean {
  const path = normalizePath(rawPath);
  if (!path) return true;
  const parts = path.split('/').filter(Boolean);
  if (!parts.length) return true;
  const last = parts[parts.length - 1];
  if (SKIP_FILES.has(last) || last.startsWith('._')) return true;
  if (parts.length === 1) return SKIP_ROOT_DIRS.has(last);
  if (SKIP_ROOT_DIRS.has(parts[0])) return true;
  for (let i = 1; i < parts.length - 1; i++) {
    if (SKIP_DIRS.has(parts[i])) return true;
  }
  return SKIP_DIRS.has(last);
}

const SOURCE_EXT_RE =
  /\.(html?|xhtml|vue|svelte|astro|[jt]sx?|m[jt]s|c[jt]s|css|s[ac]ss|less|styl|ejs|hbs|handlebars|pug|jade|jsp|jspx|ftl|jinja2?|j2|njk|twig|erb|cshtml|razor|mustache|liquid|mdx|wasm|py|pyw|ipynb|go|java|kts?|scala|groovy|rs|c|cc|cpp|cxx|h|hh|hpp|cs|fs|vb|php|phtml|rb|rake|swift|mm?|dart|lua|r|rmd|pl|pm|exs?|erl|cljs?|hs|zig|nim|sh|bash|zsh|fish|ps1|bat|cmd|sql|gql|graphql|proto|csproj|fsproj|vbproj|sln|jar|war|dockerfile)$/i;

const PROJECT_MANIFEST_RE =
  /^(package\.json|tsconfig(\.[^/]+)?\.json|jsconfig\.json|(vite|webpack|next|nuxt|vue|astro|svelte|rsbuild|craco)\.config\.[^/]+|angular\.json|go\.mod|go\.sum|pom\.xml|(build|settings)\.gradle(\.kts)?|gradlew|mvnw|requirements([-_.][^/]+)?\.txt|pyproject\.toml|setup\.(py|cfg)|pipfile|poetry\.lock|uv\.lock|dockerfile(\.[^/]+)?|(docker-)?compose\.ya?ml|makefile|cmakelists\.txt|cargo\.toml|composer\.json|gemfile|procfile|nginx\.conf|caddyfile|zhiye\.ya?ml|skill\.md)$/i;

/** 判断文件夹内是否包含任意常见网页、脚本、源码、容器或项目配置文件；最终由服务端 ProjectAnalyzer 做权威识别。 */
export function looksLikeProjectFile(rawPath: string): boolean {
  const path = normalizePath(rawPath);
  if (!path) return false;
  const base = path.slice(path.lastIndexOf('/') + 1);
  return SOURCE_EXT_RE.test(base) || PROJECT_MANIFEST_RE.test(base);
}

const filePath = (f: File & { importPath?: string }) =>
  normalizePath(String(f.importPath || f.webkitRelativePath || f.name));
const mb = (n: number) => Math.round(n / 1048576);

function sampleExtensions(paths: string[]): string {
  const exts: string[] = [];
  for (const p of paths) {
    const base = p.slice(p.lastIndexOf('/') + 1);
    const dot = base.lastIndexOf('.');
    const ext = dot > 0 ? base.slice(dot).toLowerCase() : '无后缀文件';
    if (!exts.includes(ext)) exts.push(ext);
    if (exts.length >= 4) break;
  }
  return exts.join('、');
}

/** 本地预检查：过滤依赖缓存、校验体积和基础格式，最终以服务端解析结果为准。 */
export async function precheck(
  input: File[],
  limits: Limits = DEFAULT_LIMITS,
  options: PrecheckOptions = {},
): Promise<Prechecked> {
  if (!input.length) {
    throw new Error(options.fromFolderPicker ? '所选文件夹为空，请选择包含项目源码的文件夹。' : '请选择项目文件夹、ZIP 或 HTML 文件。');
  }
  const files = input.map((file) => ({ file, path: filePath(file) })).filter((f) => !shouldSkipPath(f.path));
  if (!files.length) {
    throw new Error(
      options.fromFolderPicker
        ? '所选文件夹中没有可导入的源码文件（仅包含缓存或系统隐藏文件，如 node_modules、.git、.DS_Store）。'
        : '请选择项目文件夹、ZIP 或 HTML 文件。',
    );
  }
  if (files.length > limits.maxFiles)
    throw new Error(`项目文件过多，请移除依赖缓存（如 node_modules、.venv）后重新选择，最多 ${limits.maxFiles} 个文件。`);
  const total = files.reduce((n, f) => n + f.file.size, 0);
  if (total > limits.maxBytes) throw new Error(`项目超过 ${mb(limits.maxBytes)} MB，请移除依赖缓存或构建产物后重新选择。`);
  const hasSlash = files.some((f) => f.path.includes('/'));
  const folder = hasSlash || Boolean(options.fromFolderPicker);
  if (!folder && files.length > 1) throw new Error('每次导入一个项目，请选择项目文件夹或打包为 ZIP。');
  const first = files[0];
  const format = folder
    ? 'folder'
    : /\.zip$/i.test(first.file.name)
      ? 'zip'
      : /\.html?$/i.test(first.file.name)
        ? 'html'
        : /\.(py|js|mjs|cjs)$/i.test(first.file.name)
          ? 'script'
          : null;
  if (!format) throw new Error('请选择 HTML（.html/.htm）、Python/JS 脚本（.py/.js/.mjs）、ZIP 文件或项目文件夹。');
  if (format === 'zip') {
    const bytes = new Uint8Array(await first.file.slice(0, 4).arrayBuffer());
    const valid = bytes[0] === 80 && bytes[1] === 75 && [[3, 4], [5, 6], [7, 8]].some((p) => bytes[2] === p[0] && bytes[3] === p[1]);
    if (!valid) throw new Error('这个文件不是有效的 ZIP，请重新打包后选择。');
  }
  const paths = files.map((f) => f.path);
  if (paths.some((p) => p.split('/').some((part) => part === '..')) || new Set(paths).size !== paths.length)
    throw new Error('项目文件路径重复或无效，请重新选择。');
  if (format === 'folder') {
    if (!paths.some((p) => looksLikeProjectFile(p))) {
      if (paths.some((p) => /\.zip$/i.test(p))) {
        throw new Error('检测到文件夹里是 ZIP 压缩包，请点击「选择文件」直接上传该压缩包，或先解压后再选择源码文件夹。');
      }
      const exts = sampleExtensions(paths);
      throw new Error(
        `未在所选文件夹（共 ${paths.length} 个文件${exts ? `，检测到 ${exts}` : ''}）中找到网页、脚本或项目源码，请选择包含代码或项目配置文件的文件夹。`,
      );
    }
  }
  if (format === 'html') {
    const text = await first.file.slice(0, 65536).text();
    if (!/<[a-z][\s\S]*>/i.test(text)) throw new Error('HTML 内容为空或无效，请重新选择。');
  }
  if (format === 'script') {
    const text = await first.file.slice(0, 65536).text();
    if (!text.trim()) throw new Error('脚本文件内容为空，请重新选择。');
  }
  const folderName = hasSlash ? paths[0].split('/')[0] : folder ? 'project' : undefined;
  const name = (folderName && folderName !== 'project' ? folderName : first.file.name.replace(/\.(zip|html?|py|mjs|cjs|js)$/i, '')).slice(0, 40) || '我的代码项目';
  return { format, files, total, folderName, name };
}

/** 文件夹在浏览器中打成 zip（路径相对于文件夹根目录），其他格式原样上传。 */
export async function buildUpload(p: Prechecked): Promise<{ blob: Blob; fileName: string; folderName?: string }> {
  if (p.format !== 'folder') return { blob: p.files[0].file, fileName: p.files[0].file.name };
  const entries: Record<string, Uint8Array> = {};
  const root = p.folderName && p.files.every((f) => f.path.startsWith(p.folderName + '/')) ? p.folderName + '/' : '';
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
    const full = path + entry.name;
    if (path ? shouldSkipPath(full) : SKIP_ROOT_DIRS.has(entry.name) || SKIP_FILES.has(entry.name)) return;
    if (result.length > maxFiles) throw new Error(`项目文件过多，请移除依赖缓存后重新选择，最多 ${maxFiles} 个文件。`);
    if (entry.isFile) {
      const file = await new Promise<File>((resolve, reject) => entry.file(resolve, reject));
      Object.defineProperty(file, 'importPath', { value: full });
      result.push(file);
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      let batch: FsEntry[];
      do {
        batch = await new Promise<FsEntry[]>((resolve, reject) => reader.readEntries(resolve, reject));
        for (const child of batch) await visit(child, full + '/');
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
