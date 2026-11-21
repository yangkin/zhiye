---
name: forms-and-tables
description: 业务表单（录入、编辑、校验）和数据表格（搜索、筛选、排序、分页）的做法。需求里有登记、申请、录入、台账、清单、记录查询时使用。
---
# 表单与表格

## 表单
- 用受控组件 + 一个 `errors` 对象；提交时整体校验，字段失焦时单独校验，错误显示在字段下方（红色小字）。
- 必填项标签后加红色 `*`；日期用 `<input type="date">`，多行说明用 `<textarea>`。
- **统一自定义下拉（禁止原生 `<select>`）**：表单枚举和列表筛选下拉**严禁使用浏览器原生 `<select>`**（macOS/Windows 会弹出系统自带的深灰原生菜单，与浅色/暗色页面风格严重割裂）；统一使用模板已预置的自定义下拉组件：
  - React：`import { Select } from './components/Select'`（支持 `value`、`options: { value, label, hint? }[] | string[]`、`onChange`、`placeholder`、`className`）
  - Vue：`import CustomSelect from './components/CustomSelect.vue'`（支持 `v-model`、`options`、`placeholder`）
- 提交按钮在提交中禁用并显示「保存中…」；成功后给出明确反馈（顶部提示条或回到列表并高亮新行）。
- 编辑和新增共用一个表单组件，用 `initialValue` 区分。

## 表格
- 数据量不大（< 2000 行）时在前端完成搜索、筛选、排序、分页；把这些逻辑写成纯函数，组件里用 `useMemo` 组合。
- 表头可点击排序（显示 ↑ ↓）；每页 20 条，底部「共 N 条 · 第 x/y 页」。
- 状态列用彩色标签（如 运行=绿、停机=黄、报废=灰），不要只用文字颜色；所有状态标签、表头 `<th>`、单号、日期、金额与操作列按钮必须加 `whitespace-nowrap shrink-0`，严禁在较窄视口下把状态胶囊压成竖排两行。
- 空结果显示「没有符合条件的记录」并提供「清空筛选」。
- 表格外层**始终**包裹 `overflow-x-auto w-full`（适配 1280px 笔记本与较窄窗格横向滚动），主内容区严禁套 `max-w-5xl` / `max-w-6xl` / `max-w-7xl` / `max-w-[1440px]` + `mx-auto`，保证在 1920 / 2K / 4K 宽屏舒展铺满；筛选栏使用 `flex flex-wrap items-center gap-2` 自动折行不挤压。

## 数据
- 主键用 `crypto.randomUUID()`；创建时间、修改时间自动填（dayjs）。
- 应用启用了云服务时，用 `window.zhiye.cloud.db` 读写；否则保存到 localStorage（**必须用 `try / catch` 包裹并在失败时静默降级为内存状态**，因为工作台预览运行在禁用本地存储的沙箱 iframe 中），并在页面上注明「数据保存在本机浏览器」。
