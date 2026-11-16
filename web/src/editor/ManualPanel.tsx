import { useState, type ReactNode } from 'react';
import type { Selection } from '../api/types';
import { Icon } from '../lib/icons';
import { elementLabel } from './ModeOverlays';
import type { VisualEditor } from './useVisualEditor';
import { Select } from '../components/Select';

export function hex(value?: string): string {
  if (!value) return '';
  if (/^#[\da-f]{6}$/i.test(value)) return value.toLowerCase();
  if (/^#[\da-f]{3}$/i.test(value)) return '#' + value.slice(1).split('').map((x) => x + x).join('').toLowerCase();
  const m = String(value).match(/rgba?\((\d+),?\s+(\d+),?\s+(\d+)(?:\s*[,/]\s*([\d.]+))?\)/);
  if (!m || m[4] === '0') return '';
  return '#' + m.slice(1, 4).map((x) => (+x).toString(16).padStart(2, '0')).join('');
}
const px = (v?: string) => {
  const n = parseFloat(v ?? '');
  return Number.isFinite(n) ? String(Math.round(n * 10) / 10) : '';
};
const validColor = (v: string) => /^#[0-9a-f]{3}([0-9a-f]{3})?$/i.test(v);

const SIDES = [
  ['Left', '左'],
  ['Top', '上'],
  ['Right', '右'],
  ['Bottom', '下'],
] as const;
const ALIGNS = [
  ['left', '左对齐', '左'],
  ['center', '居中', '中'],
  ['right', '右对齐', '右'],
  ['justify', '两端对齐', '两端'],
] as const;
const DISPLAYS = [
  ['block', 'Block'],
  ['flex', 'Flex'],
  ['grid', 'Grid'],
  ['inline', 'Inline'],
  ['none', 'None'],
] as const;
const WEIGHTS = ['300', '400', '500', '600', '700', '800'];
const WEIGHT_NAMES: Record<string, string> = { '300': '细', '400': '常规', '500': '中等', '600': '半粗', '700': '粗', '800': '特粗' };

interface Props {
  editor: VisualEditor;
  selection: Selection;
  onClose: () => void;
}

/** 手动微调：替换左侧对话面板的「编辑元素」面板。key 为选中元素 loc。 */
export function ManualPanel({ editor, selection: s, onClose }: Props) {
  const st = s.styles ?? {};
  const set = (styles: Record<string, string>) => editor.apply({ styles });
  const many = s.sameLocCount > 1;
  const one = many && editor.scope === 'one';
  const canText = s.canEditText && !s.dynamic;
  const deco = st.textDecoration ?? st.textDecorationLine ?? '';
  const weight = String(parseInt(st.fontWeight) || 400);
  const bold = parseInt(st.fontWeight) >= 600;
  const isRoot = ['HTML', 'BODY'].includes(s.tag.toUpperCase());
  const display = (st.display ?? '').replace(/^inline-(block|flex|grid)$/, 'inline');
  const toggleDeco = (word: 'underline' | 'line-through') => {
    let words = ['underline', 'line-through'].filter((x) => deco.includes(x));
    words = words.includes(word) ? words.filter((x) => x !== word) : [...words, word];
    set({ textDecoration: words.join(' ') || 'none' });
  };

  return (
    <section className="chat-panel ze-edit-panel" aria-label="编辑元素">
      <header className="ze-edit-head">
        <span className="ze-edit-tag">{elementLabel(s)}</span>
        <h2>编辑元素</h2>
        <div className="ze-edit-head-actions">
          <button type="button" aria-label="撤销修改" title="撤销修改" disabled={!editor.canUndo} onClick={editor.undo}>
            <Icon name="undo" />
          </button>
          <button type="button" aria-label="重做修改" title="重做修改" disabled={!editor.canRedo} onClick={editor.redo}>
            <Icon name="redo" />
          </button>
          <button type="button" aria-label="选择父元素" title="选择父元素" disabled={!s.parentLoc} onClick={editor.selectParent}>
            <Icon name="expand" />
          </button>
          <button type="button" aria-label="关闭编辑元素" title="返回对话" onClick={onClose}>
            <Icon name="close" />
          </button>
        </div>
      </header>
      {editor.locked ? (
        <p className="ze-locked-banner" role="status">
          正在修改中，完成后可继续微调
        </p>
      ) : null}
      <fieldset className="ze-edit-body" disabled={editor.locked}>
        {many ? (
          <div className="ze-scope">
            <span className="ze-scope-title">修改范围</span>
            <div className="ze-segmented" role="radiogroup" aria-label="修改范围">
              <button type="button" role="radio" aria-checked={one} className={one ? 'active' : ''} onClick={() => editor.setScope('one')}>
                只改这一项
              </button>
              <button type="button" role="radio" aria-checked={!one} className={!one ? 'active' : ''} onClick={() => editor.setScope('all')}>
                全部同类项（{s.sameLocCount} 处）
              </button>
            </div>
            <p className="ze-edit-note">
              {one
                ? `只修改第 ${(s.instanceIndex ?? 0) + 1} 项，其他项保持不变。`
                : `列表中的 ${s.sameLocCount} 项会一起改变。`}
            </p>
          </div>
        ) : null}

        <Group title="文字">
          {s.dynamic ? (
            <p className="ze-edit-hint">{one ? '这段文字来自数据，只改其中一项请用对话修改' : '这段内容是动态生成的，可以用对话修改'}</p>
          ) : canText ? (
            <CommitTextarea value={s.text} onCommit={(text) => text !== s.text && editor.apply({ text })} />
          ) : (
            <p className="ze-edit-note">选中的是容器，可以修改样式，或选择其中的文字。</p>
          )}
        </Group>

        <Group title="字体">
          <div className="ze-row">
            <Field label="字号">
              <CommitInput aria="字号" type="number" min={8} max={200} value={px(st.fontSize)} suffix="px" onCommit={(v) => v && set({ fontSize: Math.max(8, Math.min(200, +v)) + 'px' })} />
            </Field>
            <Field label="字重">
              <Select
                ariaLabel="字重"
                className="field"
                value={WEIGHTS.includes(weight) ? weight : '400'}
                onChange={(v) => set({ fontWeight: v })}
                options={WEIGHTS.map((w) => ({ value: w, label: w, hint: WEIGHT_NAMES[w] }))}
              />
            </Field>
          </div>
          <Field label="文字颜色">
            <ColorInput aria="文字颜色" value={hex(st.color)} onCommit={(v) => set({ color: v })} />
          </Field>
          <Field label="对齐">
            <div className="ze-segmented" role="radiogroup" aria-label="对齐方式">
              {ALIGNS.map(([v, label, short]) => (
                <button key={v} type="button" role="radio" aria-checked={st.textAlign === v} aria-label={label} className={st.textAlign === v ? 'active' : ''} onClick={() => set({ textAlign: v })}>
                  {short}
                </button>
              ))}
            </div>
          </Field>
          <div className="ze-toggles">
            <button type="button" aria-label="加粗" aria-pressed={bold} className={bold ? 'active' : ''} onClick={() => set({ fontWeight: bold ? '400' : '700' })}>
              <b>B</b>
            </button>
            <button type="button" aria-label="斜体" aria-pressed={st.fontStyle === 'italic'} className={st.fontStyle === 'italic' ? 'active' : ''} onClick={() => set({ fontStyle: st.fontStyle === 'italic' ? 'normal' : 'italic' })}>
              <i>I</i>
            </button>
            <button type="button" aria-label="下划线" aria-pressed={deco.includes('underline')} className={deco.includes('underline') ? 'active' : ''} onClick={() => toggleDeco('underline')}>
              <u>U</u>
            </button>
            <button type="button" aria-label="删除线" aria-pressed={deco.includes('line-through')} className={deco.includes('line-through') ? 'active' : ''} onClick={() => toggleDeco('line-through')}>
              <s>S</s>
            </button>
          </div>
        </Group>

        <Group title="间距">
          {(
            [
              ['padding', 'Padding', '内边距'],
              ['margin', 'Margin', '外边距'],
            ] as const
          ).map(([prop, title, cn]) => (
            <div key={prop} className="ze-spacing">
              <span className="ze-spacing-title">
                {title} <small>{cn}</small>
              </span>
              <div className="ze-spacing-grid">
                {SIDES.map(([side, label]) => {
                  const key = prop + side;
                  return (
                    <label key={key}>
                      <small>{label}</small>
                      <CommitInput aria={`${cn}-${label}`} type="number" min={prop === 'padding' ? 0 : -999} value={px(st[key])} onCommit={(v) => v !== '' && set({ [key]: (+v || 0) + 'px' })} />
                    </label>
                  );
                })}
              </div>
            </div>
          ))}
        </Group>

        <Group title="外观">
          <Field label="背景颜色">
            <ColorInput aria="背景颜色" value={hex(st.backgroundColor)} placeholder="透明" onCommit={(v) => set({ backgroundColor: v })} />
          </Field>
          <div className="ze-row">
            <Field label="圆角">
              <CommitInput aria="圆角" type="number" min={0} value={px(st.borderRadius)} suffix="px" onCommit={(v) => v !== '' && set({ borderRadius: Math.max(0, +v || 0) + 'px' })} />
            </Field>
          </div>
          <Field label="透明度">
            <OpacitySlider value={st.opacity} onCommit={(v) => set({ opacity: v })} />
          </Field>
        </Group>

        <Group title="布局">
          <Field label="显示方式">
            <div className="ze-segmented" role="radiogroup" aria-label="显示方式">
              {DISPLAYS.map(([v, label]) => (
                <button key={v} type="button" role="radio" aria-checked={display === v} aria-label={label} className={display === v ? 'active' : ''} onClick={() => set({ display: v })}>
                  {label}
                </button>
              ))}
            </div>
          </Field>
        </Group>

        {s.tag.toUpperCase() === 'A' ? (
          <Group title="链接">
            <CommitInput aria="链接地址" value={s.href ?? ''} placeholder="https:// 或 #页面锚点" onCommit={(v) => v !== (s.href ?? '') && editor.apply({ href: v })} />
          </Group>
        ) : null}

        <Group title="删除">
          <button type="button" className="ze-danger" aria-label="删除元素" disabled={isRoot} onClick={() => editor.apply({ removed: true })}>
            <Icon name="close" />
            删除元素
          </button>
        </Group>
      </fieldset>
    </section>
  );
}

function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="ze-group">
      <h3>{title}</h3>
      {children}
    </div>
  );
}
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="ze-field">
      <span>{label}</span>
      {children}
    </div>
  );
}

