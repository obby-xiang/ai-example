/**
 * AI 可视可操作专项 · 区域注册表（设计稿 §3.4.1 / §3.5 围栏①）。
 *
 * 这是整套"看得见才可操作"的地基：**只有被页面显式注册的区域内的、
 * 带 `data-ai-ref` 的元素，才可能被模型看见与操作**。区域外 = 不可见 = 不可操作，
 * 这是设计目标而不是缺陷（设计稿 §8.8）。
 *
 * <h2>为什么另开模块（不塞进 stores/workspace.ts）</h2>
 * `stores/workspace.ts` 的页面能力注册表（`registerPageHandler`）只表达"页面有什么能力"；
 * 本模块表达"页面上哪些**控件**可被操作"。两者生命周期相同、职责不同，且表格专项已在
 * 改 `workspace.ts` 一族文件 —— 独立成 `utils/` 模块可把冲突面压到零（勘察 §7.2）。
 *
 * <h2>epoch 的两种失效</h2>
 * <ul>
 * <li><b>页内失效</b>：注册/注销/换页 ⇒ 计数器 +1（同一次加载内单调）；</li>
 * <li><b>刷新失效</b>：nonce 在**模块加载期**随机生成 ⇒ 刷新后即使计数器归位，
 *   `epoch` 字符串也必然不同。只写单调计数器会让"刷新后 counter=1"与"历史上下文里的
 *   旧 epoch=1"撞号，放行一次**错位**点击（设计稿 §0 第 7 条，T-14 钉死）。</li>
 * </ul>
 */

/** 元素/区域的风险档（设计稿 §3.6）。 */
export type AiRisk = 'read' | 'mutate-local' | 'consequential'

/** 受控动词（每个原语对应一个）。 */
export type AiVerb = 'click' | 'fill' | 'select' | 'scroll' | 'highlight'

/** 风险档强弱序（仅用于提示，不用于"只能升不能降"——显式声明即授权）。 */
const RISK_ORDER: Readonly<Record<AiRisk, number>> = {
  read: 0,
  'mutate-local': 1,
  consequential: 2
}

/** 区域注册入参。 */
export interface AiRegionOptions {
  /** 区域 id：`<pageId>.<region>`（如 `import.upload`），同时是 ref 的第一段 */
  id: string
  /** 区域根元素；必须是当前文档内的已挂载节点（detached / 跨文档直接抛错） */
  root: HTMLElement
  /** 区域默认风险，**必填** —— 缺省不允许注册（最安全默认值，T-15） */
  risk: AiRisk
  /** 区域所属页面（缺省取 id 第一段）；上下文围栏用 */
  page?: string
  /** 区域标签（进快照头行） */
  label?: string
  /** 区域级动词集（只能收紧） */
  verbs?: readonly AiVerb[]
  /** 上下文围栏：只在这些步骤可用（空 = 不限） */
  allowSteps?: readonly string[]
  /** 上下文围栏：需要绑定任务 */
  needsTask?: boolean
  /** 只读判定（页面提供；上传交接据此给 unavailable） */
  readOnly?: () => boolean
  /** `ui_select` 的 setter 表（key = 元素 data-ai-ref） */
  setters?: Record<string, (option: string) => boolean | Promise<boolean>>
  /** 上传入口的 ref（`ui_request_upload` 的缺省落点） */
  uploadEntry?: string
  /** 把用户手势选中的文件交回页面既有上传逻辑；返回页面摘要文本（不含路径） */
  receiveFile?: (file: File) => Promise<string>
}

/** 已注册区域（内部形态，字段都已归一）。 */
export interface AiRegion extends AiRegionOptions {
  page: string
}

/** 解析结果（含元素；detached 元素也会被解析出来，由元素围栏拒绝）。 */
export interface AiRegionHit {
  region: AiRegion
  el: HTMLElement
  risk: AiRisk
}

const regions = new Map<string, AiRegion>()

