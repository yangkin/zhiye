# 自建 K8s 沙箱多副本部署指南（无需 OpenSandbox Server）

本 overlay 用于在自有 Kubernetes 集群上直接运行织页，沙箱采用原生 Kubernetes Pod 模式（`ZHIYE_SANDBOX_PROVIDER=k8s`），`zhiye-api` 与 `zhiye-site` 默认各 2 副本高可用部署。

## 1. 前置资源清单

1. **Kubernetes 集群**（推荐 CNI 支持 `NetworkPolicy`，如 Calico / Cilium / Terway）
2. **PostgreSQL 16**（需要建 schema 权限；首次启动由 Flyway 自动建表迁移）
3. **RWX 共享存储（NFS / NAS）**：预先创建导出目录 `/zhiye-runtime`，并确保 UID/GID `10001` 可读写
4. **镜像仓库**：推送两个镜像：
   ```bash
   docker build -f deploy/docker/runner.Dockerfile -t <registry>/zhiye/zhiye-runner:<版本> .
   docker build -f deploy/docker/server.Dockerfile -t <registry>/zhiye/zhiye-server:<版本> .
   docker push <registry>/zhiye/zhiye-runner:<版本>
   docker push <registry>/zhiye/zhiye-server:<版本>
   ```

## 2. 修改占位配置

替换本目录下 4 个文件中的 `<...>`：
- `kustomization.yaml`：`zhiye-server` 镜像地址与版本
- `config.yaml`：`ZHIYE_DB_URL`、域名、`ZHIYE_RUNNER_IMAGE`、认证与白名单配置
- `pv-nfs.yaml`：NFS/NAS 地址与路径（如用阿里云 NAS CSI，参考 `../prod/pv-nas.yaml`）
- `ingress-patch.yaml`：实际域名与 TLS Secret 名

## 3. 创建 Secret（无需 OpenSandbox Key）

```bash
kubectl create namespace zhiye --dry-run=client -o yaml | kubectl apply -f -
kubectl -n zhiye create secret generic zhiye-secrets \
  --from-literal=ZHIYE_DB_PASSWORD='<数据库密码>' \
  --from-literal=ZHIYE_CLOUD_SECRET="$(openssl rand -hex 32)" \
  --from-literal=KIMI_API_KEY='<模型密钥>'
```

## 4. 一键部署与自检

```bash
# 预览渲染清单
kubectl kustomize deploy/k8s/overlays/k8s-selfhost | head -n 60

# 部署
kubectl apply -k deploy/k8s/overlays/k8s-selfhost

# 1) 检查两命名空间的 RWX PVC 均已 Bound
kubectl get pvc -n zhiye
kubectl get pvc -n zhiye-sandbox

# 2) 检查 api / site 多副本就绪
kubectl get pods -n zhiye -o wide

# 3) 验证 api ServiceAccount 具备 zhiye-sandbox 命名空间的 Pod 管理权限
kubectl auth can-i create pods -n zhiye-sandbox --as=system:serviceaccount:zhiye:zhiye-api
kubectl auth can-i create pods/exec -n zhiye-sandbox --as=system:serviceaccount:zhiye:zhiye-api
```

## 5. 上线冒烟验收

1. 打开工作台，进入「数据看板 → 集群与沙箱」，确认 2 个 `api` 副本与 2 个 `site` 副本均显示**在线**，沙箱引擎显示 **Kubernetes Pod (`k8s`)**。
2. 发起一个简单应用生成任务，观察 `kubectl get pods -n zhiye-sandbox -w` 应自动创建并销毁任务 Pod。
3. 确认沙箱启动日志通过出网隔离自检（`isolationCheck`），任务完成后预览与发布站点均可正常打开。
4. 未来接入公司 OpenSandbox Server 时，仅需将 `ZHIYE_SANDBOX_PROVIDER` 切回 `opensandbox` 并补充 OpenSandbox 地址与密钥，无需改动业务代码。
