/**
 * R-163 T1：AI 正文唯一渲染链路 —— markdown-it → DOMPurify 白名单净化 → v-html
 * （决策卡 §三.1/§三.3，XSS 口径变更的安全面仅限本链路；FormRenderer 永不 v-html 不变）。
 *
 * - 合规四件套（锁定）：markdown-it@15.0.0 / dompurify@3.4.14 / highlight.js@11.11.1 / mermaid@11.16.1
 *   （mermaid@11.16.1 deps dompurify ^3.3.3，接受 3.4.14 —— 施工前兼容已核）。
 * - 白名单（§三.3 实际集合）：标签 p/h1-6/ul/ol/li/table/thead/tbody/tr/th/td/code/pre/
 *   blockquote/em/strong/del/a/hr/br/img；span 仅 class="hljs-*"（tip 气泡/箭头按标记类整条放行）、
 *   code 仅 class="language-*"（+hljs）；
 *   figure/figcaption/button（代码块图容器，button 仅 type="button"）、div 仅表格双区 wrapper
 *   （R-165 S5a：md-table-wrap / md-table-hd / md-table-bd 三区，见 RENDER_MARKER_CLASSES）；
 *   svg/path/rect/polyline 仅复制图标钮的自绘图标（R-165 S5a，属性收窄为几何集合）；
 *   mermaid 输出 SVG 走独立 SVG profile 净化。属性仅 a.href
 *   （DOMPurify 默认协议过滤）/class/src/alt 与图标几何集合；禁 style / on* 事件 / iframe / script / 表单控件。
 * - 流式策略（§三.2 + m1）：hljs 高亮 memo（已完成围栏不重解析高亮 = 块级 memo 的落点）；
 *   未闭合围栏（流式尾块）渲染为纯文本规避闪烁，闭合后二次渲染才高亮/进 mermaid；
 *   长文 >10k 字符降级（关高亮与 mermaid，结构照渲）；单次渲染超耗时预算（24ms）后续渲染同降级。
 * - **流式代码块防抖口径（R-165 S5c 登记，本批不改渲染逻辑、实现另立小批）**：
 *   ① **未闭合 fence 立即渲染**（纯文本、不高亮、不折叠）—— 防抖目标是「高亮闪烁」而非「结构出现」，
 *      让未闭合块延后出现只会让流式观感变空；
 *   ② 闭合后的**高亮/进 mermaid 二次渲染加 75ms 防抖**（闭合瞬间起算，75ms 内若又收到增量则丢弃该次
 *      二次渲染，静默满 75ms 才一次性落地高亮/mermaid）—— 上游常在闭合后立刻继续吐下一块，
 *      防抖把「闭合→高亮→又变文本→再高亮」的抖动收敛成一次；
 *   ③ **稳定 key**：围栏按首次出现序分配序号（现有 `mermaid` 列表的 `index` 同源），闭合前后序号不变，
 *      避免 Vue 列表复用把「代码块 → mermaid 卡」的替换打到错误的兄弟节点上。
 * - mermaid（§三.7）：securityLevel:'strict' + htmlLabels:false + suppressErrorRendering:true，
 *   渲染产物 SVG 过 DOMPurify SVG profile；失败（render error/超时 3s）保留源码块。
 */
import MarkdownIt from 'markdown-it'
import type { MarkdownIt as MarkdownItInstance } from 'markdown-it'
import DOMPurify from 'dompurify'
import * as hljsNs from 'highlight.js'
import type HljsType from 'highlight.js'
// hljs 库默认主题（决策卡 §二.2：高亮用库默认主题色，不计入 UI 用色收敛）
import 'highlight.js/styles/github.css'

// vitest/vite 下 default 互operability 兜底：命名空间与 default 两个形态取有 getLanguage 的那个
const hljs: typeof HljsType = ((hljsNs as unknown as { default?: typeof HljsType }).default ?? hljsNs) as typeof HljsType

/** 长文降级阈值（字符数，决策卡 §三.2「>10k」）。 */
export const DEGRADE_AT_CHARS = 10_000
/** 单次渲染耗时预算（ms）——超出即后续渲染降级（m1 耗时预算）。 */
export const RENDER_BUDGET_MS = 24
/** mermaid 单次渲染超时（ms），超时按失败兜底（源码块保留）。 */
const MERMAID_TIMEOUT_MS = 3_000

/** DOMPurify 白名单（§三.3）。span 仅限 class="hljs-*"（下方 hook 收窄）；
 *  R-163B G4：figure/figcaption/button 为代码块图容器（button 仅 type="button"，hook 强制）；
 *  R-163D D1 → R-165 S5a：div 为表格双区 wrapper（三区标记类，渲染层生成，hook 收窄）；
 *  R-165 S5a：svg/path/rect/polyline 仅承载复制图标钮的自绘图标（几何属性白名单）。 */
