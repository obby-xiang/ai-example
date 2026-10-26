> 来源=agent-fzyp42tc/output.log（2026-10-26 归档）· 全面代码审查（全页面渲染成本、P0-1~P0-3 与需 profile 的项）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

已完成只读审查（未修改任何文件、未起停服务、未执行测试/构建/浏览器操作、未读 `.env`/凭证；结论全部为静态代码证据，无法仅凭代码定性的项已单独标注）。

---

# 一、全页面覆盖面

| 页面 / 组件 | 渲染成本与风险点 | 定时器 / 监听 | AI 联动 |
|---|---|---|---|
| `App.vue` | 外壳；`el-aside` 宽度 200ms 过渡（`App.vue:37-42`）；footer 读 `workspace.dataVersion` | 无 | 只读 `ai.expanded`，不随 token 重渲 |
| **AiPanel**（常驻右侧） | **单体大组件**：composer 本地态与全部消息同一渲染域；无子组件、无虚拟化；卡片体 `v-show` 常驻 DOM | 9 个 watch、3 处 RO/IO、捕获 scroll、10+ 计时器 | 所有 AI 状态与 `workspace.dataVersion` 都触发**整面板重渲** |
| FormRenderer | 每次按键重渲整表单（`visibleFields` 依赖 `model`）；`fieldIssues()` 每字段每渲被调 4–5 次 | 2 watch（含 `deep: true`） | 草稿 emit → store 写入 → AiPanel 重渲 |
| ResultViewRenderer / Table | 行 ≤200、列 ≤50（`resultview.ts:30`），规模有界 | 无 | 仅卡内 |
| SpreadGrid | SpreadJS 4.7MB 懒加载；`ResizeObserver` → 50ms 防抖 `workbook.refresh()`（`SpreadGrid.vue:303-313`） | RO + 2 计时器 | **不订阅 ai store 的响应式字段** |
| Export/Import 向导 | 大表 + 分段表格（staging 默认全展开）；生命周期重 |
| TaskCenter | `el-table` + 分页，无自刷（`TaskCenterView.vue:360-364`） | 无 | 无 |
| DataBrowser | `el-table` 20 行/页；单元格内 `JSON.parse`（见 P2） | 无 | 无 |
| Definitions | 全量定义表（无分页）+ `fixed` 列（EP 双表复制）+ 编辑抽屉内表格全量重渲 | 无 | 无 |
| AiSettings / AiFeedback | 挂载拉一次，模型/反馈表规模有界 | 无 | 无 |

**负面结论（重要）**：没有发现「AI token 流导致 SpreadJS / 任务页 / 向导页重渲或强制布局」的链路。两个向导对 `aiStore` 只有命令式调用（`ImportWizardView.vue:823,839,944,955,961,2074`、`ExportWizardView.vue:615,907,1107,1122,1128,1140,1934`），唯一响应式读取 `aiStore.activeUploadHandoff`（`ImportWizardView.vue:1948/1954`）在函数体内，不进渲染依赖。跨页的唯一桥梁是 **`workspace`**：`touch()/bumpVersion()` → 数据版本自增（`workspace.ts:263-275`），而 AiPanel 模板直接读 `workspace.dataVersion / contextSummary`（`AiPanel.vue:46,48`）→ **用户在向导里改一格表格，就会触发一次整面板重渲**。

---

# 二、P0（证据充分，最可能构成“浏览器看起来卡死”）

## P0-1 长会话下 `mdCache` 反复失效 → 每次重渲都重算大量历史 Markdown（DOMPurify）

证据链：

