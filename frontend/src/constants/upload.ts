/**
 * 导入上传的单一口径（F9）：扩展名 / 大小上限 / 预检 / 文案键。
 *
 * <p>为什么单独成模块：`.xlsx` / `.zip` 这一对扩展名此前在四处各写一遍（`el-upload` 的 `accept`、
 * 上传交接文案、模板下载的格式名、上传链的说明文字），任何一处要动都得全找一遍 —— 漏一处就出现
 * "选择器放行、上传链拒绝"的分叉。这里给出**唯一来源**，选择器、上传链、交接提示都读它。
 *
 * <p><b>后端仍是权威</b>：真正的上限在 `application.yml` 的
 * `spring.servlet.multipart.max-file-size: 5MB`（= {@link UPLOAD_MAX_BYTES} 字节）。
 * 前端预检只做"明显非法"的提前拦截（类型 / 大小），产物与文案都不改变后端的裁决：
 * 预检放行的文件后端照样可能拒（内容损坏、匹配不到配置项等），反向也绝不替后端"补收"。
 */

/** 允许的上传扩展名（小写，含点）。 */
export const UPLOAD_EXTENSIONS = ['.xlsx', '.zip'] as const

/**
 * 文件选择器 / 交接卡共用的 `accept` 串（`.xlsx,.zip`）。
 *
 * 浏览器 `accept` 只是**软过滤**（用户仍可在部分系统里选到别的文件），硬拦在
 * {@link checkUploadFile} —— 两处读同一份常量，口径不会分叉。
 */
export const UPLOAD_ACCEPT = UPLOAD_EXTENSIONS.join(',')

/**
 * 单文件大小上限（字节）= 后端 `spring.servlet.multipart.max-file-size: 5MB`。
 *
 * 5MB 按 5 × 1024 × 1024 解释（Spring 的 `DataSize` 对 `MB` 即 2^20 字节）。
 */
export const UPLOAD_MAX_BYTES = 5 * 1024 * 1024

/**
 * 预检提示的 locale key（文案实体由 locales 维护，两个语言文件同键）。
 *
 * - `uploadUnsupportedType`：`「{name}」不是支持的格式：仅支持 .xlsx / .zip，本次未上传`
 * - `uploadTooLarge`：`「{name}」超过 5MB 上限，本次未上传`
 *
 * 这里只存键名：本模块不 import i18n（纯常量 + 纯函数，测试与工具代码都能直接用）。
 */
export const UPLOAD_MSG_KEYS = {
  unsupportedType: 'views.import.msg.uploadUnsupportedType',
  tooLarge: 'views.import.msg.uploadTooLarge'
} as const

/** 预检拒绝的形态（`key` 是 locale 键，`name` 是插值参数）。 */
export interface UploadRejection {
  kind: 'type' | 'size'
  key: string
  name: string
}

/** 取文件名的小写扩展名（含点）；没有点或点在末尾 ⇒ 空串。 */
export function uploadExtensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 || dot === name.length - 1 ? '' : name.slice(dot).toLowerCase()
}

/** 扩展名是否在允许表内。 */
export function isUploadExtensionAllowed(name: string): boolean {
  return (UPLOAD_EXTENSIONS as readonly string[]).includes(uploadExtensionOf(name))
}

/**
 * 上传前置预检：返回拒绝原因，`null` = 通过（交给后端裁决）。
 *
 * 只收 `{ name, size }`（`File` 的结构子集）：测试与页面外的调用面都不必造真 `File`。
 */
export function checkUploadFile(file: { name: string; size: number }): UploadRejection | null {
  if (!isUploadExtensionAllowed(file.name)) {
    return { kind: 'type', key: UPLOAD_MSG_KEYS.unsupportedType, name: file.name }
  }
  if (file.size > UPLOAD_MAX_BYTES) {
    return { kind: 'size', key: UPLOAD_MSG_KEYS.tooLarge, name: file.name }
  }
  return null
}
