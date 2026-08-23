import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5174,
    open: false,
    proxy: {
      // 后端只代理出对话接口:前端 AI runtime 通过 Vercel SDK 调用 /api/ai/proxy/chat/completions
      // Vite dev proxy 透传到 ai-service(8081)的 AiProxyController
      '/api/ai/proxy': {
        target: 'http://localhost:8081',
        changeOrigin: true,
        ws: false,
        timeout: 120000
      }
    }
  },
  build: {
    sourcemap: false,
    chunkSizeWarningLimit: 2048
  }
})