/** 失焦或回车时才产生补丁，避免每次按键都进入撤销记录。 */
function CommitInput({
  aria,
  value,
  onCommit,
  type = 'text',
  min,
  max,
  suffix,
  placeholder,
}: {
  aria: string;
  value: string;
  onCommit: (v: string) => void;
  type?: string;
  min?: number;
  max?: number;
  suffix?: string;
  placeholder?: string;
}) {
  const [v, setV] = useState(value);
  const [base, setBase] = useState(value);
  if (base !== value) {
    setBase(value);
    setV(value);
  }
  const commit = () => v.trim() !== value && onCommit(v.trim());
  return (
    <span className="ze-input">
      <input
        aria-label={aria}
        type={type}
        min={min}
        max={max}
        value={v}
        placeholder={placeholder}
        onChange={(e) => setV(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === 'Enter' && commit()}
      />
      {suffix ? <small>{suffix}</small> : null}
    </span>
  );
}

function CommitTextarea({ value, onCommit }: { value: string; onCommit: (v: string) => void }) {
  const [v, setV] = useState(value);
  const [base, setBase] = useState(value);
  if (base !== value) {
    setBase(value);
    setV(value);
  }
  return (
    <textarea
      aria-label="元素文字"
      rows={3}
      value={v}
      onChange={(e) => setV(e.target.value)}
      onBlur={() => onCommit(v)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) onCommit(v);
      }}
    />
  );
}