const ALLOWED_TAGS = [
  'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'code', 'pre', 'blockquote', 'em', 'strong', 'del', 'a', 'hr', 'br', 'img', 'span',
  'figure', 'figcaption', 'button', 'div',
  'svg', 'path', 'rect', 'polyline'
]
const ALLOWED_ATTR = [
  'class', 'href', 'src', 'alt', 'type',
  // svg 图标几何属性（复制图标：rect 的 x/y/width/height/rx、path 的 d、polyline 的 points、svg 的 viewBox）
  'viewBox', 'd', 'points', 'x', 'y', 'width', 'height', 'rx'
]

/** 表格双区三区标记类（R-165 S5a，渲染层 wrapTables 生成）——hook 只认这三个标记类，
 *  div 上随行的 Tailwind 工具类同 div 保留（表格版式全在工具类里，不能被净化剥掉）。 */
const TABLE_DIV_CLASSES = new Set(['md-table-wrap', 'md-table-hd', 'md-table-bd'])
/** R-165 S5c：`<code>`/`<pre>` 的排版工具类标记（渲染层注入，见 MD_CODE_INLINE_CLASS /
 *  MD_FENCE_PRE_CLASS）——hook 命中即整条保留（否则会被下面「只留 language- 与 hljs 前缀」的收窄剥掉）。
 *  注：class 属性在 span/code/pre/div 之外的标签上本就整条放行（仅这几类做收窄），
 *  故本扩展只把这三类拉到与 `p`/`h1` 等同的风险面，不引入新的放行类别。 */
const CODE_PRE_MARKERS = new Set(['md-code', 'md-pre'])
/** R-165 S5a：渲染层自产元素标记类合集（div 三区 + span 的 tip 气泡/箭头）；
 *  R-165 S5c：+ 代码块语言/库名两枚 span 标记 + code/pre 排版标记 ——
 *  命中标记类的元素其 class 属性整条保留（随行 Tailwind 工具类是版式唯一载体）。 */
const RENDER_MARKER_CLASSES = new Set([
  ...TABLE_DIV_CLASSES,
  'tip',
  'tip-arrow',
  ...CODE_PRE_MARKERS,
  'lg',
  'hlib'
])

