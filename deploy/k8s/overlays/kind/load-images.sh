#!/usr/bin/env bash
# 把本机构建的镜像导入 kind 集群。
# Docker Desktop 打开 containerd 镜像存储时 `kind load docker-image` 会报 "content digest ... not found"，
# 所以先 docker save 单一平台再 kind load image-archive。
set -euo pipefail
CLUSTER="${KIND_CLUSTER:-zhiye-kind}"
PLATFORM="${PLATFORM:-linux/$(docker version -f '{{.Server.Arch}}')}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
for img in "${@:-zhiye-server:dev zhiye-runner:dev postgres:16}"; do
  for i in $img; do
    echo "→ $i ($PLATFORM)"
    docker save --platform "$PLATFORM" "$i" -o "$TMP/img.tar"
    kind load image-archive "$TMP/img.tar" --name "$CLUSTER"
  done
done