- 正文 v-html 的 memo 是**按消息 × 段 × 文本长度 × 流式计数**作键（`AiPanel.vue:1958`），容量 128，**满即插即淘汰队首、命中不刷新位置（不是 LRU）**（`AiPanel.vue:1959-1973`）。
- 流式期间每帧真渲染都会写入一个**新键**（长度变化），即每帧挤掉 1 个历史块；`markdownHtml` 即使被 `renderMarkdown` 的 75ms 防抖挡回上一帧结果，**仍以新键写入缓存**（`markdown.ts:702-712` + `AiPanel.vue:1958-1973`）。
- 因此：只要会话正文块数 > 128，就进入**每次渲染重算约 (N−128)…N/2 个历史块**的稳态（FIFO 下被淘汰的正是下一轮要先访问的块）；即使不足 128，流式 churn 也会持续把历史块踢出，令下一帧重算。
- 每次重算 = `md.parse` + `wrapTables` + `wrapFenceFigures` + `hljs`（命中 15 语言之一）+ **DOMPurify 全量净化**（`markdown.ts:871-909`、`471-478`）。

触发面（不只流式）：

- 每个 SSE token：模板遍历 `ai.messages`（`AiPanel.vue:203`）→ 每消息 `blocksOf(m)`（`:235`，且 `showCaret()` 会再算一遍，`:3274-3279`）→ 每正文块 `markdownHtml()`（`:260`）。
- **在输入框打字**同样触发整个面板重渲：`v-model="inputText"` + 计数/发送闸都在同一渲染域（`AiPanel.vue:919-932,940-944,1589-1653`）→ 长会话里打字卡顿与流式卡顿同源。
- 鼠标移入/移出输入区（`AiPanel.vue:912-916` → `hiddenComposerTips`）也会触发整面板重渲。

结论：这是“长会话越用越卡、最后无响应”的最佳单一解释；`MD_CACHE_MAX=128` 与会话规模**脱钩**，且未随 5902cca 的性能批调整（该批只动了 `markdown.ts` 内部）。

## P0-2 单体渲染域 + 全量卡片派生计算（每帧 O(全部卡片) 的 JSON.parse / i18n / 规范化）

- 全面板一个 render effect，没有 per-message 子组件、没有 `v-memo`、没有列表虚拟化（`AiPanel.vue:203-697`）。任一 token 都走完整 vnode 重建，而不是只 patch 末条。
- 每个工具段每帧被反复求值的函数（模板里多处调用，**均无缓存**）：
  - `paramRows → argRows → JSON.parse(args)`（`blocks.ts:181-210`；`AiPanel.vue:2914`）
  - `hasBodyRows → argRows` 再 parse 一次（`blocks.ts:530-552`；模板 `cardBodyShown/cardHeadDisabled`）
  - `gateRows` 再 parse（`AiPanel.vue:3107-3109`）
  - `toolSummary → JSON.parse(args)`（`tool-summaries.ts:75-85,258-264`）
  - `outputByView → normalizeResultView`：`JSON.parse(result)` + `JSON.stringify` + `TextEncoder` ×2（`AiPanel.vue:2930-2932`；`resultview.ts:256-327`），而 `ResultViewRenderer` 内部还会独立再归一一次（其 computed 有缓存，但面板侧这次没有）。
  - 只有 `formSpecCache` 做了 WeakMap 缓存（`AiPanel.vue:3299-3309`），args 系一律没做。
- 卡片体用 `v-show`（`AiPanel.vue:375,427`）⇒ 全部历史卡片的参数表 / 输出盒 / 结果表 / GF 表单**永久留在 DOM**，每帧都要参与 patch；长会话 DOM 无上界（与“messages 无上限”叠加，形成双重放大）。

量级：以 100 张卡、每卡 3 次 `args` 解析为例 ≈ 300 次 `JSON.parse`/帧；`args` 一旦是大表格参数（上千行），单帧即数十 ms。

## P0-3 每帧多次“强制同步布局”（与渲染成本叠加）

