import type { AppSummary, AppType, StyleId } from '../api/types';
import { Icon } from '../lib/icons';

const STYLE_IDS: StyleId[] = ['lavender', 'mint', 'rose', 'sand', 'ink', 'cyber'];

export function styleFor(id: string, style?: StyleId): StyleId {
  if (style) return style;
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0;
  return STYLE_IDS[h % STYLE_IDS.length];
}

type Template = 'landing' | 'todo';

export function templateFor(type: AppType): Template {
  return type === '日常开发' ? 'todo' : 'landing';
}

const taglines: Record<AppType, string> = {
  日常开发: '内部工具 · 数据随手可查',
  开发网站: '网页应用 · 打开即用',
  开发skill: '技能包 · 让 AI 更懂业务',
  技术文档: '技术文档 · 随时可下载',
};

/** 卡片封面：按类型画的示意缩略图（不是真实截图），标题用应用自己的名字。 */
export function Thumbnail({
  app,
  template,
  style,
}: {
  app: Pick<AppSummary, 'id' | 'name' | 'type' | 'source' | 'framework'>;
  template?: Template;
  style?: StyleId;
}) {
  const s = styleFor(app.id, style);
  if (app.source === 'import') {
    return (
      <div className={`thumbnail ${s} thumb-imported`}>
        <div className="mini-browser">
          <i />
          <i />
          <i />
          <span>IMPORTED WITH ZHIYE</span>
        </div>
        <div className="code-project-thumb">
          <Icon name="code" />
          <strong>{app.name}</strong>
          <span>{app.framework ?? '导入代码'}</span>
          <small>从已有代码，继续创造</small>
        </div>
      </div>
    );
  }
  const t = template ?? templateFor(app.type);
  return (
    <div className={`thumbnail ${s} thumb-${t}`}>
      <div className="mini-browser">
        <i />
        <i />
        <i />
        <span>MADE WITH ZHIYE</span>
      </div>
      {t === 'todo' ? (
        <div className="mini-todo">
          <small>{app.type}</small>
          <strong className="thumb-title">{app.name}</strong>
          <p>{taglines[app.type]}</p>
          <div>
            <i>✓</i>
            <span />
          </div>
          <div>
            <i />
            <span />
          </div>
          <div>
            <i />
            <span />
          </div>
        </div>
      ) : (
        <div className="mini-landing">
          <small>{taglines[app.type]}</small>
          <strong className="thumb-title">{app.name}</strong>
          <b>打开看看 ↗</b>
          <div className="mini-dashboard">
            <aside>
              <i />
              <i />
              <i />
            </aside>
            <section>
              <div />
              <span />
              <span />
              <span />
              <footer>
                <i />
                <i />
                <i />
                <i />
                <i />
              </footer>
            </section>
          </div>
        </div>
      )}
    </div>
  );
}
