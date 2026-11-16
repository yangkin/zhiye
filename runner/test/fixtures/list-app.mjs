// 列表单项手动微调的测试用 App（单元测试和集成测试共用）
export const LIST_APP = `import { useState } from 'react'

type Item = { id: number; title: string }
const items: Item[] = [
  { id: 1, title: '苹果' },
  { id: 2, title: '香蕉' },
  { id: 3, title: '橙子' },
]

function Badge() {
  return <i className="badge">新</i>
}

export default function App() {
  const [n] = useState(0)
  return (
    <main>
      <h1>水果清单</h1>
      <ul>
        {items.map(item => (
          <li key={item.id} className="p-2">
            <span>水果</span>
            <b>{item.title}</b>
            <a href="/detail">详情</a>
          </li>
        ))}
      </ul>
      {items.map((it, idx) => <p key={it.id} style={{ margin: 0 }}>备注{n}</p>)}
      {items.map(function (it) { return <em key={it.id}>强调</em> })}
      <Badge />
      <Badge />
    </main>
  )
}
`