function ColorInput({ aria, value, onCommit, placeholder }: { aria: string; value: string; onCommit: (v: string) => void; placeholder?: string }) {
  const [v, setV] = useState(value);
  const [base, setBase] = useState(value);
  const [bad, setBad] = useState(false);
  if (base !== value) {
    setBase(value);
    setV(value);
  }
  const commit = (next: string) => {
    const t = next.trim();
    if (!t || t === value) return setBad(false);
    if (!validColor(t)) return setBad(true);
    setBad(false);
    onCommit(t.toLowerCase());
  };
  return (
    <span className={`ze-color ${bad ? 'bad' : ''}`}>
      <input
        type="color"
        aria-label={`${aria}取色`}
        value={validColor(v) && v.length === 7 ? v : '#000000'}
        onChange={(e) => {
          setV(e.target.value);
          commit(e.target.value);
        }}
      />
      <input
        aria-label={aria}
        value={v}
        placeholder={placeholder ?? '#000000'}
        onChange={(e) => setV(e.target.value)}
        onBlur={() => commit(v)}
        onKeyDown={(e) => e.key === 'Enter' && commit(v)}
      />
    </span>
  );
}

function OpacitySlider({ value, onCommit }: { value?: string; onCommit: (v: string) => void }) {
  const initial = Number.isFinite(parseFloat(value ?? '')) ? parseFloat(value!) : 1;
  const [v, setV] = useState(initial);
  const [base, setBase] = useState(initial);
  if (base !== initial) {
    setBase(initial);
    setV(initial);
  }
  return (
    <span className="ze-range">
      <input
        type="range"
        aria-label="透明度"
        min={0}
        max={1}
        step={0.05}
        value={v}
        onChange={(e) => {
          const n = +e.target.value;
          setV(n);
          if (n !== initial) onCommit(String(n));
        }}
      />
      <small>{Math.round(v * 100)}%</small>
    </span>
  );
}
