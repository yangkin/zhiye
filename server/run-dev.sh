#!/usr/bin/env bash
# 本地启动织页主服务：同一个 jar 启动两次
#   api  (8080)：REST + SSE + 模型网关 + 任务工作进程 + 前端静态资源
#   site (8083)：预览 /p/{buildId}/ 与已发布站点 {slug}.localhost:8083
# 日志：runtime/logs/api.log、runtime/logs/site.log；PID：runtime/api.pid、runtime/site.pid（scripts/dev-down.sh 用）
#
# 环境变量：
#   ZHIYE_SECRETS_FILE   含 KIMI_API_KEY=... 的文件（默认 /tmp/claude-501/zhiye-secrets/kimi.env），只加载进进程环境
#   ZHIYE_RUNNER_SCRIPT  runner 入口（相对仓库根），默认 runner/src/main.mjs；可设为 server/dev/fake-runner.mjs
#   ZHIYE_PROFILES_EXTRA 追加给 api 的 profile，默认 dev（打开 /api/dev/faults）
#   ZHIYE_SEATBELT       true/false，是否用 sandbox-exec 包住 runner（默认 true；假 runner 默认 false）
#   SKIP_SERVER_BUILD=1  不重新打包
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ZHIYE_ROOT="${ZHIYE_ROOT:-$(cd "$SERVER_DIR/.." && pwd)}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ZHIYE_NODE_DIR="${ZHIYE_NODE_DIR:-$HOME/.nvm/versions/node/v24.14.1/bin}"
export PATH="$ZHIYE_NODE_DIR:$JAVA_HOME/bin:$PATH"
# 仓库路径含中文，Java 需要 UTF-8 区域设置
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8

RUNTIME="$ZHIYE_ROOT/runtime"
LOGS="$RUNTIME/logs"
mkdir -p "$LOGS"
JAR="$SERVER_DIR/zhiye-server/target/zhiye-server.jar"

# 1. 模型密钥（不打印）
SECRETS="${ZHIYE_SECRETS_FILE:-/tmp/claude-501/zhiye-secrets/kimi.env}"
if [ -f "$SECRETS" ]; then
  set -a; # shellcheck disable=SC1090
  . "$SECRETS"; set +a
else
  echo "⚠ 没有找到密钥文件 ${SECRETS}：模型调用会失败（KIMI_API_KEY 未设置）"
fi

# 2. runner
export ZHIYE_RUNNER_SCRIPT="${ZHIYE_RUNNER_SCRIPT:-runner/src/main.mjs}"
if [ ! -f "$ZHIYE_ROOT/$ZHIYE_RUNNER_SCRIPT" ] && [ ! -f "$ZHIYE_RUNNER_SCRIPT" ]; then
  echo "⚠ runner 入口 $ZHIYE_RUNNER_SCRIPT 不存在，改用假 runner server/dev/fake-runner.mjs"
  export ZHIYE_RUNNER_SCRIPT="server/dev/fake-runner.mjs"
fi
# 假 runner 在 server/ 下，Seatbelt 配置不允许读取，只给真实 runner 开沙箱
case "$ZHIYE_RUNNER_SCRIPT" in *fake-runner*) export ZHIYE_SEATBELT="${ZHIYE_SEATBELT:-false}";; esac
export ZHIYE_NODE_BINARY="${ZHIYE_NODE_BINARY:-$ZHIYE_NODE_DIR/node}"

# 3. 打包
needs_build=0
if [ "${SKIP_SERVER_BUILD:-0}" != "1" ]; then
  if [ ! -f "$JAR" ]; then needs_build=1
  elif [ -n "$(find "$SERVER_DIR"/zhiye-*/src/main "$SERVER_DIR"/pom.xml "$SERVER_DIR"/zhiye-*/pom.xml -newer "$JAR" -type f 2>/dev/null | head -1)" ]; then needs_build=1; fi
fi
if [ "$needs_build" = 1 ]; then
  echo "… 打包主服务"
  (cd "$SERVER_DIR" && mvn -q -B -DskipTests package >"$LOGS/server-build.log" 2>&1) || { echo "✗ 打包失败，见 $LOGS/server-build.log"; tail -30 "$LOGS/server-build.log"; exit 1; }
fi
echo "✓ 主服务已打包"

# 4. 停掉旧进程
for name in api site; do
  pidf="$RUNTIME/$name.pid"
  if [ -f "$pidf" ]; then kill "$(cat "$pidf")" 2>/dev/null || true; rm -f "$pidf"; fi
done
sleep 1

start() { # name profiles
  local name="$1" profiles="$2"
  # 这台机器常开着系统级 SOCKS 代理（Clash），JDK 会把 127.0.0.1 也走代理，导致连不上本地 Postgres。
  # 把本机地址排除在代理之外；需要时可以用 JAVA_TOOL_OPTIONS 覆盖。
  nohup "$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 \
    -DsocksNonProxyHosts='localhost|127.*|*.local|*.localhost' -jar "$JAR" \
    --spring.profiles.active="$profiles" >"$LOGS/$name.log" 2>&1 &
  echo $! >"$RUNTIME/$name.pid"
}

wait_http() { # name url pidfile
  local name="$1" url="$2" pid
  pid="$(cat "$RUNTIME/$name.pid")"
  for _ in $(seq 1 90); do
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "✗ $name 进程已退出，日志末尾："; tail -40 "$LOGS/$name.log"; return 1
    fi
    if curl -fsS -o /dev/null --max-time 2 "$url" 2>/dev/null; then return 0; fi
    sleep 1
  done
  echo "✗ $name 在 90 秒内没有响应 ${url}，日志末尾："; tail -40 "$LOGS/$name.log"; return 1
}

# api 先启动（执行数据库迁移），再启动 site
# ZHIYE_MOCK_INTRANET=1：模拟内网（统一认证 + 五星AI岛 + llm-gateway），见 application-mock.yml
MOCK_PROFILE=""; [ "${ZHIYE_MOCK_INTRANET:-0}" = "1" ] && MOCK_PROFILE=",mock"
start api "api,${ZHIYE_PROFILES_EXTRA:-dev}${MOCK_PROFILE}"
wait_http api "http://127.0.0.1:8080/api/meta" || exit 1
echo "✓ api  http://127.0.0.1:8080   （日志 runtime/logs/api.log，runner：${ZHIYE_RUNNER_SCRIPT}）"
start site "site${MOCK_PROFILE}"
wait_http site "http://127.0.0.1:8083/" || exit 1
echo "✓ site http://127.0.0.1:8083   （预览 /p/{buildId}/，站点 http://{slug}.localhost:8083/，日志 runtime/logs/site.log）"
echo "织页已启动：打开 http://127.0.0.1:8080"
exit 0
