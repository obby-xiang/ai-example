> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1173` 的 wire 末条 assistant 文本（消息序号 m=22；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-19 20:23 → 2026-10-19 20:26（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：21719 B / 14889 字符 / 280 行；行指纹覆盖率（本批复算）8.2%（最高单文件 7%：`docs/evidence/补档-问题4-composer-修后复验.md`）
> - 类别：**过程核验**
> - 与既有件关系：补档-问题4；报告口径覆盖率 0.07
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
已读我自身 wire 的 `profile.bind` 行，确认自证信息如下：`agentId=agent-1173`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=explore`、`thinkingEffort=high`。全程只读，未写入、未启动服务、未提交。

---

## 1. 看板与 demo 的口径原文

### 1.1 `docs/任务看板.md:39`（问题5 行，整行原文）

> | 批·问题5 窄栏落地 | 窄栏口径落地（右侧窄栏 ≈400px：composer 4 行 101px 上限、面板高 15–20% 预算） | 📋 | **在途**（10-19 验收实测：**页面级 360/400 横向溢出 FAIL**（归本项）；composer 4 行 / 101px 上限已随 `60afe6f` 回落；v7.11 仅历史引用） | demo `docs/demo/chat-ux/composer-decisions.html`（v2，approved）· 仓外 `_cu-composer-project-r26/browser/final/report.md`（页面级 FAIL / composer 内部 PASS）· 历史 `docs/demo/chat-ux/archive/r165-chat-ui-v7.11.html`（仅历史引用） |

注意：该行首的状态图标是 **📋**，正文写「**在途**」；这与任务卡里写的「🔧在途」不一致。看板原文并没有 🔧。

### 1.2 `docs/决策看板.md:15`（整行原文）

> | 批·问题4 composer v2 项目回落（工程口径，非用户原话） | ✅ | composer 内部 PASS：4 行 / 流式可编辑 / Enter 静默 / 无排队 / 失败保稿防重复；3 代码文件已入库（入库锚 `60afe6f`）。**边界**：页面级 360/400 横向溢出 = 既有问题5（`App.vue` / styles 本批 0 diff，不归本批但仍 FAIL）；真实中文 IME 未实机 **BLOCKED**；live HITL 未复验（不影响本批，不得称全浏览器通过）；最终审查 **READY**（0 Critical / 0 阻断 Important / 7 Minor） | 3 文件 = `frontend/src/components/AiPanel/AiPanel.vue`、`frontend/src/stores/ai.ts`、`frontend/tests/ai-panel-parts-render.spec.ts` · 本批入库锚 `60afe6f`（diff 锚 `aa138aa`）· 证据 仓外 `_cu-composer-project-r26/`：`final-review-verify.log`（targeted 173/173 + full 444/444）、`final-review-build.log`（build ✓）、`gate-check-docs.log` 6/6、`gate-diff-check.log` 0、`browser/final/report.md`、`final-review-report.md`（READY）· 闭合 commit `5274419` |

### 1.3 `docs/demo/README.md:59`（v7.11 窄栏口径，整行原文）

> | `chat-ux/archive/r165-chat-ui-v7.11.html` + `chat-ux/archive/r165-chat-ui-v7.11.css` | R-165（累积版 v7~v7.12 族） | v7.11（**累积归档版**：对话输入框重画） | 归档 | 2026-10-16 | 累积版倒数第二版：对话输入框重画（自适应 1 行 ≈40px → 上限 4 行 101px 内滚 / 可编辑白底 + 清晰边框 / 盒内工具栏钉底 / 圆形发送钮 / 两钮悬停 tip / Enter 三缺陷修复 / 运行中 Enter 排队）+ 窄栏口径（右侧窄栏 ≈400px，composer ≤ 面板高 15–20%）；v7.11 区块 **3 勾位待用户裁决**（①形态 ②流式可编辑行为变更 ③运行中 Enter 排队）—— **2026-10-19 已由 composer v2 终裁 supersede（4 行 / 流式 Enter 无响应 / 无排队）**；**入库已脱敏**（工作区标签 2 处改占位）+ 外链改入库名 | 仓外 `r163-demo/INDEX.md`（版本登记）+ 本页「现役主题单文件」节 | 仓外 `r163-demo/v7.11.html` + `v7.11.css` |

