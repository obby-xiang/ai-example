import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 端口约定：启动前检测占用（见 docs/06-部署运行手册.md）；被占用时修改此端口与代理保持一致
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: false,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true
      }
    }
  },
  build: {
    chunkSizeWarningLimit: 3000
  }
})
