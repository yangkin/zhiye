#!/usr/bin/env bash
source "$(dirname "$0")/env.sh"
cd "$ZHIYE_ROOT"
for f in runtime/api.pid runtime/site.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null && rm -f "$f"; done
# 给 api 的优雅停机一个窗口：在跑任务做完后进程会自己退出，期间不要动它的 runner。
# Windows 上 Git Bash 的 kill 对原生 JVM 不一定触发优雅停机，超时后照旧清场（进行中的任务会失败）。
waited=0
while [ $waited -lt 660 ]; do
  alive=0
  for name in api site; do
    # pid 文件已删，按 jar + profile 认进程
    pgrep -f "zhiye-server.jar.*$name" >/dev/null 2>&1 && alive=1
  done
  [ $alive -eq 0 ] && break
  sleep 3; waited=$((waited+3))
  [ $waited -eq 30 ] && echo "… 等待在跑任务完成（优雅停机，最多 11 分钟）"
done
[ $waited -ge 660 ] && echo "⚠ 优雅停机超时，强制清场（进行中的任务会失败）"
pkill -f "zhiye/runner/src/main.mjs" 2>/dev/null || true
pkill -f "zhiye-server.jar" 2>/dev/null || true
[ "${1:-}" = "--all" ] && pg_ctl -D runtime/pg stop -m fast || true
echo "已停止"
