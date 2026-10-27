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
 *   （R-165 S5a：md-table-wrap / md-table-hd / md-table-bd 三区 + 问题1 偏差修正批 aj04：
 *   md-table-hdscroll / md-table-gutter 两格，见 RENDER_MARKER_CLASSES）；
 *   svg/path/rect/polyline 仅复制图标钮的自绘图标（R-165 S5a，属性收窄为几何集合）；
 *   mermaid 输出 SVG 走独立 SVG profile 净化。属性仅 a.href
 *   （DOMPurify 默认协议过滤）/class/src/alt 与图标几何集合；禁 style / on* 事件 / iframe / script / 表单控件。
 * - 流式策略（§三.2 + m1）：hljs 高亮 memo（已完成围栏不重解析高亮 = 块级 memo 的落点）；
 *   未闭合围栏（流式尾块）渲染为纯文本规避闪烁，闭合后二次渲染才高亮/进 mermaid；
 *   长文 >10k 字符**按条**降级（关高亮与 mermaid，结构照渲；不粘滞、不影响下一条）。
 * - **流式块级「净化后 HTML」前缀缓存（2026-10-24 性能专项）**：markdown-it 顶层 token 流定出
 *   「最后一个顶层块之前」的冻结前缀，前缀产物（sanitize 之后）按源文本前缀复用 ⇒ 每帧只
 *   重新净化新增块（DOMPurify 是瓶颈，md.parse ≈ 1.2ms/4k）；前缀 / 新增块各自独立过同一套白名单；
 *   `data-fence-key` 按前缀围栏数续号保证全局唯一；原生 HTML 跨块 / 引用式链接定义退回全文渲染；
 *   流式结束整篇重渲一次。
 * - **流式代码块防抖口径（问题1 偏差修正批 aj14d 落地：立即渲染 + 75ms 防抖 + 块级稳定 key）**：
 *   ① **未闭合 fence 立即渲染**（纯文本、不高亮、不折叠）—— 防抖目标是「高亮闪烁」而非「结构出现」，
 *      让未闭合块延后出现只会让流式观感变空；
 *   ② **75ms 防抖**：同一正文块（`blockKey`）在窗口内的连续增量合并为一次重绘（`renderMarkdown`
 *      的准入 + 静默满窗后的 flush 回调），把逐 token 重排压到 ≤14 次/秒 —— 块的**首次**渲染恒立即
 *      落地（不延后首帧），窗口外的增量也恒立即落地；窗口内沿用上一帧结果（调用方据 flush 回调重入）；
 *   ③ **块级稳定 key**：每个围栏按「**块序号（本次渲染的出现序）+ 语言**」生成
 *      `data-fence-key="f{n}-{lang}"`（fence 规则内自增，见 `nextFenceKey`；批次 7C 起 key 先进账本，
 *      净化后按渲染器 token 回填 —— 见下方「批次 7C ①」），流式未闭合阶段与闭合之后序号/语言均不变
 *      ⇒ 渲染产物带稳定身份，mermaid 卡按该 key 绑定槽位（不再靠位置下标），
 *      「代码块 → mermaid 卡」的替换不会打到别的块上。
 * - mermaid（§三.7）：securityLevel:'strict' + htmlLabels:false + suppressErrorRendering:true +
 *   maxTextSize / maxEdges 硬上限，渲染产物 SVG 过 DOMPurify SVG profile；
 *   失败（render error / 3s 等待预算用尽——只停止等待、不中断同步渲染 / 超上限）保留源码块。
 *
 * - **批次 7C（2026-10-26）三处收口**：
 *   ① **围栏槽位身份只由渲染器生成**（FE-42）：`data-fence-key` **不进白名单** —— 模型原生 HTML
 *      写的 key 一律在净化期被剥；渲染器在 fence 规则里按出现序记 key 账本，并给 pre 打一枚
 *      随机 `data-fence-token`（净化期唯一放行的内部标记），净化后按 token 认领、回填
 *      `data-fence-key="f{n}-{lang}"` 并立刻删掉 token。模型既不能携带/覆盖槽位属性，
 *      也不能挤占序号或把自己登记成 mermaid 槽位（mermaid 登记改在回填后、只认带 key 的 pre）。
 *   ② **mermaid「可见 + idle」双门控**：组件侧 IntersectionObserver 管「可见」，渲染层
 *      `renderMermaidSvg` 进 idle（`requestIdleCallback`，1s 超时兜底；无 rIC 退 `setTimeout(0)`）
 *      才开始 import/渲染。3s `Promise.race` 只是**等待预算、不是中断**（JS 单线程，同步渲染
 *      无法被抢占）；超时后迟到的 SVG 直接丢弃（不缓存、不回填），源码块保留，下次仍重试。
 *   ③ **>10000 字符的降级提示按条只出一次**：`renderMarkdown` 产物带 `degraded` 与 `degradeNotice`；
 *      同一消息（`blockKey` 去掉段号）的首个降级帧给文案，后续帧为 null，下一条消息不粘滞。
 */
import MarkdownIt from 'markdown-it'
import type { MarkdownIt as MarkdownItInstance } from 'markdown-it'
import DOMPurify from 'dompurify'
// 高亮瘦身（2026-10-24 性能专项）：全量入口 ~1.0MB（gzip ~310KB）里绝大部分是语言定义，
// 改 core + 常用语言白名单（15 种，~78KB / gzip ~25KB）；未注册语言走既有 escapeHtml 兜底（行为兼容）。
import hljs from 'highlight.js/lib/core'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import java from 'highlight.js/lib/languages/java'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import json from 'highlight.js/lib/languages/json'
import bash from 'highlight.js/lib/languages/bash'
import yaml from 'highlight.js/lib/languages/yaml'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import markdownLanguage from 'highlight.js/lib/languages/markdown'
import diff from 'highlight.js/lib/languages/diff'
import go from 'highlight.js/lib/languages/go'
import csharp from 'highlight.js/lib/languages/csharp'
import kotlin from 'highlight.js/lib/languages/kotlin'
// hljs 库默认主题（决策卡 §二.2：高亮用库默认主题色，不计入 UI 用色收敛）
import 'highlight.js/styles/github.css'
import { translate } from '@/composables/useCopy'