### 1.4 `docs/demo/README.md:62`（现行 composer demo 行，整行原文）

> | `chat-ux/composer-decisions.html` | ai-example 问题4（composer 输入区方案批准） | v2（`data-composer-decisions="2026-10-18-v2"`；主题单文件） | 定稿 | 2026-10-18 | 问题4 composer 方案批准稿（已批准）：① 最多 4 行（1 行 40px 起、自适应增高、4 行 101px 封顶、白底可编辑、圆形发送钮）② 流式可编辑且普通 Enter 无响应（不发送、不换行、不提示；Shift+Enter 换行；停止钮只停止模拟，不自动发送；圆形中性停止钮）③ 不做发送排队（已裁）。①②③ 均为最终口径，页面 `data-review-status="approved"`；v2 版本与交互 / 布局不变，仅状态转 approved。用户 2026-10-19（批准日）「通过，开始吧」批准项目回落（见 `docs/任务看板.md` 10-19，已入库 `60afe6f`）。外链复用 `chat-ux/archive/r165-chat-ui-v7.11.css`，未另配同名 css；零脱敏命中、无仓外源件 | 用户 2026-10-18 口头「待我裁决的我没看到效果不好裁决，你先demo出来看看，新demo哈。」；后续直裁「流式中按 Enter 不做任何响应，不需要提示」「不做发送排队」；2026-10-19 批准「通过，开始吧」 | 仓内新建（无仓外源件） |

---

## 2. FAIL 现场证据

### 2.1 发生页面与视口

页面 = 真实项目 `http://localhost:5202/tasks`（`_cu-composer-project-r26/browser/final/report.md:6`）。视口 = **360px 与 400px** 两档（`:67-77`）。

### 2.2 实测数据（`browser/final/report.md:67-77` 原表）

| 项 | 360 | 400 |
|---|---:|---:|
| document `scrollWidth` | **493** | **493** |
| page-level overflow | **FAIL** | **FAIL** |
| header 右按钮 right | 493.13 | 493.13 |
| aside 展开 | 400px，right 432 | 400px，right 432 |
| composer wrap | 368px，right 415 | 368px，right 415 |
| wrap 在 aside 内 | **true** | **true** |
| composer 子元素越出 aside | **[]** | **[]** |
| 收起后 scrollWidth | **493** | **493** |
| 收起后 aside | 48px | 48px |

溢出量（由上表直接相减）：360 视口 = 493−360 = **133px**；400 视口 = 493−400 = **93px**。aside 右缘 432：在 360 视口超出 **72px**，在 400 视口超出 **32px**。

### 2.3 是页面横向滚动，还是元素破框

两者同时存在，但页面级滚动的主因是 **header 内容破框**，不是 composer：

> **页面级 FAIL**：493 仍由 header nowrap 菜单 + “隐藏/显示 AI 栏”按钮决定；收起面板后仍 493（header 右按钮 right 493.13）。这是既有问题5页面壳层，不是本批引入；`App.vue/styles` 本批 0 diff。 —— `browser/final/report.md:79`

> **composer 内部 PASS**：composer 368px 完全在 400px aside 内，right 415 < aside 432，`descendantBeyondAside=[]`；没有 composer 内部新增横向溢出。360/400 下 composer 被裁是父面板整体越界/视口裁切，不是 composer 内容越界。 —— `browser/final/report.md:80`

具体机制（`browser/scope-diagnosis/report.md:22-28`）：

- header 自身盒子宽只有 360/400，但 `display:flex; flex-wrap:nowrap; justify-content:space-between; overflow:visible`；
- `headerLeft` 实际 **408.11px**，`el-menu` **374.11px**；`el-menu :ellipsis="false"`（`App.vue:9`）使菜单不省略；
- 右侧 toggle 默认 `white-space:nowrap`，宽 **69.02px**；合计右缘 **493.13**；
- header 内容越出自身盒子，被 `overflow:visible` 计入 document 横向滚动。

additional element-level overflow（`:27`）：aside 为 `width:400px; flex:0 0 auto; flex-shrink:0; overflow:hidden`，x32..432，是第二处越出视口的元素，并把 `section` 挤到 **32px**。`section` 自身有 `overflow-x:hidden`（`App.vue:27`），所以内部 1250px 表格被裁在 section 内，**不贡献** document scrollWidth（`scope-diagnosis/report.md:28`）。

