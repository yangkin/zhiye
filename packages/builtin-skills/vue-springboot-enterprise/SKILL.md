---
name: vue-springboot-enterprise
description: Vue 3 + Java Spring Boot 3.5 离线全栈应用工程规范：前后端一体目录组织、Maven 离线依赖复用、端口与健康检查契约、DATA_DIR 与托管 PostgreSQL 持久化、统一异常处理与零外部 CDN。构建 Vue + Java Spring Boot 全栈应用时必读。
---

# Vue 3 + Java Spring Boot 3.5 离线全栈工程规范

## 1. 标准目录结构与单包交付
采用前后端一体清晰结构，保证平台离线构建与单容器稳定托管：
- `frontend/`：Vue 3 (`<script setup lang="ts">`) + Vite + TypeScript + Tailwind CSS（可复用预热模板依赖缓存，构建输出到 `frontend/dist/`，运行托管时自动同步至 `backend/src/main/resources/static/`）。
- `backend/`：Java 17 + Spring Boot 3.5 (`pom.xml`)，通过 `mvn -B -o -DskipTests package` 离线打成可执行 Fat JAR（`target/*.jar`）。
- **严禁联网拉包与外部 CDN**：`pom.xml` 仅使用沙箱本地 `.m2` 预热缓存中已有的 Spring Boot Starter（`spring-boot-starter-web`、`spring-boot-starter-validation`、`spring-boot-starter-jdbc`、`postgresql`、`jackson` 等）；前端字体/图标/图表全部本地打包，不引用外网 CDN。

## 2. 平台运行态强制契约
- **端口监听**：`application.yml` 必须配置 `server.port: ${PORT:${SERVER_PORT:8080}}`，绝不硬编码死端口。
- **健康探针**：必须暴露免鉴权 `GET /api/health`（返回 HTTP 200 及 JSON `{"status":"ok","timestamp":...}`），供平台蓝绿切流与 K8s ReadinessProbe 使用。
- **非 Root 可写持久化**：
  - 文件/JSON/SQLite 存储必须读取环境变量 `DATA_DIR`（默认 `./data`），并在启动时 `Files.createDirectories(...)` 自动建目录，严禁写入只读源码目录；
  - 若使用 PostgreSQL，读取 `JDBC_DATABASE_URL` / `DATABASE_URL`（由平台自动注入隔离 Schema 凭据），启动时幂等执行 `CREATE TABLE IF NOT EXISTS ...` 建表与种子初始化。
- **SPA 路由回退**：对非 `/api/**`、非静态资源扩展名的 GET 请求转发至 `/index.html`，防止前端路由刷新 404。

## 3. 后端代码质量底线
- 清晰分层：`controller`（请求参数校验 `@Valid` 与路由）→ `service`（业务逻辑、状态机校验、并发锁/事务）→ `store/repository`（持久化与原子写盘 `Files.move(..., ATOMIC_MOVE)`）；
- 统一 `@RestControllerAdvice` 将参数校验错误映射为 `400`、资源不存在映射为 `404`、状态冲突映射为 `409`，返回结构化中文 `{"error":"...","code":"..."}`；
- 前端所有请求走相对路径 `/api/...`（开发环境配 Vite `server.proxy` 转发），杜绝写死 `http://localhost:8080` 导致发布子域名跨域失败。
