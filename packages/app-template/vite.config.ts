import { defineConfig, type PluginOption } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { pathToFileURL } from 'node:url'

// 织页预览构建（mode=preview）时，由平台注入源码位置标记与可视化编辑桥。
// 导出源码后在本地运行不受影响。
export default defineConfig(async ({ mode }) => {
  const plugins: PluginOption[] = [react(), tailwindcss()]
  const locPlugin = process.env.ZHIYE_LOC_PLUGIN
  if (mode === 'preview' && locPlugin) {
    // 平台传入的可能是 Windows 原生路径（D:\...），不是合法 ESM 标识符，统一转 file:// URL
    const locUrl = locPlugin.startsWith('file:') ? locPlugin : pathToFileURL(locPlugin).href
    const { default: zhiyeLoc } = await import(/* @vite-ignore */ locUrl)
    plugins.unshift(zhiyeLoc())
  }
  return { base: './', plugins, build: { chunkSizeWarningLimit: 2000 } }
})