/** 尾块未闭合围栏判据：``` 围栏标记数为奇 ⇒ 流式尾块在围栏内。 */
export function hasUnclosedFence(text: string): boolean {
  const matches = text.match(/^```/gm)
  return matches !== null && matches.length % 2 === 1
}

/** hljs 高亮 memo：已完成围栏的高亮结果缓存（块级 memo——已完成块不重解析）。 */
const highlightCache = new Map<string, string>()
const HIGHLIGHT_CACHE_MAX = 200

function highlightCode(lang: string, code: string): string {
  const key = `${lang}${code}`
  const hit = highlightCache.get(key)
  if (hit !== undefined) {
    return hit
  }
  let out: string
  try {
    if (lang && hljs.getLanguage(lang)) {
      out = hljs.highlight(code, { language: lang }).value
    } else {
      out = escapeHtml(code)
    }
  } catch {
    out = escapeHtml(code)
  }
  if (highlightCache.size >= HIGHLIGHT_CACHE_MAX) {
    const first = highlightCache.keys().next().value
    if (first !== undefined) {
      highlightCache.delete(first)
    }
  }
  highlightCache.set(key, out)
  return out
}

/** 降级开关：长文阈值或耗时预算触发后，后续渲染关高亮/mermaid（结构照渲）。 */
let degraded = false

export function isDegraded(): boolean {
  return degraded
}

/* ---------- R-165 S5c：正文排版工具类（原 src/styles/ai-chat.css 的 .md-body / 表格 / figure.code 族
   逐值迁移；类串写在本 .ts 源码里 ⇒ 被 tailwind.config.js 的 content 扫到并编译进产物） ---------- */

/**
 * 等宽字体栈：原 ai-chat.css `--mono` 令牌**逐字**（本机装有 Cascadia Mono ⇒ 不能退化成 `font-mono`
 * 的 Tailwind 默认栈，否则代码块字形会变）。Tailwind 无该 fontFamily 令牌，且本仓禁改 config 的
 * `theme.extend.fontFamily` ⇒ 走任意值类。
 */
const MONO_STACK = "font-['Cascadia_Mono','Cascadia_Code',Consolas,'SF_Mono',Menlo,monospace]"

/**
 * 正文根容器类（挂 AiPanel 的 `.md-body`）：= 原 `.ai-chat .md-body` 的 13.8px / 1.7 / --ink
 * ＋ 原 `.md-body > :first-child { margin-top: 0 }`（首个块顶边距归零 —— 只有标题族自带 `mt-[18px]`，
 * 这条不迁移会让「正文第一行是标题」多出 18px，实机 before/after computed 逐值比对时抓到）。
 */
export const MD_BODY_ROOT_CLASS = 'text-[13.8px] leading-[1.7] text-[#1f2328] [&>*:first-child]:mt-0'

/** 标题阶梯（原 `.ai-chat .md-body h1…h6`：margin 18/9 + line-height 1.5 + weight 650）。 */
const MD_HEAD_COMMON = 'mt-[18px] mb-[9px] leading-[1.5] [font-weight:650]'
const MD_HEAD_SIZE: Record<string, string> = {
  h1: 'text-[16.5px]',
  h2: 'text-[15.5px]',
  h3: 'text-[14.2px]',
  h4: 'text-[14.2px]',
  h5: 'text-[14.2px]',
  h6: 'text-[13.8px]'
}
const MD_P_CLASS = 'mt-0 mb-[12px]'
const MD_STRONG_CLASS = '[font-weight:650]'
const MD_LIST_CLASS = 'mt-0 mb-[12px] pl-[19px]'
/** `li::marker` 的 --ink3 走 Tailwind 内置 `marker:` 变体（原 `.md-body li::marker`）。 */
const MD_LI_CLASS = 'my-[3px] marker:text-[#8b949e]'
/** `m-0` 先归零再 `mb-[12px]`（Tailwind 产出顺序 mb 恒在 m 之后）—— 原规则是 `margin: 0 0 12px`，
 *  漏掉横向归零会让 UA 的 `blockquote { margin: 1em 40px }` 露出 40px 左右内缩（实测抓到）。 */
const MD_BLOCKQUOTE_CLASS =
  'm-0 mb-[12px] py-[2px] pl-[11px] border-0 border-l-2 border-solid border-[#cddcff] text-[#8b949e] text-[12.6px] leading-[1.65]'
/** 行内 code chip（原 `code:not(pre code)`）——`md-code` 为 hook 放行标记（见 RENDER_MARKER_CLASSES）。
 *  S6 批1（长内容审计清零项「行内 code nowrap 吞内容」）：撤 `whitespace-nowrap`，补 `break-words`
 *  + `[overflow-wrap:anywhere]` —— 长串（token / 长路径）在自身容器内折行，不再撑破气泡或右侧被裁。 */
const MD_CODE_INLINE_CLASS =
  `md-code ${MONO_STACK} text-[12.4px] bg-[#f6f7f9] border border-solid border-[#eef0f3] rounded-[4px] px-[4px] py-[1px] whitespace-normal break-words [overflow-wrap:anywhere]`
/** 围栏 pre（原 `.ai-chat .md-body pre` + `pre code` 两条）——`md-pre` 为 hook 放行标记。
 *  S6 批1（长内容审计清零项「degraded 期代码块限高失效」）：限高类**落在这里**（而不是只挂在
 *  AiPanel 的 `[&_figure.code>pre]` 变体上）—— 降级分支不包 `figure.code`（`wrapFenceFigures` 只在
 *  非降级路径跑），此前降级期代码块高度不设上限。同值 200px（与 S5a 代码块口径一致，不新增数值）。 */
const MD_FENCE_PRE_CLASS =
  `md-pre m-0 py-[10px] px-[11px] max-h-[200px] overflow-auto bg-[#f8f8f8] text-[#333] ${MONO_STACK} text-[12.3px] leading-[1.62] ` +
  '[&_code]:bg-transparent [&_code]:p-0 [&_code]:border-0 [&_code]:whitespace-pre'
const MD_A_CLASS = 'text-[#2563eb]'
const MD_HR_CLASS = 'mx-0 my-[0.9em] border-0 border-t border-solid border-[#eef0f3]'
const MD_IMG_CLASS = 'max-w-full'
const MD_TH_CLASS =
  'border border-solid border-[#e3e6ea] px-[8px] py-[6px] text-left whitespace-nowrap bg-[#f6f7f9] font-semibold text-[#57606a]'
const MD_TD_CLASS =
  'border border-solid border-[#e3e6ea] px-[8px] py-[6px] text-left [word-break:normal] [overflow-wrap:anywhere]'

/** 代码块图容器三件（原 `.ai-chat .md-body figure.code` 族）。 */
const FIGURE_CODE_CLASS = 'code m-0 mb-3.5 border border-solid border-[#e3e6ea] rounded-[10px] overflow-hidden bg-white'
const FIGCAPTION_CLASS =
  'flex items-center py-[5px] pr-[8px] pl-[11px] bg-[#f6f7f9] border-0 border-b border-solid border-[#eef0f3] text-[11.5px] text-[#57606a]'
const FIG_LANG_CLASS = `lg ${MONO_STACK} tracking-[.4px]`
const FIG_HLIB_CLASS = `hlib ml-auto text-[10.5px] text-[#8b949e] ${MONO_STACK}`

/** 把类串挂到 token 的 class 属性上（走 markdown-it 的 renderAttrs ⇒ 与库同源的属性转义口径）。 */
function joinClass(token: { attrJoin: (name: string, value: string) => void }, cls: string): void {
  if (cls !== '') {
    token.attrJoin('class', cls)
  }
}

/**
 * R-165 S5c：把「渲染层吐工具类」装到实例上 —— 原 ai-chat.css 的正文排版族全部改由此处注入
 * （v-html 节点不带 scope，工具类挂在元素自身属性上 ⇒ 不再依赖全局样式表）。
 * 装两份实例（`md` / `mdDegraded`），降级路径的版式与常规路径逐字一致。
 */
function applyTypographyRules(instance: MarkdownItInstance): void {
  const rules = instance.renderer.rules
  /**
   * 「并类串 → 走原规则」包装器。markdown-it 只为 9 条特例预置 `renderer.rules`
   * （code_inline / code_block / fence / image / hardbreak / softbreak / text / html_*），
   * 其余 open/close 走 `renderToken` ⇒ 缺失时回落它（class 已并进 token.attrs，会被带上）。
   */
  const wrap = (name: string, cls: string): void => {
    const original = rules[name]
    rules[name] = (tokens, idx, options, env, self) => {
      joinClass(tokens[idx], cls)
      return original ? original(tokens, idx, options, env, self) : self.renderToken(tokens, idx, options)
    }
  }
  const heading = rules.heading_open
  rules.heading_open = (tokens, idx, options, env, self) => {
    joinClass(tokens[idx], `${MD_HEAD_COMMON} ${MD_HEAD_SIZE[tokens[idx].tag] ?? ''}`)
    return heading ? heading(tokens, idx, options, env, self) : self.renderToken(tokens, idx, options)
  }
  wrap('paragraph_open', MD_P_CLASS)
  wrap('strong_open', MD_STRONG_CLASS)
  wrap('bullet_list_open', MD_LIST_CLASS)
  wrap('ordered_list_open', MD_LIST_CLASS)
  wrap('list_item_open', MD_LI_CLASS)
  wrap('blockquote_open', MD_BLOCKQUOTE_CLASS)
  wrap('link_open', MD_A_CLASS)
  wrap('hr', MD_HR_CLASS)
  wrap('image', MD_IMG_CLASS)
  wrap('th_open', MD_TH_CLASS)
  wrap('td_open', MD_TD_CLASS)
  wrap('code_inline', MD_CODE_INLINE_CLASS)
  // fence：默认规则把语言 class 挂在 <code> 上、<pre> 是裸标签 ⇒ 这里给 <pre> 补上排版类。
  const fence = rules.fence
  rules.fence = (tokens, idx, options, env, self) => {
    const out = fence ? fence(tokens, idx, options, env, self) : ''
    return out.startsWith('<pre>') ? `<pre class="${escapeHtml(MD_FENCE_PRE_CLASS)}">${out.slice(5)}` : out
  }
}

/**
 * markdown-it 实例：`html: true` —— 原生 HTML 由 markdown-it 透传、统一交由下游 DOMPurify
 * 白名单净化（唯一链路的安全面不在解析层双保险，而在净化层单点收口；复核 F3 更正 stale 注释）。
 */
const md = new MarkdownIt({
  html: true, // 原生 HTML 经 markdown-it 透传，统一由 DOMPurify 白名单净化（唯一链路的本意）
  linkify: true,
  breaks: false,
  highlight: (code, lang) => {
    // 未闭合尾围栏（流式）：纯文本转义 —— 规避高亮闪烁，闭合后二次渲染才高亮
    if (tailFenceFingerprint !== null && code === tailFenceFingerprint) {
      return escapeHtml(code)
    }
    return highlightCode(lang, code)
  }
})

/** 降级实例：无 highlight（围栏内纯文本转义），markdown 结构照渲。 */
const mdDegraded = new MarkdownIt({
  html: true, // 原生 HTML 经 markdown-it 透传，统一由 DOMPurify 白名单净化（唯一链路的本意）
  linkify: true,
  breaks: false,
  highlight: (code) => escapeHtml(code)
})

applyTypographyRules(md)
applyTypographyRules(mdDegraded)

/**
 * 流式尾围栏的高亮抑制（决策卡 §三.2「围栏闭合前纯文本规避闪烁」）：
 * 渲染前置入"尾围栏内容指纹"，highlight 回调命中该指纹即返回纯文本（不高亮）；
 * mermaid 尾围栏同理保持源码块，闭合后才进 mermaid 渲染管线。
 */
let tailFenceFingerprint: string | null = null

DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'SPAN') {
    const cls = node.getAttribute('class') ?? ''
    // R-165 S5a/S5c：tip 气泡/箭头、代码块语言名（lg）/ 高亮库名（hlib）三组渲染层自产 span
    // 整条保留（随行 Tailwind 工具类是版式唯一载体）；其余 span 仍只放行 hljs-*（收窄不变）。
    if (cls.split(/\s+/).some((c) => RENDER_MARKER_CLASSES.has(c))) {
      return
    }
    node.setAttribute(
      'class',
      cls.split(/\s+/).filter((c) => c.startsWith('hljs-') || c === 'hljs').join(' ')
    )
  }
  if (node.tagName === 'CODE' || node.tagName === 'PRE') {
    const cls = node.getAttribute('class') ?? ''
    // R-165 S5c：渲染层注入的 md-code / md-pre 标记命中即整条保留（Tailwind 排版类在里面）；
    // 其余仍只放行 language-* / hljs*（hljs 库自产）。
    if (cls.split(/\s+/).some((c) => RENDER_MARKER_CLASSES.has(c))) {
      return
    }
    node.setAttribute(
      'class',
      cls.split(/\s+/).filter((c) => c.startsWith('language-') || c.startsWith('hljs')).join(' ')
    )
  }
  if (node.tagName === 'DIV') {
    // R-163D D1 → R-165 S5a：div 仅限表格双区 wrapper（markdown-it 正文渲染不产出其他 div）
    const cls = node.getAttribute('class') ?? ''
    if (!cls.split(/\s+/).some((c) => RENDER_MARKER_CLASSES.has(c))) {
      node.removeAttribute('class')
    }
  }
  // R-163B G4：figure.code 复制按钮只允许 type="button"（禁表单控件混入）
  if (node.tagName === 'BUTTON') {
    if (node.getAttribute('type') !== 'button') {
      node.setAttribute('type', 'button')
    }
  }
})