- 滚动指纹 watch 在**每个 token** 上执行：`scrollBottom()` → `await nextTick()` → 再 `scrollBottom()`，并在同帧 `nextTick(measureUserMsgOverflow)`（`AiPanel.vue:3557-3576`）。
- `measureUserMsgOverflow` 遍历**所有**用户消息元素读 `scrollHeight`（`AiPanel.vue:1455-1465`），不区分“这条是否变过”。
- `pinBottom()` 读 `scrollHeight` 后写 `scrollTop`（`AiPanel.vue:3150-3167`）；写后浏览器派发 scroll → `onMsgScroll` 再读（`:1300-1323`）。
- 内容层 `ResizeObserver` 每帧因内容长高触发 → 又读 `scrollHeight` + `pinBottom`（`:3600-3610`）。
- 净效果：每帧 2–3 个“读-写-读”布局回合；在大表格/大表单页（SpreadJS canvas + 大量 DOM）上，一次全文档布局可达数 ms～数十 ms，且与 P0-1/P0-2 的脚本时间叠加。

---

# 三、P1

1. **5902cca 的渲染规格变更（重点核对）**
   - 删除的是**页签级粘滞降级**（旧代码：任一帧超过 24ms ⇒ 模块级 `degraded=true` ⇒ 其后所有渲染关高亮/mermaid，`5902cca^:markdown.ts` `renderNow`）。它原本是一道**覆盖长会话整体负载的保险**。
   - 新增的前缀缓存只在**多顶层块**文档生效；`probePrefix` 仍在**每帧对全文做一次 `md.parse`**（`markdown.ts:804-845`），且单顶层块时 `boundary<=0` 直接整篇渲染（`:947-958`）——**单个长段落 / 单张大表格的流式形态没有任何收益，只剩多一次 parse**，同时失去了旧的保险。
   - 阈值从“页签级”收窄为“单条 >10k”（`:926`、`renderNow`），意味着一条 9.9k 的消息的高亮/mermaid 永远开着；而 `mdDegraded` 只是不高亮，**DOMPurify 与结构渲染照旧全量**（`:908`）。
   - 度量口径问题：提交里“18.65ms → 1.15ms/帧”是 `renderMarkdown` 单块场景的实测；仓库内的 `tests/markdown-render-perf.spec.ts` **只有逐字全等/白名单类断言，没有耗时断言**（我读了用例名），也就是说**没有回归闸**。P0-1/P0-2 的整面板放大不在该测量口径内。
   - 因此“删降级后是否更差”**方向可两面成立**：多块文档且缓存命中 ⇒ 更好；单块长文 + 长会话 mdCache 抖动 ⇒ 更差。**必须用真实浏览器 A/B 回放才能定性**（见第五节）。
   - 附带语义回退（非性能）：hljs 收窄到 15 语言（`markdown.ts:67-86`），`vue/scss/properties/toml/powershell/dockerfile…` 一律退化为纯文本转义。

2. **折叠/展开 AI 面板后，RO / IntersectionObserver / 表格滚动监听永久绑在“已卸载的旧 DOM”**
   - 面板用 `v-if/v-else` 切换收起条与完整面板（`AiPanel.vue:3-14`），但组件实例**不卸载**（`App.vue` 始终渲染 `<AiPanel />`），而 RO、`scroll` 捕获监听、mermaid IO 只在 `onMounted` 建立、只在 `onBeforeUnmount` 清理（`AiPanel.vue:3637-3648` / `3655-3689`）。
   - mermaid IO 的 `root` 在创建时固定为当时的 `msgWrapRef`（`:2562-2587`）。
   - 后果：① 收起再展开后，**内容增高不再自动钉底**、表头/表体横滚联动失效；② **mermaid 槽位永远不再交汇 → 图不再水合**（外观回落为源码块）；③ 旧 DOM 子树被观测器/待渲染表持有（`mermaidPending`），形成常驻内存；每轮“收起-展开”再叠一份。
   - 这是纯代码可判定，浏览器只需 30 秒复现确认可见症状。

