import type { SceneTemplate } from '../lib/templates';
import { TemplateIcon } from '../lib/templateIcons';

const palettes: Record<SceneTemplate['style'], [string, string, string]> = {
  lavender: ['#f3eefe', '#e6dcfb', '#7657d9'],
  mint: ['#eaf5f0', '#d6ece2', '#286458'],
  sand: ['#f7f1e8', '#ece0cc', '#9a6b41'],
  rose: ['#fbeff2', '#f3dbe2', '#ad6177'],
  ink: ['#eeeff3', '#dcdee6', '#252833'],
  cyber: ['#edeaff', '#dcd5ff', '#5b43d6'],
};

/** 场景模板卡片的封面：按模板类型画一个示意界面，标题即模板名。 */
export function SceneThumb({ t }: { t: SceneTemplate }) {
  const [bg, soft, ink] = palettes[t.style];
  return (
    <div className="thumbnail scene-thumb" style={{ background: `linear-gradient(150deg, ${bg}, ${soft})`, color: ink }} aria-hidden="true">
      <span className="scene-thumb-watermark">
        <TemplateIcon id={t.id} size={120} />
      </span>
      <div className="scene-thumb-bar">
        <i /> <i /> <i />
        <span>TCL CSOT · {t.scene}</span>
      </div>
      <div className="scene-thumb-body">
        <span className="scene-thumb-icon" style={{ background: `linear-gradient(140deg, ${ink}, ${ink}cc)` }}>
          <TemplateIcon id={t.id} size={20} />
        </span>
        <b>{t.title}</b>
        {t.thumb === 'dashboard' ? (
          <div className="scene-thumb-dash">
            <span>
              <i />
              <i />
              <i />
            </span>
            <svg viewBox="0 0 120 34" preserveAspectRatio="none">
              {[14, 22, 18, 27, 20, 30, 24].map((h, i) => (
                <rect key={i} x={i * 17 + 2} y={34 - h} width="9" height={h} rx="2" fill="currentColor" opacity=".16" />
              ))}
              <polyline points="6,22 23,16 40,19 57,9 74,14 91,5 108,10" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" opacity=".7" />
            </svg>
          </div>
        ) : (
          <div className="scene-thumb-lines">
            <em style={{ width: '82%' }} />
            <em style={{ width: '64%' }} />
            <em style={{ width: '46%' }} />
          </div>
        )}
      </div>
    </div>
  );
}
