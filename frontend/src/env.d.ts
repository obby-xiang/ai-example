/// <reference types="vite/client" />

// SpreadJS 中文资源包只有 JS，无类型声明（仅在 import 时产生副作用：注册 zh-cn 资源）
declare module '@grapecity/spread-sheets-resources-zh'

interface ImportMetaEnv {
  /** /api 代理目标（默认 http://localhost:8080） */
  readonly VITE_API_BASE?: string
  /** dev server 端口（默认 5200） */
  readonly VITE_DEV_PORT?: string
  /** SpreadJS 授权 Key（留空为评估模式） */
  readonly VITE_SPREADJS_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