3. **大表格交互放大到 AI 面板**
   - `CellChanged/RangeChanged` → `data-changed`（`SpreadGrid.vue:269-276`）→ 向导 `onGridChanged`：`workspace.touch()/bumpVersion()`（`ImportWizardView.vue:983`、`ExportWizardView.vue:1134-1137`）+ `aiStore.refreshSheetApplied()`（`ImportWizardView.vue:985`、`ExportWizardView.vue:1140`）。
   - `touch` 改 `dataVersion` ⇒ AiPanel 重渲（P0-1/P0-2 全量成本）；`refreshSheetApplied` 会 `await resolveSheetContext` 后**无条件赋一个新对象** `this.sheetApplied = …`（`ai.ts:3740-3752`），值相同也触发第二次整面板重渲。
   - `sheetAppliedSummary → sheetManualChangeCount` 逐格 `adapter.getCell()` 遍历**整批 AI 改动**（`sheet-tools.ts:2504-2519`）——AI 刚改过 5000 格时，用户之后每敲一格都要 5000 次 SpreadJS API 调用。
   - 该路径与 AI 无关也会触发，但它是“AI 改完表 → 用户接手 → 卡”的典型重合面。

4. **大表单：每次按键三重成本**
   - `setValue` 重排 `model` 并 `emit('draft')`（`FormRenderer.vue:557-574`）⇒ ①FormRenderer 整表重渲；②`updateFormDraft` 写 `sessionStorage`（`ai.ts:3611-3618`）；③AI store 变更 → 深度订阅 → AiPanel 整面板重渲（P0-1/P0-2）。
   - `fieldIssues(field)` 每字段每渲被调用 ~5 次（`FormRenderer.vue:28,44,60,202,507,769`；实现 `:704-719` 每次 filter 全量 issues），`liveSummary` 再遍历一遍（`:736-744`）。字段多（30+）时是 O(字段×问题) 的重复工作。

5. **mirror 序列化的 O(会话) 成本（与另一 agent 结论衔接，这里只补与本报告相关的耦合）**
   - `$subscribe(..., {deep:true})`（`ai.ts:1536-1559`）由 Pinia 实现为对整个 store state 的 `deep` watch ⇒ **每次变更批次都要遍历一遍全部消息/段**；命中 300ms 窗口时执行 `JSON.stringify` 全量镜像（`ai.ts:870-907`）。
   - 关键是它与 P0-1/P0-2 同帧竞速：压缩结束/首字到达时一批帧在同一 microtask 落地，既有一次全量渲染，又可能刚好跨过 300ms 边界 → 一次全量序列化。

6. **mermaid 主线程同步成本 + 超时不可取消**
   - `renderMermaidSvg` 用 `Promise.race` 做 3s“超时”（`markdown.ts:1067-1092`），**不会中断 mermaid 自身的同步布局/计算**；成功后还有一遍 DOMPurify SVG profile 净化（`:481-483`）；`initMermaidInline` 再读 `offsetWidth/Height`（`AiPanel.vue:2202-2205`）。
   - IO 门控（`:2562-2587`）降低了“打开面板即排队渲染”的风险，但**可见即渲染**仍会在流式大渲染同帧插入一次重活。

---

# 四、P2