收起后仍 493 的直接证据：`scope-diagnosis/collapse-scroll.json` 记录 360 收起后 `scrollX=133, sw=493, aside width=48`；400 收起后 `scrollX=93, sw=493, aside width=48`。这说明 493 由 header 最小内容宽度决定，不由面板宽度决定（`scope-diagnosis/report.md:45-49`）。

### 2.4 截图清单

- `_cu-composer-project-r26/browser/final/shots/final-page-360-expanded.png`
- `_cu-composer-project-r26/browser/final/shots/final-page-360-collapsed.png`
- `_cu-composer-project-r26/browser/final/shots/final-page-400-expanded.png`
- `_cu-composer-project-r26/browser/final/shots/final-page-400-collapsed.png`
- 根因阶段另有：`scope-diagnosis/expanded-360.png`、`expanded-400.png`、`collapsed-360.png`、`collapsed-400.png`（`scope-diagnosis/shots/` 同名）
- 更早浏览器验收：`browser/shots/project-360.png`、`project-400.png`、`project-360-collapsed.png`

### 2.5 其他诊断件的作用

- `_cu-composer-project-r26/browser/scope-diagnosis/report.md` 是唯一给出溢出根因结论的件（§1，`18-60`）。
- `boxscan.mjs` / `css-headers.txt` 在 `_cu-composer-decisions-r26/`，不在 `_cu-composer-project-r26/`。`boxscan.mjs` 是 PNG 像素扫描脚本，用于量边框/背景行，**不分析横向溢出**；`css-headers.txt` 只是 demo CSS 的 HTTP 200 响应头（`Content-Type: text/css; charset=utf-8`），也**不是溢出根因结论**。
- 引用行号有漂移：`final/report.md:85` 与 `scope-diagnosis/report.md:57` 都写 `docs/任务看板.md:26`，但当前问题5 行实际在 `:39`。

---

## 3. 项目布局现状

### 3.1 右侧面板宽度定义

`frontend/src/App.vue:30-35`：

```vue
<el-aside
  :width="panelWidth"
  class="border-l border-solid border-[#e4e7ed] bg-white transition-[width] duration-200 overflow-hidden"
>
  <AiPanel />
</el-aside>
```

宽度来自 `frontend/src/App.vue:72`：

```ts
/** AI 栏宽度：蓝本 400px 展开 / 48px 收起。 */
const panelWidth = computed(() => (ai.expanded ? '400px' : '48px'))
```

结论：**固定 px 字符串，经 Element Plus `el-aside` 的 `width` prop 落地，不是百分比、不是 Tailwind 宽度类**。`el-aside` 默认自带 `flex-shrink:0`（`frontend/node_modules/element-plus/theme-chalk/el-aside.css:1`：`.el-aside{...width:var(--el-aside-width,300px);flex-shrink:0;overflow:auto}`），所以在窄视口下不会收缩。

### 3.2 头部与主内容区

`frontend/src/App.vue:3-22`：

- header：`class="flex items-center justify-between px-4 py-2 bg-white border-b border-gray-200"`，**无 `flex-wrap`、无 `min-w-0`、无响应式断点类**。
- 左组：`App.vue:4` `class="flex items-center gap-4"`，同样无 `min-w-0`。
- `el-menu`：`App.vue:6-12`，`:ellipsis="false"`（`App.vue:9`）——这是菜单最小内容宽 374.11 不被省略的直接原因。
- 右侧按钮：`App.vue:19-21`，`el-button link` 文案「隐藏 AI 栏 / 显示 AI 栏」，默认 nowrap，实测 69.02px。

主内容区 `frontend/src/App.vue:25-29`：

```vue
<main class="flex-1 flex min-h-0">
  <section class="flex-1 min-w-0 min-h-0 overflow-y-auto overflow-x-hidden p-4">
```

结论：业务区**已经有 `min-w-0` + `overflow-x-hidden`**，没有会撑破页面的 min-width；内部宽表被裁在 section 内，不贡献页面 scrollWidth。页面溢出的责任在 header 的 nowrap 内容，以及不收缩的固定 400px aside。

### 3.3 360/400 断点行为

