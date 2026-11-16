import { useEffect, useMemo, useRef } from 'react';
import CodeMirror, { EditorView, type Extension, type ReactCodeMirrorRef } from '@uiw/react-codemirror';
import { markdown } from '@codemirror/lang-markdown';
import { javascript } from '@codemirror/lang-javascript';
import { python } from '@codemirror/lang-python';
import { html } from '@codemirror/lang-html';
import { css } from '@codemirror/lang-css';
import { json } from '@codemirror/lang-json';

function languageFor(path: string): Extension[] {
  const ext = (path.split('.').pop() ?? '').toLowerCase();
  switch (ext) {
    case 'md':
    case 'markdown':
    case 'mdx':
      return [markdown()];
    case 'js':
    case 'mjs':
    case 'cjs':
      return [javascript()];
    case 'jsx':
      return [javascript({ jsx: true })];
    case 'ts':
    case 'mts':
      return [javascript({ typescript: true })];
    case 'tsx':
      return [javascript({ jsx: true, typescript: true })];
    case 'py':
      return [python()];
    case 'html':
    case 'htm':
    case 'vue':
      return [html()];
    case 'css':
    case 'scss':
    case 'less':
      return [css()];
    case 'json':
      return [json()];
    default:
      return [];
  }
}

interface Props {
  path: string;
  value: string;
  readOnly?: boolean;
  line?: number;
  onChange: (value: string) => void;
}

/** CodeMirror 6 编辑器（按需加载的独立分包）。 */
export default function CodeEditor({ path, value, readOnly, line, onChange }: Props) {
  const ref = useRef<ReactCodeMirrorRef>(null);
  const extensions = useMemo(() => [...languageFor(path), EditorView.lineWrapping], [path]);

  useEffect(() => {
    const view = ref.current?.view;
    if (!view || !line) return;
    const n = Math.min(Math.max(1, line), view.state.doc.lines);
    const pos = view.state.doc.line(n).from;
    view.dispatch({ selection: { anchor: pos }, effects: EditorView.scrollIntoView(pos, { y: 'center' }) });
    view.focus();
  }, [line, path]);

  return (
    <div className="code-editor" role="region" aria-label="代码编辑器">
      <CodeMirror
        ref={ref}
        value={value}
        theme="light"
        height="100%"
        readOnly={readOnly}
        editable={!readOnly}
        extensions={extensions}
        basicSetup={{ foldGutter: true, highlightActiveLine: true }}
        onChange={onChange}
        onCreateEditor={(view) => {
          if (!line) return;
          const n = Math.min(Math.max(1, line), view.state.doc.lines);
          const pos = view.state.doc.line(n).from;
          view.dispatch({ selection: { anchor: pos }, effects: EditorView.scrollIntoView(pos, { y: 'center' }) });
        }}
      />
    </div>
  );
}