function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    ALLOW_DATA_ATTR: false
  })
}

/** mermaid 渲染产物 SVG 净化（SVG profile，§三.7）。 */
export function sanitizeSvg(html: string): string {
  return DOMPurify.sanitize(html, { USE_PROFILES: { svg: true, svgFilters: true } })
}

/** 渲染结果：净化后 HTML + 待渲染 mermaid 源码块（闭合围栏才出现）。 */
export interface MarkdownRender {
  html: string
  /** 闭合 mermaid 围栏（降流/降级期为空数组，保持源码块）。 */
  mermaid: Array<{ index: number; source: string }>
}

/** R-165 S5c：`<pre>` 现带排版工具类（见 MD_FENCE_PRE_CLASS）⇒ 属性段不再可为空。 */
const mermaidIndexRe = /<pre[^>]*><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/g

/* ---------- R-165 S5a：共享配方常量（demo v7.7 `.icbtn` / `.tip` / 6px 滚动条口径逐条照搬） ---------- */

/** tip 气泡（demo v7 `.tip`，黑底 + 居中 + `bottom calc(100%+7px)`）——AiPanel.vue 同源导入，
 *  避免「组件侧常量」与「渲染层注入 HTML」两处字面量漂移。 */
export const TIP_CLASS = 'pointer-events-none absolute bottom-[calc(100%+7px)] left-1/2 -translate-x-1/2 z-[6] whitespace-nowrap rounded-[6px] bg-[#1f2328] px-[8px] py-[3px] text-[11px] leading-[1.5] text-white opacity-0 transition-opacity duration-[180ms] motion-reduce:transition-none group-hover/tip:opacity-100 group-focus-visible/tip:opacity-100'
/** tip 小三角（v7 `::after` 的旋转方块复刻，伪元素属手写 CSS 铁律禁用）。 */
export const TIP_ARROW_CLASS = 'absolute left-1/2 top-full -ml-[4px] h-[8px] w-[8px] -translate-y-1/2 rotate-45 rounded-[1px] bg-[#1f2328]'

