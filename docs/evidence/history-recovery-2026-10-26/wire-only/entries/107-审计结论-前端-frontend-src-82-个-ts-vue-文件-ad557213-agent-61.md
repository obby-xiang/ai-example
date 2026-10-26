> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-61` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：25640 B / 16148 字符 / 161 行；行指纹覆盖率（仓内 + 落盘语料）0.8%
> - 类别：**正式独立报告**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 审计结论（前端 `frontend/src`，82 个 ts/vue 文件）

先确认依赖事实：`package.json` 里没有 vueuse / vee-validate / ajv / zod / lodash / @vue-flow / floating-ui；但 `node_modules` 里**已经存在** `@vueuse/core@15`、`async-validator`、`lodash-es`（三者都是 element-plus 的传递依赖），所以下面标"VueUse"的替代方案**不需要新增安装**，只多一行直接依赖 + tree-shaking 后 1–3KB。

---

## 一、确凿：生态有公认库，自己写了一遍

### 1. 手写 SSE 帧解析器
- **手搓的是什么**：字节流 → 事件块 → `data:` 载荷 → JSON 帧的完整切分器（含 CRLF 跨块、BOM、多行 data、流尾 flush）。
- **证据**：`frontend/src/api/sse.ts:154`（`SseChunkParser`，`push`/`flush`/`drain` 共 55 行）、`:216`（`extractData` 手写 `data:` 前缀与 BOM 剥离）、`:234`（`parseSseFrame`）、`:360`（`readStream` 手搓 `getReader()` 循环 + `TextDecoder`）。`EventSource` 只用于 GET reattach（`:711`），POST 通道因为要设头只能裸 fetch —— 这正是库存在的原因。
- **本可用的开源库**：`eventsource-parser`（MIT，~2KB gzip，同时支持 POST/自定义头场景）或 `@microsoft/fetch-event-source`（MIT，自带 fetch + 重试骨架）。
- **判断**：**确实重复造轮子**（帧切分/`data:` 提取这一段）。但重连退避、建连/帧间双看门狗、终帧闸门、`lastSeq` 增量、`runId` 归属闸（`:270`、`:283`）都不在库的范围，属于真业务。
- **替代成本与风险**：低-中。只需把 `SseChunkParser` 换成 `eventsource-parser` 的 `createParser({onEvent})`，保留外层状态机；已有 `tests/sse-parser-boundary.spec.ts` 可当回归网。风险点是库对"块尾裸 CR"和 BOM 的处理口径与现有注释不同，需逐条对齐测试；`@microsoft/fetch-event-source` 不建议整体替换（它会接管重试策略，与现有 4 次/1·2·4·8s+抖动、45s 静默、15s 建连三只看门狗冲突）。

### 2. 手写 XLSX（OOXML）生成器
- **手搓的是什么**：直接拼 SpreadsheetML —— `[Content_Types].xml`、`_rels`、sharedStrings、styles、worksheet XML，再用 JSZip 打 zip。
- **证据**：`frontend/src/utils/excel.ts:544`（`buildXlsx`）、`:566`（单元格引用 `${columnLetter}${row}`）、`:618`（sharedStrings 表）、`:610`（最小 styles.xml）、`:622`（JSZip 落盘）；入口 `:457`（`buildRowsXlsxBlob`）。另有 `:518` 手写列号→列名、`:530` 手写 XML 转义。
- **本可用的开源库**：`exceljs`（MIT，直接 `worksheet.addRow` + hidden sheet + `writeBuffer`）；`xlsx`/SheetJS（Apache-2.0，社区版仍需注意 npm 上版本陈旧）。
- **判断**：**确实重复造轮子，但动机成立**——注释 `:441-456` 记录 SpreadJS 评估版 ExcelIO 保存会插 "Evaluation Version" 水印表，后端按"首个非 `_` 表"读会读成垃圾数据。这是对商业库缺陷的规避，不是无知重写。
- **替代成本与风险**：中-高。ExcelJS 体积大（≈90KB gzip，且带 stream/buffer 依赖），而当前实现只服务 3 张极简表（数据/`_dict`/`_meta`）、产物约几百字节；换库反而扩大包体与失败面。**建议保留**，但把 `buildXlsx` 的"最小 OOXML 子集"当成受测公共能力（现在只由 `excel-zip.spec.ts` 间接触及）。许可无风险（JSZip MIT 已在用）。

### 3. mermaid 图查看器：手写缩放/平移/全屏
- **手搓的是什么**：指针锚定的滚轮缩放（`Math.pow(1.0015, -deltaY)`）、拖拽平移、越界钳制、双 tab 滚动位置记忆、全屏 scrim 对话框 + Esc + body 滚动锁。
- **证据**：`frontend/src/components/AiPanel/AiPanel.vue:2176`（`initMermaidInline`：`measure/clamp/absorb/setZoom` + wheel/mousedown/mousemove）、`:2299`（`switchMermaidTab` 手写 tablist + `aria-selected`）、`:2333` 起（`mmdViewerZ/X/Y`、`onMmdViewerWheel`、`onMmdViewerDragStart`、`:2471` scrim/Esc）。
- **本可用的开源库**：`panzoom`（MIT，~6KB gzip，指针锚定缩放 + 拖拽 + 边界钳制就是它的默认行为）；`svg-pan-zoom`（BSD-2，SVG 专用）；对话框部分 `el-dialog`（已装）或 `focus-trap`。
- **判断**：**确实重复造轮子**。缩放/平移这套数学有 4 份近似实现（卡内、全屏、还有 clamp 逻辑三处），是最容易出边界 bug 的自研点。
- **替代成本与风险**：低。`panzoom` 无框架耦合，可直接接管 `.mmdc-pan`；风险是要保留现有 CSS 变量（`--z/--px/--py`）的读屏百分比与复位钮语义，且 `panzoom` 对 `wheel` 的 `preventDefault` 策略需与面板滚动容器对齐。全屏查看器改 `el-dialog` 会让 `v-html` 的 SVG 进 Teleport，需复核 `prepareMermaidSvg` 的内联样式克隆是否仍生效。

### 4. 剪贴板：手写 `execCommand` 兜底链
- **手搓的是什么**：离屏 `<textarea>` + `select()` + `execCommand('copy')`，外加"2s 绿勾反馈"状态机，且存在**两条不一致的复制路径**（代码块复制直接走 `legacyCopy`，消息复制才先试 `navigator.clipboard`）。
- **证据**：`frontend/src/components/AiPanel/AiPanel.vue:3384`（`legacyCopy`）、`:3415`（`copyMessage` 的优先/兜底分支）、`:2015`/`:2023`（代码块与 mermaid 源码按钮**只**调 `legacyCopy`，绕过了 `navigator.clipboard`）、`:2452`（查看器复制）。
- **本可用的开源库**：VueUse `useClipboard({ legacy: true })`（MIT，已在 `node_modules`，实现就是这条链 + 状态计时）；或 `clipboard-polyfill`。
- **判断**：**确实重复造轮子**，而且当前实现有实质缺陷（安全上下文里代码块复制走了已被废弃的 API，Firefox 有兼容面）。
- **替代成本与风险**：极低。一个 composable 替掉 4 处调用 + 3 个 `copyTimer`；风险仅为 jsdom 下 `execCommand` 不存在需 mock（现有测试已在 mock 剪贴板）。

### 5. 手写节流/防抖（两套）
- **手搓的是什么**：① sessionStorage 会话镜像的 leading+trailing 节流 + `pagehide`/`visibilitychange` flush；② 流式 markdown 的 75ms 合并 + 模块级**单槽** flush 回调。
- **证据**：`frontend/src/stores/ai.ts:191-197`（`mirrorLastWriteAt` / `MIRROR_THROTTLE_MS = 300`）与 `:1292` 起（`$subscribe` 内手算 elapsed、单 `mirrorTimer`）；`frontend/src/utils/markdown.ts:495`（`STREAM_DEBOUNCE_MS`）、`:500-529`（`streamRenders` 记账 + `streamFlushHandler` 单槽 + `streamFlushTimers` 集合）。
- **本可用的开源库**：VueUse `useThrottleFn` / `useDebounceFn` / `useEventListener`（组合出 pagehide flush 只需 2 行）；或 `lodash-es` 的 `throttle(fn, ms, {leading:true, trailing:true})`（lodash-es 已是传递依赖）。
- **判断**：**确实重复造轮子**（且仓库自己的调研 `docs/research/排查-A7A8A9-轮询歪门邪道与过度设计-2026-10-24.md:462` 已认定"本仓未引入 VueUse，属自造而非误造"，并点名单槽回调与 mermaid 3s 计时器未清两个成本）。
- **替代成本与风险**：低。需注意两处语义边界：镜像的"空态不写"闸（`writeMirrorNow` 首段）与流式的"首帧立即"必须由 wrapper 保留，不能把 flush 时机一并交出去。包体积 ≈0（VueUse 按函数 tree-shake）。

### 6. 裸用 ResizeObserver / IntersectionObserver + 手搓去抖
- **手搓的是什么**：宿主尺寸变化 → 清/重排 `setTimeout(…, 50)` → `workbook.refresh()`；消息区 RO 触发 `pinBottom`；mermaid 卡片用 IO 做视口门控水合。
- **证据**：`frontend/src/components/SpreadGrid.vue:307`（`new ResizeObserver` + `resizeTimer`）、`frontend/src/components/AiPanel/AiPanel.vue:3581-3630`（`msgResizeObserver` 观察内容层）、`:3525`（`mermaidObserver` IO 门控）。
- **本可用的开源库**：VueUse `useResizeObserver` / `useElementSize` / `useIntersectionObserver` / `useDebounceFn`。
- **判断**：**确实重复造轮子**（薄封装层），但收益是"少 3 个手写 timer 生命周期"。
- **替代成本与风险**：极低；`useElementSize` 直接给 `width/height` 可替掉 `lastMsgScrollHeight` 的回声比较，但要小心它会引入额外的 rAF 触发，与现有"钉底回声闸 ≤1px"（`:1294`）叠加时序需回归 `ai-panel-scroll-follow.spec.ts`。

### 7. 相对/渐进时间手写，且**中文硬编码进 UI**
- **手搓的是什么**：`刚刚 / N 分钟前 / N 小时前 / N 天前` 与 `HH:mm / 昨天 / 周X / M月D日` 的五档渐进格式，全部中文常量。
- **证据**：`frontend/src/utils/format.ts:35`（`formatFromNow`，**已无调用方**）、`:54`（`WEEKDAY_LABELS = ['周日',…]`）、`:67`（`formatProgressiveTs` 直接拼中文）；消费方 `components/AiPanel/AiPanel.vue:722` 在 en-US 界面同样输出"昨天 10:22 / 周三"。
- **本可用的开源库**：dayjs **`relativeTime` + `calendar` 插件**（已装 dayjs，插件各 <1KB，MIT，自带 zh-cn/en 两套文案）；或 vue-i18n 的 `datetimeFormats`（`locales/index.ts:26` 建 i18n 时**完全没配** `datetimeFormats/numberFormats`）。
- **判断**：**确实重复造轮子**，并且是真 bug：非中文界面出现中文时间。`formatFromNow` 还是死代码。
- **替代成本与风险**：极低。`dayjs.extend(relativeTime).extend(calendar)` + 在 `stores/locale.ts` 已有的 `dayjs.locale()` 联动下即自动双语；风险是现有测试逐字断言中文输出（`ai-ref-i18n-stable.spec.ts` 一类），需改为按 locale 断言。另注意 `BACKEND_DATETIME_FORMAT`（`:17`）是展示口径，不在本次范围。

### 8. 四个手写 LRU/FIFO 缓存 + 手写哈希
- **手搓的是什么**：高亮 memo、mermaid SVG 缓存、流式前缀缓存、流式渲染记账，各自复制一遍"超过上限就删 `keys().next().value`"，其中前缀缓存还手工实现了 LRU 触碰（delete + re-set）。
- **证据**：`frontend/src/utils/markdown.ts:203`（`highlightCache`/200）、`:748`（`prefixCache`/16 + `:999` 的 LRU 触碰）、`:1061`（`mermaidCache`/64）、`:500`（`streamRenders`/64）、`:1094`（`hashCode` 手写 31 进制哈希做 mermaid 元素 id）。
- **本可用的开源库**：`lru-cache`（ISC，~8KB）或 `quick-lru`（MIT，~2KB）；VueUse `useMemoize` 带缓存但无容量淘汰。
- **判断**：**确实重复造轮子**（4 份重复实现、淘汰策略不一致：只有前缀缓存是真 LRU，其余是 FIFO，注释里还都写 "LRU"）。
- **替代成本与风险**：低，收益是"容量/淘汰行为可配 + `has/get/set` 原子"。风险几乎为零；`hashCode` 建议顺手换 `crypto.randomUUID()`（已有 `createSessionId` 的非安全上下文回退范式，`stores/ai.ts:201`）。

### 9. `el-form` 摆着不用，手写 `validateForm(): string | null`
- **手搓的是什么**：配置定义弹窗用 `<el-form>`/`<el-form-item required>` 排版，但校验另写一个返回首个错误字符串的函数（含正则、重复 code、主键必填）。
- **证据**：`frontend/src/views/DefinitionsView.vue:132-154`（el-form 无 `:rules`、无 `:model`）、`:419-447`（`validateForm`：code 正则、名称必填、字段 code 正则/重复、REFERENCE 必填、主键必填）、`:452`（`ElMessage.warning(problem)` —— 只报第一条，不点亮字段）。同样形态见 `TaskCenterView.vue:121-137`。
- **本可用的开源库**：Element Plus 自带 `rules` + `async-validator`（MIT，**已在 `node_modules`**）；或 `vee-validate` + `zod`。
- **判断**：**确实重复造轮子**（连库都已经装好了），代价是错误只能 toast 一条、无法定位到字段。
- **替代成本与风险**：低。改成 `rules` 后 `formRef.validate()` 可一次点亮全部错误；风险是现有 E2E 断言 toast 文案（`ElMessage.warning`），需同步改断言。

### 10. 微工具重复（低优先，一并记）
- `saveBlob`（`api/http.ts:206`）与 `downloadBlob`（`utils/excel.ts:839`）是同一份实现的两次拷贝；`parseContentDisposition`（`utils/excel.ts:851`）手写 RFC 5987 解析；`truncate`/`delay`/`escapeHtml` 在 4 个文件各写一份（`ai-dom-tools.ts:231-239`、`frontend-tools.ts:829`、`markdown.ts:1022`、`SheetPreviewCard.vue:173`）。
- **本可用的开源库**：`file-saver`（MIT ~1KB）、`content-disposition`（MIT）、`es-toolkit`（MIT，`truncate`/`escape`/`delay`）。
- **判断**：**部分重复**，单体都很小，主要问题是同仓两份语义漂移（`saveBlob` 立即 revoke，`downloadBlob` 3s 后 revoke）。
- **替代成本与风险**：极低；也可以只内部合并，不引库。

---

## 二、存疑：部分重复，但有站得住的理由

### A. 生成式表单引擎（`FormRenderer` + `types/form-schema.ts`）
- **手搓的是什么**：schema 防御性解析（白名单/上限/原型键黑名单）、七型控件渲染、五原语联动（`visibleWhen/requiredWhen/disabledWhen/optionsFrom/defaultFrom`）、提交前结构化校验（`VALUE_*` 十余个码）、草稿镜像、附件上传、a11y（label/aria-describedby/live region/首错聚焦）。
- **证据**：`frontend/src/types/form-schema.ts:334`（`parseFormSchema`）、`:486`（`evaluateCondition`，纯数据比较、禁表达式）、`:645`（`checkFormIssues`）、`:592-610`（email/tel/url/idcard 四条正则手写）、`:98-101`（`MAX_FORM_FIELDS/OPTIONS/200/2000` 与后端**逐值重复**）；`components/AiPanel/FormRenderer.vue:24-205`（原生控件全手写，连 switch 都是 `:86-108` 自绘）。
- **本可用的开源库**：`ajv`（JSON Schema，MIT，~40KB gzip）或 `zod`（MIT ~13KB）；表单层 `FormKit`（MIT）/`form-create`（MIT）；校验引擎 `async-validator`（**已装**）。
- **判断**：**部分重复但有合理理由**。理由：① schema 由模型生成，安全边界要求"只认闭集、不执行表达式、fail-closed"，通用库的 `if/then`、`$ref`、`pattern` 反而是风险面；② 联动五原语是产品语义，不在任何表单库的标准模型里；③ 后端 `GenerativeFormRules.java` 已有 600 行同规则 Java 实现，"前端先校验避免 400"是刻意双写。**真正可收敛的是取值校验那一半**：`checkFormIssues` 的十几个 `VALUE_*` 完全可以由一份共享 JSON Schema + ajv 生成，消除"前端 200/2000/50、后端 200/2000/50"的三处字面量。
- **替代成本与风险**：中。整表换 form-create 会引入 ~50KB 且丢掉"零 EP 承载 + a11y 逐属性控制"，不建议；只把 `fieldValueIssues`（`:674-776`）换成 ajv 编译的 schema，成本低（半人日），风险是错误码映射要逐条对齐既有 `ai.gf.errors.*` i18n 键与 `frontend/tests/gf-schema-rules.spec.ts`。

### B. 前端工具参数校验（描述符表 → 手写 coercion）
- **证据**：`frontend/src/utils/frontend-tools.ts:210`（`validateArgs`：number/string/object/object[]/string[]/boolean 六型手判 + 强制转换）、`:943-965`（`WORKSPACE_ACTION_REQUIRED_ARGS` / `UI_EVENT_REQUIRED_ARGS` 两张必填表）、`:618`（`dedupeCache` + `:638` `stableArgsKey` 手写稳定序列化）。
- **本可用的开源库**：`zod`（`z.object(...).parse()` 一步给类型 + 校验 + 错误码）；`ajv`（后端若已有 JSON Schema 可直接复用）。
- **判断**：**部分重复**。工具描述符已经是"迷你 JSON Schema"，再写一遍 switch 属重复；但闭集只有 6 型、且错误文案要直给模型（中文、含参数说明），换成 zod 要额外做错误格式映射。
- **替代成本与风险**：低-中（~8 处校验点）。风险：`string[]` 的"必填非空"语义、空串过滤（`:245-256`）是后端约束的镜像，换库时极易丢掉；`stableArgsKey` 的键序稳定性必须保持（去重缓存依赖它）。

### C. AI 可操作 DOM 的"可访问名/角色"推导
- **手搓的是什么**：`data-ai-label → aria-label → title → 文本内容` 的 label 推导，和 `tag/role/type` → `button/input/select/toggle/tab/link` 的 role 归纳，外加 checked/disabled/selected 状态。
- **证据**：`frontend/src/utils/ai-dom-tools.ts:285`（`labelTextOf`）、`:302`（`childTextExcludingBadges` 手剔徽标文本）、`:332`（`roleOf`）、`:369`（`stateOf`）、`:389`（`isBuiltinDenied`）。
- **本可用的开源库**：`dom-accessibility-api`（MIT，~10KB，`computeAccessibleName` / `computeAccessibleRole`，正是 testing-library 用的同一套算法）。
- **判断**：**部分重复但有合理理由**。理由：快照要"确定性 + 2000 字符预算 + 闭集角色"，W3C 完整算法会把 `aria-labelledby`、`<legend>`、`placeholder` 全算进来，输出反而更长且不稳定；但**"accessible name"这块是标准算法**，自研极易与读屏不一致。
- **替代成本与风险**：低-中。可选择性使用：role 用库、label 保留 `data-ai-label` 优先。风险是快照文本会变（`ai-snapshot*.spec.ts` 系列逐字断言），需要重设基线，且模型侧提示词可能依赖当前字面量。

### D. `ui_highlight` 的描边+徽标+播报
- **证据**：`frontend/src/utils/ai-dom-tools.ts:1540`（`highlightElement`：加 `ring-2` 类 + `scrollIntoView` + 插入 `span` 徽标 + 2.5s 自动清除）、`:1580`（`clearUiHighlights`）。
- **本可用的开源库**：`driver.js`（MIT，~5KB gzip，高亮遮罩 + 气泡 + 键盘/滚动跟随）；`shepherd.js` 偏引导流程。
- **判断**：**部分重复**。当前实现把徽标插进元素内部（会污染 `labelTextOf` 的取文，故又写了 `childTextExcludingBadges` 来绕），而 driver.js 的浮层挂 body、天然不污染。
- **替代成本与风险**：低，但注意本仓禁用行内 style / 禁改 tailwind config，driver.js 自身的样式注入需要复核与 Tailwind preflight=false 的兼容。

### E. 聊天滚动跟随（hysteresis + 回声闸）
- **证据**：`frontend/src/components/AiPanel/AiPanel.vue:1240`（`followBottom`/`FOLLOW_THRESHOLD_PX=32`/`SHOW_BACK_TO_BOTTOM_PX=160`）、`:1266-1285`（`PIN_SETTLE_MS`/`USER_INTENT_MS`/`markUserScrollIntent`）、`:1287`（`onMsgScroll` 回声闸）、`:3140`（`pinBottom` 记 `pinGuardTop`）。
- **本可用的开源库**：VueUse `useScroll` + `scroll-into-view-if-needed`（MIT，含平滑与"是否已在视口"判定）；React 侧有 `react-scroll-to-bottom`，Vue 无等价成品。
- **判断**：**部分重复**。`bottomDist`/`pinBottom`/浮钮显隐可以直接用 `useScroll` + 库的 `isInViewport`，但"钉底回声不去解跟随"这条是本项目踩出来的真实竞态，库不管。
- **替代成本与风险**：中。这块是 `ai-panel-scroll-follow.spec.ts`、`a10-recovery-paths.spec.ts` 反复回归过的区域，纯替换风险 > 收益，建议只抽 `bottomDist`/`useScroll` 一层。

### F. 轮询控制器（可见性纪律 + 在途守卫 + dirty 合并）
- **证据**：`frontend/src/utils/polling.ts:46`（`createPollingController`，248 行：`paused`/`dirty`/`catchupTimer`/`visibilitychange` 全套）、`utils/job-watch.ts:139`（组合使用）、`:98`（`REALTIME_IDLE_MS = 2_147_483_647` 用 24.8 天定时器"假装停表"）。
- **本可用的开源库**：`@tanstack/vue-query`（MIT ~13KB gzip，`refetchInterval` + `refetchIntervalInBackground` + 在途去重 + `enabled` 直接给全）；或 VueUse `useIntervalFn` + `useDocumentVisibility`。
- **判断**：**部分重复**。语义上这就是 query 的 `refetchInterval`，但本项目是 SSE 主通道 + 轮询降级 + 帧空闲看门狗的双通道编排，引入 query 会与 `watchJob` 的生命周期打架。
- **替代成本与风险**：架构级，不建议。至少要先把 `REALTIME_IDLE_MS` 魔法值换成真正的 `pause()/resume()`（仓库调研 Y-14 同结论）。

### G. 其它小项
- **工作区事件总线**：`stores/workspace.ts:344-368` 手写 `Map<topic, Subscriber[]>` + try/catch；`mitt` 200B 可替。**判断：部分重复**，但 4 行实现且泛型直给，换库收益 ≈0。
- **自绘控件**：`FormRenderer.vue:86-108` 用原生 checkbox + 两个 span 手搓 switch（`el-switch` 已装）；v-html 内的 tablist/tooltip/copy 按钮同理（`AiPanel.vue:2127`）。**判断：合理**——v-html 产物里挂 EP 组件不可行，FormRenderer 的"零 EP 承载"是明确的产品/样式纪律。
- **等待 DOM 静止**：`ai-dom-tools.ts:1077`（MutationObserver 重新计时 + 30ms 静默窗 + 25ms 轮询兜底）、`:1125`（`waitFor` 弹层出现）。**判断：部分重复**（VueUse `useMutationObserver` 给观察层），但"静默窗 + 软超时如实回执"是业务判据。
- **流式 markdown 前缀缓存**：`markdown.ts:804`（`probePrefix` 用 markdown-it 顶层 token 定冻结边界）、`:917`（`renderNow` 拼前缀 + 增量）。**判断：合理自研**——这是 O(n²) 净化的针对性优化，思路与 Streamdown/LobeChat 同构；注意 **Streamdown 是 React-only**，Vue 侧没有对等成品，别把它列进"本可用库"。

---

## 三、合理自研 / 生态无合适方案（不建议替换）

| 位置 | 手搓的事 | 为什么不该换 |
|---|---|---|
| `utils/markdown.ts:571-664` | markdown 表格双区 wrapper（表头不滚 + 表体滚 + `scrollLeft` 同步 + 6px gutter） | 产物是 `v-html` 字符串，`el-table`/`el-table-v2` 无法接管；`position: sticky` 方案会改滚动条归属（注释已有实证）。 |
| `api/sse.ts:270-303` | 终帧闸门、跨轮 `runId` 丢弃、`lastSeq` 增量 | 库只做帧解析，不做业务状态机。 |
| `utils/job-watch.ts:73-252` | SSE 主通道 + 3s 轮询降级 + 帧空闲看门狗 | 双通道降级编排无成品；query 类库无法表达"pong 才是通道活着"。 |
| `stores/ai.ts:252-330` | 页签会话归属（`pageId` + Web Locks `ifAvailable` 探活） | 浏览器原生 API，无库需要；`broadcast-channel` 只能解决广播，解决不了"复制页签继承 sessionStorage"。 |
| `utils/confirm-irreversible.ts:28-60` | `ElMessageBox` + 自定义 checkbox + `beforeClose` 唯一放行闸 | 已在用 EP 的对话框，只是加了 ack 语义；无库更贴。 |
| `utils/sheet-tools.ts`（2914 行） | 表格 AI 工具：查找/替换/聚合/规范化/预览/撤销栈 | 全部基于 SpreadJS 适配器语义；`hyperformula` 解决不了"预览身份/撤销深度/选区作用域"。 |
| `utils/excel.ts:163` | 行级校验 + 主键重复（Excel 行号口径） | 与后端 `ValidationIssue` 逐字段对齐，`zod` 给不出 Excel 行号与中文原因。 |
| `scripts/build.mjs`（40 行） | TMP/TEMP 重定向 + 失败重试一次 | 只是绕杀软锁文件，不存在"手写打包器"。**确实缺失的是 bundle 分析/版本注入**，若需要加 `rollup-plugin-visualizer`（MIT，devDep，~10KB）。 |

---

## 四、明确"没有重复造"的项（避免误判）

- **虚拟列表**：全仓无虚拟滚动，也无 `el-table-v2`/`vue-virtual-scroller`；列表是后端分页 + `el-pagination`（`TaskCenterView.vue:109`、`DataBrowserView.vue:124`），大表交给 SpreadJS 自身视口渲染。**不需要引入**。
- **上传分片/断点**：`ImportWizardView.vue:48-55` 是 `el-upload :auto-upload="false"` 单次 multipart；AI 附件上限 5MB（`FormRenderer.vue:287`）。**uppy / tus-js-client 在此无收益**，除非将来要断点续传。
- **路由守卫/权限**：`router/index.ts` 只有 `afterEach` 改标题（`:79-86`），无鉴权体系，因此没有"手写权限框架"可指摘。
- **axios 拦截器**：`api/http.ts:149-162` 只做信封拆解 + 错误归一，属最小必要；无重试/缓存层，也就没有重复实现 `axios-retry`/`p-retry`。
- **Markdown 安全**：DOMPurify 用法**正确且比默认更严**——显式 `ALLOWED_TAGS/ATTR`、`ALLOW_DATA_ATTR:false`、自定 `ALLOWED_URI_REGEXP`（`markdown.ts:134`）、`afterSanitizeAttributes` 收窄 span/code/div、图片只认同源与非 svg 的 `data:image`（`:430`）、SVG 独立 profile（`:481`）。这是"用对"的样板，不是自研轮子。
- **代码高亮**：`highlight.js/lib/core` + 15 语言白名单（`markdown.ts:43-86`），是官方推荐的瘦身姿势，正确。

---

## 五、优先级建议（按"收益/风险"排序）

1. **零风险、立刻可做**：dayjs `relativeTime`+`calendar` 替 `format.ts`（顺手修 en-US 中文时间 bug）；VueUse `useClipboard` 统一 4 处复制；`useResizeObserver`/`useThrottleFn` 替 3 处 RO/2 处节流；`lru-cache` 替 4 份缓存。
2. **低风险、局部替换**：`eventsource-parser` 替 `SseChunkParser`（保留外层状态机）；`panzoom` 替 mermaid 双份 pan/zoom；`el-form rules` 替 `DefinitionsView.validateForm`。
3. **值得做但要设计**：把 `fieldValueIssues` 与后端 `GenerativeFormRules` 收敛到一份共享 JSON Schema + ajv，消掉三处重复字面量；`validateArgs` 换 zod。
4. **建议不动**：xlsx 手写 OOXML（有真实动机）、表格双区 wrapper、job-watch 双通道、sheet-tools 域逻辑、滚动跟随（回归成本 > 收益）。

**许可风险**：上述候选全部 MIT/ISC/BSD-2/Apache-2.0，无 GPL/AGPL；唯一要注意的是 SheetJS 社区版 npm 版本陈旧、`@grapecity` 本身是商业授权（现状，与替换无关）。**包体积**：VueUse 单函数 1–3KB（且已被 element-plus 带入 `node_modules`）、`eventsource-parser` ~2KB、`panzoom` ~6KB、`lru-cache` ~8KB 都可忽略；真正贵的是 `exceljs`（≈90KB gzip）与 `ajv`（≈40KB gzip），后者可按需/构建期编译规避。
