import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { useNavigate } from 'react-router-dom';
import { create } from 'zustand';
import { useQuery } from '@tanstack/react-query';
import { WorkCard } from '../components/Community';
import { api, errorMessage } from '../api/client';
import { TaskLimitLink, errorCode } from '../components/LimitNotice';
import { useActiveTask, useMeta } from '../api/queries';
import { APP_TYPES, type AppType, type Asset, type Mode } from '../api/types';
import { TEMPLATES, domainOf, featuredTemplates, templatesOf, type SceneTemplate } from '../lib/templates';
import { SceneThumb } from '../components/SceneThumb';
import { Pet } from '../components/Pet';
import { TemplateIcon } from '../lib/templateIcons';
import { Icon, kindIcon } from '../lib/icons';
import { toast, useUi } from '../lib/store';
import { AddMenuButton, SourceChips, handleAssetPaste } from '../components/AddMenu';
import { ModelModeButton, PolishButton, SkillChips, SkillPicker, mentionAt, useModelList } from '../components/ComposerPopovers';
import { EcosystemChips, EcosystemPickerModal, appendEcosystemContext, type EcosystemItem } from '../components/EcosystemPicker';
import { Modal } from '../components/Modal';
import { loadDraft, saveDraft } from '../lib/drafts';

interface CreateForm {
  type: AppType;
  prompt: string;
  attachments: Asset[];
  skills: { id: string; name: string }[];
  sources: { id: string; name: string }[];
  model: string;
  mode: Mode;
  /** 用户明确点选了类型（默认不选，由模型判断）；和 type 一起保存，离开再回来不丢 */
  typeChosen: boolean;
  set: (p: Partial<Omit<CreateForm, 'set'>>) => void;
}

type CreateDraft = Omit<CreateForm, 'set'>;
const savedCreate = loadDraft<Partial<CreateDraft>>('create');

export const useCreateForm = create<CreateForm>((set) => ({
  type: '开发网站',
  prompt: '',
  attachments: [],
  skills: [],
  sources: [],
  model: 'auto',
  mode: '适中',
  typeChosen: false,
  ...savedCreate,
  set: (p) => set(p),
}));

// 没发出去的需求存在本地：刷新、误关页面后还在；发送成功后表单清空，草稿也跟着清掉
useCreateForm.subscribe((s) => {
  const { set: _set, ...draft } = s;
  const empty = !draft.prompt.trim() && !draft.attachments.length && !draft.skills.length && !draft.sources.length && !draft.typeChosen;
  saveDraft('create', empty ? null : draft);
});

/**
 * 判断这句话是「问一句」还是「要做出来」。
 * 创造页里 @ 技能 问「这是干嘛的」这类问题应当直接回答，而不是建一个应用。
 * 只要有明确的生产动词就按需求处理（更安全：用户可以随时停止）。
 */
const BUILD_WORDS = /(帮我做|做一个|做个|做出来|实现|生成|搭建|创建|开发|写一个|写个|设计一|改一下|优化一下|部署|上线|重构|替换|加上|增加一个)/;
const QUESTION_WORDS = /(\?|？|吗|呢|怎么|如何|为什么|什么|干嘛|干吗|介绍|能不能|可不可以|区别|用处|用途|有哪些|支持|适合|是不是)/;
/** 明确「现在就要做」：有生产动词，且不是在问问题 */
export function isIntentToBuild(text: string): boolean {
  const t = text.trim();
  if (!t) return false;
  if (/[?？]\s*$/.test(t)) return false;
  return BUILD_WORDS.test(t);
}

export function isQuestion(text: string): boolean {
  const t = text.trim();
  if (!t || t.length > 160) return false;
  // 以问号结尾通常是提问，即使句子里有「实现/生成」这类词（例如「顶栏是怎么实现的？」）；
  // 明确以「帮我/给我/请」开头的命令句仍按需求处理。
  if (/[?？]\s*$/.test(t) && !/^(帮我|给我|请|帮忙)/.test(t)) return true;
  if (BUILD_WORDS.test(t)) return false;
  return QUESTION_WORDS.test(t);
}

export type Route = 'ask' | 'modify' | 'model';

