import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: {
    host: '0.0.0.0',
    port: 15271,
    strictPort: true,
    open: false,
    proxy: {
      '/api': {
        target: 'http://localhost:18290',
        changeOrigin: true,
        ws: false,
        timeout: 300000
      }
    }
  },
  build: {
    sourcemap: false,
    chunkSizeWarningLimit: 4096,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['vue', 'vue-router', 'pinia', 'axios', 'element-plus'],
          spreadjs: ['@grapecity/spread-sheets', '@grapecity/spread-excelio']
        }
      }
    }
  }
})
