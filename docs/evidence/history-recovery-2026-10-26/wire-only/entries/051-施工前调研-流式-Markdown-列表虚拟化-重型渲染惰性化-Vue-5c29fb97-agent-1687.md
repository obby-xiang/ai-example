> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1687` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：26687 B / 16444 字符 / 228 行；行指纹覆盖率（仓内 + 落盘语料）4.9%
> - 类别：**正式独立报告**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# 施工前调研：流式 Markdown、列表虚拟化、重型渲染惰性化（Vue 3 + element-plus）

## 0. 先把我们这套栈的事实钉住（含实测）

**栈确认**（`frontend/package.json`、`frontend/src/utils/markdown.ts:1-40`）：

| 项 | 现状 |
|---|---|
| 框架 | Vue 3.5.13 + element-plus 2.14.7 + Pinia 2.3 + Vite 6 + vitest 4 |
| Markdown | `markdown-it@15.0.0`（`html:true`、`linkify:true`），两份实例（正常/降级），排版类由渲染层逐标签注入 |
| 净化 | `dompurify@3.4.14`，白名单 + `afterSanitizeAttributes` hook 收窄 span/code/div/svg |
| 高亮 | `highlight.js@11.11.1`，**整包静态导入**（`lib/index.js` 注册 192 种语言） |
| Mermaid | `mermaid@11.16.1`，**已动态 import**、`securityLevel:'strict'`、3s 超时、SVG 单独 profile 净化、源码指纹 LRU（上限 20） |
| 流式策略 | 75ms 防抖（`STREAM_DEBOUNCE_MS`，≈14 次/秒）+ 未闭合围栏纯文本 + `data-fence-key` 稳定身份 + hljs memo（200）+ 消息级 `mdCache`（128） |
| 降级 | `DEGRADE_AT_CHARS=10k` 或单次渲染 > `RENDER_BUDGET_MS=24ms` ⇒ **模块级粘滞** `degraded=true`（`markdown.ts:179`，刷新页签才归零） |
| 列表 | `AiPanel.vue:202` 直接 `v-for="(m, mi) in ai.messages"`，**无虚拟化**；滚动跟随是自研 RO + `pinBottom` + `pinGuard` + 意图闸（`AiPanel.vue:1272`、`:3017`、`:3446`） |

**实测（vitest + jsdom，median of 15，临时 spec 已删除）**：

| 正文长度 | `renderMarkdown` 全量 | markdown-it 解析 | DOMPurify 净化 |
|---|---|---|---|
| 1k | 11.4 ms | — | — |
| 2k | 16.0 ms | — | — |
| 4k | 29.3 ms | 1.24 ms | 13.3 ms（原始 HTML 7,980 字符） |
| 8k | 55.5 ms | 1.70 ms | 19.8 ms（原始 HTML 15,960 字符） |
| 9.5k | 67.9 ms | — | — |

关键结论（不是常识推论，是这套代码的结构性事实）：

1. **瓶颈不是 markdown-it，是 DOMPurify**。我们的渲染层给每个标签注入了大量 Tailwind 工具类（4k 正文产出约 108KB HTML，是裸 markdown-it 产物的 13 倍），净化成本随 HTML 体量走。所以任何"块级缓存"如果缓存的是**未净化 HTML**，收益会很小；必须缓存**净化后 HTML**。
2. **已完成消息已经是 O(1)**（`mdCache` 键 = 消息+段+长度+`f`），不需要动。
3. **流式消息每次都从字符 0 重解析 + 重净化全文**，成本随长度线性增长、按帧重复，是 O(n²) 的真实来源；75ms 防抖只是把常数压到 14 次/秒。
4. **24ms 预算很容易被冷启动打穿，而且打穿后整页签永久失去高亮与 mermaid**。我实测冷启动首渲 50–100ms（jsdom，含 hljs/DOMPurify 预热），`degraded` 一次就翻。这是比"慢一点"更伤用户体验的隐性缺陷。
5. 另外，mermaid 的渲染成本**根本不在 24ms 同步预算里**（它是 `nextTick` 后异步 `await mermaid.render`），却被这个降级开关一起关掉 —— 属于误伤。

（jsdom 绝对值偏高，请按相对比例看；真机建议再用 Chrome Performance 复测。）

---

## 1. 流式 Markdown 增量渲染

### 结论先行

1. **业界没有"增量 markdown-it"这种现成能力**。markdown-it 是纯函数式整文档解析器，没有跨 chunk 的 parser 状态。所有成熟方案的本质都是同一件事：**把文档切成顶层块，已完成的块冻结并缓存，每帧只重算尾块**（"块级 memo"），而不是真的做 token 级增量解析。
2. **LobeChat 有按 block 切分缓存，而且是两层**：`@lobehub/streamdown` 里用 `marked.lexer` 把文本切成顶层块、把"不可能再增长的稳定前缀"冻结（`blockLexer.ts`），每个块再用 `React.memo` 包一层（`CachedMarkdown`）；外层 `@lobehub/ui` 的 `StreamdownRender` 只是把配置透传进去。它的冻结类型**明确排除了 list 和缩进代码块**，因为 `- a\n\n` 后面再来 `- b` 会合并回同一个 list。
3. **Vercel 生态的答案是 Streamdown**（`vercel/ai-chatbot` 的 `package.json` 已经是 `streamdown@^2.3.0` + `@streamdown/mermaid`）：block-level memoization + 用 `remend` 自动补齐未闭合语法 + 稳定 key + React Transition。它**不是** react-markdown 的包装，而是自己搭的 remark→rehype 管线。
4. **Open WebUI 是反例也是重要参照**：它用 `marked.lexer` 全量重解析，但用 `requestAnimationFrame` 节流，并且把 token 树直接渲染成组件（`MarkdownTokens.svelte`），不是拼 HTML 字符串。它没有块级缓存，说明"rAF + 全量"在聊天量级确实能跑。
5. **LangChain 官方前端指南给了免过度优化的量化口径**：`marked` 约 1MB/s，5KB 消息 <5ms，**只在 >50KB 且观察到卡顿时**才建议上 rAF 节流或增量解析。这条要作为我们判断 ROI 的基线。
6. **ChatGPT 的解析实现没有可核实的一手资料**。能核实的只有产品行为（逐字平滑输出、代码块 Preview 门控）。第三方文章断言它"每 token 全量重解析"，属推断，不建议当证据用。

### 各家机制（一手源码/官方文档）

| 项目 | 机制 | 证据 |
|---|---|---|
| **Vercel Streamdown** | 顶层块切分 + 每块 `React.memo` + `remend` 补齐未闭合块 + 解析结果按块缓存 | [memoization 文档](https://streamdown.ai/docs/memoization)、[GitHub](https://github.com/vercel/streamdown) |
| **LobeChat `@lobehub/streamdown`** | `marked.lexer` 切块；`FROZEN_TYPES = {blockquote, code, heading, hr, paragraph, table}`，仅当"该块后面紧跟 `space` token"才冻结；只对尾部重新 `remend` + lex | [blockLexer.ts](https://github.com/lobehub/streamdown/blob/main/src/blockLexer.ts)、[README](https://github.com/lobehub/streamdown) |
| **Lobe UI** | `StreamdownRender` → `@lobehub/streamdown`，块级 memo 由库内 `CachedMarkdown` 承担 | [StreamdownRender.tsx](https://github.com/lobehub/lobe-ui/blob/master/src/Markdown/SyntaxMarkdown/StreamdownRender.tsx) |
| **Open WebUI** | `marked.lexer` 全量 + `requestAnimationFrame` 节流 + token 组件树 | [Markdown.svelte](https://github.com/open-webui/open-webui/blob/main/src/lib/components/chat/Messages/Markdown.svelte) |
| **markstream-vue** | 基于 `markdown-it-ts` 分支的 `md.stream.parse`（有跨 chunk 状态）+ 虚拟窗口（默认 `max-live-nodes=220`） | [README](https://github.com/Simon-He95/markstream-vue)、[对比文档](https://github.com/Simon-He95/markstream-vue/blob/main/docs/compare/marked-markdown-it.md) |
| **StreamMD（第三方）** | 自研行级状态机，`prevLength` 只处理 delta，未完成行单独 buffer | [架构文章](https://dev.to/jvoltci/i-made-streaming-markdown-300x-faster-heres-the-architecture-4a9f) |

### 方案对比（针对我们这套栈）

| 方案 | 每次更新解析量 | 改造量 | 风险 | 适配结论 |
|---|---|---|---|---|
| **A. 自研 token 级增量解析**（换 `streaming-markdown` / StreamMD / markstream 的 `md.stream.parse`） | O(新增 token) | 换解析器 + 重写渲染/白名单/表格包装/围栏 key | **极高**：212 条 `ai-panel-parts-render` 用例 + 10 条 XSS 用例全绑在 markdown-it 产物上；`html:true` + DOMPurify hook 的收窄逻辑也得重做 | **不做** |
| **B. 块级切分 + 已完成块"净化后 HTML"缓存**（Streamdown/LobeChat 的同构做法） | O(尾块) | 中：在 `renderMarkdown` 外层加边界判定 + 前缀缓存，markdown-it 实例不动 | 中：引用式链接、raw HTML 跨块、围栏序号偏移、per-chunk 净化 | **推荐** |
| **C. 仅节流**（现状 75ms；可改 rAF） | O(全文)，≤14 次/秒 | 0（已实现） | 低 | **保留为兜底**；不改也能用，但长回答抖动 + 降级误伤仍在 |

### 我们这套栈怎么接（方案 B 的落地形态）

核心洞察：**`md.parse()` 很便宜（实测 1.2ms/4k），贵的是 `render + sanitize`。** 所以不需要自己写 markdown 扫描器，直接用 markdown-it 自己的 token 流做边界判定：

```ts
// 1) 每次更新先 parse 一遍（便宜），拿到顶层 token 的 map（行号区间）
const env: Record<string, unknown> = {}
const tokens = md.parse(text, env)