/** 复制成功反馈两态（demo v7 `14c`）：默认藏绿勾、`is-copied` 时换勾 + 绿字（#1a7f45）。
 *  选择器全部走任意变体（`[&…]`），`is-copied` 变体多一个类 ⇒ 特异度恒高压过默认态。 */
export const COPY_STATE_CLASS = '[&_svg.i-check]:hidden [&.is-copied_svg.i-copy]:hidden [&.is-copied_svg.i-check]:block [&.is-copied]:text-[#1a7f45]'
/** 复制图标钮配方（demo `.icbtn` 24×24：细描边 + 圆角 6 + hover 加深 + 具名组 group/tip）。 */
export const COPY_ICON_BTN_CLASS = `cpbtn group/tip relative inline-flex w-[24px] h-[24px] items-center justify-center rounded-[6px] border border-solid border-[#e3e6ea] bg-white p-0 text-[#57606a] transition-colors duration-[150ms] hover:border-[#8b949e] hover:text-[#1f2328] [&_svg]:w-[14px] [&_svg]:h-[14px] [&_svg]:fill-none [&_svg]:stroke-current [&_svg]:[stroke-width:1.7] [&_svg]:[stroke-linecap:round] [&_svg]:[stroke-linejoin:round] ${COPY_STATE_CLASS}`
/** 复制图标钮的两枚叠放 svg（v7 图标逐字照搬：复制 = rect+path，成功 = polyline 勾）。 */
export const COPY_ICON_SVGS =
  '<svg viewBox="0 0 24 24" aria-hidden="true" class="i-copy"><rect x="9" y="9" width="13" height="13" rx="2"></rect>' +
  '<path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>' +
  '<svg viewBox="0 0 24 24" aria-hidden="true" class="i-check"><polyline points="20 6 9 17 4 12"></polyline></svg>'

