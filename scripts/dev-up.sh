#!/usr/bin/env bash
# 一键启动本地环境：PostgreSQL(5433) → 前端构建 → 主服务 api(8080) + 预览/站点网关 site(8083)
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$ZHIYE_ROOT"
mkdir -p runtime/logs

# 1. PostgreSQL
if ! pg_isready -h 127.0.0.1 -p "$ZHIYE_PG_PORT" -q; then
  if [ ! -f runtime/pg/PG_VERSION ]; then initdb -D runtime/pg -U zhiye --auth=trust -E UTF8 --locale=C >/dev/null; fi
  pg_ctl -D runtime/pg -o "-p $ZHIYE_PG_PORT -k /tmp -c listen_addresses=127.0.0.1" -l runtime/pg.log -w start >/dev/null
  psql -h 127.0.0.1 -p "$ZHIYE_PG_PORT" -U zhiye -d postgres -tAc "select 1 from pg_database where datname='zhiye'" | grep -q 1 || createdb -h 127.0.0.1 -p "$ZHIYE_PG_PORT" -U zhiye zhiye
fi
echo "✓ PostgreSQL 127.0.0.1:$ZHIYE_PG_PORT"

# 2. 生成应用模板依赖缓存（React / Vue / Fullstack 模板）
for pair in template-cache:app-template vue-template-cache:vue-template; do
  cache="${pair%%:*}"; tpl="${pair##*:}"; lock="deploy/docker/$cache/package-lock.json"
  if [ ! -d "runtime/$cache/node_modules" ] || ! cmp -s "packages/$tpl/package.json" "runtime/$cache/package.json" || ! cmp -s "$lock" "runtime/$cache/package-lock.json"; then
    mkdir -p "runtime/$cache" && cp "packages/$tpl/package.json" "$lock" "runtime/$cache/"
    (cd "runtime/$cache" && npm ci --no-audit --no-fund)
  fi
done
if [ ! -d "runtime/fullstack-template-cache/node_modules" ] || ! cmp -s "packages/fullstack-template/package.json" "runtime/fullstack-template-cache/package.json"; then
  mkdir -p "runtime/fullstack-template-cache" && cp "packages/fullstack-template/package.json" "runtime/fullstack-template-cache/"
  (cd "runtime/fullstack-template-cache" && npm install --no-audit --no-fund)
fi
if [ -f "packages/vue-springboot-template/backend/pom.xml" ]; then
  (mvn -B -q -o -f "packages/vue-springboot-template/backend/pom.xml" test-compile >/dev/null 2>&1 || mvn -B -q -f "packages/vue-springboot-template/backend/pom.xml" dependency:go-offline >/dev/null 2>&1 || true)
fi
echo "✓ 模板依赖缓存（含 Vue + Spring Boot 离线 Maven 依赖）"

# 3. 前端
if [ "${SKIP_WEB_BUILD:-0}" != "1" ]; then (npm run build -w web >runtime/logs/web-build.log 2>&1) && echo "✓ 前端已构建" || { echo "✗ 前端构建失败，见 runtime/logs/web-build.log"; exit 1; }; fi

# 4. 主服务（api + site 两个 profile）
exec bash server/run-dev.sh