1. `copyFlashTimers` 只增不删：`flashCopied` 每点一次复制就 `set` 一个元素且回调里不 `delete`（`AiPanel.vue:2058-2067`），被复制的按钮随 v-html 重建后成为**被 Map 持有的脱离节点**（直到卸载才清）。
2. `mermaidPending` 不随节点失效清理（`AiPanel.vue:2539-2587`）；`hydrateMermaid` 在**每次 memo 未命中的渲染**里都会被 `nextTick` 调度一次（`AiPanel.vue:1963-1966`），缓存抖动时会反复尝试替换。
3. `probePrefix` 每帧全文 parse（`markdown.ts:804-845`）+ `hasUnclosedFence` 全文正则（`:197-200`）+ `text.slice(0,boundary)` / 前缀 HTML 字符串拼接（`:1011-1016`）——单块形态无收益、纯增成本。
4. memo 键用 `text.length`（`AiPanel.vue:1958`）：同长度不同文本会误命中（当前被 `renderMarkdown` 的文本比对部分兜住，属脆弱设计）。
5. `streamRenders` 每个块存 `{text, render}` 全量快照（`markdown.ts:500`），`streamFlushTimers` 只在测试入口清理（`:523-529`），卸载时只注销回调（`:510-512`）。
6. DataBrowser 单元格内 `dataOf(row)` = 每格一次 `JSON.parse(dataJson)`（`DataBrowserView.vue:87,107-112,212-214`；`types/data.ts:59-69`），展开行再 parse 一遍；20 行 × N 字段 ≈ 每渲数百次 parse。
7. Import 向导 staging 默认**全部组展开**（`ImportWizardView.vue:1642`），每组一张 100 行 `el-table`（`:184-208`）→ 组多时首屏 DOM 爆炸（无虚拟化）。
8. Definitions 主表无分页 + `fixed="right"`（EP 会为固定列复制一份表体），编辑抽屉里字段表每个单元格是 `el-input/el-select`，改一个字符整表重渲（`DefinitionsView.vue:35-75,91-120,140-175`）。
9. 收/展 AI 面板会改变业务区宽度 → SpreadJS `ResizeObserver` → 50ms 后 `workbook.refresh()` 整表重绘（`SpreadGrid.vue:303-313`），与面板宽度 200ms 过渡重叠。
10. `App.vue:81-87` `panelWidth` computed 每帧无谓；`el-aside :width` + `transition-[width]` 在 200ms 内持续触发布局（一次性，非 token 级）。

---

# 五、与“压缩时机”重合的机制（前端侧，不含压缩本身）

压缩在 `start` 之前、无帧无心跳，这一点另一 agent 已证；前端与之重合的**真实机制**是：

1. **开流前无任何超时**：`streamChat` 的 `fetch` 没有 signal 之外的计时器（`api/sse.ts:502-529`）——服务端尚未提交响应头时，前端**零定时器**，只有 `loading=true` 与呼吸点（`appendAssistantMessage` 起手就是 `streaming:true`，`ai.ts:5124-5140`），所以 UI 不是“无提示”，而是“有提示、零进度”。
2. **45s 帧间静默判死**（`api/sse.ts:31,371-390`）：一旦响应头已提交而压缩期不写任何字节，45s 后 `controller.abort()` → `tryReattach()` 因**尚无 `runId`（start 未到）**直接返回“未收到 start 帧（无 runId 可重挂），判定断流”（`:556-565`）→ 前端把这一轮封口为断流（`ai.ts:2610-2630` onClose 分支）。若 L2 摘要串行 60s，这条 45s 线**正好切在压缩中间**——用户看到“结果可能不完整”，而后端可能仍在跑。
3. **重试放大**：用户点“继续/重试” → 新 `POST /chat`（`ai.ts:2558-2612`）→ 若压缩条件仍成立，会再排一次 60s 级摘要 → 表现为“越点越久”，与卡死在时间上相似但机制不同。
4. **压缩结束的首帧突发**：一批帧在同一 microtask 落地 ⇒ 一次全量渲染 + 可能命中 300ms 边界的全量 mirror 序列化（P1-5）——这是“压缩完成后忽然卡一下/几秒”的最可能前端解释。
5. 压缩期间**没有**任何前缀缓存/高亮/mermaid 工作（无帧），因此压缩本身**不产生**前端脚本负载；把它当作主线程冻结原因不成立。

**结论口径**：压缩窗口的可见症状应分两类记录——(a) 等待/无进度/45s 误判断流（可确定，属产品与超时口径问题）；(b) 压缩结束后或长会话渲染期的“无响应”（属 P0-1～P0-3，需 profile 定位归属）。

---

# 六、必须真实浏览器 profile 才能定性的项

