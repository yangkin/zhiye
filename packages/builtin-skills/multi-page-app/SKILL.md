---
name: multi-page-app
description: 多页面内部业务应用的结构：侧边导航 + 列表页 + 详情/编辑页 + hash 路由。需求里有多个功能模块、台账、管理后台、需要在页面之间跳转时使用。
---
# 多页面业务应用

## 路由（只能用 hash 路由）
```tsx
import { HashRouter, Routes, Route, NavLink, Navigate, useNavigate, useParams } from 'react-router'

export default function App() {
  return (
    <HashRouter>
      <Layout>
        <Routes>
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/devices" element={<DeviceList />} />
          <Route path="/devices/:id" element={<DeviceDetail />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </Layout>
    </HashRouter>
  )
}
```
- 预览地址在子路径下，**不要用 BrowserRouter / createBrowserRouter**（会白屏，校验也会失败）。
- 从 `react-router` 导入，不是 `react-router-dom`。

## 目录
- `src/pages/`：每个页面一个文件；`src/components/`：通用组件（Layout、PageHeader、EmptyState、ConfirmButton）；`src/data/`：示例数据；`src/lib/`：纯函数（筛选、统计、格式化）。
- 共享状态（如设备列表）放一个 `src/store.ts`：用 React context 或简单的模块级 store + `useSyncExternalStore`，同步到 localStorage 时**必须封装在 `try / catch` 中降级为内存状态**（工作台沙箱预览禁用同源存储，直接访问会抛 `SecurityError` 白屏）。

## 布局
- 全分辨率适配：宽屏（`lg:` ≥ 1024px）采用左侧 `200px~220px` 固定导航（图标用 lucide-react，当前项高亮）+ 右侧 `flex-1 min-w-0` 流式内容区（使用 `w-full`，不要把整个管理后台锁死在 `max-w-5xl` 导致 2K/4K 大屏两侧大片空白）；`< 1024px`（含工作台窄预览窗、平板与手机）自动收起为顶部标题栏 + 抽屉菜单。
- 每个页面顶部：标题 + 一句说明 + 右侧主操作按钮（如「新增设备」），支持 `flex-wrap gap-3` 避免窄屏按钮挤压。
- 列表页：搜索框 + 筛选下拉（`flex-wrap gap-2`）+ 带 `overflow-x-auto` 的表格；表头、状态胶囊、单号、日期与操作列按钮均加 `whitespace-nowrap` 防止被挤成竖排；行操作（查看、编辑、删除）放在最后一列，不要调用浏览器原生 `window.confirm()` / `alert()` / `prompt()`（沙箱预览会静默拦截）。
