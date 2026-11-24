---
name: fullstack-app
description: 织页前后端一体全栈应用开发规范（React 19 + Vite + Express + /data 持久化 + 可选 PostgreSQL + SSO 身份头），开发或修改全栈应用时按需读取。
---

# 织页全栈应用开发规范

## 1. 目录与运行约束
- 前端源码在 `src/`（React 19 + TypeScript + Tailwind CSS 4 + Recharts + Lucide），构建产物输出到 `dist/`。
- 后端源码在 `server/`，入口固定为 `server/index.mjs`（ESM，Express），负责：
  1. 提供 `/api/*` REST 接口与 `GET /api/health`（返回 HTTP 200 JSON `{ ok: true, ... }`）；
  2. 静态托管 `dist/`，对非 `/api` 路径返回 `dist/index.html`（SPA 回退），但**缺失的静态资源（如 `/assets/xxx.js`）必须返回 404**，不要回退成 HTML；
  3. 监听 `0.0.0.0` 与 `Number(process.env.PORT || 8080)`。
- 资源上限为单应用 **1C 1G**，保持单体简洁，不引入 Redis / Kafka / 外部微服务。

## 2. 接口与持久化最佳实践
- 前端请求一律使用同源相对路径 `fetch('/api/...')`，**严禁**硬编码 `http://localhost:xxxx`。
- 默认使用 `process.env.DATA_DIR || '.data'` 持久化 JSON/SQLite 文件：
  - 写入采用原子替换（写 `.tmp` 再 `renameSync`），避免并发或断电损坏；
  - 若现有数据文件损坏（JSON 解析失败），**拒绝静默覆盖清空**，保留原文件并抛错或写明恢复提示；
  - 首次空目录时幂等初始化贴近业务的演示种子数据。
- 若运行环境注入了 `process.env.DATABASE_URL`（用户在「服务托管」开启独立 PostgreSQL Schema），可自动使用 `pg` 或提示兼容。
- 身份识别直接读取平台网关透传的 SSO 请求头：`X-Zhiye-User-Id`、`X-Zhiye-User-Login`、`X-Zhiye-User-Name`、`X-Zhiye-User-Admin`（本地开发未注入时可回退为默认演示用户）。

## 3. 自检与排查
- 修改或排查线上运行异常时，先调用 `service_runtime_inspect` 查看脱敏日志、已配环境变量键名与数据库表结构。
- 收尾前务必调用 `verify_app`（会自动执行脚本语法、TypeScript 类型检查、Vite 构建、临时端口拉起后端测 `/api/health` 与首屏渲染冒烟），全部通过后再总结交付。
