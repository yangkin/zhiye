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

/** 从 SKILL.md 的 YAML front matter 读出 name / description（只支持这两个键的常见写法）。 */
export function parseSkillFrontmatter(src: string): { name: string; description: string; hasFrontmatter: boolean } {
  const m = /^﻿?---\r?\n([\s\S]*?)\r?\n---\s*(\r?\n|$)/.exec(src);
  if (!m) return { name: '', description: '', hasFrontmatter: false };
  const lines = m[1].split(/\r?\n/);
  const read = (key: string) => {
    const i = lines.findIndex((l) => new RegExp(`^${key}\\s*:`).test(l));
    if (i < 0) return '';
    let v = lines[i].replace(new RegExp(`^${key}\\s*:\\s*`), '');
    if (/^[>|][+-]?\s*$/.test(v)) {
      // 折叠 / 多行块
      const block: string[] = [];
      for (let j = i + 1; j < lines.length && (/^\s+/.test(lines[j]) || !lines[j].trim()); j++) block.push(lines[j].trim());
      v = block.join(v.startsWith('|') ? '\n' : ' ').trim();
    }
    v = v.trim();
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
    return v.trim();
  };
  return { name: read('name'), description: read('description'), hasFrontmatter: true };
}

const VIS: [SkillVisibility, string, string][] = [
  ['private', '仅自己', '只有你能看到和安装'],
  ['team', '团队', '同一团队的成员可以使用'],
  ['public', '公开', '所有人都可以搜索和安装'],
];

interface Props {
  app: AppDetail;
  versions: Version[];
  onDownload: (versionId: string) => void;
  onClose: () => void;
}

export function SkillhubModal({ app, versions, onDownload, onClose }: Props) {
  const [versionId, setVersionId] = useState(app.currentVersion?.id ?? versions[0]?.id ?? '');
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
  const missing: string[] = [];
  if (!loading) {
    if (!skillPath) missing.push('SKILL.md 文件');
    else if (meta && !meta.hasFrontmatter) missing.push('SKILL.md 开头的 front matter（--- 包围的 name / description）');
    else if (meta) {
      if (!meta.name) missing.push('name（技能名称）');
      if (!meta.description) missing.push('description（技能描述）');
    }
  }
  const loadError = files.error || skillFile.error;
  const canPublish = !loading && !loadError && missing.length === 0 && !!versionId;
  const comingSoon = app.skillhub?.enabled === false;

  const publish = async () => {
    if (!canPublish || busy) return;
    const body = { versionId, visibility, changelog: changelog.trim() };
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
        <h3 className="center">{meta?.name || app.name} 已发布</h3>
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
      <section className="skill-details" aria-label="技能信息">
        {loading ? (
          <p className="muted-note">正在读取 SKILL.md…</p>
        ) : loadError ? (
          <p className="inline-error">{errorMessage(loadError)}</p>
        ) : (
          <>
            <div className="skill-detail-row">
              <span>名称</span>
              <b>{meta?.name || <i className="skill-missing">未填写</i>}</b>
            </div>
            <div className="skill-detail-row">
              <span>描述</span>
              <p>{meta?.description || <i className="skill-missing">未填写</i>}</p>
            </div>
            {skillPath ? <small className="muted-note">来自 {skillPath}</small> : null}
          </>
        )}
        {missing.length ? (
          <p className="inline-error" role="alert">
            还不能发布：缺少 {missing.join('、')}。可以在「代码」里编辑 SKILL.md 补上后再发布。
          </p>
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
      <span className="field-label">可见范围</span>
      <div className="vis-group" role="radiogroup" aria-label="可见范围">
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
            placeholder="这一版改了什么，例如：新增 PDF 表格提取脚本"
            value={changelog}
            onChange={(e) => setChangelog(e.target.value)}
          />
        </>
      ) : (
        <p className="muted-note">首次发布将创建技能并提交审核；再次发布时可以填写更新说明。</p>
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
