---
name: data-sources
description: 调用应用已绑定的公司数据源（只读接口），把真实数据做成列表、看板和查询页。指令里出现「已绑定的数据源」，或需求提到 MES、产线、接口数据、实时数据时使用。
---
# 数据源（只读接口）

平台替应用调用已登记的公司接口：地址和凭证都在服务端，页面只知道「调用名」和参数。

## 调用方式

```ts
// src/lib/source.ts
type Params = Record<string, string | number | boolean | undefined>;

declare global {
  interface Window { zhiye?: { source?: { query: (key: string, params?: Params) => Promise<unknown> } } }
}

export const sourceReady = () => !!window.zhiye?.source;

export async function querySource<T>(key: string, params?: Params): Promise<T> {
  if (!window.zhiye?.source) throw new Error('数据源只在预览和发布页面可用');
  return (await window.zhiye.source.query(key, params)) as T;
}
```

- 返回值是接口原始 JSON。按指令里的「返回示例」写类型和解析逻辑；字段可能缺失，取值时用 `?.` 和默认值。
- 参数必须是指令里列出的参数名，类型不对或缺少必填参数会直接报错（错误信息是中文，可以原样显示）。
- 不要写接口地址、不要用 `fetch`/`axios` 直连、不要在代码里放任何 token。
- 只读：数据源不能写入。需要保存用户录入的内容时用云服务数据库（`window.zhiye.cloud`），没启用就告诉用户。

## 页面状态（必须都有）

```tsx
const [state, setState] = useState<{ loading: boolean; error: string; rows: Row[] }>({ loading: true, error: '', rows: [] });

useEffect(() => {
  if (!sourceReady()) { setState({ loading: false, error: '数据源只在预览和发布页面可用', rows: [] }); return; }
  let alive = true;
  querySource<Resp>('mes_daily_output', { date })
    .then((r) => alive && setState({ loading: false, error: '', rows: r?.data ?? [] }))
    .catch((e) => alive && setState({ loading: false, error: e.message, rows: [] }));
  return () => { alive = false; };
}, [date]);
```

- 加载中：骨架屏或「正在读取…」。
- 失败：显示 `error.message`，并给一个「重试」按钮。
- 空数据：说明「没有符合条件的数据」，不要显示空表格。
- 查询条件（日期、产线等）放在页面顶部，改动后重新查询；默认值用今天或最近 7 天。
- 本地构建自测时没有数据源，页面要能正常渲染（显示上面的提示），不能白屏或抛异常。

## 性能

- 单次返回最多 2 MB、10 秒超时。数据多时用接口的分页 / 时间范围参数，不要一次拉全量。
- 同一组参数不要重复请求：查询条件不变时复用结果；轮询间隔不少于 30 秒，且页面隐藏时暂停。
- 统计图从返回数据在前端汇总即可（配合 charts-dashboard 技能）。