// 2) 引用式链接是文档级语义，出现即放弃分块（退回全文渲染，罕见）
if (env.references && Object.keys(env.references).length > 0) return renderNow(text, streaming)

// 3) 冻结边界 = 除"最后一个顶层块"之外的所有块结尾
//    markdown-it 的 list/blockquote token 的 map 覆盖整个容器 ⇒ 天然规避
//    LobeChat 的「list 会继续长」问题：list 是最后一个 token 时我们本来就不冻结它
const boundaryLine = topLevelTokens.at(-1)?.map?.[0] ?? 0   // 最后一个顶层块的起始行

// 4) 前缀缓存按「源文本」而不是长度做键（防止分叉）
type Prefix = { src: string; html: string; fenceCount: number }
const cached = prefixCache.get(blockKey)
let prefixHtml: string, prefixFence: number
if (cached && text.startsWith(cached.src) && cached.src.length <= cutOffset) {
  // 只渲染「新增完成块」这一小段，append 到缓存
  const delta = text.slice(cached.src.length, cutOffset)
  prefixFence = cached.fenceCount
  fenceRenderSeq = prefixFence                 // 围栏序号全局续号，不是每次归零
  const deltaHtml = sanitizeHtml(wrapFenceFigures(wrapTables(md.render(delta))))
  prefixHtml = cached.html + deltaHtml
  prefixCache.set(blockKey, { src: text.slice(0, cutOffset), html: prefixHtml, fenceCount: ... })
} else {
  // 冷启动：前缀一次性渲染（这条路径的成本可接受，因为它只发生一次）
}

