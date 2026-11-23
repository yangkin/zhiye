import { useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError, api, errorMessage, newIdempotencyKey } from '../api/client';
import { qk } from '../api/queries';
import type { AppDetail, SkillVisibility, Version } from '../api/types';
import { Icon } from '../lib/icons';
import { copyText } from '../lib/format';
import { toast } from '../lib/store';
import { Modal } from '../components/Modal';
import { Select } from '../components/Select';

const NAME_RE = /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/;
const VERSION_IN_NAME_RE = /(?:^|-)v\d+(?:-\d+)*$|-\d+-\d+(?:-\d+)*$/;
const VAGUE_COMPAT_RE = /^(?:仅?兼容飞书|兼容飞书|飞书|通用|全平台通用|无|无特殊要求|无依赖|不限|all|any|general|none|n\/a)[。.!！\s]*$/i;
const GENERIC_DISPLAY_NAMES = new Set(['spc', '产品助手', '助手', 'ai助手', '智能助手', '工具', '新技能', '技能', 'skill', '测试', 'test', 'demo', '示例']);
const JUNK_DIRS = new Set(['tmp', 'temp', 'logs', 'cache', '.cache', '__pycache__', '.pytest_cache', '.mypy_cache', '.ruff_cache', 'coverage', 'output']);
const JUNK_FILE_RE = /^(?:\.DS_Store|Thumbs\.db|\.env(?:\..+)?|(?:debug|test[_-]?output).*)$/i;
const JUNK_EXT_RE = /\.(?:log|tmp|temp|bak|swp|swo|orig|pyc|pyo)$/i;
const PLACEHOLDER_RE = /^(?:your[_-]|xxx+|placeholder|example|sample|dummy|test|changeme|replace|demo|\*+|<[^>]+>|\$\{[^}]+\}|%[a-z_]+%)/i;
const SECRET_PATTERNS: [string, RegExp][] = [
  ['飞书用户 ID（ou_xxx）', /\bou_[a-zA-Z0-9]{16,}\b/],
  ['飞书应用 ID（cli_xxx）', /\bcli_[a-f0-9]{16,}\b/],
  ['私钥内容', /-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/],
  ['JWT 令牌', /\beyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/],
  ['API Key / Token', /\b(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})\b/],
  ['Bearer 令牌', /\bBearer\s+[A-Za-z0-9._-]{20,}\b/],
];
const ASSIGN_SECRET_RE = /(?:^|[\s,{])["']?(password|passwd|pwd|app_?secret|api_?key|access_?token|auth_?token|secret_?key|private_?key)["']?\s*[:=]\s*["']([^"'\r\n\s]{8,})["']/gi;

const MAX_DISPLAY_NAME = 20;
const MAX_DESCRIPTION = 120;
const MAX_SKILL_LINES = 500;

function isJunkPath(rel: string): boolean {
  const segs = rel.replace(/\\/g, '/').split('/');
  if (segs.slice(0, -1).some((s) => JUNK_DIRS.has(s.toLowerCase()))) return true;
  const base = segs[segs.length - 1] ?? '';
  return JUNK_DIRS.has(base.toLowerCase()) || JUNK_FILE_RE.test(base) || JUNK_EXT_RE.test(base) || base.endsWith('~');
}

function scanTextSecrets(text: string): string[] {
  const hits = new Set<string>();
  for (const [label, re] of SECRET_PATTERNS) {
    if (re.test(text)) hits.add(label);
  }
  ASSIGN_SECRET_RE.lastIndex = 0;
  for (const m of text.matchAll(ASSIGN_SECRET_RE)) {
    const val = m[2];
    if (!PLACEHOLDER_RE.test(val) && !val.includes('<') && !val.includes('${')) {
      hits.add(`明文凭证字段 ${m[1]}`);
    }
  }
  return [...hits];
}

/** 从 SKILL.md 的 YAML front matter 读出 name / description / compatibility。 */
export function parseSkillFrontmatter(src: string): {
  name: string;
  description: string;
  compatibility: string;
  hasCompatibilityField: boolean;
  hasFrontmatter: boolean;
  lineCount: number;
} {
  const normalized = src.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const lineCount = normalized ? normalized.split('\n').length : 0;
  const m = /^\uFEFF?---\n([\s\S]*?)\n---\s*(\n|$)/.exec(normalized);
  if (!m) return { name: '', description: '', compatibility: '', hasCompatibilityField: false, hasFrontmatter: false, lineCount };
  const lines = m[1].split('\n');
  const hasField = (key: string) => lines.some((l) => new RegExp(`^${key}\\s*:`).test(l));
  const read = (key: string) => {
    const i = lines.findIndex((l) => new RegExp(`^${key}\\s*:`).test(l));
    if (i < 0) return '';
    let v = lines[i].replace(new RegExp(`^${key}\\s*:\\s*`), '');
    if (/^[>|][+-]?\s*$/.test(v)) {
      const block: string[] = [];
      for (let j = i + 1; j < lines.length && (/^\s+/.test(lines[j]) || !lines[j].trim()); j++) block.push(lines[j].trim());
      v = block.join(v.startsWith('|') ? '\n' : ' ').trim();
    } else {
      v = v.replace(/\s+#.*$/, '').trim();
    }
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
    return v.trim();
  };
  return {
    name: read('name'),
    description: read('description'),
    compatibility: read('compatibility'),
    hasCompatibilityField: hasField('compatibility'),
    hasFrontmatter: true,
    lineCount,
  };
}

const VIS: [SkillVisibility, string, string][] = [
  ['private', '仅本人使用', '免上架人工审批，仍须通过发布检测'],
  ['team', '部门 / 中心', '所在部门或中心可见，审批通过后上架'],
  ['public', '全员共享', '全公司可搜索和安装，审批通过后上架'],
];

interface Props {
  app: AppDetail;
  versions: Version[];
  onDownload: (versionId: string) => void;
  onClose: () => void;
}

export function SkillhubModal({ app, versions, onDownload, onClose }: Props) {
  const [versionId, setVersionId] = useState(app.currentVersion?.id ?? versions[0]?.id ?? '');
  const [displayName, setDisplayName] = useState(app.name);
  const [visibility, setVisibility] = useState<SkillVisibility>('private');
  const [changelog, setChangelog] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const [result, setResult] = useState<string | null>(null);
  const keyRef = useRef<{ sig: string; key: string } | null>(null);

  const files = useQuery({ queryKey: qk.files(versionId), queryFn: () => api.versionFiles(versionId), enabled: !!versionId });
  const skillPath = useMemo(
    () => files.data?.filter((f) => /(^|\/)SKILL\.md$/i.test(f.path)).sort((a, b) => a.path.split('/').length - b.path.split('/').length)[0]?.path ?? null,
    [files.data],
  );
  const skillFile = useQuery({
    queryKey: qk.file(versionId, skillPath ?? ''),
    queryFn: () => api.versionFile(versionId, skillPath!),
    enabled: !!versionId && !!skillPath,
    staleTime: Infinity,
  });
  const meta = useMemo(() => (skillFile.data !== undefined ? parseSkillFrontmatter(skillFile.data) : null), [skillFile.data]);
  const loading = files.isLoading || (!!skillPath && skillFile.isLoading);

  const trimmedDisplay = displayName.trim();
  const displayLen = [...trimmedDisplay].length;
  const descLen = meta ? [...meta.description].length : 0;

  const issues = useMemo(() => {
    const list: string[] = [];
    if (!trimmedDisplay) list.push('请填写 SkillHub 显示名称');
    else if (GENERIC_DISPLAY_NAMES.has(trimmedDisplay.toLowerCase().replace(/\s+/g, '')))
      list.push('显示名称过于泛化，建议按「厂区/业务域/系统 - 场景用途」命名（如：t11 - 储位告警查询）');
    else if (displayLen > MAX_DISPLAY_NAME) list.push(`显示名称超过 ${MAX_DISPLAY_NAME} 字（当前 ${displayLen} 字）`);

    if (loading) return list;
    if (!skillPath) {
      list.push('根目录缺少 SKILL.md 文件');
      return list;
    }
    if (skillPath !== 'SKILL.md') list.push(`SKILL.md 应放在根目录（当前位于 ${skillPath}）`);
    if (meta && !meta.hasFrontmatter) {
      list.push('SKILL.md 开头缺少 YAML 配置头（--- 包围的 name / description）');
    } else if (meta) {
      if (meta.lineCount > MAX_SKILL_LINES) list.push(`SKILL.md 超过 ${MAX_SKILL_LINES} 行上限（当前 ${meta.lineCount} 行），请将详细规范拆到 references/`);
      if (!meta.name) list.push('配置头缺少标识 name');
      else if (!NAME_RE.test(meta.name) || meta.name.includes('--'))
        list.push(`标识 name「${meta.name}」仅允许小写字母、数字和连字符 "-"，不超过 64 字符`);
      else if (VERSION_IN_NAME_RE.test(meta.name)) list.push(`标识 name「${meta.name}」不能包含版本号（如 -v1、-1-0）`);

      if (!meta.description) list.push('配置头缺少简介 description（说明做什么与何时使用）');
      else if (descLen > MAX_DESCRIPTION) list.push(`简介 description 超过 ${MAX_DESCRIPTION} 字上限（当前 ${descLen} 字）`);

      if (meta.hasCompatibilityField && (!meta.compatibility || VAGUE_COMPAT_RE.test(meta.compatibility))) {
        list.push('compatibility 描述过于笼统，请写明具体运行环境、工具或接口依赖（不得只写「兼容飞书」）');
      }
    }
    if (skillFile.data) {
      const secrets = scanTextSecrets(skillFile.data);
      if (secrets.length) list.push(`SKILL.md 检测到敏感信息（${secrets.join('、')}），请脱敏后再发布`);
    }
    const junkFiles = (files.data ?? []).map((f) => f.path).filter(isJunkPath);
    if (junkFiles.length) list.push(`包含无关缓存或临时文件：${junkFiles.slice(0, 3).join('、')}${junkFiles.length > 3 ? ' 等' : ''}`);
    return list;
  }, [trimmedDisplay, displayLen, loading, skillPath, meta, descLen, skillFile.data, files.data]);

  const loadError = files.error || skillFile.error;
  const canPublish = !loading && !loadError && issues.length === 0 && !!versionId;
  const comingSoon = app.skillhub?.enabled === false;

  const publish = async () => {
    if (!canPublish || busy) return;
    const body = { versionId, displayName: trimmedDisplay, visibility, changelog: changelog.trim() };
    const sig = JSON.stringify(body);
    if (keyRef.current?.sig !== sig) keyRef.current = { sig, key: newIdempotencyKey() };
    setBusy(true);
    setError('');
    setInfo('');
    try {
      const res = await api.skillhubPublish(app.id, body, keyRef.current.key);
      keyRef.current = null;
      setResult(res.url);
      toast('已发布到 SkillHub。');
    } catch (err) {
      if (err instanceof ApiError && err.code === 'SKILLHUB_NOT_CONFIGURED') setInfo(err.message);
      else setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  if (result)
    return (
      <Modal title="发布到 SkillHub" overline="SKILLHUB" onClose={onClose} className="skillhub-modal">
        <div className="publish-art">
          <Icon name="cloud" />
          <i>✦</i>
        </div>
        <h3 className="center">{trimmedDisplay || meta?.name || app.name} 已发布</h3>
        <label className="field-label">技能地址</label>
        <div className="copy-field">
          <span>{result}</span>
          <button className="icon-button" aria-label="复制技能地址" onClick={async () => toast((await copyText(result)) ? '技能地址已复制。' : '请手动复制地址。')}>
            <Icon name="copy" />
          </button>
        </div>
        <div className="skillhub-actions">
          <button className="subtle-button" onClick={onClose}>
            完成
          </button>
          <button className="primary-button" aria-label="打开技能地址" onClick={() => window.open(result, '_blank', 'noopener')}>
            打开 <Icon name="external" />
          </button>
        </div>
      </Modal>
    );

  return (
    <Modal
      title="发布到 SkillHub"
      overline="SKILLHUB"
      onClose={onClose}
      className="skillhub-modal"
      footer={
        <>
          <button className="subtle-button" aria-label="下载技能包" disabled={!versionId} onClick={() => onDownload(versionId)}>
            <Icon name="download" />
            下载技能包
          </button>
          <button className="primary-button" aria-label="确认发布到 SkillHub" disabled={!canPublish || busy} onClick={() => void publish()}>
            {busy ? '正在发布…' : '发布到 SkillHub'}
            {comingSoon ? <em className="soon-badge">未配置</em> : null}
          </button>
        </>
      }
    >
      <div className="skill-name-field">
        <div className="skill-field-head">
          <label className="field-label" htmlFor="skill-display-name">
            SkillHub 显示名称
          </label>
          <span className={`skill-char-count ${displayLen > MAX_DISPLAY_NAME ? 'is-over' : ''}`}>
            {displayLen} / {MAX_DISPLAY_NAME} 字
          </span>
        </div>
        <input
          id="skill-display-name"
          className="text-input"
          type="text"
          value={displayName}
          placeholder="例：t11 - 储位告警查询（厂区/业务域/系统 - 场景用途）"
          onChange={(e) => setDisplayName(e.target.value)}
        />
      </div>

      <section className="skill-details" aria-label="SkillHub 规范自检">
        {loading ? (
          <p className="muted-note">正在检查 SKILL.md 与规范合规性…</p>
        ) : loadError ? (
          <p className="inline-error">{errorMessage(loadError)}</p>
        ) : (
          <>
            <div className="skill-detail-row">
              <span>标识</span>
              <b>{meta?.name || <i className="skill-missing">未填写</i>}</b>
            </div>
            <div className="skill-detail-row">
              <span>简介</span>
              <p>
                {meta?.description || <i className="skill-missing">未填写</i>}
                {meta?.description ? (
                  <small className={`skill-inline-count ${descLen > MAX_DESCRIPTION ? 'is-over' : ''}`}>
                    {' '}({descLen}/{MAX_DESCRIPTION} 字)
                  </small>
                ) : null}
              </p>
            </div>
            {meta?.hasCompatibilityField ? (
              <div className="skill-detail-row">
                <span>依赖</span>
                <p>{meta.compatibility || <i className="skill-missing">空</i>}</p>
              </div>
            ) : null}
            {skillPath && meta ? (
              <small className="muted-note">
                来自 {skillPath} · 共 {meta.lineCount} / {MAX_SKILL_LINES} 行
              </small>
            ) : null}
          </>
        )}
        {issues.length ? (
          <div className="inline-error skill-issues" role="alert">
            <b>发布前规范检测未通过：</b>
            <ul>
              {issues.map((msg, i) => (
                <li key={i}>{msg}</li>
              ))}
            </ul>
            <span>可在「代码」里编辑 SKILL.md 或在对话中让织页按 SkillHub 规范自动修复。</span>
          </div>
        ) : !loading && !loadError ? (
          <p className="skill-check-pass">规范性与安全预检通过（正式发布时服务端将再次校验全包文件）。</p>
        ) : null}
      </section>

      <label className="field-label" htmlFor="skill-version">
        发布版本
      </label>
      <Select
        id="skill-version"
        ariaLabel="发布版本"
        className="field block"
        value={versionId}
        onChange={setVersionId}
        options={
          versions.length
            ? versions.map((v) => ({ value: v.id, label: `V${v.number} · ${v.title}${v.isCurrent ? '（当前）' : ''}` }))
            : app.currentVersion
              ? [{ value: app.currentVersion.id, label: `V${app.currentVersion.number} · ${app.currentVersion.title}（当前）` }]
              : []
        }
      />

      <span className="field-label">使用范围</span>
      <div className="vis-group" role="radiogroup" aria-label="使用范围">
        {VIS.map(([v, label, desc]) => (
          <button key={v} type="button" role="radio" aria-checked={visibility === v} value={v} className={visibility === v ? 'active' : ''} onClick={() => setVisibility(v)}>
            <b>{label}</b>
            <small>{desc}</small>
          </button>
        ))}
      </div>

      {app.skillhub?.lastPublishedAt ? (
        <>
          <label className="field-label" htmlFor="skill-changelog">
            更新说明
          </label>
          <textarea
            id="skill-changelog"
            className="text-input"
            aria-label="更新说明"
            rows={3}
            maxLength={2000}
            placeholder="这一版改了什么，例如：新增储位异常趋势统计脚本"
            value={changelog}
            onChange={(e) => setChangelog(e.target.value)}
          />
        </>
      ) : (
        <p className="muted-note">涉及高密数据或生产操作的高风险技能须先经 AI BP 与业务/安全负责人评估并灰度验收。</p>
      )}
      {info ? (
        <p className="notice-box skillhub-info" role="status">
          {info}
        </p>
      ) : null}
      {error ? (
        <p className="inline-error" role="alert">
          {error}
        </p>
      ) : null}
      {app.skillhub?.lastPublishedAt ? (
        <p className="muted-note">
          上次发布：{new Date(app.skillhub.lastPublishedAt).toLocaleString('zh-CN')}
          {app.skillhub.url ? (
            <>
              {' · '}
              <a href={app.skillhub.url} target="_blank" rel="noopener noreferrer">
                查看
              </a>
            </>
          ) : null}
        </p>
      ) : null}
    </Modal>
  );
}
