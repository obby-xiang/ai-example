/**
 * `select_definitions` 的**编码校验口径**（单点，由页面 handler 调用）。
 *
 * 两个页面入口共用同一套判据与文案：
 * - 导出向导页面能力 `export.selectDefs`；
 * - 导入向导页面能力 `import.selectDefs`。
 *
 * 写类兜底已移除：`dispatchUiEvent` 只认页面能力 handler，页面未挂载即返回「页面未就绪」的
 * 确定性失败，契约层不再替页面落库；校验由页面 handler 负责。
 *
 * 判据 = **当前可用编码清单**（页面取自己已加载的定义列表）。
 * 清单为空表示"**不知道**有哪些编码"（加载中 / 加载失败 / 服务不可用）—— 此时不能放行，
 * 也不能说成"没有可用编码"（那会把一次未校验写成事实）。
 */

/** 校验上下文（只影响加载中/不可用的措辞，不改变判据）。 */
export interface DefinitionCodesContext {
  /** 定义列表仍在加载（true ⇒ 文案说"仍在加载"；否则说"不可用"） */
  loading?: boolean
}

/**
 * 编码校验：拿到的可用清单为空（不知道有哪些编码）时也**必**返回原因，
 * 因此空清单调用方无需再写死兜底文案（返回类型收窄为 `string`）。
 *
 * @param codes 本次要选择的编码（调用方已归一：字符串、去空；重复项不影响判据）
 * @param available 当前**可信**的可用编码清单（空数组 = 拿不到清单）
 * @returns 失败原因（必非空）
 */
export function definitionCodesProblem(
  codes: readonly string[],
  available: readonly never[],
  context?: DefinitionCodesContext
): string
/**
 * @param codes 本次要选择的编码（调用方已归一：字符串、去空；重复项不影响判据）
 * @param available 当前**可信**的可用编码清单
 * @returns `null` = 全部可用；否则为可直接回灌的失败原因
 */
export function definitionCodesProblem(
  codes: readonly string[],
  available: readonly string[],
  context?: DefinitionCodesContext
): string | null
export function definitionCodesProblem(
  codes: readonly string[],
  available: readonly string[],
  context: DefinitionCodesContext = {}
): string | null {
  if (codes.length === 0) {
    return '未指定任何配置项（codes 为空）：本次未改动所选配置项，也未落库。'
  }
  if (available.length === 0) {
    return `配置定义列表${context.loading === true ? '仍在加载' : '不可用'}，无法校验 ${codes.join('、')} 是否为可用编码`
      + '（当前拿不到可用编码清单）。本次未改动所选配置项，也未落库。'
  }
  const known = new Set(available)
  const invalid = codes.filter((code) => !known.has(code))
  if (invalid.length === 0) {
    return null
  }
  return `无效配置编码 ${invalid.join('、')}：当前定义列表里没有这些编码。`
    + `可用编码：${available.join('、')}。本次未改动所选配置项，也未落库。`
}

/** 编码归一：字符串化、去空、去重（保持首次出现的顺序）。 */
export function normalizeDefinitionCodes(codes: readonly unknown[]): string[] {
  const normalized: string[] = []
  const seen = new Set<string>()
  for (const raw of codes) {
    const code = String(raw)
    if (code === '' || seen.has(code)) {
      continue
    }
    seen.add(code)
    normalized.push(code)
  }
  return normalized
}