在 `App.vue`、`AiPanel.vue`、`main.css` 中检索 `sm:` / `md:` / `lg:` / `xl:` 均为**零命中**，也没有 `@media`。因此：

- 360/400 视口下**没有任何断点切换**；
- aside 仍是 400px；
- header 仍是 nowrap 单行；
- 布局不会自动改为上下堆叠或收窄侧栏。

### 3.4 AiPanel 与 composer 的自适应

- AiPanel 展开根容器：`frontend/src/components/AiPanel/AiPanel.vue:14`，`class="ai-chat h-full flex flex-col ..."`，无固定宽度，随 aside 填满。
- 消息区：`AiPanel.vue:123-130`，`flex-1 relative min-h-0 flex flex-col` + `flex-1 overflow-y-auto overflow-x-hidden p-3`。
- composer 外层：`AiPanel.vue:727-734`；输入框 `AiPanel.vue:741`：`block min-h-[40px] max-h-[101px] w-full resize-none overflow-y-auto ...`；工具栏与按钮 `AiPanel.vue:748-770`。
- 这与证据一致：composer wrap 368px 在 400px aside 内，子元素没有越出 aside。

### 3.5 手写 CSS 存量与 Tailwind 纪律

- `frontend/src/styles/main.css` 是项目**唯一**手写 CSS 文件（44 行）。文件头 `main.css:1-5` 原文：

  > `/* R-165 S5c：AI 面板样式已全量 Tailwind 化 —— 原 @import './ai-chat.css'（362 行手写，含 .md-body 排版族 / 三个 keyframes / EP 覆写块）随文件一并拆除： ... */`

  即 `ai-chat.css` 已按「违例存量」口径拆除，当前仓库里已不存在该文件。
- 现存手写部分：
  - `main.css:11-19`：`html, body, #app` 复位（preflight 关闭后的布局必需）；
  - `main.css:21-27`：全局 `box-sizing: border-box` 兜底；
  - `main.css:29-34`：body 背景/文字/字体；
  - `main.css:36-43`：`@layer utilities { .writing-vertical { writing-mode: vertical-rl; letter-spacing: 2px; } }`，注释明确写「Tailwind 无 writing-mode 工具类」。
- `frontend/tailwind.config.js:4-7`：`preflight: false`；`:14-39`：`aiBlink` / `aiBreathe` / `aiFadeOut` 三个 keyframes 与 animation（注释称逐字取自原 `ai-chat.css`）。
- 全 `frontend/src`：`<style` 块 **0 处**；`style="` 仅 `SpreadGrid.vue:6` 的动态 `:style="{ height }"`，以及 `utils/excel.ts:424` 的离屏打印容器 `host.style.cssText`。没有发现新的手写样式表。
- 纪律原文（仓外热档）：
  - `handoff-archive/交接提示词-2026-10-13-当前态.md:98`：①「项目中所有样式必须使用 TailwindCSS，**不允许手写任何样式**（禁手写 CSS 文件/`<style>` 块/行内 style…）」；③「复用方式 = **Tailwind 工具类组合复用，禁往 tailwind.config 配置文件新增**」；④「**ai-chat.css 等手写样式表属违例存量，整改方向=拆除**」。
  - 同上 `:99`：「**demo 先行纪律**：凡涉及设计与交互的改动，一律先改 demo…用户看图确认后才允许应用到项目仓库；未确认禁施工入库。」

> 需要主智能体注意的存量口径矛盾：热档 `:98` 第③条写「禁往 tailwind.config 配置文件新增」，但当前 `tailwind.config.js:14-39` 确实新增了 keyframes/animation（R-165 S5c，替代原 `ai-chat.css`）。这属于存量口径冲突/例外，呈报时最好显式说明，别把「0 手写 CSS」误写成「config 也零新增」。

---

## 4. demo 现状

### 4.1 相关 demo 件

- **现行 composer / 窄栏主题件**：`docs/demo/chat-ux/composer-decisions.html`（v2，`data-review-status="approved"`），README 索引行 `docs/demo/README.md:62`。
- **窄栏口径的来源（历史件）**：`docs/demo/chat-ux/archive/r165-chat-ui-v7.11.html` + `archive/r165-chat-ui-v7.11.css`，README 索引行 `docs/demo/README.md:59`。composer 具体勾位已被 v2 终裁 supersede，但「右侧窄栏 ≈400px、composer ≤ 面板高 15–20%」的窄栏语句仍在这行。