/** 6px 圆角半透明滚动条（demo 14a 口径：滑轨透明 / 滑块 `rgba(31,35,40,.28)`、hover `.5`）。
 *  v7 走 config plugin 的 addBase，本仓禁改 tailwind.config.js ⇒ 改任意变体类写在模板/渲染层类串里。 */
export const SCROLLBAR_CLASS = '[&::-webkit-scrollbar]:w-[6px] [&::-webkit-scrollbar]:h-[6px] [&::-webkit-scrollbar-thumb]:rounded-full [&::-webkit-scrollbar-thumb]:bg-[#1f2328]/30 [&::-webkit-scrollbar-thumb:hover]:bg-[#1f2328]/50 [&::-webkit-scrollbar-track]:bg-transparent'

/** 表格双区（R-165 S5a，v7.6/v7.7 修法）：表头区不滚（programmatic scrollLeft 由 JS 同步）+
 *  表体区 `max-h-[320px]` 内滚（6px 圆角滚动条只贴表体右缘）。两表同 `w-full table-fixed`
 *  （等分列宽 ⇒ 两表逐列对齐，`table-fixed` 的适用边界见 v7.6 口径登记：宽表/内容驱动列宽需
 *  JS 列宽同步，本场景不做）。表头区 `pr-[6px]` 抵消表体区竖滚动条占位，保证两区可视宽相等。 */
const TABLE_WRAP_CLASS = 'md-table-wrap mb-3.5 w-full overflow-hidden rounded-[8px] border border-solid border-[#eef0f3]'
const TABLE_HD_CLASS = 'md-table-hd overflow-hidden pr-[6px] bg-[#f6f7f9]'
const TABLE_BD_CLASS = `md-table-bd max-h-[320px] overflow-auto [scrollbar-gutter:stable] ${SCROLLBAR_CLASS}`
/** 两区共用表体配方：等分列 + 表头 nowrap 截断（th 的 nowrap 由 MD_TH_CLASS 注入，固定布局下
 *  必须自解 `overflow:hidden + text-ellipsis` 才不会溢出压到邻列）。 */
const TABLE_CLASS = 'w-full table-fixed border-separate border-spacing-0 [&_th]:overflow-hidden [&_th]:text-ellipsis'

/**
 * 每列最小宽度（demo v7.6/v7.7 实证锚：4 列窄表取 `min-w-[720px]` ⇒ **180px/列** 是终审通过的
 * 可读下限）。本仓渲染层表格列数不定 ⇒ 按列数取**静态档位**（JIT 只能扫字面类名，禁用运行期拼类名）。
 * 为什么必须给最小宽度：`w-full table-fixed` 下表格宽度恒等于容器宽 ⇒ 永不横向溢出，
 * 「横滚联动」会退化成死代码；给定 180px/列后，「超宽表格在表体区内横滚 + 表头区同步」才真成立
 * （与 demo 同观感：4 列 = 720px 表宽）。
 * 例外（终裁边界）：**≤2 列**视为「容器装得下的窄表」⇒ 不给最小宽度，保持现状视觉（不触发横滚）。
 */