// 5) 尾块单独 render + sanitize；streaming 时尾围栏仍走纯文本口径
const tailHtml = sanitizeHtml(wrapTables(md.render(text.slice(cutOffset))))
return { html: prefixHtml + tailHtml, mermaid: [...] }
```

必须处理/验证的边界（每一条都要有测试）：

- **围栏序号**：`fenceRenderSeq` 改成"按前缀围栏数续号"，否则 `data-fence-key` 会重复，mermaid 卡绑定错位。
- **引用式链接**：`env.references` 非空 → 退回全文（一行开关）。
- **raw HTML 跨块**：`html:true` 下 `<div>` 跨空行可能改变解析结果；检测到块内未闭合标签就退回全文，或依赖白名单本来只放行极少数标签，接受差异。
- **流式结束必须整篇重渲一次**：`streaming=false` 时走一次完整 `renderNow`（结果进 `mdCache`，永久缓存）。这样块边界造成的细微结构差异（松散列表间距等）在消息完成时自动校正 —— 和 Streamdown 的 `final` 语义同源。
- **净化口径不变**：每个 chunk 单独 `sanitizeHtml`，再拼接；绝不缓存未净化 HTML，也绝不对拼接结果再"省一次净化"。
- **mermaid 登记**：改为按 chunk 收集再合并，`index` 用全局序号。

预期收益（按实测比例推）：4k 正文每次更新从 ~29ms（jsdom）降到 ~3–5ms（parse 1.2ms + 尾块 render/sanitize 2–4ms），约 **5–10 倍**；同时把"单次渲染 >24ms"的出现概率压到接近 0，间接解决降级误伤。

---

## 2. 消息列表虚拟化（Vue 3）

### 结论先行

1. **首选 `@tanstack/vue-virtual`（3.13.48，2026-10-22 发布，依赖 `virtual-core@3.19.7`）**。它刚刚把"聊天/反向列表"做成一等能力：`anchorTo:'end'`、`followOnAppend`、`scrollEndThreshold`、`scrollToEnd()`、`isAtEnd()`、`measureElement` 动态测量。这正是我们"倒序/贴底/流式变高/历史前置"的四个需求，且是 headless —— 不抢我们的 DOM 和样式。我核过发布物：这些选项在 3.19.7 的 core 里确实存在，Vue 适配层只是薄包装。
2. **次选 `virtua/vue`（0.53.6）**：更小（~4.6KB gzip）、零配置动态测量、`shift` 专为"向前置历史"设计、`keepMounted` 可钉住末条。Element Plus X 的 `BubbleList` 就是拿它做虚拟滚动的，说明 Vue 聊天场景已被验证。代价：**没有内建贴底跟随**，要自己写（BubbleList 也是自己写的状态机）。
3. **`vue-virtual-scroller` 不推荐新项目**：动态高度已知问题多（`#221` `scrollToBottom()` 触发 rAF 无限递归、`#696` 动态高度下 `scrollToItem` 定位错、`#285` before 槽高度变化直接破坏滚动器），且没有 prepend 锚定。
4. **element-plus 的虚拟化不够用**：公开的只有 `el-table-v2` / `el-tree-v2` / `el-select-v2`；内部的 `virtual-list`（`FixedSizeList` / `DynamicSizeList`）**没有从包入口导出**（我查了 `element-plus/es/index.mjs`，只有 `TableV2/TreeV2/SelectV2`），且 API 是 `itemSize/estimatedItemSize + total + height` 的估算模型，没有 DOM 实测、没有贴底/前置锚定。
5. **Vue 侧没有"ChatGPT-Next-Web 的 Vue 版"这种权威参照**。最接近的两个真实参照是：Element Plus X `BubbleList`（virtua + AT_BOTTOM 状态机 + 流式跟随）和 markstream-vue（`max-live-nodes` 虚拟窗口）。LobeChat 是 React，但它的块缓存设计可以平移，虚拟化不行。