/**
 * ref → 元素 的索引（惰性建）。
 *
 * 只在**快照过 / 解析过**之后才有内容。它的唯一用途是让"元素已被卸载但模型手里还有 ref"
 * 这条真实路径能被**明确拒绝**（T-10）而不是退化成"ref 不存在"这种看不出原因的文案。
 */
const refIndex = new Map<string, { el: HTMLElement; regionId: string }>()

/** 模块加载期随机段（6 位 base36；刷新即变，T-14 的唯一防线）。 */
const EPOCH_NONCE = makeNonce()

let epochCounter = 1

/** 当前页面（由 `noteAiPage` 维护；缺省 null = 未声明页面）。 */
let currentPage: string | null = null

function makeNonce(): string {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789'
  let out = ''
  const cryptoApi = globalThis.crypto as Crypto | undefined
  const bytes = new Uint8Array(6)
  if (cryptoApi && typeof cryptoApi.getRandomValues === 'function') {
    cryptoApi.getRandomValues(bytes)
    for (const byte of bytes) {
      out += alphabet[byte % alphabet.length]
    }
    return out
  }
  for (let i = 0; i < 6; i += 1) {
    out += alphabet[Math.floor(Math.random() * alphabet.length)]
  }
  return out
}

/** 当前 epoch：`<6 位 nonce>-<计数器>`（后端 Guard 同款正则 `^[a-z0-9]{6}-\d+$`）。 */
export function currentAiEpoch(): string {
  return `${EPOCH_NONCE}-${epochCounter}`
}

/** 主动推进 epoch（注册/注销/换页之外的显式失效点，如页面内部重建）。 */
export function bumpAiEpoch(): string {
  epochCounter += 1
  return currentAiEpoch()
}

/** 页面切换：换页才推进 epoch（同页重复进入不刷，避免无谓失效）。 */
export function noteAiPage(page: string | null): void {
  if (page === currentPage) {
    return
  }
  currentPage = page
  epochCounter += 1
}

/** 当前页面（区域解析/上下文围栏共用；null = 未声明）。 */
export function aiCurrentPage(): string | null {
  return currentPage
}

/** 注册区域（幂等：同 id 覆盖 + epoch+1）。缺 risk / 空 id / detached root 一律抛错。 */
export function registerAiRegion(options: AiRegionOptions): AiRegion {
  assertRegionOptions(options)
  const page = options.page ?? options.id.split('.')[0] ?? ''
  const region: AiRegion = { ...options, page }
  regions.set(region.id, region)
  epochCounter += 1
  // 建索引（只用于"元素已卸载"的明确拒绝；不代替实时查询）
  indexRegion(region)
  return region
}

function assertRegionOptions(options: AiRegionOptions): void {
  if (!options || typeof options !== 'object') {
    throw new Error('registerAiRegion：缺少注册参数')
  }
  if (typeof options.id !== 'string' || options.id.trim() === '') {
    throw new Error('registerAiRegion：区域 id 必填（形如 import.upload）')
  }
  if (options.risk !== 'read' && options.risk !== 'mutate-local' && options.risk !== 'consequential') {
    throw new Error(
      `registerAiRegion（${options.id}）：risk 必填且只能是 read / mutate-local / consequential`
        + '—— 缺省不允许注册（fail-fast，禁止静默用默认值）'
    )
  }
  const root = options.root as Node | undefined
  if (!root || typeof root.nodeType !== 'number') {
    throw new Error(`registerAiRegion（${options.id}）：root 必须是 DOM 元素`)
  }
  if (root.isConnected !== true) {
    throw new Error(`registerAiRegion（${options.id}）：root 必须已挂载在当前文档（detached 节点会让 ref 无法校验）`)
  }
  if (typeof document !== 'undefined' && root.ownerDocument !== document) {
    throw new Error(`registerAiRegion（${options.id}）：不接受跨文档/跨域 iframe 的 root`)
  }
}

