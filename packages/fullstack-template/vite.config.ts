import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  // 全栈应用与预览/线上均托管在站点根路径 /，使用绝对资产路径确保深层 SPA 路由刷新也能稳定加载 JS/CSS
  base: '/',
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://127.0.0.1:8080',
    },
  },
})
