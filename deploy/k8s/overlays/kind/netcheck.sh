#!/usr/bin/env bash
# 【legacy 回滚专用】验证 provider=k8s 的沙箱网络策略：在 zhiye-sandbox 里起两个和沙箱 Pod 同标签、
# 同安全设置的探针 Pod（allow-net=0 / 1），
# 分别测试：模型网关（应通）、公网（allow-net=0 应不通）、npm 镜像（allow-net=1 应通）、PostgreSQL、K8s API、云元数据（都应不通）。
set -uo pipefail
K="kubectl --context ${KUBE_CONTEXT:-kind-zhiye-kind}"
NS=zhiye-sandbox
IMAGE="${RUNNER_IMAGE:-zhiye-runner:dev}"
probe() { # name allowNet
cat <<YAML | $K apply -f - >/dev/null
apiVersion: v1
kind: Pod
metadata:
  name: $1
  namespace: $NS
  labels: { app: zhiye-sandbox, zhiye/task-id: netcheck, zhiye/allow-net: "$2" }
  annotations: { zhiye/task-id: netcheck }
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  activeDeadlineSeconds: 600
  nodeSelector: { zhiye/pool: sandbox }
  tolerations: [{ key: zhiye/sandbox, operator: Equal, value: "true", effect: NoSchedule }]
  securityContext: { runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: { type: RuntimeDefault } }
  containers:
    - name: runner
      image: $IMAGE
      imagePullPolicy: Never
      command: ["sleep", "600"]
      securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: ["ALL"] } }
YAML
}
probe netcheck-deny 0
probe netcheck-allow 1
$K -n $NS wait --for=condition=Ready pod/netcheck-deny pod/netcheck-allow --timeout=120s >/dev/null
# CNI 在 Pod Running 后需要一点时间下发策略（kind 实测约 1.3 秒）；真实沙箱由 KubernetesSandboxProvider 的隔离检查等待
sleep 5

# node 内置 fetch，5 秒超时；输出 HTTP 状态码或错误
JS='const u=process.argv[1];fetch(u,{signal:AbortSignal.timeout(5000)}).then(r=>console.log("HTTP "+r.status)).catch(e=>console.log("BLOCKED ("+(e.cause?.code||e.name)+")"))'
TCP='const [h,p]=process.argv[1].split(":");const s=require("net").connect({host:h,port:+p,timeout:4000});s.on("connect",()=>{console.log("OPEN");s.destroy()});s.on("timeout",()=>{console.log("BLOCKED (timeout)");s.destroy()});s.on("error",e=>console.log("BLOCKED ("+e.code+")"))'
check() { # pod label target kind
  local out
  if [ "$4" = http ]; then out=$($K -n $NS exec "$1" -- node -e "$JS" "$3" 2>&1); else out=$($K -n $NS exec "$1" -- node -e "$TCP" "$3" 2>&1); fi
  printf '%-16s %-34s %-55s %s\n' "$1" "$2" "$3" "$out"
}
for p in netcheck-deny netcheck-allow; do
  check $p "模型网关 zhiye-api:8080"        http://zhiye-api.zhiye.svc:8080/llm/v1/models http
  check $p "公网 npm 镜像"                  https://registry.npmmirror.com/react http
  check $p "公网 example.com"               https://example.com/ http
  check $p "zhiye-site:8083"                http://zhiye-site.zhiye.svc:8083/ http
  check $p "PostgreSQL"                     zhiye-postgres.zhiye.svc:5432 tcp
  check $p "K8s API Server"                 kubernetes.default.svc:443 tcp
  check $p "云元数据 169.254.169.254"       169.254.169.254:80 tcp
done
$K -n $NS delete pod netcheck-deny netcheck-allow --grace-period=0 --wait=false >/dev/null 2>&1