/** 注销区域（卸载即注销 + epoch+1；同时清掉该区域的索引）。 */
export function unregisterAiRegion(id: string): void {
  if (!regions.delete(id)) {
    return
  }
  for (const [ref, entry] of refIndex) {
    if (entry.regionId === id) {
      refIndex.delete(ref)
    }
  }
  epochCounter += 1
}

/** 已注册区域表（快照遍历用）。 */
export function listAiRegions(): readonly AiRegion[] {
  return [...regions.values()]
}

/** 取单个区域（上传提交、恢复判定用）。 */
export function getAiRegion(id: string): AiRegion | null {
  return regions.get(id) ?? null
}

/** 元素风险：`data-ai-risk` 显式声明优先，缺省继承区域（显式即授权，允许低于区域）。 */
export function riskOfElement(el: HTMLElement, region: AiRegion): AiRisk {
  const declared = el.getAttribute('data-ai-risk')
  if (declared === 'read' || declared === 'mutate-local' || declared === 'consequential') {
    return declared
  }
  return region.risk
}

/** 元素声明的动词子集（`data-ai-verbs="click,fill"`；缺省 = 不限）。 */
export function verbsOfElement(el: HTMLElement): AiVerb[] | null {
  const raw = el.getAttribute('data-ai-verbs')
  if (!raw) {
    return null
  }
  const allowed = raw.split(',').map((item) => item.trim()).filter((item): item is AiVerb =>
    item === 'click' || item === 'fill' || item === 'select' || item === 'scroll' || item === 'highlight')
  return allowed
}

/** 动词是否被区域/元素双层允许（只能收紧，不能放宽）。 */
export function verbAllowed(el: HTMLElement, region: AiRegion, verb: AiVerb): boolean {
  if (region.verbs && !region.verbs.includes(verb)) {
    return false
  }
  const local = verbsOfElement(el)
  if (local && !local.includes(verb)) {
    return false
  }
  return true
}

/**
 * ref → 元素 解析（围栏①）。
 *
 * **不做选择器求值**：ref 只用于与 `[data-ai-ref]` 属性**逐字比对**，因此
 * `"import.x"] , [data-y` 一类注入形态天然无效（T-54）。
 */
export function resolveAiRef(ref: string): AiRegionHit | null {
  if (typeof ref !== 'string' || ref.trim() === '') {
    return null
  }
  for (const region of regions.values()) {
    const found = findByRef(region, ref)
    if (found) {
      return { region, el: found, risk: riskOfElement(found, region) }
    }
  }
  // 实时查不到：看索引里的历史节点（已被卸载的元素 → 由元素围栏给明确拒绝原因）
  const indexed = refIndex.get(ref)
  if (indexed) {
    const region = regions.get(indexed.regionId)
    if (region) {
      return { region, el: indexed.el, risk: riskOfElement(indexed.el, region) }
    }
  }
  return null
}

function findByRef(region: AiRegion, ref: string): HTMLElement | null {
  const nodes = region.root.querySelectorAll('[data-ai-ref]')
  for (const node of Array.from(nodes)) {
    if (node.getAttribute('data-ai-ref') === ref) {
      return node as HTMLElement
    }
  }
  return null
}

function indexRegion(region: AiRegion): void {
  for (const node of Array.from(region.root.querySelectorAll('[data-ai-ref]'))) {
    const ref = node.getAttribute('data-ai-ref')
    if (ref) {
      refIndex.set(ref, { el: node as HTMLElement, regionId: region.id })
    }
  }
}

/** 记入索引（快照遍历时就地补，覆盖动态渲染出的新元素）。 */
export function rememberAiRef(ref: string, el: HTMLElement, regionId: string): void {
  refIndex.set(ref, { el, regionId })
}

/** 风险强弱（仅用于 DEV 提示"元素级低于区域级"）。 */
export function riskRank(risk: AiRisk): number {
  return RISK_ORDER[risk]
}

/** 测试/页面卸载时清空（不进生产路径的语义：区域必须逐个注销）。 */
export function resetAiRegions(): void {
  regions.clear()
  refIndex.clear()
  currentPage = null
  epochCounter += 1
}