/**
 * 常用语言白名单（15 种）。别名随语言定义自带：xml → html/svg 等、markdown → md、
 * typescript → ts、javascript → js、bash → sh、yaml → yml、python → py。
 */
const HLJS_LANGUAGES = {
  javascript,
  typescript,
  java,
  python,
  sql,
  json,
  bash,
  yaml,
  xml,
  css,
  markdown: markdownLanguage,
  diff,
  go,
  csharp,
  kotlin
}
for (const [name, definition] of Object.entries(HLJS_LANGUAGES)) {
  hljs.registerLanguage(name, definition)
}

/** 已注册语言（诊断/回归用：hljs 白名单必须保持收敛，防全量包回流）。 */
export function highlightLanguages(): string[] {
  return hljs.listLanguages()
}

/** 长文降级阈值（字符数，决策卡 §三.2「>10k」；**按条**短路、不粘滞）。 */
export const DEGRADE_AT_CHARS = 10_000
/** mermaid 单次渲染的**等待预算**（ms）：到点只释放等待方，不中断同步渲染（见 renderMermaidSvg）。 */
export const MERMAID_TIMEOUT_MS = 3_000
/** mermaid 图源硬上限（字符）——超出由 mermaid 抛错，回源码卡（防超长图卡死主线程）。 */
export const MERMAID_MAX_TEXT_SIZE = 30_000
/** mermaid 边数硬上限——同上。 */
export const MERMAID_MAX_EDGES = 300
/** mermaid SVG 渲染缓存上限（源码指纹 → 净化后 SVG）。 */
export const MERMAID_CACHE_MAX = 64

/** DOMPurify 白名单（§三.3）。span 仅限 class="hljs-*"（下方 hook 收窄）；
 *  R-163B G4：figure/figcaption/button 为代码块图容器（button 仅 type="button"，hook 强制）；
 *  R-163D D1 → R-165 S5a：div 为表格双区 wrapper（三区标记类，渲染层生成，hook 收窄）；
 *  R-165 S5a：svg/path/rect/polyline 仅承载复制图标钮的自绘图标（几何属性白名单）。
 *  L-86：`s`（markdown-it 的 `~~x~~` 产物）与 `del` 同级 —— 无属性、纯表现型元素，
 *  缺失时消毒只剥元素留文字（页面静默降级成纯文本）。 */
const ALLOWED_TAGS = [
  'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'code', 'pre', 'blockquote', 'em', 'strong', 'del', 's', 'a', 'hr', 'br', 'img', 'span',
  'figure', 'figcaption', 'button', 'div',
  'svg', 'path', 'rect', 'polyline'
]
const ALLOWED_ATTR = [
  'class', 'href', 'src', 'alt', 'type',
  // svg 图标几何属性（复制图标：rect 的 x/y/width/height/rx、path 的 d、polyline 的 points、svg 的 viewBox）
  'viewBox', 'd', 'points', 'x', 'y', 'width', 'height', 'rx',
  // 批次 7C（FE-42）：`data-fence-key` **不是白名单属性** —— 模型原生 HTML 写了也在净化期被剥，
  // 围栏身份只能由渲染器在净化后回填。净化期唯一放行的是内部认领标记 `data-fence-token`
  // （随机 nonce，回填即从产物删除；模型伪造的 token 值不匹配、同样被删）。
  'data-fence-token'
]

/**
 * URI 白名单（防护批② B2-8；用户裁决 2026-10-21 第 3 条）：
 * **http/https 保留**，`#锚点`/相对路径保留，`data:image/*`（非 svg）保留；**其余协议一律剥离**
 * （含 `javascript:` / `vbscript:` / `file:` / 非图片 `data:`）。
 *
 * <p>判据抄 DOMPurify 默认正则的"scheme 形态"分支（`[a-z+.-]+` 后必须紧跟非 scheme 字符或结尾），
 * 因此 `javascript:alert(1)` 不会被当成"普通相对路径"放行。
 */
