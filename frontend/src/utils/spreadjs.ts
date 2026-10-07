/**
 * SpreadJS 懒加载入口（裁决⑥：首屏不再背 4.7MB）。
 *
 * 背景：`main.ts` 若静态 `import '@grapecity/spread-sheets'`，入口 chunk 就必然
 * 静态依赖 vendor 之外的 spreadjs chunk，首屏体积与首屏 TTI 都被拖累。
 * 这里改成**按需动态 import**：只有真正要用表格（SpreadGrid 挂载 / Excel 工具层）
 * 时才去拉 spreadjs chunk，路由与业务页都不 import 本模块的运行时值。
 *
 * 加载顺序（不可颠倒）：
 * 1. `@grapecity/spread-sheets` 核心：UMD 副作用是挂 `window.GC`；
 * 2. `@grapecity/spread-sheets-resources-zh`：注册 zh-cn 资源，必须在 culture 之前；
 * 3. `CultureManager.culture('zh-cn')` + LicenseKey（VITE_SPREADJS_KEY，评估模式仅水印）；
 * 4. `@grapecity/spread-excelio`：UMD 工厂在**模块求值瞬间**就读全局 `GC`，
 *    故必须先等核心加载完（否则 ReferenceError: GC is not defined）。
 */

/** spreadjs 核心命名空间（`import * as GC from '@grapecity/spread-sheets'` 的形态）。 */
export type SpreadSheets = typeof import('@grapecity/spread-sheets')

/** ExcelIO 的最小接口（其 d.ts 是 ambient 声明，取不到 IO 的类型，这里自declare 收口）。 */
export interface SpreadExcelIOInstance {
  save(
    json: string,
    success: (blob: Blob) => void,
    error: (error: unknown) => void,
    options?: Record<string, unknown>
  ): void
  open(
    file: Blob | File,
    success: (json: string) => void,
    error: (error: unknown) => void,
    options?: Record<string, unknown>
  ): void
}

/** excelio 模块形态：CommonJS `export =` ⇒ 运行时可能是命名空间本身或挂在其 `default` 上。 */
export interface SpreadExcelIOModule {
  IO: new () => SpreadExcelIOInstance
}

let spreadCorePromise: Promise<SpreadSheets> | null = null
let spreadExcelIoPromise: Promise<SpreadExcelIOModule> | null = null

/** 动态加载 SpreadJS 核心（含中文 Culture 与 LicenseKey），同一次运行内只加载一次。 */
export function loadSpreadCore(): Promise<SpreadSheets> {
  if (!spreadCorePromise) {
    spreadCorePromise = (async (): Promise<SpreadSheets> => {
      const ns = await import('@grapecity/spread-sheets')
      const GC = (ns as unknown as { default?: SpreadSheets }).default ?? (ns as SpreadSheets)
      // 资源包只有副作用（注册 zh-cn 资源），必须在 culture() 之前求值
      await import('@grapecity/spread-sheets-resources-zh')
      await import('@grapecity/spread-sheets/styles/gc.spread.sheets.excel2013white.css')
      GC.Spread.Common.CultureManager.culture('zh-cn')
      const key = import.meta.env.VITE_SPREADJS_KEY
      if (key) {
        GC.Spread.Sheets.LicenseKey = key
      }
      return GC
    })().catch((error: unknown) => {
      // 失败不缓存：下次调用可以重试（离线/网络抖动场景）
      spreadCorePromise = null
      throw error
    })
  }
  return spreadCorePromise
}

/** 动态加载 ExcelIO（自动先保证核心已加载）。 */
export function loadSpreadExcelIO(): Promise<SpreadExcelIOModule> {
  if (!spreadExcelIoPromise) {
    spreadExcelIoPromise = (async (): Promise<SpreadExcelIOModule> => {
      await loadSpreadCore()
      const ns = await import('@grapecity/spread-excelio')
      return (ns as unknown as { default?: SpreadExcelIOModule }).default ?? (ns as unknown as SpreadExcelIOModule)
    })().catch((error: unknown) => {
      spreadExcelIoPromise = null
      throw error
    })
  }
  return spreadExcelIoPromise
}

/** 是否已加载核心（供 UI 显示"表格引擎准备中/已就绪"）。 */
export function isSpreadCoreLoaded(): boolean {
  return spreadCorePromise !== null
}
