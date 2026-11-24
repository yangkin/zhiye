import { useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { api, errorMessage } from '../api/client';
import type { HelpKnowledgeArticle } from '../api/types';
import { toast } from '../lib/store';
import { fmtDate, fmtTime } from '../lib/format';
import { Icon } from '../lib/icons';
import { DashEmptyBox } from './DashboardCharts';
import { Modal } from './Modal';

const CATEGORIES = [
  { value: 'limits', label: '资源限制' },
  { value: 'monolith', label: '单体托管' },
  { value: 'preflight', label: '预检规范' },
  { value: 'cloud', label: '云服务与边界' },
  { value: 'guide', label: '使用指南' },
  { value: 'faq', label: '常见问题' },
];

function renderPreviewMd(md: string): string {
  const raw = marked.parse(md || '', { async: false }) as string;
  return DOMPurify.sanitize(raw, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button', 'iframe', 'img'],
    FORBID_ATTR: ['style'],
  });
}

function fmtShortStamp(iso?: string | null): string {
  if (!iso) return '—';
  const d = fmtDate(iso);
  const t = fmtTime(iso);
  return `${d.replace(/^\d{4}-/, '')} ${t}`;
}

export function AdminKnowledgePanel() {
  const qc = useQueryClient();
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['admin-help-knowledge'],
    queryFn: api.adminHelpKnowledgeList,
  });
  const items = data?.items ?? [];

  const [catFilter, setCatFilter] = useState<string>('all');
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState<Partial<HelpKnowledgeArticle> | null>(null);
  const [previewing, setPreviewing] = useState<HelpKnowledgeArticle | null>(null);
  const [saving, setSaving] = useState(false);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return items.filter((a) => {
      if (catFilter !== 'all' && a.category !== catFilter) return false;
      if (!q) return true;
      return (
        a.title.toLowerCase().includes(q) ||
        a.slug.toLowerCase().includes(q) ||
        (a.summary || '').toLowerCase().includes(q) ||
        (a.keywords || '').toLowerCase().includes(q)
      );
    });
  }, [items, catFilter, query]);

  const catCounts = useMemo(() => {
    const m: Record<string, number> = {};
    for (const a of items) m[a.category] = (m[a.category] ?? 0) + 1;
    return m;
  }, [items]);

  const previewHtml = useMemo(
    () => (editing ? renderPreviewMd(editing.contentMd ?? '') : ''),
    [editing?.contentMd],
  );

  const openNew = () =>
    setEditing({
      slug: '',
      category: 'guide',
      title: '',
      shortTitle: '',
      badge: '',
      summary: '',
      contentMd: '',
      keywords: '',
      askPrompt: '',
      sortOrder: (items.length + 1) * 10,
      forDocs: true,
      forBot: true,
      enabled: true,
    });

  const save = async () => {
    if (!editing) return;
    if (!editing.title?.trim() || !editing.contentMd?.trim()) {
      toast('请填写标题和 Markdown 正文内容', 'error');
      return;
    }
    setSaving(true);
    try {
      if (editing.id) {
        await api.adminHelpKnowledgeUpdate(editing.id, editing);
        toast('知识库条目已更新，帮助文档与小织机器人已实时生效。');
      } else {
        await api.adminHelpKnowledgeCreate(editing);
        toast('已新增知识库条目，帮助文档与小织机器人已实时生效。');
      }
      setEditing(null);
      await refetch();
      void qc.invalidateQueries({ queryKey: ['help-docs'] });
    } catch (e) {
      toast(errorMessage(e, '保存失败'), 'error');
    } finally {
      setSaving(false);
    }
  };

  const toggleFlag = async (a: HelpKnowledgeArticle, field: 'enabled' | 'forDocs' | 'forBot') => {
    try {
      await api.adminHelpKnowledgeUpdate(a.id, { [field]: !a[field] });
      await refetch();
      void qc.invalidateQueries({ queryKey: ['help-docs'] });
      toast('已更新并实时生效。');
    } catch (e) {
      toast(errorMessage(e, '更新失败'), 'error');
    }
  };

  const remove = async (a: HelpKnowledgeArticle) => {
    if (!window.confirm(`确定删除知识条目「${a.title}」吗？删除后帮助文档与小织都将不再展示。`)) return;
    try {
      await api.adminHelpKnowledgeDelete(a.id);
      await refetch();
      void qc.invalidateQueries({ queryKey: ['help-docs'] });
      toast('已删除知识条目。');
    } catch (e) {
      toast(errorMessage(e, '删除失败'), 'error');
    }
  };

  return (
    <section className="gov-kb-panel">
      {/* 顶栏 */}
      <header className="gov-kb-head">
        <div className="gov-kb-head-left">
          <span className="gov-kb-head-icon" aria-hidden="true">
            <Icon name="book" />
          </span>
          <div>
            <h3 className="gov-kb-head-title">
              帮助文档与小织知识库
              <span className="gov-kb-live-pill">免发版实时生效</span>
            </h3>
            <p className="gov-kb-head-sub">
              单一事实来源：修改并保存后，左侧「帮助文档」抽屉与「小织」机器人回答上下文同步更新。
            </p>
          </div>
        </div>
        <button type="button" className="primary-button" onClick={openNew}>
          <Icon name="plus" />
          新增知识条目
        </button>
      </header>

      {/* 编辑器（新增 / 编辑时展开，左编辑 · 右实时渲染预览） */}
      {editing ? (
        <div className="gov-kb-editor">
          <div className="gov-kb-editor-head">
            <b style={{ fontSize: 14, color: '#1d1830' }}>
              {editing.id ? `编辑知识条目：${editing.title}` : '新增知识条目'}
            </b>
            <button type="button" className="subtle-button" onClick={() => setEditing(null)}>
              取消
            </button>
          </div>
          <div className="gov-kb-editor-grid">
            <label className="svc-field">
              <span>标题 *</span>
              <input
                type="text"
                value={editing.title ?? ''}
                onChange={(e) => setEditing({ ...editing, title: e.target.value })}
                placeholder="例如：平台资源与配额限制总览"
              />
            </label>
            <label className="svc-field">
              <span>章节简称</span>
              <input
                type="text"
                value={editing.shortTitle ?? ''}
                onChange={(e) => setEditing({ ...editing, shortTitle: e.target.value })}
                placeholder="如：资源限制"
              />
            </label>
            <label className="svc-field">
              <span>分类</span>
              <select
                value={editing.category ?? 'guide'}
                onChange={(e) => setEditing({ ...editing, category: e.target.value })}
                style={{ height: 38, borderRadius: 10, border: '1px solid #e2dcf0', padding: '0 10px', background: '#fff' }}
              >
                {CATEGORIES.map((c) => (
                  <option key={c.value} value={c.value}>
                    {c.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="svc-field">
              <span>标识 slug</span>
              <input
                type="text"
                disabled={!!editing.id}
                value={editing.slug ?? ''}
                onChange={(e) => setEditing({ ...editing, slug: e.target.value })}
                placeholder="留空自动生成"
              />
            </label>
            <label className="svc-field">
              <span>排序</span>
              <input
                type="number"
                value={editing.sortOrder ?? 100}
                onChange={(e) => setEditing({ ...editing, sortOrder: Number(e.target.value) })}
              />
            </label>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
            <label className="svc-field">
              <span>一句话摘要</span>
              <input
                type="text"
                value={editing.summary ?? ''}
                onChange={(e) => setEditing({ ...editing, summary: e.target.value })}
                placeholder="显示在文档列表与章节顶部说明"
              />
            </label>
            <label className="svc-field">
              <span>搜索关键词（逗号分隔）</span>
              <input
                type="text"
                value={editing.keywords ?? ''}
                onChange={(e) => setEditing({ ...editing, keywords: e.target.value })}
                placeholder="如：1C,1G,休眠,配额"
              />
            </label>
          </div>
          <div className="gov-kb-split">
            <label className="svc-field">
              <span>Markdown 正文（支持标题、列表与表格）*</span>
              <textarea
                rows={12}
                value={editing.contentMd ?? ''}
                onChange={(e) => setEditing({ ...editing, contentMd: e.target.value })}
                style={{
                  width: '100%',
                  borderRadius: 10,
                  border: '1px solid #dfd8ee',
                  padding: 12,
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                  fontSize: 12.5,
                  lineHeight: 1.6,
                  background: '#fff',
                  resize: 'vertical',
                }}
              />
            </label>
            <div className="svc-field">
              <span>实时效果预览（同左侧帮助文档样式）</span>
              <div
                className="gov-kb-preview-box"
                dangerouslySetInnerHTML={{
                  __html: previewHtml || '<p style="color:#9a92ad">在左侧输入 Markdown 内容后，此处实时渲染预览效果…</p>',
                }}
              />
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
            <div style={{ display: 'flex', gap: 18, fontSize: 13, color: '#39324d' }}>
              <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={editing.forDocs !== false}
                  onChange={(e) => setEditing({ ...editing, forDocs: e.target.checked })}
                />
                在「帮助文档」展示
              </label>
              <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={editing.forBot !== false}
                  onChange={(e) => setEditing({ ...editing, forBot: e.target.checked })}
                />
                注入「小织」机器人知识库
              </label>
              <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={editing.enabled !== false}
                  onChange={(e) => setEditing({ ...editing, enabled: e.target.checked })}
                />
                启用状态
              </label>
            </div>
            <button type="button" className="primary-button" disabled={saving} onClick={() => void save()}>
              {saving ? '保存中…' : '保存并立即生效'}
            </button>
          </div>
        </div>
      ) : null}

      {/* 筛选与搜索条 */}
      <div className="gov-kb-toolbar">
        <div className="gov-kb-filters" role="tablist" aria-label="按分类筛选知识条目">
          <button
            type="button"
            role="tab"
            aria-selected={catFilter === 'all'}
            className={`gov-kb-filter-btn ${catFilter === 'all' ? 'active' : ''}`}
            onClick={() => setCatFilter('all')}
          >
            全部 {items.length}
          </button>
          {CATEGORIES.filter((c) => (catCounts[c.value] ?? 0) > 0 || catFilter === c.value).map((c) => (
            <button
              key={c.value}
              type="button"
              role="tab"
              aria-selected={catFilter === c.value}
              className={`gov-kb-filter-btn ${catFilter === c.value ? 'active' : ''}`}
              onClick={() => setCatFilter(c.value)}
            >
              {c.label} {catCounts[c.value] ?? 0}
            </button>
          ))}
        </div>
        <label className="gov-kb-search">
          <Icon name="search" />
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="搜索标题、标识或关键词…"
            aria-label="搜索知识库"
          />
        </label>
      </div>

      {/* 条目列表 */}
      {isLoading ? (
        <div style={{ padding: 24 }}>
          <p className="muted-note">正在加载知识库条目…</p>
        </div>
      ) : error ? (
        <div style={{ padding: 24 }}>
          <p className="inline-error">{errorMessage(error, '加载知识库失败')}</p>
        </div>
      ) : filtered.length === 0 ? (
        <div style={{ padding: 28 }}>
          <DashEmptyBox
            icon="book"
            title={items.length === 0 ? '暂无知识条目' : '没有匹配的知识条目'}
            hint={items.length === 0 ? '点击右上角「新增知识条目」开始维护平台文档与小织知识库。' : '试试切换分类或清空搜索关键词。'}
          />
        </div>
      ) : (
        <div className="gov-kb-list">
          <div className="gov-kb-list-head" aria-hidden="true">
            <span>排序</span>
            <span>文档标题与摘要</span>
            <span>分类</span>
            <span>生效范围（点击切换）</span>
            <span>最近更新</span>
            <span style={{ textAlign: 'right' }}>操作</span>
          </div>
          {filtered.map((a) => {
            const catLabel = CATEGORIES.find((c) => c.value === a.category)?.label ?? a.category;
            return (
              <div key={a.id} className={`gov-kb-row ${a.enabled ? '' : 'is-disabled'}`}>
                <div>
                  <span className="gov-kb-order">{a.sortOrder}</span>
                </div>
                <div className="gov-kb-main">
                  <div className="gov-kb-title-line">
                    <button type="button" className="gov-kb-title-btn" onClick={() => setPreviewing(a)}>
                      {a.title}
                    </button>
                    {a.badge ? <span className="gov-kb-badge">{a.badge}</span> : null}
                    <code className="gov-kb-slug">{a.slug}</code>
                  </div>
                  <p className="gov-kb-summary" title={a.summary || ''}>
                    {a.summary || '暂无摘要'}
                  </p>
                </div>
                <div>
                  <span className={`gov-kb-cat is-${a.category}`}>{catLabel}</span>
                </div>
                <div className="gov-kb-switches">
                  <button
                    type="button"
                    className={`gov-kb-chip is-doc ${a.forDocs ? 'on' : ''}`}
                    title="点击切换是否在左侧「帮助文档」展示"
                    onClick={() => void toggleFlag(a, 'forDocs')}
                  >
                    帮助文档
                  </button>
                  <button
                    type="button"
                    className={`gov-kb-chip is-bot ${a.forBot ? 'on' : ''}`}
                    title="点击切换是否注入「小织」机器人知识库"
                    onClick={() => void toggleFlag(a, 'forBot')}
                  >
                    小织知识
                  </button>
                  <button
                    type="button"
                    className={`gov-kb-chip is-live ${a.enabled ? 'on' : ''}`}
                    title="点击切换整体启用/停用状态"
                    onClick={() => void toggleFlag(a, 'enabled')}
                  >
                    {a.enabled ? '已启用' : '已停用'}
                  </button>
                </div>
                <div className="gov-kb-meta">
                  <span>{fmtShortStamp(a.updatedAt)}</span>
                  <small>{a.updatedBy || 'system'}</small>
                </div>
                <div className="gov-kb-actions">
                  <button type="button" className="gov-kb-act" onClick={() => setPreviewing(a)}>
                    预览
                  </button>
                  <button type="button" className="gov-kb-act" onClick={() => setEditing(a)}>
                    编辑
                  </button>
                  <button type="button" className="gov-kb-act is-danger" onClick={() => void remove(a)}>
                    删除
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* 快速预览弹窗 */}
      {previewing ? (
        <Modal title={`文档预览 · ${previewing.title}`} onClose={() => setPreviewing(null)} wide>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', fontSize: 12.5, color: '#6b6380' }}>
              <span className={`gov-kb-cat is-${previewing.category}`}>
                {CATEGORIES.find((c) => c.value === previewing.category)?.label ?? previewing.category}
              </span>
              <code className="gov-kb-slug">{previewing.slug}</code>
              <span>最近更新：{fmtShortStamp(previewing.updatedAt)}</span>
              <span>操作人：{previewing.updatedBy || 'system'}</span>
            </div>
            {previewing.summary ? (
              <p className="muted-note" style={{ margin: 0, background: '#f8f6fc', padding: '8px 12px', borderRadius: 8 }}>
                {previewing.summary}
              </p>
            ) : null}
            <div
              className="gov-kb-preview-box"
              style={{ maxHeight: 460 }}
              dangerouslySetInnerHTML={{ __html: renderPreviewMd(previewing.contentMd) }}
            />
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
              <button
                type="button"
                className="primary-button"
                onClick={() => {
                  const target = previewing;
                  setPreviewing(null);
                  setEditing(target);
                }}
              >
                编辑此条目
              </button>
            </div>
          </div>
        </Modal>
      ) : null}
    </section>
  );
}