### 4.2 demo 的 360/400 先例：有，但只到「单栏页面」层

`composer-decisions.html` 的页面骨架是单列收窄：

- `docs/demo/chat-ux/composer-decisions.html:12`：`<main class="w-full max-w-[600px] mx-auto px-[10px] py-[12px]">`
- `:13`：`<div class="w-[420px] max-w-full mx-auto">`

所以在 360/400 视口，内层 420px 会被 `max-w-full` 收窄，页面没有固定 400px aside，也没有项目 header 菜单。

实测先例：

- `_cu-composer-decisions-r26/acceptance.json:26`：`{ "id": "5", "item": "360/400 视口无横向溢出；tip/chip/按钮不遮挡主要操作", "verdict": "PASS", ... }`
- `_cu-composer-decisions-r26/acceptance.json:111-112`：
  - 360：`scrollWidth=360`、`hasHorizontalOverflow=false`、`overflowingVisibleElements=0`
  - 400：`scrollWidth=400`、`hasHorizontalOverflow=false`、`overflowingVisibleElements=0`
- `_cu-composer-decisions-r26/v2/report.json:30`：`"overflow_360_400": "PASS"`；`:93-95` 同口径数据。
- `_cu-composer-project-r26/browser/report.md:48` 明确对照：「这是真实项目页面级响应式缺陷；**demo v2 在 360/400 无此问题**。」

### 4.3 关键缺口

现有 demo 的 360/400 PASS **不能直接证明项目页面壳层会 PASS**：

- 它没有项目里的 header（nowrap 菜单 + 右侧 toggle）；
- 它没有固定 400px 的右侧 aside；
- 它没有「400px 面板挤压业务区」的结构；
- 它是单列 `max-w-full`，天然不会横向溢出。

另外，v7.11 历史 demo 的 400px 面板写法是 `flex-[0_0_400px] w-[400px] max-w-full`（`archive/r165-chat-ui-v7.11.html:720`），带 `max-w-full`；项目 `el-aside` 没有等效的 `max-width`。这是 demo 与项目在窄栏行为上的直接结构差异。

结论：**demo 先行在 360/400 上已有「composer 单列」先例，但还没有「项目页面壳层 + 固定 400 右侧栏」的 360/400 先例。** 按 demo 先行纪律，问题5 若要施工，需先补一个覆盖 header + 固定 400 aside + 360/400 视口的 demo，且用户确认后才能回落项目。

---

## 5. 验收口径：什么算「页面级 360/400 PASS」

### 5.1 显式验收项

`docs/evidence/补档-问题4-composer-浏览器验收.md`：

> `:18` | 1 | 视觉/几何：360/400 无横向溢出 | **FAIL** |
>
> `:24` | 5 | 360/400 无内溢 | **FAIL** |
>
> `:28` | — | **总体** | **FAIL（2 个显式验收项失败 + 2 个 BLOCKED）** |
>
> `:138` 1. **FAIL：360/400 横向溢出**。真实项目页面 `scrollWidth 493`，AI 面板 400px、头部按钮越界；收起面板仍 493。影响窄栏可用性，是显式验收项失败。

即验收原文只有两句：**「360/400 无横向溢出」**与**「360/400 无内溢」**，没有区分页面级/面板级。

### 5.2 判定实现口径

demo 侧的精确量测脚本 `_cu-composer-decisions-r26/v2/v2-verify.cjs:249-263` 定义：

```js
for (const width of [360, 400]) {
  await page.setViewportSize({ width, height: 800 });
  await page.reload({ waitUntil: 'networkidle' });
  out.overflow[width] = await page.evaluate(() => {
    const de = document.documentElement;
    const over = [];
    for (const el of document.querySelectorAll('body *')) {
      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) continue;
      const b = el.getBoundingClientRect();
      if (b.width > 0 && (b.right > innerWidth + 0.5 || b.left < -0.5)) over.push(...)
    }
    return { innerWidth, clientWidth: de.clientWidth, scrollWidth: de.scrollWidth,
             hasHorizontalOverflow: de.scrollWidth > de.clientWidth + 1,
             overCount: over.length, over };
  });
}
```