### 库对比（2026-10 现状）

| 库 | 版本/维护 | 体积(gzip) | 动态高度 | 前置/倒序锚定 | 流式贴底 | 结论 |
|---|---|---|---|---|---|---|
| `@tanstack/vue-virtual` | 3.13.48（2026-10-22） | ~7.3KB | `measureElement` + RO | `anchorTo:'end'` + 稳定 `getItemKey` | `followOnAppend` + `scrollEndThreshold` + `isAtEnd()` | **首选** |
| `virtua/vue` | 0.53.6（2026-10-21） | ~4.6KB | 自动测量 + `keepMounted` | `shift`（前置保位） | 无内建，需自管 | 次选 |
| `vue-virtual-scroller` | 3.0.5（2026-08） | ~10.4KB | `DynamicScroller`（估算+测量） | 无 | `scrollToBottom` 有已知 rAF 递归 | 不推荐 |
| element-plus 内置 `virtual-list` | 2.14.7 | 随库 | 固定/估算 `itemSize` | 无 | 无 | 不够用（且未公开导出） |
| `vue-virtual-scroll-list` | 2.3.5（2023-05） | ~4.9KB | 估算缓存，不实测 | 无 | 无 | 不用 |
| `vueuc`（Naive UI 内部） | 0.4.66 | ~8.6KB | 有 | 无 | 无 | 私有部件，不碰 |

