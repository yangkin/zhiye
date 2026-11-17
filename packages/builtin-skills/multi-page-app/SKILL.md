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
- 桌面：左侧 200px 导航（图标用 lucide-react，当前项高亮），右侧内容区；手机：顶部标题栏 + 抽屉菜单。
- 每个页面顶部：标题 + 一句说明 + 右侧主操作按钮（如「新增设备」）。
- 列表页：搜索框 + 筛选下拉 + 表格；行操作（查看、编辑、删除）放在最后一列，不要调用浏览器原生 `window.confirm()` / `alert()` / `prompt()`（沙箱预览会静默拦截）。