export const ALLOWED_URI_REGEXP =
  /^(?:https?:|data:image\/(?!svg)|#|\/(?!\/)|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i

/** 图片 src 的额外约束（比 URI 白名单更严）：只放行 data:image/*（非 svg）与同源地址。 */
export function isAllowedImageSrc(src: string): boolean {
  if (!src) return false
  const value = src.trim()
  if (/^data:image\/(?!svg)/i.test(value)) return true
  if (/^[a-z][a-z0-9+.\-]*:/i.test(value) || value.startsWith('//')) {
    // 绝对地址 / 协议相对地址：只有同源才放行（图片是"无需点击即出网"的外泄通道）
    return isSameOrigin(value)
  }
  return true
}

/** 是否同源（用浏览器解析后的 origin 比对；测试环境 jsdom 同样可用）。 */
export function isSameOrigin(url: string): boolean {
  try {
    const base = typeof window !== 'undefined' && window.location ? window.location.href : 'http://localhost/'
    const parsed = new URL(url, base)
    return parsed.origin === new URL(base).origin
  } catch {
    return false
  }
}

/** 是否站外 http(s) 链接（决定加 rel / 新窗口 / 外链标记）。 */
export function isExternalHref(href: string): boolean {
  if (!href) return false
  return /^https?:/i.test(href.trim()) && !isSameOrigin(href)
}


/** 表格双区三区标记类（R-165 S5a，渲染层 wrapTables 生成）——hook 只认这三个标记类，
 *  div 上随行的 Tailwind 工具类同 div 保留（表格版式全在工具类里，不能被净化剥掉）。
 *  问题1 偏差修正批（aj04）：表头行由「单容器 + pr-[6px] 补偿条」改为
 *  「flex 行 = 可横滚表头区（md-table-hdscroll）+ 6px gutter 占位格（md-table-gutter）」
 *  ⇒ 两个新格同样必须是标记类，否则其工具类会被 hook 剥掉（版式塌）。 */
const TABLE_DIV_CLASSES = new Set([
  'md-table-wrap',
  'md-table-hd',
  'md-table-hdscroll',
  'md-table-gutter',
  'md-table-bd'
])
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

/** 长文降级已收敛为**按条短路**（>10k 的这条消息关高亮/mermaid，结构照渲）；不再有任何粘滞开关。 */

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
 *  非降级路径跑），此前降级期代码块高度不设上限。
 *  2A 批（裁决 §14.4 代码族）：限高改**双约束** `min(280px,35vh)` —— 正常窗口取 280px 上限，
 *  矮窗口按视口比例收紧。 */
const MD_FENCE_PRE_CLASS =
  `md-pre m-0 py-[10px] px-[11px] max-h-[min(280px,35vh)] overflow-auto bg-[#f8f8f8] text-[#333] ${MONO_STACK} text-[12.3px] leading-[1.62] ` +
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

/* ---------- 批次 7C（FE-42）：围栏槽位身份的「渲染器账本 + 净化后回填」 ----------
 *
 * 围栏身份（`f{序号}-{语言}`）只能由渲染器的 fence 规则按**出现序**生成：
 * ① 每次单块渲染前生成一枚随机 nonce，fence 规则把 key 记进账本、并给 `<pre>` 打
 *    `data-fence-token="{nonce}"`（净化期唯一放行的内部标记，不是槽位身份）；
 * ② `data-fence-key` 不在 DOMPurify 白名单 ⇒ 模型原生 HTML 写的 key 在净化期被剥；
 * ③ 净化后按 token 认领 pre、回填 key 并立刻删掉 token（模型伪造的 token 值不匹配 ⇒ 同样删除）。
 * 因而模型既不能携带/覆盖槽位属性，也不能挤占序号；mermaid 登记同样只认回填后的 key。
 * ------------------------------------------------------------------------- */
const fenceLedger: string[] = []
let activeFenceNonce = ''
let fenceNonceSeq = 0

function newFenceNonce(): string {
  fenceNonceSeq += 1
  return `fk${fenceNonceSeq.toString(36)}${Math.random().toString(36).slice(2, 10)}`
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
  // fence：默认规则把语言 class 挂在 <code> 上、<pre> 是裸标签 ⇒ 这里给 <pre> 补上排版类，
  // 并记下稳定身份（批次 7C：key 进账本，pre 只带内部 token；净化后由 backfillFenceKeys 回填）。
  const fence = rules.fence
  rules.fence = (tokens, idx, options, env, self) => {
    const out = fence ? fence(tokens, idx, options, env, self) : ''
    if (!out.startsWith('<pre>')) {
      return out
    }
    fenceLedger.push(nextFenceKey(tokens[idx].info))
    return `<pre class="${escapeHtml(MD_FENCE_PRE_CLASS)}" data-fence-token="${escapeHtml(activeFenceNonce)}">${out.slice(5)}`
  }
}

/**
 * markdown-it 实例：`html: true` —— 原生 HTML 由 markdown-it 透传、统一交由下游 DOMPurify
 * 白名单净化（唯一链路的安全面不在解析层双保险，而在净化层单点收口；复核 F3 更正 stale 注释）。
 */
const md = new MarkdownIt({
  // 原生 HTML 经 markdown-it 透传，统一由 DOMPurify 白名单净化（唯一链路的本意）
  html: true,
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
  // 原生 HTML 经 markdown-it 透传，统一由 DOMPurify 白名单净化（唯一链路的本意）
  html: true,
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
  // 防护批② B2-8：图片只认同源与 data:image/*（模型输出一句 ![](https://attacker/?d=…) 就能
  // 让浏览器自动出网，无需用户点击 —— 这正是 MSRC 列的第一种外泄手法）。
  if (node.tagName === 'IMG') {
    const src = node.getAttribute('src') ?? ''
    if (!isAllowedImageSrc(src)) {
      node.remove()
    }
  }
  // 防护批② B2-8：外链一律 rel="noopener noreferrer nofollow"；站外 http(s) 另加 target 与可见标记。
  if (node.tagName === 'A') {
    node.setAttribute('rel', 'noopener noreferrer nofollow')
    const href = node.getAttribute('href') ?? ''
    if (isExternalHref(href)) {
      node.setAttribute('target', '_blank')
      const cls = node.getAttribute('class') ?? ''
      node.setAttribute('class', cls ? `${cls} md-ext-link` : 'md-ext-link')
      node.appendChild(createExternalLinkIcon())
    }
  }
})

/** 外链标记图标（自绘 svg，禁 emoji —— 用户裁决 2026-10-21 第 10 条）。 */
function createExternalLinkIcon(): Element {
  const doc = typeof document !== 'undefined' ? document : (globalThis as { document?: Document }).document
  const ns = 'http://www.w3.org/2000/svg'
  const svg = doc!.createElementNS(ns, 'svg')
  svg.setAttribute('viewBox', '0 0 12 12')
  svg.setAttribute('width', '10')
  svg.setAttribute('height', '10')
  svg.setAttribute('class', 'md-ext-icon inline-block ml-0.5 align-baseline opacity-70')
  svg.setAttribute('aria-hidden', 'true')
  const path = doc!.createElementNS(ns, 'path')
  path.setAttribute('d', 'M4 8 L8.5 3.5 M5 3.5 H8.5 V7')
  path.setAttribute('fill', 'none')
  path.setAttribute('stroke', 'currentColor')
  path.setAttribute('stroke-width', '1.2')
  path.setAttribute('stroke-linecap', 'round')
  path.setAttribute('stroke-linejoin', 'round')
  svg.appendChild(path)
  return svg as unknown as Element
}


function sanitizeHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS,
    ALLOWED_ATTR,
    ALLOWED_URI_REGEXP,
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
  /** 闭合 mermaid 围栏（降流/降级期为空数组，保持源码块）；`key` = 稳定身份（批次 7C 回填）。 */
  mermaid: Array<{ index: number; key: string; source: string }>
  /** 本条是否命中 >10k 按条降级（关高亮与 mermaid，结构照渲）。 */
  degraded: boolean
  /** 降级提示文案：**本条消息只在首个降级帧给出**，后续帧 / 非降级帧为 null。 */
  degradeNotice: string | null
}

/* ---------- 问题1 偏差修正批 aj14d ②：流式块重绘防抖（75ms 合并一次） ---------- */

