---
name: charts-dashboard
description: 用 recharts 做数据看板和统计图（折线、柱状、饼图、面积图、指标卡）。需求里出现看板、报表、趋势、占比、统计、图表时使用。
---
# 数据看板与图表

## 布局
- 整体使用流式满宽容器（`w-full`），适配 1280px 笔记本、1920px 办公屏和 2K/4K 大屏，不要套窄 `max-w-5xl` 导致大屏两侧空荡。
- 顶部一行指标卡（3～6 个）：使用响应式网格（如 `grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 2xl:grid-cols-6 gap-4`），大号等宽数字（`tabular-nums`）+ 名称 + 同比/环比（上升绿色、下降红色，用 lucide-react 的 TrendingUp / TrendingDown）。
- 下面是图表网格：窄屏 1 列、桌面 2 列、超宽大屏按需 2～3 列（`grid grid-cols-1 lg:grid-cols-2 gap-4`）；每个图表放在白底圆角卡片里，卡片有标题和一句说明，外层设 `min-w-0` 防止 ResponsiveContainer 撑破网格。
- 需要筛选时，筛选条（时间范围、部门、产线等）放在指标卡上方并使用 `flex-wrap gap-2`，筛选结果同时影响指标卡和所有图表。

## recharts 用法要点
```tsx
import { ResponsiveContainer, LineChart, Line, BarChart, Bar, PieChart, Pie, Cell, XAxis, YAxis, CartesianGrid, Tooltip, Legend } from 'recharts'

<div className="h-72">{/* 父元素必须有明确高度，否则图表高度为 0 */}
  <ResponsiveContainer width="100%" height="100%">
    <LineChart data={rows} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
      <CartesianGrid strokeDasharray="3 3" stroke="#eee" />
      <XAxis dataKey="date" tick={{ fontSize: 12 }} />
      <YAxis tick={{ fontSize: 12 }} width={40} />
      <Tooltip />
      <Legend />
      <Line type="monotone" dataKey="output" name="产量" stroke="#6366f1" strokeWidth={2} dot={false} />
    </LineChart>
  </ResponsiveContainer>
</div>
```
- 数据是对象数组，`dataKey` 指字段名，`name` 写中文图例。
- 饼图每块颜色用 `<Cell fill={COLORS[i % COLORS.length]} />`；超过 6 类时把小的合并成「其他」。
- 数值格式化：`Tooltip formatter={(v) => v.toLocaleString('zh-CN')}`；百分比保留 1 位小数。
- 日期用 dayjs 处理：`dayjs(d).format('MM-DD')`，按周/月汇总用 `dayjs(d).startOf('month')`。

## 数据
- 没有真实数据源时，用一份**结构真实**的示例数据（字段名和业务一致、数量 20～60 条），放在 `src/data/sample.ts`，界面上标注「示例数据」。
- 汇总逻辑（分组、求和、占比）写成纯函数放 `src/lib/stats.ts`，图表只负责展示。
- 空数据要有空状态（「暂无数据」），不要渲染空白图表。