（体积与特性矩阵参考[第三方对比页](https://pdanpdan.github.io/virtual-scroll/compare/)，注意它来自竞品，数字当参考不当结论；TanStack 的聊天能力以[官方 Chat 文档](https://tanstack.com/virtual/latest/docs/chat)与[发布博客](https://tanstack.com/blog/tanstack-virtual-chat)为准。）

### 聊天场景的坑（逐条对应我们代码）

1. **动态高度抖动**：块高度靠 DOM 实测，图片/代码块/字体回流都会改高度。virtua FAQ 明确建议：给图片/视频预留 `width/height` 或 `aspect-ratio`，否则每次重新挂载都会再跳一次。我们的 markdown 图片没有尺寸信息（`MD_IMG_CLASS` 只有 `max-w-full`），这是必踩项。
2. **流式末条不断变高**：TanStack 的 `anchorTo:'end'` 在"变高前已贴底"时会按 delta 补偿；virtua 需要自己在内容变化时 `scrollToIndex(last, {align:'end'})`。我们现有的 `ResizeObserver + pinBottom + pinGuard` 会与库的滚动管理**打架**，必须二选一（建议库接管滚动，我们只保留"回到底部"按钮与未读计数）。
3. **前置历史保位**：TanStack 要求稳定 key（消息 id），不能用下标。我们 `:key="m.id"` 已经满足；但 store 目前是整表替换（`loadHistory` 一次性读全量），如果以后加分页，必须用 `anchorTo:'end'`。
4. **DOM 卸载的副作用（我们特有，最危险）**：
   - `hydrateMermaid` 是 `querySelector` + `replaceWith` 把 `pre` 换成卡片的**命令式 DOM 操作**；虚拟化卸载后卡片消失，重新挂载时 `markdownHtml` 命中 `mdCache` **不会再次触发 hydrate**（`AiPanel.vue:1928` 的命中分支直接 return），用户会看到裸 mermaid 源码。
   - 表单/门卡（`FormRenderer`、`el-select` 展开态、焦点、局部滚动位置）会随卸载丢失。`form-draft` 只保了草稿值，不保交互态。
   - `setUserBodyRef` 的"单一 measure"、`msgResizeObserver` 观测内容层、`querySelector('[data-testid=msg-bubble-*]')` 全部依赖"消息 DOM 常驻"。
5. **测试面**：`tests/ai-panel-parts-render.spec.ts` 有 212 个用例、`ai-panel-scroll-follow.spec.ts` 4 个，jsdom 里高度恒为 0，虚拟列表会**一个 item 都不渲染**，这些断言会大面积红。可行做法是"阈值开关"：`messages.length > 80` 才启用虚拟化，测试数据量小，自动走旧路径。
6. **iOS Safari**：virtua 的反向滚动在 iOS 上需要用户先松手；TanStack 官方也把 iOS 列为部分支持。我们桌面后台为主，可接受，但要记录。

### 接入成本与门槛

- **接入成本**：TanStack 路线约 **3–5 人日**（含把 `pinBottom/onMsgScroll/pinGuard` 换成 `isAtEnd/scrollToEnd`、末条 `keepMounted` 等价处理、mermaid 重挂载修复、测试开关），外加 1–2 天回归。
- **收益门槛**：只有当 P95 会话消息数 > ~80、或首屏 DOM 节点 > ~5k 时才值得。当前没有这个数据，**建议先量化再决定**（在真机上跑一次 100 条消息的 panel，看 DOM 节点数、首屏 TTI、滚动 FPS）。
- **零依赖备选**：给消息行加 `content-visibility:auto` + `contain-intrinsic-size`。实现成本几乎为零，但它会让 `scrollHeight` 变成估算值，**直接破坏我们基于 `bottomDist()` 的贴底判定**，需要实测后谨慎使用。

---

## 3. 代码块高亮与 mermaid 的惰性化

### 结论先行

1. **聊天流式场景真正被采用的是"流式期不高亮，稳定后再高亮"**，不是"每帧高亮"。assistant-ui 的 Shiki 高亮组件写得很直白：`streaming=true` 时**完全跳过 tokenization**，直接渲染 `<pre><code>` 原文；`false` 后再用 Shiki 分词，并且默认 **150ms 延迟**（吸收平滑逐字动画的尾巴）。我们现在的"未闭合围栏纯文本 + 闭合后二次渲染 + 75ms 窗口"方向是对的，只是没有"闭合后延迟一次"的收尾。
2. **Shiki 系已有官方增量高亮**：`@shikijs/stream` 的 `CodeToTokenTransformStream` 只对新增 chunk 分词，必要时用 recall token 回退；还提供 Vue 版 `ShikiStreamRenderer` / `ShikiCachedRenderer`。如果将来要换高亮引擎，这是"流式"这一维的正解。
3. **Web Worker 跑 highlight.js 在实际聊天产品里很少见**。高亮结果最终还是要回主线程写 DOM，hljs 单块成本又不高，收益/复杂度不划算。真正被搬到 worker 的是**解析类**工作：Kimi Code 官方更新日志明确写"KaTeX 数学公式与 Mermaid 的解析改到 Web Worker 执行，流式输出时界面更跟手"。但注意：**Mermaid 的布局/测量依赖 DOM，完整 `render` 不能进 worker**，能进 worker 的只是语法解析。
4. **mermaid 的关键约束是体积 + 阻塞**：约 315KB gzip（v11.14），且**不可有效 tree-shake**（图解析器在模块初始化时注册），必须 dynamic import（我们已做）。渲染本身 50–200ms/图，10 张图串行就是 1–2 秒主线程占用。业界缓解手段优先级：**可见才渲染（IntersectionObserver）> 串行/限并发 > 结果缓存 > 收紧 `maxTextSize/maxEdges`**。

### 我们现状盘点（哪些已经做对）

| 能力 | 现状 | 评价 |
|---|---|---|
| mermaid 动态 import | `loadMermaid()`，`startOnLoad:false`、`strict` | 已做对 |
| 围栏闭合才渲染 | `renderNow` 里 `!streaming` 才登记 mermaid | 已做对（对齐 Streamdown/open-webui 共识） |
| SVG 结果缓存 | `mermaidCache`（源码指纹 → 净化 SVG，上限 20） | 已有，但容量偏小 |
| 超时/失败兜底 | 3s 超时，失败保留源码块，`suppressErrorRendering` | 已做对 |
| 未闭合围栏不高亮 | `tailFenceFingerprint` 抑制 hljs | 已做对 |
| 高亮 memo | `highlightCache`（200） | 已做对 |
| **hljs 体积** | `import * as hljsNs from 'highlight.js'` → 192 种语言全进包 | **缺口**：本地实测 **310,795 B gzip**（markdown-it 仅 40,885 B） |
| **mermaid 可见性** | `hydrateMermaid` 对该消息所有闭合图**立即串行渲染**，无视是否在视口 | **缺口** |
| **降级耦合** | 同步渲染超 24ms → 全局粘滞关掉"高亮 + mermaid" | **缺口/缺陷**：mermaid 成本不在该预算内，属误伤 |

### 具体动作（按性价比排序）

1. **hljs 换成 core + 语言白名单**：`highlight.js/lib/core` + 注册 ~15 种常用语言（ts/js/java/python/sql/json/bash/yaml/xml/html/css/md/diff…）。`highlightCode` 现在是 `hljs.getLanguage(lang)` 判断，未知语言已经走 `escapeHtml`，所以行为兼容；唯一变化是罕见语言从"有高亮"变"纯文本"。收益：初始包体减约 250KB gzip。风险：低。
2. **mermaid 加 IntersectionObserver 门控**：在 `hydrateMermaid` 里先给 `pre[data-fence-key]` 挂 IO（`root: msgWrapRef`，`rootMargin: '200px'`），进入视口才 `renderMermaidSvg`；渲染完成后 `unobserve`。流式期间不渲染（已有）。收益：长会话/多图时，打开会话不再一次性付 1–2 秒主线程。风险：低（失败仍回源码）。
3. **收紧 mermaid 复杂度**：`maxTextSize` 默认 50,000、`maxEdges` 默认 500（`mermaid/dist/config.type.d.ts` 确认存在），建议降到 30,000/300，超限直接回源码卡，避免单图把主线程钉死。收益：防雪崩。
4. **把 `degraded` 的作用域收窄**：只让它关 hljs（同步成本），mermaid 交给"闭合 + 可见 + 并发 1"控制；或者把粘滞改成"本消息内降级、下条消息重试"。收益：消除"一次慢渲染永久失去图表"的 UX 事故；风险：需要改测试（`tests/setup.ts` 已有 `resetDegradation` 的全局 beforeEach，改动面可控）。
5. **可选**：`requestIdleCallback`（Safari 需 fallback）在"贴底且空闲"时预渲染下一张图；`mermaidCache` 上限从 20 提到 50–64。

---

## 4. 综合：只做两件事，做哪两件

### 业界经验收敛成的两件

1. **别重复计算已经完成的内容**（块级冻结 + 净化后缓存 + 完成时整篇校正）。
2. **别渲染屏幕外的重内容**（虚拟化 / 可见性门控 / 惰性加载）。

这两条几乎是所有成熟实现的公因式：Streamdown 的 block memo、LobeChat 的 `blockLexer` + `CachedMarkdown`、Open WebUI 的 rAF、TanStack 的 chat 虚拟化、assistant-ui 的 streaming 跳过高亮，都是这两条的不同切面。

### 落到我们栈：施工建议草案

| 项 | 改哪些文件 | 依赖 | 预期收益 | 风险 | 验证 |
|---|---|---|---|---|---|
| **① 块级"净化后 HTML"前缀缓存 + 完成时整篇重渲** | `frontend/src/utils/markdown.ts`（`renderMarkdown` / `renderNow` 外层新增 `splitStablePrefix`、`prefixCache`；`fenceRenderSeq` 改续号；新增 `resetMarkdownCaches`）；`AiPanel.vue:1922 markdownHtml` 调用新入口 | 无新依赖 | 4k 正文每次更新 ~29ms → ~3–5ms（jsdom 比例，约 5–10×）；长回答不抖；不再触发 24ms 降级 | 中：引用式链接 / raw HTML 跨块 / 松散列表边界 / 围栏 key 重复；测试面大（212 条渲染用例 + 10 条 XSS 用例） | `npx vitest run tests/ai-panel-parts-render.spec.ts tests/ai-panel-security-render.spec.ts`；新增"分块 vs 全文"等价性用例（同一文本两种路径产物 HTML 全等，流式结束后） |
| **② 重型内容惰性化 + 体积削减** | `markdown.ts`（hljs 改 core + 语言表；`loadMermaid` 配置 `maxTextSize/maxEdges`；降级作用域拆分）；`AiPanel.vue:2504 hydrateMermaid`（IO 门控 + idle 调度 + 缓存上限） | 无新依赖 | 初始包 -~250KB gzip；长会话/多图首开不再卡 1–2s；图表不再被误降级 | 低–中：罕见语言变纯文本；IO 在 jsdom 下需要 mock | `npm run build` 对比 chunk 体积；`tests/ai-panel-parts-render.spec.ts` 里 mermaid/高亮用例；真机长会话手测 |
| **③（条件项）消息列表虚拟化** | `AiPanel.vue` 模板 + 滚动跟随段（`onMsgScroll`/`pinBottom`/`pinGuard`/`msgResizeObserver`）；新增 `tests` 开关 | `@tanstack/vue-virtual@3.13.48` 或 `virtua@0.53.6` | DOM 节点从 O(全部消息) 降到 O(视口)；长会话滚动/首屏明显改善 | 高：mermaid 命令式 DOM 注入、表单交互态、现有 4 条滚动用例、jsdom 零高度 | 先量化（消息数 P95、首屏节点数、滚动 FPS）；用 `messages.length > 80` 阈值开关保测试 |

### 推荐施工范围

建议**本批只做 ① + ②，不做 ③**：

- ①是"把 O(n²) 变 O(n)"的结构性修复，同时顺手修掉"一次慢渲染永久关高亮/图表"这个隐性 UX 事故；改动集中在 `markdown.ts` 一个文件，markdown-it 实例、白名单、DOMPurify hook、表格包装、围栏身份全部不动，风险可控。
- ②是纯收益项：hljs 从 304KB gzip 降到几十 KB，mermaid 从"打开会话就全渲染"变成"滚到才渲染"，并把降级开关的误伤拆开。两项加起来预计 3–5 人日（含测试）。
- ③先不排期，但**先补一次量化**（真机 100 条消息的 DOM 节点数、首屏耗时、滚动 FPS）。若 P95 会话 <80 条消息，虚拟化收益覆盖不了它对 mermaid/表单/测试的破坏面；超过阈值再单独立项，用 `@tanstack/vue-virtual` + `anchorTo:'end'`，并以消息数阈值开关保证现有 212+4 条用例继续走非虚拟路径。

**两条硬约束（无论做哪项）**：
1. 缓存里只存 `sanitizeHtml` 之后的 HTML，净化口径与白名单一个字都不能放宽；分块后每块仍需过同一套 `ALLOWED_TAGS/ALLOWED_ATTR/URI` 与 hook。
2. `data-fence-key` 必须全局唯一（前缀围栏数 + 尾块序号），否则 mermaid 卡会绑到错误的代码块上 —— 这是 `AiPanel.vue:2504` 按 key 查槽位的直接依赖。

**参考的仓内既有调研**（避免重复劳动，也说明这次结论与它们的关系）：`docs/research/业界调研-AI对话管理-渲染-结论-2026-10-10.md`（已覆盖流式共识、mermaid 三条硬约束、安全惯例，当时裁决"优先级不高，暂不动"）；`docs/research/业界滚动调研-2026-10-13.md`（现有钉底/回底实现就是按它落的）。本次新增的是：块级缓存的具体落地路径（markdown-it token 边界）、2026-10 的 Vue 虚拟化选型（TanStack 的 `anchorTo:'end'` 是一周内才出现的）、以及本仓实测数据。

**未验证/待确认**：ChatGPT 前端解析实现（无一手证据）；真机性能数字（本次只有 jsdom 相对值）；`@tanstack/vue-virtual` 的 `anchorTo/followOnAppend` 在 Vue 适配层的运行时行为（已核对发布物导出与 core 选项，但未在浏览器里跑过）。
