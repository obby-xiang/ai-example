> 来源=agent-cg4nt89k/output.log（2026-10-26 归档）· 外部研究（W3C/WCAG/MDN/web.dev 的阈值、机制与 profile 清单）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

以下仅基于公开资料做只读研究；未读取或发送任何密钥，未修改代码/服务/文档，未调用真实模型。

## 一、P0 口径：先把“卡死”与“慢”分开

文档给出的硬阈值如下：主线程任务超过 **50ms** 即为长任务，阻塞时间 = 任务时长 − 50ms（来源 1）；INP 的“良好”上限是 **200ms**，“差”是 **>500ms**（来源 2）；60Hz 下每帧约 **16.66ms**，留给脚本/样式/布局/绘制的实际预算常按 **10ms** 量级考虑（来源 3）；LoAF 把渲染更新延迟超过 **50ms** 的帧视为长动画帧（来源 19）。

| 现象 | 文档依据 | 建议级别 |
|---|---|---|
| 单次点击/按键到下一帧 **>500ms** | INP 的 poor 阈值是 >500ms（字段 75 分位口径） | 本项目按 **P0 候选** 处理 |
| LoAF `blockingDuration` **>500ms** | LoAF 的阻塞时长定义（来源 19） | 本项目按 **P0 候选** |
| 连续 **1s 无新帧**、输入完全无视觉反馈 | 这是项目 SLO，不是规范阈值 | **P0** |
| 长任务 50–500ms | 长任务/INP 预警区间 | P1/优化项，不直接升级 P0 |
| 后台标签页更新变慢 | Page Visibility/定时器节流（来源 12、13） | **不是**前台卡死，单独归类 |

一个容易误判的点：**页面还能滚动，不等于主线程没卡**。Chromium 会尽量把滚动放在合成线程执行，即使主线程忙，已绘制内容仍可能滚动（来源 3）。所以“能滚”不能作为“没卡死”的证据。

## 二、四类归因：网络等待 / 主线程阻塞 / 布局渲染压力 / 后台不可见更新

| 类别 | 现场证据 | 不要误判 |
|---|---|---|
| **网络等待** | 流式 chunk 之间有大间隙；间隙内没有长任务/LoAF；此时点击/键盘仍能在 200ms 内出帧；服务端 flush 时间晚于客户端回调 | chunk 回调晚也可能是主线程被占住，需同时看 LoAF 和输入延迟 |
| **主线程阻塞** | 长任务 >50ms；LoAF `scripts[].duration` 高、`blockingDuration` 高；INP 的 input delay 或 processing 高 | 不要只看“总耗时”，要把 input delay、processing、presentation delay 拆开（来源 2） |
| **布局/渲染压力** | LoAF `duration` 高但脚本占比低；`renderStart`/`styleAndLayoutStart` 靠前；layout/paint 长；DOM 节点多；存在 forced reflow | `duration` 高但 `blockingDuration` 低，更多是平滑度/jank，不一定直接伤 INP（来源 19） |
| **后台不可见更新** | `document.hidden=true`；rAF 停止；定时器变成 1 秒/次或 1 分钟/次；恢复可见时出现更新洪峰 | WebSocket/SSE、音频、IndexedDB 等可能不被节流，不能假设浏览器会自动省 CPU（来源 12） |
| **序列化/存储** | `postMessage` 结构化克隆耗时；大字符串 `JSON.stringify/parse`；`localStorage.setItem` 同步占用当前任务 | 这部分通常表现为“主线程阻塞”，但根因是数据边界，不是渲染 |

## 三、核验的一手来源与文档明确规则

W3C 主站对自动抓取返回 403；以下 WCAG 规则按 W3C GitHub 仓库正文核验，链接给规范页。来源表只写文档明确支持的内容，项目映射放在后面的检查点里，避免把推断当成规范。

