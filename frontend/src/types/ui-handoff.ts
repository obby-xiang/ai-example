import type { ToolPart } from './ai'

/**
 * AI 可视可操作专项：上传交接的活动态（设计稿 §3.9）。
 *
 * 与表格专项的 `SheetWritePlan` 是**两种形态**（必须写清差异，否则复审会当成不一致）：
 * 表格预览依赖"浏览器里算出来的 diff"，刷新必死（转 `stalePreviewToolCallId`）；
 * 上传交接只依赖"页面入口是否还在"，刷新后**可以继续**（`status` 由区域是否仍注册决定）。
 *
 * 只活在浏览器内存（不进镜像）：`toolCallId/runId` 是重建所需的全部身份信息。
 */
export interface ActiveUploadHandoff {
  toolCallId: string
  runId: string | null
  /**
   * 上传入口的 ref（区域声明的 `uploadEntry` 或模型显式给的 ref）。
   *
   * <p>`null` = 模型没指定入口，且重建那一刻区域尚未注册（刷新时路由页是懒加载的）。
   * 这种交接**不是死卡**：落点按区域实况惰性解析（`resolveUploadRegion(null)` 取区域声明的
   * `uploadEntry`），区域注册后判据自动复原（A09）。
   */
  ref: string | null
  /** 收窄后的扩展名（缺省 = 页面允许的 .xlsx/.zip） */
  accept: string[] | null
  /**
   * - waiting：主按钮可点（浏览器支持 picker 且区域仍注册）
   * - degraded：浏览器不支持 picker（回退为"点左侧高亮按钮/拖到高亮区"），主按钮禁用
   * - missing：刷新后区域已不在该页（主按钮禁用，只能放弃）
   */
  status: 'waiting' | 'degraded' | 'missing'
  /** 状态原因（展示给用户的一句话） */
  reason: string | null
  /** 已选文件名（只回 basename，≤64 字符） */
  filename: string | null
}

/** 面板操作条 / 摘要行用的原语工具名集合（写动词）。 */
export const UI_WRITE_TOOL_NAMES: ReadonlySet<string> = new Set<string>(['ui_click', 'ui_fill', 'ui_select'])

/** 原语工具名全体（面板据此决定是否渲染"撤销/操作摘要行"）。 */
export const UI_PRIMITIVE_TOOL_NAMES: ReadonlySet<string> = new Set<string>([
  'ui_snapshot',
  'ui_click',
  'ui_fill',
  'ui_select',
  'ui_scroll',
  'ui_highlight',
  'ui_request_upload'
])

/** 供面板断言用的窄类型（防止把 `ToolPart` 换成 `any`）。 */
export type UiToolPart = ToolPart
