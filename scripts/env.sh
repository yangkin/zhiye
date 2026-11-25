# 织页本地开发环境变量（被其他脚本 source）
export ZHIYE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 工具链路径：Windows（Git Bash）与 macOS（交接环境）自动适配；都可用 ZHIYE_JAVA_HOME / ZHIYE_NODE_DIR 覆盖
if [[ "$(uname -s)" == MINGW64_NT* || "$(uname -s)" == MINGW32_NT* || "$(uname -s)" == MSYS* ]]; then
  export JAVA_HOME="${ZHIYE_JAVA_HOME:-/c/Users/yangkai37/.jdks/ms-17.0.16}"
  export ZHIYE_NODE_DIR="${ZHIYE_NODE_DIR:-/c/Users/yangkai37/.local/nodejs/node-v22.20.0-win-x64}"
  export PATH="$ZHIYE_NODE_DIR:$JAVA_HOME/bin:/d/tools/postgresql-16/pgsql/bin:$PATH"
  if [ -f "$ZHIYE_ROOT/runtime/tools/deno/deno.exe" ]; then
    export ZHIYE_FUNCTIONS_DENO="${ZHIYE_FUNCTIONS_DENO:-$(cygpath -m "$ZHIYE_ROOT/runtime/tools/deno/deno.exe")}"
  fi
  if [ -z "${ZHIYE_CHROME_PATH:-}" ]; then
    for chrome in '/c/Program Files/Google/Chrome/Application/chrome.exe' '/c/Program Files (x86)/Google/Chrome/Application/chrome.exe'; do
      if [ -f "$chrome" ]; then export ZHIYE_CHROME_PATH="$(cygpath -m "$chrome")"; break; fi
    done
  fi
else
  export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"
  export ZHIYE_NODE_DIR="${ZHIYE_NODE_DIR:-$HOME/.nvm/versions/node/v24.14.1/bin}"
  export PATH="$ZHIYE_NODE_DIR:$JAVA_HOME/bin:/opt/homebrew/opt/postgresql@16/bin:$PATH"
fi
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
    if [ -z "${ZHIYE_SECRETS_FILE:-}" ]; then
      if [ -f "$ZHIYE_ROOT/runtime/secrets/deepseek.env" ]; then
        export ZHIYE_SECRETS_FILE="$ZHIYE_ROOT/runtime/secrets/deepseek.env"
      elif [ -f "/tmp/claude-501/zhiye-secrets/deepseek.env" ]; then
        export ZHIYE_SECRETS_FILE="/tmp/claude-501/zhiye-secrets/deepseek.env"
      else
        export ZHIYE_SECRETS_FILE="$ZHIYE_ROOT/runtime/secrets/deepseek.env"
      fi
    fi
    export ZHIYE_LLM_BASE_URL="${ZHIYE_LLM_BASE_URL:-https://api.deepseek.com/v1}"
    export ZHIYE_LLM_KEY_ENV="${ZHIYE_LLM_KEY_ENV:-DEEPSEEK_API_KEY}"
    export ZHIYE_LLM_MODEL="${ZHIYE_LLM_MODEL:-deepseek-flash}"
    export ZHIYE_MODEL_PROVIDER="${ZHIYE_MODEL_PROVIDER:-deepseek}"
    export ZHIYE_MODEL_ID="${ZHIYE_MODEL_ID:-deepseek-flash}"
    ;;
  glm)
    # 公司中转 GLM-5.3-Flash（同本机 Kimi Code 的 coding-infra 配置）：直连公司服务器，不依赖 cc-switch 本地代理。
    # 中转只按模型路由名调度，所以 Pi 侧用 ZHIYE_MODEL_DEF 整条自定义定义，模型名必须和中转路由一致。
    # 密钥由 run-dev.sh 从 ZHIYE_SECRETS_FILE（默认 runtime/secrets/coding-infra.env）加载进进程环境。
    export ZHIYE_SECRETS_FILE="${ZHIYE_SECRETS_FILE:-$ZHIYE_ROOT/runtime/secrets/coding-infra.env}"
    export ZHIYE_LLM_BASE_URL="${ZHIYE_LLM_BASE_URL:-https://llm.csot.tcl.com/v1}"
    export ZHIYE_LLM_KEY_ENV="${ZHIYE_LLM_KEY_ENV:-CODING_INFRA_API_KEY}"
    export ZHIYE_LLM_MODEL="${ZHIYE_LLM_MODEL:-Coding-Infra-pd-router/csot-sz-cloud/GLM-5.3-Flash-router}"
    # 借用 Pi 内置 deepseek provider（OpenAI 兼容），模型定义由 ZHIYE_MODEL_DEF 覆写
    export ZHIYE_MODEL_PROVIDER="${ZHIYE_MODEL_PROVIDER:-deepseek}"
    export ZHIYE_MODEL_ID="${ZHIYE_MODEL_ID:-Coding-Infra-pd-router/csot-sz-cloud/GLM-5.3-Flash-router}"
    # 注意：值里有 }，不能写进 ${var:-...}（会被当成扩展的结尾截断），单独判断赋值
    if [ -z "${ZHIYE_MODEL_DEF:-}" ]; then
      export ZHIYE_MODEL_DEF='{"id":"Coding-Infra-pd-router/csot-sz-cloud/GLM-5.3-Flash-router","name":"GLM-5.3-Flash (company relay)","api":"openai-completions","reasoning":true,"thinkingLevelMap":{"minimal":null,"low":"low","medium":null,"high":"high","max":"max"},"input":["text","image"],"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"contextWindow":400000,"maxTokens":131072}'
    fi
    ;;
  *)
    if [ -z "${ZHIYE_SECRETS_FILE:-}" ]; then
      if [ -f "$ZHIYE_ROOT/runtime/secrets/kimi.env" ]; then
        export ZHIYE_SECRETS_FILE="$ZHIYE_ROOT/runtime/secrets/kimi.env"
      elif [ -f "/tmp/claude-501/zhiye-secrets/kimi.env" ]; then
        export ZHIYE_SECRETS_FILE="/tmp/claude-501/zhiye-secrets/kimi.env"
      else
        export ZHIYE_SECRETS_FILE="$ZHIYE_ROOT/runtime/secrets/kimi.env"
      fi
    fi
    ;;
esac
export ZHIYE_PG_PORT="${ZHIYE_PG_PORT:-5433}"
export LC_ALL=C LANG=C
export ZHIYE_NPM_REGISTRY="${ZHIYE_NPM_REGISTRY:-https://registry.npmmirror.com}"
