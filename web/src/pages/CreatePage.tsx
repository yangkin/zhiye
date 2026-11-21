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
import { domainOf, templatesOf } from '../lib/templates';
import { SceneThumb } from '../components/SceneThumb';
import { Pet } from '../components/Pet';
import { TemplateIcon } from '../lib/templateIcons';
import { Icon, kindIcon } from '../lib/icons';
import { toast, useUi } from '../lib/store';
import { AddMenuButton, SourceChips, handleAssetPaste } from '../components/AddMenu';
import { ModelModeButton, PolishButton, SkillChips, SkillPicker, mentionAt } from '../components/ComposerPopovers';
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
  // 默认不选类型：直接进对话，类型由模型在构建时判断；也可以手动指定一个类型作为提示
  const typeChosen = f.typeChosen;
  const setTypeChosen = (typeChosen: boolean) => f.set({ typeChosen });
  const skillBtn = useRef<HTMLButtonElement>(null);
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

  const models = meta?.models?.length ? meta.models : [{ id: 'auto', label: 'Auto' }];
  const modes: Mode[] = meta?.modes?.length ? meta.modes : ['快速', '适中', '深度'];

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
    if (!prompt) return toast('先写下你的应用想法吧。');
    if (submitting || active) return;
    // 默认就走对话：聊完再写，或者边聊边写（生成中补充会排队）
    setSubmitting(true);
    setError('');
    try {
      const conv = await api.createConversation();
      const state = {
        initialMessage: prompt,
        ...(typeChosen ? { type: f.type } : {}),
        model: f.model,
        mode: f.mode,
        attachments: f.attachments,
        skills: f.skills,
        sources: f.sources,
      };
      f.set({ prompt: '', attachments: [], skills: [], sources: [], typeChosen: false });
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
  const setHelpOpen = useUi((st) => st.setHelpOpen);
  // 帮助抽屉里已经有一只小织，页面上这只先藏起来
  const helpOpen = useUi((st) => st.helpOpen);
  const [domain, setDomain] = useState('全部');
  useEffect(() => setDomain('全部'), [f.type]);
  const domains = useMemo(() => [...new Set(templatesOf(f.type).map(domainOf))], [f.type]);
  const shownTemplates = templatesOf(f.type).filter((t) => domain === '全部' || domainOf(t) === domain);

  const works = useQuery({ queryKey: ['templates', { sort: 'likes' }], queryFn: () => api.templates({ sort: 'likes' }) });

  // 已经写了内容再点推荐：照样填进去，但给一次「撤销」找回原来的草稿
  const fill = (text: string) => {
    const prev = f.prompt.trim();
    f.set({ prompt: text });
    if (prev && prev !== text.trim()) toast('已换成推荐的需求', 'ok', { label: '撤销', run: () => useCreateForm.getState().set({ prompt: prev }) });
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
          <Pet size={120} onClick={() => setHelpOpen(true)} />
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
        {f.attachments.length || f.skills.length || f.sources.length ? (
          <div className="attachments">
            <SkillChips skills={f.skills} onRemove={(id) => f.set({ skills: f.skills.filter((x) => x.id !== id) })} />
            <SourceChips sources={f.sources} onRemove={(id) => f.set({ sources: f.sources.filter((x) => x.id !== id) })} />
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
            <PolishButton value={f.prompt} onPolished={(text) => f.set({ prompt: text })} />
            <ModelModeButton models={models} modes={modes} model={f.model} mode={f.mode} onModel={(m) => f.set({ model: m })} onMode={(m) => f.set({ mode: m })} />
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
          </div>
          <div>
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
          「{active.appName}」正在生成中，完成后再创建
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
        {templatesOf(f.type).slice(0, 4).map((t) => (
          <button key={t.id} className="prompt-chip" title={t.title} onClick={() => fill(t.prompt)}>
            <TemplateIcon id={t.id} size={14} />
            {t.title}
          </button>
        ))}
      </div>
      <div className="inspiration-section">
        <div className="section-title">
          <div>
            <h2>
              {typeChosen ? f.type : '灵感'} · 华星场景模板<span>✦</span>
            </h2>
            <p>来自 TCL华星 日常工作的场景，点一下就能填好需求，再按你的情况改一改。</p>
          </div>
          <span className="quiet-label">TCL华星 精选 · {templatesOf(f.type).length} 个</span>
        </div>
        <div className="template-domains" role="tablist" aria-label="模板领域">
          {['全部', ...domains].map((d) => (
            <button key={d} role="tab" aria-selected={domain === d} className={domain === d ? 'active' : ''} onClick={() => setDomain(d)}>
              {d}
            </button>
          ))}
        </div>
        <div className="inspiration-grid">
          {shownTemplates.map((x) => (
            <button key={x.id} className="inspiration-card" onClick={() => fill(x.prompt)}>
              <SceneThumb t={x} />
              <div className="inspiration-info">
                <span className="scene-tag">{x.scene}</span>
                <b className="sr-only">{x.title}</b>
                <Icon name="arrow" />
                <p>{x.desc}</p>
              </div>
            </button>
          ))}
        </div>
      </div>
      {works.data && works.data.length > 0 ? (
        <div className="inspiration-section community-teaser">
          <div className="section-title">
            <div>
              <h2>
                灵感社区 · 同事都在用<span>✦</span>
              </h2>
              <p>华星同事亲手织好的应用，看看效果，喜欢就「使用模板」复刻一份。</p>
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
    </div>
  );
}