/** 防抖窗口（ms）：同一正文块的连续增量在窗口内合并为一次重绘（≈14 次/秒上限）。 */
export const STREAM_DEBOUNCE_MS = 75
/** 防抖记账上限（块数）——防长会话下无限增长（与高亮 memo 同族的 FIFO 淘汰）。 */
const STREAM_RENDER_MAX = 64

/** 每块的防抖记账：上次真渲染的文本 / 时刻 / 结果。 */
const streamRenders = new Map<string, { text: string; renderedAt: number; render: MarkdownRender }>()

/** flush 回调（调用方注册：窗口静默满后重入渲染一次）；null = 无消费者（测试/纯函数场景）。 */
let streamFlushHandler: (() => void) | null = null
const streamFlushTimers = new Set<ReturnType<typeof setTimeout>>()

/**
 * 注册「防抖窗口静默满」回调。窗口内被合并掉的增量靠它落地 —— 否则上游停吐（未闭合围栏中途停顿）
 * 时最后一帧内容会一直不现身。调用方在卸载时传 null 注销。
 */
export function setStreamFlushHandler(handler: (() => void) | null): void {
  streamFlushHandler = handler
}

function scheduleStreamFlush(delay: number): void {
  const timer = setTimeout(() => {
    streamFlushTimers.delete(timer)
    streamFlushHandler?.()
  }, Math.max(1, delay))
  streamFlushTimers.add(timer)
}

/** 清空防抖记账与挂起定时器（测试用；不触发 flush）。 */
export function resetStreamDebounce(): void {
  for (const timer of streamFlushTimers) {
    clearTimeout(timer)
  }
  streamFlushTimers.clear()
  streamRenders.clear()
}

/** 批次 7C：mermaid 登记改在「净化 + key 回填」之后跑 —— 只认带 `data-fence-key` 的 pre
 *  （模型原生 HTML 的 `language-mermaid` 预块没有 key，不会进清单、也抢不走槽位）。 */
const mermaidIndexRe = /<pre\b([^>]*)><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/g

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

/** 表格双区（R-165 S5a 双区骨架 → 问题1 偏差修正批 aj04 改 v7.8 终裁右栏）：表头区不滚
 *  （programmatic scrollLeft 由 JS 同步）+ 表体区**长表格族**限高内滚（6px 圆角滚动条只贴表体右缘）。
 *  2A 批（裁决 §14.4）：表体限高改双约束 `min(480px,60vh)` —— 长表格族给高一档，矮窗口按视口收紧。
 *  两表同 `w-full table-fixed`（等分列宽 ⇒ 两表逐列对齐，`table-fixed` 的适用边界见 v7.6 口径登记：
 *  宽表/内容驱动列宽需 JS 列宽同步，本场景不做）。
 *
 *  aj04 改后（v7.8 终裁）：表头行 = `flex` 行 = [可横滚表头区 `md-table-hdscroll`] + [6px gutter 占位格]。
 *  原 v7.6 口径用 `pr-[6px]` 让表头内容给滚动条让位 —— 那 6px 是**内边距**：没有单元格边界、
 *  不参与表头行边框绘制 ⇒ 窄表档下表头底边止步于内容盒右缘，右端 6px 只剩底色（实机 =「一截断了头的
 *  竖条挂在表头右端」）。改成**真实占位格**后，表头下边框由这一格补齐（背景与表头同色、下边框对齐），
 *  表头底边连续收口、滚动条只出现在表体右缘；宽度 6px 恰等于表体 `[scrollbar-gutter:stable]`
 *  预留的竖滚动条宽度 ⇒ 两表可视宽相等、列边界严格对齐（短表不溢出时亦同，列宽不跳动）。 */
const TABLE_WRAP_CLASS = 'md-table-wrap mb-3.5 w-full overflow-hidden rounded-[8px] border border-solid border-[#eef0f3]'
/** 表头行容器：`flex` 行（背景与表头同色，承载「表头区 + gutter 格」两格）。 */
const TABLE_HD_CLASS = 'md-table-hd flex bg-[#f6f7f9]'
/** 表头区（可程序化 scrollLeft 的容器，仍 `overflow-hidden`）：`flex-1 min-w-0` 吃掉除 gutter 外的全宽。 */
const TABLE_HD_SCROLL_CLASS = 'md-table-hdscroll flex-1 min-w-0 overflow-hidden'
/** gutter 占位格（demo aj04 改后样张类名逐字 + 本仓 preflight 关闭所需的两条复位：
 *  `border-0` 归零其余三边、`border-solid` 给 `border-b` 补边框样式 —— demo 靠 preflight 的
 *  全局 `border-style: solid`，本仓 `corePlugins.preflight=false`，缺它则下边框恒 none/不可见）。 */
const TABLE_GUTTER_CLASS = 'md-table-gutter flex-none w-[6px] bg-[#f6f7f9] border-0 border-b border-solid border-[#e3e6ea]'
const TABLE_BD_CLASS = `md-table-bd max-h-[min(480px,60vh)] overflow-auto [scrollbar-gutter:stable] ${SCROLLBAR_CLASS}`
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
const fenceFigureRe = /<pre([^>]*)><code class="language-(?!mermaid)([^"]*)">([\s\S]*?)<\/code><\/pre>/g

