# syntax=docker/dockerfile:1.7
# 织页 agent-runner 沙箱镜像（公司 OpenSandbox 使用；zhiye.sandbox.provider=opensandbox）
#
#   docker build -f deploy/docker/runner.Dockerfile -t zhiye-runner:dev .      # 在 zhiye/ 仓库根执行
#
# 布局（与 OpenSandboxProvider 的默认配置对应）：
#   /opt/zhiye/runner/src/main.mjs                      runner 入口（node_modules 为生产依赖）
#   /opt/zhiye/packages/vite-plugin-loc/index.mjs       ZHIYE_LOC_PLUGIN
#   /opt/zhiye/packages/editor-bridge/bridge.js         ZHIYE_BRIDGE_PATH
#   /opt/zhiye/packages/{app,vue,skill,static,script}-template  模板（runner 本身不读，保留便于排查）
#   /opt/zhiye/template-cache/node_modules              React 模板的全部依赖（工作区 node_modules 链接到这里）
#   /opt/zhiye/vue-template-cache/node_modules          Vue 模板的全部依赖
#   /usr/bin/chromium                                   ZHIYE_CHROME_PATH（冒烟测试、截图自查；ZHIYE_SMOKE_REQUIRED=1：浏览器不可用时冒烟测试判失败，不再跳过）
#   /opt/zhiye/bin/start-runner.sh                      修正 node_modules 链接后启动 HTTP runner（OpenSandbox 的 entrypoint）
ARG NODE_IMAGE=node:22-bookworm-slim

# ---------- 依赖安装（构建期可以联网；运行期 Pod 默认不能出网） ----------
FROM ${NODE_IMAGE} AS deps
ARG NPM_REGISTRY=https://registry.npmmirror.com
ENV npm_config_registry=${NPM_REGISTRY} npm_config_update_notifier=false npm_config_fund=false npm_config_audit=false
WORKDIR /build

COPY runner/package.json runner/package-lock.json runner/
RUN --mount=type=cache,target=/root/.npm cd runner && npm ci --omit=dev --ignore-scripts

COPY packages/vite-plugin-loc/package.json packages/vite-plugin-loc/package-lock.json packages/vite-plugin-loc/
RUN --mount=type=cache,target=/root/.npm cd packages/vite-plugin-loc && npm ci --omit=dev --ignore-scripts

# 模板依赖：package.json 取自 packages/app-template，锁文件在 deploy/docker/template-cache/
COPY packages/app-template/package.json template-cache/package.json
COPY deploy/docker/template-cache/package-lock.json template-cache/package-lock.json
# esbuild / rollup / tailwind oxide 的 linux 平台二进制来自 optionalDependencies，不要加 --omit=optional
# npm ci --ignore-scripts 后部分原生二进制没有执行位（esbuild 沙箱里 EACCES），显式补上并自检
RUN --mount=type=cache,target=/root/.npm cd template-cache && npm ci --ignore-scripts \
 && find node_modules -type f -path '*/bin/*' -exec chmod a+x {} + \
 && node node_modules/vite/bin/vite.js --version && node node_modules/typescript/bin/tsc --version \
 && node_modules/@esbuild/linux-x64/bin/esbuild --version \
 && rm -rf node_modules/.cache

# Vue 模板依赖：package.json 取自 packages/vue-template，锁文件在 deploy/docker/vue-template-cache/
COPY packages/vue-template/package.json vue-template-cache/package.json
COPY deploy/docker/vue-template-cache/package-lock.json vue-template-cache/package-lock.json
RUN --mount=type=cache,target=/root/.npm cd vue-template-cache && npm ci --ignore-scripts \
 && find node_modules -type f -path '*/bin/*' -exec chmod a+x {} + \
 && node node_modules/vite/bin/vite.js --version && node node_modules/vue-tsc/bin/vue-tsc.js --version \
 && node_modules/@esbuild/linux-x64/bin/esbuild --version \
 && rm -rf node_modules/.cache

# Fullstack 模板依赖：package.json 取自 packages/fullstack-template
COPY packages/fullstack-template/package.json fullstack-template-cache/package.json
RUN --mount=type=cache,target=/root/.npm cd fullstack-template-cache && npm install --no-audit --no-fund --ignore-scripts \
 && find node_modules -type f -path '*/bin/*' -exec chmod a+x {} + \
 && rm -rf node_modules/.cache