export function quickRoute(text: string, hasElement = false): Route {
  const t = text.trim();
  const asked = /[?？]\s*$/.test(t) && !/^(帮我|给我|请|帮忙)/.test(t);
  if (hasElement) return asked ? 'model' : 'modify';
  if (asked) return 'ask';
  if (t.length <= 80 && BUILD_WORDS.test(t) && !QUESTION_WORDS.test(t)) return 'modify';
  if (t.length <= 80 && QUESTION_WORDS.test(t) && !BUILD_WORDS.test(t)) return 'ask';
  return 'model';
}

/** 创造页的一段对话（只存在浏览器里，点「开始做」时把用户说过的话合成需求） */
interface Turn {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  skills?: { id: string; name: string }[];
  busy?: boolean;
  error?: string;
}
const THREAD_KEY = 'zhiye:create-thread:v1';
function loadThread(): Turn[] {
  try {
    const raw = localStorage.getItem(THREAD_KEY);
    const list = raw ? (JSON.parse(raw) as Turn[]) : [];
    return Array.isArray(list) ? list.filter((t) => t && typeof t.content === 'string').slice(-24) : [];
  } catch {
    return [];
  }
}
function saveThread(list: Turn[]) {
  try {
    localStorage.setItem(THREAD_KEY, JSON.stringify(list.filter((t) => !t.busy).slice(-24)));
  } catch {
    /* 隐私模式下写不进去就算了 */
  }
}

const sanitizeMarkdown = (src: string) =>
  DOMPurify.sanitize(marked.parse(src, { async: false, gfm: true }) as string, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button', 'img', 'iframe'],
  });

const HEADLINES = ['让想法成形', '一句话成应用', '把灵感织成页', '改一句就生效', '想好就上线'];

