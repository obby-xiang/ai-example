import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * /api 代理目标一律走环境变量 VITE_API_BASE（默认 http://localhost:8080）。
 * 禁止把端口硬编码进源码：基座前端硬编码 8081 已被 G5 实测判为缺陷。
 */
const DEFAULT_API_BASE = 'http://localhost:8080'
const DEFAULT_DEV_PORT = 5200

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const apiBase = env.VITE_API_BASE || DEFAULT_API_BASE
  const devPort = Number(env.VITE_DEV_PORT || DEFAULT_DEV_PORT)

  return {
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
    },
    server: {
      host: '0.0.0.0',
      port: devPort,
      strictPort: true,
      open: false,
      proxy: {
        '/api': {
          target: apiBase,
          changeOrigin: true,
          ws: false,
          timeout: 300000,
          proxyTimeout: 300000,
          // SSE 经代理不得被压缩缓冲：显式要求上游用 identity 编码
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyReq) => {
              proxyReq.setHeader('Accept-Encoding', 'identity')
            })
          }
        }
      }
    },
    build: {
      sourcemap: false,
      // SpreadJS 单包即 ~4.7MB（评估模式），blueprint 的 4096 阈值不够，这里抬高避免噪音警告
      chunkSizeWarningLimit: 8192,
      rollupOptions: {
        output: {
          manualChunks: {
            vendor: ['vue', 'vue-router', 'pinia', 'axios', 'dayjs'],
            element: ['element-plus', '@element-plus/icons-vue'],
            spreadjs: ['@grapecity/spread-sheets', '@grapecity/spread-excelio']
          }
        }
      }
    }
  }
})
