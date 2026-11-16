---
name: cloud-functions
description: 写云函数（functions/{name}.ts）：审批通过/驳回、提交时的业务规则校验、扣库存同时写流水等多表事务、需要组合调用数据源或 AI 的服务端逻辑。应用已启用云服务且需求里有「审批」「流转」「同时更新」「校验规则」时使用。
---
# 云函数

函数在平台的隔离环境里运行（Deno），跟着版本走：预览调用当前版本的函数，线上调用线上版本的函数，不需要单独部署。

## 什么时候用

| 用云函数 | 直接用 zhiye.cloud.db |
|---|---|
| 审批「通过 / 驳回」：改状态 + 写审批记录 | 普通的新增、编辑、删除、列表 |
| 扣库存同时写出入库流水（必须一起成功） | 单表计数用 `db.increment` |
| 提交前要查别的表做校验（是否重复预约、额度够不够） | 字段必填/范围/唯一用 policies.json 的 fields |
| 组合数据源和数据库（把 MES 数据存一份快照） | 只读展示数据源用 `zhiye.source.query` |

## 写法

1. 类型定义写到 `functions/zhiye-fn.d.ts`（原样复制）：

```ts
export type Row = Record<string, unknown> & { id?: number }
export type DbOp =
  | { op: 'insert'; table: string; row: Row }
  | { op: 'update'; table: string; id: number; row: Row }
  | { op: 'remove'; table: string; id: number }
  | { op: 'increment'; table: string; id: number; column: string; delta: number; min?: number; max?: number }
export interface FnContext {
  user: { id: string; loginName: string; name: string; admin: boolean } | null
  db: {
    list(table: string, query?: Record<string, unknown>): Promise<{ rows: Row[]; total: number }>
    get(table: string, id: number): Promise<Row | null>
    insert(table: string, row: Row): Promise<Row>
    update(table: string, id: number, patch: Row): Promise<Row>
    remove(table: string, id: number): Promise<void>
    increment(table: string, id: number, field: string, delta: number, opts?: { min?: number; max?: number }): Promise<Row>
    aggregate(table: string, spec: Record<string, unknown>): Promise<Row[]>
    transaction(ops: DbOp[]): Promise<(Row | null)[]>
  }
  storage: { put(path: string, data: Uint8Array | string): Promise<{ path: string }>; get(path: string): Promise<Uint8Array | null> }
  source: { query(name: string, params?: Record<string, unknown>): Promise<unknown> }
  ai: { chat(req: string | { messages: { role: 'user' | 'assistant'; content: string }[]; system?: string; json?: boolean }): Promise<string> }
  log: (...args: unknown[]) => void
}
export type Handler = (args: Record<string, unknown>, ctx: FnContext) => Promise<unknown>
```

2. 每个函数一个文件，文件名就是调用名（小写字母、数字、- _），**只能用 `import type` 引入类型，不能 import 其他文件或 npm 包**：

```ts
// functions/approve.ts
import type { Handler } from './zhiye-fn.d.ts'

const handler: Handler = async (args, ctx) => {
  if (!ctx.user?.admin) throw new Error('只有管理员能审批')
  const id = Number(args.id)
  const order = await ctx.db.get('orders', id)
  if (!order) throw new Error('单据不存在')
  const [, updated] = await ctx.db.transaction([
    { op: 'insert', table: 'approvals', row: { order_id: id, decision: String(args.decision), remark: String(args.remark ?? '') } },
    { op: 'update', table: 'orders', id, row: { status: String(args.decision) } },
  ])
  ctx.log('审批完成', id, args.decision)
  return updated
}
export default handler
```

3. 页面调用：

```ts
const updated = await window.zhiye.fn.call('approve', { id, decision: '通过' })
```

4. 只允许函数做的状态变更，在 `cloud/policies.json` 里限定：
`"transitions": { "status": [ { "from": "待审", "to": ["通过", "驳回"], "by": "function" } ] }`——页面绕过函数直接 `db.update` 改状态会被拒绝。

## 要点

- `ctx.db` 不受表的权限预设限制（函数是服务端代码），所以**权限判断要写在函数里**（`ctx.user` 为 null 表示未登录，`admin` 表示管理员）；字段规则和状态流转仍然生效。
- 抛出的 `Error` 的 message 会以「云函数 xxx 出错：…」返回给页面，写成用户能看懂的中文。
- 限制：10 秒超时、128 MB 内存、返回 1 MB；不能读写文件、读环境变量、访问外网（外部数据只能用 `ctx.source`）。
- 函数的报错会记录下来，用户下次说「按钮报错了」时，修改任务里会附带最近的报错，按报错修对应的函数。