function wrapFenceFigures(html: string): string {
  return html.replace(
    fenceFigureRe,
    (_m, preAttrs: string, lang: string, code: string) =>
      // 批次 7C：key 由净化后回填（只落在 pre 槽位上）；figure 不再携带任何槽位属性
      `<figure class="${FIGURE_CODE_CLASS}"><figcaption class="${FIGCAPTION_CLASS}"><span class="${FIG_LANG_CLASS}">${escapeHtml(lang)}</span>` +
      `<span class="${FIG_HLIB_CLASS}">highlight.js</span>` +
      `<button type="button" class="${COPY_ICON_BTN_CLASS}" aria-label="${translate('markdown.copyCode')}">${COPY_ICON_SVGS}` +
      `<span class="tip ${TIP_CLASS}">${translate('markdown.copyCode')}<span class="tip-arrow ${TIP_ARROW_CLASS}" aria-hidden="true"></span></span>` +
      `</button></figcaption>` +
      `<pre${preAttrs}><code class="language-${lang}">${code}</code></pre></figure>`
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
 *  gutter 格与 `[scrollbar-gutter:stable]` 同步抵消 ⇒ 与单区形态视觉一致（列宽不跳动）。
 *
 *  问题1 偏差修正批 aj04：表头行改「flex 行 = 表头区 + gutter 占位格」（v7.8 终裁右栏），
 *  不再是「单容器 + `pr-[6px]` 补偿条」。
 */
function wrapTables(html: string): string {
  return html.replace(/<table>([\s\S]*?)<\/table>/g, (_m, inner: string) => {
    const headMatch = /<thead>([\s\S]*?)<\/thead>/.exec(inner)
    const head = headMatch === null ? '' : headMatch[0]
    const body = headMatch === null ? inner : inner.replace(headMatch[0], '')
    const cls = `${TABLE_CLASS} ${tableMinWidthClass(head)}`
    const headZone = head === ''
      ? ''
      : `<div class="${TABLE_HD_CLASS}"><div class="${TABLE_HD_SCROLL_CLASS}"><table class="${cls}">${head}</table></div>` +
        `<div class="${TABLE_GUTTER_CLASS}"></div></div>`
    return (
      `<div class="${TABLE_WRAP_CLASS}">${headZone}` +
      `<div class="${TABLE_BD_CLASS}"><table class="${cls}">${body}</table></div>` +
      `</div>`
    )
  })
}

/**
 * 问题1 偏差修正批 aj14d ③：围栏块稳定身份 —— 每个围栏按「**块序号（出现序）+ 语言**」生成
 * `data-fence-key="f{n}-{lang}"`，序号在一次渲染内由 fence 规则自增（= 围栏 token 的出现序，
 * 与产物文档序恒等；缩进/引用块里的围栏同样计入，不靠源码行扫描猜）。
 * 流式未闭合阶段与闭合之后同一块的序号/语言都不变（语言取 token 的 info 首词 —— 尾块被剥
 * `language-*` class 后仍拿得到）⇒ 「代码块 → mermaid 卡」的替换按 key 绑定，不靠位置下标。
 */
let fenceRenderSeq = 0

/** 当前围栏块的稳定身份（fence 规则内取值；`renderNow` 每块渲染前置位到该块的起始序号）。 */
function nextFenceKey(info: string | undefined): string {
  fenceRenderSeq += 1
  const lang = (info ?? '').trim().split(/\s+/)[0] ?? ''
  return `f${fenceRenderSeq}-${lang}`
}

/**
 * 批次 7C（FE-42）：净化后回填围栏槽位身份。
 *
 * <p>只认「带本帧 nonce 的 `data-fence-token`」的标签 —— 那是 fence 规则给渲染器围栏打的内部标记。
 * 模型原生 HTML 要么被白名单剥掉 `data-fence-key`，要么 token 值不匹配 ⇒ 既拿不到 key，
 * 也不消耗账本序号。回填后 token 一律删除（含模型伪造的）。
 */
function backfillFenceKeys(html: string, keys: string[], nonce: string): string {
  let next = 0
  return html.replace(/<([a-zA-Z][\w:-]*)([^>]*)>/g, (_match, tag: string, attrs: string) => {
    const claimed = attrs.includes(`data-fence-token="${nonce}"`)
    const cleaned = attrs
      .replace(/\sdata-fence-token="[^"]*"/g, '')
      .replace(/\sdata-fence-key="[^"]*"/g, '')
    if (!claimed) {
      return `<${tag}${cleaned}>`
    }
    const key = keys[next]
    next += 1
    return key === undefined
      ? `<${tag}${cleaned}>`
      : `<${tag}${cleaned} data-fence-key="${escapeHtml(key)}">`
  })
}

/* ---------- 批次 7C：>10000 字符降级提示「按条只出一次」 ----------
 *
 * `renderMarkdown` 的 `degradeNotice` 只在**某条消息的首个降级帧**给文案；后续帧为 null。
 * 记账键 = `blockKey` 去掉段号（`messageId:seg` → `messageId`），所以同一条消息的多个正文段
 * 不会各弹一次；换一条消息自然重新计（不粘滞）。FIFO 上限防长会话无限增长。
 * ------------------------------------------------------------------------- */
const degradeNotified = new Set<string>()
const DEGRADE_NOTICE_MAX = 256

/** 清空降级提示记账（测试/会话切换用）。 */
export function resetDegradeNotices(): void {
  degradeNotified.clear()
}

/** 消息键（`blockKey` 形如 `messageId:seg`；无冒号时整串就是消息键）。 */
function messageKeyOf(blockKey: string): string {
  const at = blockKey.lastIndexOf(':')
  return at > 0 ? blockKey.slice(0, at) : blockKey
}

/** 取本条消息的降级提示：首次返回文案，之后返回 null（无 blockKey ⇒ 无消息身份，不提示）。 */
function claimDegradeNotice(blockKey?: string): string | null {
  if (blockKey === undefined) {
    return null
  }
  const key = messageKeyOf(blockKey)
  if (degradeNotified.has(key)) {
    return null
  }
  if (degradeNotified.size >= DEGRADE_NOTICE_MAX) {
    const first = degradeNotified.values().next().value
    if (first !== undefined) {
      degradeNotified.delete(first)
    }
  }
  degradeNotified.add(key)
  return translate('markdown.degradeNotice')
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
 * 产物一律经白名单（新增 svg 图标与表格区标记类已登记在 ALLOWED_TAGS / RENDER_MARKER_CLASSES）。
 *
 * 问题1 偏差修正批 aj14d：`blockKey` 给定时，流式块走 75ms 防抖准入（见 `STREAM_DEBOUNCE_MS`）。
 * 性能专项（2026-10-24）：流式块另走「净化后 HTML 前缀缓存」（见下方 {@link renderNow}）。
 */
export function renderMarkdown(text: string, streaming: boolean, blockKey?: string): MarkdownRender {
  if (streaming && blockKey !== undefined) {
    const prev = streamRenders.get(blockKey)
    if (prev !== undefined && prev.text !== text) {
      const now = performance.now()
      if (now - prev.renderedAt < STREAM_DEBOUNCE_MS) {
        // 窗口内：本次增量并入下一次重绘（沿用上一帧结果），并保证静默满窗后有一次落地
        scheduleStreamFlush(STREAM_DEBOUNCE_MS - (now - prev.renderedAt))
        return prev.render
      }
    }
  }
  const render = renderNow(text, streaming, blockKey)
  if (streaming && blockKey !== undefined) {
    if (streamRenders.size >= STREAM_RENDER_MAX) {
      const first = streamRenders.keys().next().value
      if (first !== undefined) {
        streamRenders.delete(first)
      }
    }
    streamRenders.set(blockKey, { text, renderedAt: performance.now(), render })
  }
  return render
}

/* ---------- 性能专项（2026-10-24）：流式块级「净化后 HTML」前缀缓存 ----------
 *
 * 瓶颈不在解析（md.parse ≈ 1.2ms/4k）而在 DOMPurify，且流式每帧都从字符 0 全量重净化 ⇒ O(n²)。
 * 做法（Streamdown / LobeChat 同构）：用 markdown-it 顶层 token 流定出「已定型前缀」，
 * 前缀的**净化后 HTML** 按源文本前缀（`startsWith`）复用，只对新增块重新 render + sanitize。
 * 净化口径一字不改：每块仍独立过同一套白名单与 hook；缓存里只存 sanitizeHtml 之后的字符串。
 * 流式结束（streaming=false）必须整篇重渲一次（分块边界可能带来细微结构差异，如表格补齐、
 * 尾围栏闭合后的图容器/高亮恢复）。
 * ------------------------------------------------------------------------- */

interface PrefixEntry {
  /** 冻结的源文本前缀（下一帧的文本必须 `startsWith` 它才可复用）。 */
  src: string
  /** 该前缀的**净化后 HTML**。 */
  html: string
  /** 前缀内的围栏数（`data-fence-key` 续号用 —— 错号会让 mermaid 卡绑错槽位）。 */
  fences: number
  /** 构建时的降级态（跨越 >10k 阈值会换渲染实例，高亮口径不同 ⇒ 必须重切）。 */
  degraded: boolean
}

const prefixCache = new Map<string, PrefixEntry>()
const PREFIX_CACHE_MAX = 16

/** 渲染诊断（测试/基准用）：分块与全文路径的次数。 */
export interface MarkdownRenderStats {
  /** 走整篇渲染的次数。 */
  fullRenders: number
  /** 命中已冻结前缀的次数。 */
  prefixHits: number
  /** 首次为某块构建冻结前缀的次数（含降级态切换后的重切）。 */
  prefixBuilds: number
  /** 分块（前缀 + 新增块）渲染的次数。 */
  chunkRenders: number
}

const renderStats: MarkdownRenderStats = { fullRenders: 0, prefixHits: 0, prefixBuilds: 0, chunkRenders: 0 }

/** 当前渲染诊断快照（只读副本）。 */
export function getMarkdownRenderStats(): MarkdownRenderStats {
  return { ...renderStats }
}

/** 归零渲染诊断（测试/基准用）。 */
export function resetMarkdownRenderStats(): void {
  renderStats.fullRenders = 0
  renderStats.prefixHits = 0
  renderStats.prefixBuilds = 0
  renderStats.chunkRenders = 0
}

/** 已解析源码（token + env）——让「定边界」与「渲染」共用一次 parse。 */
interface ParsedSource {
  tokens: ReturnType<MarkdownItInstance['parse']>
  env: Record<string, unknown>
}

interface PrefixProbe {
  /** 可冻结前缀的字符长度（0 = 无可用前缀；-1 = 本帧必须整篇渲染）。 */
  length: number
  /** 整篇解析结果（仅在 `length <= 0` 时由调用方直接拿去渲染，省掉第二次 parse）。 */
  parsed: ParsedSource | null
}

/**
 * 探测稳定前缀（0 = 无可用前缀；-1 = 本帧必须整篇渲染）。
 *
 * <p>判据来自 markdown-it 顶层 token 流（不换解析器）：冻结边界 = **最后一个顶层块的起始行**。
 * 该行之后的内容仍可能增长（流式尾块），此前的块已定型 ⇒ 只对它们复用净化产物。
 *
 * <p>两类必须退回全文的情形：
 * <ul>
 *   <li>`env.references` 非空（引用式链接定义可出现在文末，反向影响前缀里的 `[x][ref]`）；</li>
 *   <li>前缀内含原生 HTML token（`html:true` 下未闭合标签会跨块吞内容，分块净化会就地闭合
 *       —— `<div>` + 后续段落的形态见 `markdown-render-perf.spec.ts`）。</li>
 * </ul>
 */
function probePrefix(text: string): PrefixProbe {
  if (text.includes('\r')) {
    // 行号→字符偏移按 LF 计（CRLF 由 markdown-it 归一，原串偏移不可直接复用）
    return { length: -1, parsed: null }
  }
  const env: Record<string, unknown> = {}
  let tokens
  try {
    tokens = md.parse(text, env)
  } catch {
    return { length: -1, parsed: null }
  }
  const parsed: ParsedSource = { tokens, env }
  const references = env.references
  if (references !== null && typeof references === 'object' && Object.keys(references).length > 0) {
    return { length: -1, parsed }
  }
  let lastStart = -1
  let boundaryToken = -1
  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i]
    if (token.level !== 0 || token.map === null) {
      continue
    }
    lastStart = token.map[0]
    boundaryToken = i
  }
  if (lastStart <= 0 || boundaryToken <= 0) {
    return { length: 0, parsed }
  }
  for (let i = 0; i < boundaryToken; i += 1) {
    const token = tokens[i]
    if (token.type === 'html_block') {
      return { length: -1, parsed }
    }
    // 行内原生 HTML 挂在 inline token 的 children 上（顶层 token 流里看不到 html_inline 类型）
    if (token.children?.some((child) => child.type === 'html_inline') === true) {
      return { length: -1, parsed }
    }
  }
  return { length: lineOffset(text, lastStart), parsed }
}

/** 第 `line` 行（0 起）的行首字符偏移（LF 口径；CR 已在调用方拦下）。 */
function lineOffset(text: string, line: number): number {
  let offset = 0
  for (let i = 0; i < line; i += 1) {
    const nl = text.indexOf('\n', offset)
    if (nl < 0) {
      return text.length
    }
    offset = nl + 1
  }
  return offset
}

interface ChunkResult {
  html: string
  fences: number
  mermaid: Array<{ index: number; key: string; source: string }>
}

/**
 * 单块渲染流水线（全文路径 = 一个 chunk；前缀缓存路径 = 冻结前缀 chunk + 新增块 chunk）。
 * `mermaidBase` 是该 chunk 在整篇里的 mermaid 起始下标（跨 chunk 合并后 index 仍是全局序号）。
 * `stripTailLanguage` 只对「含未闭合流式尾围栏」的那个 chunk 为真。
 */
function renderChunk(
  source: string,
  instance: MarkdownItInstance,
  options: { collectMermaid: boolean; mermaidBase: number; stripTailLanguage: boolean },
  parsed?: ParsedSource | null
): ChunkResult {
  const startSeq = fenceRenderSeq
  const ledgerStart = fenceLedger.length
  activeFenceNonce = newFenceNonce()
  const nonce = activeFenceNonce
  let html: string
  let keys: string[] = []
  try {
    // 已有整篇 token 时直接走 renderer（等价 instance.render，但省掉一次 parse）
    html =
      parsed === undefined || parsed === null
        ? instance.render(source)
        : instance.renderer.render(parsed.tokens, instance.options, parsed.env)
    keys = fenceLedger.slice(ledgerStart)
  } finally {
    // 账本只服务本次 chunk；即使渲染抛错也不把残缺 key 留给下一块
    fenceLedger.length = ledgerStart
  }
  const fences = fenceRenderSeq - startSeq
  html = wrapTables(html)
  if (options.collectMermaid) {
    // G4：非 mermaid 围栏包成图容器（语言标签 + 复制按钮）
    html = wrapFenceFigures(html)
  }
  if (options.stripTailLanguage) {
    // 未闭合尾围栏：剥掉尾块 language-* class（纯文本口径，闭合后二次渲染才恢复高亮）
    const at = html.lastIndexOf('class="language-')
    if (at >= 0) {
      html = html.slice(0, at) + html.slice(at).replace(/class="language-[^"]*"/, 'class=""')
    }
  }
  // 批次 7C：净化期 key 被白名单剥掉，这里只给「渲染器 token 认领的 pre」按出现序回填
  const safe = backfillFenceKeys(sanitizeHtml(html), keys, nonce)
  const mermaid: Array<{ index: number; key: string; source: string }> = []
  if (options.collectMermaid) {
    // 登记闭合 mermaid 围栏（按出现序）；源码块本身留在净化后 HTML 里。
    // 只认带 key 的 pre ⇒ 模型原生 HTML 的 language-mermaid 预块不进清单。
    let match: RegExpExecArray | null
    mermaidIndexRe.lastIndex = 0
    while ((match = mermaidIndexRe.exec(safe)) !== null) {
      const key = /data-fence-key="([^"]*)"/.exec(match[1])?.[1] ?? ''
      if (key === '') {
        continue
      }
      mermaid.push({
        index: options.mermaidBase + mermaid.length,
        key,
        source: unescapeHtml(match[2])
      })
    }
  }
  return { html: safe, fences, mermaid }
}