# ---------- 运行镜像 ----------
FROM ${NODE_IMAGE}
# 构建机到镜像源的速度差异很大，按需换（mirrors.aliyun.com / mirrors.tuna.tsinghua.edu.cn / 空 = 官方源）
ARG APT_MIRROR=mirrors.tuna.tsinghua.edu.cn
ARG MAVEN_MIRROR=https://maven.aliyun.com/repository/public
# chromium：冒烟测试；git：agent 的 git status/diff；python3 / bash：脚本运行与检查；openjdk-17-jdk-headless + maven：Java / Spring Boot 离线编译与运行
RUN set -eux; \
    if [ -n "${APT_MIRROR}" ] && [ -f /etc/apt/sources.list.d/debian.sources ]; then \
      sed -i "s|deb.debian.org|${APT_MIRROR}|g" /etc/apt/sources.list.d/debian.sources; fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends chromium git python3 bash ca-certificates fonts-noto-cjk tini openjdk-17-jdk-headless maven; \
    rm -rf /var/lib/apt/lists/* /usr/share/doc /usr/share/man; \
    JAVA_DIR="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"; \
    ln -sfn "${JAVA_DIR}" /usr/lib/jvm/zhiye-jdk; \
    groupadd -g 10001 zhiye; useradd -u 10001 -g 10001 -M -d /agent -s /bin/bash zhiye; \
    mkdir -p /workspace /agent /opt/zhiye/bin /opt/zhiye/m2/repository; chown 10001:10001 /workspace /agent

COPY --from=deps /build/runner/node_modules /opt/zhiye/runner/node_modules
COPY runner/package.json /opt/zhiye/runner/package.json
COPY runner/src /opt/zhiye/runner/src
COPY --from=deps /build/packages/vite-plugin-loc/node_modules /opt/zhiye/packages/vite-plugin-loc/node_modules
COPY packages/vite-plugin-loc/index.mjs packages/vite-plugin-loc/package.json /opt/zhiye/packages/vite-plugin-loc/
COPY packages/editor-bridge /opt/zhiye/packages/editor-bridge
COPY packages/app-template /opt/zhiye/packages/app-template
COPY packages/vue-template /opt/zhiye/packages/vue-template
COPY packages/fullstack-template /opt/zhiye/packages/fullstack-template
COPY packages/vue-springboot-template /opt/zhiye/packages/vue-springboot-template
COPY packages/skill-template /opt/zhiye/packages/skill-template
COPY packages/static-template /opt/zhiye/packages/static-template
COPY packages/script-template /opt/zhiye/packages/script-template
COPY --from=deps /build/template-cache /opt/zhiye/template-cache
COPY --from=deps /build/vue-template-cache /opt/zhiye/vue-template-cache
COPY --from=deps /build/fullstack-template-cache /opt/zhiye/fullstack-template-cache

# 预热 Vue + Spring Boot 离线 Maven 仓库，保障沙箱无外网时 mvn -B -o -DskipTests package 秒级离线编译
RUN set -eux; \
    mkdir -p /opt/zhiye/m2/repository; \
    if [ -n "${MAVEN_MIRROR}" ]; then \
      printf '<settings><mirrors><mirror><id>m</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>\n' "${MAVEN_MIRROR}" > /tmp/m2-settings.xml; \
      mvn -B -q -s /tmp/m2-settings.xml -f /opt/zhiye/packages/vue-springboot-template/backend/pom.xml -Dmaven.repo.local=/opt/zhiye/m2/repository dependency:go-offline package -DskipTests; \
      rm -f /tmp/m2-settings.xml; \
    else \
      mvn -B -q -f /opt/zhiye/packages/vue-springboot-template/backend/pom.xml -Dmaven.repo.local=/opt/zhiye/m2/repository dependency:go-offline package -DskipTests; \
    fi; \
    rm -rf /opt/zhiye/packages/vue-springboot-template/backend/target; \
    find /opt/zhiye/m2/repository -name '_remote.repositories' -delete; \
    chmod -R a+rX /opt/zhiye/m2

ARG NPM_REGISTRY=https://registry.npmmirror.com
ARG PNPM_VERSION=10.34.9
ENV COREPACK_HOME=/opt/zhiye/corepack COREPACK_ENABLE_DOWNLOAD_PROMPT=0 COREPACK_NPM_REGISTRY=${NPM_REGISTRY}
RUN corepack enable pnpm && corepack prepare pnpm@${PNPM_VERSION} --activate && pnpm --version && chmod -R a+rX /opt/zhiye/corepack
COPY --chmod=0755 deploy/docker/start-runner.sh /opt/zhiye/bin/start-runner.sh

ENV JAVA_HOME=/usr/lib/jvm/zhiye-jdk \
    ZHIYE_M2_REPO=/opt/zhiye/m2/repository \
    MAVEN_OPTS="-Dmaven.repo.local=/opt/zhiye/m2/repository -Dmaven.repo.local.tail=/opt/zhiye/m2/repository -Daether.syncContext.named.factory=noop" \
    ZHIYE_LOC_PLUGIN=/opt/zhiye/packages/vite-plugin-loc/index.mjs \
    ZHIYE_BRIDGE_PATH=/opt/zhiye/packages/editor-bridge/bridge.js \
    ZHIYE_CHROME_PATH=/usr/bin/chromium \
    ZHIYE_SMOKE_REQUIRED=1 \
    ZHIYE_TEMPLATE_NODE_MODULES=/opt/zhiye/template-cache/node_modules \
    ZHIYE_VUE_TEMPLATE_NODE_MODULES=/opt/zhiye/vue-template-cache/node_modules \
    ZHIYE_FULLSTACK_TEMPLATE_NODE_MODULES=/opt/zhiye/fullstack-template-cache/node_modules \
    HOME=/agent \
    TMPDIR=/tmp \
    NODE_ENV=production \
    npm_config_update_notifier=false

USER 10001:10001
WORKDIR /workspace
# runner 直接作为容器入口启动，控制端口 8790 只由 OpenSandbox Ingress 暴露给工作进程
EXPOSE 8790
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["/opt/zhiye/bin/start-runner.sh"]