const TABLE_MIN_W: Record<number, string> = {
  3: 'min-w-[540px]',
  4: 'min-w-[720px]',
  5: 'min-w-[900px]',
  6: 'min-w-[1080px]',
  7: 'min-w-[1260px]',
  8: 'min-w-[1440px]',
  9: 'min-w-[1620px]',
  10: 'min-w-[1800px]'
}
/** 列数 → 最小宽度档位类（>10 列取最大档；≤2 列返回空串 = 不给最小宽度）。
 *  注意计数用 `<th>` / `<th ` 而非裸 `<th`（`<thead>` 会被裸前缀误计一列）。 */
function tableMinWidthClass(headHtml: string): string {
  const cols = (headHtml.match(/<th[ >]/g) ?? []).length
  if (cols <= 2) {
    return ''
  }
  return TABLE_MIN_W[Math.min(cols, 10)]
}

/**
 * R-163B G4 → R-165 S5a：围栏 → 代码块图容器（demo v7.7 :133-139）——figcaption（语言名 +
 * highlight.js + **复制图标钮**）+ pre 内部横滚。闭合 mermaid 围栏除外（保留裸源码块，
 * 由 mermaid 卡接管）。复制钮由「文字钮」改 v7 图标钮（两枚同尺寸叠放 svg + tip 气泡，
 * 成功态换绿勾，见 COPY_ICON_BTN_CLASS）。
 */