/**
 * 单块渲染本体（防抖准入之外的全部逻辑）。
 *
 * <p>流式且给了 `blockKey` 时走前缀缓存；否则（非流式 / 无 key）整篇渲染并清掉该块的旧前缀
 * —— 流式结束必须整篇重渲一次，校正分块边界带来的细微结构差异。
 */
function renderNow(text: string, streaming: boolean, blockKey?: string): MarkdownRender {
  const fenceOpen = streaming && hasUnclosedFence(text)
  // 指纹 = 尾围栏 info 行之后的代码体（与 markdown-it 传给 highlight 的 code 逐字相等）
  let tailSource: string | null = null
  if (fenceOpen) {
    const tailRaw = text.slice(text.lastIndexOf('```') + 3)
    const nl = tailRaw.indexOf('\n')
    tailSource = nl >= 0 ? tailRaw.slice(nl + 1) : ''
  }
  const useDegraded = text.length > DEGRADE_AT_CHARS
  // 批次 7C：提示按「条」记账 —— 本条消息的首个降级帧给文案，后续帧为 null；短消息恒 null
  const degradeNotice = useDegraded ? claimDegradeNotice(blockKey) : null
  const instance = useDegraded ? mdDegraded : md
  const cacheKey = streaming && blockKey !== undefined ? blockKey : null
  try {
    if (cacheKey === null) {
      if (blockKey !== undefined) {
        // 流式结束/非流式：整篇重渲，前缀缓存作废
        prefixCache.delete(blockKey)
      }
      renderStats.fullRenders += 1
      fenceRenderSeq = 0
      tailFenceFingerprint = tailSource
      const chunk = renderChunk(text, instance, {
        collectMermaid: !useDegraded && !streaming,
        mermaidBase: 0,
        stripTailLanguage: fenceOpen
      })
      return { html: chunk.html, mermaid: chunk.mermaid, degraded: useDegraded, degradeNotice }
    }
    const probe = probePrefix(text)
    const boundary = probe.length
    if (boundary <= 0) {
      // 只有一个顶层块（或本帧不可切）：整篇渲染；结果不进前缀缓存（下一帧边界可能前移）
      renderStats.fullRenders += 1
      fenceRenderSeq = 0
      tailFenceFingerprint = tailSource
      const chunk = renderChunk(
        text,
        instance,
        { collectMermaid: false, mermaidBase: 0, stripTailLanguage: fenceOpen },
        probe.parsed
      )
      return { html: chunk.html, mermaid: chunk.mermaid, degraded: useDegraded, degradeNotice }
    }
    let prefix: PrefixEntry
    const cached = prefixCache.get(cacheKey)
    if (
      cached === undefined ||
      cached.degraded !== useDegraded ||
      cached.src.length > boundary ||
      !text.startsWith(cached.src)
    ) {
      renderStats.prefixBuilds += 1
      fenceRenderSeq = 0
      // 前缀里的围栏恒为闭合块，不得被尾围栏指纹抑制高亮
      tailFenceFingerprint = null
      const built = renderChunk(text.slice(0, boundary), instance, {
        collectMermaid: false,
        mermaidBase: 0,
        stripTailLanguage: false
      })
      prefix = { src: text.slice(0, boundary), html: built.html, fences: built.fences, degraded: useDegraded }
    } else if (cached.src.length < boundary) {
      // 边界前移（新块已定型）：只渲染「新完成的块」，按序追加到已冻结的净化产物后面
      renderStats.prefixHits += 1
      fenceRenderSeq = cached.fences
      tailFenceFingerprint = null
      const gap = renderChunk(text.slice(cached.src.length, boundary), instance, {
        collectMermaid: false,
        mermaidBase: 0,
        stripTailLanguage: false
      })
      prefix = {
        src: text.slice(0, boundary),
        html: cached.html + gap.html,
        fences: cached.fences + gap.fences,
        degraded: useDegraded
      }
    } else {
      renderStats.prefixHits += 1
      prefix = cached
    }
    // 刷新 LRU 位置（活跃增长的前缀不被最旧淘汰误伤）
    prefixCache.delete(cacheKey)
    if (prefixCache.size >= PREFIX_CACHE_MAX) {
      const first = prefixCache.keys().next().value
      if (first !== undefined) {
        prefixCache.delete(first)
      }
    }
    prefixCache.set(cacheKey, prefix)
    renderStats.chunkRenders += 1
    // 续号：键值在整篇内连续且唯一（mermaid 卡按 key 绑槽位）
    fenceRenderSeq = prefix.fences
    tailFenceFingerprint = tailSource
    const rest = renderChunk(text.slice(prefix.src.length), instance, {
      collectMermaid: false,
      mermaidBase: 0,
      stripTailLanguage: fenceOpen
    })
    return { html: prefix.html + rest.html, mermaid: rest.mermaid, degraded: useDegraded, degradeNotice }
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

/** mermaid 模块惰性初始化（securityLevel:'strict' / htmlLabels:false / suppressErrorRendering:true / 图源硬上限）。 */
let mermaidPromise: Promise<typeof import('mermaid')> | null = null

function loadMermaid(): Promise<typeof import('mermaid')> {
  mermaidPromise ??= import('mermaid')
    .then((mod) => {
      mod.default.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        htmlLabels: false,
        suppressErrorRendering: true,
        maxTextSize: MERMAID_MAX_TEXT_SIZE,
        maxEdges: MERMAID_MAX_EDGES
      })
      return mod
    })
    .catch((error: unknown) => {
      // 失败不缓存（与 spreadjs.ts 同纪律）：动态 import 抖动/失败后下一次调用重试，
      // 而不是把一次失败永久钉成"mermaid 不可用"。
      mermaidPromise = null
      throw error
    })
  return mermaidPromise
}