1. **长任务归属**：一个 ≥200ms 的 Long Task 里，脚本时间在 markdown-it parse / DOMPurify / Vue patch / 卡片派生 / `JSON.stringify(mirror)` 各占多少（代码只能证明“都可能”，不能给出比例）。
2. **`mdCache` 实际抖动率**：需在真实会话里读一次 DOM 正文块数（`document.querySelectorAll('[data-testid^="msg-bubble-"] .md-body')` 计数）并抓 `renderMarkdown` 调用/重算次数（模块已导出 `getMarkdownRenderStats()`，但**未覆盖 AiPanel 层的 mdCache 命中率**——这是当前可观测性的缺口）。
3. **5902cca 前后 A/B**：同一长会话回放，比较帧耗时分布（P50/P95/max）与 Long Task 数；旧版需带 `degraded` 粘滞、新版不带——只有这样才能回答“删降级是否更差”。
4. **单块 vs 多块形态**：单段落/单大表格的流式是否真的没有前缀收益（静态分析如此，需实测确认 parse 占比）。
5. **折叠-展开后 mermaid 不再水合 / RO 失效**：症状可由代码确定，但需浏览器确认可见面与复现步骤。
6. **内存曲线**：mirror 镜像字符串、`mdCache`/`prefixCache`/`highlightCache`/`streamRenders` 的字符串驻留、脱离 DOM（RO/IO 持有）在 30–60 分钟会话中的增长。
7. **mermaid**：可见图在流式同期渲染时是否产生 >100ms 的同步块（`Promise.race` 无法取消，必须看 trace）。
8. **SpreadJS 侧**：`sheetManualChangeCount` 在 5000 格改动栈下的实际耗时，以及 `workbook.refresh()` 的耗时。

---

# 七、最小改进方向（仅供审，我不改）

按“收益/风险”排序，均为方向而非实现：

1. **给消息列表加渲染边界**：把单条消息（或单个 block）拆成子组件，或至少 `v-memo` 绑定「该 block 文本/状态指纹」；把 composer 的 `inputText`、hover tip、`copyState`、`showBackToBottom` 等本地态移出列表渲染域（或抽子组件）。这一条同时消掉“打字卡”“悬停卡”“滚动卡”。
2. **mdCache 改 LRU + 容量按块数/字节预算**；流式块不要用“长度键”进缓存（单独一个 `lastStreamRender` 槽即可），避免每帧挤掉一个历史块。
3. **卡片派生值缓存**：按 `part` 对象 WeakMap 缓存 `argRows / toolSummary / outputByView`（`formSpecCache` 已是先例），或在 store 侧把 `args` 解析结果随段保存一次。
4. **布局读取收敛**：`measureUserMsgOverflow` 改为“按 id 只测新增长消息 / 文本变化的那条”；`pinBottom` 的 watch 与 RO 二选一；把“读 scrollHeight”合并到一次。
5. **折叠/展开重挂观察器**：在 `watch(() => ai.expanded)` 里重建 RO / `scroll` 捕获 / mermaid IO（或 `root` 改用稳定祖先），并在收起时显式清理 `mermaidPending`。
6. **mirror 与序列化**：按消息增量写 + 上限/裁剪 + `requestIdleCallback` 落地（与另一 agent 的结论衔接）。
7. **降级口径**：不建议恢复“页签级粘滞”（一刀切、不可预测），可考虑“**按条一次性预算**”：单条单次超预算 ⇒ 仅该条降级（确定性、不跨条），并把 `prefixCache.degraded` 的重切成本一并计入。
8. **mermaid**：把水合时机从“可见即渲”改为“可见且主线程空闲”（`requestIdleCallback` + 短超时回退），避免与大渲染同帧；超时后显式标记“未渲染”而不是留下一个永不完成的 promise 链。
9. **卡片体**：评估 `v-show` → `v-if`（折叠态只渲卡头），或对隐藏体用 `template v-if`；若保留 `v-show`，至少把大表格族（`ResultViewRenderer`）改为展开后才挂载。
10. **可观测性**：把 `getMarkdownRenderStats()` 之外再加一个面板级计数（mdCache 命中/未命中、每帧块数），否则下一次回归仍然只能靠肉眼。

---

**边界声明**：以上均为静态代码证据与逻辑推演；未运行任何测试、构建、基准或浏览器操作，因此“主线程冻结”的**归属与占比**仍待性能 profile 定性。压缩链路本身我未重做，只补充了与它时间上重合的前端机制（第五节）。
