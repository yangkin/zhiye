#!/bin/sh
# 在沙箱里启动 HTTP runner（cwd = /workspace）。
# 工作进程为 template / vue 项目建的 /workspace/node_modules 是指向工作进程自己路径的绝对链接，
# 在沙箱里解析不到，这里改指到镜像内的模板依赖（Vue 模板项目指到 Vue 的那份）。
set -e
cd /workspace
if [ -L node_modules ]; then
  T="${ZHIYE_TEMPLATE_NODE_MODULES:-/opt/zhiye/template-cache/node_modules}"
  case "$(readlink node_modules)" in
    */vue-template-cache/node_modules) T="${ZHIYE_VUE_TEMPLATE_NODE_MODULES:-/opt/zhiye/vue-template-cache/node_modules}" ;;
    */fullstack-template-cache/node_modules) T="${ZHIYE_FULLSTACK_TEMPLATE_NODE_MODULES:-/opt/zhiye/fullstack-template-cache/node_modules}" ;;
  esac
  if [ "$(readlink node_modules)" != "$T" ]; then ln -sfn "$T" node_modules; fi
fi
if [ -L frontend/node_modules ]; then
  FT="${ZHIYE_VUE_TEMPLATE_NODE_MODULES:-/opt/zhiye/vue-template-cache/node_modules}"
  if [ "$(readlink frontend/node_modules)" != "$FT" ]; then ln -sfn "$FT" frontend/node_modules; fi
elif [ ! -e frontend/node_modules ] && [ -f frontend/package.json ] && grep -q '"vue"' frontend/package.json 2>/dev/null; then
  FT="${ZHIYE_VUE_TEMPLATE_NODE_MODULES:-/opt/zhiye/vue-template-cache/node_modules}"
  if [ -d "$FT" ]; then ln -sfn "$FT" frontend/node_modules; fi
fi
if [ -d "${ZHIYE_M2_REPO:-/opt/zhiye/m2/repository}" ] && [ ! -e "${HOME:-/agent}/.m2/repository" ]; then
  mkdir -p "${HOME:-/agent}/.m2"
  ln -sfn "${ZHIYE_M2_REPO:-/opt/zhiye/m2/repository}" "${HOME:-/agent}/.m2/repository"
fi
exec node /opt/zhiye/runner/src/main.mjs
