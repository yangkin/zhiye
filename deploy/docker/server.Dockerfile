# syntax=docker/dockerfile:1.7
# 织页主服务镜像：同一个镜像跑 api（含任务工作进程、模型网关）和 site（预览/站点网关），
# 用 SPRING_PROFILES_ACTIVE 区分，例如 "api,k8s" / "site,k8s"。
#
#   docker build -f deploy/docker/server.Dockerfile -t zhiye-server:dev .                 # 在 zhiye/ 仓库根执行，容器内完整构建
#   docker build -f deploy/docker/server.Dockerfile -t zhiye-server:dev \
#     --build-arg JAR_SOURCE=prebuilt --build-arg WEB_SOURCE=prebuilt .                   # 复用本机已构建的 jar 和 web/dist
#
# 布局：ZHIYE_ROOT=/opt/zhiye，下面有 web/dist、packages/{app,skill,static,script}-template（GitService / TaskExecutor 用模板初始化仓库）、packages/builtin-skills（内置技能）、packages/fn-runtime（云函数宿主，配合 /usr/local/bin/deno）。
# 运行时目录 /var/lib/zhiye/runtime（K8s 上挂 RWX 卷）。
ARG NODE_IMAGE=node:22-bookworm-slim
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre
# 云函数运行时（每次调用一个受限的 deno 进程，见 packages/fn-runtime/host.ts）
ARG DENO_IMAGE=denoland/deno:bin-2.9.8
ARG JAR_SOURCE=maven
ARG WEB_SOURCE=build

# ---------- 前端 ----------
FROM ${NODE_IMAGE} AS web-build
ARG NPM_REGISTRY=https://registry.npmmirror.com
ENV npm_config_registry=${NPM_REGISTRY} npm_config_update_notifier=false npm_config_fund=false npm_config_audit=false
WORKDIR /src
COPY package.json package-lock.json ./
COPY web/package.json web/
COPY e2e/package.json e2e/
RUN --mount=type=cache,target=/root/.npm npm ci -w web --ignore-scripts
COPY web web
RUN npm run build -w web

FROM scratch AS web-prebuilt
COPY web/dist /src/web/dist

FROM web-${WEB_SOURCE} AS web

# ---------- 主服务 jar ----------
FROM ${MAVEN_IMAGE} AS jar-maven
ARG MAVEN_MIRROR=https://maven.aliyun.com/repository/public
WORKDIR /src/server
RUN mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' "${MAVEN_MIRROR}" > /root/.m2/settings.xml
COPY server/pom.xml pom.xml
COPY server/zhiye-common/pom.xml zhiye-common/pom.xml
COPY server/zhiye-api/pom.xml zhiye-api/pom.xml
COPY server/zhiye-server/pom.xml zhiye-server/pom.xml
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -q dependency:go-offline -DexcludeGroupIds=com.tcl || true
COPY server/zhiye-common/src zhiye-common/src
COPY server/zhiye-api/src zhiye-api/src
COPY server/zhiye-server/src zhiye-server/src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -q -DskipTests package && cp zhiye-server/target/zhiye-server.jar /zhiye-server.jar

FROM scratch AS jar-prebuilt
COPY server/zhiye-server/target/zhiye-server.jar /zhiye-server.jar

FROM jar-${JAR_SOURCE} AS jar

FROM ${DENO_IMAGE} AS deno

# ---------- 运行镜像 ----------
FROM ${JRE_IMAGE}
RUN set -eux; groupadd -g 10001 zhiye; useradd -u 10001 -g 10001 -M -d /var/lib/zhiye -s /usr/sbin/nologin zhiye; \
    mkdir -p /opt/zhiye/server /var/lib/zhiye/runtime; chown -R 10001:10001 /var/lib/zhiye
COPY --from=web /src/web/dist /opt/zhiye/web/dist
COPY packages/app-template /opt/zhiye/packages/app-template
COPY packages/vue-template /opt/zhiye/packages/vue-template
COPY packages/fullstack-template /opt/zhiye/packages/fullstack-template
COPY packages/skill-template /opt/zhiye/packages/skill-template
COPY packages/static-template /opt/zhiye/packages/static-template
COPY packages/script-template /opt/zhiye/packages/script-template
COPY packages/builtin-skills /opt/zhiye/packages/builtin-skills
COPY packages/fn-runtime /opt/zhiye/packages/fn-runtime
COPY --from=deno /deno /usr/local/bin/deno
COPY --from=jar /zhiye-server.jar /opt/zhiye/server/zhiye-server.jar

ENV ZHIYE_ROOT=/opt/zhiye \
    ZHIYE_RUNTIME_DIR=/var/lib/zhiye/runtime \
    SPRING_PROFILES_ACTIVE=api \
    LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8"
USER 10001:10001
WORKDIR /opt/zhiye
EXPOSE 8080 8083
ENTRYPOINT ["java", "-jar", "/opt/zhiye/server/zhiye-server.jar"]