可据此还原 PASS 标准：

| 维度 | PASS 条件 | 当前项目 |
|---|---|---|
| 视口 | 360px 与 400px 两档 | 两档都测了 |
| 页面横向滚动 | `documentElement.scrollWidth <= clientWidth + 1` | 493 > 360/400，**FAIL** |
| 可见元素越界 | 可见元素 `rect.right <= innerWidth + 0.5` 且 `rect.left >= -0.5`，`overCount=0` | header 右按钮 right 493.13、aside right 432，**FAIL** |
| 容差 | scrollWidth 允许 **+1px**；元素边界允许 **+0.5px** | 493 远超容差 |
| 忽略 | `display:none` / `visibility:hidden` / `opacity:0` 元素不计 | — |

“允许/不允许”归纳：允许 1px 量测误差；不允许 document 级横向滚动，也不允许任何可见元素越出视口。composer 内部「wrap 在 aside 内、`descendantBeyondAside=[]`」是**另一个更窄的判据**，只能证明 composer 内部 PASS，不能替代页面级判据（`final/report.md:80`；`final-review-report.md:130`、`:174`）。

### 5.3 必须呈报的口径冲突

历史上「卡片通道」批用的是**面板级**判据，并且明确容忍页面级滚动：

- `docs/evidence/补档-卡片通道-最终独立验收.md:72-73`：
  > 卡头横向溢出 | `scrollWidth==clientWidth`（350/350） | 310/310 | **PASS**
  > 面板横向溢出 | 396/396 | 356/356 | **PASS**
- 同件 `:81`：
  > 说明：右侧固定 400px 面板在 viewport 360 时页面整体会横向滚动，这是既有布局（业务区固定表宽 + 400px 面板）而非卡头引入；面板内部与卡头均无横向溢出，故按“面板 360/400 无横向溢出”口径判 PASS。
- `docs/evidence/补档-HITL-G回落-r26回归.md:49` 同口径：面板 `396/396`、`356/356`，header `350/350`、`310/310`，判 **PASS**。

而 composer 批用的是**页面级**判据（`document.scrollWidth`），所以同一套 360/400 结构在卡片批 PASS、在 composer 批 FAIL。`final/report.md:86` 明确要求「**不能把页面级问题改判 PASS**」；`final-review-report.md:130` 同。

因此，问题5 呈报时最需要用户先裁的是：**问题5 的验收门槛是「页面级 document 无横向滚动」还是「右侧面板内部无横向溢出」**。在页面级门槛下，当前 493/360、493/400 为 FAIL；在面板级门槛下，面板内部已经 PASS，但会出现「页面整体仍横向滚动、header 按钮需横向滚才点到」的既存事实（`scope-diagnosis/report.md:45-49`）。

---

## 呈报要点（事实版）

1. **问题定位**：FAIL 发生在 `/tasks` 页面壳层，不是 composer。composer 368px 完全在 400px aside 内，`descendantBeyondAside=[]`；App.vue/styles 本批 0 diff。
2. **两个独立溢出源**：主责是 header nowrap 内容（右缘 493.13；`el-menu :ellipsis="false"` + 右侧按钮 nowrap），次责是固定 400px、`flex-shrink:0` 的 `el-aside`（右缘 432，并把 section 挤到 32px）。只修 aside 不够：收起后仍是 493。
3. **溢出量与形态**：360 视口横向溢出 133px，400 视口溢出 93px；是 document 级横向滚动 + header 内容破框 + aside 越出视口，不是 composer 内部破框。
4. **demo 缺口**：现有 demo 有 360/400 PASS 先例（`acceptance.json` / `v2/report.json`），但只在单列 `max-w-full` 页面成立，没有模拟项目 header + 固定 400 aside；v7.11 历史面板带 `max-w-full`，项目 `el-aside` 没有。按 demo 先行纪律，问题5 施工前需补页面壳层 demo。
5. **验收门槛未统一**：composer 批要求页面级 `scrollWidth<=clientWidth(+1)` 且 0 元素越界；卡片通道批只要求面板 `scrollWidth==clientWidth` 并明确容忍页面级滚动。这个冲突需要用户裁决，不能由执行方改判。
