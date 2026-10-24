import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * /api 代理目标一律走环境变量 VITE_API_BASE（默认 http://localhost:8081，与后端 server.port 对齐）。
 * 口径：默认值须与后端实际端口对齐、**必须可被 VITE_API_BASE 覆盖**、禁止在组件/业务源码内另行硬编码端口。
 * （G5 实测判为缺陷的是基座前端"硬编码且不可覆盖"的 8081，不是默认值本身对齐后端。）
 */
const DEFAULT_API_BASE = 'http://localhost:8081'
const DEFAULT_DEV_PORT = 5200

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const apiBase = env.VITE_API_BASE || DEFAULT_API_BASE
  const devPort = Number(env.VITE_DEV_PORT || DEFAULT_DEV_PORT)
  /**
   * E2E 隔离（L-115）：真机 E2E 的 dev server 跑在**活动工作树**上，若运行期有人改源码，
   * Vite 的 HMR 会用 `?t=` 时间戳重新取模块 —— 于是同一页面里可能同时活着两份
   * `stores/workspace.ts`（页面能力注册表 `pageHandlers` 是模块级 Map）：
   * 视图把 `sheet.current` 注册在旧实例上，工具/用例读到新实例 ⇒ 「当前页面没有可操作的表格」；
   * SFC 热替换还会让 `SpreadGrid` 在页面中途重挂（SpreadJS 退回「需要 HTML5 Canvas」的兜底 DOM）。
   * 这正是 11b 的 E2E-T13 / T05 在全量串跑里红、单跑绿的形态。
   * 因此 harness 起 dev server 时设 `VITE_HMR=0`：一次页面生命期内模块图不可变
   * （每个用例都是新 page，新代码照常生效）。默认（5202 开发）保持 HMR。
   */
  const hmr = env.VITE_HMR === '0' ? false : undefined

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
      hmr,
      proxy: {
        '/api': {
          target: apiBase,
          changeOrigin: false,
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
            vendor: ['vue', 'vue-router', 'pinia', 'axios', 'dayjs', 'vue-i18n'],
            element: ['element-plus', '@element-plus/icons-vue'],
            spreadjs: ['@grapecity/spread-sheets', '@grapecity/spread-excelio']
          }
        }
      }
    }
  }
})
