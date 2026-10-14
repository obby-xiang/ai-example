/**
 * R-163 T1：AI 正文唯一渲染链路 —— markdown-it → DOMPurify 白名单净化 → v-html
 * （决策卡 §三.1/§三.3，XSS 口径变更的安全面仅限本链路；FormRenderer 永不 v-html 不变）。
 *
 * - 合规四件套（锁定）：markdown-it@15.0.0 / dompurify@3.4.14 / highlight.js@11.11.1 / mermaid@11.16.1
 *   （mermaid@11.16.1 deps dompurify ^3.3.3，接受 3.4.14 —— 施工前兼容已核）。
 * - 白名单（§三.3 实际集合）：标签 p/h1-6/ul/ol/li/table/thead/tbody/tr/th/td/code/pre/
 *   blockquote/em/strong/del/a/hr/br/img；span 仅 class="hljs-*"、code 仅 class="language-*"（+hljs）；
 *   figure/figcaption/button（代码块图容器，button 仅 type="button"）、div 仅 class="md-table-scroll"
 *   （表格滚动 wrapper，R-163D D1）；mermaid 输出 SVG 走独立 SVG profile 净化。属性仅 a.href
 *   （DOMPurify 默认协议过滤）/class/src/alt；禁 style / on* 事件 / iframe / script / 表单控件。
 * - 流式策略（§三.2 + m1）：hljs 高亮 memo（已完成围栏不重解析高亮 = 块级 memo 的落点）；
 *   未闭合围栏（流式尾块）渲染为纯文本规避闪烁，闭合后二次渲染才高亮/进 mermaid；
 *   长文 >10k 字符降级（关高亮与 mermaid，结构照渲）；单次渲染超耗时预算（24ms）后续渲染同降级。
 * - mermaid（§三.7）：securityLevel:'strict' + htmlLabels:false + suppressErrorRendering:true，
 *   渲染产物 SVG 过 DOMPurify SVG profile；失败（render error/超时 3s）保留源码块。
 */
import MarkdownIt from 'markdown-it'
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
 *  R-163D D1：div 为表格滚动 wrapper（仅限 class="md-table-scroll"，渲染层生成，hook 收窄）。 */
const ALLOWED_TAGS = [
  'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'ul', 'ol', 'li', 'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'code', 'pre', 'blockquote', 'em', 'strong', 'del', 'a', 'hr', 'br', 'img', 'span',
  'figure', 'figcaption', 'button', 'div'
]
const ALLOWED_ATTR = ['class', 'href', 'src', 'alt', 'type']

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

/**
 * 流式尾围栏的高亮抑制（决策卡 §三.2「围栏闭合前纯文本规避闪烁」）：
 * 渲染前置入"尾围栏内容指纹"，highlight 回调命中该指纹即返回纯文本（不高亮）；
 * mermaid 尾围栏同理保持源码块，闭合后才进 mermaid 渲染管线。
 */
let tailFenceFingerprint: string | null = null

DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'SPAN') {
    const cls = node.getAttribute('class') ?? ''
    node.setAttribute(
      'class',
      cls.split(/\s+/)
        .filter((c) => c.startsWith('hljs-') || c === 'hljs' || c === 'lg' || c === 'hlib')
        .join(' ')
    )
  }
  if (node.tagName === 'CODE' || node.tagName === 'PRE') {
    const cls = node.getAttribute('class') ?? ''
    node.setAttribute(
      'class',
      cls.split(/\s+/).filter((c) => c.startsWith('language-') || c.startsWith('hljs')).join(' ')
    )
  }
  if (node.tagName === 'DIV') {
    // R-163D D1：div 仅限表格滚动 wrapper（markdown-it 正文渲染不产出其他 div）
    const cls = node.getAttribute('class') ?? ''
    if (cls !== 'md-table-scroll') {
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

const mermaidIndexRe = /<pre><code class="language-mermaid">([\s\S]*?)<\/code><\/pre>/g

/**
 * R-163B G4：围栏 → 代码块图容器（demo :133-139）——figcaption（语言名 + highlight.js +
 * 复制按钮）+ pre 内部横滚。闭合 mermaid 围栏除外（保留裸源码块，由 mermaid 卡接管）。
 */
const fenceFigureRe = /<pre( class="[^"]*")?><code class="language-(?!mermaid)([^"]*)">([\s\S]*?)<\/code><\/pre>/g

function wrapFenceFigures(html: string): string {
  return html.replace(
    fenceFigureRe,
    (_m, preCls: string | undefined, lang: string, code: string) =>
      `<figure class="code"><figcaption><span class="lg">${escapeHtml(lang)}</span>` +
      `<span class="hlib">highlight.js</span><button type="button" class="copy">复制</button></figcaption>` +
      `<pre${preCls ?? ''}><code class="language-${lang}">${code}</code></pre></figure>`
  )
}

/**
 * R-163D D1：表格包滚动 wrapper（推翻 R-163C H2 的 table{display:block} 方案——块盒宽恒=容器宽，
 * 滚动永不触发）。table 回 display:table + width:max-content;min-width:100%：窄表撑满、
 * 宽表列宽随内容，超宽仅在 wrapper 内横滚，不撑爆消息容器（映射表 §五「超 demo」方案）。
 */
function wrapTables(html: string): string {
  return html
    .replace(/<table>/g, '<div class="md-table-scroll"><table>')
    .replace(/<\/table>/g, '</table></div>')
}

/**
 * AI 正文渲染唯一入口。`streaming` = 该正文块仍处流式（未闭合围栏按纯文本、不进 mermaid）。
 * 结果已 DOMPurify 净化，调用方直接 v-html。
 * 闭合 mermaid 围栏：**保留** `language-mermaid` 源码块（= 失败兜底与「查看源码」同体），
 * 同时在返回值的 mermaid 列表登记源码——组件异步渲染 SVG 成功后替换为 mermaid 卡。
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