const fenceFigureRe = /<pre( class="[^"]*")?><code class="language-(?!mermaid)([^"]*)">([\s\S]*?)<\/code><\/pre>/g

function wrapFenceFigures(html: string): string {
  return html.replace(
    fenceFigureRe,
    (_m, preCls: string | undefined, lang: string, code: string) =>
      `<figure class="${FIGURE_CODE_CLASS}"><figcaption class="${FIGCAPTION_CLASS}"><span class="${FIG_LANG_CLASS}">${escapeHtml(lang)}</span>` +
      `<span class="${FIG_HLIB_CLASS}">highlight.js</span>` +
      `<button type="button" class="${COPY_ICON_BTN_CLASS}" aria-label="复制代码">${COPY_ICON_SVGS}` +
      `<span class="tip ${TIP_CLASS}">复制代码<span class="tip-arrow ${TIP_ARROW_CLASS}" aria-hidden="true"></span></span>` +
      `</button></figcaption>` +
      `<pre${preCls ?? ''}><code class="language-${lang}">${code}</code></pre></figure>`
  )
}

/**
 * R-163D D1 → R-165 S5a：表格双区 wrapper（v7.6/v7.7 修法，推翻 D1 的单容器方案）。
 *
 * D1 把表头与表体放在同一个 `overflow-auto` 容器里 ⇒ 竖滚动条沿容器整个视口从头画到尾，
 * 视觉上「穿过」表头（`sticky` 只改绘制位置，改不了滚动条的归属与跨度）。S5a 按终裁把表头
 * **移出**滚动容器：`md-table-hd`（不滚）+ `md-table-bd`（唯一滚动容器，滚动条只贴它右缘），
 * 表体区横滚时由 AiPanel 同步表头区 `scrollLeft`（scroll 事件代理）。
 *
 * 边界（终裁给定）：行数在渲染期不可知 ⇒ 一律套双区结构；表体不超 320px 时无滚动条、
 * 表头区 `pr-[6px]` 与 `[scrollbar-gutter:stable]` 同步抵消 ⇒ 与单区形态视觉一致。
 */
function wrapTables(html: string): string {
  return html.replace(/<table>([\s\S]*?)<\/table>/g, (_m, inner: string) => {
    const headMatch = /<thead>([\s\S]*?)<\/thead>/.exec(inner)
    const head = headMatch === null ? '' : headMatch[0]
    const body = headMatch === null ? inner : inner.replace(headMatch[0], '')
    const cls = `${TABLE_CLASS} ${tableMinWidthClass(head)}`
    const headZone = head === ''
      ? ''
      : `<div class="${TABLE_HD_CLASS}"><table class="${cls}">${head}</table></div>`
    return (
      `<div class="${TABLE_WRAP_CLASS}">${headZone}` +
      `<div class="${TABLE_BD_CLASS}"><table class="${cls}">${body}</table></div>` +
      `</div>`
    )
  })
}

/**
 * AI 正文渲染唯一入口。`streaming` = 该正文块仍处流式（未闭合围栏按纯文本、不进 mermaid）。
 * 结果已 DOMPurify 净化，调用方直接 v-html。
 * 闭合 mermaid 围栏：**保留** `language-mermaid` 源码块（= 渲染失败时的兜底源码；R-165 S5b 起
 * 渲染成功时整块被 mermaid 卡替换，卡内「源码」态展示同一份源码），
 * 同时在返回值的 mermaid 列表登记源码——组件异步渲染 SVG 成功后替换为 mermaid 卡。
 *
 * R-165 S5a：非 mermaid 围栏包成 `figure.code`（figcaption 带复制**图标**钮 + tip 气泡）；
 * 表格包成双区 wrapper（表头不滚 / 表体 320px 内滚）。两处都在**净化前**改写，
 * 产物一律经白名单（新增 svg 图标与表格三区标记类已登记在 ALLOWED_TAGS / RENDER_MARKER_CLASSES）。
 */
export function renderMarkdown(text: string, streaming: boolean): MarkdownRender {
  const fenceOpen = streaming && hasUnclosedFence(text)
  // 指纹 = 尾围栏 info 行之后的代码体（与 markdown-it 传给 highlight 的 code 逐字相等）
  let tailSource: string | null = null
  if (fenceOpen) {
    const tailRaw = text.slice(text.lastIndexOf('```') + 3)
    const nl = tailRaw.indexOf('\n')
    tailSource = nl >= 0 ? tailRaw.slice(nl + 1) : ''
  }
  tailFenceFingerprint = tailSource
  try {
    const useDegraded = degraded || text.length > DEGRADE_AT_CHARS
    const started = performance.now()
    let html = (useDegraded ? mdDegraded : md).render(text)
    html = wrapTables(html)
    const mermaid: Array<{ index: number; source: string }> = []
    if (!useDegraded && !streaming) {
      // 登记闭合 mermaid 围栏（按出现序）；源码块本身留在净化后 HTML 里
      let match: RegExpExecArray | null
      let index = 0
      mermaidIndexRe.lastIndex = 0
      while ((match = mermaidIndexRe.exec(html)) !== null) {
        mermaid.push({ index: index++, source: unescapeHtml(match[1]) })
      }
      // G4：非 mermaid 围栏包成图容器（语言标签 + 复制按钮）
      html = wrapFenceFigures(html)
    }
    if (fenceOpen) {
      // 未闭合尾围栏：剥掉尾块 language-* class（纯文本口径，闭合后二次渲染才恢复高亮）
      const at = html.lastIndexOf('class="language-')
      if (at >= 0) {
        html = html.slice(0, at) + html.slice(at).replace(/class="language-[^"]*"/, 'class=""')
      }
    }
    if (performance.now() - started > RENDER_BUDGET_MS) {
      degraded = true
    }
    return { html: sanitizeHtml(html), mermaid }
  } finally {
    tailFenceFingerprint = null
  }
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function unescapeHtml(s: string): string {
  return s
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
}

/** mermaid 模块惰性初始化（securityLevel:'strict' / htmlLabels:false / suppressErrorRendering:true）。 */
let mermaidPromise: Promise<typeof import('mermaid')> | null = null

function loadMermaid(): Promise<typeof import('mermaid')> {
  mermaidPromise ??= import('mermaid').then((mod) => {
    mod.default.initialize({
      startOnLoad: false,
      securityLevel: 'strict',
      htmlLabels: false,
      suppressErrorRendering: true
    })
    return mod
  })
  return mermaidPromise
}

/** mermaid 渲染缓存（源码指纹 → 净化后 SVG），失败不缓存。 */
const mermaidCache = new Map<string, string>()

/**
 * 渲染单个 mermaid 图源（围栏闭合后一次性调用；render error/超时 ⇒ 返回 null，
 * 调用方保留源码块——§三.7 失败兜底）。返回净化后 SVG 字符串。
 */
export async function renderMermaidSvg(source: string): Promise<string | null> {
  const key = source
  const hit = mermaidCache.get(key)
  if (hit !== undefined) {
    return hit
  }
  try {
    const mod = await loadMermaid()
    const id = `mmd-${Math.abs(hashCode(key)).toString(36)}`
    const timeout = new Promise<never>((_resolve, reject) =>
      setTimeout(() => reject(new Error('mermaid timeout')), MERMAID_TIMEOUT_MS)
    )
    const result = (await Promise.race([mod.default.render(id, source), timeout])) as { svg: string }
    const svg = sanitizeSvg(result.svg)
    if (mermaidCache.size >= 20) {
      const first = mermaidCache.keys().next().value
      if (first !== undefined) {
        mermaidCache.delete(first)
      }
    }
    mermaidCache.set(key, svg)
    return svg
  } catch {
    return null
  }
}

function hashCode(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) | 0
  }
  return h
}
