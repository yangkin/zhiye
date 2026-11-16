# 织页本地开发环境变量（被其他脚本 source）
export ZHIYE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
export ZHIYE_NODE_DIR="${ZHIYE_NODE_DIR:-$HOME/.nvm/versions/node/v24.14.1/bin}"
export PATH="$ZHIYE_NODE_DIR:$JAVA_HOME/bin:/opt/homebrew/opt/postgresql@16/bin:$PATH"
# 本机配置（不放密钥），例如 ZHIYE_MODEL=deepseek；命令行里显式设置的变量优先
if [ -f "$ZHIYE_ROOT/runtime/local.env" ]; then
  while IFS='=' read -r k v; do
    case "$k" in ''|\#*) continue;; esac
    [ -z "${!k:-}" ] && export "$k=$v"
  done < "$ZHIYE_ROOT/runtime/local.env"
fi
# 模型上游：默认 Kimi；换别的（例如 DeepSeek）时用 ZHIYE_MODEL=deepseek 并准备对应的密钥文件
export ZHIYE_MODEL="${ZHIYE_MODEL:-kimi}"
case "$ZHIYE_MODEL" in
  deepseek)
    export ZHIYE_SECRETS_FILE="${ZHIYE_SECRETS_FILE:-/tmp/claude-501/zhiye-secrets/deepseek.env}"
    export ZHIYE_LLM_BASE_URL="${ZHIYE_LLM_BASE_URL:-https://api.deepseek.com/v1}"
    export ZHIYE_LLM_KEY_ENV="${ZHIYE_LLM_KEY_ENV:-DEEPSEEK_API_KEY}"
    export ZHIYE_LLM_MODEL="${ZHIYE_LLM_MODEL:-deepseek-flash}"
    export ZHIYE_MODEL_PROVIDER="${ZHIYE_MODEL_PROVIDER:-deepseek}"
    export ZHIYE_MODEL_ID="${ZHIYE_MODEL_ID:-deepseek-flash}"
    ;;
  *)
    export ZHIYE_SECRETS_FILE="${ZHIYE_SECRETS_FILE:-/tmp/claude-501/zhiye-secrets/kimi.env}"
    ;;
esac
export ZHIYE_PG_PORT="${ZHIYE_PG_PORT:-5433}"
export LC_ALL=C LANG=C
export ZHIYE_NPM_REGISTRY="${ZHIYE_NPM_REGISTRY:-https://registry.npmmirror.com}"
