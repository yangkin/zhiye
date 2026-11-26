// 为 Pi 的 bash 工具强制注入默认超时上限与进程树清理，防止无超时全盘扫描或卡死命令无限挂起。
import { createBashToolDefinition } from '@earendil-works/pi-coding-agent'

export const DEFAULT_BASH_TIMEOUT_SEC = 120
export const MAX_BASH_TIMEOUT_SEC = 300

export function resolveBashTimeoutSec(requested, {
  defaultTimeoutSec = DEFAULT_BASH_TIMEOUT_SEC,
  maxTimeoutSec = MAX_BASH_TIMEOUT_SEC,
} = {}) {
  const defSec = Number.isFinite(Number(defaultTimeoutSec)) && Number(defaultTimeoutSec) > 0
    ? Number(defaultTimeoutSec)
    : DEFAULT_BASH_TIMEOUT_SEC
  const maxSec = Math.max(defSec, Number.isFinite(Number(maxTimeoutSec)) && Number(maxTimeoutSec) > 0
    ? Number(maxTimeoutSec)
    : MAX_BASH_TIMEOUT_SEC)
  if (requested === undefined || requested === null) return defSec
  const n = Number(requested)
  if (!Number.isFinite(n) || n <= 0) return defSec
  return Math.min(n, maxSec)
}

/**
 * 包装 Pi 内置 bash 工具定义：
 * - 模型未显式传 timeout 时强制套用默认超时（默认 120s，可由 ZHIYE_BASH_DEFAULT_TIMEOUT_SEC 覆盖）
 * - 超出上限时钳制到最大超时（默认 300s，可由 ZHIYE_BASH_MAX_TIMEOUT_SEC 覆盖）
 * - 底层 createLocalBashOperations 以 detached 进程组运行，并在超时/中止时调用 killProcessTree(-pid) 杀死整个子进程树
 */
export function createBoundedBashTool(cwd, { env = process.env, defaultTimeoutSec, maxTimeoutSec } = {}) {
  const defSec = defaultTimeoutSec ?? Number(env.ZHIYE_BASH_DEFAULT_TIMEOUT_SEC || DEFAULT_BASH_TIMEOUT_SEC)
  const maxSec = maxTimeoutSec ?? Number(env.ZHIYE_BASH_MAX_TIMEOUT_SEC || MAX_BASH_TIMEOUT_SEC)
  const base = createBashToolDefinition(cwd)
  return {
    ...base,
    description: `${base.description} Default timeout is ${defSec}s (max ${maxSec}s); timed-out commands have their entire process group terminated.`,
    async execute(toolCallId, params, signal, onUpdate, ctx) {
      const timeout = resolveBashTimeoutSec(params?.timeout, { defaultTimeoutSec: defSec, maxTimeoutSec: maxSec })
      return base.execute(toolCallId, { ...params, timeout }, signal, onUpdate, ctx)
    },
  }
}