| # | 资料 | 文档实际支持的规则 |
|---|---|---|
| 1 | [Optimize long tasks](https://web.dev/articles/optimize-long-tasks) | 主线程一次只做一个任务；>50ms 为长任务；阻塞时间 = 总时长 − 50ms；应拆分任务让浏览器响应更高优先级工作；`scheduler.yield()` 的续体优先执行；`setTimeout(0)` 会排到新任务，但嵌套 5 层后浏览器强制至少 5ms；可按 50ms 截止时间批量让步；不推荐 `isInputPending()` |
| 2 | [Interaction to Next Paint](https://web.dev/articles/inp) | INP 取页面生命周期内最长交互（忽略离群），字段按 75 分位；≤200ms 良好，200–500ms 需改进，>500ms 差；交互延迟 = input delay + processing + presentation delay；滚动、悬停、缩放不计入 INP；长会话应在 `visibilitychange` 时上报；event 条目默认 <104ms 不上报，`durationThreshold` 最低 16ms |
| 3 | [Rendering performance](https://web.dev/articles/rendering-performance) | 60Hz 设备每帧 16.66ms，实际应把工作压到约 10ms 内；像素管线为 JS → style → layout → paint → composite；改 width/height/left/top 会触发布局，改颜色/阴影通常只重绘，改 transform/opacity 可走合成；滚动可被优化到合成线程 |
| 4 | [Avoid large, complex layouts and layout thrashing](https://web.dev/articles/avoid-large-complex-layouts-and-layout-thrashing) | 布局通常按整个文档计算；DOM 元素数量会影响布局成本；避免 forced synchronous layout；应先读样式再写样式；DevTools 有 Forced Reflow 提示；LoAF 可用 `forcedStyleAndLayoutDuration` 归因 |
| 5 | [Avoid an excessive DOM size](https://developer.chrome.com/docs/lighthouse/performance/dom-size) | Lighthouse 在 body 约 >800 节点时警告、内容元素 >1,400 节点时报错；只在实际需要时创建节点，不需要时移除；大列表用 windowing/虚拟化；大 DOM 无法避免时简化 CSS 选择器 |
| 6 | [content-visibility: the new CSS property](https://web.dev/articles/content-visibility) | `content-visibility:auto` 让离屏元素跳过布局和绘制；自动获得 layout/style/paint containment，离屏时再加 size containment；用 `contain-intrinsic-size` 提供占位尺寸；`auto` 会记住上次渲染尺寸；离屏内容仍在 DOM 和无障碍树中；`hidden` 保留渲染状态，适合高级虚拟滚动/非活动 SPA 视图 |
| 7 | [MDN content-visibility](https://developer.mozilla.org/en-US/docs/Web/CSS/content-visibility) | `hidden` 会跳过内容，且内容不得被 find-in-page、Tab 顺序、选择、聚焦访问，类似 `display:none`；`auto` 跳过的内容仍应可被查找、Tab、聚焦、选择；`auto` 的离屏内容仍在 DOM 和无障碍树 |
| 8 | [Vue Performance Guide](https://vuejs.org/guide/best-practices/performance.html) | 上千项列表因 DOM 节点数量本身就会慢；虚拟化只渲染视口内或附近项目；保持传给子组件的 props 稳定；`v-once` 跳过未来更新，`v-memo` 跳过大型子树/`v-for` 更新；大而深的不可变数据用 `shallowRef/shallowReactive` 降低深层响应开销；组件实例比普通 DOM 节点更贵；警惕体积大的依赖 |
| 9 | [MDN Prioritized Task Scheduling API](https://developer.mozilla.org/en-US/docs/Web/API/Prioritized_Task_Scheduling_API) | `scheduler.postTask()` 支持 `user-blocking`、`user-visible`、`background` 三档优先级；`scheduler.yield()` 默认 `user-visible`，在 `postTask` 回调内会继承外层优先级；任务按优先级再按加入顺序执行；可用 `AbortSignal/TaskSignal` 取消 |
| 10 | [MDN Streams API](https://developer.mozilla.org/en-US/docs/Web/API/Streams_API) | 流式处理可以“数据一到就按块处理”，无需先缓冲成完整字符串/Blob；可检测开始/结束、串联、取消、按读取速度反应；`WritableStream` 自带背压和排队；`fetch` 成功响应的 `body` 是 `ReadableStream` |
| 11 | [MDN Using server-sent events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events) | 服务端用 `text/event-stream`，UTF-8；消息以一对换行分隔；`EventSource` 默认断线重连，`close()` 停止；不支持 HTTP/2 时，浏览器+域名最多约 6 个 SSE 连接 |
| 12 | [MDN Page Visibility API](https://developer.mozilla.org/en-US/docs/Web/API/Page_Visibility_API) | `document.hidden`/`visibilityState` 与 `visibilitychange`；iframe 可见性与父文档一致；后台标签页通常停止 rAF、节流 `setTimeout`；有基于预算的后台超时节流，预算按 10ms/秒恢复；播放音频、WebRTC、IndexedDB 等可能豁免 |
| 13 | [Chrome: Heavy throttling of chained JS timers](https://developer.chrome.com/blog/timer-throttling-in-chrome-88) | 可见或最近有声音：最小节流；隐藏 <5 分钟或链计数 <5：约 1 秒/次；隐藏 >5 分钟、链计数 ≥5、静音 ≥30 秒、无 WebRTC：约 1 分钟/次；轮询应改用 IntersectionObserver/ResizeObserver/MutationObserver/SSE/fetch streams；动画用 rAF，rAF 在隐藏时暂停 |
| 14 | [MDN Using Web Workers](https://developer.mozilla.org/en-US/docs/Web/API/Web_Workers_API/Using_web_workers) | Worker 运行在独立全局环境，不能直接操作 DOM；`postMessage` 的数据默认是复制（结构化克隆），两端各有一份；Transferable 对象可零拷贝转移，转移后原 buffer 被清空；Worker 可用 IndexedDB、fetch |
| 15 | [MDN IndexedDB](https://developer.mozilla.org/en-US/docs/Web/API/IndexedDB_API) / [HTML Web Storage](https://html.spec.whatwg.org/multipage/webstorage.html) | IndexedDB 操作异步，“so as not to block applications”，适合大量结构化数据；`localStorage/sessionStorage` 是同步的字符串键值 API，`getItem/setItem` 直接返回/写入，不是 Promise；`storage` 事件广播到其他同源窗口 |
| 16 | [WCAG 2.2.2 Pause, Stop, Hide](https://www.w3.org/WAI/WCAG22/Understanding/pause-stop-hide.html) / [2.3.3 Animation from Interactions](https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions.html) / [4.1.3 Status Messages](https://www.w3.org/WAI/WCAG21/Understanding/status-messages.html) | 2.2.2：自动开始的移动/闪烁/滚动/自动更新内容，需提供暂停、停止、隐藏或更新频率控制，除非 essential；自动更新没有 5 秒例外；暂停机制不能把焦点/页面占住。2.3.3：用户交互触发的非必要动画应可关闭或尊重减少动画偏好；必要动画允许。4.1.3：状态消息传达成功/结果/等待/进度/错误，且不通过改变上下文传递；需可程序化识别；只更新数字可能只播报“三”；live region 可能过于“话痨” |
| 17 | [WCAG Keyboard](https://www.w3.org/WAI/WCAG22/Understanding/keyboard.html) / [On Focus](https://www.w3.org/WAI/WCAG22/Understanding/on-focus.html) / [Focus Order](https://www.w3.org/WAI/WCAG22/Understanding/focus-order.html) | 指针能做的操作应有键盘等价方式；组件获得焦点时不得改变上下文（如自动提交、弹窗、把焦点移走）；焦点顺序应保持意义和可操作性；模态对话框示例：焦点进入对话框、背景 inert、关闭后回到触发按钮 |
| 18 | [MDN ARIA live regions](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/ARIA_Live_Regions) / [prefers-reduced-motion](https://developer.mozilla.org/en-US/docs/Web/CSS/@media/prefers-reduced-motion) / [inert](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Global_attributes/inert) / [aria-rowcount](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Attributes/aria-rowcount) | 通常只用 `aria-live="polite"`；`assertive` 只用于时间敏感/关键通知，会打断当前播报；`off` 不代表完全不播报，焦点在区域内时仍可能播报；`role=log/status/timer` 是隐式 live region；`aria-atomic=true` 播报整个区域；`aria-relevant` 可指定 additions/removals。`prefers-reduced-motion: reduce` 表示用户要求移除、减少或替换非必要运动。`inert` 使子树不可点击、不可聚焦，并从 Tab 顺序和无障碍树移除。表格未加载全部行时需 `aria-rowcount`（总数或 -1），示例配合 `aria-rowindex` |
| 19 | [Chrome Long Animation Frames API](https://developer.chrome.com/docs/web-platform/long-animation-frames) / [MDN PerformanceLongAnimationFrameTiming](https://developer.mozilla.org/en-US/docs/Web/API/PerformanceLongAnimationFrameTiming) | LoAF 衡量渲染更新延迟 >50ms 的帧；`blockingDuration` 是所有 >50ms 任务超出 50ms 部分加渲染时间的总和；`renderStart` 是渲染周期开始，`styleAndLayoutStart` 是样式/布局开始；脚本归因覆盖 >5ms 的脚本，含 `forcedStyleAndLayoutDuration`、`pauseDuration`；缓冲区 200 条；Chrome 123+，MDN 标为 limited/experimental |
| 20 | [MDN AbortController](https://developer.mozilla.org/en-US/docs/Web/API/AbortController) / [requestIdleCallback](https://developer.mozilla.org/en-US/docs/Web/API/Window/requestIdleCallback) | `abort()` 可中止 fetch、响应体消费和流；`requestIdleCallback` 在浏览器空闲时执行，非 Baseline，`timeout` 强烈建议，否则可能等数秒才执行 |

## 四、主题 → 资料 → 本项目检查点 → 需现场 profile 验证的边界

### 4.1 长列表 / 避免全量重渲染

**资料**：来源 5、8、6、2。

**检查点（项目映射，需实测）**
- 长会话、工具卡列表、业务表格只渲染视口内或临近视口的项；输入框、会话头、滚动容器外的固定操作区不要放进虚拟化区域，避免焦点/输入被卸载。
- 流式输出时只更新“当前这一条消息”的子树；已完成消息用稳定 key、稳定 props、`v-memo`/`memo` 或等价机制跳过更新；大而不可变的历史数据用 `shallowRef` 一类浅层响应，避免逐属性代理。
- 未虚拟化的长页面，对离屏会话块/工具块用 `content-visibility:auto` + `contain-intrinsic-size:auto <估算高度>`；`auto` 会记住上次渲染尺寸，适合无限滚动。
- 不要在每个 token 回调里重建整个消息列表；把多个 token 合并到一帧或一个短批次再写 DOM。
- 以 Lighthouse 的约 800/1,400 节点为预警线，但不要把它当唯一目标；真正的判据是滚动/点击时的 LoAF 和 INP。

**需现场 profile 验证的边界**
- body 节点数、最大深度、最大子节点数；每收一个 token 的 DOM mutation 数。
- 滚动时 LoAF 的 `duration`、`blockingDuration`、`styleAndLayoutStart`：是脚本、布局还是绘制占主导。
- 虚拟化后是否出现空白、跳动、焦点丢失、Tab 顺序错乱、find-in-page 找不到内容、屏幕阅读器行数错误。
- 1 小时/8 小时长会话后的 JS heap、detached DOM、事件监听器数量；长会话是真实场景，INP 文档明确提到标签页可能开数天/数周（来源 2）。

### 4.2 折叠 DOM / 工具卡 / 非活动视图

**资料**：来源 6、7、18、5。

**检查点**
- 先区分“用户主动折叠”和“只是滚出视口”：
  - 用户折叠、且折叠后不应被读屏/查找/Tab 访问：用 `hidden`/`display:none`，让子树离开无障碍树和 Tab 顺序。
  - 折叠后仍要保留渲染状态、但隐藏期间不需要被访问：可用 `content-visibility:hidden`；MDN 明确说其内容不可被 find-in-page、Tab、聚焦、选择，类似 `display:none`，但保留渲染状态。
  - 只是长页面中滚出视口、仍应可被查找/Tab/读屏访问：用 `content-visibility:auto`；MDN 明确说跳过内容仍可被查找、Tab、聚焦、选择。
- 不要用 `visibility:hidden` 折叠长内容：它仍占几何空间，不会真正减少布局占位（来源 6）。
- 非活动 SPA 视图可保留在 DOM，用 `content-visibility:hidden` 保持缓存状态，返回时更快（来源 6）。
- 面板/对话框打开时，背景用 `inert`，而不是只加半透明遮罩；`inert` 会移出 Tab 顺序和无障碍树，并阻止点击/聚焦（来源 18）。
- `content-visibility:auto` 有一个无障碍陷阱：子树内原本用 `display:none/visibility:hidden` 隐藏的装饰内容，离屏时可能仍进入无障碍树；需要时加 `aria-hidden="true"`（来源 6）。

**需现场 profile 验证的边界**
- 展开/折叠的延迟、布局偏移、滚动位置是否保持；`contain-intrinsic-size` 估值不准时是否造成滚动条跳动。
- 折叠前后无障碍树、Tab 顺序、find-in-page 行为是否符合预期。
- `content-visibility` 用在表格行/复杂 grid 上是否真的生效；不要假设对 `tr` 一定适用，必须单独测。
- 折叠/展开后焦点是否回到触发按钮；读屏是否重复播报。

### 4.3 流式 Markdown / 图表 / 序列化与存储

**资料**：来源 10、11、20、14、15、3。

**检查点**
- 用 `response.body.getReader()` 增量读取，不要等整个响应结束再解析；这是 Streams API 的直接用法（来源 10）。
- 每个 token 不直接 `setState`/改 DOM；按帧或短批次合并写入。这个“批处理”是项目策略，不是规范要求，需用 LoAF 验证批次大小。
- Markdown 解析、JSON 解析、图表数据变换如果造成 >50ms 长任务，移到 Worker；Worker 不能碰 DOM，结果回主线程再渲染（来源 14）。
- Worker 传大对象默认是结构化克隆复制，字符串/对象会在两端各一份；大二进制用 Transferable 零拷贝，转移后原 buffer 失效（来源 14）。
- 图表：用 rAF 驱动 canvas 更新，或 CSS/Web Animations 做可合成动画；不要用 `setInterval` 做逐帧动画；隐藏时 rAF 会暂停（来源 13）。若图表每帧重建大量 SVG 节点，按 DOM/布局压力处理，但**没有找到可核验的图表库官方性能基准**，不能直接下结论。
- 持久化：长会话按“追加 delta + 定期快照”存，不要每个 token 把整个会话 `JSON.stringify`；大量结构化数据用 IndexedDB（异步），`localStorage` 只放小设置。`localStorage` 是同步 API，大字符串读写会占用当前任务（来源 15）。
- 用户点“停止”、切换会话、卸载组件时，用 `AbortController` 中止 fetch/流消费，SSE 用 `close()`；否则后台还在解析/渲染（来源 20、11）。

**需现场 profile 验证的边界**
- chunk 到达时间 vs 帧时间：如果 chunk 间隙大但 LoAF 为空、交互仍快，归为网络/服务端；如果 chunk 回调被长任务推迟，归为主线程。
- 每个 chunk 的解析耗时、DOM 写入耗时、布局/绘制耗时；Markdown 解析是否单独超过 50ms。
- `postMessage`/结构化克隆的耗时与 payload 大小；Transferable 是否真的避免了复制。
- IndexedDB 写入延迟、事务排队；`localStorage.setItem` 在真实会话大小下的毫秒数。
- SSE 断线重连是否重复消息、是否按 `id` 去重；多标签页下是否触发 6 连接上限（来源 11）。
- 图表更新时 SVG 节点数、layout/paint 时间；canvas 是否因尺寸变化触发重排。

### 4.4 同步渲染 / yield / Worker

**资料**：来源 1、9、14、4、19。

**检查点**
- 把长循环/长渲染拆成块；每块结束检查 `performance.now()`，超过约 50ms 就让步；`scheduler.yield()` 可用则用，不可用回退 `setTimeout(0)`（来源 1）。
- 优先级映射：立即反馈（按钮按下、 spinner、乐观状态）用 `user-blocking`；普通消息渲染用 `user-visible`；持久化、分析、预取用 `background`（来源 9）。
- CPU 密集的 Markdown/JSON/图表数据变换放 Worker；DOM 写入留在主线程；不要把“渲染”整体丢进 Worker（来源 14）。
- 避免 forced synchronous layout：先读 `offsetWidth/scrollHeight` 等，再写样式；不要在循环里读-写-读-写（来源 4）。
- 不要用 `isInputPending()` 作为唯一让出条件；不要用 `setTimeout` 链做动画，用 rAF（来源 1、13）。

**需现场 profile 验证的边界**
- LoAF 的 `blockingDuration`、`duration`、`scripts[].duration`、`forcedStyleAndLayoutDuration`、`pauseDuration`；长任务数量与分布。
- INP 归因：input delay 高还是有事件处理/渲染高；`event` 条目默认可能漏报 <104ms，需要调整 `durationThreshold`（来源 2）。
- Worker 消息往返延迟、复制数据量、Transferable 是否生效；Worker 初始化成本是否摊薄。
- `scheduler.yield` 在目标浏览器的支持情况；回退路径是否仍然可交互。
- 让步批次是 16ms、50ms 还是按工作量；批次太小会让中断/恢复开销累积（来源 1 明确提醒）。

### 4.5 可见性 / 计时器 / 后台更新

**资料**：来源 12、13、20、2。

**检查点**
- 监听 `visibilitychange`：隐藏时暂停倒计时 UI、图表动画、自动滚动、逐 token 渲染；网络流可以继续收，但把 UI 更新合并/缓冲。
- 恢复可见时按时间戳和权威状态“对账”，一次性 flush，不要回放隐藏期间每一个 token。
- 倒计时按目标时间计算剩余，不要按 tick 累加；隐藏 >5 分钟且满足链式/静音条件时，定时器可能变成 1 分钟/次（来源 13），丢 tick 是正常现象，不应报错。
- 不要用 `setInterval` 轮询服务器；用 SSE/WebSocket/fetch streams。若必须轮询，隐藏时停掉（来源 13）。
- 离屏图表/视频用 IntersectionObserver 暂停；标签页级用 Page Visibility；不要用 `focus/blur` 判断标签页是否隐藏，MDN 明确说这是不完美的代理（来源 12）。
- `requestIdleCallback` 只用于非必要的持久化/清理，且带 `timeout`；它非 Baseline，可能延迟数秒（来源 20）。

**需现场 profile 验证的边界**
- 隐藏 1 分钟/10 分钟后的定时器实际触发间隔；恢复可见后第一帧的 LoAF/长任务；积压更新数量。
- 隐藏期间 SSE/WebSocket 是否仍收包、内存是否增长；恢复时是否一次性重渲染导致洪峰。
- 后台标签页是否因音频/WebRTC/IndexedDB 被豁免节流；不要假设“后台一定省 CPU”。
- 长会话在 `visibilitychange` 时上报 INP/性能数据，避免依赖 `unload`（来源 2）。

### 4.6 焦点 / 键盘 / ARIA live / 减少动画

**资料**：来源 16、17、18、14。

**检查点**
- 流式输出不要把 `aria-live` 挂在每个 token 的容器上；用稳定的 `role="log"` 或 `role="status"` + `aria-live="polite"` 播报“已完成/失败/正在生成”这类状态；只有关键错误才考虑 `assertive`（来源 18）。
- 工具卡状态用专用状态节点，更新文本；`aria-atomic="true"` 让“3 items”整体播报，而不是只读“3”（来源 16、18）。
- `role="timer"` 是隐式 live region，但按 MDN 的 off 语义，通常只在焦点位于其内部时播报；不要指望它每秒自动播报，也不要把每秒 tick 都塞进 live region（来源 18）。
- 焦点：流式更新时输入框保持焦点；不要自动把焦点移到新消息；模态 AI 面板打开时焦点进入面板、背景 `inert`、关闭后回到触发按钮；非模态面板不要抢焦点（来源 17、18）。
- 键盘：展开/折叠、复制、重试、停止、关闭都必须有键盘等价操作；Tab 顺序符合视觉/阅读顺序；折叠内容不能留下可聚焦的隐藏节点（来源 17、18）。
- 减少动画：用 `prefers-reduced-motion: reduce` 关闭/替换面板滑动、图表动画、打字光标闪烁等非必要运动；交互触发的动画按 WCAG 2.3.3，自动开始的运动/自动更新按 2.2.2（来源 16、18）。
- 自动更新的计时器/状态若与页面其他内容并行，按 2.2.2 评估是否需要暂停/停止/隐藏或更新频率控制；自动更新没有 5 秒例外（来源 16）。

**需现场 profile 验证的边界**
- 用 NVDA/VoiceOver 实测：流式过程中是否重复播报、打断、播报半句话；完成后是否只播报一次。
- 展开/折叠、面板开关、停止生成后的焦点位置；Tab 顺序；虚拟列表是否让焦点跳走。
- 减少动画开启后，面板/图表/光标是否真的停止或降级；是否仍有自动播放的运动。
- 2.2.2 的暂停控件是否可用键盘操作、是否会把焦点占住；这是 WCAG 明确要求。

### 4.7 业务表格

**资料**：来源 5、8、18、4、6。

**检查点**
- 只有测到滚动/排序/筛选出现长任务或布局压力时才虚拟化；虚拟化后表头、筛选器、分页器留在虚拟区域外，行 key 稳定。
- 如果 DOM 中不是全部行，设置 `aria-rowcount` 为总行数；总数未知用 `-1`；示例配合 `aria-rowindex` 标真实行号（来源 18）。
- 排序/筛选只计算一次，不要每行重复计算；用 `v-memo`/memo/浅层响应减少全表重渲染（来源 8）。
- 列宽拖拽、横向滚动、行高变化时，先读后写，避免 forced reflow（来源 4）。
- 长单元格内容折叠；卡片式行可评估 `content-visibility:auto`，但表格行是否适用必须实测，不要假设。
- 虚拟化表格 + 屏幕阅读器是高风险组合：行数、行号、表头关联、键盘网格导航都要单独验证。

**需现场 profile 验证的边界**
- 真实行数 vs DOM 行数；滚动时 LoAF 的 layout/paint 占比；排序/筛选用时。
- 列宽拖拽是否触发整表布局；`offsetWidth` 读取是否造成 forced reflow。
- 读屏行数/行号是否正确；键盘能否遍历；find-in-page 是否还能找到未渲染行。
- 虚拟化后复制/导出是否包含未渲染数据；这是产品行为，不是性能问题，但必须一起验。

### 4.8 AI 面板 / 长表单

**资料**：来源 2、16、17、18、14、8。

**检查点**
- 面板打开/关闭：模态则焦点进入、背景 `inert`、关闭回到触发按钮；非模态则不抢焦点，输入框保持可用（来源 17、18）。
- 长表单与流式区域隔离：不要把表单放在会因 token 更新而重建的子树里；稳定 key，保持输入框、光标、选区、滚动位置。
- 工具卡完成时不要自动滚动/自动聚焦；用 `role="status"` 播报“完成/失败”，让用户自己决定是否查看。
- 长会话用虚拟化或 `content-visibility:auto` 控制 DOM；输入框/发送按钮必须常驻，不能被虚拟列表卸载。
- 面板/消息的出现动画尊重 `prefers-reduced-motion`；不要用闪烁/抖动表达“正在生成”（来源 16、18）。
- 发送、停止、重试、展开工具卡、复制等操作全部键盘可达；不要只靠 hover 才能出现操作按钮（来源 17）。

**需现场 profile 验证的边界**
- 打开面板、发送、首 token、完成、展开工具卡、停止、切会话的逐项 INP；是 input delay、processing 还是 presentation delay。
- 流式更新期间输入框是否丢焦点/丢选区；表单输入延迟是否被消息渲染拖慢。
- 读屏播报是否过多；完成后是否只播报一次；错误是否用 `assertive` 过度打断。
- 1 小时长会话的内存、DOM、滚动性能；后台 10 分钟后恢复的更新洪峰。

## 五、不建议默认引入大依赖

优先用平台能力：`content-visibility`/`contain-intrinsic-size`、`scheduler.yield`/`postTask`、Streams、Page Visibility、IntersectionObserver、`inert`、`prefers-reduced-motion`。这些都不需要引入大依赖。

虚拟列表库只在“测到问题”后引入：body 节点接近/超过 800–1,400 预警线、滚动 LoAF 以布局为主、或一次渲染造成 >50ms 长任务。引入前评估包体积、无障碍（行数/行号/焦点/find-in-page）和维护成本；Vue 官方性能指南也明确提醒警惕体积大的依赖（来源 8）。Markdown 解析器、图表库同理：不要因为“流式”就默认换库，先用现有实现测解析/渲染耗时，再决定是否 Worker 化或替换。

## 六、现场 profile 最小清单

1. **场景**：200+ 轮长会话、1 小时以上会话、10k 行业务表格、100 字段长表单、工具卡频繁展开、流式中途停止、标签页隐藏 10 分钟后恢复。
2. **设备/网络**：真实中端机或 4x–6x CPU 降速；网络慢速只用于制造“网络等待”，不要用它解释主线程卡顿。
3. **记录**：Chrome DevTools Performance 录打开/发送/滚动/展开/隐藏/恢复；同时用 `PerformanceObserver` 收 `longtask`、`long-animation-frame`、`event`。
4. **归因顺序**：先看 chunk 间隙与 LoAF 是否重叠 → 再看 `blockingDuration` 与 `scripts` → 再看 `renderStart/styleAndLayoutStart` 与 DOM 规模 → 最后看后台节流/恢复洪峰。
5. **无障碍**：键盘走完整流程、NVDA/VoiceOver 听流式播报、开减少动画、检查折叠后 Tab/读屏、虚拟表格行数/行号。
6. **长会话**：记录 heap、detached nodes、监听器、IndexedDB 体积；不要只测首屏。

## 七、结论

文档能支持的是阈值和机制：50ms 长任务、200/500ms INP、16.66ms 帧、800/1,400 DOM 预警、隐藏标签页 1 秒/1 分钟节流、`content-visibility` 的跳过渲染与无障碍差异、`aria-live` 的 polite/assertive 语义、WCAG 的状态消息/暂停隐藏/减少动画/焦点顺序要求。**这些规则不能直接证明本项目哪里卡**。

最可能触发 P0 的路径，按现有资料排序是：每个 token 全量重渲染会话 → 主线程 Markdown/JSON 解析 → 流式更新引发布局抖动/forced reflow → 长会话 DOM 无界增长 → 后台恢复时一次性回放积压更新。每一条都必须用 LoAF/INP/DOM/内存数据确认；确认前不要用“加虚拟列表/加 Worker/加缓存”作为默认答案。