/** mermaid 渲染缓存（源码指纹 → 净化后 SVG），失败不缓存。 */
const mermaidCache = new Map<string, string>()

/** mermaid 水合的 idle 等待上限（ms）：浏览器迟迟不空闲时到点也必须开工，避免图永远不现身。 */
export const MERMAID_IDLE_TIMEOUT_MS = 1_000

/** 等一次浏览器 idle（无 rIC 环境退 `setTimeout(0)`）——组件「可见」门控之后的第二层门控。 */
function whenIdle(): Promise<void> {
  return new Promise((resolve) => {
    const ric = (globalThis as unknown as {
      requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number
    }).requestIdleCallback
    if (typeof ric === 'function') {
      ric(() => resolve(), { timeout: MERMAID_IDLE_TIMEOUT_MS })
      return
    }
    setTimeout(resolve, 0)
  })
}

/**
 * 渲染单个 mermaid 图源（围栏闭合后一次性调用；render error / 等待超时 ⇒ 返回 null，
 * 调用方保留源码块——§三.7 失败兜底）。返回净化后 SVG 字符串。
 *
 * <p>批次 7C 两条硬口径：
 * <ul>
 *   <li>**进入 idle 才开工**：组件侧 IntersectionObserver 只管「可见」，这里再等一次
 *       `requestIdleCallback`（1s 超时兜底）⇒ 「可见且 idle」双门控，不在首屏/流式高负载时抢主线程。</li>
 *   <li>**3s `Promise.race` 是等待预算、不是中断**：JS 单线程，mermaid 一旦进入同步布局/渲染，
 *       定时器根本无法插进去抢占；超时只让**等待方**拿到 null。迟到的 SVG 会被 Promise.race
 *       丢弃（不入缓存、不回填槽位），源码块保留，下一次调用仍会重试。</li>
 * </ul>
 */
export async function renderMermaidSvg(source: string): Promise<string | null> {
  const key = source
  const hit = mermaidCache.get(key)
  if (hit !== undefined) {
    return hit
  }
  try {
    await whenIdle()
    const mod = await loadMermaid()
    const id = `mmd-${Math.abs(hashCode(key)).toString(36)}`
    let timer: ReturnType<typeof setTimeout> | null = null
    const timeout = new Promise<never>((_resolve, reject) => {
      timer = setTimeout(() => reject(new Error('mermaid timeout')), MERMAID_TIMEOUT_MS)
    })
    try {
      const result = (await Promise.race([mod.default.render(id, source), timeout])) as { svg: string }
      const svg = sanitizeSvg(result.svg)
      if (mermaidCache.size >= MERMAID_CACHE_MAX) {
        const first = mermaidCache.keys().next().value
        if (first !== undefined) {
          mermaidCache.delete(first)
        }
      }
      mermaidCache.set(key, svg)
      return svg
    } finally {
      // 超时/成功都清掉等待计时器（迟到结果已被 race 丢弃，不会走到这里）
      if (timer !== null) {
        clearTimeout(timer)
      }
    }
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
