#!/usr/bin/env bash
source "$(dirname "$0")/env.sh"
cd "$ZHIYE_ROOT"
for f in runtime/api.pid runtime/site.pid; do [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null && rm -f "$f"; done
pkill -f "zhiye/runner/src/main.mjs" 2>/dev/null || true
[ "${1:-}" = "--all" ] && pg_ctl -D runtime/pg stop -m fast || true
echo "已停止"