export function CreatePage() {
  const f = useCreateForm();
  const navigate = useNavigate();
  const { data: meta } = useMeta();
  const active = useActiveTask(5000).data ?? null;
  const [picker, setPicker] = useState<null | { via: 'button' } | { via: 'mention'; start: number; query: string }>(null);
  const [ecoOpen, setEcoOpen] = useState(false);
  const [ecosystems, setEcosystems] = useState<EcosystemItem[]>([]);
  const toggleEco = (item: EcosystemItem) =>
    setEcosystems((prev) => (prev.some((x) => x.id === item.id) ? prev.filter((x) => x.id !== item.id) : [...prev, item]));
  // 默认不选类型：直接进对话，类型由模型在构建时判断；也可以手动指定一个类型作为提示
  const typeChosen = f.typeChosen;
  const setTypeChosen = (typeChosen: boolean) => f.set({ typeChosen });
  const skillBtn = useRef<HTMLButtonElement>(null);
  const ecoBtn = useRef<HTMLButtonElement>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [errorKind, setErrorKind] = useState('');
  // 创造页的对话：问一句就回答，聊清楚了再点「开始做」
  const [thread, setThread] = useState<Turn[]>(loadThread);
  const askAbort = useRef<AbortController | null>(null);
  useEffect(() => {
    saveThread(thread);
  }, [thread]);
  const textarea = useRef<HTMLTextAreaElement>(null);

  const { models, defaultId } = useModelList();
  const modes: Mode[] = meta?.modes?.length ? meta.modes : ['快速', '适中', '深度'];
  // 新会话优先选免费模型（用户自己点过就不再改）
  const modelTouched = useRef(false);
  useEffect(() => {
    if (!modelTouched.current && defaultId && defaultId !== 'auto' && (f.model === 'auto' || !f.model)) f.set({ model: defaultId });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [defaultId]);

  useEffect(() => {
    textarea.current?.focus();
  }, []);

  // 输入框高度按屏幕视口高度自适应（短屏笔记本克制不挤掉下方模板，大屏舒展展开），随窗口缩放实时调整
  useLayoutEffect(() => {
    const syncHeight = () => {
      const el = textarea.current;
      if (!el) return;
      const vh = window.innerHeight || 800;
      const minH = Math.round(Math.min(132, Math.max(88, vh * 0.135)));
      const maxH = Math.round(Math.min(260, Math.max(144, vh * 0.24)));
      el.style.minHeight = `${minH}px`;
      el.style.maxHeight = `${maxH}px`;
      el.style.height = 'auto';
      el.style.height = `${Math.min(maxH, Math.max(minH, el.scrollHeight))}px`;
    };
    syncHeight();
    window.addEventListener('resize', syncHeight);
    return () => window.removeEventListener('resize', syncHeight);
  }, [f.prompt]);

  /** 聊清楚了，用这段对话里的需求开始做 */
  const buildFromThread = async () => {
    const asks = thread.filter((t) => t.role === 'user' && t.content.trim());
    if (!asks.length) return toast('先说说你想做什么吧。');
    if (submitting || active) return;
    const brief = asks.map((t) => t.content.trim()).join('\n').slice(0, 4000);
    await submit(brief);
  };

  const submit = async (overridePrompt?: string) => {
    const prompt = (overridePrompt ?? f.prompt).trim();
    if (!prompt) return toast('先写下你的应用想法吧。', 'error');
    if (submitting) return;
    // 先进入会话；普通问答不受其他应用生成任务影响，真正开始生成时再检查任务限制
    setSubmitting(true);
    setError('');
    try {
      const conv = await api.createConversation(f.sources.map((s) => s.id));
      const fullPrompt = appendEcosystemContext(prompt, ecosystems);
      const state = {
        initialMessage: fullPrompt,
        ...(typeChosen ? { type: f.type } : {}),
        model: f.model,
        mode: f.mode,
        attachments: f.attachments,
        skills: f.skills,
        sources: f.sources,
      };
      f.set({ prompt: '', attachments: [], skills: [], sources: [], typeChosen: false });
      setEcosystems([]);
      navigate('/chat/' + conv.id, { state });
    } catch (err) {
      setError(errorMessage(err));
      setErrorKind(errorCode(err));
    } finally {
      setSubmitting(false);
    }
    return;
  };

  const [headline, setHeadline] = useState(0);
  const nextHeadline = () => setHeadline((h) => (h + 1) % HEADLINES.length);
  const openHelp = useUi((st) => st.openHelp);
  // 帮助抽屉里已经有一只小织，页面上这只先藏起来
  const helpOpen = useUi((st) => st.helpOpen);
  const [domain, setDomain] = useState('全部');
  const [templateQuery, setTemplateQuery] = useState('');
  const [galleryOpen, setGalleryOpen] = useState(false);
  const [galleryDomain, setGalleryDomain] = useState('全部');
  const [galleryType, setGalleryType] = useState<'全部' | AppType>('全部');
  const [galleryQuery, setGalleryQuery] = useState('');
  useEffect(() => setDomain('全部'), [f.type, typeChosen]);

  const activeTemplates = useMemo(
    () => (typeChosen ? templatesOf(f.type) : TEMPLATES),
    [typeChosen, f.type],
  );
  const domainCounts = useMemo(() => {
    const map = new Map<string, number>();
    for (const t of activeTemplates) {
      const d = domainOf(t);
      map.set(d, (map.get(d) ?? 0) + 1);
    }
    return [...map.entries()].sort((a, b) => b[1] - a[1]);
  }, [activeTemplates]);
  const allDomainCounts = useMemo(() => {
    const map = new Map<string, number>();
    for (const t of TEMPLATES) {
      if (galleryType !== '全部' && t.type !== galleryType) continue;
      const d = domainOf(t);
      map.set(d, (map.get(d) ?? 0) + 1);
    }
    return [...map.entries()].sort((a, b) => b[1] - a[1]);
  }, [galleryType]);
  const shownTemplates = useMemo(() => {
    const q = templateQuery.trim().toLowerCase();
    return activeTemplates.filter((t) => {
      if (domain !== '全部' && domainOf(t) !== domain) return false;
      if (!q) return true;
      return (
        t.title.toLowerCase().includes(q) ||
        t.desc.toLowerCase().includes(q) ||
        t.scene.toLowerCase().includes(q) ||
        t.type.toLowerCase().includes(q)
      );
    });
  }, [activeTemplates, domain, templateQuery]);
  // 首页精选：默认只展示 8 张高价值卡片（2×4 网格），完整 60+ 模板收口进宽幅分类抽屉，保持首页清爽与社区曝光
  const HOME_TEMPLATE_LIMIT = 8;
  const homeTemplates = useMemo(() => {
    if (domain === '全部' && !templateQuery.trim() && !typeChosen) {
      const picks = featuredTemplates();
      const pickedIds = new Set(picks.map((p) => p.id));
      const rest = shownTemplates.filter((t) => !pickedIds.has(t.id));
      return [...picks, ...rest].slice(0, HOME_TEMPLATE_LIMIT);
    }
    return shownTemplates.slice(0, HOME_TEMPLATE_LIMIT);
  }, [shownTemplates, domain, templateQuery, typeChosen]);
  const hiddenCount = Math.max(0, shownTemplates.length - homeTemplates.length);
  const galleryTemplates = useMemo(() => {
    const q = galleryQuery.trim().toLowerCase();
    return TEMPLATES.filter((t) => {
      if (galleryType !== '全部' && t.type !== galleryType) return false;
      if (galleryDomain !== '全部' && domainOf(t) !== galleryDomain) return false;
      if (!q) return true;
      return (
        t.title.toLowerCase().includes(q) ||
        t.desc.toLowerCase().includes(q) ||
        t.scene.toLowerCase().includes(q) ||
        t.type.toLowerCase().includes(q)
      );
    });
  }, [galleryDomain, galleryType, galleryQuery]);
  const openGallery = (initDomain?: string) => {
    setGalleryDomain(initDomain ?? (domain !== '全部' ? domain : '全部'));
    setGalleryType(typeChosen ? f.type : '全部');
    setGalleryQuery(templateQuery);
    setGalleryOpen(true);
  };
  const quickChips = useMemo(
    () => (typeChosen ? templatesOf(f.type).slice(0, 4) : featuredTemplates()),
    [typeChosen, f.type],
  );

  const works = useQuery({ queryKey: ['templates', { sort: 'likes' }], queryFn: () => api.templates({ sort: 'likes' }) });

  // 已经写了内容再点推荐：照样填进去，但给一次「撤销」找回原来的草稿和类型
  const fill = (tpl: SceneTemplate) => {
    const prevPrompt = f.prompt;
    const prevType = f.type;
    const prevChosen = f.typeChosen;
    f.set({ prompt: tpl.prompt, type: tpl.type, typeChosen: true });
    if (prevPrompt.trim() && prevPrompt.trim() !== tpl.prompt.trim()) {
      toast(`已填入「${tpl.title}」`, 'ok', {
        label: '撤销',
        run: () => useCreateForm.getState().set({ prompt: prevPrompt, type: prevType, typeChosen: prevChosen }),
      });
    } else {
      toast(`已填入「${tpl.title}」（${tpl.type}）`, 'ok');
    }
    window.scrollTo({ top: 0, behavior: 'smooth' });
    setTimeout(() => {
      const el = textarea.current;
      if (!el) return;
      el.focus();
      el.scrollTop = 0;
    }, 0);
  };

  return (
    <div className="create-page">
      <h1 className="cp-title">
        <button
          type="button"
          className="cp-title-text"
          aria-label={`${HEADLINES[headline]}，悬停或点击换一句`}
          onMouseEnter={nextHeadline}
          onClick={nextHeadline}
        >
          {[...HEADLINES[headline]].map((ch, i) => (
            <span key={headline + '-' + i} className="cp-char" style={{ animationDelay: `${i * 55}ms` }}>
              {ch}
            </span>
          ))}
        </button>
        <i className="cp-dot">·</i>
        <span className="cp-brand">织页</span>
      </h1>
      {thread.length ? (
        <section className="cp-thread" aria-label="对话">
          <header className="cp-thread-head">
            <b>和织页聊聊</b>
            <span>问清楚再开始做；做好了点下面「开始做」</span>
            <button type="button" onClick={() => { askAbort.current?.abort(); setThread([]); toast('已开始新对话'); }}>
              <Icon name="plus" /> 新对话
            </button>
          </header>
          <div className="cp-thread-list">
            {thread.map((t) =>
              t.role === 'user' ? (
                <div key={t.id} className="cp-tu">
                  <p>{t.content}</p>
                  {t.skills?.length ? <small>技能 · {t.skills.map((k) => k.name).join('、')}</small> : null}
                </div>
              ) : (
                <div key={t.id} className="cp-ta">
                  <span className="cp-ta-avatar">
                    <img src="/brand.svg" alt="" />
                  </span>
                  <div>
                    {t.content ? (
                      <div className="markdown-body" dangerouslySetInnerHTML={{ __html: sanitizeMarkdown(t.content) }} />
                    ) : (
                      <p className="cp-ask-status">
                        <Icon name="refresh" className="spinning" /> 正在回答…
                      </p>
                    )}
                    {t.error ? <p className="inline-error">{t.error}</p> : null}
                  </div>
                </div>
              ),
            )}
          </div>
          <footer className="cp-thread-foot">
            <button type="button" className="primary-button" disabled={submitting || !!active} onClick={() => void buildFromThread()}>
              <Icon name="spark" /> 开始做
            </button>
            <span>{active ? `「${active.appName}」正在生成中` : '会用你上面说过的需求去生成，确认计划后再执行'}</span>
          </footer>
        </section>
      ) : null}
      <section className="cp-card" aria-label="创造应用" style={{ ['--glow-x' as string]: '34%' }}>
        <div className="cp-glow" aria-hidden="true">
          <i />
          <i />
          <i />
        </div>
        <div className="cp-tabs" role="tablist" aria-label="应用类型（可不选）">
          {APP_TYPES.map((t) => (
            <button
              key={t}
              className={typeChosen && f.type === t ? 'selected' : ''}
              role="tab"
              aria-selected={typeChosen && f.type === t}
              title={typeChosen && f.type === t ? '再点一次取消，回到默认聊天' : `可选：提示类型为「${t}」`}
              onClick={() => {
                if (typeChosen && f.type === t) setTypeChosen(false);
                else f.set({ type: t, typeChosen: true });
              }}
            >
              <Icon name={kindIcon[t]} />
              {t}
            </button>
          ))}
        </div>
        <div className={`cp-pet ${helpOpen ? 'is-hidden' : ''}`}>
          <Pet size={120} onClick={() => openHelp('chat')} />
        </div>
        <div className="composer cp-composer">
        <textarea
          ref={textarea}
          id="home-prompt"
          rows={4}
          placeholder="直接说说你的想法，边聊边做，不用先选类型…"
          maxLength={3000}
          aria-label="应用需求"
          value={f.prompt}
          onChange={(e) => {
            f.set({ prompt: e.target.value });
            if (error) setError('');
            const m = mentionAt(e.target.value, e.target.selectionStart ?? e.target.value.length);
            if (m) setPicker({ via: 'mention', ...m });
            else if (picker?.via === 'mention') setPicker(null);
          }}
          onPaste={(e) => handleAssetPaste(e, (asset) => {
            const cur = useCreateForm.getState().attachments;
            if (cur.length < 5 && !cur.some((a) => a.id === asset.id)) f.set({ attachments: [...cur, asset] });
          }, f.attachments.length)}
          onKeyDown={(e) => {
            // 回车发送，Shift+Enter 换行；输入法组合中不触发
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              void submit();
            }
          }}
        />
        {f.sources.length ? (
          <div className="source-context" aria-label="新应用的数据源">
            <span className="source-context-label"><Icon name="database" /> 应用数据源 <small>创建后持续可用</small></span>
            <SourceChips sources={f.sources} onRemove={(id) => f.set({ sources: f.sources.filter((x) => x.id !== id) })} />
          </div>
        ) : null}
        {f.attachments.length || f.skills.length || ecosystems.length ? (
          <div className="attachments">
            <EcosystemChips items={ecosystems} onRemove={(id) => setEcosystems((prev) => prev.filter((x) => x.id !== id))} />
            <SkillChips skills={f.skills} onRemove={(id) => f.set({ skills: f.skills.filter((x) => x.id !== id) })} />
            {f.attachments.map((a) => (
              <span key={a.id}>
                <Icon name="file" />
                {a.name}
                <button
                  aria-label="移除附件"
                  onClick={() => f.set({ attachments: f.attachments.filter((x) => x.id !== a.id) })}
                >
                  <Icon name="close" />
                </button>
              </span>
            ))}
          </div>
        ) : null}
        <div className="composer-bottom">
          <div className="composer-tools">
            <AddMenuButton
              variant="create"
              attachedCount={f.attachments.length}
              onAttach={(asset) => {
                const cur = useCreateForm.getState().attachments;
                if (cur.length < 5 && !cur.some((a) => a.id === asset.id)) f.set({ attachments: [...cur, asset] });
              }}
              pickedSourceIds={f.sources.map((s) => s.id)}
              onPickSource={(s) => f.set({ sources: [...useCreateForm.getState().sources, { id: s.id, name: s.name }] })}
            />
            <ModelModeButton models={models} modes={modes} model={f.model} mode={f.mode} onModel={(m) => { modelTouched.current = true; f.set({ model: m }); }} onMode={(m) => f.set({ mode: m })} />
            <button
              ref={skillBtn}
              type="button"
              className="skill-button"
              aria-label="选择技能"
              aria-expanded={picker !== null}
              onClick={() => setPicker(picker ? null : { via: 'button' })}
            >
              <Icon name="book" />
              技能
            </button>
            <button
              ref={ecoBtn}
              type="button"
              className={`skill-button${ecosystems.length ? ' on' : ''}`}
              aria-label="选择企业生态能力"
              aria-expanded={ecoOpen}
              title="一键接入飞书机器人、多维表格、企业知识 MCP 或 AGW 接口"
              onClick={() => setEcoOpen((v) => !v)}
            >
              <Icon name="globe" />
              {ecosystems.length ? `生态 · ${ecosystems.length}` : '生态'}
            </button>
          </div>
          <div className="composer-actions">
            <PolishButton value={f.prompt} onPolished={(text) => f.set({ prompt: text })} />
            <button
              className="send-button"
              aria-label="发送"
              title="发到对话里继续聊；聊清楚后再开始生成"
              disabled={!f.prompt.trim() || submitting}
              onClick={() => void submit()}
            >
              <Icon name={submitting ? 'refresh' : 'send'} className={submitting ? 'spinning' : ''} />
            </button>
          </div>
        </div>
        </div>
      </section>



      {active ? (
        <p className="busy-note create-error" role="status">
          「{active.appName}」正在生成中，仍可继续聊天；再建应用需等它完成
          <button type="button" className="limit-link" onClick={() => navigate('/apps/' + active.appId)}>
            去查看
          </button>
        </p>
      ) : null}
      {error ? (
        <p className="inline-error create-error" role="alert">
          {error}
          {errorKind === 'TASK_LIMIT' ? <TaskLimitLink /> : null}
        </p>
      ) : null}
      <div className="prompt-chips">
        {quickChips.map((t) => (
          <button key={t.id} className="prompt-chip" title={`${t.title}（${t.type}）`} onClick={() => fill(t)}>
            <TemplateIcon id={t.id} size={14} />
            {t.title}
          </button>
        ))}
      </div>
      <div className="inspiration-section">
        <div className="section-title">
          <div>
            <h2>{typeChosen ? `${f.type} · 场景模板` : '华星场景模板'}</h2>
            <p>覆盖数据看板、日常工具、内部业务系统、Skill 与技术文档，点击即可填入完整需求继续微调。</p>
          </div>
          <div className="template-section-meta">
            {typeChosen ? (
              <button type="button" className="template-show-all" onClick={() => setTypeChosen(false)}>
                切换全部类型
              </button>
            ) : null}
            <button type="button" className="template-gallery-trigger" onClick={() => openGallery()}>
              <Icon name="grid" />
              <span>浏览全部 {TEMPLATES.length} 个模板</span>
              <Icon name="arrow" />
            </button>
          </div>
        </div>
        <div className="template-filter-bar">
          <div className="template-domains" role="tablist" aria-label="模板领域">
            <button
              role="tab"
              aria-selected={domain === '全部'}
              className={domain === '全部' ? 'active' : ''}
              onClick={() => setDomain('全部')}
            >
              精选推荐 <em>{activeTemplates.length}</em>
            </button>
            {domainCounts.map(([d, count]) => (
              <button
                key={d}
                role="tab"
                aria-selected={domain === d}
                className={domain === d ? 'active' : ''}
                onClick={() => setDomain(d)}
              >
                {d} <em>{count}</em>
              </button>
            ))}
          </div>
          <label className="template-search" aria-label="搜索模板">
            <Icon name="search" />
            <input
              type="text"
              value={templateQuery}
              onChange={(e) => setTemplateQuery(e.target.value)}
              placeholder="搜索模板名称或场景…"
            />
            {templateQuery ? (
              <button type="button" className="template-search-clear" aria-label="清空搜索" onClick={() => setTemplateQuery('')}>
                <Icon name="close" />
              </button>
            ) : null}
          </label>
        </div>
        {homeTemplates.length > 0 ? (
          <>
            <div className="inspiration-grid inspiration-grid-curated">
              {homeTemplates.map((x) => (
                <button key={x.id} className="inspiration-card" onClick={() => fill(x)}>
                  <SceneThumb t={x} />
                  <div className="inspiration-info">
                    <div className="inspiration-tags">
                      <span className="scene-tag">{x.scene}</span>
                      {!typeChosen ? <span className="type-tag">{x.type}</span> : null}
                    </div>
                    <b className="sr-only">{x.title}</b>
                    <Icon name="arrow" />
                    <p>{x.desc}</p>
                  </div>
                </button>
              ))}
            </div>
            {hiddenCount > 0 ? (
              <div className="template-more-bar">
                <span>
                  当前展示精选 {homeTemplates.length} 个，还有 <b>{hiddenCount}</b> 个同类别模板
                </span>
                <button type="button" className="template-more-btn" onClick={() => openGallery()}>
                  打开完整模板库（共 {TEMPLATES.length} 个）
                  <Icon name="arrow" />
                </button>
              </div>
            ) : null}
          </>
        ) : (
          <div className="template-empty">
            <span>没有匹配「{templateQuery}」的模板</span>
            <button
              type="button"
              onClick={() => {
                setTemplateQuery('');
                setDomain('全部');
                if (typeChosen) setTypeChosen(false);
              }}
            >
              重置筛选
            </button>
          </div>
        )}
      </div>
      {galleryOpen ? (
        <Modal
          title={`场景模板库（共 ${TEMPLATES.length} 个）`}
          overline="按行业与业务场景探索 · 一键带入输入框"
          className="template-gallery-modal"
          onClose={() => setGalleryOpen(false)}
        >
          <div className="tpl-gal-shell">
            <aside className="tpl-gal-nav" aria-label="模板行业分类">
              <div className="tpl-gal-nav-title">业务领域</div>
              <button
                type="button"
                className={galleryDomain === '全部' ? 'active' : ''}
                onClick={() => setGalleryDomain('全部')}
              >
                <span>全部领域</span>
                <em>{galleryType === '全部' ? TEMPLATES.length : templatesOf(galleryType).length}</em>
              </button>
              {allDomainCounts.map(([d, count]) => (
                <button
                  key={d}
                  type="button"
                  className={galleryDomain === d ? 'active' : ''}
                  onClick={() => setGalleryDomain(d)}
                >
                  <span>{d}</span>
                  <em>{count}</em>
                </button>
              ))}
            </aside>
            <section className="tpl-gal-main">
              <div className="tpl-gal-toolbar">
                <div className="tpl-gal-toolbar-top">
                  <label className="template-search tpl-gal-search" aria-label="在模板库中搜索">
                    <Icon name="search" />
                    <input
                      type="text"
                      autoFocus
                      value={galleryQuery}
                      onChange={(e) => setGalleryQuery(e.target.value)}
                      placeholder="搜索模板标题、行业场景或能力关键词（如 良率、飞书、Excel、审批）…"
                    />
                    {galleryQuery ? (
                      <button type="button" className="template-search-clear" aria-label="清空搜索" onClick={() => setGalleryQuery('')}>
                        <Icon name="close" />
                      </button>
                    ) : null}
                  </label>
                  <span className="tpl-gal-count">匹配 <b>{galleryTemplates.length}</b> 个模板</span>
                </div>
                <div className="tpl-gal-type-bar" role="tablist" aria-label="按应用形态筛选">
                  <span className="tpl-gal-type-label">应用形态</span>
                  <div className="tpl-gal-type-pills">
                    <button
                      type="button"
                      role="tab"
                      aria-selected={galleryType === '全部'}
                      className={galleryType === '全部' ? 'active' : ''}
                      onClick={() => {
                        setGalleryType('全部');
                        setGalleryDomain('全部');
                      }}
                    >
                      <span>全部形态</span>
                      <em>{TEMPLATES.length}</em>
                    </button>
                    {APP_TYPES.map((tp) => (
                      <button
                        key={tp}
                        type="button"
                        role="tab"
                        aria-selected={galleryType === tp}
                        className={galleryType === tp ? 'active' : ''}
                        onClick={() => {
                          setGalleryType(tp);
                          setGalleryDomain('全部');
                        }}
                      >
                        <span>{tp}</span>
                        <em>{templatesOf(tp).length}</em>
                      </button>
                    ))}
                  </div>
                </div>
              </div>
              {galleryTemplates.length > 0 ? (
                <div className="tpl-gal-scroll">
                  <div className="tpl-gal-grid">
                    {galleryTemplates.map((x) => (
                      <button
                        key={x.id}
                        type="button"
                        className="inspiration-card tpl-gal-card"
                        onClick={() => {
                          setGalleryOpen(false);
                          fill(x);
                        }}
                      >
                        <SceneThumb t={x} />
                        <div className="inspiration-info">
                          <div className="inspiration-tags">
                            <span className="scene-tag">{x.scene}</span>
                            <span className="type-tag">{x.type}</span>
                          </div>
                          <b className="sr-only">{x.title}</b>
                          <Icon name="arrow" />
                          <p>{x.desc}</p>
                        </div>
                      </button>
                    ))}
                  </div>
                </div>
              ) : (
                <div className="template-empty">
                  <span>当前筛选条件下暂无匹配模板</span>
                  <button
                    type="button"
                    onClick={() => {
                      setGalleryDomain('全部');
                      setGalleryType('全部');
                      setGalleryQuery('');
                    }}
                  >
                    查看全部 {TEMPLATES.length} 个
                  </button>
                </div>
              )}
            </section>
          </div>
        </Modal>
      ) : null}
      {works.data && works.data.length > 0 ? (
        <div className="inspiration-section community-teaser">
          <div className="section-title">
            <div>
              <h2>灵感社区 · 同事都在用</h2>
              <p>华星同事亲手做好的应用，预览效果满意即可「使用模板」复刻一份。</p>
            </div>
            <button className="community-more" onClick={() => navigate("/community")}>
              去灵感社区看看 →
            </button>
          </div>
          <div className="work-grid">
            {works.data.slice(0, 4).map((t) => (
              <WorkCard key={t.id} t={t} onOpen={() => navigate('/community/' + t.id)} />
            ))}
          </div>
        </div>
      ) : null}
      <footer className="page-footer">
        <span>每个想法，都值得开始。</span>
        <span>
          由织页创造 <i>✦</i>
        </span>
      </footer>
      <SkillPicker
        open={picker !== null}
        anchor={picker?.via === 'mention' ? textarea : skillBtn}
        picked={f.skills}
        mentionQuery={picker?.via === 'mention' ? picker.query : null}
        onClose={() => setPicker(null)}
        onPick={(sk) => {
          const st = useCreateForm.getState();
          if (picker?.via === 'mention') {
            // 去掉输入框里的「@查询」
            const end = picker.start + 1 + picker.query.length;
            f.set({ prompt: st.prompt.slice(0, picker.start) + st.prompt.slice(end) });
            setTimeout(() => {
              textarea.current?.focus();
              textarea.current?.setSelectionRange(picker.start, picker.start);
            }, 0);
          }
          f.set({ skills: [...st.skills, sk] });
          setPicker(null);
        }}
      />
      <EcosystemPickerModal
        open={ecoOpen}
        anchor={ecoBtn}
        onClose={() => setEcoOpen(false)}
        selected={ecosystems}
        onToggle={toggleEco}
        pickedSourceIds={f.sources.map((s) => s.id)}
        onPickSource={(s) => f.set({ sources: [...useCreateForm.getState().sources, { id: s.id, name: s.name }] })}
      />
    </div>
  );
}
