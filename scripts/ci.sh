#!/usr/bin/env bash
# 织页全仓质量门禁：评测集校验 → runner 单测 → 前端类型检查与构建 → 后端全量测试
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/scripts/env.sh" 2>/dev/null || true
cd "$ROOT"

export PATH="$HOME/.nvm/versions/node/v24.14.1/bin:/opt/homebrew/bin:/opt/homebrew/opt/openjdk@21/bin:/opt/homebrew/opt/postgresql@16/bin:$PATH"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export JAVA_TOOL_OPTIONS="-DsocksNonProxyHosts=127.0.0.1|localhost|*.localhost"
# 测试固定使用假上游和测试密钥，不受本机 GLM / DeepSeek 配置影响
export ZHIYE_MODEL=kimi
export ZHIYE_LLM_KEY_ENV=KIMI_API_KEY
export KIMI_API_KEY="${KIMI_API_KEY:-test-key}"

echo "==> 1/4 Eval 基线与评分器检查"
node evals/run-eval.mjs --check

echo "==> 2/4 Runner 单测"
(cd runner && npm test)

echo "==> 3/4 前端类型检查与构建"
(cd web && npx tsc --noEmit -p .)
npm run build -w web

echo "==> 4/4 服务端集成测试"
# 默认跳过依赖公司内网统一登录/FiveStar 环境的 2 个 SkillHub 用例；设 INCLUDE_INTRANET=1 可全量运行
if [ "${INCLUDE_INTRANET:-0}" = "1" ]; then
  (cd server && mvn -o test)
else
  (cd server && mvn -o test -Dtest='!SkillHubTest,!SkillHubEnabledTest' -Dsurefire.failIfNoSpecifiedTests=false)
fi

echo "✓ 全仓质量门禁通过"
